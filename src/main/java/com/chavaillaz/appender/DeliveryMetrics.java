package com.chavaillaz.appender;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A snapshot of a delivery pipeline for a metrics library to poll: how much it holds right now, for gauges, and
 * what it did since it started, for counters, which only ever grow. Taken by
 * {@link com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender#getMetrics()} for a whole appender, or by
 * {@link LogDelivery#getMetrics()} for its delivery alone.
 * <p>
 * Instances are immutable. A delivery tracking its own creates them through {@link #builder()}, naming each
 * value it sets and leaving the others at {@code 0}: a value a later version adds starts there too, so that code
 * building metrics keeps compiling.
 */
public final class DeliveryMetrics {

    /**
     * The metrics of a pipeline that tracks none, every value {@code 0}.
     */
    public static final DeliveryMetrics NONE = builder().build();

    private final int queuedEvents;
    private final int pendingDocuments;
    private final long pendingBytes;
    private final int transmissionsInFlight;
    private final long transmittedDocuments;
    private final long failedTransmissions;
    private final long droppedDocuments;

    /**
     * Creates the metrics a builder describes.
     *
     * @param builder The builder holding the values
     */
    private DeliveryMetrics(Builder builder) {
        this.queuedEvents = builder.queuedEvents;
        this.pendingDocuments = builder.pendingDocuments;
        this.pendingBytes = builder.pendingBytes;
        this.transmissionsInFlight = builder.transmissionsInFlight;
        this.transmittedDocuments = builder.transmittedDocuments;
        this.failedTransmissions = builder.failedTransmissions;
        this.droppedDocuments = builder.droppedDocuments;
    }

    /**
     * Creates a builder for new metrics, every value starting at {@code 0}.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder pre-filled with these metrics, so that some of them can be replaced while the others,
     * those a later version adds included, are carried over: how an appender adds what its queue holds to the
     * metrics of its delivery.
     *
     * @return A builder starting from these metrics
     */
    public Builder with() {
        return new Builder(this);
    }

    /**
     * Logging events waiting for the background thread of the appender, {@code 0} for a delivery on its own.
     *
     * @return The number of queued events
     */
    public int queuedEvents() {
        return queuedEvents;
    }

    /**
     * Documents in the pending batch, waiting to be transmitted or retried.
     *
     * @return The number of pending documents
     */
    public int pendingDocuments() {
        return pendingDocuments;
    }

    /**
     * Estimated size of the pending batch, {@code 0} while no byte limit is in effect.
     *
     * @return The estimated pending size, in bytes
     */
    public long pendingBytes() {
        return pendingBytes;
    }

    /**
     * Requests sent and not answered yet.
     *
     * @return The number of transmissions in flight
     */
    public int transmissionsInFlight() {
        return transmissionsInFlight;
    }

    /**
     * Documents the downstream system accepted.
     *
     * @return The number of documents transmitted since the pipeline started
     */
    public long transmittedDocuments() {
        return transmittedDocuments;
    }

    /**
     * Transmission attempts that failed, in whole or in part, their documents retried or not.
     *
     * @return The number of failed transmission attempts since the pipeline started
     */
    public long failedTransmissions() {
        return failedTransmissions;
    }

    /**
     * Documents irrecoverably discarded, whatever the reason, those of the events the appender discarded
     * included: each reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}, unless too
     * many were already waiting to be reported, or the appender has no delivery to report to.
     *
     * @return The number of documents dropped since the pipeline started
     */
    public long droppedDocuments() {
        return droppedDocuments;
    }

    /**
     * Compares the values: two snapshots are equal when every value is.
     *
     * @param other The object to compare with
     * @return {@code true} for metrics with the same values
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DeliveryMetrics metrics
                && queuedEvents == metrics.queuedEvents
                && pendingDocuments == metrics.pendingDocuments
                && pendingBytes == metrics.pendingBytes
                && transmissionsInFlight == metrics.transmissionsInFlight
                && transmittedDocuments == metrics.transmittedDocuments
                && failedTransmissions == metrics.failedTransmissions
                && droppedDocuments == metrics.droppedDocuments;
    }

    @Override
    public int hashCode() {
        return Objects.hash(queuedEvents, pendingDocuments, pendingBytes, transmissionsInFlight,
                transmittedDocuments, failedTransmissions, droppedDocuments);
    }

    /**
     * Lists every value with its name.
     *
     * @return The values, as {@code DeliveryMetrics[queuedEvents=0, ...]}
     */
    @Override
    public String toString() {
        return "DeliveryMetrics[queuedEvents=" + queuedEvents
                + ", pendingDocuments=" + pendingDocuments
                + ", pendingBytes=" + pendingBytes
                + ", transmissionsInFlight=" + transmissionsInFlight
                + ", transmittedDocuments=" + transmittedDocuments
                + ", failedTransmissions=" + failedTransmissions
                + ", droppedDocuments=" + droppedDocuments + "]";
    }

    /**
     * Builder for {@link DeliveryMetrics}, the only way to create them: each value is named when it is set, where
     * a constructor would take seven numbers in a row, two of them easily swapped.
     */
    public static final class Builder {

        private int queuedEvents;
        private int pendingDocuments;
        private long pendingBytes;
        private int transmissionsInFlight;
        private long transmittedDocuments;
        private long failedTransmissions;
        private long droppedDocuments;

        private Builder() {
            // Instantiated only through DeliveryMetrics.builder()
        }

        /**
         * Creates a builder starting from existing metrics, for {@link DeliveryMetrics#with()}.
         *
         * @param metrics The metrics to start from
         */
        private Builder(DeliveryMetrics metrics) {
            this.queuedEvents = metrics.queuedEvents;
            this.pendingDocuments = metrics.pendingDocuments;
            this.pendingBytes = metrics.pendingBytes;
            this.transmissionsInFlight = metrics.transmissionsInFlight;
            this.transmittedDocuments = metrics.transmittedDocuments;
            this.failedTransmissions = metrics.failedTransmissions;
            this.droppedDocuments = metrics.droppedDocuments;
        }

        /**
         * Sets the logging events waiting for the background thread of the appender.
         *
         * @param queuedEvents The number of queued events
         * @return This builder
         */
        public Builder queuedEvents(int queuedEvents) {
            this.queuedEvents = queuedEvents;
            return this;
        }

        /**
         * Sets the documents in the pending batch, waiting to be transmitted or retried.
         *
         * @param pendingDocuments The number of pending documents
         * @return This builder
         */
        public Builder pendingDocuments(int pendingDocuments) {
            this.pendingDocuments = pendingDocuments;
            return this;
        }

        /**
         * Sets the estimated size of the pending batch.
         *
         * @param pendingBytes The estimated pending size, in bytes
         * @return This builder
         */
        public Builder pendingBytes(long pendingBytes) {
            this.pendingBytes = pendingBytes;
            return this;
        }

        /**
         * Sets the requests sent and not answered yet.
         *
         * @param transmissionsInFlight The number of transmissions in flight
         * @return This builder
         */
        public Builder transmissionsInFlight(int transmissionsInFlight) {
            this.transmissionsInFlight = transmissionsInFlight;
            return this;
        }

        /**
         * Sets the documents the downstream system accepted.
         *
         * @param transmittedDocuments The number of documents transmitted
         * @return This builder
         */
        public Builder transmittedDocuments(long transmittedDocuments) {
            this.transmittedDocuments = transmittedDocuments;
            return this;
        }

        /**
         * Sets the transmission attempts that failed, in whole or in part.
         *
         * @param failedTransmissions The number of failed transmission attempts
         * @return This builder
         */
        public Builder failedTransmissions(long failedTransmissions) {
            this.failedTransmissions = failedTransmissions;
            return this;
        }

        /**
         * Sets the documents irrecoverably discarded, whatever the reason.
         *
         * @param droppedDocuments The number of documents dropped
         * @return This builder
         */
        public Builder droppedDocuments(long droppedDocuments) {
            this.droppedDocuments = droppedDocuments;
            return this;
        }

        /**
         * Builds the metrics.
         *
         * @return The new metrics
         */
        public DeliveryMetrics build() {
            return new DeliveryMetrics(this);
        }

    }

}
