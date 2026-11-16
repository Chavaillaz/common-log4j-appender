package com.chavaillaz.appender.log4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;
import com.chavaillaz.appender.TransmissionResult;

/**
 * An appender reporting the context class loader each of its extension points runs with: its converter, the
 * transport of its delivery and the listener of that delivery. Public, and using nothing but the public API of
 * the library, so that a test can load it through a class loader of its own, as an application packaged with
 * class loaders of its own loads an appender. An event whose message is {@value #UNCONVERTIBLE} fails to
 * convert, and is reported to the listener as dropped.
 */
public final class ContextReportingAppender extends AbstractLogDeliveryAppender<LogConfiguration> {

    /**
     * The message of an event this appender fails to convert.
     */
    public static final String UNCONVERTIBLE = "unconvertible";

    private final Map<String, ClassLoader> observed;
    private final LogConverter converter;

    /**
     * Creates the appender.
     *
     * @param name          The name of the appender
     * @param configuration The configuration of the appender
     * @param observed      Where the context class loader of each extension point is reported, by its name
     */
    public ContextReportingAppender(String name, LogConfiguration configuration, Map<String, ClassLoader> observed) {
        super(name, null, configuration);
        this.observed = observed;
        DefaultLogConverter standard = new DefaultLogConverter(configuration);
        this.converter = event -> {
            observe("converter");
            if (UNCONVERTIBLE.equals(event.getMessage().getFormattedMessage())) {
                throw new IllegalStateException("Simulated conversion failure");
            }
            return standard.convert(event);
        };
    }

    @Override
    protected LogConverter getLogConverter() {
        return converter;
    }

    @Override
    protected LogDelivery createLogDeliveryHandler() {
        return new AbstractBatchLogDelivery<>(getLogConfiguration(), new LogDeliveryListener() {
            @Override
            public void onTransmissionSucceeded(int documentCount, long durationMillis) {
                observe("listener of a transmission");
            }

            @Override
            public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
                observe("listener of a discarded event");
            }
        }) {
            @Override
            protected CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents) {
                observe("transport");
                return CompletableFuture.completedFuture(TransmissionResult.success());
            }
        };
    }

    private void observe(String extensionPoint) {
        observed.put(extensionPoint, Thread.currentThread().getContextClassLoader());
    }

}
