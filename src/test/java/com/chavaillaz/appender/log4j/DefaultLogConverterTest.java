package com.chavaillaz.appender.log4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.AbstractLogEvent;
import org.apache.logging.log4j.core.impl.JdkMapAdapterStringMap;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
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
        assertThat(document.get("datetime")).isEqualTo(Instant.ofEpochMilli(epochMilli).toString());
        assertThat(document.get("host")).isEqualTo("my-host");
        assertThat(document.get("environment")).isEqualTo("pre-production");
        assertThat(document.get("application")).isEqualTo("my-application");
        assertThat(document.get("logger")).isEqualTo("com.chavaillaz.appender.log4j.MyClass");
        assertThat(document.get("level")).isEqualTo("ERROR");
        assertThat(document.get("thread")).isEqualTo("thread-name");
        assertThat(document.get("logmessage")).isEqualTo("Correct horse battery staple");
        assertThat(document.get("mdc-key")).isEqualTo("mdc-value");
        assertThat(document.get("class")).isEqualTo("java.lang.RuntimeException");
        String stacktrace = document.get("stacktrace").toString();
        assertThat(stacktrace).contains("java.lang.RuntimeException: my-exception");
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
            assertThat(document).doesNotContainKey("class");
            assertThat(document).doesNotContainKey("stacktrace");
        }

        @Test
        @DisplayName("Converts an event carrying no MDC context data at all, without a warning")
        void testConversionWithoutMdc() {
            // given: an event answering null for its context data, as one of a custom LogEventFactory may, where a
            // Log4jLogEvent carries a map at least, though an empty one
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
            var event = new AbstractLogEvent() {
                @Override
                public Level getLevel() {
                    return Level.INFO;
                }

                @Override
                public String getLoggerName() {
                    return "com.chavaillaz.appender.log4j.MyClass";
                }

                @Override
                public Message getMessage() {
                    return new SimpleMessage("No MDC here");
                }
            };
            assertThat(event.getContextData()).as("This event should carry no context data").isNull();

            // when
            var documents = new ArrayList<Map<String, Object>>();
            List<StatusData> statuses = captureStatuses(() -> documents.add(converter.convert(event)));

            // then: the fields of the event alone, and nothing said of context data it has no reason to carry
            assertThat(documents).singleElement().satisfies(document -> {
                assertThat(document).containsOnlyKeys(
                        "datetime", "host", "environment", "application", "logger", "level", "logmessage", "thread");
                assertThat(document).containsEntry("logmessage", "No MDC here");
            });
            assertThat(statuses).extracting(StatusData::getFormattedStatus).isEmpty();
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
            assertThat(document.get("host")).isEqualTo("real-host");
        }

        @Test
        @DisplayName("Does not let an MDC entry fill a reserved field left null")
        void testConversionMdcDoesNotFillReservedFieldsLeftNull() {
            // given: a configuration without host, and an MDC entry of that name, the host a request came from
            var configuration = TestLogConfiguration.builder().host(null).build();
            var converter = new DefaultLogConverter(configuration);
            var mdc = new JdkMapAdapterStringMap();
            mdc.putValue("host", "client.example.com");
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setContextData(mdc)
                    .build();

            // when
            var document = converter.convert(event);

            // then: the field keeps what the converter wrote, whatever the MDC of the event holds
            assertThat(document).containsEntry("host", null);
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
            assertThat(document.get("logger")).isEqualTo("com.chavaillaz.appender.log4j.MyClass");
        }

        @Test
        @DisplayName("Falls back to the class of the logger when the logger name is missing")
        void testConversionFallsBackToFqcnWhenLoggerNameMissing() {
            // given
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new SimpleMessage("Message"))
                    .setLoggerName(null)
                    .setLoggerFqcn("org.apache.logging.log4j.spi.AbstractLogger")
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertThat(document.get("logger")).isEqualTo("org.apache.logging.log4j.spi.AbstractLogger");
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
            assertThat(document.get("logmessage")).isNull();
        }

        @Test
        @DisplayName("Cannot be built without a configuration, rather than converting without it")
        void testConverterRequiresAConfiguration() {
            // when / then: refused when built, where a converter without one used to omit the fields it provides
            assertThatThrownBy(() -> new DefaultLogConverter(null)).isInstanceOf(NullPointerException.class);
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
            assertThat(document.get("logmessage")).isEqualTo("0123456789... (truncated, 6 more characters)");
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
            assertThat(document.get("logmessage")).isEqualTo("ab... (truncated, 4 more characters)");
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
            assertThat(document.get("logmessage")).isEqualTo("A perfectly ordinary log line");
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
            assertThat(document.get("logmessage")).isEqualTo(message);
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
            assertThat(document.get("stacktrace"))
                    .isEqualTo(fullStackTrace.toString().substring(0, 50) + "... (truncated)");
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
            assertThat(printed.get())
                    .as("Only the causes within the limit should have been printed, but " + printed.get() + " were")
                    .isLessThan(100);
            assertThat(document.get("stacktrace").toString()).endsWith("... (truncated)");
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
            assertThat(document.get("stacktrace").toString()).doesNotContain("truncated");
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
            assertThat(stacktrace).doesNotContain("truncated");
            assertThat(stacktrace).as("The full (untruncated) cause chain should be present").contains("innermost");
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
            var document = converter.convert(event);

            // then
            assertThat(document.get("logger")).isEqualTo("com.chavaillaz.appender.log4j.MyClass");
            assertThat(document.get("level")).isEqualTo("INFO");
            assertThat(document.get("logmessage").toString()).contains("unable to format message");
        }

        @Test
        @DisplayName("Names the failure of a message by its class when the failure cannot describe itself either")
        void testConversionToleratesAMessageFailureThatCannotDescribeItself() {
            // given: a message failing with an exception whose getMessage() throws as well, which the placeholder
            // of the message used to run into, losing the whole document, and its report as dropped with it
            var configuration = TestLogConfiguration.builder().build();
            var converter = new DefaultLogConverter(configuration);
            var event = Log4jLogEvent.newBuilder()
                    .setMessage(new ThrowingMessage(new ThrowingThrowable()))
                    .setLoggerName("com.chavaillaz.appender.log4j.MyClass")
                    .setLevel(Level.INFO)
                    .build();

            // when
            var document = converter.convert(event);

            // then
            assertThat(document.get("level")).isEqualTo("INFO");
            assertThat(document.get("logmessage"))
                    .isEqualTo("<unable to format message: " + ThrowingThrowable.class.getName() + ">");
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
            var document = converter.convert(event);

            // then
            assertThat(document.get("class")).isEqualTo(ThrowingThrowable.class.getCanonicalName());
            assertThat(document).doesNotContainKey("stacktrace");
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
            var document = converter.convert(event);

            // then: only the field the configuration could not provide is left null, instead of the whole
            // document -- the log message included -- being lost with it, or the fields read after it
            assertThat(document.get("logmessage")).isEqualTo("Correct horse battery staple");
            assertThat(document.get("level")).isEqualTo("ERROR");
            assertThat(document).containsEntry("environment", null);
            assertThat(document.get("host")).isEqualTo("my-host");
            assertThat(document.get("application")).isEqualTo("my-application");
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
            var document = converter.convert(event);

            // then: only the message it could not build was replaced, instead of the whole document being
            // lost along with every other field already written into it
            assertThat(document.get("logger")).isEqualTo("com.chavaillaz.appender.log4j.MyClass");
            assertThat(document.get("level")).isEqualTo("INFO");
            assertThat(document.get("logmessage").toString()).contains("unable to format message");
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
            assertThat(thrown.getClass().getCanonicalName())
                    .as("This exception should have no canonical name")
                    .isNull();
            assertThat(document.get("class")).isEqualTo(thrown.getClass().getName());
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
            var document = converter.convert(event);

            // then
            assertThat(document.get("logger")).isEqualTo("com.chavaillaz.appender.log4j.MyClass");
            assertThat(document.get("level")).isEqualTo("INFO");
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
            var document = converter.convert(event);

            // then: sized for what it holds, rather than failing to size for a count no map can reach
            assertThat(document.get("logmessage")).isEqualTo("Message");
        }

    }

    /**
     * Runs the given action while capturing what is logged on the status channel of Log4j2 from warnings up.
     */
    private static List<StatusData> captureStatuses(Runnable action) {
        var statuses = new CopyOnWriteArrayList<StatusData>();
        var statusListener = new StatusListener() {
            @Override
            public void log(StatusData data) {
                statuses.add(data);
            }

            @Override
            public Level getStatusLevel() {
                return Level.WARN;
            }

            @Override
            public void close() {
                // Nothing to release
            }
        };
        StatusLogger.getLogger().registerListener(statusListener);
        try {
            action.run();
        } finally {
            StatusLogger.getLogger().removeListener(statusListener);
        }
        return statuses;
    }

    private static class ThrowingMessage implements Message {

        private final RuntimeException failure;

        ThrowingMessage() {
            this(new RuntimeException("Simulated formatting failure"));
        }

        ThrowingMessage(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String getFormattedMessage() {
            throw failure;
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
