package com.chavaillaz.appender.log4j;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.apache.logging.log4j.core.util.Constants;
import org.apache.logging.log4j.message.AsynchronouslyFormattable;
import org.apache.logging.log4j.message.Message;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;

/**
 * Base of an appender transmitting logs from Log4j2.
 * <p>
 * {@link #append(LogEvent)} never blocks the application thread: it snapshots the event and queues it for a
 * single background thread, which converts the events (see {@link LogConverter}) and batches them (see
 * {@link AbstractBatchLogDelivery}), transmissions running on virtual threads of their own. A scheduler thread
 * periodically queues a flush, so that batching only ever happens on the background thread.
 * <p>
 * The queue holds at most {@link DeliveryOptions#maxQueuedEvents()} events, so that events arriving faster than
 * they are processed, while every transmission slot is stuck on an unresponsive downstream system for
 * instance, cannot grow memory without bound. Once full, the oldest event is discarded to make room, and
 * reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}: the one thing here running on
 * the application thread, which a slow listener therefore slows down.
 * <p>
 * An event at least as severe as {@link DeliveryOptions#flushLevel()} flushes the batch right after it, so
 * that it is not held back by settings favoring throughput.
 *
 * @param <C> The configuration type
 */
public abstract class AbstractLogDeliveryAppender<C extends LogConfiguration> extends AbstractAppender {

    /**
     * Creates the threads of an executor, named with the given prefix for thread dumps.
     * <p>
     * Daemon threads, like the background threads of Log4j2: neither ends before
     * {@link #stop(long, TimeUnit)}, which only the shutdown hook of Log4j2 calls, once the JVM starts shutting
     * down, which a live non-daemon thread prevents. Nor do they inherit the inheritable thread-locals of the
     * thread creating them, which they would keep reachable until the appender stops.
     *
     * @param name The thread name prefix
     * @return The thread factory to use
     */
    private static ThreadFactory namedThreadFactory(String name) {
        return Thread.ofPlatform().name(name).daemon(true).inheritInheritableThreadLocals(false).factory();
    }

