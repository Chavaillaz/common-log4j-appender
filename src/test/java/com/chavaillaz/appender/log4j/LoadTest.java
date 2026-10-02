package com.chavaillaz.appender.log4j;

import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.status.StatusConsoleListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.DeliveryMetrics;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;
import com.chavaillaz.appender.TransmissionResult;

/**
 * Loads a delivery, and an appender in front of one, with a downstream system timing out, to check that every
 * document is either delivered or reported dropped, exactly once, however the requests interleave.
 * <p>
 * The {@link StrugglingSystem} answers each request at random, as one under strain does: it accepts it, times it
 * out, rejects part of it, refuses it right away, or, when asked to, never answers it. The {@link Ledger} counts
 * every document the system accepted and every document reported dropped, by message.
 */
@DisplayName("Under load")
class LoadTest {

    private final StatusConsoleListener statusConsole = StatusLogger.getLogger().getFallbackListener();
    private Level statusLevel;
    private LoggerContext context;

    @BeforeEach
    void silenceStatusLogger() {
        // Thousands of failures are logged, each with its stack trace, which would bury the output of the build
        statusLevel = statusConsole.getStatusLevel();
        statusConsole.setLevel(Level.OFF);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.stop();
        }
        statusConsole.setLevel(statusLevel);
    }

    @Nested
    @DisplayName("Delivery")
    class DeliveryUnderLoad {

        private static final int DOCUMENTS = 50_000;

        @Test
        @DisplayName("Delivers or reports every document while the downstream system times out")
        void testEveryDocumentIsAccountedForWhileTimingOut() throws Exception {
            // given
            var ledger = new Ledger();
            var delivery = new StrugglingDelivery(configuration().build(), new StrugglingSystem(ledger, 0), ledger);

            // when: sent one by one, as the background thread of an appender does
            List<String> messages = sendAll(delivery);
            delivery.close();

            // then
            assertAccountedFor(ledger, messages, delivery.getMetrics());
            assertThat(ledger.dropReasons()).containsKey("maximum consecutive transmission failures exceeded");
        }

        @Test
        @DisplayName("Reports every document a downstream system never answering holds once closed")
        void testEveryDocumentIsAccountedForWhenRequestsAreNeverAnswered() throws Exception {
            // given: one request in 200 never answered, so that every transmission slot is soon held for good
            var ledger = new Ledger();
            var configuration = configuration().closeTimeout(Duration.ofSeconds(1)).build();
            var delivery = new StrugglingDelivery(configuration, new StrugglingSystem(ledger, 200), ledger);

            // when: sent from a thread of its own, which waits for a free slot for as long as the delivery is open
            var messages = new CompletableFuture<List<String>>();
            Thread sender = Thread.ofPlatform().start(() -> messages.complete(sendAll(delivery)));
            awaitEverySlotHeld(delivery);
            long start = System.nanoTime();
            delivery.close();
            long closeMillis = (System.nanoTime() - start) / 1_000_000;
            sender.join(SECONDS.toMillis(5));

            // then: the close kept to its timeout, and the sender, given up on, had what it still sent reported
            assertThat(closeMillis).isLessThan(3_000);
            assertAccountedFor(ledger, messages.get(5, SECONDS), delivery.getMetrics());
            assertThat(ledger.dropReasons()).containsKeys("still in flight when closed", "submitted after close");
        }

        /**
         * Sends {@link #DOCUMENTS} documents one by one, flushing every 500 as the periodic flush would.
         *
         * @return The message of each document sent
         */
        private static List<String> sendAll(LogDelivery delivery) {
            List<String> messages = new ArrayList<>(DOCUMENTS);
            for (int i = 0; i < DOCUMENTS; i++) {
                String message = "document " + i;
                messages.add(message);
                delivery.send(Map.of("logmessage", message));
                if (i % 500 == 0) {
                    delivery.flush();
                }
            }
            return messages;
        }

        /**
         * Waits until every transmission slot is held by a request never answered.
         */
        private static void awaitEverySlotHeld(StrugglingDelivery delivery) throws InterruptedException {
            int slots = delivery.getConfiguration().getDeliveryOptions().maxConcurrentTransmissions();
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(10);
            while (delivery.system.unanswered() < slots) {
                assertThat(System.nanoTime()).as("Every slot should be held by now").isLessThan(deadlineNanos);
                Thread.sleep(10);
            }
        }

    }

    @Nested
    @DisplayName("Appender")
    class AppenderUnderLoad {

        private static final int THREADS = 4;
        private static final int EVENTS_PER_THREAD = 12_500;

        @Test
        @DisplayName("Delivers or reports every event logged by concurrent threads while the downstream system times out")
        void testEveryEventIsAccountedForWhileTimingOut() throws Exception {
            // given: a queue small enough for a downstream system timing out to leave it full, evicting events
            var ledger = new Ledger();
            var appender = new StrugglingAppender(configuration().build(), new StrugglingSystem(ledger, 0), ledger);
            Logger logger = startContext(appender);

            // when: logged in bursts, at a pace the report of the evicted events keeps up with
            List<String> messages = logConcurrently(logger, 20);
            context.stop();

            // then
            assertAccountedFor(ledger, messages, appender.getMetrics());
            assertThat(ledger.dropReasons()).containsKey("internal queue capacity exceeded, discarding the oldest pending event");
        }

        @Test
        @DisplayName("Reports every event a downstream system never answering holds once stopped")
        void testEveryEventIsAccountedForWhenRequestsAreNeverAnswered() throws Exception {
            // given: one request in 100 never answered, which soon holds every transmission slot, the background
            // thread then waiting for one until the stop gives up on it
            var ledger = new Ledger();
            var configuration = configuration().closeTimeout(Duration.ofSeconds(1)).build();
            var appender = new StrugglingAppender(configuration, new StrugglingSystem(ledger, 100), ledger);
            Logger logger = startContext(appender);

            // when
            List<String> messages = logConcurrently(logger, 20);
            long start = System.nanoTime();
            context.stop();
            long stopMillis = (System.nanoTime() - start) / 1_000_000;

            // then: the stop kept to its budget, and reported what the queue, the batch and the requests held
            assertThat(stopMillis).isLessThan(3_000);
            assertAccountedFor(ledger, messages, appender.getMetrics());
            assertThat(ledger.dropReasons()).containsKeys("still in flight when closed",
                    "appender stopped before the event could be processed");
        }

        @Test
        @DisplayName("Counts every event it discards as dropped, those discarded faster than it reports them included")
        void testEveryEventIsCountedWhenSaturated() throws Exception {
            // given
            var ledger = new Ledger();
            var appender = new StrugglingAppender(configuration().build(), new StrugglingSystem(ledger, 0), ledger);
            Logger logger = startContext(appender);

            // when: logged as fast as the threads go, the events discarded possibly outpacing their report
            List<String> messages = logConcurrently(logger, 0);
            context.stop();

            // then: no event delivered or reported twice, and the metrics count those neither delivered nor reported
            List<String> unreported = ledger.misaccounted(messages);
            assertThat(unreported).allMatch(message -> message.endsWith("(0 times)"));
            DeliveryMetrics metrics = appender.getMetrics();
            assertThat(metrics.transmittedDocuments()).isEqualTo(ledger.deliveredCount());
            assertThat(metrics.droppedDocuments()).isEqualTo(ledger.droppedCount() + unreported.size());
            assertThat(ledger.deliveredCount() + metrics.droppedDocuments()).isEqualTo(messages.size());
        }

        /**
         * Logs {@link #EVENTS_PER_THREAD} events from each of {@link #THREADS} threads at once, each with a message
         * of its own.
         *
         * @param burst How many events a thread logs before pausing for a millisecond, {@code 0} never to pause
         * @return The message of each event logged
         */
        private static List<String> logConcurrently(Logger logger, int burst) throws InterruptedException {
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                threads.add(Thread.ofPlatform().name("load-" + t).start(() -> {
                    for (int i = 1; i <= EVENTS_PER_THREAD; i++) {
                        logger.info("event {}-{}", thread, i);
                        if (burst > 0 && i % burst == 0) {
                            LockSupport.parkNanos(MILLISECONDS.toNanos(1));
                        }
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join();
            }
            List<String> messages = new ArrayList<>(THREADS * EVENTS_PER_THREAD);
            for (int t = 0; t < THREADS; t++) {
                for (int i = 1; i <= EVENTS_PER_THREAD; i++) {
                    messages.add("event " + t + "-" + i);
                }
            }
            return messages;
        }

        /**
         * Starts a context of its own routing every event to the given appender, rather than using the context
         * the other tests share, as {@link PluginIntegrationTest} does.
         */
        private Logger startContext(StrugglingAppender appender) {
            ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
            // Applied to the status logger as the context starts, which would otherwise set it back to its default
            builder.setStatusLevel(Level.OFF);
            builder.setConfigurationName("LoadTest");
            builder.add(builder.newRootLogger(Level.INFO));
            context = new LoggerContext("LoadTest");
            context.start(builder.build());
            appender.start();
            context.getConfiguration().addAppender(appender);
            context.getConfiguration().getRootLogger().addAppender(appender, null, null);
            context.updateLoggers();
            return context.getLogger(LoadTest.class.getName());
        }

    }

    /**
     * Settings bringing every limit into play: batches split into requests, a bounded pending batch and queue,
     * documents given up on after three failures in a row, and a retry backoff.
     */
    private static TestLogConfiguration.Builder configuration() {
        return TestLogConfiguration.builder()
                .flushThreshold(100)
                .flushInterval(Duration.ofMillis(20))
                .maxRequestDocuments(20)
                .maxPendingDocuments(1_000)
                .maxQueuedEvents(1_000)
                .maxConsecutiveFailures(3)
                .retryBackoff(Duration.ofMillis(1))
                .maxRetryBackoff(Duration.ofMillis(4));
    }

    /**
     * Asserts that every message was delivered or reported dropped, exactly once, as the metrics count them.
     */
    private static void assertAccountedFor(Ledger ledger, List<String> messages, DeliveryMetrics metrics) {
        assertThat(ledger.misaccounted(messages)).as("Neither delivered nor reported dropped exactly once").isEmpty();
        assertThat(ledger.deliveredCount() + ledger.droppedCount()).isEqualTo(messages.size());
        assertThat(metrics.transmittedDocuments()).isEqualTo(ledger.deliveredCount());
        assertThat(metrics.droppedDocuments()).isEqualTo(ledger.droppedCount());
        assertThat(metrics.pendingDocuments()).isZero();
    }

    /**
     * Where each document ended up, counted by message: accepted by the downstream system, or reported dropped.
     */
    static final class Ledger implements LogDeliveryListener {

        private final Map<Object, Integer> delivered = new ConcurrentHashMap<>();
        private final Map<Object, Integer> dropped = new ConcurrentHashMap<>();
        private final Map<String, Integer> dropReasons = new ConcurrentHashMap<>();

        void recordDelivered(List<Map<String, Object>> documents) {
            documents.forEach(document -> delivered.merge(document.get("logmessage"), 1, Integer::sum));
        }

        @Override
        public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
            documents.forEach(document -> dropped.merge(document.get("logmessage"), 1, Integer::sum));
            dropReasons.merge(reason, documents.size(), Integer::sum);
        }

        /**
         * The given messages not accounted for exactly once, each with how many times it was.
         */
        List<String> misaccounted(List<String> messages) {
            List<String> misaccounted = new ArrayList<>();
            for (String message : messages) {
                int outcomes = delivered.getOrDefault(message, 0) + dropped.getOrDefault(message, 0);
                if (outcomes != 1) {
                    misaccounted.add(message + " (" + outcomes + " times)");
                }
            }
            return misaccounted;
        }

        long deliveredCount() {
            return delivered.values().stream().mapToLong(Integer::longValue).sum();
        }

        long droppedCount() {
            return dropped.values().stream().mapToLong(Integer::longValue).sum();
        }

        Map<String, Integer> dropReasons() {
            return dropReasons;
        }

    }

    /**
     * A downstream system under strain, answering each request a few milliseconds later, as an asynchronous
     * client completes its future: 70% accepted, 15% timed out, 10% half rejected, 5% refused right away. Every
     * given number of requests, one is never answered, as when the system silently drops the connection.
     */
    static final class StrugglingSystem {

        private final Ledger ledger;
        private final int unansweredEvery;
        private final AtomicInteger requests = new AtomicInteger();

        /**
         * Creates a downstream system recording the documents it accepts in the given ledger.
         *
         * @param ledger          The ledger recording the documents accepted
         * @param unansweredEvery Every how many requests one is never answered, or {@code 0} for none
         */
        StrugglingSystem(Ledger ledger, int unansweredEvery) {
            this.ledger = ledger;
            this.unansweredEvery = unansweredEvery;
        }

        CompletableFuture<TransmissionResult<Map<String, Object>>> receive(List<Map<String, Object>> documents) {
            int request = requests.incrementAndGet();
            var answer = new CompletableFuture<TransmissionResult<Map<String, Object>>>();
            if (unansweredEvery > 0 && request % unansweredEvery == 0) {
                return answer;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int outcome = random.nextInt(100);
            if (outcome < 5) {
                throw new IllegalStateException("Connection refused");
            } else if (outcome < 20) {
                delayedExecutor(random.nextInt(2, 5), MILLISECONDS)
                        .execute(() -> answer.completeExceptionally(new TimeoutException("Request timed out")));
            } else if (outcome < 30) {
                List<Map<String, Object>> accepted = new ArrayList<>();
                List<Map<String, Object>> rejected = new ArrayList<>();
                documents.forEach(document -> (random.nextBoolean() ? accepted : rejected).add(document));
                delayedExecutor(random.nextInt(2), MILLISECONDS).execute(() -> {
                    ledger.recordDelivered(accepted);
                    answer.complete(TransmissionResult.partialFailure(rejected, null));
                });
            } else {
                delayedExecutor(random.nextInt(2), MILLISECONDS).execute(() -> {
                    ledger.recordDelivered(documents);
                    answer.complete(TransmissionResult.success());
                });
            }
            return answer;
        }

        /**
         * How many requests were left unanswered so far.
         */
        int unanswered() {
            return unansweredEvery > 0 ? requests.get() / unansweredEvery : 0;
        }

    }

    /**
     * A delivery sending to a {@link StrugglingSystem}, reporting every document dropped to the ledger.
     */
    static final class StrugglingDelivery extends AbstractBatchLogDelivery<TestLogConfiguration> {

        private final StrugglingSystem system;

        StrugglingDelivery(TestLogConfiguration configuration, StrugglingSystem system, Ledger ledger) {
            super(configuration, ledger);
            this.system = system;
        }

        @Override
        protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
            return system.receive(documents);
        }

    }

    /**
     * An appender sending to a {@link StrugglingSystem}, reporting every document dropped to the ledger.
     */
    static final class StrugglingAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

        private final StrugglingSystem system;
        private final Ledger ledger;

        StrugglingAppender(TestLogConfiguration configuration, StrugglingSystem system, Ledger ledger) {
            super("struggling", null, configuration);
            this.system = system;
            this.ledger = ledger;
        }

        @Override
        protected LogDelivery createLogDeliveryHandler() {
            return new StrugglingDelivery(getLogConfiguration(), system, ledger);
        }

    }

}
