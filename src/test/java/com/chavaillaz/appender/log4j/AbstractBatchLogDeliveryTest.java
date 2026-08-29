package com.chavaillaz.appender.log4j;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.chavaillaz.appender.LogDeliveryListener;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

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
        @DisplayName("Discards the oldest documents once a retried batch exceeds the maximum batch size")
        void testBatchSizeCapDiscardsOldestDocuments() throws Exception {
            // given: batching disabled (threshold of 1) so every send() dispatches immediately and
            // deterministically, and a maximum batch size small enough to be reached by 4 documents,
            // both configured through LogConfiguration rather than by subclassing the delivery
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            configuration.setMaxBatchSize(3);
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
            assertTrue(listener.dropped.get(0).reason().contains("batch size"));

            // when the backend recovers and the batch is retried
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then only the 3 most recently received documents were ultimately sent
            assertEquals(3, logDelivery.lastBulkSize.get());
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
