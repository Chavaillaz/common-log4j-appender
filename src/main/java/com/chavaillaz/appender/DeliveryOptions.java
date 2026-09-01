package com.chavaillaz.appender;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

/**
 * How the delivery pipeline behaves: batching, backpressure, retries and shutdown. The generic half of
 * {@link LogConfiguration}, which keeps only what identifies the application producing the logs.
 * <p>
 * These settings used to be default methods on {@link LogConfiguration} itself, one added per knob. That
 * shape does not scale: every new setting grows an interface every implementation inherits but none
 * implements, an implementation cannot be told apart from one that deliberately overrode a value, and there
 * is nowhere to put a setting that is only meaningful next to another one. As an immutable value, the whole
 * block is instead passed around, defaulted, copied and compared as a unit, and adding a knob touches this
 * class alone.
 * <p>
 * Instances are created through {@link #builder()} (or {@link #defaults()}), every setting having a usable
 * default. Being a record, {@link #with()} gives back a builder pre-filled from an existing instance, so a
 * caller can override one setting without restating the rest.
 * <p>
 * A {@link LogConfiguration} is expected to return a stored instance rather than build a new one per call:
 * {@link LogConfiguration#getDeliveryOptions()} is read on the hot path (once or more per document), not
 * once at construction, precisely so an implementation can vary a limit at runtime by returning a different
 * instance.
 *
 * @param flushThreshold             Number of accumulated documents that triggers a transmission. A value of
 *                                   {@code 1} or less disables batching entirely: every document is sent
 *                                   individually, as soon as it arrives. Defaults to {@code 1}; an appender
 *                                   shipping logs over the network normally raises it well above that.
 * @param flushInterval              Maximum time between two transmissions: past this interval, a
 *                                   transmission is triggered even if {@code flushThreshold} was not reached
 *                                   yet, so a partial batch is not delayed indefinitely under low traffic.
 *                                   Only relevant when {@code flushThreshold} is above {@code 1}. Defaults to
 *                                   five seconds.
 * @param flushLevel                 Minimum severity that triggers an immediate flush right after the
 *                                   corresponding event was handed to the current batch, instead of waiting
 *                                   for {@code flushThreshold} or {@code flushInterval} as usual; every
 *                                   other, less severe event still only counts toward those two as before.
 *                                   Useful so an occasional severe event (e.g. {@code ERROR}) is shipped
 *                                   promptly even while a high {@code flushThreshold} or
 *                                   {@code flushInterval} otherwise favors throughput over latency for
 *                                   routine traffic. Compared using {@link Level#isMoreSpecificThan(Level)},
 *                                   so e.g. a value of {@code WARN} also triggers on {@code ERROR} and
 *                                   {@code FATAL}. Defaults to {@code null}, disabling this behavior
 *                                   entirely.
 * @param maxConcurrentTransmissions Maximum number of batches transmitted concurrently; forming the next
 *                                   batch waits for a slot once this limit is reached, so the downstream
 *                                   system is never overwhelmed with unbounded concurrent requests. Defaults
 *                                   to {@code 4}; must be at least {@code 1}, or every transmission would
 *                                   wait forever for a slot that never opens.
 * @param maxPendingDocuments        Maximum number of documents a pending batch can hold in memory while
 *                                   waiting to be (re)transmitted, across both accumulation and retries; past
 *                                   it, the oldest documents are discarded so a struggling downstream system
 *                                   cannot grow memory usage without bound. Independent of
 *                                   {@code maxRequestDocuments}, which governs how many documents go out per
 *                                   request rather than how many can be held pending. Defaults to
 *                                   {@code 10'000}; {@code 0} or less discards every document as soon as it
 *                                   would be added, effectively disabling the delivery.
 * @param maxRequestDocuments        Maximum number of documents sent together in a single request; a pending
 *                                   batch bigger than this is split into chunks of at most this size, each
 *                                   transmitted (and retried on failure) independently. Exists only to
 *                                   respect a downstream system's own limit on documents per request; leave
 *                                   unset if it has none. Defaults to {@link Integer#MAX_VALUE} (no
 *                                   chunking); a non-positive value also disables chunking.
 * @param maxPendingBytes            Maximum estimated size a pending batch can reach in memory; past it, the
 *                                   oldest documents are discarded, exactly as {@code maxPendingDocuments}
 *                                   does by count. The two are complementary rather than redundant: a batch
 *                                   comfortably within a 10'000-document limit still holds hundreds of
 *                                   megabytes if the documents are large, which is the failure a count-based
 *                                   limit alone cannot see coming. Sizes are approximated by
 *                                   {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery#estimateDocumentSize(Map)}.
 *                                   Defaults to {@link Long#MAX_VALUE} (unbounded); a non-positive value also
 *                                   disables the limit.
 * @param maxRequestBytes            Maximum estimated size of a single request: a batch whose documents add
 *                                   up to more than this is split into chunks that each stay under it, on top
 *                                   of whatever {@code maxRequestDocuments} already imposes. This is usually
 *                                   the limit that actually matters downstream — Elasticsearch and OpenSearch
 *                                   reject a bulk request over {@code http.max_content_length} (100MB by
 *                                   default) by payload size, not by document count, so a
 *                                   {@code maxRequestDocuments} tuned to stay under it is only ever a guess
 *                                   about average document size. A single document larger than this limit is
 *                                   still sent on its own, there being no way to split it further. Defaults to
 *                                   {@link Long#MAX_VALUE} (unbounded); a non-positive value also disables the
 *                                   limit.
 * @param maxConsecutiveFailures     Maximum number of consecutive failed transmission attempts allowed for
 *                                   the same pending batch before it is discarded instead of retried again.
 *                                   Without this limit, a single document the downstream system can never
 *                                   accept (a "poison document") would fail its batch forever, blocking
 *                                   everything accumulated behind it. Defaults to {@code 5}; {@code 0} or
 *                                   less disables this limit and retries indefinitely instead.
 * @param maxQueuedEvents            Maximum number of logging events an appender's background thread can
 *                                   queue awaiting conversion and batching, bounding the memory events
 *                                   arriving faster than they can be processed could otherwise use without
 *                                   limit; unrelated to {@code maxPendingDocuments}, which bounds documents
 *                                   that already reached that stage. Once full, the oldest queued event is
 *                                   discarded to make room for the new one. Defaults to {@code 10'000};
 *                                   values below {@code 1} are clamped up to {@code 1}, since a queue of zero
 *                                   has nowhere to hand off even a single event.
 * @param retryBackoff               Base delay before retrying after a failed transmission, doubling with
 *                                   every consecutive failure up to {@code maxRetryBackoff} and picked at
 *                                   random within that (see
 *                                   {@link BackoffPolicy#exponential(Duration, Duration)}), so a downstream
 *                                   system that is down, overloaded or rate-limiting is not hit again at the
 *                                   rate logs happen to arrive. Applies to newly accumulated documents too
 *                                   while it is in effect, which is the point: the pipeline stops sending for
 *                                   a while rather than merely reordering what it sends. Defaults to one
 *                                   second; {@link Duration#ZERO} or less disables backing off entirely.
 * @param maxRetryBackoff            Cap the delay described by {@code retryBackoff} never grows past, so a
 *                                   long outage settles into retrying at a steady (low) rate instead of
 *                                   drifting towards intervals long enough for {@code maxPendingDocuments} to
 *                                   start discarding documents that could have been sent. Ignored if smaller
 *                                   than {@code retryBackoff}. Defaults to thirty seconds.
 * @param closeTimeout               Maximum time a batch-based log delivery waits, while closing, for the
 *                                   transmissions still in flight (including the final flush's own) to
 *                                   complete, before giving up and reporting whatever is still pending
 *                                   through {@link LogDeliveryListener#onDocumentsDropped(List, String)}.
 *                                   Bounds how long an unresponsive downstream system can hold up an
 *                                   application's shutdown. Distinct from, and applied after, the
 *                                   {@code shutdownTimeout} of the Log4j2 configuration, which governs how
 *                                   long the appender waits for already-accepted events to reach the delivery
 *                                   in the first place. Defaults to thirty seconds; {@code null} waits
 *                                   indefinitely.
 */
