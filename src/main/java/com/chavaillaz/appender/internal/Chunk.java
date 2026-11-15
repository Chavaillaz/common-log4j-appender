package com.chavaillaz.appender.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Elements taken out of the pending batch of a {@link Batcher} together, with the failure streak each of
 * them has so far: a request handed to the transport, or what a failed one puts back. Compared by identity,
 * since two requests may carry equal elements.
 *
 * @param <T> The type of element
 */
final class Chunk<T> {

    /**
     * Numbers the chunks as they are made, across every engine, for {@link #sequence()}.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final List<T> elements;
    private final List<FailureRun> runs;
    private final long sequence = SEQUENCE.incrementAndGet();

    /**
     * Whether a transmission was started for this chunk, its elements being possibly delivered from then on.
     */
    private volatile boolean dispatched;

    /**
     * Creates a chunk of the given elements.
     *
     * @param elements The elements, in order, kept as an unmodifiable view: what the transport is handed is
     *                 what gets retried or reported if it fails
     * @param runs     The failure streaks of these elements, run by run (see {@link FailureRun})
     */
    Chunk(List<T> elements, List<FailureRun> runs) {
        this.elements = Collections.unmodifiableList(elements);
        this.runs = runs;
    }

    /**
     * The elements of this chunk.
     *
     * @return The elements, in order, as an unmodifiable list
     */
    List<T> elements() {
        return elements;
    }

    /**
     * The failure streaks of the elements of this chunk.
     *
     * @return The failure streaks, run by run
     */
    List<FailureRun> runs() {
        return runs;
    }

    /**
     * The number of elements of this chunk.
     *
     * @return The number of elements
     */
    int size() {
        return elements.size();
    }

    /**
     * When this chunk was made, relative to the others: the chunks a batch is split into are numbered in their
     * order, and after those of the batches taken out before it, so that a close reports the oldest first.
     *
     * @return A number growing with each chunk made
     */
    long sequence() {
        return sequence;
    }

    /**
     * Whether a transmission was started for this chunk.
     *
     * @return {@code true} once its elements may have reached the transport
     */
    boolean isDispatched() {
        return dispatched;
    }

    /**
     * Records whether a transmission was started for this chunk.
     *
     * @param dispatched {@code true} as its transmission starts, {@code false} if it failed to
     */
    void dispatched(boolean dispatched) {
        this.dispatched = dispatched;
    }

    /**
     * The elements of a dispatched chunk, split into those a
     * {@link com.chavaillaz.appender.TransmissionResult.PartialFailure} reported as rejected and those it did not.
     *
     * @param accepted The elements not reported as rejected, in their original order
     * @param rejected The elements reported as rejected, in their original order, with the failure streaks
     *                 they had before this attempt
     * @param foreign  How many of the instances reported as rejected the chunk does not hold, ignored
     * @param <T>      The type of element
     */
    record Partition<T>(List<T> accepted, Chunk<T> rejected, int foreign) {

    }

    /**
     * Splits this chunk according to whether each of its elements is in {@code rejected}, matched by reference
     * rather than {@code equals()} (see {@link com.chavaillaz.appender.TransmissionResult}). Driven by this
     * chunk, so that the result only holds elements of the chunk, each as many times as the chunk does, however
     * many times {@code rejected} names it.
     *
     * @param rejected The elements the transport reported as rejected
     * @return This chunk, partitioned into accepted and rejected elements
     */
    Partition<T> partition(List<T> rejected) {
        if (rejected.isEmpty()) {
            return new Partition<>(elements, new Chunk<>(List.of(), List.of()), 0);
        }
        Set<T> rejectedIdentity = Collections.newSetFromMap(new IdentityHashMap<>(rejected.size()));
        rejectedIdentity.addAll(rejected);
        Set<T> held = Collections.newSetFromMap(new IdentityHashMap<>(rejectedIdentity.size()));
        List<T> accepted = new ArrayList<>(size());
        List<T> actuallyRejected = new ArrayList<>(Math.min(size(), rejected.size()));
        List<FailureRun> rejectedRuns = new ArrayList<>();
        FailureRun.Streaks streaks = new FailureRun.Streaks(runs);
        for (T element : elements) {
            int streak = streaks.next();
            if (rejectedIdentity.contains(element)) {
                held.add(element);
                actuallyRejected.add(element);
                FailureRun.append(rejectedRuns, streak, 1);
            } else {
                accepted.add(element);
            }
        }
        return new Partition<>(accepted, new Chunk<>(actuallyRejected, rejectedRuns),
                rejectedIdentity.size() - held.size());
    }

    /**
     * The elements of a chunk that failed to be transmitted, split by the failure streak each of them reached
     * with that attempt.
     *
     * @param givenUp       The elements that reached the maximum number of consecutive failures, in their
     *                      original order, as an unmodifiable list
     * @param retried       The other elements, in their original order, with the failure streaks they reached
     * @param highestStreak The highest failure streak among the elements retried, {@code 0} if there are none
     * @param <T>           The type of element
     */
    record AfterFailure<T>(List<T> givenUp, Chunk<T> retried, int highestStreak) {

    }

    /**
     * Counts one more failure for each element of this chunk, each counting its own failures, and splits it into
     * the elements reaching {@code maxFailures} in a row, given up on, and those kept for a retry.
     *
     * @param maxFailures The number of consecutive failures an element is given up on at, a non-positive value
     *                    never giving up on any
     * @return This chunk, split into the elements given up on and those retried
     */
    AfterFailure<T> afterFailure(int maxFailures) {
        List<T> givenUp = new ArrayList<>();
        List<T> retried = new ArrayList<>(size());
        List<FailureRun> retriedRuns = new ArrayList<>();
        int highestStreak = 0;
        FailureRun.Streaks streaks = new FailureRun.Streaks(runs);
        for (T element : elements) {
            int streak = streaks.next() + 1;
            if (maxFailures > 0 && streak >= maxFailures) {
                givenUp.add(element);
            } else {
                retried.add(element);
                FailureRun.append(retriedRuns, streak, 1);
                highestStreak = Math.max(highestStreak, streak);
            }
        }
        return new AfterFailure<>(Collections.unmodifiableList(givenUp), new Chunk<>(retried, retriedRuns),
                highestStreak);
    }

}