    /**
     * A task queued on {@link #workerExecutor}, carrying the {@link LogEvent} it was created for, so that
     * {@link DiscardOldestEventPolicy} can report it when evicting it. The flush trigger is not one, not being
     * an event to discard.
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
     * of the queue. Anything else than an {@link EventTask}, such as the flush trigger, which must never evict
     * an event, and any task once the executor is shutting down, are still rejected.
     */
    private final class DiscardOldestEventPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown() || !(task instanceof EventTask newTask)) {
                throw new RejectedExecutionException("Task " + task + " rejected from " + executor);
            }

            // Retried first, the worker having possibly taken an event since the offer that failed
            if (executor.getQueue().offer(newTask)) {
                return;
            }

            // A race against the worker thread dequeuing the same event is harmless: nothing left to evict.
            Runnable evicted = executor.getQueue().poll();
            boolean queued = executor.getQueue().offer(newTask);

            // Reported once the freed slot is claimed, which a slow listener would otherwise leave for another
            // thread to take, discarding two events instead of one
            if (evicted instanceof EventTask evictedTask) {
                error("Log delivery task rejected: the internal queue is full, discarding the oldest pending event");
                reportDropped(evictedTask.event(), "internal queue capacity exceeded, discarding the oldest pending event");
            }
            if (!queued) {
                // Another event took the freed slot first, so this one is discarded instead
                error("Log delivery task rejected: the internal queue is full, or the appender is stopping");
                reportDropped(newTask.event(), "internal queue capacity exceeded, or the appender is stopping");
            }
        }

    }

    /**
     * The single background thread converting and batching events, fed by a queue bounded to
     * {@link DeliveryOptions#maxQueuedEvents()}, typed as a {@link ThreadPoolExecutor} so that its queue can be
     * inspected.
     */
    private final ThreadPoolExecutor workerExecutor;

    /**
     * The scheduler periodically queuing a flush, only created when something can leave a partial batch
     * waiting (see {@link #needsPeriodicFlush(DeliveryOptions)}). Volatile, {@link #stop(long, TimeUnit)} being
     * free to run on another thread than {@link #start()}.
     */
    private volatile @Nullable ScheduledExecutorService flushScheduler;

    @Getter
    private final C logConfiguration;

    /**
     * The logs delivery handler, or {@code null} before {@link #start()}, or if it could not be created.
     * Volatile, being written by the thread starting the appender and read by every thread logging.
     */
    @Getter
    private volatile @Nullable LogDelivery logDeliveryHandler;

    /**
     * Converts an event discarded before reaching the worker, so that it can still be reported, independently
     * of the converter of a subclass, which runs on the worker.
     */
    private final DefaultLogConverter fallbackConverter = new DefaultLogConverter();

    /**
     * The close timeout this appender was built with, used by {@link #stop(long, TimeUnit)} when the current
     * one cannot be read.
     */
    private final @Nullable Duration initialCloseTimeout;

    /**
     * Creates a new appender transmitting logs.
     *
     * @param name          The appender name
     * @param filter        The filter to associate with the appender, or {@code null} for none
     * @param layout        The layout to use to format the event, or {@code null} for none
     * @param configuration The log transmission configuration
     */
    protected AbstractLogDeliveryAppender(String name, @Nullable Filter filter, @Nullable Layout<?> layout, C configuration) {
        super(name, filter, layout, true, Property.EMPTY_ARRAY);
        this.logConfiguration = configuration;
        DeliveryOptions options = configuration.getDeliveryOptions();
        this.initialCloseTimeout = options.closeTimeout();
        this.workerExecutor = newWorkerExecutor(options.maxQueuedEvents());
        this.fallbackConverter.configure(configuration);
    }

    /**
     * Creates the single-thread executor backing {@link #workerExecutor}, its queue bounded to the given
     * capacity, with {@link DiscardOldestEventPolicy} in place of the default {@code AbortPolicy}.
     *
     * @param queueCapacity The maximum number of tasks the queue can hold, raised to {@code 1} at least
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
            // Started again, which nothing in Log4j2 rules out: a second handler and scheduler would leak the
            // first ones
            super.start();
            return;
        }

        startLogDeliveryHandler();

        if (logDeliveryHandler != null) {
            // Created here, by the thread applying the configuration, rather than by the first thread to log,
            // whose context class loader it would keep until the appender stops
            workerExecutor.prestartCoreThread();
            startPeriodicFlush();
        }

        super.start();
    }

    /**
     * Schedules the periodic flush, if anything can leave a partial batch waiting (see
     * {@link #needsPeriodicFlush(DeliveryOptions)}). Delivery options that cannot be read are reported rather
     * than thrown, which would leave the appender unstarted for good, its handler already created.
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
     * Whether anything can leave documents in a partial batch with nothing else to send them: batching, and
     * the retry backoff even without batching, the documents stacked while it holds transmissions off
     * otherwise waiting for the next document to arrive, however long that takes.
     *
     * @param options The options to inspect
     * @return {@code true} if a periodic flush should be scheduled
     */
    private static boolean needsPeriodicFlush(DeliveryOptions options) {
        if (options.flushThreshold() > 1) {
            return true;
        }
        // Any positive duration, a flush with nothing to do costing one wake-up per interval
        Duration retryBackoff = options.retryBackoff();
        return retryBackoff != null && !retryBackoff.isZero() && !retryBackoff.isNegative();
    }

    /**
     * Validates the given flush interval, reporting an unusable one through the {@code ErrorHandler} of this
     * appender rather than failing {@link #start()}: one overflowing {@link Duration#toMillis()}, or rounding
     * down to {@code 0}, which {@link ScheduledExecutorService#scheduleWithFixedDelay} rejects.
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
     * Whether the given event is at least as severe as {@link DeliveryOptions#flushLevel()}, and so flushes
     * the batch right after it. Always {@code false} while that setting is unset, or for an event without level.
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
     * Queues a flush on {@link #workerExecutor}, so that it runs on the background thread like everything else,
     * whether triggered by {@link #flushScheduler} or by {@link #append(LogEvent)} for an urgent event, which
     * is then already in the batch by the time the flush runs.
     */
    private void triggerFlush() {
        try {
            workerExecutor.execute(() -> {
                // Read on the worker, a method reference on a null handler throwing outside the guard below
                LogDelivery handler = logDeliveryHandler;
                if (handler != null) {
                    executeLogDeliveryTask(handler::flush);
                }
            });
        } catch (RejectedExecutionException e) {
            // A full queue, or a worker shutting down: this flush is skipped, the next one sending what is pending
        }
    }

    /**
     * Starts the client for logs transmission, reporting a failure through the {@code ErrorHandler} of this
     * appender rather than throwing it out of {@link #start()}, the appender then discarding every event.
     */
    private void startLogDeliveryHandler() {
        try {
            logDeliveryHandler = createLogDeliveryHandler();
            if (logDeliveryHandler == null) {
                // Every event is then discarded, as after a creation that threw, which at least was reported
                error("Log delivery starting error: createLogDeliveryHandler() returned null, "
                        + "every logging event will be discarded");
            }
        } catch (Throwable e) {
            // Throwable: a client library missing at runtime is an Error, which would fail the whole configuration
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
     * Never blocks nor fails the calling thread: the event is snapshotted and queued for the background
     * thread, and a failure is reported through the {@code ErrorHandler} of this appender, and through the
     * listener of the delivery when it loses the event, rather than thrown.
     *
     * @param loggingEvent The logging event to append
     */
    @Override
    public void append(LogEvent loggingEvent) {
        if (logDeliveryHandler == null) {
            // Starting failed, and reported it: Log4j2 appends to an appender not started all the same
            return;
        }

        // Snapshots the event on the calling thread: log4j2 may reuse (mutate) it once this method returns.
        LogEvent immutableEvent = loggingEvent.toImmutable();
        freezeMessage(immutableEvent);
        // Once queued, the event is on its way, and must not be reported dropped whatever fails next
        boolean handedOff = false;
        try {
            Runnable task = createLogDeliveryTask(immutableEvent);
            workerExecutor.execute(new EventTask(immutableEvent, () -> executeLogDeliveryTask(task)));
            handedOff = true;
            if (isUrgent(immutableEvent)) {
                triggerFlush();
            }
        } catch (RejectedExecutionException e) {
            // A full queue evicts rather than rejects, so this is the appender stopping
            error("Log delivery task rejected: the internal queue is full, or the appender is stopping", e);
            reportDropped(immutableEvent, "internal queue capacity exceeded, or the appender is stopping");
        } catch (Throwable e) {
            // Either createLogDeliveryTask() failed, losing the event, or the severity check once it was queued
            error("Log delivery task creation error", e);
            if (!handedOff) {
                reportDropped(immutableEvent, "log delivery task creation error");
            }
        }
    }

    /**
     * Formats the message of the event on the calling thread, while its arguments still hold the values they
     * were logged with: {@link LogEvent#toImmutable()} only does so for a reusable message, where any other
     * would be formatted on the worker, racing the application modifying its arguments. Honors the opt-outs
     * of the {@code AsyncAppender} of Log4j2, which does the same.
     *
     * @param event The snapshot of the logging event
     */
    private static void freezeMessage(LogEvent event) {
        try {
            Message message = event.getMessage();
            if (message != null && !Constants.FORMAT_MESSAGES_IN_BACKGROUND
                    && !message.getClass().isAnnotationPresent(AsynchronouslyFormattable.class)) {
                message.getFormattedMessage();
            }
        } catch (Throwable e) {
            // Left for the conversion, where it only costs the message field rather than the whole event
        }
    }

    /**
     * Reports a logging event discarded before reaching {@link #workerExecutor}, converted by
     * {@link #fallbackConverter}.
     *
     * @param event  The logging event being discarded
     * @param reason A short, human-readable explanation of why it was discarded
     */
    private void reportDropped(LogEvent event, String reason) {
        reportDropped(List.of(event), reason);
    }

    /**
     * Reports several discarded events in one notification rather than one per event, a listener saving them
     * usually paying per call, and a stop timing out abandoning up to a full queue at once.
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
            LogDeliveryListener listener = handler.getListener();
            if (listener == LogDeliveryListener.NOOP) {
                // Nothing would read the documents, whose conversion costs the thread logging, stack traces included
                return;
            }
            List<Map<String, Object>> documents = new ArrayList<>(events.size());
            for (LogEvent event : events) {
                documents.add(fallbackConverter.convert(event));
            }
            listener.onDocumentsDropped(List.copyOf(documents), reason);
        } catch (Throwable e) {
            // Throwable: this runs on the application thread when a full queue evicts an event
            error("Failed to report a discarded logging event to the delivery listener", e);
        }
    }

    /**
     * Runs the given log delivery task, reporting what it throws through the {@code ErrorHandler} of this
     * appender rather than letting it end the worker thread.
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
     * Creates the task transmitting the given event, called on the application thread: it should only capture
     * what it needs, leaving the conversion and the transmission to the task, run on the background thread.
     *
     * @param loggingEvent The logging event to send, an immutable snapshot
     * @return The task to run
     */
    public abstract Runnable createLogDeliveryTask(LogEvent loggingEvent);

    /**
     * Whether the most recent transmission attempt of the delivery succeeded (see
     * {@link LogDelivery#isHealthy()}), {@code true} before the handler is created, or if it could not be.
     *
     * @return The current health of the log delivery pipeline
     */
    public boolean isHealthy() {
        LogDelivery handler = logDeliveryHandler;
        return handler == null || handler.isHealthy();
    }

    /**
     * Whether the queue feeding the background thread is full, the next event evicting the oldest one: a
     * signal an application can poll before events are lost, from a readiness endpoint for instance.
     *
     * @return {@code true} if the queue is currently full
     */
    public boolean isBackpressured() {
        return workerExecutor.getQueue().remainingCapacity() == 0;
    }

    /**
     * How many entries the queue feeding the background thread holds, out of
     * {@link DeliveryOptions#maxQueuedEvents()}: the graduated counterpart of {@link #isBackpressured()},
     * showing a queue trending towards full. A queued flush takes an entry too, so this measures the pressure
     * on the queue rather than exactly counting events.
     *
     * @return The number of entries currently queued
     */
    public int getQueuedEventCount() {
        return workerExecutor.getQueue().size();
    }

    /**
     * Stops accepting new logging events, waits for already-accepted ones to be processed, then flushes and
     * closes the delivery handler, so logs accepted just before shutdown are not lost. Both waits share one
     * budget, the close getting what the events left of it.
     *
     * @param timeout  The maximum time the whole stop may take, or {@code 0} or less for
     *                 {@link DeliveryOptions#closeTimeout()} instead: Log4j2 passes {@code 0} when its
     *                 {@code shutdownTimeout} is left unset, and on every reconfiguration, meaning each appender
     *                 uses its own default
     * @param timeUnit The unit of {@code timeout}
     * @return Always {@code true}
     */
    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        long startNanos = System.nanoTime();
        long budgetNanos = timeout > 0 ? timeUnit.toNanos(timeout) : defaultStopTimeoutNanos();
        try {
            // No more flushes nor events, and the queued ones processed, so that closing the handler flushes them
            if (flushScheduler != null) {
                flushScheduler.shutdownNow();
            }
            workerExecutor.shutdown();
            boolean terminated = workerExecutor.awaitTermination(budgetNanos, NANOSECONDS);
            if (!terminated) {
                error("Log delivery thread pool did not terminate within " + Duration.ofNanos(budgetNanos)
                        + ", some logs may be lost");
                // Interrupts the task still running, possibly stuck, and reports the events still queued, which
                // never ran, in one notification
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
            stopLogDeliveryHandler(remainingBudget(startNanos, budgetNanos));
            super.stop(timeout, timeUnit);
        }
        return true;
    }

    /**
     * What is left of the budget of {@link #stop(long, TimeUnit)} for closing the delivery handler.
     *
     * @param startNanos  The {@link System#nanoTime()} reading the stop started at
     * @param budgetNanos The budget of the whole stop in nanoseconds, {@link Long#MAX_VALUE} for none
     * @return The time left, never negative, or {@code null} to wait indefinitely
     */
    private static @Nullable Duration remainingBudget(long startNanos, long budgetNanos) {
        if (budgetNanos == Long.MAX_VALUE) {
            return null;
        }
        return Duration.ofNanos(Math.max(0, budgetNanos - (System.nanoTime() - startNanos)));
    }

    /**
     * How long {@link #stop(long, TimeUnit)} may take when Log4j2 leaves it to the appender:
     * {@link DeliveryOptions#closeTimeout()}, or the one this appender was built with if it cannot be read.
     *
     * @return The time to wait in nanoseconds, {@link Long#MAX_VALUE} to wait indefinitely
     */
    private long defaultStopTimeoutNanos() {
        Duration closeTimeout;
        try {
            closeTimeout = logConfiguration.getDeliveryOptions().closeTimeout();
        } catch (Throwable e) {
            error("Unable to read the close timeout, falling back to the one configured at startup", e);
            closeTimeout = initialCloseTimeout;
        }
        // Saturates rather than overflows, and a negative timeout waits no more than a zero one does
        return closeTimeout == null ? Long.MAX_VALUE : Math.max(0, NANOSECONDS.convert(closeTimeout));
    }

    /**
     * Stops the client for logs transmission.
     *
     * @param timeout The maximum time closing it may take, or {@code null} to wait indefinitely
     */
    private void stopLogDeliveryHandler(@Nullable Duration timeout) {
        LogDelivery handler = logDeliveryHandler;
        if (handler == null) {
            return;
        }

        try {
            handler.close(timeout);
        } catch (Throwable e) {
            // Throwable: a class the client only needs to close may fail to load while the JVM shuts down, and
            // super.stop() must still run
            error("Log delivery closing error", e);
        }
    }

}
