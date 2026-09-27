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

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

/**
 * Generic accumulate, batch, transmit and retry engine, knowing nothing of Log4j2 nor of the shape of an
 * element: {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery} wires it to log documents and a
 * {@link LogDeliveryListener}.
 * <p>
 * Elements are stacked until a threshold is reached, or {@link #flush()} is called, and the batch is then
 * handed to the transport function on a virtual thread of its own, so that a slow transmission never delays
 * the next batch. At most {@code maxConcurrentTransmissions} run at once, the calling thread waiting for one
 * to complete past that.
 * <p>
 * The transport returns a future, completed right away or later by an asynchronous client, with a
 * {@link TransmissionResult}. What failed is put back at the front of the batch, and retried once a
 * {@link BackoffPolicy} allows it: on the next flush while batching, rather than on every new element. A
 * {@link TransmissionResult.PartialFailure} retries only the elements it rejected.
 * <p>
 * Safety limits keep a struggling transport, or an element it never accepts, from growing memory or wedging
 * the pipeline: the pending batch is capped at {@code maxPendingElements}, and at {@code maxPendingBytes}
 * given an {@code elementSize} measurement, its oldest elements being discarded past them, and elements
 * failing {@code maxConsecutiveFailures} times in a row are discarded, the streak counting their own
 * failures alone. Every discard is reported through {@link BatcherListener#onDropped(List, String)}. A batch
 * bigger than {@code maxRequestElements}, or {@code maxRequestBytes}, is split into chunks transmitted and
 * retried independently.
 * <p>
 * Instances are created through {@link #builder()}, every setting but the transport function having a usable
 * default.
 *
 * @param <T> The type of element accumulated and transmitted
 */
public class Batcher<T> {

    /**
     * The status logger of Log4j2 rather than an application logger: an appender built on this engine is
     * normally attached to the root logger, where a diagnostic logged the ordinary way comes back as one more
     * element to transmit, whose own failure logs again, in a loop starting when the downstream system goes
     * down. Visible with {@code -Dlog4j2.debug}, a {@code status} attribute on the configuration, or a
     * {@code <StatusListener>}.
     */
    private static final Logger log = StatusLogger.getLogger();

    /**
     * Maximum initial capacity reserved for a new batch, to avoid over-allocating for a large threshold.
     */
    private static final int MAX_INITIAL_BATCH_CAPACITY = 1_024;

    /**
     * Value of {@link #retryNotBeforeNanos} meaning no backoff is in effect, rather than {@code 0}, which
     * {@link System#nanoTime()} can return as well as any other value.
     */
    private static final long NO_BACKOFF = Long.MIN_VALUE;

    /**
     * Value of {@link #closeDeadlineNanos} meaning no deadline is in effect, for the same reason as
     * {@link #NO_BACKOFF}.
     */
    private static final long NO_DEADLINE = Long.MIN_VALUE;

    /**
     * Upper bound of a close timeout, so that converting it to nanoseconds cannot overflow into a deadline
     * already elapsed. A year is indistinguishable from waiting indefinitely.
     */
    private static final long MAX_TIMEOUT_MILLIS = Duration.ofDays(365).toMillis();

    /**
     * How many notifications of {@link #notifyDropped(List, String)} may be in flight at once, the next ones
     * running on the calling thread, so that a slow listener cannot pile them up without bound.
     */
    private static final int MAX_CONCURRENT_DROP_NOTIFICATIONS = 4;

    /**
     * How long {@link #acquireTransmissionPermit()} waits at a time while no close is in progress, before
     * looking again whether one began.
     */
    private static final long PERMIT_WAIT_SLICE_NANOS = Duration.ofSeconds(1).toNanos();

    private final LongSupplier flushThreshold;
    private final IntSupplier maxPendingElements;
    private final IntSupplier maxRequestElements;
    private final IntSupplier maxConsecutiveFailures;
    private final LongSupplier maxPendingBytes;
    private final LongSupplier maxRequestBytes;

