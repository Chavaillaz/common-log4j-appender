package com.chavaillaz.appender;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.time.Duration;
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
import java.util.function.ToLongFunction;

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
 * Both of those limits count elements, which only stands in for what actually matters — memory held, and
 * bytes put on the wire — while elements are of roughly uniform size. Supplying an {@code elementSize}
 * measurement enables the two that bound those directly: {@code maxPendingBytes}, discarding the oldest
 * elements once the pending batch weighs more than that, and {@code maxRequestBytes}, splitting a batch into
 * chunks that each stay under it. Both are off (and cost nothing) unless a measurement is supplied.
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

    /**
     * Sentinel value of {@link #closeDeadlineNanos} meaning no deadline is in effect, for the same reason
     * {@link #NO_BACKOFF} cannot simply be {@code 0}.
     */
    private static final long NO_DEADLINE = Long.MIN_VALUE;

    /**
     * Upper bound applied to a close timeout before converting it to nanoseconds, so an absurdly large one
     * cannot overflow that conversion into a negative (i.e. already elapsed) deadline. A year is well past
     * any sane shutdown budget, and indistinguishable from waiting indefinitely in practice.
     */
    private static final long MAX_TIMEOUT_MILLIS = Duration.ofDays(365).toMillis();

    /**
     * How many {@link #notifyDropped(List, String)} notifications may be in flight at once before the rest
     * are delivered on the calling thread instead. Small on purpose: this exists to keep a slow listener
     * from piling up notifications (and the elements each one holds) without bound, not to parallelize it.
     */
    private static final int MAX_CONCURRENT_DROP_NOTIFICATIONS = 4;

    /**
     * How long {@link #acquireTransmissionPermit()} waits for a permit at a time while no close is in
     * progress, before looking again at whether one has begun (see that method for why it does not simply
     * wait indefinitely). Only ever reached while genuinely blocked on a saturated transport, so waking up
     * this often costs nothing on any path that is actually making progress.
     */
    private static final long PERMIT_WAIT_SLICE_NANOS = Duration.ofSeconds(1).toNanos();

    private final LongSupplier flushThreshold;
    private final IntSupplier maxPendingElements;
    private final IntSupplier maxRequestElements;
    private final IntSupplier maxConsecutiveFailures;
    private final LongSupplier maxPendingBytes;
    private final LongSupplier maxRequestBytes;

    /**
     * Measures one element, or {@code null} to not track sizes at all — in which case both byte limits above
     * are inert and nothing here ever pays for measuring. Fixed at construction rather than re-read per call
     * like the count limits: turning measurement on midway would leave {@link #pendingBytes} describing only
     * part of the batch, so the decision has to be made once, before anything accumulates.
     */
    private final @Nullable ToLongFunction<T> elementSize;

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
     * How many of the leading elements of {@link #batch} are the ones {@link #pendingFailureStreak} was
     * earned by, {@code 0} meaning none of them are left and the streak no longer describes anything.
     * <p>
     * A streak belongs to the elements that failed, not to the batch holding them, and both places that
     * shrink the batch — {@link BoundedTrim} and {@link #trimToPendingBytes()} — cut from the front, which
     * is exactly where {@link #requeue(List, int)} puts what it hands back. So counting how far that failed
     * region still reaches is enough to know when the last of it is gone, at which point the streak is
     * dropped rather than charged to elements that never failed at all.
     * <p>
     * Always guarded by the monitor of this instance, like {@link #batch} itself.
     */
    private int pendingFailureCount;

    /**
     * Estimated total size of {@link #batch}, kept up to date as elements are added and discarded rather than
     * recomputed on demand: summing it per call would make every {@link #add(List)} walk the whole pending
     * batch, turning an O(1) accumulation into an O(n) one exactly when the batch is at its largest. Always
     * {@code 0} while {@link #elementSize} is {@code null}.
     * <p>
     * Always guarded by the monitor of this instance, like {@link #batch} itself.
     */
    private long pendingBytes;

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
     * Bounds the number of drop notifications handed to {@link #transmissionExecutor} at once to
     * {@link #MAX_CONCURRENT_DROP_NOTIFICATIONS}. Deliberately separate from {@link #transmissionPermits}:
     * a notification is best-effort and must not consume a slot a real transmission could use, nor make
     * {@link #awaitPendingTransmissions()} wait on a listener.
     */
    private final Semaphore dropNotificationPermits = new Semaphore(MAX_CONCURRENT_DROP_NOTIFICATIONS);

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
     * Whether {@link #close(Duration)} has been called, after which no element is accepted any more: without
     * this, a late {@link #add(List)} would stack elements into a batch nothing will ever transmit or report
     * (its dispatch being rejected by the shut-down {@link #transmissionExecutor}), losing them silently.
     * <p>
     * Raised before {@link #close(Duration)} acquires the monitor of this instance for the first time, which
     * is what lets {@link #add(List)} read it under that monitor and conclude that an unset value means
     * {@link #reportRemainingAsDropped()} has not run yet, and will therefore still see what it stacks.
     */
    private volatile boolean closed;

    /**
     * Whether {@link #reportRemainingAsDropped()} has already emptied {@link #batch} for good. Past that
     * point nothing will ever transmit or report the batch again, so {@link #requeue(List, int)} reports its
     * elements directly instead of merging them into a batch no one is left to look at.
     * <p>
     * Always guarded by the monitor of this instance, like {@link #batch} itself.
     */
    private boolean drained;

    /**
     * The {@link System#nanoTime()} reading past which {@link #close(Duration)} stops waiting, or
     * {@link #NO_DEADLINE} while no bounded close is in progress. Read by {@link #dispatchChunk(List, int)}
     * too, so the final flush's own wait for a transmission permit is bounded by the very same budget: with
     * every permit held by transmissions that never complete, acquiring one would otherwise block the whole
     * close before it even reaches its (bounded) wait, defeating the timeout entirely.
     */
    private volatile long closeDeadlineNanos = NO_DEADLINE;

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
        this.maxPendingBytes = builder.maxPendingBytes;
        this.maxRequestBytes = builder.maxRequestBytes;
        this.elementSize = builder.elementSize;
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
        private LongSupplier maxPendingBytes = () -> Long.MAX_VALUE;
        private LongSupplier maxRequestBytes = () -> Long.MAX_VALUE;
        private @Nullable ToLongFunction<T> elementSize;
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
         * Sets the maximum estimated size a pending batch can reach in memory, re-read on every call. Has no
         * effect unless {@link #elementSize(ToLongFunction)} is also set. Defaults to {@link Long#MAX_VALUE}
         * (unbounded); a non-positive value also disables the limit.
         *
         * @param maxPendingBytes The supplier of the maximum pending size, in bytes
         * @return This builder
         */
        public Builder<T> maxPendingBytes(LongSupplier maxPendingBytes) {
            this.maxPendingBytes = maxPendingBytes;
            return this;
        }

        /**
         * Sets the maximum estimated size of a single request, re-read on every call: a batch whose elements
         * add up to more than this is split into chunks that each stay under it. Has no effect unless
         * {@link #elementSize(ToLongFunction)} is also set. Defaults to {@link Long#MAX_VALUE} (unbounded); a
         * non-positive value also disables the limit.
         *
         * @param maxRequestBytes The supplier of the maximum request size, in bytes
         * @return This builder
         */
        public Builder<T> maxRequestBytes(LongSupplier maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
            return this;
        }

        /**
         * Sets how to measure one element, enabling the two byte limits above — which do nothing without it,
         * since there is otherwise no way to know what an element weighs.
         * <p>
         * Left unset by default, and deliberately not defaulted to some generic guess: measuring costs
         * something on the path every element takes, and an engine given no byte limit to enforce should not
         * pay it. Set it only alongside {@link #maxPendingBytes(LongSupplier)} or
         * {@link #maxRequestBytes(LongSupplier)}.
         * <p>
         * Expected to be cheap, pure and free of side effects: it is called once per element on the way in,
         * and again per element when chunking a batch for transmission. A negative result is treated as zero,
         * and an exception is logged and treated the same way, so a misbehaving measurement can only make the
         * limits less accurate, never break the pipeline.
         *
         * @param elementSize The measurement function
         * @return This builder
         */
        public Builder<T> elementSize(ToLongFunction<T> elementSize) {
            this.elementSize = elementSize;
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
        PendingBatch<T> snapshot = null;
        List<T> discarded = null;
        List<T> oversized = null;
        boolean rejected;
        int maxPending = 0;
        synchronized (this) {
            // Read while holding the monitor rather than before taking it. close() raises this flag before
            // it ever acquires the monitor, so seeing it unset here proves reportRemainingAsDropped() has
            // not drained the batch yet and will still pick up whatever is stacked below. Checked outside,
            // a caller could pass it just as a concurrent close() begins, then stack elements into a batch
            // that has already been drained -- nothing would ever transmit them (dispatch being rejected by
            // the shut-down executor) nor report them, losing them silently.
            rejected = closed;
            if (!rejected) {
                batch.addAll(elements);
                pendingBytes += sizeOf(elements);
                log.trace("Stacking batch {}/{}", batch.size(), flushThreshold.getAsLong());

                // Enforces the same cap as requeue() here too, so maxPendingElements bounds memory even when
                // flushThreshold is set higher (or effectively unbounded) and nothing is actually failing.
                maxPending = maxPendingElements.getAsInt();
                BoundedTrim.Trimmed<T> trimmed = BoundedTrim.trim(batch, maxPending);
                batch = trimmed.kept();
                discarded = trimmed.discarded();
                pendingBytes -= sizeOf(discarded);
                oversized = trimToPendingBytes();
                // Only ever appends at the end, so whatever the two trims above cut came off the front,
                // where the elements a failure streak was earned by sit. Once the last of them is gone the
                // streak goes with it, instead of being charged to the newer elements left behind.
                dropFailedPrefix(size(discarded) + size(oversized));

                long threshold = flushThreshold.getAsLong();
                boolean batchingEnabled = threshold > 1;
                // Never on an empty batch, however low the threshold: a non-positive one (allowed, meaning
                // "no batching") makes "reached" trivially true, so an add() left with nothing -- an empty
                // list, or one the caps above discarded outright -- would otherwise hand the transport an
                // empty request to send, fail on it, and count that failure against the pending batch.
                // flush() has always guarded this; the threshold path never did.
                boolean thresholdReached = !batch.isEmpty() && batch.size() >= threshold;
                if (thresholdReached && !(batchingEnabled && transmissionFailed) && !isBackingOff()) {
                    snapshot = swapBatch();
                }
            }
        }
        if (rejected) {
            log.warn("Discarding {} {}(s) submitted after this engine was closed", elements.size(), elementNoun);
            List<T> copy = List.copyOf(elements);
            // Notified on the calling thread: transmissionExecutor is already shut down by the time this
            // flag is visible, so handing it off would only fall straight back here.
            notifyListener(() -> listener.onDropped(copy, "submitted after close"));
            return;
        }
        // Notified off this thread where possible (see notifyDropped()) since this runs directly on whatever
        // thread called add(): a slow listener should not stall it, and only stalls it once enough
        // notifications are already in flight that queueing another would grow without bound.
        if (discarded != null) {
            log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending {}s of {} while accumulating",
                    discarded.size(), elementNoun, elementNoun, maxPending);
            notifyDropped(discarded, "maximum pending " + elementNoun + "s exceeded");
        }
        if (oversized != null) {
            notifyDropped(oversized, "maximum pending bytes exceeded");
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
     * @param force Whether to transmit even while a backoff delay is in effect, used by
     *              {@link #close(Duration)} since it is the last chance for those elements to be sent at all
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
        pendingFailureCount = 0;
        pendingBytes = 0;
        return previous;
    }

    /**
     * Accounts for elements discarded from the front of {@link #batch}, forgetting
     * {@link #pendingFailureStreak} once the last element that earned it is gone (see
     * {@link #pendingFailureCount}).
     * <p>
     * Must be called while holding the monitor of this instance.
     *
     * @param discardedFromFront How many elements were just discarded from the front of the batch
     */
    private void dropFailedPrefix(int discardedFromFront) {
        pendingFailureCount = Math.max(0, pendingFailureCount - discardedFromFront);
        if (pendingFailureCount == 0) {
            pendingFailureStreak = 0;
        }
    }

    /**
     * Measures one element through {@link #elementSize}, or returns {@code 0} if sizes are not tracked.
     * Tolerates a misbehaving measurement — a negative result, or one throwing outright — by counting the
     * element as weightless: a byte limit going unenforced is a far better outcome than the element being
     * lost, or the whole pipeline failing, over a faulty estimate.
     *
     * @param element The element to measure
     * @return The estimated size of the element in bytes, never negative
     */
    private long sizeOf(T element) {
        if (elementSize == null) {
            return 0;
        }
        try {
            return Math.max(0, elementSize.applyAsLong(element));
        } catch (Throwable e) {
            // Throwable, not Exception: a measurement walking a self-referencing element throws a
            // StackOverflowError, which is an Error. Letting it escape would carry it out of add() through
            // a batch already appended to but not yet accounted for in pendingBytes, leaving that running
            // total describing a batch it no longer matches for the rest of this engine's life.
            log.warn("Unhandled exception thrown while measuring a {}, counting it as empty", elementNoun, e);
            return 0;
        }
    }

    /**
     * The size of a list of discarded elements, {@code null} standing for none of them.
     *
     * @param elements The elements to count, or {@code null} for none
     * @return The number of elements
     */
    private static int size(@Nullable List<?> elements) {
        return elements == null ? 0 : elements.size();
    }

    /**
     * Measures a whole list of elements (see {@link #sizeOf(Object)}).
     *
     * @param elements The elements to measure, or {@code null} for none
     * @return The estimated total size in bytes
     */
    private long sizeOf(@Nullable List<T> elements) {
        if (elementSize == null || elements == null) {
            return 0;
        }
        long total = 0;
        for (T element : elements) {
            total += sizeOf(element);
        }
        return total;
    }

    /**
     * Discards the oldest elements of {@link #batch} until its estimated size fits within
     * {@code maxPendingBytes}, the size-based counterpart of the {@code maxPendingElements} cap
     * {@link BoundedTrim} applies: a batch of documents well within the count limit can still hold far more
     * memory than expected when the documents themselves are large.
     * <p>
     * Must be called while holding the monitor of this instance.
     *
     * @return The elements discarded, or {@code null} if none were
     */
    private @Nullable List<T> trimToPendingBytes() {
        long maxBytes = maxPendingBytes.getAsLong();
        if (elementSize == null || maxBytes <= 0 || pendingBytes <= maxBytes) {
            return null;
        }

        List<T> discarded = new ArrayList<>();
        int index = 0;
        while (index < batch.size() && pendingBytes > maxBytes) {
            T element = batch.get(index++);
            pendingBytes -= sizeOf(element);
            discarded.add(element);
        }
        batch = new ArrayList<>(batch.subList(index, batch.size()));
        if (batch.isEmpty()) {
            // Re-anchors the running total on an empty batch rather than letting rounding from a measurement
            // that is not perfectly stable accumulate across the engine's whole lifetime.
            pendingBytes = 0;
        }
        if (discarded.isEmpty()) {
            // Only reachable when the running total drifted above the limit while the batch was already
            // empty (a measurement that is not perfectly stable). The re-anchoring above has just healed
            // it, and announcing a discard of zero elements would only be noise.
            return null;
        }

        log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending size of {} bytes",
                discarded.size(), elementNoun, maxBytes);
        // Copied rather than handed over as-is: this list travels to BatcherListener#onDropped, whose
        // contract (like every other discard path here) is an unmodifiable list the listener may keep.
        return List.copyOf(discarded);
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
        List<T> discarded = null;
        List<T> oversized = null;
        List<T> abandoned = null;
        int maxPending = 0;
        synchronized (this) {
            if (drained) {
                // reportRemainingAsDropped() already emptied the batch for good, and add() refuses to stack
                // anything new past that point, so nothing will ever look at this batch again: merging these
                // elements back in would leave them sitting there with nothing left to transmit or report
                // them. Reached when a transmission, or a dispatch that never got a permit within the close
                // budget, hands its elements back just after close() drained.
                abandoned = List.copyOf(elements);
            } else {
                List<T> merged = new ArrayList<>(elements.size() + batch.size());
                merged.addAll(elements);
                merged.addAll(batch);
                // The requeued elements left this batch (and its running total) when they were swapped out
                // for transmission, so they are measured again on the way back in rather than remembered.
                pendingBytes += sizeOf(elements);

                maxPending = maxPendingElements.getAsInt();
                BoundedTrim.Trimmed<T> trimmed = BoundedTrim.trim(merged, maxPending);
                batch = trimmed.kept();
                discarded = trimmed.discarded();
                pendingBytes -= sizeOf(discarded);
                oversized = trimToPendingBytes();

                // Both trims above cut from the front, which is exactly where the requeued elements were
                // just put, so anything they cut past those ate into whatever failed region was already
                // there behind them.
                int discardedFromFront = size(discarded) + size(oversized);
                dropFailedPrefix(Math.max(0, discardedFromFront - elements.size()));

                int keptRequeued = Math.max(0, elements.size() - discardedFromFront);
                if (keptRequeued > 0) {
                    // The live batch might already carry forward a streak of its own (from a different
                    // failed chunk merged in moments earlier by a concurrent transmission); keeping the
                    // higher of the two is the conservative choice, since the merged batch is at least as
                    // "tainted" as its worst ingredient.
                    pendingFailureStreak = Math.max(pendingFailureStreak, failureStreak);
                    pendingFailureCount += keptRequeued;
                }
            }
        }
        // Notified outside the lock: it's also needed to accumulate the next batch, and a slow listener
        // must not stall that. Called directly (not via notifyDropped()) since this is reached either from
        // onTransmissionFailure() (already on transmissionExecutor's own thread) or, more rarely, from a
        // failed dispatch handoff — never from the caller-facing add()/flush().
        if (abandoned != null) {
            List<T> lost = abandoned;
            log.error("Discarding {} {}(s) put back after this engine was closed, they were never successfully transmitted",
                    lost.size(), elementNoun);
            notifyListener(() -> listener.onDropped(lost, "still pending when closed"));
            return;
        }
        if (discarded != null) {
            List<T> trimmedOut = discarded;
            log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending {}s of {} while retrying",
                    trimmedOut.size(), elementNoun, elementNoun, maxPending);
            notifyListener(() -> listener.onDropped(trimmedOut, "maximum pending " + elementNoun + "s exceeded"));
        }
        if (oversized != null) {
            List<T> tooHeavy = oversized;
            notifyListener(() -> listener.onDropped(tooHeavy, "maximum pending bytes exceeded"));
        }
    }


    /**
     * Notifies {@link #listener} of dropped elements on a fresh virtual thread rather than the calling one,
     * so a slow listener (writing to a file, a network call) can never stall it. Used only by {@link #add(List)},
     * reached directly from the caller; every other discard in this class is already off that path and
     * notified directly instead.
     * <p>
     * Bounded to {@link #MAX_CONCURRENT_DROP_NOTIFICATIONS} in flight at once: past that, the notification
     * happens on the calling thread instead, so a slow listener cannot have them (and the elements each one
     * holds) accumulate without bound exactly when sustained overload is making every {@link #add(List)}
     * discard.
     * <p>
     * Best-effort: not tracked by {@link #transmissionPermits}, so {@link #awaitPendingTransmissions()} (and
     * transitively {@link #close()}) does not wait for it; falls back to notifying on the calling thread if
     * the executor is already shutting down, rather than losing the notification entirely.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    private void notifyDropped(List<T> elements, String reason) {
        if (!dropNotificationPermits.tryAcquire()) {
            // Every slot is still held by a listener that has not returned yet. Handing this notification to
            // yet another thread would let a slow one (a file write, a lock, a network call) accumulate them
            // without bound, each pinning the elements it was given -- and the moment that happens is
            // precisely when the pending cap is already discarding under overload, so the mechanism meant to
            // bound memory would be the one growing it. Notifying inline instead applies backpressure to the
            // accumulating thread, which is never an application thread (see the class documentation).
            notifyListener(() -> listener.onDropped(elements, reason));
            return;
        }

        try {
            transmissionExecutor.execute(() -> {
                try {
                    notifyListener(() -> listener.onDropped(elements, reason));
                } finally {
                    dropNotificationPermits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            dropNotificationPermits.release();
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
        int maxCount = maxRequestElements.getAsInt();
        long maxBytes = maxRequestBytes.getAsLong();
        boolean countLimited = maxCount > 0 && elements.size() > maxCount;
        // A single element is never worth measuring: there is nothing to split it against, and it goes out
        // on its own either way.
        boolean byteLimited = elementSize != null && maxBytes > 0 && maxBytes < Long.MAX_VALUE && elements.size() > 1;
        if (!countLimited && !byteLimited) {
            dispatchChunk(elements, failureStreak);
            return;
        }

        for (List<T> chunk : chunk(elements, maxCount, byteLimited ? maxBytes : 0)) {
            dispatchChunk(chunk, failureStreak);
        }
    }

    /**
     * Splits the given list into consecutive, independent chunks preserving order, cutting whenever the next
     * element would push the current chunk past {@code maxCount} elements or {@code maxBytes} bytes.
     *
     * @param elements The elements to split
     * @param maxCount The maximum number of elements per chunk, or a non-positive value for no such limit
     * @param maxBytes The maximum estimated size per chunk, or a non-positive value for no such limit
     * @return The list of chunks, in original order
     */
    private List<List<T>> chunk(List<T> elements, int maxCount, long maxBytes) {
        List<List<T>> chunks = new ArrayList<>();
        List<T> current = new ArrayList<>();
        long currentBytes = 0;
        for (T element : elements) {
            long size = maxBytes > 0 ? sizeOf(element) : 0;
            boolean countReached = maxCount > 0 && current.size() >= maxCount;
            // Never cut before a chunk's first element: one element bigger than the byte limit all by itself
            // cannot be split any further, so it goes out alone (and is left for the transport to reject)
            // rather than producing an empty chunk, or no chunk at all, forever.
            boolean bytesReached = maxBytes > 0 && !current.isEmpty() && currentBytes + size > maxBytes;
            if (countReached || bytesReached) {
                chunks.add(List.copyOf(current));
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(element);
            currentBytes += size;
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
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
            if (!acquireTransmissionPermit()) {
                log.warn("Unable to transmit {} {}(s) within the remaining close budget, they will be reported as dropped",
                        elements.size(), elementNoun);
                requeue(elements, failureStreak);
                return;
            }
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
     * Acquires one transmission permit, waiting indefinitely in the normal case but only until
     * {@link #closeDeadlineNanos} while a bounded {@link #close(Duration)} is in progress.
     * <p>
     * While no close is in progress, the wait is taken in {@link #PERMIT_WAIT_SLICE_NANOS} slices rather
     * than as one unbounded acquire, so that a close beginning <em>after</em> this call still bounds it. A
     * single unbounded acquire reads the deadline once and would never learn about one set a moment later,
     * leaving the caller blocked for good on a transport whose future never completes — the very case
     * {@link #close(Duration)} takes a timeout for.
     *
     * @return {@code true} if a permit was acquired, {@code false} if the close deadline elapsed first
     */
    private boolean acquireTransmissionPermit() throws InterruptedException {
        while (true) {
            long deadline = closeDeadlineNanos;
            if (deadline != NO_DEADLINE) {
                long remainingNanos = deadline - System.nanoTime();
                return remainingNanos > 0 && transmissionPermits.tryAcquire(remainingNanos, NANOSECONDS);
            }
            if (transmissionPermits.tryAcquire(PERMIT_WAIT_SLICE_NANOS, NANOSECONDS)) {
                return true;
            }
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
        } catch (Throwable e) {
            // Throwable, not Exception: an Error thrown here (a NoClassDefFoundError from a transport
            // dependency missing at runtime, a StackOverflowError from serializing a self-referencing
            // element, ...) would escape this virtual thread before the whenComplete() below is even
            // registered, so its finally block would never release the permit acquired for this chunk, and
            // these elements would be neither requeued nor reported. A handful of those wedge the engine
            // for good: every later dispatch waits forever for a permit that will never come back, and an
            // unbounded close() waits with it.
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((result, throwable) -> {
            try {
                long durationMillis = (System.nanoTime() - start) / 1_000_000;
                handleResult(elements, failureStreak, normalizeResult(elements, result, throwable), durationMillis);
            } catch (Throwable e) {
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
        } catch (Throwable e) {
            // Throwable, not Exception: a listener writing a self-referencing element to a dead-letter sink
            // throws a StackOverflowError, which is an Error. Since the whole point of this guard is that
            // the onFailed notification cannot skip the onTransmissionFailure() call right after it,
            // letting an Error through would lose exactly the elements it exists to preserve.
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
        } catch (Throwable e) {
            // Throwable, not Exception, for the same reason as the guards above: this runs between the
            // decision to retry and the requeue() actually keeping the elements, so anything escaping here
            // discards them without a retry and without a report.
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
     * Flushes the current batch and waits, indefinitely, for every pending transmission (including that
     * final one) to complete; prefer {@link #close(Duration)}, which bounds that wait, for a transport whose
     * future is not guaranteed to complete.
     * <p>
     * Never throws: an interruption while waiting is caught, re-flagged on the current thread, and logged.
     */
    public void close() {
        close(null);
    }

    /**
     * Flushes the current batch and waits for every pending transmission (including that final one) to
     * complete, so a caller can safely release resources used by the transport function right after this
     * call returns without racing an in-flight transmission.
     * <p>
     * Whatever is still pending once that wait is over — elements a failing final transmission put back, or
     * elements still held by a transmission that did not complete within {@code timeout} — is reported
     * through {@link BatcherListener#onDropped(List, String)} rather than silently discarded along with this
     * engine, giving a dead-letter sink a last chance to save them.
     *
     * @param timeout The maximum time to wait for pending transmissions to complete, or {@code null} to wait
     *                indefinitely (only safe for a transport guaranteed to always complete its future, since
     *                one that never does would otherwise block this call, and any JVM shutdown behind it,
     *                forever)
     */
    public void close(@Nullable Duration timeout) {
        closed = true;
        Long timeoutMillis = toMillis(timeout);
        if (timeoutMillis != null) {
            closeDeadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(timeoutMillis);
        }
        try {
            // Forced: a backoff delay still in effect must not stop this very last transmission attempt,
            // which is the only remaining chance for those elements to be sent at all.
            flush(true);
            if (!awaitUntilCloseDeadline()) {
                log.warn("Gave up waiting for pending transmissions to complete after {}", timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for pending transmissions to complete");
        } finally {
            transmissionExecutor.shutdown();
            reportRemainingAsDropped();
        }
    }

    /**
     * Reports whatever is left in the pending batch once this engine is closed (see {@link #close(Duration)})
     * as dropped, so those elements are not lost without a trace. Notifies on the calling thread rather than
     * through {@link #notifyDropped(List, String)}, {@link #transmissionExecutor} being shut down by then,
     * and because a caller of {@code close()} is already waiting on this work anyway.
     */
    private void reportRemainingAsDropped() {
        List<T> remaining;
        synchronized (this) {
            // Raised before the early return below too: an empty batch is still a drained one, and a
            // requeue landing after this point must report its elements rather than stack them here.
            drained = true;
            if (batch.isEmpty()) {
                return;
            }
            remaining = List.copyOf(batch);
            batch = new ArrayList<>(0);
            pendingBytes = 0;
            pendingFailureStreak = 0;
            pendingFailureCount = 0;
        }
        log.error("Discarding {} {}(s) still pending once closed, they were never successfully transmitted",
                remaining.size(), elementNoun);
        notifyListener(() -> listener.onDropped(remaining, "still pending when closed"));
    }

    /**
     * Waits, indefinitely, until every currently dispatched transmission (including the one triggered by the
     * last call to {@link #flush()}) has completed, so that closing any resource used by the transport
     * function (typically done right after calling this method) does not race with an in-flight transmission.
     * <p>
     * Also used by tests to deterministically wait for asynchronous transmissions to complete.
     */
    public void awaitPendingTransmissions() throws InterruptedException {
        awaitPendingTransmissions(null);
    }

    /**
     * Waits until every currently dispatched transmission has completed, or the given timeout elapses.
     *
     * @param timeout The maximum time to wait, or {@code null} to wait indefinitely
     * @return {@code true} if every pending transmission completed, {@code false} if the timeout elapsed first
     */
    public boolean awaitPendingTransmissions(@Nullable Duration timeout) throws InterruptedException {
        Long timeoutMillis = toMillis(timeout);
        return awaitPendingTransmissions(timeoutMillis == null ? -1 : MILLISECONDS.toNanos(timeoutMillis));
    }

    /**
     * Waits until every currently dispatched transmission has completed, or {@link #closeDeadlineNanos}
     * (if any is in effect) is reached.
     *
     * @return {@code true} if every pending transmission completed, {@code false} if the deadline elapsed first
     */
    private boolean awaitUntilCloseDeadline() throws InterruptedException {
        long deadline = closeDeadlineNanos;
        if (deadline == NO_DEADLINE) {
            return awaitPendingTransmissions(-1);
        }
        // Clamped at zero: a deadline already elapsed (the final flush having spent the whole budget waiting
        // for a transmission permit) would otherwise be passed down as a negative value, which means "wait
        // indefinitely" there and would turn a bounded close into an unbounded one at the worst moment.
        return awaitPendingTransmissions(Math.max(0, deadline - System.nanoTime()));
    }

    /**
     * Waits until every currently dispatched transmission has completed, or the given time elapses.
     *
     * @param timeoutNanos The maximum time to wait, in nanoseconds, or a negative value to wait indefinitely
     * @return {@code true} if every pending transmission completed, {@code false} if the timeout elapsed first
     */
    private boolean awaitPendingTransmissions(long timeoutNanos) throws InterruptedException {
        if (timeoutNanos < 0) {
            transmissionPermits.acquire(maxConcurrentTransmissions);
            transmissionPermits.release(maxConcurrentTransmissions);
            return true;
        }
        if (transmissionPermits.tryAcquire(maxConcurrentTransmissions, timeoutNanos, NANOSECONDS)) {
            transmissionPermits.release(maxConcurrentTransmissions);
            return true;
        }
        return false;
    }

    /**
     * Converts the given timeout to milliseconds, clamped to {@link #MAX_TIMEOUT_MILLIS} rather than letting
     * {@link Duration#toMillis()} throw out of a shutdown path.
     *
     * <p>
     * Package-private rather than private so a test can pin the clamping directly: the difference between
     * the two outcomes it decides between is a year of waiting versus an unbounded one, which no test can
     * tell apart by observing a call.
     *
     * @param timeout The timeout to convert, or {@code null}
     * @return The timeout in milliseconds (never negative), or {@code null} to wait indefinitely
     */
    static @Nullable Long toMillis(@Nullable Duration timeout) {
        if (timeout == null) {
            return null;
        }
        try {
            return Math.clamp(timeout.toMillis(), 0, MAX_TIMEOUT_MILLIS);
        } catch (ArithmeticException e) {
            // Too large to even be expressed in milliseconds, so necessarily past the clamp above. Returning
            // null here instead would read as "wait indefinitely", turning the largest timeout a caller can
            // ask for into the one value that never times out: close() would leave no deadline in effect,
            // so a transport whose future never completes would block it, and any JVM shutdown behind it,
            // for good -- the very outcome asking for a timeout at all is meant to rule out.
            return MAX_TIMEOUT_MILLIS;
        }
    }

}
