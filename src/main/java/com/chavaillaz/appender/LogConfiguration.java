package com.chavaillaz.appender;

import java.time.Duration;

/**
 * Configuration for logs transmission.
 */
public interface LogConfiguration {

    /**
     * Gets the name of the application generating the logs.
     *
     * @return The application name
     */
    String getApplication();

    /**
     * Sets the name of the application generating the logs.
     *
     * @param application The application name
     */
    void setApplication(String application);

    /**
     * Gets the name of the host on which the application is running.
     *
     * @return The host name
     */
    String getHost();

    /**
     * Sets the name of the host on which the application is running.
     *
     * @param host The host name
     */
    void setHost(String host);

    /**
     * Sets the environment in which the application is running.
     *
     * @return The environment name
     */
    String getEnvironment();

    /**
     * Sets the environment in which the application is running.
     *
     * @param environment The environment name
     */
    void setEnvironment(String environment);

    /**
     * Gets the threshold number of messages triggering the transmission of the logs.
     *
     * @return The threshold number of messages
     */
    long getFlushThreshold();

    /**
     * Sets the threshold number of messages triggering the transmission of the logs.
     *
     * @param messageNumber The threshold number of messages
     */
    void setFlushThreshold(long messageNumber);

    /**
     * Gets the maximum time interval between two flushes.
     * After each interval, the transmission of logs is triggered, even if not reaching the threshold number of messages.
     *
     * @return The maximum interval between two flushes
     */
    Duration getFlushInterval();

    /**
     * Gets the maximum time interval between two flushes.
     * After each interval, the transmission of logs is triggered, even if not reaching the threshold number of messages.
     *
     * @param flushInterval The maximum interval between two flushes
     */
    void setFlushInterval(Duration flushInterval);

    /**
     * Gets the maximum number of batches that can be transmitted concurrently by a batch-based log delivery.
     * Beyond this limit, forming the next batch waits for one of the current transmissions to complete
     * before it can be handed over, so that the downstream system is never overwhelmed with unbounded
     * concurrent requests. Defaults to {@code 4}; override to raise or lower this limit.
     *
     * @return The maximum number of concurrent transmissions
     */
    default int getMaxConcurrentTransmissions() {
        return 4;
    }

    /**
     * Gets the maximum number of documents a pending batch can hold while waiting to be (re)transmitted by
     * a batch-based log delivery. Once exceeded, the oldest documents in excess are discarded so that a
     * struggling downstream system cannot grow memory usage without bound. Defaults to {@code 10'000};
     * override to raise or lower this limit.
     *
     * @return The maximum number of documents a pending batch can hold
     */
    default int getMaxBatchSize() {
        return 10_000;
    }

    /**
     * Gets the maximum number of consecutive failed transmission attempts allowed for the same pending
     * batch before it is discarded instead of being retried again, by a batch-based log delivery. Without
     * this limit, a single document that can never be accepted by the downstream system (a "poison
     * document") would cause the batch containing it to fail forever, blocking every document accumulated
     * behind it from ever being delivered. Defaults to {@code 5}; override to allow more or fewer attempts.
     *
     * @return The maximum number of consecutive failed attempts allowed
     */
    default int getMaxConsecutiveFailures() {
        return 5;
    }

}
