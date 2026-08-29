package com.chavaillaz.appender.log4j;

import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.jupiter.api.Test;

@Slf4j
class AbstractBatchLogDeliveryTest {

    @Test
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

    @Test
    void testNotSendingNullList() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(2);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send((List<Map<String, Object>>) null);

        // then
        assertEquals(0, logDelivery.countBulkSent.get());
    }

    @Test
    void testNotSendingEmptyList() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(2);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(emptyList());

        // then
        assertEquals(0, logDelivery.countBulkSent.get());
    }

    @Test
    void testSendingReachedThreshold() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(2);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());

        // then
        assertEquals(1, logDelivery.countBulkSent.get());
    }

    @Test
    void testSendingMultipleDocumentsInOneCallReachingThreshold() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(2);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(List.of(Map.of(), Map.of()));

        // then
        assertEquals(1, logDelivery.countBulkSent.get());
        assertEquals(2, logDelivery.lastBulkSize.get());
    }

    @Test
    void testSendingForcedFlush() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(2);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(Map.of());
        logDelivery.flush();

        // then
        assertEquals(1, logDelivery.countBulkSent.get());
    }

    @Test
    void testCloseFlushesRemainingBatch() throws Exception {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(10);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(Map.of());
        logDelivery.close();

        // then
        assertEquals(1, logDelivery.countBulkSent.get());
    }

    @Test
    void testSendingRetriedOnFlush() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(2);
        var logDelivery = new TestLogDelivery(configuration);
        logDelivery.bulkResult = false;

        // when
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());

        // then (first attempt failed, documents are kept for a later retry)
        assertEquals(1, logDelivery.countBulkSent.get());

        // when the periodic flush retries once the backend is available again
        logDelivery.bulkResult = true;
        logDelivery.flush();

        // then
        assertEquals(2, logDelivery.countBulkSent.get());
    }

    @Test
    void testSendingNotRetriedOnEveryMessageWhileBatchingAndFailing() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(2);
        var logDelivery = new TestLogDelivery(configuration);
        logDelivery.bulkResult = false;

        // when reaching the threshold once (first attempt) then sending many more messages while still failing
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());
        for (int i = 0; i < 20; i++) {
            logDelivery.send(Map.of());
        }

        // then only the first attempt was made, avoiding hammering the downstream system on every message
        assertEquals(1, logDelivery.countBulkSent.get());

        // when the periodic flush retries with all the accumulated documents
        logDelivery.bulkResult = true;
        logDelivery.flush();

        // then
        assertEquals(2, logDelivery.countBulkSent.get());
        assertEquals(22, logDelivery.lastBulkSize.get());
    }

    @Test
    void testSendingRetriedOnEveryMessageWhenBatchingDisabled() {
        // given (threshold of 1 means no batching is wanted, so every message should be attempted)
        var configuration = new TestLogConfiguration();
        configuration.setFlushThreshold(1);
        var logDelivery = new TestLogDelivery(configuration);
        logDelivery.bulkResult = false;

        // when
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());

        // then
        assertEquals(3, logDelivery.countBulkSent.get());
    }

    @Test
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

        // then
        assertEquals(threads * messagesPerThread, logDelivery.totalDocumentsSent.get());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class TestLogDelivery extends AbstractBatchLogDelivery<TestLogConfiguration> {

        MutableInt countBulkSent = new MutableInt();
        MutableInt lastBulkSize = new MutableInt();
        AtomicInteger totalDocumentsSent = new AtomicInteger();
        boolean bulkResult = true;

        protected TestLogDelivery(TestLogConfiguration configuration) {
            super(configuration);
        }

        @Override
        protected boolean sendBulk(List<Map<String, Object>> documents) {
            countBulkSent.increment();
            lastBulkSize.setValue(documents.size());
            if (bulkResult) {
                totalDocumentsSent.addAndGet(documents.size());
            }
            return bulkResult;
        }

    }

}
