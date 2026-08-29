package com.chavaillaz.appender.log4j;

import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.apache.logging.log4j.core.ErrorHandler;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.LogDelivery;

@DisplayName("AbstractLogDeliveryAppender")
class AbstractLogDeliveryAppenderTest {

    private LogEvent mockLogEvent() {
        var logEvent = mock(LogEvent.class);
        when(logEvent.toImmutable()).thenReturn(logEvent);
        return logEvent;
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
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            configuration.setFlushInterval(Duration.ofMillis(200));
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
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            configuration.setFlushInterval(Duration.ofMillis(1_000));
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

            // when
            appender.start();
            appender.append(logEvent);

            // then
            assertEquals(0, appender.getCountTaskExecuted().get());
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());

            // when
            appender.stop();

            // then
            verify(appender.getLogDeliveryHandler()).close();
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

        @Test
        @DisplayName("Does not schedule a periodic flush when the flush threshold disables batching")
        void testWithoutThreadPool() throws Exception {
            // given
            var logEvent = mockLogEvent();
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(1);
            configuration.setFlushInterval(Duration.ofMillis(1_000));
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
    @DisplayName("Non-blocking append")
    class NonBlockingAppend {

        @Test
        @DisplayName("Returns immediately even while a delivery task is still running")
        void testAppendNeverBlocksCallingThread() throws Exception {
            // given: a delivery task that takes a while, to prove append() returns long before it completes
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            configuration.setFlushInterval(Duration.ofSeconds(10));
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
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("Waits for pending tasks to complete before closing the delivery handler")
        void testStopWaitsForPendingTasksBeforeClosingHandler() throws Exception {
            // given: several slow delivery tasks queued up right before stop() is called
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(100);
            configuration.setFlushInterval(Duration.ofSeconds(10));
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
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            configuration.setFlushInterval(Duration.ofSeconds(10));
            var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            appender.start();
            appender.stop(1, SECONDS);

            // when / then: logging after the appender stopped must never propagate to the application thread
            assertDoesNotThrow(() -> appender.append(mockLogEvent()));
            assertFalse(errors.isEmpty(), "The rejection should still be reported through the error handler");
        }

    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Reports a failing task and keeps processing the next ones")
        void testTaskExceptionIsReportedAndDoesNotStopFurtherProcessing() throws Exception {
            // given: the first task fails, the second one must still be processed by the same background thread
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            configuration.setFlushInterval(Duration.ofSeconds(10));
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
            var configuration = new TestLogConfiguration();
            configuration.setFlushThreshold(10);
            configuration.setFlushInterval(Duration.ofSeconds(10));
            var appender = new FailCreationLogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when / then
            appender.start();
            assertDoesNotThrow(() -> appender.append(mockLogEvent()));
            appender.append(mockLogEvent());
            appender.stop(2, SECONDS);

            // then the second (non-throwing) task creation was still processed by the background thread
            assertEquals(1, appender.getCountTaskExecuted().get());
            assertFalse(errors.isEmpty(), "The exception thrown while creating the task should have been reported");
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
