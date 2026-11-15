package com.chavaillaz.appender;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Transmits the documents converted from logging events to an external system.
 */
public interface LogDelivery extends AutoCloseable {

    /**
     * Sends the given document, or does nothing for {@code null}, which a
     * {@link com.chavaillaz.appender.log4j.LogConverter} returns to skip an event. Throwing tells that the
     * document was not kept: the appender then reports its event as dropped.
     *
     * @param document The document, or {@code null} to skip this event
     */
    void send(@Nullable Map<String, Object> document);

    /**
     * Sends the given documents, skipping the {@code null} ones as {@link #send(Map)} does, and doing nothing
     * for a {@code null} list.
     *
     * @param documents The documents, or {@code null}
     */
    void send(@Nullable List<@Nullable Map<String, Object>> documents);

    /**
     * Sends the pending documents, a batch under its threshold included, rather than leaving them waiting for the
     * next document: the appender calls it periodically (see {@link DeliveryOptions#flushInterval()}).
     */
    void flush();

    /**
     * Listener notified of the outcome of every transmission attempt, if this implementation supports one.
     *
     * @return The listener, defaulting to {@link LogDeliveryListener#NOOP} for an implementation that does
     *         not support one
     */
    default LogDeliveryListener getListener() {
        return LogDeliveryListener.NOOP;
    }

    /**
     * Takes the health of this delivery, for a health indicator or an alert to poll (see
     * {@link DeliveryHealth}). Defaults to {@link DeliveryHealth.Status#UP} for an implementation that does not
     * track its transmissions, as if its last one had just succeeded, one that does creating its own through
     * {@link DeliveryHealth#of(DeliveryHealth.Status, Duration)}.
     *
     * @return The current health
     */
    default DeliveryHealth getHealth() {
        return DeliveryHealth.of(DeliveryHealth.Status.UP, Duration.ZERO);
    }

    /**
     * Takes a snapshot of this delivery, for a metrics library to poll (see {@link DeliveryMetrics}). Defaults to
     * {@link DeliveryMetrics#NONE} for an implementation that does not track them, one that does creating its
     * own through {@link DeliveryMetrics#builder()}.
     *
     * @return The current metrics, {@link DeliveryMetrics#queuedEvents()} being left to the appender
     */
    default DeliveryMetrics getMetrics() {
        return DeliveryMetrics.NONE;
    }

    /**
     * Closes this delivery, waiting at most the given time for what it still has to send, so that an appender
     * stopping can hand it what is left of its own budget. Defaults to {@link #close()}, for an implementation
     * with nothing to wait for.
     *
     * @param timeout The maximum time to wait, or {@code null} to wait indefinitely
     * @throws Exception If closing fails
     */
    default void close(@Nullable Duration timeout) throws Exception {
        close();
    }

}
