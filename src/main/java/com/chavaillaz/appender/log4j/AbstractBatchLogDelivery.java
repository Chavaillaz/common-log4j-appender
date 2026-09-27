package com.chavaillaz.appender.log4j;

import static java.util.Collections.singletonList;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.Getter;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.BackoffPolicy;
import com.chavaillaz.appender.Batcher;
import com.chavaillaz.appender.BatcherListener;
import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.DocumentSizeEstimator;
import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;
import com.chavaillaz.appender.TransmissionResult;

/**
 * Abstract implementation of logs transmission by batches.
 * <p>
 * A batch ready to be sent (threshold reached, or {@link #flush()} called) is handed off to a virtual
 * thread instead of being sent by the calling thread, so a slow or blocked transmission never delays
 * accumulation of the next batch. Concurrent transmissions are bounded by
 * {@link DeliveryOptions#maxConcurrentTransmissions()}: beyond that limit, the calling thread (in
 * practice, {@link AbstractLogDeliveryAppender}'s background thread, never the application thread) waits
 * for one to complete before handing over the next.
 * <p>
 * A failed transmission's documents are put back at the front of the batch for a later retry instead of
 * being lost. While batching is enabled (a flush threshold greater than {@code 1}) and the last attempt is
 * still failing, a new one is not attempted for every incoming message; retries wait for the next
 * {@link #flush()} instead. Retried documents may end up redelivered out of order under concurrent
 * transmissions; each already carries its own timestamp, so this is an accepted trade-off.
 * <p>
 * Two safety nets bound the cost of a struggling downstream system, or of a single "poison document" it can
 * never accept, so neither can grow memory without bound or wedge the pipeline forever: the pending batch
 * is capped at {@link DeliveryOptions#maxPendingDocuments()} via the shared {@link com.chavaillaz.appender.BoundedTrim}
 * policy (oldest documents discarded past that, both while accumulating and while requeuing after a
 * failure, the same policy {@link AbstractLogDeliveryAppender}'s own pending event queue applies), and a
 * batch that fails {@link DeliveryOptions#maxConsecutiveFailures()} times in a row (counted for the
 * documents that failed, not delivery-wide, so neither an unrelated batch succeeding concurrently nor a
 * newer document joining them can reset it) is discarded instead of retried indefinitely. Both are reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)},
 * so this data loss is never silent.
 * <p>
 * A batch due for (re)transmission bigger than {@link DeliveryOptions#maxRequestDocuments()} is first
 * split into chunks of at most that size, each dispatched, retried and (subject to
 * {@link DeliveryOptions#maxConcurrentTransmissions()}) transmitted independently. Disabled by default;
 * only matters for a downstream system with its own limit on documents or payload size per request.
 * <p>
 * The generic accumulate/backpressure/retry machinery itself lives in {@link Batcher}; this class is a thin
 * adapter wiring it to {@link LogConfiguration}, {@link LogDelivery} and {@link LogDeliveryListener}.
 *
 * @param <C> The configuration type
 */
public abstract class AbstractBatchLogDelivery<C extends LogConfiguration> implements LogDelivery {

    /**
     * Log4j2's own internal diagnostic channel rather than an ordinary {@code LogManager} logger: this
     * class runs inside an appender normally attached to the root logger, so an ordinary logger here would
     * write its diagnostics straight back into the pipeline they are about, which loops (see {@link
     * com.chavaillaz.appender.Batcher}, whose own logger carries the measurement). Make it visible with
     * {@code -Dlog4j2.debug}, a {@code status} attribute on the configuration, or a {@code <StatusListener>}.
     */
    private static final Logger log = StatusLogger.getLogger();

    @Getter
    private final C configuration;

    /**
     * The listener notified of the outcome of every transmission attempt.
     * <p>
     * Volatile because {@link #setListener(LogDeliveryListener)} exists precisely to change it after
     * construction, from whatever thread the application happens to wire it up on, while every read of it
     * happens on one of the virtual threads the {@link Batcher} transmits on. Nothing links the two: the
     * writer touches neither the batching engine's monitor nor its semaphore, so without this a
     * transmission thread is free to keep using the previous listener indefinitely, or to see a new one
     * before its own constructor finished.
     */
    @Getter
    private volatile LogDeliveryListener listener;

    private final Batcher<Map<String, Object>> batcher;

    /**
     * The close timeout this delivery was built with, kept only as a fallback for {@link #closeTimeout()}
     * when reading the current one fails. Every other setting can go unenforced until the next read; this
     * one is read once, at the only moment that matters, with no later attempt to fall back on.
     */
    private final @Nullable Duration initialCloseTimeout;

