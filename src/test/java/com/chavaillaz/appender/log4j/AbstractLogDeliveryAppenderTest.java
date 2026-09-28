package com.chavaillaz.appender.log4j;

import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import lombok.Getter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.AbstractLogEvent;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.ErrorHandler;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.DeliveryHealth;
import com.chavaillaz.appender.DeliveryMetrics;
import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogConfiguration;
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

    /**
     * How many periodic flush schedulers are alive right now, across every appender in this JVM. Only ever
     * compared against a reading taken moments earlier, so schedulers left behind by another test do not
     * matter.
     */
    private static int countFlushSchedulerThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> "log-delivery-flush-scheduler".equals(thread.getName()))
                .count();
    }

    /**
     * The live threads with the given name right now, across every appender in this JVM, as a mutable set so
     * a caller can subtract the ones that were already there before it acted.
     */
    private static Set<Thread> liveThreadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> name.equals(thread.getName()))
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Waits until the worker has taken every queued event, so a blocking one it runs no longer holds a queue
     * slot: the worker already runs when the appender starts, and takes each event from the queue.
     */
    private static void awaitEmptyQueue(AbstractLogDeliveryAppender<?> appender) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (appender.getQueuedEventCount() > 0) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for the worker to take the queued events");
            }
            sleep(1);
        }
    }

    /**
     * Waits until the given list holds the given number of elements at least, an event discarded on the
     * application thread being reported on another one.
     */
    private static void awaitSize(List<?> list, int expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (list.size() < expected) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for " + expected + " element(s), got " + list);
            }
            sleep(1);
        }
    }

    /**
     * The executor of the worker of an appender, which reports a full queue through the policy it was built with.
     */
    private static ThreadPoolExecutor workerExecutorOf(AbstractLogDeliveryAppender<?> appender)
            throws ReflectiveOperationException {
        Field field = AbstractLogDeliveryAppender.class.getDeclaredField("workerExecutor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(appender);
    }

    /**
     * Replaces the executor of the worker of an appender not started yet, which is then started instead.
     */
    private static void replaceWorkerExecutor(AbstractLogDeliveryAppender<?> appender, ThreadPoolExecutor executor)
            throws ReflectiveOperationException {
        Field field = AbstractLogDeliveryAppender.class.getDeclaredField("workerExecutor");
        field.setAccessible(true);
        field.set(appender, executor);
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
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

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
            verify(appender.getLogDeliveryHandler()).close(any());
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

        @Test
        @DisplayName("Builds a single delivery handler and scheduler however many times it is started")
        void testStartingTwiceDoesNotLeakTheFirstHandlerAndScheduler() throws Exception {
            // given: batching enabled, so start() also creates the periodic flush scheduler
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(200)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

            // when: started again, as nothing in Log4j2's lifecycle rules out
            appender.start();
            var handler = appender.getLogDeliveryHandler();
            int schedulersAfterFirstStart = countFlushSchedulerThreads();
            appender.start();

            // then: still the same handler, rather than a second one leaving the first with no reference
            // left to close it
            assertSame(handler, appender.getLogDeliveryHandler());
            assertTrue(appender.isStarted());

            // and: still a single scheduler. Counted rather than timed: a second one only shows up as two
            // flushes per interval, which is indistinguishable from one slow interval on a loaded machine
            assertTrue(countFlushSchedulerThreads() <= schedulersAfterFirstStart,
                    "Starting again should not have created a second flush scheduler");

            // and: that scheduler is running, waited for rather than slept through for the same reason
            appender.append(mockLogEvent());
            verify(appender.getLogDeliveryHandler(), timeout(5_000).atLeastOnce()).flush();

            // cleanup
            appender.stop();
            verify(appender.getLogDeliveryHandler()).close(any());
        }

        @Test
        @DisplayName("Finishes starting when the delivery options cannot be read at all")
        void testStartCompletesWhenTheDeliveryOptionsCannotBeRead() {
            // given: a configuration that fails to resolve, which the interface invites by documenting that
            // the options are re-read rather than captured once. start() reads them to decide whether to
            // schedule the periodic flush, after the delivery handler has already been built
            var configuration = new FlakyOptionsConfiguration();
            var appender = new FlakyOptionsLogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            configuration.broken.set(true);
            assertDoesNotThrow(appender::start);
            configuration.broken.set(false);

            // then: reported and degraded to no periodic flush, rather than handed to whatever is applying
            // the Log4j2 configuration -- which also skipped the super.start() below it, leaving an appender
            // that already had a working handler permanently unstarted
            assertFalse(errors.isEmpty(), "The failure should have been reported through the error handler");
            assertTrue(appender.isStarted(), "The appender should have finished starting");
            assertNotNull(appender.getLogDeliveryHandler());

            // cleanup
            appender.stop(0, MILLISECONDS);
        }

        @Test
        @DisplayName("Does not flush before the configured interval elapses")
        void testWithThreadPool_DoNotWaitFlush() throws Exception {
            // given
            var logEvent = mockLogEvent();
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(1_000)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

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
            verify(appender.getLogDeliveryHandler()).close(any());
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

        @Test
        @DisplayName("Keeps flushing periodically after one flush failed to be queued with an Error")
        void testPeriodicFlushSurvivesAnError() throws Exception {
            // given: a flush that cannot be queued once, as when the heap runs out on the scheduler thread.
            // Nothing but a rejection can fail it otherwise, so the executor queuing it is replaced
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(50)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            var failuresLeft = new AtomicInteger(1);
            replaceWorkerExecutor(appender, new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, new LinkedBlockingQueue<>()) {
                @Override
                public void execute(Runnable command) {
                    if (failuresLeft.getAndDecrement() > 0) {
                        throw new OutOfMemoryError("Java heap space");
                    }
                    super.execute(command);
                }
            });

            // when
            appender.start();

            // then: the next flush still ran, rather than that Error cancelling every later one for good
            verify(appender.getLogDeliveryHandler(), timeout(5_000).atLeastOnce()).flush();
            assertTrue(errors.stream().anyMatch(OutOfMemoryError.class::isInstance),
                    "The failure should have been reported through the error handler, got: " + errors);

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Runs a periodic flush the full queue had no room for right after the event in progress")
        void testPeriodicFlushIsNotLostToAFullQueue() throws Exception {
            // given: a queue of a single event, a first event holding up the worker, and a second one filling
            // the queue, so that the periodic flushes firing meanwhile find no room in it
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(100))
                    .maxQueuedEvents(1).build();
            var releaseFirstTask = new CountDownLatch(1);
            var appender = new RecordingBlockingLogDeliveryAppender("appender-name", null, configuration, releaseFirstTask);
            doAnswer(invocation -> {
                appender.getProcessedMessages().add("flush");
                return null;
            }).when(appender.getLogDeliveryHandler()).flush();
            appender.start();
            appender.append(realLogEvent("first"));
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("second"));
            sleep(350);

            // when
            releaseFirstTask.countDown();

            // then: the flush requested meanwhile ran as soon as the worker was done with the first event. It
            // used to be skipped for as long as the queue stayed full, which a steady load keeps it
            awaitSize(appender.getProcessedMessages(), 3);
            assertEquals(List.of("first", "flush", "second"), appender.getProcessedMessages().subList(0, 3));

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Does not schedule a periodic flush when the flush threshold disables batching")
        void testWithoutThreadPool() throws Exception {
            // given
            var logEvent = mockLogEvent();
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .flushInterval(Duration.ofMillis(1_000)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

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
            verify(appender.getLogDeliveryHandler()).close(any());
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

        @Test
        @DisplayName("Schedules a periodic flush when batching is disabled but a retry backoff can hold documents back")
        void testSchedulesPeriodicFlushForRetryBackoffWithoutBatching() throws Exception {
            // given: no batching, so the threshold alone would not schedule anything -- but a retry backoff
            // (enabled by default in production, unlike in this test configuration) stacks documents while
            // it holds off transmissions, and once it elapses nothing sends them until the next document
            // happens to arrive. Under low traffic the periodic flush is the only thing that ever will.
            var logEvent = mockLogEvent();
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .retryBackoff(Duration.ofSeconds(1))
                    .maxRetryBackoff(Duration.ofSeconds(1))
                    .flushInterval(Duration.ofMillis(200)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

            // when
            appender.start();
            appender.append(logEvent);
            sleep(300);

            // then
            assertEquals(1, appender.getCountTaskExecuted().get());
            verify(appender.getLogDeliveryHandler()).flush();

            // cleanup
            appender.stop();
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
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

            // when
            appender.start();
            appender.append(mockLogEvent(Level.ERROR));

            // then: ERROR is more severe than the configured WARN threshold
            verify(appender.getLogDeliveryHandler(), timeout(1_000)).flush();

            // cleanup
            appender.stop();
        }

        @Test
        @DisplayName("Flushes right after an urgent event even when the queue has no room for anything else")
        void testUrgentEventFlushesDespiteAFullQueue() throws Exception {
            // given: a queue of a single event, which an urgent one fills while a first event holds up the
            // worker, and no periodic flush due for a long time
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10))
                    .flushLevel(Level.WARN)
                    .maxQueuedEvents(1).build();
            var releaseFirstTask = new CountDownLatch(1);
            var appender = new RecordingBlockingLogDeliveryAppender("appender-name", null, configuration, releaseFirstTask);
            doAnswer(invocation -> {
                appender.getProcessedMessages().add("flush");
                return null;
            }).when(appender.getLogDeliveryHandler()).flush();
            appender.start();
            appender.append(realLogEvent("first"));
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("urgent", Level.ERROR));

            // when
            releaseFirstTask.countDown();

            // then: the flush follows the urgent event, where it used to need a slot of its own in the queue,
            // and was dropped for the lack of one
            awaitSize(appender.getProcessedMessages(), 3);
            assertEquals(List.of("first", "urgent", "flush"), appender.getProcessedMessages());

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Does not flush for an event less severe than FlushLevel")
        void testEventBelowFlushLevelDoesNotTriggerFlush() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10))
                    .flushLevel(Level.WARN).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

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
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

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
    @DisplayName("Default delivery task")
    class DefaultDeliveryTask {

        @Test
        @DisplayName("Converts each event with a DefaultLogConverter and sends the document to the delivery")
        void testDefaultTaskSendsTheConvertedEvent() throws Exception {
            // given: an appender creating its delivery, and nothing else
            var configuration = TestLogConfiguration.builder()
                    .application("my-application")
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var handler = mock(LogDelivery.class);
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                public LogDelivery createLogDeliveryHandler() {
                    return handler;
                }
            };

            // when
            appender.start();
            appender.append(realLogEvent("hello"));

            // then
            verify(handler, timeout(5_000)).send(argThat((Map<String, Object> document) ->
                    "hello".equals(document.get("logmessage")) && "my-application".equals(document.get("application"))));

            // and: without a layout, the document being what is sent
            assertNull(appender.getLayout());

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Converts each event with the converter the appender returns instead, when it overrides it")
        void testDefaultTaskUsesTheConverterOfTheAppender() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var handler = mock(LogDelivery.class);
            LogConverter converter = event -> Map.of("custom", event.getMessage().getFormattedMessage());
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                public LogDelivery createLogDeliveryHandler() {
                    return handler;
                }

                @Override
                protected LogConverter getLogConverter() {
                    return converter;
                }
            };

            // when
            appender.start();
            appender.append(realLogEvent("hello"));

            // then
            verify(handler, timeout(5_000)).send(Map.<String, Object>of("custom", "hello"));

            // cleanup
            appender.stop(1, SECONDS);
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
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);

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
    @DisplayName("Event snapshot")
    class EventSnapshot {

        @Test
        @DisplayName("Formats a message with its arguments as they were when logged, not when processed")
        void testMessageIsFormattedWithTheArgumentsItWasLoggedWith() throws Exception {
            // given: a ParameterizedMessage, which is what every logger creates once Log4j2 turns its thread
            // locals off (by default, whenever the Servlet API is on the classpath), and which toImmutable()
            // leaves unformatted. A first event holds the worker, so the second one is still queued when the
            // application goes on to change the list it logged
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var releaseFirstTask = new CountDownLatch(1);
            var appender = new DeferredFormattingLogDeliveryAppender(
                    "appender-name", null, configuration, releaseFirstTask);
            var items = new ArrayList<>(List.of("first"));
            var loggingEvent = Log4jLogEvent.newBuilder()
                    .setMessage(new ParameterizedMessage("Processing {}", items))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            appender.append(loggingEvent);
            items.add("second");
            releaseFirstTask.countDown();
            appender.stop(2, SECONDS);

            // then
            assertEquals(List.of("A", "Processing [first]"), appender.getFormattedMessages());
        }

        @Test
        @DisplayName("Still hands off an event whose message fails to format on the calling thread")
        void testMessageFailingToFormatIsStillHandedOff() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            var message = mock(Message.class);
            when(message.getFormattedMessage()).thenThrow(new IllegalStateException("cannot format"));
            var loggingEvent = Log4jLogEvent.newBuilder()
                    .setMessage(message)
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when
            appender.start();
            assertDoesNotThrow(() -> appender.append(loggingEvent));
            appender.stop(2, SECONDS);

            // then: left for the conversion to run into, where it only costs the message field
            assertEquals(1, appender.getCountTaskExecuted().get());
            assertEquals(List.of(), errors);
        }

        @Test
        @DisplayName("Records the thread that logged an event, even one resolving its thread lazily")
        void testEventRecordsTheThreadThatLoggedIt() throws Exception {
            // given: an event reading its thread from whichever thread asks first, and whose snapshot is the
            // event itself, as the Log4jLogEvent of Log4j2 before 2.25 does, which Log4j2 creates whenever its
            // thread locals are off (by default, whenever the Servlet API is on the classpath)
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var threads = new CopyOnWriteArrayList<String>();
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                public LogDelivery createLogDeliveryHandler() {
                    return mock(LogDelivery.class);
                }

                @Override
                protected LogConverter getLogConverter() {
                    return event -> {
                        threads.add(event.getThreadName());
                        return null;
                    };
                }
            };

            // when
            appender.start();
            Thread.ofPlatform().name("application-thread").start(() -> appender.append(new LazyThreadLogEvent())).join();
            appender.stop(2, SECONDS);

            // then: rather than the worker, which reads it first otherwise
            assertEquals(List.of("application-thread"), threads);
        }

        @Test
        @DisplayName("Leaves out the caller location, found by walking the stack, for the appenders after it too")
        void testSnapshotLeavesOutTheCallerLocation() {
            // given: an appender not requiring the location, followed by another reading it
            var appender = new LocationRecordingAppender(false);
            var nextSources = new CopyOnWriteArrayList<@Nullable StackTraceElement>();
            var next = new AbstractAppender("next", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    nextSources.add(event.getSource());
                }
            };

            // when
            logThroughSynchronousLogger(appender, next);

            // then: the next appender still finds it, the event being left as it was logged
            assertEquals(Arrays.asList((StackTraceElement) null), appender.getSources());
            assertEquals(1, nextSources.size());
            assertEquals("logThroughSynchronousLogger", nextSources.getFirst().getMethodName());
        }

        @Test
        @DisplayName("Keeps the caller location for an appender requiring it")
        void testSnapshotKeepsTheCallerLocationWhenRequired() {
            // given
            var appender = new LocationRecordingAppender(true);

            // when
            logThroughSynchronousLogger(appender);

            // then
            assertEquals(1, appender.getSources().size());
            assertEquals("logThroughSynchronousLogger", appender.getSources().getFirst().getMethodName());
        }

        /**
         * Logs one event through the root logger of a context of its own, synchronous and so including the
         * caller location by default, to the given appenders in turn, then stops the context.
         */
        private void logThroughSynchronousLogger(Appender... appenders) {
            ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
            builder.setStatusLevel(Level.ERROR);
            builder.add(builder.newRootLogger(Level.INFO));
            var context = new LoggerContext("EventSnapshot");
            context.start(builder.build());
            try {
                var configuration = context.getConfiguration();
                for (Appender appender : appenders) {
                    appender.start();
                    configuration.addAppender(appender);
                    configuration.getRootLogger().addAppender(appender, null, null);
                }
                context.updateLoggers();
                context.getLogger(getClass()).info("hello");
            } finally {
                context.stop();
            }
        }

    }

    /**
     * Records the caller location of every event it converts, declaring that it requires it or not.
     */
    @Getter
    static class LocationRecordingAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        List<@Nullable StackTraceElement> sources = new CopyOnWriteArrayList<>();
        boolean locationRequired;

        LocationRecordingAppender(boolean locationRequired) {
            super("location-recording", null, TestLogConfiguration.builder().build());
            this.locationRequired = locationRequired;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return mock(LogDelivery.class);
        }

        @Override
        protected LogConverter getLogConverter() {
            return event -> {
                sources.add(event.getSource());
                return null;
            };
        }

        @Override
        public boolean requiresLocation() {
            return locationRequired;
        }

    }

    /**
     * A logging event reading its thread from whichever thread asks first, and whose snapshot is the event
     * itself, as the {@code Log4jLogEvent} of Log4j2 before 2.25 does.
     */
    static class LazyThreadLogEvent extends AbstractLogEvent {

        private @Nullable String threadName;

        @Override
        public String getThreadName() {
            if (threadName == null) {
                threadName = Thread.currentThread().getName();
            }
            return threadName;
        }

        @Override
        public Message getMessage() {
            return new SimpleMessage("hello");
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
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
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
            awaitEmptyQueue(appender);
            appender.append(loggingEvent); // fills the one-slot queue
            var start = System.nanoTime();
            appender.append(loggingEvent); // discards the previously queued one instead of blocking
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then
            assertTrue(elapsedMillis < 200, "append() should not block even when the queue is full, took " + elapsedMillis + "ms");
            awaitSize(dropped, 1);
            assertEquals(1, dropped.size());
            assertEquals("would have been dropped", dropped.get(0).get("logmessage"));
            assertTrue(droppedReasons.get(0).contains("queue"), "Reason should mention the queue: " + droppedReasons.get(0));
            assertFalse(errors.isEmpty(), "The discard should also have been reported through the error handler");

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Never fails the application thread when the listener notified of a discard throws an Error")
        void testListenerThrowingAnErrorOnDiscardDoesNotReachTheApplicationThread() throws Exception {
            // given: the same full-queue setup as above, with a listener throwing an Error rather than an
            // exception, as one walking a self-referencing document into a dead-letter sink would
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    throw new StackOverflowError("listener blew up");
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue

            // then: the discard reaches the error handler, not whatever application code called append()
            assertDoesNotThrow(() -> appender.append(realLogEvent("C")));
            assertFalse(errors.isEmpty(), "The listener failure should have been reported through the error handler");

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
                    "appender-name", null, configuration, releaseFirstTask);
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
            awaitEmptyQueue(appender);
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
        @DisplayName("Claims the slot freed by an eviction before notifying the listener about it")
        void testEvictedSlotIsClaimedBeforeTheDiscardIsReported() throws Exception {
            // given: the same full-queue setup, with a listener that observes the queue from inside its own
            // onDocumentsDropped -- which is where a real one writes to a file or a dead-letter queue, and
            // so where it spends whatever time it spends. Leaving the freed slot open for that long lets
            // another appending thread take it, so the eviction that was supposed to make room for this
            // event ends up discarding two events for someone else's benefit.
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            var queuedWhileReporting = new CompletableFuture<Integer>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    queuedWhileReporting.complete(appender.getQueuedEventCount());
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);

            // when
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue
            appender.append(realLogEvent("C")); // evicts B and takes its place

            // then: C was already back in the queue while B's discard was being reported
            assertEquals(1, queuedWhileReporting.get(5, SECONDS));

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Skips converting an event discarded while nothing listens for discards")
        void testDiscardNobodyListensForIsNotConverted() throws Exception {
            // given: the same full-queue setup, with a delivery keeping the default listener, which ignores
            // every discard, as a delivery built without a listener does, and an event whose stack trace
            // records being printed
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(LogDeliveryListener.NOOP);
            var printed = new AtomicInteger();
            var thrown = new IllegalStateException("boom") {
                @Override
                public void printStackTrace(PrintWriter writer) {
                    printed.incrementAndGet();
                    super.printStackTrace(writer);
                }
            };
            var failing = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("B"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.ERROR)
                    .setThrown(thrown)
                    .build();

            // when
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(failing); // fills the one-slot queue
            appender.append(realLogEvent("C")); // evicts B
            releaseTask.countDown();
            appender.stop(2, SECONDS); // waits for the discard to be reported

            // then: B was not turned into a document, stack trace included, for a listener ignoring it
            assertEquals(0, printed.get());
        }

        @Test
        @DisplayName("Reports a discard on another thread than the one logging, however slow the listener")
        void testDiscardIsReportedOffTheLoggingThread() throws Exception {
            // given: the same full-queue setup, with a listener slow to return, as one writing every discarded
            // document to a file is while the downstream system is down and the queue stays full
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            var releaseListener = new CountDownLatch(1);
            var listenerThread = new CompletableFuture<Thread>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    listenerThread.complete(Thread.currentThread());
                    try {
                        releaseListener.await(5, SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);

            // when
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue
            long start = System.nanoTime();
            appender.append(realLogEvent("C")); // evicts B
            appender.append(realLogEvent("D")); // evicts C
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: the application went on logging while the listener was still busy with the first discard
            assertNotSame(Thread.currentThread(), listenerThread.get(5, SECONDS));
            assertTrue(elapsedMillis < 1_000, "append() should not wait for the listener, took " + elapsedMillis + "ms");

            // cleanup
            releaseListener.countDown();
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Logs a discard instead of waiting once as many as the queue holds wait for the listener")
        void testDiscardsTheListenerCannotTakeAreLogged() throws Exception {
            // given: a one-slot queue, and a listener blocked on the first discard it is told about
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            var releaseListener = new CountDownLatch(1);
            var listenerCalled = new CountDownLatch(1);
            var reported = new CopyOnWriteArrayList<Object>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    documents.forEach(document -> reported.add(document.get("logmessage")));
                    listenerCalled.countDown();
                    try {
                        releaseListener.await(5, SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when: B is reported, the listener then holding on to it
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue
            appender.append(realLogEvent("C")); // evicts B, reported
            assertTrue(listenerCalled.await(5, SECONDS), "The listener should have been told about B");
            appender.append(realLogEvent("D")); // evicts C, waiting for the listener
            appender.append(realLogEvent("E")); // evicts D, with nowhere left to wait

            // then: D is only logged, rather than kept on its way to a listener already that far behind
            assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("Unable to report")),
                    "The discard that could not be reported should have been logged, got: " + errors);
            releaseListener.countDown();
            releaseTask.countDown();
            appender.stop(2, SECONDS);
            assertEquals(List.of("B", "C"), reported);
        }

        @Test
        @DisplayName("Reports discards on a thread that does not take after the first thread to have one")
        void testDiscardReporterDoesNotTakeAfterTheFirstThreadToHaveADiscard() throws Exception {
            // given: the same full-queue setup, with a listener reporting the context it runs with
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            var requestContext = new InheritableThreadLocal<String>();
            var requestClassLoader = new ClassLoader() {
            };
            var reporterContext = new CompletableFuture<List<Object>>();
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    reporterContext.complete(Arrays.asList(
                            requestContext.get(), Thread.currentThread().getContextClassLoader()));
                }
            });
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue

            // when: the first discard happens on a thread carrying a context of its own, as a request thread of
            // a web application does, with an inheritable thread-local and the class loader of the application
            Thread.ofPlatform().start(() -> {
                requestContext.set("request-1");
                Thread.currentThread().setContextClassLoader(requestClassLoader);
                appender.append(realLogEvent("C")); // evicts B
            }).join();

            // then: the reporter runs until the appender stops, so a context it took from that thread would be
            // kept alive as long, the class loader of an undeployed application included
            var context = reporterContext.get(5, SECONDS);
            assertNull(context.get(0), "The reporter should not see the inheritable thread-locals of that thread");
            assertNotSame(requestClassLoader, context.get(1), "The reporter should not use the class loader of that thread");

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Reports an event the worker shut down under while it was being queued, rather than stranding it")
        void testEventQueuedAsTheWorkerShutsDownIsReported() throws Exception {
            // given: a worker queue claiming to be full, which leaves the event to the policy discarding the
            // oldest one, then shutting its executor down right before accepting it from that policy, its idle
            // worker exiting: the window a stop leaves between the policy checking the executor and queuing
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                }
            });
            var queue = new ShutdownRaceQueue();
            var executor = new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, queue,
                    workerExecutorOf(appender).getRejectedExecutionHandler());
            queue.executor = executor;
            replaceWorkerExecutor(appender, executor);
            appender.start();

            // when
            queue.armed = true;
            appender.append(realLogEvent("queued too late"));

            // then: reported as dropped, where it used to stay in the queue of an executor terminated for good,
            // neither run nor reported
            awaitSize(dropped, 1);
            assertEquals("queued too late", dropped.get(0).get("logmessage"));
            assertTrue(queue.isEmpty(), "The event should not have been left in the queue");

            // cleanup
            appender.stop(1, SECONDS);
        }

    }

    /**
     * A worker queue claiming to be full on the first offer once armed, which hands the task to the policy
     * of its executor, then shutting that executor down, its idle worker exiting, right before accepting the
     * offer the policy makes in turn.
     */
    static class ShutdownRaceQueue extends LinkedBlockingQueue<Runnable> {

        volatile boolean armed;
        volatile ThreadPoolExecutor executor;
        private final AtomicInteger armedOffers = new AtomicInteger();

        @Override
        public boolean offer(Runnable task) {
            if (armed) {
                int offer = armedOffers.incrementAndGet();
                if (offer == 1) {
                    return false;
                }
                if (offer == 2) {
                    executor.shutdown();
                    try {
                        executor.awaitTermination(5, SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return super.offer(task);
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
            var appender = new RecordingLogDeliveryAppender("appender-name", null, configuration, events, 50);

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
        @DisplayName("Waits for pending tasks when Log4j2 leaves the timeout to the appender")
        void testStopWithDefaultTimeoutWaitsForPendingTasks() throws Exception {
            // given: the same slow tasks, stopped with 0, which Log4j2 passes when its shutdownTimeout is
            // left unset and on every reconfiguration, documenting it as "each appender uses its default"
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var events = new CopyOnWriteArrayList<String>();
            var appender = new RecordingLogDeliveryAppender("appender-name", null, configuration, events, 50);

            // when
            appender.start();
            for (int i = 0; i < 5; i++) {
                appender.append(mockLogEvent());
            }
            appender.stop(0, MILLISECONDS);

            // then: waited up to CloseTimeout, rather than abandoning all but the running task in the queue
            assertEquals(5, events.stream().filter("send"::equals).count());
            assertEquals("close", events.get(events.size() - 1));

            // and: closed the delivery within what the tasks left of it, rather than a whole one of its own
            assertClosedWithin(appender, Duration.ofSeconds(10).minusMillis(250));
        }

        @Test
        @DisplayName("Closes the delivery within what processing the queued events left of the stop budget")
        void testStopClosesTheDeliveryWithinWhatIsLeftOfItsBudget() throws Exception {
            // given: slow tasks queued right before stopping, taking a quarter of the budget between them
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var events = new CopyOnWriteArrayList<String>();
            var appender = new RecordingLogDeliveryAppender("appender-name", null, configuration, events, 50);

            // when
            appender.start();
            for (int i = 0; i < 5; i++) {
                appender.append(mockLogEvent());
            }
            appender.stop(1, SECONDS);

            // then: the delivery got the rest of that second, rather than its own CloseTimeout on top of it,
            // which let a stop take twice as long as the shutdownTimeout Log4j2 was configured with
            assertEquals(5, events.stream().filter("send"::equals).count());
            assertClosedWithin(appender, Duration.ofMillis(750));
        }

        /**
         * Asserts that the delivery of the given appender was closed once, with a timeout between zero and the
         * given maximum.
         */
        private static void assertClosedWithin(RecordingLogDeliveryAppender appender, Duration maximum) {
            assertEquals(1, appender.getCloseTimeouts().size(), "The delivery should have been closed once");
            Duration timeout = appender.getCloseTimeouts().get(0);
            assertNotNull(timeout, "The delivery should have been closed with a bounded timeout");
            assertFalse(timeout.isNegative(), "The delivery should not have been closed with a negative timeout");
            assertTrue(timeout.compareTo(maximum) <= 0,
                    "The delivery should have been closed within " + maximum + ", but was given " + timeout);
        }

        @Test
        @DisplayName("Bounds the wait by CloseTimeout when Log4j2 leaves the timeout to the appender")
        void testStopWithDefaultTimeoutIsBoundedByCloseTimeout() throws Exception {
            // given: a task that blocks until interrupted, and a short close timeout
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .closeTimeout(Duration.ofMillis(200)).build();
            var interrupted = new CountDownLatch(1);
            var appender = new StuckLogDeliveryAppender("appender-name", null, configuration, interrupted);

            // when
            appender.start();
            appender.append(mockLogEvent());
            long start = System.nanoTime();
            appender.stop(0, MILLISECONDS);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: gave up once the close timeout elapsed, instead of waiting on the stuck task forever
            assertTrue(interrupted.await(2, SECONDS), "The stuck task should have been interrupted after the timeout");
            assertTrue(elapsedMillis < 5_000, "stop() should have given up after the close timeout, took " + elapsedMillis + "ms");
        }

        @Test
        @DisplayName("Never keeps the JVM alive on its own once the application is done")
        void testBackgroundThreadsDoNotKeepTheJvmAlive() throws Exception {
            // given: batching enabled, so start() also creates the periodic flush scheduler, and a worker
            // task reporting which kind of thread it runs on
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var workerIsDaemon = new CompletableFuture<Boolean>();
            var appender = new LogDeliveryAppender("appender-name", null, configuration) {
                @Override
                protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    return () -> workerIsDaemon.complete(Thread.currentThread().isDaemon());
                }
            };
            var schedulersBefore = liveThreadsNamed("log-delivery-flush-scheduler");

            // when: started and logged to from a non-daemon thread, as an application's main thread does
            Thread.ofPlatform().daemon(false).start(() -> {
                appender.start();
                appender.append(mockLogEvent());
            }).join();

            // then: neither thread holds the JVM open. Neither ends before stop(), which only the Log4j2
            // shutdown hook calls, and that hook waits for the JVM to start shutting down -- which a live
            // non-daemon thread prevents, so an application returning from main() never exited at all
            assertTrue(workerIsDaemon.get(5, SECONDS), "The worker thread should be a daemon");
            var schedulersCreated = liveThreadsNamed("log-delivery-flush-scheduler");
            schedulersCreated.removeAll(schedulersBefore);
            assertFalse(schedulersCreated.isEmpty(), "Starting should have created a flush scheduler");
            assertTrue(schedulersCreated.stream().allMatch(Thread::isDaemon), "The flush scheduler thread should be a daemon");

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Never keeps the context of the first thread to log for as long as it runs")
        void testWorkerDoesNotTakeAfterTheFirstThreadToLog() throws Exception {
            // given: a worker task reporting the context it runs with
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var requestContext = new InheritableThreadLocal<String>();
            var requestClassLoader = new ClassLoader() {
            };
            var workerContext = new CompletableFuture<List<Object>>();
            var appender = new LogDeliveryAppender("appender-name", null, configuration) {
                @Override
                protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    return () -> workerContext.complete(Arrays.asList(
                            requestContext.get(), Thread.currentThread().getContextClassLoader()));
                }
            };
            appender.start();

            // when: the first thread to log carries a context of its own, as a request thread of a web
            // application does, with an inheritable thread-local and the class loader of the application
            Thread.ofPlatform().start(() -> {
                requestContext.set("request-1");
                Thread.currentThread().setContextClassLoader(requestClassLoader);
                appender.append(mockLogEvent());
            }).join();

            // then: the worker runs until the appender stops, so a context it took from that thread would be
            // kept alive as long, the class loader of an undeployed application included
            var context = workerContext.get(5, SECONDS);
            assertNull(context.get(0), "The worker should not see the inheritable thread-locals of that thread");
            assertNotSame(requestClassLoader, context.get(1), "The worker should not use the class loader of that thread");

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Finishes stopping even when closing the delivery fails with an Error")
        void testStopCompletesWhenClosingTheDeliveryThrowsAnError() {
            // given: closing a delivery releases its transport client, and a class that client only needs on
            // the way out can fail to load once the JVM is already shutting down
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new ErrorOnCloseLogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            appender.start();

            // when / then: reported rather than propagated, and the lifecycle still completes -- this runs
            // from stop()'s finally block, so an escaping failure also skipped the super.stop() after it and
            // left Log4j2 holding an appender still reporting itself as started
            assertDoesNotThrow(() -> appender.stop(0, MILLISECONDS));
            assertFalse(errors.isEmpty(), "The closing failure should have been reported through the error handler");
            assertTrue(appender.isStopped(), "The appender should have finished stopping");
        }

        @Test
        @DisplayName("Rejects further appends without throwing back to the calling thread")
        void testAppendAfterStopDoesNotThrowToCallingThread() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);
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
            var appender = new StuckLogDeliveryAppender("appender-name", null, configuration, interrupted);

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
            var appender = new StuckLogDeliveryAppender("appender-name", null, configuration, interrupted);
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
            var appender = new FailStartLogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            appender.start();
            assertEquals(1, errors.size(), "The startup failure should have been reported exactly once");
            for (int i = 0; i < 5; i++) {
                appender.append(mockLogEvent());
            }

            // then: an appender discarding every event says so, where it used to report itself healthy with
            // nothing dropped, the failure showing in the status logger at startup alone
            assertEquals(DeliveryHealth.Status.DOWN, appender.getHealth().status());
            assertEquals(5, appender.getMetrics().droppedDocuments());
            appender.stop(2, SECONDS);

            // and: append() never even attempted to create a delivery task for a handler that never
            // started, instead of paying for a snapshot, a background thread hand-off and a doomed task
            // on every single one of the 5 events above
            assertEquals(0, appender.getCountTaskCreated().get());
            assertEquals(1, errors.size(), "No additional error should have been reported for the skipped events");
        }

        @Test
        @DisplayName("Reports a delivery handler that comes back null instead of discarding every event in silence")
        void testStartReportsANullHandler() {
            // given: a subclass returning null rather than throwing, which leaves exactly the state a failed
            // creation does -- except nothing went wrong loudly enough for anyone to notice
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new NullHandlerLogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            appender.start();
            appender.append(realLogEvent("goes nowhere"));

            // then: the appender says so, instead of quietly swallowing everything logged for its lifetime
            assertEquals(1, errors.size(), "The missing handler should have been reported exactly once");
            assertNull(appender.getLogDeliveryHandler());

            // cleanup
            appender.stop(0, MILLISECONDS);
        }

        @Test
        @DisplayName("Reports an Error from the handler's creation instead of failing the whole configuration")
        void testStartReportsAnErrorFromHandlerCreation() {
            // given: building a delivery handler is where its transport client is constructed, and a client
            // library missing at runtime surfaces as a NoClassDefFoundError rather than an exception
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new ErrorOnStartLogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when / then: reported and degraded to the same no-handler state an exception leaves behind,
            // rather than handed to whatever is applying the Log4j2 configuration, taking down more than
            // the one appender that cannot ship its logs
            assertDoesNotThrow(appender::start);
            assertEquals(1, errors.size(), "The startup failure should have been reported exactly once");
            assertNull(appender.getLogDeliveryHandler());

            // cleanup
            appender.stop(0, MILLISECONDS);
        }

    }

    @Nested
    @DisplayName("Health and backpressure signal")
    class HealthAndBackpressure {

        @Test
        @DisplayName("getHealth() is down until started, the one of its delivery while started, and down once stopped")
        void testHealthDelegatesToHandlerWhileStarted() throws Exception {
            // given
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

            // then: nothing created yet, so nothing delivered
            assertEquals(DeliveryHealth.Status.DOWN, appender.getHealth().status());

            // when
            appender.start();
            var backingOff = new DeliveryHealth(DeliveryHealth.Status.BACKING_OFF, Duration.ofMinutes(2));
            when(appender.getLogDeliveryHandler().getHealth()).thenReturn(backingOff);

            // then
            assertEquals(backingOff, appender.getHealth());

            // when
            appender.stop();

            // then: down, since its last success still
            assertEquals(new DeliveryHealth(DeliveryHealth.Status.DOWN, Duration.ofMinutes(2)), appender.getHealth());
        }

        @Test
        @DisplayName("Adds what its queue holds and discarded to the metrics of its delivery")
        void testMetricsCombineTheQueueWithTheDelivery() throws Exception {
            // given: a one-slot queue behind a blocked worker, and a delivery with metrics of its own
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            when(appender.getLogDeliveryHandler().getMetrics()).thenReturn(new DeliveryMetrics(0, 5, 500, 2, 40, 3, 7));
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(LogDeliveryListener.NOOP);

            // then: nothing to report before the delivery exists
            assertEquals(DeliveryMetrics.NONE, appender.getMetrics());

            // when: A occupies the worker, B fills the queue, and C evicts B
            appender.start();
            appender.append(mockLogEvent());
            awaitEmptyQueue(appender);
            appender.append(mockLogEvent());
            appender.append(mockLogEvent());

            // then: the queue and the event it discarded come on top of what the delivery reports
            assertEquals(new DeliveryMetrics(1, 5, 500, 2, 40, 3, 8), appender.getMetrics());

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
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
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);

            // when
            appender.start();

            // then: nothing queued yet
            assertFalse(appender.isBackpressured());

            // when: A occupies the worker thread (blocked), B fills the one-slot queue
            appender.append(mockLogEvent());
            awaitEmptyQueue(appender);
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
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);

            // when
            appender.start();

            // then: nothing queued yet
            assertEquals(0, appender.getQueuedEventCount());

            // when: the first event occupies the (blocked) worker thread, the next two sit in the queue
            appender.append(mockLogEvent());
            awaitEmptyQueue(appender);
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
            var appender = new FailFirstLogDeliveryAppender("appender-name", null, configuration);
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
            var appender = new FailCreationLogDeliveryAppender("appender-name", null, configuration);
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

        @Test
        @DisplayName("Never reports an event as dropped once it has already been handed to the worker")
        void testEventAlreadyHandedOffIsNotReportedDropped() throws Exception {
            // given: a configuration that fails to resolve, which the interface documents as legitimate for
            // an implementation varying its settings at runtime. It is read again by the severity check run
            // *after* the event is delivered, so that check is what fails here
            var configuration = new FlakyOptionsConfiguration();
            var appender = new FlakyOptionsLogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);

            // when
            appender.start();
            configuration.broken.set(true);
            assertDoesNotThrow(() -> appender.append(realLogEvent("delivered once")));
            awaitSize(errors, 1);
            configuration.broken.set(false);
            appender.stop(2, SECONDS);

            // then: the failure is reported, but the event is not called dropped -- it reached the worker
            // and was delivered. onDocumentsDropped means "irrecoverably lost, your last chance to save it",
            // so a dead-letter sink would have filed a second copy of an event delivered normally
            assertFalse(errors.isEmpty(), "The failure should still have been reported through the error handler");
            assertEquals(1, appender.getCountTaskExecuted().get(), "The event should have been processed");
            assertEquals(List.of(), dropped);
        }

    }

    @Getter
    static class LogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        AtomicInteger countTaskExecuted = new AtomicInteger();

        protected LogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> countTaskExecuted.incrementAndGet();
        }

    }

    @Getter
    static class StuckLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        CountDownLatch interrupted;

        protected StuckLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration,
                                            CountDownLatch interrupted) {
            super(name, filter, configuration);
            this.interrupted = interrupted;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
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

        protected BlockingLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration,
                                               CountDownLatch releaseTask) {
            super(name, filter, configuration);
            this.releaseTask = releaseTask;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
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

        protected RecordingBlockingLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration,
                                                         CountDownLatch releaseFirstTask) {
            super(name, filter, configuration);
            this.releaseFirstTask = releaseFirstTask;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
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

    /**
     * Reads each event's message only once its task runs on the worker thread, the way an implementation
     * converting there does (the README's example appender, for one), unlike
     * {@link RecordingBlockingLogDeliveryAppender}, which captures it on the calling thread. The very first
     * task blocks until released, so every event appended meanwhile is still queued behind it.
     */
    @Getter
    static class DeferredFormattingLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        CountDownLatch releaseFirstTask;
        List<String> formattedMessages = new CopyOnWriteArrayList<>();

        protected DeferredFormattingLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration,
                                                        CountDownLatch releaseFirstTask) {
            super(name, filter, configuration);
            this.releaseFirstTask = releaseFirstTask;
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
                try {
                    releaseFirstTask.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                formattedMessages.add(loggingEvent.getMessage().getFormattedMessage());
            };
        }

    }

    @Getter
    static class RecordingLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        List<String> events;
        long taskDelayMillis;
        List<Duration> closeTimeouts = new CopyOnWriteArrayList<>();

        protected RecordingLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration,
                                                List<String> events, long taskDelayMillis) {
            super(name, filter, configuration);
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
                    close(null);
                }

                @Override
                public void close(@Nullable Duration timeout) {
                    closeTimeouts.add(timeout);
                    events.add("close");
                }
            };
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
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

    /**
     * A configuration whose delivery options can be made to fail to resolve, standing in for one that
     * builds them from something external — which {@link com.chavaillaz.appender.LogConfiguration} invites
     * by documenting that they are re-read rather than captured once.
     */
    static class FlakyOptionsConfiguration implements LogConfiguration {

        final AtomicBoolean broken = new AtomicBoolean();

        private final DeliveryOptions options = DeliveryOptions.builder()
                .flushThreshold(10)
                .flushInterval(Duration.ofSeconds(10))
                .flushLevel(Level.ERROR)
                .closeTimeout(Duration.ofSeconds(1))
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

    @Getter
    static class FlakyOptionsLogDeliveryAppender extends AbstractLogDeliveryAppender<FlakyOptionsConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        AtomicInteger countTaskExecuted = new AtomicInteger();

        protected FlakyOptionsLogDeliveryAppender(String name, Filter filter, FlakyOptionsConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> countTaskExecuted.incrementAndGet();
        }

    }

    /**
     * An appender whose delivery fails to close with an {@link Error}, as a transport client needing a
     * class that can no longer be loaded during shutdown does.
     */
    static class ErrorOnCloseLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        protected ErrorOnCloseLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
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
                    throw new NoClassDefFoundError("com/example/transport/RestClient$Closer");
                }
            };
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
            };
        }

    }

    /**
     * An appender whose {@code createLogDeliveryHandler()} answers {@code null} rather than throwing,
     * breaking a contract nothing enforces at runtime.
     */
    static class NullHandlerLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        protected NullHandlerLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        @SuppressWarnings("NullAway")
        public LogDelivery createLogDeliveryHandler() {
            return null;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
            };
        }

    }

    /**
     * An appender whose delivery handler cannot be built because a class it needs is missing at runtime,
     * which is how an absent transport client library actually shows up: an {@link Error}, not an exception.
     */
    static class ErrorOnStartLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        protected ErrorOnStartLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            throw new NoClassDefFoundError("com/example/transport/RestClient");
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> {
            };
        }

    }

    @Getter
    static class FailStartLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        AtomicInteger countTaskCreated = new AtomicInteger();

        protected FailStartLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            throw new RuntimeException("Simulated startup failure");
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
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

        protected FailFirstLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
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

        protected FailCreationLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        public LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            if (firstCall.compareAndSet(true, false)) {
                throw new RuntimeException("Simulated failure creating the task");
            }
            return countTaskExecuted::incrementAndGet;
        }

    }

}
