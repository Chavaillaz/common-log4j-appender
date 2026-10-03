package com.chavaillaz.appender.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Chunk")
class ChunkTest {

    /**
     * An element equal to any other of the same name, yet a distinct instance, as two documents logging the same
     * message are.
     */
    private record Item(String name) {

    }

    @Test
    @DisplayName("Matches the rejected elements by reference, an equal copy rejecting none of them")
    void testPartitionMatchesByReference() {
        // Given
        Item a = new Item("a");
        Item b = new Item("b");
        Chunk<Item> chunk = new Chunk<>(List.of(a, b), List.of());

        // When: the transport reports an equal copy rather than the instance it was given
        Chunk.Partition<Item> partition = chunk.partition(List.of(new Item("a")));

        // Then: nothing is rejected, and the copy is counted as foreign
        assertThat(partition.accepted()).containsExactly(a, b);
        assertThat(partition.rejected().elements()).isEmpty();
        assertThat(partition.foreign()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejects an element as many times as the chunk holds it, however many times it is reported")
    void testPartitionFollowsTheChunk() {
        // Given: a chunk holding one instance twice
        Item a = new Item("a");
        Item b = new Item("b");
        Chunk<Item> chunk = new Chunk<>(List.of(a, a, b), List.of());

        // When: the transport reports it three times
        Chunk.Partition<Item> partition = chunk.partition(List.of(a, a, a));

        // Then
        assertThat(partition.rejected().elements()).containsExactly(a, a);
        assertThat(partition.accepted()).containsExactly(b);
        assertThat(partition.foreign()).isZero();
    }

    @Test
    @DisplayName("Hands the rejected elements over with the failure streaks they had")
    void testPartitionKeepsTheStreaksOfTheRejectedElements() {
        // Given: the first element failed twice, the others never did
        Item a = new Item("a");
        Item b = new Item("b");
        Item c = new Item("c");
        Chunk<Item> chunk = new Chunk<>(List.of(a, b, c), List.of(new FailureRun(2, 1)));

        // When
        Chunk.Partition<Item> partition = chunk.partition(List.of(c, a));

        // Then: in the order of the chunk, each with its own streak
        assertThat(partition.rejected().elements()).containsExactly(a, c);
        assertThat(partition.rejected().runs()).containsExactly(new FailureRun(2, 1), new FailureRun(0, 1));
        assertThat(partition.accepted()).containsExactly(b);
    }

    @Test
    @DisplayName("Accepts every element when none is rejected")
    void testPartitionWithoutRejectedElements() {
        // Given
        Item a = new Item("a");
        Chunk<Item> chunk = new Chunk<>(List.of(a), List.of(new FailureRun(1, 1)));

        // When
        Chunk.Partition<Item> partition = chunk.partition(List.of());

        // Then
        assertThat(partition.accepted()).containsExactly(a);
        assertThat(partition.rejected().size()).isZero();
        assertThat(partition.foreign()).isZero();
    }

    @Test
    @DisplayName("Counts one more failure for each element, keeping it for a retry below the limit")
    void testAfterFailureCountsOneMoreFailure() {
        // Given: two elements that failed once, and one that never did
        Chunk<String> chunk = new Chunk<>(List.of("a", "b", "c"), List.of(new FailureRun(1, 2)));

        // When
        Chunk.AfterFailure<String> outcome = chunk.afterFailure(5);

        // Then
        assertThat(outcome.givenUp()).isEmpty();
        assertThat(outcome.retried().elements()).containsExactly("a", "b", "c");
        assertThat(outcome.retried().runs()).containsExactly(new FailureRun(2, 2), new FailureRun(1, 1));
        assertThat(outcome.highestStreak()).isEqualTo(2);
    }

    @Test
    @DisplayName("Gives up on the elements reaching the maximum number of consecutive failures")
    void testAfterFailureGivesUpAtTheLimit() {
        // Given: an element that failed twice, and one that failed once
        Chunk<String> chunk = new Chunk<>(List.of("a", "b"), List.of(new FailureRun(2, 1), new FailureRun(1, 1)));

        // When: at most three failures in a row
        Chunk.AfterFailure<String> outcome = chunk.afterFailure(3);

        // Then: the first reached it, the other is retried with its own streak
        assertThat(outcome.givenUp()).containsExactly("a");
        assertThat(outcome.retried().elements()).containsExactly("b");
        assertThat(outcome.retried().runs()).containsExactly(new FailureRun(2, 1));
        assertThat(outcome.highestStreak()).isEqualTo(2);
        assertThatThrownBy(() -> outcome.givenUp().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Never gives up on an element while the maximum is not positive")
    void testAfterFailureWithoutLimit() {
        // Given: an element that failed a thousand times
        Chunk<String> chunk = new Chunk<>(List.of("a"), List.of(new FailureRun(1_000, 1)));

        // When
        Chunk.AfterFailure<String> outcome = chunk.afterFailure(0);

        // Then
        assertThat(outcome.givenUp()).isEmpty();
        assertThat(outcome.retried().runs()).containsExactly(new FailureRun(1_001, 1));
        assertThat(outcome.highestStreak()).isEqualTo(1_001);
    }

    @Test
    @DisplayName("Reports no streak once every element is given up on")
    void testAfterFailureGivingUpOnEveryElement() {
        // Given
        Chunk<String> chunk = new Chunk<>(List.of("a", "b"), List.of());

        // When: a single failure is one too many
        Chunk.AfterFailure<String> outcome = chunk.afterFailure(1);

        // Then
        assertThat(outcome.givenUp()).containsExactly("a", "b");
        assertThat(outcome.retried().size()).isZero();
        assertThat(outcome.highestStreak()).isZero();
    }

}
