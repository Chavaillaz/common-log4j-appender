package com.chavaillaz.appender.log4j;

import static java.util.Collections.singletonList;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.DeliveryHealth;
import com.chavaillaz.appender.DeliveryMetrics;
import com.chavaillaz.appender.DeliveryOptions;
import com.chavaillaz.appender.DocumentSizeEstimator;
import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;
import com.chavaillaz.appender.TransmissionResult;
import com.chavaillaz.appender.internal.BackoffPolicy;
import com.chavaillaz.appender.internal.Batcher;
import com.chavaillaz.appender.internal.BatcherListener;

/**
 * Base of a delivery transmitting logs by batches, adapting the batching engine of the library to a
 * {@link LogConfiguration} and a {@link LogDeliveryListener}: a subclass only implements
 * {@link #sendBulk(List)}.
 * <p>
 * A batch due (threshold reached, or {@link #flush()} called) is transmitted on a virtual thread of its own,
 * at most {@link DeliveryOptions#maxConcurrentTransmissions()} at once, the thread accumulating documents (the
 * background thread of {@link AbstractLogDeliveryAppender}, never the application thread) waiting for a slot
 * past that. The documents of a failed transmission are put back at the front of the batch and retried, so
 * that concurrent transmissions may deliver documents out of order, each carrying its own timestamp.
 * <p>
 * The pending batch is capped by {@link DeliveryOptions#maxPendingDocuments()} and
 * {@link DeliveryOptions#maxPendingBytes()}, a request by {@link DeliveryOptions#maxRequestDocuments()} and
 * {@link DeliveryOptions#maxRequestBytes()}, and documents failing
 * {@link DeliveryOptions#maxConsecutiveFailures()} times in a row are given up on. Every document discarded is
 * reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}.
 *
 * @param <C> The configuration type
 */
public abstract class AbstractBatchLogDelivery<C extends LogConfiguration> implements LogDelivery {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    private final C configuration;

    /**
     * The listener notified of the outcome of every transmission attempt, volatile since
     * {@link #setListener(LogDeliveryListener)} may replace it from any thread while transmissions read it.
     */
    private volatile LogDeliveryListener listener;

    private final Batcher<Map<String, Object>> batcher;

    /**
     * The close timeout this delivery was built with, used by {@link #closeTimeout()} when the current one
     * cannot be read.
     */
    private final @Nullable Duration initialCloseTimeout;

    /**
     * Creates a delivery sending documents by batches.
     *
     * @param configuration The configuration to use
     */
    protected AbstractBatchLogDelivery(C configuration) {
        this(configuration, LogDeliveryListener.NOOP);
    }

