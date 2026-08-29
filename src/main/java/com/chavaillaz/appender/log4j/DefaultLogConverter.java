package com.chavaillaz.appender.log4j;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;

import com.chavaillaz.appender.LogConfiguration;

/**
 * Default implementation converting the following fields:
 * <ul>
 *     <li><strong>datetime:</strong> Date of logging event</li>
 *     <li><strong>host:</strong> Taken from appender configuration</li>
 *     <li><strong>environment:</strong> Taken from appender configuration</li>
 *     <li><strong>application:</strong> Taken from appender configuration</li>
 *     <li><strong>logger:</strong> Logger of logging event</li>
 *     <li><strong>level:</strong> Level of logging event</li>
 *     <li><strong>logmessage:</strong> Message of the logging event</li>
 *     <li><strong>thread:</strong> Thread that created the logging event</li>
 * </ul>
 * <p>All the MDC fields will also be added as is (only if they do not already exist).</p>
 * <p>In case the event contains an exception, it also includes the fields:</p>
 * <ul>
 *     <li><strong>class:</strong> Class of the exception</li>
 *     <li><strong>stacktrace:</strong> Stacktrace of the exception, truncated to
 *     {@link #getMaxStackTraceLength()} characters</li>
 * </ul>
 */
public class DefaultLogConverter implements LogConverter {

    /**
     * Number of fields written by {@link #writeBasic(Map, LogEvent)}.
     */
    private static final int BASIC_FIELD_COUNT = 8;

    /**
     * Number of fields written by {@link #writeThrowable(Map, LogEvent)} when an exception is present.
     */
    private static final int THROWABLE_FIELD_COUNT = 2;

    /**
     * The configuration of the logs transmission
     */
    private LogConfiguration configuration;

    @Override
    public void configure(LogConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public Map<String, Object> convert(LogEvent event) {
        Map<String, Object> document = new HashMap<>(estimateCapacity(event));
        writeBasic(document, event);
        writeThrowable(document, event);
        writeMDC(document, event);
        return document;
    }

    /**
     * Estimates the {@link HashMap} capacity needed to hold the document of the given event without
     * triggering a resize while it is being populated, based on the number of fields this converter is
     * expected to write for it.
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
     * @param document The document in which writes the fields
     * @param event    The logging event
     */
    protected void writeBasic(Map<String, Object> document, LogEvent event) {
        document.put("datetime", Instant.ofEpochMilli(event.getInstant().getEpochMillisecond()).toString());
        document.put("host", configuration.getHost());
        document.put("environment", configuration.getEnvironment());
        document.put("application", configuration.getApplication());
        document.put("logger", Optional.ofNullable(event.getLoggerFqcn())
                .orElse(event.getLoggerName()));
        document.put("level", Optional.ofNullable(event.getLevel())
                .map(Level::toString)
                .orElse(null));
        document.put("logmessage", Optional.ofNullable(event.getMessage())
                .map(Message::getFormattedMessage)
                .orElse(null));
        document.put("thread", event.getThreadName());
    }

    /**
     * Writes the MDC fields of the logging event in the given document.
     *
     * @param document The document in which writes the fields
     * @param event    The logging event
     */
    protected void writeMDC(Map<String, Object> document, LogEvent event) {
        if (event.getContextData() != null) {
            event.getContextData().forEach(document::putIfAbsent);
        }
    }

    /**
     * Writes the exception passed to the logging event in the given document.
     *
     * @param document The document in which writes the fields
     * @param event    The logging event
     */
    protected void writeThrowable(Map<String, Object> document, LogEvent event) {
        Throwable throwable = event.getThrown();
        if (throwable != null) {
            document.put("class", throwable.getClass().getCanonicalName());
            document.put("stacktrace", getStackTrace(throwable));
        }
    }

    /**
     * Gets the stack trace of the given throwable, truncated to {@link #getMaxStackTraceLength()}
     * characters if needed, so that a deeply nested cause chain (or a recursive one caused by a bug in the
     * application itself) cannot single-handedly inflate a document (and by extension a whole batch) to a
     * size more likely to be rejected by the downstream system.
     *
     * @param throwable The throwable
     * @return The stack trace
     */
    protected String getStackTrace(Throwable throwable) {
        Writer result = new StringWriter();
        PrintWriter printWriter = new PrintWriter(result);
        throwable.printStackTrace(printWriter);
        String stackTrace = result.toString();

        int maxLength = getMaxStackTraceLength();
        if (maxLength > 0 && stackTrace.length() > maxLength) {
            int truncatedChars = stackTrace.length() - maxLength;
            return stackTrace.substring(0, maxLength) + "... (truncated, " + truncatedChars + " more characters)";
        }
        return stackTrace;
    }

    /**
     * Gets the maximum length (in characters) of the {@code stacktrace} field written by
     * {@link #writeThrowable(Map, LogEvent)}. Can be overridden to raise, lower, or disable ({@code 0} or
     * a negative value) this limit.
     *
     * @return The maximum length allowed for the stack trace, or a non-positive value to disable truncation
     */
    protected int getMaxStackTraceLength() {
        return 8_192;
    }

}
