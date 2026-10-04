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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.util.Constants;
import org.apache.logging.log4j.message.AsynchronouslyFormattable;
import org.apache.logging.log4j.message.Message;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.DeliveryHealth;
import com.chavaillaz.appender.DeliveryMetrics;
import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;

/**
 * Base of an appender transmitting logs from Log4j2.
 * <p>
 * {@link #append(LogEvent)} never blocks the application thread: it snapshots the event and queues it for a
 * single background thread, which converts the events (see {@link #getLogConverter()}) and batches them (see
 * {@link AbstractBatchLogDelivery}), transmissions running on virtual threads of their own. A scheduler thread
 * periodically requests a flush, which the background thread runs as soon as it is done with its current
 * event, so that batching only ever happens there. A subclass only creates its delivery (see
 * {@link #createLogDeliveryHandler()}).
 * <p>
 * The queue holds at most {@link DeliveryOptions#maxQueuedEvents()} events, so that events arriving faster than
 * they are processed, while every transmission slot is stuck on an unresponsive downstream system for
 * instance, cannot grow memory without bound. Once full, the oldest event is discarded to make room, and
 * reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)} on a virtual thread, so that a
 * slow listener never holds up the application. At most as many discards as the queue holds wait to be
 * reported: a burst discarding events faster than they are reported leaves the next ones only counted, in
 * {@link #getMetrics()}, and logged.
 * <p>
 * An event at least as severe as {@link DeliveryOptions#flushLevel()} flushes the batch right after it, so
 * that it is not held back by settings favoring throughput.
 *
 * @param <C> The configuration type
 */
public abstract class AbstractLogDeliveryAppender<C extends LogConfiguration> extends AbstractAppender {

    /**
     * Creates the threads of an executor, named after their role and their appender (see
     * {@link #threadName(String, String)}).
     * <p>
     * Daemon threads, like those of Log4j2: they only end with {@link #stop(long, TimeUnit)}, which the shutdown
     * hook of Log4j2 calls once the JVM starts shutting down, and a live non-daemon thread keeps it from doing so.
     * They take neither the inheritable thread-locals nor the context class loader of the thread creating them,
     * which they would otherwise keep reachable until the appender stops.
     *
     * @param role         The role of the threads
     * @param appenderName The name of the appender they belong to
     * @return The thread factory to use
     */
    private static ThreadFactory namedThreadFactory(String role, String appenderName) {
        return Thread.ofPlatform().name(threadName(role, appenderName)).daemon(true)
                .inheritInheritableThreadLocals(false).factory();
    }

    /**
     * Names a thread of an appender after its role and the appender, so that a thread dump tells apart the
     * threads of two appenders, such as two instances of one plugin sending to different places.
     *
     * @param role         The role of the thread, such as {@code worker}
     * @param appenderName The name of the appender it belongs to
     * @return The name of the thread, {@code log-delivery-worker-Elasticsearch} for instance
     */
    private static String threadName(String role, String appenderName) {
        return "log-delivery-" + role + "-" + appenderName;
    }

    /**
     * A task queued on {@link #workerExecutor}, carrying the {@link LogEvent} it was created for, so that
     * {@link AbstractLogDeliveryAppender.DiscardOldestEventPolicy} can report it when evicting it. A requested
     * flush is not one, not being an event to discard.
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
                    error("Log delivery task rejected: the internal queue is full, discarding the oldest pending event");
                    discard(evictedTask.event(), "internal queue capacity exceeded, discarding the oldest pending event");
                } else if (evicted == flushTask) {
                    // The flush it requested is left to the next event, and the next one requested queued again
                    flushTaskQueued.set(false);
                }
                if (!queued) {
                    // Another event took the freed slot first, so this one is discarded instead
                    error("Log delivery task rejected: the internal queue is full, discarding the newest event");
                    discard(newTask.event(), "internal queue capacity exceeded, discarding the newest event");
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

    /**
     * The single background thread converting and batching events, fed by a queue bounded to
     * {@link DeliveryOptions#maxQueuedEvents()}, typed as a {@link ThreadPoolExecutor} so that its queue can be
     * inspected.
     */
    private final ThreadPoolExecutor workerExecutor;

    /**
     * The scheduler periodically queuing a flush, created once the delivery is. Volatile,
     * {@link #stop(long, TimeUnit)} being free to run on another thread than {@link #start()}.
     */
    private volatile @Nullable ScheduledExecutorService flushScheduler;

