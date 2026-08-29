package com.chavaillaz.appender;

import static com.chavaillaz.appender.CommonUtils.getInitialHostname;
import static com.chavaillaz.appender.CommonUtils.getProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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

}
