package com.chavaillaz.appender.log4j;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * slow listener never holds up the application: past as many discards waiting for it, one is only logged.
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
     * down, which a live non-daemon thread prevents. Nor do they inherit the inheritable thread-locals and the
     * context class loader of the thread creating them, which they would keep reachable until the appender
     * stops, the system class loader being theirs instead.
     *
     * @param name The thread name prefix
     * @return The thread factory to use
     */
    private static ThreadFactory namedThreadFactory(String name) {
        return Thread.ofPlatform().name(name).daemon(true).inheritInheritableThreadLocals(false).factory();
    }

    /**
     * A task queued on {@link #workerExecutor}, carrying the {@link LogEvent} it was created for, so that
     * {@link DiscardOldestEventPolicy} can report it when evicting it. A requested flush is not one, not being
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
     * of the queue. Anything else than an {@link EventTask}, such as a requested flush, which must never evict
     * an event, and any task once the executor is shutting down, are still rejected.
     */
    private final class DiscardOldestEventPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown() || !(task instanceof EventTask newTask)) {
                throw new RejectedExecutionException("Task " + task + " rejected from " + executor);
            }

            // Retried first, the worker having possibly taken an event since the offer that failed
            if (!executor.getQueue().offer(newTask)) {
                // A race against the worker thread dequeuing the same event is harmless: nothing left to evict.
                Runnable evicted = executor.getQueue().poll();
                boolean queued = executor.getQueue().offer(newTask);

                if (evicted instanceof EventTask evictedTask) {
                    error("Log delivery task rejected: the internal queue is full, discarding the oldest pending event");
                    discard(evictedTask.event(), "internal queue capacity exceeded, discarding the oldest pending event");
                }
                if (!queued) {
                    // Another event took the freed slot first, so this one is discarded instead
                    error("Log delivery task rejected: the internal queue is full, or the appender is stopping");
                    discard(newTask.event(), "internal queue capacity exceeded, or the appender is stopping");
                    return;
                }
            }

            // Checked again once queued, as ThreadPoolExecutor.execute() does: shut down since the check above,
            // the executor may have no worker left to run it, nor anything left to report it
            if (executor.isShutdown() && executor.remove(newTask)) {
                throw new RejectedExecutionException("Task " + task + " rejected from " + executor);
            }
        }

    }

    /**
     * A logging event discarded on the application thread, waiting in {@link #pendingDiscards} to be reported.
     *
     * @param event  The logging event discarded
     * @param reason A short, human-readable explanation of why it was discarded
     */
    private record Discard(LogEvent event, String reason) {

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

    private final C logConfiguration;

    /**
     * The logs delivery handler, or {@code null} before {@link #start()}, or if it could not be created.
     * Volatile, being written by the thread starting the appender and read by every thread logging.
     */
    private volatile @Nullable LogDelivery logDeliveryHandler;

    /**
     * The converter {@link #getLogConverter()} returns by default, which also converts an event discarded
     * before reaching the worker, so that it can still be reported whatever converter a subclass returns: this
     * one is known to be safe to call from the thread reporting it as well.
     */
    private final DefaultLogConverter defaultConverter = new DefaultLogConverter();

    /**
     * The events discarded on the application thread, waiting for {@link #discardReporter} to report them, so
     * that converting them and calling the listener never holds up the thread logging. Bounded like the queue,
     * a discard past it being logged rather than waited for.
     */
    private final BlockingQueue<Discard> pendingDiscards;

    /**
     * Reports {@link #pendingDiscards} on a virtual thread, one notification per reason, which inherits
     * neither the inheritable thread-locals nor the context class loader of the thread discarding the first
     * event (see {@link #namedThreadFactory(String)}).
     */
    private final ExecutorService discardReporter = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("log-delivery-discard-reporter").inheritInheritableThreadLocals(false).factory());

    /**
     * Whether a report of {@link #pendingDiscards} is queued on {@link #discardReporter} and yet to start, so
     * that a burst of discards queues a single report.
     */
    private final AtomicBoolean discardReportQueued = new AtomicBoolean();

    /**
     * Whether a periodic flush is waiting for the background thread, which runs it after the event it is
     * processing, or on its own when idle: a flush needing a slot of its own in a full queue would wait for as
     * long as the application keeps logging faster than the delivery keeps up.
     */
    private final AtomicBoolean flushRequested = new AtomicBoolean();

    /**
     * Events discarded before reaching the delivery, reported or not, for {@link #getMetrics()}.
     */
    private final LongAdder discardedEvents = new LongAdder();

    /**
     * The close timeout this appender was built with, used by {@link #stop(long, TimeUnit)} when the current
     * one cannot be read.
     */
    private final @Nullable Duration initialCloseTimeout;

    /**
     * Creates a new appender transmitting logs, without a layout: each event is sent as the document its
     * converter makes of it (see {@link #getLogConverter()}).
     *
     * @param name          The appender name
     * @param filter        The filter to associate with the appender, or {@code null} for none
     * @param configuration The log transmission configuration
     */
    protected AbstractLogDeliveryAppender(String name, @Nullable Filter filter, C configuration) {
        super(name, filter, null, true, Property.EMPTY_ARRAY);
        this.logConfiguration = configuration;
        DeliveryOptions options = configuration.getDeliveryOptions();
        this.initialCloseTimeout = options.closeTimeout();
        this.workerExecutor = newWorkerExecutor(options.maxQueuedEvents());
        this.pendingDiscards = new LinkedBlockingQueue<>(options.maxQueuedEvents());
        this.defaultConverter.configure(configuration);
    }

    /**
     * Creates the single-thread executor backing {@link #workerExecutor}, its queue bounded to the given
     * capacity, with {@link DiscardOldestEventPolicy} in place of the default {@code AbortPolicy}.
     *
     * @param queueCapacity The maximum number of tasks the queue can hold
     * @return The executor to use for {@link #workerExecutor}
     */
    private ThreadPoolExecutor newWorkerExecutor(int queueCapacity) {
        return new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity), namedThreadFactory("log-delivery-worker"), new DiscardOldestEventPolicy());
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
            // whose thread group and priority it would take
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
        // At least a millisecond, as the delivery options require
        long intervalMillis = options.flushInterval().toMillis();
        flushScheduler = newSingleThreadScheduledExecutor(namedThreadFactory("log-delivery-flush-scheduler"));
        flushScheduler.scheduleWithFixedDelay(this::requestFlush, intervalMillis, intervalMillis, MILLISECONDS);
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
        // A flush with nothing to do costing one wake-up per interval
        return options.flushThreshold() > 1 || !options.retryBackoff().isZero();
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
     * Requests a flush from {@link #flushScheduler}, queued on {@link #workerExecutor} so that it runs on the
     * background thread like everything else. A full queue leaves it to the next event processed (see
     * {@link #flushRequested}).
     */
    private void requestFlush() {
        if (!flushRequested.compareAndSet(false, true)) {
            // Still waiting for the background thread, which runs one flush for both
            return;
        }
        try {
            workerExecutor.execute(this::flushIfRequested);
        } catch (RejectedExecutionException e) {
            // A full queue, whose next event runs it, or a worker shutting down, whose stop flushes anyway
        } catch (Throwable e) {
            // Throwable: an Error escaping the periodic flush, the heap running out say, cancels all its later
            // runs. Withdrawn, the next run requesting it again rather than waiting for an event that may not come
            flushRequested.set(false);
            error("Unable to queue a flush, the next one will send what is pending", e);
        }
    }

    /**
     * Runs the flush {@link #requestFlush()} requested, unless the background thread ran it already.
     */
    private void flushIfRequested() {
        if (flushRequested.compareAndSet(true, false)) {
            executeLogDeliveryTask(this::flushLogDeliveryHandler);
        }
    }

    /**
     * Runs a delivery task on the background thread, then flushes the batch if the event it delivered is
     * urgent (see {@link #isUrgent(LogEvent)}), or if a periodic flush is waiting (see {@link #flushRequested}),
     * each part failing on its own.
     *
     * @param task  The delivery task to run
     * @param event The logging event it delivers
     */
    private void deliver(Runnable task, LogEvent event) {
        executeLogDeliveryTask(task);
        executeLogDeliveryTask(() -> {
            boolean urgent = isUrgent(event);
            if ((flushRequested.get() && flushRequested.compareAndSet(true, false)) || urgent) {
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
     * Never blocks nor fails the calling thread: the event is snapshotted and queued for the background
     * thread, and a failure is reported through the {@code ErrorHandler} of this appender, and through the
     * listener of the delivery when it loses the event, rather than thrown.
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

        // Snapshots the event on the calling thread: log4j2 may reuse (mutate) it once this method returns.
        LogEvent immutableEvent = loggingEvent.toImmutable();
        freezeMessage(immutableEvent);
        try {
            Runnable task = createLogDeliveryTask(immutableEvent);
            // Everything else, the check of its severity included, runs once it is queued, on the worker
            workerExecutor.execute(new EventTask(immutableEvent, () -> deliver(task, immutableEvent)));
        } catch (RejectedExecutionException e) {
            // A full queue evicts rather than rejects, so this is the appender stopping
            error("Log delivery task rejected: the internal queue is full, or the appender is stopping", e);
            discard(immutableEvent, "internal queue capacity exceeded, or the appender is stopping");
        } catch (Throwable e) {
            // createLogDeliveryTask() failed, losing the event
            error("Log delivery task creation error", e);
            discard(immutableEvent, "log delivery task creation error");
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
     * Hands a logging event discarded on the application thread to {@link #discardReporter}, so that
     * converting it and calling the listener never holds up that thread. Reported right away only once the
     * appender stopped, nothing being left to report it later.
     *
     * @param event  The logging event being discarded
     * @param reason A short, human-readable explanation of why it was discarded
     */
    private void discard(LogEvent event, String reason) {
        discardedEvents.increment();
        if (!pendingDiscards.offer(new Discard(event, reason))) {
            // The listener cannot keep up with the discards, which waiting for it would pass on to the application
            error("Unable to report a discarded logging event, " + pendingDiscards.size()
                    + " are already waiting for the delivery listener");
            return;
        }
        if (discardReportQueued.compareAndSet(false, true)) {
            try {
                discardReporter.execute(this::reportPendingDiscards);
            } catch (Throwable e) {
                // Throwable: a report never queued would leave the flag raised, and every later discard waiting
                discardReportQueued.set(false);
                reportPendingDiscards();
            }
        }
    }

    /**
     * Reports the events waiting in {@link #pendingDiscards}, in one notification per run of events discarded
     * for the same reason.
     */
    private void reportPendingDiscards() {
        // Lowered first, so that a discard waiting from now on queues a report of its own
        discardReportQueued.set(false);
        List<Discard> discards = new ArrayList<>();
        pendingDiscards.drainTo(discards);
        List<LogEvent> events = new ArrayList<>(discards.size());
        String reason = null;
        for (Discard discard : discards) {
            if (reason != null && !reason.equals(discard.reason())) {
                reportDropped(events, reason);
                events = new ArrayList<>();
            }
            reason = discard.reason();
            events.add(discard.event());
        }
        if (reason != null) {
            reportDropped(events, reason);
        }
    }

    /**
     * Reports several discarded events in one notification rather than one per event, a listener saving them
     * usually paying per call, and a stop timing out abandoning up to a full queue at once. Converted by
     * {@link #defaultConverter}, and only for a listener reading them.
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
                // Nothing would read the documents, not worth converting, stack traces included
                return;
            }
            List<Map<String, Object>> documents = new ArrayList<>(events.size());
            for (LogEvent event : events) {
                documents.add(defaultConverter.convert(event));
            }
            listener.onDocumentsDropped(List.copyOf(documents), reason);
        } catch (Throwable e) {
            // Throwable: a listener failing must not end the thread reporting, nor escape a stop
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
     * Defaults to a task converting the event with {@link #getLogConverter()} and sending the document to the
     * delivery handler.
     *
     * @param loggingEvent The logging event to send, an immutable snapshot
     * @return The task to run
     */
    protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
        return () -> {
            LogDelivery handler = logDeliveryHandler;
            if (handler != null) {
                handler.send(getLogConverter().convert(loggingEvent));
            }
        };
    }

    /**
     * The converter turning an event into the document sent, called on the background thread by the task
     * {@link #createLogDeliveryTask(LogEvent)} creates by default. Defaults to a {@link DefaultLogConverter}
     * configured with the configuration of this appender; override it to return another, configured already.
     *
     * @return The converter to use
     */
    protected LogConverter getLogConverter() {
        return defaultConverter;
    }

    /**
     * Whether the most recent transmission attempt of the delivery succeeded (see
     * {@link LogDelivery#isHealthy()}): {@code false} once started without a delivery, its creation having
     * failed and every event being discarded, and {@code true} before the appender is started.
     *
     * @return The current health of the log delivery pipeline
     */
    public boolean isHealthy() {
        LogDelivery handler = logDeliveryHandler;
        return handler != null ? handler.isHealthy() : !isStarted();
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
     * Takes a snapshot of this appender and of its delivery, for a metrics library to poll (see
     * {@link DeliveryMetrics}): the dropped documents count the events this appender discarded as well, every
     * one of them once started without a delivery.
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
     * not lost. Each step shares one budget, getting what the steps before it left.
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
                discardedEvents.add(abandonedEvents.size());
                reportDropped(abandonedEvents, "appender stopped before the event could be processed");
            }
        } catch (InterruptedException e) {
            error("Thread interrupted during termination", e);
            currentThread().interrupt();
        } finally {
            stopDiscardReporter(remainingBudget(startNanos, budgetNanos));
            stopLogDeliveryHandler(remainingBudget(startNanos, budgetNanos));
            super.stop(timeout, timeUnit);
        }
        return true;
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
     * Stops the reporter of the events discarded on the application thread once it reported those still
     * waiting, a report running past the given time finishing on its own.
     *
     * @param timeout The maximum time to wait, or {@code null} to wait indefinitely
     */
    private void stopDiscardReporter(@Nullable Duration timeout) {
        discardReporter.shutdown();
        try {
            long timeoutNanos = timeout == null ? Long.MAX_VALUE : timeout.toNanos();
            if (!discardReporter.awaitTermination(timeoutNanos, NANOSECONDS)) {
                error("Discarded logging events were still being reported to the delivery listener as the appender stopped");
            }
        } catch (InterruptedException e) {
            currentThread().interrupt();
        }
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
