package com.chavaillaz.appender.log4j;

import java.lang.reflect.Constructor;
import java.util.Map;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.util.LoaderUtil;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogConfiguration;

/**
 * Conversion of events to records for logs transmission.
 * <p>
 * A converter is given whatever configuration it needs when built, through its constructor, so that none can
 * be used before it is configured: {@link #newInstance(String, LogConfiguration)} creates one an appender
 * configuration names by its class.
 */
@FunctionalInterface
public interface LogConverter {

    /**
     * Converts a logging event into properties (key/value) to log.
     *
     * @param event The logging event
     * @return The properties to send, or {@code null} to skip this event (see
     *         {@link com.chavaillaz.appender.LogDelivery#send(Map)})
     */
    @Nullable Map<String, Object> convert(LogEvent event);

    /**
     * Creates the converter of the given class through its public constructor taking the configuration, the
     * one declaring the most specific type the configuration is an instance of when there are several:
     * {@link LogConfiguration}, or the configuration class of an appender.
     * <p>
     * The class is loaded as Log4j2 loads a plugin (see {@link LoaderUtil#loadClass(String)}), and on the
     * module path, its module exports its package.
     *
     * @param className     The binary name of the class, implementing this interface
     * @param configuration The configuration to build the converter with
     * @return The new converter
     * @throws ReflectiveOperationException If the class cannot be found, has no public constructor taking the
     *                                      configuration, or its constructor fails
     * @throws ClassCastException           If the class does not implement this interface
     */
    static LogConverter newInstance(String className, LogConfiguration configuration) throws ReflectiveOperationException {
        Class<? extends LogConverter> type = LoaderUtil.loadClass(className).asSubclass(LogConverter.class);
        Constructor<?> chosen = null;
        for (Constructor<?> candidate : type.getConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(configuration)
                    && (chosen == null || chosen.getParameterTypes()[0].isAssignableFrom(parameters[0]))) {
                chosen = candidate;
            }
        }
        if (chosen == null) {
            throw new NoSuchMethodException(className + " has no public constructor taking a "
                    + configuration.getClass().getName());
        }
        return type.cast(chosen.newInstance(configuration));
    }

}
