package com.chavaillaz.appender.log4j;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.chavaillaz.appender.LogDeliveryListener;

@Slf4j
@DisplayName("AbstractBatchLogDelivery")
class AbstractBatchLogDeliveryTest {

    @Nested
    @DisplayName("Below the flush threshold")
    class BelowThreshold {

        @Test
        @DisplayName("Does not trigger a transmission")
        void testNotSendingBelowThreshold() {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());

            // then
            assertEquals(0, logDelivery.countBulkSent.get());
        }

        @Test
        @DisplayName("Flushing an empty batch does not trigger a transmission")
        void testNotSendingEmpty() {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.flush();

            // then
            assertEquals(0, logDelivery.countBulkSent.get());
        }

        @ParameterizedTest(name = "documents = {0}")
        @NullAndEmptySource
        @DisplayName("Sending null or no documents does not trigger a transmission")
        void testNotSendingNullOrEmptyList(List<Map<String, Object>> documents) {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(documents);

            // then
            assertEquals(0, logDelivery.countBulkSent.get());
        }

        @Test
        @DisplayName("Sending a null document does not trigger a transmission nor stack a null entry")
        void testNotSendingNullDocument() throws Exception {
            // given: a LogConverter returning null (e.g. to signal "skip this event") must be tolerated
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send((Map<String, Object>) null);
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(0, logDelivery.countBulkSent.get());
        }

