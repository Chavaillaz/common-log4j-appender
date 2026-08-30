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
