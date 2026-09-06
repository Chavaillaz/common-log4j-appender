package com.chavaillaz.appender;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Observes the outcome of log transmissions performed by a {@link LogDelivery}, so the health of the
 * delivery pipeline (throughput, latency, failures, data loss) can be monitored, e.g. by forwarding these
 * events to a metrics library. Every method has a no-op default; override only the ones you need.
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
     * Called after a batch of documents failed to be transmitted. Not necessarily lost: unless
     * {@link #onDocumentsDropped(List, String)} is also called for the same documents, they were kept for a
     * later retry.
     *
     * @param documentCount The number of documents in the batch
     * @param cause         The exception responsible for the failure, or {@code null} if the implementation
     *                      of {@code sendBulk} simply returned {@code false} without throwing
     */
    default void onTransmissionFailed(int documentCount, @Nullable Throwable cause) {
        // No-op by default
    }

    /**
     * Called when documents are irrecoverably discarded instead of kept for a later retry: a batch that
     * kept failing, one of the pending or queued limits reached, or a delivery closing on documents it
     * never managed to send. {@code reason} says which. Unlike the other methods here, the actual documents
     * are passed (as an unmodifiable list) rather than just a count, since this is a last chance to save
     * them, e.g. to a local file or a dead-letter queue.
     *
     * @param documents The documents being discarded
     * @param reason    A short, human-readable explanation of why the documents were discarded
     */
    default void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
        // No-op by default
    }

}
