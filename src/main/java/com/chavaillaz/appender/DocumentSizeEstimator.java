package com.chavaillaz.appender;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Approximates how many bytes a document takes on the wire, so that the delivery pipeline can bound what it
 * holds and what it puts in one request by size (see {@link DeliveryOptions#maxPendingBytes()} and
 * {@link DeliveryOptions#maxRequestBytes()}).
 * <p>
 * It approximates the size of a compact UTF-8 JSON rendering of the document, the right order of magnitude for
 * any JSON transport, where serializing every document to measure it would cost more than the limits save. A
 * transport knowing its own encoding overrides
 * {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery#estimateDocumentSize(Map)} instead.
 * <p>
 * {@code null}, {@link Boolean}, {@link Number}, {@link Map}, {@link Iterable} and array values count as their
 * JSON counterpart, the latter three recursively, and anything else as a JSON string of its
 * {@link String#valueOf(Object)} rendering, as {@link FileDeadLetterListener} writes them. Escaping is not
 * counted, and nesting is followed to a bounded depth, past which a value counts as zero, so that a document
 * containing itself cannot recurse forever.
 */
public final class DocumentSizeEstimator {

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
     * Bytes added after each element of a JSON array: the comma separating it from the next one.
     */
    private static final int ELEMENT_OVERHEAD = 1;

    /**
     * Bytes added by the two quotes surrounding a JSON string.
     */
    private static final int STRING_OVERHEAD = 2;

    /**
     * Bytes taken by a JSON {@code null}.
     */
    private static final int NULL_LENGTH = 4;

    /**
     * Maximum nesting depth followed before a value stops being counted, so that a structure containing
     * itself does not recurse until the stack runs out. Far deeper than any log document has reason to be.
     */
    private static final int MAX_DEPTH = 16;

    private DocumentSizeEstimator() {
        // Static methods only
    }

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
            case Object array when array.getClass().isArray() -> estimateArray(elementsOf(array), depth);
            default -> utf8Length(String.valueOf(value)) + STRING_OVERHEAD;
        };
    }

    /**
     * Lists the elements of an array, primitive ones boxed, so that it is measured and written as the JSON
     * array a serializer turns it into, rather than as the identity hash {@link String#valueOf(Object)}
     * renders it as.
     *
     * @param array The array, of objects or of primitives
     * @return Its elements, in order
     */
    static List<?> elementsOf(Object array) {
        if (array instanceof Object[] objects) {
            return Arrays.asList(objects);
        }
        int length = Array.getLength(array);
        List<Object> elements = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            elements.add(Array.get(array, i));
        }
        return elements;
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
            size += estimateValue(element, depth + 1) + ELEMENT_OVERHEAD;
        }
        return size;
    }

    /**
     * Counts how many bytes the given text takes once encoded as UTF-8, without allocating the encoded form.
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
