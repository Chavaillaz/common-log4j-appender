package com.chavaillaz.appender.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers {@link BackoffPolicy}, in particular the arithmetic corners of
 * {@link BackoffPolicy#exponential(Duration, Duration)}: a delay doubling per failure reaches numbers big
 * enough to overflow a {@code long} shift within a few dozen attempts, and a batch retried that many times
 * is exactly the situation the cap exists for, so it must not be the one where the delay silently wraps
 * around to something tiny (or negative).
 */
@DisplayName("BackoffPolicy")
class BackoffPolicyTest {

    @Test
    @DisplayName("Never delays with the no-op policy")
    void testNoneNeverDelays() {
        BackoffPolicy policy = BackoffPolicy.none();

        assertThat(policy.delayMillis(1)).isZero();
        assertThat(policy.delayMillis(100)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -1000})
    @DisplayName("Falls back to never delaying when the initial delay is not positive")
    void testNonPositiveInitialDelayDisablesBackoff(int initialMillis) {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofMillis(initialMillis), Duration.ofSeconds(30));

        assertThat(policy.delayMillis(1)).isZero();
        assertThat(policy.delayMillis(5)).isZero();
    }

    @Test
    @DisplayName("Grows the delay ceiling exponentially, capped at the maximum")
    void testDelayGrowsExponentiallyUpToTheCap() {
        // given: full jitter means only the ceiling is deterministic, so each attempt is sampled enough
        // times for its bounds to be meaningful rather than accidental
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofMillis(100), Duration.ofMillis(800));

        // then: the ceiling doubles per consecutive failure (100, 200, 400) and then stops at the cap
        assertCeiling(policy, 1, 100);
        assertCeiling(policy, 2, 200);
        assertCeiling(policy, 3, 400);
        assertCeiling(policy, 4, 800);
        assertCeiling(policy, 5, 800);
    }

    @ParameterizedTest
    @ValueSource(ints = {62, 63, 64, 1_000, Integer.MAX_VALUE})
    @DisplayName("Keeps backing off at the cap for a failure count large enough to overflow the exponential")
    void testHugeFailureCountStaysAtTheCap(int consecutiveFailures) {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofSeconds(1), Duration.ofSeconds(30));

        // when/then: a shift by this much overflows a long several times over, and the truncated result is
        // not necessarily negative — a one second base delay wraps to exactly 0 here. Asserting only an
        // upper bound would accept that, so the ceiling is asserted in full: a batch that failed this many
        // times is deep into an outage, which is when retrying without any delay at all hurts the most.
        assertCeiling(policy, consecutiveFailures, 30_000);
    }

    @Test
    @DisplayName("Keeps backing off at the cap when doubling a base delay of months overflows")
    void testOverflowOfALongBaseDelayStaysAtTheCap() {
        // given: a base delay of 2^33 + 5 ms, about 99 days, whose 31st doubling drops its top bit and leaves
        // 5 x 2^31 ms, about 124 days: a wrapped value above the base delay, which comparing with it misses
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofMillis((1L << 33) + 5), Duration.ofDays(365));

        // when/then: the delay after 32 failures reaches the cap rather than stopping at the wrapped value
        assertCeiling(policy, 32, Duration.ofDays(365).toMillis());
    }

    @Test
    @DisplayName("Keeps backing off at the cap across every consecutive failure count in a long outage")
    void testEveryFailureCountKeepsSomeDelay() {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofSeconds(1), Duration.ofSeconds(30));

        // when/then: walks the whole range rather than a few hand-picked points, since which counts
        // overflow depends on the base delay; none of them may silently drop back to no delay at all
        for (int consecutiveFailures = 1; consecutiveFailures < 200; consecutiveFailures++) {
            long ceiling = 0;
            for (int i = 0; i < 200; i++) {
                ceiling = Math.max(ceiling, policy.delayMillis(consecutiveFailures));
            }
            assertThat(ceiling)
                    .as("Delay for " + consecutiveFailures + " failures collapsed to no delay at all")
                    .isPositive();
            assertThat(ceiling)
                    .as("Delay for " + consecutiveFailures + " failures exceeded the cap: " + ceiling)
                    .isLessThanOrEqualTo(30_000);
        }
    }

    @Test
    @DisplayName("Tolerates durations too large to be expressed in milliseconds")
    void testDurationsTooLargeToConvertAreClamped() {
        // given: Duration.toMillis() throws rather than saturating past roughly 292 million years, which
        // must not propagate out of building a policy (nor overflow the jitter bound derived from it)
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE));

        assertThat(policy.delayMillis(1)).isNotNegative();
        assertThat(policy.delayMillis(Integer.MAX_VALUE)).isNotNegative();
    }

    @Test
    @DisplayName("Disables backoff for an initial delay too far below zero to be expressed in milliseconds")
    void testDurationsTooNegativeToConvertDisableBackoff() {
        // given: as far below zero as a duration goes, where Duration.toMillis() throws as well
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofSeconds(Long.MIN_VALUE), Duration.ofSeconds(30));

        // then: negative, so no backoff at all, rather than the year a large positive one is clamped to
        assertThat(policy.delayMillis(1)).isZero();
        assertThat(policy.delayMillis(5)).isZero();
    }

    @Test
    @DisplayName("Falls back to never delaying when the initial delay is null")
    void testNullInitialDelayDisablesBackoff() {
        BackoffPolicy policy = BackoffPolicy.exponential(null, Duration.ofSeconds(30));

        assertThat(policy.delayMillis(1)).isZero();
        assertThat(policy.delayMillis(5)).isZero();
    }

    @Test
    @DisplayName("Treats a null maximum as no cap beyond the initial delay")
    void testNullMaximumFallsBackToTheInitialDelay() {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofMillis(100), null);

        assertCeiling(policy, 1, 100);
        assertCeiling(policy, 10, 100);
    }

    @Test
    @DisplayName("Treats a maximum smaller than the initial delay as the initial delay")
    void testMaximumSmallerThanInitialIsIgnored() {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofMillis(500), Duration.ofMillis(100));

        assertCeiling(policy, 1, 500);
        assertCeiling(policy, 10, 500);
    }

    @Test
    @DisplayName("Clamps a failure count below one to the first attempt")
    void testFailureCountBelowOneIsClampedToTheFirstAttempt() {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofMillis(100), Duration.ofSeconds(30));

        assertCeiling(policy, 0, 100);
        assertCeiling(policy, -5, 100);
    }

    /**
     * Asserts that the delays produced for the given failure count all fall within {@code [0, ceiling]}, and
     * that they are actually jittered rather than always returning the ceiling itself.
     *
     * @param policy              The policy to sample
     * @param consecutiveFailures The failure count to sample it at
     * @param ceiling             The expected upper bound of the delay, in milliseconds
     */
    private static void assertCeiling(BackoffPolicy policy, int consecutiveFailures, long ceiling) {
        long max = -1;
        for (int i = 0; i < 500; i++) {
            long delay = policy.delayMillis(consecutiveFailures);
            assertThat(delay)
                    .as("Delay should be within [0, " + ceiling + "] but was " + delay)
                    .isBetween(0L, ceiling);
            max = Math.max(max, delay);
        }
        // Sampling 500 times, the odds of never exceeding half the ceiling by chance are negligible; this
        // catches a policy that silently degenerated to a constant (or a much smaller range) instead.
        assertThat(max)
                .as("Delays should be spread across the range up to " + ceiling + ", but never exceeded " + max)
                .isGreaterThan(ceiling / 2);
    }

}
