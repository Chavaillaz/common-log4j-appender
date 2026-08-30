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
 * All MDC fields are also added as-is (only if not already present). If the event carries an exception, it
 * also includes:
 * <ul>
 *     <li><strong>class:</strong> Class of the exception</li>
 *     <li><strong>stacktrace:</strong> Stack trace, truncated to {@link #getMaxStackTraceLength()} characters</li>
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

    private LogConfiguration configuration;

    /**
     * Creates a new default log converter. {@link #configure(LogConfiguration)} must be called before
     * {@link #convert(LogEvent)} is, since {@link #writeBasic(Map, LogEvent)} reads the configuration set
     * there.
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
        document.put("datetime", Instant.ofEpochMilli(event.getInstant().getEpochMillisecond()).toString());
        document.put("host", configuration.getHost());
        document.put("environment", configuration.getEnvironment());
        document.put("application", configuration.getApplication());
        document.put("logger", Optional.ofNullable(event.getLoggerName())
                .orElse(event.getLoggerFqcn()));
        document.put("level", Optional.ofNullable(event.getLevel())
                .map(Level::toString)
                .orElse(null));
        document.put("logmessage", formatMessage(event.getMessage()));
        document.put("thread", event.getThreadName());
    }

    /**
     * Formats the given message, falling back to a placeholder instead of propagating an exception out of
     * {@link #convert(LogEvent)} if a custom {@link Message} implementation's
     * {@link Message#getFormattedMessage()} throws (every implementation bundled with Log4j2 already guards
     * its own formatting).
     *
     * @param message The message to format, or {@code null}
     * @return The formatted message, {@code null} if the given message was {@code null}, or a placeholder
     *         describing the failure if formatting it threw
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
     * Writes the MDC fields of the logging event in the given document, tolerating (losing only the MDC
     * fields, not the whole document) a custom context data implementation that throws while iterated.
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
        } catch (Exception e) {
            log.warn("Unable to read the MDC context data of a logging event, it will be omitted", e);
        }
    }

    /**
     * Writes the exception passed to the logging event in the given document, tolerating (losing only this
     * field, not the whole document) a custom {@link Throwable} subclass whose
     * {@code getMessage()}/{@code getCause()}/{@code toString()} throws while printing the stack trace.
     *
     * @param document The document to write the fields into
     * @param event    The logging event
     */
    protected void writeThrowable(Map<String, Object> document, LogEvent event) {
        Throwable throwable = event.getThrown();
        if (throwable != null) {
            document.put("class", throwable.getClass().getCanonicalName());
            try {
                document.put("stacktrace", getStackTrace(throwable));
            } catch (Exception e) {
                log.warn("Unable to compute the stack trace of a logging event, the field will be omitted", e);
            }
        }
    }

    /**
     * Gets the stack trace of the given throwable, truncated to {@link #getMaxStackTraceLength()}
     * characters so a deep or recursive cause chain cannot inflate a document (and by extension a whole
     * batch) enough to be rejected by the downstream system.
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
     * Maximum length (in characters) of the {@code stacktrace} field written by
     * {@link #writeThrowable(Map, LogEvent)}. Override to raise, lower, or disable ({@code 0} or negative)
     * truncation.
     *
     * @return The maximum length allowed for the stack trace, or a non-positive value to disable truncation
     */
    protected int getMaxStackTraceLength() {
        return 8_192;
    }

}
