package com.chavaillaz.appender;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
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
        assertThrows(IllegalArgumentException.class, () -> Batcher.<String>builder()
                .maxConcurrentTransmissions(0)
                .transport(elements -> completedFuture(TransmissionResult.success()))
                .build());
    }

    @Test
    @DisplayName("Rejects a missing transport function at construction")
    void testRejectsMissingTransport() {
        assertThrows(NullPointerException.class, () -> Batcher.builder().build());
    }

    @Test
    @DisplayName("Rejects a null element before accumulating any of the elements given with it")
    void testRejectsNullElements() throws Exception {
        // given
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = batcher(2, elements -> {
            transmitted.add(elements);
            return completedFuture(TransmissionResult.success());
        }, BatcherListener.noop());

        // when
        assertThrows(NullPointerException.class, () -> batcher.add(Arrays.asList("a", null)));
        batcher.add(List.of("b", "c"));
        batcher.awaitPendingTransmissions();

        // then: nothing of the rejected call was kept, the batch stacking none of the elements before the null
        assertEquals(List.of(List.of("b", "c")), transmitted);
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
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxPendingElements(() -> 10)
                .maxConsecutiveFailures(() -> maxConsecutiveFailures)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(listener)
                .build();
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
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 100L)
                .maxPendingElements(maxPending::get)
                .transport(elements -> completedFuture(TransmissionResult.success()))
                .listener(listener)
                .build();

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
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 3L)
                .maxPendingElements(() -> 10)
                .transport(elements -> {
                    attempts.add(elements);
                    List<String> rejected = elements.stream().filter("b"::equals).toList();
                    return completedFuture(rejected.isEmpty()
                            ? TransmissionResult.success()
                            : TransmissionResult.partialFailure(rejected, null));
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onSucceeded(int count, long durationMillis) {
                        succeeded.add(count);
                    }
                })
                .build();

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
        Batcher<String> batcher = Batcher.<String>builder()
                .maxPendingElements(() -> 10)
                .maxConsecutiveFailures(() -> 2)
                .transport(elements -> {
                    attempts.incrementAndGet();
                    return completedFuture(TransmissionResult.partialFailure(List.of(elements.get(0), elements.get(0)), null));
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();

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

    @Test
    @DisplayName("Never holds up the client thread completing a transmission with the handling of its result")
    void testResultIsHandledOffTheThreadCompletingIt() throws Exception {
        // given: a future the client completes later from a thread of its own, as the I/O thread of an
        // asynchronous HTTP client does, and a listener slow to return, as one writing to a file is
        var pending = new CompletableFuture<TransmissionResult<String>>();
        var releaseListener = new CountDownLatch(1);
        var listenerThread = new CompletableFuture<Thread>();
        Batcher<String> batcher = batcher(1, elements -> pending, new BatcherListener<>() {
            @Override
            public void onSucceeded(int count, long durationMillis) {
                listenerThread.complete(Thread.currentThread());
                try {
                    releaseListener.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        batcher.add(List.of("a"));
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (pending.getNumberOfDependents() == 0 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5);
        }

        // when
        var client = Thread.ofPlatform().name("client-io")
                .start(() -> pending.complete(TransmissionResult.success()));

        // then: the client thread is free to serve its other requests while the listener still runs elsewhere
        client.join(SECONDS.toMillis(5));
        assertFalse(client.isAlive(), "The client thread should not have waited for the listener");
        assertNotSame(client, listenerThread.get(5, SECONDS));

        // cleanup
        releaseListener.countDown();
        batcher.awaitPendingTransmissions();
    }

    @Test
    @DisplayName("Keeps the elements of a failed request whose transport tried to empty the list it was given")
    void testTransportCannotChangeTheElementsItWasGiven() throws Exception {
        // given: a transport clearing the list it was handed once done with it, then reporting a failure
        var attempts = new CopyOnWriteArrayList<List<String>>();
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConsecutiveFailures(() -> 2)
                .transport(elements -> {
                    attempts.add(List.copyOf(elements));
                    elements.clear();
                    return completedFuture(TransmissionResult.failure(null));
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("a", "b"));

        // when: transmitted, then retried
        batcher.flush();
        batcher.awaitPendingTransmissions();
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: the list the engine retries from was not the transport's to empty, so the elements were
        // retried, then given up on and reported, rather than lost after the first attempt without a trace
        assertEquals(List.of(List.of("a", "b"), List.of("a", "b")), attempts);
        assertEquals(List.of("a", "b"), dropped);
    }

    @Test
    @DisplayName("Never requeues an element a PartialFailure referenced but the chunk never contained")
    void testPartialFailureCannotInjectForeignElements() throws Exception {
        // given: a misbehaving transport reporting, alongside the element it was actually given, one that
        // was never part of the chunk (nor of this Batcher at all)
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConsecutiveFailures(() -> 0)
                .transport(elements -> {
                    attempts.add(elements);
                    return completedFuture(TransmissionResult.partialFailure(List.of(elements.get(0), "foreign"), null));
                })
                .listener(BatcherListener.noop())
                .build();
        batcher.add(List.of("a"));

        // when: retried a few times, each attempt reporting the same foreign element again
        for (int i = 0; i < 3; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then: only the element genuinely dispatched is ever retried, the foreign one never making its way
        // into the pending batch (where it would be transmitted as if it were real data)
        assertEquals(3, attempts.size());
        assertTrue(attempts.stream().allMatch(List.of("a")::equals), "Attempts should only ever contain \"a\", but were " + attempts);
    }

    @Test
    @DisplayName("Splits a batch into chunks that each stay under the maximum request size")
    void testChunksByRequestBytes() throws Exception {
        // given: elements measured at 10 bytes each and a 25 byte request limit, so chunks hold two of them
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 5L)
                // One at a time, so the chunks arrive in the order they were cut rather than racing each other
                .maxConcurrentTransmissions(1)
                .elementSize(element -> 10)
                .maxRequestBytes(() -> 25L)
                .transport(elements -> {
                    attempts.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when
        batcher.add(List.of("a", "b", "c", "d", "e"));
        batcher.awaitPendingTransmissions();

        // then: cut before the element that would have pushed a chunk over, never after
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d"), List.of("e")), attempts);
    }

    @Test
    @DisplayName("Sends an element larger than the maximum request size on its own rather than never at all")
    void testOversizedElementIsStillDispatchedAlone() throws Exception {
        // given: "big" alone weighs more than a whole request is allowed to, and cannot be split any further
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                // One at a time, so the chunks arrive in the order they were cut rather than racing each other
                .maxConcurrentTransmissions(1)
                .flushThreshold(() -> 3L)
                .elementSize(element -> element.equals("big") ? 500 : 10)
                .maxRequestBytes(() -> 100L)
                .transport(elements -> {
                    attempts.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when
        batcher.add(List.of("a", "big", "b"));
        batcher.awaitPendingTransmissions();

        // then: it goes out alone (for the transport to accept or reject on its own merits) instead of
        // stalling the batch behind an element no chunk can ever fit
        assertEquals(List.of(List.of("a"), List.of("big"), List.of("b")), attempts);
    }

    @Test
    @DisplayName("Discards the oldest elements once the pending batch weighs more than allowed")
    void testPendingBytesCapDiscardsOldestElements() throws Exception {
        // given: a batch that never transmits on its own, capped at 25 bytes with elements of 10
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        var mutableLists = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 100L)
                .elementSize(element -> 10)
                .maxPendingBytes(() -> 25L)
                .transport(elements -> completedFuture(TransmissionResult.success()))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                        reasons.add(reason);
                        if (isModifiable(elements)) {
                            mutableLists.add(elements);
                        }
                    }
                })
                .build();

        // when: four elements weigh 40 bytes, 15 over the cap
        batcher.add(List.of("a", "b"));
        batcher.add(List.of("c"));
        batcher.add(List.of("d"));
        awaitDropped(dropped, 2);

        // then: the two oldest were discarded, leaving the batch back under the cap. Sorted, since each add()
        // above discarded one of them and notified it on a thread of its own, so they land in either order
        assertEquals(List.of("a", "b"), dropped.stream().sorted().toList());
        assertEquals(List.of("maximum pending bytes exceeded"), reasons.stream().distinct().toList());

        // and: handed over as the unmodifiable list this callback promises, like every other discard path,
        // rather than the working list this one used to expose to whatever the listener does with it
        assertEquals(List.of(), mutableLists);

        // and: what is left is exactly what the cap allows to be kept
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertFalse(batcher.isTransmissionFailing());
    }

    @Test
    @DisplayName("Never measures an element when no measurement was supplied at all")
    void testNoMeasurementWithoutAnElementSizeFunction() throws Exception {
        // given: byte limits set but no way to measure against them, which must be inert rather than
        // treating every element as weightless-but-still-counted (or worse, as unbounded)
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 3L)
                .maxRequestBytes(() -> 1L)
                .maxPendingBytes(() -> 1L)
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when
        batcher.add(List.of("a", "b", "c"));
        batcher.awaitPendingTransmissions();

        // then: neither limit did anything, despite being far below what three elements would weigh
        assertEquals(List.of(List.of("a", "b", "c")), transmitted);
    }

    @Test
    @DisplayName("Treats a measurement that throws as weightless instead of failing the pipeline")
    void testThrowingElementSizeIsTolerated() throws Exception {
        // given
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 2L)
                .elementSize(element -> {
                    throw new IllegalStateException("cannot measure " + element);
                })
                .maxRequestBytes(() -> 100L)
                .maxPendingBytes(() -> 100L)
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when
        batcher.add(List.of("a", "b"));
        batcher.awaitPendingTransmissions();

        // then: the byte limits simply go unenforced, rather than the elements being lost over a faulty estimate
        assertEquals(List.of(List.of("a", "b")), transmitted);
    }

    @Test
    @DisplayName("Reports the elements still pending when closed as dropped instead of discarding them silently")
    void testCloseReportsStillPendingElementsAsDropped() {
        // given: a transport that never succeeds, and no consecutive-failure limit, so the final flush's
        // documents are put back for a retry that will never come — this used to end there, the pending
        // batch simply going away with the engine, with no log line and no listener call to show for it
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConsecutiveFailures(() -> 0)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                        reasons.add(reason);
                    }
                })
                .build();
        batcher.add(List.of("a", "b"));

        // when
        batcher.close();

        // then: a dead-letter sink gets its last chance at them
        assertEquals(List.of("a", "b"), dropped);
        assertEquals(List.of("still pending when closed"), reasons);
    }

    @Test
    @DisplayName("Gives up closing once the timeout elapses instead of waiting forever on a stuck transport")
    void testCloseIsBoundedByItsTimeout() throws Exception {
        // given: a transport whose future is never completed, holding the single transmission permit for good
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConcurrentTransmissions(1)
                .transport(elements -> new CompletableFuture<>())
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("stuck"));
        batcher.flush();
        batcher.add(List.of("pending"));

        // when: closing, which must both wait for a permit the stuck transmission never gives back and then
        // wait for that transmission itself, neither of which can be allowed to block a JVM shutdown forever
        long start = System.nanoTime();
        batcher.close(Duration.ofMillis(200));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // then
        assertTrue(elapsedMillis < 5_000, "close() should have given up quickly, but took " + elapsedMillis + "ms");
        assertEquals(List.of("pending"), dropped, "The element never handed to the stuck transport should be reported");
    }

    @Test
    @DisplayName("Still transmits the final flush when closed with no time to wait, a free permit needing none")
    void testCloseWithoutTimeToWaitStillTransmitsTheFinalFlush() throws Exception {
        // given: a pending batch and every transmission permit free
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = batcher(10, elements -> {
            transmitted.add(elements);
            return completedFuture(TransmissionResult.success());
        }, new BatcherListener<>() {
            @Override
            public void onDropped(List<String> elements, String reason) {
                dropped.addAll(elements);
            }
        });
        batcher.add(List.of("a", "b"));

        // when: closed without waiting for anything
        batcher.close(Duration.ZERO);
        batcher.awaitPendingTransmissions();

        // then: a zero budget only means not waiting, the batch still going out on a permit already free
        // instead of being reported dropped without an attempt
        assertEquals(List.of(List.of("a", "b")), transmitted);
        assertEquals(List.of(), dropped);
    }

    @Test
    @DisplayName("Releases a caller blocked waiting for a permit once a bounded close begins")
    void testBlockedDispatchIsReleasedByALaterClose() throws Exception {
        // given: the single transmission permit held for good by a transport whose future never completes,
        // so the next dispatch blocks waiting for it with no close in progress yet
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1L)
                .maxConcurrentTransmissions(1)
                .maxConsecutiveFailures(() -> 0)
                .transport(elements -> new CompletableFuture<>())
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("stuck"));

        var blocked = new AtomicBoolean(true);
        var adder = new Thread(() -> {
            batcher.add(List.of("waiting"));
            blocked.set(false);
        });
        adder.setDaemon(true);
        adder.start();
        Thread.sleep(300);
        assertTrue(blocked.get(), "The second add() should be waiting for the only transmission permit");

        // when: closing with a budget, which the blocked caller must notice rather than wait out an
        // unbounded acquire it entered before the deadline existed
        batcher.close(Duration.ofMillis(200));

        // then: it comes back instead of staying blocked for as long as the transport stays stuck
        adder.join(SECONDS.toMillis(10));
        assertFalse(blocked.get(), "The blocked add() should have been released by the close");
        assertFalse(adder.isAlive(), "The blocked add() should have returned");
    }

    @Test
    @DisplayName("Reports elements a transmission hands back after closing drained the batch")
    void testElementsRequeuedAfterCloseDrainedAreReported() throws Exception {
        // given: a transport holding its future open, so closing gives up on it and drains the batch while
        // that transmission is still in flight. Completing it afterwards puts its elements back into a batch
        // nothing is left to transmit or report, which used to lose them without a trace.
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        var inFlight = new CopyOnWriteArrayList<CompletableFuture<TransmissionResult<String>>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1L)
                .maxConsecutiveFailures(() -> 0)
                .transport(elements -> {
                    var future = new CompletableFuture<TransmissionResult<String>>();
                    inFlight.add(future);
                    return future;
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                        reasons.add(reason);
                    }
                })
                .build();
        batcher.add(List.of("stuck"));
        // Dispatch hands the batch to a virtual thread, so the transport is reached shortly after add()
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
        while (inFlight.isEmpty() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5);
        }
        assertEquals(1, inFlight.size(), "The element should have been dispatched");

        // when: closing gives up on the transmission, then it finally fails and hands its element back
        batcher.close(Duration.ofMillis(200));
        assertTrue(dropped.isEmpty(), "The element is still held by the transport, not dropped yet");
        inFlight.get(0).complete(TransmissionResult.failure(null));
        awaitDropped(dropped, 1);

        // then: it is reported rather than merged back into a batch nobody will ever look at again
        assertEquals(List.of("stuck"), dropped);
        assertEquals(List.of("still pending when closed"), reasons);
    }

    @Test
    @DisplayName("Reports elements submitted after being closed as dropped instead of stacking them for nobody")
    void testElementsSubmittedAfterCloseAreReported() {
        // given
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = batcher(10, elements -> completedFuture(TransmissionResult.success()),
                new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                        reasons.add(reason);
                    }
                });
        batcher.close();

        // when: a late caller (a log event racing the shutdown) still hands over an element
        batcher.add(List.of("late"));

        // then: reported rather than stacked into a batch nothing will ever transmit, its dispatch being
        // rejected by the shut-down executor
        assertEquals(List.of("late"), dropped);
        assertEquals(List.of("submitted after close"), reasons);
    }

    @Test
    @DisplayName("Holds off retrying while the backoff policy says to wait, then retries once it elapses")
    void testBackoffHoldsOffRetries() throws Exception {
        // given
        var attempts = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConsecutiveFailures(() -> 0)
                .backoff(consecutiveFailures -> 200)
                .transport(elements -> {
                    attempts.incrementAndGet();
                    return completedFuture(TransmissionResult.failure(null));
                })
                .listener(BatcherListener.noop())
                .build();
        batcher.add(List.of("a"));

        // when: a first attempt fails
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: the next flush is held off rather than immediately hammering the failing transport again
        assertEquals(1, attempts.get());
        assertTrue(batcher.isBackingOff());
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertEquals(1, attempts.get(), "The retry should have been held off by the backoff");

        // when: the backoff elapses
        awaitBackoffElapsed(batcher);
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: retrying resumes on its own, without any further prompting
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("Ignores an active backoff while closing, that transmission being the last chance to send at all")
    void testCloseTransmitsDespiteAnActiveBackoff() throws Exception {
        // given: a backoff far longer than this test, engaged by a first failing attempt
        var attempts = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConsecutiveFailures(() -> 0)
                .backoff(consecutiveFailures -> 60_000)
                .transport(elements -> completedFuture(
                        attempts.incrementAndGet() > 1 ? TransmissionResult.success() : TransmissionResult.failure(null)))
                .listener(BatcherListener.noop())
                .build();
        batcher.add(List.of("a"));
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertTrue(batcher.isBackingOff());

        // when
        batcher.close();

        // then: the second attempt happened despite the backoff, and succeeded
        assertEquals(2, attempts.get());
        assertFalse(batcher.isTransmissionFailing());
    }

    private static void awaitBackoffElapsed(Batcher<String> batcher) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (batcher.isBackingOff()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for the backoff to elapse");
            }
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("Bounds how many drop notifications a slow listener can have in flight at once")
    void testSlowListenerCannotAccumulateDropNotifications() throws Exception {
        // given: a listener that never returns, and a pending cap of one so that every add() past the first
        // discards. Handing each notification to its own thread would let them (and the elements each one
        // holds) pile up without bound, turning the cap that bounds memory into the thing consuming it.
        var release = new java.util.concurrent.CountDownLatch(1);
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                // Never reached, so nothing ever drains the batch and every add() trips the pending cap
                .flushThreshold(() -> Long.MAX_VALUE)
                .maxPendingElements(() -> 1)
                .transport(elements -> new CompletableFuture<>())
                .listener(new BatcherListener<String>() {
                    @Override
                    public void onDropped(List<String> dropped, String reason) {
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        inFlight.decrementAndGet();
                    }
                })
                .build();

        // when: adding from a thread of its own, since saturating the bound makes add() notify inline and
        // therefore block on the listener above, which is the backpressure being asserted
        var adder = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                batcher.add(List.of("element-" + i));
            }
        });
        adder.setDaemon(true);
        adder.start();

        // then: notifications stop being handed off well before the 500 adds, rather than one per add
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
        while (inFlight.get() < 5 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5);
        }
        Thread.sleep(200);
        assertTrue(peak.get() <= 5,
                "At most four notifications should be in flight, plus the one blocking the caller, but peaked at " + peak.get());

        release.countDown();
        adder.join(SECONDS.toMillis(5));
    }

    @Test
    @DisplayName("Retries a chunk whose transport threw an Error instead of leaking its transmission permit")
    void testTransportThrowingAnErrorDoesNotLeakAPermit() throws Exception {
        // given: a single transmission permit, and a transport whose first attempt throws an Error rather
        // than an Exception (a NoClassDefFoundError from a missing runtime dependency, a StackOverflowError
        // from serializing a self-referencing element, ...). This used to escape the virtual thread before
        // whenComplete() was registered, so the permit was never released and the elements never came back
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var attempts = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1L)
                .maxConcurrentTransmissions(1)
                .transport(elements -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new StackOverflowError("transport blew up");
                    }
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when: the first element fails on the Error, the second one needs the very permit it was holding
        batcher.add(List.of("a"));
        // Awaiting here rather than adding straight away makes the retry deterministic: the permit is only
        // released once the failure has been handled, so "a" is provably back in the pending batch by then
        batcher.awaitPendingTransmissions();
        batcher.add(List.of("b"));
        batcher.awaitPendingTransmissions();

        // then: the Error was treated as an ordinary failure, so "a" was put back and went out with "b"
        assertEquals(2, attempts.get());
        assertEquals(List.of(List.of("a", "b")), transmitted);
    }

    @Test
    @DisplayName("Still requeues a failed chunk when the listener notified of that failure throws an Error")
    void testListenerThrowingAnErrorDoesNotLoseElements() throws Exception {
        // given: a listener whose onFailed throws an Error, sitting between the failure and the retry
        // handling it must never skip
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConsecutiveFailures(() -> 0)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onFailed(int count, Throwable cause) {
                        throw new StackOverflowError("listener blew up");
                    }

                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("a"));

        // when
        batcher.close();

        // then: the element was put back and reported on close, rather than vanishing with the notification
        assertEquals(List.of("a"), dropped);
    }

    @Test
    @DisplayName("Still requeues a failed chunk when the backoff policy consulted for it throws an Error")
    void testBackoffPolicyThrowingAnErrorDoesNotLoseElements() {
        // given: a policy throwing an Error, consulted between the decision to retry and the requeue
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxConsecutiveFailures(() -> 0)
                .backoff(consecutiveFailures -> {
                    throw new StackOverflowError("policy blew up");
                })
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("a"));

        // when
        batcher.close();

        // then
        assertEquals(List.of("a"), dropped);
    }

    @Test
    @DisplayName("Treats a measurement throwing an Error as weightless instead of propagating to the caller")
    void testElementSizeThrowingAnErrorIsTolerated() throws Exception {
        // given: a measurement throwing an Error, called by add() on a batch already appended to but not
        // yet accounted for in the running byte total
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 2L)
                .elementSize(element -> {
                    throw new StackOverflowError("cannot measure " + element);
                })
                .maxPendingBytes(() -> 100L)
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when
        batcher.add(List.of("a", "b"));
        batcher.awaitPendingTransmissions();

        // then: the byte limit simply goes unenforced, instead of the Error reaching whoever called add()
        assertEquals(List.of(List.of("a", "b")), transmitted);
    }

    private static Batcher<String> batcher(long flushThreshold,
            Function<List<String>, CompletableFuture<TransmissionResult<String>>> transport, BatcherListener<String> listener) {
        return Batcher.<String>builder()
                .flushThreshold(() -> flushThreshold)
                .transport(transport)
                .listener(listener)
                .build();
    }

    @Test
    @DisplayName("Keeps a batch whose request limit could not be read, instead of losing it on the way out")
    void testThrowingRequestLimitDoesNotLoseTheBatch() throws Exception {
        // given: a limit supplier that throws, which is the shape a delivery reading a configuration it
        // re-reads at runtime takes when that configuration is momentarily unavailable. It is read after
        // the batch has been swapped out but before anything has taken responsibility for it
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var broken = new AtomicBoolean(true);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 2L)
                .maxRequestElements(() -> {
                    if (broken.get()) {
                        throw new IllegalStateException("configuration unavailable");
                    }
                    return Integer.MAX_VALUE;
                })
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when: the threshold is reached while the limit cannot be read
        batcher.add(List.of("a", "b"));
        batcher.awaitPendingTransmissions();

        // then: the chunking limit simply went unenforced and the batch went out whole, rather than being
        // carried off by the exception -- never transmitted, never put back, never reported
        assertEquals(List.of(List.of("a", "b")), transmitted);
    }

    @Test
    @DisplayName("Keeps a failed batch whose pending limit could not be read while putting it back")
    void testThrowingPendingLimitDoesNotLoseTheRequeue() throws Exception {
        // given: the same kind of supplier, read this time while a failed transmission is being put back
        var dropped = new CopyOnWriteArrayList<String>();
        var broken = new AtomicBoolean(false);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Long.MAX_VALUE)
                .maxPendingElements(() -> {
                    if (broken.get()) {
                        throw new IllegalStateException("configuration unavailable");
                    }
                    return 1_000;
                })
                .maxConsecutiveFailures(() -> 0)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("a"));

        // when: the transmission fails while the limit cannot be read
        broken.set(true);
        batcher.flush();
        batcher.awaitPendingTransmissions();
        broken.set(false);
        batcher.close();

        // then: the element made it back into the batch and was reported on close, rather than being
        // dropped on the floor between the failure and the requeue
        assertEquals(List.of("a"), dropped);
    }

    @Test
    @DisplayName("Never charges a failure streak to elements the requeue that carried it kept none of")
    void testFailureStreakIsNotInheritedByUntaintedElements() throws Exception {
        // given: a pending cap of three and a limit of two consecutive failures, the shape a sustained
        // outage settles into -- the batch sitting at its cap, so each retry cycle trims away exactly the
        // elements it just put back
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Long.MAX_VALUE)
                .maxPendingElements(() -> 3)
                .maxConsecutiveFailures(() -> 2)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();

        // when: three elements fail once and are put back, carrying a streak of one
        batcher.add(List.of("old-1", "old-2", "old-3"));
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // and: three newer ones push every one of them out of the batch, then fail once themselves
        batcher.add(List.of("new-1", "new-2", "new-3"));
        awaitDropped(dropped, 3);
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: only the elements that really did fail twice were given up on. The newer ones have a single
        // failure of their own, and used to be discarded alongside them on a streak they never earned
        assertEquals(List.of("old-1", "old-2", "old-3"), dropped);
    }

    @Test
    @DisplayName("Counts the failures of an element joining a failed one from its own first attempt")
    void testFailureStreakIsNotInheritedByElementsJoiningTheBatch() throws Exception {
        // given: two consecutive failures allowed, and a transport failing every attempt
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Long.MAX_VALUE)
                .maxConsecutiveFailures(() -> 2)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();

        // when: an element fails once, then a newer one joins it and both fail together
        batcher.add(List.of("old"));
        batcher.flush();
        batcher.awaitPendingTransmissions();
        batcher.add(List.of("new"));
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: only the element that failed twice was given up on. The newer one failed once, and used to
        // be discarded along with it, after a single attempt, on the streak of the batch it joined
        assertEquals(List.of("old"), dropped);

        // when: the newer one fails a second time
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: it is given up on in turn
        assertEquals(List.of("old", "new"), dropped);
    }

    @Test
    @DisplayName("Keeps the failure streak of each element across the requests a batch is split into")
    void testFailureStreaksFollowTheirElementsIntoChunks() throws Exception {
        // given: requests of two elements at most, three consecutive failures allowed, and a transport
        // rejecting everything but "fresh-1"
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Long.MAX_VALUE)
                .maxRequestElements(() -> 2)
                .maxConsecutiveFailures(() -> 3)
                .transport(elements -> {
                    List<String> rejected = elements.stream().filter(element -> !element.equals("fresh-1")).toList();
                    return completedFuture(rejected.isEmpty()
                            ? TransmissionResult.success()
                            : TransmissionResult.partialFailure(rejected, null));
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();

        // when: three elements fail twice, then three newer ones join them, the batch of six being split in
        // requests mixing both, which fail once more
        batcher.add(List.of("old-1", "old-2", "old-3"));
        for (int i = 0; i < 2; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }
        batcher.add(List.of("fresh-1", "fresh-2", "fresh-3"));
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: only the three that failed three times were given up on, whichever request carried them
        assertEquals(Set.of("old-1", "old-2", "old-3"), Set.copyOf(dropped));

        // when: the two newer ones still rejected fail twice more
        for (int i = 0; i < 2; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then: they are given up on in turn, the accepted one never being
        assertEquals(Set.of("old-1", "old-2", "old-3", "fresh-2", "fresh-3"), Set.copyOf(dropped));
        assertEquals(5, dropped.size());
    }

    @Test
    @DisplayName("Never hands the transport an empty request, however low the flush threshold")
    void testNeverTransmitsAnEmptyBatch() throws Exception {
        // given: a non-positive threshold (allowed, meaning "no batching") and a pending cap that discards
        // every element on the way in, so nothing is ever left to send
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 0L)
                .maxPendingElements(() -> 0)
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();

        // when
        batcher.add(List.of("a"));
        batcher.awaitPendingTransmissions();

        // then: the element was reported as discarded, and nothing was sent on its behalf
        awaitDropped(dropped, 1);
        assertEquals(List.of("a"), dropped);
        assertEquals(List.of(), transmitted);
        assertFalse(batcher.isTransmissionFailing());
    }

    @Test
    @DisplayName("Clamps a close timeout too large to express in milliseconds instead of waiting forever")
    void testOversizedCloseTimeoutIsClampedRatherThanUnbounded() {
        // given: a year, the cap past which every timeout is indistinguishable from waiting indefinitely
        long oneYearMillis = Duration.ofDays(365).toMillis();

        // then: only an absent timeout means "wait indefinitely"
        assertNull(Batcher.toMillis(null));
        assertEquals(0L, Batcher.toMillis(Duration.ofSeconds(-1)));
        assertEquals(1_000L, Batcher.toMillis(Duration.ofSeconds(1)));
        assertEquals(oneYearMillis, Batcher.toMillis(Duration.ofDays(1_000)));

        // and: a duration too large for Duration#toMillis() to express at all is past that cap too, rather
        // than the one value that leaves close() with no deadline in effect and therefore never times out
        assertEquals(oneYearMillis, Batcher.toMillis(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    @DisplayName("Reports its own diagnostics on Log4j2's status channel, not through an application logger")
    void testDiagnosticsGoToTheStatusLoggerNotTheApplicationPipeline() throws Exception {
        // given: an appender built on this engine is normally attached to the root logger, so anything this
        // class logs through an ordinary LogManager logger comes straight back to it as one more element to
        // transmit -- whose own failure logs again. That loop does not settle, and it starts exactly when a
        // downstream system goes down: one application log line against a failing transport produced sixty
        // thousand self-inflicted documents in three seconds before this was fixed. Log4j2's status channel
        // is where an appender's internals are expected to speak, and it feeds nothing back.
        var statusMessages = new CopyOnWriteArrayList<String>();
        var statusListener = new StatusListener() {
            @Override
            public void log(StatusData data) {
                statusMessages.add(data.getFormattedStatus());
            }

            @Override
            public Level getStatusLevel() {
                return Level.WARN;
            }

            @Override
            public void close() {
                // Nothing to release
            }
        };
        StatusLogger.getLogger().registerListener(statusListener);

        // when: a pending cap of 1 makes the second element discard the first, which is logged
        try {
            Batcher<String> batcher = Batcher.<String>builder()
                    .flushThreshold(() -> 10L)
                    .maxPendingElements(() -> 1)
                    .transport(elements -> completedFuture(TransmissionResult.success()))
                    .build();
            batcher.add(List.of("a"));
            batcher.add(List.of("b"));
            batcher.close();
        } finally {
            StatusLogger.getLogger().removeListener(statusListener);
        }

        // then
        assertTrue(statusMessages.stream().anyMatch(message -> message.contains("maximum pending element")),
                "Expected the discard on the status channel, got: " + statusMessages);
    }

    @Test
    @DisplayName("Takes a snapshot of what it holds right now, and of what it did so far")
    void testMetricsTrackWhatItHoldsAndDid() throws Exception {
        // given: a first request left unanswered until the test answers it, and a failing next one
        var unanswered = new CompletableFuture<TransmissionResult<String>>();
        var requests = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10L)
                .maxPendingElements(() -> 3)
                .elementSize(element -> 10)
                .maxPendingBytes(() -> 1_000L)
                .transport(elements -> requests.incrementAndGet() == 1
                        ? unanswered
                        : completedFuture(TransmissionResult.failure(null)))
                .build();

        // when: two elements are pending
        batcher.add(List.of("a", "b"));

        // then
        assertEquals(new Batcher.Metrics(2, 20, 0, 0, 0, 0), batcher.metrics());

        // when: they are sent, the request left unanswered, then answered
        batcher.flush();
        assertEquals(new Batcher.Metrics(0, 0, 1, 0, 0, 0), batcher.metrics());
        unanswered.complete(TransmissionResult.success());
        batcher.awaitPendingTransmissions();

        // then
        assertEquals(new Batcher.Metrics(0, 0, 0, 2, 0, 0), batcher.metrics());

        // when: four more push the oldest past the pending cap of three, and the other three fail once
        batcher.add(List.of("c", "d", "e", "f"));
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: the three are pending again, counted with the failed attempt and the element discarded
        assertEquals(new Batcher.Metrics(3, 30, 0, 2, 1, 1), batcher.metrics());
    }

    @Test
    @DisplayName("Logs the cause of every failure the transport returns, before any element is given up on")
    void testReturnedFailureIsLoggedWithItsCause() throws Exception {
        // given: a transport reporting its failure rather than throwing it, the way the README tells one to,
        // and room for several retries before anything is given up on
        var cause = new IOException("connection refused");
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1L)
                .maxConsecutiveFailures(() -> 5)
                .transport(elements -> completedFuture(TransmissionResult.failure(cause)))
                .build();

        // when
        List<StatusData> statuses = captureStatuses(() -> {
            batcher.add(List.of("a"));
            batcher.awaitPendingTransmissions();
        });

        // then: the outage shows from its first failure, with what caused it
        assertTrue(statuses.stream().anyMatch(status -> status.getLevel() == Level.WARN && status.getThrowable() == cause),
                "Expected a warning carrying the cause, got: " + statuses.stream().map(StatusData::getFormattedStatus).toList());
    }

    @Test
    @DisplayName("Logs the cause of the last failure along with the elements it gives up on")
    void testGivingUpIsLoggedWithTheCause() throws Exception {
        // given
        var cause = new IOException("connection refused");
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1L)
                .maxConsecutiveFailures(() -> 1)
                .transport(elements -> completedFuture(TransmissionResult.failure(cause)))
                .build();

        // when
        List<StatusData> statuses = captureStatuses(() -> {
            batcher.add(List.of("a"));
            batcher.awaitPendingTransmissions();
        });

        // then
        assertTrue(statuses.stream().anyMatch(status -> status.getLevel() == Level.ERROR && status.getThrowable() == cause),
                "Expected an error carrying the cause, got: " + statuses.stream().map(StatusData::getFormattedStatus).toList());
    }

    /**
     * Runs the given action while capturing what is logged on the status channel of Log4j2 from warnings up.
     */
    private static List<StatusData> captureStatuses(ThrowingRunnable action) throws Exception {
        var statuses = new CopyOnWriteArrayList<StatusData>();
        var statusListener = new StatusListener() {
            @Override
            public void log(StatusData data) {
                statuses.add(data);
            }

            @Override
            public Level getStatusLevel() {
                return Level.WARN;
            }

            @Override
            public void close() {
                // Nothing to release
            }
        };
        StatusLogger.getLogger().registerListener(statusListener);
        try {
            action.run();
        } finally {
            StatusLogger.getLogger().removeListener(statusListener);
        }
        return statuses;
    }

    /**
     * An action a test captures the statuses of, free to throw what it waits for.
     */
    private interface ThrowingRunnable {

        void run() throws Exception;

    }

    private static boolean isModifiable(List<String> elements) {
        try {
            elements.add("probe");
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
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