    private final C logConfiguration;

    /**
     * The delivery, or {@code null} before {@link #start()}, or if it could not be created.
     * Volatile, being written by the thread starting the appender and read by every thread logging.
     */
    private volatile @Nullable LogDelivery logDeliveryHandler;

    /**
     * The converter {@link #getLogConverter()} returns by default, which {@link #discardReporter} also converts
     * every discarded event with, whatever converter a subclass returns, one that failed on it included: this one
     * is known to be safe to call from the thread reporting it.
     */
    private final DefaultLogConverter defaultConverter;

    /**
     * Reports the events discarded on their way to the delivery, so that converting them and calling the
     * listener never holds up the thread logging, nor the worker. Bounded like the queue, a discard past it being
     * counted and logged rather than waited for. Runs on a virtual thread, which inherits neither the
     * inheritable thread-locals nor the context class loader of the thread discarding the first event (see
     * {@link #namedThreadFactory(String, String)}).
     */
    private final DiscardReporter discardReporter;

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
     * Events discarded on their way to the delivery, reported or not, for {@link #getMetrics()}.
     */
    private final LongAdder discardedEvents = new LongAdder();

    /**
     * The close timeout this appender was built with, used by {@link #stop(long, TimeUnit)} when the current
     * one cannot be read.
     */
    private final @Nullable Duration initialCloseTimeout;

    /**
     * The {@link System#nanoTime()} reading when this appender was created, which {@link #getHealth()} counts
     * the time without a success from until a delivery exists.
     */
    private final long createdNanos = System.nanoTime();

    /**
     * Creates a new appender transmitting logs, without a layout: each event is sent as the document its
     * converter makes of it (see {@link #getLogConverter()}).
     *
     * @param name          The appender name
     * @param filter        The filter to associate with the appender, or {@code null} for none
     * @param configuration The configuration of the appender and of its delivery
     */
    protected AbstractLogDeliveryAppender(String name, @Nullable Filter filter, C configuration) {
        super(name, filter, null, true, Property.EMPTY_ARRAY);
        this.logConfiguration = configuration;
        DeliveryOptions options = configuration.getDeliveryOptions();
        this.initialCloseTimeout = options.closeTimeout();
        this.workerExecutor = newWorkerExecutor(options.maxQueuedEvents(), name);
        this.defaultConverter = new DefaultLogConverter(configuration);
        this.discardReporter = new DiscardReporter(this, () -> logDeliveryHandler, defaultConverter,
                options.maxQueuedEvents(), Thread.ofVirtual().name(threadName("discard-reporter", name))
                        .inheritInheritableThreadLocals(false).factory());
    }

    /**
     * Creates the single-thread executor backing {@link #workerExecutor}, its queue bounded to the given
     * capacity, with {@link DiscardOldestEventPolicy} in place of the default {@code AbortPolicy}.
     *
     * @param queueCapacity The maximum number of tasks the queue can hold
     * @param appenderName  The name of this appender, which its thread is named after
     * @return The executor to use for {@link #workerExecutor}
     */
    private ThreadPoolExecutor newWorkerExecutor(int queueCapacity, String appenderName) {
        return new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, new LinkedBlockingQueue<>(queueCapacity),
                namedThreadFactory("worker", appenderName), new DiscardOldestEventPolicy());
    }

