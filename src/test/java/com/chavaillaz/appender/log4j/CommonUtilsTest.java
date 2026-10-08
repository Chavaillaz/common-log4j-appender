package com.chavaillaz.appender.log4j;

import static com.chavaillaz.appender.log4j.CommonUtils.await;
import static com.chavaillaz.appender.log4j.CommonUtils.getInitialHostname;
import static com.chavaillaz.appender.log4j.CommonUtils.getProperty;
import static com.chavaillaz.appender.log4j.CommonUtils.lookUpCanonicalHostName;
import static com.chavaillaz.appender.log4j.CommonUtils.lookUpOnce;
import static com.chavaillaz.appender.log4j.CommonUtils.start;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertThat(value).isEqualTo(defaultValue);
    }

    @Test
    @DisplayName("Returns the system property value when the key is found")
    void testProperty_System() {
        // given
        System.setProperty(TEST_KEY, "systemValue");

        // when
        var value = getProperty(TEST_KEY, "defaultValue");

        // then
        assertThat(value).isEqualTo("systemValue");
    }

    @Test
    @DisplayName("Returns the canonical host name of the local machine")
    void testHostname() throws Exception {
        // when: looked up for as long as it takes, as getInitialHostname() has it looked up in the background
        var hostname = lookUpCanonicalHostName();

        // then
        assertThat(hostname).isEqualTo(InetAddress.getLocalHost().getCanonicalHostName());

        // and: the name getInitialHostname() gives, unless its wait for that lookup ran out first, as it does on
        // a machine whose own name takes more than 5 seconds to resolve
        assertThat(getInitialHostname()).isIn(hostname, "localhost");
    }

    @Test
    @DisplayName("Looks the host name up once, every later call sharing its result")
    void testHostnameIsLookedUpOnce() {
        // given: a lookup counting how many times it runs, and naming its result after it
        var lookups = new AtomicInteger();
        var hostname = lookUpOnce(() -> "host-" + lookups.incrementAndGet(), Duration.ofSeconds(5));

        // when
        var results = List.of(hostname.get(), hostname.get(), hostname.get());

        // then: the result of the one lookup, whichever call asks for it
        assertThat(results).containsOnly("host-1");
        assertThat(lookups).hasValue(1);
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
            assertThat(result).isEqualTo("fast-result");
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
            assertThat(result).isEqualTo("localhost");
            assertThat(elapsedMillis)
                    .as("Should not have waited for the slow lookup, took " + elapsedMillis + "ms")
                    .isLessThan(2_000);
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
            assertThat(await(lookup, Duration.ofMillis(10))).isEqualTo("localhost");
            assertThat(await(lookup, Duration.ofSeconds(5))).isEqualTo("late-result");
        }

        @Test
        @DisplayName("Falls back to localhost when the lookup itself throws")
        void testFallsBackToLocalhostWhenLookupThrows() {
            // when
            var result = await(start(() -> {
                throw new RuntimeException("Simulated lookup failure");
            }), Duration.ofSeconds(5));

            // then
            assertThat(result).isEqualTo("localhost");
        }

    }

}