        @Test
        @DisplayName("Filters out null elements of a list before stacking them")
        void testFiltersOutNullElementsOfList() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Arrays.asList(Map.of("id", 1), null, Map.of("id", 2)));
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, logDelivery.countBulkSent.get());
            assertEquals(List.of(Map.of("id", 1), Map.of("id", 2)), logDelivery.lastBulkDocuments);
        }

        @Test
        @DisplayName("Does not trigger a transmission when a list only contains null elements")
        void testNotSendingListOfOnlyNullElements() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Arrays.asList(null, null));
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(0, logDelivery.countBulkSent.get());
        }

    }

    @Nested
    @DisplayName("Configuration validation")
    class ConfigurationValidation {

        @Test
        @DisplayName("Rejects a zero maximum number of concurrent transmissions at construction")
        void testRejectsZeroMaxConcurrentTransmissions() {
            // given: a value of 0 would leave the internal semaphore with no permits to ever hand out,
            // so every dispatch() would block forever instead of merely lowering throughput
            var configuration = new TestLogConfiguration();
            configuration.setMaxConcurrentTransmissions(0);

            // when / then
            assertThrows(IllegalArgumentException.class, () -> new TestLogDelivery(configuration));
        }

        @Test
        @DisplayName("Rejects a negative maximum number of concurrent transmissions at construction")
        void testRejectsNegativeMaxConcurrentTransmissions() {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setMaxConcurrentTransmissions(-1);

            // when / then
            assertThrows(IllegalArgumentException.class, () -> new TestLogDelivery(configuration));
        }

        @Test
        @DisplayName("Tolerates a negative maximum pending documents by discarding instead of failing")
        void testNegativeMaxPendingDocumentsDoesNotThrow() throws Exception {
            // given: a negative maxPendingDocuments, the value that would otherwise make
            // trimToMaxPendingDocuments() compute an out-of-bounds sublist range; caught as soon as the
            // document is accumulated (a capacity of "less than zero" discards on arrival, before ever
            // attempting a transmission doomed to be undone anyway), so bulkResult/countBulkSent are not
            // exercised by this test
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            configuration.setMaxPendingDocuments(-1);
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);

            // when / then
            assertDoesNotThrow(() -> logDelivery.send(Map.of("id", 1)));
            // the notification itself is asynchronous and best-effort (see notifyDropped()'s documentation),
            // so it is awaited by polling rather than by logDelivery.awaitPendingTransmissions(), which does
            // not track it
            awaitDropped(listener, 1);
            assertEquals(1, listener.dropped.size());
            assertEquals(List.of(Map.of("id", 1)), listener.dropped.get(0).documents());
        }

        private void awaitDropped(RecordingListener listener, int expected) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
            while (listener.dropped.size() < expected) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new AssertionError("Timed out waiting for " + expected + " dropped notification(s)");
                }
                Thread.sleep(5);
            }
        }

    }

    @Nested
    @DisplayName("Once the flush threshold is reached")
    class ThresholdReached {

        @Test
        @DisplayName("Triggers a single transmission of the accumulated batch")
        void testSendingReachedThreshold() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, logDelivery.countBulkSent.get());
        }

        @Test
        @DisplayName("Is also reached when several documents are sent in a single call")
        void testSendingMultipleDocumentsInOneCallReachingThreshold() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(List.of(Map.of(), Map.of()));
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, logDelivery.countBulkSent.get());
            assertEquals(2, logDelivery.lastBulkSize.get());
        }

        @Test
        @DisplayName("Can also be forced early by calling flush()")
        void testSendingForcedFlush() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, logDelivery.countBulkSent.get());
        }

    }

    @Nested
    @DisplayName("When closing")
    class Closing {

        @Test
        @DisplayName("Flushes and waits for the remaining batch to be transmitted before returning")
        void testCloseFlushesRemainingBatch() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());
            logDelivery.close();

            // then (close() waits for the transmission it triggers to complete before returning)
            assertEquals(1, logDelivery.countBulkSent.get());
        }

    }

    @Nested
    @DisplayName("When a transmission fails")
    class TransmissionFailure {

        @Test
        @DisplayName("Keeps the documents and retries on the next flush")
        void testSendingRetriedOnFlush() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then (first attempt failed, documents are kept for a later retry)
            assertEquals(1, logDelivery.countBulkSent.get());

            // when the periodic flush retries once the backend is available again
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(2, logDelivery.countBulkSent.get());
        }

        @Test
        @DisplayName("Is not retried on every new message while batching is enabled, only on the next flush")
        void testSendingNotRetriedOnEveryMessageWhileBatchingAndFailing() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;

            // when reaching the threshold once (first attempt) then sending many more messages while still failing
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions(); // ensure the first attempt (and its requeue) is fully processed
            for (int i = 0; i < 20; i++) {
                logDelivery.send(Map.of());
            }

            // then only the first attempt was made, avoiding hammering the downstream system on every message
            assertEquals(1, logDelivery.countBulkSent.get());

            // when the periodic flush retries with all the accumulated documents
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(2, logDelivery.countBulkSent.get());
            assertEquals(22, logDelivery.lastBulkSize.get());
        }

        @Test
        @DisplayName("Is retried on every message when batching is disabled (flush threshold of 1)")
        void testSendingRetriedOnEveryMessageWhenBatchingDisabled() throws Exception {
            // given (threshold of 1 means no batching is wanted, so every message should be attempted)
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(3, logDelivery.countBulkSent.get());
        }

    }

    @Nested
    @DisplayName("Safety limits")
    class SafetyLimits {

        @Test
        @DisplayName("Discards the oldest documents once a retried batch exceeds the maximum pending documents")
        void testPendingDocumentsCapDiscardsOldestDocuments() throws Exception {
            // given: batching disabled (threshold of 1) so every send() dispatches immediately and
            // deterministically, and a maximum pending documents small enough to be reached by 4 documents,
            // both configured through LogConfiguration rather than by subclassing the delivery
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            configuration.setMaxPendingDocuments(3);
            configuration.setMaxConsecutiveFailures(1_000); // effectively disabled, tested separately below
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;

            // when sending 4 documents one by one, each one failing and being merged back into the batch
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of("id", i));
                logDelivery.awaitPendingTransmissions();
            }

            // then the oldest document (id=0) was discarded once the cap of 3 was exceeded, its actual
            // content was reported (not just a count), and the reason explains why
            assertEquals(1, listener.dropped.size());
            assertEquals(List.of(Map.of("id", 0)), listener.dropped.get(0).documents());
            assertTrue(listener.dropped.get(0).reason().contains("pending documents"));

            // when the backend recovers and the batch is retried
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then only the 3 most recently received documents were ultimately sent
            assertEquals(3, logDelivery.lastBulkSize.get());
        }

        @Test
        @DisplayName("Discards the oldest documents while accumulating, even before any transmission is attempted")
        void testPendingDocumentsCapEnforcedWhileAccumulating() throws Exception {
            // given: a flush threshold high enough to never be reached by this test on its own (so the
            // batch keeps growing purely through accumulation) and a maximum pending documents small enough
            // to be exceeded well before that; this exercises the cap being enforced by stackAndSend()
            // itself, as opposed to testPendingDocumentsCapDiscardsOldestDocuments() above which only
            // exercises it being enforced by requeue() after a failed transmission
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(100);
            configuration.setMaxPendingDocuments(3);
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);

            // when accumulating 4 documents, one at a time, without ever reaching the flush threshold or
            // any transmission ever failing (bulkResult defaults to true, and nothing is dispatched yet)
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of("id", i));
            }

            // then the oldest document (id=0) was already discarded once the cap of 3 was exceeded, well
            // before the flush threshold was ever reached; awaited rather than asserted immediately since
            // this particular notification is intentionally not tracked by awaitPendingTransmissions() (see
            // notifyDropped()'s documentation), unlike every other assertion on a listener in this test class
            assertEquals(0, logDelivery.countBulkSent.get());
            awaitDropped(listener, 1);
            assertEquals(1, listener.dropped.size());
            assertEquals(List.of(Map.of("id", 0)), listener.dropped.get(0).documents());
            assertTrue(listener.dropped.get(0).reason().contains("pending documents"));

            // when flushing what remains
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then only the 3 most recently received documents were ever sent
            assertEquals(1, logDelivery.countBulkSent.get());
            assertEquals(List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)), logDelivery.lastBulkDocuments);
        }

        @Test
        @DisplayName("Gives up on a batch after too many consecutive failed attempts, unblocking the pipeline")
        void testGivesUpAfterMaxConsecutiveFailures() throws Exception {
            // given: the limit is configured through LogConfiguration rather than by subclassing the delivery
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10); // Only flush() (below) triggers a (re)transmission
            configuration.setMaxConsecutiveFailures(3);
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of("id", "poison"));

            // when retrying the same (never growing) batch until the limit is reached
            for (int i = 0; i < configuration.getMaxConsecutiveFailures(); i++) {
                logDelivery.flush();
                logDelivery.awaitPendingTransmissions();
            }

            // then it was attempted exactly the allowed number of times, then given up on and reported,
            // with the actual document handed over so it could still be saved through a fallback if needed
            assertEquals(3, logDelivery.countBulkSent.get());
            assertEquals(1, listener.dropped.size());
            assertEquals(List.of(Map.of("id", "poison")), listener.dropped.get(0).documents());
            assertTrue(listener.dropped.get(0).reason().contains("consecutive"));

            // when new documents arrive afterward
            logDelivery.bulkResult = true;
            logDelivery.send(Map.of());
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then the pipeline is not stuck: the new document is delivered normally
            assertEquals(4, logDelivery.countBulkSent.get());
            assertEquals(1, logDelivery.lastBulkSize.get());
        }

        @ParameterizedTest(name = "maxConsecutiveFailures = {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Never gives up when MaxConsecutiveFailures is zero or negative, retrying indefinitely instead")
        void testNonPositiveMaxConsecutiveFailuresDisablesTheLimit(int maxConsecutiveFailures) throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10); // Only flush() (below) triggers a (re)transmission
            configuration.setMaxConsecutiveFailures(maxConsecutiveFailures);
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of("id", "poison"));

            // when retrying well past the number of attempts that would otherwise trigger a give-up
            for (int i = 0; i < 10; i++) {
                logDelivery.flush();
                logDelivery.awaitPendingTransmissions();
            }

            // then it was retried every single time instead of ever being given up on
            assertEquals(10, logDelivery.countBulkSent.get());
            assertEquals(0, listener.dropped.size());

            // when the backend eventually recovers
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then the original document is still delivered, having never been discarded
            assertEquals(11, logDelivery.countBulkSent.get());
            assertEquals(List.of(Map.of("id", "poison")), logDelivery.lastBulkDocuments);
        }

        @Test
        @DisplayName("Gives up on a poison document based only on its own failures, unaffected by unrelated concurrent successes")
        void testConsecutiveFailuresAreTrackedPerBatchNotGlobally() throws Exception {
            // given: batching disabled (threshold of 1) so every send()/flush() dispatches immediately as
            // its own independent transmission, up to the default of 4 concurrently; sendBulk() fails only
            // for the "poison" document (blocking on a per-round latch first, to guarantee it is still in
            // flight when an unrelated document is sent and succeeds concurrently) and always succeeds for
            // everything else
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            configuration.setMaxConsecutiveFailures(3);
            var listener = new RecordingListener();
            var poisonEntered = new AtomicReference<CountDownLatch>();
            var releasePoison = new AtomicReference<CountDownLatch>();
            var cleanSucceeded = new AtomicInteger();
            var poisonAttempts = new AtomicInteger();
            var logDelivery = new TestLogDelivery(configuration, listener) {
                @Override
                protected boolean sendBulk(List<Map<String, Object>> documents) {
                    boolean poison = documents.stream().anyMatch(document -> document.containsKey("poison"));
                    if (poison) {
                        poisonAttempts.incrementAndGet();
                        poisonEntered.get().countDown();
                        await(releasePoison.get());
                        return false;
                    }
                    cleanSucceeded.incrementAndGet();
                    return true;
                }
            };

            // when the poison document fails MaxConsecutiveFailures times in a row, with a different,
            // unrelated document successfully transmitted concurrently during each of its attempts
            int maxConsecutiveFailures = configuration.getMaxConsecutiveFailures();
            for (int round = 1; round <= maxConsecutiveFailures; round++) {
                poisonEntered.set(new CountDownLatch(1));
                releasePoison.set(new CountDownLatch(1));
                if (round == 1) {
                    logDelivery.send(Map.of("poison", true));
                } else {
                    logDelivery.flush(); // re-dispatches the poison document requeued after the previous round
                }
                assertTrue(poisonEntered.get().await(2, SECONDS), "Round " + round + ": poison should be in flight");

                logDelivery.send(Map.of("clean", round));
                awaitAtLeast(cleanSucceeded, round);

                releasePoison.get().countDown();
                logDelivery.awaitPendingTransmissions();
            }

            // then: despite as many unrelated concurrent successes as the failure limit itself, the poison
            // document was still given up on based purely on its own failure count
            assertEquals(maxConsecutiveFailures, poisonAttempts.get());
            assertEquals(maxConsecutiveFailures, cleanSucceeded.get());
            assertEquals(1, listener.dropped.size());
            assertEquals(List.of(Map.of("poison", true)), listener.dropped.get(0).documents());
            assertTrue(listener.dropped.get(0).reason().contains("consecutive"));
        }

        private void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitAtLeast(AtomicInteger counter, int expected) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
            while (counter.get() < expected) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new AssertionError("Timed out waiting for the counter to reach " + expected);
                }
                Thread.sleep(5);
            }
        }

        private void awaitDropped(RecordingListener listener, int expected) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
            while (listener.dropped.size() < expected) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new AssertionError("Timed out waiting for " + expected + " dropped notification(s)");
                }
                Thread.sleep(5);
            }
        }

    }

    @Nested
    @DisplayName("Request size chunking")
    class RequestSizeChunking {

        @Test
        @DisplayName("Sends the whole batch as a single request by default (no chunking)")
        void testDoesNotChunkByDefault() throws Exception {
            // given: MaxRequestSize left at its default (unbounded)
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(20);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            for (int i = 0; i < 20; i++) {
                logDelivery.send(Map.of("id", i));
            }
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, logDelivery.countBulkSent.get());
            assertEquals(20, logDelivery.lastBulkSize.get());
        }

        @ParameterizedTest(name = "maxRequestSize = {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Does not chunk when MaxRequestSize is zero or negative, treating it as disabled")
        void testNonPositiveMaxRequestSizeDisablesChunking(int maxRequestSize) throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(5);
            configuration.setMaxRequestSize(maxRequestSize);
            var logDelivery = new TestLogDelivery(configuration);

            // when
            for (int i = 0; i < 5; i++) {
                logDelivery.send(Map.of("id", i));
            }
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, logDelivery.countBulkSent.get());
            assertEquals(5, logDelivery.lastBulkSize.get());
        }

        @Test
        @DisplayName("Splits a batch larger than MaxRequestSize into several requests, none exceeding it")
        void testSplitsBatchLargerThanMaxRequestSize() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            configuration.setMaxRequestSize(4);
            var logDelivery = new TestLogDelivery(configuration);
            List<Map<String, Object>> documents = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                documents.add(Map.of("id", i));
            }

            // when
            logDelivery.send(documents);
            logDelivery.awaitPendingTransmissions();

            // then: split into 3 requests (4 + 4 + 2), none exceeding the configured limit
            assertEquals(3, logDelivery.countBulkSent.get());
            for (List<Map<String, Object>> chunk : logDelivery.allBulkDocuments) {
                assertTrue(chunk.size() <= 4, "No chunk should exceed MaxRequestSize, was " + chunk.size());
            }

            // then: every document was sent exactly once across the chunks (order between chunks is not
            // guaranteed, since they may transmit concurrently, so ids are compared as a set once sorted)
            List<Integer> sentIds = logDelivery.allBulkDocuments.stream()
                    .flatMap(List::stream)
                    .map(document -> (Integer) document.get("id"))
                    .sorted()
                    .toList();
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), sentIds);
        }

        @Test
        @DisplayName("Transmits chunks of the same batch concurrently rather than sequentially")
        void testChunksTransmitConcurrently() throws Exception {
            // given: a batch of 4 split into 4 chunks of 1, each transmission taking 200ms
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(4);
            configuration.setMaxRequestSize(1);
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(200));

            // when
            var start = System.nanoTime();
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of("id", i));
            }
            logDelivery.awaitPendingTransmissions();
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: sequential chunking would take at least 4 * 200ms = 800ms
            assertEquals(4, logDelivery.countBulkSent.get());
            assertTrue(elapsedMillis < 600, "Chunks should transmit concurrently, took " + elapsedMillis + "ms");
        }

        @Test
        @DisplayName("Retries a failed chunk independently, without affecting or duplicating a sibling that succeeded")
        void testFailedChunkIsRetriedIndependentlyOfSiblings() throws Exception {
            // given: a batch of 4 split into two chunks of 2; only the chunk containing id=0 fails
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(4);
            configuration.setMaxRequestSize(2);
            var attempts = new CopyOnWriteArrayList<List<Map<String, Object>>>();
            var logDelivery = new TestLogDelivery(configuration) {
                @Override
                protected boolean sendBulk(List<Map<String, Object>> documents) {
                    attempts.add(documents);
                    boolean poison = documents.stream().anyMatch(document -> document.get("id").equals(0));
                    return !poison;
                }
            };

            // when
            logDelivery.send(List.of(Map.of("id", 0), Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));
            logDelivery.awaitPendingTransmissions();

            // then: both chunks were attempted, exactly one of them failing
            assertEquals(2, attempts.size());
            List<Map<String, Object>> failedChunk = attempts.stream()
                    .filter(chunk -> chunk.stream().anyMatch(document -> document.get("id").equals(0)))
                    .findFirst()
                    .orElseThrow();

            // when the pending batch (still holding only the requeued failed chunk) is retried
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then only the previously failed chunk was retried, with its exact original content, not the
            // sibling that had already succeeded
            assertEquals(3, attempts.size());
            assertEquals(failedChunk, attempts.get(2));
        }

    }

    @Nested
    @DisplayName("Observability")
    class Observability {

        @Test
        @DisplayName("Notifies the listener when a transmission succeeds")
        void testListenerNotifiedOnSuccess() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(2);
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, listener.succeeded.size());
            assertEquals(2, listener.succeeded.get(0).documentCount());
            assertTrue(listener.failed.isEmpty());
        }

        @Test
        @DisplayName("Notifies the listener with no cause when sendBulk() simply returns false")
        void testListenerNotifiedOnFailureWithoutCause() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;

            // when
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, listener.failed.size());
            assertEquals(1, listener.failed.get(0).documentCount());
            assertNull(listener.failed.get(0).cause());
        }

        @Test
        @DisplayName("Notifies the listener with the cause when sendBulk() throws")
        void testListenerNotifiedOnFailureWithCause() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            var exception = new RuntimeException("Simulated failure");
            logDelivery.failureToThrow = exception;

            // when
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(1, listener.failed.size());
            assertSame(exception, listener.failed.get(0).cause());
        }

        @Test
        @DisplayName("Still requeues failed documents for retry even when onTransmissionFailed() throws")
        void testFailedDocumentsAreRequeuedDespiteThrowingListener() throws Exception {
            // given: a listener whose onTransmissionFailed() implementation is broken; without a guard
            // around this notification, the exception it throws would skip onTransmissionFailure()
            // entirely, losing the failed document without ever requeuing or reporting it as dropped
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onTransmissionFailed(int documentCount, Throwable cause) {
                    throw new RuntimeException("Simulated misbehaving listener");
                }
            };
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;

            // when the first attempt fails (the listener throwing must not prevent the requeue)
            logDelivery.send(Map.of("id", 1));
            logDelivery.awaitPendingTransmissions();
            assertEquals(1, logDelivery.countBulkSent.get());

            // when the backend recovers and the batch is retried
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then the document was not lost: it was requeued and eventually delivered
            assertEquals(2, logDelivery.countBulkSent.get());
            assertEquals(List.of(Map.of("id", 1)), logDelivery.lastBulkDocuments);
        }

        @Test
        @DisplayName("Keeps transmitting further batches even when onTransmissionSucceeded() throws")
        void testFurtherTransmissionsContinueDespiteThrowingListenerOnSuccess() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onTransmissionSucceeded(int documentCount, long durationMillis) {
                    throw new RuntimeException("Simulated misbehaving listener");
                }
            };
            var logDelivery = new TestLogDelivery(configuration, listener);

            // when
            logDelivery.send(Map.of("id", 1));
            logDelivery.awaitPendingTransmissions();
            logDelivery.send(Map.of("id", 2));
            logDelivery.awaitPendingTransmissions();

            // then both transmissions completed normally despite the listener throwing on each one
            assertEquals(2, logDelivery.countBulkSent.get());
        }

    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("Does not lose or duplicate documents when sent concurrently by several threads")
        void testConcurrentSendsDoNotLoseOrDuplicateDocuments() throws Exception {
            // given
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1_000_000); // Never reached, everything is flushed manually below
            var logDelivery = new TestLogDelivery(configuration);
            var threads = 8;
            var messagesPerThread = 500;
            var pool = Executors.newFixedThreadPool(threads);
            var ready = new CountDownLatch(threads);
            var start = new CountDownLatch(1);

            // when
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    for (int i = 0; i < messagesPerThread; i++) {
                        logDelivery.send(Map.of());
                    }
                });
            }
            assertTrue(ready.await(5, SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, SECONDS));
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertEquals(threads * messagesPerThread, logDelivery.totalDocumentsSent.get());
        }

        @Test
        @DisplayName("Overlaps transmissions instead of delaying the accumulation of the next batch")
        void testTransmissionsOverlapInsteadOfDelayingBatchAccumulation() throws Exception {
            // given: every message is sent individually (threshold of 1), each transmission taking 200ms
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(200));

            // when sending 4 messages (the default concurrency limit), all transmissions should overlap
            var start = System.nanoTime();
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of());
            }
            logDelivery.awaitPendingTransmissions();
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: sequential transmission would take at least 4 * 200ms = 800ms
            assertEquals(4, logDelivery.countBulkSent.get());
            assertTrue(elapsedMillis < 600, "Transmissions should overlap, took " + elapsedMillis + "ms");
        }

        @Test
        @DisplayName("Bounds the number of transmissions running at the same time")
        void testTransmissionConcurrencyIsBounded() throws Exception {
            // given: twice the default concurrency limit (4) of messages, each transmission taking 150ms
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(150));

            // when
            var start = System.nanoTime();
            for (int i = 0; i < 8; i++) {
                logDelivery.send(Map.of());
            }
            logDelivery.awaitPendingTransmissions();
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: bounded to 4 concurrent transmissions means roughly 2 waves of 150ms are needed (~300ms),
            // well under a fully sequential execution (8 * 150ms = 1200ms) but above a single wave (150ms),
            // showing that concurrency is capped rather than unlimited
            assertEquals(8, logDelivery.countBulkSent.get());
            assertTrue(elapsedMillis >= 250, "Expected at least two waves given the concurrency bound, took " + elapsedMillis + "ms");
            assertTrue(elapsedMillis < 1_000, "Transmissions within a wave should overlap, took " + elapsedMillis + "ms");
        }

        @Test
        @DisplayName("Can be lowered through the configuration")
        void testConcurrencyLimitIsConfigurable() throws Exception {
            // given: half the default concurrency limit, each transmission taking 150ms
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            configuration.setMaxConcurrentTransmissions(2);
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(150));

            // when sending 4 messages, twice the configured limit
            var start = System.nanoTime();
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of());
            }
            logDelivery.awaitPendingTransmissions();
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: bounded to 2 concurrent transmissions means 2 waves of 150ms are needed (~300ms),
            // instead of the ~150ms a single wave would take with the default limit of 4
            assertEquals(4, logDelivery.countBulkSent.get());
            assertTrue(elapsedMillis >= 250, "Expected at least two waves given the configured limit of 2, took " + elapsedMillis + "ms");
        }

        @Test
        @DisplayName("Does not block accumulation of the next batch while notifying a slow listener of dropped documents")
        void testSlowListenerDoesNotBlockBatchAccumulation() throws Exception {
            // given: batching disabled (threshold of 1) and a maximum pending documents of 1, so that a
            // second failed document overflows the batch and triggers a drop notification; the listener
            // blocks until released, to prove send() does not need to wait for it to return
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            configuration.setMaxPendingDocuments(1);
            var listenerEntered = new CountDownLatch(1);
            var releaseListener = new CountDownLatch(1);
            var listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    listenerEntered.countDown();
                    await(releaseListener);
                }
            };
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;

            // when: the first failed document is requeued without overflowing yet, then a second one
            // overflows the batch of size 1, triggering a drop whose listener notification blocks
            logDelivery.send(Map.of("id", 1));
            logDelivery.awaitPendingTransmissions();
            logDelivery.send(Map.of("id", 2));
            assertTrue(listenerEntered.await(2, SECONDS), "The listener should have been entered by now");

            // then: send() must still be able to accumulate a new document promptly, proving it does not
            // hold (or wait for) the same lock as the still-blocked listener call
            var start = System.nanoTime();
            logDelivery.send(Map.of("id", 3));
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMillis < 500, "send() should not wait for the listener, took " + elapsedMillis + "ms");

            // cleanup
            releaseListener.countDown();
            logDelivery.awaitPendingTransmissions();
        }

        private void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

    }

    static class TestLogDelivery extends AbstractBatchLogDelivery<TestLogConfiguration> {

        AtomicInteger countBulkSent = new AtomicInteger();
        AtomicInteger lastBulkSize = new AtomicInteger();
        AtomicInteger totalDocumentsSent = new AtomicInteger();
        List<List<Map<String, Object>>> allBulkDocuments = new CopyOnWriteArrayList<>();
        volatile List<Map<String, Object>> lastBulkDocuments;
        volatile boolean bulkResult = true;
        volatile RuntimeException failureToThrow;

        protected TestLogDelivery(TestLogConfiguration configuration) {
            super(configuration);
        }

        protected TestLogDelivery(TestLogConfiguration configuration, LogDeliveryListener listener) {
            super(configuration, listener);
        }

        @Override
        protected boolean sendBulk(List<Map<String, Object>> documents) {
            countBulkSent.incrementAndGet();
            lastBulkSize.set(documents.size());
            lastBulkDocuments = documents;
            allBulkDocuments.add(documents);
            if (failureToThrow != null) {
                throw failureToThrow;
            }
            if (bulkResult) {
                totalDocumentsSent.addAndGet(documents.size());
            }
            return bulkResult;
        }

    }

    static class RecordingListener implements LogDeliveryListener {

        List<Succeeded> succeeded = new CopyOnWriteArrayList<>();
        List<Failed> failed = new CopyOnWriteArrayList<>();
        List<Dropped> dropped = new CopyOnWriteArrayList<>();

        @Override
        public void onTransmissionSucceeded(int documentCount, long durationMillis) {
            succeeded.add(new Succeeded(documentCount, durationMillis));
        }

        @Override
        public void onTransmissionFailed(int documentCount, Throwable cause) {
            failed.add(new Failed(documentCount, cause));
        }

        @Override
        public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
            dropped.add(new Dropped(documents, reason));
        }

        record Succeeded(int documentCount, long durationMillis) {

        }

        record Failed(int documentCount, Throwable cause) {

        }

        record Dropped(List<Map<String, Object>> documents, String reason) {

        }

    }

    static class SlowTestLogDelivery extends AbstractBatchLogDelivery<TestLogConfiguration> {

        AtomicInteger countBulkSent = new AtomicInteger();
        Duration delay;

        protected SlowTestLogDelivery(TestLogConfiguration configuration, Duration delay) {
            super(configuration);
            this.delay = delay;
        }

        @Override
        protected boolean sendBulk(List<Map<String, Object>> documents) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            countBulkSent.incrementAndGet();
            return true;
        }

    }

}
