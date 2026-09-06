package com.chavaillaz.appender.log4j;

import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.ErrorHandler;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;

@DisplayName("AbstractLogDeliveryAppender")
class AbstractLogDeliveryAppenderTest {

    private LogEvent mockLogEvent() {
        var logEvent = mock(LogEvent.class);
        when(logEvent.toImmutable()).thenReturn(logEvent);
        return logEvent;
    }

    private LogEvent mockLogEvent(Level level) {
        var logEvent = mock(LogEvent.class);
        when(logEvent.toImmutable()).thenReturn(logEvent);
        when(logEvent.getLevel()).thenReturn(level);
        return logEvent;
    }

    private LogEvent realLogEvent(String message) {
        return realLogEvent(message, Level.INFO);
    }

    private LogEvent realLogEvent(String message, Level level) {
        return Log4jLogEvent.newBuilder()
                .setMessage(new SimpleMessage(message))
                .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                .setLevel(level)
                .build();
    }

    private ErrorHandler recordingErrorHandler(List<Throwable> errors) {
        return new ErrorHandler() {
            @Override
            public void error(String msg) {
                errors.add(new RuntimeException(msg));
            }

            @Override
            public void error(String msg, Throwable t) {
                errors.add(t != null ? t : new RuntimeException(msg));
            }

            @Override
            public void error(String msg, LogEvent event, Throwable t) {
                errors.add(t != null ? t : new RuntimeException(msg));
            }
        };
    }

    @Nested
    @DisplayName("Periodic flush scheduling")
    class PeriodicFlushScheduling {

        @Test
        @DisplayName("Schedules a periodic flush and waits for it when the flush threshold allows batching")
        void testWithThreadPool_WaitFlush() throws Exception {
            // given
            var logEvent = mockLogEvent();
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(200)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // when
            appender.start();
            appender.append(logEvent);
            sleep(300);

            // then
            assertEquals(1, appender.getCountTaskExecuted().get());
            verify(appender.getLogDeliveryHandler()).flush();

            // when
            appender.stop();

            // then
            verify(appender.getLogDeliveryHandler()).close();
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

        @Test
        @DisplayName("Does not flush before the configured interval elapses")
        void testWithThreadPool_DoNotWaitFlush() throws Exception {
            // given
            var logEvent = mockLogEvent();
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(1_000)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // when
            appender.start();
            appender.append(logEvent);
            sleep(100); // gives the background thread time to process the event before asserting on it

            // then: the event itself was processed, but well below the flush threshold and before the
            // flush interval elapsed, no flush was triggered
            assertEquals(1, appender.getCountTaskExecuted().get());
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());

            // when
            appender.stop();

            // then
            verify(appender.getLogDeliveryHandler()).close();
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

        @Test
        @DisplayName("Reports an error and does not schedule a periodic flush when FlushInterval is zero")
        void testWithThreadPool_ZeroFlushInterval() throws Exception {
            // given: ScheduledExecutorService#scheduleWithFixedDelay requires a strictly positive delay
            // and would otherwise throw IllegalArgumentException straight out of start()
            var logEvent = mockLogEvent();
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ZERO).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when / then: starting must not throw despite the invalid configuration
            assertDoesNotThrow(appender::start);
            assertFalse(errors.isEmpty(), "The invalid FlushInterval should have been reported");

            // when
            appender.append(logEvent);
            sleep(100);

            // then: without a scheduler, only the immediate append is processed, never a periodic flush
            assertEquals(1, appender.getCountTaskExecuted().get());
            verify(appender.getLogDeliveryHandler(), never()).flush();

            // cleanup
            appender.stop();
        }

        @Test
        @DisplayName("Reports an error and does not schedule a periodic flush when FlushInterval is negative")
        void testWithThreadPool_NegativeFlushInterval() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(-100)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when / then
            assertDoesNotThrow(appender::start);
            assertFalse(errors.isEmpty(), "The invalid FlushInterval should have been reported");

            // cleanup
            appender.stop();
        }

