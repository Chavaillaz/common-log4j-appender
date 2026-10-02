package com.chavaillaz.appender;

import java.util.List;

/**
 * A snapshot of a delivery pipeline for a metrics library to poll: how much it holds right now, for gauges, and
 * what it did since it started, for counters, which only ever grow. Taken by
 * {@link com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender#getMetrics()} for a whole appender, or by
 * {@link LogDelivery#getMetrics()} for its delivery alone.
 *
 * @param queuedEvents          Logging events waiting for the background thread of the appender, {@code 0} for a
 *                              delivery on its own
 * @param pendingDocuments      Documents in the pending batch, waiting to be transmitted or retried
 * @param pendingBytes          Estimated size of the pending batch, {@code 0} while no byte limit is in effect
 * @param transmissionsInFlight Requests sent and not answered yet
 * @param transmittedDocuments  Documents the downstream system accepted
 * @param failedTransmissions   Transmission attempts that failed, in whole or in part, their documents retried or
 *                              not
 * @param droppedDocuments      Documents irrecoverably discarded, whatever the reason, those of the events the
 *                              appender discarded included: each reported through
 *                              {@link LogDeliveryListener#onDocumentsDropped(List, String)}, unless too many were
 *                              already waiting to be reported, or the appender has no delivery to report to
 */
public record DeliveryMetrics(
        int queuedEvents,
        int pendingDocuments,
        long pendingBytes,
        int transmissionsInFlight,
        long transmittedDocuments,
        long failedTransmissions,
        long droppedDocuments) {

    /**
     * The metrics of a pipeline that tracks none, every value {@code 0}.
     */
    public static final DeliveryMetrics NONE = new DeliveryMetrics(0, 0, 0, 0, 0, 0, 0);

}
