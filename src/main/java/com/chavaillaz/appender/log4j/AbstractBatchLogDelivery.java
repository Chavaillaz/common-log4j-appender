package com.chavaillaz.appender.log4j;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.BackoffPolicy;
import com.chavaillaz.appender.Batcher;
import com.chavaillaz.appender.BatcherListener;
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
 * {@link LogConfiguration#getMaxConcurrentTransmissions()}: beyond that limit, the calling thread (in
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
 * is capped at {@link LogConfiguration#getMaxPendingDocuments()} via the shared {@link com.chavaillaz.appender.BoundedTrim}
 * policy (oldest documents discarded past that, both while accumulating and while requeuing after a
 * failure, the same policy {@link AbstractLogDeliveryAppender}'s own pending event queue applies), and a
 * batch that fails {@link LogConfiguration#getMaxConsecutiveFailures()} times in a row (tracked per batch,
 * not delivery-wide, so an unrelated batch succeeding concurrently can never reset it) is discarded instead
 * of retried indefinitely. Both are reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)},
 * so this data loss is never silent.
 * <p>
 * A batch due for (re)transmission bigger than {@link LogConfiguration#getMaxRequestDocuments()} is first
 * split into chunks of at most that size, each dispatched, retried and (subject to
 * {@link LogConfiguration#getMaxConcurrentTransmissions()}) transmitted independently. Disabled by default;
 * only matters for a downstream system with its own limit on documents or payload size per request.
 * <p>
 * The generic accumulate/backpressure/retry machinery itself lives in {@link Batcher}; this class is a thin
 * adapter wiring it to {@link LogConfiguration}, {@link LogDelivery} and {@link LogDeliveryListener}.
 *
 * @param <C> The configuration type
 */
@Log4j2
public abstract class AbstractBatchLogDelivery<C extends LogConfiguration> implements LogDelivery {

    @Getter
    private final C configuration;

    /**
     * The listener notified of the outcome of every transmission attempt.
     */
    @Getter
    private LogDeliveryListener listener;

    private final Batcher<Map<String, Object>> batcher;

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
        warnAboutDegradedConfiguration(configuration);
        this.listener = listener != null ? listener : LogDeliveryListener.NOOP;
        this.batcher = Batcher.<Map<String, Object>>builder()
                .flushThreshold(configuration::getFlushThreshold)
                .maxPendingElements(this::getMaxPendingDocuments)
                .maxRequestElements(this::getMaxRequestDocuments)
                .maxConsecutiveFailures(this::getMaxConsecutiveFailures)
                .maxConcurrentTransmissions(configuration.getMaxConcurrentTransmissions())
                .backoff(BackoffPolicy.exponential(configuration.getRetryBackoff(), configuration.getMaxRetryBackoff()))
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
     * Warns about configuration values that degrade this delivery's functionality instead of leaving it
     * fully working, so a misconfiguration is visible at startup instead of only showing up later as
     * unexplained data loss or runaway retries. Unlike {@code MaxConcurrentTransmissions} below {@code 1}
     * (validated by {@link Batcher}'s constructor below, which throws rather than warns), none of these
     * leave the pipeline stuck: a request never blocks waiting for one, so they are deliberately still
     * allowed, only reported.
     *
     * @param configuration The configuration to inspect
     */
    private void warnAboutDegradedConfiguration(C configuration) {
        int maxPendingDocuments = configuration.getMaxPendingDocuments();
        if (maxPendingDocuments <= 0) {
            log.warn("MaxPendingDocuments is {} (0 or less), every document will be discarded immediately, "
                    + "effectively disabling this delivery", maxPendingDocuments);
        }

        int maxRequestDocuments = configuration.getMaxRequestDocuments();
        if (maxRequestDocuments <= 0) {
            log.warn("MaxRequestDocuments is {} (0 or less), request chunking is disabled", maxRequestDocuments);
        }

        int maxConsecutiveFailures = configuration.getMaxConsecutiveFailures();
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
     * Defaults to {@link LogConfiguration#getMaxPendingDocuments()}; override if the limit should depend on
     * more than just the configuration.
     *
     * @return The maximum number of documents a pending batch can hold in memory
     */
    protected int getMaxPendingDocuments() {
        return configuration.getMaxPendingDocuments();
    }

    /**
     * Maximum number of documents sent together in a single request; a bigger pending batch is split into
     * chunks of at most this size before dispatch. Defaults to {@link LogConfiguration#getMaxRequestDocuments()}
     * (unbounded, no chunking); override if the limit should depend on more than just the configuration.
     *
     * @return The maximum number of documents sent together in a single request
     */
    protected int getMaxRequestDocuments() {
        return configuration.getMaxRequestDocuments();
    }

    /**
     * Maximum number of consecutive failed transmission attempts allowed for the same pending batch before
     * it is discarded instead of retried again, so a single document the downstream system can never accept
     * (a "poison document") cannot block everything behind it forever. Defaults to
     * {@link LogConfiguration#getMaxConsecutiveFailures()}; override if the limit should depend on more than
     * just the configuration.
     *
     * @return The maximum number of consecutive failed attempts allowed
     */
    protected int getMaxConsecutiveFailures() {
        return configuration.getMaxConsecutiveFailures();
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
     * alongside them, are retried or counted toward {@link LogConfiguration#getMaxConsecutiveFailures()}. A
     * synchronously thrown exception, a future completed exceptionally, or a future completed with
     * {@code null} are all treated the same as {@link TransmissionResult#failure(Throwable)}.
     *
     * @param documents The list of record data
     * @return A future completed with the outcome of the transmission
     */
    protected abstract CompletableFuture<TransmissionResult<Map<String, Object>>> sendBulk(List<Map<String, Object>> documents);

    /**
     * Flushes the current batch and waits, up to {@link LogConfiguration#getCloseTimeout()}, for every
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
        batcher.close(configuration.getCloseTimeout());
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
