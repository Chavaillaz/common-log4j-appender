package com.example.appender;

import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.LogConfiguration;

/**
 * What the library asks of a configuration, and the address of the collector the documents are sent to.
 * A record, the threads of the library reading it without synchronization.
 *
 * @param application     The name of the application, written in every document
 * @param host            The name of the host it runs on, written in every document
 * @param environment     The name of the environment it runs in, written in every document
 * @param deliveryOptions How the documents are batched, retried and given up on
 * @param collectorHost   The host of the collector
 * @param collectorPort   The port the collector listens on
 */
public record ExampleConfiguration(String application, String host, String environment,
                                   DeliveryOptions deliveryOptions, String collectorHost, int collectorPort)
        implements LogConfiguration {

    @Override
    public String getApplication() {
        return application;
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public String getEnvironment() {
        return environment;
    }

    @Override
    public DeliveryOptions getDeliveryOptions() {
        return deliveryOptions;
    }

}
