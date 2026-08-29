package com.chavaillaz.appender;

import java.util.List;
import java.util.Map;

/**
 * Transmission of logs to an external monitoring system.
 */
public interface LogDelivery extends AutoCloseable {

    /**
     * Sends the given record data. A {@code null} document is treated as a no-op rather than an error, so
     * that a {@link com.chavaillaz.appender.log4j.LogConverter} implementation can signal "skip this event"
     * by returning {@code null} without special-casing it on the caller side.
     *
     * @param document The record data, or {@code null} to skip this event
     */
    void send(Map<String, Object> document);

    /**
     * Sends the given list of record data. Any {@code null} element of the list is skipped for the same
     * reason a {@code null} argument to {@link #send(Map)} is (see above); the list itself may still be
     * {@code null} or empty, in which case this is also a no-op.
     *
     * @param documents The list of record data
     */
    void send(List<Map<String, Object>> documents);

    /**
     * Sends the pending records (generating a partially filled batch).
     * Intended to be called periodically to clean out pending messages.
     */
    void flush();

}
