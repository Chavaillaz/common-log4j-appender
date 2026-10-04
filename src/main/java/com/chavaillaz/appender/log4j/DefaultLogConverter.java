package com.chavaillaz.appender.log4j;

import java.io.PrintWriter;
import java.io.Serial;
import java.io.Writer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogConfiguration;

/**
 * Default converter, writing the following fields:
 * <ul>
 *     <li><strong>datetime:</strong> ISO-8601 instant of the logging event</li>
 *     <li><strong>host</strong>, <strong>environment</strong> and <strong>application:</strong> Taken from the
 *     configuration</li>
 *     <li><strong>logger:</strong> Name of the logger, or {@link LogEvent#getLoggerFqcn()} without one</li>
 *     <li><strong>level:</strong> Level of the logging event</li>
 *     <li><strong>logmessage:</strong> Formatted message, truncated to {@link #getMaxMessageLength()} characters</li>
 *     <li><strong>thread:</strong> Name of the thread that created the logging event</li>
 * </ul>
 * The MDC entries are added as well, without overriding any of these fields, even one left {@code null}. If the
 * event carries an exception:
 * <ul>
 *     <li><strong>class:</strong> Name of the class of the exception</li>
 *     <li><strong>stacktrace:</strong> Stack trace, truncated to {@link #getMaxStackTraceLength()} characters</li>
 * </ul>
 * A value that cannot be read, a message failing to format for instance, costs its own field, never the
 * document.
 */
public class DefaultLogConverter implements LogConverter {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link com.chavaillaz.appender.internal.Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    /**
     * Number of fields written by {@link #writeBasic(Map, LogEvent)}.
     */
    private static final int BASIC_FIELD_COUNT = 8;

    /**
     * Number of fields written by {@link #writeThrowable(Map, LogEvent)} when an exception is present.
     */
    private static final int THROWABLE_FIELD_COUNT = 2;

    /**
     * Most MDC entries a document is sized for up front, far more than a log event usually carries: the
     * {@code size()} of a custom context data is application code, whose wrong answer must not size a map
     * beyond what memory holds.
     */
    private static final int MAX_PRESIZED_MDC_FIELDS = 64;

    /**
     * The configuration the host, environment and application of each document are taken from.
     */
    private final LogConfiguration configuration;

