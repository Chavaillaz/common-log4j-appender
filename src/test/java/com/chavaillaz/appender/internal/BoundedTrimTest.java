package com.chavaillaz.appender.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("BoundedTrim")
class BoundedTrimTest {

    private static Queue<String> mutable(String... elements) {
        return new ArrayDeque<>(List.of(elements));
    }

    @Test
    @DisplayName("Keeps everything and discards nothing when under the cap")
    void testUnderCapKeepsEverything() {
        Queue<String> elements = mutable("a", "b");

        assertThat(BoundedTrim.trim(elements, 5)).isNull();
        assertThat(List.copyOf(elements)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("Keeps everything and discards nothing when exactly at the cap")
    void testExactlyAtCapKeepsEverything() {
        Queue<String> elements = mutable("a", "b", "c");

        assertThat(BoundedTrim.trim(elements, 3)).isNull();
        assertThat(List.copyOf(elements)).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("Discards the single oldest element when one over the cap")
    void testSingleOverflowDiscardsOldest() {
        Queue<String> elements = mutable("a", "b", "c");

        assertThat(BoundedTrim.trim(elements, 2)).containsExactly("a");
        assertThat(List.copyOf(elements)).containsExactly("b", "c");
    }

    @Test
    @DisplayName("Discards every oldest element in excess, keeping the most recently added ones")
    void testBulkOverflowDiscardsOldestElements() {
        Queue<String> elements = mutable("a", "b", "c", "d", "e");

        assertThat(BoundedTrim.trim(elements, 2)).containsExactly("a", "b", "c");
        assertThat(List.copyOf(elements)).containsExactly("d", "e");
    }

    @Test
    @DisplayName("Discards everything when the cap is zero")
    void testZeroCapDiscardsEverything() {
        Queue<String> elements = mutable("a", "b");

        assertThat(BoundedTrim.trim(elements, 0)).containsExactly("a", "b");
        assertThat(List.copyOf(elements)).isEmpty();
    }

    // Integer.MIN_VALUE is not just a bigger negative: it used to make size - maxSize overflow back into a
    // negative, which read as "nothing to discard" and kept every element instead of discarding them all
    @ParameterizedTest(name = "maxSize = {0}")
    @ValueSource(ints = {-1, -100, Integer.MIN_VALUE + 1, Integer.MIN_VALUE})
    @DisplayName("Treats a negative cap the same as zero, discarding everything, instead of throwing")
    void testNegativeCapDiscardsEverythingWithoutThrowing(int maxSize) {
        Queue<String> elements = mutable("a", "b");

        assertThat(BoundedTrim.trim(elements, maxSize)).containsExactly("a", "b");
        assertThat(List.copyOf(elements)).isEmpty();
    }

    @Test
    @DisplayName("Does not discard anything, or throw, for an empty queue")
    void testEmptyQueueDoesNotThrow() {
        Queue<String> elements = mutable();

        assertThat(BoundedTrim.trim(elements, 3)).isNull();
        assertThat(List.copyOf(elements)).isEmpty();
    }

    @Test
    @DisplayName("Does not throw when the cap is larger than the queue itself")
    void testCapLargerThanQueueDoesNotThrow() {
        Queue<String> elements = mutable("a");

        assertThat(BoundedTrim.trim(elements, 1_000_000)).isNull();
        assertThat(List.copyOf(elements)).containsExactly("a");
    }

    @Test
    @DisplayName("Hands back the discarded elements as an unmodifiable list")
    void testDiscardedElementsAreUnmodifiable() {
        // The listener this list ends up at is documented as free to keep a reference to it, so it must not
        // be a view of, or share anything with, the batch that keeps being trimmed behind its back
        List<String> discarded = BoundedTrim.trim(mutable("a", "b", "c"), 1);

        assertThat(discarded).containsExactly("a", "b");
        assertThatThrownBy(() -> discarded.add("d")).isInstanceOf(UnsupportedOperationException.class);
    }

}
