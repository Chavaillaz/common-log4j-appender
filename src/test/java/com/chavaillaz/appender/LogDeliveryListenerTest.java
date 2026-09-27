package com.chavaillaz.appender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LogDeliveryListener")
class LogDeliveryListenerTest {

    @Test
    @DisplayName("Composes listeners into one notifying each of them in turn, of every outcome")
    void testComposedListenersAreEachNotified() {
        // given
        var calls = new CopyOnWriteArrayList<String>();
        var composed = LogDeliveryListener.compose(new Recording("first", calls), new Recording("second", calls));

        // when
        composed.onTransmissionSucceeded(3, 10);
        composed.onTransmissionFailed(2, null);
        composed.onDocumentsDropped(List.of(Map.of("id", 1)), "reason");

        // then
        assertEquals(List.of(
                "first succeeded 3", "second succeeded 3",
                "first failed 2", "second failed 2",
                "first dropped 1", "second dropped 1"), calls);
    }

    @Test
    @DisplayName("Notifies the next listeners when one fails, then throws the first failure")
    void testFailingListenerDoesNotKeepTheOthersFromBeingNotified() {
        // given: a listener failing before the dead-letter sink, as a metrics registry closed early does
        var calls = new CopyOnWriteArrayList<String>();
        var failure = new IllegalStateException("registry closed");
        var secondFailure = new IllegalStateException("also failing");
        var composed = LogDeliveryListener.compose(
                new Failing(failure), new Recording("sink", calls), new Failing(secondFailure));

        // when
        var thrown = assertThrows(IllegalStateException.class,
                () -> composed.onDocumentsDropped(List.of(Map.of("id", 1)), "reason"));

        // then: the documents still reached the sink, and the caller learns of the failure as of any listener,
        // without the second one being added to the first, which a listener may throw again every time
        assertEquals(List.of("sink dropped 1"), calls);
        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    @DisplayName("Composes no listener into the no-op one, and a single listener into itself")
    void testComposingNoneOrOneListener() {
        var listener = new Recording("only", new CopyOnWriteArrayList<>());

        assertSame(LogDeliveryListener.NOOP, LogDeliveryListener.compose());
        assertSame(listener, LogDeliveryListener.compose(listener));
    }

    @Test
    @DisplayName("Rejects a null listener when composing rather than when notifying")
    void testRejectsNullListeners() {
        var listener = new Recording("only", new CopyOnWriteArrayList<>());

        assertThrows(NullPointerException.class, () -> LogDeliveryListener.compose(listener, null));
    }

    /**
     * Records every call it gets, named after the listener it stands for.
     */
    private record Recording(String name, List<String> calls) implements LogDeliveryListener {

        @Override
        public void onTransmissionSucceeded(int documentCount, long durationMillis) {
            calls.add(name + " succeeded " + documentCount);
        }

        @Override
        public void onTransmissionFailed(int documentCount, @Nullable Throwable cause) {
            calls.add(name + " failed " + documentCount);
        }

        @Override
        public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
            calls.add(name + " dropped " + documents.size());
        }

    }

    /**
     * Fails every call it gets with the same exception.
     */
    private record Failing(RuntimeException failure) implements LogDeliveryListener {

        @Override
        public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
            throw failure;
        }

    }

}
