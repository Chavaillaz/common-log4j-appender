package com.chavaillaz.appender;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of one transmission attempt by a {@link Batcher}'s transport function.
 * <p>
 * {@link PartialFailure} lets a transport distinguish "some elements of this chunk were rejected" (e.g. one
 * malformed document in an otherwise-fine Elasticsearch bulk response) from "the whole chunk failed" (e.g.
 * the downstream system is unreachable): only the rejected elements are requeued for retry and counted
 * toward the consecutive-failure limit, instead of the whole chunk being retried (or given up on) for the
 * sake of one bad element.
 * <p>
 * {@code rejected} is matched back against the dispatched chunk <strong>by reference identity, not
 * {@code equals()}</strong>: elements are often plain data (e.g. {@code Map<String,Object>} log documents),
 * and two content-identical-but-distinct elements are common (repeated heartbeat messages, for instance);
 * equals-based matching would silently misattribute which ones are retried or dropped. Report the exact
 * object references received by the transport function, not equal copies.
 *
 * @param <T> The type of element transmitted
 */
public sealed interface TransmissionResult<T> {

    /**
     * The whole batch was transmitted successfully.
     *
     * @param <T> The type of element transmitted
     * @return A successful result
     */
    static <T> TransmissionResult<T> success() {
        return new Success<>();
    }

    /**
     * The whole batch failed to be transmitted.
     *
     * @param cause The exception responsible for the failure, or {@code null} if the transport simply
     *              reported failure without throwing
     * @param <T>   The type of element transmitted
     * @return A failed result
     */
    static <T> TransmissionResult<T> failure(@Nullable Throwable cause) {
        return new Failure<>(cause);
    }

    /**
     * Part of the batch was rejected while the rest was accepted.
     *
     * @param rejected The elements rejected, matched back against the dispatched chunk by reference
     *                 identity (see the interface documentation)
     * @param cause    The exception responsible for the rejection, or {@code null} if the transport simply
     *                 reported which elements were rejected without throwing
     * @param <T>      The type of element transmitted
     * @return A partially failed result
     */
    static <T> TransmissionResult<T> partialFailure(List<T> rejected, @Nullable Throwable cause) {
        return new PartialFailure<>(List.copyOf(rejected), cause);
    }

    /**
     * The whole batch was transmitted successfully.
     *
     * @param <T> The type of element transmitted
     */
    record Success<T>() implements TransmissionResult<T> {

    }

    /**
     * The whole batch failed to be transmitted.
     *
     * @param cause The exception responsible for the failure, or {@code null} if none
     * @param <T>   The type of element transmitted
     */
    record Failure<T>(@Nullable Throwable cause) implements TransmissionResult<T> {

    }

    /**
     * Part of the batch was rejected while the rest was accepted.
     *
     * @param rejected The elements rejected, matched back against the dispatched chunk by reference
     *                 identity (see the interface documentation)
     * @param cause    The exception responsible for the rejection, or {@code null} if none
     * @param <T>      The type of element transmitted
     */
    record PartialFailure<T>(List<T> rejected, @Nullable Throwable cause) implements TransmissionResult<T> {

    }

}
