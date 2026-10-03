package com.chavaillaz.appender.internal;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.DeliveryHealth;
import com.chavaillaz.appender.TransmissionResult;

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
        assertThatThrownBy(() -> Batcher.<String>builder()
                .maxConcurrentTransmissions(0)
                .transport(elements -> completedFuture(TransmissionResult.success()))
                .build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects a missing transport function at construction")
    void testRejectsMissingTransport() {
        assertThatThrownBy(() -> Batcher.builder().build()).isInstanceOf(NullPointerException.class);
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
        assertThatThrownBy(() -> batcher.add(Arrays.asList("a", null))).isInstanceOf(NullPointerException.class);
        batcher.add(List.of("b", "c"));
        batcher.awaitPendingTransmissions();

        // then: nothing of the rejected call was kept, the batch stacking none of the elements before the null
        assertThat(transmitted).containsExactly(List.of("b", "c"));
    }

    @Test
    @DisplayName("Transmits without the context of the thread handing the batch over")
    void testTransmissionDoesNotTakeTheContextOfTheCallingThread() throws Exception {
        // given: a transport reporting the context it runs with
        var requestContext = new InheritableThreadLocal<String>();
        var requestClassLoader = new ClassLoader() {
        };
        var transportContext = new CompletableFuture<List<Object>>();
        Batcher<String> batcher = batcher(1, elements -> {
            transportContext.complete(Arrays.asList(requestContext.get(), Thread.currentThread().getContextClassLoader()));
            return completedFuture(TransmissionResult.success());
        }, BatcherListener.noop());

        // when: the batch is handed over by a thread carrying a context of its own, as the thread undeploying a
        // web application does when it closes the engine, which sends the last batch
        Thread.ofPlatform().start(() -> {
            requestContext.set("request-1");
            Thread.currentThread().setContextClassLoader(requestClassLoader);
            batcher.add(List.of("a"));
        }).join();

        // then: a transmission that never completes runs for good, and a context it took from that thread would
        // be kept alive as long, the class loader of an undeployed application included
        var context = transportContext.get(5, SECONDS);
        assertThat(context.get(0))
                .as("The transmission should not see the inheritable thread-locals of that thread")
                .isNull();
        assertThat(context.get(1))
                .as("The transmission should not use the class loader of that thread")
                .isNotSameAs(requestClassLoader);
        batcher.close();
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
        assertThat(transmitted).hasSize(1);
        assertThat(transmitted.get(0)).containsExactly("a", "b");

        // when: "c" alone is below the threshold, so it stays pending until explicitly flushed
        batcher.add(List.of("c"));
        assertThat(transmitted).hasSize(1);
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then
        assertThat(transmitted).hasSize(2);
        assertThat(transmitted.get(1)).containsExactly("c");
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
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Reports itself retrying after a failed transmission, up once one succeeds, and down once closed")
    void testHealthReflectsTheLastAttempt() throws Exception {
        // given: fails until told to succeed
        var succeeding = new AtomicBoolean(false);
        Batcher<String> batcher = batcher(1,
                elements -> completedFuture(succeeding.get() ? TransmissionResult.success() : TransmissionResult.failure(null)),
                BatcherListener.noop());
        long builtBy = System.nanoTime();

        // then: nothing attempted yet
        assertThat(batcher.health().status()).isEqualTo(DeliveryHealth.Status.UP);

        // when: the only attempt so far fails
        batcher.add(List.of("poison"));
        batcher.awaitPendingTransmissions();

        // then: retried at the next opportunity, without a success since the engine was built
        long checkedAfter = System.nanoTime();
        DeliveryHealth failing = batcher.health();
        assertThat(failing.status()).isEqualTo(DeliveryHealth.Status.RETRYING);
        assertThat(failing.sinceLastSuccess().toNanos()).isGreaterThanOrEqualTo(checkedAfter - builtBy);

        // when: retried and this time succeeds
        succeeding.set(true);
        long retriedAfter = System.nanoTime();
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: counted from that success
        DeliveryHealth recovered = batcher.health();
        assertThat(recovered.status()).isEqualTo(DeliveryHealth.Status.UP);
        assertThat(recovered.sinceLastSuccess().toNanos()).isLessThanOrEqualTo(System.nanoTime() - retriedAfter);

        // when
        batcher.close();

        // then
        assertThat(batcher.health().status()).isEqualTo(DeliveryHealth.Status.DOWN);
    }

    @Test
    @DisplayName("Reports itself backing off while a failure holds transmissions off, whatever succeeded meanwhile")
    void testHealthReportsTheBackoffOfAFailureHandledAlongASuccess() throws Exception {
        // given: a failure whose handling is held up between its notification and the backoff it decides
        var attempts = new AtomicInteger();
        var failing = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var succeeded = new CountDownLatch(1);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1)
                .maxConsecutiveFailures(() -> 0)
                .backoff(consecutiveFailures -> 60_000)
                .transport(elements -> {
                    attempts.incrementAndGet();
                    return completedFuture(elements.contains("fails") ? TransmissionResult.failure(null) : TransmissionResult.success());
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onSucceeded(int count, long durationMillis) {
                        succeeded.countDown();
                    }

                    @Override
                    public void onFailed(int count, Throwable cause) {
                        failing.countDown();
                        try {
                            resume.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .build();
        batcher.add(List.of("fails"));
        assertThat(failing.await(2, SECONDS)).as("The first transmission should have failed").isTrue();

        // when: a concurrent transmission succeeds before that failure holds transmissions off
        batcher.add(List.of("succeeds"));
        assertThat(succeeded.await(2, SECONDS)).as("The second transmission should have succeeded").isTrue();
        resume.countDown();
        batcher.awaitPendingTransmissions();

        // then: transmissions are held off, as the health says, which used to report the success instead
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertThat(attempts.get()).as("The retry should have been held off by the backoff").isEqualTo(2);
        assertThat(batcher.health().status()).isEqualTo(DeliveryHealth.Status.BACKING_OFF);
        batcher.close(Duration.ZERO);
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
                .flushThreshold(() -> 10)
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
        assertThat(dropped).containsExactly("poison");
    }

    @Test
    @DisplayName("Re-reads its dynamic limits on every call instead of capturing them once at construction")
    void testDynamicLimitsAreReReadOnEveryCall() throws Exception {
        // given: a maxPendingElements supplier whose return value changes after construction, exactly how
        // AbstractBatchLogDelivery.getMaxPendingDocuments() can be overridden by a subclass to vary at
        // runtime rather than being fixed once from LogConfiguration
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var maxPending = new AtomicInteger(10);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 100)
                .maxPendingElements(maxPending::get)
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when: accumulating under the initial generous limit
        batcher.add(List.of("a", "b", "c"));
        batcher.awaitPendingTransmissions();

        // then: nothing sent yet
        assertThat(transmitted).isEmpty();

        // when: the limit is lowered (as if the overriding method started returning a smaller value) and
        // another element is accumulated
        maxPending.set(4);
        batcher.add(List.of("d"));
        batcher.awaitPendingTransmissions();

        // then: the newly-lowered limit was honored immediately, without reconstructing the Batcher, the batch
        // filling up to it being sent
        assertThat(transmitted).containsExactly(List.of("a", "b", "c", "d"));
    }

    @Test
    @DisplayName("Retries only the rejected elements of a PartialFailure, reporting the rest as succeeded")
    void testPartialFailureRetriesOnlyRejectedElements() throws Exception {
        // given: "b" is rejected every attempt, "a" and "c" are accepted the very first time
        var succeeded = new CopyOnWriteArrayList<Integer>();
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 3)
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
        assertThat(succeeded).containsExactly(2);
        assertThat(attempts).containsExactly(List.of("a", "b", "c"));

        // when: retried
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: only "b" (not "a" or "c" again) was retried, and it still fails every time
        assertThat(attempts).containsExactly(List.of("a", "b", "c"), List.of("b"));
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
        assertThat(attempts.get())
                .as("The malformed chunk should have been retried, not discarded outright")
                .isGreaterThanOrEqualTo(2);
        assertThat(dropped).as("\"a\" should have been reported dropped instead of lost silently").contains("a");
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
        assertThat(elapsedMillis)
                .as("add() should not block on an incomplete transport future, took " + elapsedMillis + "ms")
                .isLessThan(500);
        assertThat(transmitted.get()).as("The transport should not have completed yet").isFalse();

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
        assertThat(client.isAlive()).as("The client thread should not have waited for the listener").isFalse();
        assertThat(listenerThread.get(5, SECONDS)).isNotSameAs(client);

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
                .flushThreshold(() -> 10)
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
        assertThat(attempts).containsExactly(List.of("a", "b"), List.of("a", "b"));
        assertThat(dropped).containsExactly("a", "b");
    }

    @Test
    @DisplayName("Never requeues an element a PartialFailure referenced but the chunk never contained")
    void testPartialFailureCannotInjectForeignElements() throws Exception {
        // given: a misbehaving transport reporting, alongside the element it was actually given, one that
        // was never part of the chunk (nor of this Batcher at all)
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
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
        assertThat(attempts).hasSize(3);
        assertThat(attempts)
                .as("Attempts should only ever contain \"a\", but were " + attempts)
                .allMatch(List.of("a")::equals);
    }

    @Test
    @DisplayName("Warns of a rejected element the request never held, such as an equal copy of one it did")
    void testPartialFailureNamingAnEqualCopyIsReported() throws Exception {
        // given: a transport reporting a copy of the element it was given, equal to it but another instance
        record Item(String name) {

        }
        Batcher<Item> batcher = Batcher.<Item>builder()
                .transport(elements -> completedFuture(TransmissionResult.partialFailure(
                        List.of(new Item(elements.getFirst().name())), null)))
                .build();

        // when
        List<StatusData> statuses = captureStatuses(() -> {
            batcher.add(List.of(new Item("a")));
            batcher.awaitPendingTransmissions();
        });

        // then: the copy, matching nothing the request held, is said to be ignored
        assertThat(statuses)
                .extracting(StatusData::getFormattedStatus)
                .anyMatch(status -> status.contains("reported 1 rejected element(s) the batch of 1 does not hold"));
    }

    @Test
    @DisplayName("Does not warn of a request holding an element twice when the transport rejects it once")
    void testPartialFailureOfAnElementHeldTwiceIsNotReported() throws Exception {
        // given: one instance sent twice, the transport naming it once, as the very instance it was given
        String twice = "a";
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 2)
                .transport(elements -> completedFuture(TransmissionResult.partialFailure(List.of(elements.getFirst()), null)))
                .build();

        // when
        List<StatusData> statuses = captureStatuses(() -> {
            batcher.add(List.of(twice, twice));
            batcher.awaitPendingTransmissions();
        });

        // then: nothing the transport reported was ignored, the instance belonging to the request
        assertThat(statuses)
                .extracting(StatusData::getFormattedStatus)
                .noneMatch(status -> status.contains("rejected element(s)"));
    }

    @Test
    @DisplayName("Splits a batch into chunks that each stay under the maximum request size")
    void testChunksByRequestBytes() throws Exception {
        // given: elements measured at 10 bytes each and a 25 byte request limit, so chunks hold two of them
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 5)
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
        assertThat(attempts).containsExactly(List.of("a", "b"), List.of("c", "d"), List.of("e"));
    }

    @Test
    @DisplayName("Sends an element larger than the maximum request size on its own rather than never at all")
    void testOversizedElementIsStillDispatchedAlone() throws Exception {
        // given: "big" alone weighs more than a whole request is allowed to, and cannot be split any further
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                // One at a time, so the chunks arrive in the order they were cut rather than racing each other
                .maxConcurrentTransmissions(1)
                .flushThreshold(() -> 3)
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
        assertThat(attempts).containsExactly(List.of("a"), List.of("big"), List.of("b"));
    }

    @Test
    @DisplayName("Sends elements measured at the largest size apart rather than adding them up past overflow")
    void testHugeMeasurementsDoNotOverflowTheLimits() throws Exception {
        // given: a measurement answering Long.MAX_VALUE for the elements it deems unbounded, two of which added
        // up to a negative size, under the limit, sending them in one request along with every small one
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                // One at a time, so the chunks arrive in the order they were cut rather than racing each other
                .maxConcurrentTransmissions(1)
                .flushThreshold(() -> 100)
                .elementSize(element -> element.startsWith("huge") ? Long.MAX_VALUE : 10)
                .maxRequestBytes(() -> 1_000L)
                .transport(elements -> {
                    attempts.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();
        batcher.add(List.of("huge-1", "huge-2", "a", "b"));
        long pendingBytes = batcher.metrics().pendingBytes();

        // when
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: each huge one alone, as any element heavier than a request, the small ones together
        assertThat(attempts).containsExactly(List.of("huge-1"), List.of("huge-2"), List.of("a", "b"));
        assertThat(pendingBytes).isEqualTo(2L * Integer.MAX_VALUE + 20);
    }

    @Test
    @DisplayName("Measures each element once when splitting a batch, whatever the number of chunks")
    void testSplittingMeasuresEachElementOnce() throws Exception {
        // given: elements measured at 10 bytes each and a 25 byte request limit, so chunks hold two of them
        var measured = new CopyOnWriteArrayList<String>();
        var attempts = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 5)
                // One at a time, so the chunks arrive in the order they were cut rather than racing each other
                .maxConcurrentTransmissions(1)
                .elementSize(element -> {
                    measured.add(element);
                    return 10;
                })
                .maxRequestBytes(() -> 25L)
                .transport(elements -> {
                    attempts.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when
        batcher.add(List.of("a", "b", "c", "d", "e"));
        batcher.awaitPendingTransmissions();

        // then: measured on the way in, then once more when split, an element starting a chunk included
        assertThat(attempts).containsExactly(List.of("a", "b"), List.of("c", "d"), List.of("e"));
        assertThat(measured).containsExactly("a", "b", "c", "d", "e", "a", "b", "c", "d", "e");
    }

    @Test
    @DisplayName("Discards the oldest elements once the pending batch weighs more than allowed while held off")
    void testPendingBytesCapDiscardsOldestElements() throws Exception {
        // given: a batch capped at 25 bytes with elements of 10, whose first element fails, which holds
        // transmissions off until the next flush retries it
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        var mutableLists = new CopyOnWriteArrayList<List<String>>();
        var failing = new AtomicBoolean(true);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 100)
                .elementSize(element -> 10)
                .maxPendingBytes(() -> 25L)
                .transport(elements -> completedFuture(failing.get()
                        ? TransmissionResult.failure(null)
                        : TransmissionResult.success()))
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

        batcher.add(List.of("a"));
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // when: three more elements weigh 40 bytes with it, 15 over the cap
        batcher.add(List.of("b"));
        batcher.add(List.of("c"));
        batcher.add(List.of("d"));
        awaitDropped(dropped, 2);

        // then: the two oldest were discarded, leaving the batch back under the cap. Sorted, since each add()
        // above discarded one of them and notified it on a thread of its own, so they land in either order
        assertThat(dropped.stream().sorted().toList()).containsExactly("a", "b");
        assertThat(reasons.stream().distinct().toList()).containsExactly("maximum pending bytes exceeded");

        // and: handed over as the unmodifiable list this callback promises, like every other discard path,
        // rather than the working list this one used to expose to whatever the listener does with it
        assertThat(mutableLists).isEmpty();

        // and: what is left is exactly what the cap allows to be kept
        failing.set(false);
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertThat(batcher.health().status()).isEqualTo(DeliveryHealth.Status.UP);
    }

    @Test
    @DisplayName("Discards the oldest elements a failed request puts back past the maximum pending size")
    void testRequeuePastPendingBytesCapDiscardsOldestElements() throws Exception {
        // given: a batch capped at 25 bytes with elements of 10, and a first request of two elements answered
        // only once a third element was stacked behind it
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        var firstAnswer = new CompletableFuture<TransmissionResult<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 100)
                .elementSize(element -> 10)
                .maxPendingBytes(() -> 25L)
                .transport(elements -> {
                    transmitted.add(elements);
                    return transmitted.size() == 1 ? firstAnswer : completedFuture(TransmissionResult.success());
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                        reasons.add(reason);
                    }
                })
                .build();
        batcher.add(List.of("a", "b"));
        batcher.flush();
        batcher.add(List.of("c"));

        // when: the request fails, putting its 20 bytes back in front of the 10 stacked meanwhile
        firstAnswer.complete(TransmissionResult.failure(null));
        batcher.awaitPendingTransmissions();

        // then: the oldest was discarded to bring the batch back under the cap, the rest kept in order
        assertThat(dropped).containsExactly("a");
        assertThat(reasons).containsExactly("maximum pending bytes exceeded");
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertThat(transmitted).containsExactly(List.of("a", "b"), List.of("b", "c"));
    }

    @Test
    @DisplayName("Never measures an element when no measurement was supplied at all")
    void testNoMeasurementWithoutAnElementSizeFunction() throws Exception {
        // given: byte limits set but no way to measure against them, which must be inert rather than
        // treating every element as weightless-but-still-counted (or worse, as unbounded)
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 3)
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
        assertThat(transmitted).containsExactly(List.of("a", "b", "c"));
    }

    @Test
    @DisplayName("Treats a measurement that throws as weightless instead of failing the pipeline")
    void testThrowingElementSizeIsTolerated() throws Exception {
        // given
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 2)
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
        assertThat(transmitted).containsExactly(List.of("a", "b"));
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
                .flushThreshold(() -> 10)
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
        assertThat(dropped).containsExactly("a", "b");
        assertThat(reasons).containsExactly("still pending when closed");
    }

    @Test
    @DisplayName("Gives up closing once the timeout elapses instead of waiting forever on a stuck transport")
    void testCloseIsBoundedByItsTimeout() throws Exception {
        // given: a transport whose future is never completed, holding the single transmission permit for good
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
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

        // then: both the element never handed to the stuck transport and the one it holds are reported
        assertThat(elapsedMillis)
                .as("close() should have given up quickly, but took " + elapsedMillis + "ms")
                .isLessThan(5_000);
        assertThat(dropped).containsExactly("pending", "stuck");
    }

    @Test
    @DisplayName("Still transmits the final flush when closed with no time to wait, and reports it, nothing waiting for it")
    void testCloseWithoutTimeToWaitStillTransmitsTheFinalFlush() throws Exception {
        // given: a pending batch, every transmission permit free, and a transport answering once told to
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var dropped = new CopyOnWriteArrayList<String>();
        var answer = new CompletableFuture<TransmissionResult<String>>();
        Batcher<String> batcher = batcher(10, elements -> {
            transmitted.add(elements);
            return answer;
        }, new BatcherListener<>() {
            @Override
            public void onDropped(List<String> elements, String reason) {
                dropped.addAll(elements);
            }
        });
        batcher.add(List.of("a", "b"));

        // when: closed without waiting for anything
        batcher.close(Duration.ZERO);

        // then: the batch is reported, nothing being left to wait for the outcome of its transmission
        assertThat(dropped).containsExactly("a", "b");

        // when: that transmission succeeds after all
        answer.complete(TransmissionResult.success());
        batcher.awaitPendingTransmissions();

        // then: a zero budget only meant not waiting, the batch still going out on a permit already free
        // instead of being reported without an attempt, and its late success is not reported a second time
        assertThat(transmitted).containsExactly(List.of("a", "b"));
        assertThat(dropped).containsExactly("a", "b");
    }

    @Test
    @DisplayName("Releases a caller blocked waiting for a permit once a bounded close begins")
    void testBlockedDispatchIsReleasedByALaterClose() throws Exception {
        // given: the single transmission permit held for good by a transport whose future never completes,
        // so the next dispatch blocks waiting for it with no close in progress yet
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1)
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
        assertThat(blocked.get()).as("The second add() should be waiting for the only transmission permit").isTrue();

        // when: closing with a budget, which the blocked caller must notice rather than wait out an
        // unbounded acquire it entered before the deadline existed
        batcher.close(Duration.ofMillis(200));

        // then: it comes back instead of staying blocked for as long as the transport stays stuck
        adder.join(SECONDS.toMillis(10));
        assertThat(blocked.get()).as("The blocked add() should have been released by the close").isFalse();
        assertThat(adder.isAlive()).as("The blocked add() should have returned").isFalse();
    }

    @Test
    @DisplayName("Reports the requests a close found no permit for in their order, oldest first")
    void testRequestsWithoutPermitAtCloseAreReportedInOrder() {
        // given: the single transmission permit held for good by a stuck transmission, and a batch split into
        // three requests of two elements
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
                .maxRequestElements(() -> 2)
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
        batcher.add(List.of("a", "b", "c", "d", "e", "f"));

        // when: closing, the final flush finding no permit within the budget for any of its requests
        batcher.close(Duration.ofMillis(100));

        // then: a dead-letter sink gets them as they were added. Each used to be put back in front of the one
        // before, reporting "e", "f", "c", "d", "a", "b"
        assertThat(dropped).containsExactly("a", "b", "c", "d", "e", "f", "stuck");
    }

    @Test
    @DisplayName("Retries the requests an interrupted dispatch put back in their order, oldest first")
    void testRequestsPutBackOnInterruptAreRetriedInOrder() throws Exception {
        // given: the single transmission permit held by a transmission answering once told to, and a batch
        // split into three requests of two elements
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var answer = new CompletableFuture<TransmissionResult<String>>();
        var attempts = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
                .maxRequestElements(() -> 2)
                .maxConcurrentTransmissions(1)
                .transport(elements -> {
                    transmitted.add(elements);
                    return attempts.incrementAndGet() == 1 ? answer : completedFuture(TransmissionResult.success());
                })
                .build();
        batcher.add(List.of("held"));
        batcher.flush();
        batcher.add(List.of("a", "b", "c", "d", "e", "f"));

        // when: flushed by an interrupted thread, as the worker of an appender whose stop abandons it, every
        // request being put back untried
        Thread.currentThread().interrupt();
        batcher.flush();
        boolean stillInterrupted = Thread.interrupted();
        answer.complete(TransmissionResult.success());
        batcher.awaitPendingTransmissions();
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: they go out as they were added, rather than newest first, the interruption kept for the caller
        assertThat(stillInterrupted).isTrue();
        assertThat(transmitted).containsExactly(
                List.of("held"), List.of("a", "b"), List.of("c", "d"), List.of("e", "f"));
    }

    @Test
    @DisplayName("Reports the elements of a transmission still in flight once closing gives up on it, and only once")
    void testElementsInFlightWhenClosedAreReportedOnce() throws Exception {
        // given: a transport holding its future open, so closing gives up on it while that transmission is
        // still in flight, which used to lose its elements for good unless it failed later on
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        var inFlight = new CopyOnWriteArrayList<CompletableFuture<TransmissionResult<String>>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1)
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
        assertThat(inFlight).as("The element should have been dispatched").hasSize(1);

        // when: closing gives up on the transmission
        batcher.close(Duration.ofMillis(200));

        // then: its element gets its last chance right away, rather than with a transport that may never answer
        assertThat(dropped).containsExactly("stuck");
        assertThat(reasons).containsExactly("still in flight when closed");

        // when: the transmission finally fails and hands its element back
        inFlight.get(0).complete(TransmissionResult.failure(null));
        batcher.awaitPendingTransmissions();

        // then: it is not reported a second time
        assertThat(dropped).containsExactly("stuck");
    }

    @Test
    @DisplayName("Returns at once when closed again, rather than waiting again for a transmission it gave up on")
    void testClosingAgainDoesNotWaitAgain() {
        // given: a transmission that never completes, which a first close gave up on and reported
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = batcherNeverAnswering(dropped);
        batcher.add(List.of("stuck"));
        batcher.close(Duration.ofMillis(200));

        // when: closed again, as an appender stopped twice closes its delivery twice
        long start = System.nanoTime();
        batcher.close(Duration.ofSeconds(10));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // then: it used to wait its whole timeout again for that transmission, which still holds its permit
        assertThat(elapsedMillis)
                .as("Closing again should have returned at once, but took " + elapsedMillis + "ms")
                .isLessThan(5_000);
        assertThat(dropped).containsExactly("stuck");
    }

    @Test
    @DisplayName("Waits for the close in progress when closed concurrently, rather than returning before it is over")
    void testClosingConcurrentlyWaitsForTheFirstClose() throws Exception {
        // given: a first close waiting for a transmission that never completes
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = batcherNeverAnswering(dropped);
        batcher.add(List.of("stuck"));
        var firstClose = new Thread(() -> batcher.close(Duration.ofMillis(500)));
        firstClose.setDaemon(true);
        firstClose.start();
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
        while (batcher.health().status() != DeliveryHealth.Status.DOWN && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5);
        }

        // when: closed again meanwhile, by a caller about to release what the transport uses
        long start = System.nanoTime();
        batcher.close(Duration.ofSeconds(10));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // then: it returns once the first close is over, neither before, nor after waiting its own timeout
        assertThat(dropped).containsExactly("stuck");
        assertThat(elapsedMillis)
                .as("Closing again should have returned with the first close, but took " + elapsedMillis + "ms")
                .isLessThan(5_000);
        firstClose.join(SECONDS.toMillis(5));
    }

    @Test
    @DisplayName("Logs an interrupted wait for the close in progress, as the close itself logs one")
    void testInterruptedWaitForTheFirstCloseIsLogged() throws Exception {
        // given: a first close waiting for a transmission that never completes
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = batcherNeverAnswering(dropped);
        batcher.add(List.of("stuck"));
        var firstClose = new Thread(() -> batcher.close(Duration.ofMillis(500)));
        firstClose.setDaemon(true);
        firstClose.start();
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
        while (batcher.health().status() != DeliveryHealth.Status.DOWN && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5);
        }

        // when: closed again meanwhile by an interrupted thread
        var stillInterrupted = new AtomicBoolean();
        List<StatusData> statuses = captureStatuses(() -> {
            Thread.currentThread().interrupt();
            batcher.close(Duration.ofSeconds(10));
            stillInterrupted.set(Thread.interrupted());
        });

        // then: it says it stopped waiting, rather than returning before the first close is over without a word
        assertThat(stillInterrupted).isTrue();
        assertThat(statuses)
                .extracting(StatusData::getFormattedStatus)
                .anyMatch(status -> status.contains("Interrupted while waiting for the close in progress to complete"));
        firstClose.join(SECONDS.toMillis(5));
    }

    /**
     * A batcher sending every element on its own to a transport that never answers, reporting the elements it
     * drops to the given list.
     */
    private static Batcher<String> batcherNeverAnswering(List<String> dropped) {
        return Batcher.<String>builder()
                .flushThreshold(() -> 1)
                .transport(elements -> new CompletableFuture<>())
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
    }

    @Test
    @DisplayName("Reports elements a failed transmission hands back after closing drained the batch")
    void testElementsRequeuedAfterCloseDrainedAreReported() throws Exception {
        // given: a transport failing at once, and a listener holding up the handling of that failure until
        // closing drained the batch, which the element is only put back into afterwards
        var dropped = new CopyOnWriteArrayList<String>();
        var reasons = new CopyOnWriteArrayList<String>();
        var failing = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1)
                .maxConsecutiveFailures(() -> 0)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onFailed(int count, Throwable cause) {
                        failing.countDown();
                        try {
                            resume.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                        reasons.add(reason);
                    }
                })
                .build();
        batcher.add(List.of("late"));
        assertThat(failing.await(2, SECONDS)).as("The transmission should have failed").isTrue();

        // when: closing gives up waiting for the handling of that failure, which then puts the element back
        batcher.close(Duration.ofMillis(200));
        assertThat(dropped).as("The element is still held by the handling of its failure").isEmpty();
        resume.countDown();
        awaitDropped(dropped, 1);

        // then: it is reported rather than merged back into a batch nobody will ever look at again
        assertThat(dropped).containsExactly("late");
        assertThat(reasons).containsExactly("still pending when closed");
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
        assertThat(dropped).containsExactly("late");
        assertThat(reasons).containsExactly("submitted after close");
    }

    @Test
    @DisplayName("Waits for the listener still notified of a dropped element before returning from close")
    void testCloseWaitsForTheDropNotificationsStillRunning() throws Exception {
        // given: an element pushed out of the batch, notified on a thread of its own to a listener taking its
        // time over it, as a remote dead-letter queue does
        var release = new CountDownLatch(1);
        var saved = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = batcherNotifyingTheFirstDiscard(release, saved);

        // when
        var closer = new Thread(() -> batcher.close(Duration.ofSeconds(10)));
        closer.setDaemon(true);
        closer.start();
        closer.join(300);

        // then: the close waits for that notification, which used to be left running, for the JVM exiting once
        // the appender stopped to cut short
        assertThat(closer.isAlive())
                .as("close() should wait for the listener still notified of a dropped element")
                .isTrue();
        release.countDown();
        closer.join(SECONDS.toMillis(5));
        assertThat(closer.isAlive()).as("close() should return once the listener did").isFalse();
        assertThat(saved).containsExactly("first", "second");
    }

    @Test
    @DisplayName("Gives up waiting for a listener stuck on a dropped element once the close timeout elapses")
    void testCloseBoundsTheWaitForTheDropNotifications() throws Exception {
        // given: the same notification, to a listener that does not return from it
        var release = new CountDownLatch(1);
        var saved = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = batcherNotifyingTheFirstDiscard(release, saved);

        // when
        long start = System.nanoTime();
        batcher.close(Duration.ofMillis(200));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // then: the wait is bounded like any other of the close, which still reports what is pending
        assertThat(elapsedMillis)
                .as("close() should have given up quickly, but took " + elapsedMillis + "ms")
                .isLessThan(5_000);
        assertThat(saved).containsExactly("second");
        release.countDown();
    }

    /**
     * A batcher holding "second", which pushed "first" out to a listener notified of it on a thread of its own,
     * and only returning from that notification once the given latch is released: the pending batch holds a
     * single element, and a failing transport holds the next ones off until a flush.
     */
    private static Batcher<String> batcherNotifyingTheFirstDiscard(CountDownLatch release, List<String> saved)
            throws InterruptedException {
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 100)
                .maxPendingElements(() -> 1)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        if (elements.contains("first")) {
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        saved.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("first"));
        batcher.awaitPendingTransmissions();
        batcher.add(List.of("second"));
        return batcher;
    }

    @Test
    @DisplayName("Holds off retrying while the backoff policy says to wait, then retries once it elapses")
    void testBackoffHoldsOffRetries() throws Exception {
        // given
        var attempts = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
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
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(batcher.health().status()).isEqualTo(DeliveryHealth.Status.BACKING_OFF);
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertThat(attempts.get()).as("The retry should have been held off by the backoff").isEqualTo(1);

        // when: the backoff elapses
        awaitBackoffElapsed(batcher);
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: retrying resumes on its own, without any further prompting
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Ignores an active backoff while closing, that transmission being the last chance to send at all")
    void testCloseTransmitsDespiteAnActiveBackoff() throws Exception {
        // given: a backoff far longer than this test, engaged by a first failing attempt
        var attempts = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
                .maxConsecutiveFailures(() -> 0)
                .backoff(consecutiveFailures -> 60_000)
                .transport(elements -> completedFuture(
                        attempts.incrementAndGet() > 1 ? TransmissionResult.success() : TransmissionResult.failure(null)))
                .listener(BatcherListener.noop())
                .build();
        batcher.add(List.of("a"));
        batcher.flush();
        batcher.awaitPendingTransmissions();
        assertThat(batcher.health().status()).isEqualTo(DeliveryHealth.Status.BACKING_OFF);

        // when
        batcher.close();

        // then: the second attempt happened despite the backoff, and succeeded
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(batcher.metrics().transmittedElements()).isEqualTo(1);
    }

    private static void awaitBackoffElapsed(Batcher<String> batcher) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (batcher.health().status() == DeliveryHealth.Status.BACKING_OFF) {
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
                // Never reached, the transport failing so that transmissions are held off until a flush, which
                // never comes: nothing ever drains the batch and every add() trips the pending cap
                .flushThreshold(() -> Integer.MAX_VALUE)
                .maxPendingElements(() -> 1)
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
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
        // A first element filling the batch is sent and fails, which holds the next ones off
        batcher.add(List.of("first"));
        batcher.awaitPendingTransmissions();

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
        assertThat(peak.get())
                .as("At most four notifications should be in flight, plus the one blocking the caller, but peaked at " + peak.get())
                .isLessThanOrEqualTo(5);

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
                .flushThreshold(() -> 1)
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
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(transmitted).containsExactly(List.of("a", "b"));
    }

    @Test
    @DisplayName("Retries the elements of a transport returning no future, as a failure without cause")
    void testTransportReturningNoFutureIsAFailure() throws Exception {
        assertRetriedAfterFailureWithoutCause(null);
    }

    @Test
    @DisplayName("Retries the elements of a future completed without result, as a failure without cause")
    void testFutureCompletedWithoutResultIsAFailure() throws Exception {
        assertRetriedAfterFailureWithoutCause(completedFuture(null));
    }

    /**
     * Checks that a first attempt answered with the given future is reported as a failure without cause, and
     * that its element is retried with the next one.
     */
    private static void assertRetriedAfterFailureWithoutCause(
            @Nullable CompletableFuture<TransmissionResult<String>> firstAnswer) throws Exception {
        // given: a transport answering its first request with the given future, and the next ones with a success
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var failures = new CopyOnWriteArrayList<Optional<Throwable>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .transport(elements -> {
                    transmitted.add(elements);
                    return transmitted.size() == 1 ? firstAnswer : completedFuture(TransmissionResult.success());
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onFailed(int count, @Nullable Throwable cause) {
                        failures.add(Optional.ofNullable(cause));
                    }
                })
                .build();

        // when
        batcher.add(List.of("a"));
        batcher.awaitPendingTransmissions();
        DeliveryHealth.Status afterFirstAttempt = batcher.health().status();
        batcher.add(List.of("b"));
        batcher.awaitPendingTransmissions();

        // then: a failure, as the transport contract says, rather than a success losing "a"
        assertThat(failures).containsExactly(Optional.empty());
        assertThat(afterFirstAttempt).isEqualTo(DeliveryHealth.Status.RETRYING);
        assertThat(transmitted).containsExactly(List.of("a"), List.of("a", "b"));
    }

    @Test
    @DisplayName("Reports the cause of a failure a future derived by the transport wraps, rather than its wrapper")
    void testWrappedFailureIsReportedWithItsCause() throws Exception {
        // given: a transport deriving its future from the one of a client, through thenApply(), whose failure
        // then completes it with a CompletionException wrapping the actual cause
        var cause = new IOException("connection refused");
        var failures = new CopyOnWriteArrayList<Throwable>();
        Batcher<String> batcher = batcher(1,
                elements -> CompletableFuture.<String>failedFuture(cause).thenApply(response -> TransmissionResult.success()),
                new BatcherListener<>() {
                    @Override
                    public void onFailed(int count, @Nullable Throwable failure) {
                        failures.add(failure);
                    }
                });

        // when
        batcher.add(List.of("a"));
        batcher.awaitPendingTransmissions();

        // then: the listener, and the logs, name what went wrong rather than the future machinery
        assertThat(failures).singleElement().isSameAs(cause);
    }

    @Test
    @DisplayName("Retries a chunk whose thread failed to start with an Error instead of leaking its permit")
    void testThreadFailingToStartDoesNotLeakAPermit() throws Exception {
        // given: a single transmission permit, and a first thread failing to start, as one does once the
        // memory runs out
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1)
                .maxConcurrentTransmissions(1)
                .transmissionThreadFactory(failingToCreate(1))
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.success());
                })
                .build();

        // when: the first element fails to start, the second one needs the very permit it took
        assertThatNoException().isThrownBy(() -> batcher.add(List.of("a")));
        assertThat(CompletableFuture.runAsync(() -> batcher.add(List.of("b")))).succeedsWithin(Duration.ofSeconds(5));
        batcher.awaitPendingTransmissions();

        // then: "a" was put back, and went out with "b" on the permit it gave back
        assertThat(transmitted).containsExactly(List.of("a", "b"));
    }

    @Test
    @DisplayName("Notifies a discard on the calling thread when its own thread failed to start with an Error")
    void testDropNotificationWhoseThreadFailsToStartIsNotLost() throws Exception {
        // given: a pending cap of one, and a first element failing, which holds the next ones off until the
        // next flush; the first thread transmits it, and the second one, notifying its discard, fails to start
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Integer.MAX_VALUE)
                .maxPendingElements(() -> 1)
                .transmissionThreadFactory(failingToCreate(2))
                .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("first"));
        batcher.awaitPendingTransmissions();

        // when: the next element pushes the first one out of the batch
        assertThatNoException().isThrownBy(() -> batcher.add(List.of("second")));

        // then: the listener was notified on the calling thread instead, rather than never
        assertThat(dropped).containsExactly("first");
    }

    @Test
    @DisplayName("Leaves its carrier to other virtual threads while one waits for the pending batch")
    void testWaitingForTheBatchDoesNotPinACarrier() throws Exception {
        // given: a measurement blocking while it holds the pending batch, as a slow one does, and as many
        // virtual threads waiting for the batch as there are carriers to run virtual threads on. On Java 21, one
        // waiting to enter a monitor keeps its carrier, where one waiting for a lock gives it back; later
        // versions give it back either way
        var measuring = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
                .elementSize(element -> {
                    measuring.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 1;
                })
                .transport(elements -> completedFuture(TransmissionResult.success()))
                .build();
        Thread adder = Thread.ofPlatform().daemon().start(() -> batcher.add(List.of("a")));
        assertThat(measuring.await(5, SECONDS)).as("The measurement should have started").isTrue();
        int carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors());
        var waiting = new ArrayList<Thread>();
        for (int i = 0; i < carriers; i++) {
            waiting.add(Thread.ofVirtual().start(batcher::metrics));
        }
        awaitWaiting(waiting);

        // when: one more virtual thread has something to run
        var ran = new CountDownLatch(1);
        Thread.ofVirtual().start(ran::countDown);
        boolean ranMeanwhile = ran.await(2, SECONDS);
        release.countDown();
        adder.join();
        for (Thread thread : waiting) {
            thread.join();
        }

        // then: it ran while the others waited, rather than once they were done
        assertThat(ranMeanwhile)
                .as("A virtual thread should have run while " + carriers + " others waited for the batch")
                .isTrue();
    }

    /**
     * Waits until each of the given threads waits, to enter a monitor or for a lock.
     */
    private static void awaitWaiting(List<Thread> threads) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        for (Thread thread : threads) {
            while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.WAITING) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new AssertionError("Timed out waiting for " + thread + " to wait, " + thread.getState());
                }
                Thread.sleep(1);
            }
        }
    }

    /**
     * A factory of virtual threads failing to create the given one, counting from one, with an Error as a thread
     * fails to start once the memory runs out: an InternalError rather than an OutOfMemoryError, which JUnit
     * rethrows out of a failing test, aborting every other one.
     */
    private static ThreadFactory failingToCreate(int failing) {
        var created = new AtomicInteger();
        ThreadFactory virtualThreads = Thread.ofVirtual().factory();
        return task -> {
            if (created.incrementAndGet() == failing) {
                throw new InternalError("Unable to start a thread");
            }
            return virtualThreads.newThread(task);
        };
    }

    @Test
    @DisplayName("Still requeues a failed chunk when the listener notified of that failure throws an Error")
    void testListenerThrowingAnErrorDoesNotLoseElements() throws Exception {
        // given: a listener whose onFailed throws an Error, sitting between the failure and the retry
        // handling it must never skip
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
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
        assertThat(dropped).containsExactly("a");
    }

    @Test
    @DisplayName("Still requeues a failed chunk when the backoff policy consulted for it throws an Error")
    void testBackoffPolicyThrowingAnErrorDoesNotLoseElements() {
        // given: a policy throwing an Error, consulted between the decision to retry and the requeue
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
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
        assertThat(dropped).containsExactly("a");
    }

    @Test
    @DisplayName("Treats a measurement throwing an Error as weightless instead of propagating to the caller")
    void testElementSizeThrowingAnErrorIsTolerated() throws Exception {
        // given: a measurement throwing an Error, called by add() on a batch already appended to but not
        // yet accounted for in the running byte total
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 2)
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
        assertThat(transmitted).containsExactly(List.of("a", "b"));
    }

    private static Batcher<String> batcher(int flushThreshold,
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
                .flushThreshold(() -> 2)
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
        assertThat(transmitted).containsExactly(List.of("a", "b"));
    }

    @Test
    @DisplayName("Keeps a failed batch whose pending limit could not be read while putting it back")
    void testThrowingPendingLimitDoesNotLoseTheRequeue() throws Exception {
        // given: the same kind of supplier, read this time while a failed transmission is being put back
        var dropped = new CopyOnWriteArrayList<String>();
        var broken = new AtomicBoolean(false);
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Integer.MAX_VALUE)
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
        assertThat(dropped).containsExactly("a");
    }

    @Test
    @DisplayName("Never charges a failure streak to elements the requeue that carried it kept none of")
    void testFailureStreakIsNotInheritedByUntaintedElements() throws Exception {
        // given: a pending cap of three and a limit of two consecutive failures, the shape a sustained
        // outage settles into -- the batch sitting at its cap, so each retry cycle trims away exactly the
        // elements it just put back
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Integer.MAX_VALUE)
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

        // when: three elements fill the batch, which sends them, fail once and are put back, carrying a
        // streak of one
        batcher.add(List.of("old-1", "old-2", "old-3"));
        batcher.awaitPendingTransmissions();

        // and: three newer ones, held off until the next flush, push every one of them out of the batch, then
        // fail once themselves
        batcher.add(List.of("new-1", "new-2", "new-3"));
        awaitDropped(dropped, 3);
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: only the elements that really did fail twice were given up on. The newer ones have a single
        // failure of their own, and used to be discarded alongside them on a streak they never earned
        assertThat(dropped).containsExactly("old-1", "old-2", "old-3");
    }

    @Test
    @DisplayName("Counts the failures of an element joining a failed one from its own first attempt")
    void testFailureStreakIsNotInheritedByElementsJoiningTheBatch() throws Exception {
        // given: two consecutive failures allowed, and a transport failing every attempt
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Integer.MAX_VALUE)
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
        assertThat(dropped).containsExactly("old");

        // when: the newer one fails a second time
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then: it is given up on in turn
        assertThat(dropped).containsExactly("old", "new");
    }

    @Test
    @DisplayName("Keeps the failure streak of each element across the requests a batch is split into")
    void testFailureStreaksFollowTheirElementsIntoChunks() throws Exception {
        // given: requests of two elements at most, three consecutive failures allowed, and a transport
        // rejecting everything but "fresh-1"
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> Integer.MAX_VALUE)
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
        assertThat(Set.copyOf(dropped)).isEqualTo(Set.of("old-1", "old-2", "old-3"));

        // when: the two newer ones still rejected fail twice more
        for (int i = 0; i < 2; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then: they are given up on in turn, the accepted one never being
        assertThat(Set.copyOf(dropped)).isEqualTo(Set.of("old-1", "old-2", "old-3", "fresh-2", "fresh-3"));
        assertThat(dropped).hasSize(5);
    }

    @Test
    @DisplayName("Never hands the transport an empty request, however low the flush threshold")
    void testNeverTransmitsAnEmptyBatch() throws Exception {
        // given: a non-positive threshold (allowed, meaning "no batching"), a pending cap keeping nothing
        // waiting, and a first attempt failing, which holds the next ones off for a minute
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        var dropped = new CopyOnWriteArrayList<String>();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 0)
                .maxPendingElements(() -> 0)
                .backoff(consecutiveFailures -> 60_000L)
                .transport(elements -> {
                    transmitted.add(elements);
                    return completedFuture(TransmissionResult.failure(null));
                })
                .listener(new BatcherListener<>() {
                    @Override
                    public void onDropped(List<String> elements, String reason) {
                        dropped.addAll(elements);
                    }
                })
                .build();
        batcher.add(List.of("a"));
        batcher.awaitPendingTransmissions();

        // when: the next element is discarded on the way in, leaving nothing to send
        batcher.add(List.of("b"));
        batcher.awaitPendingTransmissions();

        // then: both were reported as discarded, and nothing was sent on behalf of the emptied batch
        awaitDropped(dropped, 2);
        assertThat(dropped).containsExactly("a", "b");
        assertThat(transmitted).containsExactly(List.of("a"));
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

        // when: a pending cap of 1 makes the second element discard the first, which failed and waits for the
        // next flush, the discard being logged
        try {
            Batcher<String> batcher = Batcher.<String>builder()
                    .flushThreshold(() -> 10)
                    .maxPendingElements(() -> 1)
                    .transport(elements -> completedFuture(TransmissionResult.failure(null)))
                    .build();
            batcher.add(List.of("a"));
            batcher.awaitPendingTransmissions();
            batcher.add(List.of("b"));
            batcher.close();
        } finally {
            StatusLogger.getLogger().removeListener(statusListener);
        }

        // then
        assertThat(statusMessages)
                .as("Expected the discard on the status channel, got: " + statusMessages)
                .anyMatch(message -> message.contains("maximum pending element"));
    }

    @Test
    @DisplayName("Takes a snapshot of what it holds right now, and of what it did so far")
    void testMetricsTrackWhatItHoldsAndDid() throws Exception {
        // given: a first request left unanswered until the test answers it, and a failing next one
        var unanswered = new CompletableFuture<TransmissionResult<String>>();
        var requests = new AtomicInteger();
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 10)
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
        assertThat(batcher.metrics()).isEqualTo(new Batcher.Metrics(2, 20, 0, 0, 0, 0));

        // when: they are sent, the request left unanswered, then answered
        batcher.flush();
        assertThat(batcher.metrics()).isEqualTo(new Batcher.Metrics(0, 0, 1, 0, 0, 0));
        unanswered.complete(TransmissionResult.success());
        batcher.awaitPendingTransmissions();

        // then
        assertThat(batcher.metrics()).isEqualTo(new Batcher.Metrics(0, 0, 0, 2, 0, 0));

        // when: four more fill the batch past its pending cap of three, which sends them, and fail once
        batcher.add(List.of("c", "d", "e", "f"));
        batcher.awaitPendingTransmissions();

        // then: the three most recent are pending again, counted with the failed attempt and the oldest one,
        // discarded to put them back within the cap
        assertThat(batcher.metrics()).isEqualTo(new Batcher.Metrics(3, 30, 0, 2, 1, 1));
    }

    @Test
    @DisplayName("Logs the cause of every failure the transport returns, before any element is given up on")
    void testReturnedFailureIsLoggedWithItsCause() throws Exception {
        // given: a transport reporting its failure rather than throwing it, the way the README tells one to,
        // and room for several retries before anything is given up on
        var cause = new IOException("connection refused");
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1)
                .maxConsecutiveFailures(() -> 5)
                .transport(elements -> completedFuture(TransmissionResult.failure(cause)))
                .build();

        // when
        List<StatusData> statuses = captureStatuses(() -> {
            batcher.add(List.of("a"));
            batcher.awaitPendingTransmissions();
        });

        // then: the outage shows from its first failure, with what caused it
        assertThat(statuses)
                .as("Expected a warning carrying the cause, got: " + statuses.stream().map(StatusData::getFormattedStatus).toList())
                .anyMatch(status -> status.getLevel() == Level.WARN && status.getThrowable() == cause);
    }

    @Test
    @DisplayName("Logs the cause of the last failure along with the elements it gives up on")
    void testGivingUpIsLoggedWithTheCause() throws Exception {
        // given
        var cause = new IOException("connection refused");
        Batcher<String> batcher = Batcher.<String>builder()
                .flushThreshold(() -> 1)
                .maxConsecutiveFailures(() -> 1)
                .transport(elements -> completedFuture(TransmissionResult.failure(cause)))
                .build();

        // when
        List<StatusData> statuses = captureStatuses(() -> {
            batcher.add(List.of("a"));
            batcher.awaitPendingTransmissions();
        });

        // then
        assertThat(statuses)
                .as("Expected an error carrying the cause, got: " + statuses.stream().map(StatusData::getFormattedStatus).toList())
                .anyMatch(status -> status.getLevel() == Level.ERROR && status.getThrowable() == cause);
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
