package com.chavaillaz.appender.log4j;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.status.StatusConsoleListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;
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
 * <p>
 * The answers are drawn from a seed, which every failure gives: {@code -DloadTest.seed} replays them, though not
 * the way the threads interleaved. What a test expects does not depend on how fast the machine runs it: the
 * threads logging to an appender wait for the events it discarded to be reported, and the system holds its
 * answers until the first one is.
 */
@DisplayName("Under load")
class LoadTest {

    /**
     * Seeds the answers of the downstream system, a new one for each test unless given.
     */
    private final long seed = Long.getLong("loadTest.seed", System.nanoTime());

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
            var delivery = new StrugglingDelivery(configuration().build(), new StrugglingSystem(ledger, seed, 0), ledger);

            // when: sent one by one, as the background thread of an appender does
            List<String> messages = sendAll(delivery);
            delivery.close();

            // then
            assertAccountedFor(ledger, messages, delivery.getMetrics());
            assertThat(ledger.dropReasons())
                    .as(replayable("Reasons of the drops"))
                    .containsKey("maximum consecutive transmission failures exceeded");
        }

        @Test
        @DisplayName("Reports every document a downstream system never answering holds once closed")
        void testEveryDocumentIsAccountedForWhenRequestsAreNeverAnswered() throws Exception {
            // given: one request in 200 never answered, so that every transmission slot is soon held for good
            var ledger = new Ledger();
            var configuration = configuration().closeTimeout(Duration.ofSeconds(1)).build();
            var delivery = new StrugglingDelivery(configuration, new StrugglingSystem(ledger, seed, 200), ledger);

            // when: sent from a thread of its own, which waits for a free slot for as long as the delivery is open
            var messages = new CompletableFuture<List<String>>();
            Thread sender = Thread.ofPlatform().start(() -> messages.complete(sendAll(delivery)));
            awaitEverySlotHeld(delivery);
            long start = System.nanoTime();
            delivery.close();
            long closeMillis = (System.nanoTime() - start) / 1_000_000;
            sender.join(SECONDS.toMillis(5));

            // then: the close kept to its timeout, and the sender, given up on, had what it still sent reported
            assertThat(closeMillis).as(replayable("Time the close took, in milliseconds")).isLessThan(3_000);
            assertAccountedFor(ledger, messages.get(5, SECONDS), delivery.getMetrics());
            assertThat(ledger.dropReasons())
                    .as(replayable("Reasons of the drops"))
                    .containsKeys("still in flight when closed", "submitted after close");
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
        private void awaitEverySlotHeld(StrugglingDelivery delivery) throws InterruptedException {
            int slots = delivery.getConfiguration().getDeliveryOptions().maxConcurrentTransmissions();
            long deadlineNanos = System.nanoTime() + SECONDS.toNanos(10);
            while (delivery.system.unanswered() < slots) {
                assertThat(System.nanoTime()).as(replayable("Every slot should be held by now")).isLessThan(deadlineNanos);
                Thread.sleep(10);
            }
        }

    }

    @Nested
    @DisplayName("Appender")
    class AppenderUnderLoad {

        private static final int THREADS = 4;
        private static final int EVENTS_PER_THREAD = 12_500;
        private static final int BURST = 20;
        private static final String EVICTED = "internal queue capacity exceeded, discarding the oldest pending event";

        @Test
        @DisplayName("Delivers or reports every event logged by concurrent threads while the downstream system times out")
        void testEveryEventIsAccountedForWhileTimingOut() throws Exception {
            // given: a queue small enough for a downstream system timing out to leave it full, evicting events. The
            // system holds its answers until a first event is evicted, the worker waiting for one meanwhile, so that
            // some are however slowly the threads log
            var ledger = new Ledger();
            var system = new StrugglingSystem(ledger, seed, 0);
            system.holdAnswers();
            ledger.whenFirstDropped(EVICTED, system::releaseAnswers);
            var appender = new StrugglingAppender(configuration().build(), system, ledger);
            Logger logger = startContext(appender);

            // when: logged in bursts, no faster than the evicted events are reported
            List<String> messages = logConcurrently(logger, reportKeepingUp(appender, ledger));
            context.stop();

            // then
            assertAccountedFor(ledger, messages, appender.getMetrics());
            assertThat(ledger.dropReasons()).as(replayable("Reasons of the drops")).containsKey(EVICTED);
        }

        @Test
        @DisplayName("Reports every event a downstream system never answering holds once stopped")
        void testEveryEventIsAccountedForWhenRequestsAreNeverAnswered() throws Exception {
            // given: one request in 100 never answered, which soon holds every transmission slot, the background
            // thread then waiting for one until the stop gives up on it
            var ledger = new Ledger();
            var configuration = configuration().closeTimeout(Duration.ofSeconds(1)).build();
            var appender = new StrugglingAppender(configuration, new StrugglingSystem(ledger, seed, 100), ledger);
            Logger logger = startContext(appender);

            // when
            List<String> messages = logConcurrently(logger, reportKeepingUp(appender, ledger));
            long start = System.nanoTime();
            context.stop();
            long stopMillis = (System.nanoTime() - start) / 1_000_000;

            // then: the stop kept to its budget, and reported what the queue, the batch and the requests held
            assertThat(stopMillis).as(replayable("Time the stop took, in milliseconds")).isLessThan(3_000);
            assertAccountedFor(ledger, messages, appender.getMetrics());
            assertThat(ledger.dropReasons())
                    .as(replayable("Reasons of the drops"))
                    .containsKeys("still in flight when closed", "appender stopped before the event could be processed");
        }

        @Test
        @DisplayName("Counts every event it discards as dropped, those discarded faster than it reports them included")
        void testEveryEventIsCountedWhenSaturated() throws Exception {
            // given
            var ledger = new Ledger();
            var appender = new StrugglingAppender(configuration().build(), new StrugglingSystem(ledger, seed, 0), ledger);
            Logger logger = startContext(appender);

            // when: logged as fast as the threads go, the events discarded possibly outpacing their report
            List<String> messages = logConcurrently(logger, () -> {
                // No pause between two bursts
            });
            context.stop();

            // then: no event delivered or reported twice, and the metrics count those neither delivered nor reported
            List<String> unreported = ledger.misaccounted(messages);
            assertThat(unreported).as(replayable("Events not accounted for once")).allMatch(message -> message.endsWith("(0 times)"));
            DeliveryMetrics metrics = appender.getMetrics();
            assertThat(metrics.transmittedDocuments()).as(replayable("Documents transmitted")).isEqualTo(ledger.deliveredCount());
            assertThat(metrics.droppedDocuments())
                    .as(replayable("Documents dropped"))
                    .isEqualTo(ledger.droppedCount() + unreported.size());
            assertThat(ledger.deliveredCount() + metrics.droppedDocuments())
                    .as(replayable("Documents delivered or dropped"))
                    .isEqualTo(messages.size());
        }

        /**
         * What a thread logging in bursts does between two of them: it pauses for a millisecond, then for as long as
         * the events discarded and not reported yet take more than half of the room they have to wait in. Past that
         * room they are only counted, which the machine running the test would otherwise decide.
         */
        private static Runnable reportKeepingUp(StrugglingAppender appender, Ledger ledger) {
            int room = appender.getLogConfiguration().getDeliveryOptions().maxQueuedEvents();
            return () -> {
                long deadlineNanos = System.nanoTime() + SECONDS.toNanos(10);
                do {
                    LockSupport.parkNanos(MILLISECONDS.toNanos(1));
                } while (appender.getMetrics().droppedDocuments() - ledger.reportedCount() > room / 2
                        && System.nanoTime() < deadlineNanos);
            };
        }

        /**
         * Logs {@link #EVENTS_PER_THREAD} events from each of {@link #THREADS} threads at once, each with a message
         * of its own, in bursts of {@link #BURST}.
         *
         * @param betweenBursts What a thread does once it logged a burst
         * @return The message of each event logged
         */
        private static List<String> logConcurrently(Logger logger, Runnable betweenBursts) throws InterruptedException {
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                threads.add(Thread.ofPlatform().name("load-" + t).start(() -> {
                    for (int i = 1; i <= EVENTS_PER_THREAD; i++) {
                        logger.info("event {}-{}", thread, i);
                        if (i % BURST == 0) {
                            betweenBursts.run();
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
     * Completes the description of an assertion with the seed its failure can be replayed with.
     */
    private String replayable(String description) {
        return description + " (answers replayed with -DloadTest.seed=" + seed + ")";
    }

    /**
     * Asserts that every message was delivered or reported dropped, exactly once, as the metrics count them.
     */
    private void assertAccountedFor(Ledger ledger, List<String> messages, DeliveryMetrics metrics) {
        assertThat(ledger.misaccounted(messages))
                .as(replayable("Neither delivered nor reported dropped exactly once"))
                .isEmpty();
        assertThat(ledger.deliveredCount() + ledger.droppedCount())
                .as(replayable("Documents delivered or reported dropped"))
                .isEqualTo(messages.size());
        assertThat(metrics.transmittedDocuments()).as(replayable("Documents transmitted")).isEqualTo(ledger.deliveredCount());
        assertThat(metrics.droppedDocuments()).as(replayable("Documents dropped")).isEqualTo(ledger.droppedCount());
        assertThat(metrics.pendingDocuments()).as(replayable("Documents pending")).isZero();
    }

    /**
     * Where each document ended up, counted by message: accepted by the downstream system, or reported dropped.
     */
    static final class Ledger implements LogDeliveryListener {

        private final Map<Object, Integer> delivered = new ConcurrentHashMap<>();
        private final Map<Object, Integer> dropped = new ConcurrentHashMap<>();
        private final Map<String, Integer> dropReasons = new ConcurrentHashMap<>();
        private final LongAdder reported = new LongAdder();
        private final AtomicReference<@Nullable Runnable> firstDrop = new AtomicReference<>();
        private volatile @Nullable String firstDropReason;

        void recordDelivered(List<Map<String, Object>> documents) {
            documents.forEach(document -> delivered.merge(document.get("logmessage"), 1, Integer::sum));
        }

        @Override
        public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
            documents.forEach(document -> dropped.merge(document.get("logmessage"), 1, Integer::sum));
            dropReasons.merge(reason, documents.size(), Integer::sum);
            reported.add(documents.size());
            if (reason.equals(firstDropReason)) {
                Runnable action = firstDrop.getAndSet(null);
                if (action != null) {
                    action.run();
                }
            }
        }

        /**
         * Runs the given action once, when documents are first reported dropped for the given reason.
         */
        void whenFirstDropped(String reason, Runnable action) {
            firstDrop.set(action);
            firstDropReason = reason;
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

        /**
         * How many documents were reported dropped so far, a document reported twice counting twice.
         */
        long reportedCount() {
            return reported.sum();
        }

        Map<String, Integer> dropReasons() {
            return dropReasons;
        }

    }

    /**
     * A downstream system under strain, answering each request a few milliseconds later, as an asynchronous
     * client completes its future: 70% accepted, 15% timed out, 10% half rejected, 5% refused right away. Every
     * given number of requests, one is never answered, as when the system silently drops the connection. The
     * answer to a request is drawn from the seed and the number of that request.
     */
    static final class StrugglingSystem {

        private final Ledger ledger;
        private final long seed;
        private final int unansweredEvery;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile CompletableFuture<Void> answering = completedFuture(null);

        /**
         * Creates a downstream system recording the documents it accepts in the given ledger.
         *
         * @param ledger          The ledger recording the documents accepted
         * @param seed            The seed its answers are drawn from
         * @param unansweredEvery Every how many requests one is never answered, or {@code 0} for none
         */
        StrugglingSystem(Ledger ledger, long seed, int unansweredEvery) {
            this.ledger = ledger;
            this.seed = seed;
            this.unansweredEvery = unansweredEvery;
        }

        /**
         * Holds back every answer but a refusal, given right away, until {@link #releaseAnswers()}: the requests
         * keep their transmission slots meanwhile.
         */
        void holdAnswers() {
            answering = new CompletableFuture<>();
        }

        /**
         * Gives the answers held back since {@link #holdAnswers()}, and each later one as it comes.
         */
        void releaseAnswers() {
            answering.complete(null);
        }

        CompletableFuture<TransmissionResult<Map<String, Object>>> receive(List<Map<String, Object>> documents) {
            int request = requests.incrementAndGet();
            var answer = new CompletableFuture<TransmissionResult<Map<String, Object>>>();
            if (unansweredEvery > 0 && request % unansweredEvery == 0) {
                return answer;
            }
            var random = new SplittableRandom(seed + request);
            int outcome = random.nextInt(100);
            if (outcome < 5) {
                throw new IllegalStateException("Connection refused");
            } else if (outcome < 20) {
                answerAfter(random.nextInt(2, 5),
                        () -> answer.completeExceptionally(new TimeoutException("Request timed out")));
            } else if (outcome < 30) {
                List<Map<String, Object>> accepted = new ArrayList<>();
                List<Map<String, Object>> rejected = new ArrayList<>();
                documents.forEach(document -> (random.nextBoolean() ? accepted : rejected).add(document));
                answerAfter(random.nextInt(2), () -> {
                    ledger.recordDelivered(accepted);
                    answer.complete(TransmissionResult.partialFailure(rejected, null));
                });
            } else {
                answerAfter(random.nextInt(2), () -> {
                    ledger.recordDelivered(documents);
                    answer.complete(TransmissionResult.success());
                });
            }
            return answer;
        }

        /**
         * Gives an answer the given time after the request, or after the answers held back are released.
         */
        private void answerAfter(int delayMillis, Runnable answer) {
            answering.thenRunAsync(answer, delayedExecutor(delayMillis, MILLISECONDS));
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
