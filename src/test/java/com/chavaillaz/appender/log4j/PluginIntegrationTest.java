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
        // directly, so plugin discovery (via the "packages" below) is genuinely exercised. A dedicated
        // LoggerContext is created and started explicitly (rather than going through Configurator.initialize(),
        // which resolves/reuses whatever context the JVM-wide ContextSelector already has active — shared
        // with every other test in this suite that logs through the default log4j2.xml on the test
        // classpath) so this test is fully isolated from that shared state.
        PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.clear();
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.ERROR);
        builder.setConfigurationName("PluginIntegrationTest");
        builder.setPackages("com.chavaillaz.appender.log4j");
        builder.add(builder.newAppender("Test", "TestDeliveryAppender")
                .addAttribute("application", "integration-test-app")
                .addAttribute("flushThreshold", "1"));
        builder.add(builder.newRootLogger(Level.INFO)
                .add(builder.newAppenderRef("Test")));

        // when
        context = new LoggerContext("PluginIntegrationTest");
        context.start(builder.build());
        Logger logger = context.getLogger(PluginIntegrationTest.class.getName());
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