    /**
     * Measures one element, or {@code null} not to track sizes, leaving both byte limits inert. Fixed at
     * construction, unlike the limits: turning it on midway would leave {@link #pendingBytes} describing part
     * of the batch.
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
     * Number of consecutive failed attempts of the {@link #pendingFailureCount} leading elements of
     * {@link #batch}, which {@link #requeue(List, int)} put back there, the higher streak being kept when two
     * are merged. Guarded by the monitor of this instance, like the batch.
     */
    private int pendingFailureStreak;

    /**
     * How many leading elements of {@link #batch} earned {@link #pendingFailureStreak}, {@code 0} meaning none
     * is left: a streak belongs to the elements that failed, not to the batch holding them, and both trims cut
     * from the front, where {@link #requeue(List, int)} puts them. Guarded by the monitor of this instance.
     */
    private int pendingFailureCount;

    /**
     * Estimated total size of {@link #batch}, kept up to date rather than summed on demand, which would make
     * every {@link #add(List)} walk the whole batch. Always {@code 0} without {@link #elementSize}. Guarded by
     * the monitor of this instance.
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
     * Bounds the drop notifications handed to {@link #transmissionExecutor} at once. Separate from
     * {@link #transmissionPermits}, so that a notification never takes the slot of a transmission, nor makes
     * {@link #awaitPendingTransmissions()} wait for a listener.
     */
    private final Semaphore dropNotificationPermits = new Semaphore(MAX_CONCURRENT_DROP_NOTIFICATIONS);

    /**
     * Whether the last completed transmission attempt failed, so that a failing transport is not retried on
     * every new element while batching (see {@link #isTransmissionFailing()}).
     */
    private volatile boolean transmissionFailed;

    /**
     * The {@link System#nanoTime()} reading before which no transmission is attempted, as decided by
     * {@link #backoff} after the last failure, or {@link #NO_BACKOFF} if none is in effect.
     */
    private volatile long retryNotBeforeNanos = NO_BACKOFF;

    /**
     * Whether {@link #close(Duration)} was called, after which no element is accepted, since nothing would
     * transmit or report it. Raised before the close first takes the monitor of this instance, so that
     * {@link #add(List)} reading it unset under the monitor knows {@link #reportRemainingAsDropped()} will
     * still see what it stacks.
     */
    private volatile boolean closed;

    /**
     * Whether {@link #reportRemainingAsDropped()} emptied {@link #batch} for good, past which
     * {@link #requeue(List, int)} reports its elements rather than merging them into a batch nobody looks at.
     * Guarded by the monitor of this instance.
     */
    private boolean drained;

    /**
     * The {@link System#nanoTime()} reading past which {@link #close(Duration)} stops waiting, or
     * {@link #NO_DEADLINE}. Also bounds the wait of {@link #dispatchChunk(List, int)} for a permit, which would
     * otherwise block the close forever while every permit is held by a transmission that never completes.
     */
    private volatile long closeDeadlineNanos = NO_DEADLINE;

