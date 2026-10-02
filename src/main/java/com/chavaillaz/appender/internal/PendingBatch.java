package com.chavaillaz.appender.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.function.ToLongFunction;

import org.jspecify.annotations.Nullable;

/**
 * The elements a {@link Batcher} accumulates until they are sent, oldest first, along with the failure streaks
 * of those a failed transmission put back and their estimated size. Not thread-safe: the batcher guards the one
 * it accumulates with a lock, and swaps it for an empty one once due, the one swapped out being no one else's
 * from then on.
 * <p>
 * What a failed transmission puts back goes in front of every newer element, so that the failure streaks only
 * ever describe the leading elements, the others having never failed, and both trims discard from the front,
 * the oldest elements first.
 *
 * @param <T> The type of element accumulated
 */
final class PendingBatch<T> {

    /**
     * Measures one element, never throwing nor returning a size outside {@code [0, 2 GiB]}, so that no total
     * overflows, or {@code null} not to track sizes, leaving the size limits inert.
     */
    private final @Nullable ToLongFunction<T> sizeOf;

    /**
     * The elements, oldest first. A ring buffer, both trims discarding from its front and
     * {@link #prepend(Chunk)} putting elements back there, in constant time per element where a list shifts
     * every element it keeps: once at its cap, the batch is trimmed on every addition.
     */
    private final ArrayDeque<T> elements;

    /**
     * The failure streaks of the leading elements, front first (see {@link FailureRun}). A streak belongs to
     * the elements that failed, not to the batch holding them: an element joining them starts from its own
     * first attempt.
     */
    private final ArrayDeque<FailureRun> failedRuns = new ArrayDeque<>();

    /**
     * Estimated total size of the elements, kept up to date rather than summed on demand, which would make
     * every addition walk the whole batch. Always {@code 0} without {@link #sizeOf}.
     */
    private long bytes;

    /**
     * Creates an empty batch.
     *
     * @param sizeOf          Measures one element, never throwing nor returning a size outside
     *                        {@code [0, 2 GiB]}, or {@code null} not to track sizes
     * @param initialCapacity The number of elements to reserve room for
     */
    PendingBatch(@Nullable ToLongFunction<T> sizeOf, int initialCapacity) {
        this.sizeOf = sizeOf;
        this.elements = new ArrayDeque<>(initialCapacity);
    }

    /**
     * The number of elements of this batch.
     *
     * @return The number of elements
     */
    int size() {
        return elements.size();
    }

    /**
     * Whether this batch holds no element.
     *
     * @return {@code true} if it is empty
     */
    boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * The estimated size of this batch.
     *
     * @return The estimated size in bytes, never negative, {@code 0} without a measurement
     */
    long bytes() {
        return bytes;
    }

    /**
     * Adds the given elements after every other, as they arrive.
     *
     * @param added The elements to add, in order
     */
    void append(Collection<T> added) {
        elements.addAll(added);
        bytes += measure(added);
    }

    /**
     * Puts the elements of a chunk back in front of every other, in their order and with their failure streaks,
     * so that they are retried before any newer element.
     *
     * @param chunk The chunk to put back
     */
    void prepend(Chunk<T> chunk) {
        List<T> returned = chunk.elements();
        for (ListIterator<T> iterator = returned.listIterator(returned.size()); iterator.hasPrevious(); ) {
            elements.addFirst(iterator.previous());
        }
        // Describing all its elements, those past its runs never having failed: put in front of the runs of the
        // elements behind them, runs describing fewer would hand those elements the streaks of others
        List<FailureRun> runs = FailureRun.slice(chunk.runs(), 0, returned.size());
        for (ListIterator<FailureRun> iterator = runs.listIterator(runs.size()); iterator.hasPrevious(); ) {
            prependRun(iterator.previous());
        }
        // Measured again, having left the running total when swapped out
        bytes += measure(returned);
    }

    /**
     * Discards the oldest elements in excess of the given count (see {@link BoundedTrim}).
     *
     * @param maxCount The maximum number of elements to keep
     * @return The elements discarded, oldest first, as an unmodifiable list, or {@code null} if none were
     */
    @Nullable List<T> trimToCount(int maxCount) {
        List<T> discarded = BoundedTrim.trim(elements, maxCount);
        if (discarded != null) {
            subtract(measure(discarded));
            forgetFront(discarded.size());
        }
        return discarded;
    }

    /**
     * Discards the oldest elements until the estimated size of this batch fits within the given one, the
     * counterpart by size of {@link #trimToCount(int)}.
     *
     * @param maxBytes The maximum estimated size to keep, a non-positive value for no limit
     * @return The elements discarded, oldest first, as an unmodifiable list, or {@code null} if none were
     */
    @Nullable List<T> trimToBytes(long maxBytes) {
        ToLongFunction<T> measurement = sizeOf;
        if (measurement == null || maxBytes <= 0 || bytes <= maxBytes) {
            return null;
        }

        List<T> discarded = new ArrayList<>();
        while (!elements.isEmpty() && bytes > maxBytes) {
            T oldest = elements.pollFirst();
            subtract(measurement.applyAsLong(oldest));
            discarded.add(oldest);
        }
        if (elements.isEmpty()) {
            // Re-anchored, so that a measurement not perfectly stable cannot drift for the whole lifetime
            bytes = 0;
        }
        if (discarded.isEmpty()) {
            // A total drifting above the limit on an empty batch, which the re-anchoring just healed
            return null;
        }
        forgetFront(discarded.size());
        return Collections.unmodifiableList(discarded);
    }

