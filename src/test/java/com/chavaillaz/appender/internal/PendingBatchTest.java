package com.chavaillaz.appender.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pending batch")
class PendingBatchTest {

    private static PendingBatch<String> measuredByLength() {
        return new PendingBatch<>(element -> (long) element.length(), 4);
    }

    private static List<List<String>> elementsOf(List<Chunk<String>> chunks) {
        return chunks.stream().map(Chunk::elements).toList();
    }

    private static List<List<FailureRun>> runsOf(List<Chunk<String>> chunks) {
        return chunks.stream().map(Chunk::runs).toList();
    }

    @Test
    @DisplayName("Puts what failed before every newer element, with the streaks of its elements")
    void testPrependedElementsComeFirstWithTheirStreaks() {
        // Given
        PendingBatch<String> batch = measuredByLength();
        batch.append(List.of("c"));

        // When
        batch.prepend(new Chunk<>(List.of("a", "b"), List.of(new FailureRun(2, 2))));
        List<Chunk<String>> chunks = batch.split(0, 0);

        // Then: the element that never failed counts no streak
        assertEquals(List.of(List.of("a", "b", "c")), elementsOf(chunks));
        assertEquals(List.of(List.of(new FailureRun(2, 2), new FailureRun(0, 1))), runsOf(chunks));
        assertEquals(0, batch.size());
    }

    @Test
    @DisplayName("Forgets the streaks of the elements a trim discards")
    void testTrimsForgetTheStreaksOfWhatTheyDiscard() {
        // Given
        PendingBatch<String> batch = measuredByLength();
        batch.append(List.of("c"));
        batch.prepend(new Chunk<>(List.of("a", "b"), List.of(new FailureRun(3, 1), new FailureRun(1, 1))));

        // When
        List<String> discarded = batch.trimToCount(2);
        List<Chunk<String>> chunks = batch.split(0, 0);

        // Then: the oldest is discarded, and the streaks left describe the elements left
        assertEquals(List.of("a"), discarded);
        assertEquals(List.of(List.of("b", "c")), elementsOf(chunks));
        assertEquals(List.of(List.of(new FailureRun(1, 1), new FailureRun(0, 1))), runsOf(chunks));
    }

    @Test
    @DisplayName("Splits within the request limits, each chunk carrying the streaks of its own elements")
    void testSplitCutsWithinLimitsAndSlicesTheStreaks() {
        // Given: elements of 10 bytes, the first two having failed twice
        PendingBatch<String> batch = new PendingBatch<>(element -> 10L, 4);
        batch.append(List.of("c", "d", "e"));
        batch.prepend(new Chunk<>(List.of("a", "b"), List.of(new FailureRun(2, 2))));

        // When: at most 25 bytes per chunk
        List<Chunk<String>> chunks = batch.split(0, 25);

        // Then
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d"), List.of("e")), elementsOf(chunks));
        assertEquals(List.of(
                List.of(new FailureRun(2, 2)),
                List.of(new FailureRun(0, 2)),
                List.of(new FailureRun(0, 1))), runsOf(chunks));
        assertEquals(0, batch.size());
        assertEquals(0, batch.bytes());
    }

    @Test
    @DisplayName("Hands each chunk over as an unmodifiable list of its own")
    void testChunksAreUnmodifiable() {
        // Given
        PendingBatch<String> batch = measuredByLength();
        batch.append(List.of("a", "b", "c"));

        // When
        List<Chunk<String>> chunks = batch.split(2, 0);

        // Then
        assertEquals(List.of(List.of("a", "b"), List.of("c")), elementsOf(chunks));
        assertThrows(UnsupportedOperationException.class, () -> chunks.getFirst().elements().add("d"));
    }

    @Test
    @DisplayName("Discards the oldest elements until the batch fits within the size given")
    void testTrimToBytesDiscardsTheOldest() {
        // Given: 7 bytes
        PendingBatch<String> batch = measuredByLength();
        batch.append(List.of("aaaa", "bb", "c"));

        // When
        List<String> discarded = batch.trimToBytes(3);

        // Then
        assertEquals(List.of("aaaa"), discarded);
        assertEquals(2, batch.size());
        assertEquals(3, batch.bytes());
        assertNull(batch.trimToBytes(3));
    }

    @Test
    @DisplayName("Tracks no size and never trims by size without a measurement")
    void testWithoutMeasurementSizesAreNotTracked() {
        // Given
        PendingBatch<String> batch = new PendingBatch<>(null, 4);
        batch.append(List.of("aaaa", "bb"));

        // When / Then: neither the trim nor the split limit by size do anything
        assertEquals(0, batch.bytes());
        assertNull(batch.trimToBytes(1));
        assertEquals(List.of(List.of("aaaa", "bb")), elementsOf(batch.split(0, 1)));
    }

    @Test
    @DisplayName("Takes every element out for good when drained")
    void testDrainTakesEverything() {
        // Given
        PendingBatch<String> batch = measuredByLength();
        batch.append(List.of("b"));
        batch.prepend(new Chunk<>(List.of("a"), List.of(new FailureRun(1, 1))));

        // When
        List<String> remaining = batch.drain();

        // Then: nothing left to send
        assertEquals(List.of("a", "b"), remaining);
        assertTrue(batch.isEmpty());
        assertEquals(0, batch.bytes());
        assertEquals(List.of(), batch.split(0, 0));
    }

    @Test
    @DisplayName("Slices runs past their end as elements that never failed")
    void testSliceFillsPastTheRunsWithNoStreak() {
        // Given
        List<FailureRun> runs = List.of(new FailureRun(2, 1), new FailureRun(1, 2));

        // When / Then
        assertEquals(List.of(new FailureRun(2, 1), new FailureRun(1, 2), new FailureRun(0, 2)), FailureRun.slice(runs, 0, 5));
        assertEquals(List.of(new FailureRun(1, 1)), FailureRun.slice(runs, 2, 3));
        assertEquals(List.of(new FailureRun(0, 1)), FailureRun.slice(runs, 4, 5));
    }

    @Test
    @DisplayName("Reads no streak for an element past the last run")
    void testStreaksPastTheLastRunAreZero() {
        // Given
        FailureRun.Streaks streaks = new FailureRun.Streaks(List.of(new FailureRun(3, 1)));

        // When / Then
        assertEquals(3, streaks.next());
        assertEquals(0, streaks.next());
        assertEquals(0, streaks.next());
    }

}
