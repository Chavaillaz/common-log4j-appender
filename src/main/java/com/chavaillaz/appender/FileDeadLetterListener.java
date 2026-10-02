package com.chavaillaz.appender;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * A ready-made {@link LogDeliveryListener} appending every irrecoverably dropped document to a local file, one
 * JSON object per line (newline-delimited JSON), as a last resort so that data a {@link LogDelivery} gave up on
 * is not lost outright. Every other method is a no-op: compose it with another listener (see
 * {@link LogDeliveryListener#compose(LogDeliveryListener...)}), or extend it, to observe transmissions as well.
 * <p>
 * The file rotates every day, the date of the drop being inserted before the extension of the file given:
 * documents dropped on 28 September 2026 go to {@code dead-letters-2026-09-28.ndjson} for
 * {@code dead-letters.ndjson}, so that an outage lasting days leaves files that can be replayed, archived or
 * deleted one day at a time.
 * <p>
 * Each line wraps the document, unmodified, with the reason it was dropped and when:
 * {@code {"reason":"...","droppedAt":"...","document":{...}}}. Lines are appended whole, never interleaved with
 * those of concurrent callers, nor of other listeners or processes appending to the same file, and a line left
 * incomplete by a write cut short (a crash, a full disk) is ended before the next one. A failure to write hands
 * the documents it left out to {@link #onWriteFailure(Path, List, String, IOException)}, which logs them by
 * default, rather than throwing into the pipeline.
 * <p>
 * The JSON is written without any dependency: {@code null}, {@link Boolean}, {@link Number}, {@link Map},
 * {@link Iterable} and array values as their JSON counterpart, the latter three recursively and keys in their
 * own iteration order, and anything else, {@link String} included, as a JSON string of its
 * {@link String#valueOf(Object)} rendering. A number JSON cannot express ({@link Double#NaN}, an infinity, a
 * custom {@link Number} rendering otherwise) is written as a string, and nesting is followed to a bounded
 * depth, so that no value can leave a line a parser cannot read. A document that cannot be rendered at all,
 * a value whose {@code toString()} throws for instance, costs only its own line, replaced by one naming the
 * failure.
 */
public class FileDeadLetterListener implements LogDeliveryListener {

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

    /**
     * The most bytes appended by a single write, made of whole lines, unless one line alone is longer.
     */
    private static final int MAX_WRITE_BYTES = 64 * 1024;

    /**
     * What ends each line, encoded.
     */
    private static final byte[] LINE_SEPARATOR = System.lineSeparator().getBytes(UTF_8);

    /**
     * The file whose name the one of each day is derived from, never written to itself.
     */
    private final Path file;

    /**
     * The name of {@link #file} up to its extension, which the date of each file follows.
     */
    private final String baseName;

    /**
     * The extension of {@link #file}, its dot included, or an empty string for a file without one.
     */
    private final String extension;

    /**
     * Tells when a document is dropped, and in which time zone its day starts.
     */
    private final Clock clock;

    /**
     * Serializes the appends of this listener, each checking whether the file ends with a line left open before
     * appending to it.
     * <p>
     * A lock rather than {@code synchronized}, the callers often being virtual threads: on Java 21, one blocked
     * entering a monitor keeps its carrier, so that writers waiting their turn could hold every carrier and
     * stall every virtual thread of the application, where one waiting for a lock unmounts. Package-private so
     * that a test can hold it the way a slow write does.
     */
    final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates a new dead-letter listener rotating its file at midnight in the default time zone of the system
     * (see {@link #FileDeadLetterListener(Path, Clock)}).
     *
     * @param file The file the name of the file of each day is derived from
     * @throws UncheckedIOException If the parent directory does not exist and cannot be created
     */
    public FileDeadLetterListener(Path file) {
        this(file, Clock.systemDefaultZone());
    }

    /**
     * Creates a new dead-letter listener, creating the parent directory of the given file right away, so that
     * a misconfiguration shows at startup rather than when something needs saving.
     *
     * @param file  The file the name of the file of each day is derived from, in the same directory: each is
     *              created if it does not exist yet, or appended to if it does
     * @param clock The clock telling when a document is dropped, whose time zone decides when a day starts
     * @throws UncheckedIOException If the parent directory does not exist and cannot be created
     */
    public FileDeadLetterListener(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Path fileName = file.getFileName();
        String name = fileName != null ? fileName.toString() : "";
        // A leading dot starts a hidden name rather than an extension
        int extensionStart = name.lastIndexOf('.');
        this.baseName = extensionStart > 0 ? name.substring(0, extensionStart) : name;
        this.extension = extensionStart > 0 ? name.substring(extensionStart) : "";
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
            // Nothing to append, nor a file worth creating: an empty dead-letter file reads as logs lost
            return;
        }
        Instant droppedAt = clock.instant();
        List<byte[]> lines = new ArrayList<>(documents.size());
        for (Map<String, Object> document : documents) {
            lines.add(toLine(document, reason, droppedAt).getBytes(UTF_8));
        }
        Path target = fileOf(LocalDate.ofInstant(droppedAt, clock.getZone()));
        // Counted write by write, so that one failing partway hands over only the documents it left out
        int appended = 0;
        try {
            writeLock.lock();
            try (OutputStream out = openForAppend(target)) {
                if (endsWithPartialLine(target)) {
                    // A write cut short, by a crash or a full disk, would otherwise swallow the first line
                    out.write(LINE_SEPARATOR);
                }
                while (appended < lines.size()) {
                    appended = appendWholeLines(out, lines, appended);
                }
            } finally {
                writeLock.unlock();
            }
        } catch (IOException e) {
            onWriteFailure(target, documents.subList(appended, documents.size()), reason, e);
        }
    }

    /**
     * Opens the given file for appending, creating it if it does not exist. Package-private so that a test can
     * make a write fail partway, as a full disk does.
     *
     * @param file The file to append to
     * @return A stream appending to it
     * @throws IOException If the file cannot be opened
     */
    OutputStream openForAppend(Path file) throws IOException {
        return Files.newOutputStream(file, CREATE, APPEND);
    }

    /**
     * Whether the given file ends in the middle of a line, a previous write having been cut short, so that the
     * next line appended must first end it. Reads its last byte alone, both line separators ending with a
     * line feed. A file that cannot be read, being write-only or read on an interrupted thread, is appended to
     * as is: the check must never cost the write.
     *
     * @param file The file about to be appended to
     * @return {@code true} if it exists, can be read, and its last byte is not a line feed
     */
    private static boolean endsWithPartialLine(Path file) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, READ)) {
            long size = channel.size();
            if (size == 0) {
                return false;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(size - 1).read(last);
            return last.get(0) != '\n';
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            log.debug("Unable to check whether {} ends with a complete line, appending to it as is", file, e);
            return false;
        }
    }

    /**
     * Appends in a single write, each followed by a line separator, as many of the given lines as fit in
     * {@link #MAX_WRITE_BYTES}, starting from the given one, which goes in however long: on a local file system,
     * a write to a file opened for appending lands at its end in one piece, so that another listener or process
     * appending to the same file puts its lines between these, never inside one.
     *
     * @param out   The file to append to
     * @param lines The lines to append, encoded
     * @param from  The index of the first line to append
     * @return The index past the last line appended
     * @throws IOException If the write fails, possibly after appending part of it
     */
    private static int appendWholeLines(OutputStream out, List<byte[]> lines, int from) throws IOException {
        ByteArrayOutputStream write = new ByteArrayOutputStream(MAX_WRITE_BYTES);
        int next = from;
        do {
            write.writeBytes(lines.get(next));
            write.writeBytes(LINE_SEPARATOR);
            next++;
        } while (next < lines.size() && write.size() + lines.get(next).length + LINE_SEPARATOR.length <= MAX_WRITE_BYTES);
        write.writeTo(out);
        return next;
    }

    /**
     * The file collecting the documents dropped on the given day, next to the file given at construction.
     *
     * @param day The day the documents were dropped on
     * @return The file of that day
     */
    private Path fileOf(LocalDate day) {
        return file.resolveSibling(baseName + "-" + day + extension);
    }

    /**
     * Renders one dropped document as the line to append for it, or as a line naming why it could not be
     * rendered: rendering runs application code (a {@code toString()}, the iteration of a custom collection),
     * and one document failing must not cost the others handed over with it.
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
            // Throwable, a value whose own toString() recurses overflowing the stack whatever MAX_DEPTH says.
            // Only the class name is used, the toString() of this exception being application code too
            String failure = e.getClass().getName();
            log.warn("Unable to render a dropped document as JSON ({}), saving a placeholder in its place", failure);
            return toJson(wrap(Map.of("unrenderable", failure), reason, droppedAt));
        }
    }

    /**
     * Wraps the given document with the reason it was dropped and when, without modifying it, so that a field
     * of the document bearing the same name is kept.
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
     * Called when appending dropped documents to their file fails, logging the failure along with the
     * documents by default, so that they at least appear somewhere. Override to fall back to another sink.
     *
     * @param file      The file of the day the documents could not be appended to
     * @param documents The documents that could not be appended, those appended before the failure staying in
     *                  the file
     * @param reason    The reason they were originally dropped
     * @param cause     The failure encountered while appending them
     */
    protected void onWriteFailure(Path file, List<Map<String, Object>> documents, String reason, IOException cause) {
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
            // Cut rather than followed, a document containing itself recursing until the stack runs out
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
