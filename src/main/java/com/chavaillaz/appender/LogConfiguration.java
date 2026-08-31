package com.chavaillaz.appender;

import java.time.Duration;

import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for logs transmission.
 * <p>
 * A read-only contract deliberately without setters: an implementation is expected to be immutable (e.g. a
 * {@code record}, or a plain class built once via a builder) rather than mutated after being handed to an
 * {@link com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender}/{@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery}
 * pair, which read it repeatedly (not just once at construction) from a background thread with no
 * synchronization of their own around these reads. Log4j2's own {@code @PluginBuilderFactory} builder
 * pattern already produces exactly this shape: mutable fields on the builder, an immutable object out of
 * its {@code build()}.
 */
public interface LogConfiguration {

    /**
     * Gets the name of the application generating the logs.
     *
     * @return The application name
     */
    String getApplication();

    /**
     * Gets the name of the host the application is running on.
     *
     * @return The host name
     */
    String getHost();

    /**
     * Gets the name of the environment the application is running in.
     *
     * @return The environment name
     */
    String getEnvironment();

    /**
     * Gets the number of messages accumulated before triggering a transmission. A value of {@code 1} or
     * less disables batching entirely: every message is sent individually, as soon as it arrives.
     *
     * @return The flush threshold
     */
    long getFlushThreshold();

    /**
     * Gets the maximum time between two transmissions: past this interval, a transmission is triggered even
     * if {@link #getFlushThreshold()} was not reached yet.
     *
     * @return The flush interval
     */
    Duration getFlushInterval();

    /**
     * Minimum severity that triggers an immediate flush right after the corresponding event was handed to
     * the current batch, instead of waiting for {@link #getFlushThreshold()} or {@link #getFlushInterval()}
     * as usual; every other, less severe event still only counts toward those two as before. Useful so an
     * occasional severe event (e.g. {@code ERROR}) is shipped promptly even while a high
     * {@code FlushThreshold} or {@code FlushInterval} otherwise favors throughput over latency for routine
     * traffic. Compared using {@link Level#isMoreSpecificThan(Level)}, so e.g. a value of {@code WARN} also
     * triggers on {@code ERROR} and {@code FATAL}. Defaults to {@code null}, disabling this behavior
     * entirely (every event only triggers a flush the usual way).
     *
     * @return The minimum severity that triggers an immediate flush, or {@code null} to disable it
     */
    default @Nullable Level getFlushLevel() {
        return null;
    }

    /**
     * Maximum number of batches a batch-based log delivery transmits concurrently; forming the next batch
     * waits for a slot once this limit is reached, so the downstream system is never overwhelmed with
     * unbounded concurrent requests. Defaults to {@code 4}; must be at least {@code 1}, or every
     * transmission would wait forever for a slot that never opens.
     *
     * @return The maximum number of concurrent transmissions, at least {@code 1}
     */
    default int getMaxConcurrentTransmissions() {
        return 4;
    }

    /**
     * Maximum number of documents a pending batch can hold in memory while waiting to be (re)transmitted by
     * a batch-based log delivery, across both accumulation and retries; past it, the oldest documents are
     * discarded so a struggling downstream system cannot grow memory usage without bound. Independent of
     * {@link #getMaxRequestDocuments()}, which governs how many documents go out per request rather than how
     * many can be held pending. Defaults to {@code 10'000}; {@code 0} or less discards every document as
     * soon as it would be added, effectively disabling this delivery.
     *
     * @return The maximum number of documents a pending batch can hold in memory
     */
    default int getMaxPendingDocuments() {
        return 10_000;
    }

    /**
     * Maximum number of documents sent together in a single request by a batch-based log delivery; a
     * pending batch bigger than this is split into chunks of at most this size, each transmitted (and
     * retried on failure) independently. Exists only to respect a downstream system's own limit on
     * documents (or payload size) per request; leave unset if it has none. Defaults to
     * {@link Integer#MAX_VALUE} (no chunking, the behavior of every version of this library before this
     * setting existed); a non-positive value also disables chunking.
     *
     * @return The maximum number of documents sent together in a single request
     */
    default int getMaxRequestDocuments() {
        return Integer.MAX_VALUE;
    }

    /**
     * Maximum number of consecutive failed transmission attempts allowed for the same pending batch, by a
     * batch-based log delivery, before it is discarded instead of retried again. Without this limit, a
     * single document the downstream system can never accept (a "poison document") would fail its batch
     * forever, blocking everything accumulated behind it. Defaults to {@code 5}; {@code 0} or less disables
     * this limit and retries indefinitely instead.
     *
     * @return The maximum number of consecutive failed attempts allowed
     */
    default int getMaxConsecutiveFailures() {
        return 5;
    }

    /**
     * Maximum number of logging events an appender's background thread can queue awaiting conversion and
     * batching, bounding the memory events arriving faster than they can be processed could otherwise use
     * without limit; unrelated to {@link #getMaxPendingDocuments()}, which bounds documents that already
     * reached that stage. Once full, the oldest queued event is discarded to make room for the new one,
     * mirroring the policy {@link #getMaxPendingDocuments()} applies to a full pending batch. Defaults to
     * {@code 10'000}; values below {@code 1} are clamped up to {@code 1}, since a queue of zero has nowhere
     * to hand off even a single event.
     *
     * @return The maximum number of logging events that can be queued awaiting processing
     */
    default int getMaxQueuedEvents() {
        return 10_000;
    }

    /**
     * Base delay before retrying after a failed transmission, doubling with every consecutive failure up to
     * {@link #getMaxRetryBackoff()} and picked at random within that (see
     * {@link com.chavaillaz.appender.BackoffPolicy#exponential(Duration, Duration)}), so a downstream system
     * that is down, overloaded or rate-limiting is not hit again at the rate logs happen to arrive. Applies
     * to newly accumulated documents too while it is in effect, which is the point: the pipeline stops
     * sending for a while rather than merely reordering what it sends.
     * <p>
     * Defaults to one second; {@link Duration#ZERO} or less disables backing off entirely, retrying at the
     * next flush (or, with {@link #getFlushThreshold()} at {@code 1}, on the very next document) as every
     * version of this library before this setting existed did.
     *
     * @return The base delay before retrying a failed transmission
     */
    default Duration getRetryBackoff() {
        return Duration.ofSeconds(1);
    }

    /**
     * Cap the delay described by {@link #getRetryBackoff()} never grows past, so a long outage settles into
     * retrying at a steady (low) rate instead of drifting towards intervals long enough for
     * {@link #getMaxPendingDocuments()} to start discarding documents that could have been sent. Ignored if
     * smaller than {@link #getRetryBackoff()}. Defaults to thirty seconds.
     *
     * @return The maximum delay before retrying a failed transmission
     */
    default Duration getMaxRetryBackoff() {
        return Duration.ofSeconds(30);
    }

}
