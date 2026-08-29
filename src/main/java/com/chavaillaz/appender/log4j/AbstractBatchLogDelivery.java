package com.chavaillaz.appender.log4j;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Abstract implementation of logs transmission by batches.
 * <p>
 * Once a batch is ready to be sent (threshold reached, or {@link #flush()} called), it is handed over to a
 * dedicated virtual thread instead of being sent by the calling thread. This means that a slow or blocked
 * transmission never delays the accumulation of the next batch, only the (fast, in-memory) batching work
 * ever happens on the calling thread. The number of batches transmitted concurrently is bounded (see
 * {@link LogConfiguration#getMaxConcurrentTransmissions()}) to protect the downstream system from being
 * overwhelmed and to cap the number of concurrent outgoing requests; once that limit is reached, the
 * calling thread waits for a transmission to complete before it can hand over the next one (this only ever
 * affects the internal background thread of {@link AbstractLogDeliveryAppender}, never the application
 * thread calling it).
 * <p>
 * When a transmission fails, its documents are put back at the front of the current batch so they are
 * retried later instead of being lost. However, when batching is enabled (a flush threshold greater than
 * {@code 1}), a new transmission is not attempted for every single additional message received while the
 * previous attempt is still failing, as that would hammer an already struggling downstream system with one
 * request per incoming log message. Instead, once batching is enabled, retries only happen on the next
 * call to {@link #flush()}, which the appender using this class calls periodically. Note that under
 * concurrent transmissions, retried documents may end up being redelivered in a different relative order
 * than they were originally received; this is an accepted trade-off given every document already carries
 * its own timestamp.
 * <p>
 * Two safety nets bound how much a repeatedly failing transmission can cost, so that a struggling or
 * unreachable downstream system (or a single document it can never accept, a so-called "poison document")
 * cannot grow memory usage without bound or wedge the pipeline forever:
 * <ul>
 *     <li>The pending batch is never allowed to grow past {@link LogConfiguration#getMaxBatchSize()}: once
 *     exceeded, the oldest documents in excess are discarded.</li>
 *     <li>A batch that fails {@link LogConfiguration#getMaxConsecutiveFailures()} times in a row is
 *     discarded entirely instead of being retried indefinitely.</li>
 * </ul>
 * Both cases are reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)} (in addition
 * to being logged), so this data loss is never silent.
 *
 * @param <C> The configuration type
 */
@Log4j2
public abstract class AbstractBatchLogDelivery<C extends LogConfiguration> implements LogDelivery {

    /**
     * Maximum initial capacity reserved for a new batch, to avoid over-allocating for a large threshold.
     */
    private static final int MAX_INITIAL_BATCH_CAPACITY = 1_024;

    /**
     * The list of logs documents to be sent with the next transmission
     */
    private List<Map<String, Object>> batch;

    /**
     * The configuration of the logs transmission
     */
    @Getter
    private final C configuration;

    /**
     * The listener notified of the outcome of every transmission attempt.
     */
    @Getter
    private LogDeliveryListener listener;

    /**
     * Executor transmitting batches on virtual threads, so that network I/O never blocks the thread
     * accumulating the next batch.
     */
    private final ExecutorService transmissionExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("log-delivery-transmission-", 0).factory());

    /**
     * The maximum number of batches transmitted concurrently, as given by
     * {@link LogConfiguration#getMaxConcurrentTransmissions()} at construction time.
     */
    private final int maxConcurrentTransmissions;

    /**
     * Bounds the number of batches transmitted concurrently to {@link #maxConcurrentTransmissions}.
     */
    private final Semaphore transmissionPermits;

    /**
     * Whether the last completed transmission attempt failed, used to avoid retrying on every single new
     * message received while batching is enabled and the downstream system is unavailable or failing.
     */
    private volatile boolean transmissionFailed;

    /**
     * Number of consecutive transmission attempts that failed, reset to {@code 0} as soon as one succeeds.
     * Used to give up on a batch that can seemingly never be transmitted successfully instead of retrying
     * it forever (see {@link #getMaxConsecutiveFailures()}).
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

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
     * @param listener      The listener to notify of the outcome of every transmission attempt
     */
    protected AbstractBatchLogDelivery(C configuration, LogDeliveryListener listener) {
        this.configuration = configuration;
        this.batch = newBatch();
        this.listener = listener != null ? listener : LogDeliveryListener.NOOP;
        this.maxConcurrentTransmissions = configuration.getMaxConcurrentTransmissions();
        this.transmissionPermits = new Semaphore(maxConcurrentTransmissions);
    }

    /**
     * Sets the listener to notify of the outcome of every transmission attempt.
     *
     * @param listener The listener, or {@code null} to stop notifying any listener
     */
    public void setListener(LogDeliveryListener listener) {
        this.listener = listener != null ? listener : LogDeliveryListener.NOOP;
    }

    /**
     * Gets the maximum number of documents a pending batch can hold while waiting to be (re)transmitted.
     * <p>
     * This bounds the memory used by documents awaiting transmission, and keeps a single bulk request from
     * growing so large that it is more likely to be rejected outright by the downstream system because of
     * its own request size limits (which would only make the underlying issue worse, since the resulting
     * failed batch would be even bigger on the next attempt). Once exceeded, the oldest documents in excess
     * are discarded, favoring keeping the most recently received (and therefore most likely still relevant)
     * documents.
     * <p>
     * Defaults to {@link LogConfiguration#getMaxBatchSize()}; can be overridden here instead if the limit
     * should depend on more than just the configuration.
     *
     * @return The maximum number of documents a pending batch can hold
     */
    protected int getMaxBatchSize() {
        return configuration.getMaxBatchSize();
    }

    /**
     * Gets the maximum number of consecutive failed transmission attempts allowed for the same pending
     * batch before it is discarded instead of being retried again.
     * <p>
     * Without this limit, a single document that can never be accepted by the downstream system (a
     * "poison document", for example one that fails a server-side validation rule) would cause the batch
     * containing it to fail forever, blocking every document accumulated behind it from ever being
     * delivered.
     * <p>
     * Defaults to {@link LogConfiguration#getMaxConsecutiveFailures()}; can be overridden here instead if
     * the limit should depend on more than just the configuration.
     *
     * @return The maximum number of consecutive failed attempts allowed
     */
    protected int getMaxConsecutiveFailures() {
        return configuration.getMaxConsecutiveFailures();
    }

    private List<Map<String, Object>> newBatch() {
        long threshold = configuration.getFlushThreshold();
        int capacity = threshold > 0 ? (int) Math.min(threshold, MAX_INITIAL_BATCH_CAPACITY) : 16;
        return new ArrayList<>(capacity);
    }

    @Override
    public void send(Map<String, Object> document) {
        send(singletonList(document));
    }

    @Override
    public void send(List<Map<String, Object>> documents) {
        if (documents != null && !documents.isEmpty()) {
            stackAndSend(documents);
        }
    }

    @Override
    public void flush() {
        List<Map<String, Object>> snapshot;
        synchronized (this) {
            if (batch.isEmpty()) {
                return;
            }
            log.debug("Sending partial batch of {}/{}", batch.size(), configuration.getFlushThreshold());
            snapshot = swapBatch();
        }
        dispatch(snapshot);
    }

    private void stackAndSend(List<Map<String, Object>> data) {
        List<Map<String, Object>> snapshot = null;
        synchronized (this) {
            batch.addAll(data);
            log.trace("Stacking batch {}/{}", batch.size(), configuration.getFlushThreshold());

            boolean batchingEnabled = configuration.getFlushThreshold() > 1;
            boolean thresholdReached = batch.size() >= configuration.getFlushThreshold();
            if (thresholdReached && !(batchingEnabled && transmissionFailed)) {
                snapshot = swapBatch();
            }
        }
        if (snapshot != null) {
            dispatch(snapshot);
        }
    }

    /**
     * Replaces the current batch with a new empty one and returns its previous content, so accumulation of
     * the next batch can start immediately while the returned one is being transmitted.
     * <p>
     * Must be called while holding the monitor of this instance.
     *
     * @return The content of the batch prior to the swap
     */
    private List<Map<String, Object>> swapBatch() {
        List<Map<String, Object>> previous = batch;
        batch = newBatch();
        return previous;
    }

    /**
     * Puts back the documents of a failed (or not attempted) transmission at the front of the current
     * batch, so they are retried before any newer document, discarding the oldest documents in excess if
     * this would grow the batch past {@link #getMaxBatchSize()}.
     *
     * @param documents The documents to put back
     */
    private synchronized void requeue(List<Map<String, Object>> documents) {
        List<Map<String, Object>> merged = new ArrayList<>(documents.size() + batch.size());
        merged.addAll(documents);
        merged.addAll(batch);

        int maxBatchSize = getMaxBatchSize();
        int overflow = merged.size() - maxBatchSize;
        if (overflow > 0) {
            List<Map<String, Object>> discarded = List.copyOf(merged.subList(0, overflow));
            log.warn("Discarding {} oldest document(s) after exceeding the maximum batch size of {} while retrying",
                    overflow, maxBatchSize);
            listener.onDocumentsDropped(discarded, "maximum batch size exceeded");
            merged = new ArrayList<>(merged.subList(overflow, merged.size()));
        }

        batch = merged;
    }

    private void dispatch(List<Map<String, Object>> documents) {
        try {
            transmissionPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requeue(documents);
            return;
        }

        try {
            transmissionExecutor.execute(() -> transmit(documents));
        } catch (RejectedExecutionException e) {
            transmissionPermits.release();
            requeue(documents);
        }
    }

    private void transmit(List<Map<String, Object>> documents) {
        long start = System.nanoTime();
        try {
            boolean success;
            Exception failure = null;
            try {
                success = sendBulk(documents);
            } catch (Exception e) {
                log.warn("Unhandled exception while sending a bulk of {} documents", documents.size(), e);
                success = false;
                failure = e;
            }

            long durationMillis = (System.nanoTime() - start) / 1_000_000;
            transmissionFailed = !success;
            if (success) {
                consecutiveFailures.set(0);
                listener.onTransmissionSucceeded(documents.size(), durationMillis);
            } else {
                listener.onTransmissionFailed(documents.size(), failure);
                onTransmissionFailure(documents);
            }
        } finally {
            transmissionPermits.release();
        }
    }

    /**
     * Handles a failed transmission attempt: either discards the given documents once
     * {@link #getMaxConsecutiveFailures()} has been reached, or puts them back for a later retry.
     *
     * @param documents The documents that failed to be transmitted
     */
    private void onTransmissionFailure(List<Map<String, Object>> documents) {
        int maxConsecutiveFailures = getMaxConsecutiveFailures();
        int failures = consecutiveFailures.incrementAndGet();
        if (maxConsecutiveFailures > 0 && failures >= maxConsecutiveFailures) {
            consecutiveFailures.set(0);
            log.error("Giving up on {} document(s) after {} consecutive failed transmission attempts, they will be discarded",
                    documents.size(), failures);
            listener.onDocumentsDropped(List.copyOf(documents), "maximum consecutive transmission failures exceeded");
        } else {
            requeue(documents);
        }
    }

    /**
     * Sends the given list of documents representing logs.
     *
     * @param documents The list of record data
     * @return {@code true} if the transmission was successful, {@code false} otherwise
     */
    protected abstract boolean sendBulk(List<Map<String, Object>> documents);

    @Override
    public void close() throws Exception {
        try {
            flush();
            awaitPendingTransmissions();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for pending log transmissions to complete");
        } finally {
            transmissionExecutor.shutdown();
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
        transmissionPermits.acquire(maxConcurrentTransmissions);
        transmissionPermits.release(maxConcurrentTransmissions);
    }

}
