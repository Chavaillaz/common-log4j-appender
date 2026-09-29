package com.chavaillaz.appender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Duration;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers {@link DeliveryOptions}, the value the pipeline tuning settings moved into when they stopped being
 * one default method per knob on {@link LogConfiguration}. The defaults themselves are asserted here
 * because they are the contract for every appender that never touches them: silently changing one changes
 * the behavior of every such appender, so it should have to break a test to do so.
 */
@DisplayName("DeliveryOptions")
class DeliveryOptionsTest {

    @Test
    @DisplayName("Defaults to the values documented as the pipeline's out-of-the-box behavior")
    void testDefaults() {
        DeliveryOptions options = DeliveryOptions.defaults();

        assertEquals(1, options.flushThreshold());
        assertEquals(Duration.ofSeconds(5), options.flushInterval());
        assertNull(options.flushLevel());
        assertEquals(4, options.maxConcurrentTransmissions());
        assertEquals(10_000, options.maxPendingDocuments());
        assertEquals(Integer.MAX_VALUE, options.maxRequestDocuments());
        assertEquals(5, options.maxConsecutiveFailures());
        assertEquals(10_000, options.maxQueuedEvents());
        assertEquals(Duration.ofSeconds(1), options.retryBackoff());
        assertEquals(Duration.ofSeconds(30), options.maxRetryBackoff());
        assertEquals(Duration.ofSeconds(30), options.closeTimeout());
    }

    @Test
    @DisplayName("A configuration that overrides nothing gets exactly those defaults")
    void testConfigurationDefaultsToTheSameOptions() {
        // given: the minimum a LogConfiguration can implement, now that tuning lives in one value
        LogConfiguration configuration = new LogConfiguration() {

            @Override
            public String getApplication() {
                return "application";
            }

            @Override
            public String getHost() {
                return "host";
            }

            @Override
            public String getEnvironment() {
                return "environment";
            }

        };

        // then
        assertEquals(DeliveryOptions.defaults(), configuration.getDeliveryOptions());

        // and: the same instance on every read, since it is read several times per document and the
        // interface asks implementations not to build a new one each time -- its own default included
        assertSame(configuration.getDeliveryOptions(), configuration.getDeliveryOptions());
    }

    @Test
    @DisplayName("Derives a new instance overriding one setting and carrying the rest over")
    void testWithCarriesEveryOtherSettingOver() {
        // given
        DeliveryOptions original = DeliveryOptions.builder()
                .flushThreshold(500)
                .flushLevel(Level.WARN)
                .maxPendingDocuments(1_000)
                .closeTimeout(null)
                .build();

        // when
        DeliveryOptions derived = original.with().maxPendingDocuments(50_000).build();

        // then: only the overridden setting differs, the rest (including a deliberately null one) is kept
        assertEquals(50_000, derived.maxPendingDocuments());
        assertEquals(500, derived.flushThreshold());
        assertEquals(Level.WARN, derived.flushLevel());
        assertNull(derived.closeTimeout());
        assertEquals(original, derived.with().maxPendingDocuments(1_000).build());
        assertNotSame(original, derived);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfRangeSettings")
    @DisplayName("Rejects a setting out of its range when built, naming it")
    void testRejectsOutOfRangeSettings(String setting, UnaryOperator<DeliveryOptions.Builder> misconfiguration) {
        // given
        var builder = misconfiguration.apply(DeliveryOptions.builder());

        // when
        var exception = assertThrows(IllegalArgumentException.class, builder::build);

        // then: a configuration naming it fails to load, rather than a delivery misbehaving once it runs
        assertTrue(exception.getMessage().startsWith(setting), "Unexpected message: " + exception.getMessage());
    }

    static Stream<Arguments> outOfRangeSettings() {
        return Stream.of(
                arguments("flushThreshold", setting(builder -> builder.flushThreshold(0))),
                arguments("flushInterval", setting(builder -> builder.flushInterval(Duration.ZERO))),
                arguments("flushInterval", setting(builder -> builder.flushInterval(Duration.ofNanos(999_999)))),
                arguments("flushInterval", setting(builder -> builder.flushInterval(Duration.ofSeconds(Long.MAX_VALUE)))),
                arguments("maxConcurrentTransmissions", setting(builder -> builder.maxConcurrentTransmissions(0))),
                arguments("maxPendingDocuments", setting(builder -> builder.maxPendingDocuments(0))),
                arguments("maxRequestDocuments", setting(builder -> builder.maxRequestDocuments(-1))),
                arguments("maxPendingBytes", setting(builder -> builder.maxPendingBytes(0))),
                arguments("maxRequestBytes", setting(builder -> builder.maxRequestBytes(Long.MIN_VALUE))),
                arguments("maxConsecutiveFailures", setting(builder -> builder.maxConsecutiveFailures(0))),
                arguments("maxQueuedEvents", setting(builder -> builder.maxQueuedEvents(0))),
                arguments("retryBackoff", setting(builder -> builder.retryBackoff(Duration.ofMillis(-1)))),
                arguments("maxRetryBackoff", setting(builder -> builder.maxRetryBackoff(Duration.ofMillis(-1)))),
                arguments("closeTimeout", setting(builder -> builder.closeTimeout(Duration.ofMillis(-1)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSettings")
    @DisplayName("Rejects a missing setting when built, naming it")
    void testRejectsMissingSettings(String setting, UnaryOperator<DeliveryOptions.Builder> misconfiguration) {
        // given
        var builder = misconfiguration.apply(DeliveryOptions.builder());

        // when
        var exception = assertThrows(NullPointerException.class, builder::build);

        // then
        assertTrue(exception.getMessage().startsWith(setting), "Unexpected message: " + exception.getMessage());
    }

    static Stream<Arguments> missingSettings() {
        return Stream.of(
                arguments("flushInterval", setting(builder -> builder.flushInterval(null))),
                arguments("retryBackoff", setting(builder -> builder.retryBackoff(null))),
                arguments("maxRetryBackoff", setting(builder -> builder.maxRetryBackoff(null))));
    }

    @Test
    @DisplayName("Accepts every setting at the edge of its range")
    void testAcceptsTheEdgesOfEveryRange() {
        // when
        var smallest = DeliveryOptions.builder()
                .flushThreshold(1)
                .flushInterval(Duration.ofMillis(1))
                .maxConcurrentTransmissions(1)
                .maxPendingDocuments(1)
                .maxRequestDocuments(1)
                .maxPendingBytes(1)
                .maxRequestBytes(1)
                .maxConsecutiveFailures(1)
                .maxQueuedEvents(1)
                .retryBackoff(Duration.ZERO)
                .maxRetryBackoff(Duration.ZERO)
                .closeTimeout(Duration.ZERO)
                .build();
        var largest = DeliveryOptions.builder()
                .flushThreshold(Integer.MAX_VALUE)
                .maxConcurrentTransmissions(Integer.MAX_VALUE)
                .maxConsecutiveFailures(Integer.MAX_VALUE)
                .maxQueuedEvents(Integer.MAX_VALUE)
                .closeTimeout(null)
                .build();

        // then
        assertEquals(Duration.ZERO, smallest.closeTimeout());
        assertNull(largest.closeTimeout());
    }

    /**
     * Types a change to a builder, the lambdas of the arguments above having no target type of their own.
     */
    private static UnaryOperator<DeliveryOptions.Builder> setting(UnaryOperator<DeliveryOptions.Builder> change) {
        return change;
    }

}
