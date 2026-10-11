package com.chavaillaz.appender.log4j;

import static com.chavaillaz.appender.log4j.CommonUtils.await;
import static com.chavaillaz.appender.log4j.CommonUtils.getInitialHostname;
import static com.chavaillaz.appender.log4j.CommonUtils.getProperty;
import static com.chavaillaz.appender.log4j.CommonUtils.lookUpCanonicalHostName;
import static com.chavaillaz.appender.log4j.CommonUtils.lookUpOnce;
import static com.chavaillaz.appender.log4j.CommonUtils.start;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
    @DisplayName("Returns the environment variable of that name before the system property")
    void testProperty_Environment() {
        // given: an environment variable of this process, whichever comes first, and a system property of its name
        assumeThat(System.getenv()).as("Environment variables of this process").isNotEmpty();
        var variable = System.getenv().entrySet().iterator().next();
        var previousProperty = System.getProperty(variable.getKey());
        System.setProperty(variable.getKey(), "systemValue");

        try {
            // when
            var value = getProperty(variable.getKey(), "defaultValue");

            // then: what the environment says, which is how a container overrides what an image sets
            assertThat(value).isEqualTo(variable.getValue());
        } finally {
            if (previousProperty == null) {
                System.clearProperty(variable.getKey());
            } else {
                System.setProperty(variable.getKey(), previousProperty);
            }
        }
    }

    @Test
    @DisplayName("Returns the canonical host name of the local machine")
    void testHostname() throws Exception {
        // given: a local machine known by a short name and a canonical one
        var localHost = mock(InetAddress.class);
        when(localHost.getHostName()).thenReturn("my-host");
        when(localHost.getCanonicalHostName()).thenReturn("my-host.example.com");

        try (var addresses = mockStatic(InetAddress.class)) {
            addresses.when(InetAddress::getLocalHost).thenReturn(localHost);

            // when
            var hostname = lookUpCanonicalHostName();

            // then
            assertThat(hostname).isEqualTo("my-host.example.com");
        }
    }

    @Test
    @DisplayName("Falls back to localhost when the local machine has no name that resolves")
    void testHostnameOfAnUnknownLocalHost() {
        // given: a machine whose own name resolves to no address, as in a container given a host name its
        // resolver knows nothing of
        try (var addresses = mockStatic(InetAddress.class)) {
            addresses.when(InetAddress::getLocalHost).thenThrow(new UnknownHostException("my-host: Name or service not known"));

            // when
            var hostname = lookUpCanonicalHostName();

            // then
            assertThat(hostname).isEqualTo("localhost");
        }
    }

    @Test
    @DisplayName("Gives the host name its lookup finds, and localhost to a call made before it found it")
    void testInitialHostnameIsTheOneLookedUp() throws Exception {
        // given: the name of this machine, looked up for as long as it takes
        var hostname = lookUpCanonicalHostName();

        // when: asked until the lookup it started in the background is over, which takes more than the 5 seconds a
        // call waits for it on a machine slow to resolve its own name
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(30);
        var initialHostname = getInitialHostname();
        while (!initialHostname.equals(hostname) && System.nanoTime() < deadlineNanos) {
            assertThat(initialHostname).as("The name given while the lookup goes on").isEqualTo("localhost");
            Thread.sleep(100);
            initialHostname = getInitialHostname();
        }

        // then: the name it found, rather than localhost for good. Where localhost is the name of the machine, as
        // without a network, this cannot tell them apart
        assertThat(initialHostname).isEqualTo(hostname);
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
