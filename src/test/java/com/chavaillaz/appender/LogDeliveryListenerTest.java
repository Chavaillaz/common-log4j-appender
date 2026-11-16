package com.chavaillaz.appender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
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
        assertThat(calls)
                .containsExactly(
                        "first succeeded 3", "second succeeded 3",
                        "first failed 2", "second failed 2",
                        "first dropped 1", "second dropped 1");
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
        var thrown = catchThrowableOfType(IllegalStateException.class,
                () -> composed.onDocumentsDropped(List.of(Map.of("id", 1)), "reason"));

        // then: the documents still reached the sink, and the caller learns of the failure as of any listener,
        // without the second one being added to the first, which a listener may throw again every time
        assertThat(calls).containsExactly("sink dropped 1");
        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("Throws an Error of a listener as it is, once the next ones were notified")
    void testFailingListenerErrorIsThrownAsItIs() {
        // given: a listener overflowing the stack, serializing a document containing itself for instance
        var calls = new CopyOnWriteArrayList<String>();
        var failure = new StackOverflowError();
        var composed = LogDeliveryListener.compose(new Failing(failure), new Recording("sink", calls));

        // when
        var thrown = catchThrowable(() -> composed.onDocumentsDropped(List.of(Map.of("id", 1)), "reason"));

        // then
        assertThat(calls).containsExactly("sink dropped 1");
        assertThat(thrown).isSameAs(failure);
    }

    @Test
    @DisplayName("Wraps a checked exception a listener sneaks past the compiler, once the next ones were notified")
    void testFailingListenerCheckedExceptionIsWrapped() {
        // given: a listener throwing a checked exception none of its methods declares
        var calls = new CopyOnWriteArrayList<String>();
        var failure = new IOException("disk full");
        var composed = LogDeliveryListener.compose(new Failing(failure), new Recording("sink", calls));

        // when
        var thrown = catchThrowable(() -> composed.onDocumentsDropped(List.of(Map.of("id", 1)), "reason"));

        // then: the caller gets an unchecked exception, as a proxy throws, the failure being its cause
        assertThat(calls).containsExactly("sink dropped 1");
        assertThat(thrown).isInstanceOf(UndeclaredThrowableException.class).cause().isSameAs(failure);
    }

    @Test
    @DisplayName("Composes no listener into the no-op one, and a single listener into itself")
    void testComposingNoneOrOneListener() {
        var listener = new Recording("only", new CopyOnWriteArrayList<>());

        assertThat(LogDeliveryListener.compose()).isSameAs(LogDeliveryListener.NOOP);
        assertThat(LogDeliveryListener.compose(listener)).isSameAs(listener);
    }

    @Test
    @DisplayName("Rejects a null listener when composing rather than when notifying, naming the argument")
    void testRejectsNullListeners() {
        var listener = new Recording("only", new CopyOnWriteArrayList<>());

        assertThatThrownBy(() -> LogDeliveryListener.compose(listener, null))
                .isInstanceOf(NullPointerException.class).hasMessage("listeners must not contain null");
        assertThatThrownBy(() -> LogDeliveryListener.compose((LogDeliveryListener[]) null))
                .isInstanceOf(NullPointerException.class).hasMessage("listeners must not be null");
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
     * Fails every call it gets with the same failure, a checked exception included, thrown without being declared.
     */
    private record Failing(Throwable failure) implements LogDeliveryListener {

        @Override
        public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
            Failing.<RuntimeException>sneakyThrow(failure);
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
            throw (E) failure;
        }

    }

}
