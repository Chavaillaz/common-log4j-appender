package com.chavaillaz.appender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers {@link DocumentSizeEstimator}. Being an approximation by design, the assertions here are about the
 * properties a byte limit actually depends on — that a bigger document estimates bigger, that multi-byte
 * characters are not counted as one byte each, and that nothing can make the estimate loop or throw — rather
 * than about exact byte counts, which would only pin down the current formula.
 */
@DisplayName("DocumentSizeEstimator")
class DocumentSizeEstimatorTest {

    @Test
    @DisplayName("Estimates nothing for a null or empty document")
    void testEmptyDocument() {
        assertEquals(0, DocumentSizeEstimator.estimate(null));
        assertTrue(DocumentSizeEstimator.estimate(Map.of()) < 10, "An empty document should be near zero");
    }

    @Test
    @DisplayName("Grows with the content of the document")
    void testGrowsWithContent() {
        long small = DocumentSizeEstimator.estimate(Map.of("logmessage", "short"));
        long large = DocumentSizeEstimator.estimate(Map.of("logmessage", "x".repeat(10_000)));

        assertTrue(large > small + 9_000, "A 10'000 character message should dominate the estimate, got " + large);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "café", "日本語のログ", "emoji 🔥 here"})
    @DisplayName("Counts a string by its UTF-8 byte length, not its character count")
    void testUtf8LengthMatchesActualEncoding(String value) {
        // given/when: measured against what the JDK actually produces, the only reference that matters for a
        // limit meant to keep a request under a server's maximum content length
        long estimated = DocumentSizeEstimator.utf8Length(value);

        // then
        assertEquals(value.getBytes(UTF_8).length, estimated);
    }

    @Test
    @DisplayName("Follows nested maps and lists rather than counting them as one opaque value")
    void testNestedStructuresAreFollowed() {
        long flat = DocumentSizeEstimator.estimate(Map.of("context", "x"));
        long nested = DocumentSizeEstimator.estimate(Map.of("context",
                Map.of("user", "a-fairly-long-user-identifier", "tags", List.of("alpha", "beta", "gamma"))));

        assertTrue(nested > flat + 40, "Nested content should be counted, got " + nested + " against " + flat);
    }

    @Test
    @DisplayName("Terminates on a document that contains itself instead of recursing forever")
    void testSelfReferentialDocumentTerminates() {
        // given: pathological, but reachable through a caller-supplied MDC value or a custom converter, and
        // an estimate is never worth a StackOverflowError on the logging path
        Map<String, Object> document = new HashMap<>();
        document.put("logmessage", "recursive");
        document.put("self", document);

        // when / then: bounded depth means this returns rather than blowing the stack
        assertTrue(DocumentSizeEstimator.estimate(document) > 0);
    }

}
