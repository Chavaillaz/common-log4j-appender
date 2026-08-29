package com.chavaillaz.appender.log4j;

import java.util.Map;

import org.apache.logging.log4j.core.LogEvent;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogConfiguration;

/**
 * Conversion of events to records for logs transmission.
 */
public interface LogConverter {

    /**
     * Converts a logging event into multiple properties (key/value) to log.
     *
     * @param event The logging event
     * @return The {@link Map} containing the properties to send, or {@code null} to skip this event
     *         (see {@link com.chavaillaz.appender.LogDelivery#send(Map)})
     */
    @Nullable Map<String, Object> convert(LogEvent event);

    /**
     * Configures the converter with the given settings.
     *
     * @param configuration The configuration
     */
    void configure(LogConfiguration configuration);

}