    /**
     * Creates a delivery sending documents by batches.
     *
     * @param configuration The configuration to use
     * @param listener      The listener to notify of the outcome of every transmission attempt, or
     *                      {@code null} to not notify any listener
     */
    protected AbstractBatchLogDelivery(C configuration, @Nullable LogDeliveryListener listener) {
        this.configuration = configuration;
        DeliveryOptions options = configuration.getDeliveryOptions();
        this.initialCloseTimeout = options.closeTimeout();
        this.listener = listener != null ? listener : LogDeliveryListener.NOOP;
        this.batcher = Batcher.<Map<String, Object>>builder()
                // Read again on every call, so that a limit varied at runtime is honored
                .flushThreshold(() -> configuration.getDeliveryOptions().flushThreshold())
                .maxPendingElements(this::getMaxPendingDocuments)
                .maxRequestElements(this::getMaxRequestDocuments)
                .maxConsecutiveFailures(this::getMaxConsecutiveFailures)
                .maxPendingBytes(this::getMaxPendingBytes)
                .maxRequestBytes(this::getMaxRequestBytes)
                // Always wired in, measuring only while a byte limit is in effect, so that the overridable
                // getters of both limits can turn them on at any time
                .elementSize(this::measureIfBounded)
                // Fixed once, unlike the limits above
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
                })
                .build();
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
     * Measures the given document through {@link #estimateDocumentSize(Map)}, or counts it as weightless while
     * neither byte limit is in effect, measuring costing something for every document. A limit coming into
     * effect under-counts the documents already pending until the next batch, being enforced late at worst.
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
     * The configuration this delivery was created with.
     *
     * @return The configuration
     */
    public C getConfiguration() {
        return configuration;
    }

    /**
     * {@inheritDoc}
     *
     * @return The listener, {@link LogDeliveryListener#NOOP} while none is set
     */
    @Override
    public LogDeliveryListener getListener() {
        return listener;
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
     * Maximum number of documents the pending batch holds: a batch reaching it is sent, and loses its oldest
     * documents past it while transmissions are held off after a failure. Defaults to
     * {@link DeliveryOptions#maxPendingDocuments()}; override it for a limit depending on more than that.
     *
     * @return The maximum number of documents a pending batch can hold in memory
     */
    protected int getMaxPendingDocuments() {
        return configuration.getDeliveryOptions().maxPendingDocuments();
    }

    /**
     * Maximum number of documents per request, a bigger batch being split into chunks. Defaults to
     * {@link DeliveryOptions#maxRequestDocuments()}; override it for a limit depending on more than that.
     *
     * @return The maximum number of documents sent together in a single request
     */
    protected int getMaxRequestDocuments() {
        return configuration.getDeliveryOptions().maxRequestDocuments();
    }

    /**
     * Maximum number of consecutive failed attempts for the same documents before they are discarded. Defaults
     * to {@link DeliveryOptions#maxConsecutiveFailures()}; override it for a limit depending on more than that.
     *
     * @return The maximum number of consecutive failed attempts allowed
     */
    protected int getMaxConsecutiveFailures() {
        return configuration.getDeliveryOptions().maxConsecutiveFailures();
    }

    /**
     * Maximum estimated size of the pending batch, which it bounds as {@link #getMaxPendingDocuments()} does by
     * count. Defaults to {@link DeliveryOptions#maxPendingBytes()}; override it for a limit depending on more
     * than that.
     *
     * @return The maximum estimated size a pending batch can reach, in bytes
     */
    protected long getMaxPendingBytes() {
        return configuration.getDeliveryOptions().maxPendingBytes();
    }

    /**
     * Maximum estimated size of a request, a heavier batch being split into chunks. Defaults to
     * {@link DeliveryOptions#maxRequestBytes()}; override it for a limit depending on more than that.
     *
     * @return The maximum estimated size of a single request, in bytes
     */
    protected long getMaxRequestBytes() {
        return configuration.getDeliveryOptions().maxRequestBytes();
    }

    /**
     * Estimates how many bytes the given document takes on the wire, to enforce {@link #getMaxPendingBytes()}
     * and {@link #getMaxRequestBytes()}, and never called while both are unbounded.
     * <p>
     * Defaults to {@link DocumentSizeEstimator#estimate(Map)}, a compact JSON approximation. Override it when
     * the encoding differs enough to matter: an Elasticsearch bulk body adds an action line per document, and a
     * compressed or binary transport is smaller. Expected to be cheap and free of side effects, being called
     * for every document on the way in, and again when a batch is split for transmission.
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
     * @param document The document, or {@code null} to skip this event
     */
    @Override
    public void send(@Nullable Map<String, Object> document) {
        if (document != null) {
            send(singletonList(document));
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param documents The documents, or {@code null}
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
     * Filters out the {@code null} elements of the given list, copying it only if it has any.
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
     * <p>
     * {@link DeliveryHealth.Status#DOWN} once closed, {@link DeliveryHealth.Status#BACKING_OFF} while the retry
     * backoff holds transmissions off after a failure, and otherwise what the last completed transmission attempt
     * left: {@link DeliveryHealth.Status#RETRYING} if it failed, {@link DeliveryHealth.Status#UP} if it succeeded
     * or none was made yet.
     *
     * @return The current health, with the time since documents were last accepted, or since this delivery was
     *         created while none were yet
     */
    @Override
    public DeliveryHealth getHealth() {
        return batcher.health();
    }

    /**
     * {@inheritDoc}
     * <p>
     * The pending size is only estimated while a byte limit is in effect (see {@link #getMaxPendingBytes()}),
     * and {@code 0} otherwise.
     *
     * @return The current metrics
     */
    @Override
    public DeliveryMetrics getMetrics() {
        Batcher.Metrics metrics = batcher.metrics();
        return new DeliveryMetrics(0, metrics.pendingElements(), metrics.pendingBytes(), metrics.transmissionsInFlight(),
                metrics.transmittedElements(), metrics.failedTransmissions(), metrics.droppedElements());
    }

    /**
     * Sends the given documents, called on a virtual thread of its own, concurrently with the other
     * transmissions.
     * <p>
     * The returned future may be completed before this method returns, by a blocking client holding only that
     * thread, or later by an asynchronous one, the result then being handled on a thread of this delivery rather
     * than on the one of the client. Either way it must complete: until it does, the transmission holds one of
     * the {@link DeliveryOptions#maxConcurrentTransmissions()} slots, and once unanswered requests hold them all,
     * the next batch waits for a slot until this delivery closes. Give the client a timeout.
     * <p>
     * It completes with {@link TransmissionResult#success()} when every document was accepted,
     * {@link TransmissionResult#failure(Throwable)} when none was, or
     * {@link TransmissionResult#partialFailure(List, Throwable)} when only some were rejected, a malformed
     * document in a bulk response for instance, so that only those are retried. An exception thrown, a future
     * completed exceptionally or with {@code null} all count as a failure.
     *
     * @param documents The documents to send, as an unmodifiable list
     * @return A future completed with the outcome of the transmission
     */
    protected abstract CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents);

    /**
     * Closes this delivery within {@link DeliveryOptions#closeTimeout()} (see {@link #close(Duration)}, the
     * one to override).
     *
     * @throws Exception If closing fails
     */
    @Override
    public final void close() throws Exception {
        close(closeTimeout());
    }

    /**
     * Flushes the current batch and waits, up to the given timeout, for every pending transmission, that final
     * one included, and for the listener still being notified of documents dropped before: a subclass
     * overriding it calls it first, and releases the resources of {@link #sendBulk(List)} once it returns.
     * <p>
     * What is still pending once that wait is over, the documents of a transmission still in flight included,
     * is reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}, giving a dead-letter sink
     * a last chance: a transmission answering after that may still deliver them.
     * <p>
     * Only the first call closes: a later one, an appender stopped twice for instance, waits for it to be over,
     * within its own timeout, rather than waiting again for the transmissions it gave up on.
     *
     * @param timeout The maximum time to wait, or {@code null} to wait indefinitely
     * @throws Exception Never thrown by this implementation (an interruption while waiting is caught,
     *                    re-flagged on the current thread, and logged instead)
     */
    @Override
    public void close(@Nullable Duration timeout) throws Exception {
        batcher.close(timeout);
    }

    /**
     * Reads how long to wait for in-flight transmissions while closing, falling back to the value this
     * delivery was built with: a failure here would skip the close flushing and reporting the pending batch.
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
     * Waits until every transmission dispatched so far has completed, for the tests to wait for them.
     *
     * @throws InterruptedException If the current thread is interrupted while waiting
     */
    void awaitPendingTransmissions() throws InterruptedException {
        batcher.awaitPendingTransmissions();
    }

}
