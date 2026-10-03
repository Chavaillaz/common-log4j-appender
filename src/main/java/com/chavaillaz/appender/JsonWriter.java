package com.chavaillaz.appender;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

/**
 * Writes the documents {@link FileDeadLetterListener} saves as JSON, without any dependency: see its class
 * documentation for what is supported, and for how a value that cannot be written is replaced, so that no value
 * leaves a line a parser cannot read. {@link DocumentSizeEstimator} approximates the size of what it writes.
 */
final class JsonWriter {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link com.chavaillaz.appender.internal.Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    /**
     * The grammar of a JSON number (RFC 8259), telling a {@link Number} rendering as one apart from
     * {@link Double#NaN}, an infinity, or a custom subclass rendering as something else.
     */
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][-+]?\\d+)?");

    /**
     * Maximum nesting depth written before a value is replaced by {@link #TRUNCATED_MARKER}, so that a
     * structure containing itself does not recurse until the stack runs out. The depth
     * {@link DocumentSizeEstimator} stops measuring at, though the wrapped document starts one level deeper.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * Written in place of a value nested deeper than {@link #MAX_DEPTH}, so that a truncated document says so.
     */
    private static final String TRUNCATED_MARKER = "<truncated: maximum nesting depth of " + MAX_DEPTH + " reached>";

    private JsonWriter() {
        // Static methods only
    }

    /**
     * Serializes the given value as a JSON string (see the class documentation for what is supported).
     *
     * @param value The value to serialize
     * @return The JSON representation of the value
     */
    static String toJson(Object value) {
        StringBuilder builder = new StringBuilder();
        writeJson(builder, value, 0);
        return builder.toString();
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
     * Writes the given value, or a string naming why it could not be written in its place: rendering runs
     * application code (a {@code toString()}, the iteration of a custom collection), and one value failing must
     * not cost the rest of its document.
     *
     * @param builder The builder to append to
     * @param value   The value to write
     * @param depth   The nesting depth this value sits at
     */
    private static void writeJson(StringBuilder builder, @Nullable Object value, int depth) {
        if (depth > MAX_DEPTH) {
            // Cut rather than followed, a document containing itself recursing until the stack runs out
            writeJsonString(builder, TRUNCATED_MARKER);
            return;
        }
        int start = builder.length();
        try {
            switch (value) {
                case null -> builder.append("null");
                case Boolean bool -> builder.append(bool);
                case Number number -> writeJsonNumber(builder, number);
                case Map<?, ?> map -> writeJsonObject(builder, map, depth);
                case Iterable<?> iterable -> writeJsonArray(builder, iterable, depth);
                case Object array when array.getClass().isArray() ->
                        writeJsonArray(builder, elementsOf(array), depth);
                default -> writeJsonString(builder, String.valueOf(value));
            }
        } catch (Throwable e) {
            // Throwable, a value whose own toString() recurses overflowing the stack whatever MAX_DEPTH says.
            // Only the class name is used, the toString() of this exception being application code too
            builder.setLength(start);
            String failure = e.getClass().getName();
            log.warn("Unable to render a value of a dropped document as JSON ({}), saving a placeholder in its place", failure);
            writeJsonString(builder, "<unrenderable: " + failure + ">");
        }
    }

    /**
     * Writes the given number as a JSON number, or as a JSON string when its rendering is not one JSON
     * accepts: {@link Double#NaN} and the infinities render as bare words, and a custom {@link Number} as
     * anything, either of which would leave a line no parser can read.
     *
     * @param builder The builder to append to
     * @param number  The number to write
     */
    private static void writeJsonNumber(StringBuilder builder, Number number) {
        String rendered = String.valueOf(number);
        if (JSON_NUMBER.matcher(rendered).matches()) {
            builder.append(rendered);
        } else {
            writeJsonString(builder, rendered);
        }
    }

    private static void writeJsonObject(StringBuilder builder, Map<?, ?> map, int depth) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeJsonString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeJson(builder, entry.getValue(), depth + 1);
        }
        builder.append('}');
    }

    private static void writeJsonArray(StringBuilder builder, Iterable<?> iterable, int depth) {
        builder.append('[');
        boolean first = true;
        for (Object element : iterable) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeJson(builder, element, depth + 1);
        }
        builder.append(']');
    }

    /**
     * Writes the given string as a JSON string literal, escaping the quote, the backslash and the control
     * characters.
     * <p>
     * Half of a surrogate pair without its other half is escaped too, as JSON allows for any code unit: it is
     * not a character, and UTF-8, having no encoding for it, would write a question mark in its place.
     *
     * @param builder The builder to append to
     * @param value   The string to write
     */
    private static void writeJsonString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20 || isLoneSurrogate(value, i)) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    /**
     * Whether the character at the given index is half of a surrogate pair whose other half is missing.
     *
     * @param value The string to inspect
     * @param index The index of the character to check
     * @return {@code true} if it is a surrogate not paired with a neighbor
     */
    private static boolean isLoneSurrogate(String value, int index) {
        char c = value.charAt(index);
        if (Character.isHighSurrogate(c)) {
            return index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return index == 0 || !Character.isHighSurrogate(value.charAt(index - 1));
        }
        return false;
    }

}
