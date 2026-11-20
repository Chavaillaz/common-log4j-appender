package com.chavaillaz.appender.log4j;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;

import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.TransmissionResult;

/**
 * A minimal, real Log4j2 plugin appender, used only by {@link PluginIntegrationTest} to exercise this
 * library's classes through Log4j2's actual plugin discovery and configuration-building machinery instead
 * of by directly instantiating them, the way every other test in this suite does. Every document it
 * receives is captured in {@link #RECEIVED_DOCUMENTS} for the test to assert on. It only creates its
 * delivery, leaving the conversion of the events to its base class, as an appender with nothing to
 * customize does.
 */
@Plugin(name = "TestDeliveryAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public final class PluginIntegrationTestAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

    /**
     * Every document handed to {@link #createLogDeliveryHandler()}'s delivery, across every appender
     * instance: cleared by {@link PluginIntegrationTest} before each test, since a real Log4j2
     * configuration cannot easily hand the test a direct reference to the appender instance it created.
     */
    static final List<Map<String, Object>> RECEIVED_DOCUMENTS = new CopyOnWriteArrayList<>();

    private final long transmissionDelayMillis;

    private PluginIntegrationTestAppender(Builder builder, TestLogConfiguration configuration) {
        super(builder, configuration);
        this.transmissionDelayMillis = builder.transmissionDelayMillis;
    }

    /**
     * The builder Log4j2 discovers (via {@link PluginBuilderFactory}) and populates from the matching XML
     * (or programmatically built, see {@link PluginIntegrationTest}) appender element's attributes: those of
     * the builder of the library, inherited, and one of its own.
     */
    public static class Builder extends LogDeliveryAppenderBuilder<PluginIntegrationTestAppender, Builder> {

        @PluginBuilderAttribute
        private long transmissionDelayMillis = 0;

        /**
         * The options of the configuration of the other tests, which disable the retry backoff.
         */
        @Override
        protected DeliveryOptions defaultDeliveryOptions() {
            return TestLogConfiguration.builder().build().getDeliveryOptions();
        }

        @Override
        public PluginIntegrationTestAppender build() {
            TestLogConfiguration configuration = new TestLogConfiguration(getApplication(), getHost(),
                    getEnvironment(), getDeliveryOptions());
            return new PluginIntegrationTestAppender(this, configuration);
        }

        public Builder setTransmissionDelayMillis(long transmissionDelayMillis) {
            this.transmissionDelayMillis = transmissionDelayMillis;
            return this;
        }

    }

    /**
     * Referenced by {@link PluginBuilderFactory} so Log4j2's plugin machinery knows how to build this
     * appender from a configuration.
     *
     * @return A new, unpopulated builder
     */
    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    @Override
    protected LogDelivery createLogDeliveryHandler() {
        return new AbstractBatchLogDelivery<>(getLogConfiguration()) {
            @Override
            protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                // Completed later rather than slept on, the way an asynchronous client answers
                return CompletableFuture.supplyAsync(() -> {
                    RECEIVED_DOCUMENTS.addAll(documents);
                    return TransmissionResult.success();
                }, CompletableFuture.delayedExecutor(transmissionDelayMillis, MILLISECONDS));
            }
        };
    }

}
