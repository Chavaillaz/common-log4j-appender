package com.chavaillaz.appender.log4j;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.util.NetUtils;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.DeliveryOptions;

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
        assertThat(PluginIntegrationTestAppender.RECEIVED_DOCUMENTS).hasSize(1);
        assertThat(PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.get(0).get("logmessage"))
                .isEqualTo("hello from the real Log4j2 pipeline");
        assertThat(PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.get(0).get("application"))
                .isEqualTo("integration-test-app");
    }

    @Test
    @DisplayName("Binds the attributes every appender takes to the builder of the library, which the one of the plugin extends")
    void testAttributesOfTheLibraryBuilderAreBound() {
        // given: attributes the builder of the plugin inherits, as an application names them in its configuration
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Application", "integration-test-app");
        attributes.put("Host", "integration-test-host");
        attributes.put("Environment", "integration");
        attributes.put("Converter", LogConverterTest.ApplicationConverter.class.getName());
        attributes.put("MaxQueuedEvents", "50");
        attributes.put("TransmissionTimeout", "1500");

        // when
        startContext(Map.of(), attributes);
        var appender = (PluginIntegrationTestAppender) context.getConfiguration().getAppender("Test");

        // then: Log4j2 set the fields the plugin inherits, as it sets those it declares
        assertThat(appender.getLogConfiguration().getApplication()).isEqualTo("integration-test-app");
        assertThat(appender.getLogConfiguration().getHost()).isEqualTo("integration-test-host");
        assertThat(appender.getLogConfiguration().getEnvironment()).isEqualTo("integration");
        assertThat(appender.getLogConfiguration().getDeliveryOptions().maxQueuedEvents()).isEqualTo(50);
        assertThat(appender.getLogConfiguration().getDeliveryOptions().transmissionTimeout())
                .isEqualTo(Duration.ofMillis(1_500));
        assertThat(appender.getLogConverter()).isInstanceOf(LogConverterTest.ApplicationConverter.class);
    }

    @Test
    @DisplayName("Takes as host the hostName of the configuration of Log4j2 unless Host is set, one it defines included")
    void testHostDefaultsToTheHostNameOfTheConfiguration() {
        // given: a configuration defining the property ${hostName} resolves to, which otherwise holds the name of
        // the machine Log4j2 found
        startContext(Map.of("hostName", "configured-host"), Map.of("application", "integration-test-app"));

        // when
        var appender = (PluginIntegrationTestAppender) context.getConfiguration().getAppender("Test");

        // then
        assertThat(appender.getLogConfiguration().getHost()).isEqualTo("configured-host");
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

        // then: the queue was processed and the batch flushed, rather than abandoned on the way out, every
        // message delivered exactly once, rather than as many documents as were logged, which a lost one and a
        // duplicated one would add up to as well
        List<String> expected = IntStream.range(0, 2_000).mapToObj(i -> "message " + i).toList();
        assertThat(PluginIntegrationTestAppender.RECEIVED_DOCUMENTS)
                .extracting(document -> document.get("logmessage"))
                .containsExactlyInAnyOrderElementsOf(expected);
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
    private Logger startContext(int flushThreshold, long transmissionDelayMillis) {
        return startContext(Map.of(), Map.of(
                "application", "integration-test-app",
                "flushThreshold", String.valueOf(flushThreshold),
                "transmissionDelayMillis", String.valueOf(transmissionDelayMillis)));
    }

    /**
     * Starts a dedicated {@link LoggerContext} routing the root logger to the test appender (see
     * {@link #startContext(int, long)}).
     *
     * @param properties The properties of the configuration, by name
     * @param attributes The attributes of the appender, by name
     * @return A logger of the started context
     */
    private Logger startContext(Map<String, String> properties, Map<String, String> attributes) {
        PluginIntegrationTestAppender.RECEIVED_DOCUMENTS.clear();
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.ERROR);
        builder.setConfigurationName("PluginIntegrationTest");
        builder.setPackages("com.chavaillaz.appender.log4j");
        properties.forEach(builder::addProperty);
        AppenderComponentBuilder appender = builder.newAppender("Test", "TestDeliveryAppender");
        attributes.forEach(appender::addAttribute);
        builder.add(appender);
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

    @Nested
    @DisplayName("Plugin builder")
    class PluginBuilder {

        @Test
        @DisplayName("Keeps the delivery options it defaults to for every attribute left unset")
        void testUnsetAttributesKeepTheDefaults() {
            // given: the options of an appender batching by default, as one sending over the network does
            var batching = DeliveryOptions.builder().flushThreshold(100).maxRequestBytes(1_024).build();

            // when / then: the defaults of the library, or those the builder of the plugin gives instead
            assertThat(new OptionsBuilder(DeliveryOptions.defaults()).getDeliveryOptions()).isEqualTo(DeliveryOptions.defaults());
            assertThat(new OptionsBuilder(batching).getDeliveryOptions()).isEqualTo(batching);
        }

        @Test
        @DisplayName("Sets every delivery option from the attribute named after it, durations in milliseconds")
        void testEveryOptionIsSetFromItsAttribute() {
            // when
            DeliveryOptions options = new OptionsBuilder(DeliveryOptions.defaults())
                    .setFlushThreshold(2)
                    .setFlushInterval(3)
                    .setFlushLevel("warn")
                    .setMaxConcurrentTransmissions(4)
                    .setMaxPendingDocuments(5)
                    .setMaxRequestDocuments(6)
                    .setMaxPendingBytes(7)
                    .setMaxRequestBytes(8)
                    .setMaxConsecutiveFailures(9)
                    .setMaxQueuedEvents(10)
                    .setRetryBackoff(11)
                    .setMaxRetryBackoff(12)
                    .setTransmissionTimeout(13)
                    .setCloseTimeout(14)
                    .getDeliveryOptions();

            // then
            assertThat(options).isEqualTo(DeliveryOptions.builder()
                    .flushThreshold(2)
                    .flushInterval(Duration.ofMillis(3))
                    .flushLevel(Level.WARN)
                    .maxConcurrentTransmissions(4)
                    .maxPendingDocuments(5)
                    .maxRequestDocuments(6)
                    .maxPendingBytes(7)
                    .maxRequestBytes(8)
                    .maxConsecutiveFailures(9)
                    .maxQueuedEvents(10)
                    .retryBackoff(Duration.ofMillis(11))
                    .maxRetryBackoff(Duration.ofMillis(12))
                    .transmissionTimeout(Duration.ofMillis(13))
                    .closeTimeout(Duration.ofMillis(14))
                    .build());
        }

        @Test
        @DisplayName("Waits indefinitely for an answer at a TransmissionTimeout of zero, and flushes on no severity at a blank FlushLevel")
        void testZeroTimeoutAndBlankLevelTurnThemOff() {
            // given: defaults timing transmissions out and flushing on errors
            var builder = new OptionsBuilder(DeliveryOptions.builder()
                    .transmissionTimeout(Duration.ofSeconds(5))
                    .flushLevel(Level.ERROR)
                    .build());

            // when
            DeliveryOptions options = builder.setTransmissionTimeout(0).setFlushLevel(" ").getDeliveryOptions();

            // then
            assertThat(options.transmissionTimeout()).isNull();
            assertThat(options.flushLevel()).isNull();
        }

        @Test
        @DisplayName("Fails on a FlushLevel naming no level, as on an option out of its range, rather than ignoring it")
        void testInvalidAttributesFail() {
            // when / then: thrown out of the build, which Log4j2 reports, leaving the appender out
            assertThatThrownBy(() -> new OptionsBuilder(DeliveryOptions.defaults()).setFlushLevel("LOUD").getDeliveryOptions())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OptionsBuilder(DeliveryOptions.defaults()).setFlushThreshold(0).getDeliveryOptions())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("flushThreshold");
        }

        @Test
        @DisplayName("Takes the name of the machine as host when built without a configuration, unless Host is set")
        void testHostDefaultsToTheMachineWithoutAConfiguration() {
            // given: a builder created in code, which no configuration of Log4j2 fills
            var builder = new OptionsBuilder(DeliveryOptions.defaults());

            // when / then
            assertThat(builder.getHost()).isEqualTo(NetUtils.getLocalHostname());
            assertThat(builder.setHost("my-host").getHost()).isEqualTo("my-host");
        }

        @Test
        @DisplayName("Converts with the converter its Converter attribute names, built with the configuration")
        void testNamedConverterIsBuiltWithTheConfiguration() {
            // given
            var appender = PluginIntegrationTestAppender.newBuilder()
                    .setName("Test")
                    .setApplication("my-application")
                    .setConverter(LogConverterTest.ApplicationConverter.class.getName())
                    .build();

            // when
            LogConverter converter = appender.getLogConverter();

            // then
            assertThat(converter).isInstanceOf(LogConverterTest.ApplicationConverter.class);
            assertThat(converter.convert(Log4jLogEvent.newBuilder().build())).containsEntry("application", "my-application");
        }

        @Test
        @DisplayName("Converts with the default converter, reporting why, when the one named cannot be created")
        void testUnknownConverterFallsBackToTheDefault() {
            // given
            var statuses = new CopyOnWriteArrayList<StatusData>();
            StatusListener listener = new StatusListener() {
                @Override
                public void log(StatusData data) {
                    statuses.add(data);
                }

                @Override
                public Level getStatusLevel() {
                    return Level.ERROR;
                }

                @Override
                public void close() {
                    // Nothing to release
                }
            };
            StatusLogger.getLogger().registerListener(listener);

            // when
            PluginIntegrationTestAppender appender;
            try {
                appender = PluginIntegrationTestAppender.newBuilder()
                        .setName("Test")
                        .setConverter("package.does.not.exist.NotFoundConverter")
                        .build();
            } finally {
                StatusLogger.getLogger().removeListener(listener);
            }

            // then: the events still flow, converted the default way, rather than being lost to a misspelled name
            assertThat(appender.getLogConverter()).isInstanceOf(DefaultLogConverter.class);
            assertThat(statuses).anyMatch(status -> status.getLevel() == Level.ERROR
                    && status.getMessage().getFormattedMessage().contains("package.does.not.exist.NotFoundConverter"));
        }

        @Test
        @DisplayName("Requires a name, as every appender does")
        void testNameIsRequired() {
            // when / then
            assertThatThrownBy(() -> PluginIntegrationTestAppender.newBuilder().build())
                    .isInstanceOf(NullPointerException.class).hasMessage("name must not be null");
        }

    }

    /**
     * A builder of the library defaulting to the given delivery options, whose appender these tests never build.
     */
    static final class OptionsBuilder extends AbstractLogDeliveryAppender.Builder<PluginIntegrationTestAppender, OptionsBuilder> {

        private final DeliveryOptions defaults;

        OptionsBuilder(DeliveryOptions defaults) {
            this.defaults = defaults;
        }

        @Override
        protected DeliveryOptions defaultDeliveryOptions() {
            return defaults;
        }

        @Override
        public PluginIntegrationTestAppender build() {
            throw new UnsupportedOperationException("Never built by these tests");
        }

    }

}
