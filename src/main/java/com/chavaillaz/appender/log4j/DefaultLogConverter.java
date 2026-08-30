package com.chavaillaz.appender.log4j;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;
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
@Log4j2
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
        document.put("logmessage", formatMessage(event.getMessage()));
        document.put("thread", event.getThreadName());
    }

    /**
     * Formats the given message, tolerating a {@link Message} implementation (typically a custom one
     * supplied by the application, since every implementation bundled with Log4j2 already guards its own
     * formatting) whose {@link Message#getFormattedMessage()} throws instead of letting the exception
     * propagate out of {@link #convert(LogEvent)}, which would otherwise discard the whole document (and
     * with it every other field already written for this event) instead of just the message.
     *
     * @param message The message to format, or {@code null}
     * @return The formatted message, {@code null} if the given message was {@code null}, or a short
     *         placeholder describing the failure if formatting it threw
     */
    private String formatMessage(Message message) {
        if (message == null) {
            return null;
        }
        try {
            return message.getFormattedMessage();
        } catch (Exception e) {
            log.warn("Unable to format the message of a logging event, using a placeholder instead", e);
            return "<unable to format message: " + e + ">";
        }
    }

    /**
     * Writes the MDC fields of the logging event in the given document.
     *
     * @param document The document in which writes the fields
     * @param event    The logging event
     */
    protected void writeMDC(Map<String, Object> document, LogEvent event) {
        if (event.getContextData() == null) {
            return;
        }
        try {
            event.getContextData().forEach(document::putIfAbsent);
        } catch (Exception e) {
            // A custom ContextDataProvider/ReadOnlyStringMap implementation (unlike the one bundled with
            // Log4j2) could throw while being iterated; losing only the MDC fields instead of the whole
            // document (as an uncaught exception here would, since this is called from convert()) still
            // leaves every other field written so far usable.
            log.warn("Unable to read the MDC context data of a logging event, it will be omitted", e);
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
            try {
                document.put("stacktrace", getStackTrace(throwable));
            } catch (Exception e) {
                // A custom Throwable subclass could override getMessage()/getCause()/toString() (all
                // exercised by printStackTrace()) in a way that itself throws; losing only this one field
                // instead of the whole document (as an uncaught exception here would, since this is called
                // from convert()) still leaves every other field written so far usable.
                log.warn("Unable to compute the stack trace of a logging event, the field will be omitted", e);
            }
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
