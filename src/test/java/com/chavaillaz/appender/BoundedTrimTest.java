package com.chavaillaz.appender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("BoundedTrim")
class BoundedTrimTest {

    @Test
    @DisplayName("Keeps everything and discards nothing when under the cap")
    void testUnderCapKeepsEverything() {
        var result = BoundedTrim.trim(List.of("a", "b"), 5);

        assertEquals(List.of("a", "b"), result.kept());
        assertNull(result.discarded());
    }

    @Test
    @DisplayName("Returns the same list instance when nothing is discarded")
    void testUnderCapReturnsSameInstance() {
        List<String> elements = List.of("a", "b");

        var result = BoundedTrim.trim(elements, 5);

        assertSame(elements, result.kept());
    }

    @Test
    @DisplayName("Keeps everything and discards nothing when exactly at the cap")
    void testExactlyAtCapKeepsEverything() {
        var result = BoundedTrim.trim(List.of("a", "b", "c"), 3);

        assertEquals(List.of("a", "b", "c"), result.kept());
        assertNull(result.discarded());
    }

    @Test
    @DisplayName("Discards the single oldest element when one over the cap")
    void testSingleOverflowDiscardsOldest() {
        var result = BoundedTrim.trim(List.of("a", "b", "c"), 2);

        assertEquals(List.of("b", "c"), result.kept());
        assertEquals(List.of("a"), result.discarded());
    }

    @Test
    @DisplayName("Discards every oldest element in excess, keeping the most recently added ones")
    void testBulkOverflowDiscardsOldestElements() {
        var result = BoundedTrim.trim(List.of("a", "b", "c", "d", "e"), 2);

        assertEquals(List.of("d", "e"), result.kept());
        assertEquals(List.of("a", "b", "c"), result.discarded());
    }

    @Test
    @DisplayName("Discards everything when the cap is zero")
    void testZeroCapDiscardsEverything() {
        var result = BoundedTrim.trim(List.of("a", "b"), 0);

        assertEquals(List.of(), result.kept());
        assertEquals(List.of("a", "b"), result.discarded());
    }

    // Integer.MIN_VALUE is not just a bigger negative: it used to make size - maxSize overflow back into a
    // negative, which read as "nothing to discard" and kept every element instead of discarding them all
    @ParameterizedTest(name = "maxSize = {0}")
    @ValueSource(ints = {-1, -100, Integer.MIN_VALUE + 1, Integer.MIN_VALUE})
    @DisplayName("Treats a negative cap the same as zero, discarding everything, instead of throwing")
    void testNegativeCapDiscardsEverythingWithoutThrowing(int maxSize) {
        var result = BoundedTrim.trim(List.of("a", "b"), maxSize);

        assertEquals(List.of(), result.kept());
        assertEquals(List.of("a", "b"), result.discarded());
    }

    @Test
    @DisplayName("Does not discard anything, or throw, for an empty list")
    void testEmptyListDoesNotThrow() {
        var result = BoundedTrim.trim(List.of(), 3);

        assertEquals(List.of(), result.kept());
        assertNull(result.discarded());
    }

    @Test
    @DisplayName("Does not throw when the cap is larger than the list itself")
    void testCapLargerThanListDoesNotThrow() {
        var result = BoundedTrim.trim(List.of("a"), 1_000_000);

        assertEquals(List.of("a"), result.kept());
        assertNull(result.discarded());
    }

}
