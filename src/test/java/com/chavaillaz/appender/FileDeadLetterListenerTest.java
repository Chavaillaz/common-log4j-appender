package com.chavaillaz.appender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.Thread.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FileDeadLetterListener")
class FileDeadLetterListenerTest {

    /**
     * The clock of most listeners here, all of them dropping on 28 September 2026 and so appending to
     * {@code dead-letters-2026-09-28.ndjson} for {@code dead-letters.ndjson}.
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T10:15:30Z"), ZoneOffset.UTC);

    /**
     * A clock a test moves forward, across midnight for instance.
     */
    private static final class SettableClock extends Clock {

        private final ZoneId zone;
        private volatile Instant instant;

        private SettableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new SettableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

    }

    @Test
    @DisplayName("Appends one JSON line per dropped document, wrapping it with the reason and a timestamp")
    void testAppendsWrappedDocumentAsJsonLine(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("logmessage", "boom");
        document.put("level", "ERROR");

        // when
        listener.onDocumentsDropped(List.of(document), "maximum consecutive transmission failures exceeded");

        // then: the document is wrapped, unmodified, with the reason and the instant it was dropped at
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines)
                .containsExactly("{\"reason\":\"maximum consecutive transmission failures exceeded\","
                        + "\"droppedAt\":\"2026-09-28T10:15:30Z\","
                        + "\"document\":{\"logmessage\":\"boom\",\"level\":\"ERROR\"}}");
    }

    @Test
    @DisplayName("Moves on to the file of the next day at midnight")
    void testRotatesEveryDay(@TempDir Path tempDir) throws Exception {
        // given
        var clock = new SettableClock(Instant.parse("2026-09-28T23:59:59Z"), ZoneOffset.UTC);
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), clock);

        // when: a document is dropped either side of midnight
        listener.onDocumentsDropped(List.of(Map.of("id", 1)), "before midnight");
        clock.instant = Instant.parse("2026-09-29T00:00:01Z");
        listener.onDocumentsDropped(List.of(Map.of("id", 2)), "after midnight");

        // then: each went to the file of its own day, the one given never being written to itself
        assertThat(Files.readAllLines(tempDir.resolve("dead-letters-2026-09-28.ndjson"), UTF_8)).hasSize(1);
        List<String> nextDay = Files.readAllLines(tempDir.resolve("dead-letters-2026-09-29.ndjson"), UTF_8);
        assertThat(nextDay).hasSize(1);
        assertThat(nextDay.get(0)).as("Unexpected line: " + nextDay.get(0)).contains("\"reason\":\"after midnight\"");
        assertThat(tempDir.resolve("dead-letters.ndjson")).doesNotExist();
    }

    @Test
    @DisplayName("Starts a day at midnight in the time zone of its clock")
    void testDayStartsInTheTimeZoneOfTheClock(@TempDir Path tempDir) throws Exception {
        // given: half past midnight in Zurich, still the day before in UTC
        var clock = Clock.fixed(Instant.parse("2026-09-28T22:30:00Z"), ZoneId.of("Europe/Zurich"));
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), clock);

        // when
        listener.onDocumentsDropped(List.of(Map.of("id", 1)), "reason");

        // then: filed on the day of the time zone, while the line keeps the instant itself
        List<String> lines = Files.readAllLines(tempDir.resolve("dead-letters-2026-09-29.ndjson"), UTF_8);
        assertThat(lines.get(0))
                .as("Unexpected line: " + lines.get(0))
                .contains("\"droppedAt\":\"2026-09-28T22:30:00Z\"");
    }

    @Test
    @DisplayName("Appends the date to a file name with no extension, a leading dot not starting one")
    void testDatesAFileNameWithoutExtension(@TempDir Path tempDir) throws Exception {
        // given
        var plain = new FileDeadLetterListener(tempDir.resolve("dead-letters"), CLOCK);
        var hidden = new FileDeadLetterListener(tempDir.resolve(".dead-letters"), CLOCK);

        // when
        plain.onDocumentsDropped(List.of(Map.of("id", 1)), "reason");
        hidden.onDocumentsDropped(List.of(Map.of("id", 2)), "reason");

        // then
        assertThat(tempDir.resolve("dead-letters-2026-09-28")).exists();
        assertThat(tempDir.resolve(".dead-letters-2026-09-28")).exists();
    }

    @Test
    @DisplayName("Appends across multiple calls instead of overwriting")
    void testAppendsAcrossMultipleCalls(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);

        // when
        listener.onDocumentsDropped(List.of(Map.of("id", 1)), "reason-1");
        listener.onDocumentsDropped(List.of(Map.of("id", 2)), "reason-2");

        // then
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).as("Unexpected line: " + lines.get(0)).contains("\"reason\":\"reason-1\"");
        assertThat(lines.get(1)).as("Unexpected line: " + lines.get(1)).contains("\"reason\":\"reason-2\"");
    }

    @Test
    @DisplayName("Ends a line a write cut short left incomplete rather than appending to it")
    void testEndsAnIncompleteLineBeforeAppending(@TempDir Path tempDir) throws Exception {
        // given: the file of the day ending in the middle of a line, as a crash during a write leaves it
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        Files.writeString(file, "{\"reason\":\"cut\"");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);

        // when
        listener.onDocumentsDropped(List.of(Map.of("id", 1)), "reason");

        // then: the incomplete line costs only itself, the new one standing on a line of its own
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines).as("Unexpected lines: " + lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("{\"reason\":\"cut\"");
        assertThat(lines.get(1)).as("Unexpected line: " + lines.get(1)).startsWith("{\"reason\":\"reason\"");
    }

    @Test
    @DisplayName("Appends on an interrupted thread, as an interrupted stop reports what it abandons")
    void testAppendsOnAnInterruptedThread(@TempDir Path tempDir) throws Exception {
        // given: a file already holding a complete line, checked before appending to it
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        listener.onDocumentsDropped(List.of(Map.of("id", 1)), "first");

        // when: the next drop is reported by an interrupted thread
        Thread.currentThread().interrupt();
        try {
            listener.onDocumentsDropped(List.of(Map.of("id", 2)), "second");
        } finally {
            // then: still flagged, and cleared for the next tests
            assertThat(Thread.interrupted()).isTrue();
        }

        // and: saved all the same
        assertThat(Files.readAllLines(file, UTF_8)).hasSize(2);
    }

    @Test
    @DisplayName("Keeps each line whole while another listener appends to the same file")
    void testLinesStayWholeAlongsideAnotherWriter(@TempDir Path tempDir) throws Exception {
        // given: two listeners on the same file, as two appenders or two applications on one host configure
        // them, each dropping batches far larger than a buffered write at the same time
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var first = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        var second = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        var firstDocuments = new ArrayList<Map<String, Object>>();
        var secondDocuments = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            firstDocuments.add(Map.of("logmessage", "a".repeat(5_000)));
            secondDocuments.add(Map.of("logmessage", "b".repeat(5_000)));
        }

        // when
        var start = new CountDownLatch(1);
        var writers = new ArrayList<Thread>();
        for (int round = 0; round < 5; round++) {
            writers.add(Thread.ofPlatform().start(() -> awaitThenDrop(start, first, firstDocuments)));
            writers.add(Thread.ofPlatform().start(() -> awaitThenDrop(start, second, secondDocuments)));
        }
        start.countDown();
        for (Thread writer : writers) {
            writer.join();
        }

        // then: every line is one complete document, the two writers only alternating between lines
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines).hasSize(1_000);
        for (String line : lines) {
            assertThat(line.startsWith("{\"reason\":\"reason\"") && line.endsWith("\"}}")
                    && (line.contains("a".repeat(5_000)) != line.contains("b".repeat(5_000))))
                    .as("Corrupted line of " + line.length() + " characters")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Writes one line per document when multiple are dropped together")
    void testOneLinePerDocument(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);

        // when
        listener.onDocumentsDropped(List.of(Map.of("id", 1), Map.of("id", 2)), "reason");

        // then
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines).hasSize(2);
    }

    @Test
    @DisplayName("Escapes characters JSON requires escaping in string values")
    void testEscapesSpecialCharacters(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("logmessage", "line one\nline \"two\"\\three\ttab");

        // when
        listener.onDocumentsDropped(List.of(document), "reason");

        // then
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line)
                .as("Unexpected line: " + line)
                .contains("\"logmessage\":\"line one\\nline \\\"two\\\"\\\\three\\ttab\"");
    }

    @Test
    @DisplayName("Saves half of a surrogate pair as its JSON escape rather than losing it")
    void testLoneSurrogateDoesNotFailTheBatch(@TempDir Path tempDir) throws Exception {
        // given: a message cut in the middle of an emoji, as any substring() of user content can produce,
        // dropped in the middle of a batch
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var failures = new CopyOnWriteArrayList<IOException>();
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK) {
            @Override
            protected void onWriteFailure(Path target, List<Map<String, Object>> documents, String reason, IOException cause) {
                failures.add(cause);
            }
        };
        String emoji = "\uD83D\uDE00";
        Map<String, Object> cut = new LinkedHashMap<>();
        cut.put("logmessage", ("cut " + emoji).substring(0, 5));
        cut.put("tail", emoji.substring(1) + " orphan");
        cut.put("intact", emoji);

        // when: followed by another batch, as the next drop appends to the same file
        listener.onDocumentsDropped(List.of(Map.of("logmessage", "first"), cut, Map.of("logmessage", "last")), "reason");
        listener.onDocumentsDropped(List.of(Map.of("logmessage", "next")), "next");

        // then: every document was saved, whatever the one character UTF-8 cannot encode
        assertThat(failures).as("No write should have failed: " + failures).isEmpty();
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines).as("Unexpected lines: " + lines).hasSize(4);

        // and: each half is kept as the JSON escape for it, while a pair that is whole is written as is
        assertThat(lines.get(1)).as("Unexpected line: " + lines.get(1)).contains("\"logmessage\":\"cut \\ud83d\"");
        assertThat(lines.get(1)).as("Unexpected line: " + lines.get(1)).contains("\"tail\":\"\\ude00 orphan\"");
        assertThat(lines.get(1)).as("Unexpected line: " + lines.get(1)).contains("\"intact\":\"" + emoji + "\"");

        // and: the next batch starts a line of its own, instead of running into an unterminated one
        assertThat(lines.get(3)).as("Unexpected line: " + lines.get(3)).startsWith("{\"reason\":\"next\"");
    }

    @Test
    @DisplayName("Serializes nested maps, lists, numbers, booleans and null correctly")
    void testSerializesNestedStructures(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
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
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line)
                .as("Unexpected line: " + line)
                .endsWith(",\"document\":{\"count\":3,\"success\":false,\"missing\":null,"
                        + "\"tags\":[\"a\",\"b\"],\"nested\":{\"code\":42}}}");
    }

    @Test
    @DisplayName("Writes an array as a JSON array rather than as its identity hash")
    void testSerializesArrays(@TempDir Path tempDir) throws Exception {
        // given: arrays of objects and of primitives, as a custom converter putting the parameters of a
        // message in its document produces
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("parameters", new Object[]{"a", 1, null});
        document.put("codes", new int[]{4, 2});

        // when
        listener.onDocumentsDropped(List.of(document), "reason");

        // then: their content is saved, where "[Ljava.lang.Object;@1b6d3586" saved nothing of it
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line)
                .as("Unexpected line: " + line)
                .endsWith(",\"document\":{\"parameters\":[\"a\",1,null],\"codes\":[4,2]}}");
    }

    @Test
    @DisplayName("Keeps a number JSON cannot express as a string instead of writing an unreadable line")
    void testNonFiniteNumbersStayParseable(@TempDir Path tempDir) throws Exception {
        // given: a computed value that happened to come out as NaN or an infinity, which render as bare
        // NaN/Infinity tokens no JSON parser accepts
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("ratio", Double.NaN);
        document.put("rate", Double.POSITIVE_INFINITY);
        document.put("drift", Float.NEGATIVE_INFINITY);
        document.put("count", 7);

        // when
        listener.onDocumentsDropped(List.of(document), "reason");

        // then: the values are preserved, quoted, and the line as a whole stays readable
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line)
                .as("Unexpected line: " + line)
                .endsWith(",\"document\":{\"ratio\":\"NaN\",\"rate\":\"Infinity\","
                        + "\"drift\":\"-Infinity\",\"count\":7}}");
        assertBalancedJson(line);
    }

    @Test
    @DisplayName("Keeps a custom Number rendering as something other than a JSON number as a string")
    void testCustomNumberRenderingIsQuoted(@TempDir Path tempDir) throws Exception {
        // given: a Number subclass is free to render as anything at all (a separator, a currency symbol,
        // a locale-specific decimal comma), which would break the line just as badly
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
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
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line.endsWith(",\"document\":{\"amount\":\"1,50 \\u20ac\"}}") || line.endsWith(",\"document\":{\"amount\":\"1,50 €\"}}"))
                .as("Unexpected line: " + line)
                .isTrue();
        assertBalancedJson(line);
    }

    @Test
    @DisplayName("Writes the numbers JSON does accept as bare numbers")
    void testValidNumbersStayUnquoted(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("integer", 42);
        document.put("negative", -7L);
        document.put("decimal", 1.5d);
        document.put("exponent", 1e20d);
        document.put("big", new java.math.BigDecimal("123.456"));

        // when
        listener.onDocumentsDropped(List.of(document), "reason");

        // then: none of them are quoted, so a consumer still reads them back as numbers
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line)
                .as("Unexpected line: " + line)
                .endsWith(",\"document\":{\"integer\":42,\"negative\":-7,\"decimal\":1.5,"
                        + "\"exponent\":1.0E20,\"big\":123.456}}");
    }

    @Test
    @DisplayName("Truncates a self-referential document instead of overflowing the stack")
    void testSelfReferentialDocumentIsTruncated(@TempDir Path tempDir) throws Exception {
        // given: a document reachable from itself, e.g. an application logging a list that holds the very
        // map being logged. A StackOverflowError here is an Error, so it slips past every catch(Exception)
        // guard in the pipeline and escapes through close(), on the shutdown path this sink serves.
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        Map<String, Object> document = new LinkedHashMap<>();
        List<Object> holder = new java.util.ArrayList<>();
        holder.add(document);
        document.put("self", holder);

        // when
        assertThatNoException().isThrownBy(() -> listener.onDocumentsDropped(List.of(document), "reason"));

        // then: the line is still written, truncated where the cycle was cut, and still readable
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line).as("Unexpected line: " + line).contains("truncated: maximum nesting depth");
        assertBalancedJson(line);
    }

    @Test
    @DisplayName("Truncates a document nested deeper than the limit instead of overflowing the stack")
    void testDeeplyNestedDocumentIsTruncated(@TempDir Path tempDir) throws Exception {
        // given: no cycle, just a structure deep enough to exhaust the stack on its own
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
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
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line).as("Unexpected line: " + line).contains("truncated: maximum nesting depth");
        assertBalancedJson(line);
    }

    @Test
    @DisplayName("Writes nesting up to the depth limit in full")
    void testNestingWithinTheLimitIsWrittenInFull(@TempDir Path tempDir) throws Exception {
        // given: the wrapper adds one level, so a document nested well inside the limit must survive intact
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("a", Map.of("b", Map.of("c", Map.of("d", "leaf"))));

        // when
        listener.onDocumentsDropped(List.of(document), "reason");

        // then
        String line = Files.readString(file, UTF_8).strip();
        assertThat(line)
                .as("Unexpected line: " + line)
                .endsWith(",\"document\":{\"a\":{\"b\":{\"c\":{\"d\":\"leaf\"}}}}}");
        assertThat(line).as("Should not have truncated: " + line).doesNotContain("truncated");
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

    @Test
    @DisplayName("Creates the parent directory of the target file if it does not exist yet")
    void testCreatesParentDirectory(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("nested/does/not/exist/dead-letters.ndjson");

        // when
        assertThatNoException().isThrownBy(() -> new FileDeadLetterListener(file, CLOCK));

        // then
        assertThat(file.getParent()).isDirectory();
    }

    @Test
    @DisplayName("Creates the parent directory again once removed, as a cleaner of old files does")
    void testCreatesTheParentDirectoryAgainOnceRemoved(@TempDir Path tempDir) throws Exception {
        // given: the directory of the listener, empty since it was created, removed by a cleaner of old files
        var directory = tempDir.resolve("dead-letters");
        var listener = new FileDeadLetterListener(directory.resolve("dead-letters.ndjson"), CLOCK);
        Files.delete(directory);

        // when
        listener.onDocumentsDropped(List.of(Map.of("logmessage", "boom")), "reason");

        // then: saved, rather than handed to onWriteFailure, which only logs it
        assertThat(Files.readAllLines(directory.resolve("dead-letters-2026-09-28.ndjson"), UTF_8))
                .singleElement()
                .asString()
                .contains("\"logmessage\":\"boom\"");
    }

    @Test
    @DisplayName("Saves the rest of a document when one of its values cannot be rendered")
    void testUnrenderableValueOnlyCostsItself(@TempDir Path tempDir) throws Exception {
        // given: a document holding a value whose toString() throws, as any application object put into a
        // document by a custom converter is free to do
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
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

        // then: the documents around them were saved, instead of being discarded a second time by the
        // very sink meant to be their last resort
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines).as("Unexpected lines: " + lines).hasSize(4);
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
    void testCollectionFailingPartwayIsReplacedWhole(@TempDir Path tempDir) throws Exception {
        // given: a custom collection whose iteration fails after its first element was written
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
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
        String line = Files.readAllLines(file, UTF_8).getFirst();
        assertBalancedJson(line);
        assertThat(line).endsWith(
                "\"document\":{\"items\":\"<unrenderable: java.util.ConcurrentModificationException>\",\"logmessage\":\"kept\"}}");
    }

    @Test
    @DisplayName("Reports a write failure through onWriteFailure instead of throwing")
    void testReportsWriteFailureInsteadOfThrowing(@TempDir Path tempDir) throws Exception {
        // given: the file of the day is actually a directory, so Files.write() will fail
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        Files.createDirectory(file);
        var failures = new CopyOnWriteArrayList<String>();
        var failedFiles = new CopyOnWriteArrayList<Path>();
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK) {
            @Override
            protected void onWriteFailure(Path target, List<Map<String, Object>> documents, String reason, IOException cause) {
                failures.add(reason);
                failedFiles.add(target);
            }
        };

        // when / then
        assertThatNoException().isThrownBy(() -> listener.onDocumentsDropped(List.of(Map.of("id", 1)), "reason"));
        assertThat(failures).containsExactly("reason");
        assertThat(failedFiles).containsExactly(file);
    }

    @Test
    @DisplayName("Hands over only the documents a write failing partway left out of the file")
    void testWriteFailingPartwayHandsOverOnlyTheDocumentsLeftOut(@TempDir Path tempDir) throws Exception {
        // given: documents taking several writes, the disk filling up after the first one
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var failed = new CopyOnWriteArrayList<Map<String, Object>>();
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK) {
            @Override
            OutputStream openForAppend(Path target) throws IOException {
                return new FilterOutputStream(super.openForAppend(target)) {
                    private int writes;

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (++writes > 1) {
                            throw new IOException("No space left on device");
                        }
                        out.write(bytes, offset, length);
                    }
                };
            }

            @Override
            protected void onWriteFailure(Path target, List<Map<String, Object>> documents, String reason, IOException cause) {
                failed.addAll(documents);
            }
        };
        var documents = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            documents.add(Map.of("id", i, "padding", "x".repeat(1_000)));
        }

        // when
        listener.onDocumentsDropped(documents, "reason");

        // then: the lines of the first write stay in the file, and only the others are handed over, rather than
        // every document, which a fallback sink would then hold twice
        int appended = Files.readAllLines(file, UTF_8).size();
        assertThat(appended)
                .as("Unexpected number of lines appended: " + appended)
                .isStrictlyBetween(0, documents.size());
        assertThat(failed.stream().map(document -> document.get("id")).toList())
                .isEqualTo(IntStream.range(appended, documents.size()).boxed().toList());
    }

    @Test
    @DisplayName("Hands over every document when only closing the file fails, which can mean none reached it")
    void testClosingFailingHandsOverEveryDocument(@TempDir Path tempDir) throws Exception {
        // given: a file taking every write but failing to close, as a network file system reports a write it
        // could not complete
        var failed = new CopyOnWriteArrayList<Map<String, Object>>();
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK) {
            @Override
            OutputStream openForAppend(Path target) throws IOException {
                return new FilterOutputStream(super.openForAppend(target)) {
                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        out.write(bytes, offset, length);
                    }

                    @Override
                    public void close() throws IOException {
                        super.close();
                        throw new IOException("Input/output error");
                    }
                };
            }

            @Override
            protected void onWriteFailure(Path target, List<Map<String, Object>> documents, String reason, IOException cause) {
                failed.addAll(documents);
            }
        };
        List<Map<String, Object>> documents = List.of(Map.of("id", 1), Map.of("id", 2));

        // when
        listener.onDocumentsDropped(documents, "reason");

        // then: all of them, which a fallback sink may then hold twice, rather than none
        assertThat(failed).containsExactlyElementsOf(documents);
    }

    @Test
    @DisplayName("Renders the documents write by write rather than all of them before the first write")
    void testRendersTheDocumentsWriteByWrite(@TempDir Path tempDir) throws Exception {
        // given: documents taking several writes, recording when each one is rendered and when each write goes out
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var events = new CopyOnWriteArrayList<String>();
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK) {
            @Override
            OutputStream openForAppend(Path target) throws IOException {
                return new FilterOutputStream(super.openForAppend(target)) {
                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        events.add("write");
                        out.write(bytes, offset, length);
                    }
                };
            }
        };
        var documents = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            documents.add(Map.of("payload", new Object() {
                @Override
                public String toString() {
                    events.add("render");
                    return "x".repeat(1_000);
                }
            }));
        }

        // when
        listener.onDocumentsDropped(documents, "reason");

        // then: the first write went out before the last documents were rendered, rather than after a second
        // copy of all of them was held, a whole batch when a delivery gives up on one
        assertThat(events.indexOf("write")).isLessThan(events.lastIndexOf("render"));
        assertThat(Files.readAllLines(file, UTF_8)).hasSize(documents.size());
    }

    @Test
    @DisplayName("Does not create the file at all when there is nothing to append")
    void testEmptyNotificationDoesNotCreateTheFile(@TempDir Path tempDir) {
        // given: an empty notification, as a listener chain forwarding one it did not filter produces
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);

        // when
        listener.onDocumentsDropped(List.of(), "nothing was actually dropped");

        // then: no file, since one sitting next to an application reads as "logs were lost here"
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("Leaves other virtual threads a carrier while writers wait for their turn at the file")
    void testWritersWaitingForTheFileDoNotHoldCarriers(@TempDir Path tempDir) throws Exception {
        // given: a write in progress, held here the way a slow disk would hold it, and as many virtual
        // threads waiting behind it as the scheduler has carriers, the transmissions giving up on documents
        // and the reports of the discarded events all running on virtual threads
        var file = tempDir.resolve("dead-letters-2026-09-28.ndjson");
        var listener = new FileDeadLetterListener(tempDir.resolve("dead-letters.ndjson"), CLOCK);
        int carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors());
        var writers = new ArrayList<Thread>();
        boolean othersRan;
        listener.writeLock.lock();
        try {
            for (int i = 0; i < carriers; i++) {
                var document = Map.<String, Object>of("id", i);
                writers.add(Thread.ofVirtual().start(() -> listener.onDocumentsDropped(List.of(document), "reason")));
            }
            awaitAllWaiting(writers);

            // when
            var unrelated = new CountDownLatch(1);
            Thread.ofVirtual().start(unrelated::countDown);
            othersRan = unrelated.await(5, SECONDS);
        } finally {
            listener.writeLock.unlock();
        }
        for (Thread writer : writers) {
            writer.join();
        }

        // then: the rest of the application kept running meanwhile, and every writer still got its line in
        assertThat(othersRan).as("An unrelated virtual thread should have run while the writers were waiting").isTrue();
        assertThat(Files.readAllLines(file, UTF_8)).hasSize(carriers);
    }

    /**
     * Waits until every one of the given threads is blocked, which for a writer means waiting its turn at
     * the file.
     */
    private static void awaitAllWaiting(List<Thread> threads) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + SECONDS.toNanos(5);
        while (threads.stream().anyMatch(thread -> thread.getState() != State.WAITING && thread.getState() != State.BLOCKED)) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Timed out waiting for every writer to wait for the file");
            }
            Thread.sleep(5);
        }
    }

    /**
     * Drops the given documents through the given listener once the start is given, so that several writers
     * append at the same time.
     */
    private static void awaitThenDrop(CountDownLatch start, FileDeadLetterListener listener, List<Map<String, Object>> documents) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listener.onDocumentsDropped(documents, "reason");
    }

}
