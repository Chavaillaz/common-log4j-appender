package com.chavaillaz.appender;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused tests proving {@link Batcher} is genuinely generic and transport-agnostic: unlike
 * {@link com.chavaillaz.appender.log4j.AbstractBatchLogDeliveryTest}, which exhaustively covers this same
 * accumulate/backpressure/retry behavior through the {@code Map<String,Object>}/Log4j2-flavored facade,
 * this class exercises {@link Batcher} directly, with a non-document element type and no Log4j2 concept in
 * sight, plus the supplier-based dynamic limits that facade relies on to preserve its own overridable
 * {@code getMaxPendingDocuments()}-style hooks, and the {@link TransmissionResult} contract (including
 * {@link TransmissionResult.PartialFailure} and a transport completing asynchronously).
 */
@DisplayName("Batcher")
class BatcherTest {

    @Test
    @DisplayName("Rejects a non-positive maximum number of concurrent transmissions at construction")
    void testRejectsNonPositiveMaxConcurrentTransmissions() {
        assertThrows(IllegalArgumentException.class,
                () -> new Batcher<>(() -> 1L, () -> 10, () -> 10, () -> 5, 0, "batcher-test-", "element",
                        elements -> completedFuture(TransmissionResult.success()), BatcherListener.noop()));
    }

    @Test
    @DisplayName("Accumulates elements of an arbitrary type and transmits once the threshold is reached")
    void testAccumulatesAndTransmitsGenericElements() throws Exception {
        // given: a Batcher<String>, proving it has no dependency on any document/log4j type
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = batcher(2, elements -> {
            transmitted.add(elements);
            return completedFuture(TransmissionResult.success());
        }, BatcherListener.noop());

        // when
        batcher.add(List.of("a"));
        batcher.add(List.of("b"));
        batcher.awaitPendingTransmissions();

        // then: threshold of 2 was reached by "a" + "b"
        assertEquals(1, transmitted.size());
        assertEquals(List.of("a", "b"), transmitted.get(0));

        // when: "c" alone is below the threshold, so it stays pending until explicitly flushed
        batcher.add(List.of("c"));
        assertEquals(1, transmitted.size());
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then
        assertEquals(2, transmitted.size());
        assertEquals(List.of("c"), transmitted.get(1));
    }

