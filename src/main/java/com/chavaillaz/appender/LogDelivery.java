package com.chavaillaz.appender;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Transmission of logs to an external monitoring system.
 */
public interface LogDelivery extends AutoCloseable {

    /**
     * Sends the given record data, or does nothing for {@code null}, which a
     * {@link com.chavaillaz.appender.log4j.LogConverter} returns to skip an event.
     *
     * @param document The record data, or {@code null} to skip this event
     */
    void send(@Nullable Map<String, Object> document);

    /**
     * Sends the given list of record data, doing nothing for {@code null} and skipping its {@code null}
     * elements, as {@link #send(Map)} does.
     *
     * @param documents The list of record data
     */
    void send(@Nullable List<@Nullable Map<String, Object>> documents);

    /**
     * Sends the pending records (a partially filled batch). Intended to be called periodically to clean out
     * pending messages.
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
     * Whether the most recently completed transmission attempt succeeded, a health signal a caller can poll,
     * from a readiness endpoint for instance. A failed attempt is retried: documents are only lost once
     * reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}. Defaults to {@code true}
     * for an implementation that does not track it.
     *
     * @return {@code true} if the last completed transmission attempt succeeded, or none was attempted yet
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * Takes a snapshot of this delivery, for a metrics library to poll (see {@link DeliveryMetrics}). Defaults to
     * {@link DeliveryMetrics#NONE} for an implementation that does not track them.
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