    /**
     * Creates a new logs delivery handler sending them by batches.
     *
     * @param configuration The configuration to use
     */
    protected AbstractBatchLogDelivery(C configuration) {
        this(configuration, LogDeliveryListener.NOOP);
    }

    /**
     * Creates a new logs delivery handler sending them by batches.
     *
     * @param configuration The configuration to use
     * @param listener      The listener to notify of the outcome of every transmission attempt, or
     *                      {@code null} to not notify any listener
     */
    protected AbstractBatchLogDelivery(C configuration, @Nullable LogDeliveryListener listener) {
        this.configuration = configuration;
        DeliveryOptions options = configuration.getDeliveryOptions();
        this.initialCloseTimeout = options.closeTimeout();
        warnAboutDegradedConfiguration(options);
        this.listener = listener != null ? listener : LogDeliveryListener.NOOP;
        Batcher.Builder<Map<String, Object>> batcherBuilder = Batcher.<Map<String, Object>>builder()
                // Read through getDeliveryOptions() on every call rather than off the instance captured
                // above, so a configuration varying a limit at runtime is still honored, exactly as the
                // per-setting getters it replaced were.
                .flushThreshold(() -> configuration.getDeliveryOptions().flushThreshold())
                .maxPendingElements(this::getMaxPendingDocuments)
                .maxRequestElements(this::getMaxRequestDocuments)
                .maxConsecutiveFailures(this::getMaxConsecutiveFailures)
                .maxPendingBytes(this::getMaxPendingBytes)
                .maxRequestBytes(this::getMaxRequestBytes)
                // Fixed once here, unlike the limits above: the Batcher sizes its semaphore from it, and the
                // backoff policy is built once from these two durations.
                .maxConcurrentTransmissions(options.maxConcurrentTransmissions())
                .backoff(BackoffPolicy.exponential(options.retryBackoff(), options.maxRetryBackoff()))
                .transmissionThreadPrefix("log-delivery-transmission-")
                .elementNoun("document")
                .transport(this::sendBulk)
                .listener(new BatcherListener<>() {
                    @Override
                    public void onSucceeded(int count, long durationMillis) {
                        AbstractBatchLogDelivery.this.listener.onTransmissionSucceeded(count, durationMillis);
                    }

                    @Override
                    public void onFailed(int count, @Nullable Throwable cause) {
                        AbstractBatchLogDelivery.this.listener.onTransmissionFailed(count, cause);
                    }

                    @Override
                    public void onDropped(List<Map<String, Object>> dropped, String reason) {
                        AbstractBatchLogDelivery.this.listener.onDocumentsDropped(dropped, reason);
                    }
                });

        // Always wired in, but only measuring while a byte limit is in effect (see measureIfBounded).
        // Deciding that once here instead, from the configuration, made getMaxPendingBytes() and
        // getMaxRequestBytes() useless as the overridable hooks they are documented to be: a subclass
        // raising a limit through one of them got a limit that silently enforced nothing.
        batcherBuilder.elementSize(this::measureIfBounded);
        this.batcher = batcherBuilder.build();
    }

    /**
     * Whether the given byte limit actually bounds anything, as opposed to being left unset or explicitly
     * disabled.
     *
     * @param limit The limit to check
     * @return {@code true} if the limit is in effect
     */
    private static boolean isBounded(long limit) {
        return limit > 0 && limit < Long.MAX_VALUE;
    }

    /**
     * Measures the given document through {@link #estimateDocumentSize(Map)}, or counts it as weightless
     * while neither byte limit is in effect: measuring costs something on the path every document takes, and
     * a delivery with no size limit to enforce should not pay it.
     * <p>
     * Checked per document rather than once at construction, so both limits keep working through the
     * overridable getters below. A limit coming into effect while documents are already accumulating starts
     * out under-counting them; the running total re-anchors on the next batch, so it is at worst enforced
     * a little late rather than not at all.
     *
     * @param document The document to measure
     * @return The estimated size of the document in bytes, or {@code 0} while no byte limit is in effect
     */
    private long measureIfBounded(Map<String, Object> document) {
        return isBounded(getMaxPendingBytes()) || isBounded(getMaxRequestBytes())
                ? estimateDocumentSize(document)
                : 0;
    }

