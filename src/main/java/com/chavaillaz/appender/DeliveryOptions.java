package com.chavaillaz.appender;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

/**
 * How the delivery pipeline behaves: batching, backpressure, retries and shutdown. The generic half of
 * {@link LogConfiguration}, which keeps what identifies the application producing the logs.
 * <p>
 * Instances are created through {@link #builder()}, or derived from another one through {@link #with()}, every
 * setting having a usable default (see {@link #defaults()}).
 * <p>
 * {@link LogConfiguration#getDeliveryOptions()} is read again each time a setting is used, so that a
 * configuration can vary one at runtime by returning another instance, stored rather than built on every call.
 * A few are read once all the same: {@code flushInterval}, {@code maxConcurrentTransmissions},
 * {@code maxQueuedEvents}, {@code retryBackoff} and {@code maxRetryBackoff}, when the appender or its delivery
 * is created, along with whether a periodic flush is scheduled at all, which {@code flushThreshold} and
 * {@code retryBackoff} decide when the appender starts.
 * <p>
 * Every setting is validated when built, a value out of its range throwing an {@link IllegalArgumentException}
 * and a missing one a {@link NullPointerException}, so that a misconfiguration fails the configuration rather
 * than the delivery once running. A limit meant to be unbounded takes the largest value of its type, as its
 * default does.
 *
 * @param flushThreshold             Number of accumulated documents that triggers a transmission, {@code 1}
 *                                   sending every document on its own as it arrives. Defaults to {@code 1}; an
 *                                   appender shipping logs over the network normally raises it. At least
 *                                   {@code 1}.
 * @param flushInterval              Time between two periodic flushes, which send a partial batch nothing else
 *                                   would send: one under {@code flushThreshold}, or held back by
 *                                   {@code retryBackoff}. Defaults to five seconds; at least a millisecond.
 * @param flushLevel                 Minimum severity, compared with {@link Level#isMoreSpecificThan(Level)}, of
 *                                   an event flushing the batch right after it, so that an occasional severe
 *                                   event is not held back by settings favoring throughput. Defaults to
 *                                   {@code null}, disabling it.
 * @param maxConcurrentTransmissions Maximum number of requests in flight at once, the next batch waiting for a
 *                                   slot past it. Defaults to {@code 4}; at least {@code 1}.
 * @param maxPendingDocuments        Maximum number of documents the pending batch holds while waiting to be
 *                                   (re)transmitted, the oldest ones being discarded past it while transmissions
 *                                   are held off after a failure. A batch reaching it otherwise is transmitted,
 *                                   as if it reached {@code flushThreshold}. Defaults to {@code 10'000}; at
 *                                   least {@code 1}.
 * @param maxRequestDocuments        Maximum number of documents per request, a bigger batch being split into
 *                                   chunks transmitted and retried independently. Defaults to
 *                                   {@link Integer#MAX_VALUE}, not splitting by count; at least {@code 1}.
 * @param maxPendingBytes            Maximum estimated size of the pending batch, which it bounds as
 *                                   {@code maxPendingDocuments} does by count. Sizes are estimated by
 *                                   {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery#estimateDocumentSize(Map)}.
 *                                   Defaults to {@link Long#MAX_VALUE}, unbounded; at least {@code 1}.
 * @param maxRequestBytes            Maximum estimated size of a request, a heavier batch being split into
 *                                   chunks that each stay under it, and a heavier document still being sent
 *                                   alone. Usually the limit that matters: Elasticsearch and OpenSearch reject a
 *                                   request over {@code http.max_content_length} by its size, not by its
 *                                   document count. Defaults to {@link Long#MAX_VALUE}, unbounded; at least
 *                                   {@code 1}.
 * @param maxConsecutiveFailures     Maximum number of consecutive failed attempts for the same documents before
 *                                   they are discarded, so that a document the downstream system never accepts
 *                                   does not block everything behind it. Defaults to {@code 5}; at least
 *                                   {@code 1}, {@link Integer#MAX_VALUE} retrying for good in practice.
 * @param maxQueuedEvents            Maximum number of logging events queued for the background thread of an
 *                                   appender, the oldest one being discarded past it. Defaults to
 *                                   {@code 10'000}; at least {@code 1}.
 * @param retryBackoff               Base delay before retrying after a failed transmission, doubling with each
 *                                   consecutive failure up to {@code maxRetryBackoff}, each delay being drawn at
 *                                   random up to that value, so that instances losing the same system do not
 *                                   retry in lockstep. Newly accumulated documents are held back too while it
 *                                   is in effect. Defaults to one second; {@link Duration#ZERO} disables it, and
 *                                   it cannot be negative.
 * @param maxRetryBackoff            Cap of the delay described by {@code retryBackoff}, ignored if smaller than
 *                                   it. Defaults to thirty seconds; it cannot be negative.
 * @param closeTimeout               Maximum time closing a delivery waits for the transmissions in flight, its
 *                                   final flush included, before reporting what is still pending through
 *                                   {@link LogDeliveryListener#onDocumentsDropped(List, String)}. While the
 *                                   {@code shutdownTimeout} of the Log4j2 configuration is left at {@code 0},
 *                                   also the budget of a whole appender stop: the events it accepted are
 *                                   processed first, and its delivery closed within what they left. Defaults
 *                                   to thirty seconds; {@code null} waits indefinitely, and it cannot be
 *                                   negative.
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
     * The instance {@link #defaults()} returns, shared rather than built per call, since
     * {@link LogConfiguration#getDeliveryOptions()} returns it by default for every document.
     */
    private static final DeliveryOptions DEFAULTS = builder().build();

    /**
     * Validates every setting (see the documentation of the record).
     *
     * @throws IllegalArgumentException If a setting is out of its range
     * @throws NullPointerException     If a setting other than {@code flushLevel} and {@code closeTimeout} is
     *                                  {@code null}
     */
    public DeliveryOptions {
        requirePositive(flushThreshold, "flushThreshold");
        requireSchedulable(flushInterval);
        requirePositive(maxConcurrentTransmissions, "maxConcurrentTransmissions");
        requirePositive(maxPendingDocuments, "maxPendingDocuments");
        requirePositive(maxRequestDocuments, "maxRequestDocuments");
        requirePositive(maxPendingBytes, "maxPendingBytes");
        requirePositive(maxRequestBytes, "maxRequestBytes");
        requirePositive(maxConsecutiveFailures, "maxConsecutiveFailures");
        requirePositive(maxQueuedEvents, "maxQueuedEvents");
        requireNotNegative(Objects.requireNonNull(retryBackoff, "retryBackoff must not be null"), "retryBackoff");
        requireNotNegative(Objects.requireNonNull(maxRetryBackoff, "maxRetryBackoff must not be null"), "maxRetryBackoff");
        if (closeTimeout != null) {
            requireNotNegative(closeTimeout, "closeTimeout");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, but was " + value);
        }
    }

    private static void requireNotNegative(Duration value, String name) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative, but was " + value);
        }
    }

    /**
     * Requires a flush interval the scheduler of an appender takes, which counts in whole milliseconds.
     *
     * @param flushInterval The flush interval to check
     */
    private static void requireSchedulable(Duration flushInterval) {
        Objects.requireNonNull(flushInterval, "flushInterval must not be null");
        long millis;
        try {
            millis = flushInterval.toMillis();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("flushInterval is too large to count in milliseconds: " + flushInterval, e);
        }
        if (millis < 1) {
            throw new IllegalArgumentException("flushInterval must be at least a millisecond, but was " + flushInterval);
        }
    }

    /**
     * The options every setting left at its default produces, suitable as-is for an appender that has no
     * reason to tune anything. Always the same instance.
     *
     * @return The default options
     */
    public static DeliveryOptions defaults() {
        return DEFAULTS;
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
     * Builder for {@link DeliveryOptions}, preferred over the canonical constructor, whose thirteen mostly
     * same-typed components are easy to swap by mistake.
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
         * @throws IllegalArgumentException If a setting is out of its range (see {@link DeliveryOptions})
         * @throws NullPointerException     If a setting other than the flush level and the close timeout is
         *                                  {@code null}
         */
        public DeliveryOptions build() {
            return new DeliveryOptions(flushThreshold, flushInterval, flushLevel, maxConcurrentTransmissions,
                    maxPendingDocuments, maxRequestDocuments, maxPendingBytes, maxRequestBytes,
                    maxConsecutiveFailures, maxQueuedEvents,
                    retryBackoff, maxRetryBackoff, closeTimeout);
        }

    }

}