        @Test
        @DisplayName("Reports an error and does not schedule a periodic flush when FlushInterval overflows Duration#toMillis()")
        void testWithThreadPool_OverflowingFlushInterval() throws Exception {
            // given: a Duration so large that Duration#toMillis() itself throws ArithmeticException,
            // which would otherwise propagate straight out of start() instead of being reported cleanly
            // like every other invalid FlushInterval
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(Long.MAX_VALUE / 500)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when / then
            assertDoesNotThrow(appender::start);
            assertFalse(errors.isEmpty(), "The overflowing FlushInterval should have been reported");
            verify(appender.getLogDeliveryHandler(), never()).flush();

            // cleanup
            appender.stop();
        }

        @Test
        @DisplayName("Does not schedule a periodic flush when the flush threshold disables batching")
        void testWithoutThreadPool() throws Exception {
            // given
            var logEvent = mockLogEvent();
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .flushInterval(Duration.ofMillis(1_000)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // when
            appender.start();
            appender.append(logEvent);
            sleep(100);

            // then
            assertEquals(1, appender.getCountTaskExecuted().get());
            verify(appender.getLogDeliveryHandler(), never()).flush();

            // when
            appender.stop();

            // then
            verify(appender.getLogDeliveryHandler()).close();
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

    }

    @Nested
    @DisplayName("Severity-triggered flush")
    class SeverityTriggeredFlush {

        @Test
        @DisplayName("Triggers an immediate flush right after an event at least as severe as FlushLevel")
        void testUrgentEventTriggersImmediateFlush() throws Exception {
            // given: a threshold high enough, and no periodic flush due for a long time, that only the
            // severity trigger itself could possibly cause a flush within the test
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10))
                    .flushLevel(Level.WARN).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // when
            appender.start();
            appender.append(mockLogEvent(Level.ERROR));

            // then: ERROR is more severe than the configured WARN threshold
            verify(appender.getLogDeliveryHandler(), timeout(1_000)).flush();

            // cleanup
            appender.stop();
        }

