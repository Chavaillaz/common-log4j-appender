package com.chavaillaz.appender.log4j;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import lombok.Getter;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;

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
     * The single background thread converting and batching events (see the class documentation).
     */
    private final ExecutorService workerExecutor = newSingleThreadExecutor(namedThreadFactory("log-delivery-worker"));

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
    }

    @Override
    public void start() {
        startLogDeliveryHandler();

        if (logDeliveryHandler != null && logConfiguration.getFlushThreshold() > 1) {
            Duration flushInterval = logConfiguration.getFlushInterval();
            if (flushInterval == null) {
                error("FlushInterval is not set although FlushThreshold is greater than 1, "
                        + "no periodic flush will be scheduled and partial batches may be delayed indefinitely");
            } else {
                long interval = flushInterval.toMillis();
                flushScheduler = newSingleThreadScheduledExecutor(namedThreadFactory("log-delivery-flush-scheduler"));
                flushScheduler.scheduleWithFixedDelay(this::triggerFlush, interval, interval, MILLISECONDS);
            }
        }

        super.start();
    }

    /**
     * Hands a flush over to {@link #workerExecutor}, so that it still only ever runs on the same single
     * background thread as the conversion and batching of events, even though it is triggered by the
     * (otherwise idle) {@link #flushScheduler} thread.
     */
    private void triggerFlush() {
        try {
            workerExecutor.execute(logDeliveryHandler::flush);
        } catch (RejectedExecutionException e) {
            // The worker is shutting down; the scheduler itself is about to be stopped as well.
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
            workerExecutor.execute(() -> executeLogDeliveryTask(task));
        } catch (RejectedExecutionException e) {
            // Happens when a log is emitted while the appender is stopping: the event is dropped
            // instead of being propagated to the calling application thread.
            error("Log delivery task rejected, the appender is stopping", e);
        } catch (Throwable e) {
            error("Log delivery task creation error", e);
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
            if (!terminated && timeout > 0) {
                error("Log delivery thread pool did not terminate within the given timeout, some logs may be lost");
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
