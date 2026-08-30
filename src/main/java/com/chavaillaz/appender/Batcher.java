package com.chavaillaz.appender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;

/**
 * Generic accumulate-batch-transmit-retry engine: elements are stacked until a threshold is reached (or
 * {@link #flush()} is called), then handed to a transport function, retried on failure and eventually
 * discarded past configurable safety limits. Deliberately has no notion of Log4j2, logging events, or a
 * document shape (unlike {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery}, which wires this
 * engine to a {@code Map<String,Object>} document type and a {@link LogDeliveryListener}), so it can be
 * tested, and reused, entirely on its own.
 * <p>
 * A batch ready to be sent is handed off to a virtual thread instead of being sent by the calling thread,
 * so a slow or blocked transmission never delays accumulation of the next batch. Concurrent transmissions
 * are bounded by {@code maxConcurrentTransmissions}: beyond that limit, the calling thread waits for one to
 * complete before handing over the next.
 * <p>
 * A failed transmission's elements are put back at the front of the batch for a later retry instead of
 * being lost. While batching is enabled (a flush threshold greater than {@code 1}) and the last attempt is
 * still failing, a new one is not attempted for every incoming element; retries wait for the next
 * {@link #flush()} instead.
 * <p>
 * Two safety nets bound the cost of a struggling transport, or of a single element it can never accept, so
 * neither can grow memory without bound or wedge the pipeline forever: the pending batch is capped at
 * {@code maxPendingElements} via {@link BoundedTrim} (oldest elements discarded past that, both while
 * accumulating and while requeuing after a failure), and a batch that fails {@code maxConsecutiveFailures}
 * times in a row (tracked per batch, not engine-wide, so an unrelated batch succeeding concurrently can
 * never reset it) is discarded instead of retried indefinitely. Both are reported through
 * {@link BatcherListener#onDropped(List, String)}, so this data loss is never silent.
 * <p>
 * A batch due for (re)transmission bigger than {@code maxRequestElements} is first split into chunks of at
 * most that size, each dispatched, retried and (subject to {@code maxConcurrentTransmissions}) transmitted
 * independently.
 * <p>
 * The transport function returns a {@code CompletableFuture<TransmissionResult<T>>} rather than blocking:
 * it may complete it synchronously, or kick off asynchronous I/O and complete it later from another thread,
 * without tying up one of the (bounded) virtual threads this engine dispatches on for the whole duration of
 * the transmission. A {@link TransmissionResult.PartialFailure} lets it report that only some elements of a
 * chunk were rejected; only those are requeued for retry and counted toward
 * {@code maxConsecutiveFailures}, instead of the whole chunk being retried (or given up on) over a single
 * bad element.
 *
 * @param <T> The type of element accumulated and transmitted
 */
@Log4j2
public class Batcher<T> {

    /**
     * Maximum initial capacity reserved for a new batch, to avoid over-allocating for a large threshold.
     */
    private static final int MAX_INITIAL_BATCH_CAPACITY = 1_024;

    private final LongSupplier flushThreshold;
    private final IntSupplier maxPendingElements;
    private final IntSupplier maxRequestElements;
    private final IntSupplier maxConsecutiveFailures;
    private final String elementNoun;
    private final Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport;
    private final BatcherListener<T> listener;

    /**
     * The elements to be sent with the next transmission
     */
    private List<T> batch;

    /**
     * Number of consecutive times the data currently in {@link #batch} has already failed to be transmitted
     * (0 if never attempted). Carried forward (keeping the higher of the two when merged) by
     * {@link #requeue(List, int)}, and reset to {@code 0} when {@link #swapBatch()} swaps in a fresh batch,
     * since the returned snapshot then owns its own streak through {@link #dispatch(List, int)}.
     * <p>
     * Always guarded by the monitor of this instance, like {@link #batch} itself.
     */
    private int pendingFailureStreak;

    /**
     * Executor transmitting batches on virtual threads, so that transmission never blocks the thread
     * accumulating the next batch.
     */
    private final ExecutorService transmissionExecutor;

