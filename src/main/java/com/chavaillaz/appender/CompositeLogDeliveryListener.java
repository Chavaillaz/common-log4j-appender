package com.chavaillaz.appender;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

/**
 * The listener {@link LogDeliveryListener#compose(LogDeliveryListener...)} returns, notifying each of the
 * listeners it combines in turn.
 */
final class CompositeLogDeliveryListener implements LogDeliveryListener {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link com.chavaillaz.appender.internal.Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    private final List<LogDeliveryListener> listeners;

    /**
     * Creates a listener notifying the given ones in turn.
     *
     * @param listeners The listeners to notify, in order
     */
    CompositeLogDeliveryListener(List<LogDeliveryListener> listeners) {
        this.listeners = listeners;
    }

    @Override
    public void onTransmissionSucceeded(int documentCount, long durationMillis) {
        notifyEach(listener -> listener.onTransmissionSucceeded(documentCount, durationMillis));
    }

    @Override
    public void onTransmissionFailed(int documentCount, @Nullable Throwable cause) {
        notifyEach(listener -> listener.onTransmissionFailed(documentCount, cause));
    }

    @Override
    public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
        notifyEach(listener -> listener.onDocumentsDropped(documents, reason));
    }

    /**
     * Notifies every listener, one failing not keeping the next ones from being notified. The first failure is
     * thrown once all were, as a single listener's would be, and the later ones logged: added to it as
     * suppressed, they would keep growing an exception a listener throws again on every call.
     *
     * @param notification The call to make on each listener
     */
    private void notifyEach(Consumer<LogDeliveryListener> notification) {
        Throwable firstFailure = null;
        for (LogDeliveryListener listener : listeners) {
            try {
                notification.accept(listener);
            } catch (Throwable e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    log.warn("Unhandled exception thrown by a LogDeliveryListener, after another one failed already", e);
                }
            }
        }
        switch (firstFailure) {
            case null -> {
                // Every listener was notified
            }
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            // A checked exception, which a listener can only throw by sneaking it past the compiler
            default -> throw new UndeclaredThrowableException(firstFailure);
        }
    }

}
