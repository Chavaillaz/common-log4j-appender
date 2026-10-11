package com.chavaillaz.appender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        assertThat(DocumentSizeEstimator.estimate(null)).isZero();
        assertThat(DocumentSizeEstimator.estimate(Map.of())).as("An empty document should be near zero").isLessThan(10);
    }

    @Test
    @DisplayName("Grows with the content of the document")
    void testGrowsWithContent() {
        long small = DocumentSizeEstimator.estimate(Map.of("logmessage", "short"));
        long large = DocumentSizeEstimator.estimate(Map.of("logmessage", "x".repeat(10_000)));

        assertThat(large)
                .as("A 10'000 character message should dominate the estimate, got " + large)
                .isGreaterThan(small + 9_000);
    }

    @Test
    @DisplayName("Counts every separator of the compact JSON rendering once")
    void testCountsEachSeparatorOnce() {
        // given: strings, a number and a value rendered as a string, in an object
        Instant instant = Instant.parse("2026-09-28T10:15:30Z");
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("level", "ERROR");
        scalars.put("count", 3);
        scalars.put("when", instant);
        String scalarsJson = "{\"level\":\"ERROR\",\"count\":3,\"when\":\"2026-09-28T10:15:30Z\"}";

        // and: arrays on their own, long enough for the separators between their elements to outnumber the one
        // an object counts in excess, which would otherwise make up for them
        Map<String, Object> arrays = new LinkedHashMap<>();
        arrays.put("tags", List.of("a", "b", "c", "d", "e"));
        arrays.put("seen", List.of(instant, instant, instant));
        String arraysJson = "{\"tags\":[\"a\",\"b\",\"c\",\"d\",\"e\"],\"seen\":[\"2026-09-28T10:15:30Z\","
                + "\"2026-09-28T10:15:30Z\",\"2026-09-28T10:15:30Z\"]}";

        // when
        long scalarsOverhead = DocumentSizeEstimator.estimate(scalars) - scalarsJson.getBytes(UTF_8).length;
        long arraysOverhead = DocumentSizeEstimator.estimate(arrays) - arraysJson.getBytes(UTF_8).length;

        // then: never under it, and over it by no more than the comma counted after the last element of each
        // container, the object for one, the object and its two arrays for the other
        assertThat(scalarsOverhead)
                .as("Estimated " + scalarsOverhead + " bytes more than the JSON of the entries takes")
                .isBetween(0L, 1L);
        assertThat(arraysOverhead)
                .as("Estimated " + arraysOverhead + " bytes more than the JSON of the arrays takes")
                .isBetween(0L, 3L);
    }

    @Test
    @DisplayName("Counts null and booleans as the JSON literals they are written as")
    void testCountsNullAndBooleansAsLiterals() {
        // given: a field a configuration left unset, as the default converter writes it, and four flags, more
        // of them false than true, so that one literal counted for the length of the other shows
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("host", null);
        document.put("retried", true);
        document.put("truncated", false);
        document.put("sampled", false);
        document.put("delayed", false);
        String json = "{\"host\":null,\"retried\":true,\"truncated\":false,\"sampled\":false,"
                + "\"delayed\":false}";

        // when
        long overhead = DocumentSizeEstimator.estimate(document) - json.getBytes(UTF_8).length;

        // then: off by the comma counted after the last entry at most
        assertThat(overhead)
                .as("Estimated " + overhead + " bytes more than the JSON takes")
                .isBetween(0L, 1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "café", "日本語のログ", "emoji 🔥 here"})
    @DisplayName("Counts a string by its UTF-8 byte length, not its character count")
    void testUtf8LengthMatchesActualEncoding(String value) {
        // given/when: measured against what the JDK actually produces, the only reference that matters for a
        // limit meant to keep a request under a server's maximum content length
        long estimated = DocumentSizeEstimator.utf8Length(value);

        // then
        assertThat(estimated).isEqualTo(value.getBytes(UTF_8).length);
    }

    @Test
    @DisplayName("Follows nested maps and lists rather than counting them as one opaque value")
    void testNestedStructuresAreFollowed() {
        long flat = DocumentSizeEstimator.estimate(Map.of("context", "x"));
        long nested = DocumentSizeEstimator.estimate(Map.of("context",
                Map.of("user", "a-fairly-long-user-identifier", "tags", List.of("alpha", "beta", "gamma"))));

        assertThat(nested)
                .as("Nested content should be counted, got " + nested + " against " + flat)
                .isGreaterThan(flat + 40);
    }

    @Test
    @DisplayName("Counts an array by its elements rather than as one opaque value")
    void testArraysAreFollowed() {
        // given: arrays serialize as their elements, however large, and not as the identity hash String.valueOf()
        // renders them as, a few dozen bytes whatever they hold
        long objects = DocumentSizeEstimator.estimate(Map.of("parameters", new Object[]{"x".repeat(10_000)}));
        long primitives = DocumentSizeEstimator.estimate(Map.of("payload", new int[10_000]));

        // then
        assertThat(objects)
                .as("The elements of an object array should be counted, got " + objects)
                .isGreaterThan(10_000);
        assertThat(primitives)
                .as("The elements of a primitive array should be counted, got " + primitives)
                .isGreaterThan(10_000);
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
        assertThat(DocumentSizeEstimator.estimate(document)).isPositive();
    }

}
