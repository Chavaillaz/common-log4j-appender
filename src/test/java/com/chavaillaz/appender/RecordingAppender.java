package com.chavaillaz.appender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;

/**
 * An appender keeping every event it is given, for a test to read the dead letters handed to it.
 */
public class RecordingAppender extends AbstractAppender {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Creates an appender, not started.
     *
     * @param name   The name of the appender
     * @param filter The filter of the appender, or {@code null} for none
     */
    public RecordingAppender(String name, @Nullable Filter filter) {
        super(name, filter, null, true, Property.EMPTY_ARRAY);
    }

    /**
     * Creates an appender, started.
     *
     * @param name The name of the appender
     * @return The appender
     */
    public static RecordingAppender started(String name) {
        var appender = new RecordingAppender(name, null);
        appender.start();
        return appender;
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }

    /**
     * The events given so far.
     *
     * @return The events, in the order given
     */
    public List<LogEvent> events() {
        return events;
    }

    /**
     * The messages of the events given so far.
     *
     * @return The formatted messages, in the order given
     */
    public List<String> lines() {
        return events.stream().map(event -> event.getMessage().getFormattedMessage()).toList();
    }

}