    /**
     * Creates a new batching engine from the given builder.
     *
     * @param builder The builder carrying the settings to use
     */
    private Batcher(Builder<T> builder) {
        if (builder.maxConcurrentTransmissions < 1) {
            // No permit to hand out, every dispatch would block forever
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
     * Builder for {@link Batcher}, preferred over a constructor taking every setting positionally, most of them
     * being same-typed suppliers and counts, easy to swap by mistake.
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
         * Sets how to measure one element, enabling the two byte limits above, which do nothing without it.
         * Unset by default, measuring costing something for every element: set it along with one of them.
         * <p>
         * Expected to be cheap and free of side effects, being called for every element on the way in, and
         * again when a batch is split for transmission. A negative result counts as zero, and so does one
         * throwing, which is logged, so that a faulty measurement only makes the limits less accurate.
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
         * Sets how long to hold off transmissions after a failure. Defaults to {@link BackoffPolicy#none()}.
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
         * Sets the transport function, called with a batch (or chunk) to transmit, as an unmodifiable list;
         * it may complete the returned future synchronously or asynchronously, and is not expected to block
         * the calling thread either way. Required, having no sensible default.
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
        long threshold = readLimit(flushThreshold, Long.MAX_VALUE, "the flush threshold");
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
            // Read under the monitor, which close() only takes after raising it (see closed)
            rejected = closed;
            if (!rejected) {
                batch.addAll(elements);
                pendingBytes += sizeOf(elements);
                // Read once and reused below, so the two readings cannot disagree.
                long threshold = readLimit(flushThreshold, Long.MAX_VALUE, "the flush threshold");
                log.trace("Stacking batch {}/{}", batch.size(), threshold);

                // Capped here too, and not only when requeuing, since nothing may be failing
                maxPending = readLimit(maxPendingElements, Integer.MAX_VALUE, "the maximum pending elements");
                discarded = BoundedTrim.trim(batch, maxPending);
                pendingBytes -= sizeOf(discarded);
                oversized = trimToPendingBytes();
                // Both trims cut from the front, where the elements that earned a streak sit
                dropFailedPrefix(size(discarded) + size(oversized));

                boolean batchingEnabled = threshold > 1;
                // Never an empty batch, which a threshold of 0 or less would otherwise send
                boolean thresholdReached = !batch.isEmpty() && batch.size() >= threshold;
                if (thresholdReached && !(batchingEnabled && transmissionFailed) && !isBackingOff()) {
                    snapshot = swapBatch();
                }
            }
        }
        if (rejected) {
            log.warn("Discarding {} {}(s) submitted after this engine was closed", elements.size(), elementNoun);
            List<T> copy = List.copyOf(elements);
            // On the calling thread, the executor being shut down by then
            notifyListener(() -> listener.onDropped(copy, "submitted after close"));
            return;
        }
        // Off the calling thread where possible (see notifyDropped())
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
     * Whether the most recently completed transmission attempt failed, a health signal a caller can poll,
     * from a readiness endpoint for instance.
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
        // The difference, not the readings, being the only way to order two System.nanoTime() values
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
            log.debug("Sending partial batch of {}/{}", batch.size(), readLimit(flushThreshold, Long.MAX_VALUE, "the flush threshold"));
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
     * Replaces the current batch with an empty one and returns its content with the failure streak it
     * carried, so that accumulating the next batch resumes while this one is transmitted.
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
     * Measures one element through {@link #elementSize}, or returns {@code 0} if sizes are not tracked. A
     * negative result, or a measurement throwing, counts as weightless: a byte limit going unenforced is better
     * than losing the element over a faulty estimate.
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
            // Throwable: measuring a self-referencing element overflows the stack, which would escape add()
            // between appending the elements and accounting for them
            log.warn("Unhandled exception thrown while measuring a {}, counting it as empty", elementNoun, e);
            return 0;
        }
    }

    /**
     * Reads one of the dynamic limits, a supplier throwing leaving the limit unenforced for now: these
     * suppliers are application code reading a configuration, and the request limits are read once a batch
     * was swapped out, which an exception would otherwise carry away.
     * <p>
     * The fallback is always the value meaning unbounded or disabled, never one that discards: going
     * unenforced for a while costs memory or an oversized request, where the alternative costs elements.
     *
     * @param limit      The supplier to read
     * @param unenforced The value to fall back to, leaving the limit unenforced
     * @param name       The name of the limit, used to phrase the warning logged
     * @return The limit, or {@code unenforced} if the supplier threw
     */
    private long readLimit(LongSupplier limit, long unenforced, String name) {
        try {
            return limit.getAsLong();
        } catch (Throwable e) {
            log.warn("Unhandled exception thrown while reading {}, leaving it unenforced for now", name, e);
            return unenforced;
        }
    }

    /**
     * Reads one of the dynamic count limits (see {@link #readLimit(LongSupplier, long, String)}).
     *
     * @param limit      The supplier to read
     * @param unenforced The value to fall back to, leaving the limit unenforced
     * @param name       The name of the limit, used to phrase the warning logged
     * @return The limit, or {@code unenforced} if the supplier threw
     */
    private int readLimit(IntSupplier limit, int unenforced, String name) {
        try {
            return limit.getAsInt();
        } catch (Throwable e) {
            log.warn("Unhandled exception thrown while reading {}, leaving it unenforced for now", name, e);
            return unenforced;
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
     * {@code maxPendingBytes}, the counterpart by size of the cap {@link BoundedTrim} applies by count.
     * <p>
     * Must be called while holding the monitor of this instance.
     *
     * @return The elements discarded, or {@code null} if none were
     */
    private @Nullable List<T> trimToPendingBytes() {
        long maxBytes = readLimit(maxPendingBytes, Long.MAX_VALUE, "the maximum pending bytes");
        if (elementSize == null || maxBytes <= 0 || pendingBytes <= maxBytes) {
            return null;
        }

        int index = 0;
        while (index < batch.size() && pendingBytes > maxBytes) {
            pendingBytes -= sizeOf(batch.get(index++));
        }
        List<T> excess = batch.subList(0, index);
        // Copied before clearing, a sublist being a view of the range it covers
        List<T> discarded = List.copyOf(excess);
        excess.clear();
        if (batch.isEmpty()) {
            // Re-anchored, so that a measurement not perfectly stable cannot drift for the whole lifetime
            pendingBytes = 0;
        }
        if (discarded.isEmpty()) {
            // A total drifting above the limit on an empty batch, which the re-anchoring just healed
            return null;
        }

        log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending size of {} bytes",
                discarded.size(), elementNoun, maxBytes);
        return discarded;
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
                // Nothing would ever transmit or report them: a transmission, or a dispatch that got no permit
                // within the close budget, handing its elements back once close() drained the batch
                abandoned = List.copyOf(elements);
            } else {
                List<T> merged = new ArrayList<>(elements.size() + batch.size());
                merged.addAll(elements);
                merged.addAll(batch);
                // Measured again, having left the running total when swapped out
                pendingBytes += sizeOf(elements);

                maxPending = readLimit(maxPendingElements, Integer.MAX_VALUE, "the maximum pending elements");
                discarded = BoundedTrim.trim(merged, maxPending);
                batch = merged;
                pendingBytes -= sizeOf(discarded);
                oversized = trimToPendingBytes();

                // Both trims cut from the front, where the requeued elements were just put, so anything cut
                // past them ate into the failed region already behind them.
                int discardedFromFront = size(discarded) + size(oversized);
                dropFailedPrefix(Math.max(0, discardedFromFront - elements.size()));

                int keptRequeued = Math.max(0, elements.size() - discardedFromFront);
                if (keptRequeued > 0) {
                    // The batch may carry the streak of another chunk requeued moments earlier: the higher
                    // one is kept, the merged elements being at least as suspect as the worst of them
                    pendingFailureStreak = Math.max(pendingFailureStreak, failureStreak);
                    pendingFailureCount += keptRequeued;
                }
            }
        }
        // Outside the monitor, which accumulating the next batch needs, and directly: this runs where a
        // transmission completed, or where a dispatch failed while closing
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
     * Notifies {@link #listener} of dropped elements on a virtual thread, so that a slow listener does not
     * stall {@link #add(List)}, its caller. At most {@link #MAX_CONCURRENT_DROP_NOTIFICATIONS} run at once:
     * past that, or once the executor is shut down, the notification runs on the calling thread, so that a
     * slow listener cannot pile notifications, and the elements they hold, up without bound under the very
     * overload causing them. Not tracked by {@link #transmissionPermits}, so closing does not wait for it.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    private void notifyDropped(List<T> elements, String reason) {
        if (!dropNotificationPermits.tryAcquire()) {
            // Every slot is held by a listener yet to return: backpressure rather than one more thread
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
     * Dispatches the given elements for transmission, split first into chunks within the request limits if
     * needed, each chunk being handed to {@link #dispatchChunk(List, int)} independently.
     *
     * @param elements      The elements to dispatch, possibly more than one request's worth
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted, inherited by every chunk split off from it
     */
    private void dispatch(List<T> elements, int failureStreak) {
        int maxCount = readLimit(maxRequestElements, Integer.MAX_VALUE, "the maximum request elements");
        long maxBytes = readLimit(maxRequestBytes, Long.MAX_VALUE, "the maximum request bytes");
        boolean countLimited = maxCount > 0 && elements.size() > maxCount;
        // A single element goes out on its own whatever it weighs, so it is not measured
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
            // Never cut before the first element of a chunk, so that one heavier than the limit goes out alone
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
     * Dispatches a single chunk for transmission on a virtual thread of its own, once a transmission permit
     * is acquired, which may block the caller where the transmission itself never does.
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
     * Acquires one transmission permit, waiting indefinitely, or until {@link #closeDeadlineNanos} while a
     * bounded {@link #close(Duration)} is in progress. The wait is taken in slices of
     * {@link #PERMIT_WAIT_SLICE_NANOS}, so that a close beginning during it still bounds it.
     *
     * @return {@code true} if a permit was acquired, {@code false} if none was free by the close deadline
     */
    private boolean acquireTransmissionPermit() throws InterruptedException {
        while (true) {
            long deadline = closeDeadlineNanos;
            if (deadline != NO_DEADLINE) {
                // Still tried once the budget is spent: a zero wait does not wait, but takes a free permit
                return transmissionPermits.tryAcquire(Math.max(0, deadline - System.nanoTime()), NANOSECONDS);
            }
            if (transmissionPermits.tryAcquire(PERMIT_WAIT_SLICE_NANOS, NANOSECONDS)) {
                return true;
            }
        }
    }

    /**
     * Calls the transport function and handles the result once its future completes, on whichever thread
     * completes it.
     *
     * @param elements      The chunk to transmit
     * @param failureStreak The number of consecutive times this exact content has already failed to be
     *                      transmitted
     */
    private void transmit(List<T> elements, int failureStreak) {
        long start = System.nanoTime();
        CompletableFuture<TransmissionResult<T>> future;
        try {
            // Unmodifiable, since what the transport is handed is what gets retried or reported if it fails
            CompletableFuture<TransmissionResult<T>> applied = transport.apply(Collections.unmodifiableList(elements));
            future = applied != null ? applied : CompletableFuture.completedFuture(null);
        } catch (Throwable e) {
            // Throwable: an Error, from a class of the transport missing say, would otherwise skip the release
            // of the permit below, and a few of them wedge every later dispatch
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((result, throwable) -> {
            try {
                long durationMillis = (System.nanoTime() - start) / 1_000_000;
                handleResult(elements, failureStreak, normalizeResult(elements, result, throwable), durationMillis);
            } catch (Throwable e) {
                // Defensive: a bug in handleResult(), or a result inconsistent with the chunk, degrades it to
                // a failure rather than losing it without a trace
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
     * Turns what the future of the transport completed with into a {@link TransmissionResult}, an exceptional
     * completion or a {@code null} result becoming a {@link TransmissionResult.Failure}.
     *
     * @param elements  The chunk that was transmitted, only used to phrase the warning logged
     * @param result    The result the future completed with, or {@code null} if it completed exceptionally
     *                  or with {@code null} itself
     * @param throwable The exception the future completed with, or {@code null} if it completed normally
     * @return A non-{@code null} result
     */
    private TransmissionResult<T> normalizeResult(List<T> elements, @Nullable TransmissionResult<T> result, @Nullable Throwable throwable) {
        if (throwable != null) {
            // A future derived by the transport, through thenApply() for instance, wraps the failure
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
     * Handles a transmission result: notifies {@link #listener} of what succeeded and failed, and retries or
     * gives up on what failed through {@link #onTransmissionFailure(List, int)}, which for a
     * {@link TransmissionResult.PartialFailure} is only the elements it rejected, matched by reference.
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
                onTransmissionFailure(elements, failureStreak);
            }
            case TransmissionResult.PartialFailure<T> partial -> {
                Partition<T> partition = partition(elements, partial.rejected());
                List<T> rejected = partition.rejected();
                if (rejected.size() != partial.rejected().size()) {
                    // A transport breaking the contract, with duplicates or foreign elements, which would
                    // otherwise be injected into the batch: only those belonging to the chunk are kept
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
     * Splits {@code elements} according to whether each is in {@code rejected}, matched by reference rather
     * than {@code equals()} (see {@link TransmissionResult}). Driven by {@code elements}, so that the result
     * only holds elements of the chunk, each as many times as the chunk does.
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
     * Invokes the given listener notification, logging whatever it throws, so that a throwing
     * {@code onFailed} never skips the {@link #onTransmissionFailure(List, int)} following it.
     *
     * @param notification The listener call to perform
     */
    private void notifyListener(Runnable notification) {
        try {
            notification.run();
        } catch (Throwable e) {
            // Throwable: a listener serializing a self-referencing element overflows the stack
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
        int maxFailures = readLimit(maxConsecutiveFailures, 0, "the maximum consecutive failures");
        int newFailureStreak = failureStreak + 1;
        if (maxFailures > 0 && newFailureStreak >= maxFailures) {
            log.error("Giving up on {} {}(s) after {} consecutive failed transmission attempts, they will be discarded",
                    elements.size(), elementNoun, newFailureStreak);
            // Directly, under the permit the transmission still holds, so that awaitPendingTransmissions()
            // waits for it too
            notifyListener(() -> listener.onDropped(List.copyOf(elements), "maximum consecutive transmission failures exceeded"));
            return;
        }

        applyBackoff(newFailureStreak);
        requeue(elements, newFailureStreak);
    }

    /**
     * Holds off the next transmission for as long as {@link #backoff} decides for the given failure streak,
     * only ever pushing the deadline further out: a shorter delay, of another chunk failing for the first
     * time, must not cut short a longer one.
     *
     * @param consecutiveFailures The number of consecutive failures the pending batch has now reached
     */
    private void applyBackoff(int consecutiveFailures) {
        long delayMillis;
        try {
            delayMillis = backoff.delayMillis(consecutiveFailures);
        } catch (Throwable e) {
            // Throwable: anything escaping would skip the requeue() keeping the elements
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
     * Flushes the current batch and waits for every pending transmission, that final one included, to
     * complete, so that the resources of the transport function can be released right after.
     * <p>
     * What is still pending once that wait is over, put back by a failing final transmission or held by one
     * not completed in time, is reported through {@link BatcherListener#onDropped(List, String)}, giving a
     * dead-letter sink a last chance to save it.
     *
     * @param timeout The maximum time to wait for pending transmissions to complete, or {@code null} to wait
     *                indefinitely, which is only safe for a transport always completing its future
     */
    public void close(@Nullable Duration timeout) {
        closed = true;
        Long timeoutMillis = toMillis(timeout);
        if (timeoutMillis != null) {
            closeDeadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(timeoutMillis);
        }
        try {
            // Forced, this last attempt going out whatever the backoff
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
     * Reports what is left in the pending batch once closed as dropped, on the calling thread, the executor
     * being shut down by then.
     */
    private void reportRemainingAsDropped() {
        List<T> remaining;
        synchronized (this) {
            // Raised for an empty batch too, so that a later requeue reports its elements
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
     * Waits, indefinitely, until every transmission dispatched so far has completed, so that the resources of
     * the transport function can be released right after.
     *
     * @throws InterruptedException If the current thread is interrupted while waiting
     */
    public void awaitPendingTransmissions() throws InterruptedException {
        awaitPendingTransmissions(null);
    }

    /**
     * Waits until every currently dispatched transmission has completed, or the given timeout elapses.
     *
     * @param timeout The maximum time to wait, or {@code null} to wait indefinitely
     * @return {@code true} if every pending transmission completed, {@code false} if the timeout elapsed first
     * @throws InterruptedException If the current thread is interrupted while waiting
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
        // Clamped at zero, a negative timeout meaning to wait indefinitely below
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
     * {@link Duration#toMillis()} throw out of a shutdown path. Package-private for a test to pin the clamp,
     * which no call can be observed telling apart from waiting indefinitely.
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
            // Past the clamp above, where null would mean waiting indefinitely
            return MAX_TIMEOUT_MILLIS;
        }
    }

}
