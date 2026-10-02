package com.chavaillaz.appender;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Observes the outcome of log transmissions performed by a {@link LogDelivery}, so the health of the
 * delivery pipeline (throughput, latency, failures, data loss) can be monitored, e.g. by forwarding these
 * events to a metrics library. Every method has a no-op default; override only the ones you need.
 * <p>
 * Methods are called concurrently, from the threads transmitting, the background threads of the appender and
 * the thread stopping it, never from a thread while it logs: an implementation must be thread-safe, and should
 * return quickly, a stop waiting for its last notifications whatever its budget.
 */
public interface LogDeliveryListener {

    /**
     * A listener doing nothing, used as the default when none is configured.
     */
    LogDeliveryListener NOOP = new LogDeliveryListener() {
        // Uses only the default no-op implementations below
    };

    /**
     * Combines the given listeners into one notifying each of them in turn, so that a delivery taking a single
     * listener can feed a dead-letter sink and a metrics library at once, for instance. One failing does not
     * keep the next ones from being notified: its failure is thrown once all of them were, a later one being
     * logged.
     *
     * @param listeners The listeners to notify, in order
     * @return A listener notifying all of them, {@link #NOOP} for none, or the listener itself for one
     * @throws NullPointerException If one of the listeners is {@code null}
     */
    static LogDeliveryListener compose(LogDeliveryListener... listeners) {
        List<LogDeliveryListener> all = List.of(listeners);
        return switch (all.size()) {
            case 0 -> NOOP;
            case 1 -> all.getFirst();
            default -> new CompositeLogDeliveryListener(all);
        };
    }

    /**
     * Called after a request was accepted, in whole, or in part for a partial failure (see
     * {@link TransmissionResult#partialFailure(List, Throwable)}), which calls
     * {@link #onTransmissionFailed(int, Throwable)} for the rest.
     *
     * @param documentCount  The number of documents accepted
     * @param durationMillis The time taken by the transmission, in milliseconds
     */
    default void onTransmissionSucceeded(int documentCount, long durationMillis) {
        // No-op by default
    }

    /**
     * Called after a request failed, in whole, or in part for a partial failure, which calls
     * {@link #onTransmissionSucceeded(int, long)} for the documents accepted. Not necessarily lost: unless
     * {@link #onDocumentsDropped(List, String)} is also called for the same documents, they were kept for a
     * later retry.
     *
     * @param documentCount The number of documents that failed
     * @param cause         The exception responsible for the failure, or {@code null} if the transmission
     *                      reported a failure without one
     */
    default void onTransmissionFailed(int documentCount, @Nullable Throwable cause) {
        // No-op by default
    }

    /**
     * Called when documents are discarded for good instead of kept for a later retry, as {@code reason} says:
     * they kept failing, a pending or queued limit was reached, an event failed to convert, or a delivery closed
     * before sending them or before their transmission was answered. The documents themselves are passed, as
     * an unmodifiable list, this being the last chance to save them, to a local file or a dead-letter queue for
     * instance.
     *
     * @param documents The documents being discarded
     * @param reason    A short, human-readable explanation of why the documents were discarded
     */
    default void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
        // No-op by default
    }

}