    /**
     * Splits this batch into chunks within the given request limits, cutting before whichever element would
     * push a chunk past one of them, and empties it. Meant for a batch swapped out, which no one else holds:
     * each element is copied once, from the batch into the chunk holding it, and a chunk of its own lets the
     * elements of a completed transmission go while another is still in flight.
     *
     * @param maxCount The maximum number of elements per chunk, or a non-positive value for no such limit
     * @param maxBytes The maximum estimated size per chunk, or a non-positive value for no such limit
     * @return The chunks, in order, each with the failure streaks of its elements, none for an empty batch
     */
    List<Chunk<T>> split(int maxCount, long maxBytes) {
        if (elements.isEmpty()) {
            return List.of();
        }
        boolean countLimited = maxCount > 0 && elements.size() > maxCount;
        // A single element goes out on its own whatever it weighs, so it is not measured
        boolean byteLimited = sizeOf != null && maxBytes > 0 && maxBytes < Long.MAX_VALUE && elements.size() > 1;
        List<Integer> chunkSizes = countLimited || byteLimited
                ? chunkSizes(maxCount, byteLimited ? maxBytes : 0)
                : List.of(elements.size());

        List<FailureRun> runs = List.copyOf(failedRuns);
        List<Chunk<T>> chunks = new ArrayList<>(chunkSizes.size());
        int offset = 0;
        for (int size : chunkSizes) {
            chunks.add(new Chunk<>(take(size), FailureRun.slice(runs, offset, offset + size)));
            offset += size;
        }
        failedRuns.clear();
        bytes = 0;
        return chunks;
    }

    /**
     * Takes every element out of this batch for good, as a close does, and empties it.
     *
     * @return The elements, oldest first, as an unmodifiable list
     */
    List<T> drain() {
        List<T> remaining = take(elements.size());
        failedRuns.clear();
        bytes = 0;
        return remaining;
    }

    /**
     * Where to cut this batch into consecutive chunks: before whichever element would push the current chunk
     * past {@code maxCount} elements or {@code maxBytes} bytes.
     *
     * @param maxCount The maximum number of elements per chunk, or a non-positive value for no such limit
     * @param maxBytes The maximum estimated size per chunk, or a non-positive value for no such limit, only
     *                 given along with a measurement
     * @return The number of elements of each chunk, in order
     */
    private List<Integer> chunkSizes(int maxCount, long maxBytes) {
        List<Integer> sizes = new ArrayList<>();
        int count = 0;
        long chunkBytes = 0;
        for (T element : elements) {
            long size = maxBytes > 0 && sizeOf != null ? sizeOf.applyAsLong(element) : 0;
            boolean countReached = maxCount > 0 && count >= maxCount;
            // Never cut before the first element of a chunk, so that one heavier than the limit goes out alone
            boolean bytesReached = maxBytes > 0 && count > 0 && chunkBytes + size > maxBytes;
            if (countReached || bytesReached) {
                sizes.add(count);
                count = 0;
                chunkBytes = 0;
            }
            count++;
            chunkBytes += size;
        }
        if (count > 0) {
            sizes.add(count);
        }
        return sizes;
    }

    /**
     * Moves the given number of elements from the front of this batch into a list of their own.
     *
     * @param count How many elements to take, at most as many as this batch holds
     * @return The elements taken, in order, as an unmodifiable list
     */
    @SuppressWarnings("unchecked")
    private List<T> take(int count) {
        Object[] taken = new Object[count];
        for (int i = 0; i < count; i++) {
            taken[i] = elements.pollFirst();
        }
        return (List<T>) Collections.unmodifiableList(Arrays.asList(taken));
    }

    /**
     * Measures the given elements altogether.
     *
     * @param measured The elements to measure
     * @return Their estimated total size in bytes, {@code 0} without a measurement
     */
    private long measure(Collection<T> measured) {
        ToLongFunction<T> measurement = sizeOf;
        if (measurement == null) {
            return 0;
        }
        long total = 0;
        for (T element : measured) {
            total += measurement.applyAsLong(element);
        }
        return total;
    }

    /**
     * Takes the size of discarded elements off the estimated size of this batch, never below zero: measured
     * heavier than when they were added, a byte limit coming into effect in between for instance, they would
     * otherwise leave it negative for as long as the batch is pending.
     *
     * @param discardedBytes The estimated size of the elements discarded
     */
    private void subtract(long discardedBytes) {
        bytes = Math.max(0, bytes - discardedBytes);
    }

    /**
     * Forgets the failure streaks of elements just discarded from the front of this batch.
     *
     * @param discarded How many elements were discarded from the front
     */
    private void forgetFront(int discarded) {
        int remaining = discarded;
        while (remaining > 0 && !failedRuns.isEmpty()) {
            FailureRun first = failedRuns.pollFirst();
            if (first.count() > remaining) {
                failedRuns.addFirst(new FailureRun(first.streak(), first.count() - remaining));
                return;
            }
            remaining -= first.count();
        }
    }

    /**
     * Puts the given run in front of the failure streaks, merged into the first run when they share a streak.
     *
     * @param run The run to put in front
     */
    private void prependRun(FailureRun run) {
        FailureRun first = failedRuns.peekFirst();
        if (first != null && first.streak() == run.streak()) {
            failedRuns.pollFirst();
            failedRuns.addFirst(new FailureRun(run.streak(), first.count() + run.count()));
        } else {
            failedRuns.addFirst(run);
        }
    }

}