    /**
     * Starts the log delivery handler (see {@link #createLogDeliveryHandler()}) and schedules the periodic flush
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
            // whose thread group and priority it would take
            workerExecutor.prestartCoreThread();
            startPeriodicFlush();
        }

        super.start();
    }

    /**
     * Schedules the periodic flush, sending what would otherwise wait for the next event, however long that
     * takes: a batch under the threshold, documents stacked while the retry backoff holds transmissions off, or
     * put back by a failed transmission. Delivery options that cannot be read are reported rather than thrown,
     * which would leave the appender unstarted for good, its handler already created.
     */
    private void startPeriodicFlush() {
        long intervalMillis;
        try {
            // At least a millisecond, as the delivery options require
            intervalMillis = logConfiguration.getDeliveryOptions().flushInterval().toMillis();
        } catch (Throwable e) {
            error("Unable to read the delivery options, no periodic flush will be scheduled and partial "
                    + "batches may be delayed indefinitely", e);
            return;
        }

        flushScheduler = newSingleThreadScheduledExecutor(namedThreadFactory("flush-scheduler", getName()));
        flushScheduler.scheduleWithFixedDelay(this::requestFlush, intervalMillis, intervalMillis, MILLISECONDS);
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
            workerExecutor.execute(flushTask);
        } catch (RejectedExecutionException e) {
            // A full queue, whose next event runs it, or a worker shutting down, whose stop flushes anyway
            flushTaskQueued.set(false);
        } catch (Throwable e) {
            // Throwable: an Error escaping the periodic flush, the heap running out say, cancels all its later
            // runs. Withdrawn, the next run requesting it again rather than waiting for an event that may not come
            flushTaskQueued.set(false);
            flushRequested.set(false);
            error("Unable to queue a flush, the next one will send what is pending", e);
        }
    }

    /**
     * Runs the flush {@link #requestFlush()} requested, unless the background thread ran it already.
     */
    private void flushIfRequested() {
        // Lowered first, so that a flush requested from now on queues a task of its own
        flushTaskQueued.set(false);
        if (flushRequested.compareAndSet(true, false)) {
            executeLogDeliveryTask(this::flushLogDeliveryHandler);
        }
    }

    /**
     * Runs a delivery task on the background thread, then flushes the batch if a periodic flush is waiting
     * (see {@link #flushRequested}), or if the event it delivered is urgent (see {@link #isUrgent(LogEvent)}),
     * each part failing on its own.
     *
     * @param task  The delivery task to run
     * @param event The logging event it delivers
     */
    private void deliver(Runnable task, LogEvent event) {
        executeLogDeliveryTask(task);
        // The waiting flush first, which a severity failing to be checked would otherwise hold up for good, a
        // flush being requested again only once the one waiting ran
        executeLogDeliveryTask(() -> {
            if ((flushRequested.get() && flushRequested.compareAndSet(true, false)) || isUrgent(event)) {
                flushLogDeliveryHandler();
            }
        });
    }

    /**
     * Flushes the delivery handler, read on the background thread since it may not be created yet.
     */
    private void flushLogDeliveryHandler() {
        LogDelivery handler = logDeliveryHandler;
        if (handler != null) {
            handler.flush();
        }
    }

    /**
     * Creates the delivery, reporting a failure through the {@code ErrorHandler} of this appender rather
     * than throwing it out of {@link #start()}, the appender then discarding every event.
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
     * Creates the delivery, once, when the appender starts: the appender closes it when it stops.
     *
     * @return The delivery
     */
    protected abstract LogDelivery createLogDeliveryHandler();

    /**
     * The configuration this appender was created with.
     *
     * @return The configuration
     */
    public C getLogConfiguration() {
        return logConfiguration;
    }

    /**
     * The delivery {@link #createLogDeliveryHandler()} created when this appender started.
     *
     * @return The delivery, or {@code null} before the appender is started, or if it could not be created
     */
    public @Nullable LogDelivery getLogDeliveryHandler() {
        return logDeliveryHandler;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Never fails the calling thread, nor blocks it while the appender runs: the event is snapshotted and queued
     * for the background thread, and a failure is reported through the {@code ErrorHandler} of this appender,
     * and through the listener of the delivery when it loses the event, rather than thrown. An event appended
     * once the appender stopped, which Log4j2 still allows, is reported to that listener on the calling thread,
     * no other thread being left to do it.
     *
     * @param loggingEvent The logging event to append
     */
    @Override
    public void append(LogEvent loggingEvent) {
        if (logDeliveryHandler == null) {
            // Creating the delivery failed, which starting reported: counted, with nothing to report it to
            discardedEvents.increment();
            return;
        }

        LogEvent immutableEvent = snapshot(loggingEvent);
        freezeMessage(immutableEvent);
        try {
            Runnable task = createLogDeliveryTask(immutableEvent);
            // Everything else, the check of its severity included, runs once it is queued, on the worker
            workerExecutor.execute(new EventTask(immutableEvent, () -> deliver(task, immutableEvent)));
        } catch (RejectedExecutionException e) {
            // A full queue evicts rather than rejects, so this is the appender stopping
            error("Log delivery task rejected: the appender is stopping or stopped", e);
            discard(immutableEvent, "appender no longer accepting events");
        } catch (Throwable e) {
            // createLogDeliveryTask() failed, losing the event
            error("Log delivery task creation error", e);
            discard(immutableEvent, "log delivery task creation error");
        }
    }

    /**
     * Snapshots the event on the calling thread, Log4j2 being free to reuse (mutate) it once
     * {@link #append(LogEvent)} returns.
     * <p>
     * Its thread is read first, on the logging thread: a {@code Log4jLogEvent}, created by Log4j2 while its
     * thread-locals are off (by default whenever the Servlet API is on the classpath), records the thread that
     * first reads it, and its {@code toImmutable()} only reads it since Log4j2 2.25.
     * <p>
     * The caller location is left out unless {@link #requiresLocation()}: a synchronous logger includes it by
     * default, and {@code toImmutable()} finds it by walking the stack, twenty times what the rest of
     * {@link #append(LogEvent)} costs. The event is left as it was for the appenders after this one.
     *
     * @param event The logging event
     * @return An immutable snapshot of the event
     */
    private LogEvent snapshot(LogEvent event) {
        event.getThreadId();
        event.getThreadName();
        event.getThreadPriority();
        if (!event.isIncludeLocation() || requiresLocation()) {
            return event.toImmutable();
        }
        event.setIncludeLocation(false);
        try {
            return event.toImmutable();
        } finally {
            event.setIncludeLocation(true);
        }
    }

    /**
     * Whether the converter reads the location of the caller ({@link LogEvent#getSource()}), which Log4j2 then
     * finds when the event is logged, by walking the stack, if the logger includes it (a synchronous one does by
     * default). An event is otherwise snapshotted without it. Defaults to {@code false}.
     *
     * @return {@code true} for a converter reading the location of the caller
     */
    @Override
    public boolean requiresLocation() {
        return false;
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
     * Counts a discarded logging event, and hands it to {@link #discardReporter}, so that converting it and
     * calling the listener never holds up the thread discarding it, the one logging or the worker.
     *
     * @param event  The logging event being discarded
     * @param reason A short, human-readable explanation of why it was discarded
     */
    private void discard(LogEvent event, String reason) {
        discardedEvents.increment();
        discardReporter.report(event, reason);
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
     * Defaults to a task converting the event with {@link #getLogConverter()} and sending the document to the
     * delivery handler, an event failing to convert, or that the delivery throws on, being reported as dropped.
     *
     * @param loggingEvent The logging event to send, an immutable snapshot
     * @return The task to run
     */
    protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
        return () -> {
            LogDelivery handler = logDeliveryHandler;
            if (handler == null) {
                return;
            }
            @Nullable Map<String, Object> document;
            try {
                document = getLogConverter().convert(loggingEvent);
            } catch (Throwable e) {
                // Never sent, so lost for good unless reported, converted the default way as any discard is
                error("Log conversion error, reporting the event as dropped", e);
                discard(loggingEvent, "log conversion error");
                return;
            }
            try {
                handler.send(document);
            } catch (Throwable e) {
                // Not kept by the delivery, which then has nothing to report: lost for good unless reported here
                error("Log delivery error, reporting the event as dropped", e);
                discard(loggingEvent, "log delivery error");
            }
        };
    }

    /**
     * The converter turning an event into the document sent, called on the background thread by the task
     * {@link #createLogDeliveryTask(LogEvent)} creates by default. Defaults to a {@link DefaultLogConverter}
     * built with the configuration of this appender; override it to return another one (see
     * {@link LogConverter#newInstance(String, LogConfiguration)} for one named in a configuration), along with
     * {@link #requiresLocation()} for one reading the location of the caller.
     * <p>
     * The events this appender discards, when its queue is full, a stop gives up on them, this converter fails on
     * them or the delivery throws on them, are reported to the listener converted by a {@link DefaultLogConverter}
     * whatever this returns: that one is safe to call from the thread reporting them, and only gives up the
     * fields it cannot read.
     *
     * @return The converter to use
     */
    protected LogConverter getLogConverter() {
        return defaultConverter;
    }

    /**
     * Takes the health of this appender, the one of its delivery while it runs (see
     * {@link LogDelivery#getHealth()}), and {@link DeliveryHealth.Status#DOWN} otherwise: before it starts,
     * from the moment it starts stopping, and for good if creating its delivery failed, every event then being
     * discarded.
     *
     * @return The current health of the log delivery pipeline, with the time since documents were last
     *         accepted, or since this appender was created while none were yet
     */
    public DeliveryHealth getHealth() {
        LogDelivery handler = logDeliveryHandler;
        if (handler == null) {
            return new DeliveryHealth(DeliveryHealth.Status.DOWN, Duration.ofNanos(System.nanoTime() - createdNanos));
        }
        DeliveryHealth health = handler.getHealth();
        return isStarted() ? health : new DeliveryHealth(DeliveryHealth.Status.DOWN, health.sinceLastSuccess());
    }

    /**
     * Whether the queue feeding the background thread is full, the next event evicting the oldest one: a
     * signal an application can poll before events are lost, from a health indicator or an alert for instance,
     * not from a readiness probe (see {@link DeliveryHealth}).
     *
     * @return {@code true} if the queue is currently full
     */
    public boolean isBackpressured() {
        return workerExecutor.getQueue().remainingCapacity() == 0;
    }

    /**
     * How many entries the queue feeding the background thread holds, out of
     * {@link DeliveryOptions#maxQueuedEvents()}: the graduated counterpart of {@link #isBackpressured()},
     * showing a queue trending towards full. A queued flush takes an entry too, one at most, so this measures
     * the pressure on the queue rather than exactly counting events.
     *
     * @return The number of entries currently queued
     */
    public int getQueuedEventCount() {
        return workerExecutor.getQueue().size();
    }

    /**
     * Takes a snapshot of this appender and of its delivery, for a metrics library to poll (see
     * {@link DeliveryMetrics}): the dropped documents count the events this appender discarded as well, every
     * event if creating its delivery failed.
     *
     * @return The current metrics, those of the delivery being {@code 0} before it is created, or if it could
     *         not be
     */
    public DeliveryMetrics getMetrics() {
        LogDelivery handler = logDeliveryHandler;
        DeliveryMetrics delivery = handler != null ? handler.getMetrics() : DeliveryMetrics.NONE;
        return new DeliveryMetrics(getQueuedEventCount(), delivery.pendingDocuments(), delivery.pendingBytes(),
                delivery.transmissionsInFlight(), delivery.transmittedDocuments(), delivery.failedTransmissions(),
                delivery.droppedDocuments() + discardedEvents.sum());
    }

    /**
     * Stops accepting new logging events, waits for already-accepted ones to be processed and for discarded ones
     * to be reported, then flushes and closes the delivery handler, so logs accepted just before shutdown are
     * not lost. Each step shares one budget, getting what the steps before it left. Once it is spent, or a wait
     * interrupted, nothing is waited for any longer: the task running is interrupted, and the events still
     * queued or still waiting to be reported are reported as dropped on the calling thread.
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
        // From the start, as the appenders of Log4j2 do, rather than started for as long as the stop takes
        setStopping();
        long startNanos = System.nanoTime();
        long budgetNanos = timeout > 0 ? timeUnit.toNanos(timeout) : defaultStopTimeoutNanos();
        try {
            // No more flushes nor events, and the queued ones processed, so that closing the handler flushes them
            if (flushScheduler != null) {
                flushScheduler.shutdownNow();
            }
            workerExecutor.shutdown();
            if (!workerExecutor.awaitTermination(budgetNanos, NANOSECONDS)) {
                error("Log delivery thread pool did not terminate within " + Duration.ofNanos(budgetNanos)
                        + ", some logs may be lost");
                abandonQueuedEvents();
            }
        } catch (InterruptedException e) {
            error("Thread interrupted during termination", e);
            currentThread().interrupt();
            // Otherwise left running past the stop, stuck for good or feeding the queue to a closed delivery
            abandonQueuedEvents();
        } finally {
            discardReporter.stop(remainingBudget(startNanos, budgetNanos));
            stopLogDeliveryHandler(remainingBudget(startNanos, budgetNanos));
            // The filter alone, the state of this appender being changed here
            super.stop(timeout, timeUnit, false);
            setStopped();
        }
        return true;
    }

    /**
     * Stops the background thread without waiting for it any longer, interrupting the task it runs, possibly
     * stuck, and reports the events still queued, which never ran, in one notification.
     */
    private void abandonQueuedEvents() {
        List<LogEvent> abandonedEvents = new ArrayList<>();
        for (Runnable abandoned : workerExecutor.shutdownNow()) {
            if (abandoned instanceof EventTask task) {
                abandonedEvents.add(task.event());
            }
        }
        discardedEvents.add(abandonedEvents.size());
        discardReporter.reportNow(abandonedEvents, "appender stopped before the event could be processed");
    }

    /**
     * What is left of the budget of {@link #stop(long, TimeUnit)} for its next step.
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
     * Closes the delivery.
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
