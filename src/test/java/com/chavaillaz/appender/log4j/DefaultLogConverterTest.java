package com.chavaillaz.appender.log4j;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.JdkMapAdapterStringMap;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.BiConsumer;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.apache.logging.log4j.util.StringMap;
import org.apache.logging.log4j.util.TriConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DefaultLogConverter")
class DefaultLogConverterTest {

    @Test
    @DisplayName("Converts every field of a fully populated logging event")
    void testConversion() {
        // given
        var configuration = TestLogConfiguration.builder()
                .host("my-host")
                .environment("pre-production")
                .application("my-application").build();
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
            var configuration = TestLogConfiguration.builder().build();
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
            var configuration = TestLogConfiguration.builder().build();
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
            var configuration = TestLogConfiguration.builder().host("real-host").build();
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
        @DisplayName("Prefers the logger name over the internal fully qualified caller class name")
        void testConversionPrefersLoggerNameOverFqcn() {
            // given: loggerFqcn identifies the internal wrapper class Log4j2 uses to locate the caller's
            // stack frame for %C/%class-style location info (e.g. "org.apache.logging.log4j.spi.AbstractLogger"
            // for a plain Logger.info() call, or an SLF4J bridge class when going through that facade); it is
            // not a meaningful logger/category name and must never win over the actual logger name
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLoggerFqcn("org.apache.logging.log4j.spi.AbstractLogger")
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertEquals("com.chavaillaz.appender.log4j.MyClass", document.get("logger"));
        }

        @Test
        @DisplayName("Falls back to the fully qualified caller class name when the logger name is missing")
        void testConversionFallsBackToFqcnWhenLoggerNameMissing() {
            // given
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName(null)
                    .setLoggerFqcn("com.chavaillaz.appender.log4j.MyClass")
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
            var configuration = TestLogConfiguration.builder().build();
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

        @Test
        @DisplayName("Truncates the stack trace once it exceeds the configured maximum length")
        void testConversionTruncatesLongStackTrace() {
            // given
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter() {
                @Override
                protected int getMaxStackTraceLength() {
                    return 50;
                }
            };
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setThrown(new RuntimeException("my-exception"))
                    .build();

            // when
            var document = converter.convert(event);

            // then
            String stacktrace = document.get("stacktrace").toString();
            assertEquals(50, stacktrace.indexOf("... (truncated"));
            assertTrue(stacktrace.startsWith("java.lang.RuntimeException: my-exception"));
        }

        @Test
        @DisplayName("Does not truncate a stack trace within the configured maximum length")
        void testConversionDoesNotTruncateShortStackTrace() {
            // given: a stack trace pinned to a single frame, since the real one at this point in the test
            // includes the whole JUnit/reflection call chain and could otherwise exceed the default limit
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var exception = new RuntimeException("my-exception");
            exception.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.chavaillaz.appender.log4j.MyClass", "myMethod", "MyClass.java", 42)
            });
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setThrown(exception)
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertFalse(document.get("stacktrace").toString().contains("truncated"));
        }

        @ParameterizedTest(name = "maxStackTraceLength = {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("Disables truncation entirely when the configured maximum length is zero or negative")
        void testNonPositiveMaxStackTraceLengthDisablesTruncation(int maxStackTraceLength) {
            // given: a deliberately deep (and therefore long) cause chain, which would normally exceed
            // the default limit and be truncated
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter() {
                @Override
                protected int getMaxStackTraceLength() {
                    return maxStackTraceLength;
                }
            };
            converter.configure(configuration);
            Throwable exception = new RuntimeException("innermost");
            for (int i = 0; i < 200; i++) {
                exception = new RuntimeException("wrapper-" + i, exception);
            }
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setThrown(exception)
                    .build();

            // when
            var document = converter.convert(event);

            // then
            String stacktrace = document.get("stacktrace").toString();
            assertFalse(stacktrace.contains("truncated"));
            assertTrue(stacktrace.contains("innermost"), "The full (untruncated) cause chain should be present");
        }

        @Test
        @DisplayName("Falls back to a placeholder instead of losing the whole document when a message fails to format")
        void testConversionToleratesThrowingMessage() {
            // given: a custom Message implementation (unlike every one bundled with Log4j2) that throws
            // while formatting itself, which must not prevent every other field from being captured
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new ThrowingMessage())
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when
            var document = assertDoesNotThrow(() -> converter.convert(event));

            // then
            assertEquals("com.chavaillaz.appender.log4j.MyClass", document.get("logger"));
            assertEquals("INFO", document.get("level"));
            assertTrue(document.get("logmessage").toString().contains("unable to format message"));
        }

        @Test
        @DisplayName("Falls back to omitting the field instead of losing the whole document when a stack trace fails to compute")
        void testConversionToleratesThrowingThrowable() {
            // given: a custom Throwable whose getMessage() throws, which Throwable#printStackTrace()
            // relies on indirectly (through toString()) while writing every stack frame; this must not
            // prevent every other field, including "class", from being captured
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setThrown(new ThrowingThrowable())
                    .build();

            // when
            var document = assertDoesNotThrow(() -> converter.convert(event));

            // then
            assertEquals(ThrowingThrowable.class.getCanonicalName(), document.get("class"));
            assertFalse(document.containsKey("stacktrace"));
        }

        @Test
        @DisplayName("Falls back to omitting MDC fields instead of losing the whole document when context data fails to be read")
        void testConversionToleratesThrowingContextData() {
            // given: a custom ReadOnlyStringMap (unlike the one bundled with Log4j2) whose forEach() throws,
            // which must not prevent every other field, already written before writeMDC() runs, from being
            // captured
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter();
            converter.configure(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .setContextData(new ThrowingContextData())
                    .build();

            // when
            var document = assertDoesNotThrow(() -> converter.convert(event));

            // then
            assertEquals("com.chavaillaz.appender.log4j.MyClass", document.get("logger"));
            assertEquals("INFO", document.get("level"));
        }

    }

    private static class ThrowingMessage implements Message {

        @Override
        public String getFormattedMessage() {
            throw new RuntimeException("Simulated formatting failure");
        }

        @Override
        public Object[] getParameters() {
            return new Object[0];
        }

        @Override
        public Throwable getThrowable() {
            return null;
        }

    }

    private static class ThrowingThrowable extends RuntimeException {

        @Override
        public String getMessage() {
            throw new RuntimeException("Simulated getMessage failure");
        }

    }

    private static class ThrowingContextData implements StringMap {

        @Override
        public Map<String, String> toMap() {
            return Map.of();
        }

        @Override
        public boolean containsKey(String key) {
            return false;
        }

        @Override
        public <V> void forEach(BiConsumer<String, ? super V> action) {
            throw new RuntimeException("Simulated context data failure");
        }

        @Override
        public <V, S> void forEach(TriConsumer<String, ? super V, S> action, S state) {
            throw new RuntimeException("Simulated context data failure");
        }

        @Override
        public <V> V getValue(String key) {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void clear() {
            // Unused by this test
        }

        @Override
        public void freeze() {
            // Unused by this test
        }

        @Override
        public boolean isFrozen() {
            return false;
        }

        @Override
        public void putAll(ReadOnlyStringMap source) {
            // Unused by this test
        }

        @Override
        public void putValue(String key, Object value) {
            // Unused by this test
        }

        @Override
        public void remove(String key) {
            // Unused by this test
        }

    }

}
