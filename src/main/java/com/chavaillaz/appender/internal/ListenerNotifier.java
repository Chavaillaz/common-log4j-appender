package com.chavaillaz.appender.internal;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

/**
 * Notifies the {@link BatcherListener} of a {@link Batcher}, logging whatever a notification throws rather than
 * letting it escape into the engine, where a throwing {@code onFailed} would skip the handling of the failure
 * following it. Counts the elements reported as dropped along the way, whichever thread reports them.
 * <p>
 * Dropped elements are reported on the calling thread, or on a virtual thread of the engine when the caller must
 * not wait for a slow listener, at most {@link #MAX_CONCURRENT_DROP_NOTIFICATIONS} at once: past that, the
 * notification runs on the calling thread, so that a slow listener cannot pile up notifications, and the
 * elements they hold, under the very overload causing them.
 *
 * @param <T> The type of element transmitted
 */
final class ListenerNotifier<T> {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    /**
     * How many notifications of {@link #droppedInBackground(List, String)} may be in flight at once, the next
     * ones running on the calling thread, so that a slow listener cannot pile them up without bound.
     */
    private static final int MAX_CONCURRENT_DROP_NOTIFICATIONS = 4;

    private final BatcherListener<T> listener;

    /**
     * The executor of the engine, running its transmissions as well as the notifications made in the
     * background, and shut down by its close.
     */
    private final ExecutorService executor;

    /**
     * The singular noun describing one element, used only to phrase log messages.
     */
    private final String elementNoun;

    /**
     * Bounds the drop notifications handed to {@link #executor} at once. Separate from the transmission permits
     * of the engine, so that a notification never takes the slot of a transmission, nor makes
     * {@link Batcher#awaitPendingTransmissions()} wait for a listener: only {@link Batcher#close(java.time.Duration)}
     * waits for them, through {@link #awaitDropNotifications(long)}.
     */
    private final Semaphore dropNotificationPermits = new Semaphore(MAX_CONCURRENT_DROP_NOTIFICATIONS);

    /**
     * Elements reported as dropped, whatever the reason, for {@link Batcher#metrics()}.
     */
    private final LongAdder droppedElements = new LongAdder();

    /**
     * Creates a notifier of the given listener.
     *
     * @param listener    The listener to notify
     * @param executor    The executor of the engine, running the notifications made in the background
     * @param elementNoun The singular noun describing one element, used only to phrase log messages
     */
    ListenerNotifier(BatcherListener<T> listener, ExecutorService executor, String elementNoun) {
        this.listener = listener;
        this.executor = executor;
        this.elementNoun = elementNoun;
    }

    /**
     * Notifies the listener that a request was accepted, in whole or in part, on the calling thread.
     *
     * @param count          The number of elements accepted
     * @param durationMillis The time the transmission took, in milliseconds
     */
    void succeeded(int count, long durationMillis) {
        notifySafely(() -> listener.onSucceeded(count, durationMillis));
    }

    /**
     * Notifies the listener that a request failed, in whole or in part, on the calling thread.
     *
     * @param count The number of elements that failed
     * @param cause The exception responsible for the failure, or {@code null} if none was reported
     */
    void failed(int count, @Nullable Throwable cause) {
        notifySafely(() -> listener.onFailed(count, cause));
    }

    /**
     * Counts dropped elements, and notifies the listener of them on the calling thread.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    void dropped(List<T> elements, String reason) {
        droppedElements.add(elements.size());
        notifySafely(() -> listener.onDropped(elements, reason));
    }

    /**
     * Counts dropped elements, and notifies the listener of them on a virtual thread, so that a slow listener
     * does not stall {@link Batcher#add(List)}, its caller. At most {@link #MAX_CONCURRENT_DROP_NOTIFICATIONS} run
     * at once: past that, or when no thread starts (the executor shut down, for instance), the notification runs
     * on the calling thread. Waited for by {@link #awaitDropNotifications(long)}, which the close calls, not by
     * {@link Batcher#awaitPendingTransmissions()}.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    void droppedInBackground(List<T> elements, String reason) {
        droppedElements.add(elements.size());
        if (!dropNotificationPermits.tryAcquire()) {
            // Every slot is held by a listener yet to return: backpressure rather than one more thread
            notifySafely(() -> listener.onDropped(elements, reason));
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    notifySafely(() -> listener.onDropped(elements, reason));
                } finally {
                    dropNotificationPermits.release();
                }
            });
        } catch (Throwable e) {
            // Throwable, an Error starting the thread keeping the slot for good otherwise (see Batcher.dispatchChunk())
            dropNotificationPermits.release();
            if (e instanceof RejectedExecutionException) {
                log.warn("Notifying the listener of {} dropped {}(s) on the calling thread, the engine is shutting down",
                        elements.size(), elementNoun);
            } else {
                log.warn("Notifying the listener of {} dropped {}(s) on the calling thread, unable to start a thread for it",
                        elements.size(), elementNoun, e);
            }
            notifySafely(() -> listener.onDropped(elements, reason));
        }
    }

    /**
     * Waits, at most the given time, for the drop notifications running on a thread of their own (see
     * {@link #droppedInBackground(List, String)}), an interruption ending the wait.
     *
     * @param timeoutNanos The maximum time to wait, in nanoseconds, or a negative value to wait indefinitely
     */
    void awaitDropNotifications(long timeoutNanos) {
        try {
            if (timeoutNanos < 0) {
                dropNotificationPermits.acquire(MAX_CONCURRENT_DROP_NOTIFICATIONS);
            } else if (!dropNotificationPermits.tryAcquire(MAX_CONCURRENT_DROP_NOTIFICATIONS,
                    timeoutNanos, NANOSECONDS)) {
                log.warn("Gave up waiting for the listener to return from {} notification(s) of dropped {}s",
                        MAX_CONCURRENT_DROP_NOTIFICATIONS - dropNotificationPermits.availablePermits(), elementNoun);
                return;
            }
            dropNotificationPermits.release(MAX_CONCURRENT_DROP_NOTIFICATIONS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the listener to return from notifications of dropped {}s", elementNoun);
        }
    }

    /**
     * The number of elements reported as dropped so far.
     *
     * @return The number of elements reported as dropped since the engine was built
     */
    long droppedElements() {
        return droppedElements.sum();
    }

    /**
     * Invokes the given listener notification, logging whatever it throws.
     *
     * @param notification The listener call to perform
     */
    private void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (Throwable e) {
            // Throwable: a listener serializing a self-referencing element overflows the stack
            log.warn("Unhandled exception thrown by a BatcherListener", e);
        }
    }

}
