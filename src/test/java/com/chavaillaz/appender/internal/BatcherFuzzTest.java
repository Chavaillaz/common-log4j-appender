package com.chavaillaz.appender.internal;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
 * {@link BatcherTest#testMalformedPartialFailureDoesNotLoseElements()} for the specific regression this
 * generalizes).
 * <p>
 * Every element handed to a trial's {@link Batcher} is tracked by reference identity, independently of
 * {@code Batcher}'s own bookkeeping: the fake transport itself decides, as ground truth, which elements a
 * given attempt genuinely accepts (removed from {@link #stillPending}), while a {@link BatcherListener}
 * records every element the {@code Batcher} eventually reports through {@link BatcherListener#onDropped}.
 * After driving each trial through enough retries to force a resolution one way or another, every element
 * must appear in exactly one of those two sets: genuinely accepted at some point, or explicitly reported
 * dropped. An element in neither is exactly what "silently lost" means.
 */
@DisplayName("Batcher fuzzing")
class BatcherFuzzTest {

    /**
     * A single fuzzed element, deliberately not overriding {@code equals()}/{@code hashCode()} so this
     * class's own reference-identity tracking (via {@link IdentityHashMap}) matches the reference-identity
     * semantics {@link Batcher} itself uses to match a {@link TransmissionResult.PartialFailure}'s
     * {@code rejected} list back against a dispatched chunk.
     *
     * @param id Only used to make failure messages readable; never compared
     */
    private record Elem(int id) {

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
        assertTrue(uncaughtExceptions.isEmpty(), "No exception should ever escape to a thread's default "
                + "handler, but got: " + uncaughtExceptions);
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

        Set<Elem> stillPending = Collections.newSetFromMap(new IdentityHashMap<>());
        stillPending.addAll(elements);
        List<Elem> dropped = new CopyOnWriteArrayList<>();
        AtomicInteger transmitCount = new AtomicInteger();

        Batcher<Elem> batcher = Batcher.<Elem>builder()
                .flushThreshold(() -> Integer.MAX_VALUE)
                .maxPendingElements(() -> 100)
                .maxConsecutiveFailures(() -> maxConsecutiveFailures)
                .transmissionThreadPrefix("batcher-fuzz-")
                .transport(chunk -> {
                    transmitCount.incrementAndGet();
                    return completedFuture(fuzzedResult(random, chunk, foreignPool, stillPending));
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

        // Drive a fixed, generous number of retry cycles unconditionally (not just until stillPending looks
        // empty: a corrupted rejected list could smuggle a foreign element into the live batch, which would
        // then keep cycling through retries on its own even after every real element has resolved). Every
        // round sends the whole batch, and each element still rejected adds one to its own streak, so
        // maxConsecutiveFailures rounds are always enough to force a give-up; the margin is only a margin.
        for (int i = 0; i < maxConsecutiveFailures + 20; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then: every element ended up either genuinely accepted (no longer pending) or explicitly reported
        // dropped - never neither, which is exactly what "silently lost" would look like
        Set<Elem> unaccountedFor = Collections.newSetFromMap(new IdentityHashMap<>());
        unaccountedFor.addAll(stillPending);
        Set<Elem> droppedIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        droppedIdentity.addAll(dropped);
        unaccountedFor.removeAll(droppedIdentity);
        assertTrue(unaccountedFor.isEmpty(), seedMessage + ": elements neither accepted nor dropped: " + unaccountedFor);

        // and: the batch actually drained, instead of an element being stuck in limbo forever, retried but
        // never making progress towards either outcome
        int transmitCountBeforeFinalFlush = transmitCount.get();
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertEquals(transmitCountBeforeFinalFlush, transmitCount.get(),
                seedMessage + ": an extra flush() should not trigger another transmission once everything settled");
    }

    /**
     * Builds a randomized, occasionally-malformed {@link TransmissionResult} for the given chunk, updating
     * {@code stillPending} to reflect which elements the fake transport genuinely (as ground truth) accepts
     * this round.
     *
     * @param random      The source of randomness for this trial
     * @param chunk       The chunk handed to this transmission attempt
     * @param foreignPool Elements never part of any real chunk, occasionally injected as noise
     * @param stillPending The set of elements not yet genuinely accepted, updated in place
     * @return The (possibly malformed) result to complete this attempt's future with
     */
    private TransmissionResult<Elem> fuzzedResult(Random random, List<Elem> chunk, List<Elem> foreignPool,
            Set<Elem> stillPending) {
        int outcome = random.nextInt(3);
        if (outcome == 0) {
            // Whole chunk genuinely accepted.
            stillPending.removeAll(chunk);
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
        List<Elem> realAccepted = shuffled.subList(rejectedCount, shuffled.size());
        stillPending.removeAll(realAccepted);

        // Corrupt the reported rejected list with noise a well-behaved transport would never produce: a
        // duplicate reference and/or a foreign element, occasionally pushing its size past the chunk's own
        // - exactly the shape that used to crash withoutIdentity()'s capacity calculation.
        List<Elem> reported = new ArrayList<>(realRejected);
        if (random.nextBoolean()) {
            reported.add(realRejected.get(random.nextInt(realRejected.size())));
        }
        if (random.nextBoolean()) {
            reported.add(foreignPool.get(random.nextInt(foreignPool.size())));
        }
        return TransmissionResult.partialFailure(reported, null);
    }

}
