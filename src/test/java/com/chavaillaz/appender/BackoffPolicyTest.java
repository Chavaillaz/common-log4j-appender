package com.chavaillaz.appender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(0, policy.delayMillis(1));
        assertEquals(0, policy.delayMillis(100));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -1000})
    @DisplayName("Falls back to never delaying when the initial delay is not positive")
    void testNonPositiveInitialDelayDisablesBackoff(int initialMillis) {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofMillis(initialMillis), Duration.ofSeconds(30));

        assertEquals(0, policy.delayMillis(1));
        assertEquals(0, policy.delayMillis(5));
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

    @Test
    @DisplayName("Stays within the cap for a failure count large enough to overflow the exponential")
    void testHugeFailureCountStaysWithinTheCap() {
        BackoffPolicy policy = BackoffPolicy.exponential(Duration.ofSeconds(1), Duration.ofSeconds(30));

        // when/then: a shift by this much would overflow a long several times over; the delay must still be
        // a sane value within the cap rather than a wrapped-around (possibly negative) one
        for (int consecutiveFailures : new int[] {62, 63, 64, 1_000, Integer.MAX_VALUE}) {
            long delay = policy.delayMillis(consecutiveFailures);
            assertTrue(delay >= 0 && delay <= 30_000,
                    "Delay for " + consecutiveFailures + " failures should be within [0, 30000] but was " + delay);
        }
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
            assertTrue(delay >= 0 && delay <= ceiling,
                    "Delay should be within [0, " + ceiling + "] but was " + delay);
            max = Math.max(max, delay);
        }
        // Sampling 500 times, the odds of never exceeding half the ceiling by chance are negligible; this
        // catches a policy that silently degenerated to a constant (or a much smaller range) instead.
        assertTrue(max > ceiling / 2, "Delays should be spread across the range up to " + ceiling + ", but never exceeded " + max);
    }

}
