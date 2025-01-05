package com.chavaillaz.appender;

import static com.chavaillaz.appender.CommonUtils.getInitialHostname;
import static com.chavaillaz.appender.CommonUtils.getProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class CommonUtilsTest {

    @Test
    void testProperty_Unknown() {
        // when
        var value = getProperty("unknownKey", "defaultValue");

        // then
        assertEquals("defaultValue", value);
    }

    @Test
    void testProperty_System() {
        // given
        System.setProperty("knownKey", "systemValue");

        // when
        var value = getProperty("knownKey", "defaultValue");

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