    /**
     * The maximum number of batches transmitted concurrently, fixed at construction time (unlike the other
     * limits above, which are re-read on every call to allow a dynamic override).
     */
    private final int maxConcurrentTransmissions;

    /**
     * Bounds the number of batches transmitted concurrently to {@link #maxConcurrentTransmissions}.
     */
    private final Semaphore transmissionPermits;

    /**
     * Whether the last completed transmission attempt failed, used to avoid retrying on every single new
     * element received while batching is enabled and the transport is unavailable or failing. Also exposed
     * through {@link #isTransmissionFailing()} as a simple health signal.
     */
    private volatile boolean transmissionFailed;

    /**
     * Creates a new batching engine.
     *
     * @param flushThreshold             The number of accumulated elements that triggers a transmission,
     *                                    re-read on every call
     * @param maxPendingElements          The maximum number of elements a pending batch can hold in memory,
     *                                    re-read on every call
     * @param maxRequestElements          The maximum number of elements sent together in a single request; a
     *                                    non-positive value disables chunking, re-read on every call
     * @param maxConsecutiveFailures     The maximum number of consecutive failed attempts allowed for the
     *                                    same pending batch before it is discarded; a non-positive value
     *                                    disables this limit, re-read on every call
     * @param maxConcurrentTransmissions The maximum number of batches transmitted concurrently, fixed at
     *                                    construction time; must be at least {@code 1}, or every dispatch
     *                                    would block forever waiting for a permit that never opens
     * @param transmissionThreadPrefix   The name prefix for threads created to run transmissions
     * @param elementNoun                Singular noun describing one element (e.g. {@code "document"}), used
     *                                    only to phrase log messages and the reason string reported to
     *                                    {@code listener} on a discard
     * @param transport                  The transport function, called with a batch (or chunk) to transmit;
     *                                    may complete the returned future synchronously or asynchronously,
     *                                    and is not expected to block the calling thread either way
     * @param listener                   The listener notified of the outcome of every transmission attempt
     */
    public Batcher(LongSupplier flushThreshold, IntSupplier maxPendingElements, IntSupplier maxRequestElements,
            IntSupplier maxConsecutiveFailures, int maxConcurrentTransmissions, String transmissionThreadPrefix,
            String elementNoun, Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport,
            BatcherListener<T> listener) {
        if (maxConcurrentTransmissions < 1) {
            // A value of 0 or less would give the semaphore below no permits to ever hand out, so every
            // dispatch() would block forever instead of merely lowering throughput.
            throw new IllegalArgumentException(
                    "maxConcurrentTransmissions must be at least 1, but was " + maxConcurrentTransmissions);
        }
        this.flushThreshold = flushThreshold;
        this.maxPendingElements = maxPendingElements;
        this.maxRequestElements = maxRequestElements;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.maxConcurrentTransmissions = maxConcurrentTransmissions;
        this.elementNoun = elementNoun;
        this.transport = transport;
        this.listener = listener;
        this.batch = newBatch();
        this.transmissionPermits = new Semaphore(maxConcurrentTransmissions);
        this.transmissionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(transmissionThreadPrefix, 0).factory());
    }

    private List<T> newBatch() {
        long threshold = flushThreshold.getAsLong();
        int capacity = threshold > 0 ? (int) Math.min(threshold, MAX_INITIAL_BATCH_CAPACITY) : 16;
        return new ArrayList<>(capacity);
    }

    /**
     * Accumulates the given elements, triggering a transmission if this reaches {@code flushThreshold}.
     * Never blocks the caller on the network: a transmission this triggers runs on its own virtual thread.
     *
     * @param elements The elements to accumulate, must not be empty
     */
    public void add(List<T> elements) {
        PendingBatch snapshot = null;
        List<T> discarded;
        int maxPending;
        synchronized (this) {
            batch.addAll(elements);
            log.trace("Stacking batch {}/{}", batch.size(), flushThreshold.getAsLong());

            // Enforces the same cap as requeue() here too, so maxPendingElements bounds memory even when
            // flushThreshold is set higher (or effectively unbounded) and nothing is actually failing.
            maxPending = maxPendingElements.getAsInt();
            BoundedTrim.Trimmed<T> trimmed = BoundedTrim.trim(batch, maxPending);
            batch = trimmed.kept();
            discarded = trimmed.discarded();

            boolean batchingEnabled = flushThreshold.getAsLong() > 1;
            boolean thresholdReached = batch.size() >= flushThreshold.getAsLong();
            if (thresholdReached && !(batchingEnabled && transmissionFailed)) {
                snapshot = swapBatch();
            }
        }
        // Notified asynchronously (see notifyDropped()) since this runs directly on whatever thread called
        // add(): a slow listener must not stall it.
        if (discarded != null) {
            log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending {}s of {} while accumulating",
                    discarded.size(), elementNoun, elementNoun, maxPending);
            notifyDropped(discarded, "maximum pending " + elementNoun + "s exceeded");
        }
        if (snapshot != null) {
            dispatch(snapshot.elements(), snapshot.failureStreak());
        }
    }

    /**
     * Whether the most recently completed transmission attempt failed (see {@link #transmissionFailed}),
     * exposed as a simple health signal a caller can poll directly (e.g. for a readiness/liveness endpoint)
     * instead of only reacting to {@link BatcherListener} callbacks.
     *
     * @return {@code true} if the last completed transmission attempt failed and its elements are currently
     *         being retried, {@code false} if it succeeded or none was attempted yet
     */
    public boolean isTransmissionFailing() {
        return transmissionFailed;
    }

    /**
     * Sends the pending elements (a partially filled batch). Intended to be called periodically to clean
     * out pending elements.
     */
    public void flush() {
        PendingBatch snapshot;
        synchronized (this) {
            if (batch.isEmpty()) {
                return;
            }
            log.debug("Sending partial batch of {}/{}", batch.size(), flushThreshold.getAsLong());
            snapshot = swapBatch();
        }
        dispatch(snapshot.elements(), snapshot.failureStreak());
    }

    /**
     * The content of {@link #batch} at the time it was swapped out, together with the number of times that
     * same content had already failed to be transmitted (see {@link #pendingFailureStreak}).
     *
     * @param elements      The content of the batch prior to the swap
     * @param failureStreak The failure streak inherited from {@link #pendingFailureStreak} at the time of
     *                      the swap
     */
    private record PendingBatch<T>(List<T> elements, int failureStreak) {

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
    private PendingBatch<T> swapBatch() {
        PendingBatch<T> previous = new PendingBatch<>(batch, pendingFailureStreak);
        batch = newBatch();
        pendingFailureStreak = 0;
        return previous;
    }

    /**
     * Puts back the elements of a failed (or not attempted) transmission at the front of the current batch,
     * so they are retried before any newer element, discarding the oldest elements in excess if this would
     * grow the batch past {@code maxPendingElements}.
     *
     * @param elements      The elements to put back
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted (0 if it was never actually attempted, e.g. because acquiring a
     *                      transmission permit was interrupted)
     */
    private void requeue(List<T> elements, int failureStreak) {
        List<T> discarded;
        int maxPending;
        synchronized (this) {
            List<T> merged = new ArrayList<>(elements.size() + batch.size());
            merged.addAll(elements);
            merged.addAll(batch);

            maxPending = maxPendingElements.getAsInt();
            BoundedTrim.Trimmed<T> trimmed = BoundedTrim.trim(merged, maxPending);
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
        // failed dispatch handoff — never from the caller-facing add()/flush().
        if (discarded != null) {
            log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending {}s of {} while retrying",
                    discarded.size(), elementNoun, elementNoun, maxPending);
            notifyListener(() -> listener.onDropped(discarded, "maximum pending " + elementNoun + "s exceeded"));
        }
    }

    /**
     * Notifies {@link #listener} of dropped elements on a fresh virtual thread rather than the calling one,
     * so a slow listener (writing to a file, a network call) can never stall it. Used only by {@link #add(List)},
     * reached directly from the caller; every other discard in this class is already off that path and
     * notified directly instead.
     * <p>
     * Best-effort: not tracked by {@link #transmissionPermits}, so {@link #awaitPendingTransmissions()} (and
     * transitively {@link #close()}) does not wait for it; lost entirely if the executor is already
     * shutting down.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    private void notifyDropped(List<T> elements, String reason) {
        try {
            transmissionExecutor.execute(() -> notifyListener(() -> listener.onDropped(elements, reason)));
        } catch (RejectedExecutionException e) {
            log.warn("Unable to notify the listener of {} dropped {}(s), the engine is shutting down", elements.size(), elementNoun);
        }
    }

    /**
     * Dispatches the given elements for transmission, splitting them into consecutive chunks of at most
     * {@code maxRequestElements} first if needed, each chunk then handed to {@link #dispatchChunk(List, int)}
     * independently (see the class documentation for what that means for concurrency and retries).
     *
     * @param elements      The elements to dispatch, possibly more than one request's worth
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted, inherited by every chunk split off from it
     */
    private void dispatch(List<T> elements, int failureStreak) {
        int maxRequest = maxRequestElements.getAsInt();
        if (maxRequest > 0 && elements.size() > maxRequest) {
            for (List<T> chunk : chunk(elements, maxRequest)) {
                dispatchChunk(chunk, failureStreak);
            }
        } else {
            dispatchChunk(elements, failureStreak);
        }
    }

    /**
     * Splits the given list into consecutive, independent chunks of at most {@code chunkSize} elements each
     * (the last one possibly smaller), preserving order.
     *
     * @param elements  The elements to split
     * @param chunkSize The maximum size of each chunk, guaranteed positive by the only caller
     * @return The list of chunks, in original order
     */
    private static <T> List<List<T>> chunk(List<T> elements, int chunkSize) {
        List<List<T>> chunks = new ArrayList<>((elements.size() + chunkSize - 1) / chunkSize);
        for (int i = 0; i < elements.size(); i += chunkSize) {
            chunks.add(List.copyOf(elements.subList(i, Math.min(i + chunkSize, elements.size()))));
        }
        return chunks;
    }

    /**
     * Dispatches a single chunk (at most {@code maxRequestElements}) for transmission on its own virtual
     * thread, bounded by {@link #transmissionPermits} like any other request. Acquiring a permit may block
     * the caller (see the class documentation on {@code maxConcurrentTransmissions} for why that is an
     * accepted trade-off), but the actual transmission never does.
     *
     * @param elements      The chunk to dispatch
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted
     */
    private void dispatchChunk(List<T> elements, int failureStreak) {
        try {
            transmissionPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requeue(elements, failureStreak);
            return;
        }

        try {
            transmissionExecutor.execute(() -> transmit(elements, failureStreak));
        } catch (RejectedExecutionException e) {
            transmissionPermits.release();
            requeue(elements, failureStreak);
        }
    }

    /**
     * Calls the transport function and, once its returned future completes (synchronously or not — see the
     * class documentation), handles the result. Never blocks the calling (virtual) thread on the actual
     * transmission: only the handoff to the transport function itself runs here, the rest happens in
     * whatever thread completes the future.
     *
     * @param elements      The chunk to transmit
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted
     */
    private void transmit(List<T> elements, int failureStreak) {
        long start = System.nanoTime();
        CompletableFuture<TransmissionResult<T>> future;
        try {
            CompletableFuture<TransmissionResult<T>> applied = transport.apply(elements);
            future = applied != null ? applied : CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((result, throwable) -> {
            try {
                long durationMillis = (System.nanoTime() - start) / 1_000_000;
                handleResult(elements, failureStreak, normalizeResult(elements, result, throwable), durationMillis);
            } catch (Exception e) {
                // handleResult() is expected to always either notify a success/failure or requeue/drop the
                // elements involved; a defensive catch-all in case a future bug there (or a
                // TransmissionResult.PartialFailure inconsistent with the dispatched chunk) throws instead,
                // so such a bug can only degrade this chunk to an ordinary failure instead of losing it
                // silently with no trace at all.
                log.warn("Unhandled exception while processing the result of a transmitted batch of {} {}(s), treating it as a failure",
                        elements.size(), elementNoun, e);
                notifyListener(() -> listener.onFailed(elements.size(), e));
                onTransmissionFailure(elements, failureStreak);
            } finally {
                transmissionPermits.release();
            }
        });
    }

    /**
     * Turns whatever the transport function's future completed with into a non-{@code null}
     * {@link TransmissionResult}, treating an exceptional completion or a {@code null} result (either a
     * misbehaving transport, since neither is a valid {@link TransmissionResult}) as a plain
     * {@link TransmissionResult.Failure} instead of letting either propagate and skip the
     * retry/give-up handling in {@link #handleResult(List, int, TransmissionResult, long)}.
     *
     * @param elements  The chunk that was transmitted, only used to phrase the warning logged for either
     *                  case
     * @param result    The result the future completed with, or {@code null} if it completed exceptionally
     *                  or with {@code null} itself
     * @param throwable The exception the future completed with, or {@code null} if it completed normally
     * @return A non-{@code null} result
     */
    private TransmissionResult<T> normalizeResult(List<T> elements, @Nullable TransmissionResult<T> result, @Nullable Throwable throwable) {
        if (throwable != null) {
            // A future completed via completeExceptionally() (or failedFuture(), as used above for a
            // synchronously-thrown exception) delivers that exact exception here; one derived from a
            // transport's own internal composition (e.g. its own thenApply()) may instead wrap it in a
            // CompletionException, which is unwrapped here so the reported cause is the original failure.
            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                    ? throwable.getCause() : throwable;
            log.warn("Unhandled exception while transmitting a batch of {} {}(s)", elements.size(), elementNoun, cause);
            return TransmissionResult.failure(cause);
        }
        if (result == null) {
            log.warn("Transport returned a null result for a batch of {} {}(s), treating it as a failure",
                    elements.size(), elementNoun);
            return TransmissionResult.failure(null);
        }
        return result;
    }

    /**
     * Handles a (normalized, non-{@code null}) transmission result: notifies {@link #listener} of what
     * succeeded and/or failed, and requeues or gives up on whatever failed via
     * {@link #onTransmissionFailure(List, int)} — for a {@link TransmissionResult.PartialFailure}, scoped
     * to only the rejected elements, matched back against {@code elements} by reference identity (see
     * {@link TransmissionResult} for why), so the elements accepted alongside them are never retried or
     * counted toward {@code maxConsecutiveFailures}.
     *
     * @param elements       The chunk that was transmitted
     * @param failureStreak  The number of consecutive times this exact content had already failed before
     *                       this attempt
     * @param result         The (normalized) result of the attempt
     * @param durationMillis The time the transmission took, in milliseconds
     */
    private void handleResult(List<T> elements, int failureStreak, TransmissionResult<T> result, long durationMillis) {
        switch (result) {
            case TransmissionResult.Success<T> success -> {
                transmissionFailed = false;
                notifyListener(() -> listener.onSucceeded(elements.size(), durationMillis));
            }
            case TransmissionResult.Failure<T> failure -> {
                transmissionFailed = true;
                notifyListener(() -> listener.onFailed(elements.size(), failure.cause()));
                // Runs regardless of whether the notification above threw: a misbehaving listener must
                // never skip the retry/give-up handling below, or the failed elements would be lost
                // silently.
                onTransmissionFailure(elements, failureStreak);
            }
            case TransmissionResult.PartialFailure<T> partial -> {
                List<T> rejected = partial.rejected();
                transmissionFailed = !rejected.isEmpty();
                List<T> accepted = withoutIdentity(elements, rejected);
                if (!accepted.isEmpty()) {
                    notifyListener(() -> listener.onSucceeded(accepted.size(), durationMillis));
                }
                if (!rejected.isEmpty()) {
                    notifyListener(() -> listener.onFailed(rejected.size(), partial.cause()));
                    onTransmissionFailure(rejected, failureStreak);
                }
            }
        }
    }

    /**
     * Returns the elements of {@code elements} not present in {@code excluded}, matched by reference
     * identity rather than {@code equals()} (see {@link TransmissionResult} for why: content-identical but
     * distinct elements, e.g. repeated heartbeat log messages, are common, and equals-based matching would
     * silently misattribute which ones are excluded).
     *
     * @param elements The elements to filter
     * @param excluded The elements to exclude, by reference identity
     * @return The elements of {@code elements} not identical to any element of {@code excluded}
     */
    private static <T> List<T> withoutIdentity(List<T> elements, List<T> excluded) {
        Set<T> excludedIdentity = Collections.newSetFromMap(new IdentityHashMap<>(excluded.size()));
        excludedIdentity.addAll(excluded);
        // A misbehaving transport reporting more (or foreign) rejected elements than were actually
        // dispatched must not be able to make this capacity negative and throw, or the whole chunk (both
        // the genuinely accepted and rejected elements alike) would be lost silently instead of at least
        // being reported.
        List<T> remaining = new ArrayList<>(Math.max(0, elements.size() - excluded.size()));
        for (T element : elements) {
            if (!excludedIdentity.contains(element)) {
                remaining.add(element);
            }
        }
        return remaining;
    }

    /**
     * Invokes the given listener notification, logging and swallowing any exception a misbehaving
     * {@link BatcherListener} might throw. Matters most before {@link #onTransmissionFailure(List, int)} in
     * {@link #transmit(List, int)}: without this guard, a throwing {@code onFailed} would skip that call,
     * losing the failed elements without requeuing or reporting them dropped.
     *
     * @param notification The listener call to perform
     */
    private void notifyListener(Runnable notification) {
        try {
            notification.run();
        } catch (Exception e) {
            log.warn("Unhandled exception thrown by a BatcherListener", e);
        }
    }

    /**
     * Handles a failed transmission attempt: either discards the given elements once
     * {@code maxConsecutiveFailures} has been reached, or puts them back for a later retry.
     *
     * @param elements      The elements that failed to be transmitted
     * @param failureStreak The number of consecutive times this exact content had already failed before
     *                      this attempt
     */
    private void onTransmissionFailure(List<T> elements, int failureStreak) {
        int maxFailures = maxConsecutiveFailures.getAsInt();
        int newFailureStreak = failureStreak + 1;
        if (maxFailures > 0 && newFailureStreak >= maxFailures) {
            log.error("Giving up on {} {}(s) after {} consecutive failed transmission attempts, they will be discarded",
                    elements.size(), elementNoun, newFailureStreak);
            // Called directly (not via notifyDropped()): already running on transmissionExecutor's own
            // thread, and keeping this under the same permit transmit() still holds lets
            // awaitPendingTransmissions() observe it, same as the transmission itself.
            notifyListener(() -> listener.onDropped(List.copyOf(elements), "maximum consecutive transmission failures exceeded"));
        } else {
            requeue(elements, newFailureStreak);
        }
    }

    /**
     * Flushes the current batch and waits for every pending transmission (including that final one) to
     * complete, so a caller can safely release resources used by the transport function right after this
     * call returns without racing an in-flight transmission.
     *
     * @throws Exception Never thrown by this implementation (an interruption while waiting is caught,
     *                    re-flagged on the current thread, and logged instead)
     */
    public void close() throws Exception {
        try {
            flush();
            awaitPendingTransmissions();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for pending transmissions to complete");
        } finally {
            transmissionExecutor.shutdown();
        }
    }

    /**
     * Waits until every currently dispatched transmission (including the one triggered by the last call to
     * {@link #flush()}) has completed, so that closing any resource used by the transport function
     * (typically done right after calling this method) does not race with an in-flight transmission.
     * <p>
     * Also used by tests to deterministically wait for asynchronous transmissions to complete.
     */
    public void awaitPendingTransmissions() throws InterruptedException {
        transmissionPermits.acquire(maxConcurrentTransmissions);
        transmissionPermits.release(maxConcurrentTransmissions);
    }

}
