package com.chavaillaz.appender;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Observes the outcome of transmissions performed by a {@link Batcher}. The generic, transport-agnostic
 * counterpart of {@link LogDeliveryListener}: {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery}
 * bridges the two, forwarding every call here to whatever {@link LogDeliveryListener} it was configured
 * with. Every method has a no-op default; override only the ones you need.
 *
 * @param <T> The type of element transmitted
 */
public interface BatcherListener<T> {

    /**
     * A listener doing nothing, used as the default when none is configured. The unchecked cast is safe, no
     * method touching an element.
     *
     * @param <T> The type of element transmitted
     * @return A listener implementing every method as a no-op
     */
    @SuppressWarnings("unchecked")
    static <T> BatcherListener<T> noop() {
        return (BatcherListener<T>) NOOP;
    }

    /**
     * Backing instance for {@link #noop()}.
     */
    BatcherListener<Object> NOOP = new BatcherListener<>() {
        // Uses only the default no-op implementations below
    };

    /**
     * Called after a batch of elements has been transmitted successfully.
     *
     * @param count          The number of elements in the batch
     * @param durationMillis The time taken by the transmission, in milliseconds
     */
    default void onSucceeded(int count, long durationMillis) {
        // No-op by default
    }

    /**
     * Called after a batch of elements failed to be transmitted. Not necessarily lost: unless
     * {@link #onDropped(List, String)} is also called for the same elements, they were kept for a later
     * retry.
     *
     * @param count The number of elements in the batch
     * @param cause The exception responsible for the failure, or {@code null} if none
     */
    default void onFailed(int count, @Nullable Throwable cause) {
        // No-op by default
    }

    /**
     * Called when elements are irrecoverably discarded instead of kept for a later retry.
     *
     * @param dropped The elements being discarded, as an unmodifiable list the implementation is free to
     *                keep a reference to (this being its last chance to save them somewhere)
     * @param reason  A short, human-readable explanation of why they were discarded
     */
    default void onDropped(List<T> dropped, String reason) {
        // No-op by default
    }

}
