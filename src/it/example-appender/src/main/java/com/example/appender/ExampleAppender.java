package com.example.appender;

import java.time.Duration;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender;
import com.chavaillaz.appender.log4j.CommonUtils;

/**
 * The plugin Log4j2 creates for an {@code <ExampleAppender>} element of its configuration.
 */
@Plugin(name = "ExampleAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class ExampleAppender extends AbstractLogDeliveryAppender<ExampleConfiguration> {

    protected ExampleAppender(String name, Filter filter, ExampleConfiguration configuration) {
        super(name, filter, configuration);
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    @Override
    protected LogDelivery createLogDeliveryHandler() {
        return new ExampleLogDelivery(getLogConfiguration());
    }

    /**
     * Holds the attributes of the element, which Log4j2 sets before calling {@link #build()}. Each of them has
     * a public setter returning the builder: the annotation processor of Log4j2 fails the compilation of a
     * plugin without one.
     */
    public static class Builder implements org.apache.logging.log4j.core.util.Builder<ExampleAppender> {

        @PluginBuilderAttribute
        @Required(message = "No appender name provided")
        private String name;

        @PluginElement("Filter")
        private Filter filter;

        @PluginBuilderAttribute("CollectorHost")
        @Required(message = "No collector host provided")
        private String collectorHost;

        @PluginBuilderAttribute("CollectorPort")
        private int collectorPort = 5000;

        @PluginBuilderAttribute("Application")
        private String application = "unknown";

        @PluginBuilderAttribute("Environment")
        private String environment = "local";

        @PluginBuilderAttribute("FlushThreshold")
        private int flushThreshold = DeliveryOptions.defaults().flushThreshold();

        @PluginBuilderAttribute("FlushInterval")
        private long flushInterval = DeliveryOptions.defaults().flushInterval().toMillis();

        @Override
        public ExampleAppender build() {
            // Only the options the element exposes are named, the others keeping their default. A value out of
            // its range throws, which Log4j2 reports, leaving the appender out of the configuration
            DeliveryOptions options = DeliveryOptions.builder()
                    .flushThreshold(flushThreshold)
                    .flushInterval(Duration.ofMillis(flushInterval))
                    .build();
            ExampleConfiguration configuration = new ExampleConfiguration(application,
                    CommonUtils.getInitialHostname(), environment, options, collectorHost, collectorPort);
            return new ExampleAppender(name, filter, configuration);
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setFilter(Filter filter) {
            this.filter = filter;
            return this;
        }

        public Builder setCollectorHost(String collectorHost) {
            this.collectorHost = collectorHost;
            return this;
        }

        public Builder setCollectorPort(int collectorPort) {
            this.collectorPort = collectorPort;
            return this;
        }

        public Builder setApplication(String application) {
            this.application = application;
            return this;
        }

        public Builder setEnvironment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder setFlushThreshold(int flushThreshold) {
            this.flushThreshold = flushThreshold;
            return this;
        }

        public Builder setFlushInterval(long flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

    }

}
