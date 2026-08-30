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

import com.chavaillaz.appender.BoundedTrim;
import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;

/**
 * Abstract appender with basic implementation for transmissions of logs from Log4j2.
 * <p>
 * {@link #append(LogEvent)} never blocks or performs costly work on the calling (application) thread: it
 * only snapshots the event and hands it to a single background thread, which converts and batches events
 * (see {@link LogConverter} and {@link AbstractBatchLogDelivery}); network transmission then runs on its own
 * virtual thread, so a slow transmission never delays the next batch either.
 * <p>
 * That background thread is fed by a plain FIFO queue rather than a scheduled executor's (logarithmic-time,
 * delay-ordered) one, since this is the path taken once per log event; a separate, otherwise idle scheduler
 * thread only hands a flush trigger over to the same queue periodically, so batching still only ever
 * happens on the one background thread.
 * <p>
 * The queue is bounded to {@link LogConfiguration#getMaxQueuedEvents()}, so events arriving faster than
 * they can be processed (e.g. while every {@link LogConfiguration#getMaxConcurrentTransmissions()}
 * transmission slot is stuck on an unresponsive downstream system) cannot grow memory usage without limit.
 * Once full, the oldest queued event is discarded to make room for the new one (never blocking the
 * application thread), applying the same {@link BoundedTrim} policy {@link AbstractBatchLogDelivery}'s own
 * pending batch is bounded by, and reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}
 * so this data loss is never silent.
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
     * A task queued on {@link #workerExecutor}, carrying the {@link LogEvent} it was created for so
     * {@link DiscardOldestEventPolicy} can still produce a document for it if evicted before running. The
     * periodic flush trigger from {@link #triggerFlush()} is not wrapped this way, since it is not itself a
     * discardable logging event.
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
     * Replaces {@link #workerExecutor}'s default {@code AbortPolicy} (which would reject, and discard, the
     * newest event once the queue is full) so a full queue instead discards its oldest still-queued event to
     * make room, keeping the most recently received ones — the same {@link BoundedTrim} policy
     * {@link AbstractBatchLogDelivery} applies to a full pending batch, applied here through the queue's own
     * O(1) {@code poll()}/{@code offer()} (see below) rather than a call to {@link BoundedTrim#trim(List, int)}
     * itself, since this queue can only ever be one event over capacity at a time and materializing its full
     * contents on every eviction would be wasteful under sustained overload.
     * <p>
     * Falls back to the default reject-the-newest behavior for anything that is not itself a discardable
     * {@link EventTask} (currently only the periodic flush trigger, which must never evict a real event to
     * make room for itself) or once {@link #workerExecutor} is shutting down, leaving both to their callers
     * as before.
     */
    private final class DiscardOldestEventPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown() || !(task instanceof EventTask newTask)) {
                throw new RejectedExecutionException("Task " + task + " rejected from " + executor);
            }

            // A race against the worker thread dequeuing the same event is harmless: nothing left to evict.
            if (executor.getQueue().poll() instanceof EventTask evictedTask) {
                error("Log delivery task rejected: the internal queue is full, discarding the oldest pending event");
                reportDropped(evictedTask.event(), "internal queue capacity exceeded, discarding the oldest pending event");
            }

            if (!executor.getQueue().offer(newTask)) {
                // Lost a race against another concurrent event refilling the freed slot first: falls back
                // to discarding this new one instead, the same outcome the default policy would have produced.
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

    @Getter
    private final C logConfiguration;

    /**
     * The logs delivery handler, or {@code null} before {@link #start()} is called, or if
     * {@link #createLogDeliveryHandler()} threw an exception while starting it.
     */
    @Getter
    private @Nullable LogDelivery logDeliveryHandler;

    /**
     * Used only as a last resort to produce a document for a logging event discarded before it could even
     * reach {@link #createLogDeliveryTask(LogEvent)} (see {@link #append(LogEvent)}), so it can still be
     * reported instead of vanishing without a trace. Independent of whatever {@link LogConverter} a
     * subclass's own pipeline uses, since that one only runs on the very worker thread being bypassed here.
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
     * Creates the single-thread executor backing {@link #workerExecutor}, its queue bounded to the given
     * capacity (unlike a plain {@code Executors.newSingleThreadExecutor()}'s unbounded one — see the class
     * documentation for why), with {@link DiscardOldestEventPolicy} in place of the default {@code AbortPolicy}.
     *
     * @param queueCapacity The maximum number of tasks the queue can hold, clamped to at least {@code 1}
     *                      since {@link LinkedBlockingQueue}'s constructor rejects a non-positive capacity
     *                      outright
     * @return The executor to use for {@link #workerExecutor}
     */
    private ExecutorService newWorkerExecutor(int queueCapacity) {
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
                new LinkedBlockingQueue<>(capacity), namedThreadFactory("log-delivery-worker"), new DiscardOldestEventPolicy());
    }

    /**
     * Starts the log delivery handler (see {@link #createLogDeliveryHandler()}) and, if batching is enabled
     * (a flush threshold greater than {@code 1}), schedules the periodic flush described in the class
     * documentation.
     */
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
     * instead of letting an exception propagate out of {@link #start()}: a {@link Duration} can fail
     * {@link Duration#toMillis()} either by overflowing it, or (like a non-positive one) by rounding down to
     * {@code 0}, which {@link ScheduledExecutorService#scheduleWithFixedDelay} rejects.
     *
     * @param flushInterval The interval to validate, as returned by {@link LogConfiguration#getFlushInterval()}
     * @return The interval in milliseconds, or {@code null} if invalid and no periodic flush should be
     *         scheduled
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
     * Hands a flush over to {@link #workerExecutor}, so it still only ever runs on the single background
     * thread, even though triggered by the (otherwise idle) {@link #flushScheduler} thread. Routed through
     * {@link #executeLogDeliveryTask(Runnable)} like every other task, so an exception from a misbehaving
     * {@link LogDelivery#flush()} is reported consistently instead of only reaching the worker thread's
     * default uncaught exception handler.
     */
    private void triggerFlush() {
        try {
            workerExecutor.execute(() -> executeLogDeliveryTask(logDeliveryHandler::flush));
        } catch (RejectedExecutionException e) {
            // The queue may be momentarily full (see MaxQueuedEvents) or the worker may be shutting down;
            // either way this cycle's flush is simply skipped, not reported as dropped: nothing
            // irrecoverable is lost, the next periodic flush (or the one from close()) picks up what's pending.
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

    /**
     * {@inheritDoc}
     * <p>
     * Never blocks or fails the calling thread (see the class documentation): the event is snapshotted and
     * handed off to the background thread, and any failure along the way is reported through this
     * appender's {@code ErrorHandler} and, where possible, the delivery handler's
     * {@link LogDeliveryListener} instead of propagating here.
     *
     * @param loggingEvent The logging event to append
     */
    @Override
    public void append(LogEvent loggingEvent) {
        if (logDeliveryHandler == null) {
            // createLogDeliveryHandler() failed while starting (already reported there): log4j still calls
            // append() on a non-started appender by default, so without this check every log call would
            // keep paying for a doomed snapshot/hand-off/execution instead of this cheap no-op.
            return;
        }

        // Snapshots the event on the calling thread: log4j2 may reuse (mutate) it once this method returns.
        LogEvent immutableEvent = loggingEvent.toImmutable();
        try {
            // Guarded so a subclass mistake (or unexpected null/exception) in createLogDeliveryTask() —
            // expected to only capture state on this thread and defer real work to the returned Runnable —
            // can never propagate to the application thread.
            Runnable task = createLogDeliveryTask(immutableEvent);
            workerExecutor.execute(new EventTask(immutableEvent, () -> executeLogDeliveryTask(task)));
        } catch (RejectedExecutionException e) {
            // A full queue is now handled internally by DiscardOldestEventPolicy, so this is reached only
            // because the appender is stopping (or, rarely, a race lost even after that policy already
            // evicted an event to make room).
            error("Log delivery task rejected: the internal queue is full, or the appender is stopping", e);
            reportDropped(immutableEvent, "internal queue capacity exceeded, or the appender is stopping");
        } catch (Throwable e) {
            // createLogDeliveryTask() itself threw: the event never reached workerExecutor, so it is lost
            // and must be reported the same way as a rejected one.
            error("Log delivery task creation error", e);
            reportDropped(immutableEvent, "log delivery task creation error");
        }
    }

    /**
     * Reports a logging event discarded before it could reach {@link #workerExecutor}, using
     * {@link #fallbackConverter} to still produce a document for it instead of it vanishing without a trace.
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
     * Executes the given log delivery task, reporting any exception it throws instead of letting the thread
     * pool silently swallow it (submitted tasks discard their result, and any exception with it).
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

    /**
     * Stops accepting new logging events, waits (up to the given timeout) for already-accepted ones to be
     * processed, then flushes and closes the delivery handler, so logs accepted just before shutdown are
     * not lost.
     *
     * @param timeout  The maximum time to wait for pending events to be processed before giving up (a
     *                 non-positive value returns immediately without waiting or reporting a timeout error)
     * @param timeUnit The unit of {@code timeout}
     * @return Always {@code true}
     */
    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        try {
            // Stops further flushes first, then stops accepting new tasks and waits for those already
            // submitted to complete, so closing the delivery handler flushes everything received so far
            // instead of racing with the background thread.
            if (flushScheduler != null) {
                flushScheduler.shutdownNow();
            }
            workerExecutor.shutdown();
            boolean terminated = workerExecutor.awaitTermination(timeout, timeUnit);
            if (!terminated) {
                if (timeout > 0) {
                    error("Log delivery thread pool did not terminate within the given timeout, some logs may be lost");
                }
                // Best-effort attempt to interrupt whatever task is stuck (typically blocked on I/O in
                // sendBulk()) instead of closing the delivery handler out from under it unconditionally:
                // this narrows the window during which it could still call into a handler being closed
                // concurrently. A task that ignores interruption isn't helped, but one that honors it is
                // stopped instead of finishing on its own after the handler is already gone.
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
