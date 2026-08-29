package com.chavaillaz.appender;

import static com.chavaillaz.appender.CommonUtils.getInitialHostname;
import static com.chavaillaz.appender.CommonUtils.getProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CommonUtilsTest {

    private static final String TEST_KEY = "commonUtilsTest.knownKey";

    @AfterEach
    void cleanup() {
        System.clearProperty(TEST_KEY);
    }

    @Test
    void testProperty_Unknown() {
        // when
        var value = getProperty("commonUtilsTest.unknownKey", "defaultValue");

        // then
        assertEquals("defaultValue", value);
    }

    @Test
    void testProperty_UnknownWithoutDefault() {
        // when
        var value = getProperty("commonUtilsTest.unknownKey", null);

        // then
        assertNull(value);
    }

    @Test
    void testProperty_System() {
        // given
        System.setProperty(TEST_KEY, "systemValue");

        // when
        var value = getProperty(TEST_KEY, "defaultValue");

        // then
        assertEquals("systemValue", value);
    }

    @Test
    void testHostname() throws Exception {
        // when
        var hostname = getInitialHostname();

        // then
        assertEquals(InetAddress.getLocalHost().getCanonicalHostName(), hostname);
    }

}
