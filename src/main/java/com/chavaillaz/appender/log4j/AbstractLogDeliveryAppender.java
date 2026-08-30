package com.chavaillaz.appender.log4j;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;

/**
 * Abstract appender with basic implementation for transmissions of logs from Log4j2.
 * <p>
 * The {@link #append(LogEvent)} method never performs any blocking or costly operation on the calling
 * (application) thread: it only converts the event to an immutable snapshot and hands it off to a single
 * background thread, which is responsible for converting and batching events (see {@link LogConverter} and
 * {@link AbstractBatchLogDelivery}). The actual network transmission of a batch may then run concurrently
 * on a separate virtual thread, so that a slow transmission never delays the accumulation of the next
 * batch either. This guarantees that logging calls made by the application never wait on network I/O.
 * <p>
 * The background thread is fed by a plain FIFO queue (constant-time to enqueue/dequeue) rather than a
 * scheduled executor's queue (logarithmic-time, since it is ordered by delay), as this is the path taken
 * once per log event; a separate, otherwise idle, single-thread scheduler is only used to periodically hand
 * a flush trigger over to that same background thread, so batching still always happens on it alone.
 * <p>
 * That queue is bounded to {@link LogConfiguration#getMaxQueuedEvents()}: since {@link #append(LogEvent)}
 * always accepts new events immediately regardless of how fast the background thread can process them, an
 * unbounded queue would let memory usage grow without limit for as long as events keep arriving faster than
 * they can be converted and batched (for example while every {@link LogConfiguration#getMaxConcurrentTransmissions()}
 * transmission slot is stuck on an unresponsive downstream system). Once that limit is reached, the oldest
 * event still waiting in the queue is discarded to make room for the new one (never blocking the
 * application thread), mirroring the same "keep the most recently received, most likely still relevant
 * documents" policy {@link AbstractBatchLogDelivery} already applies to {@link LogConfiguration#getMaxPendingDocuments()}.
 * Either way, the discarded event is reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}
 * on the delivery handler's listener, so this data loss is never silent.
 *
 * @param <C> The configuration type
 */
public abstract class AbstractLogDeliveryAppender<C extends LogConfiguration> extends AbstractAppender {

    /**
     * Names the threads created by an executor with the given prefix, so that they can be identified in a
     * thread dump instead of showing up as a generic {@code pool-N-thread-1}.
     *
     * @param name The thread name prefix
     * @return The thread factory to use
     */
    private static ThreadFactory namedThreadFactory(String name) {
        return Thread.ofPlatform().name(name).factory();
    }

    /**
     * A task queued on {@link #workerExecutor}, carrying the {@link LogEvent} it was created for alongside
     * the actual work to run, so that {@link DiscardOldestEventPolicy} can still produce a document for it
     * (through {@link #reportDropped(LogEvent, String)}) if it ends up being evicted from the queue before
     * ever running. The periodic flush trigger submitted by {@link #triggerFlush()} is deliberately not
     * wrapped this way, since it is not itself a discardable logging event.
     *
     * @param event    The logging event this task was created for
     * @param delegate The actual work to run
     */
    private record EventTask(LogEvent event, Runnable delegate) implements Runnable {

        @Override
        public void run() {
            delegate.run();
        }

    }