    @Test
    @DisplayName("Retries the batch on the next flush when a transmission fails")
    void testRetriesOnFailure() throws Exception {
        // given
        var attempts = new AtomicInteger();
        var succeed = new AtomicInteger(-1); // becomes true only once this many attempts have been made
        Batcher<String> batcher = batcher(1,
                elements -> completedFuture(attempts.incrementAndGet() > succeed.get()
                        ? TransmissionResult.success()
                        : TransmissionResult.failure(null)),
                BatcherListener.noop());
        succeed.set(1);

        // when
        batcher.add(List.of("poison"));
        batcher.awaitPendingTransmissions();
        batcher.flush(); // no-op: nothing pending yet, "poison" itself is still mid-retry cycle
        batcher.awaitPendingTransmissions();

        // then: the first attempt failed and was requeued, the second (triggered by the explicit flush
        // above, since threshold 1 alone would otherwise not retry a failing batch on every new element)
        // succeeded
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("Reports itself as failing after a failed transmission, and healthy again once one succeeds")
    void testIsTransmissionFailingReflectsLastAttempt() throws Exception {
        // given: fails until told to succeed
        var succeeding = new AtomicBoolean(false);
        Batcher<String> batcher = batcher(1,
                elements -> completedFuture(succeeding.get() ? TransmissionResult.success() : TransmissionResult.failure(null)),
                BatcherListener.noop());

        // then: nothing attempted yet
        assertFalse(batcher.isTransmissionFailing());

        // when: the only attempt so far fails
        batcher.add(List.of("poison"));
        batcher.awaitPendingTransmissions();

        // then
        assertTrue(batcher.isTransmissionFailing());

        // when: retried and this time succeeds
        succeeding.set(true);
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then
        assertFalse(batcher.isTransmissionFailing());
    }

    @Test
    @DisplayName("Reports dropped elements through the listener once MaxConsecutiveFailures is exceeded")
    void testGivesUpAfterMaxConsecutiveFailures() throws Exception {
        // given
        var dropped = new CopyOnWriteArrayList<String>();
        BatcherListener<String> listener = new BatcherListener<>() {
            @Override
            public void onDropped(List<String> elements, String reason) {
                dropped.addAll(elements);
            }
        };
        int maxConsecutiveFailures = 2;
        Batcher<String> batcher = new Batcher<>(() -> 10L, () -> 10, () -> Integer.MAX_VALUE, () -> maxConsecutiveFailures, 4,
                "batcher-test-", "element", elements -> completedFuture(TransmissionResult.failure(null)), listener);
        batcher.add(List.of("poison"));

        // when: retried (via flush(), since the flush threshold of 10 is never reached on its own) until
        // the limit of consecutive failures is reached
        for (int i = 0; i < maxConsecutiveFailures; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then
        assertEquals(List.of("poison"), dropped);
    }

    @Test
    @DisplayName("Re-reads its dynamic limits on every call instead of capturing them once at construction")
    void testDynamicLimitsAreReReadOnEveryCall() throws Exception {
        // given: a maxPendingElements supplier whose return value changes after construction, exactly how
        // AbstractBatchLogDelivery.getMaxPendingDocuments() can be overridden by a subclass to vary at
        // runtime rather than being fixed once from LogConfiguration
        var dropped = new CopyOnWriteArrayList<String>();
        var maxPending = new AtomicInteger(10);
        BatcherListener<String> listener = new BatcherListener<>() {
            @Override
            public void onDropped(List<String> elements, String reason) {
                dropped.addAll(elements);
            }
        };
        Batcher<String> batcher = new Batcher<>(() -> 100L, maxPending::get, () -> Integer.MAX_VALUE, () -> 5, 4,
                "batcher-test-", "element", elements -> completedFuture(TransmissionResult.success()), listener);

        // when: accumulating under the initial generous limit
        batcher.add(List.of("a", "b", "c"));

        // then: nothing discarded yet
        assertTrue(dropped.isEmpty());

        // when: the limit is lowered (as if the overriding method started returning a smaller value) and
        // another element is accumulated
        maxPending.set(1);
        batcher.add(List.of("d"));
        awaitDropped(dropped, 3);

        // then: the newly-lowered limit was honored immediately, without reconstructing the Batcher
        assertEquals(3, dropped.size());
    }

    @Test
    @DisplayName("Retries only the rejected elements of a PartialFailure, reporting the rest as succeeded")
    void testPartialFailureRetriesOnlyRejectedElements() throws Exception {
        // given: "b" is rejected every attempt, "a" and "c" are accepted the very first time
        var succeeded = new CopyOnWriteArrayList<Integer>();
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = new Batcher<>(() -> 3L, () -> 10, () -> Integer.MAX_VALUE, () -> 5, 4,
                "batcher-test-", "element", elements -> {
                    attempts.add(elements);
                    List<String> rejected = elements.stream().filter("b"::equals).toList();
                    return completedFuture(rejected.isEmpty()
                            ? TransmissionResult.success()
                            : TransmissionResult.partialFailure(rejected, null));
                }, new BatcherListener<>() {
                    @Override
                    public void onSucceeded(int count, long durationMillis) {
                        succeeded.add(count);
                    }
                });

        // when: the first attempt rejects "b" only
        batcher.add(List.of("a", "b", "c"));
        batcher.awaitPendingTransmissions();

        // then: "a" and "c" were reported succeeded (2), only "b" is pending for retry
        assertEquals(List.of(2), succeeded);
        assertEquals(List.of(List.of("a", "b", "c")), attempts);

        // when: retried
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: only "b" (not "a" or "c" again) was retried, and it still fails every time
        assertEquals(List.of(List.of("a", "b", "c"), List.of("b")), attempts);
    }

    @Test
    @DisplayName("Treats a PartialFailure reporting more rejected elements than were dispatched as a failure instead of losing them silently")
    void testMalformedPartialFailureDoesNotLoseElements() throws Exception {
        // given: a misbehaving transport reporting "a" as rejected twice for a chunk that only contains it
        // once; before the fix, this made the accepted/rejected split throw (a negative ArrayList capacity)
        // from inside a whenComplete() callback nobody observes, silently losing "a" without even a log line
        var dropped = new CopyOnWriteArrayList<String>();
        var attempts = new AtomicInteger();
        Batcher<String> batcher = new Batcher<>(() -> 1L, () -> 10, () -> Integer.MAX_VALUE, () -> 2, 4,
                "batcher-test-", "element", elements -> {
                    attempts.incrementAndGet();
                    return completedFuture(TransmissionResult.partialFailure(List.of(elements.get(0), elements.get(0)), null));
                }, new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                });

        // when: retried until the malformed chunk is eventually given up on
        batcher.add(List.of("a"));
        batcher.awaitPendingTransmissions();
        for (int i = 0; i < 2; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then: "a" was retried (not lost after the very first malformed response) and eventually reported
        // dropped once MaxConsecutiveFailures was reached, instead of vanishing without a trace
        assertTrue(attempts.get() >= 2, "The malformed chunk should have been retried, not discarded outright");
        assertTrue(dropped.contains("a"), "\"a\" should have been reported dropped instead of lost silently");
    }

    @Test
    @DisplayName("Does not block the calling thread on a transport that completes its future asynchronously")
    void testDoesNotBlockOnAsynchronousTransport() throws Exception {
        // given: a transport whose future is completed later, from a separate thread, rather than before
        // the transport function itself returns
        var releaseTransport = new CompletableFuture<Void>();
        var transmitted = new AtomicBoolean();
        Batcher<String> batcher = batcher(1, elements -> {
            var future = new CompletableFuture<TransmissionResult<String>>();
            releaseTransport.thenRun(() -> {
                transmitted.set(true);
                future.complete(TransmissionResult.success());
            });
            return future;
        }, BatcherListener.noop());

        // when
        var start = System.nanoTime();
        batcher.add(List.of("a"));
        var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // then: add() returned promptly without waiting for the (still incomplete) transport future
        assertTrue(elapsedMillis < 500, "add() should not block on an incomplete transport future, took " + elapsedMillis + "ms");
        assertTrue(!transmitted.get(), "The transport should not have completed yet");

        // cleanup
        releaseTransport.complete(null);
        batcher.awaitPendingTransmissions();
    }

    private static Batcher<String> batcher(long flushThreshold,
            Function<List<String>, CompletableFuture<TransmissionResult<String>>> transport, BatcherListener<String> listener) {
        return new Batcher<>(() -> flushThreshold, () -> 10_000, () -> Integer.MAX_VALUE, () -> 5, 4,
                "batcher-test-", "element", transport, listener);
    }

    private static void awaitDropped(List<String> dropped, int expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
        while (dropped.size() < expected) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for " + expected + " dropped element(s)");
            }
            Thread.sleep(5);
        }
    }

}
