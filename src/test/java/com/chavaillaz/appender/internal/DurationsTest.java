package com.chavaillaz.appender.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Durations")
class DurationsTest {

    private static final long ONE_YEAR_MILLIS = Duration.ofDays(365).toMillis();

    @Test
    @DisplayName("Converts a duration within a year to as many milliseconds")
    void testConvertsWithinAYear() {
        assertThat(Durations.toMillisWithinYear(Duration.ZERO)).isZero();
        assertThat(Durations.toMillisWithinYear(Duration.ofSeconds(1))).isEqualTo(1_000L);
        assertThat(Durations.toMillisWithinYear(Duration.ofDays(365))).isEqualTo(ONE_YEAR_MILLIS);
    }

    @Test
    @DisplayName("Clamps a duration past a year, or below zero, to the nearest of both")
    void testClampsPastEitherEnd() {
        assertThat(Durations.toMillisWithinYear(Duration.ofDays(1_000))).isEqualTo(ONE_YEAR_MILLIS);
        assertThat(Durations.toMillisWithinYear(Duration.ofSeconds(-1))).isZero();
    }

    @Test
    @DisplayName("Clamps a duration too long to count in milliseconds rather than throwing")
    void testClampsWhatMillisecondsCannotCount() {
        assertThat(Durations.toMillisWithinYear(Duration.ofSeconds(Long.MAX_VALUE))).isEqualTo(ONE_YEAR_MILLIS);
        assertThat(Durations.toMillisWithinYear(Duration.ofSeconds(Long.MIN_VALUE))).isZero();
    }

}
