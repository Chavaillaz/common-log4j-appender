package com.chavaillaz.appender;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Transmission of logs to an external monitoring system.
 */
public interface LogDelivery extends AutoCloseable {

    /**
     * Sends the given record data, or does nothing if {@code null} (lets a
     * {@link com.chavaillaz.appender.log4j.LogConverter} signal "skip this event" by returning {@code null}
     * without special-casing it on the caller side).
     *
     * @param document The record data, or {@code null} to skip this event
     */
    void send(@Nullable Map<String, Object> document);

    /**
     * Sends the given list of record data; {@code null} or empty is a no-op, and any {@code null} element is
     * skipped, for the same reason as {@link #send(Map)}.
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
     * Whether this delivery's most recently completed transmission attempt succeeded, exposed as a simple
     * health signal a caller can poll directly (e.g. for a readiness/liveness endpoint) instead of only
     * reacting to {@link LogDeliveryListener} callbacks. Not necessarily fatal when {@code false}: unless
     * elements have also been reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)},
     * they are simply being retried.
     * <p>
     * Defaults to {@code true} for an implementation that does not track this.
     *
     * @return {@code true} if the last completed transmission attempt succeeded, or none was attempted yet
     */
    default boolean isHealthy() {
        return true;
    }

}