    /**
     * Creates a new default log converter.
     *
     * @param configuration The configuration the host, environment and application of each document are
     *                      taken from
     */
    public DefaultLogConverter(LogConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * @param event The logging event
     * @return The document to send, never {@code null}
     */
    @Override
    public Map<String, Object> convert(LogEvent event) {
        Map<String, Object> document = HashMap.newHashMap(expectedFieldCount(event));
        writeBasic(document, event);
        writeThrowable(document, event);
        writeMDC(document, event);
        return document;
    }

    /**
     * Counts the fields this converter is expected to write for the given event, so that the document is not
     * resized while being populated, unless its MDC is unusually large.
     *
     * @param event The logging event
     * @return The expected number of fields, never negative
     */
    private int expectedFieldCount(LogEvent event) {
        int fields = BASIC_FIELD_COUNT;
        if (event.getThrown() != null) {
            fields += THROWABLE_FIELD_COUNT;
        }
        ReadOnlyStringMap contextData = event.getContextData();
        if (contextData != null) {
            try {
                // A larger one only resizes the document while it is populated
                fields += Math.clamp(contextData.size(), 0, MAX_PRESIZED_MDC_FIELDS);
            } catch (Throwable e) {
                // Throwable, a custom context data running code of the application: left to writeMDC(),
                // which reports it failing, the document only resizing meanwhile
            }
        }
        return fields;
    }

    /**
     * Writes the basic fields of the logging event in the given document.
     *
     * @param document The document to write the fields into
     * @param event    The logging event
     */
    protected void writeBasic(Map<String, Object> document, LogEvent event) {
        String host = null;
        String environment = null;
        String application = null;
        try {
            host = configuration.getHost();
            environment = configuration.getEnvironment();
            application = configuration.getApplication();
        } catch (Throwable e) {
            // A configuration resolving a value on demand can fail: the fields read before are kept
            log.warn("Unable to read the host/environment/application from the configuration, "
                    + "the fields it did not provide will be omitted", e);
        }
        document.put("datetime", Instant.ofEpochMilli(event.getInstant().getEpochMillisecond()).toString());
        document.put("host", host);
        document.put("environment", environment);
        document.put("application", application);
        String loggerName = event.getLoggerName();
        document.put("logger", loggerName != null ? loggerName : event.getLoggerFqcn());
        Level level = event.getLevel();
        document.put("level", level != null ? level.toString() : null);
        document.put("logmessage", formatMessage(event.getMessage()));
        document.put("thread", event.getThreadName());
    }

    /**
     * Formats the given message, or writes a placeholder naming the failure when a custom {@link Message}
     * throws while formatting.
     *
     * @param message The message to format, or {@code null}
     * @return The formatted message, {@code null} if the given message was {@code null}, or a placeholder
     *         describing the failure if formatting it threw
     */
    private @Nullable String formatMessage(@Nullable Message message) {
        if (message == null) {
            return null;
        }
        try {
            return truncate(message.getFormattedMessage(), getMaxMessageLength());
        } catch (Throwable e) {
            // Throwable: a message built from a structure referring to itself overflows the stack
            log.warn("Unable to format the message of a logging event, using a placeholder instead", e);
            return "<unable to format message: " + describe(e) + ">";
        }
    }

    /**
     * Describes a failure of application code by its {@link Throwable#toString()}, or by its class name when that
     * throws too, being application code as well.
     *
     * @param failure The failure to describe
     * @return Its description
     */
    private static String describe(Throwable failure) {
        try {
            return failure.toString();
        } catch (Throwable e) {
            return failure.getClass().getName();
        }
    }

    /**
     * Truncates the given value to at most {@code maxLength} characters, appending how much was cut off so that
     * it is not mistaken for a complete value. Cuts one character earlier rather than between the two halves
     * of a surrogate pair, such as an emoji, a half not being a character any encoding accepts.
     *
     * @param value     The value to truncate, or {@code null}
     * @param maxLength The maximum length allowed, a non-positive value disabling truncation
     * @return The value, truncated if it was longer than allowed
     */
    private static @Nullable String truncate(@Nullable String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        int keep = cutIndex(value, maxLength);
        int truncatedChars = value.length() - keep;
        return value.substring(0, keep) + "... (truncated, " + truncatedChars + " more characters)";
    }

    /**
     * Where to cut the given value, longer than {@code maxLength}, so that it keeps at most that many characters:
     * one character earlier rather than between the two halves of a surrogate pair.
     *
     * @param value     The value to cut, longer than {@code maxLength}
     * @param maxLength The maximum length to keep, positive
     * @return The number of characters to keep
     */
    private static int cutIndex(String value, int maxLength) {
        return Character.isHighSurrogate(value.charAt(maxLength - 1)) && Character.isLowSurrogate(value.charAt(maxLength))
                ? maxLength - 1
                : maxLength;
    }

    /**
     * Writes the MDC fields of the logging event in the given document, never in place of a field written
     * already, even one left {@code null}: a document keeps the same shape whatever the MDC of its event holds.
     * A custom context data failing while iterated only costs its own fields.
     *
     * @param document The document to write the fields into
     * @param event    The logging event
     */
    protected void writeMDC(Map<String, Object> document, LogEvent event) {
        ReadOnlyStringMap contextData = event.getContextData();
        if (contextData == null) {
            return;
        }
        try {
            contextData.forEach((key, value) -> {
                // Rather than putIfAbsent(), which replaces a null value
                if (!document.containsKey(key)) {
                    document.put(key, value);
                }
            });
        } catch (Throwable e) {
            // Throwable, iterating a custom context data running code of the application
            log.warn("Unable to read the MDC context data of a logging event, it will be omitted", e);
        }
    }

    /**
     * Writes the exception of the logging event in the given document, which a stack trace failing to print
     * only costs its own field.
     *
     * @param document The document to write the fields into
     * @param event    The logging event
     */
    protected void writeThrowable(Map<String, Object> document, LogEvent event) {
        Throwable throwable = event.getThrown();
        if (throwable != null) {
            document.put("class", className(throwable.getClass()));
            try {
                document.put("stacktrace", getStackTrace(throwable));
            } catch (Throwable e) {
                // Throwable: printStackTrace() walks the causes by recursion, which a deep chain overflows
                log.warn("Unable to compute the stack trace of a logging event, the field will be omitted", e);
            }
        }
    }

    /**
     * Names the class of a logged exception: its canonical name, or its binary name for a class without one,
     * such as an anonymous or local class.
     *
     * @param type The class of the logged exception
     * @return A non-{@code null} name for it
     */
    private static String className(Class<?> type) {
        String canonicalName = type.getCanonicalName();
        return canonicalName != null ? canonicalName : type.getName();
    }

    /**
     * Gets the stack trace of the given throwable, truncated to {@link #getMaxStackTraceLength()} characters so
     * that a deep cause chain cannot inflate a document, and the batch holding it, past what the downstream
     * system accepts. Printing stops right past that length, rather than rendering every frame to cut them.
     *
     * @param throwable The throwable
     * @return The stack trace
     */
    protected String getStackTrace(Throwable throwable) {
        int maxLength = getMaxStackTraceLength();
        BoundedWriter writer = new BoundedWriter(maxLength);
        try {
            throwable.printStackTrace(new PrintWriter(writer));
        } catch (LimitReached e) {
            // What is left would only be cut
        }
        String printed = writer.toString();
        if (maxLength <= 0 || printed.length() <= maxLength) {
            return printed;
        }
        return printed.substring(0, cutIndex(printed, maxLength)) + "... (truncated)";
    }

    /**
     * Collects what is printed into it, and stops the printing by throwing {@link LimitReached} once it holds
     * one character past the given length, which tells a stack trace of exactly that length from a longer one.
     */
    private static final class BoundedWriter extends Writer {

        private final StringBuilder printed = new StringBuilder();

        /**
         * How many characters to collect, {@link Long#MAX_VALUE} for no limit.
         */
        private final long capacity;

        /**
         * Creates a writer collecting at most one character past the given length.
         *
         * @param maxLength The maximum length of the stack trace, a non-positive value disabling the limit
         */
        private BoundedWriter(int maxLength) {
            this.capacity = maxLength > 0 ? maxLength + 1L : Long.MAX_VALUE;
        }

        @Override
        public void write(char[] chars, int offset, int length) {
            printed.append(chars, offset, room(length));
            stopOnceFull();
        }

        @Override
        public void write(String string, int offset, int length) {
            printed.append(string, offset, offset + room(length));
            stopOnceFull();
        }

        private int room(int length) {
            return (int) Math.min(length, capacity - printed.length());
        }

        private void stopOnceFull() {
            if (printed.length() == capacity) {
                throw LimitReached.INSTANCE;
            }
        }

        @Override
        public void flush() {
            // Nothing buffered beyond the builder itself
        }

        @Override
        public void close() {
            // Nothing to release
        }

        @Override
        public String toString() {
            return printed.toString();
        }

    }

    /**
     * Thrown by {@link BoundedWriter} to stop the printing of a stack trace, which only catches an
     * {@link java.io.IOException} of its writer. Without a stack trace nor any state, and so shared.
     */
    private static final class LimitReached extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private static final LimitReached INSTANCE = new LimitReached();

        private LimitReached() {
            super(null, null, false, false);
        }

    }

    /**
     * Maximum length (in characters) of the {@code stacktrace} field written by
     * {@link #writeThrowable(Map, LogEvent)}. Defaults to {@code 8'192}; override to raise, lower, or disable
     * ({@code 0} or negative) truncation.
     *
     * @return The maximum length allowed for the stack trace, or a non-positive value to disable truncation
     */
    protected int getMaxStackTraceLength() {
        return 8_192;
    }

    /**
     * Maximum length (in characters) of the {@code logmessage} field written by
     * {@link #writeBasic(Map, LogEvent)}, so that a call logging a large payload cannot inflate its document,
     * and the batch holding it, past what the downstream system accepts. Defaults to {@code 32'768}; override
     * to raise, lower, or disable ({@code 0} or negative) truncation.
     *
     * @return The maximum length allowed for the message, or a non-positive value to disable truncation
     */
    protected int getMaxMessageLength() {
        return 32_768;
    }

}
