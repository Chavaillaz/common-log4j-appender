package com.chavaillaz.appender;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.Filterable;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

/**
 * A ready-made {@link LogDeliveryListener} handing every irrecoverably dropped document to a Log4j2 appender, one
 * event per document whose message is a line of JSON, as a last resort so that data a {@link LogDelivery} gave up
 * on is not lost outright. Every other method is a no-op: compose it with another listener (see
 * {@link LogDeliveryListener#compose(LogDeliveryListener...)}) to observe transmissions as well.
 * <p>
 * The appender decides where the lines go, and for how long: a {@code RollingFile} writing the message alone
 * ({@code %m%n}) keeps newline-delimited JSON, rotated and compressed as it is told, with the permissions of its
 * {@code filePermissions} and the retention of its rollover strategy. An appender built on this library creates
 * this listener for the appender nested in its element (see
 * {@link com.chavaillaz.appender.log4j.LogDeliveryAppenderBuilder}).
 * <p>
 * Each line wraps the document, unmodified, with the reason it was dropped and when:
 * {@code {"reason":"...","droppedAt":"...","document":{...}}}. Its event has the level {@code ERROR}, the name of
 * this class as logger, and the time of the drop. The JSON is written without any dependency: {@code null},
 * {@link Boolean}, {@link Number}, {@link Map}, {@link Iterable} and array values as their JSON counterpart, the
 * latter three recursively and keys in their own iteration order, and anything else, {@link String} included, as
 * a JSON string of its {@link String#valueOf(Object)} rendering. A number JSON cannot express ({@link Double#NaN},
 * an infinity, a custom {@link Number} rendering otherwise) is written as a string, and nesting is followed to a
 * bounded depth, so that no value can leave a line a parser cannot read. A value that cannot be rendered, one
 * whose {@code toString()} throws for instance, is replaced by a string naming the failure, the rest of its
 * document being saved.
 * <p>
 * The documents the appender does not take, being stopped or failing, go to
 * {@link #onAppendFailure(List, String, Throwable)}, which logs them by default, rather than a failure being
 * thrown into the pipeline. A filter of the appender is honored, as Log4j2 honors it.
 */
public class DeadLetterListener implements LogDeliveryListener {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link com.chavaillaz.appender.internal.Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    private final Appender appender;

    /**
     * Tells when a document is dropped.
     */
    private final Clock clock;

    /**
     * Creates a listener handing the dropped documents to the given appender.
     *
     * @param appender The appender, started, which is left to its owner to start and stop
     * @throws NullPointerException If the appender is {@code null}
     */
    public DeadLetterListener(Appender appender) {
        this(appender, Clock.systemUTC());
    }

    /**
     * Creates a listener handing the dropped documents to the given appender.
     *
     * @param appender The appender, started, which is left to its owner to start and stop
     * @param clock    The clock telling when a document is dropped
     * @throws NullPointerException If the appender or the clock is {@code null}
     */
    public DeadLetterListener(Appender appender, Clock clock) {
        this.appender = Objects.requireNonNull(appender, "appender must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The appender the dropped documents are handed to.
     *
     * @return The appender
     */
    public Appender getAppender() {
        return appender;
    }

    /**
     * {@inheritDoc}
     *
     * @param documents The documents being discarded
     * @param reason    A short, human-readable explanation of why the documents were discarded
     */
    @Override
    public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
        Instant droppedAt = clock.instant();
        for (int i = 0; i < documents.size(); i++) {
            if (!appender.isStarted()) {
                onAppendFailure(documents.subList(i, documents.size()), reason, null);
                return;
            }
            LogEvent event = Log4jLogEvent.newBuilder()
                    .setLoggerName(DeadLetterListener.class.getName())
                    .setLoggerFqcn(DeadLetterListener.class.getName())
                    .setLevel(Level.ERROR)
                    .setMessage(new SimpleMessage(JsonWriter.toJson(wrap(documents.get(i), reason, droppedAt))))
                    .setTimeMillis(droppedAt.toEpochMilli())
                    .build();
            if (appender instanceof Filterable filterable && filterable.isFiltered(event)) {
                continue;
            }
            try {
                appender.append(event);
            } catch (Throwable e) {
                // Throwable: an appender failing, a class it needs missing as the JVM shuts down for instance, must
                // not escape into the pipeline. Those left are not handed to it, failing in all likelihood too
                onAppendFailure(documents.subList(i, documents.size()), reason, e);
                return;
            }
        }
    }

    /**
     * Wraps the given document with the reason it was dropped and when, without modifying it, so that a field
     * of the document bearing the same name is kept.
     *
     * @param document  The document being dropped
     * @param reason    A short, human-readable explanation of why it was dropped
     * @param droppedAt The instant it was dropped at
     * @return The document, wrapped with its drop metadata
     */
    private static Map<String, Object> wrap(Map<String, Object> document, String reason, Instant droppedAt) {
        Map<String, Object> wrapped = new LinkedHashMap<>(3);
        wrapped.put("reason", reason);
        wrapped.put("droppedAt", droppedAt.toString());
        wrapped.put("document", document);
        return wrapped;
    }

    /**
     * Called with the dropped documents the appender did not take, logging them along with the failure by
     * default, so that they at least appear somewhere. Override to fall back to another sink.
     *
     * @param documents The documents the appender did not take: the one it failed on, and those after it
     * @param reason    The reason they were originally dropped
     * @param cause     The failure of the appender, or {@code null} if it was not started
     */
    protected void onAppendFailure(List<Map<String, Object>> documents, String reason, @Nullable Throwable cause) {
        log.error("Unable to hand {} dropped document(s) (originally dropped because: {}) to the appender {}, "
                        + "which is {}: {}", documents.size(), reason, appender.getName(),
                cause != null ? "failing" : "not started", documents, cause);
    }

}
