package com.chavaillaz.appender.log4j;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.status.StatusLogger;
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
 * The MDC entries are added as well, without overriding any of these fields. If the event carries an exception:
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
     * into the appender they are about (see {@link com.chavaillaz.appender.Batcher}).
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
     * Volatile, {@link #configure(LogConfiguration)} and {@link #convert(LogEvent)} typically running on
     * different threads.
     */
    private volatile @Nullable LogConfiguration configuration;

    /**
     * Creates a new default log converter, to be configured through {@link #configure(LogConfiguration)}
     * before converting events.
     */
    public DefaultLogConverter() {
        // Nothing to initialize here: configuration is only available once configure() is called
    }

    /**
     * {@inheritDoc}
     *
     * @param configuration The configuration
     */
    @Override
    public void configure(LogConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * {@inheritDoc}
     *
     * @param event The logging event
     * @return The properties to send (never {@code null})
     */
    @Override
    public Map<String, Object> convert(LogEvent event) {
        Map<String, Object> document = new HashMap<>(estimateCapacity(event));
        writeBasic(document, event);
        writeThrowable(document, event);
        writeMDC(document, event);
        return document;
    }

    /**
     * Estimates the {@link HashMap} capacity needed to hold the document without a resize while populating
     * it, based on the fields this converter is expected to write.
     *
     * @param event The logging event
     * @return The estimated initial capacity to use for the resulting document
     */
    private int estimateCapacity(LogEvent event) {
        int entries = BASIC_FIELD_COUNT;
        if (event.getThrown() != null) {
            entries += THROWABLE_FIELD_COUNT;
        }
        if (event.getContextData() != null) {
            entries += event.getContextData().size();
        }
        // Default HashMap load factor is 0.75, so this avoids a resize for the estimated entry count
        return (int) (entries / 0.75f) + 1;
    }

    /**
     * Writes the basic fields of the logging event in the given document.
     *
     * @param document The document to write the fields into
     * @param event    The logging event
     */
    protected void writeBasic(Map<String, Object> document, LogEvent event) {
        LogConfiguration settings = configuration;
        if (settings == null) {
            // Reported for every event rather than thrown, costing the three fields of the configuration alone
            log.warn("The converter was never configured, the host/environment/application fields will be omitted");
        }
        String host = null;
        String environment = null;
        String application = null;
        if (settings != null) {
            try {
                host = settings.getHost();
                environment = settings.getEnvironment();
                application = settings.getApplication();
            } catch (Throwable e) {
                // A configuration resolving a value on demand can fail: the fields read before are kept
                log.warn("Unable to read the host/environment/application from the configuration, "
                        + "the fields it did not provide will be omitted", e);
            }
        }
        document.put("datetime", Instant.ofEpochMilli(event.getInstant().getEpochMillisecond()).toString());
        document.put("host", host);
        document.put("environment", environment);
        document.put("application", application);
        document.put("logger", Optional.ofNullable(event.getLoggerName())
                .orElse(event.getLoggerFqcn()));
        document.put("level", Optional.ofNullable(event.getLevel())
                .map(Level::toString)
                .orElse(null));
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
            return "<unable to format message: " + e + ">";
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
        int keep = Character.isHighSurrogate(value.charAt(maxLength - 1)) && Character.isLowSurrogate(value.charAt(maxLength))
                ? maxLength - 1
                : maxLength;
        int truncatedChars = value.length() - keep;
        return value.substring(0, keep) + "... (truncated, " + truncatedChars + " more characters)";
    }

    /**
     * Writes the MDC fields of the logging event in the given document, which a custom context data failing
     * while iterated only costs its own fields.
     *
     * @param document The document to write the fields into
     * @param event    The logging event
     */
    protected void writeMDC(Map<String, Object> document, LogEvent event) {
        if (event.getContextData() == null) {
            return;
        }
        try {
            event.getContextData().forEach(document::putIfAbsent);
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
     * system accepts.
     *
     * @param throwable The throwable
     * @return The stack trace
     */
    protected String getStackTrace(Throwable throwable) {
        Writer result = new StringWriter();
        PrintWriter printWriter = new PrintWriter(result);
        throwable.printStackTrace(printWriter);
        return truncate(result.toString(), getMaxStackTraceLength());
    }

    /**
     * Maximum length (in characters) of the {@code stacktrace} field written by
     * {@link #writeThrowable(Map, LogEvent)}. Override to raise, lower, or disable ({@code 0} or negative)
     * truncation.
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
