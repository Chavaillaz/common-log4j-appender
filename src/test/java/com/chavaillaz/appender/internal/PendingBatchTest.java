package com.chavaillaz.appender.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
        assertThat(elementsOf(chunks)).containsExactly(List.of("a", "b", "c"));
        assertThat(runsOf(chunks)).containsExactly(List.of(new FailureRun(2, 2), new FailureRun(0, 1)));
        assertThat(batch.size()).isZero();
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
        assertThat(discarded).containsExactly("a");
        assertThat(elementsOf(chunks)).containsExactly(List.of("b", "c"));
        assertThat(runsOf(chunks)).containsExactly(List.of(new FailureRun(1, 1), new FailureRun(0, 1)));
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
        assertThat(elementsOf(chunks)).containsExactly(List.of("a", "b"), List.of("c", "d"), List.of("e"));
        assertThat(runsOf(chunks))
                .containsExactly(
                        List.of(new FailureRun(2, 2)),
                        List.of(new FailureRun(0, 2)),
                        List.of(new FailureRun(0, 1)));
        assertThat(batch.size()).isZero();
        assertThat(batch.bytes()).isZero();
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
        assertThat(elementsOf(chunks)).containsExactly(List.of("a", "b"), List.of("c"));
        assertThatThrownBy(() -> chunks.getFirst().elements().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
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
        assertThat(discarded).containsExactly("aaaa");
        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.bytes()).isEqualTo(3);
        assertThat(batch.trimToBytes(3)).isNull();
    }

    @Test
    @DisplayName("Never counts a negative size once an element weighs more leaving than it did coming in")
    void testSizeNeverGoesNegative() {
        // Given: an element counted as weightless, then measured, as a byte limit coming into effect makes it
        AtomicBoolean measuring = new AtomicBoolean();
        PendingBatch<String> batch = new PendingBatch<>(element -> measuring.get() ? element.length() : 0L, 4);
        batch.append(List.of("a".repeat(100)));
        measuring.set(true);
        batch.append(List.of("bbbbbb", "cccccc"));

        // When: trimmed by size, then by count, each discarding more than the batch is counted for
        List<String> oversized = batch.trimToBytes(10);
        long bytesAfterTrimToBytes = batch.bytes();
        List<String> discarded = batch.trimToCount(1);

        // Then: under-counted at worst, never below zero
        assertThat(oversized).containsExactly("a".repeat(100));
        assertThat(bytesAfterTrimToBytes).isZero();
        assertThat(discarded).containsExactly("bbbbbb");
        assertThat(batch.bytes()).isZero();
    }

    @Test
    @DisplayName("Tracks no size and never trims by size without a measurement")
    void testWithoutMeasurementSizesAreNotTracked() {
        // Given
        PendingBatch<String> batch = new PendingBatch<>(null, 4);
        batch.append(List.of("aaaa", "bb"));

        // When / Then: neither the trim nor the split limit by size do anything
        assertThat(batch.bytes()).isZero();
        assertThat(batch.trimToBytes(1)).isNull();
        assertThat(elementsOf(batch.split(0, 1))).containsExactly(List.of("aaaa", "bb"));
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
        assertThat(remaining).containsExactly("a", "b");
        assertThat(batch.isEmpty()).isTrue();
        assertThat(batch.bytes()).isZero();
        assertThat(batch.split(0, 0)).isEmpty();
    }

    @Test
    @DisplayName("Slices runs past their end as elements that never failed")
    void testSliceFillsPastTheRunsWithNoStreak() {
        // Given
        List<FailureRun> runs = List.of(new FailureRun(2, 1), new FailureRun(1, 2));

        // When / Then
        assertThat(FailureRun.slice(runs, 0, 5))
                .containsExactly(new FailureRun(2, 1), new FailureRun(1, 2), new FailureRun(0, 2));
        assertThat(FailureRun.slice(runs, 2, 3)).containsExactly(new FailureRun(1, 1));
        assertThat(FailureRun.slice(runs, 4, 5)).containsExactly(new FailureRun(0, 1));
    }

    @Test
    @DisplayName("Reads no streak for an element past the last run")
    void testStreaksPastTheLastRunAreZero() {
        // Given
        FailureRun.Streaks streaks = new FailureRun.Streaks(List.of(new FailureRun(3, 1)));

        // When / Then
        assertThat(streaks.next()).isEqualTo(3);
        assertThat(streaks.next()).isZero();
        assertThat(streaks.next()).isZero();
    }

}
