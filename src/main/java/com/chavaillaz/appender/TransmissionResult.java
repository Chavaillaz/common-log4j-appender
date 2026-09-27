package com.chavaillaz.appender;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of one transmission attempt by the transport function of a {@link Batcher}.
 * <p>
 * A {@link PartialFailure} tells that only some elements were rejected, a malformed document in a bulk
 * response for instance, rather than the whole request failing, so that only those are retried and counted
 * against the limit of consecutive failures.
 * <p>
 * Its elements are matched against the request <strong>by reference, not {@code equals()}</strong>, equal
 * documents being common (a repeated message for instance): report the very instances the transport was
 * given, not equal copies.
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
