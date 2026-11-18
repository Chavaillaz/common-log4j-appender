package com.chavaillaz.appender;

/**
 * Identifies the application whose logs are transmitted, and carries the settings governing how they are
 * transmitted (see {@link DeliveryOptions}).
 * <p>
 * The application, host and environment are what only the application can tell, and end up in every document,
 * where {@link DeliveryOptions} tunes the pipeline the same way for every appender built on this library.
 * <p>
 * Read repeatedly from background threads without synchronization: an implementation is expected to be
 * immutable, as the {@code build()} of a Log4j2 {@code @PluginBuilderFactory} builder produces it.
 */
public interface LogConfiguration {

    /**
     * Gets the name of the application generating the logs.
     *
     * @return The application name
     */
    String getApplication();

    /**
     * Gets the name of the host the application is running on.
     *
     * @return The host name
     */
    String getHost();

    /**
     * Gets the name of the environment the application is running in.
     *
     * @return The environment name
     */
    String getEnvironment();

    /**
     * Gets the settings governing how logs are batched, retried and shipped, defaulting to
     * {@link DeliveryOptions#defaults()}.
     * <p>
     * Read once, when the appender and its delivery are created: a change takes effect with the next appender,
     * which a reconfiguration of Log4j2 creates.
     *
     * @return The delivery options
     */
    default DeliveryOptions getDeliveryOptions() {
        return DeliveryOptions.defaults();
    }

}
