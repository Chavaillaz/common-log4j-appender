package com.chavaillaz.appender;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How long a {@link Batcher} waits before retrying after a failed transmission, so a downstream system that
 * is down, overloaded or rate-limiting is not hit again immediately (and then again, and again) by the very
 * traffic it is already struggling with.
 * <p>
 * Without this, the retry cadence is dictated purely by how fast logs happen to arrive: with batching
 * disabled ({@code FlushThreshold} of {@code 1}), every single log line reaching a dead endpoint produces
 * another request against it; with batching enabled, every flush interval does. Neither backs off, so an
 * outage is met with a constant (or traffic-proportional) request rate for its whole duration, which is
 * exactly what turns a brief downstream hiccup into a longer one.
 * <p>
 * A policy is consulted once per failed attempt, with the number of consecutive failures that attempt just
 * brought the pending batch to ({@code 1} for the first failure), and returns how long to hold off before
 * the next attempt. Holding off delays newly accumulated elements too, which is deliberate: the point is to
 * stop sending for a while, not just to reorder what is sent.
 *
 * @see #exponential(Duration, Duration)
 */
@FunctionalInterface
public interface BackoffPolicy {

    /**
     * Never waits: a retry is attempted at the next opportunity (the next flush, or the next element with
     * batching disabled), which is the behavior of every version of this library before this interface
     * existed.
     *
     * @return A policy that never delays a retry
     */
    static BackoffPolicy none() {
        return consecutiveFailures -> 0L;
    }

    /**
     * Waits {@code initial × 2^(consecutiveFailures - 1)}, capped at {@code max}, with full jitter (the
     * actual delay is picked uniformly at random in {@code [0, thatValue]}).
     * <p>
     * The jitter matters more than the exponential growth itself here: a fleet of application instances all
     * losing the same downstream system at the same moment would otherwise all retry in lockstep, rebuilding
     * the very thundering herd the backoff exists to avoid, and would keep doing so on every subsequent
     * attempt since their delays stay identical.
     *
     * @param initial The base delay after a first failure, {@link Duration#ZERO} or less disabling the
     *                policy entirely (equivalent to {@link #none()})
     * @param max     The cap the delay never grows past, ignored if smaller than {@code initial}
     * @return An exponential backoff policy with full jitter
     */
    static BackoffPolicy exponential(Duration initial, Duration max) {
        long initialMillis = initial != null ? initial.toMillis() : 0L;
        long maxMillis = max != null ? max.toMillis() : 0L;
        if (initialMillis <= 0) {
            return none();
        }
        long cappedMaxMillis = Math.max(initialMillis, maxMillis);
        return consecutiveFailures -> {
            // Shifting by 62 or more would overflow (or, past 63, wrap around to a nonsensical small value),
            // and any exponent that large is already far past the cap anyway.
            int exponent = Math.min(Math.max(consecutiveFailures, 1) - 1, 62);
            long unbounded = initialMillis << exponent;
            long ceiling = unbounded < 0 ? cappedMaxMillis : Math.min(unbounded, cappedMaxMillis);
            return ThreadLocalRandom.current().nextLong(ceiling + 1);
        };
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