    /**
     * Replaces the default {@code AbortPolicy} of {@link #workerExecutor} (which would simply reject, and by
     * extension discard, the newest event once the queue is full) so that the queue instead keeps the most
     * recently received events, the ones most likely still relevant, discarding the oldest one still waiting
     * to make room instead: see the class documentation for why this mirrors the policy
     * {@link AbstractBatchLogDelivery} already applies to a full pending batch.
     * <p>
     * Falls back to the default reject-the-newest behavior (by throwing, exactly like {@code AbortPolicy}
     * would) for anything that is not itself a discardable {@link EventTask} (currently only the periodic
     * flush trigger from {@link #triggerFlush()}, which must never evict a real logging event from the queue
     * just to make room for itself) or once {@link #workerExecutor} is shutting down, leaving both cases to
     * be handled exactly as before by their respective callers.
     */
    private final class DiscardOldestEventPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown() || !(task instanceof EventTask newTask)) {
                throw new RejectedExecutionException("Task " + task + " rejected from " + executor);
            }

            // Evicts the oldest still-queued event (if any: a race against the worker thread dequeuing it
            // at the very same moment is harmless, it simply means there is nothing left to evict) to free
            // up the room this new event needs.
            if (executor.getQueue().poll() instanceof EventTask evictedTask) {
                error("Log delivery task rejected: the internal queue is full, discarding the oldest pending event");
                reportDropped(evictedTask.event(), "internal queue capacity exceeded, discarding the oldest pending event");
            }

            if (!executor.getQueue().offer(newTask)) {
                // Lost a race against another concurrently submitted event refilling the slot just freed
                // above: falls back to discarding this new event instead, the same outcome the default
                // policy would already have produced for it.
                error("Log delivery task rejected: the internal queue is full, or the appender is stopping");
                reportDropped(newTask.event(), "internal queue capacity exceeded, or the appender is stopping");
            }
        }

    }

    /**
     * The single background thread converting and batching events (see the class documentation), fed by a
     * queue bounded to {@link LogConfiguration#getMaxQueuedEvents()} (see {@link #newWorkerExecutor(int)}).
     */
    private final ExecutorService workerExecutor;

    /**
     * The scheduler periodically triggering a flush on {@link #workerExecutor}, created only when batching
     * is enabled (a flush threshold greater than {@code 1}).
     */
    private @Nullable ScheduledExecutorService flushScheduler;

    /**
     * The configuration of the logs transmission
     */
    @Getter
    private final C logConfiguration;

    /**
     * The logs delivery handler, or {@code null} before {@link #start()} is called, or if
     * {@link #createLogDeliveryHandler()} threw an exception while starting it.
     */
    @Getter
    private @Nullable LogDelivery logDeliveryHandler;

    /**
     * Used only as a last resort to still produce a document for a logging event discarded before it could
     * even reach {@link #createLogDeliveryTask(LogEvent)} (see {@link #append(LogEvent)}), so that it can be
     * reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)} instead of vanishing
     * without a trace. Deliberately independent of whatever {@link LogConverter} (if any) a subclass's own
     * delivery pipeline uses, since that one only ever runs on the very worker thread being bypassed here.
     */
    private final DefaultLogConverter fallbackConverter = new DefaultLogConverter();

    /**
     * Creates a new logs delivery handler.
     *
     * @param name          The appender name
     * @param filter        The filter to associate with the appender
     * @param layout        The layout to use to format the event
     * @param configuration The log transmission configuration
     */
    protected AbstractLogDeliveryAppender(String name, Filter filter, Layout<?> layout, C configuration) {
        super(name, filter, layout, true, Property.EMPTY_ARRAY);
        this.logConfiguration = configuration;
        this.workerExecutor = newWorkerExecutor(configuration.getMaxQueuedEvents());
        this.fallbackConverter.configure(configuration);
    }

    /**
     * Creates the single-thread executor backing {@link #workerExecutor}, with its queue bounded to the
     * given capacity instead of the unbounded one a plain {@code Executors.newSingleThreadExecutor()} would
     * use (see the class documentation for why this queue is bounded at all), and {@link DiscardOldestEventPolicy}
     * in place of the default {@code AbortPolicy} so a full queue discards its oldest pending event instead
     * of rejecting the newest one.
     *
     * @param queueCapacity The maximum number of tasks the queue can hold, clamped to at least {@code 1}
     *                      since {@link LinkedBlockingQueue}'s constructor rejects a non-positive capacity
     *                      outright, and a misconfigured value should degrade to the smallest possible
     *                      buffer instead of crashing the appender at construction time
     * @return The executor to use for {@link #workerExecutor}
     */
    private ExecutorService newWorkerExecutor(int queueCapacity) {
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
                new LinkedBlockingQueue<>(capacity), namedThreadFactory("log-delivery-worker"), new DiscardOldestEventPolicy());
    }

    @Override
    public void start() {
        startLogDeliveryHandler();

        if (logDeliveryHandler != null && logConfiguration.getFlushThreshold() > 1) {
            Long intervalMillis = validateFlushInterval(logConfiguration.getFlushInterval());
            if (intervalMillis != null) {
                flushScheduler = newSingleThreadScheduledExecutor(namedThreadFactory("log-delivery-flush-scheduler"));
                flushScheduler.scheduleWithFixedDelay(this::triggerFlush, intervalMillis, intervalMillis, MILLISECONDS);
            }
        }

        super.start();
    }

    /**
     * Validates the given flush interval, reporting a clear error through this appender's {@code ErrorHandler}
     * (like every other misconfiguration in this class) instead of letting an exception propagate straight
     * out of {@link #start()} for either of the two ways a {@link Duration} can fail
     * {@link Duration#toMillis()} rather than just yield a non-positive result: overflowing it (one so
     * large that {@code toMillis()} itself throws {@link ArithmeticException}) or, like a merely
     * non-positive one, rounding down to {@code 0} once expressed in milliseconds (the unit
     * {@link ScheduledExecutorService#scheduleWithFixedDelay} is called with below, which requires a
     * strictly positive delay).
     *
     * @param flushInterval The interval to validate, as returned by {@link LogConfiguration#getFlushInterval()}
     * @return The interval in milliseconds, or {@code null} if it is invalid and no periodic flush should
     *         be scheduled
     */
    private @Nullable Long validateFlushInterval(@Nullable Duration flushInterval) {
        if (flushInterval == null) {
            error("FlushInterval is not set although FlushThreshold is greater than 1, "
                    + "no periodic flush will be scheduled and partial batches may be delayed indefinitely");
            return null;
        }

        long millis;
        try {
            millis = flushInterval.toMillis();
        } catch (ArithmeticException e) {
            error("FlushInterval is too large to be expressed in milliseconds (" + flushInterval + "), "
                    + "no periodic flush will be scheduled and partial batches may be delayed indefinitely");
            return null;
        }

        if (millis <= 0) {
            error("FlushInterval must be at least 1ms once rounded down but was " + flushInterval + ", "
                    + "no periodic flush will be scheduled and partial batches may be delayed indefinitely");
            return null;
        }

        return millis;
    }

    /**
     * Hands a flush over to {@link #workerExecutor}, so that it still only ever runs on the same single
     * background thread as the conversion and batching of events, even though it is triggered by the
     * (otherwise idle) {@link #flushScheduler} thread. Routed through {@link #executeLogDeliveryTask(Runnable)}
     * like every other task submitted to that executor, so that an exception thrown by a misbehaving
     * {@link LogDelivery#flush()} implementation is reported the same consistent way instead of only ever
     * reaching the worker thread's default uncaught exception handler (typically just a stack trace printed
     * to the console, bypassing this appender's configured {@code ErrorHandler} entirely).
     */
    private void triggerFlush() {
        try {
            workerExecutor.execute(() -> executeLogDeliveryTask(logDeliveryHandler::flush));
        } catch (RejectedExecutionException e) {
            // Either the worker's queue is momentarily full (see MaxQueuedEvents) or the worker is
            // shutting down; in both cases this cycle's flush is simply skipped rather than reported as
            // dropped data, since (unlike a discarded event in append()) nothing irrecoverable is lost:
            // the next periodic flush, or the one triggered by close(), will pick up whatever is pending.
        }
    }

    /**
     * Starts the client for logs transmission.
     */
    private void startLogDeliveryHandler() {
        try {
            logDeliveryHandler = createLogDeliveryHandler();
        } catch (Exception e) {
            error("Log delivery starting error", e);
        }
    }

    /**
     * Creates the client for logs transmission.
     *
     * @return The logs delivery handler
     */
    public abstract LogDelivery createLogDeliveryHandler();

    @Override
    public void append(LogEvent loggingEvent) {
        if (logDeliveryHandler == null) {
            // createLogDeliveryHandler() failed while starting (already reported once there): log4j still
            // calls append() on a non-started appender by default (it merely reports it through the error
            // handler and moves on), so without this check every single log call for the remaining
            // lifetime of the application would keep paying for a snapshot, a background thread hand-off
            // and a task execution doomed to fail, instead of this cheap, immediate no-op.
            return;
        }

        // Captures an immutable snapshot of the event on the calling thread, as log4j2 may reuse
        // (mutate) the given event object once this method returns, which would corrupt the data
        // read later by the background thread if a mutable reference was kept instead.
        LogEvent immutableEvent = loggingEvent.toImmutable();
        try {
            // createLogDeliveryTask() itself runs on the calling (application) thread: a subclass is
            // expected to only capture state there and defer any actual work to the returned Runnable,
            // but this call is still guarded so that a subclass mistake (or an unexpected null/exception)
            // can never propagate to the application thread, which would otherwise break the guarantee
            // that logging calls never fail or block because of this appender.
            Runnable task = createLogDeliveryTask(immutableEvent);
            workerExecutor.execute(new EventTask(immutableEvent, () -> executeLogDeliveryTask(task)));
        } catch (RejectedExecutionException e) {
            // A full queue is now handled internally by DiscardOldestEventPolicy instead of reaching this
            // catch block (see its own documentation), so this is reached only because a log is emitted
            // while the appender is stopping (or, exceedingly rarely, a race lost against another
            // concurrent event even after that policy already evicted the oldest one to make room): either
            // way, the event is discarded instead of being propagated to the calling application thread,
            // but still reported through the delivery's listener so this data loss is not silent.
            error("Log delivery task rejected: the internal queue is full, or the appender is stopping", e);
            reportDropped(immutableEvent, "internal queue capacity exceeded, or the appender is stopping");
        } catch (Throwable e) {
            // createLogDeliveryTask() itself threw (the only statement above that can throw anything but
            // RejectedExecutionException, already handled above): the event was never even submitted to
            // workerExecutor, so it is lost exactly the same way a rejected one is, and must be reported the
            // same way too instead of only ever reaching the ErrorHandler.
            error("Log delivery task creation error", e);
            reportDropped(immutableEvent, "log delivery task creation error");
        }
    }

    /**
     * Reports a logging event discarded before it could even be handed off to {@link #workerExecutor}
     * through the delivery handler's {@link LogDeliveryListener#onDocumentsDropped(List, String)}, using
     * {@link #fallbackConverter} to still produce a document for it (see its own documentation for why a
     * dedicated fallback is used here instead of whatever the delivery pipeline itself relies on).
     *
     * @param event  The logging event being discarded
     * @param reason A short, human-readable explanation of why it was discarded
     */
    private void reportDropped(LogEvent event, String reason) {
        LogDelivery handler = logDeliveryHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.getListener().onDocumentsDropped(List.of(fallbackConverter.convert(event)), reason);
        } catch (Exception e) {
            error("Failed to report a discarded logging event to the delivery listener", e);
        }
    }

    /**
     * Executes the given log delivery task, making sure any exception it throws is reported instead of
     * being silently swallowed by the thread pool (which would otherwise happen since tasks are submitted
     * rather than executed, discarding their result and any exception with it).
     *
     * @param task The log delivery task to execute
     */
    private void executeLogDeliveryTask(Runnable task) {
        try {
            task.run();
        } catch (Throwable e) {
            error("Log delivery task execution error", e);
        }
    }

    /**
     * Creates a runnable in order to transmit the given log.
     *
     * @param loggingEvent The logging event to send, guaranteed to be an immutable snapshot
     * @return The runnable to execute
     */
    public abstract Runnable createLogDeliveryTask(LogEvent loggingEvent);

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        try {
            // Stops any further flush from being triggered first, then stops accepting new tasks and waits
            // for those already submitted (i.e. log events accepted before this call) to complete, so that
            // closing the delivery handler below flushes every log received so far instead of racing with
            // the background thread still processing them.
            if (flushScheduler != null) {
                flushScheduler.shutdownNow();
            }
            workerExecutor.shutdown();
            boolean terminated = workerExecutor.awaitTermination(timeout, timeUnit);
            if (!terminated) {
                if (timeout > 0) {
                    error("Log delivery thread pool did not terminate within the given timeout, some logs may be lost");
                }
                // Best-effort attempt to interrupt whatever task is still stuck (typically blocked on I/O
                // in sendBulk()) instead of closing the delivery handler out from under it unconditionally
                // right below: this narrows, even if it cannot fully close, the window during which that
                // task could still be calling into a handler that is concurrently being closed. A task that
                // does not respond to interruption (many blocking I/O calls don't) is not helped by this,
                // but one that does (e.g. one blocked on further permits or I/O that honors it) is stopped
                // instead of being left to finish on its own after the handler is already gone.
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            error("Thread interrupted during termination", e);
            currentThread().interrupt();
        } finally {
            stopLogDeliveryHandler();
            super.stop(timeout, timeUnit);
        }
        return true;
    }

    /**
     * Stops the client for logs transmission.
     */
    private void stopLogDeliveryHandler() {
        if (logDeliveryHandler == null) {
            return;
        }

        try {
            logDeliveryHandler.close();
        } catch (Exception e) {
            error("Log delivery closing error", e);
        }
    }

}
