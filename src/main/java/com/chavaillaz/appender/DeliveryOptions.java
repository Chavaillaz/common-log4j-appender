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
 * Instances are immutable. They are created through {@link #builder()}, or derived from another one through
 * {@link #with()}, every setting having a usable default (see {@link #defaults()}): a setting a later version
 * adds comes with a default of its own, so that code building options keeps compiling, and behaving as it did.
 * <p>
 * {@link LogConfiguration#getDeliveryOptions()} is read again each time a setting is used, so that a
 * configuration can vary a setting at runtime by returning another instance, held in a field rather than built
 * on every call. A few settings are read only once, when the appender or its delivery is created:
 * {@code flushInterval}, {@code maxConcurrentTransmissions}, {@code maxQueuedEvents}, {@code retryBackoff},
 * {@code maxRetryBackoff} and {@code transmissionTimeout}.
 * <p>
 * Every setting is validated when built, a value out of its range throwing an {@link IllegalArgumentException}
 * and a missing one a {@link NullPointerException}, so that a misconfiguration fails the configuration rather
 * than the delivery once running. A limit meant to be unbounded takes the largest value of its type, as its
 * default does.
 */
public final class DeliveryOptions {

    /**
     * The instance {@link #defaults()} returns, shared rather than built per call, since
     * {@link LogConfiguration#getDeliveryOptions()} returns it by default for every document.
     */
    private static final DeliveryOptions DEFAULTS = builder().build();

    private final int flushThreshold;
    private final Duration flushInterval;
    private final @Nullable Level flushLevel;
    private final int maxConcurrentTransmissions;
    private final int maxPendingDocuments;
    private final int maxRequestDocuments;
    private final long maxPendingBytes;
    private final long maxRequestBytes;
    private final int maxConsecutiveFailures;
    private final int maxQueuedEvents;
    private final Duration retryBackoff;
    private final Duration maxRetryBackoff;
    private final @Nullable Duration transmissionTimeout;
    private final @Nullable Duration closeTimeout;

    /**
     * Creates the options a builder describes, validating every setting.
     *
     * @param builder The builder holding the settings
     * @throws IllegalArgumentException If a setting is out of its range
     * @throws NullPointerException     If a setting other than the flush level, the transmission timeout and the
     *                                  close timeout is {@code null}
     */
    private DeliveryOptions(Builder builder) {
        this.flushThreshold = requirePositive(builder.flushThreshold, "flushThreshold");
        this.flushInterval = requireSchedulable(builder.flushInterval);
        this.flushLevel = builder.flushLevel;
        this.maxConcurrentTransmissions = requirePositive(builder.maxConcurrentTransmissions, "maxConcurrentTransmissions");
        this.maxPendingDocuments = requirePositive(builder.maxPendingDocuments, "maxPendingDocuments");
        this.maxRequestDocuments = requirePositive(builder.maxRequestDocuments, "maxRequestDocuments");
        this.maxPendingBytes = requirePositive(builder.maxPendingBytes, "maxPendingBytes");
        this.maxRequestBytes = requirePositive(builder.maxRequestBytes, "maxRequestBytes");
        this.maxConsecutiveFailures = requirePositive(builder.maxConsecutiveFailures, "maxConsecutiveFailures");
        this.maxQueuedEvents = requirePositive(builder.maxQueuedEvents, "maxQueuedEvents");
        this.retryBackoff = requireNotNegative(
                Objects.requireNonNull(builder.retryBackoff, "retryBackoff must not be null"), "retryBackoff");
        this.maxRetryBackoff = requireNotNegative(
                Objects.requireNonNull(builder.maxRetryBackoff, "maxRetryBackoff must not be null"), "maxRetryBackoff");
        this.transmissionTimeout = builder.transmissionTimeout != null
                ? requireAtLeastAMillisecond(builder.transmissionTimeout, "transmissionTimeout")
                : null;
        this.closeTimeout = builder.closeTimeout != null ? requireNotNegative(builder.closeTimeout, "closeTimeout") : null;
    }

    private static int requirePositive(int value, String name) {
        return (int) requirePositive((long) value, name);
    }

    private static long requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, but was " + value);
        }
        return value;
    }

    private static Duration requireNotNegative(Duration value, String name) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative, but was " + value);
        }
        return value;
    }

    private static Duration requireAtLeastAMillisecond(Duration value, String name) {
        if (value.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(name + " must be at least a millisecond, but was " + value);
        }
        return value;
    }

    /**
     * Requires a flush interval the scheduler of an appender takes, which counts in whole milliseconds.
     *
     * @param flushInterval The flush interval to check
     * @return The flush interval
     */
    private static Duration requireSchedulable(Duration flushInterval) {
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
        return flushInterval;
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
        return new Builder(this);
    }

    /**
     * Number of accumulated documents that triggers a transmission, {@code 1} sending every document on its own
     * as it arrives. Defaults to {@code 1}; an appender shipping logs over the network normally raises it. At
     * least {@code 1}.
     *
     * @return The flush threshold
     */
    public int flushThreshold() {
        return flushThreshold;
    }

    /**
     * Time between two periodic flushes, which send the documents nothing else would send: under
     * {@link #flushThreshold()}, held back by {@link #retryBackoff()}, or put back by a failed transmission.
     * Defaults to five seconds; at least a millisecond.
     *
     * @return The flush interval
     */
    public Duration flushInterval() {
        return flushInterval;
    }

    /**
     * Minimum severity, compared with {@link Level#isMoreSpecificThan(Level)}, of an event flushing the batch
     * right after it, so that an occasional severe event is not held back by settings favoring throughput.
     * Defaults to {@code null}, disabling it.
     *
     * @return The flush level, or {@code null} if no severity flushes on its own
     */
    public @Nullable Level flushLevel() {
        return flushLevel;
    }

    /**
     * Maximum number of requests in flight at once, the next batch waiting for a slot past it. Defaults to
     * {@code 4}; at least {@code 1}.
     *
     * @return The maximum number of concurrent transmissions
     */
    public int maxConcurrentTransmissions() {
        return maxConcurrentTransmissions;
    }

    /**
     * Maximum number of documents the pending batch holds while waiting to be (re)transmitted, the oldest ones
     * being discarded past it while transmissions are held off after a failure. A batch reaching it otherwise is
     * transmitted, as if it reached {@link #flushThreshold()}. Defaults to {@code 10'000}; at least {@code 1}.
     *
     * @return The maximum number of pending documents
     */
    public int maxPendingDocuments() {
        return maxPendingDocuments;
    }

    /**
     * Maximum number of documents per request, a bigger batch being split into chunks transmitted and retried
     * independently. Defaults to {@link Integer#MAX_VALUE}, not splitting by count; at least {@code 1}.
     *
     * @return The maximum number of documents per request
     */
    public int maxRequestDocuments() {
        return maxRequestDocuments;
    }

    /**
     * Maximum estimated size of the pending batch, which it bounds as {@link #maxPendingDocuments()} does by
     * count. Sizes are estimated by
     * {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery#estimateDocumentSize(Map)}. Defaults to
     * {@link Long#MAX_VALUE}, unbounded; at least {@code 1}.
     *
     * @return The maximum pending size, in bytes
     */
    public long maxPendingBytes() {
        return maxPendingBytes;
    }

    /**
     * Maximum estimated size of a request, a heavier batch being split into chunks that each stay under it, and
     * a heavier document still being sent alone. Usually the limit that matters: Elasticsearch and OpenSearch
     * reject a request over {@code http.max_content_length} by its size, not by its document count. Defaults to
     * {@link Long#MAX_VALUE}, unbounded; at least {@code 1}.
     *
     * @return The maximum request size, in bytes
     */
    public long maxRequestBytes() {
        return maxRequestBytes;
    }

    /**
     * Maximum number of consecutive failed attempts for the same documents before they are discarded, so that a
     * document the downstream system never accepts does not block everything behind it. Defaults to {@code 5};
     * at least {@code 1}, {@link Integer#MAX_VALUE} retrying for good in practice.
     *
     * @return The maximum number of consecutive failures
     */
    public int maxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    /**
     * Maximum number of logging events queued for the background thread of an appender, the oldest one being
     * discarded past it. Defaults to {@code 10'000}; at least {@code 1}.
     *
     * @return The maximum number of queued events
     */
    public int maxQueuedEvents() {
        return maxQueuedEvents;
    }

    /**
     * Base delay before retrying after a failed transmission, doubling with each consecutive failure up to
     * {@link #maxRetryBackoff()}, each delay being drawn at random up to that value, so that instances losing
     * the same system do not retry in lockstep. Newly accumulated documents are held back too while it is in
     * effect. Defaults to one second; {@link Duration#ZERO} disables it, and it cannot be negative.
     *
     * @return The base retry delay
     */
    public Duration retryBackoff() {
        return retryBackoff;
    }

    /**
     * Cap of the delay described by {@link #retryBackoff()}, ignored if smaller than it. Defaults to thirty
     * seconds; it cannot be negative.
     *
     * @return The maximum retry delay
     */
    public Duration maxRetryBackoff() {
        return maxRetryBackoff;
    }

    /**
     * Maximum time a transmission waits for its answer before it counts as failed: its slot is freed and its
     * documents are retried as those of any failed transmission, the thread still calling the client is
     * interrupted, and the future the transmission returned is cancelled. It keeps a downstream system that
     * stops answering, behind a client without a timeout of its own, from holding every slot until the delivery
     * closes. An answer coming later is ignored, its documents being delivered twice if the downstream system
     * accepted them. Set it above the timeout of the client, whose own failure says more. Defaults to one
     * minute; {@code null} waits indefinitely, and it is at least a millisecond otherwise.
     *
     * @return The transmission timeout, or {@code null} to wait indefinitely
     */
    public @Nullable Duration transmissionTimeout() {
        return transmissionTimeout;
    }

    /**
     * Maximum time closing a delivery waits for the transmissions in flight, its final flush included, before
     * reporting what is still pending through {@link LogDeliveryListener#onDocumentsDropped(List, String)}.
     * While the {@code shutdownTimeout} of the Log4j2 configuration is left at {@code 0}, also the budget of a
     * whole appender stop: the events it accepted are processed first, and its delivery closed within what they
     * left. Defaults to five seconds, so that the platform running the application, which kills a process
     * taking longer to stop (after 10 seconds for Docker, 30 for Kubernetes), lets it report what is still
     * pending, the budgets of several appenders adding up as Log4j2 stops them one after the other;
     * {@code null} waits indefinitely, and it cannot be negative.
     *
     * @return The close timeout, or {@code null} to wait indefinitely
     */
    public @Nullable Duration closeTimeout() {
        return closeTimeout;
    }

    /**
     * Compares the settings: two instances are equal when every setting is.
     *
     * @param other The object to compare with
     * @return {@code true} for options with the same settings
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DeliveryOptions options
                && flushThreshold == options.flushThreshold
                && flushInterval.equals(options.flushInterval)
                && Objects.equals(flushLevel, options.flushLevel)
                && maxConcurrentTransmissions == options.maxConcurrentTransmissions
                && maxPendingDocuments == options.maxPendingDocuments
                && maxRequestDocuments == options.maxRequestDocuments
                && maxPendingBytes == options.maxPendingBytes
                && maxRequestBytes == options.maxRequestBytes
                && maxConsecutiveFailures == options.maxConsecutiveFailures
                && maxQueuedEvents == options.maxQueuedEvents
                && retryBackoff.equals(options.retryBackoff)
                && maxRetryBackoff.equals(options.maxRetryBackoff)
                && Objects.equals(transmissionTimeout, options.transmissionTimeout)
                && Objects.equals(closeTimeout, options.closeTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flushThreshold, flushInterval, flushLevel, maxConcurrentTransmissions,
                maxPendingDocuments, maxRequestDocuments, maxPendingBytes, maxRequestBytes,
                maxConsecutiveFailures, maxQueuedEvents, retryBackoff, maxRetryBackoff, transmissionTimeout,
                closeTimeout);
    }

    /**
     * Lists every setting with its value.
     *
     * @return The settings, as {@code DeliveryOptions[flushThreshold=1, ...]}
     */
    @Override
    public String toString() {
        return "DeliveryOptions[flushThreshold=" + flushThreshold
                + ", flushInterval=" + flushInterval
                + ", flushLevel=" + flushLevel
                + ", maxConcurrentTransmissions=" + maxConcurrentTransmissions
                + ", maxPendingDocuments=" + maxPendingDocuments
                + ", maxRequestDocuments=" + maxRequestDocuments
                + ", maxPendingBytes=" + maxPendingBytes
                + ", maxRequestBytes=" + maxRequestBytes
                + ", maxConsecutiveFailures=" + maxConsecutiveFailures
                + ", maxQueuedEvents=" + maxQueuedEvents
                + ", retryBackoff=" + retryBackoff
                + ", maxRetryBackoff=" + maxRetryBackoff
                + ", transmissionTimeout=" + transmissionTimeout
                + ", closeTimeout=" + closeTimeout + "]";
    }

    /**
     * Builder for {@link DeliveryOptions}, the only way to create them: a setting is named when it is set, and
     * one added by a later version takes its default where it is not.
     */
    public static final class Builder {

        private int flushThreshold = 1;
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
        private @Nullable Duration transmissionTimeout = Duration.ofMinutes(1);
        private @Nullable Duration closeTimeout = Duration.ofSeconds(5);

        private Builder() {
            // Instantiated only through DeliveryOptions.builder()
        }

        /**
         * Creates a builder starting from the settings of existing options, for {@link DeliveryOptions#with()}.
         *
         * @param options The options to start from
         */
        private Builder(DeliveryOptions options) {
            this.flushThreshold = options.flushThreshold;
            this.flushInterval = options.flushInterval;
            this.flushLevel = options.flushLevel;
            this.maxConcurrentTransmissions = options.maxConcurrentTransmissions;
            this.maxPendingDocuments = options.maxPendingDocuments;
            this.maxRequestDocuments = options.maxRequestDocuments;
            this.maxPendingBytes = options.maxPendingBytes;
            this.maxRequestBytes = options.maxRequestBytes;
            this.maxConsecutiveFailures = options.maxConsecutiveFailures;
            this.maxQueuedEvents = options.maxQueuedEvents;
            this.retryBackoff = options.retryBackoff;
            this.maxRetryBackoff = options.maxRetryBackoff;
            this.transmissionTimeout = options.transmissionTimeout;
            this.closeTimeout = options.closeTimeout;
        }

        /**
         * Sets the number of accumulated documents that triggers a transmission.
         *
         * @param flushThreshold The flush threshold
         * @return This builder
         */
        public Builder flushThreshold(int flushThreshold) {
            this.flushThreshold = flushThreshold;
            return this;
        }

        /**
         * Sets the time between two periodic flushes.
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
         * Sets the maximum number of consecutive failed attempts allowed for the same documents.
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
         * Sets the maximum time a transmission waits for its answer before it counts as failed, or {@code null}
         * to wait indefinitely.
         *
         * @param transmissionTimeout The transmission timeout
         * @return This builder
         */
        public Builder transmissionTimeout(@Nullable Duration transmissionTimeout) {
            this.transmissionTimeout = transmissionTimeout;
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
         * @throws NullPointerException     If a setting other than the flush level, the transmission timeout and
         *                                  the close timeout is {@code null}
         */
        public DeliveryOptions build() {
            return new DeliveryOptions(this);
        }

    }

}
