package com.chavaillaz.appender;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
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
 * {@link #flush()} instead. On top of that, a {@link BackoffPolicy} (disabled by default) holds off every
 * transmission for a while after a failure, so a struggling downstream system is not hit again at the rate
 * logs happen to arrive.
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
 * <p>
 * Instances are created through {@link #builder()}, every setting having a usable default except the
 * transport function itself.
 *
 * @param <T> The type of element accumulated and transmitted
 */
@Log4j2
public class Batcher<T> {

    /**
     * Maximum initial capacity reserved for a new batch, to avoid over-allocating for a large threshold.
     */
    private static final int MAX_INITIAL_BATCH_CAPACITY = 1_024;

    /**
     * Sentinel value of {@link #retryNotBeforeNanos} meaning no backoff is currently in effect. A plain
     * {@code 0} would not do: {@link System#nanoTime()} has an arbitrary origin and can legitimately return
     * it (or any other fixed value), so no reading of the clock can be reserved as a sentinel.
     */
    private static final long NO_BACKOFF = Long.MIN_VALUE;

    private final LongSupplier flushThreshold;
    private final IntSupplier maxPendingElements;
    private final IntSupplier maxRequestElements;
    private final IntSupplier maxConsecutiveFailures;
    private final BackoffPolicy backoff;
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
     * The {@link System#nanoTime()} reading before which no transmission is attempted, as decided by
     * {@link #backoff} after the last failure, or {@link #NO_BACKOFF} if none is in effect.
     */
    private volatile long retryNotBeforeNanos = NO_BACKOFF;

    /**
     * Whether {@link #close()} has been called, after which no element is accepted any more: without this, a
     * late {@link #add(List)} would stack elements into a batch nothing will ever transmit or report (its
     * dispatch being rejected by the shut-down {@link #transmissionExecutor}), losing them silently.
     */
    private volatile boolean closed;

    /**
     * Creates a new batching engine from the given builder.
     *
     * @param builder The builder carrying the settings to use
     */
    private Batcher(Builder<T> builder) {
        if (builder.maxConcurrentTransmissions < 1) {
            // A value of 0 or less would give the semaphore below no permits to ever hand out, so every
            // dispatch() would block forever instead of merely lowering throughput.
            throw new IllegalArgumentException(
                    "maxConcurrentTransmissions must be at least 1, but was " + builder.maxConcurrentTransmissions);
        }
        this.flushThreshold = builder.flushThreshold;
        this.maxPendingElements = builder.maxPendingElements;
        this.maxRequestElements = builder.maxRequestElements;
        this.maxConsecutiveFailures = builder.maxConsecutiveFailures;
        this.maxConcurrentTransmissions = builder.maxConcurrentTransmissions;
        this.backoff = builder.backoff;
        this.elementNoun = builder.elementNoun;
        this.transport = Objects.requireNonNull(builder.transport, "transport must not be null");
        this.listener = builder.listener;
        this.batch = newBatch();
        this.transmissionPermits = new Semaphore(builder.maxConcurrentTransmissions);
        this.transmissionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(builder.transmissionThreadPrefix, 0).factory());
    }

    /**
     * Creates a builder for a new batching engine, every setting having a usable default except
     * {@link Builder#transport(Function)}.
     *
     * @param <T> The type of element accumulated and transmitted
     * @return A new builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link Batcher}, preferred over a constructor taking every setting positionally: most of
     * them are same-typed suppliers and counts, which such a constructor makes trivially easy to swap by
     * mistake with no compiler error to show for it.
     *
     * @param <T> The type of element accumulated and transmitted
     */
    public static final class Builder<T> {

        private LongSupplier flushThreshold = () -> 1L;
        private IntSupplier maxPendingElements = () -> 10_000;
        private IntSupplier maxRequestElements = () -> Integer.MAX_VALUE;
        private IntSupplier maxConsecutiveFailures = () -> 5;
        private int maxConcurrentTransmissions = 4;
        private BackoffPolicy backoff = BackoffPolicy.none();
        private String transmissionThreadPrefix = "batcher-transmission-";
        private String elementNoun = "element";
        private @Nullable Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport;
        private BatcherListener<T> listener = BatcherListener.noop();

        private Builder() {
            // Instantiated only through Batcher.builder()
        }

        /**
         * Sets the number of accumulated elements that triggers a transmission, re-read on every call so it
         * can be changed at runtime. Defaults to {@code 1} (no batching).
         *
         * @param flushThreshold The supplier of the flush threshold
         * @return This builder
         */
        public Builder<T> flushThreshold(LongSupplier flushThreshold) {
            this.flushThreshold = flushThreshold;
            return this;
        }

        /**
         * Sets the maximum number of elements a pending batch can hold in memory, re-read on every call.
         * Defaults to {@code 10'000}.
         *
         * @param maxPendingElements The supplier of the maximum number of pending elements
         * @return This builder
         */
        public Builder<T> maxPendingElements(IntSupplier maxPendingElements) {
            this.maxPendingElements = maxPendingElements;
            return this;
        }

        /**
         * Sets the maximum number of elements sent together in a single request, a non-positive value
         * disabling chunking, re-read on every call. Defaults to {@link Integer#MAX_VALUE} (no chunking).
         *
         * @param maxRequestElements The supplier of the maximum number of elements per request
         * @return This builder
         */
        public Builder<T> maxRequestElements(IntSupplier maxRequestElements) {
            this.maxRequestElements = maxRequestElements;
            return this;
        }

        /**
         * Sets the maximum number of consecutive failed attempts allowed for the same pending batch before
         * it is discarded, a non-positive value disabling this limit, re-read on every call. Defaults to
         * {@code 5}.
         *
         * @param maxConsecutiveFailures The supplier of the maximum number of consecutive failures
         * @return This builder
         */
        public Builder<T> maxConsecutiveFailures(IntSupplier maxConsecutiveFailures) {
            this.maxConsecutiveFailures = maxConsecutiveFailures;
            return this;
        }

        /**
         * Sets the maximum number of batches transmitted concurrently, fixed once built (unlike the limits
         * above); must be at least {@code 1}, or every dispatch would block forever waiting for a permit
         * that never opens. Defaults to {@code 4}.
         *
         * @param maxConcurrentTransmissions The maximum number of concurrent transmissions
         * @return This builder
         */
        public Builder<T> maxConcurrentTransmissions(int maxConcurrentTransmissions) {
            this.maxConcurrentTransmissions = maxConcurrentTransmissions;
            return this;
        }

        /**
         * Sets how long to hold off transmissions after a failure. Defaults to {@link BackoffPolicy#none()},
         * the behavior of every version of this library before that interface existed.
         *
         * @param backoff The backoff policy
         * @return This builder
         */
        public Builder<T> backoff(BackoffPolicy backoff) {
            this.backoff = backoff;
            return this;
        }

        /**
         * Sets the name prefix for the threads created to run transmissions, so they can be identified in a
         * thread dump. Defaults to {@code "batcher-transmission-"}.
         *
         * @param transmissionThreadPrefix The thread name prefix
         * @return This builder
         */
        public Builder<T> transmissionThreadPrefix(String transmissionThreadPrefix) {
            this.transmissionThreadPrefix = transmissionThreadPrefix;
            return this;
        }

        /**
         * Sets the singular noun describing one element (e.g. {@code "document"}), used only to phrase log
         * messages and the reason string reported to the listener on a discard. Defaults to
         * {@code "element"}.
         *
         * @param elementNoun The singular noun describing one element
         * @return This builder
         */
        public Builder<T> elementNoun(String elementNoun) {
            this.elementNoun = elementNoun;
            return this;
        }

        /**
         * Sets the transport function, called with a batch (or chunk) to transmit; it may complete the
         * returned future synchronously or asynchronously, and is not expected to block the calling thread
         * either way. Required, having no sensible default.
         *
         * @param transport The transport function
         * @return This builder
         */
        public Builder<T> transport(Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport) {
            this.transport = transport;
            return this;
        }

        /**
         * Sets the listener notified of the outcome of every transmission attempt. Defaults to
         * {@link BatcherListener#noop()}.
         *
         * @param listener The listener
         * @return This builder
         */
        public Builder<T> listener(BatcherListener<T> listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Builds the batching engine, which starts accepting elements immediately.
         *
         * @return The new batching engine
         * @throws IllegalArgumentException If {@code maxConcurrentTransmissions} is below {@code 1}
         * @throws NullPointerException     If no transport function was set
         */
        public Batcher<T> build() {
            return new Batcher<>(this);
        }

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
        if (closed) {
            // Stacking them would be worse than reporting them here: nothing would ever transmit them
            // (dispatch being rejected by the shut-down executor) nor report them, losing them silently.
            log.warn("Discarding {} {}(s) submitted after this engine was closed", elements.size(), elementNoun);
            List<T> rejected = List.copyOf(elements);
            notifyListener(() -> listener.onDropped(rejected, "submitted after close"));
            return;
        }

        PendingBatch<T> snapshot = null;
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
            if (thresholdReached && !(batchingEnabled && transmissionFailed) && !isBackingOff()) {
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
     * Whether transmissions are currently held off by {@link #backoff} after a failure, meaning neither a
     * reached threshold nor a {@link #flush()} triggers one until the delay it decided has elapsed.
     *
     * @return {@code true} if a backoff delay is currently in effect
     */
    public boolean isBackingOff() {
        long notBefore = retryNotBeforeNanos;
        // Compares the difference rather than the readings themselves, the only correct way to order two
        // System.nanoTime() values (they may wrap around, being meaningful only relative to each other).
        return notBefore != NO_BACKOFF && System.nanoTime() - notBefore < 0;
    }

    /**
     * Sends the pending elements (a partially filled batch). Intended to be called periodically to clean
     * out pending elements. Does nothing while a backoff delay is in effect (see {@link #isBackingOff()}).
     */
    public void flush() {
        flush(false);
    }

    /**
     * Sends the pending elements (see {@link #flush()}).
     *
     * @param force Whether to transmit even while a backoff delay is in effect, used by {@link #close()}
     *              since it is the last chance for those elements to be sent at all
     */
    private void flush(boolean force) {
        PendingBatch<T> snapshot;
        synchronized (this) {
            if (batch.isEmpty()) {
                return;
            }
            if (!force && isBackingOff()) {
                log.debug("Holding off the transmission of {} pending {}(s) while backing off after a failure",
                        batch.size(), elementNoun);
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
     * transitively {@link #close()}) does not wait for it; falls back to notifying on the calling thread if
     * the executor is already shutting down, rather than losing the notification entirely.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    private void notifyDropped(List<T> elements, String reason) {
        try {
            transmissionExecutor.execute(() -> notifyListener(() -> listener.onDropped(elements, reason)));
        } catch (RejectedExecutionException e) {
            log.warn("Notifying the listener of {} dropped {}(s) on the calling thread, the engine is shutting down",
                    elements.size(), elementNoun);
            notifyListener(() -> listener.onDropped(elements, reason));
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
            case TransmissionResult.Success<T> success -> onTransmissionSuccess(elements.size(), durationMillis);
            case TransmissionResult.Failure<T> failure -> {
                transmissionFailed = true;
                notifyListener(() -> listener.onFailed(elements.size(), failure.cause()));
                // Runs regardless of whether the notification above threw: a misbehaving listener must
                // never skip the retry/give-up handling below, or the failed elements would be lost
                // silently.
                onTransmissionFailure(elements, failureStreak);
            }
            case TransmissionResult.PartialFailure<T> partial -> {
                Partition<T> partition = partition(elements, partial.rejected());
                List<T> rejected = partition.rejected();
                if (rejected.size() != partial.rejected().size()) {
                    // Only possible from a transport violating the TransmissionResult contract: duplicate
                    // references, or elements that were never part of this chunk. Requeuing those verbatim
                    // would inject foreign data into the pending batch, so only the intersection is kept.
                    log.warn("Transport reported {} rejected {}(s) for a batch of {}, but only {} of them belong to it; "
                                    + "ignoring the rest (they must be reported by reference identity)",
                            partial.rejected().size(), elementNoun, elements.size(), rejected.size());
                }
                if (rejected.isEmpty()) {
                    onTransmissionSuccess(elements.size(), durationMillis);
                    return;
                }
                transmissionFailed = true;
                List<T> accepted = partition.accepted();
                if (!accepted.isEmpty()) {
                    notifyListener(() -> listener.onSucceeded(accepted.size(), durationMillis));
                }
                notifyListener(() -> listener.onFailed(rejected.size(), partial.cause()));
                onTransmissionFailure(rejected, failureStreak);
            }
        }
    }

    /**
     * Handles a fully successful transmission attempt: clears the failure and backoff state, so the next
     * batch is transmitted at the first opportunity again, and notifies {@link #listener}.
     *
     * @param count          The number of elements accepted
     * @param durationMillis The time the transmission took, in milliseconds
     */
    private void onTransmissionSuccess(int count, long durationMillis) {
        transmissionFailed = false;
        retryNotBeforeNanos = NO_BACKOFF;
        notifyListener(() -> listener.onSucceeded(count, durationMillis));
    }

    /**
     * The elements of a dispatched chunk, split into those a {@link TransmissionResult.PartialFailure}
     * reported as rejected and those it did not.
     *
     * @param accepted The elements not reported as rejected, in their original order
     * @param rejected The elements reported as rejected, in their original order
     * @param <T>      The type of element
     */
    private record Partition<T>(List<T> accepted, List<T> rejected) {

    }

    /**
     * Splits {@code elements} according to whether each is present in {@code rejected}, matched by reference
     * identity rather than {@code equals()} (see {@link TransmissionResult} for why: content-identical but
     * distinct elements, e.g. repeated heartbeat log messages, are common, and equals-based matching would
     * silently misattribute which ones are retried).
     * <p>
     * Driven by {@code elements} rather than by {@code rejected}, so the result can only ever contain
     * elements this chunk was actually dispatched with, each exactly as many times as it appears in it: a
     * misbehaving transport reporting duplicates, or references foreign to the chunk entirely, cannot get
     * them injected into the pending batch through a requeue.
     *
     * @param elements The dispatched chunk
     * @param rejected The elements the transport reported as rejected
     * @param <T>      The type of element
     * @return The chunk, partitioned into accepted and rejected elements
     */
    private static <T> Partition<T> partition(List<T> elements, List<T> rejected) {
        if (rejected.isEmpty()) {
            return new Partition<>(elements, List.of());
        }
        Set<T> rejectedIdentity = Collections.newSetFromMap(new IdentityHashMap<>(rejected.size()));
        rejectedIdentity.addAll(rejected);
        List<T> accepted = new ArrayList<>(elements.size());
        List<T> actuallyRejected = new ArrayList<>(Math.min(elements.size(), rejected.size()));
        for (T element : elements) {
            if (rejectedIdentity.contains(element)) {
                actuallyRejected.add(element);
            } else {
                accepted.add(element);
            }
        }
        return new Partition<>(accepted, actuallyRejected);
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
     * {@code maxConsecutiveFailures} has been reached, or puts them back for a later retry, holding off the
     * next attempt for as long as {@link #backoff} decides in the latter case.
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
            return;
        }

        applyBackoff(newFailureStreak);
        requeue(elements, newFailureStreak);
    }

    /**
     * Holds off the next transmission for as long as {@link #backoff} decides for the given failure streak.
     * Only ever pushes the deadline further out, never pulls it back in: a shorter delay computed for a
     * lower streak (a different chunk failing for the first time, concurrently) must not cut short the wait
     * a longer one already established.
     *
     * @param consecutiveFailures The number of consecutive failures the pending batch has now reached
     */
    private void applyBackoff(int consecutiveFailures) {
        long delayMillis;
        try {
            delayMillis = backoff.delayMillis(consecutiveFailures);
        } catch (Exception e) {
            log.warn("Unhandled exception thrown by a BackoffPolicy, retrying without backing off", e);
            return;
        }
        if (delayMillis <= 0) {
            return;
        }

        long deadline = System.nanoTime() + MILLISECONDS.toNanos(delayMillis);
        synchronized (this) {
            long current = retryNotBeforeNanos;
            if (current == NO_BACKOFF || deadline - current > 0) {
                retryNotBeforeNanos = deadline;
            }
        }
        log.debug("Holding off the next transmission for {}ms after {} consecutive failure(s)", delayMillis, consecutiveFailures);
    }

    /**
     * Flushes the current batch and waits for every pending transmission (including that final one) to
     * complete, so a caller can safely release resources used by the transport function right after this
     * call returns without racing an in-flight transmission.
     * <p>
     * Whatever is still pending once that wait is over — typically elements a failing final transmission put
     * back — is reported through {@link BatcherListener#onDropped(List, String)} rather than silently
     * discarded along with this engine, giving a dead-letter sink a last chance to save them.
     * <p>
     * Never throws: an interruption while waiting is caught, re-flagged on the current thread, and logged.
     */
    public void close() {
        closed = true;
        try {
            // Forced: a backoff delay still in effect must not stop this very last transmission attempt,
            // which is the only remaining chance for those elements to be sent at all.
            flush(true);
            awaitPendingTransmissions();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for pending transmissions to complete");
        } finally {
            transmissionExecutor.shutdown();
            reportRemainingAsDropped();
        }
    }

    /**
     * Reports whatever is left in the pending batch once this engine is closed (see {@link #close()}) as
     * dropped, so those elements are not lost without a trace. Notifies on the calling thread rather than
     * through {@link #notifyDropped(List, String)}, {@link #transmissionExecutor} being shut down by then,
     * and because a caller of {@code close()} is already waiting on this work anyway.
     */
    private void reportRemainingAsDropped() {
        List<T> remaining;
        synchronized (this) {
            if (batch.isEmpty()) {
                return;
            }
            remaining = List.copyOf(batch);
            batch = new ArrayList<>(0);
            pendingFailureStreak = 0;
        }
        log.error("Discarding {} {}(s) still pending once closed, they were never successfully transmitted",
                remaining.size(), elementNoun);
        notifyListener(() -> listener.onDropped(remaining, "still pending when closed"));
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