    /**
     * Warns about configuration values that degrade this delivery's functionality instead of leaving it
     * fully working, so a misconfiguration is visible at startup instead of only showing up later as
     * unexplained data loss or runaway retries. Unlike {@code MaxConcurrentTransmissions} below {@code 1}
     * (validated by {@link Batcher}'s constructor below, which throws rather than warns), none of these
     * leave the pipeline stuck: a request never blocks waiting for one, so they are deliberately still
     * allowed, only reported.
     *
     * @param options The options to inspect
     */
    private void warnAboutDegradedConfiguration(DeliveryOptions options) {
        int maxPendingDocuments = options.maxPendingDocuments();
        if (maxPendingDocuments <= 0) {
            log.warn("MaxPendingDocuments is {} (0 or less), every document will be discarded immediately, "
                    + "effectively disabling this delivery", maxPendingDocuments);
        }

        int maxRequestDocuments = options.maxRequestDocuments();
        if (maxRequestDocuments <= 0) {
            log.warn("MaxRequestDocuments is {} (0 or less), request chunking is disabled", maxRequestDocuments);
        }

        int maxConsecutiveFailures = options.maxConsecutiveFailures();
        if (maxConsecutiveFailures <= 0) {
            log.warn("MaxConsecutiveFailures is {} (0 or less), failed batches will be retried indefinitely "
                    + "instead of eventually discarded", maxConsecutiveFailures);
        }
    }

    /**
     * Sets the listener to notify of the outcome of every transmission attempt.
     *
     * @param listener The listener, or {@code null} to stop notifying any listener
     */
    public void setListener(@Nullable LogDeliveryListener listener) {
        this.listener = listener != null ? listener : LogDeliveryListener.NOOP;
    }

    /**
     * Maximum number of documents a pending batch can hold in memory; past it, the oldest documents are
     * discarded, favoring the most recently received ones. Independent of {@link #getMaxRequestDocuments()}.
     * Defaults to {@link DeliveryOptions#maxPendingDocuments()}; override if the limit should depend on
     * more than just the configuration.
     *
     * @return The maximum number of documents a pending batch can hold in memory
     */
    protected int getMaxPendingDocuments() {
        return configuration.getDeliveryOptions().maxPendingDocuments();
    }

    /**
     * Maximum number of documents sent together in a single request; a bigger pending batch is split into
     * chunks of at most this size before dispatch. Defaults to {@link DeliveryOptions#maxRequestDocuments()}
     * (unbounded, no chunking); override if the limit should depend on more than just the configuration.
     *
     * @return The maximum number of documents sent together in a single request
     */
    protected int getMaxRequestDocuments() {
        return configuration.getDeliveryOptions().maxRequestDocuments();
    }

    /**
     * Maximum number of consecutive failed transmission attempts allowed for the same pending batch before
     * it is discarded instead of retried again, so a single document the downstream system can never accept
     * (a "poison document") cannot block everything behind it forever. Defaults to
     * {@link DeliveryOptions#maxConsecutiveFailures()}; override if the limit should depend on more than
     * just the configuration.
     *
     * @return The maximum number of consecutive failed attempts allowed
     */
    protected int getMaxConsecutiveFailures() {
        return configuration.getDeliveryOptions().maxConsecutiveFailures();
    }

    /**
     * Maximum estimated size a pending batch can reach in memory; past it, the oldest documents are
     * discarded, the size-based counterpart of {@link #getMaxPendingDocuments()}. Defaults to
     * {@link DeliveryOptions#maxPendingBytes()} (unbounded); override if the limit should depend on more than
     * just the configuration.
     *
     * @return The maximum estimated size a pending batch can reach, in bytes
     */
    protected long getMaxPendingBytes() {
        return configuration.getDeliveryOptions().maxPendingBytes();
    }

    /**
     * Maximum estimated size of a single request; a batch weighing more is split into chunks that each stay
     * under it, the size-based counterpart of {@link #getMaxRequestDocuments()}. Defaults to
     * {@link DeliveryOptions#maxRequestBytes()} (unbounded); override if the limit should depend on more than
     * just the configuration.
     *
     * @return The maximum estimated size of a single request, in bytes
     */
    protected long getMaxRequestBytes() {
        return configuration.getDeliveryOptions().maxRequestBytes();
    }

    /**
     * Estimates how many bytes the given document takes on the wire, used only to enforce
     * {@link #getMaxPendingBytes()} and {@link #getMaxRequestBytes()} — and never called at all while both
     * are left unbounded, so a delivery that does not use them pays nothing for this.
     * <p>
     * Defaults to {@link DocumentSizeEstimator#estimate(Map)}, a compact-JSON approximation good enough to
     * keep a request under a server's maximum content length. Override it when the actual encoding is known
     * and differs enough to matter: an Elasticsearch bulk body adds an action line per document, a
     * compressed or binary transport is smaller than its JSON rendering, and either way the transport itself
     * is the only thing that knows. Expected to be cheap and free of side effects: it is called once per
     * document on the way in, and again per document when a batch is split for transmission.
     *
     * @param document The document to measure
     * @return The estimated size of the document in bytes
     */
    protected long estimateDocumentSize(Map<String, Object> document) {
        return DocumentSizeEstimator.estimate(document);
    }

