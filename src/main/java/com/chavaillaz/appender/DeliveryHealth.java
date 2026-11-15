package com.chavaillaz.appender;

import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The health of a delivery pipeline, for a health indicator or an alert to poll: what it does about its last
 * transmission attempt, and for how long it delivered nothing. Taken by
 * {@link com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender#getHealth()} for a whole appender, or by
 * {@link LogDelivery#getHealth()} for its delivery alone.
 * <p>
 * A failed attempt is retried: documents are only lost once reported through
 * {@link LogDeliveryListener#onDocumentsDropped(java.util.List, String)}. An outage shows as a status other than
 * {@link Status#UP} along with a time since the last success growing past what the application tolerates.
 * <p>
 * It is not meant for the readiness or liveness probe of the application: an outage of the downstream system
 * would take every instance out of service at once, and an instance out of service logs nothing that could
 * succeed and bring it back.
 * <p>
 * Instances are immutable, and created through {@link #of(Status, Duration)}.
 */
public final class DeliveryHealth {

    private final Status status;
    private final Duration sinceLastSuccess;

    private DeliveryHealth(Status status, Duration sinceLastSuccess) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.sinceLastSuccess = Objects.requireNonNull(sinceLastSuccess, "sinceLastSuccess must not be null");
    }

    /**
     * Creates the health of a pipeline.
     *
     * @param status           What the pipeline does about its last transmission attempt
     * @param sinceLastSuccess The time elapsed since documents were last accepted, or since the pipeline was
     *                         created while none were yet
     * @return The health
     * @throws NullPointerException If one of them is {@code null}
     */
    public static DeliveryHealth of(Status status, Duration sinceLastSuccess) {
        return new DeliveryHealth(status, sinceLastSuccess);
    }

    /**
     * What the pipeline does about its last transmission attempt.
     *
     * @return The status
     */
    public Status status() {
        return status;
    }

    /**
     * The time elapsed since documents were last accepted, or since the pipeline was created while none were
     * yet: it grows while there is nothing to send too, which is why it is read along with the status.
     *
     * @return The time since the last success
     */
    public Duration sinceLastSuccess() {
        return sinceLastSuccess;
    }

    /**
     * Compares the status and the time since the last success.
     *
     * @param other The object to compare with
     * @return {@code true} for a health with the same status and the same time since the last success
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DeliveryHealth health
                && status == health.status
                && sinceLastSuccess.equals(health.sinceLastSuccess);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, sinceLastSuccess);
    }

    /**
     * Gives the status and the time since the last success.
     *
     * @return Both, as {@code DeliveryHealth[status=UP, sinceLastSuccess=PT0S]}
     */
    @Override
    public String toString() {
        return "DeliveryHealth[status=" + status + ", sinceLastSuccess=" + sinceLastSuccess + "]";
    }

    /**
     * What a delivery pipeline does about its last transmission attempt.
     */
    public enum Status {

        /**
         * The last transmission attempt succeeded, or none was made yet.
         */
        UP,

        /**
         * The last transmission attempt failed, in whole or in part, and no backoff holds the next one off: it
         * goes out with the next flush, or with the next document when not batching, retrying what failed unless
         * it was given up on.
         */
        RETRYING,

        /**
         * A transmission attempt failed, and transmissions are held off until the retry backoff it decided
         * elapses (see {@link DeliveryOptions#retryBackoff()}).
         */
        BACKING_OFF,

        /**
         * Nothing is accepted for delivery: the pipeline is not started, could not be created, or is stopping
         * or stopped.
         */
        DOWN

    }

}
