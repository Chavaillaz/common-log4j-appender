package com.chavaillaz.appender.log4j;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.BoundedTrim;
import com.chavaillaz.appender.DeliveryOptions;
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
 * The queue is bounded to {@link DeliveryOptions#maxQueuedEvents()}, so events arriving faster than
 * they can be processed (e.g. while every {@link DeliveryOptions#maxConcurrentTransmissions()}
 * transmission slot is stuck on an unresponsive downstream system) cannot grow memory usage without limit.
 * Once full, the oldest queued event is discarded to make room for the new one rather than blocking,
 * applying the same {@link BoundedTrim} policy {@link AbstractBatchLogDelivery}'s own pending batch is
 * bounded by, and reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)} so this
 * data loss is never silent. That report is the one thing here that does run on the calling thread, so a
 * listener saving documents somewhere slow costs the application that time on every discarded event.
 * <p>
 * An event at least as severe as {@link DeliveryOptions#flushLevel()} (disabled by default) triggers an
 * immediate flush of the whole current batch right after it, instead of waiting for
 * {@link DeliveryOptions#flushThreshold()} or {@link DeliveryOptions#flushInterval()} as usual, so
 * an occasional severe event is not held hostage behind settings otherwise tuned for throughput.
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

            // Retried before evicting anything: the offer that brought us here failed a moment ago, and the
            // worker may have taken an event since, in which case evicting would discard a deliverable event
            // to make room the queue already has.
            if (executor.getQueue().offer(newTask)) {
                return;
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
     * queue bounded to {@link DeliveryOptions#maxQueuedEvents()} (see {@link #newWorkerExecutor(int)}).
     * Kept typed as {@link ThreadPoolExecutor} (rather than the narrower {@link ExecutorService}) so
     * {@link #isBackpressured()} can inspect its queue directly.
     */
    private final ThreadPoolExecutor workerExecutor;

    /**
     * The scheduler periodically triggering a flush on {@link #workerExecutor}, created only when something
     * can actually leave a partial batch waiting (see {@link #needsPeriodicFlush(DeliveryOptions)}).
     * <p>
     * Volatile for the same reason as {@link #logDeliveryHandler} below: written by {@link #start()} and
     * read by {@link #stop(long, TimeUnit)}, which Log4j2 is free to call from another thread entirely.
     */
    private volatile @Nullable ScheduledExecutorService flushScheduler;

    @Getter
    private final C logConfiguration;

    /**
     * The logs delivery handler, or {@code null} before {@link #start()} is called, or if
     * {@link #createLogDeliveryHandler()} threw an exception while starting it.
     * <p>
     * Volatile because it is written on whatever thread configures this appender and read on every other:
     * by {@link #append(LogEvent)} on application threads, by {@link #triggerFlush()} on the worker and
     * scheduler ones. Log4j2 publishing a started configuration establishes that edge in the usual case,
     * but nothing here requires it, and a thread that never sees the handler takes {@code append()}.s "not
     * started yet" path forever, discarding everything it logs without a word.
     */
    @Getter
    private volatile @Nullable LogDelivery logDeliveryHandler;

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
        this.workerExecutor = newWorkerExecutor(configuration.getDeliveryOptions().maxQueuedEvents());
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
    private ThreadPoolExecutor newWorkerExecutor(int queueCapacity) {
        int capacity = Math.max(1, queueCapacity);
        if (queueCapacity < 1) {
            error("MaxQueuedEvents is " + queueCapacity + " (below 1), clamped up to 1: a queue of zero has "
                    + "nowhere to hand off even a single event");
        }
        return new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
                new LinkedBlockingQueue<>(capacity), namedThreadFactory("log-delivery-worker"), new DiscardOldestEventPolicy());
    }

    /**
     * Starts the log delivery handler (see {@link #createLogDeliveryHandler()}) and, if anything can leave a
     * partial batch waiting (see {@link #needsPeriodicFlush(DeliveryOptions)}), schedules the periodic flush
     * described in the class documentation.
     */
    @Override
    public void start() {
        if (logDeliveryHandler != null) {
            // Nothing in Log4j2's lifecycle promises start() runs exactly once, and going through it again
            // would build a second handler and scheduler while dropping the only references to the first
            // pair: an unstoppable scheduler thread, and a delivery nobody will ever flush, report or close.
            super.start();
            return;
        }

        startLogDeliveryHandler();

        if (logDeliveryHandler != null) {
            startPeriodicFlush();
        }

        super.start();
    }

    /**
     * Schedules the periodic flush described in the class documentation, if anything can leave a partial
     * batch waiting (see {@link #needsPeriodicFlush(DeliveryOptions)}).
     * <p>
     * Reads the delivery options once, and reports a failure to read them at all the same way
     * {@link #validateFlushInterval(Duration)} reports an unusable value: an implementation is free to
     * resolve them dynamically, and so to fail. Letting that escape would also skip the
     * {@code super.start()} below it, leaving an appender that already has a working handler permanently
     * unstarted -- and, since having a handler makes a repeated {@code start()} a no-op, with no retry.
     */
    private void startPeriodicFlush() {
        DeliveryOptions options;
        try {
            options = logConfiguration.getDeliveryOptions();
        } catch (Throwable e) {
            error("Unable to read the delivery options, no periodic flush will be scheduled and partial "
                    + "batches may be delayed indefinitely", e);
            return;
        }

        if (!needsPeriodicFlush(options)) {
            return;
        }
        Long intervalMillis = validateFlushInterval(options.flushInterval());
        if (intervalMillis != null) {
            flushScheduler = newSingleThreadScheduledExecutor(namedThreadFactory("log-delivery-flush-scheduler"));
            flushScheduler.scheduleWithFixedDelay(this::triggerFlush, intervalMillis, intervalMillis, MILLISECONDS);
        }
    }

    /**
     * Whether anything can leave documents sitting in a partial batch with no other trigger to send them,
     * making the periodic flush the only thing that will.
     * <p>
     * Batching is the obvious one: past {@code FlushThreshold} documents accumulate on purpose. The retry
     * backoff is the less obvious one, and it applies even with batching disabled: while it holds off
     * transmissions after a failure, incoming documents are stacked rather than sent, and once it elapses
     * nothing goes out until the <em>next</em> document happens to arrive. Under low or bursty traffic (an
     * application logging an error and then falling quiet, say) that leaves everything accumulated during
     * the backoff waiting in memory until shutdown -- and since backing off is enabled by default, so is
     * that wait.
     *
     * @param options The options to inspect
     * @return {@code true} if a periodic flush should be scheduled
     */
    private static boolean needsPeriodicFlush(DeliveryOptions options) {
        if (options.flushThreshold() > 1) {
            return true;
        }
        // Any positive duration counts, rather than only the ones BackoffPolicy does not round down to "no
        // backoff at all": scheduling a flush that turns out to have nothing to do costs one wake-up per
        // interval on an otherwise idle thread, skipping one costs whatever a real backoff stacked up.
        Duration retryBackoff = options.retryBackoff();
        return retryBackoff != null && !retryBackoff.isZero() && !retryBackoff.isNegative();
    }

    /**
     * Validates the given flush interval, reporting a clear error through this appender's {@code ErrorHandler}
     * instead of letting an exception propagate out of {@link #start()}: a {@link Duration} can fail
     * {@link Duration#toMillis()} either by overflowing it, or (like a non-positive one) by rounding down to
     * {@code 0}, which {@link ScheduledExecutorService#scheduleWithFixedDelay} rejects.
     *
     * @param flushInterval The interval to validate, as returned by {@link DeliveryOptions#flushInterval()}
     * @return The interval in milliseconds, or {@code null} if invalid and no periodic flush should be
     *         scheduled
     */
    private @Nullable Long validateFlushInterval(@Nullable Duration flushInterval) {
        if (flushInterval == null) {
            error("FlushInterval is not set although FlushThreshold is greater than 1 or a retry backoff is "
                    + "configured, no periodic flush will be scheduled and partial batches may be delayed indefinitely");
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
     * Whether the given event's severity is at least as severe as {@link DeliveryOptions#flushLevel()},
     * meaning it should trigger an immediate flush (see {@link #append(LogEvent)}) rather than wait for
     * {@link DeliveryOptions#flushThreshold()} or {@link DeliveryOptions#flushInterval()} as usual.
     * Always {@code false} while that setting is left at its default of {@code null} (disabled), or for an
     * event carrying no level at all.
     *
     * @param event The logging event to check
     * @return {@code true} if this event should trigger an immediate flush
     */
    private boolean isUrgent(LogEvent event) {
        Level flushLevel = logConfiguration.getDeliveryOptions().flushLevel();
        Level eventLevel = event.getLevel();
        return flushLevel != null && eventLevel != null && eventLevel.isMoreSpecificThan(flushLevel);
    }

    /**
     * Hands a flush over to {@link #workerExecutor}, so it still only ever runs on the single background
     * thread, even though triggered by the (otherwise idle) {@link #flushScheduler} thread, or by
     * {@link #append(LogEvent)} itself for an urgent event (see {@link #isUrgent(LogEvent)}); queuing it
     * right after that event's own task guarantees it is already part of the batch by the time this flush
     * runs, since both only ever run on this same single background thread. Routed through
     * {@link #executeLogDeliveryTask(Runnable)} like every other task, so an exception from a misbehaving
     * {@link LogDelivery#flush()} is reported consistently instead of only reaching the worker thread's
     * default uncaught exception handler.
     */
    private void triggerFlush() {
        try {
            workerExecutor.execute(() -> {
                // Re-read on the worker thread rather than captured when the task is queued: a method
                // reference on a null handler would throw a NullPointerException outside
                // executeLogDeliveryTask()'s own guard, escaping to the worker thread's uncaught exception
                // handler instead of being reported through this appender's ErrorHandler.
                LogDelivery handler = logDeliveryHandler;
                if (handler != null) {
                    executeLogDeliveryTask(handler::flush);
                }
            });
        } catch (RejectedExecutionException e) {
            // The queue may be momentarily full (see MaxQueuedEvents) or the worker may be shutting down;
            // either way this cycle's flush is simply skipped, not reported as dropped: nothing
            // irrecoverable is lost, the next periodic flush (or the one from close()) picks up what's pending.
        }
    }

    /**
     * Starts the client for logs transmission, reporting a failure through this appender's
     * {@code ErrorHandler} and leaving the appender in its degraded (see {@link #append(LogEvent)})
     * no-handler state rather than letting it propagate out of {@link #start()}.
     */
    private void startLogDeliveryHandler() {
        try {
            logDeliveryHandler = createLogDeliveryHandler();
            if (logDeliveryHandler == null) {
                // Indistinguishable from a creation that threw, so append() takes its cheap "already
                // reported, nothing to do" path for every event -- except nothing was reported.
                error("Log delivery starting error: createLogDeliveryHandler() returned null, "
                        + "every logging event will be discarded");
            }
        } catch (Throwable e) {
            // Throwable, not Exception: building a handler constructs its transport client, and a client
            // library missing at runtime surfaces as a NoClassDefFoundError. Letting one out of start()
            // hands it to whatever applies the Log4j2 configuration, taking down more than this appender.
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
        // Whether the event reached the queue, and so is going to be delivered whatever happens next here.
        // onDocumentsDropped means "irrecoverably lost, your last chance to save it", so reporting one past
        // that point has a dead-letter sink file a second copy of an event on its way out.
        boolean handedOff = false;
        try {
            // Guarded so a subclass mistake (or unexpected null/exception) in createLogDeliveryTask() —
            // expected to only capture state on this thread and defer real work to the returned Runnable —
            // can never propagate to the application thread.
            Runnable task = createLogDeliveryTask(immutableEvent);
            workerExecutor.execute(new EventTask(immutableEvent, () -> executeLogDeliveryTask(task)));
            handedOff = true;
            if (isUrgent(immutableEvent)) {
                triggerFlush();
            }
        } catch (RejectedExecutionException e) {
            // A full queue is now handled internally by DiscardOldestEventPolicy, so this is reached only
            // because the appender is stopping (or, rarely, a race lost even after that policy already
            // evicted an event to make room).
            error("Log delivery task rejected: the internal queue is full, or the appender is stopping", e);
            reportDropped(immutableEvent, "internal queue capacity exceeded, or the appender is stopping");
        } catch (Throwable e) {
            // Either createLogDeliveryTask() threw, leaving the event nowhere, or the severity check after
            // the hand-off did -- it reads the delivery options, which may be resolved dynamically. Only
            // the first is a discard.
            error("Log delivery task creation error", e);
            if (!handedOff) {
                reportDropped(immutableEvent, "log delivery task creation error");
            }
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
        reportDropped(List.of(event), reason);
    }

    /**
     * Reports several discarded events as one notification rather than one per event: a listener receives
     * the documents in order to save them somewhere, which usually costs something per call. A timed-out
     * {@link #stop(long, TimeUnit)} can abandon a full queue's worth at once, ten thousand by default.
     *
     * @param events The logging events being discarded
     * @param reason A short, human-readable explanation of why they were discarded
     */
    private void reportDropped(List<LogEvent> events, String reason) {
        LogDelivery handler = logDeliveryHandler;
        if (handler == null || events.isEmpty()) {
            return;
        }
        try {
            List<Map<String, Object>> documents = new ArrayList<>(events.size());
            for (LogEvent event : events) {
                documents.add(fallbackConverter.convert(event));
            }
            handler.getListener().onDocumentsDropped(List.copyOf(documents), reason);
        } catch (Throwable e) {
            // Throwable, not Exception: this runs on the application thread whenever a full queue evicts an
            // event, and append() promises never to fail its caller. An Error from a listener would
            // otherwise surface in the middle of unrelated application code.
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
     * Whether {@link #logDeliveryHandler}'s most recently completed transmission attempt succeeded (see
     * {@link LogDelivery#isHealthy()}), exposed here since an application typically holds a reference to
     * this appender rather than the handler itself. {@code true} if the handler failed to start, or hasn't
     * started yet, since there is nothing to be unhealthy about in that case (already reported separately
     * through this appender's {@code ErrorHandler}).
     *
     * @return The current health of the log delivery pipeline
     */
    public boolean isHealthy() {
        LogDelivery handler = logDeliveryHandler;
        return handler == null || handler.isHealthy();
    }

    /**
     * Whether the internal queue feeding {@link #workerExecutor} (bounded to
     * {@link DeliveryOptions#maxQueuedEvents()}) is currently full, meaning the very next logging event
     * would cause the oldest queued one to be discarded (see {@link DiscardOldestEventPolicy}) rather than
     * merely accumulating pending work. A simple backpressure signal an application can poll (e.g. for a
     * readiness/liveness endpoint, or to throttle its own logging) before data loss actually starts,
     * instead of only reacting to {@link LogDeliveryListener#onDocumentsDropped(List, String)} after the
     * fact.
     *
     * @return {@code true} if the queue is currently full
     */
    public boolean isBackpressured() {
        return workerExecutor.getQueue().remainingCapacity() == 0;
    }

    /**
     * How full the queue feeding the background thread currently is, out of the
     * {@link DeliveryOptions#maxQueuedEvents()} it can hold. The graduated counterpart of
     * {@link #isBackpressured()}: exported as a gauge, it shows a queue trending towards full long before it
     * gets there, which is when raising {@code MaxQueuedEvents}, lowering {@code FlushInterval} or looking at
     * the downstream system is still cheap.
     * <p>
     * Almost every entry is a logging event awaiting conversion and batching, but not quite all: a queued
     * flush trigger takes a slot of its own, one per {@code FlushInterval} and one more per event reaching
     * {@link DeliveryOptions#flushLevel()}. This measures queue pressure, not an exact count of events.
     *
     * @return The number of entries currently queued
     */
    public int getQueuedEventCount() {
        return workerExecutor.getQueue().size();
    }

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
                // Every task still sitting in the queue at this point (as opposed to the one actively
                // running, if any) never even started, so it can still be reported instead of vanishing
                // silently: shutdownNow() only removes them from the queue, it does not run them. Collected
                // and reported as one batch, since there can be a full queue's worth of them here.
                List<LogEvent> abandonedEvents = new ArrayList<>();
                for (Runnable abandoned : workerExecutor.shutdownNow()) {
                    if (abandoned instanceof EventTask task) {
                        abandonedEvents.add(task.event());
                    }
                }
                reportDropped(abandonedEvents, "appender stopped before the event could be processed");
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
        } catch (Throwable e) {
            // Throwable, not Exception: closing releases the transport client, and a class it only needs on
            // the way out can fail to load once the JVM is shutting down. This runs from stop()'s finally
            // block, so an escaping failure also skips the super.stop() marking this appender stopped.
            error("Log delivery closing error", e);
        }
    }

}
