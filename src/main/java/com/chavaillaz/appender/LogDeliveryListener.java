package com.chavaillaz.appender;

import java.util.List;
import java.util.Map;

/**
 * Observes the outcome of log transmissions performed by a {@link LogDelivery}, so that the health of the
 * delivery pipeline (throughput, latency, failures, data loss) can be monitored by the application using
 * it (for example by forwarding these events to a metrics library).
 * <p>
 * All methods have a no-op default implementation: implementations only need to override the ones they
 * are interested in.
 */
public interface LogDeliveryListener {

    /**
     * A listener doing nothing, used as the default when none is configured.
     */
    LogDeliveryListener NOOP = new LogDeliveryListener() {
        // Uses only the default no-op implementations below
    };

    /**
     * Called after a batch of documents has been transmitted successfully.
     *
     * @param documentCount  The number of documents in the batch
     * @param durationMillis The time taken by the transmission, in milliseconds
     */
    default void onTransmissionSucceeded(int documentCount, long durationMillis) {
        // No-op by default
    }

    /**
     * Called after a batch of documents failed to be transmitted. The batch is not necessarily lost: unless
     * {@link #onDocumentsDropped(List, String)} is also called for the same documents, they have been kept
     * for a later retry.
     *
     * @param documentCount The number of documents in the batch
     * @param cause         The exception responsible for the failure, or {@code null} if the implementation
     *                      of {@code sendBulk} simply returned {@code false} without throwing
     */
    default void onTransmissionFailed(int documentCount, Throwable cause) {
        // No-op by default
    }

    /**
     * Called when documents are irrecoverably discarded instead of being kept for a later retry, either
     * because a batch kept failing for too many consecutive attempts (possibly because of a single
     * document that can never be transmitted successfully) or because the amount of documents pending
     * retry exceeded the maximum allowed batch size.
     * <p>
     * Unlike the other methods of this interface, the actual documents are passed (as an unmodifiable list)
     * rather than just a count, since this is meant as a last chance to save them from being lost entirely,
     * for example by writing them to a local file or a dead-letter queue.
     *
     * @param documents The documents being discarded
     * @param reason    A short, human-readable explanation of why the documents were discarded
     */
    default void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
        // No-op by default
    }

}
