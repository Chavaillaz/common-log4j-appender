package com.chavaillaz.appender.log4j;

import java.time.Duration;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.core.util.NetUtils;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.DeadLetterListener;
import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogConfiguration;

/**
 * The builder of an appender plugin, holding the attributes every appender built on this library takes,
 * which Log4j2 sets from the element configuring it before calling {@link #build()}:
 * <ul>
 *     <li>{@code name} (required) and a {@code Filter} element, as for any appender</li>
 *     <li>{@code Application}, {@code Host} and {@code Environment}, identifying the application in every
 *     document (see {@link LogConfiguration}), which a configuration takes from the environment through the
 *     lookups of Log4j2, as {@code ${env:APP:-unknown}} or {@code ${sys:app}}</li>
 *     <li>{@code Converter}, the class name of the converter, built through its public constructor taking the
 *     configuration (see {@link LogConverter#newInstance(String, LogConfiguration)})</li>
 *     <li>an appender nested in the element, which the dropped documents are handed to as lines of JSON (see
 *     {@link DeadLetterListener}), a {@code RollingFile} for instance: declared there rather than among the
 *     other appenders, it is started and stopped by the appender it serves, which Log4j2 would otherwise stop in
 *     any order, possibly before the last of them reached it</li>
 *     <li>every delivery option, named as its accessor in {@link DeliveryOptions} but capitalized, such as
 *     {@code FlushThreshold}, durations in milliseconds, {@code TransmissionTimeout} waiting indefinitely at
 *     {@code 0}</li>
 * </ul>
 * A delivery option left unset keeps the value {@link #defaultDeliveryOptions()} gives it. The builder of an
 * appender extends this one with the attributes of its own, each with a public setter returning the builder,
 * which the annotation processor of Log4j2 requires, and builds its configuration from
 * {@link #getApplication()}, {@link #getHost()}, {@link #getEnvironment()} and {@link #getDeliveryOptions()},
 * before creating the appender with it (see
 * {@link AbstractLogDeliveryAppender#AbstractLogDeliveryAppender(LogDeliveryAppenderBuilder, LogConfiguration)}).
 *
 * @param <A> The type of the appender built
 * @param <B> The type of the builder, which its setters return
 */
