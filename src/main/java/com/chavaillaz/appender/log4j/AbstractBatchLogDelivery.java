package com.chavaillaz.appender.log4j;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import com.chavaillaz.appender.LogDeliveryListener;

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
 *     discarded entirely instead of being retried indefinitely. This streak is tracked per pending batch
 *     rather than as a single delivery-wide counter, so that an unrelated batch succeeding concurrently
 *     (see {@link LogConfiguration#getMaxConcurrentTransmissions()}) can never reset it: a poison document
 *     is given up on purely based on how many times it has itself failed to be transmitted, regardless of
 *     how much unrelated traffic keeps succeeding around it at the same time.</li>
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
     * Number of consecutive times the data currently sitting in {@link #batch} has already failed to be
     * transmitted (0 if it holds only documents that have never been attempted yet). Carried forward (and
     * merged conservatively, keeping the higher of the two) whenever failed documents are merged back into
     * the batch by {@link #requeue(List, int)}, and reset to {@code 0} whenever the batch is swapped out
     * for a fresh, empty one by {@link #swapBatch()}, since the returned snapshot then becomes solely
     * responsible for carrying its own streak through {@link #dispatch(List, int)}.
     * <p>
     * Always guarded by the monitor of this instance, like {@link #batch} itself.
     */
    private int pendingFailureStreak;

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
        int concurrentTransmissions = configuration.getMaxConcurrentTransmissions();
        if (concurrentTransmissions < 1) {
            // A value of 0 or less would give the semaphore below no permits to ever hand out (and a
            // negative one makes it even worse), so every dispatch() would block forever waiting for one,
            // permanently wedging the single thread responsible for batching (and, transitively, the
            // appender's background thread submitting work to it) instead of merely lowering throughput.
            throw new IllegalArgumentException(
                    "MaxConcurrentTransmissions must be at least 1, but was " + concurrentTransmissions);
        }
        this.configuration = configuration;
        this.batch = newBatch();
        this.listener = listener != null ? listener : LogDeliveryListener.NOOP;
        this.maxConcurrentTransmissions = concurrentTransmissions;
        this.transmissionPermits = new Semaphore(maxConcurrentTransmissions);
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
    public void send(@Nullable Map<String, Object> document) {
        // A null document is treated as a no-op rather than stacked as-is, be it because of a caller
        // mistake or a LogConverter implementation choosing to signal "skip this event" by returning null.
        if (document != null) {
            send(singletonList(document));
        }
    }

    @Override
    public void send(@Nullable List<@Nullable Map<String, Object>> documents) {
        if (documents != null && !documents.isEmpty()) {
            List<Map<String, Object>> filtered = withoutNullElements(documents);
            if (!filtered.isEmpty()) {
                stackAndSend(filtered);
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

    @Override
    public void flush() {
        PendingBatch snapshot;
        synchronized (this) {
            if (batch.isEmpty()) {
                return;
            }
            log.debug("Sending partial batch of {}/{}", batch.size(), configuration.getFlushThreshold());
            snapshot = swapBatch();
        }
        dispatch(snapshot.documents(), snapshot.failureStreak());
    }

    private void stackAndSend(List<Map<String, Object>> data) {
        PendingBatch snapshot = null;
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
            dispatch(snapshot.documents(), snapshot.failureStreak());
        }
    }

    /**
     * The content of {@link #batch} at the time it was swapped out, together with the number of times that
     * same content had already failed to be transmitted (see {@link #pendingFailureStreak}).
     *
     * @param documents     The content of the batch prior to the swap
     * @param failureStreak The failure streak inherited from {@link #pendingFailureStreak} at the time of
     *                      the swap
     */
    private record PendingBatch(List<Map<String, Object>> documents, int failureStreak) {

    }

    /**
     * Replaces the current batch with a new, fresh (never failed) empty one and returns its previous
     * content together with the failure streak it had accumulated so far, so accumulation of the next
     * batch can start immediately while the returned one is being transmitted.
     * <p>
     * Must be called while holding the monitor of this instance.
     *
     * @return The content of the batch prior to the swap, and its failure streak
     */
    private PendingBatch swapBatch() {
        PendingBatch previous = new PendingBatch(batch, pendingFailureStreak);
        batch = newBatch();
        pendingFailureStreak = 0;
        return previous;
    }

    /**
     * Puts back the documents of a failed (or not attempted) transmission at the front of the current
     * batch, so they are retried before any newer document, discarding the oldest documents in excess if
     * this would grow the batch past {@link #getMaxBatchSize()}.
     *
     * @param documents     The documents to put back
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted (0 if it was never actually attempted, e.g. because acquiring a
     *                      transmission permit was interrupted)
     */
    private void requeue(List<Map<String, Object>> documents, int failureStreak) {
        List<Map<String, Object>> discarded = null;
        int maxBatchSize;
        synchronized (this) {
            List<Map<String, Object>> merged = new ArrayList<>(documents.size() + batch.size());
            merged.addAll(documents);
            merged.addAll(batch);

            maxBatchSize = getMaxBatchSize();
            // Clamped to [0, merged.size()] so that an unexpected value of maxBatchSize (0, negative, or
            // simply overridden by a subclass to return one of those) can never make subList() below throw,
            // instead degrading gracefully to discarding nothing (a size larger than the batch) or
            // everything (a size of 0 or less).
            int overflow = Math.min(merged.size(), Math.max(0, merged.size() - maxBatchSize));
            if (overflow > 0) {
                discarded = List.copyOf(merged.subList(0, overflow));
                merged = new ArrayList<>(merged.subList(overflow, merged.size()));
            }

            batch = merged;
            // The live batch might already carry forward a streak of its own (from a different failed
            // chunk merged in moments earlier by a concurrent transmission); keeping the higher of the two
            // is the conservative choice, since the merged batch is at least as "tainted" as its worst
            // ingredient.
            pendingFailureStreak = Math.max(pendingFailureStreak, failureStreak);
        }
        // Notified outside the lock above: this instance's monitor is also needed by send()/flush() to
        // accumulate the next batch, and a listener that takes a while (writing to a file, a metrics call
        // over the network) must not be able to stall that unrelated, latency-sensitive path.
        if (discarded != null) {
            log.warn("Discarding {} oldest document(s) after exceeding the maximum batch size of {} while retrying",
                    discarded.size(), maxBatchSize);
            listener.onDocumentsDropped(discarded, "maximum batch size exceeded");
        }
    }

    private void dispatch(List<Map<String, Object>> documents, int failureStreak) {
        try {
            transmissionPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requeue(documents, failureStreak);
            return;
        }

        try {
            transmissionExecutor.execute(() -> transmit(documents, failureStreak));
        } catch (RejectedExecutionException e) {
            transmissionPermits.release();
            requeue(documents, failureStreak);
        }
    }

    private void transmit(List<Map<String, Object>> documents, int failureStreak) {
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
                listener.onTransmissionSucceeded(documents.size(), durationMillis);
            } else {
                listener.onTransmissionFailed(documents.size(), failure);
                onTransmissionFailure(documents, failureStreak);
            }
        } finally {
            transmissionPermits.release();
        }
    }

    /**
     * Handles a failed transmission attempt: either discards the given documents once
     * {@link #getMaxConsecutiveFailures()} has been reached, or puts them back for a later retry.
     *
     * @param documents     The documents that failed to be transmitted
     * @param failureStreak The number of consecutive times this exact content had already failed before
     *                      this attempt
     */
    private void onTransmissionFailure(List<Map<String, Object>> documents, int failureStreak) {
        int maxConsecutiveFailures = getMaxConsecutiveFailures();
        int newFailureStreak = failureStreak + 1;
        if (maxConsecutiveFailures > 0 && newFailureStreak >= maxConsecutiveFailures) {
            log.error("Giving up on {} document(s) after {} consecutive failed transmission attempts, they will be discarded",
                    documents.size(), newFailureStreak);
            listener.onDocumentsDropped(List.copyOf(documents), "maximum consecutive transmission failures exceeded");
        } else {
            requeue(documents, newFailureStreak);
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
