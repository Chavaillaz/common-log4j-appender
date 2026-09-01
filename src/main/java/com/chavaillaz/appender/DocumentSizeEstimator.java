package com.chavaillaz.appender;

import java.util.Map;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Approximates how many bytes a document takes on the wire, so the delivery pipeline can bound what it holds
 * in memory and what it puts in one request by size rather than only by document count (see
 * {@link DeliveryOptions#maxPendingBytes()} and {@link DeliveryOptions#maxRequestBytes()}).
 * <p>
 * Deliberately an approximation, not a measurement: the exact size depends on the downstream system's own
 * encoding (an Elasticsearch bulk body interleaves an action line per document, a protobuf payload looks
 * nothing like either), and actually serializing every document just to measure it would cost more than the
 * limits save. What is computed here is the size of a compact, UTF-8 encoded JSON rendering of the document:
 * the right order of magnitude for any JSON-ish transport, which is what a limit meant to keep requests under
 * a server's maximum content length needs. A transport that knows its own encoding should override
 * {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery#estimateDocumentSize(Map)} with something
 * exact instead.
 * <p>
 * {@code null}, {@link Boolean}, {@link Number}, {@link Map} and {@link Iterable} values are accounted for as
 * their natural JSON counterpart (recursively for the latter two), anything else as a JSON string of its
 * {@link String#valueOf(Object)} rendering — mirroring what {@link FileDeadLetterListener} writes, so a
 * document's estimate here matches what that sink would actually produce for it.
 * <p>
 * Escaping is not accounted for: a string full of quotes or control characters really serializes a little
 * larger than estimated here. Nesting is followed to a bounded depth, past which a value counts as zero,
 * so a document that (however unusually) contains itself cannot recurse forever.
 */
@UtilityClass
public class DocumentSizeEstimator {

    /**
     * Bytes added by the braces surrounding a JSON object, or the brackets surrounding an array.
     */
    private static final int CONTAINER_OVERHEAD = 2;

    /**
     * Bytes added around each entry of a JSON object: the two quotes around the key, the colon separating it
     * from its value, and the comma separating the entry from the next one.
     */
    private static final int ENTRY_OVERHEAD = 4;

    /**
     * Bytes added by the two quotes surrounding a JSON string, plus the comma separating it from the next
     * element when it sits in an array.
     */
    private static final int STRING_OVERHEAD = 3;

    /**
     * Bytes taken by a JSON {@code null}.
     */
    private static final int NULL_LENGTH = 4;

    /**
     * Maximum nesting depth followed before a value stops being counted, guarding against a structure that
     * contains itself (which would otherwise recurse until the stack runs out). Far deeper than any log
     * document has reason to be.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * Estimates the encoded size of the given document.
     *
     * @param document The document to measure, or {@code null}
     * @return The estimated size in bytes, never negative
     */
    public static long estimate(@Nullable Map<String, Object> document) {
        return document == null ? 0 : estimateValue(document, 0);
    }

    /**
     * Estimates the encoded size of a single value, of whatever type.
     *
     * @param value The value to measure
     * @param depth The nesting depth this value sits at
     * @return The estimated size in bytes
     */
    private static long estimateValue(@Nullable Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return 0;
        }
        return switch (value) {
            case null -> NULL_LENGTH;
            case Boolean bool -> bool ? 4 : 5;
            case Number number -> String.valueOf(number).length();
            case CharSequence text -> utf8Length(text) + STRING_OVERHEAD;
            case Map<?, ?> map -> estimateObject(map, depth);
            case Iterable<?> iterable -> estimateArray(iterable, depth);
            default -> utf8Length(String.valueOf(value)) + STRING_OVERHEAD;
        };
    }

    /**
     * Estimates the encoded size of a JSON object.
     *
     * @param map   The entries of the object
     * @param depth The nesting depth this object sits at
     * @return The estimated size in bytes
     */
    private static long estimateObject(Map<?, ?> map, int depth) {
        long size = CONTAINER_OVERHEAD;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            size += utf8Length(String.valueOf(entry.getKey())) + ENTRY_OVERHEAD;
            size += estimateValue(entry.getValue(), depth + 1);
        }
        return size;
    }

    /**
     * Estimates the encoded size of a JSON array.
     *
     * @param iterable The elements of the array
     * @param depth    The nesting depth this array sits at
     * @return The estimated size in bytes
     */
    private static long estimateArray(Iterable<?> iterable, int depth) {
        long size = CONTAINER_OVERHEAD;
        for (Object element : iterable) {
            // One extra byte for the comma separating this element from the next one; already included in
            // STRING_OVERHEAD for a string, so only added here for everything else.
            size += estimateValue(element, depth + 1) + (element instanceof CharSequence ? 0 : 1);
        }
        return size;
    }

    /**
     * Counts how many bytes the given text takes once encoded as UTF-8, without allocating the encoded form
     * (unlike {@code value.getBytes(UTF_8).length}, which would copy the whole thing just to read its
     * length, on a path taken once per document).
     *
     * @param value The text to measure
     * @return The number of bytes its UTF-8 encoding would take
     */
    static long utf8Length(CharSequence value) {
        long length = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x80) {
                length += 1;
            } else if (character < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(character) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                // A valid surrogate pair is one code point encoded as four bytes, not two characters of
                // three bytes each, so the low half is consumed here rather than counted on its own.
                length += 4;
                i++;
            } else {
                length += 3;
            }
        }
        return length;
    }

}
