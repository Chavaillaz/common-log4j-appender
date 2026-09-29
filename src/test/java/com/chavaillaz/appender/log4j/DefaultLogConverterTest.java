package com.chavaillaz.appender.log4j;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.chavaillaz.appender.LogConfiguration;

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
        var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertNull(document.get("logmessage"));
        }

        @Test
        @DisplayName("Cannot be built without a configuration, rather than converting without it")
        void testConverterRequiresAConfiguration() {
            // when / then: refused when built, where a converter without one used to omit the fields it provides
            assertThrows(NullPointerException.class, () -> new DefaultLogConverter(null));
        }

        @Test
        @DisplayName("Truncates the message once it exceeds the configured maximum length")
        void testConversionTruncatesLongMessage() {
            // given: a single log call carrying a large payload (a response body, a serialized entity, a
            // dumped collection), the one part of a document whose size the application controls entirely
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration) {
                @Override
                protected int getMaxMessageLength() {
                    return 10;
                }
            };
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("0123456789abcdef"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .build();

            // when
            var document = converter.convert(event);

            // then: truncated, and visibly so, rather than inflating the whole batch it travels in
            assertEquals("0123456789... (truncated, 6 more characters)", document.get("logmessage"));
        }

        @Test
        @DisplayName("Cuts before a surrogate pair rather than splitting one at the limit")
        void testConversionDoesNotSplitASurrogatePair() {
            // given: a message whose limit falls between the two halves of an emoji, which is a matter of
            // where the message happens to reach the limit rather than of anything exotic
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration) {
                @Override
                protected int getMaxMessageLength() {
                    return 3;
                }
            };
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("ab😀cd"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .build();

            // when
            var document = converter.convert(event);

            // then: the emoji is dropped whole instead of leaving its high half behind, which is not a
            // character at all and encodes to a replacement one in whatever request carries the document
            assertEquals("ab... (truncated, 4 more characters)", document.get("logmessage"));
        }

        @Test
        @DisplayName("Does not truncate a message within the configured maximum length")
        void testConversionDoesNotTruncateShortMessage() {
            // given
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("A perfectly ordinary log line"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertEquals("A perfectly ordinary log line", document.get("logmessage"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        @DisplayName("Disables message truncation entirely when the configured maximum length is zero or negative")
        void testNonPositiveMaxMessageLengthDisablesTruncation(int maxMessageLength) {
            // given: a message well past the default limit, so only the override under test can keep it whole
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration) {
                @Override
                protected int getMaxMessageLength() {
                    return maxMessageLength;
                }
            };
            String message = "x".repeat(64_000);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage(message))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertEquals(message, document.get("logmessage"));
        }

        @Test
        @DisplayName("Truncates the stack trace once it exceeds the configured maximum length")
        void testConversionTruncatesLongStackTrace() {
            // given
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration) {
                @Override
                protected int getMaxStackTraceLength() {
                    return 50;
                }
            };
            var exception = new RuntimeException("my-exception");
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setThrown(exception)
                    .build();

            // when
            var document = converter.convert(event);

            // then: the first characters of the stack trace, as printed in full
            var fullStackTrace = new StringWriter();
            exception.printStackTrace(new PrintWriter(fullStackTrace));
            assertEquals(fullStackTrace.toString().substring(0, 50) + "... (truncated)", document.get("stacktrace"));
        }

        @Test
        @DisplayName("Stops printing a stack trace once past the configured maximum length rather than printing it all")
        void testConversionStopsPrintingPastTheMaximumLength() {
            // given: a cause chain a thousand deep, as a framework wrapping an exception at each layer builds,
            // each cause counting how often it is printed
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration) {
                @Override
                protected int getMaxStackTraceLength() {
                    return 1_000;
                }
            };
            var printed = new AtomicInteger();
            Throwable exception = new CountedException("innermost", null, printed);
            for (int i = 0; i < 1_000; i++) {
                exception = new CountedException("wrapper-" + i, exception, printed);
            }
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setThrown(exception)
                    .build();

            // when
            var document = converter.convert(event);

            // then: only the causes that fit were printed, rather than all of them only to cut the rest
            assertTrue(printed.get() < 100, "Only the causes within the limit should have been printed, but " + printed.get() + " were");
            assertTrue(document.get("stacktrace").toString().endsWith("... (truncated)"));
        }

        @Test
        @DisplayName("Does not truncate a stack trace within the configured maximum length")
        void testConversionDoesNotTruncateShortStackTrace() {
            // given: a stack trace pinned to a single frame, since the real one at this point in the test
            // includes the whole JUnit/reflection call chain and could otherwise exceed the default limit
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration) {
                @Override
                protected int getMaxStackTraceLength() {
                    return maxStackTraceLength;
                }
            };
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
            var converter = new DefaultLogConverter(configuration);
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
            var converter = new DefaultLogConverter(configuration);
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
        @DisplayName("Keeps the rest of the document when the configuration cannot answer for its own fields")
        void testConversionToleratesAThrowingConfiguration() {
            // given: a configuration resolving a value on demand rather than answering from a field, and
            // failing at it -- the last caller-supplied code in writeBasic() left unguarded, while the
            // message, MDC and stack trace around it each already fall back instead
            var converter = new DefaultLogConverter(new LogConfiguration() {
                @Override
                public String getApplication() {
                    return "my-application";
                }

                @Override
                public String getHost() {
                    return "my-host";
                }

                @Override
                public String getEnvironment() {
                    throw new IllegalStateException("Simulated configuration failure");
                }
            });
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Correct horse battery staple"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.ERROR)
                    .build();

            // when
            var document = assertDoesNotThrow(() -> converter.convert(event));

            // then: only the fields the configuration could not provide are omitted, instead of the whole
            // document -- the log message included -- being lost with it
            assertEquals("Correct horse battery staple", document.get("logmessage"));
            assertEquals("ERROR", document.get("level"));
            assertEquals("my-host", document.get("host"), "The field read before the failure should be kept");
            assertNull(document.get("environment"));
            assertNull(document.get("application"));
        }

        @Test
        @DisplayName("Keeps the rest of the document when a field's own code fails with an Error rather than an exception")
        void testConversionToleratesAnErrorFromAFieldsOwnCode() {
            // given: a message whose formatting overflows the stack, which is what building one out of a
            // structure that refers back to itself does -- and an Error, so none of the guards meant to
            // confine such a failure to its one field used to see it
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new ErrorThrowingMessage())
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when
            var document = assertDoesNotThrow(() -> converter.convert(event));

            // then: only the message it could not build was replaced, instead of the whole document being
            // lost along with every other field already written into it
            assertEquals("com.chavaillaz.appender.log4j.MyClass", document.get("logger"));
            assertEquals("INFO", document.get("level"));
            assertTrue(document.get("logmessage").toString().contains("unable to format message"));
        }

        @Test
        @DisplayName("Names an anonymous exception class by its binary name, which it has, rather than not at all")
        void testConversionNamesAnAnonymousThrowable() {
            // given: an exception declared on the spot, the shape `throw new RuntimeException(...) {}` and
            // every exception declared inside a method produce, and which has no canonical name at all
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
            var thrown = new RuntimeException("boom") {
                // Anonymous on purpose: Class#getCanonicalName() answers null for it
            };
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setThrown(thrown)
                    .build();

            // when
            var document = converter.convert(event);

            // then: the one field saying what went wrong is filled in, where it used to come out null on
            // exactly the events most worth looking at
            assertNull(thrown.getClass().getCanonicalName(), "This exception should have no canonical name");
            assertEquals(thrown.getClass().getName(), document.get("class"));
        }

        @Test
        @DisplayName("Falls back to omitting MDC fields instead of losing the whole document when context data fails to be read")
        void testConversionToleratesThrowingContextData() {
            // given: a custom ReadOnlyStringMap (unlike the one bundled with Log4j2) whose size() and forEach()
            // throw, which must not prevent every other field from being captured, the document being sized
            // before any of them is written
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
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

        @Test
        @DisplayName("Converts an event whose context data claims more entries than a document can hold")
        void testConversionToleratesAContextDataOverstatingItsSize() {
            // given: a custom context data holding nothing, whose size() says otherwise
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .setContextData(new ThrowingContextData() {
                        @Override
                        public <V> void forEach(BiConsumer<String, ? super V> action) {
                            // Holds nothing
                        }

                        @Override
                        public int size() {
                            return Integer.MAX_VALUE;
                        }
                    })
                    .build();

            // when
            var document = assertDoesNotThrow(() -> converter.convert(event));

            // then: sized for what it holds, rather than failing to size for a count no map can reach
            assertEquals("Message", document.get("logmessage"));
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

    /**
     * The {@link ThrowingMessage} counterpart failing with an {@link Error} instead of an exception, as
     * formatting a message out of a structure that refers back to itself does.
     */
    private static class ErrorThrowingMessage implements Message {

        @Override
        public String getFormattedMessage() {
            throw new StackOverflowError("Simulated recursive formatting");
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

    /**
     * An exception counting how often it is printed, which its stack trace does through its description.
     */
    private static class CountedException extends RuntimeException {

        private final transient AtomicInteger printed;

        CountedException(String message, Throwable cause, AtomicInteger printed) {
            super(message, cause);
            this.printed = printed;
        }

        @Override
        public String toString() {
            printed.incrementAndGet();
            return super.toString();
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
            throw new RuntimeException("Simulated context data failure");
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
