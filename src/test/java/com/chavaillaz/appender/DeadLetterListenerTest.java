package com.chavaillaz.appender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DeadLetterListener")
class DeadLetterListenerTest {

    /**
     * The clock of the listeners here, all of them dropping on 28 September 2026.
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T10:15:30Z"), ZoneOffset.UTC);

    private final RecordingAppender appender = RecordingAppender.started("DeadLetters");

    private final DeadLetterListener listener = new DeadLetterListener(appender, CLOCK);

    @Nested
    @DisplayName("Events handed over")
    class Events {

        @Test
        @DisplayName("Hands the appender one event per dropped document, its message the document wrapped with the reason and the time it was dropped")
        void testHandsOneEventPerDocument() {
            // given
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("logmessage", "boom");
            document.put("level", "ERROR");

            // when
            listener.onDocumentsDropped(List.of(document, Map.of("id", 2)), "maximum consecutive transmission failures exceeded");

            // then: each document is wrapped, unmodified, with the reason and the instant it was dropped at
            assertThat(appender.lines()).containsExactly(
                    "{\"reason\":\"maximum consecutive transmission failures exceeded\","
                            + "\"droppedAt\":\"2026-09-28T10:15:30Z\","
                            + "\"document\":{\"logmessage\":\"boom\",\"level\":\"ERROR\"}}",
                    "{\"reason\":\"maximum consecutive transmission failures exceeded\","
                            + "\"droppedAt\":\"2026-09-28T10:15:30Z\","
                            + "\"document\":{\"id\":2}}");

            // and: in events a configuration tells apart from the logs of the application
            assertThat(appender.events()).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getLoggerName()).isEqualTo(DeadLetterListener.class.getName());
                assertThat(event.getTimeMillis()).isEqualTo(CLOCK.millis());
            });
        }

        @Test
        @DisplayName("Hands the appender nothing when no document is dropped")
        void testEmptyNotificationHandsNothing() {
            // when: an empty notification, as a listener chain forwarding one it did not filter produces
            listener.onDocumentsDropped(List.of(), "nothing was actually dropped");

            // then: nothing a dead-letter file would read as logs lost
            assertThat(appender.events()).isEmpty();
        }

        @Test
        @DisplayName("Leaves out a document the filter of the appender denies, as Log4j2 does")
        void testFilterOfTheAppenderIsHonored() {
            // given: an appender keeping fatal events only, which dead letters are not
            var filtered = new RecordingAppender("Fatal", ThresholdFilter.createFilter(Level.FATAL, Filter.Result.ACCEPT, Filter.Result.DENY));
            filtered.start();

            // when
            new DeadLetterListener(filtered, CLOCK).onDocumentsDropped(List.of(Map.of("id", 1)), "reason");

            // then
            assertThat(filtered.events()).isEmpty();
        }

    }

    @Nested
    @DisplayName("JSON lines")
    class JsonLines {

        @Test
        @DisplayName("Escapes characters JSON requires escaping in string values")
        void testEscapesSpecialCharacters() {
            // given
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("logmessage", "line one\nline \"two\"\\three\ttab");

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then
            assertThat(line()).contains("\"logmessage\":\"line one\\nline \\\"two\\\"\\\\three\\ttab\"");
        }

        @Test
        @DisplayName("Escapes control characters too, which JSON does not allow raw in a string")
        void testEscapesControlCharacters() {
            // given: a message carrying a carriage return, a NUL, the escape starting a terminal color, a backspace,
            // a form feed and a unit separator, as the output of a command or a binary payload logged as text does
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("logmessage", "one\r\ntwo" + (char) 0x00 + (char) 0x1b + "[31mred\b\f" + (char) 0x1f + "end");

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then: the carriage return and the line feed by their own escapes, the others by their code, so that
            // the document stays on one line
            String line = line();
            assertThat(line).contains("\"logmessage\":\"one\\r\\ntwo\\u0000\\u001b[31mred\\u0008\\u000c\\u001fend\"");

            // and: nothing a JSON parser refuses is left raw in it
            assertThat(line.chars().filter(character -> character < 0x20).boxed().toList())
                    .as("Control characters left raw in the line")
                    .isEmpty();
        }

        @Test
        @DisplayName("Saves half of a surrogate pair as its JSON escape rather than losing it")
        void testLoneSurrogateIsEscaped() {
            // given: a message cut in the middle of an emoji, as any substring() of user content can produce
            String emoji = "\uD83D\uDE00";
            Map<String, Object> cut = new LinkedHashMap<>();
            cut.put("logmessage", ("cut " + emoji).substring(0, 5));
            cut.put("tail", emoji.substring(1) + " orphan");
            cut.put("intact", emoji);

            // when
            listener.onDocumentsDropped(List.of(cut), "reason");

            // then: each half is kept as the JSON escape for it, while a pair that is whole is written as is
            String line = line();
            assertThat(line).contains("\"logmessage\":\"cut \\ud83d\"");
            assertThat(line).contains("\"tail\":\"\\ude00 orphan\"");
            assertThat(line).contains("\"intact\":\"" + emoji + "\"");
        }

        @Test
        @DisplayName("Serializes nested maps, lists, numbers, booleans and null correctly")
        void testSerializesNestedStructures() {
            // given
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("code", 42);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("count", 3);
            document.put("success", false);
            document.put("missing", null);
            document.put("tags", List.of("a", "b"));
            document.put("nested", nested);

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then
            assertThat(line()).endsWith(",\"document\":{\"count\":3,\"success\":false,\"missing\":null,"
                    + "\"tags\":[\"a\",\"b\"],\"nested\":{\"code\":42}}}");
        }

        @Test
        @DisplayName("Writes an array as a JSON array rather than as its identity hash")
        void testSerializesArrays() {
            // given: arrays of objects and of primitives, as a custom converter putting the parameters of a
            // message in its document produces
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("parameters", new Object[]{"a", 1, null});
            document.put("codes", new int[]{4, 2});

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then: their content is saved, where "[Ljava.lang.Object;@1b6d3586" saved nothing of it
            assertThat(line()).endsWith(",\"document\":{\"parameters\":[\"a\",1,null],\"codes\":[4,2]}}");
        }

        @Test
        @DisplayName("Keeps a number JSON cannot express as a string instead of writing an unreadable line")
        void testNonFiniteNumbersStayParseable() {
            // given: a computed value that happened to come out as NaN or an infinity, which render as bare
            // NaN/Infinity tokens no JSON parser accepts
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("ratio", Double.NaN);
            document.put("rate", Double.POSITIVE_INFINITY);
            document.put("drift", Float.NEGATIVE_INFINITY);
            document.put("count", 7);

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then: the values are preserved, quoted, and the line as a whole stays readable
            String line = line();
            assertThat(line).endsWith(",\"document\":{\"ratio\":\"NaN\",\"rate\":\"Infinity\","
                    + "\"drift\":\"-Infinity\",\"count\":7}}");
            assertBalancedJson(line);
        }

        @Test
        @DisplayName("Keeps a custom Number rendering as something other than a JSON number as a string")
        void testCustomNumberRenderingIsQuoted() {
            // given: a Number subclass is free to render as anything at all (a separator, a currency symbol,
            // a locale-specific decimal comma), which would break the line just as badly
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("amount", new Number() {
                @Override public int intValue() { return 1; }
                @Override public long longValue() { return 1L; }
                @Override public float floatValue() { return 1.5f; }
                @Override public double doubleValue() { return 1.5d; }
                @Override public String toString() { return "1,50 €"; }
            });

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then
            String line = line();
            assertThat(line).endsWith(",\"document\":{\"amount\":\"1,50 €\"}}");
            assertBalancedJson(line);
        }

        @Test
        @DisplayName("Writes the numbers JSON does accept as bare numbers")
        void testValidNumbersStayUnquoted() {
            // given
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("integer", 42);
            document.put("negative", -7L);
            document.put("decimal", 1.5d);
            document.put("exponent", 1e20d);
            document.put("big", new BigDecimal("123.456"));

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then: none of them are quoted, so a consumer still reads them back as numbers
            assertThat(line()).endsWith(",\"document\":{\"integer\":42,\"negative\":-7,\"decimal\":1.5,"
                    + "\"exponent\":1.0E20,\"big\":123.456}}");
        }

        @Test
        @DisplayName("Truncates a self-referential document instead of overflowing the stack")
        void testSelfReferentialDocumentIsTruncated() {
            // given: a document reachable from itself, e.g. an application logging a list that holds the very
            // map being logged. A StackOverflowError here is an Error, so it slips past every catch(Exception)
            // guard in the pipeline and escapes through close(), on the shutdown path this sink serves.
            Map<String, Object> document = new LinkedHashMap<>();
            List<Object> holder = new ArrayList<>();
            holder.add(document);
            document.put("self", holder);

            // when
            assertThatNoException().isThrownBy(() -> listener.onDocumentsDropped(List.of(document), "reason"));

            // then: the line is still handed over, truncated where the cycle was cut, and still readable
            String line = line();
            assertThat(line).contains("truncated: maximum nesting depth");
            assertBalancedJson(line);
        }

        @Test
        @DisplayName("Truncates a document nested deeper than the limit instead of overflowing the stack")
        void testDeeplyNestedDocumentIsTruncated() {
            // given: no cycle, just a structure deep enough to exhaust the stack on its own
            Map<String, Object> deepest = new LinkedHashMap<>();
            deepest.put("bottom", "reached");
            Object current = deepest;
            for (int i = 0; i < 10_000; i++) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("next", current);
                current = wrapper;
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("chain", current);

            // when
            assertThatNoException().isThrownBy(() -> listener.onDocumentsDropped(List.of(document), "reason"));

            // then
            String line = line();
            assertThat(line).contains("truncated: maximum nesting depth");
            assertBalancedJson(line);
        }

        @Test
        @DisplayName("Writes nesting up to the depth limit in full")
        void testNestingWithinTheLimitIsWrittenInFull() {
            // given: the wrapper adds one level, so a document nested well inside the limit must survive intact
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("a", Map.of("b", Map.of("c", Map.of("d", "leaf"))));

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then
            String line = line();
            assertThat(line).endsWith(",\"document\":{\"a\":{\"b\":{\"c\":{\"d\":\"leaf\"}}}}}");
            assertThat(line).doesNotContain("truncated");
        }

        @Test
        @DisplayName("Saves the rest of a document when one of its values cannot be rendered")
        void testUnrenderableValueOnlyCostsItself() {
            // given: a document holding a value whose toString() throws, as any application object put into a
            // document by a custom converter is free to do
            Map<String, Object> unrenderable = new LinkedHashMap<>();
            unrenderable.put("logmessage", "kept");
            unrenderable.put("payload", new Object() {
                @Override
                public String toString() {
                    throw new IllegalStateException("cannot render");
                }
            });
            // An Error is the other half of the same problem: a value whose toString() recurses ends in a
            // StackOverflowError, which the bounded nesting depth here cannot rule out, since that recursion
            // happens inside the value rather than between the documents this class walks
            Map<String, Object> recursive = new LinkedHashMap<>();
            recursive.put("payload", new Object() {
                @Override
                public String toString() {
                    throw new StackOverflowError("recursive rendering");
                }
            });
            recursive.put("logmessage", "kept too");

            // when: they are handed over in the middle of a batch, exactly as a pending batch would be
            assertThatNoException()
                    .isThrownBy(() -> listener.onDocumentsDropped(
                            List.of(Map.of("logmessage", "first"), unrenderable, recursive, Map.of("logmessage", "second")),
                            "reason"));

            // then: the documents around them were handed over, instead of being discarded a second time by the
            // very sink meant to be their last resort
            List<String> lines = appender.lines();
            assertThat(lines).hasSize(4);
            assertThat(lines.get(0)).contains("\"logmessage\":\"first\"");
            assertThat(lines.get(3)).contains("\"logmessage\":\"second\"");

            // and: each value that could not be rendered is replaced by its failure, the rest of its document kept
            assertThat(lines.get(1)).endsWith(
                    "\"document\":{\"logmessage\":\"kept\",\"payload\":\"<unrenderable: java.lang.IllegalStateException>\"}}");
            assertThat(lines.get(2)).endsWith(
                    "\"document\":{\"payload\":\"<unrenderable: java.lang.StackOverflowError>\",\"logmessage\":\"kept too\"}}");
        }

        @Test
        @DisplayName("Replaces a collection failing partway through its iteration as a whole, keeping the line valid")
        void testCollectionFailingPartwayIsReplacedWhole() {
            // given: a custom collection whose iteration fails after its first element was written
            Iterable<String> failingPartway = () -> new Iterator<>() {
                private boolean first = true;

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public String next() {
                    if (!first) {
                        throw new ConcurrentModificationException();
                    }
                    first = false;
                    return "written";
                }
            };
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("items", failingPartway);
            document.put("logmessage", "kept");

            // when
            listener.onDocumentsDropped(List.of(document), "reason");

            // then: what it had written was taken back rather than left as an unclosed array
            String line = line();
            assertBalancedJson(line);
            assertThat(line).endsWith(
                    "\"document\":{\"items\":\"<unrenderable: java.util.ConcurrentModificationException>\",\"logmessage\":\"kept\"}}");
        }

    }

    @Nested
    @DisplayName("When the appender does not take a document")
    class AppendFailure {

        @Test
        @DisplayName("Hands the document an appender failed on, and those after it, to onAppendFailure instead of throwing")
        void testFailureIsHandedOverInsteadOfThrown() {
            // given: an appender failing from the second event on, as a full disk makes a file appender fail
            var cause = new AppenderLoggingException("No space left on device", new IOException("No space left on device"));
            var failing = new RecordingAppender("Failing", null) {
                @Override
                public void append(LogEvent event) {
                    if (!events().isEmpty()) {
                        throw cause;
                    }
                    super.append(event);
                }
            };
            failing.start();
            var failures = new CopyOnWriteArrayList<List<Map<String, Object>>>();
            var causes = new CopyOnWriteArrayList<Throwable>();
            var recording = new DeadLetterListener(failing, CLOCK) {
                @Override
                protected void onAppendFailure(List<Map<String, Object>> documents, String reason, @Nullable Throwable thrown) {
                    failures.add(documents);
                    causes.add(thrown);
                }
            };

            // when / then
            assertThatNoException().isThrownBy(() -> recording.onDocumentsDropped(
                    List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)), "reason"));
            assertThat(failing.events()).hasSize(1);
            assertThat(failures).containsExactly(List.of(Map.of("id", 2), Map.of("id", 3)));
            assertThat(causes).containsExactly(cause);
        }

        @Test
        @DisplayName("Hands every document to onAppendFailure while the appender is not started")
        void testStoppedAppenderIsNotCalled() {
            // given: an appender stopped already, as one stopped along with its configuration
            var stopped = RecordingAppender.started("Stopped");
            stopped.stop();
            var failures = new CopyOnWriteArrayList<List<Map<String, Object>>>();
            var causes = new ArrayList<@Nullable Throwable>();
            var recording = new DeadLetterListener(stopped, CLOCK) {
                @Override
                protected void onAppendFailure(List<Map<String, Object>> documents, String reason, @Nullable Throwable cause) {
                    failures.add(documents);
                    causes.add(cause);
                }
            };

            // when
            recording.onDocumentsDropped(List.of(Map.of("id", 1), Map.of("id", 2)), "reason");

            // then
            assertThat(stopped.events()).isEmpty();
            assertThat(failures).containsExactly(List.of(Map.of("id", 1), Map.of("id", 2)));
            assertThat(causes).containsExactly((Throwable) null);
        }

        @Test
        @DisplayName("Logs the documents the appender did not take by default, so that they appear somewhere")
        void testDefaultFailureLogsTheDocuments() {
            // given: an appender stopped already, and onAppendFailure left as it is
            var stopped = RecordingAppender.started("Stopped");
            stopped.stop();
            var defaultListener = new DeadLetterListener(stopped, CLOCK);
            var errors = new CopyOnWriteArrayList<StatusData>();
            var statusListener = new StatusListener() {
                @Override
                public void log(StatusData data) {
                    errors.add(data);
                }

                @Override
                public Level getStatusLevel() {
                    return Level.ERROR;
                }

                @Override
                public void close() {
                    // Nothing to release
                }
            };

            // when
            StatusLogger.getLogger().registerListener(statusListener);
            try {
                defaultListener.onDocumentsDropped(List.of(Map.of("id", 1)), "reason");
            } finally {
                StatusLogger.getLogger().removeListener(statusListener);
            }

            // then: the document, the reason and the appender, on the status channel rather than lost
            assertThat(errors)
                    .filteredOn(error -> error.getMessage().getFormattedMessage().contains("Stopped"))
                    .singleElement()
                    .satisfies(error -> assertThat(error.getMessage().getFormattedMessage())
                            .contains("reason", "not started", "{id=1}"));
        }

    }

    /**
     * The only line the appender was given.
     *
     * @return The message of its only event
     */
    private String line() {
        List<String> lines = appender.lines();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }

    /**
     * Asserts that the given line is structurally balanced JSON, catching a value written as a bare token
     * (rather than quoted) that would leave a parser mid-object.
     *
     * @param line The line to check
     */
    private static void assertBalancedJson(String line) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (char c : line.toCharArray()) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && (c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == '}' || c == ']')) {
                depth--;
            } else if (!inString && Character.isLetter(c)) {
                assertThat("true".indexOf(c) >= 0 || "false".indexOf(c) >= 0 || "null".indexOf(c) >= 0 || c == 'E' || c == 'e')
                        .as("Bare token outside a string, the line is not valid JSON: " + line)
                        .isTrue();
            }
        }
        assertThat(depth).as("Unbalanced JSON: " + line).isZero();
        assertThat(inString).as("Unterminated string: " + line).isFalse();
    }

}
