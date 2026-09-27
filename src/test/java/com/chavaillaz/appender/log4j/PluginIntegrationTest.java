package com.chavaillaz.appender.log4j;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link PluginIntegrationTestAppender} (and, transitively, {@link AbstractLogDeliveryAppender},
 * {@link AbstractBatchLogDelivery} and {@link DefaultLogConverter}) the way an actual application would:
 * through a real Log4j2 {@link org.apache.logging.log4j.core.config.Configuration} built from appender
 * attributes, not by constructing any of this library's classes directly the way every other test in this
 * suite does. This is the only test proving the {@code @Plugin}/{@code @PluginBuilderFactory}/
 * {@code @PluginBuilderAttribute} wiring actually works end to end: unit tests of the abstract base classes
 * alone cannot catch a plugin annotation mistake (a wrong category, a builder Log4j2 fails to discover, an
 * attribute name that does not match a builder field) since they never go through Log4j2's plugin discovery
 * at all.
 */
@DisplayName("Log4j2 plugin loading")
class PluginIntegrationTest {

    private LoggerContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.stop();
        }
    }

    @Test
    @DisplayName("Discovers the appender by its plugin name, binds attributes to its builder, and delivers a logged message through it")
    void testAppenderIsDiscoveredConfiguredAndReceivesLoggedMessages() throws Exception {
        // given: a configuration built purely from appender/attribute names (as Log4j2 would parse them
        // out of an XML/properties/YAML file), never referencing PluginIntegrationTestAppender's class
        // directly, so plugin discovery (through the packages startContext() sets) is genuinely exercised
        Logger logger = startContext(1, 0);

        // when
        logger.info("hello from the real Log4j2 pipeline");

        // then: the message reached the delivery through actual plugin discovery, builder attribute
        // binding, and the append()/convert()/send() pipeline, asynchronously, exactly as a real
        // application's logging calls would, not injected directly by this test
        awaitAtLeastOneDocument();
        assertEquals(1, PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.size());
        assertEquals("hello from the real Log4j2 pipeline",
                PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.get(0).get("logmessage"));
        assertEquals("integration-test-app", PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.get(0).get("application"));
    }

    @Test
    @DisplayName("Delivers every message logged right before the context stops with no shutdownTimeout set")
    void testStopWithoutShutdownTimeoutDeliversEveryLoggedMessage() {
        // given: batching and a slow transport, so a burst logged right before stopping is still queued for
        // the worker, which waits for a transmission slot
        Logger logger = startContext(100, 20);
        for (int i = 0; i < 2_000; i++) {
            logger.info("message {}", i);
        }

        // when: stopped as the shutdown hook and every reconfiguration stop it without a shutdownTimeout,
        // passing the appender 0, which Log4j2 documents as leaving the timeout to each appender
        context.stop();

        // then: the queue was processed and the batch flushed, rather than abandoned on the way out
        assertEquals(2_000, PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.size());
    }

    /**
     * Starts a dedicated {@link LoggerContext} (rather than going through {@code Configurator.initialize()},
     * which reuses the context the JVM-wide {@code ContextSelector} already has active, shared with every
     * other test logging through the default log4j2.xml) routing the root logger to the test appender.
     *
     * @param flushThreshold          The flush threshold attribute of the appender
     * @param transmissionDelayMillis How long each transmission of the appender takes
     * @return A logger of the started context
     */
    private Logger startContext(long flushThreshold, long transmissionDelayMillis) {
        PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.clear();
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.ERROR);
        builder.setConfigurationName("PluginIntegrationTest");
        builder.setPackages("com.chavaillaz.appender.log4j");
        builder.add(builder.newAppender("Test", "TestDeliveryAppender")
                .addAttribute("application", "integration-test-app")
                .addAttribute("flushThreshold", String.valueOf(flushThreshold))
                .addAttribute("transmissionDelayMillis", String.valueOf(transmissionDelayMillis)));
        builder.add(builder.newRootLogger(Level.INFO)
                .add(builder.newAppenderRef("Test")));
        context = new LoggerContext("PluginIntegrationTest");
        context.start(builder.build());
        return context.getLogger(PluginIntegrationTest.class.getName());
    }

    private void awaitAtLeastOneDocument() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.isEmpty()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for the logged message to reach the delivery");
            }
            Thread.sleep(10);
        }
    }

}
