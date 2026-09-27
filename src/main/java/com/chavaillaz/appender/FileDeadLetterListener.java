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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;

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
 * {@link Boolean}, {@link Number}, {@link Map}, {@link Iterable} and array values are written as their natural
 * JSON counterpart (recursively for the latter three), anything else (including {@link String}) is written as a
 * JSON string via {@link String#valueOf(Object)}. A key's own iteration order is preserved as-is, without
 * imposing any sorting. A number JSON has no notion of ({@link Double#NaN}, either infinity, or whatever a
 * custom {@link Number} subclass renders as) falls back to a JSON string, so a single such value cannot
 * leave a line no parser can read. Nesting is followed to a bounded depth, past which a value is replaced
 * by a marker, so a document that (however unusually) contains itself cannot recurse forever. A document
 * that cannot be rendered at all — a value whose {@code toString()} throws, a custom {@link Map} or
 * {@link Iterable} that throws while iterated — costs only its own line, replaced by one naming the
 * failure, rather than the whole batch it was handed over with.
 */
public class FileDeadLetterListener implements LogDeliveryListener {

    /**
     * Log4j2's own internal diagnostic channel rather than an ordinary {@code LogManager} logger: this
     * class runs inside an appender normally attached to the root logger, so an ordinary logger here would
     * write its diagnostics straight back into the pipeline they are about, which loops (see {@link
     * com.chavaillaz.appender.Batcher}, whose own logger carries the measurement). Make it visible with
     * {@code -Dlog4j2.debug}, a {@code status} attribute on the configuration, or a {@code <StatusListener>}.
     */
    private static final Logger log = StatusLogger.getLogger();

    /**
     * The grammar JSON allows for a number (RFC 8259), used by {@link #writeJsonNumber(StringBuilder, Number)}
     * to tell a {@link Number} that renders as one from a {@link Double#NaN}, an infinity, or a custom
     * subclass rendering as something else entirely.
     */
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][-+]?\\d+)?");

    /**
     * Maximum nesting depth written before a value is replaced by {@link #TRUNCATED_MARKER}, guarding
     * against a structure that contains itself (which would otherwise recurse until the stack runs out).
     * Far deeper than any log document has reason to be, and the same number
     * {@link DocumentSizeEstimator#estimate(Map)} stops measuring at — though not quite the same bound in
     * practice, since the document is wrapped here before being written and so starts one level deeper than
     * the estimator counts it. Only a document nested to within one level of the limit could notice, and
     * both numbers are approximations of a structure no log document has any business having.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * Written in place of a value nested deeper than {@link #MAX_DEPTH}, so a truncated document says so on
     * the line itself rather than looking like it simply held that value.
     */
    private static final String TRUNCATED_MARKER = "<truncated: maximum nesting depth of " + MAX_DEPTH + " reached>";

    private final Path file;

    /**
     * Serializes appends to {@link #file}, so concurrent callers never interleave mid-line.
     * <p>
     * A lock rather than {@code synchronized} because of which threads call in: a delivery reports its drops
     * from its own virtual threads, and a full queue reports an evicted event from whichever application
     * thread is logging, a virtual one in an application running on them. On Java 21, a virtual thread
     * blocked entering a monitor keeps its carrier, and the scheduler adds none to make up for it: with as
     * many writers waiting as there are carriers, no other virtual thread in the application could run until
     * the write ahead of them finished. A virtual thread waiting for this lock unmounts instead.
     * <p>
     * Package-private rather than private so a test can hold it the way a slow write does.
     */
    final ReentrantLock writeLock = new ReentrantLock();

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
        if (documents.isEmpty()) {
            // Nothing to append, and nothing worth creating the file for either: an empty dead-letter file
            // sitting next to an application is read as "logs were lost here", which is the one thing that
            // did not happen. Reachable from any listener chain forwarding a notification it did not filter.
            return;
        }
        Instant droppedAt = Instant.now();
        List<String> lines = new ArrayList<>(documents.size());
        for (Map<String, Object> document : documents) {
            lines.add(toLine(document, reason, droppedAt));
        }
        try {
            writeLock.lock();
            try {
                Files.write(file, lines, UTF_8, CREATE, APPEND);
            } finally {
                writeLock.unlock();
            }
        } catch (IOException e) {
            onWriteFailure(documents, reason, e);
        }
    }

    /**
     * Renders one dropped document as the line to append for it, falling back to a line recording why it
     * could not be rendered rather than letting that failure escape.
     * <p>
     * Rendering runs application code this sink does not control: a value's {@code toString()}, a custom
     * {@link Map} or {@link Iterable}'s iteration. Unguarded, one document whose rendering fails takes
     * every other document handed over with it -- up to a full pending batch, discarded a second time by
     * the very thing meant to be their last resort.
     *
     * @param document  The document being dropped
     * @param reason    A short, human-readable explanation of why it was dropped
     * @param droppedAt The instant it was dropped at
     * @return The line to append for this document
     */
    private String toLine(Map<String, Object> document, String reason, Instant droppedAt) {
        try {
            return toJson(wrap(document, reason, droppedAt));
        } catch (Throwable e) {
            // Throwable, not Exception: MAX_DEPTH only rules out recursion between the documents this class
            // walks, not inside a value whose own toString() recurses. Only the class name is used, and it
            // is logged as a plain value: this exception is application code too, and its own toString() is
            // free to throw in turn, right on the path meant to contain the first failure.
            String failure = e.getClass().getName();
            log.warn("Unable to render a dropped document as JSON ({}), saving a placeholder in its place", failure);
            return toJson(wrap(Map.of("unrenderable", failure), reason, droppedAt));
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
        writeJson(builder, value, 0);
        return builder.toString();
    }

    private static void writeJson(StringBuilder builder, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            // Cut rather than followed any further: a document that (however unusually) contains itself
            // would otherwise recurse until the stack runs out, and a StackOverflowError is an Error, so it
            // slips past every "a misbehaving listener must not break the pipeline" guard in Batcher and
            // AbstractLogDeliveryAppender -- all of which catch Exception -- and escapes through close(),
            // taking down the shutdown path this sink is most likely to be called on.
            writeJsonString(builder, TRUNCATED_MARKER);
            return;
        }
        switch (value) {
            case null -> builder.append("null");
            case Boolean bool -> builder.append(bool);
            case Number number -> writeJsonNumber(builder, number);
            case Map<?, ?> map -> writeJsonObject(builder, map, depth);
            case Iterable<?> iterable -> writeJsonArray(builder, iterable, depth);
            case Object array when array.getClass().isArray() ->
                    writeJsonArray(builder, DocumentSizeEstimator.elementsOf(array), depth);
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
     * Writes the given string as a JSON string literal, escaping the characters JSON requires (quote,
     * backslash, and control characters) so an arbitrary log message can never produce invalid JSON or break
     * out of the string it is written into.
     * <p>
     * Half of a surrogate pair found without its other half is escaped too, with the same Unicode escape as
     * a control character, which JSON's grammar allows for any code unit. It is not a character, so UTF-8
     * has no encoding for it, and {@link Files#write} reports one rather than replacing it: a message cut in
     * the middle of an emoji failed the write of the whole batch it was dropped with, after part of that
     * batch had already reached the file -- leaving an unterminated line for the next append to run into,
     * corrupting a second document as well. Escaped, it is kept exactly as it was, and only ASCII reaches
     * the encoder in its place.
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
