package com.chavaillaz.appender;

/**
 * Identifies the application whose logs are being transmitted, and carries the settings governing how they
 * are transmitted (see {@link DeliveryOptions}).
 * <p>
 * The split is deliberate: the three fields below are what only the application can answer and what ends up
 * inside every document, while everything in {@link DeliveryOptions} is generic pipeline tuning that means
 * the same thing for every appender built on this library. Keeping the latter here, as one default method
 * per knob, made this interface grow with every new setting — inherited by every implementation, implemented
 * by none — with no way to tell a value left at its default from one deliberately set to the same thing.
 * <p>
 * A read-only contract deliberately without setters: an implementation is expected to be immutable (e.g. a
 * {@code record}, or a plain class built once via a builder) rather than mutated after being handed to an
 * {@link com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender}/{@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery}
 * pair, which read it repeatedly (not just once at construction) from a background thread with no
 * synchronization of their own around these reads. Log4j2's own {@code @PluginBuilderFactory} builder
 * pattern already produces exactly this shape: mutable fields on the builder, an immutable object out of
 * its {@code build()}.
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
     * Gets the settings governing how logs are batched, retried and shipped. Defaults to
     * {@link DeliveryOptions#defaults()}, so an appender with no reason to tune anything only has to answer
     * the three questions above.
     * <p>
     * Read repeatedly rather than once at construction — for several settings, on the path taken by every
     * single document — so that an implementation can vary a limit at runtime by returning a different
     * instance. Return a stored one rather than building a new one on every call.
     *
     * @return The delivery options
     */
    default DeliveryOptions getDeliveryOptions() {
        return DeliveryOptions.defaults();
    }

}
