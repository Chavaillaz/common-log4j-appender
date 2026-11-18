package com.example.appender;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.log4j.AbstractLogDeliveryAppender;

/**
 * The plugin Log4j2 creates for an {@code <ExampleAppender>} element of its configuration.
 */
@Plugin(name = "ExampleAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class ExampleAppender extends AbstractLogDeliveryAppender<ExampleConfiguration> {

    protected ExampleAppender(Builder builder, ExampleConfiguration configuration) {
        super(builder, configuration);
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
     * Holds the attributes of the element, which Log4j2 sets before calling {@link #build()}: those every
     * appender built on the library takes, inherited, and those of this one, each with a public setter returning
     * the builder, which the annotation processor of Log4j2 requires.
     */
    public static class Builder extends AbstractLogDeliveryAppender.Builder<ExampleAppender, Builder> {

        @PluginBuilderAttribute("CollectorHost")
        @Required(message = "No collector host provided")
        private String collectorHost;

        @PluginBuilderAttribute("CollectorPort")
        private int collectorPort = 5000;

        @Override
        public ExampleAppender build() {
            // A delivery option out of its range throws, which Log4j2 reports, leaving the appender out of the
            // configuration
            ExampleConfiguration configuration = new ExampleConfiguration(getApplication(), getHost(),
                    getEnvironment(), getDeliveryOptions(), collectorHost, collectorPort);
            return new ExampleAppender(this, configuration);
        }

        public Builder setCollectorHost(String collectorHost) {
            this.collectorHost = collectorHost;
            return this;
        }

        public Builder setCollectorPort(int collectorPort) {
            this.collectorPort = collectorPort;
            return this;
        }

    }

}
