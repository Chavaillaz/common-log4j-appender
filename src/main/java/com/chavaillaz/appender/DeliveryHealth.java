package com.chavaillaz.appender;

import java.time.Duration;
import java.util.Objects;

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
 *
 * @param status           What the pipeline does about its last transmission attempt
 * @param sinceLastSuccess The time elapsed since documents were last accepted, or since the pipeline was created
 *                         while none were yet: it grows while there is nothing to send too, which is why it is
 *                         read along with the status
 */
public record DeliveryHealth(Status status, Duration sinceLastSuccess) {

    /**
     * Validates both components.
     *
     * @throws NullPointerException If one of them is {@code null}
     */
    public DeliveryHealth {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(sinceLastSuccess, "sinceLastSuccess must not be null");
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
