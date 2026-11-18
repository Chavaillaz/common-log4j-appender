package com.chavaillaz.appender.internal;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Reports dropped elements to a sink on a thread of its own, so that a slow sink, a listener saving them to a
 * file for instance, never holds up the thread dropping them: the one logging, the worker of an appender, or the
 * one accumulating a batch. Drops waiting together for the same reason are reported in one call, a sink saving
 * them usually paying per call.
 * <p>
 * At most the number of drops given at construction wait to be reported: {@link #report(List, String)} refuses
 * the next ones, which its caller reports itself or only counts, rather than letting a slow sink pile up the
 * elements they hold.
 *
 * @param <E> The type of element dropped
 */
public final class DropReporter<E> {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    /**
     * Elements dropped together, waiting to be reported.
     *
     * @param elements The elements dropped
     * @param reason   A short, human-readable explanation of why they were dropped
     * @param <E>      The type of element dropped
     */
    private record Drop<E>(List<E> elements, String reason) {

    }

    /**
     * Receives the elements dropped, with the reason they were, expected to handle its own failures.
     */
    private final BiConsumer<List<E>, String> sink;

    /**
     * The drops waiting for {@link #executor} to report them.
     */
    private final BlockingQueue<Drop<E>> waiting;

    /**
     * Reports {@link #waiting} on a thread of its own.
     */
    private final ExecutorService executor;

    /**
     * Whether a report is queued on {@link #executor} and yet to start, so that a burst of drops queues a
     * single report.
     */
    private final AtomicBoolean reportQueued = new AtomicBoolean();

    /**
     * Whether {@link #executor} is running a report, so that a stop giving up on it can tell a sink still
     * running from a reporter merely idle, whose thread has yet to end.
     */
    private volatile boolean reporting;

    /**
     * Creates a reporter.
     *
     * @param sink          Receives the elements dropped, on the thread of this reporter, or on the one stopping
     *                      it for those still waiting then, expected to handle its own failures
     * @param capacity      The maximum number of drops waiting to be reported
     * @param threadFactory The factory of the thread reporting them
     */
    public DropReporter(BiConsumer<List<E>, String> sink, int capacity, ThreadFactory threadFactory) {
        this.sink = sink;
        this.waiting = new LinkedBlockingQueue<>(capacity);
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    /**
     * Hands the given dropped elements over to be reported on the thread of this reporter. Once stopped, or if
     * that thread fails to start, they are reported on the calling thread instead, along with those waiting.
     *
     * @param elements The elements dropped
     * @param reason   A short, human-readable explanation of why they were dropped
     * @return {@code false} if as many drops as this reporter holds already wait to be reported, the given ones
     *         being left to the caller
     */
    public boolean report(List<E> elements, String reason) {
        if (!waiting.offer(new Drop<>(elements, reason))) {
            return false;
        }
        if (reportQueued.compareAndSet(false, true)) {
            try {
                executor.execute(this::runReport);
            } catch (Throwable e) {
                // Throwable: a report never queued would leave the flag raised, and every later drop waiting
                reportQueued.set(false);
                reportWaiting();
            }
        }
        return true;
    }

    /**
     * Stops reporting once the drops still waiting are reported. Past the given time, or once interrupted, those
     * not taken yet are reported on the calling thread instead, rather than left to a thread the JVM exiting
     * would cut short, while the report in progress, if any, finishes on its own.
     *
     * @param timeoutNanos The maximum time to wait, in nanoseconds, or a negative value to wait indefinitely
     * @return {@code true} if the sink was still reporting once the wait was over
     */
    public boolean stop(long timeoutNanos) {
        executor.shutdown();
        boolean stillReporting = false;
        try {
            if (executor.awaitTermination(timeoutNanos < 0 ? Long.MAX_VALUE : timeoutNanos, NANOSECONDS)) {
                return false;
            }
            // Otherwise idle, its thread yet to end, which a spent budget does not wait for
            stillReporting = reporting;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stillReporting = reporting;
        }
        reportWaiting();
        return stillReporting;
    }

    /**
     * Reports the drops waiting, on the thread of this reporter, flagged as {@link #reporting} meanwhile.
     */
    private void runReport() {
        reporting = true;
        try {
            reportWaiting();
        } finally {
            reporting = false;
        }
    }

    /**
     * Reports the drops waiting, in one call per run of drops waiting together for the same reason.
     */
    private void reportWaiting() {
        // Lowered first, so that a drop waiting from now on queues a report of its own
        reportQueued.set(false);
        List<Drop<E>> drops = new ArrayList<>();
        waiting.drainTo(drops);
        List<E> elements = new ArrayList<>();
        String reason = null;
        for (Drop<E> drop : drops) {
            if (reason != null && !reason.equals(drop.reason())) {
                deliver(elements, reason);
                elements = new ArrayList<>();
            }
            reason = drop.reason();
            elements.addAll(drop.elements());
        }
        if (reason != null) {
            deliver(elements, reason);
        }
    }

    /**
     * Hands elements dropped for the same reason to the sink, logging what it throws rather than letting it end
     * the thread reporting.
     *
     * @param elements The elements dropped
     * @param reason   A short, human-readable explanation of why they were dropped
     */
    private void deliver(List<E> elements, String reason) {
        try {
            sink.accept(Collections.unmodifiableList(elements), reason);
        } catch (Throwable e) {
            // Throwable: a sink failing must not end the thread reporting, nor escape a stop
            log.warn("Unhandled exception thrown while reporting {} dropped element(s)", elements.size(), e);
        }
    }

}
