package com.chavaillaz.appender.log4j;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;

/**
 * Reports the logging events an appender discards before they reach its delivery, notifying the listener of
 * that delivery on a thread of its own: converting the events and calling the listener never hold up the thread
 * discarding them, the one logging or the worker of the appender. Its own failures go to the
 * {@code ErrorHandler} of the appender.
 * <p>
 * At most the number of discards given at construction wait to be reported. A discard past it is logged
 * instead, since waiting for the reports to catch up would pass the delay on to the application.
 */
final class DiscardReporter {

    /**
     * A logging event discarded before reaching the delivery, waiting in {@link #pendingDiscards} to be reported.
     *
     * @param event  The logging event discarded
     * @param reason A short, human-readable explanation of why it was discarded
     */
    private record Discard(LogEvent event, String reason) {

    }

    /**
     * The appender whose discarded events are reported, kept rather than its {@code ErrorHandler}, which a
     * configuration may replace: each failure of this reporter goes to the one the appender has by then.
     */
    private final AbstractAppender appender;

    /**
     * The delivery of the appender, whose listener is notified, read when reporting: the appender creates it
     * once started, and has none if creating it failed.
     */
    private final Supplier<@Nullable LogDelivery> delivery;

    /**
     * Converts every discarded event to report it, whatever converter the appender uses, one that failed on it
     * included: this one is known to be safe to call from the thread reporting it.
     */
    private final DefaultLogConverter converter;

    /**
     * The events discarded before reaching the delivery, waiting for {@link #executor} to report them.
     */
    private final BlockingQueue<Discard> pendingDiscards;

    /**
     * Reports {@link #pendingDiscards} on a thread of its own, one notification per reason.
     */
    private final ExecutorService executor;

    /**
     * Whether a report of {@link #pendingDiscards} is queued on {@link #executor} and yet to start, so that a
     * burst of discards queues a single report.
     */
    private final AtomicBoolean reportQueued = new AtomicBoolean();

    /**
     * Whether {@link #executor} is running a report, so that a stop giving up on it can tell a listener still
     * being notified from a reporter merely idle, whose thread has yet to end.
     */
    private volatile boolean reportRunning;

    /**
     * Creates a reporter of the events the given appender discards.
     *
     * @param appender      The appender whose discarded events are reported, and whose {@code ErrorHandler}
     *                      this reporter reports its own failures through
     * @param delivery      Reads the delivery of the appender, whose listener is notified, or {@code null}
     *                      while it has none
     * @param converter     The converter turning a discarded event into the document reported
     * @param capacity      The maximum number of discards waiting to be reported
     * @param threadFactory The factory of the thread reporting them
     */
    DiscardReporter(AbstractAppender appender, Supplier<@Nullable LogDelivery> delivery,
                    DefaultLogConverter converter, int capacity, ThreadFactory threadFactory) {
        this.appender = appender;
        this.delivery = delivery;
        this.converter = converter;
        this.pendingDiscards = new LinkedBlockingQueue<>(capacity);
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    /**
     * Hands a discarded logging event to {@link #executor}, so that converting it and calling the listener
     * never hold up the thread discarding it. Once stopped, or if the thread reporting it fails to start, it is
     * reported on the calling thread instead.
     *
     * @param event  The logging event being discarded
     * @param reason A short, human-readable explanation of why it was discarded
     */
    void report(LogEvent event, String reason) {
        if (!pendingDiscards.offer(new Discard(event, reason))) {
            // The report falls behind the discards, which waiting for it would pass on to the application
            appender.error("Unable to report a discarded logging event, " + pendingDiscards.size()
                    + " are already waiting for the delivery listener");
            return;
        }
        if (reportQueued.compareAndSet(false, true)) {
            try {
                executor.execute(this::runReport);
            } catch (Throwable e) {
                // Throwable: a report never queued would leave the flag raised, and every later discard waiting
                reportQueued.set(false);
                reportPending();
            }
        }
    }

    /**
     * Reports the given discarded events in one notification, on the calling thread: a listener saving them
     * usually pays per call, and a stop timing out abandons up to a full queue at once. They are converted by
     * {@link #converter}, and only for a listener other than {@link LogDeliveryListener#NOOP}.
     *
     * @param events The logging events being discarded
     * @param reason A short, human-readable explanation of why they were discarded
     */
    void reportNow(List<LogEvent> events, String reason) {
        LogDelivery handler = delivery.get();
        if (handler == null || events.isEmpty()) {
            return;
        }
        try {
            LogDeliveryListener listener = handler.getListener();
            if (listener == LogDeliveryListener.NOOP) {
                // Nothing would read the documents, not worth converting, stack traces included
                return;
            }
            List<Map<String, Object>> documents = new ArrayList<>(events.size());
            for (LogEvent event : events) {
                documents.add(converter.convert(event));
            }
            listener.onDocumentsDropped(List.copyOf(documents), reason);
        } catch (Throwable e) {
            // Throwable: a listener failing must not end the thread reporting, nor escape a stop
            appender.error("Failed to report a discarded logging event to the delivery listener", e);
        }
    }

    /**
     * Stops reporting once the events still waiting are reported. Past the given time, or once interrupted,
     * those not taken yet are reported on the calling thread instead, rather than left to a thread the JVM
     * exiting would cut short.
     *
     * @param timeout The maximum time to wait, or {@code null} to wait indefinitely
     */
    void stop(@Nullable Duration timeout) {
        executor.shutdown();
        try {
            long timeoutNanos = timeout == null ? Long.MAX_VALUE : timeout.toNanos();
            if (executor.awaitTermination(timeoutNanos, NANOSECONDS)) {
                return;
            }
            if (reportRunning) {
                // Otherwise idle, its thread yet to end, which a spent budget does not wait for
                appender.error("The delivery listener was still being notified of discarded logging events "
                        + "as the appender stopped");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The report in progress finishes on its own, while the listener is notified of the rest here
        reportPending();
    }

    /**
     * Reports the events waiting in {@link #pendingDiscards} from {@link #executor}, flagged as
     * {@link #reportRunning} meanwhile.
     */
    private void runReport() {
        reportRunning = true;
        try {
            reportPending();
        } finally {
            reportRunning = false;
        }
    }

    /**
     * Reports the events waiting in {@link #pendingDiscards}, in one notification per run of events discarded
     * for the same reason.
     */
    private void reportPending() {
        // Lowered first, so that a discard waiting from now on queues a report of its own
        reportQueued.set(false);
        List<Discard> discards = new ArrayList<>();
        pendingDiscards.drainTo(discards);
        List<LogEvent> events = new ArrayList<>(discards.size());
        String reason = null;
        for (Discard discard : discards) {
            if (reason != null && !reason.equals(discard.reason())) {
                reportNow(events, reason);
                events = new ArrayList<>();
            }
            reason = discard.reason();
            events.add(discard.event());
        }
        if (reason != null) {
            reportNow(events, reason);
        }
    }

}