    /**
     * {@inheritDoc}
     *
     * @param document The record data, or {@code null} to skip this event
     */
    @Override
    public void send(@Nullable Map<String, Object> document) {
        // A null document is treated as a no-op rather than stacked as-is, be it because of a caller
        // mistake or a LogConverter implementation choosing to signal "skip this event" by returning null.
        if (document != null) {
            send(singletonList(document));
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param documents The list of record data
     */
    @Override
    public void send(@Nullable List<@Nullable Map<String, Object>> documents) {
        if (documents != null && !documents.isEmpty()) {
            List<Map<String, Object>> filtered = withoutNullElements(documents);
            if (!filtered.isEmpty()) {
                batcher.add(filtered);
            }
        }
    }

    /**
     * Filters out any {@code null} element of the given list, only allocating a new list if at least one
     * was actually found, so that the common case (no null element) does not pay for an extra copy.
     *
     * @param documents The list of documents to filter
     * @return The given list, or a copy of it without its null elements if it had any
     */
    private static List<Map<String, Object>> withoutNullElements(List<@Nullable Map<String, Object>> documents) {
        int firstNullIndex = -1;
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i) == null) {
                firstNullIndex = i;
                break;
            }
        }
        if (firstNullIndex < 0) {
            return documents;
        }

        List<Map<String, Object>> filtered = new ArrayList<>(documents.size() - 1);
        filtered.addAll(documents.subList(0, firstNullIndex));
        for (int i = firstNullIndex + 1; i < documents.size(); i++) {
            Map<String, Object> document = documents.get(i);
            if (document != null) {
                filtered.add(document);
            }
        }
        return filtered;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        batcher.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHealthy() {
        return !batcher.isTransmissionFailing();
    }

    /**
     * Sends the given list of documents representing logs.
     * <p>
     * The returned future may be completed synchronously (before this method even returns) or
     * asynchronously from another thread once the transmission actually completes; either way, this method
     * itself is not expected to block on the transmission. Use {@link TransmissionResult#success()} when
     * every document was accepted, {@link TransmissionResult#failure(Throwable)} when none were (letting the
     * whole batch be retried or eventually discarded together), or
     * {@link TransmissionResult#partialFailure(List, Throwable)} when only some were rejected (e.g. one
     * malformed document in an otherwise-successful bulk response) so only those, not the accepted ones
     * alongside them, are retried or counted toward {@link DeliveryOptions#maxConsecutiveFailures()}. A
     * synchronously thrown exception, a future completed exceptionally, or a future completed with
     * {@code null} are all treated the same as {@link TransmissionResult#failure(Throwable)}.
     *
     * @param documents The documents to send, as an unmodifiable list
     * @return A future completed with the outcome of the transmission
     */
    protected abstract CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents);

    /**
     * Flushes the current batch and waits, up to {@link DeliveryOptions#closeTimeout()}, for every
     * pending transmission (including that final one) to complete, so a subclass can safely release
     * resources used by {@link #sendBulk(List)} right after this call returns without racing an in-flight
     * transmission.
     * <p>
     * Whatever is still pending once that wait is over — documents a failing final transmission put back, or
     * documents held by a transmission that never completed — is reported through
     * {@link LogDeliveryListener#onDocumentsDropped(List, String)} rather than discarded along with this
     * delivery, giving a dead-letter sink a last chance to save them.
     *
     * @throws Exception Never thrown by this implementation (an interruption while waiting is caught,
     *                    re-flagged on the current thread, and logged instead)
     */
    @Override
    public void close() throws Exception {
        batcher.close(closeTimeout());
    }

    /**
     * Reads how long to wait for in-flight transmissions while closing, falling back to the value this
     * delivery was built with if the configuration cannot be read at all.
     * <p>
     * Guarded because of where it sits: this is the argument to the one call that flushes the pending
     * batch, waits for it and reports whatever is left, so a failure to read it never reached that call --
     * losing the batch in silence and leaving the engine running with nothing left to close it.
     *
     * @return The close timeout, or {@code null} to wait indefinitely
     */
    private @Nullable Duration closeTimeout() {
        try {
            return configuration.getDeliveryOptions().closeTimeout();
        } catch (Throwable e) {
            log.warn("Unable to read the close timeout, falling back to the one configured at startup ({})",
                    initialCloseTimeout, e);
            return initialCloseTimeout;
        }
    }

    /**
     * Waits until every currently dispatched transmission (including the one triggered by the last call to
     * {@link #flush()}) has completed, so that closing any resource used by {@link #sendBulk(List)}
     * (typically done right after calling this method) does not race with an in-flight transmission.
     * <p>
     * Also used by tests to deterministically wait for asynchronous transmissions to complete.
     */
    void awaitPendingTransmissions() throws InterruptedException {
        batcher.awaitPendingTransmissions();
    }

}
