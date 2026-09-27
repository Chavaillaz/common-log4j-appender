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
public class DefaultLogConverter implements LogConverter {

    /**
     * Log4j2's own internal diagnostic channel rather than an ordinary {@code LogManager} logger: this
     * class runs inside an appender normally attached to the root logger, so an ordinary logger here would
     * write its diagnostics straight back into the pipeline they are about, which loops (see {@link
     * com.chavaillaz.appender.Batcher}, whose own logger carries the measurement). Make it visible with
     * {@code -Dlog4j2.debug}, a {@code status} attribute on the configuration, or a {@code <StatusListener>}.
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
     * Volatile because {@link #configure(LogConfiguration)} and {@link #convert(LogEvent)} are not
     * guaranteed to run on the same thread: the former is typically called while building the appender, the
     * latter on its background worker. In the usual case the handoff between the two already establishes a
     * happens-before edge, but nothing in this class's contract requires it, and a converter reconfigured
     * later would otherwise be free to never be seen as such by the worker.
     */
    private volatile @Nullable LogConfiguration configuration;

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
        LogConfiguration settings = configuration;
        if (settings == null) {
            // Reported once per event rather than thrown: losing the three configuration-provided fields is
            // a far better outcome for a misconfigured converter than losing every document outright.
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
                // An implementation is meant to be immutable and answer from a field, but nothing enforces
                // that, and one resolving a value on demand can fail -- the reason
                // CommonUtils.getInitialHostname() exists at all. Whichever fields were read before the
                // failure are kept, the rest omitted, as they are for a converter never configured.
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
     * Formats the given message, falling back to a placeholder instead of propagating an exception out of
     * {@link #convert(LogEvent)} if a custom {@link Message} implementation's
     * {@link Message#getFormattedMessage()} throws (every implementation bundled with Log4j2 already guards
     * its own formatting).
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
            // Throwable, not Exception: formatting is the custom Message's own code, and one built from a
            // structure that refers back to itself throws a StackOverflowError. Letting it escape would
            // lose the whole document over a single field, which is what this placeholder exists to avoid.
            log.warn("Unable to format the message of a logging event, using a placeholder instead", e);
            return "<unable to format message: " + e + ">";
        }
    }

    /**
     * Truncates the given value to at most {@code maxLength} characters, appending how much was cut off so a
     * truncated value is never mistaken for a complete one.
     * <p>
     * Cuts one character earlier when the limit falls between the two halves of a surrogate pair: the half
     * left behind is not a character, and encoding it turns it into a replacement one, so a consumer
     * validating its input rejects the document outright. Emoji and most non-BMP scripts are pairs, so this
     * is a matter of where a message reaches the limit, not of anything exotic.
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
        } catch (Throwable e) {
            // Throwable, not Exception, for the same reason as the guards around it: iterating a custom
            // context data implementation runs code this class does not own.
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
            document.put("class", className(throwable.getClass()));
            try {
                document.put("stacktrace", getStackTrace(throwable));
            } catch (Throwable e) {
                // Throwable, not Exception: printStackTrace() walks the cause chain by recursion, so a deep
                // enough one overflows the stack rather than throwing -- and this guard exists so that costs
                // the stacktrace field, not the document carrying it.
                log.warn("Unable to compute the stack trace of a logging event, the field will be omitted", e);
            }
        }
    }

    /**
     * Names the class of a logged exception, preferring the canonical name (the one written in source, dots
     * all the way down) but falling back to the binary name when there is none.
     * <p>
     * {@link Class#getCanonicalName()} answers {@code null} for a class with no source-level name -- an
     * anonymous or local one, which is what {@code throw new RuntimeException(...) {}} produces. Written
     * straight in, that {@code null} loses the one field saying what went wrong.
     *
     * @param type The class of the logged exception
     * @return A non-{@code null} name for it
     */
    private static String className(Class<?> type) {
        String canonicalName = type.getCanonicalName();
        return canonicalName != null ? canonicalName : type.getName();
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
     * {@link #writeBasic(Map, LogEvent)}. Bounds the one remaining part of a document whose size the
     * application controls entirely: a single call logging a large payload (an HTTP response body, a
     * serialized entity, a dump of a collection) would otherwise inflate its document, and by extension the
     * whole batch it travels in, enough for the downstream system to reject the request — costing every
     * other document batched alongside it, not just the oversized one. The same reasoning already applied to
     * {@link #getMaxStackTraceLength()}.
     * <p>
     * Defaults to {@code 32'768}, well above any reasonable log line; override to raise, lower, or disable
     * ({@code 0} or negative) truncation.
     *
     * @return The maximum length allowed for the message, or a non-positive value to disable truncation
     */
    protected int getMaxMessageLength() {
        return 32_768;
    }

}
