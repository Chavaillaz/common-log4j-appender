package com.chavaillaz.appender.log4j;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.core.LogEvent;
import org.jspecify.annotations.Nullable;

/**
 * The worker of an appender: the single background thread converting and batching its events one after the
 * other, fed by a queue holding at most a given number of them, and the scheduler thread periodically requesting a
 * flush, which the background thread runs as soon as it is done with its current event, so that batching only
 * ever happens there.
 * <p>
 * Once full, the queue discards its oldest event to make room for the new one, handing it back to the appender to
 * report. A requested flush never takes the place of an event: it runs after the event in progress, or on its own
 * while the background thread is idle, a single one waiting in the queue at a time.
 */
final class EventWorker {

    /**
     * What the worker hands back to its appender.
     */
    interface Callbacks {

        /**
         * Reports an event the full queue discarded.
         *
         * @param event  The logging event discarded
         * @param reason A short, human-readable explanation of why it was discarded
         */
        void discard(LogEvent event, String reason);

        /**
         * Flushes the delivery of the appender, on the background thread.
         */
        void flush();

        /**
         * Whether the given event flushes the batch right after it.
         *
         * @param event The logging event delivered
         * @return {@code true} if it triggers an immediate flush
         */
        boolean isUrgent(LogEvent event);

        /**
         * Reports a failure of the worker through the {@code ErrorHandler} of the appender.
         *
         * @param message The message to report
         */
        void error(String message);

        /**
         * Reports a failure of the worker through the {@code ErrorHandler} of the appender.
         *
         * @param message The message to report
         * @param cause   The failure
         */
        void error(String message, Throwable cause);

    }