public record DeliveryOptions(
        long flushThreshold,
        Duration flushInterval,
        @Nullable Level flushLevel,
        int maxConcurrentTransmissions,
        int maxPendingDocuments,
        int maxRequestDocuments,
        long maxPendingBytes,
        long maxRequestBytes,
        int maxConsecutiveFailures,
        int maxQueuedEvents,
        Duration retryBackoff,
        Duration maxRetryBackoff,
        @Nullable Duration closeTimeout) {

    /**
     * The options every setting left at its default produces, suitable as-is for an appender that has no
     * reason to tune anything.
     *
     * @return The default options
     */
    public static DeliveryOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a builder for new options, every setting having a usable default.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder pre-filled with this instance's settings, so one of them can be overridden without
     * restating the rest.
     *
     * @return A builder starting from these options
     */
    public Builder with() {
        return new Builder()
                .flushThreshold(flushThreshold)
                .flushInterval(flushInterval)
                .flushLevel(flushLevel)
                .maxConcurrentTransmissions(maxConcurrentTransmissions)
                .maxPendingDocuments(maxPendingDocuments)
                .maxRequestDocuments(maxRequestDocuments)
                .maxPendingBytes(maxPendingBytes)
                .maxRequestBytes(maxRequestBytes)
                .maxConsecutiveFailures(maxConsecutiveFailures)
                .maxQueuedEvents(maxQueuedEvents)
                .retryBackoff(retryBackoff)
                .maxRetryBackoff(maxRetryBackoff)
                .closeTimeout(closeTimeout);
    }

    /**
     * Builder for {@link DeliveryOptions}, preferred over the canonical constructor: it takes eleven
     * components, most of them same-typed counts and durations, which such a constructor makes trivially
     * easy to swap by mistake with no compiler error to show for it.
     */
    public static final class Builder {

        private long flushThreshold = 1;
        private Duration flushInterval = Duration.ofSeconds(5);
        private @Nullable Level flushLevel;
        private int maxConcurrentTransmissions = 4;
        private int maxPendingDocuments = 10_000;
        private int maxRequestDocuments = Integer.MAX_VALUE;
        private long maxPendingBytes = Long.MAX_VALUE;
        private long maxRequestBytes = Long.MAX_VALUE;
        private int maxConsecutiveFailures = 5;
        private int maxQueuedEvents = 10_000;
        private Duration retryBackoff = Duration.ofSeconds(1);
        private Duration maxRetryBackoff = Duration.ofSeconds(30);
        private @Nullable Duration closeTimeout = Duration.ofSeconds(30);

        private Builder() {
            // Instantiated only through DeliveryOptions.builder() or DeliveryOptions.with()
        }

        /**
         * Sets the number of accumulated documents that triggers a transmission.
         *
         * @param flushThreshold The flush threshold
         * @return This builder
         */
        public Builder flushThreshold(long flushThreshold) {
            this.flushThreshold = flushThreshold;
            return this;
        }

        /**
         * Sets the maximum time between two transmissions.
         *
         * @param flushInterval The flush interval
         * @return This builder
         */
        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        /**
         * Sets the minimum severity that triggers an immediate flush, or {@code null} to disable it.
         *
         * @param flushLevel The flush level
         * @return This builder
         */
        public Builder flushLevel(@Nullable Level flushLevel) {
            this.flushLevel = flushLevel;
            return this;
        }

        /**
         * Sets the maximum number of batches transmitted concurrently.
         *
         * @param maxConcurrentTransmissions The maximum number of concurrent transmissions
         * @return This builder
         */
        public Builder maxConcurrentTransmissions(int maxConcurrentTransmissions) {
            this.maxConcurrentTransmissions = maxConcurrentTransmissions;
            return this;
        }

        /**
         * Sets the maximum number of documents a pending batch can hold in memory.
         *
         * @param maxPendingDocuments The maximum number of pending documents
         * @return This builder
         */
        public Builder maxPendingDocuments(int maxPendingDocuments) {
            this.maxPendingDocuments = maxPendingDocuments;
            return this;
        }

        /**
         * Sets the maximum number of documents sent together in a single request.
         *
         * @param maxRequestDocuments The maximum number of documents per request
         * @return This builder
         */
        public Builder maxRequestDocuments(int maxRequestDocuments) {
            this.maxRequestDocuments = maxRequestDocuments;
            return this;
        }

        /**
         * Sets the maximum estimated size a pending batch can reach in memory.
         *
         * @param maxPendingBytes The maximum pending size, in bytes
         * @return This builder
         */
        public Builder maxPendingBytes(long maxPendingBytes) {
            this.maxPendingBytes = maxPendingBytes;
            return this;
        }

        /**
         * Sets the maximum estimated size of a single request.
         *
         * @param maxRequestBytes The maximum request size, in bytes
         * @return This builder
         */
        public Builder maxRequestBytes(long maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
            return this;
        }

        /**
         * Sets the maximum number of consecutive failed attempts allowed for the same pending batch.
         *
         * @param maxConsecutiveFailures The maximum number of consecutive failures
         * @return This builder
         */
        public Builder maxConsecutiveFailures(int maxConsecutiveFailures) {
            this.maxConsecutiveFailures = maxConsecutiveFailures;
            return this;
        }

        /**
         * Sets the maximum number of logging events queued awaiting conversion and batching.
         *
         * @param maxQueuedEvents The maximum number of queued events
         * @return This builder
         */
        public Builder maxQueuedEvents(int maxQueuedEvents) {
            this.maxQueuedEvents = maxQueuedEvents;
            return this;
        }

        /**
         * Sets the base delay before retrying after a failed transmission.
         *
         * @param retryBackoff The base retry delay
         * @return This builder
         */
        public Builder retryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
            return this;
        }

        /**
         * Sets the cap the retry delay never grows past.
         *
         * @param maxRetryBackoff The maximum retry delay
         * @return This builder
         */
        public Builder maxRetryBackoff(Duration maxRetryBackoff) {
            this.maxRetryBackoff = maxRetryBackoff;
            return this;
        }

        /**
         * Sets the maximum time to wait for in-flight transmissions while closing, or {@code null} to wait
         * indefinitely.
         *
         * @param closeTimeout The close timeout
         * @return This builder
         */
        public Builder closeTimeout(@Nullable Duration closeTimeout) {
            this.closeTimeout = closeTimeout;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return The new options
         */
        public DeliveryOptions build() {
            return new DeliveryOptions(flushThreshold, flushInterval, flushLevel, maxConcurrentTransmissions,
                    maxPendingDocuments, maxRequestDocuments, maxPendingBytes, maxRequestBytes,
                    maxConsecutiveFailures, maxQueuedEvents,
                    retryBackoff, maxRetryBackoff, closeTimeout);
        }

    }

}
