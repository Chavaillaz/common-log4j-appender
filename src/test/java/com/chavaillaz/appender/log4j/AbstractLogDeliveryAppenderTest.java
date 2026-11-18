package com.chavaillaz.appender.log4j;

import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.DeadLetterListener;
import com.chavaillaz.appender.DeliveryHealth;
import com.chavaillaz.appender.DeliveryMetrics;
import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;
import com.chavaillaz.appender.RecordingAppender;
import com.chavaillaz.appender.TransmissionResult;

@DisplayName("AbstractLogDeliveryAppender")
class AbstractLogDeliveryAppenderTest {

    /**
     * What a stop giving up on the reporter of discarded events reports while the listener is still busy.
     */
    private static final String LISTENER_STILL_NOTIFIED =
            "The delivery listener was still being notified of discarded logging events as the appender stopped";

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
     * The live platform threads with the given name right now, across every appender in this JVM: named after
     * their appender, those of an appender whose name no other test uses.
     */
    private static Set<Thread> liveThreadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> name.equals(thread.getName()))
                .collect(Collectors.toSet());
    }

    /**
     * Waits until no live platform thread bears the given name, a thread ending shortly after it was told to.
     */
    private static void awaitNoLiveThreadNamed(String name) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (!liveThreadsNamed(name).isEmpty()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for the thread " + name + " to end");
            }
            sleep(1);
        }
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
     * Waits until the given errors hold one of the given type, an error of the reporter of discarded events
     * reaching the handler from its own thread.
     */
    private static void awaitErrorOfType(List<Throwable> errors, Class<? extends Throwable> type)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (errors.stream().noneMatch(type::isInstance)) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for " + type.getSimpleName() + ", got " + errors);
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
    @DisplayName("When built")
    class Construction {

        @Test
        @DisplayName("Cannot be built without a configuration, or with one giving no delivery options, naming what is missing")
        void testRequiresAConfigurationAndItsDeliveryOptions() {
            // given: a configuration answering null for its delivery options, as one built without them does
            LogConfiguration withoutOptions = mock(LogConfiguration.class);

            // then: refused where the appender is built, with what is missing, rather than once it starts
            assertThatThrownBy(() -> new LogDeliveryAppender("appender-name", null, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("configuration must not be null");
            assertThatThrownBy(() -> new AbstractLogDeliveryAppender<>("appender-name", null, withoutOptions) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return mock(LogDelivery.class);
                }
            }).isInstanceOf(NullPointerException.class).hasMessage("configuration.getDeliveryOptions() must not be null");
        }

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

            // then: flushed once the interval elapsed, after the event it was due for
            verify(appender.getLogDeliveryHandler(), timeout(5_000)).flush();
            assertThat(appender.getCountTaskExecuted().get()).isEqualTo(1);

            // when
            appender.stop();

            // then: closed, the flushes of the intervals elapsed meanwhile aside
            verify(appender.getLogDeliveryHandler(), atLeastOnce()).flush();
            verify(appender.getLogDeliveryHandler()).close(any());
            verifyNoMoreInteractions(appender.getLogDeliveryHandler());
        }

        @Test
        @DisplayName("Builds a single delivery handler and scheduler however many times it is started")
        void testStartingTwiceDoesNotLeakTheFirstHandlerAndScheduler() throws Exception {
            // given: batching enabled, so start() also creates the periodic flush scheduler, and an appender
            // creating a new delivery handler each time it is asked for one
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(200)).build();
            var created = new CopyOnWriteArrayList<LogDelivery>();
            var appender = new AbstractLogDeliveryAppender<>("started-twice", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    LogDelivery handler = mock(LogDelivery.class);
                    created.add(handler);
                    return handler;
                }

                @Override
                protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    return () -> {
                    };
                }
            };

            // when: started again, as nothing in Log4j2's lifecycle rules out
            appender.start();
            appender.start();

            // then: still the first handler, rather than a second one leaving it with no reference left to close
            // it
            assertThat(created).hasSize(1);
            LogDelivery handler = created.getFirst();
            assertThat(appender.getLogDeliveryHandler()).isSameAs(handler);
            assertThat(appender.isStarted()).isTrue();

            // and: still a single scheduler. Counted rather than timed: a second one only shows up as two
            // flushes per interval, which is indistinguishable from one slow interval on a loaded machine
            assertThat(liveThreadsNamed("log-delivery-flush-scheduler-started-twice"))
                    .as("Starting again should not have created a second flush scheduler")
                    .hasSize(1);

            // and: that scheduler is running, waited for rather than slept through for the same reason
            appender.append(mockLogEvent());
            verify(handler, timeout(5_000).atLeastOnce()).flush();

            // cleanup
            appender.stop();
            verify(handler).close(any());
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

            // when: stopped right away, which waits for the event to be processed
            appender.start();
            appender.append(logEvent);
            appender.stop();

            // then: the event itself was processed, but well below the flush threshold and before the
            // flush interval elapsed, no flush was triggered, the stop only closing the delivery
            assertThat(appender.getCountTaskExecuted().get()).isEqualTo(1);
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
            assertThat(errors)
                    .as("The failure should have been reported through the error handler, got: " + errors)
                    .anyMatch(OutOfMemoryError.class::isInstance);

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
            var appender = new RecordingBlockingLogDeliveryAppender<>("appender-name", null, configuration, releaseFirstTask);
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
            assertThat(appender.getProcessedMessages().subList(0, 3)).containsExactly("first", "flush", "second");

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Keeps a single periodic flush in a queue backlogged for many periods")
        void testBackloggedQueueHoldsASingleFlush() throws Exception {
            // given: a flush requested every millisecond, and a worker taking longer than that for each event, so
            // that it runs the flush right after an event, long before the task requesting it comes up
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(1)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration) {
                @Override
                protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    return () -> {
                        try {
                            sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    };
                }
            };
            appender.start();
            var queue = workerExecutorOf(appender).getQueue();

            // when: a backlog the worker takes a few hundred periods to process
            for (int i = 0; i < 50; i++) {
                appender.append(mockLogEvent());
            }

            // then: a single flush task in the queue at most, where every period used to queue one more, each
            // taking the place of an event
            int mostFlushTasks = 0;
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(10);
            while (!queue.isEmpty() && System.nanoTime() < deadlineNanos) {
                int flushTasks = (int) queue.stream()
                        .filter(task -> !"EventTask".equals(task.getClass().getSimpleName()))
                        .count();
                mostFlushTasks = Math.max(mostFlushTasks, flushTasks);
                sleep(1);
            }
            assertThat(mostFlushTasks)
                    .as("At most one flush should have been queued at a time, got " + mostFlushTasks)
                    .isLessThanOrEqualTo(1);

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Keeps flushing periodically once an event evicted the queued flush from a full queue")
        void testPeriodicFlushSurvivesItsEviction() throws Exception {
            // given: a queue of a single entry, taken by a flush while a first event holds up the worker, and a
            // second event evicting that flush to make room
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(50))
                    .maxQueuedEvents(1).build();
            var releaseFirstTask = new CountDownLatch(1);
            var appender = new RecordingBlockingLogDeliveryAppender<>("appender-name", null, configuration, releaseFirstTask);
            appender.start();
            appender.append(realLogEvent("first"));
            awaitEmptyQueue(appender);
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
            while (appender.getQueuedEventCount() == 0 && System.nanoTime() < deadlineNanos) {
                sleep(1);
            }
            appender.append(realLogEvent("second"));

            // when
            releaseFirstTask.countDown();

            // then: the worker, idle once both events are processed, still flushes every period, where the
            // evicted flush kept counting as queued and none was ever queued again
            verify(appender.getLogDeliveryHandler(), timeout(5_000).atLeast(4)).flush();

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Keeps flushing periodically once a full queue refused the flush of a period")
        void testPeriodicFlushSurvivesItsRejection() throws Exception {
            // given: a queue of a single entry, taken by a second event while a first one holds up the worker, so
            // that the flush of a period going by meanwhile finds no room in it and is refused
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(20))
                    .maxQueuedEvents(1).build();
            var releaseFirstTask = new CountDownLatch(1);
            var appender = new RecordingBlockingLogDeliveryAppender<>("appender-name", null, configuration, releaseFirstTask);
            appender.start();
            appender.append(realLogEvent("first"));
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("second"));
            sleep(100);

            // when
            releaseFirstTask.countDown();

            // then: the worker, idle once both events are processed, still flushes every period, where the
            // refused flush kept counting as queued, the one following the first event being the last
            verify(appender.getLogDeliveryHandler(), timeout(5_000).atLeast(4)).flush();

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Retries a failed document without batching nor backoff, before any other event comes")
        void testRetriesAFailedDocumentWithoutAnotherEvent() throws Exception {
            // given: every document sent as it arrives, with no backoff, and a first attempt failing
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .flushInterval(Duration.ofMillis(100)).build();
            var delivered = new CountDownLatch(1);
            var appender = appenderFailingItsFirstRequest(configuration, delivered);

            // when: a single event, the application falling quiet after it
            appender.start();
            appender.append(realLogEvent("last words"));

            // then: the document put back is sent again by the periodic flush, rather than kept in memory for
            // an event that may not come before the application stops
            assertThat(delivered.await(5, SECONDS)).as("The failed document should have been retried").isTrue();

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Retries a failed document once its retry backoff elapsed, before any other event comes")
        void testRetriesADocumentHeldBackByABackoffWithoutAnotherEvent() throws Exception {
            // given: no batching, a first attempt failing, and a retry backoff (enabled by default in production,
            // unlike in this test configuration) of up to 300 ms, drawn at random, which most often holds off the
            // flushes of the first periods
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .retryBackoff(Duration.ofMillis(300))
                    .maxRetryBackoff(Duration.ofMillis(300))
                    .flushInterval(Duration.ofMillis(20)).build();
            var delivered = new CountDownLatch(1);
            var appender = appenderFailingItsFirstRequest(configuration, delivered);

            // when: a single event, the application falling quiet after it
            appender.start();
            appender.append(realLogEvent("last words"));

            // then: once the backoff elapsed, nothing sends the document until the next one happens to arrive.
            // Under low traffic the periodic flush is the only thing that ever will, a flush held off by the
            // backoff being followed by the one of the next period
            assertThat(delivered.await(5, SECONDS)).as("The failed document should have been retried").isTrue();

            // cleanup
            appender.stop(1, SECONDS);
        }

        /**
         * An appender whose delivery fails its first request, as during a short outage, and releases the given
         * latch once a later one is accepted.
         */
        private AbstractLogDeliveryAppender<TestLogConfiguration> appenderFailingItsFirstRequest(
                TestLogConfiguration configuration, CountDownLatch delivered) {
            var attempts = new AtomicInteger();
            return new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return new AbstractBatchLogDelivery<>(configuration) {
                        @Override
                        protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                            if (attempts.incrementAndGet() == 1) {
                                return CompletableFuture.completedFuture(TransmissionResult.failure(new IllegalStateException("Simulated outage")));
                            }
                            delivered.countDown();
                            return CompletableFuture.completedFuture(TransmissionResult.success());
                        }
                    };
                }
            };
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
        @DisplayName("Triggers an immediate flush right after an event exactly at FlushLevel")
        void testEventAtFlushLevelTriggersImmediateFlush() throws Exception {
            // given: the same setting, FlushLevel being the least severe level that flushes
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10))
                    .flushLevel(Level.WARN).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

            // when
            appender.start();
            appender.append(mockLogEvent(Level.WARN));

            // then
            verify(appender.getLogDeliveryHandler(), timeout(5_000)).flush();

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
            var appender = new RecordingBlockingLogDeliveryAppender<>("appender-name", null, configuration, releaseFirstTask);
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
            assertThat(appender.getProcessedMessages()).containsExactly("first", "urgent", "flush");

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

            // when: stopped right away, which waits for the event to be processed
            appender.start();
            appender.append(mockLogEvent(Level.INFO));
            appender.stop();

            // then: INFO is less severe than the configured WARN threshold, so no immediate flush
            assertThat(appender.getCountTaskExecuted().get()).isEqualTo(1);
            verify(appender.getLogDeliveryHandler(), never()).flush();
        }

        @Test
        @DisplayName("Never flushes on severity alone while FlushLevel is left disabled")
        void testFlushLevelDisabledByDefault() throws Exception {
            // given: FlushLevel left at its default (null)
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);

            // when: stopped right away, which waits for the event to be processed
            appender.start();
            appender.append(mockLogEvent(Level.FATAL));
            appender.stop();

            // then: no FlushLevel configured, so not even a FATAL event triggers an immediate flush
            assertThat(appender.getCountTaskExecuted().get()).isEqualTo(1);
            verify(appender.getLogDeliveryHandler(), never()).flush();
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
                protected LogDelivery createLogDeliveryHandler() {
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
            assertThat(appender.getLayout()).isNull();

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
                protected LogDelivery createLogDeliveryHandler() {
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

        @Test
        @DisplayName("Reports an event the converter fails on as dropped, since it is never sent")
        void testDefaultTaskReportsAnEventFailingToConvert() throws Exception {
            // given: a converter failing on one event, as one reading a field that some events lack does
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var handler = mock(LogDelivery.class);
            when(handler.getMetrics()).thenReturn(DeliveryMetrics.NONE);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            when(handler.getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                }
            });
            LogConverter converter = event -> {
                String message = event.getMessage().getFormattedMessage();
                if ("unconvertible".equals(message)) {
                    throw new IllegalStateException("Simulated conversion failure");
                }
                return Map.of("custom", message);
            };
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return handler;
                }

                @Override
                protected LogConverter getLogConverter() {
                    return converter;
                }
            };

            // when
            appender.start();
            appender.append(realLogEvent("unconvertible"));
            appender.append(realLogEvent("convertible"));
            appender.stop(2, SECONDS);

            // then: the event never sent reached the listener, converted the default way, and the metrics,
            // rather than being lost with nothing but an error the handler of the appender may rate-limit
            verify(handler).send(Map.<String, Object>of("custom", "convertible"));
            assertThat(dropped.stream().map(d -> d.get("logmessage")).toList()).containsExactly("unconvertible");
            assertThat(appender.getMetrics().droppedDocuments()).isEqualTo(1);
        }

        @Test
        @DisplayName("Reports an event its delivery throws on as dropped, since the delivery did not keep it")
        void testDefaultTaskReportsAnEventTheDeliveryThrowsOn() throws Exception {
            // given: a delivery of its own throwing on one document, as one refusing a document it cannot encode
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var handler = mock(LogDelivery.class);
            when(handler.getMetrics()).thenReturn(DeliveryMetrics.NONE);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            var droppedReasons = new CopyOnWriteArrayList<String>();
            when(handler.getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                    droppedReasons.add(reason);
                }
            });
            doAnswer(invocation -> {
                Map<String, Object> document = invocation.getArgument(0);
                if ("unsendable".equals(document.get("logmessage"))) {
                    throw new IllegalStateException("Simulated delivery failure");
                }
                return null;
            }).when(handler).send(anyMap());
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return handler;
                }
            };
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            appender.start();
            appender.append(realLogEvent("unsendable"));
            appender.append(realLogEvent("sendable"));
            appender.stop(2, SECONDS);

            // then: the next event was still sent, and the one the delivery threw on reached the listener and
            // the metrics, rather than only the handler of the appender, which may rate-limit it
            verify(handler).send(argThat((Map<String, Object> document) -> "sendable".equals(document.get("logmessage"))));
            assertThat(dropped.stream().map(d -> d.get("logmessage")).toList()).containsExactly("unsendable");
            assertThat(droppedReasons).containsExactly("log delivery error");
            assertThat(appender.getMetrics().droppedDocuments()).isEqualTo(1);
            assertThat(errors).hasSize(1).first().isInstanceOf(IllegalStateException.class);
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
            assertThat(elapsedMillis)
                    .as("append() should return immediately, took " + elapsedMillis + "ms")
                    .isLessThan(200);

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
            assertThat(appender.getFormattedMessages()).containsExactly("A", "Processing [first]");
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
            assertThatNoException().isThrownBy(() -> appender.append(loggingEvent));
            appender.stop(2, SECONDS);

            // then: left for the conversion to run into, where it only costs the message field
            assertThat(appender.getCountTaskExecuted().get()).isEqualTo(1);
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("Records the name, id and priority of the thread that logged an event, even one resolving them lazily")
        void testEventRecordsTheThreadThatLoggedIt() throws Exception {
            // given: an event reading the name, the id and the priority of its thread from whichever thread asks
            // for each first, and whose snapshot is the event itself, as the Log4jLogEvent of Log4j2 before 2.25
            // does, which Log4j2 creates whenever its thread locals are off (by default, whenever the Servlet API
            // is on the classpath)
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var threads = new CopyOnWriteArrayList<String>();
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return mock(LogDelivery.class);
                }

                @Override
                protected LogConverter getLogConverter() {
                    return event -> {
                        threads.add(event.getThreadName() + " " + event.getThreadId() + " " + event.getThreadPriority());
                        return null;
                    };
                }
            };

            // when: logged by a thread of another priority than the worker
            appender.start();
            Thread application = Thread.ofPlatform().name("application-thread").priority(Thread.NORM_PRIORITY + 2)
                    .start(() -> appender.append(new LazyThreadLogEvent()));
            application.join();
            appender.stop(2, SECONDS);

            // then: rather than those of the worker, which reads them first otherwise
            assertThat(threads).containsExactly(
                    "application-thread " + application.threadId() + " " + (Thread.NORM_PRIORITY + 2));
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
            assertThat(appender.getSources()).isEqualTo(Arrays.asList((StackTraceElement) null));
            assertThat(nextSources).hasSize(1);
            assertThat(nextSources.getFirst().getMethodName()).isEqualTo("logThroughSynchronousLogger");
        }

        @Test
        @DisplayName("Keeps the caller location for an appender requiring it")
        void testSnapshotKeepsTheCallerLocationWhenRequired() {
            // given
            var appender = new LocationRecordingAppender(true);

            // when
            logThroughSynchronousLogger(appender);

            // then
            assertThat(appender.getSources()).hasSize(1);
            assertThat(appender.getSources().getFirst().getMethodName()).isEqualTo("logThroughSynchronousLogger");
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
        protected LogDelivery createLogDeliveryHandler() {
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
     * A logging event reading the name, the id and the priority of its thread, each from whichever thread asks for
     * it first, and whose snapshot is the event itself, as the {@code Log4jLogEvent} of Log4j2 before 2.25 does.
     */
    static class LazyThreadLogEvent extends AbstractLogEvent {

        private @Nullable String threadName;
        private @Nullable Long threadId;
        private @Nullable Integer threadPriority;

        @Override
        public long getThreadId() {
            if (threadId == null) {
                threadId = Thread.currentThread().threadId();
            }
            return threadId;
        }

        @Override
        public int getThreadPriority() {
            if (threadPriority == null) {
                threadPriority = Thread.currentThread().getPriority();
            }
            return threadPriority;
        }

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
            assertThat(elapsedMillis)
                    .as("append() should not block even when the queue is full, took " + elapsedMillis + "ms")
                    .isLessThan(200);
            awaitSize(dropped, 1);
            assertThat(dropped).hasSize(1);
            assertThat(dropped.get(0).get("logmessage")).isEqualTo("would have been dropped");
            assertThat(droppedReasons.get(0))
                    .as("Reason should mention the queue: " + droppedReasons.get(0))
                    .contains("queue");
            assertThat(errors).as("The discard should also have been reported through the error handler").isNotEmpty();

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Keeps reporting discards once the listener threw an Error on one of them")
        void testListenerThrowingAnErrorOnDiscardDoesNotStopTheReport() throws Exception {
            // given: the same full-queue setup as above, with a listener throwing an Error rather than an
            // exception on the first discard, as one walking a self-referencing document into a dead-letter
            // sink would, and recording the next ones
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            var firstCall = new AtomicBoolean(true);
            var reported = new CopyOnWriteArrayList<Object>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    if (firstCall.getAndSet(false)) {
                        throw new StackOverflowError("listener blew up");
                    }
                    documents.forEach(document -> reported.add(document.get("logmessage")));
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue

            // when: the listener fails on the discard of B
            appender.append(realLogEvent("C")); // evicts B

            // then: the Error reaches the error handler, rather than ending the thread reporting the discards
            awaitErrorOfType(errors, StackOverflowError.class);

            // when
            appender.append(realLogEvent("D")); // evicts C

            // then: the next discard is still reported
            awaitSize(reported, 1);
            assertThat(reported).containsExactly("C");

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
            var appender = new RecordingBlockingLogDeliveryAppender<>(
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
            assertThat(appender.getProcessedMessages()).containsExactly("A", "C");
            assertThat(dropped).hasSize(1);
            assertThat(dropped.get(0).get("logmessage")).isEqualTo("B");
            assertThat(droppedReasons.get(0))
                    .as("Reason should mention the queue: " + droppedReasons.get(0))
                    .contains("queue");
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
            assertThat(queuedWhileReporting.get(5, SECONDS)).isEqualTo(1);

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("Discards the newest event when another one takes the slot its eviction freed")
        void testEventLosingTheFreedSlotIsDiscardedAsTheNewest() throws Exception {
            // given: a one-slot queue in which another thread appends D right as the eviction frees the slot. The
            // setting sizes the discards waiting to be reported too, two here: B and C are discarded one right
            // after the other, and C would only be logged if the report of B had not begun by then
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(2).build();
            var releaseFirstTask = new CountDownLatch(1);
            var appender = new RecordingBlockingLogDeliveryAppender<>(
                    "appender-name", null, configuration, releaseFirstTask);
            var droppedMessages = new CopyOnWriteArrayList<Object>();
            var droppedReasons = new CopyOnWriteArrayList<String>();
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    documents.forEach(document -> droppedMessages.add(document.get("logmessage")));
                    droppedReasons.add(reason);
                }
            });
            var queue = new RivalQueue(() -> appender.append(realLogEvent("D")));
            replaceWorkerExecutor(appender, new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, queue,
                    workerExecutorOf(appender).getRejectedExecutionHandler()));
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue

            // when: C evicts B, and D takes the freed slot first
            queue.armed = true;
            appender.append(realLogEvent("C"));
            releaseFirstTask.countDown();
            appender.stop(2, SECONDS);

            // then: each discard is reported for what it was, rather than as a queue full or a stop
            assertThat(appender.getProcessedMessages()).containsExactly("A", "D");
            assertThat(droppedMessages).containsExactly("B", "C");
            assertThat(droppedReasons).containsExactly(
                    "internal queue capacity exceeded, discarding the oldest pending event",
                    "internal queue capacity exceeded, discarding the newest event");
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
            assertThat(printed.get()).isZero();
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
            assertThat(listenerThread.get(5, SECONDS)).isNotSameAs(Thread.currentThread());
            assertThat(elapsedMillis)
                    .as("append() should not wait for the listener, took " + elapsedMillis + "ms")
                    .isLessThan(1_000);

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
            assertThat(listenerCalled.await(5, SECONDS)).as("The listener should have been told about B").isTrue();
            appender.append(realLogEvent("D")); // evicts C, waiting for the listener
            appender.append(realLogEvent("E")); // evicts D, with nowhere left to wait

            // then: D is only logged, rather than kept on its way to a listener already that far behind
            assertThat(errors)
                    .as("The discard that could not be reported should have been logged, got: " + errors)
                    .anyMatch(error -> error.getMessage().contains("Unable to report"));
            releaseListener.countDown();
            releaseTask.countDown();
            appender.stop(2, SECONDS);
            assertThat(reported).containsExactly("B", "C");
        }

        @Test
        @DisplayName("Reports discards waiting together apart when their reasons differ, each under its own")
        void testDiscardsOfDifferentReasonsAreReportedApart() throws Exception {
            // given: a queue of two events behind a blocked worker, an appender failing to create the task of
            // some events, and a listener held on the first discard it is told of, the next ones waiting together
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(2).build();
            var releaseTask = new CountDownLatch(1);
            var releaseListener = new CountDownLatch(1);
            var listenerCalled = new CountDownLatch(1);
            var reports = new CopyOnWriteArrayList<String>();
            var handler = mock(LogDelivery.class);
            when(handler.getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    reports.add(documents.stream().map(document -> document.get("logmessage")).toList() + " " + reason);
                    listenerCalled.countDown();
                    try {
                        releaseListener.await(5, SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return handler;
                }

                @Override
                protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    if (loggingEvent.getMessage().getFormattedMessage().startsWith("uncreatable")) {
                        throw new IllegalStateException("Simulated failure creating the task");
                    }
                    return () -> {
                        try {
                            releaseTask.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    };
                }
            };
            appender.setHandler(recordingErrorHandler(new CopyOnWriteArrayList<>()));
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("uncreatable-1")); // discarded, which the listener then holds on to
            assertThat(listenerCalled.await(5, SECONDS)).as("The listener should have been told about it").isTrue();

            // when: two more events are discarded meanwhile, one for its task and one for the room it took
            appender.append(realLogEvent("uncreatable-2"));
            appender.append(realLogEvent("B"));
            appender.append(realLogEvent("C")); // fills the queue
            appender.append(realLogEvent("D")); // evicts B
            releaseListener.countDown();
            releaseTask.countDown();
            appender.stop(2, SECONDS);

            // then: each is reported for what happened to it, rather than both under the reason of the last
            assertThat(reports).containsExactly(
                    "[uncreatable-1] log delivery task creation error",
                    "[uncreatable-2] log delivery task creation error",
                    "[B] internal queue capacity exceeded, discarding the oldest pending event");
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
            assertThat(context.get(0))
                    .as("The reporter should not see the inheritable thread-locals of that thread")
                    .isNull();
            assertThat(context.get(1))
                    .as("The reporter should not use the class loader of that thread")
                    .isNotSameAs(requestClassLoader);

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
            assertThat(dropped.get(0).get("logmessage")).isEqualTo("queued too late");
            assertThat(queue).as("The event should not have been left in the queue").isEmpty();

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

    /**
     * A one-slot worker queue in which, once armed, another event is appended right as the policy discarding
     * the oldest event frees the slot, taking it before the event the eviction was for.
     */
    static class RivalQueue extends LinkedBlockingQueue<Runnable> {

        private final Runnable rival;
        volatile boolean armed;

        RivalQueue(Runnable rival) {
            super(1);
            this.rival = rival;
        }

        @Override
        public Runnable poll() {
            Runnable evicted = super.poll();
            if (armed) {
                armed = false;
                rival.run();
            }
            return evicted;
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
            assertThat(events.stream().filter("send"::equals).count()).isEqualTo(5);
            assertThat(events.get(events.size() - 1)).isEqualTo("close");
        }

        @Test
        @DisplayName("Closes a delivery implementing only close() when stopped, through the default close(Duration)")
        void testStopClosesADeliveryImplementingOnlyClose() {
            // given: a delivery implementing only what LogDelivery requires, holding an event
            var appender = new MinimalLogDeliveryAppender("appender-name", null, TestLogConfiguration.builder().build());
            appender.start();
            appender.append(realLogEvent("before the stop"));

            // when
            appender.stop(2, SECONDS);

            // then: the event sent as converted by default, and the resources of the delivery released once
            assertThat(appender.getSent()).singleElement()
                    .satisfies(document -> assertThat(document).containsEntry("logmessage", "before the stop"));
            assertThat(appender.getCloseCount()).hasValue(1);
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
            assertThat(events.stream().filter("send"::equals).count()).isEqualTo(5);
            assertThat(events.get(events.size() - 1)).isEqualTo("close");

            // and: closed the delivery within what the tasks left of it, rather than a whole one of its own. They
            // take 250 ms of the stop, which the bound leaves a margin under
            assertClosedWithin(appender, Duration.ofSeconds(10).minusMillis(200));
        }

        @Test
        @DisplayName("Closes the delivery within what processing the queued events left of the stop budget")
        void testStopClosesTheDeliveryWithinWhatIsLeftOfItsBudget() throws Exception {
            // given: slow tasks queued right before stopping, taking a quarter of the budget between them once it
            // began
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
            assertThat(events.stream().filter("send"::equals).count()).isEqualTo(5);
            assertClosedWithin(appender, Duration.ofMillis(800));
        }

        /**
         * Asserts that the delivery of the given appender was closed once, with a timeout between zero and the
         * given maximum.
         */
        private static void assertClosedWithin(RecordingLogDeliveryAppender appender, Duration maximum) {
            assertThat(appender.getCloseTimeouts()).as("The delivery should have been closed once").hasSize(1);
            Duration timeout = appender.getCloseTimeouts().get(0);
            assertThat(timeout).as("The delivery should have been closed with a bounded timeout").isNotNull();
            assertThat(timeout.isNegative())
                    .as("The delivery should not have been closed with a negative timeout")
                    .isFalse();
            assertThat(timeout)
                    .as("The delivery should have been closed within " + maximum + ", but was given " + timeout)
                    .isLessThanOrEqualTo(maximum);
        }

        @Test
        @DisplayName("Bounds the wait by CloseTimeout when Log4j2 leaves the timeout to the appender")
        void testStopWithDefaultTimeoutIsBoundedByCloseTimeout() throws Exception {
            // given: a task that blocks until interrupted, and a short close timeout
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .closeTimeout(Duration.ofMillis(500)).build();
            var interrupted = new CountDownLatch(1);
            var appender = new StuckLogDeliveryAppender("appender-name", null, configuration, interrupted);

            // when
            appender.start();
            appender.append(mockLogEvent());
            long start = System.nanoTime();
            appender.stop(0, MILLISECONDS);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: gave up once the close timeout elapsed, neither before nor long after, instead of waiting on
            // the stuck task forever
            assertThat(interrupted.await(2, SECONDS))
                    .as("The stuck task should have been interrupted after the timeout")
                    .isTrue();
            assertThat(elapsedMillis)
                    .as("stop() should have given up after the close timeout, took " + elapsedMillis + "ms")
                    .isBetween(500L, 950L);
        }

        @Test
        @DisplayName("Waits without bound when Log4j2 leaves the timeout to an appender whose CloseTimeout is unset")
        void testStopWithoutAnyTimeoutWaitsWithoutBound() throws Exception {
            // given: slow tasks queued right before stopping, and no CloseTimeout to bound the stop
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(100)
                    .flushInterval(Duration.ofSeconds(10))
                    .closeTimeout(null).build();
            var events = new CopyOnWriteArrayList<String>();
            var appender = new RecordingLogDeliveryAppender("appender-name", null, configuration, events, 50);

            // when
            appender.start();
            for (int i = 0; i < 5; i++) {
                appender.append(mockLogEvent());
            }
            appender.stop(0, MILLISECONDS);

            // then: every queued event was processed, and the delivery closed without a timeout either
            assertThat(events.stream().filter("send"::equals).count()).isEqualTo(5);
            assertThat(appender.getCloseTimeouts()).singleElement().isNull();
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
            var appender = new LogDeliveryAppender("daemon-threads", null, configuration) {
                @Override
                protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    return () -> workerIsDaemon.complete(Thread.currentThread().isDaemon());
                }
            };

            // when: started and logged to from a non-daemon thread, as an application's main thread does
            Thread.ofPlatform().daemon(false).start(() -> {
                appender.start();
                appender.append(mockLogEvent());
            }).join();

            // then: neither thread holds the JVM open. Neither ends before stop(), which only the Log4j2
            // shutdown hook calls, and that hook waits for the JVM to start shutting down -- which a live
            // non-daemon thread prevents, so an application returning from main() never exited at all
            assertThat(workerIsDaemon.get(5, SECONDS)).as("The worker thread should be a daemon").isTrue();
            assertThat(liveThreadsNamed("log-delivery-flush-scheduler-daemon-threads"))
                    .as("The flush scheduler thread should be a daemon")
                    .singleElement()
                    .matches(Thread::isDaemon);

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Names its threads after the appender, telling two appenders apart in a thread dump")
        void testThreadsAreNamedAfterTheAppender() throws Exception {
            // given: two appenders, as two instances of one plugin sending to different places are, each
            // failing to convert its events, so that they are reported as dropped, and recording which threads
            // convert and report them
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var converting = new CopyOnWriteArrayList<String>();
            var reporting = new CopyOnWriteArrayList<String>();
            List<AbstractLogDeliveryAppender<?>> appenders = new ArrayList<>();
            for (String name : List.of("first", "second")) {
                var handler = mock(LogDelivery.class);
                when(handler.getListener()).thenReturn(new LogDeliveryListener() {
                    @Override
                    public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                        reporting.add(name + ": " + Thread.currentThread().getName());
                    }
                });
                appenders.add(new AbstractLogDeliveryAppender<>(name, null, configuration) {
                    @Override
                    protected LogDelivery createLogDeliveryHandler() {
                        return handler;
                    }

                    @Override
                    protected LogConverter getLogConverter() {
                        return event -> {
                            converting.add(name + ": " + Thread.currentThread().getName());
                            throw new IllegalStateException("Unable to convert");
                        };
                    }
                });
            }

            // when: each one is started and logged to
            for (var appender : appenders) {
                appender.start();
                appender.append(realLogEvent("message"));
            }

            // then: every thread of an appender bears its name
            awaitSize(reporting, 2);
            assertThat(converting).containsExactlyInAnyOrder(
                    "first: log-delivery-worker-first",
                    "second: log-delivery-worker-second");
            assertThat(reporting).containsExactlyInAnyOrder(
                    "first: log-delivery-discard-reporter-first",
                    "second: log-delivery-discard-reporter-second");
            assertThat(liveThreadsNamed("log-delivery-flush-scheduler-first")).hasSize(1);
            assertThat(liveThreadsNamed("log-delivery-flush-scheduler-second")).hasSize(1);

            // cleanup
            for (var appender : appenders) {
                appender.stop(1, SECONDS);
            }
        }

        @Test
        @DisplayName("Ends its worker and its flush scheduler once stopped, leaving no thread behind")
        void testStopEndsTheThreadsOfTheAppender() throws Exception {
            // given: an appender logged to and flushed, its worker and its flush scheduler both running
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofMillis(20)).build();
            var appender = new LogDeliveryAppender("stopped-for-good", null, configuration);
            appender.start();
            appender.append(mockLogEvent());
            verify(appender.getLogDeliveryHandler(), timeout(5_000).atLeastOnce()).flush();
            assertThat(liveThreadsNamed("log-delivery-worker-stopped-for-good")).hasSize(1);
            assertThat(liveThreadsNamed("log-delivery-flush-scheduler-stopped-for-good")).hasSize(1);

            // when
            appender.stop(2, SECONDS);

            // then: both end. Log4j2 stops the appenders of a configuration it replaces and starts new ones, so
            // that a thread left running would be one more at every reconfiguration
            awaitNoLiveThreadNamed("log-delivery-worker-stopped-for-good");
            awaitNoLiveThreadNamed("log-delivery-flush-scheduler-stopped-for-good");
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
            assertThat(context.get(0))
                    .as("The worker should not see the inheritable thread-locals of that thread")
                    .isNull();
            assertThat(context.get(1))
                    .as("The worker should not use the class loader of that thread")
                    .isNotSameAs(requestClassLoader);

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Never keeps the context of the thread starting it, which creates its worker and its scheduler")
        void testThreadsDoNotTakeAfterTheThreadStartingIt() throws Exception {
            // given: a worker task reporting the context it runs with
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var deploymentContext = new InheritableThreadLocal<String>();
            var applicationClassLoader = new ClassLoader() {
            };
            var workerContext = new CompletableFuture<List<Object>>();
            var appender = new LogDeliveryAppender("started-with-a-context", null, configuration) {
                @Override
                protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    return () -> workerContext.complete(Arrays.asList(
                            deploymentContext.get(), Thread.currentThread().getContextClassLoader()));
                }
            };

            // when: started by a thread carrying a context of its own, as the thread deploying a web application
            // does, with an inheritable thread-local and the class loader of the application
            Thread.ofPlatform().start(() -> {
                deploymentContext.set("deployment-1");
                Thread.currentThread().setContextClassLoader(applicationClassLoader);
                appender.start();
            }).join();
            appender.append(mockLogEvent());

            // then: both threads run until the appender stops, so a context they took from that thread would be
            // kept alive as long, the class loader of an application undeployed since included
            var context = workerContext.get(5, SECONDS);
            assertThat(context.get(0))
                    .as("The worker should not see the inheritable thread-locals of that thread")
                    .isNull();
            assertThat(context.get(1))
                    .as("The worker should not use the class loader of that thread")
                    .isNotSameAs(applicationClassLoader);
            assertThat(liveThreadsNamed("log-delivery-flush-scheduler-started-with-a-context"))
                    .singleElement()
                    .extracting(Thread::getContextClassLoader)
                    .as("The flush scheduler should not use the class loader of that thread")
                    .isNotSameAs(applicationClassLoader);

            // cleanup
            appender.stop(1, SECONDS);
        }

        @Test
        @DisplayName("Runs the converter, the transport and the listener with the class loader of the appender, whatever thread starts it")
        void testExtensionPointsRunWithTheClassLoaderOfTheAppender() throws Exception {
            // given: an appender loaded by a class loader of its own, as the one of a web application or of an
            // executable JAR nesting its libraries is, whose classes the system class loader knows nothing of
            var applicationClassLoader = new IsolatingClassLoader(ContextReportingAppender.class.getName());
            var observed = new ConcurrentHashMap<String, ClassLoader>();
            LogConfiguration configuration = TestLogConfiguration.builder().flushThreshold(1).build();
            var appender = (AbstractLogDeliveryAppender<?>) applicationClassLoader
                    .loadClass(ContextReportingAppender.class.getName())
                    .getConstructor(String.class, LogConfiguration.class, Map.class)
                    .newInstance("isolated", configuration, observed);
            assertThat(appender.getClass().getClassLoader()).isSameAs(applicationClassLoader);

            // when: started and logged to by a thread running with yet another context class loader, one event
            // being delivered and another one failing to convert, which the listener is told of
            Thread.ofPlatform().start(() -> {
                Thread.currentThread().setContextClassLoader(new ClassLoader() {
                });
                appender.start();
                appender.append(realLogEvent("delivered"));
                appender.append(realLogEvent(ContextReportingAppender.UNCONVERTIBLE));
            }).join();
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
            while (observed.size() < 4) {
                assertThat(System.nanoTime()).as("Every extension point should have run, got " + observed.keySet())
                        .isLessThan(deadlineNanos);
                sleep(1);
            }
            appender.stop(1, SECONDS);

            // then: a library looking its providers up through the context class loader finds on these threads
            // what it finds on those of the application, where the system class loader would show it nothing
            assertThat(observed).containsOnlyKeys(
                    "converter", "transport", "listener of a transmission", "listener of a discarded event");
            assertThat(observed.values()).containsOnly(applicationClassLoader);
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
            assertThatNoException().isThrownBy(() -> appender.stop(0, MILLISECONDS));
            assertThat(errors)
                    .as("The closing failure should have been reported through the error handler")
                    .isNotEmpty();
            assertThat(appender.isStopped()).as("The appender should have finished stopping").isTrue();
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
            assertThatNoException().isThrownBy(() -> appender.append(mockLogEvent()));
            assertThat(errors).as("The rejection should still be reported through the error handler").isNotEmpty();
        }

        @Test
        @DisplayName("Reports an event appended once stopped as rejected, without formatting its message")
        void testAppendAfterStopIsReportedAsRejectedWhateverItsMessage() throws Exception {
            // given: an event whose message fails to format, which the rejection used to format while describing
            // the task, the failure then passing for one of creating it
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);
            appender.setHandler(recordingErrorHandler(new CopyOnWriteArrayList<>()));
            var droppedReasons = new CopyOnWriteArrayList<String>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    droppedReasons.add(reason);
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);
            var message = mock(Message.class);
            when(message.getFormattedMessage()).thenThrow(new IllegalStateException("cannot format"));
            var loggingEvent = Log4jLogEvent.newBuilder()
                    .setMessage(message)
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();
            appender.start();
            appender.stop(1, SECONDS);

            // when
            appender.append(loggingEvent);

            // then: reported for what happened to it, right away since nothing is left to report it later
            assertThat(droppedReasons).containsExactly("appender no longer accepting events");
        }

        @Test
        @DisplayName("Never fails the application thread when the listener throws an Error on an event appended once stopped")
        void testListenerThrowingAnErrorOnAnEventAppendedOnceStoppedDoesNotReachTheApplicationThread() {
            // given: a stopped appender, which reports an event appended to it on the thread logging it, and a
            // listener throwing an Error, as one walking a self-referencing document into a dead-letter sink does
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    throw new StackOverflowError("listener blew up");
                }
            });
            appender.start();
            appender.stop(1, SECONDS);

            // when / then: the Error reaches the error handler, not the application code logging
            assertThatNoException().isThrownBy(() -> appender.append(realLogEvent("too late")));
            assertThat(errors).hasAtLeastOneElementOfType(StackOverflowError.class);
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
            assertThat(interrupted.await(2, SECONDS))
                    .as("The stuck task should have been interrupted after the timeout")
                    .isTrue();
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
            assertThat(interrupted.await(2, SECONDS))
                    .as("The stuck task should have been interrupted after the timeout")
                    .isTrue();
            assertThat(dropped).hasSize(2);
            assertThat(dropped.stream().map(d -> d.get("logmessage")).toList()).containsExactly("queued-1", "queued-2");
            assertThat(droppedReasons)
                    .as("Reason should mention the appender stopping: " + droppedReasons)
                    .allMatch(reason -> reason.contains("stop"));

            // and: handed over as one notification rather than one per event. A listener saving them usually
            // pays per call (opening a file, a round trip to a queue), and a full queue's worth can be
            // abandoned here -- ten thousand by default, on the path where a delay is least affordable
            assertThat(droppedReasons)
                    .as("Every abandoned event should be reported in a single call, but got " + droppedReasons.size())
                    .hasSize(1);
        }

        @Test
        @DisplayName("Still reports the events abandoned along with one that fails to convert")
        void testStopReportsTheEventsAbandonedWithOneFailingToConvert() throws Exception {
            // given: a task blocking the worker, with an event failing to convert queued between two others, as
            // an event of a custom LogEventFactory whose getters throw does
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var interrupted = new CountDownLatch(1);
            var appender = new StuckLogDeliveryAppender("appender-name", null, configuration, interrupted);
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                }
            });
            var unconvertible = mockLogEvent();
            when(unconvertible.getInstant()).thenThrow(new IllegalStateException("Simulated unreadable instant"));

            // when
            appender.start();
            appender.append(mockLogEvent()); // occupies the worker thread forever
            appender.append(realLogEvent("queued-1"));
            appender.append(unconvertible);
            appender.append(realLogEvent("queued-2"));
            appender.stop(200, MILLISECONDS);

            // then: the event failing to convert costs only itself, rather than every event reported with it, a
            // full queue at most
            assertThat(interrupted.await(2, SECONDS)).isTrue();
            assertThat(dropped.stream().map(d -> d.get("logmessage")).toList()).containsExactly("queued-1", "queued-2");
            assertThat(errors).hasAtLeastOneElementOfType(IllegalStateException.class);
        }

        @Test
        @DisplayName("Interrupts a still-running task and reports the queued events when the stop is interrupted")
        void testInterruptedStopAbandonsTheQueueAsATimeoutDoes() throws Exception {
            // given: a task blocking the worker until interrupted, with two events queued behind it
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var interrupted = new CountDownLatch(1);
            var appender = new StuckLogDeliveryAppender("appender-name", null, configuration, interrupted);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            LogDeliveryListener listener = new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                }
            };
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(listener);
            appender.start();
            appender.append(mockLogEvent());
            // Taken by the worker before the stop, which gives it no time at all and would abandon it with the rest
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("queued-1"));
            appender.append(realLogEvent("queued-2"));

            // when: the thread stopping it is interrupted long before its budget elapses
            Thread.currentThread().interrupt();
            try {
                appender.stop(1, MINUTES);
            } finally {
                Thread.interrupted();
            }

            // then: the worker was not left running past the stop, stuck for good or feeding the queued
            // events one by one to a delivery closed by then, and those events were reported at once
            assertThat(interrupted.await(2, SECONDS)).as("The stuck task should have been interrupted").isTrue();
            assertThat(dropped.stream().map(d -> d.get("logmessage")).toList()).containsExactly("queued-1", "queued-2");
        }

        @Test
        @DisplayName("Reports the discards still waiting for a slow listener before it returns, once its budget is spent")
        void testStopReportsTheDiscardsStillWaitingForTheListener() throws Exception {
            // given: a one-slot queue behind a blocked worker, and a listener still busy with the first discard
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            var releaseListener = new CountDownLatch(1);
            var listenerCalled = new CountDownLatch(1);
            var firstCall = new AtomicBoolean(true);
            var reported = new CopyOnWriteArrayList<Object>();
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    if (firstCall.getAndSet(false)) {
                        listenerCalled.countDown();
                        try {
                            releaseListener.await(5, SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    documents.forEach(document -> reported.add(document.get("logmessage")));
                }
            });
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue
            appender.append(realLogEvent("C")); // evicts B, which the listener then holds on to
            assertThat(listenerCalled.await(5, SECONDS)).as("The listener should have been told about B").isTrue();
            appender.append(realLogEvent("D")); // evicts C, waiting for the listener

            // when: stopped within a budget the blocked worker spends
            appender.stop(200, MILLISECONDS);

            // then: C was reported before the stop returned, rather than left to a thread the JVM exiting cuts
            // short, along with D, abandoned in the queue, and the listener still busy was reported too
            assertThat(reported).containsExactlyInAnyOrder("C", "D");
            assertThat(errors).extracting(Throwable::getMessage).contains(LISTENER_STILL_NOTIFIED);

            // cleanup
            releaseListener.countDown();
            releaseTask.countDown();
        }

        @Test
        @DisplayName("Never reports the listener as still notified once its budget is spent, when it returned already")
        void testStopSpendingItsBudgetLeavesAnIdleReporterUnreported() throws Exception {
            // given: a one-slot queue behind a blocked worker, and a listener returning at once from a discard
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxQueuedEvents(1).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            var dropped = new CopyOnWriteArrayList<Map<String, Object>>();
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    dropped.addAll(documents);
                }
            });
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));
            appender.start();
            appender.append(realLogEvent("A")); // occupies the worker thread (blocked until released)
            awaitEmptyQueue(appender);
            appender.append(realLogEvent("B")); // fills the one-slot queue
            appender.append(realLogEvent("C")); // evicts B, which the listener is told about right away
            awaitSize(dropped, 1);

            // when: stopped within a budget the blocked worker spends, leaving none to the idle reporter
            appender.stop(200, MILLISECONDS);

            // then: the worker is reported, not a listener that returned long before
            assertThat(errors).extracting(Throwable::getMessage)
                    .contains("Log delivery thread pool did not terminate within PT0.2S, some logs may be lost")
                    .doesNotContain(LISTENER_STILL_NOTIFIED);

            // cleanup
            releaseTask.countDown();
        }

        @Test
        @DisplayName("Reports the batch its worker still waited to send before a stop whose budget is spent returns")
        void testStopReportsTheBatchItsWorkerWaitedToSend() throws Exception {
            // given: a downstream system that never answers, its single slot held by a first request, and the
            // worker waiting for that slot with the next batch in hand
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(2)
                    .flushInterval(Duration.ofSeconds(10))
                    .maxConcurrentTransmissions(1)
                    .closeTimeout(Duration.ofMillis(100)).build();
            var reported = new CopyOnWriteArrayList<Object>();
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return new AbstractBatchLogDelivery<>(configuration, new LogDeliveryListener() {
                        @Override
                        public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                            documents.forEach(document -> reported.add(document.get("logmessage")));
                        }
                    }) {
                        @Override
                        protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                            return new CompletableFuture<>();
                        }
                    };
                }
            };
            appender.start();
            for (String message : List.of("A", "B", "C", "D")) {
                appender.append(realLogEvent(message));
            }
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
            while (appender.getMetrics().transmissionsInFlight() != 1 || appender.getMetrics().queuedEvents() != 0
                    || appender.getMetrics().pendingDocuments() != 0) {
                assertThat(System.nanoTime()).as("The worker should be waiting for the slot by now").isLessThan(deadlineNanos);
                Thread.sleep(1);
            }

            // when: stopped within a budget that the worker, waiting for a slot nothing frees, spends
            appender.stop();

            // then: every document was reported by the time it returned, those the worker held included, rather
            // than left to that thread, which a JVM exiting on the stop does not wait for
            assertThat(reported).containsExactlyInAnyOrder("A", "B", "C", "D");
            assertThat(appender.getMetrics().droppedDocuments()).isEqualTo(4);
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
            assertThat(errors).as("The startup failure should have been reported exactly once").hasSize(1);
            for (int i = 0; i < 5; i++) {
                appender.append(mockLogEvent());
            }

            // then: an appender discarding every event says so, where it used to report itself healthy with
            // nothing dropped, the failure showing in the status logger at startup alone
            assertThat(appender.getHealth().status()).isEqualTo(DeliveryHealth.Status.DOWN);
            assertThat(appender.getMetrics().droppedDocuments()).isEqualTo(5);
            appender.stop(2, SECONDS);

            // and: append() never even attempted to create a delivery task for a handler that never
            // started, instead of paying for a snapshot, a background thread hand-off and a doomed task
            // on every single one of the 5 events above
            assertThat(appender.getCountTaskCreated().get()).isZero();
            assertThat(errors).as("No additional error should have been reported for the skipped events").hasSize(1);
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
            assertThat(errors).as("The missing handler should have been reported exactly once").hasSize(1);
            assertThat(appender.getLogDeliveryHandler()).isNull();

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
            assertThatNoException().isThrownBy(appender::start);
            assertThat(errors).as("The startup failure should have been reported exactly once").hasSize(1);
            assertThat(appender.getLogDeliveryHandler()).isNull();

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
            assertThat(appender.getHealth().status()).isEqualTo(DeliveryHealth.Status.DOWN);

            // when
            appender.start();
            var backingOff = DeliveryHealth.of(DeliveryHealth.Status.BACKING_OFF, Duration.ofMinutes(2));
            when(appender.getLogDeliveryHandler().getHealth()).thenReturn(backingOff);

            // then
            assertThat(appender.getHealth()).isEqualTo(backingOff);

            // when
            appender.stop();

            // then: down, since its last success still
            assertThat(appender.getHealth())
                    .isEqualTo(DeliveryHealth.of(DeliveryHealth.Status.DOWN, Duration.ofMinutes(2)));
        }

        @Test
        @DisplayName("getHealth() is down from the moment the appender starts stopping")
        void testHealthIsDownWhileStopping() throws Exception {
            // given: a worker blocked on an event, which stopping waits for
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var releaseTask = new CountDownLatch(1);
            var appender = new BlockingLogDeliveryAppender("appender-name", null, configuration, releaseTask);
            appender.start();
            var healthy = DeliveryHealth.of(DeliveryHealth.Status.UP, Duration.ZERO);
            when(appender.getLogDeliveryHandler().getHealth()).thenReturn(healthy);
            appender.append(mockLogEvent());

            // when: stopping, as far as waiting for that event
            var stopping = Thread.ofPlatform().start(() -> appender.stop(5, SECONDS));
            var worker = workerExecutorOf(appender);
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
            while (!worker.isShutdown() && System.nanoTime() < deadlineNanos) {
                sleep(1);
            }

            // then: no longer accepting events, as Log4j2 is told, rather than up for as long as the stop
            // takes, CloseTimeout by default
            assertThat(worker.isShutdown()).as("The stop should have shut the worker down").isTrue();
            assertThat(appender.isStarted()).as("The appender should no longer report itself as started").isFalse();
            assertThat(appender.getHealth().status()).isEqualTo(DeliveryHealth.Status.DOWN);

            // cleanup
            releaseTask.countDown();
            stopping.join();
            assertThat(appender.isStopped()).as("The appender should have finished stopping").isTrue();
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
            when(appender.getLogDeliveryHandler().getMetrics()).thenReturn(DeliveryMetrics.builder()
                    .pendingDocuments(5).pendingBytes(500).transmissionsInFlight(2)
                    .transmittedDocuments(40).failedTransmissions(3).droppedDocuments(7).build());
            when(appender.getLogDeliveryHandler().getListener()).thenReturn(LogDeliveryListener.NOOP);

            // then: nothing to report before the delivery exists
            assertThat(appender.getMetrics()).isEqualTo(DeliveryMetrics.NONE);

            // when: A occupies the worker, B fills the queue, and C evicts B
            appender.start();
            appender.append(mockLogEvent());
            awaitEmptyQueue(appender);
            appender.append(mockLogEvent());
            appender.append(mockLogEvent());

            // then: the queue and the event it discarded come on top of what the delivery reports
            assertThat(appender.getMetrics()).isEqualTo(DeliveryMetrics.builder()
                    .queuedEvents(1).pendingDocuments(5).pendingBytes(500).transmissionsInFlight(2)
                    .transmittedDocuments(40).failedTransmissions(3).droppedDocuments(8).build());

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
            assertThat(appender.isBackpressured()).isFalse();

            // when: A occupies the worker thread (blocked), B fills the one-slot queue
            appender.append(mockLogEvent());
            awaitEmptyQueue(appender);
            appender.append(mockLogEvent());

            // then
            assertThat(appender.isBackpressured()).isTrue();

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
            assertThat(appender.getQueuedEventCount()).isZero();

            // when: the first event occupies the (blocked) worker thread, the next two sit in the queue
            appender.append(mockLogEvent());
            awaitEmptyQueue(appender);
            appender.append(mockLogEvent());
            appender.append(mockLogEvent());

            // then: a gauge worth alerting on well before documents actually start being discarded
            assertThat(appender.getQueuedEventCount()).isEqualTo(2);
            assertThat(appender.isBackpressured()).isFalse();

            // cleanup
            releaseTask.countDown();
            appender.stop(2, SECONDS);
        }

        @Test
        @DisplayName("getHealth() and getMetrics() take the defaults of a delivery tracking neither")
        void testHealthAndMetricsOfADeliveryTrackingNeither() {
            // given: a delivery implementing only what LogDelivery requires
            var appender = new MinimalLogDeliveryAppender("appender-name", null, TestLogConfiguration.builder().build());

            // when
            appender.start();

            // then: up, as if its last transmission had just succeeded, with nothing counted
            assertThat(appender.getHealth()).isEqualTo(DeliveryHealth.of(DeliveryHealth.Status.UP, Duration.ZERO));
            assertThat(appender.getMetrics()).isEqualTo(DeliveryMetrics.NONE);

            // cleanup
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
            assertThat(appender.getCountTaskExecuted().get()).isEqualTo(1);
            assertThat(errors).as("The exception thrown by the first task should have been reported").isNotEmpty();
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
            assertThatNoException().isThrownBy(() -> appender.append(loggingEvent));
            appender.append(mockLogEvent());
            appender.stop(2, SECONDS);

            // then the second (non-throwing) task creation was still processed by the background thread
            assertThat(appender.getCountTaskExecuted().get()).isEqualTo(1);
            assertThat(errors)
                    .as("The exception thrown while creating the task should have been reported")
                    .isNotEmpty();

            // then the event lost to the failed task creation was also reported through the listener,
            // exactly as any other event discarded before ever reaching the delivery would be, instead of
            // only ever reaching the ErrorHandler
            assertThat(dropped).hasSize(1);
            assertThat(dropped.get(0).get("logmessage")).isEqualTo("would have been dropped");
            assertThat(droppedReasons.get(0))
                    .as("Reason should mention task creation: " + droppedReasons.get(0))
                    .contains("creation");
        }

        @Test
        @DisplayName("Reports an event whose task comes back null as dropped, as a task failing to be created")
        void testNullTaskIsReportedAsATaskCreationFailure() throws Exception {
            // given: createLogDeliveryTask() returning null, a subclass mistake
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(10)
                    .flushInterval(Duration.ofSeconds(10)).build();
            var handler = mock(LogDelivery.class);
            when(handler.getMetrics()).thenReturn(DeliveryMetrics.NONE);
            var droppedReasons = new CopyOnWriteArrayList<String>();
            when(handler.getListener()).thenReturn(new LogDeliveryListener() {
                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    droppedReasons.add(reason);
                }
            });
            var appender = new AbstractLogDeliveryAppender<>("appender-name", null, configuration) {
                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return handler;
                }

                @Override
                protected @Nullable Runnable createLogDeliveryTask(LogEvent loggingEvent) {
                    return null;
                }
            };
            var errors = new CopyOnWriteArrayList<Throwable>();
            appender.setHandler(recordingErrorHandler(errors));

            // when
            appender.start();
            assertThatNoException().isThrownBy(() -> appender.append(realLogEvent("without a task")));
            appender.stop(2, SECONDS);

            // then: counted and reported like any event lost before reaching the worker, rather than failing on
            // the worker without a trace in the metrics nor the listener
            assertThat(droppedReasons).containsExactly("log delivery task creation error");
            assertThat(appender.getMetrics().droppedDocuments()).isEqualTo(1);
            assertThat(errors).hasSize(1).first().isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Never reports an event as dropped once it has already been handed to the worker")
        void testEventAlreadyHandedOffIsNotReportedDropped() throws Exception {
            // given: an event severe enough to flush the batch right after it is delivered, and a delivery whose
            // flush fails, so that what fails is the step run *after* the event was delivered
            var configuration = TestLogConfiguration.builder().flushLevel(Level.ERROR).build();
            var appender = new LogDeliveryAppender("appender-name", null, configuration);
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
            doThrow(new IllegalStateException("Simulated flush failure")).when(appender.getLogDeliveryHandler()).flush();

            // when
            appender.start();
            assertThatNoException().isThrownBy(() -> appender.append(realLogEvent("delivered once", Level.ERROR)));
            awaitSize(errors, 1);
            appender.stop(2, SECONDS);

            // then: the failure is reported, but the event is not called dropped -- it reached the worker
            // and was delivered. onDocumentsDropped means "irrecoverably lost, your last chance to save it",
            // so a dead-letter sink would have filed a second copy of an event delivered normally
            assertThat(errors).as("The failure should still have been reported through the error handler").isNotEmpty();
            assertThat(appender.getCountTaskExecuted().get()).as("The event should have been processed").isEqualTo(1);
            assertThat(dropped).isEmpty();
        }

        @Test
        @DisplayName("Logs nothing of its own failures back into the appender, whatever fails while it delivers an event")
        void testFailuresOfThePipelineAreNotLoggedBackIntoIt() throws Exception {
            // given: an appender counting the events it is given, with everything failing around the one it gets:
            // its message does not format, one of its values does not render, each of its requests is refused,
            // and two of its three listeners throw, the third one handing it to a dead-letter appender once it is
            // given up on
            var configuration = TestLogConfiguration.builder()
                    .flushThreshold(1)
                    .flushInterval(Duration.ofMillis(20))
                    .maxConsecutiveFailures(2).build();
            var throwing = new LogDeliveryListener() {
                @Override
                public void onTransmissionFailed(int documentCount, @Nullable Throwable cause) {
                    throw new IllegalStateException("Simulated misbehaving listener");
                }

                @Override
                public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                    throw new IllegalStateException("Simulated misbehaving listener");
                }
            };
            var deadLetterAppender = RecordingAppender.started("dead-letters");
            var deadLetters = new DeadLetterListener(deadLetterAppender);
            var appended = new AtomicInteger();
            var appender = new AbstractLogDeliveryAppender<>("logged-back-into", null, configuration) {
                @Override
                public void append(LogEvent loggingEvent) {
                    appended.incrementAndGet();
                    super.append(loggingEvent);
                }

                @Override
                protected LogDelivery createLogDeliveryHandler() {
                    return new AbstractBatchLogDelivery<>(configuration, LogDeliveryListener.compose(throwing, throwing, deadLetters)) {
                        @Override
                        protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                            return CompletableFuture.completedFuture(TransmissionResult.failure(new IOException("Connection refused")));
                        }
                    };
                }
            };
            var message = mock(Message.class);
            when(message.getFormattedMessage()).thenThrow(new IllegalStateException("cannot format"));
            var contextData = new SortedArrayStringMap();
            contextData.putValue("payload", new Object() {
                @Override
                public String toString() {
                    throw new IllegalStateException("cannot render");
                }
            });
            var loggingEvent = Log4jLogEvent.newBuilder()
                    .setMessage(message)
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .setContextData(contextData)
                    .build();

            // and: that appender on the root logger of the context the classes of this library would log to, were
            // one of them to use an ordinary logger, which is where an application puts such an appender
            LoggerContext context = LoggerContext.getContext(false);
            appender.start();
            context.getConfiguration().getRootLogger().addAppender(appender, Level.ALL, null);
            context.updateLoggers();
            try {
                // when: the event is refused twice, given up on and saved, each failure on the way being logged
                appender.append(loggingEvent);
                long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
                while (deadLetterAppender.events().isEmpty()) {
                    assertThat(System.nanoTime()).as("The event should have been saved by now").isLessThan(deadlineNanos);
                    sleep(1);
                }
                appender.stop(2, SECONDS);
            } finally {
                context.getConfiguration().getRootLogger().removeAppender("logged-back-into");
                context.updateLoggers();
            }

            // then: the status logger of Log4j2 took every one of those logs, feeding nothing back. Through an
            // ordinary logger each would be one more event to deliver, whose own failures are logged in turn
            assertThat(appended).as("Events the appender was given").hasValue(1);
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
        protected LogDelivery createLogDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        protected Runnable createLogDeliveryTask(LogEvent loggingEvent) {
            return () -> countTaskExecuted.incrementAndGet();
        }

    }

    /**
     * An appender whose delivery implements only what {@link LogDelivery} requires, leaving its listener, health,
     * metrics and bounded close to the defaults, as a delivery not extending {@link AbstractBatchLogDelivery} may.
     */
    @Getter
    static class MinimalLogDeliveryAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
        AtomicInteger closeCount = new AtomicInteger();

        protected MinimalLogDeliveryAppender(String name, Filter filter, TestLogConfiguration configuration) {
            super(name, filter, configuration);
        }

        @Override
        protected LogDelivery createLogDeliveryHandler() {
            return new LogDelivery() {
                @Override
                public void send(@Nullable Map<String, Object> document) {
                    if (document != null) {
                        sent.add(document);
                    }
                }

                @Override
                public void send(@Nullable List<@Nullable Map<String, Object>> documents) {
                    if (documents != null) {
                        documents.forEach(this::send);
                    }
                }

                @Override
                public void flush() {
                    // Nothing batched
                }

                @Override
                public void close() {
                    closeCount.incrementAndGet();
                }
            };
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
        protected LogDelivery createLogDeliveryHandler() {
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
        protected LogDelivery createLogDeliveryHandler() {
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
    static class RecordingBlockingLogDeliveryAppender<C extends LogConfiguration> extends AbstractLogDeliveryAppender<C> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        CountDownLatch releaseFirstTask;
        List<String> processedMessages = new CopyOnWriteArrayList<>();

        protected RecordingBlockingLogDeliveryAppender(String name, Filter filter, C configuration,
                                                         CountDownLatch releaseFirstTask) {
            super(name, filter, configuration);
            this.releaseFirstTask = releaseFirstTask;
        }

        @Override
        protected LogDelivery createLogDeliveryHandler() {
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
        protected LogDelivery createLogDeliveryHandler() {
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

    /**
     * Records the events it processes and the closing of its delivery, each event taking the given time once the
     * appender began stopping, so that all of that time is spent within the stop, whatever ran before it.
     */
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
        protected LogDelivery createLogDeliveryHandler() {
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
                    while (isStarted()) {
                        sleep(1);
                    }
                    sleep(taskDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                events.add("send");
            };
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
        protected LogDelivery createLogDeliveryHandler() {
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
        protected LogDelivery createLogDeliveryHandler() {
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
        protected LogDelivery createLogDeliveryHandler() {
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
        protected LogDelivery createLogDeliveryHandler() {
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
        protected LogDelivery createLogDeliveryHandler() {
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
        protected LogDelivery createLogDeliveryHandler() {
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
