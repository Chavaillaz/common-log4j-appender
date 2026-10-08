package com.chavaillaz.appender.internal;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import com.chavaillaz.appender.TransmissionResult;

/**
 * Fuzzes {@link Batcher} with adversarial {@link TransmissionResult} values a well-behaved transport would
 * never produce, but a buggy one could: a {@link TransmissionResult.PartialFailure} whose {@code rejected}
 * list is larger than the dispatched chunk, contains duplicate references, and/or references elements
 * foreign to that chunk entirely. This is exactly the boundary where {@code Batcher} depends on a
 * third-party contract it cannot enforce at compile time, and exactly where the two bugs fixed alongside
 * this test lived: a malformed {@code rejected} list could throw from inside a {@code whenComplete()}
 * callback nobody observes, silently losing the whole chunk with no trace at all (see
 * {@link BatcherTest.PartialTransmissionFailure#testMalformedPartialFailureDoesNotLoseElements()} for the
 * specific regression this generalizes).
 * <p>
 * Every element handed to a trial's {@link Batcher} is tracked by reference identity, independently of
 * {@code Batcher}'s own bookkeeping: the fake transport itself decides, as ground truth, which elements a
 * given attempt genuinely accepts, recording each acceptance, while a {@link BatcherListener} records every
 * element the {@code Batcher} reports through {@link BatcherListener#onDropped}. After driving each trial
 * through enough retries to force a resolution one way or another, every element must have been accepted
 * or reported dropped exactly once: in neither, it was silently lost; accepted and reported, or either one
 * twice, it was sent again once accepted or reported for nothing. No element foreign to the trial may be
 * accepted or reported at all, which would mean the malformed list smuggled it into the batch.
 */
@DisplayName("Batcher fuzzing")
class BatcherFuzzTest {

    /**
     * A single fuzzed element, a class rather than a record, so that two of them are equal only when they are
     * the same instance, as {@link Batcher} matches a {@link TransmissionResult.PartialFailure}'s
     * {@code rejected} list against a dispatched chunk.
     */
    private static final class Elem {

        /**
         * Only used to make failure messages readable; never compared.
         */
        private final int id;

        private Elem(int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "elem-" + id;
        }

    }

    private Thread.UncaughtExceptionHandler previousHandler;
    private List<Throwable> uncaughtExceptions;