public abstract class LogDeliveryAppenderBuilder<A extends AbstractLogDeliveryAppender<?>,
        B extends LogDeliveryAppenderBuilder<A, B>> implements org.apache.logging.log4j.core.util.Builder<A> {

    @PluginBuilderAttribute
    @Required(message = "No appender name provided")
    private @Nullable String name;

    @PluginElement("Filter")
    private @Nullable Filter filter;

    /**
     * Any appender nested in the element, Log4j2 injecting into a field of this type the first child whose
     * plugin is an appender, whatever its element name.
     */
    @PluginElement("DeadLetters")
    private @Nullable Appender deadLetterAppender;

    /**
     * The configuration of Log4j2 creating the appender, which resolves the host name by default.
     */
    @PluginConfiguration
    private @Nullable Configuration configuration;

    @PluginBuilderAttribute("Application")
    private String application = "unknown";

    @PluginBuilderAttribute("Host")
    private @Nullable String host;

    @PluginBuilderAttribute("Environment")
    private String environment = "local";

    @PluginBuilderAttribute("Converter")
    private @Nullable String converter;

    @PluginBuilderAttribute("FlushThreshold")
    private @Nullable Integer flushThreshold;

    @PluginBuilderAttribute("FlushInterval")
    private @Nullable Long flushInterval;

    @PluginBuilderAttribute("FlushLevel")
    private @Nullable String flushLevel;

    @PluginBuilderAttribute("MaxConcurrentTransmissions")
    private @Nullable Integer maxConcurrentTransmissions;

    @PluginBuilderAttribute("MaxPendingDocuments")
    private @Nullable Integer maxPendingDocuments;

    @PluginBuilderAttribute("MaxRequestDocuments")
    private @Nullable Integer maxRequestDocuments;

    @PluginBuilderAttribute("MaxPendingBytes")
    private @Nullable Long maxPendingBytes;

    @PluginBuilderAttribute("MaxRequestBytes")
    private @Nullable Long maxRequestBytes;

    @PluginBuilderAttribute("MaxConsecutiveFailures")
    private @Nullable Integer maxConsecutiveFailures;

    @PluginBuilderAttribute("MaxQueuedEvents")
    private @Nullable Integer maxQueuedEvents;

    @PluginBuilderAttribute("RetryBackoff")
    private @Nullable Long retryBackoff;

    @PluginBuilderAttribute("MaxRetryBackoff")
    private @Nullable Long maxRetryBackoff;

    @PluginBuilderAttribute("TransmissionTimeout")
    private @Nullable Long transmissionTimeout;

    @PluginBuilderAttribute("CloseTimeout")
    private @Nullable Long closeTimeout;

    /**
     * Creates a builder, every attribute unset.
     */
    protected LogDeliveryAppenderBuilder() {
        // Extended by the builder of each appender plugin
    }

    /**
     * This builder, as the type its setters return.
     *
     * @return This builder
     */
    @SuppressWarnings("unchecked")
    protected B asBuilder() {
        return (B) this;
    }

    /**
     * The delivery options an attribute left unset keeps the value of, {@link DeliveryOptions#defaults()}
     * by default. Override it for other defaults, an appender sending documents over the network batching
     * them for instance.
     *
     * @return The default delivery options
     */
    protected DeliveryOptions defaultDeliveryOptions() {
        return DeliveryOptions.defaults();
    }

    /**
     * Builds the delivery options from {@link #defaultDeliveryOptions()} and the attributes set.
     *
     * @return The delivery options
     * @throws IllegalArgumentException If an option is out of its range, or the flush level is not a level
     */
    public DeliveryOptions getDeliveryOptions() {
        DeliveryOptions.Builder options = defaultDeliveryOptions().with();
        ifSet(flushThreshold, options::flushThreshold);
        ifSet(flushInterval, millis -> options.flushInterval(Duration.ofMillis(millis)));
        // Failing the configuration rather than ignoring a level misspelled
        ifSet(flushLevel, level -> options.flushLevel(level.isBlank() ? null : Level.valueOf(level)));
        ifSet(maxConcurrentTransmissions, options::maxConcurrentTransmissions);
        ifSet(maxPendingDocuments, options::maxPendingDocuments);
        ifSet(maxRequestDocuments, options::maxRequestDocuments);
        ifSet(maxPendingBytes, options::maxPendingBytes);
        ifSet(maxRequestBytes, options::maxRequestBytes);
        ifSet(maxConsecutiveFailures, options::maxConsecutiveFailures);
        ifSet(maxQueuedEvents, options::maxQueuedEvents);
        ifSet(retryBackoff, millis -> options.retryBackoff(Duration.ofMillis(millis)));
        ifSet(maxRetryBackoff, millis -> options.maxRetryBackoff(Duration.ofMillis(millis)));
        ifSet(transmissionTimeout, millis -> options.transmissionTimeout(millis == 0 ? null : Duration.ofMillis(millis)));
        ifSet(closeTimeout, millis -> options.closeTimeout(Duration.ofMillis(millis)));
        return options.build();
    }

    /**
     * Hands the given attribute to the given setter of the options, unless it was left unset.
     *
     * @param value  The value of the attribute, or {@code null} if left unset
     * @param setter The setter of the options taking it
     * @param <V>    The type of the attribute
     */
    private static <V> void ifSet(@Nullable V value, Consumer<V> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * The name of the appender.
     *
     * @return The name, or {@code null} if not set yet
     */
    public @Nullable String getName() {
        return name;
    }

    /**
     * The filter of the appender.
     *
     * @return The filter, or {@code null} for none
     */
    public @Nullable Filter getFilter() {
        return filter;
    }

    /**
     * The name of the application generating the logs, {@code unknown} unless set.
     *
     * @return The application name
     */
    public String getApplication() {
        return application;
    }

    /**
     * The name of the host the application runs on, unless set the one {@code ${hostName}} gives in the
     * configuration of Log4j2: a property of that name it defines, or else the name of the machine, which
     * Log4j2 finds once per logger context. Without a configuration, the name of the machine.
     *
     * @return The host name
     */
    public String getHost() {
        if (host != null) {
            return host;
        }
        String hostName = configuration != null ? configuration.getStrSubstitutor().replace("${hostName:-}") : "";
        return hostName == null || hostName.isEmpty() ? NetUtils.getLocalHostname() : hostName;
    }

    /**
     * The name of the environment the application runs in, {@code local} unless set.
     *
     * @return The environment name
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * The class name of the converter.
     *
     * @return The class name, or {@code null} for the default converter
     */
    public @Nullable String getConverter() {
        return converter;
    }

    /**
     * The appender nested in the element, which the dropped documents are handed to.
     *
     * @return The appender, or {@code null} for none
     */
    public @Nullable Appender getDeadLetterAppender() {
        return deadLetterAppender;
    }

    /**
     * Sets the name of the appender.
     *
     * @param name The name
     * @return This builder
     */
    public B setName(String name) {
        this.name = name;
        return asBuilder();
    }

    /**
     * Sets the filter of the appender.
     *
     * @param filter The filter, or {@code null} for none
     * @return This builder
     */
    public B setFilter(@Nullable Filter filter) {
        this.filter = filter;
        return asBuilder();
    }

    /**
     * Sets the configuration of Log4j2 creating the appender, which Log4j2 does itself.
     *
     * @param configuration The configuration
     * @return This builder
     */
    public B setConfiguration(@Nullable Configuration configuration) {
        this.configuration = configuration;
        return asBuilder();
    }

    /**
     * Sets the name of the application generating the logs.
     *
     * @param application The application name
     * @return This builder
     */
    public B setApplication(String application) {
        this.application = application;
        return asBuilder();
    }

    /**
     * Sets the name of the host the application runs on.
     *
     * @param host The host name, or {@code null} for the one {@code ${hostName}} gives (see
     *             {@link #getHost()})
     * @return This builder
     */
    public B setHost(@Nullable String host) {
        this.host = host;
        return asBuilder();
    }

    /**
     * Sets the name of the environment the application runs in.
     *
     * @param environment The environment name
     * @return This builder
     */
    public B setEnvironment(String environment) {
        this.environment = environment;
        return asBuilder();
    }

    /**
     * Sets the class name of the converter.
     *
     * @param converter The class name, or {@code null} for the default converter
     * @return This builder
     */
    public B setConverter(@Nullable String converter) {
        this.converter = converter;
        return asBuilder();
    }

    /**
     * Sets the appender the dropped documents are handed to, which the appender built starts and stops.
     *
     * @param deadLetterAppender The appender, or {@code null} for none
     * @return This builder
     */
    public B setDeadLetterAppender(@Nullable Appender deadLetterAppender) {
        this.deadLetterAppender = deadLetterAppender;
        return asBuilder();
    }

    /**
     * Sets {@code FlushThreshold} (see {@link DeliveryOptions#flushThreshold()}).
     *
     * @param flushThreshold The flush threshold
     * @return This builder
     */
    public B setFlushThreshold(int flushThreshold) {
        this.flushThreshold = flushThreshold;
        return asBuilder();
    }

    /**
     * Sets {@code FlushInterval} (see {@link DeliveryOptions#flushInterval()}).
     *
     * @param flushInterval The flush interval, in milliseconds
     * @return This builder
     */
    public B setFlushInterval(long flushInterval) {
        this.flushInterval = flushInterval;
        return asBuilder();
    }

    /**
     * Sets {@code FlushLevel} (see {@link DeliveryOptions#flushLevel()}).
     *
     * @param flushLevel The name of the level, whatever its case, or a blank one for none
     * @return This builder
     */
    public B setFlushLevel(@Nullable String flushLevel) {
        this.flushLevel = flushLevel;
        return asBuilder();
    }

    /**
     * Sets {@code MaxConcurrentTransmissions} (see {@link DeliveryOptions#maxConcurrentTransmissions()}).
     *
     * @param maxConcurrentTransmissions The maximum number of concurrent transmissions
     * @return This builder
     */
    public B setMaxConcurrentTransmissions(int maxConcurrentTransmissions) {
        this.maxConcurrentTransmissions = maxConcurrentTransmissions;
        return asBuilder();
    }

    /**
     * Sets {@code MaxPendingDocuments} (see {@link DeliveryOptions#maxPendingDocuments()}).
     *
     * @param maxPendingDocuments The maximum number of pending documents
     * @return This builder
     */
    public B setMaxPendingDocuments(int maxPendingDocuments) {
        this.maxPendingDocuments = maxPendingDocuments;
        return asBuilder();
    }

    /**
     * Sets {@code MaxRequestDocuments} (see {@link DeliveryOptions#maxRequestDocuments()}).
     *
     * @param maxRequestDocuments The maximum number of documents per request
     * @return This builder
     */
    public B setMaxRequestDocuments(int maxRequestDocuments) {
        this.maxRequestDocuments = maxRequestDocuments;
        return asBuilder();
    }

    /**
     * Sets {@code MaxPendingBytes} (see {@link DeliveryOptions#maxPendingBytes()}).
     *
     * @param maxPendingBytes The maximum pending size, in bytes
     * @return This builder
     */
    public B setMaxPendingBytes(long maxPendingBytes) {
        this.maxPendingBytes = maxPendingBytes;
        return asBuilder();
    }

    /**
     * Sets {@code MaxRequestBytes} (see {@link DeliveryOptions#maxRequestBytes()}).
     *
     * @param maxRequestBytes The maximum request size, in bytes
     * @return This builder
     */
    public B setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
        return asBuilder();
    }

    /**
     * Sets {@code MaxConsecutiveFailures} (see {@link DeliveryOptions#maxConsecutiveFailures()}).
     *
     * @param maxConsecutiveFailures The maximum number of consecutive failures
     * @return This builder
     */
    public B setMaxConsecutiveFailures(int maxConsecutiveFailures) {
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        return asBuilder();
    }

    /**
     * Sets {@code MaxQueuedEvents} (see {@link DeliveryOptions#maxQueuedEvents()}).
     *
     * @param maxQueuedEvents The maximum number of queued events
     * @return This builder
     */
    public B setMaxQueuedEvents(int maxQueuedEvents) {
        this.maxQueuedEvents = maxQueuedEvents;
        return asBuilder();
    }

    /**
     * Sets {@code RetryBackoff} (see {@link DeliveryOptions#retryBackoff()}).
     *
     * @param retryBackoff The base retry delay, in milliseconds
     * @return This builder
     */
    public B setRetryBackoff(long retryBackoff) {
        this.retryBackoff = retryBackoff;
        return asBuilder();
    }

    /**
     * Sets {@code MaxRetryBackoff} (see {@link DeliveryOptions#maxRetryBackoff()}).
     *
     * @param maxRetryBackoff The maximum retry delay, in milliseconds
     * @return This builder
     */
    public B setMaxRetryBackoff(long maxRetryBackoff) {
        this.maxRetryBackoff = maxRetryBackoff;
        return asBuilder();
    }

    /**
     * Sets {@code TransmissionTimeout} (see {@link DeliveryOptions#transmissionTimeout()}).
     *
     * @param transmissionTimeout The transmission timeout, in milliseconds, or {@code 0} to wait indefinitely
     * @return This builder
     */
    public B setTransmissionTimeout(long transmissionTimeout) {
        this.transmissionTimeout = transmissionTimeout;
        return asBuilder();
    }

    /**
     * Sets {@code CloseTimeout} (see {@link DeliveryOptions#closeTimeout()}).
     *
     * @param closeTimeout The close timeout, in milliseconds
     * @return This builder
     */
    public B setCloseTimeout(long closeTimeout) {
        this.closeTimeout = closeTimeout;
        return asBuilder();
    }

}
