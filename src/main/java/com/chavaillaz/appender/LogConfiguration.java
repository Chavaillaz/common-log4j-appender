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
     * @param flushThreshold The threshold number of messages
     */
    void setFlushThreshold(long flushThreshold);

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
     * concurrent requests. Defaults to {@code 4}; override to raise or lower this limit, but never to
     * less than {@code 1} (a value of {@code 0} or less would leave every transmission waiting forever,
     * since none could ever be granted permission to run).
     *
     * @return The maximum number of concurrent transmissions, at least {@code 1}
     */
    default int getMaxConcurrentTransmissions() {
        return 4;
    }

    /**
     * Gets the maximum number of documents a pending batch can hold in memory while waiting to be
     * (re)transmitted by a batch-based log delivery. Once exceeded, the oldest documents in excess are
     * discarded so that a struggling downstream system cannot grow memory usage without bound; this applies
     * equally while accumulating new documents and while merging failed ones back in for a retry. Defaults
     * to {@code 10'000}; override to raise or lower this limit, down to {@code 0} (or a negative value,
     * treated the same way) to discard every document as soon as it would be added to the batch, effectively
     * disabling this delivery.
     * <p>
     * This bounds memory only: it says nothing about how many documents are sent together in a single
     * request, which {@link #getMaxRequestSize()} controls instead. A pending batch bigger than
     * {@code MaxRequestSize} is still held here in full, and split into several requests at transmission
     * time.
     *
     * @return The maximum number of documents a pending batch can hold in memory
     */
    default int getMaxPendingDocuments() {
        return 10_000;
    }

    /**
     * Gets the maximum number of documents sent together in a single request by a batch-based log delivery.
     * Once a pending batch of more than this many documents is due for (re)transmission, it is split into
     * consecutive chunks of at most this size, each sent as its own independent request (so that chunks of
     * the same batch may be transmitted concurrently, subject to the same
     * {@link #getMaxConcurrentTransmissions()} bound as any other request, and a chunk that fails is retried
     * on its own without holding up its siblings).
     * <p>
     * Unlike {@link #getMaxPendingDocuments()} (a memory cap on what can be held at once, independent of how
     * it is later sent), this exists purely to respect a downstream system's own limit on how many documents
     * (or how large a payload) it can accept per request; a downstream system without such a limit does not
     * need this set at all. Defaults to {@link Integer#MAX_VALUE} (no chunking: every pending batch is sent
     * as a single request, the behavior of every version of this library before this setting existed);
     * override with a positive value to enable chunking, or leave any non-positive value in place to keep it
     * disabled.
     *
     * @return The maximum number of documents sent together in a single request
     */
    default int getMaxRequestSize() {
        return Integer.MAX_VALUE;
    }

    /**
     * Gets the maximum number of consecutive failed transmission attempts allowed for the same pending
     * batch before it is discarded instead of being retried again, by a batch-based log delivery. Without
     * this limit, a single document that can never be accepted by the downstream system (a "poison
     * document") would cause the batch containing it to fail forever, blocking every document accumulated
     * behind it from ever being delivered. Defaults to {@code 5}; override to allow more or fewer attempts,
     * or to {@code 0} (or a negative value, treated the same way) to disable this limit entirely and retry
     * indefinitely instead of ever giving up on a batch.
     *
     * @return The maximum number of consecutive failed attempts allowed
     */
    default int getMaxConsecutiveFailures() {
        return 5;
    }

    /**
     * Gets the maximum number of logging events that can be queued, waiting to be converted and batched by
     * the background thread of an appender, before new ones are discarded instead of being accepted. This
     * bounds the memory that events accepted faster than they can be processed could otherwise use without
     * limit, for example while every {@link #getMaxConcurrentTransmissions()} transmission slot is stuck on
     * a downstream system that has become unresponsive for a long time: documents already converted and
     * handed off to a batch-based log delivery are separately bounded by {@link #getMaxPendingDocuments()};
     * this limits how many have not even reached that stage yet. Defaults to {@code 10'000}; override to raise
     * or lower this limit, though not below {@code 1}: a value of {@code 0} or less is clamped up to
     * {@code 1} instead of disabling the queue outright, since a queue capacity of zero has nowhere to hand
     * off even a single event.
     *
     * @return The maximum number of logging events that can be queued awaiting processing
     */
    default int getMaxPendingEvents() {
        return 10_000;
    }

}
