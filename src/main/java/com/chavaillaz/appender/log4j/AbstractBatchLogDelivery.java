package com.chavaillaz.appender.log4j;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Abstract implementation of logs transmission by batches.
 * <p>
 * Once a batch is ready to be sent (threshold reached, or {@link #flush()} called), it is handed over to a
 * dedicated virtual thread instead of being sent by the calling thread. This means that a slow or blocked
 * transmission never delays the accumulation of the next batch, only the (fast, in-memory) batching work
 * ever happens on the calling thread. The number of batches transmitted concurrently is bounded (see
 * {@link #MAX_CONCURRENT_TRANSMISSIONS}) to protect the downstream system from being overwhelmed and to
 * cap the number of concurrent outgoing requests; once that limit is reached, the calling thread waits for
 * a transmission to complete before it can hand over the next one (this only ever affects the internal
 * background thread of {@link AbstractLogDeliveryAppender}, never the application thread calling it).
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
 *
 * @param <C> The configuration type
 */
@Log4j2
public abstract class AbstractBatchLogDelivery<C extends LogConfiguration> implements LogDelivery {

    /**
     * Maximum number of batches that can be transmitted concurrently.
     */
    private static final int MAX_CONCURRENT_TRANSMISSIONS = 4;

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
     * Executor transmitting batches on virtual threads, so that network I/O never blocks the thread
     * accumulating the next batch.
     */
    private final ExecutorService transmissionExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("log-delivery-transmission-", 0).factory());

    /**
     * Bounds the number of batches transmitted concurrently to {@link #MAX_CONCURRENT_TRANSMISSIONS}.
     */
    private final Semaphore transmissionPermits = new Semaphore(MAX_CONCURRENT_TRANSMISSIONS);

    /**
     * Whether the last completed transmission attempt failed, used to avoid retrying on every single new
     * message received while batching is enabled and the downstream system is unavailable or failing.
     */
    private volatile boolean transmissionFailed;

    /**
     * Creates a new logs delivery handler sending them by batches.
     *
     * @param configuration The configuration to use
     */
    protected AbstractBatchLogDelivery(C configuration) {
        this.configuration = configuration;
        this.batch = newBatch();
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
     * batch, so they are retried before any newer document.
     *
     * @param documents The documents to put back
     */
    private synchronized void requeue(List<Map<String, Object>> documents) {
        List<Map<String, Object>> merged = new ArrayList<>(documents.size() + batch.size());
        merged.addAll(documents);
        merged.addAll(batch);
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
        try {
            boolean success;
            try {
                success = sendBulk(documents);
            } catch (Exception e) {
                log.warn("Unhandled exception while sending a bulk of {} documents", documents.size(), e);
                success = false;
            }
            transmissionFailed = !success;
            if (!success) {
                requeue(documents);
            }
        } finally {
            transmissionPermits.release();
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
        transmissionPermits.acquire(MAX_CONCURRENT_TRANSMISSIONS);
        transmissionPermits.release(MAX_CONCURRENT_TRANSMISSIONS);
    }

}