    /**
     * A task queued on {@link #executor}, carrying the {@link LogEvent} it was created for, so that
     * {@link DiscardOldestEventPolicy} can report it when evicting it. A requested flush is not one, not being an
     * event to discard.
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
     * Makes a full queue discard its oldest event to make room for the new one, rather than rejecting the new
     * one as the default {@code AbortPolicy} does, through the constant-time {@code poll()} and {@code offer()}
     * of the queue. Anything other than an {@link EventTask}, such as a requested flush, which must never evict
     * an event, and any task once the executor is shutting down, are still rejected.
     */
    private final class DiscardOldestEventPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            // Rejected without describing the task, whose event would format its message, application code
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("The appender is stopping");
            }
            if (!(task instanceof EventTask newTask)) {
                throw new RejectedExecutionException("The queue is full");
            }

            // Retried first, the worker having possibly taken an event since the offer that failed
            if (!executor.getQueue().offer(newTask)) {
                // A race against the worker thread dequeuing the same event is harmless: nothing left to evict.
                Runnable evicted = executor.getQueue().poll();
                boolean queued = executor.getQueue().offer(newTask);

                if (evicted instanceof EventTask evictedTask) {
                    callbacks.error("Log delivery task rejected: the internal queue is full, discarding the oldest pending event");
                    callbacks.discard(evictedTask.event(), "internal queue capacity exceeded, discarding the oldest pending event");
                } else if (evicted == flushTask) {
                    // The flush it requested is left to the next event, and the next one requested queued again
                    flushTaskQueued.set(false);
                }
                if (!queued) {
                    // Another event took the freed slot first, so this one is discarded instead
                    callbacks.error("Log delivery task rejected: the internal queue is full, discarding the newest event");
                    callbacks.discard(newTask.event(), "internal queue capacity exceeded, discarding the newest event");
                    return;
                }
            }

            // Checked again once queued, as ThreadPoolExecutor.execute() does: shut down since the check above,
            // the executor may have no worker left to run it, nor anything left to report it
            if (executor.isShutdown() && executor.remove(newTask)) {
                throw new RejectedExecutionException("The appender is stopping");
            }
        }

    }

    private final Callbacks callbacks;

    /**
     * The single background thread converting and batching events, fed by a queue bounded at construction,
     * typed as a {@link ThreadPoolExecutor} so that its queue can be inspected.
     */
    private final ThreadPoolExecutor executor;

    /**
     * Creates the thread of {@link #flushScheduler}.
     */
    private final ThreadFactory schedulerThreads;

    /**
     * The time between two periodic flushes, in milliseconds.
     */
    private final long flushIntervalMillis;

    /**
     * The scheduler periodically queuing a flush, created once the worker starts. Volatile, {@link #stop(long)}
     * being free to run on another thread than {@link #start()}.
     */
    private volatile @Nullable ScheduledExecutorService flushScheduler;

    /**
     * Whether a periodic flush is waiting for the background thread, which runs it after the event it is
     * processing, or on its own when idle: a flush needing a slot of its own in a full queue would wait for as
     * long as the application keeps logging faster than the delivery keeps up.
     */
    private final AtomicBoolean flushRequested = new AtomicBoolean();

    /**
     * Whether {@link #flushTask} sits in the queue, so that a single one does at a time: the background thread
     * running the flush after an event leaves it there, and a backlogged queue would otherwise take one more
     * every period, each in the place of an event.
     */
    private final AtomicBoolean flushTaskQueued = new AtomicBoolean();

    /**
     * The task running a requested flush while the background thread is idle, a single instance so that
     * {@link DiscardOldestEventPolicy} can tell it was evicted.
     */
    private final Runnable flushTask = this::flushIfRequested;

    /**
     * Creates a worker, not started.
     *
     * @param capacity            The maximum number of events the queue holds
     * @param flushIntervalMillis The time between two periodic flushes, in milliseconds, at least one
     * @param workerThreads       Creates the background thread
     * @param schedulerThreads    Creates the thread of the scheduler requesting the periodic flushes
     * @param callbacks           What the worker hands back to its appender
     */
    EventWorker(int capacity, long flushIntervalMillis, ThreadFactory workerThreads, ThreadFactory schedulerThreads,
                Callbacks callbacks) {
        this.callbacks = callbacks;
        this.flushIntervalMillis = flushIntervalMillis;
        this.schedulerThreads = schedulerThreads;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, new LinkedBlockingQueue<>(capacity),
                workerThreads, new DiscardOldestEventPolicy());
    }

    /**
     * Starts the background thread, by the thread applying the configuration rather than by the first thread to
     * log, whose thread group and priority it would take, and schedules the periodic flush, sending what would
     * otherwise wait for the next event, however long that takes: a batch under the threshold, documents stacked
     * while the retry backoff holds transmissions off, or put back by a failed transmission.
     */
    void start() {
        executor.prestartCoreThread();
        ScheduledExecutorService scheduler = newSingleThreadScheduledExecutor(schedulerThreads);
        flushScheduler = scheduler;
        scheduler.scheduleWithFixedDelay(this::requestFlush, flushIntervalMillis, flushIntervalMillis, MILLISECONDS);
    }

    /**
     * Queues an event for the background thread, which runs its task, then the flush a period requested, or
     * the one an urgent event triggers (see {@link Callbacks#isUrgent(LogEvent)}). A full queue discards its
     * oldest event to make room for this one.
     *
     * @param event The logging event, an immutable snapshot
     * @param task  The task delivering it
     * @throws RejectedExecutionException If the worker is stopping or stopped
     */
    void submit(LogEvent event, Runnable task) {
        executor.execute(new EventTask(event, () -> deliver(task, event)));
    }

    /**
     * Whether the queue is full, the next event evicting the oldest one.
     *
     * @return {@code true} if the queue is currently full
     */
    boolean isFull() {
        return executor.getQueue().remainingCapacity() == 0;
    }

    /**
     * How many entries the queue holds, a queued flush taking one, one at most.
     *
     * @return The number of entries currently queued
     */
    int size() {
        return executor.getQueue().size();
    }

    /**
     * Requests a flush from {@link #flushScheduler}, run on the background thread like everything else: after
     * its current event (see {@link #flushRequested}), or by {@link #flushTask} once idle, queued unless one
     * already is. A full queue leaves it to the next event processed.
     */
    private void requestFlush() {
        if (flushRequested.getAndSet(true) || !flushTaskQueued.compareAndSet(false, true)) {
            // Still waiting for the background thread, or for the task queued earlier, which runs it
            return;
        }
        try {
            executor.execute(flushTask);
        } catch (RejectedExecutionException e) {
            // A full queue, whose next event runs it, or a worker shutting down, whose stop flushes anyway
            flushTaskQueued.set(false);
        } catch (Throwable e) {
            // Throwable: an Error escaping the periodic flush, the heap running out say, cancels all its later
            // runs. Withdrawn, the next run requesting it again rather than waiting for an event that may not come
            flushTaskQueued.set(false);
            flushRequested.set(false);
            callbacks.error("Unable to queue a flush, the next one will send what is pending", e);
        }
    }

    /**
     * Runs the flush {@link #requestFlush()} requested, unless the background thread ran it already.
     */
    private void flushIfRequested() {
        // Lowered first, so that a flush requested from now on queues a task of its own
        flushTaskQueued.set(false);
        if (flushRequested.compareAndSet(true, false)) {
            executeLogDeliveryTask(callbacks::flush);
        }
    }

    /**
     * Runs a delivery task on the background thread, then flushes the batch if a periodic flush is waiting
     * (see {@link #flushRequested}), or if the event it delivered is urgent (see
     * {@link Callbacks#isUrgent(LogEvent)}), each part failing on its own.
     *
     * @param task  The delivery task to run
     * @param event The logging event it delivers
     */
    private void deliver(Runnable task, LogEvent event) {
        executeLogDeliveryTask(task);
        // The waiting flush first, which a severity failing to be checked would otherwise hold up for good, a
        // flush being requested again only once the one waiting ran
        executeLogDeliveryTask(() -> {
            if ((flushRequested.get() && flushRequested.compareAndSet(true, false)) || callbacks.isUrgent(event)) {
                callbacks.flush();
            }
        });
    }

    /**
     * Runs the given log delivery task, reporting what it throws through the {@code ErrorHandler} of the
     * appender rather than letting it end the background thread.
     *
     * @param task The log delivery task to execute
     */
    private void executeLogDeliveryTask(Runnable task) {
        try {
            task.run();
        } catch (Throwable e) {
            callbacks.error("Log delivery task execution error", e);
        }
    }

    /**
     * Stops accepting events and flushes, and waits, at most the given time, for the background thread to process
     * the events queued. Once it is spent, or the wait interrupted, the background thread is stopped without
     * waiting for it any longer, the task it runs, possibly stuck, being interrupted.
     *
     * @param budgetNanos The maximum time to wait, in nanoseconds, {@link Long#MAX_VALUE} to wait indefinitely
     * @return The events still queued, which never ran, if the background thread was stopped before processing
     *         them, or an empty list
     */
    List<LogEvent> stop(long budgetNanos) {
        try {
            ScheduledExecutorService scheduler = flushScheduler;
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            executor.shutdown();
            if (!executor.awaitTermination(budgetNanos, NANOSECONDS)) {
                callbacks.error("Log delivery thread pool did not terminate within " + Duration.ofNanos(budgetNanos)
                        + ", some logs may be lost");
                return abandon();
            }
            return List.of();
        } catch (InterruptedException e) {
            callbacks.error("Thread interrupted during termination", e);
            currentThread().interrupt();
            // Otherwise left running past the stop, stuck for good or feeding the queue to a closed delivery
            return abandon();
        }
    }

    /**
     * Stops the background thread without waiting for it any longer, interrupting the task it runs.
     *
     * @return The events still queued, which never ran
     */
    private List<LogEvent> abandon() {
        List<LogEvent> abandonedEvents = new ArrayList<>();
        for (Runnable abandoned : executor.shutdownNow()) {
            if (abandoned instanceof EventTask task) {
                abandonedEvents.add(task.event());
            }
        }
        return abandonedEvents;
    }

}