    @BeforeEach
    void captureUncaughtExceptions() {
        uncaughtExceptions = new CopyOnWriteArrayList<>();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaughtExceptions.add(throwable));
    }

    @AfterEach
    void restoreUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        assertThat(uncaughtExceptions)
                .as("No exception should ever escape to a thread's default "
                        + "handler, but got: " + uncaughtExceptions)
                .isEmpty();
    }

    @RepeatedTest(200)
    @DisplayName("Never loses an element to a malformed PartialFailure, across many randomized trials")
    void testFuzzedTransmissionResultsNeverLoseAnElement() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        String seedMessage = "Failed with seed " + seed + " (use `new Random(" + seed + ")` to reproduce)";

        int elementCount = 1 + random.nextInt(5);
        int maxConsecutiveFailures = 1 + random.nextInt(3);
        List<Elem> elements = new ArrayList<>(elementCount);
        for (int i = 0; i < elementCount; i++) {
            elements.add(new Elem(i));
        }
        // Foreign elements a buggy transport could mistakenly reference: never part of any dispatched chunk.
        List<Elem> foreignPool = List.of(new Elem(-1), new Elem(-2));

        List<Elem> accepted = new CopyOnWriteArrayList<>();
        List<Elem> dropped = new CopyOnWriteArrayList<>();
        AtomicInteger transmitCount = new AtomicInteger();

        Batcher<Elem> batcher = Batcher.<Elem>builder()
                .flushThreshold(() -> Integer.MAX_VALUE)
                .maxPendingElements(() -> 100)
                .maxConsecutiveFailures(() -> maxConsecutiveFailures)
                .transmissionThreadPrefix("batcher-fuzz-")
                .transport(chunk -> {
                    transmitCount.incrementAndGet();
                    return completedFuture(fuzzedResult(random, chunk, foreignPool, accepted));
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<Elem> elementsDropped, String reason) {
                        dropped.addAll(elementsDropped);
                    }
                })
                .build();

        batcher.add(elements);
        batcher.awaitPendingTransmissions();

        // Drive a fixed, generous number of retry cycles unconditionally (not just until every element looks
        // resolved: a corrupted rejected list could smuggle a foreign element into the live batch, which would
        // then keep cycling through retries on its own even after every real element has resolved). Every
        // round sends the whole batch, and each element still rejected adds one to its own streak, so
        // maxConsecutiveFailures rounds are always enough to force a give-up; the margin is only a margin.
        for (int i = 0; i < maxConsecutiveFailures + 20; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then: every element was either genuinely accepted or reported dropped, exactly once
        Map<Elem, Integer> timesAccepted = countByIdentity(accepted);
        Map<Elem, Integer> timesDropped = countByIdentity(dropped);
        List<String> misaccounted = new ArrayList<>();
        for (Elem element : elements) {
            int acceptances = timesAccepted.getOrDefault(element, 0);
            int drops = timesDropped.getOrDefault(element, 0);
            if (acceptances + drops != 1) {
                misaccounted.add(element + " (accepted " + acceptances + " times, dropped " + drops + " times)");
            }
        }
        assertThat(misaccounted)
                .as(seedMessage + ": elements not accepted or dropped exactly once")
                .isEmpty();

        // and: nothing foreign to the trial ever made it into a request or a report
        Set<Elem> real = Collections.newSetFromMap(new IdentityHashMap<>());
        real.addAll(elements);
        List<Elem> foreign = new ArrayList<>();
        timesAccepted.keySet().stream().filter(element -> !real.contains(element)).forEach(foreign::add);
        timesDropped.keySet().stream().filter(element -> !real.contains(element)).forEach(foreign::add);
        assertThat(foreign)
                .as(seedMessage + ": elements foreign to the trial accepted or dropped")
                .isEmpty();

        // and: the batch actually drained, instead of an element being stuck in limbo forever, retried but
        // never making progress towards either outcome
        int transmitCountBeforeFinalFlush = transmitCount.get();
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertThat(transmitCount.get())
                .as(seedMessage + ": an extra flush() should not trigger another transmission once everything settled")
                .isEqualTo(transmitCountBeforeFinalFlush);
    }

    /**
     * Builds a randomized, occasionally-malformed {@link TransmissionResult} for the given chunk, recording in
     * {@code accepted} the elements the fake transport genuinely (as ground truth) accepts this round.
     *
     * @param random      The source of randomness for this trial
     * @param chunk       The chunk handed to this transmission attempt
     * @param foreignPool Elements never part of any real chunk, occasionally injected as noise
     * @param accepted    Every element accepted so far, once per acceptance, added to in place
     * @return The (possibly malformed) result to complete this attempt's future with
     */
    private TransmissionResult<Elem> fuzzedResult(Random random, List<Elem> chunk, List<Elem> foreignPool,
            List<Elem> accepted) {
        int outcome = random.nextInt(3);
        if (outcome == 0) {
            // Whole chunk genuinely accepted.
            accepted.addAll(chunk);
            return TransmissionResult.success();
        }
        if (outcome == 1) {
            // Whole chunk genuinely rejected, nothing resolved yet.
            return TransmissionResult.failure(null);
        }

        // Partial failure: a random non-empty subset is genuinely rejected, the rest genuinely accepted.
        List<Elem> shuffled = new ArrayList<>(chunk);
        Collections.shuffle(shuffled, random);
        int rejectedCount = 1 + random.nextInt(shuffled.size());
        List<Elem> realRejected = new ArrayList<>(shuffled.subList(0, rejectedCount));
        accepted.addAll(shuffled.subList(rejectedCount, shuffled.size()));

        // Corrupt the reported rejected list with noise a well-behaved transport would never produce: a
        // duplicate reference and/or a foreign element, occasionally pushing its size past the chunk's own,
        // the shape that once crashed the split of a chunk into its accepted and rejected elements
        List<Elem> reported = new ArrayList<>(realRejected);
        if (random.nextBoolean()) {
            reported.add(realRejected.get(random.nextInt(realRejected.size())));
        }
        if (random.nextBoolean()) {
            reported.add(foreignPool.get(random.nextInt(foreignPool.size())));
        }
        return TransmissionResult.partialFailure(reported, null);
    }

    /**
     * Counts how many times each instance appears in the given list, equal instances counted apart.
     *
     * @param list The list to count
     * @return The number of times each instance appears
     */
    private static Map<Elem, Integer> countByIdentity(List<Elem> list) {
        Map<Elem, Integer> counts = new IdentityHashMap<>();
        list.forEach(element -> counts.merge(element, 1, Integer::sum));
        return counts;
    }

}