        @Test
        @DisplayName("Does not flush for an event less severe than FlushLevel")
        void testEventBelowFlushLevelDoesNotTriggerFlush() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10))
                    .flushLevel(Level.WARN).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // when
            appender.start();
            appender.append(mockLogEvent(Level.INFO));
            sleep(100);

            // then: INFO is less severe than the configured WARN threshold, so no immediate flush
            assertEquals(1, appender.getCountTaskExecuted().get());
            verify(appender.getLogDeliveryHandler(), never()).flush();

            // cleanup
            appender.stop();
        }

        @Test
        @DisplayName("Never flushes on severity alone while FlushLevel is left disabled")
        void testFlushLevelDisabledByDefault() throws Exception {
            // given: FlushLevel left at its default (null)
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // when
            appender.start();
            appender.append(mockLogEvent(Level.FATAL));
            sleep(100);

            // then: no FlushLevel configured, so not even a FATAL event triggers an immediate flush
            assertEquals(1, appender.getCountTaskExecuted().get());
            verify(appender.getLogDeliveryHandler(), never()).flush();

            // cleanup
            appender.stop();
        }

    }

    @Nested
    @DisplayName("Non-blocking append")
    class NonBlockingAppend {

        @Test
        @DisplayName("Returns immediately even while a delivery task is still running")
        void testAppendNeverBlocksCallingThread() throws Exception {
            // given: a delivery task that takes a while, to prove append() returns long before it completes
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration, releaseTask);

            // when
            appender.start();
            long before = System.nanoTime();
            appender.append(mockLogEvent());
            long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

            // then append() returns immediately, well before the (still blocked) task could possibly complete
            assertTrue(elapsedMillis < 200, "append() should return immediately, took " + elapsedMillis + "ms");

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

    }

    @Nested
    @DisplayName("Bounded worker queue")
    class BoundedWorkerQueue {

        @Test
        @DisplayName("Discards an event without blocking once the queue capacity is exceeded, reporting it as dropped")
        void testQueueCapacityExceededReportsDroppedEvents() throws Exception {
            // given: a queue capacity of 1 and a delivery task that blocks forever until released, so the
            // first appended event occupies the worker thread and the second fills the one-slot queue,
            // leaving no room for a third (which discards the second instead, see
            // testQueueCapacityExceededDiscardsOldestQueuedEvent() below for that distinction; every event
            // here carries identical content, so this test only cares that exactly one of the three was
            // discarded without blocking, and reported as such)
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration, releaseTask);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            var droppedReasons = new CopyOnWriteArrayList<String>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                    droppedReasons.add(reason);
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // a real (not mocked) event, since the dropped one below is actually converted by a fallback
            // converter to still produce something for the listener
            var loggingEvent = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("would have been dropped"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when
            appender.start();
            appender.append(loggingEvent); // occupies the worker thread (blocked on releaseTask)
            appender.append(loggingEvent); // fills the one-slot queue
            var start = System.nanoTime();
            appender.append(loggingEvent); // discards the previously queued one instead of blocking
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then
            assertTrue(elapsedMillis < 200, "append() should not block even when the queue is full, took " + elapsedMillis + "ms");
            assertEquals(1, dropped.size());
            assertEquals("would have been dropped", dropped.get(0).get("logmessage"));
            assertTrue(droppedReasons.get(0).contains("queue"), "Reason should mention the queue: " + droppedReasons.get(0));
            assertFalse(errors.isEmpty(), "The discard should also have been reported through the error handler");

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Discards the oldest queued event to make room for the newest instead of rejecting it")
        void testQueueCapacityExceededDiscardsOldestQueuedEvent() throws Exception {
            // given: a queue capacity of 1 and a delivery task that only blocks on its very first
            // invocation until released, so event A occupies the worker thread, event B fills the
            // one-slot queue, and event C then evicts B (the oldest still-queued event) to take its place
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseFirstTask = new CountDownLatch(1);
            var appender = new RecordingBlockingLogDeliveryAppender(
                    "appender-name", null, createDefaultLayout(), configuration, releaseFirstTask);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            var droppedReasons = new CopyOnWriteArrayList<String>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                    droppedReasons.add(reason);
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);

            // when
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            appender.append(realLogEvent("B")); // fills the one-slot queue
            appender.append(realLogEvent("C")); // evicts B, the oldest queued event, taking its place
            releaseFirstTask.countDown();
            appender.stop(2, SECONDS); // waits for every accepted task (A, then C) to complete

            // then: B was discarded and never processed, while A and C both were, in that order
            assertEquals(List.of("A", "C"), appender.getProcessedMessages());
            assertEquals(1, dropped.size());
            assertEquals("B", dropped.get(0).get("logmessage"));
            assertTrue(droppedReasons.get(0).contains("queue"), "Reason should mention the queue: " + droppedReasons.get(0));
        }

        @Test
        @DisplayName("Does not crash construction when MaxQueuedEvents is zero or negative")
        void testNonPositiveMaxQueuedEventsDoesNotThrow() throws Exception {
            // given: the worker executor (and its bounded queue) is created at construction time
            var configuration = TestLogConfiguration.builder().maxQueuedEvents(-1).build();

            // when / then
            var appender = assertDoesNotThrow(
                    () -> new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration));
            assertDoesNotThrow(appender::start);

            // cleanup
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Warns when MaxQueuedEvents is zero or negative before clamping it up to 1")
        void testNonPositiveMaxQueuedEventsWarns() throws Exception {
            // given: the worker executor (and its bounded queue) is created at construction time, before
            // this appender instance exists to call setHandler() on (see recordingErrorHandler() elsewhere
            // in this class), so the warning is only observable through Log4j2's own StatusLogger, the same
            // sink the default ErrorHandler reports to
            var configuration = TestLogConfiguration.builder().maxQueuedEvents(0).build();
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

            // when
            LogDeliveryAppender appender;
            try {
                appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            } finally {
                StatusLogger.getLogger().removeListener(statusListener);
            }

            // then
            assertTrue(statusMessages.stream().anyMatch(message -> message.contains("MaxQueuedEvents")),
                    "Expected a status message mentioning MaxQueuedEvents, got: " + statusMessages);

            // cleanup
            assertDoesNotThrow(appender::start);
            appender.stop(2, SECONDS);
        }

    }

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("Waits for pending tasks to complete before closing the delivery handler")
        void testStopWaitsForPendingTasksBeforeClosingHandler() throws Exception {
            // given: several slow delivery tasks queued up right before stop() is called
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var events = new CopyOnWriteArrayList<String>();
            var appender = new RecordingLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration, events, 50);

            // when
            appender.start();
            for (int i = 0; i < 5; i++) {
                appender.append(mockLogEvent());
            }
            appender.stop(2, SECONDS);

            // then all pending tasks must have completed (and been recorded) before the handler was closed,
            // otherwise logs accepted right before shutdown could be silently dropped
            assertEquals(5, events.stream().filter("send"::equals).count());
            assertEquals("close", events.get(events.size() - 1));
        }

        @Test
        @DisplayName("Rejects further appends without throwing back to the calling thread")
        void testAppendAfterStopDoesNotThrowToCallingThread() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            appender.start();
            appender.stop(1, SECONDS);

            // when / then: logging after the appender stopped must never propagate to the application thread
            assertDoesNotThrow(() -> appender.append(mockLogEvent()));
            assertFalse(errors.isEmpty(), "The rejection should still be reported through the error handler");
        }

        @Test
        @DisplayName("Attempts to interrupt a still-running task once the shutdown timeout elapses")
        void testStopInterruptsStuckTaskAfterTimeout() throws Exception {
            // given: a delivery task that blocks forever unless interrupted, and a stop() timeout short
            // enough to elapse while it is still running
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var interrupted = new CountDownLatch(1);
            var appender = new StuckLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration, interrupted);

            // when
            appender.start();
            appender.append(mockLogEvent());
            appender.stop(200, MILLISECONDS);

            // then: the stuck task was interrupted instead of being left running indefinitely after the
            // (already closed by then) delivery handler
            assertTrue(interrupted.await(2, SECONDS), "The stuck task should have been interrupted after the timeout");
        }

        @Test
        @DisplayName("Reports events still queued behind a stuck task as dropped instead of losing them silently")
        void testStopReportsQueuedEventsDroppedAfterTimeout() throws Exception {
            // given: a delivery task that blocks forever unless interrupted (occupying the single worker
            // thread), and two more events queued behind it that never even get a chance to run before the
            // shutdown timeout elapses and shutdownNow() discards them from the queue
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var interrupted = new CountDownLatch(1);
            var appender = new StuckLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration, interrupted);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            var droppedReasons = new CopyOnWriteArrayList<String>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                    droppedReasons.add(reason);
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);

            // when
            appender.start();
            appender.append(mockLogEvent()); // occupies the worker thread forever
            appender.append(realLogEvent("queued-1")); // never gets to run
            appender.append(realLogEvent("queued-2")); // never gets to run
            appender.stop(200, MILLISECONDS);

            // then: both queued (never started) events were reported dropped, instead of vanishing without
            // a trace once shutdownNow() discarded them from the queue
            assertTrue(interrupted.await(2, SECONDS), "The stuck task should have been interrupted after the timeout");
            assertEquals(2, dropped.size());
            assertEquals(List.of("queued-1", "queued-2"), dropped.stream().map(d -> d.get("logmessage")).toList());
            assertTrue(droppedReasons.stream().allMatch(reason -> reason.contains("stop")),
                    "Reason should mention the appender stopping: " + droppedReasons);

            // and: handed over as one notification rather than one per event. A listener saving them usually
            // pays per call (opening a file, a round trip to a queue), and a full queue's worth can be
            // abandoned here -- ten thousand by default, on the path where a delay is least affordable
            assertEquals(1, droppedReasons.size(),
                    "Every abandoned event should be reported in a single call, but got " + droppedReasons.size());
        }

    }

    @Nested
    @DisplayName("Handler creation failure")
    class HandlerCreationFailure {

        @Test
        @DisplayName("Reports the failure once and skips processing further events instead of failing on every one")
        void testAppendIsNoOpAfterHandlerCreationFailed() throws Exception {
            // given: createLogDeliveryHandler() always fails, so getLogDeliveryHandler() stays null
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new FailStartLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            appender.start();
            assertEquals(1, errors.size(), "The startup failure should have been reported exactly once");
            for (int i = 0; i < 5; i++) {
                appender.append(mockLogEvent());
            }
            appender.stop(2, SECONDS);

            // then: append() never even attempted to create a delivery task for a handler that never
            // started, instead of paying for a snapshot, a background thread hand-off and a doomed task
            // on every single one of the 5 events above
            assertEquals(0, appender.getCountTaskCreated().get());
            assertEquals(1, errors.size(), "No additional error should have been reported for the skipped events");
        }

    }

    @Nested
    @DisplayName("Health and backpressure signal")
    class HealthAndBackpressure {

        @Test
        @DisplayName("isHealthy() is true before the handler is created, then delegates to it")
        void testIsHealthyDelegatesToHandler() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // then: nothing created yet, nothing to be unhealthy about
            assertTrue(appender.isHealthy());

            // when
            appender.start();
            when(appender.getLogDeliveryHandler().isHealthy()).thenReturn(false);

            // then
            assertFalse(appender.isHealthy());

            // cleanup
            appender.stop();
        }

        @Test
        @DisplayName("isBackpressured() reflects whether the internal queue is currently full")
        void testIsBackpressuredReflectsQueueFullness() throws Exception {
            // given: a queue capacity of 1 and a delivery task that blocks forever until released, so the
            // first appended event occupies the worker thread and the second fills the one-slot queue
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration, releaseTask);

            // when
            appender.start();

            // then: nothing queued yet
            assertFalse(appender.isBackpressured());

            // when: A occupies the worker thread (blocked), B fills the one-slot queue
            appender.append(mockLogEvent());
            appender.append(mockLogEvent());

            // then
            assertTrue(appender.isBackpressured());

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("getQueuedEventCount() reports how full the internal queue is before it overflows")
        void testQueuedEventCountReflectsQueueDepth() throws Exception {
            // given: a queue with room to spare, so the count can be observed growing rather than only
            // reaching the all-or-nothing state isBackpressured() reports
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(10).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration, releaseTask);

            // when
            appender.start();

            // then: nothing queued yet
            assertEquals(0, appender.getQueuedEventCount());

            // when: the first event occupies the (blocked) worker thread, the next two sit in the queue
            appender.append(mockLogEvent());
            appender.append(mockLogEvent());
            appender.append(mockLogEvent());

            // then: a gauge worth alerting on well before documents actually start being discarded
            assertEquals(2, appender.getQueuedEventCount());
            assertFalse(appender.isBackpressured());

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Reports a failing task and keeps processing the next ones")
        void testTaskExceptionIsReportedAndDoesNotStopFurtherProcessing() throws Exception {
            // given: the first task fails, the second one must still be processed by the same background thread
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new FailFirstLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            appender.start();
            appender.append(mockLogEvent());
            appender.append(mockLogEvent());
            appender.stop(2, SECONDS);

            // then
            assertEquals(1, appender.getCountTaskExecuted().get());
            assertFalse(errors.isEmpty(), "The exception thrown by the first task should have been reported");
        }

        @Test
        @DisplayName("Reports an exception thrown while creating a task without propagating it to the caller")
        void testTaskCreationExceptionIsReportedAndDoesNotPropagateToCaller() throws Exception {
            // given: createLogDeliveryTask() itself throws on the calling (application) thread, simulating
            // a subclass mistake; this must never break the guarantee that append() never fails the caller
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new FailCreationLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            var droppedReasons = new CopyOnWriteArrayList<String>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                    droppedReasons.add(reason);
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);

            // a real (not mocked) event, since the dropped one below is actually converted by a fallback
            // converter to still produce something for the listener
            var loggingEvent = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("would have been dropped"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when / then
            appender.start();
            assertDoesNotThrow(() -> appender.append(loggingEvent));
            appender.append(mockLogEvent());
            appender.stop(2, SECONDS);

            // then the second (non-throwing) task creation was still processed by the background thread
            assertEquals(1, appender.getCountTaskExecuted().get());
            assertFalse(errors.isEmpty(), "The exception thrown while creating the task should have been reported");

            // then the event lost to the failed task creation was also reported through the listener,
            // exactly as any other event discarded before ever reaching the delivery would be, instead of
            // only ever reaching the ErrorHandler
            assertEquals(1, dropped.size());
            assertEquals("would have been dropped", dropped.get(0).get("logmessage"));
            assertTrue(droppedReasons.get(0).contains("creation"), "Reason should mention task creation: " + droppedReasons.get(0));
        }

    }

    @Getter
    static class LogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        AtomicInteger countTaskExecuted = new AtomicInteger();

        protected LogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration) {
            super(name, filter, layout, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> countTaskExecuted.incrementAndGet();
        }

    }

    @Getter
    static class StuckLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        CountDownLatch interrupted;

        protected StuckLogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration,
                                            CountDownLatch interrupted) {
            super(name, filter, layout, configuration);
            this.interrupted = interrupted;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
                try {
                    new CountDownLatch(1).await(); // Blocks forever unless interrupted
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            };
        }

    }

    @Getter
    static class BlockingLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        CountDownLatch releaseTask;

        protected BlockingLogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration,
                                               CountDownLatch releaseTask) {
            super(name, filter, layout, configuration);
            this.releaseTask = releaseTask;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
                try {
                    releaseTask.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
        }

    }

    @Getter
    static class RecordingBlockingLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        CountDownLatch releaseFirstTask;
        List<String> processedMessages = new CopyOnWriteArrayList<>();

        protected RecordingBlockingLogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration,
                                                         CountDownLatch releaseFirstTask) {
            super(name, filter, layout, configuration);
            this.releaseFirstTask = releaseFirstTask;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            // The message is read here, on the calling (application) thread, matching how a real
            // implementation would capture whatever it needs from the event before deferring to the
            // returned Runnable (see this method's own javadoc)
            String message = loggingEvent.getMessage().getFormattedMessage();
            return () -> {
                try {
                    // Only ever blocks the very first task: releaseFirstTask.await() returns immediately
                    // once the latch has already reached zero, so every task submitted afterward proceeds
                    // without waiting
                    releaseFirstTask.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processedMessages.add(message);
            };
        }

    }

    @Getter
    static class RecordingLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        List<String> events;
        long taskDelayMillis;

        protected RecordingLogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration,
                                                List<String> events, long taskDelayMillis) {
            super(name, filter, layout, configuration);
            this.events = events;
            this.taskDelayMillis = taskDelayMillis;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return new LogDelivery() {
                @Override
                public void send(Map<String, Object> document) {
                    // Not used in this test
                }

                @Override
                public void send(List<Map<String, Object>> documents) {
                    // Not used in this test
                }

                @Override
                public void flush() {
                    // Not used in this test
                }

                @Override
                public void close() {
                    events.add("close");
                }
            };
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
                try {
                    sleep(taskDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                events.add("send");
            };
        }

    }

    @Getter
    static class FailStartLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        AtomicInteger countTaskCreated = new AtomicInteger();

        protected FailStartLogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration) {
            super(name, filter, layout, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            throw new RuntimeException("Simulated startup failure");
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            countTaskCreated.incrementAndGet();
            return () -> {
            };
        }

    }

    @Getter
    static class FailFirstLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        AtomicBoolean firstTask = new AtomicBoolean(true);
        AtomicInteger countTaskExecuted = new AtomicInteger();

        protected FailFirstLogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration) {
            super(name, filter, layout, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
                if (firstTask.compareAndSet(true, false)) {
                    throw new RuntimeException("Simulated failure");
                }
                countTaskExecuted.incrementAndGet();
            };
        }

    }

    @Getter
    static class FailCreationLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        AtomicInteger countTaskExecuted = new AtomicInteger();

        protected FailCreationLogDeliveryAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration) {
            super(name, filter, layout, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            if (firstCall.compareAndSet(true, false)) {
                throw new RuntimeException("Simulated failure creating the task");
            }
            return countTaskExecuted::incrementAndGet;
        }

    }

}
