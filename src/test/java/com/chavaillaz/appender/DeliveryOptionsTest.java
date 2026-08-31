package com.chavaillaz.appender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

}
