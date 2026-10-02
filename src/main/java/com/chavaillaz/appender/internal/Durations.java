package com.chavaillaz.appender.internal;

import java.time.Duration;

/**
 * Converts the durations the engine is given to milliseconds within a year, so that neither a deadline computed
 * from one nor the bound a backoff delay is drawn below can overflow: a year is already indistinguishable from
 * waiting, or holding retries off, indefinitely.
 */
final class Durations {

    /**
     * The most milliseconds a duration converts to, a year.
     */
    private static final long MAX_MILLIS = Duration.ofDays(365).toMillis();

    private Durations() {
        // Static methods only
    }

    /**
     * Converts the given duration to milliseconds, clamped to {@code [0, MAX_MILLIS]} rather than letting
     * {@link Duration#toMillis()} throw for one too long to count in milliseconds.
     *
     * @param duration The duration to convert
     * @return The duration in milliseconds, between {@code 0} and {@link #MAX_MILLIS}
     */
    static long toMillisWithinYear(Duration duration) {
        try {
            return Math.clamp(duration.toMillis(), 0, MAX_MILLIS);
        } catch (ArithmeticException e) {
            // Too long to count in milliseconds, so past one end of the clamp
            return duration.isNegative() ? 0 : MAX_MILLIS;
        }
    }

}
