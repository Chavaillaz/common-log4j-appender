package com.chavaillaz.appender.internal;

import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

/**
 * Notifies the {@link BatcherListener} of a {@link Batcher}, logging whatever a notification throws rather than
 * letting it escape into the engine, where a throwing {@code onFailed} would skip the handling of the failure
 * following it. Counts the elements reported as dropped along the way, whichever thread reports them.
 * <p>
 * Dropped elements are reported on the calling thread, or by a {@link DropReporter} when the caller must not wait
 * for a slow listener, at most {@link #MAX_WAITING_DROP_NOTIFICATIONS} notifications waiting for it: past that,
 * the notification runs on the calling thread, so that a slow listener cannot pile up notifications, and the
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
     * How many notifications of {@link #droppedInBackground(List, String)} may wait for the listener, the next
     * ones running on the calling thread, so that a slow listener cannot pile them up without bound.
     */
    private static final int MAX_WAITING_DROP_NOTIFICATIONS = 4;

    private final BatcherListener<T> listener;

    /**
     * The singular noun describing one element, used only to phrase log messages.
     */
    private final String elementNoun;

    /**
     * Notifies the listener of the drops the caller must not wait for, on a thread of its own, which only
     * {@link Batcher#close(java.time.Duration)} waits for, through {@link #awaitDropNotifications(long)}.
     */
    private final DropReporter<T> backgroundDrops;

    /**
     * Elements reported as dropped, whatever the reason, for {@link Batcher#metrics()}.
     */
    private final LongAdder droppedElements = new LongAdder();

    /**
     * Creates a notifier of the given listener.
     *
     * @param listener      The listener to notify
     * @param threadFactory The factory of the thread notifying it in the background
     * @param elementNoun   The singular noun describing one element, used only to phrase log messages
     */
    ListenerNotifier(BatcherListener<T> listener, ThreadFactory threadFactory, String elementNoun) {
        this.listener = listener;
        this.elementNoun = elementNoun;
        this.backgroundDrops = new DropReporter<>(this::notifyDropped, MAX_WAITING_DROP_NOTIFICATIONS, threadFactory);
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
        notifyDropped(elements, reason);
    }

    /**
     * Counts dropped elements, and notifies the listener of them on a thread of its own, so that a slow listener
     * does not stall {@link Batcher#add(List)}, its caller. Past {@link #MAX_WAITING_DROP_NOTIFICATIONS}
     * notifications waiting, or once {@link #awaitDropNotifications(long)} was called, the notification runs on
     * the calling thread.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    void droppedInBackground(List<T> elements, String reason) {
        droppedElements.add(elements.size());
        if (!backgroundDrops.report(elements, reason)) {
            // Every place is held by a notification waiting for a listener yet to return: backpressure
            notifyDropped(elements, reason);
        }
    }

    /**
     * Waits, at most the given time, for the drop notifications made in the background (see
     * {@link #droppedInBackground(List, String)}), those still waiting past it, or once interrupted, being made
     * on the calling thread. Later ones are made on the calling thread.
     *
     * @param timeoutNanos The maximum time to wait, in nanoseconds, or a negative value to wait indefinitely
     */
    void awaitDropNotifications(long timeoutNanos) {
        if (backgroundDrops.stop(timeoutNanos)) {
            log.warn("Gave up waiting for the listener to return from a notification of dropped {}s", elementNoun);
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
     * Notifies the listener of dropped elements, already counted, on the calling thread.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    private void notifyDropped(List<T> elements, String reason) {
        notifySafely(() -> listener.onDropped(elements, reason));
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
