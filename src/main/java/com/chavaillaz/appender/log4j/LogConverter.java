package com.chavaillaz.appender.log4j;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.util.LoaderUtil;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogConfiguration;

/**
 * Converts a logging event into the document a {@link com.chavaillaz.appender.LogDelivery} sends.
 * <p>
 * A converter takes the configuration it needs in its constructor, so that none is used unconfigured:
 * {@link #newInstance(String, LogConfiguration)} creates the one a configuration names by its class.
 * <p>
 * It runs on the background thread of the appender, and logs through {@code StatusLogger.getLogger()} if it
 * logs at all: an ordinary logger hands each of its messages back to the appender as one more event to convert,
 * in a loop that never ends once a converter logs for every event.
 */
@FunctionalInterface
public interface LogConverter {

    /**
     * Converts a logging event into the document to send, a new one for every event, never modified once
     * returned: the delivery keeps it until it is sent, retried or reported as dropped, and tells the documents
     * of a request apart by reference.
     *
     * @param event The logging event
     * @return The document to send, or {@code null} to skip this event (see
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
     *                                      configuration, has several of which none is more specific than the
     *                                      others, or its constructor fails
     * @throws ClassCastException           If the class does not implement this interface
     * @throws NullPointerException         If the class name or the configuration is {@code null}
     */
    static LogConverter newInstance(String className, LogConfiguration configuration) throws ReflectiveOperationException {
        Objects.requireNonNull(className, "className must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Class<? extends LogConverter> type = LoaderUtil.loadClass(className).asSubclass(LogConverter.class);
        List<Class<?>> parameterTypes = new ArrayList<>();
        for (Constructor<?> candidate : type.getConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(configuration)) {
                parameterTypes.add(parameters[0]);
            }
        }
        if (parameterTypes.isEmpty()) {
            throw new NoSuchMethodException(className + " has no public constructor taking a "
                    + configuration.getClass().getName());
        }
        for (Class<?> parameter : parameterTypes) {
            if (parameterTypes.stream().allMatch(other -> other.isAssignableFrom(parameter))) {
                return type.cast(type.getConstructor(parameter).newInstance(configuration));
            }
        }
        // Unrelated types, two interfaces the configuration implements for instance: taking the first would
        // depend on the order of the constructors, which the JVM leaves unspecified
        throw new NoSuchMethodException(className + " has several public constructors taking a "
                + configuration.getClass().getName() + ", none more specific than the others: " + parameterTypes);
    }

}
