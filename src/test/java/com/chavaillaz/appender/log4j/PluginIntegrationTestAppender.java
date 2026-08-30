package com.chavaillaz.appender.log4j;

import static org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;

import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.TransmissionResult;

/**
 * A minimal, real Log4j2 plugin appender, used only by {@link PluginIntegrationTest} to exercise this
 * library's classes through Log4j2's actual plugin discovery and configuration-building machinery instead
 * of by directly instantiating them, the way every other test in this suite does. Every document it
 * receives is captured in {@link #RECEIVED_DOCUMENTS} for the test to assert on.
 */
@Plugin(name = "TestDeliveryAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public final class PluginIntegrationTestAppender extends AbstractLogDeliveryAppender<TestLogConfiguration> {

    /**
     * Every document handed to {@link #createLogDeliveryHandler()}'s delivery, across every appender
     * instance: cleared by {@link PluginIntegrationTest} before each test, since a real Log4j2
     * configuration cannot easily hand the test a direct reference to the appender instance it created.
     */
    static final List<Map<String, Object>> RECEIVED_DOCUMENTS = new CopyOnWriteArrayList<>();

    private final LogConverter converter = new DefaultLogConverter();

    private PluginIntegrationTestAppender(String name, Filter filter, Layout<?> layout, TestLogConfiguration configuration) {
        super(name, filter, layout, configuration);
        converter.configure(configuration);
    }

    /**
     * The builder Log4j2 discovers (via {@link PluginBuilderFactory}) and populates from the matching XML
     * (or programmatically built, see {@link PluginIntegrationTest}) appender element's attributes.
     */
    public static class Builder extends AbstractAppender.Builder<Builder>
            implements org.apache.logging.log4j.core.util.Builder<PluginIntegrationTestAppender> {

        @PluginBuilderAttribute
        private String application = "unnamed-application";

        @PluginBuilderAttribute
        private long flushThreshold = 1;

        @Override
        public PluginIntegrationTestAppender build() {
            TestLogConfiguration configuration = TestLogConfiguration.builder()
                    .application(application)
                    .flushThreshold(flushThreshold)
                    .build();
            Layout<?> layout = getLayout() != null ? getLayout() : createDefaultLayout();
            return new PluginIntegrationTestAppender(getName(), getFilter(), layout, configuration);
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
    public LogDelivery createLogDeliveryHandler() {
        return new AbstractBatchLogDelivery<>(getLogConfiguration()) {
            @Override
            protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                RECEIVED_DOCUMENTS.addAll(documents);
                return CompletableFuture.completedFuture(TransmissionResult.success());
            }
        };
    }

    @Override
    public Runnable createLogDeliveryTask(LogEvent loggingEvent) {
        return () -> Optional.ofNullable(getLogDeliveryHandler())
                .ifPresent(handler -> handler.send(converter.convert(loggingEvent)));
    }

}
