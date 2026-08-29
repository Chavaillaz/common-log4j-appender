package com.chavaillaz.appender.log4j;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
        volatile boolean bulkResult = true;

        protected TestLogDelivery(TestLogConfiguration configuration) {
            super(configuration);
        }

        @Override
        protected boolean sendBulk(List<Map<String, Object>> documents) {
            countBulkSent.incrementAndGet();
            lastBulkSize.set(documents.size());
            if (bulkResult) {
                totalDocumentsSent.addAndGet(documents.size());
            }
            return bulkResult;
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
