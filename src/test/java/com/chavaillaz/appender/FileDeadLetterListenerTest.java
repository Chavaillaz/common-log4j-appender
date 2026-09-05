package com.chavaillaz.appender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FileDeadLetterListener")
class FileDeadLetterListenerTest {

    @Test
    @DisplayName("Appends one JSON line per dropped document, wrapping it with the reason and a timestamp")
    void testAppendsWrappedDocumentAsJsonLine(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("logmessage", "boom");
        document.put("level", "ERROR");

        // when
        var before = Instant.now();
        listener.onDocumentsDropped(List.of(document), "maximum consecutive transmission failures exceeded");
        var after = Instant.now();

        // then: the document is wrapped, unmodified, with the reason and a timestamp
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("{\"reason\":\"maximum consecutive transmission failures exceeded\",\"droppedAt\":\""),
                "Unexpected line: " + line);
        assertTrue(line.endsWith(",\"document\":{\"logmessage\":\"boom\",\"level\":\"ERROR\"}}"),
                "Unexpected line: " + line);

        // and: the embedded timestamp is a genuine, plausible ISO-8601 instant
        String droppedAt = line.substring(line.indexOf("\"droppedAt\":\"") + 13, line.indexOf("\",\"document\""));
        Instant parsed = Instant.parse(droppedAt);
        assertTrue(!parsed.isBefore(before) && !parsed.isAfter(after),
                "droppedAt (" + parsed + ") should be within [" + before + ", " + after + "]");
    }

    @Test
    @DisplayName("Appends across multiple calls instead of overwriting")
    void testAppendsAcrossMultipleCalls(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);

        // when
        listener.onDocumentsDropped(List.of(Map.of("id", 1)), "reason-1");
        listener.onDocumentsDropped(List.of(Map.of("id", 2)), "reason-2");

        // then
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"reason\":\"reason-1\""), "Unexpected line: " + lines.get(0));
        assertTrue(lines.get(1).contains("\"reason\":\"reason-2\""), "Unexpected line: " + lines.get(1));
    }

    @Test
    @DisplayName("Writes one line per document when multiple are dropped together")
    void testOneLinePerDocument(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);

        // when
        listener.onDocumentsDropped(List.of(Map.of("id", 1), Map.of("id", 2)), "reason");

        // then
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertEquals(2, lines.size());
    }

    @Test
    @DisplayName("Escapes characters JSON requires escaping in string values")
    void testEscapesSpecialCharacters(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("logmessage", "line one\nline \"two\"\\three\ttab");

        // when
        listener.onDocumentsDropped(List.of(document), "reason");

        // then
        String line = Files.readString(file, UTF_8).strip();
        assertTrue(line.contains("\"logmessage\":\"line one\\nline \\\"two\\\"\\\\three\\ttab\""),
                "Unexpected line: " + line);
    }

    @Test
    @DisplayName("Serializes nested maps, lists, numbers, booleans and null correctly")
    void testSerializesNestedStructures(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);
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
        assertTrue(line.endsWith(",\"document\":{\"count\":3,\"success\":false,\"missing\":null,"
                        + "\"tags\":[\"a\",\"b\"],\"nested\":{\"code\":42}}}"),
                "Unexpected line: " + line);
    }

    @Test
    @DisplayName("Keeps a number JSON cannot express as a string instead of writing an unreadable line")
    void testNonFiniteNumbersStayParseable(@TempDir Path tempDir) throws Exception {
        // given: a computed value that happened to come out as NaN or an infinity, which render as bare
        // NaN/Infinity tokens no JSON parser accepts
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("ratio", Double.NaN);
        document.put("rate", Double.POSITIVE_INFINITY);
        document.put("drift", Float.NEGATIVE_INFINITY);
        document.put("count", 7);

        // when
        listener.onDocumentsDropped(List.of(document), "reason");

        // then: the values are preserved, quoted, and the line as a whole stays readable
        String line = Files.readString(file, UTF_8).strip();
        assertTrue(line.endsWith(",\"document\":{\"ratio\":\"NaN\",\"rate\":\"Infinity\","
                        + "\"drift\":\"-Infinity\",\"count\":7}}"),
                "Unexpected line: " + line);
        assertBalancedJson(line);
    }

    @Test
    @DisplayName("Keeps a custom Number rendering as something other than a JSON number as a string")
    void testCustomNumberRenderingIsQuoted(@TempDir Path tempDir) throws Exception {
        // given: a Number subclass is free to render as anything at all (a separator, a currency symbol,
        // a locale-specific decimal comma), which would break the line just as badly
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);
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
        assertTrue(line.endsWith(",\"document\":{\"amount\":\"1,50 \\u20ac\"}}") || line.endsWith(",\"document\":{\"amount\":\"1,50 €\"}}"),
                "Unexpected line: " + line);
        assertBalancedJson(line);
    }

    @Test
    @DisplayName("Writes the numbers JSON does accept as bare numbers")
    void testValidNumbersStayUnquoted(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("dead-letters.ndjson");
        var listener = new FileDeadLetterListener(file);
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
        assertTrue(line.endsWith(",\"document\":{\"integer\":42,\"negative\":-7,\"decimal\":1.5,"
                        + "\"exponent\":1.0E20,\"big\":123.456}}"),
                "Unexpected line: " + line);
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
                assertTrue("true".indexOf(c) >= 0 || "false".indexOf(c) >= 0 || "null".indexOf(c) >= 0 || c == 'E' || c == 'e',
                        "Bare token outside a string, the line is not valid JSON: " + line);
            }
        }
        assertEquals(0, depth, "Unbalanced JSON: " + line);
        assertTrue(!inString, "Unterminated string: " + line);
    }

    @Test
    @DisplayName("Creates the parent directory of the target file if it does not exist yet")
    void testCreatesParentDirectory(@TempDir Path tempDir) throws Exception {
        // given
        var file = tempDir.resolve("nested/does/not/exist/dead-letters.ndjson");

        // when
        assertDoesNotThrow(() -> new FileDeadLetterListener(file));

        // then
        assertTrue(Files.isDirectory(file.getParent()));
    }

    @Test
    @DisplayName("Reports a write failure through onWriteFailure instead of throwing")
    void testReportsWriteFailureInsteadOfThrowing(@TempDir Path tempDir) throws Exception {
        // given: the target "file" is actually a directory, so Files.write() will fail
        var file = tempDir.resolve("actually-a-directory");
        Files.createDirectory(file);
        var failures = new CopyOnWriteArrayList<String>();
        var listener = new FileDeadLetterListener(file) {
            @Override
            protected void onWriteFailure(List<Map<String, Object>> documents, String reason, IOException cause) {
                failures.add(reason);
            }
        };

        // when / then
        assertDoesNotThrow(() -> listener.onDocumentsDropped(List.of(Map.of("id", 1)), "reason"));
        assertEquals(List.of("reason"), failures);
    }

}
