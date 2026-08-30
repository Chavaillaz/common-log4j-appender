package com.chavaillaz.appender;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused tests proving {@link Batcher} is genuinely generic and transport-agnostic: unlike
 * {@link com.chavaillaz.appender.log4j.AbstractBatchLogDeliveryTest}, which exhaustively covers this same
 * accumulate/backpressure/retry behavior through the {@code Map<String,Object>}/Log4j2-flavored facade,
 * this class exercises {@link Batcher} directly, with a non-document element type and no Log4j2 concept in
 * sight, plus the supplier-based dynamic limits that facade relies on to preserve its own overridable
 * {@code getMaxPendingDocuments()}-style hooks.
 */
@DisplayName("Batcher")
class BatcherTest {

    @Test
    @DisplayName("Rejects a non-positive maximum number of concurrent transmissions at construction")
    void testRejectsNonPositiveMaxConcurrentTransmissions() {
        assertThrows(IllegalArgumentException.class,
                () -> new Batcher<>(() -> 1L, () -> 10, () -> 10, () -> 5, 0, "batcher-test-", "element",
                        elements -> true, BatcherListener.noop()));
    }

    @Test
    @DisplayName("Accumulates elements of an arbitrary type and transmits once the threshold is reached")
    void testAccumulatesAndTransmitsGenericElements() throws Exception {
        // given: a Batcher<String>, proving it has no dependency on any document/log4j type
        var transmitted = new CopyOnWriteArrayList<List<String>>();
        Batcher<String> batcher = batcher(2, elements -> {
            transmitted.add(elements);
            return true;
        }, BatcherListener.<String>noop());

        // when
        batcher.add(List.of("a"));
        batcher.add(List.of("b"));
        batcher.awaitPendingTransmissions();

        // then: threshold of 2 was reached by "a" + "b"
        assertEquals(1, transmitted.size());
        assertEquals(List.of("a", "b"), transmitted.get(0));

        // when: "c" alone is below the threshold, so it stays pending until explicitly flushed
        batcher.add(List.of("c"));
        assertEquals(1, transmitted.size());
        batcher.flush();
        batcher.awaitPendingTransmissions();

        // then
        assertEquals(2, transmitted.size());
        assertEquals(List.of("c"), transmitted.get(1));
    }

    @Test
    @DisplayName("Retries the batch on the next flush when a transmission fails")
    void testRetriesOnFailure() throws Exception {
        // given
        var attempts = new AtomicInteger();
        var succeed = new AtomicInteger(-1); // becomes true only once this many attempts have been made
        Batcher<String> batcher = batcher(1, elements -> attempts.incrementAndGet() > succeed.get(), BatcherListener.noop());
        succeed.set(1);

        // when
        batcher.add(List.of("poison"));
        batcher.awaitPendingTransmissions();
        batcher.flush(); // no-op: nothing pending yet, "poison" itself is still mid-retry cycle
        batcher.awaitPendingTransmissions();

        // then: the first attempt failed and was requeued, the second (triggered by the explicit flush
        // above, since threshold 1 alone would otherwise not retry a failing batch on every new element)
        // succeeded
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("Reports dropped elements through the listener once MaxConsecutiveFailures is exceeded")
    void testGivesUpAfterMaxConsecutiveFailures() throws Exception {
        // given
        var dropped = new CopyOnWriteArrayList<String>();
        BatcherListener<String> listener = new BatcherListener<>() {
            @Override
            public void onDropped(List<String> elements, String reason) {
                dropped.addAll(elements);
            }
        };
        int maxConsecutiveFailures = 2;
        Batcher<String> batcher = new Batcher<>(() -> 10L, () -> 10, () -> Integer.MAX_VALUE, () -> maxConsecutiveFailures, 4,
                "batcher-test-", "element", elements -> false, listener);
        batcher.add(List.of("poison"));

        // when: retried (via flush(), since the flush threshold of 10 is never reached on its own) until
        // the limit of consecutive failures is reached
        for (int i = 0; i < maxConsecutiveFailures; i++) {
            batcher.flush();
            batcher.awaitPendingTransmissions();
        }

        // then
        assertEquals(List.of("poison"), dropped);
    }

    @Test
    @DisplayName("Re-reads its dynamic limits on every call instead of capturing them once at construction")
    void testDynamicLimitsAreReReadOnEveryCall() throws Exception {
        // given: a maxPendingElements supplier whose return value changes after construction, exactly how
        // AbstractBatchLogDelivery.getMaxPendingDocuments() can be overridden by a subclass to vary at
        // runtime rather than being fixed once from LogConfiguration
        var dropped = new CopyOnWriteArrayList<String>();
        var maxPending = new AtomicInteger(10);
        BatcherListener<String> listener = new BatcherListener<>() {
            @Override
            public void onDropped(List<String> elements, String reason) {
                dropped.addAll(elements);
            }
        };
        Batcher<String> batcher = new Batcher<>(() -> 100L, maxPending::get, () -> Integer.MAX_VALUE, () -> 5, 4,
                "batcher-test-", "element", elements -> true, listener);

        // when: accumulating under the initial generous limit
        batcher.add(List.of("a", "b", "c"));

        // then: nothing discarded yet
        assertTrue(dropped.isEmpty());

        // when: the limit is lowered (as if the overriding method started returning a smaller value) and
        // another element is accumulated
        maxPending.set(1);
        batcher.add(List.of("d"));
        awaitDropped(dropped, 3);

        // then: the newly-lowered limit was honored immediately, without reconstructing the Batcher
        assertEquals(3, dropped.size());
    }

    private static Batcher<String> batcher(long flushThreshold, Function<List<String>, Boolean> transport, BatcherListener<String> listener) {
        return new Batcher<>(() -> flushThreshold, () -> 10_000, () -> Integer.MAX_VALUE, () -> 5, 4,
                "batcher-test-", "element", transport, listener);
    }

    private static void awaitDropped(List<String> dropped, int expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(2);
        while (dropped.size() < expected) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for " + expected + " dropped element(s)");
            }
            Thread.sleep(5);
        }
    }

}
