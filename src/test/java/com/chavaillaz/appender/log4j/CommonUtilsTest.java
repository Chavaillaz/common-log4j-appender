package com.chavaillaz.appender.log4j;

import static com.chavaillaz.appender.log4j.CommonUtils.await;
import static com.chavaillaz.appender.log4j.CommonUtils.getInitialHostname;
import static com.chavaillaz.appender.log4j.CommonUtils.getProperty;
import static com.chavaillaz.appender.log4j.CommonUtils.start;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CommonUtils")
class CommonUtilsTest {

    private static final String TEST_KEY = "commonUtilsTest.knownKey";

    @AfterEach
    void cleanup() {
        System.clearProperty(TEST_KEY);
    }

    @ParameterizedTest(name = "with default value \"{0}\"")
    @NullSource
    @ValueSource(strings = "defaultValue")
    @DisplayName("Falls back to the given default value when the key is not found")
    void testProperty_Unknown(String defaultValue) {
        // when
        var value = getProperty("commonUtilsTest.unknownKey", defaultValue);

        // then
        assertEquals(defaultValue, value);
    }

    @Test
    @DisplayName("Returns the system property value when the key is found")
    void testProperty_System() {
        // given
        System.setProperty(TEST_KEY, "systemValue");

        // when
        var value = getProperty(TEST_KEY, "defaultValue");

        // then
        assertEquals("systemValue", value);
    }

    @Test
    @DisplayName("Returns the canonical host name of the local machine")
    void testHostname() throws Exception {
        // when
        var hostname = getInitialHostname();

        // then
        assertEquals(InetAddress.getLocalHost().getCanonicalHostName(), hostname);
    }

    @Test
    @DisplayName("Looks the host name up once, every later call sharing its result")
    void testHostnameIsLookedUpOnce() {
        // when / then: the result of the one lookup, whichever call asks for it
        assertSame(getInitialHostname(), getInitialHostname());
    }

    @Nested
    @DisplayName("Lookup with a timeout")
    class LookupWithTimeout {

        @Test
        @DisplayName("Returns the lookup's result when it completes before the timeout")
        void testReturnsResultWhenFastEnough() {
            // when
            var result = await(start(() -> "fast-result"), Duration.ofSeconds(5));

            // then
            assertEquals("fast-result", result);
        }

        @Test
        @DisplayName("Falls back to localhost instead of waiting when the lookup exceeds the timeout")
        void testFallsBackToLocalhostWhenTooSlow() {
            // given: a lookup that blocks far longer than the timeout given below, simulating an
            // unresponsive DNS server without an actual network dependency in this test
            var start = System.nanoTime();

            // when
            var result = await(start(() -> {
                Thread.sleep(5_000);
                return "too-late";
            }), Duration.ofMillis(50));
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // then: the call returned close to the given timeout instead of waiting for the slow lookup
            assertEquals("localhost", result);
            assertTrue(elapsedMillis < 2_000, "Should not have waited for the slow lookup, took " + elapsedMillis + "ms");
        }

        @Test
        @DisplayName("Hands a later call the result of a lookup that outlived the wait of an earlier one")
        void testLaterCallGetsTheResultOfASlowLookup() {
            // given
            var lookup = start(() -> {
                Thread.sleep(200);
                return "late-result";
            });

            // when / then: the first call gives up, the lookup going on for the next one
            assertEquals("localhost", await(lookup, Duration.ofMillis(10)));
            assertEquals("late-result", await(lookup, Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("Falls back to localhost when the lookup itself throws")
        void testFallsBackToLocalhostWhenLookupThrows() {
            // when
            var result = await(start(() -> {
                throw new RuntimeException("Simulated lookup failure");
            }), Duration.ofSeconds(5));

            // then
            assertEquals("localhost", result);
        }

    }

}
