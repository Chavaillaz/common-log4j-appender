package com.chavaillaz.appender.log4j;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import com.chavaillaz.appender.DeliveryHealth;
import com.chavaillaz.appender.DeliveryMetrics;
import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDeliveryListener;
import com.chavaillaz.appender.TransmissionResult;
import com.chavaillaz.appender.internal.BackoffPolicy;

@Slf4j
@DisplayName("AbstractBatchLogDelivery")
class AbstractBatchLogDeliveryTest {

    /**
     * Waits for the given number of drop notifications to arrive. Needed only for a drop happening while
     * accumulating, which is deliberately notified off the calling thread and therefore not tracked by
     * {@code awaitPendingTransmissions()} (see {@code ListenerNotifier.droppedInBackground()}); every other
     * assertion on a listener in this class can be made immediately.
     *
     * @param listener The listener to watch
     * @param expected The number of drop notifications to wait for
     */
    private static void awaitDropped(RecordingListener listener, int expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
        while (listener.dropped.size() < expected) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for " + expected + " dropped notification(s)");
            }
            Thread.sleep(5);
        }
    }

    @Nested
    @DisplayName("Below the flush threshold")
    class BelowThreshold {

        @Test
        @DisplayName("Does not trigger a transmission")
        void testNotSendingBelowThreshold() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then: waited for, a transmission running on a thread of its own once dispatched
            assertThat(logDelivery.countBulkSent.get()).isZero();
        }

        @Test
        @DisplayName("Flushing an empty batch does not trigger a transmission")
        void testNotSendingEmpty() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isZero();
        }

        @ParameterizedTest(name = "documents = {0}")
        @NullAndEmptySource
        @DisplayName("Sending null or no documents does not trigger a transmission")
        void testNotSendingNullOrEmptyList(List<Map<String, Object>> documents) throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(documents);
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isZero();
        }

        @Test
        @DisplayName("Sending a null document does not trigger a transmission nor stack a null entry")
        void testNotSendingNullDocument() throws Exception {
            // given: a LogConverter returning null (e.g. to signal "skip this event") must be tolerated
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send((Map<String, Object>) null);
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isZero();
        }

        @Test
        @DisplayName("Filters out null elements of a list before stacking them")
        void testFiltersOutNullElementsOfList() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Arrays.asList(Map.of("id", 1), null, Map.of("id", 2)));
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
            assertThat(logDelivery.lastBulkDocuments).containsExactly(Map.of("id", 1), Map.of("id", 2));
        }

        @Test
        @DisplayName("Does not trigger a transmission when a list only contains null elements")
        void testNotSendingListOfOnlyNullElements() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Arrays.asList(null, null));
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isZero();
        }

    }

    @Nested
    @DisplayName("Once the flush threshold is reached")
    class ThresholdReached {

        @Test
        @DisplayName("Triggers a single transmission of the accumulated batch")
        void testSendingReachedThreshold() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Is also reached when several documents are sent in a single call")
        void testSendingMultipleDocumentsInOneCallReachingThreshold() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(List.of(Map.of(), Map.of()));
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
            assertThat(logDelivery.lastBulkSize.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("Can also be forced early by calling flush()")
        void testSendingForcedFlush() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
        }

    }

    @Nested
    @DisplayName("Request and pending size limits")
    class ByteLimits {

        @Test
        @DisplayName("Splits a batch into requests that each stay under MaxRequestBytes")
        void testSplitsRequestsByEstimatedSize() throws Exception {
            // given: a limit low enough that two documents already exceed it, so each request carries one
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(3)
                    .maxRequestBytes(120)
                    .build();
            var logDelivery = new MeasuredTestLogDelivery(configuration, 100);

            // when
            logDelivery.send(List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));
            logDelivery.awaitPendingTransmissions();

            // then: three requests of one document each, rather than one oversized request the downstream
            // system would reject outright on its content length
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(3);
            assertThat(logDelivery.allBulkDocuments.stream().map(List::size).toList()).containsExactly(1, 1, 1);
        }

        @Test
        @DisplayName("Sends a batch reaching MaxPendingBytes below the flush threshold, rather than discarding from it")
        void testBatchReachingPendingSizeCapIsSent() throws Exception {
            // given: a batch that never fills up by count, capped at a size two documents reach
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1_000)
                    .maxPendingBytes(150)
                    .build();
            var listener = new RecordingListener();
            var logDelivery = new MeasuredTestLogDelivery(configuration, 100, listener);

            // when
            logDelivery.send(Map.<String, Object>of("id", 1));
            logDelivery.send(Map.<String, Object>of("id", 2));
            logDelivery.awaitPendingTransmissions();

            // then: nothing held it off, so nothing had to wait, nor be discarded to bound what waits
            assertThat(logDelivery.allBulkDocuments).containsExactly(List.of(Map.of("id", 1), Map.of("id", 2)));
            assertThat(listener.dropped).isEmpty();
        }

        @Test
        @DisplayName("Sends a document heavier than MaxPendingBytes on its own, rather than discarding it")
        void testDocumentHeavierThanPendingSizeCapIsSent() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .maxPendingBytes(150)
                    .build();
            var listener = new RecordingListener();
            var logDelivery = new MeasuredTestLogDelivery(configuration, 200, listener);

            // when
            logDelivery.send(Map.<String, Object>of("id", 1));
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.allBulkDocuments).containsExactly(List.of(Map.of("id", 1)));
            assertThat(listener.dropped).isEmpty();
        }

        @Test
        @DisplayName("Discards the oldest documents once the pending batch exceeds MaxPendingBytes while held off")
        void testPendingSizeCapDiscardsOldestDocuments() throws Exception {
            // given: a batch that never fills up by count, capped at a size two documents already exceed, and
            // a first document failing, which holds transmissions off until the next flush retries it
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1_000)
                    .maxPendingBytes(150)
                    .build();
            var listener = new RecordingListener();
            var logDelivery = new MeasuredTestLogDelivery(configuration, 100, listener);
            var first = Map.<String, Object>of("id", 1);
            logDelivery.bulkResult = false;
            logDelivery.send(first);
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // when
            logDelivery.send(Map.<String, Object>of("id", 2));

            // then: a count-based cap alone would have seen nothing wrong here, the batch holding two of the
            // thousand documents it is allowed; awaited rather than asserted immediately since a drop while
            // accumulating is intentionally not tracked by awaitPendingTransmissions() (see ListenerNotifier)
            awaitDropped(listener, 1);
            assertThat(listener.dropped).hasSize(1);
            assertThat(listener.dropped.get(0).documents()).containsExactly(first);
            assertThat(listener.dropped.get(0).reason()).isEqualTo("maximum pending bytes exceeded");
        }

        @Test
        @DisplayName("Enforces a pending size limit a subclass supplies by overriding getMaxPendingBytes()")
        void testOverriddenPendingSizeLimitIsEnforced() throws Exception {
            // given: the limit comes from the overridable getter alone, the way its own documentation offers
            // it ("override if the limit should depend on more than just the configuration"), while the
            // configuration leaves MaxPendingBytes unbounded
            var configuration = TestLogConfiguration.builder().flushThreshold(1_000).build();
            var logDelivery = new MeasuredTestLogDelivery(configuration, 100) {
                @Override
                protected long getMaxPendingBytes() {
                    return 150;
                }
            };

            // when: two documents of a hundred bytes each reach that limit
            logDelivery.send(Map.<String, Object>of("id", 1));
            logDelivery.send(Map.<String, Object>of("id", 2));
            logDelivery.awaitPendingTransmissions();

            // then: the batch goes out, far below the flush threshold, rather than the limit quietly enforcing
            // nothing because the measurement it needs was decided against once, from the configuration
            assertThat(logDelivery.allBulkDocuments).containsExactly(List.of(Map.of("id", 1), Map.of("id", 2)));
        }

        @Test
        @DisplayName("Never measures a document while both size limits are left at their defaults")
        void testDocumentsAreNotMeasuredWithoutASizeLimit() throws Exception {
            // given: the default configuration, with a delivery that records every measurement it is asked for
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new MeasuredTestLogDelivery(configuration, 100);

            // when
            logDelivery.send(List.of(Map.of("id", 1), Map.of("id", 2)));
            logDelivery.awaitPendingTransmissions();

            // then: measuring costs something per document, and a delivery with no size limit to enforce
            // must not pay it
            assertThat(logDelivery.timesMeasured.get()).isZero();
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
        }

    }

    @Nested
    @DisplayName("When closing")
    class Closing {

        @Test
        @DisplayName("Flushes and waits for the remaining batch to be transmitted before returning")
        void testCloseFlushesRemainingBatch() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(10).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            logDelivery.send(Map.of());
            logDelivery.close();

            // then (close() waits for the transmission it triggers to complete before returning)
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Reports the documents still pending once closed instead of discarding them silently")
        void testCloseReportsStillPendingDocumentsAsDropped() throws Exception {
            // given: a downstream system that is down, and a consecutive-failure limit never reached, so the
            // final flush's documents are put back for a retry that will never come
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .maxConsecutiveFailures(Integer.MAX_VALUE)
                    .build();
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;
            var document = Map.<String, Object>of("logmessage", "last words");
            logDelivery.send(document);

            // when
            logDelivery.close();

            // then: a dead-letter sink gets its last chance at them, rather than the pending batch simply
            // going away with the delivery
            assertThat(listener.dropped).hasSize(1);
            assertThat(listener.dropped.get(0).documents()).containsExactly(document);
            assertThat(listener.dropped.get(0).reason()).isEqualTo("still pending when closed");
        }

        @Test
        @DisplayName("Still closes, and still reports what was pending, when the close timeout cannot be read")
        void testCloseReportsPendingDocumentsWhenTheTimeoutCannotBeRead() throws Exception {
            // given: the same "downstream is down, nothing can be retried" setup as above, plus a
            // configuration that stops resolving right before the delivery is closed
            var configuration = new FlakyCloseTimeoutConfiguration();
            var listener = new RecordingListener();
            var logDelivery = new AbstractBatchLogDelivery<FlakyCloseTimeoutConfiguration>(configuration, listener) {
                @Override
                protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                    return completedFuture(TransmissionResult.failure(null));
                }
            };
            var document = Map.<String, Object>of("logmessage", "last words");
            logDelivery.send(document);

            // when: the close timeout is the argument to the one call that flushes, waits and reports, so
            // failing to read it used to mean that call never happened at all
            configuration.broken.set(true);
            logDelivery.close();
            configuration.broken.set(false);

            // then: the pending document still got its last chance, instead of being lost in silence by the
            // very call meant to save it -- and the batching engine was closed rather than left running
            assertThat(listener.dropped).hasSize(1);
            assertThat(listener.dropped.get(0).documents()).containsExactly(document);
            assertThat(listener.dropped.get(0).reason()).isEqualTo("still pending when closed");
        }

        @Test
        @DisplayName("Gives up after the configured close timeout instead of waiting forever on a stuck transmission")
        void testCloseIsBoundedByTheConfiguredTimeout() throws Exception {
            // given: a delivery whose future is never completed, as an unresponsive downstream system with
            // no read timeout of its own would produce, holding the only transmission slot for good
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .maxConcurrentTransmissions(1)
                    .closeTimeout(Duration.ofMillis(500))
                    .build();
            var listener = new RecordingListener();
            var logDelivery = new StuckTestLogDelivery(configuration, listener);
            var inFlight = Map.<String, Object>of("logmessage", "in flight");
            logDelivery.send(inFlight);
            logDelivery.flush();
            var pending = Map.<String, Object>of("logmessage", "never dispatched");
            logDelivery.send(pending);

            // when
            long start = System.nanoTime();
            logDelivery.close();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: an application's shutdown is not held hostage by a downstream system that never answers,
            // waiting the configured timeout and no more, and both the document never dispatched and the one the
            // stuck transmission holds get their last chance, rather than the latter vanishing with it
            assertThat(elapsedMillis)
                    .as("close() should have given up once its timeout elapsed, but took " + elapsedMillis + "ms")
                    .isBetween(500L, 950L);
            assertThat(listener.dropped).hasSize(2);
            assertThat(listener.dropped.get(0).documents()).containsExactly(pending);
            assertThat(listener.dropped.get(1).documents()).containsExactly(inFlight);
            assertThat(listener.dropped.get(1).reason()).isEqualTo("still in flight when closed");
        }

        @Test
        @DisplayName("Gives up after the timeout it is closed with, however long the configured one")
        void testCloseWithTimeoutIsBoundedByThatTimeout() throws Exception {
            // given: the same stuck transmission, and a configured close timeout far longer than what is left
            // of the budget of an appender stopping, which it closes the delivery with
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .maxConcurrentTransmissions(1)
                    .closeTimeout(Duration.ofMinutes(1))
                    .build();
            var listener = new RecordingListener();
            var logDelivery = new StuckTestLogDelivery(configuration, listener);
            var inFlight = Map.<String, Object>of("logmessage", "in flight");
            logDelivery.send(inFlight);
            logDelivery.flush();
            var pending = Map.<String, Object>of("logmessage", "never dispatched");
            logDelivery.send(pending);

            // when
            long start = System.nanoTime();
            logDelivery.close(Duration.ofMillis(500));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then
            assertThat(elapsedMillis)
                    .as("close() should have given up once its timeout elapsed, but took " + elapsedMillis + "ms")
                    .isBetween(500L, 950L);
            assertThat(listener.dropped).hasSize(2);
            assertThat(listener.dropped.get(0).documents()).containsExactly(pending);
            assertThat(listener.dropped.get(1).documents()).containsExactly(inFlight);
        }

        @Test
        @DisplayName("Closes through close(Duration) with the configured timeout, so an override releasing resources runs")
        void testCloseGoesThroughTheOverridableClose() throws Exception {
            // given: a delivery releasing its client once closed, as one built on an HTTP client does
            var configuration = TestLogConfiguration.builder()
                    .closeTimeout(Duration.ofSeconds(3))
                    .build();
            var closedWithin = new CopyOnWriteArrayList<Duration>();
            var logDelivery = new TestLogDelivery(configuration) {
                @Override
                public void close(Duration timeout) throws Exception {
                    super.close(timeout);
                    closedWithin.add(timeout);
                }
            };

            // when
            logDelivery.close();

            // then
            assertThat(closedWithin).containsExactly(Duration.ofSeconds(3));
        }

    }

    @Nested
    @DisplayName("When a transmission fails")
    class TransmissionFailure {

        @Test
        @DisplayName("Keeps the documents and retries on the next flush")
        void testSendingRetriedOnFlush() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then (first attempt failed, documents are kept for a later retry)
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);

            // when the periodic flush retries once the backend is available again
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("Is not retried on every new message while batching is enabled, only on the next flush")
        void testSendingNotRetriedOnEveryMessageWhileBatchingAndFailing() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;

            // when reaching the threshold once (first attempt) then sending many more messages while still failing
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions(); // ensure the first attempt (and its requeue) is fully processed
            for (int i = 0; i < 20; i++) {
                logDelivery.send(Map.of());
            }
            logDelivery.awaitPendingTransmissions();

            // then only the first attempt was made, avoiding hammering the downstream system on every message
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);

            // when the periodic flush retries with all the accumulated documents
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(2);
            assertThat(logDelivery.lastBulkSize.get()).isEqualTo(22);
        }

        @Test
        @DisplayName("Is retried on every message when batching is disabled (flush threshold of 1)")
        void testSendingRetriedOnEveryMessageWhenBatchingDisabled() throws Exception {
            // given (threshold of 1 means no batching is wanted, so every message should be attempted)
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("Holds off retrying for the configured RetryBackoff, until closed")
        void testRetryIsHeldOffForTheConfiguredBackoff() throws Exception {
            // given: a backoff of a day, each delay being drawn at random up to it, so that none elapses during
            // this test, and a first attempt failing
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .retryBackoff(Duration.ofDays(1)).build();
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of("id", 1));
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // when: flushed again once the downstream system is back
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then: held off, as the health says
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
            assertThat(logDelivery.getHealth().status()).isEqualTo(DeliveryHealth.Status.BACKING_OFF);

            // when
            logDelivery.close();

            // then: closing being the last chance to send it, the backoff no longer holds it off
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(2);
            assertThat(logDelivery.lastBulkDocuments).containsExactly(Map.of("id", 1));
        }

        @Test
        @DisplayName("Retries at the next flush while RetryBackoff is zero, whatever MaxRetryBackoff")
        void testZeroRetryBackoffDisablesTheBackoffWhateverItsMaximum() throws Exception {
            // given: no backoff, but a maximum one of a day, and a first attempt failing
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .retryBackoff(Duration.ZERO)
                    .maxRetryBackoff(Duration.ofDays(1)).build();
            var logDelivery = new TestLogDelivery(configuration);
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of("id", 1));
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();
            var afterFailure = logDelivery.getHealth().status();

            // when
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then: retried right away, the maximum only capping a backoff there is none of
            assertThat(afterFailure).isEqualTo(DeliveryHealth.Status.RETRYING);
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(2);
            assertThat(logDelivery.lastBulkDocuments).containsExactly(Map.of("id", 1));
        }

        @Test
        @DisplayName("Draws its retry delays under RetryBackoff at first, and under MaxRetryBackoff as failures follow")
        void testRetryBackoffGrowsUpToItsConfiguredMaximum() {
            // given: a backoff of 100 ms, growing up to 800
            var options = DeliveryOptions.builder()
                    .retryBackoff(Duration.ofMillis(100))
                    .maxRetryBackoff(Duration.ofMillis(800)).build();

            // when: the policy a delivery hands its engine is asked for many delays, each one drawn at random
            BackoffPolicy backoff = AbstractBatchLogDelivery.backoffOf(options);
            long largestAfterOneFailure = LongStream.range(0, 500).map(i -> backoff.delayMillis(1)).max().orElseThrow();
            long largestAfterTenFailures = LongStream.range(0, 500).map(i -> backoff.delayMillis(10)).max().orElseThrow();

            // then: up to 100 ms after a first failure, and up to 800 ms once doubling it reached the maximum,
            // which a delivery leaving the maximum out, or taking one setting for the other, would not draw
            assertThat(largestAfterOneFailure).isBetween(51L, 100L);
            assertThat(largestAfterTenFailures).isBetween(401L, 800L);
        }

    }

    @Nested
    @DisplayName("Health signal")
    class HealthSignal {

        @Test
        @DisplayName("Reports up before any transmission, retrying after a failure, up once retried, and down once closed")
        void testHealthReflectsLastTransmissionAttempt() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var logDelivery = new TestLogDelivery(configuration);

            // then: nothing attempted yet
            assertThat(logDelivery.getHealth().status()).isEqualTo(DeliveryHealth.Status.UP);

            // when: the only attempt so far fails
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then: retried at the next flush, the configuration having no retry backoff
            assertThat(logDelivery.getHealth().status()).isEqualTo(DeliveryHealth.Status.RETRYING);

            // when: retried (via the next flush, since the documents are kept pending after the failure)
            // and this time succeeds
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.getHealth().status()).isEqualTo(DeliveryHealth.Status.UP);

            // when
            logDelivery.close();

            // then
            assertThat(logDelivery.getHealth().status()).isEqualTo(DeliveryHealth.Status.DOWN);
        }

        @Test
        @DisplayName("Reports its pending documents, their estimated size under a byte limit, and what it sent")
        void testMetricsReportTheDocumentsItHoldsAndSent() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .maxPendingBytes(1_000).build();
            var logDelivery = new MeasuredTestLogDelivery(configuration, 100);

            // when
            logDelivery.send(Map.of("id", 1));
            logDelivery.send(Map.of("id", 2));

            // then
            assertThat(logDelivery.getMetrics()).isEqualTo(new DeliveryMetrics(0, 2, 200, 0, 0, 0, 0));

            // when
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.getMetrics()).isEqualTo(new DeliveryMetrics(0, 0, 0, 0, 2, 0, 0));
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
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .maxPendingDocuments(3)
                    .maxConsecutiveFailures(1_000).build(); // effectively disabled, tested separately below
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
            assertThat(listener.dropped).hasSize(1);
            assertThat(listener.dropped.get(0).documents()).containsExactly(Map.of("id", 0));
            assertThat(listener.dropped.get(0).reason()).contains("pending documents");

            // when the backend recovers and the batch is retried
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then only the 3 most recently received documents were ultimately sent
            assertThat(logDelivery.lastBulkSize.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("Sends a batch filling up to the maximum pending documents below the flush threshold")
        void testBatchFillingUpToPendingDocumentsCapIsSent() throws Exception {
            // given: a flush threshold the batch cannot reach, being capped far below it
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .maxPendingDocuments(3).build();
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);

            // when
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of("id", i));
            }
            logDelivery.awaitPendingTransmissions();

            // then: the first three went out once they filled the batch, rather than the oldest being
            // discarded for every new one while the downstream system accepts everything
            assertThat(logDelivery.allBulkDocuments)
                    .containsExactly(List.of(Map.of("id", 0), Map.of("id", 1), Map.of("id", 2)));
            assertThat(listener.dropped).isEmpty();
        }

        @Test
        @DisplayName("Discards the oldest documents while accumulating as transmissions are held off, before any retry")
        void testPendingDocumentsCapEnforcedWhileAccumulating() throws Exception {
            // given: a first document failing, which holds transmissions off until the next flush retries
            // it; this exercises the cap being enforced while accumulating, as opposed to
            // testPendingDocumentsCapDiscardsOldestDocuments() above, where requeuing a failure enforces it
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .maxPendingDocuments(3).build();
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of("id", 0));
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // when accumulating 3 more documents, one at a time
            for (int i = 1; i < 4; i++) {
                logDelivery.send(Map.of("id", i));
            }
            logDelivery.awaitPendingTransmissions();

            // then the oldest document (id=0) was discarded once the cap of 3 was exceeded, with no attempt
            // since the failed one; awaited rather than asserted immediately since this particular notification
            // is intentionally not tracked by awaitPendingTransmissions() (see ListenerNotifier)
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
            awaitDropped(listener, 1);
            assertThat(listener.dropped).hasSize(1);
            assertThat(listener.dropped.get(0).documents()).containsExactly(Map.of("id", 0));
            assertThat(listener.dropped.get(0).reason()).contains("pending documents");

            // when the backend recovers and what remains is flushed
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then only the 3 most recently received documents were sent
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(2);
            assertThat(logDelivery.lastBulkDocuments)
                    .containsExactly(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3));
        }

        @Test
        @DisplayName("Gives up on a batch after too many consecutive failed attempts, unblocking the pipeline")
        void testGivesUpAfterMaxConsecutiveFailures() throws Exception {
            // given: the limit is configured through LogConfiguration rather than by subclassing the delivery
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10) // Only flush() (below) triggers a (re)transmission
                    .maxConsecutiveFailures(3).build();
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of("id", "poison"));

            // when retrying the same (never growing) batch until the limit is reached
            for (int i = 0; i < configuration.getDeliveryOptions().maxConsecutiveFailures(); i++) {
                logDelivery.flush();
                logDelivery.awaitPendingTransmissions();
            }

            // then it was attempted exactly the allowed number of times, then given up on and reported,
            // with the actual document handed over so it could still be saved through a fallback if needed
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(3);
            assertThat(listener.dropped).hasSize(1);
            assertThat(listener.dropped.get(0).documents()).containsExactly(Map.of("id", "poison"));
            assertThat(listener.dropped.get(0).reason()).contains("consecutive");

            // when new documents arrive afterward
            logDelivery.bulkResult = true;
            logDelivery.send(Map.of());
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then the pipeline is not stuck: the new document is delivered normally
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(4);
            assertThat(logDelivery.lastBulkSize.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Keeps retrying when MaxConsecutiveFailures is the largest value it takes")
        void testLargestMaxConsecutiveFailuresRetriesForGood() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10) // Only flush() (below) triggers a (re)transmission
                    .maxConsecutiveFailures(Integer.MAX_VALUE).build();
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
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(10);
            assertThat(listener.dropped).isEmpty();

            // when the backend eventually recovers
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then the original document is still delivered, having never been discarded
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(11);
            assertThat(logDelivery.lastBulkDocuments).containsExactly(Map.of("id", "poison"));
        }

        @Test
        @DisplayName("Gives up on a poison document based only on its own failures, unaffected by unrelated concurrent successes")
        void testConsecutiveFailuresAreTrackedPerBatchNotGlobally() throws Exception {
            // given: batching disabled (threshold of 1) so every send()/flush() dispatches immediately as
            // its own independent transmission, up to the default of 4 concurrently; sendBulk() fails only
            // for the "poison" document (blocking on a per-round latch first, to guarantee it is still in
            // flight when an unrelated document is sent and succeeds concurrently) and always succeeds for
            // everything else
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .maxConsecutiveFailures(3).build();
            var listener = new RecordingListener();
            var poisonEntered = new AtomicReference<CountDownLatch>();
            var releasePoison = new AtomicReference<CountDownLatch>();
            var cleanSucceeded = new AtomicInteger();
            var poisonAttempts = new AtomicInteger();
            var logDelivery = new TestLogDelivery(configuration, listener) {
                @Override
                protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                    boolean poison = documents.stream().anyMatch(document -> document.containsKey("poison"));
                    if (poison) {
                        poisonAttempts.incrementAndGet();
                        poisonEntered.get().countDown();
                        await(releasePoison.get());
                        return completedFuture(TransmissionResult.failure(null));
                    }
                    cleanSucceeded.incrementAndGet();
                    return completedFuture(TransmissionResult.success());
                }
            };

            // when the poison document fails MaxConsecutiveFailures times in a row, with a different,
            // unrelated document successfully transmitted concurrently during each of its attempts
            int maxConsecutiveFailures = configuration.getDeliveryOptions().maxConsecutiveFailures();
            for (int round = 1; round <= maxConsecutiveFailures; round++) {
                poisonEntered.set(new CountDownLatch(1));
                releasePoison.set(new CountDownLatch(1));
                if (round == 1) {
                    logDelivery.send(Map.of("poison", true));
                } else {
                    logDelivery.flush(); // re-dispatches the poison document requeued after the previous round
                }
                assertThat(poisonEntered.get().await(2, SECONDS))
                        .as("Round " + round + ": poison should be in flight")
                        .isTrue();

                logDelivery.send(Map.of("clean", round));
                awaitAtLeast(cleanSucceeded, round);

                releasePoison.get().countDown();
                logDelivery.awaitPendingTransmissions();
            }

            // then: despite as many unrelated concurrent successes as the failure limit itself, the poison
            // document was still given up on based purely on its own failure count
            assertThat(poisonAttempts.get()).isEqualTo(maxConsecutiveFailures);
            assertThat(cleanSucceeded.get()).isEqualTo(maxConsecutiveFailures);
            assertThat(listener.dropped).hasSize(1);
            assertThat(listener.dropped.get(0).documents()).containsExactly(Map.of("poison", true));
            assertThat(listener.dropped.get(0).reason()).contains("consecutive");
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

    }

    @Nested
    @DisplayName("Request size chunking")
    class RequestSizeChunking {

        @Test
        @DisplayName("Sends the whole batch as a single request by default (no chunking)")
        void testDoesNotChunkByDefault() throws Exception {
            // given: MaxRequestDocuments left at its default (unbounded)
            var configuration = TestLogConfiguration.builder().flushThreshold(20).build();
            var logDelivery = new TestLogDelivery(configuration);

            // when
            for (int i = 0; i < 20; i++) {
                logDelivery.send(Map.of("id", i));
            }
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);
            assertThat(logDelivery.lastBulkSize.get()).isEqualTo(20);
        }

        @Test
        @DisplayName("Splits a batch larger than MaxRequestDocuments into several requests, none exceeding it")
        void testSplitsBatchLargerThanMaxRequestDocuments() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .maxRequestDocuments(4).build();
            var logDelivery = new TestLogDelivery(configuration);
            List<Map<String, Object>> documents = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                documents.add(Map.of("id", i));
            }

            // when
            logDelivery.send(documents);
            logDelivery.awaitPendingTransmissions();

            // then: split into 3 requests (4 + 4 + 2), none exceeding the configured limit
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(3);
            for (List<Map<String, Object>> chunk : logDelivery.allBulkDocuments) {
                assertThat(chunk.size())
                        .as("No chunk should exceed MaxRequestDocuments, was " + chunk.size())
                        .isLessThanOrEqualTo(4);
            }

            // then: every document was sent exactly once across the chunks (order between chunks is not
            // guaranteed, since they may transmit concurrently, so ids are compared as a set once sorted)
            List<Integer> sentIds = logDelivery.allBulkDocuments.stream()
                    .flatMap(List::stream)
                    .map(document -> (Integer) document.get("id"))
                    .sorted()
                    .toList();
            assertThat(sentIds).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        }

        @Test
        @DisplayName("Transmits chunks of the same batch concurrently rather than sequentially")
        void testChunksTransmitConcurrently() throws Exception {
            // given: a batch of 4 split into 4 chunks of 1, each transmission taking 200ms
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(4)
                    .maxRequestDocuments(1).build();
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(200));

            // when
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of("id", i));
            }
            logDelivery.awaitPendingTransmissions();

            // then: all four in flight at once, where sequential chunking would have one at a time
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(4);
            assertThat(logDelivery.peakInFlight.get()).as("Chunks transmitted at the same time at most").isEqualTo(4);
        }

        @Test
        @DisplayName("Retries a failed chunk independently, without affecting or duplicating a sibling that succeeded")
        void testFailedChunkIsRetriedIndependentlyOfSiblings() throws Exception {
            // given: a batch of 4 split into two chunks of 2; only the chunk containing id=0 fails
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(4)
                    .maxRequestDocuments(2).build();
            var attempts = new CopyOnWriteArrayList<List<Map<String, Object>>>();
            var logDelivery = new TestLogDelivery(configuration) {
                @Override
                protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                    attempts.add(documents);
                    boolean poison = documents.stream().anyMatch(document -> document.get("id").equals(0));
                    return completedFuture(poison ? TransmissionResult.failure(null) : TransmissionResult.success());
                }
            };

            // when
            logDelivery.send(List.of(Map.of("id", 0), Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));
            logDelivery.awaitPendingTransmissions();

            // then: both chunks were attempted, exactly one of them failing
            assertThat(attempts).hasSize(2);
            List<Map<String, Object>> failedChunk = attempts.stream()
                    .filter(chunk -> chunk.stream().anyMatch(document -> document.get("id").equals(0)))
                    .findFirst()
                    .orElseThrow();

            // when the pending batch (still holding only the requeued failed chunk) is retried
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then only the previously failed chunk was retried, with its exact original content, not the
            // sibling that had already succeeded
            assertThat(attempts).hasSize(3);
            assertThat(attempts.get(2)).isEqualTo(failedChunk);
        }

        @Test
        @DisplayName("Retries only the rejected documents of a PartialFailure, reporting the accepted ones as succeeded")
        void testPartialFailureRetriesOnlyRejectedDocuments() throws Exception {
            // given: a single chunk of 3 documents where id=1 is rejected but id=0 and id=2 are accepted
            var configuration = TestLogConfiguration.builder().flushThreshold(3).build();
            var listener = new RecordingListener();
            var attempts = new CopyOnWriteArrayList<List<Map<String, Object>>>();
            var logDelivery = new TestLogDelivery(configuration, listener) {
                @Override
                protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                    attempts.add(documents);
                    List<Map<String, Object>> rejected = documents.stream()
                            .filter(document -> document.get("id").equals(1))
                            .toList();
                    return completedFuture(rejected.isEmpty()
                            ? TransmissionResult.success()
                            : TransmissionResult.partialFailure(rejected, null));
                }
            };

            // when
            logDelivery.send(List.of(Map.of("id", 0), Map.of("id", 1), Map.of("id", 2)));
            logDelivery.awaitPendingTransmissions();

            // then: the 2 accepted documents were reported succeeded and the 1 rejected one failed,
            // through two separate listener notifications rather than one all-or-nothing outcome
            assertThat(listener.succeeded).hasSize(1);
            assertThat(listener.succeeded.get(0).documentCount()).isEqualTo(2);
            assertThat(listener.failed).hasSize(1);
            assertThat(listener.failed.get(0).documentCount()).isEqualTo(1);

            // when retried
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then: only the rejected document (id=1) was retried, not the two already-accepted ones
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(1)).containsExactly(Map.of("id", 1));
        }

    }

    @Nested
    @DisplayName("Observability")
    class Observability {

        @Test
        @DisplayName("Notifies the listener when a transmission succeeds")
        void testListenerNotifiedOnSuccess() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(2).build();
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);

            // when
            logDelivery.send(Map.of());
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(listener.succeeded).hasSize(1);
            assertThat(listener.succeeded.get(0).documentCount()).isEqualTo(2);
            assertThat(listener.failed).isEmpty();
        }

        @Test
        @DisplayName("Notifies the listener installed after construction rather than the one it replaced")
        void testListenerReplacedAfterConstructionIsUsed() throws Exception {
            // given: a delivery already wired to one listener, as the constructor takes
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var initial = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, initial);

            // when: swapped for another one, the second way of wiring a listener this class documents. The
            // transmission below reads it from a virtual thread with nothing linking it to this one, which
            // is why the field it lives in has to be volatile
            var replacement = new RecordingListener();
            logDelivery.setListener(replacement);
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(logDelivery.getListener()).isSameAs(replacement);
            assertThat(replacement.succeeded).hasSize(1);
            assertThat(initial.succeeded).isEmpty();
        }

        @Test
        @DisplayName("Notifies the listener with no cause when sendBulk() reports a failure without one")
        void testListenerNotifiedOnFailureWithoutCause() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;

            // when
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(listener.failed).hasSize(1);
            assertThat(listener.failed.get(0).documentCount()).isEqualTo(1);
            assertThat(listener.failed.get(0).cause()).isNull();
        }

        @Test
        @DisplayName("Notifies the listener with the cause when sendBulk() throws")
        void testListenerNotifiedOnFailureWithCause() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var listener = new RecordingListener();
            var logDelivery = new TestLogDelivery(configuration, listener);
            var exception = new RuntimeException("Simulated failure");
            logDelivery.failureToThrow = exception;

            // when
            logDelivery.send(Map.of());
            logDelivery.awaitPendingTransmissions();

            // then
            assertThat(listener.failed).hasSize(1);
            assertThat(listener.failed.get(0).cause()).isSameAs(exception);
        }

        @Test
        @DisplayName("Still requeues failed documents for retry even when onTransmissionFailed() throws")
        void testFailedDocumentsAreRequeuedDespiteThrowingListener() throws Exception {
            // given: a listener whose onTransmissionFailed() implementation is broken; without a guard
            // around this notification, the exception it throws would skip onTransmissionFailure()
            // entirely, losing the failed document without ever requeuing or reporting it as dropped
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
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
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(1);

            // when the backend recovers and the batch is retried
            logDelivery.bulkResult = true;
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then the document was not lost: it was requeued and eventually delivered
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(2);
            assertThat(logDelivery.lastBulkDocuments).containsExactly(Map.of("id", 1));
        }

        @Test
        @DisplayName("Keeps transmitting further batches even when onTransmissionSucceeded() throws")
        void testFurtherTransmissionsContinueDespiteThrowingListenerOnSuccess() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
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
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(2);
        }

    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("Does not lose or duplicate documents when sent concurrently by several threads")
        void testConcurrentSendsDoNotLoseOrDuplicateDocuments() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder().flushThreshold(1_000_000).build(); // Never reached, everything is flushed manually below
            var logDelivery = new TestLogDelivery(configuration);
            var threads = 8;
            var messagesPerThread = 500;
            var pool = Executors.newFixedThreadPool(threads);
            var ready = new CountDownLatch(threads);
            var start = new CountDownLatch(1);

            // when: each document carrying an id of its own
            for (int t = 0; t < threads; t++) {
                int firstId = t * messagesPerThread;
                pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    for (int i = 0; i < messagesPerThread; i++) {
                        logDelivery.send(Map.of("id", firstId + i));
                    }
                });
            }
            assertThat(ready.await(5, SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, SECONDS)).isTrue();
            logDelivery.flush();
            logDelivery.awaitPendingTransmissions();

            // then: every one of them sent exactly once, rather than as many documents as were sent, which a
            // lost one and a duplicated one would add up to as well
            List<Integer> sentIds = logDelivery.allBulkDocuments.stream()
                    .flatMap(List::stream)
                    .map(document -> (Integer) document.get("id"))
                    .sorted()
                    .toList();
            assertThat(sentIds).isEqualTo(IntStream.range(0, threads * messagesPerThread).boxed().toList());
        }

        @Test
        @DisplayName("Overlaps transmissions instead of delaying the accumulation of the next batch")
        void testTransmissionsOverlapInsteadOfDelayingBatchAccumulation() throws Exception {
            // given: every message is sent individually (threshold of 1), each transmission taking 200ms
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(200));

            // when sending 4 messages (the default concurrency limit), all transmissions should overlap
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of());
            }
            logDelivery.awaitPendingTransmissions();

            // then: all four in flight at once, where waiting for each before the next would have one at a time
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(4);
            assertThat(logDelivery.peakInFlight.get()).as("Transmissions in flight at the same time at most").isEqualTo(4);
        }

        @Test
        @DisplayName("Bounds the number of transmissions running at the same time")
        void testTransmissionConcurrencyIsBounded() throws Exception {
            // given: twice the default concurrency limit (4) of messages, each transmission taking 150ms
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(150));

            // when
            for (int i = 0; i < 8; i++) {
                logDelivery.send(Map.of());
            }
            logDelivery.awaitPendingTransmissions();

            // then: never more than the limit in flight at once, and that many while there was enough to send
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(8);
            assertThat(logDelivery.peakInFlight.get()).as("Transmissions in flight at the same time at most").isEqualTo(4);
        }

        @Test
        @DisplayName("Can be lowered through the configuration")
        void testConcurrencyLimitIsConfigurable() throws Exception {
            // given: half the default concurrency limit, each transmission taking 150ms
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .maxConcurrentTransmissions(2).build();
            var logDelivery = new SlowTestLogDelivery(configuration, Duration.ofMillis(150));

            // when sending 4 messages, twice the configured limit
            for (int i = 0; i < 4; i++) {
                logDelivery.send(Map.of());
            }
            logDelivery.awaitPendingTransmissions();

            // then: the configured limit, rather than the default of 4, in flight at once at most
            assertThat(logDelivery.countBulkSent.get()).isEqualTo(4);
            assertThat(logDelivery.peakInFlight.get()).as("Transmissions in flight at the same time at most").isEqualTo(2);
        }

        @Test
        @DisplayName("Does not block accumulation of the next batch while notifying a slow listener of dropped documents")
        void testSlowListenerDoesNotBlockBatchAccumulation() throws Exception {
            // given: a batch of one document at most, a first document failing, which holds the next ones off
            // until a flush, and a listener that does not return from a drop until released
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .maxPendingDocuments(1).build();
            var listenerThread = new CompletableFuture<Thread>();
            var releaseListener = new CountDownLatch(1);
            var listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    listenerThread.complete(Thread.currentThread());
                    await(releaseListener);
                }
            };
            var logDelivery = new TestLogDelivery(configuration, listener);
            logDelivery.bulkResult = false;
            logDelivery.send(Map.of("id", 1));
            logDelivery.awaitPendingTransmissions();

            try {
                // when: a second document pushes the first one out of the batch as it is accumulated, from a
                // thread of its own, which a listener notified on the sending thread would hold for as long as it
                // takes
                var sender = Thread.ofPlatform().daemon().start(() -> logDelivery.send(Map.of("id", 2)));
                sender.join(SECONDS.toMillis(5));

                // then: the sender is back while the listener is still busy with the drop, on another thread
                assertThat(listenerThread.get(5, SECONDS)).isNotSameAs(sender);
                assertThat(sender.isAlive()).as("send() should not wait for the listener").isFalse();
            } finally {
                releaseListener.countDown();
            }
        }

        @Test
        @DisplayName("Does not block the transmitting thread when sendBulk() completes its future asynchronously")
        void testDoesNotBlockOnAsynchronousSendBulk() throws Exception {
            // given: a sendBulk() whose future is completed later, from a separate thread, rather than
            // before sendBulk() itself returns, proving the virtual thread handling the transmission is
            // freed immediately instead of blocking on the network the way a boolean-returning sendBulk()
            // would have forced it to
            var configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var releaseTransmission = new CompletableFuture<Void>();
            var transmitted = new AtomicBoolean();
            var logDelivery = new TestLogDelivery(configuration) {
                @Override
                protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                    var future = new CompletableFuture<TransmissionResult<Map<String, Object>>>();
                    releaseTransmission.thenRun(() -> {
                        transmitted.set(true);
                        future.complete(TransmissionResult.success());
                    });
                    return future;
                }
            };

            // when
            var start = System.nanoTime();
            logDelivery.send(Map.of("id", 1));
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: send() returned promptly without waiting for the still-incomplete sendBulk() future
            assertThat(elapsedMillis)
                    .as("send() should not block on an incomplete sendBulk() future, took " + elapsedMillis + "ms")
                    .isLessThan(500);
            assertThat(transmitted.get()).as("The transport should not have completed yet").isFalse();

            // cleanup
            releaseTransmission.complete(null);
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

    /**
     * A configuration that can be made to stop resolving its delivery options, standing in for one built
     * from something external — which {@link com.chavaillaz.appender.LogConfiguration} invites by
     * documenting that they are re-read rather than captured once.
     */
    static class FlakyCloseTimeoutConfiguration implements LogConfiguration {

        final AtomicBoolean broken = new AtomicBoolean();

        private final DeliveryOptions options = DeliveryOptions.builder()
                .flushThreshold(10)
                .maxConsecutiveFailures(Integer.MAX_VALUE)
                .retryBackoff(Duration.ZERO)
                .maxRetryBackoff(Duration.ZERO)
                .closeTimeout(Duration.ofSeconds(10))
                .build();

        @Override
        public String getApplication() {
            return "test-application";
        }

        @Override
        public String getHost() {
            return "test-host";
        }

        @Override
        public String getEnvironment() {
            return "test-environment";
        }

        @Override
        public DeliveryOptions getDeliveryOptions() {
            if (broken.get()) {
                throw new IllegalStateException("Simulated configuration failure");
            }
            return options;
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
        protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
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
            return completedFuture(bulkResult ? TransmissionResult.success() : TransmissionResult.failure(null));
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

    /**
     * A delivery reporting a fixed size per document, so tests can reason about the byte limits in round
     * numbers instead of depending on what {@link com.chavaillaz.appender.DocumentSizeEstimator} happens to
     * compute for a given map. Also counts how often it was asked, so a test can assert that a delivery with
     * no size limit configured is never asked at all.
     */
    static class MeasuredTestLogDelivery extends TestLogDelivery {

        final AtomicInteger timesMeasured = new AtomicInteger();
        private final long documentSize;

        protected MeasuredTestLogDelivery(TestLogConfiguration configuration, long documentSize) {
            super(configuration);
            this.documentSize = documentSize;
        }

        protected MeasuredTestLogDelivery(TestLogConfiguration configuration, long documentSize, LogDeliveryListener listener) {
            super(configuration, listener);
            this.documentSize = documentSize;
        }

        @Override
        protected long estimateDocumentSize(Map<String, Object> document) {
            timesMeasured.incrementAndGet();
            return documentSize;
        }

    }

    /**
     * A delivery whose transmissions never complete, standing in for a downstream system that accepts a
     * connection and then never answers (and a client with no read timeout of its own to notice).
     */
    static class StuckTestLogDelivery extends AbstractBatchLogDelivery<TestLogConfiguration> {

        protected StuckTestLogDelivery(TestLogConfiguration configuration, LogDeliveryListener listener) {
            super(configuration, listener);
        }

        @Override
        protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
            return new CompletableFuture<>();
        }

    }

    /**
     * A delivery whose transmissions each take the given time, recording how many of them ran at the same time
     * at most, so that a test asserts the concurrency itself rather than inferring it from how long they took.
     */
    static class SlowTestLogDelivery extends AbstractBatchLogDelivery<TestLogConfiguration> {

        AtomicInteger countBulkSent = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        Duration delay;

        protected SlowTestLogDelivery(TestLogConfiguration configuration, Duration delay) {
            super(configuration);
            this.delay = delay;
        }

        @Override
        protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            countBulkSent.incrementAndGet();
            return completedFuture(TransmissionResult.success());
        }

    }

}
