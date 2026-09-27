package com.chavaillaz.appender;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;

/**
 * How long a {@link Batcher} waits before retrying after a failed transmission, so that a downstream system
 * which is down, overloaded or rate-limiting is not hit again by the very traffic it is struggling with.
 * <p>
 * Consulted once per failed attempt, with the number of consecutive failures the pending batch reached
 * ({@code 1} for the first one). Holding off delays newly accumulated elements too: the point is to stop
 * sending for a while, not to reorder what is sent.
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
        long initialMillis = toMillis(initial);
        long maxMillis = toMillis(max);
        if (initialMillis <= 0) {
            return none();
        }
        long cappedMaxMillis = Math.max(initialMillis, maxMillis);
        return consecutiveFailures -> {
            // Only the low six bits of a shift distance count for a long, and anything that large is past the
            // cap anyway
            int exponent = Math.min(Math.max(consecutiveFailures, 1) - 1, 62);
            long unbounded = initialMillis << exponent;
            // A shift past the width of a long drops the high bits and can leave a small positive value, or 0,
            // which would lift the delay during the long outage it exists for: doubling only ever grows a
            // positive value, so a result below the base delay wrapped, and is past the cap
            boolean overflowed = unbounded < initialMillis;
            long ceiling = overflowed ? cappedMaxMillis : Math.min(unbounded, cappedMaxMillis);
            return ThreadLocalRandom.current().nextLong(ceiling + 1);
        };
    }

    /**
     * Converts the given duration to milliseconds, clamped to a year so that neither
     * {@link Duration#toMillis()} nor the {@code ceiling + 1} bound above can overflow. A year of backoff is
     * already indistinguishable from never retrying.
     *
     * @param duration The duration to convert, or {@code null} for none
     * @return The duration in milliseconds, never negative
     */
    private static long toMillis(@Nullable Duration duration) {
        long maxMillis = Duration.ofDays(365).toMillis();
        if (duration == null) {
            return 0L;
        }
        try {
            return Math.clamp(duration.toMillis(), 0L, maxMillis);
        } catch (ArithmeticException e) {
            // Too large to be expressed in milliseconds, so past the clamp above
            return maxMillis;
        }
    }

    /**
     * Computes how long to wait before the next transmission attempt.
     *
     * @param consecutiveFailures The number of consecutive failed attempts for the pending batch, including
     *                            the one that just failed, so always at least {@code 1}
     * @return The delay in milliseconds, {@code 0} or less meaning "retry at the next opportunity"
     */
    long delayMillis(int consecutiveFailures);

}
