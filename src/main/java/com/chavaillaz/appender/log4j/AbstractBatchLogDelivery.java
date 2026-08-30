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
 * is capped at {@link LogConfiguration#getMaxPendingDocuments()} (oldest documents discarded past that,
 * both while accumulating and while requeuing after a failure), and a batch that fails
 * {@link LogConfiguration#getMaxConsecutiveFailures()} times in a row (tracked per batch, not delivery-wide,
 * so an unrelated batch succeeding concurrently can never reset it) is discarded instead of retried
 * indefinitely. Both are reported through {@link LogDeliveryListener#onDocumentsDropped(List, String)}, so
 * this data loss is never silent.
 * <p>
 * A batch due for (re)transmission bigger than {@link LogConfiguration#getMaxRequestDocuments()} is first
 * split into chunks of at most that size (see {@link #dispatch(List, int)}), each dispatched, retried and
 * (subject to {@link LogConfiguration#getMaxConcurrentTransmissions()}) transmitted independently. Disabled
 * by default; only matters for a downstream system with its own limit on documents or payload size per
 * request.
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
     * Number of consecutive times the data currently in {@link #batch} has already failed to be transmitted
     * (0 if never attempted). Carried forward (keeping the higher of the two when merged) by
     * {@link #requeue(List, int)}, and reset to {@code 0} when {@link #swapBatch()} swaps in a fresh batch,
     * since the returned snapshot then owns its own streak through {@link #dispatch(List, int)}.
     * <p>
     * Always guarded by the monitor of this instance, like {@link #batch} itself.
     */
    private int pendingFailureStreak;

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
            // A value of 0 or less would give the semaphore below no permits to ever hand out, so every
            // dispatch() would block forever instead of merely lowering throughput.
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
     * chunks of at most this size before dispatch (see {@link #dispatch(List, int)}). Defaults to
     * {@link LogConfiguration#getMaxRequestDocuments()} (unbounded, no chunking); override if the limit
     * should depend on more than just the configuration.
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

    private List<Map<String, Object>> newBatch() {
        long threshold = configuration.getFlushThreshold();
        int capacity = threshold > 0 ? (int) Math.min(threshold, MAX_INITIAL_BATCH_CAPACITY) : 16;
        return new ArrayList<>(capacity);
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

    /**
     * {@inheritDoc}
     */
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
        List<Map<String, Object>> discarded;
        int maxPendingDocuments;
        synchronized (this) {
            batch.addAll(data);
            log.trace("Stacking batch {}/{}", batch.size(), configuration.getFlushThreshold());

            // Enforces the same cap as requeue() here too, so MaxPendingDocuments bounds memory even when
            // FlushThreshold is set higher (or effectively unbounded) and nothing is actually failing.
            maxPendingDocuments = getMaxPendingDocuments();
            Trimmed trimmed = trimToMaxPendingDocuments(batch, maxPendingDocuments);
            batch = trimmed.kept();
            discarded = trimmed.discarded();

            boolean batchingEnabled = configuration.getFlushThreshold() > 1;
            boolean thresholdReached = batch.size() >= configuration.getFlushThreshold();
            if (thresholdReached && !(batchingEnabled && transmissionFailed)) {
                snapshot = swapBatch();
            }
        }
        // Notified asynchronously (see notifyDropped()) since this runs directly on whatever thread called
        // send() (in practice, the appender's single background worker thread): a slow listener must not
        // stall it.
        if (discarded != null) {
            log.warn("Discarding {} oldest document(s) after exceeding the maximum pending documents of {} while accumulating",
                    discarded.size(), maxPendingDocuments);
            notifyDropped(discarded, "maximum pending documents exceeded");
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
     * content together with the failure streak it had accumulated so far, so accumulation of the next batch
     * can start immediately while the returned one is being transmitted.
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
     * The result of {@link #trimToMaxPendingDocuments(List, int)}.
     *
     * @param kept      The given documents, with the oldest ones in excess of the cap removed (the same
     *                  list instance as given if nothing was discarded)
     * @param discarded The documents discarded to honor the cap, or {@code null} if none were
     */
    private record Trimmed(List<Map<String, Object>> kept, @Nullable List<Map<String, Object>> discarded) {

    }

    /**
     * Trims the given list (oldest first) down to at most {@code maxPendingDocuments} elements, discarding
     * the oldest ones in excess so the most recently received documents are kept. Shared by
     * {@link #stackAndSend(List)} and {@link #requeue(List, int)} so the pending batch can never grow past
     * this cap regardless of which path pushed it over.
     *
     * @param documents           The documents to trim, oldest first
     * @param maxPendingDocuments The maximum to keep, clamped to {@code [0, documents.size()]} so an
     *                            unexpected value (zero, negative, or an overridden getter returning one)
     *                            degrades gracefully instead of making {@code subList()} throw
     * @return The trimmed list and whatever was discarded, if anything
     */
    private static Trimmed trimToMaxPendingDocuments(List<Map<String, Object>> documents, int maxPendingDocuments) {
        int overflow = Math.min(documents.size(), Math.max(0, documents.size() - maxPendingDocuments));
        if (overflow == 0) {
            return new Trimmed(documents, null);
        }
        List<Map<String, Object>> discarded = List.copyOf(documents.subList(0, overflow));
        List<Map<String, Object>> kept = new ArrayList<>(documents.subList(overflow, documents.size()));
        return new Trimmed(kept, discarded);
    }

    /**
     * Puts back the documents of a failed (or not attempted) transmission at the front of the current
     * batch, so they are retried before any newer document, discarding the oldest documents in excess if
     * this would grow the batch past {@link #getMaxPendingDocuments()}.
     *
     * @param documents     The documents to put back
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted (0 if it was never actually attempted, e.g. because acquiring a
     *                      transmission permit was interrupted)
     */
    private void requeue(List<Map<String, Object>> documents, int failureStreak) {
        List<Map<String, Object>> discarded;
        int maxPendingDocuments;
        synchronized (this) {
            List<Map<String, Object>> merged = new ArrayList<>(documents.size() + batch.size());
            merged.addAll(documents);
            merged.addAll(batch);

            maxPendingDocuments = getMaxPendingDocuments();
            Trimmed trimmed = trimToMaxPendingDocuments(merged, maxPendingDocuments);
            batch = trimmed.kept();
            discarded = trimmed.discarded();

            // The live batch might already carry forward a streak of its own (from a different failed
            // chunk merged in moments earlier by a concurrent transmission); keeping the higher of the two
            // is the conservative choice, since the merged batch is at least as "tainted" as its worst
            // ingredient.
            pendingFailureStreak = Math.max(pendingFailureStreak, failureStreak);
        }
        // Notified outside the lock: it's also needed to accumulate the next batch, and a slow listener
        // must not stall that. Called directly (not via notifyDropped()) since this is reached either from
        // onTransmissionFailure() (already on transmissionExecutor's own thread) or, more rarely, from a
        // failed dispatch handoff — never from the application-facing caller of send()/flush().
        if (discarded != null) {
            log.warn("Discarding {} oldest document(s) after exceeding the maximum pending documents of {} while retrying",
                    discarded.size(), maxPendingDocuments);
            notifyListener(() -> listener.onDocumentsDropped(discarded, "maximum pending documents exceeded"));
        }
    }

    /**
     * Notifies {@link #listener} of dropped documents on a fresh virtual thread rather than the calling
     * one, so a slow listener (writing to a file, a network call) can never stall it. Used only by
     * {@link #stackAndSend(List)}, reached directly from the caller of {@code send()} (in practice, the
     * appender's single background worker thread); every other discard in this class is already off that
     * path and notified directly instead.
     * <p>
     * Best-effort: not tracked by {@link #transmissionPermits}, so {@link #awaitPendingTransmissions()} (and
     * transitively {@link #close()}) does not wait for it; lost entirely if the executor is already
     * shutting down.
     *
     * @param documents The documents being discarded
     * @param reason    A short, human-readable explanation of why they were discarded
     */
    private void notifyDropped(List<Map<String, Object>> documents, String reason) {
        try {
            transmissionExecutor.execute(() -> notifyListener(() -> listener.onDocumentsDropped(documents, reason)));
        } catch (RejectedExecutionException e) {
            log.warn("Unable to notify the listener of {} dropped document(s), the delivery is shutting down",
                    documents.size());
        }
    }

    /**
     * Dispatches the given documents for transmission, splitting them into consecutive chunks of at most
     * {@link #getMaxRequestDocuments()} first if needed, each chunk then handed to
     * {@link #dispatchChunk(List, int)} independently (see the class documentation for what that means for
     * concurrency and retries).
     *
     * @param documents     The documents to dispatch, possibly more than one request's worth
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted, inherited by every chunk split off from it
     */
    private void dispatch(List<Map<String, Object>> documents, int failureStreak) {
        int maxRequestDocuments = getMaxRequestDocuments();
        if (maxRequestDocuments > 0 && documents.size() > maxRequestDocuments) {
            for (List<Map<String, Object>> chunk : chunk(documents, maxRequestDocuments)) {
                dispatchChunk(chunk, failureStreak);
            }
        } else {
            dispatchChunk(documents, failureStreak);
        }
    }

    /**
     * Splits the given list into consecutive, independent chunks of at most {@code chunkSize} elements each
     * (the last one possibly smaller), preserving order.
     *
     * @param documents The documents to split
     * @param chunkSize The maximum size of each chunk, guaranteed positive by the only caller
     * @return The list of chunks, in original order
     */
    private static List<List<Map<String, Object>>> chunk(List<Map<String, Object>> documents, int chunkSize) {
        List<List<Map<String, Object>>> chunks = new ArrayList<>((documents.size() + chunkSize - 1) / chunkSize);
        for (int i = 0; i < documents.size(); i += chunkSize) {
            chunks.add(List.copyOf(documents.subList(i, Math.min(i + chunkSize, documents.size()))));
        }
        return chunks;
    }

    /**
     * Dispatches a single chunk (at most {@link #getMaxRequestDocuments()} documents) for transmission on
     * its own virtual thread, bounded by {@link #transmissionPermits} like any other request. Acquiring a
     * permit may block the caller (see the class documentation on
     * {@link LogConfiguration#getMaxConcurrentTransmissions()} for why that is an accepted trade-off), but
     * the actual transmission never does.
     *
     * @param documents     The chunk to dispatch
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted
     */
    private void dispatchChunk(List<Map<String, Object>> documents, int failureStreak) {
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
                notifyListener(() -> listener.onTransmissionSucceeded(documents.size(), durationMillis));
            } else {
                Exception cause = failure;
                notifyListener(() -> listener.onTransmissionFailed(documents.size(), cause));
                // Runs regardless of whether the notification above threw: a misbehaving listener must
                // never skip the retry/give-up handling below, or the failed documents would be lost
                // silently.
                onTransmissionFailure(documents, failureStreak);
            }
        } finally {
            transmissionPermits.release();
        }
    }

    /**
     * Invokes the given listener notification, logging and swallowing any exception a misbehaving
     * {@link LogDeliveryListener} might throw. Matters most before {@link #onTransmissionFailure(List, int)}
     * in {@link #transmit(List, int)}: without this guard, a throwing {@code onTransmissionFailed} would
     * skip that call, losing the failed documents without requeuing or reporting them dropped.
     *
     * @param notification The listener call to perform
     */
    private void notifyListener(Runnable notification) {
        try {
            notification.run();
        } catch (Exception e) {
            log.warn("Unhandled exception thrown by a LogDeliveryListener", e);
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
            // Called directly (not via notifyDropped()): already running on transmissionExecutor's own
            // thread, and keeping this under the same permit transmit() still holds lets
            // awaitPendingTransmissions() observe it, same as the transmission itself.
            notifyListener(() -> listener.onDocumentsDropped(List.copyOf(documents), "maximum consecutive transmission failures exceeded"));
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

    /**
     * Flushes the current batch and waits for every pending transmission (including that final one) to
     * complete, so a subclass can safely release resources used by {@link #sendBulk(List)} right after this
     * call returns without racing an in-flight transmission.
     *
     * @throws Exception Never thrown by this implementation (an interruption while waiting is caught,
     *                    re-flagged on the current thread, and logged instead)
     */
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
