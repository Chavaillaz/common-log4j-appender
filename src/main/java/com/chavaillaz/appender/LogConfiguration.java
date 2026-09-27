package com.chavaillaz.appender;

/**
 * Identifies the application whose logs are transmitted, and carries the settings governing how they are
 * transmitted (see {@link DeliveryOptions}).
 * <p>
 * The three fields below are what only the application can answer and what ends up in every document, where
 * {@link DeliveryOptions} is pipeline tuning meaning the same for every appender built on this library.
 * <p>
 * A read-only contract: an implementation is expected to be immutable (a {@code record}, or a class built once
 * through a builder), being read repeatedly from background threads without any synchronization. The
 * {@code @PluginBuilderFactory} pattern of Log4j2 produces exactly that, mutable fields on the builder and an
 * immutable object out of its {@code build()}.
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
     * Read for every document rather than once, so that an implementation can vary a limit at runtime by
     * returning another instance: return a stored instance rather than building one on every call.
     *
     * @return The delivery options
     */
    default DeliveryOptions getDeliveryOptions() {
        return DeliveryOptions.defaults();
    }

}
