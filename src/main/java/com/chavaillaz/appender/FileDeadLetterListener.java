package com.chavaillaz.appender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import lombok.extern.log4j.Log4j2;

/**
 * A ready-made {@link LogDeliveryListener} appending every irrecoverably dropped document to a local file,
 * one JSON object per line (newline-delimited JSON), as a last-resort fallback so data a {@link LogDelivery}
 * gave up on is not lost outright. Meant to save every consumer of this library from writing the same
 * {@link #onDocumentsDropped(List, String)} implementation from scratch for that common case (see the
 * project README's Observability section); use it as-is, wrap it, or replace it entirely with something
 * more elaborate (a dead-letter queue, a database, ...) once that becomes worth the extra complexity.
 * <p>
 * Every method except {@link #onDocumentsDropped(List, String)} is a no-op, exactly like
 * {@link LogDeliveryListener#NOOP}; compose the two (or delegate to another listener from a subclass) if
 * transmission successes/failures also need to be observed.
 * <p>
 * Each line wraps the original document, unmodified, alongside the reason it was dropped and when: {@code
 * {"reason":"...","droppedAt":"...","document":{...}}}. Appends are serialized on this instance (so
 * concurrent callers, e.g. several failing batches at once, never interleave mid-line) and best-effort: a
 * failure to write (a full disk, a permission issue, ...) is reported through
 * {@link #onWriteFailure(List, String, IOException)} (logged by default) rather than thrown, since a
 * dead-letter sink failing must never itself disrupt the delivery pipeline calling it.
 * <p>
 * Only a minimal, self-contained JSON representation is produced (no external dependency): {@code null},
 * {@link Boolean}, {@link Number}, {@link Map} and {@link Iterable} values are written as their natural JSON
 * counterpart (recursively for the latter two), anything else (including {@link String}) is written as a
 * JSON string via {@link String#valueOf(Object)}. A key's own iteration order is preserved as-is, without
 * imposing any sorting. A number JSON has no notion of ({@link Double#NaN}, either infinity, or whatever a
 * custom {@link Number} subclass renders as) falls back to a JSON string, so a single such value cannot
 * leave a line no parser can read.
 */
@Log4j2
public class FileDeadLetterListener implements LogDeliveryListener {

    /**
     * The grammar JSON allows for a number (RFC 8259), used by {@link #writeJsonNumber(StringBuilder, Number)}
     * to tell a {@link Number} that renders as one from a {@link Double#NaN}, an infinity, or a custom
     * subclass rendering as something else entirely.
     */
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][-+]?\\d+)?");

    private final Path file;

    /**
     * Creates a new dead-letter listener, creating the parent directory of the given file immediately (not
     * lazily on the first drop) so a misconfiguration is visible right away instead of only surfacing once
     * something actually needs to be saved.
     *
     * @param file The file to append dropped documents to, created (along with its parent directory) if it
     *             does not exist yet, or appended to if it does
     * @throws UncheckedIOException If the parent directory does not exist and cannot be created
     */
    public FileDeadLetterListener(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to create the parent directory of " + file, e);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param documents The documents being discarded
     * @param reason    A short, human-readable explanation of why the documents were discarded
     */
    @Override
    public void onDocumentsDropped(List<Map<String, Object>> documents, String reason) {
        Instant droppedAt = Instant.now();
        List<String> lines = new ArrayList<>(documents.size());
        for (Map<String, Object> document : documents) {
            lines.add(toJson(wrap(document, reason, droppedAt)));
        }
        try {
            synchronized (this) {
                Files.write(file, lines, UTF_8, CREATE, APPEND);
            }
        } catch (IOException e) {
            onWriteFailure(documents, reason, e);
        }
    }

    /**
     * Wraps the given document with the reason it was dropped and when, without modifying it, so a field
     * already present in the document (e.g. a log entry that happens to have its own {@code reason} field)
     * is never overwritten or shadowed.
     *
     * @param document  The document being dropped
     * @param reason    A short, human-readable explanation of why it was dropped
     * @param droppedAt The instant it was dropped at
     * @return The document, wrapped with its drop metadata
     */
    private Map<String, Object> wrap(Map<String, Object> document, String reason, Instant droppedAt) {
        Map<String, Object> wrapped = new LinkedHashMap<>(3);
        wrapped.put("reason", reason);
        wrapped.put("droppedAt", droppedAt.toString());
        wrapped.put("document", document);
        return wrapped;
    }

    /**
     * Called when appending dropped documents to {@link #file} fails, logging the failure (including the
     * documents that could not be saved, as a last resort so they at least appear somewhere) by default.
     * Override to react differently, e.g. to fall back to another sink.
     *
     * @param documents The documents that could not be appended
     * @param reason    The reason they were originally dropped
     * @param cause     The failure encountered while appending them
     */
    protected void onWriteFailure(List<Map<String, Object>> documents, String reason, IOException cause) {
        log.error("Unable to append {} dropped document(s) (originally dropped because: {}) to {}: {}",
                documents.size(), reason, file, documents, cause);
    }

    /**
     * Serializes the given value as a JSON string (see the class documentation for what is supported).
     *
     * @param value The value to serialize
     * @return The JSON representation of the value
     */
    private static String toJson(Object value) {
        StringBuilder builder = new StringBuilder();
        writeJson(builder, value);
        return builder.toString();
    }

    private static void writeJson(StringBuilder builder, Object value) {
        switch (value) {
            case null -> builder.append("null");
            case Boolean bool -> builder.append(bool);
            case Number number -> writeJsonNumber(builder, number);
            case Map<?, ?> map -> writeJsonObject(builder, map);
            case Iterable<?> iterable -> writeJsonArray(builder, iterable);
            default -> writeJsonString(builder, String.valueOf(value));
        }
    }

    /**
     * Writes the given number as a JSON number, falling back to a JSON string whenever its rendering is not
     * one JSON actually accepts.
     * <p>
     * {@link Double#NaN} and both infinities render as a bare {@code NaN}, {@code Infinity} or
     * {@code -Infinity}, none of which JSON has any notion of, and a custom {@link Number} subclass is free
     * to render as absolutely anything (a thousands separator, a currency symbol, a locale-specific decimal
     * comma). Appending either verbatim would leave a line no parser can read, which for a file whose whole
     * purpose is to be machine-readable later means the dropped documents are lost a second time — so the
     * value is preserved as a string instead of quietly corrupting the line carrying it.
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

    private static void writeJsonObject(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeJsonString(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            writeJson(builder, entry.getValue());
        }
        builder.append('}');
    }

    private static void writeJsonArray(StringBuilder builder, Iterable<?> iterable) {
        builder.append('[');
        boolean first = true;
        for (Object element : iterable) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeJson(builder, element);
        }
        builder.append(']');
    }

    /**
     * Writes the given string as a JSON string literal, escaping the characters JSON requires (quote,
     * backslash, and control characters) so an arbitrary log message can never produce invalid JSON or break
     * out of the string it is written into.
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
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

}
