package com.chavaillaz.appender.internal;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;

/**
 * How long a {@link Batcher} waits before retrying after a failed transmission, so that a downstream system
 * which is down, overloaded or rate-limiting is not hit again by the very traffic it is struggling with.
 * <p>
 * Consulted once per failed attempt, with the highest failure streak among the elements it puts back for a
 * retry ({@code 1} after their first failure). Holding off delays newly accumulated elements too: the point is
 * to stop sending for a while, not to reorder what is sent.
 *
 * @see #exponential(Duration, Duration)
 */
@FunctionalInterface
public interface BackoffPolicy {

    /**
     * Never waits: a retry is attempted at the next opportunity (the next flush, or the next element with
     * batching disabled).
     *
     * @return A policy that never delays a retry
     */
    static BackoffPolicy none() {
        return consecutiveFailures -> 0L;
    }

    /**
     * Waits {@code initial × 2^(consecutiveFailures - 1)}, capped at {@code max}, with full jitter: the
     * actual delay is picked uniformly at random in {@code [0, thatValue]}, so that application instances
     * losing the same downstream system at the same moment do not all retry in lockstep.
     *
     * @param initial The base delay after a first failure, {@link Duration#ZERO} or less (or {@code null})
     *                disabling the policy entirely (equivalent to {@link #none()})
     * @param max     The cap the delay never grows past, ignored if smaller than {@code initial} or
     *                {@code null}
     * @return An exponential backoff policy with full jitter
     */
    static BackoffPolicy exponential(@Nullable Duration initial, @Nullable Duration max) {
        long initialMillis = initial != null ? Durations.toMillisWithinYear(initial) : 0;
        long maxMillis = max != null ? Durations.toMillisWithinYear(max) : 0;
        if (initialMillis <= 0) {
            return none();
        }
        long cappedMaxMillis = Math.max(initialMillis, maxMillis);
        return consecutiveFailures -> {
            int exponent = Math.max(consecutiveFailures, 1) - 1;
            // Doubled while it fits in a long, as it does shifted by fewer places than it has leading zeros:
            // shifted further, it drops high bits and can leave any value, 0 included, where it is past the cap
            long ceiling = exponent < Long.numberOfLeadingZeros(initialMillis)
                    ? Math.min(initialMillis << exponent, cappedMaxMillis)
                    : cappedMaxMillis;
            return ThreadLocalRandom.current().nextLong(ceiling + 1);
        };
    }

    /**
     * Computes how long to wait before the next transmission attempt.
     *
     * @param consecutiveFailures The highest number of consecutive failed attempts among the elements put back
     *                            for a retry, the one that just failed included, so at least {@code 1}
     * @return The delay in milliseconds, {@code 0} or less meaning "retry at the next opportunity"
     */
    long delayMillis(int consecutiveFailures);

}
