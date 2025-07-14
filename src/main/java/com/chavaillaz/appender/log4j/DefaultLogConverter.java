package com.chavaillaz.appender.log4j;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.chavaillaz.appender.LogConfiguration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;

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
 * <p>All the MDC fields will also be added as is (if they not already exist).</p>
 * <p>In case the event contains an exception, it also includes the fields:</p>
 * <ul>
 *     <li><strong>class:</strong> Class of the exception</li>
 *     <li><strong>stacktrace:</strong> Stacktrace of the exception</li>
 * </ul>
 */
public class DefaultLogConverter implements LogConverter {

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
        Map<String, Object> document = new HashMap<>();
        writeBasic(document, event);
        writeThrowable(document, event);
        writeMDC(document, event);
        return document;
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
        document.put("logger", event.getLoggerName());
        document.put("level", Optional.of(event)
                .map(LogEvent::getLevel)
                .map(Level::toString)
                .orElse(null));
        document.put("logmessage", Optional.of(event)
                .map(LogEvent::getMessage)
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
     * Gets the stack trace of the given throwable.
     *
     * @param throwable The throwable
     * @return The stack trace
     */
    protected String getStackTrace(Throwable throwable) {
        Writer result = new StringWriter();
        PrintWriter printWriter = new PrintWriter(result);
        throwable.printStackTrace(printWriter);
        return result.toString();
    }

}
