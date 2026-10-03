package com.chavaillaz.appender.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Dynamic limits")
class DynamicLimitsTest {

    @Test
    @DisplayName("Reads each limit from its own supplier, again on every use")
    void testLimitsAreReadFromTheirSupplierOnEveryUse() {
        // Given: a distinct value per limit, all moving together
        AtomicInteger base = new AtomicInteger(10);
        DynamicLimits limits = new DynamicLimits(
                () -> base.get() + 1, () -> base.get() + 2, () -> base.get() + 3, () -> base.get() + 4,
                () -> base.get() + 5L, () -> base.get() + 6L);

        // When
        base.set(20);

        // Then
        assertThat(limits.flushThreshold()).isEqualTo(21);
        assertThat(limits.maxPendingElements()).isEqualTo(22);
        assertThat(limits.maxRequestElements()).isEqualTo(23);
        assertThat(limits.maxConsecutiveFailures()).isEqualTo(24);
        assertThat(limits.maxPendingBytes()).isEqualTo(25L);
        assertThat(limits.maxRequestBytes()).isEqualTo(26L);
    }

    @Test
    @DisplayName("Leaves a limit whose supplier throws unenforced rather than discarding over it")
    void testThrowingSupplierLeavesTheLimitUnenforced() {
        // Given: suppliers failing as a configuration resolving its values on demand can
        IntSupplier count = () -> {
            throw new IllegalStateException("Unable to read the configuration");
        };
        LongSupplier bytes = () -> {
            throw new IllegalStateException("Unable to read the configuration");
        };
        DynamicLimits limits = new DynamicLimits(count, count, count, count, bytes, bytes);

        // Then: unbounded, and never giving up on an element for the consecutive failures
        assertThat(limits.flushThreshold()).isEqualTo(Integer.MAX_VALUE);
        assertThat(limits.maxPendingElements()).isEqualTo(Integer.MAX_VALUE);
        assertThat(limits.maxRequestElements()).isEqualTo(Integer.MAX_VALUE);
        assertThat(limits.maxConsecutiveFailures()).isZero();
        assertThat(limits.maxPendingBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat(limits.maxRequestBytes()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("Leaves a limit unenforced when its supplier throws an Error")
    void testSupplierThrowingAnErrorLeavesTheLimitUnenforced() {
        // Given: a supplier reading a structure that refers to itself
        IntSupplier count = () -> {
            throw new StackOverflowError();
        };
        DynamicLimits limits = new DynamicLimits(() -> 1, count, () -> 1, () -> 1, () -> 1L, () -> 1L);

        // Then
        assertThat(limits.maxPendingElements()).isEqualTo(Integer.MAX_VALUE);
    }

}
