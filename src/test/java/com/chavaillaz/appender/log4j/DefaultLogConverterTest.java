package com.chavaillaz.appender.log4j;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.JdkMapAdapterStringMap;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultLogConverter")
class DefaultLogConverterTest {

    @Test
    @DisplayName("Converts every field of a fully populated logging event")
    void testConversion() {
        // given
        var configuration = new TestLogConfiguration();
        configuration.setHost("my-host");
        configuration.setEnvironment("pre-production");
        configuration.setApplication("my-application");
        var converter = new DefaultLogConverter();
        converter.configure(configuration);
        var mdc = new JdkMapAdapterStringMap();
        mdc.putValue("mdc-key", "mdc-value");
        var epochMilli = Instant.now().toEpochMilli();
        var exception = new RuntimeException("my-exception");
        var event = Log4jLogEvent.newBuilder()
                .setMessage(new SimpleMessage("Correct horse battery staple"))
                .setTimeMillis(epochMilli)
                .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                .setLevel(Level.ERROR)
                .setThreadName("thread-name")
                .setContextData(mdc)
                .setThrown(exception)
                .build();

        // when
        var document = converter.convert(event);

        // then
        assertEquals(Instant.ofEpochMilli(epochMilli).toString(), document.get("datetime"));
        assertEquals("my-host", document.get("host"));
        assertEquals("pre-production", document.get("environment"));
        assertEquals("my-application", document.get("application"));
        assertEquals("com.chavaillaz.appender.log4j.MyClass", document.get("logger"));
        assertEquals("ERROR", document.get("level"));
        assertEquals("thread-name", document.get("thread"));
        assertEquals("Correct horse battery staple", document.get("logmessage"));
        assertEquals("mdc-value", document.get("mdc-key"));
        assertEquals("java.lang.RuntimeException", document.get("class"));
        String stacktrace = document.get("stacktrace").toString();
        assertTrue(stacktrace.contains("java.lang.RuntimeException: my-exception"));
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Does not include exception fields when the event carries no exception")
        void testConversionWithoutException() {
            // given
            var configuration = new TestLogConfiguration();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("No exception here"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertFalse(document.containsKey("class"));
            assertFalse(document.containsKey("stacktrace"));
        }

        @Test
        @DisplayName("Does not fail when the event carries no MDC context data")
        void testConversionWithoutMdc() {
            // given
            var configuration = new TestLogConfiguration();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("No MDC here"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when / then
            assertDoesNotThrow(() -> converter.convert(event));
        }

        @Test
        @DisplayName("Does not let an MDC entry override a reserved field")
        void testConversionMdcDoesNotOverrideReservedFields() {
            // given: MDC contains a key colliding with one of the fields written by the converter itself
            var configuration = new TestLogConfiguration();
            configuration.setHost("real-host");
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var mdc = new JdkMapAdapterStringMap();
            mdc.putValue("host", "should-not-override");
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setContextData(mdc)
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertEquals("real-host", document.get("host"));
        }

        @Test
        @DisplayName("Falls back to the logger name when the fully qualified caller class name is missing")
        void testConversionFallsBackToLoggerNameWhenFqcnMissing() {
            // given
            var configuration = new TestLogConfiguration();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLoggerFqcn(null)
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertEquals("com.chavaillaz.appender.log4j.MyClass", document.get("logger"));
        }

        @Test
        @DisplayName("Converts a null message to a null logmessage field")
        void testConversionWithNullMessage() {
            // given
            var configuration = new TestLogConfiguration();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertNull(document.get("logmessage"));
        }

    }

}
