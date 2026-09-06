package com.chavaillaz.appender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("BoundedTrim")
class BoundedTrimTest {

    private static List<String> mutable(String... elements) {
        return new ArrayList<>(List.of(elements));
    }

    @Test
    @DisplayName("Keeps everything and discards nothing when under the cap")
    void testUnderCapKeepsEverything() {
        List<String> elements = mutable("a", "b");

        assertNull(BoundedTrim.trim(elements, 5));
        assertEquals(List.of("a", "b"), elements);
    }

    @Test
    @DisplayName("Keeps everything and discards nothing when exactly at the cap")
    void testExactlyAtCapKeepsEverything() {
        List<String> elements = mutable("a", "b", "c");

        assertNull(BoundedTrim.trim(elements, 3));
        assertEquals(List.of("a", "b", "c"), elements);
    }

    @Test
    @DisplayName("Discards the single oldest element when one over the cap")
    void testSingleOverflowDiscardsOldest() {
        List<String> elements = mutable("a", "b", "c");

        assertEquals(List.of("a"), BoundedTrim.trim(elements, 2));
        assertEquals(List.of("b", "c"), elements);
    }

    @Test
    @DisplayName("Discards every oldest element in excess, keeping the most recently added ones")
    void testBulkOverflowDiscardsOldestElements() {
        List<String> elements = mutable("a", "b", "c", "d", "e");

        assertEquals(List.of("a", "b", "c"), BoundedTrim.trim(elements, 2));
        assertEquals(List.of("d", "e"), elements);
    }

    @Test
    @DisplayName("Discards everything when the cap is zero")
    void testZeroCapDiscardsEverything() {
        List<String> elements = mutable("a", "b");

        assertEquals(List.of("a", "b"), BoundedTrim.trim(elements, 0));
        assertEquals(List.of(), elements);
    }

    // Integer.MIN_VALUE is not just a bigger negative: it used to make size - maxSize overflow back into a
    // negative, which read as "nothing to discard" and kept every element instead of discarding them all
    @ParameterizedTest(name = "maxSize = {0}")
    @ValueSource(ints = {-1, -100, Integer.MIN_VALUE + 1, Integer.MIN_VALUE})
    @DisplayName("Treats a negative cap the same as zero, discarding everything, instead of throwing")
    void testNegativeCapDiscardsEverythingWithoutThrowing(int maxSize) {
        List<String> elements = mutable("a", "b");

        assertEquals(List.of("a", "b"), BoundedTrim.trim(elements, maxSize));
        assertEquals(List.of(), elements);
    }

    @Test
    @DisplayName("Does not discard anything, or throw, for an empty list")
    void testEmptyListDoesNotThrow() {
        List<String> elements = mutable();

        assertNull(BoundedTrim.trim(elements, 3));
        assertEquals(List.of(), elements);
    }

    @Test
    @DisplayName("Does not throw when the cap is larger than the list itself")
    void testCapLargerThanListDoesNotThrow() {
        List<String> elements = mutable("a");

        assertNull(BoundedTrim.trim(elements, 1_000_000));
        assertEquals(List.of("a"), elements);
    }

    @Test
    @DisplayName("Hands back the discarded elements as an unmodifiable list")
    void testDiscardedElementsAreUnmodifiable() {
        // The listener this list ends up at is documented as free to keep a reference to it, so it must not
        // be a view of, or share anything with, the batch that keeps being trimmed behind its back
        List<String> discarded = BoundedTrim.trim(mutable("a", "b", "c"), 1);

        assertEquals(List.of("a", "b"), discarded);
        assertThrows(UnsupportedOperationException.class, () -> discarded.add("d"));
    }

}
