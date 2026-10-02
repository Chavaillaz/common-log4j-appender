package com.chavaillaz.appender.internal;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.DeliveryHealth;
import com.chavaillaz.appender.TransmissionResult;

/**
 * Generic accumulate, batch, transmit and retry engine, knowing nothing of Log4j2 nor of the shape of an
 * element: {@link com.chavaillaz.appender.log4j.AbstractBatchLogDelivery} wires it to log documents and a
 * {@link com.chavaillaz.appender.LogDeliveryListener}.
 * <p>
 * Elements are stacked until a threshold is reached, or {@link #flush()} is called, and the batch is then
 * handed to the transport function on a virtual thread of its own, so that a slow transmission never delays
 * the next batch. At most {@code maxConcurrentTransmissions} run at once, the calling thread waiting for one
 * to complete past that.
 * <p>
 * The transport returns a future, completed right away or later by an asynchronous client, with a
 * {@link TransmissionResult}, handled on a virtual thread rather than on the thread of the client completing
 * it. What failed is put back at the front of the batch, and retried once a
 * {@link BackoffPolicy} allows it: on the next flush while batching, rather than on every new element. A
 * {@link TransmissionResult.PartialFailure} retries only the elements it rejected.
 * <p>
 * Safety limits keep a struggling transport, or an element it never accepts, from growing memory or wedging
 * the pipeline: the pending batch is capped at {@code maxPendingElements}, and at {@code maxPendingBytes}
 * given an {@code elementSize} measurement. A batch reaching one of them is transmitted as if it reached the
 * threshold, or loses its oldest elements past it while transmissions are held off after a failure. Elements
 * failing {@code maxConsecutiveFailures} times in a row are discarded, the streak counting their own failures
 * alone. Every discard is reported through {@link BatcherListener#onDropped(List, String)}. A batch bigger
 * than {@code maxRequestElements}, or {@code maxRequestBytes}, is split into chunks transmitted and retried
 * independently.
 * <p>
 * The pending batch, with the failure streaks of its elements and the trims bounding it, is a
 * {@link PendingBatch} guarded by a lock: this class decides when it is due, and handles its transmissions,
 * their results and the close.
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

    /**
     * The most bytes one element counts for: more than a request can carry, a Java array holding at most 2 GiB,
     * and little enough that the size of a batch holding as many elements as a collection can never overflow.
     */
    private static final long MAX_ELEMENT_BYTES = Integer.MAX_VALUE;

    private final IntSupplier flushThreshold;
    private final IntSupplier maxPendingElements;
    private final IntSupplier maxRequestElements;
    private final IntSupplier maxConsecutiveFailures;
    private final LongSupplier maxPendingBytes;
    private final LongSupplier maxRequestBytes;

    /**
     * Measures one element, or {@code null} not to track sizes, leaving both byte limits inert. Fixed at
     * construction, unlike the limits: turning it on midway would leave the estimated size of the pending
     * batch describing part of it.
     */
    private final @Nullable ToLongFunction<T> elementSize;

    private final BackoffPolicy backoff;
    private final String elementNoun;
    private final Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport;
    private final BatcherListener<T> listener;

    /**
     * Guards {@link #pending}, and the state deciding what happens to it, from the thread accumulating it and
     * those handling the results of transmissions. A lock rather than the monitor of this instance, those being
     * virtual threads: on Java 21, one blocked entering a monitor keeps its carrier, so that those waiting their
     * turn could hold every carrier and stall every virtual thread of the application, where one waiting for a
     * lock unmounts.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * The elements to be sent with the next transmission, swapped for an empty batch once due. Guarded by
     * {@link #lock}.
     */
    private PendingBatch<T> pending;

    /**
     * Executor transmitting batches on virtual threads, so that transmission never blocks the thread
     * accumulating the next batch. They take neither the inheritable thread-locals nor the context class loader
     * of the thread starting them, the one closing a web application for instance, which one stuck on an
     * unresponsive transport would keep reachable for good.
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
     * {@link #awaitPendingTransmissions()} wait for a listener: only {@link #close(Duration)} waits for them.
     */
    private final Semaphore dropNotificationPermits = new Semaphore(MAX_CONCURRENT_DROP_NOTIFICATIONS);

    /**
     * Elements the transport accepted, for {@link #metrics()}.
     */
    private final LongAdder transmittedElements = new LongAdder();

    /**
     * Transmission attempts that failed, in whole or in part, for {@link #metrics()}.
     */
    private final LongAdder failedTransmissions = new LongAdder();

    /**
     * Elements discarded for good, whatever the reason, for {@link #metrics()}.
     */
    private final LongAdder droppedElements = new LongAdder();

    /**
     * The transmissions dispatched and not handled yet, so that closing can report those still in flight once
     * its wait is over. Whoever removes one first handles its elements: its result, a dispatch failing to
     * start it, or the close, after which its result is ignored.
     */
    private final Set<Chunk<T>> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Whether the last completed transmission attempt failed, so that a failing transport is not retried on
     * every new element while batching, and for {@link #health()}.
     */
    private volatile boolean transmissionFailed;

    /**
     * The {@link System#nanoTime()} reading before which no transmission is attempted, as decided by
     * {@link #backoff} after the last failure, or {@link #NO_BACKOFF} if none is in effect.
     */
    private volatile long retryNotBeforeNanos = NO_BACKOFF;

    /**
     * The {@link System#nanoTime()} reading when the transport last accepted elements, or when this engine was
     * built while it accepted none yet, for {@link #health()}.
     */
    private volatile long lastSuccessNanos = System.nanoTime();

    /**
     * Whether {@link #close(Duration)} was called, after which no element is accepted, since nothing would
     * transmit or report it. Raised before the close first takes {@link #lock}, so that {@link #add(List)}
     * reading it unset under the lock knows {@link #reportRemainingAsDropped()} will still see what it stacks.
     */
    private volatile boolean closed;

    /**
     * Whether {@link #reportRemainingAsDropped()} emptied {@link #pending} for good, past which
     * {@link #requeue(Chunk)} reports its elements rather than merging them into a batch nobody looks at.
     * Guarded by {@link #lock}.
     */
    private boolean drained;

    /**
     * The {@link System#nanoTime()} reading past which {@link #close(Duration)} stops waiting, or
     * {@link #NO_DEADLINE}. Also bounds the wait of {@link #dispatchChunk(Chunk)} for a permit, which would
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
        this.pending = newPendingBatch();
        this.transmissionPermits = new Semaphore(builder.maxConcurrentTransmissions);
        this.transmissionExecutor = Executors.newThreadPerTaskExecutor(builder.transmissionThreadFactory != null
                ? builder.transmissionThreadFactory
                : Thread.ofVirtual().name(builder.transmissionThreadPrefix, 0).inheritInheritableThreadLocals(false).factory());
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

        private IntSupplier flushThreshold = () -> 1;
        private IntSupplier maxPendingElements = () -> 10_000;
        private IntSupplier maxRequestElements = () -> Integer.MAX_VALUE;
        private IntSupplier maxConsecutiveFailures = () -> 5;
        private LongSupplier maxPendingBytes = () -> Long.MAX_VALUE;
        private LongSupplier maxRequestBytes = () -> Long.MAX_VALUE;
        private @Nullable ToLongFunction<T> elementSize;
        private int maxConcurrentTransmissions = 4;
        private BackoffPolicy backoff = BackoffPolicy.none();
        private String transmissionThreadPrefix = "batcher-transmission-";
        private @Nullable ThreadFactory transmissionThreadFactory;
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
        public Builder<T> flushThreshold(IntSupplier flushThreshold) {
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
         * Sets the maximum number of consecutive failed attempts allowed for the same elements before they
         * are discarded, a non-positive value disabling this limit, re-read on every call. Defaults to
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
         * throwing, which is logged, so that a faulty measurement only makes the limits less accurate. A result
         * past 2 GiB counts as 2 GiB, more than any request carries.
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
         * Replaces the factory of the virtual threads named after {@link #transmissionThreadPrefix(String)},
         * so that a test can make starting one fail.
         *
         * @param transmissionThreadFactory The factory of the threads running transmissions and notifications
         * @return This builder
         */
        Builder<T> transmissionThreadFactory(ThreadFactory transmissionThreadFactory) {
            this.transmissionThreadFactory = transmissionThreadFactory;
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
         * the calling thread either way. A future completed later is handled on a virtual thread of this
         * engine, never on the thread completing it. Required, having no sensible default.
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

    /**
     * Creates an empty batch to accumulate the next elements into, sized for the current flush threshold.
     *
     * @return The new batch
     */
    private PendingBatch<T> newPendingBatch() {
        int threshold = readLimit(flushThreshold, Integer.MAX_VALUE, "the flush threshold");
        int capacity = threshold > 0 ? Math.min(threshold, MAX_INITIAL_BATCH_CAPACITY) : 16;
        return new PendingBatch<>(elementSize != null ? this::sizeOf : null, capacity);
    }

    /**
     * Accumulates the given elements, triggering a transmission if this reaches {@code flushThreshold}, or
     * fills the pending batch up to one of its limits. A transmission this triggers runs on a virtual thread of
     * its own, the caller only waiting for one of those in flight to complete past
     * {@code maxConcurrentTransmissions}.
     *
     * @param elements The elements to accumulate
     * @throws NullPointerException If one of the elements is {@code null}
     */
    public void add(List<T> elements) {
        for (T element : elements) {
            // Checked before anything changes, the batch refusing a null halfway through the others
            Objects.requireNonNull(element, "elements must not contain null");
        }
        PendingBatch<T> due = null;
        List<T> discarded = null;
        List<T> oversized = null;
        boolean rejected;
        int maxPending = 0;
        long maxBytes = 0;
        lock.lock();
        try {
            // Read under the lock, which close() only takes after raising it (see closed)
            rejected = closed;
            if (!rejected) {
                pending.append(elements);
                // Each read once, so that two readings cannot disagree
                int threshold = readLimit(flushThreshold, Integer.MAX_VALUE, "the flush threshold");
                maxPending = readLimit(maxPendingElements, Integer.MAX_VALUE, "the maximum pending elements");
                maxBytes = readLimit(maxPendingBytes, Long.MAX_VALUE, "the maximum pending bytes");
                log.trace("Stacking batch {}/{}", pending.size(), threshold);

                // Due once it reaches the threshold, or fills up to a pending limit, which it would otherwise
                // meet by discarding its oldest elements however healthy the transport
                boolean batchDue = !pending.isEmpty() && (pending.size() >= threshold || pending.size() >= maxPending
                        || (maxBytes > 0 && pending.bytes() >= maxBytes));
                // After a failure while batching, the next flush retries rather than every new element
                boolean heldOff = (threshold > 1 && transmissionFailed) || isBackingOff();
                if (batchDue && !heldOff) {
                    due = swapBatch();
                } else {
                    discarded = pending.trimToCount(maxPending);
                    oversized = pending.trimToBytes(maxBytes);
                }
            }
        } finally {
            lock.unlock();
        }
        if (rejected) {
            log.warn("Discarding {} {}(s) submitted after this engine was closed", elements.size(), elementNoun);
            List<T> copy = List.copyOf(elements);
            // On the calling thread, the executor being shut down by then
            drop(copy, "submitted after close");
            return;
        }
        // Off the calling thread where possible (see notifyDropped())
        if (discarded != null) {
            log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending {}s of {} while transmissions are held off",
                    discarded.size(), elementNoun, elementNoun, maxPending);
            notifyDropped(discarded, "maximum pending " + elementNoun + "s exceeded");
        }
        if (oversized != null) {
            logOversized(oversized, maxBytes);
            notifyDropped(oversized, "maximum pending bytes exceeded");
        }
        if (due != null) {
            dispatch(due);
        }
    }

    /**
     * Takes the health of this engine, a signal a caller can poll, from a readiness endpoint for instance:
     * {@link DeliveryHealth.Status#DOWN} once closed, {@link DeliveryHealth.Status#UP} while the last completed
     * transmission attempt succeeded, and otherwise {@link DeliveryHealth.Status#BACKING_OFF} or
     * {@link DeliveryHealth.Status#RETRYING}, whether a backoff delay holds the retry off or not.
     *
     * @return The current health, with the time since the transport last accepted elements
     */
    public DeliveryHealth health() {
        DeliveryHealth.Status status;
        if (closed) {
            status = DeliveryHealth.Status.DOWN;
        } else if (!transmissionFailed) {
            status = DeliveryHealth.Status.UP;
        } else {
            status = isBackingOff() ? DeliveryHealth.Status.BACKING_OFF : DeliveryHealth.Status.RETRYING;
        }
        return new DeliveryHealth(status, Duration.ofNanos(Math.max(0, System.nanoTime() - lastSuccessNanos)));
    }

    /**
     * Whether transmissions are currently held off by {@link #backoff} after a failure, meaning neither a
     * reached threshold nor a {@link #flush()} triggers one until the delay it decided has elapsed.
     *
     * @return {@code true} if a backoff delay is currently in effect
     */
    private boolean isBackingOff() {
        long notBefore = retryNotBeforeNanos;
        // The difference, not the readings, being the only way to order two System.nanoTime() values
        return notBefore != NO_BACKOFF && System.nanoTime() - notBefore < 0;
    }

    /**
     * A snapshot of this engine, for a metrics library to poll.
     *
     * @param pendingElements       Elements in the pending batch, waiting to be transmitted or retried
     * @param pendingBytes          Estimated size of the pending batch, {@code 0} without an {@code elementSize}
     * @param transmissionsInFlight Transmissions dispatched and not completed yet
     * @param transmittedElements   Elements the transport accepted since this engine was built
     * @param failedTransmissions   Transmission attempts that failed, in whole or in part, since then
     * @param droppedElements       Elements discarded for good since then, whatever the reason
     */
    public record Metrics(int pendingElements, long pendingBytes, int transmissionsInFlight,
                          long transmittedElements, long failedTransmissions, long droppedElements) {

    }

    /**
     * Takes a snapshot of this engine: what it holds right now, and what it did since it was built.
     *
     * @return The current metrics
     */
    public Metrics metrics() {
        int pendingElements;
        long pendingSize;
        lock.lock();
        try {
            pendingElements = pending.size();
            pendingSize = pending.bytes();
        } finally {
            lock.unlock();
        }
        int inFlight = Math.max(0, maxConcurrentTransmissions - transmissionPermits.availablePermits());
        return new Metrics(pendingElements, pendingSize, inFlight,
                transmittedElements.sum(), failedTransmissions.sum(), droppedElements.sum());
    }

    /**
     * Sends the pending elements (a partially filled batch). Intended to be called periodically to clean
     * out pending elements. Does nothing while a backoff delay is in effect (see {@link #health()}).
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
        PendingBatch<T> due;
        lock.lock();
        try {
            if (pending.isEmpty()) {
                return;
            }
            if (!force && isBackingOff()) {
                log.debug("Holding off the transmission of {} pending {}(s) while backing off after a failure",
                        pending.size(), elementNoun);
                return;
            }
            log.debug("Sending partial batch of {}/{}", pending.size(), readLimit(flushThreshold, Integer.MAX_VALUE, "the flush threshold"));
            due = swapBatch();
        } finally {
            lock.unlock();
        }
        dispatch(due);
    }

    /**
     * Replaces the pending batch with an empty one and returns it, so that accumulating the next batch
     * resumes while this one is transmitted.
     * <p>
     * Must be called while holding {@link #lock}.
     *
     * @return The batch swapped out, no one else's from then on
     */
    private PendingBatch<T> swapBatch() {
        PendingBatch<T> due = pending;
        pending = newPendingBatch();
        return due;
    }

    /**
     * Measures one element through {@link #elementSize}, or returns {@code 0} if sizes are not tracked. A
     * negative result, or a measurement throwing, counts as weightless: a byte limit going unenforced is better
     * than losing the element over a faulty estimate. A result past {@link #MAX_ELEMENT_BYTES} counts as that
     * much, so that two huge ones cannot add up to a size under the limits, sent in one request.
     *
     * @param element The element to measure
     * @return The estimated size of the element in bytes, between {@code 0} and {@link #MAX_ELEMENT_BYTES}
     */
    private long sizeOf(T element) {
        if (elementSize == null) {
            return 0;
        }
        try {
            return Math.clamp(elementSize.applyAsLong(element), 0, MAX_ELEMENT_BYTES);
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
     * Logs the oldest elements a trim discarded to keep the pending batch within {@code maxPendingBytes}.
     *
     * @param oversized The elements discarded
     * @param maxBytes  The maximum pending size they exceeded
     */
    private void logOversized(List<T> oversized, long maxBytes) {
        log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending size of {} bytes",
                oversized.size(), elementNoun, maxBytes);
    }

    /**
     * Puts back the elements of a failed (or not attempted) transmission at the front of the current batch,
     * so they are retried before any newer element, discarding the oldest elements in excess if this would
     * grow the batch past {@code maxPendingElements} or {@code maxPendingBytes}. Once {@link #close(Duration)}
     * drained the batch, reports them as dropped instead.
     *
     * @param chunk The elements to put back, with their failure streaks: unchanged if they were never
     *              actually attempted, because acquiring a transmission permit was interrupted for instance
     */
    private void requeue(Chunk<T> chunk) {
        List<T> discarded = null;
        List<T> oversized = null;
        List<T> abandoned = null;
        int maxPending = 0;
        long maxBytes = 0;
        lock.lock();
        try {
            if (drained) {
                // Nothing would ever transmit or report them: a transmission, or a dispatch that got no permit
                // within the close budget, handing its elements back once close() drained the batch
                abandoned = chunk.elements();
            } else {
                pending.prepend(chunk);
                maxPending = readLimit(maxPendingElements, Integer.MAX_VALUE, "the maximum pending elements");
                discarded = pending.trimToCount(maxPending);
                maxBytes = readLimit(maxPendingBytes, Long.MAX_VALUE, "the maximum pending bytes");
                oversized = pending.trimToBytes(maxBytes);
            }
        } finally {
            lock.unlock();
        }
        // Outside the lock, which accumulating the next batch needs, and directly: this runs where a
        // transmission completed, or where a dispatch failed while closing
        if (abandoned != null) {
            log.error("Discarding {} {}(s) put back after this engine was closed, they were never successfully transmitted",
                    abandoned.size(), elementNoun);
            drop(abandoned, "still pending when closed");
            return;
        }
        if (discarded != null) {
            log.warn("Discarding {} oldest {}(s) after exceeding the maximum pending {}s of {} while retrying",
                    discarded.size(), elementNoun, elementNoun, maxPending);
            drop(discarded, "maximum pending " + elementNoun + "s exceeded");
        }
        if (oversized != null) {
            logOversized(oversized, maxBytes);
            drop(oversized, "maximum pending bytes exceeded");
        }
    }

    /**
     * Counts dropped elements, and notifies {@link #listener} of them on the calling thread.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    private void drop(List<T> elements, String reason) {
        droppedElements.add(elements.size());
        notifyListener(() -> listener.onDropped(elements, reason));
    }

    /**
     * Counts dropped elements, and notifies {@link #listener} of them on a virtual thread, so that a slow
     * listener does not stall {@link #add(List)}, its caller. At most {@link #MAX_CONCURRENT_DROP_NOTIFICATIONS}
     * run at once: past that, or once no thread starts, the executor being shut down for instance, the
     * notification runs on the calling thread, so that a slow listener cannot pile up notifications, and the
     * elements they hold, under the very overload causing them. Waited for by {@link #close(Duration)}, not by
     * {@link #awaitPendingTransmissions()}.
     *
     * @param elements The elements being discarded
     * @param reason   A short, human-readable explanation of why they were discarded
     */
    private void notifyDropped(List<T> elements, String reason) {
        droppedElements.add(elements.size());
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
        } catch (Throwable e) {
            // Throwable, an Error starting the thread keeping the slot for good otherwise (see dispatchChunk())
            dropNotificationPermits.release();
            if (e instanceof RejectedExecutionException) {
                log.warn("Notifying the listener of {} dropped {}(s) on the calling thread, the engine is shutting down",
                        elements.size(), elementNoun);
            } else {
                log.warn("Notifying the listener of {} dropped {}(s) on the calling thread, unable to start a thread for it",
                        elements.size(), elementNoun, e);
            }
            notifyListener(() -> listener.onDropped(elements, reason));
        }
    }

    /**
     * Dispatches the elements of a swapped batch for transmission, split first into chunks within the request
     * limits if needed (see {@link PendingBatch#split(int, long)}), each chunk being handed to
     * {@link #dispatchChunk(Chunk)} independently.
     *
     * @param due The batch swapped out, no one else's by then
     */
    private void dispatch(PendingBatch<T> due) {
        int maxCount = readLimit(maxRequestElements, Integer.MAX_VALUE, "the maximum request elements");
        long maxBytes = readLimit(maxRequestBytes, Long.MAX_VALUE, "the maximum request bytes");
        for (Chunk<T> chunk : due.split(maxCount, maxBytes)) {
            dispatchChunk(chunk);
        }
    }

    /**
     * Dispatches a single chunk for transmission on a virtual thread of its own, once a transmission permit
     * is acquired, which may block the caller where the transmission itself never does.
     *
     * @param chunk The chunk to dispatch
     */
    private void dispatchChunk(Chunk<T> chunk) {
        try {
            if (!acquireTransmissionPermit()) {
                log.warn("Unable to transmit {} {}(s) within the remaining close budget, they will be reported as dropped",
                        chunk.size(), elementNoun);
                requeue(chunk);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requeue(chunk);
            return;
        }

        // Before it starts, so that a close looking for what is in flight cannot miss it
        inFlight.add(chunk);
        try {
            transmissionExecutor.execute(() -> transmit(chunk));
        } catch (Throwable e) {
            // Throwable: an Error starting the thread, the memory running out say, would keep the permit for
            // good, a few of them wedging every later dispatch, and lose the chunks still to dispatch
            transmissionPermits.release();
            if (!(e instanceof RejectedExecutionException)) {
                log.warn("Unable to start the transmission of {} {}(s), putting them back", chunk.size(), elementNoun, e);
            }
            // Unless the close shutting the executor down reported them already
            if (inFlight.remove(chunk)) {
                requeue(chunk);
            }
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
     * Calls the transport function and handles the result once its future completes: on this virtual thread
     * if the transport completed it before returning, or on a virtual thread of its own otherwise, never on
     * the thread of the client completing it.
     *
     * @param chunk The chunk to transmit, registered as in flight
     */
    private void transmit(Chunk<T> chunk) {
        long start = System.nanoTime();
        CompletableFuture<TransmissionResult<T>> future;
        try {
            // Unmodifiable (see Chunk), what the transport is handed being what gets retried or reported
            CompletableFuture<TransmissionResult<T>> applied = transport.apply(chunk.elements());
            future = applied != null ? applied : CompletableFuture.completedFuture(null);
        } catch (Throwable e) {
            // Throwable: an Error, from a class of the transport missing say, would otherwise skip the release
            // of the permit below, and a few of them wedge every later dispatch
            future = CompletableFuture.failedFuture(e);
        }
        BiConsumer<TransmissionResult<T>, Throwable> handler = (result, throwable) -> {
            try {
                if (!inFlight.remove(chunk)) {
                    log.debug("Ignoring the result of a transmission of {} {}(s) that closing reported as dropped already",
                            chunk.size(), elementNoun);
                    return;
                }
                long durationMillis = (System.nanoTime() - start) / 1_000_000;
                handleResult(chunk, normalizeResult(chunk, result, throwable), durationMillis);
            } catch (Throwable e) {
                // Defensive: a bug in handleResult(), or a result inconsistent with the chunk, degrades it to
                // a failure rather than losing it without a trace
                log.warn("Unhandled exception while processing the result of a transmitted batch of {} {}(s), treating it as a failure",
                        chunk.size(), elementNoun, e);
                notifyListener(() -> listener.onFailed(chunk.size(), e));
                onTransmissionFailure(chunk, e);
            } finally {
                transmissionPermits.release();
            }
        };
        if (future.isDone()) {
            future.whenComplete(handler);
        } else {
            // The thread completing it belongs to the client, an I/O thread serving other requests, which the
            // listener, a dead-letter write or a requeue waiting for the lock must not hold up
            future.whenCompleteAsync(handler, this::executeOffTheCompletingThread);
        }
    }

    /**
     * Runs the handling of a transmission result on a virtual thread of its own, or on the calling thread once
     * that fails, the executor being shut down by a close whose wait is over: a result never handled would
     * lose its elements, and the permit it holds.
     *
     * @param task The handling of the result
     */
    private void executeOffTheCompletingThread(Runnable task) {
        try {
            transmissionExecutor.execute(task);
        } catch (Throwable e) {
            // Throwable: an Error starting the thread would be caught by the future, which then skips the task
            task.run();
        }
    }

    /**
     * Turns what the future of the transport completed with into a {@link TransmissionResult}, an exceptional
     * completion or a {@code null} result becoming a {@link TransmissionResult.Failure}, logged along with the
     * returned ones.
     *
     * @param chunk     The chunk that was transmitted, only used to phrase the warning logged
     * @param result    The result the future completed with, or {@code null} if it completed exceptionally
     *                  or with {@code null} itself
     * @param throwable The exception the future completed with, or {@code null} if it completed normally
     * @return A non-{@code null} result
     */
    private TransmissionResult<T> normalizeResult(Chunk<T> chunk, @Nullable TransmissionResult<T> result, @Nullable Throwable throwable) {
        if (throwable != null) {
            // A future derived by the transport, through thenApply() for instance, wraps the failure
            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                    ? throwable.getCause() : throwable;
            return TransmissionResult.failure(cause);
        }
        if (result == null) {
            log.warn("Transport returned a null result for a batch of {} {}(s), treating it as a failure",
                    chunk.size(), elementNoun);
            return TransmissionResult.failure(null);
        }
        return result;
    }

    /**
     * Handles a transmission result: notifies {@link #listener} of what succeeded and failed, and retries or
     * gives up on what failed through {@link #onTransmissionFailure(Chunk, Throwable)}, which for a
     * {@link TransmissionResult.PartialFailure} is only the elements it rejected, matched by reference.
     *
     * @param chunk          The chunk that was transmitted, with the failure streaks its elements had before
     * @param result         The (normalized) result of the attempt
     * @param durationMillis The time the transmission took, in milliseconds
     */
    private void handleResult(Chunk<T> chunk, TransmissionResult<T> result, long durationMillis) {
        switch (result) {
            case TransmissionResult.Success<T> success -> onTransmissionSuccess(chunk.size(), durationMillis);
            case TransmissionResult.Failure<T> failure -> {
                transmissionFailed = true;
                notifyListener(() -> listener.onFailed(chunk.size(), failure.cause()));
                onTransmissionFailure(chunk, failure.cause());
            }
            case TransmissionResult.PartialFailure<T> partial -> {
                Partition<T> partition = partition(chunk, partial.rejected());
                Chunk<T> rejected = partition.rejected();
                if (rejected.size() != partial.rejected().size()) {
                    // A transport breaking the contract, with duplicates or foreign elements, which would
                    // otherwise be injected into the batch: only those belonging to the chunk are kept
                    log.warn("Transport reported {} rejected {}(s) for a batch of {}, but only {} of them belong to it; "
                                    + "ignoring the rest (they must be reported by reference identity)",
                            partial.rejected().size(), elementNoun, chunk.size(), rejected.size());
                }
                if (rejected.size() == 0) {
                    onTransmissionSuccess(chunk.size(), durationMillis);
                    return;
                }
                transmissionFailed = true;
                List<T> accepted = partition.accepted();
                if (!accepted.isEmpty()) {
                    transmittedElements.add(accepted.size());
                    lastSuccessNanos = System.nanoTime();
                    notifyListener(() -> listener.onSucceeded(accepted.size(), durationMillis));
                }
                notifyListener(() -> listener.onFailed(rejected.size(), partial.cause()));
                onTransmissionFailure(rejected, partial.cause());
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
        transmittedElements.add(count);
        lastSuccessNanos = System.nanoTime();
        transmissionFailed = false;
        retryNotBeforeNanos = NO_BACKOFF;
        notifyListener(() -> listener.onSucceeded(count, durationMillis));
    }

    /**
     * The elements of a dispatched chunk, split into those a {@link TransmissionResult.PartialFailure}
     * reported as rejected and those it did not.
     *
     * @param accepted The elements not reported as rejected, in their original order
     * @param rejected The elements reported as rejected, in their original order, with the failure streaks
     *                 they had before this attempt
     * @param <T>      The type of element
     */
    private record Partition<T>(List<T> accepted, Chunk<T> rejected) {

    }

    /**
     * Splits a chunk according to whether each of its elements is in {@code rejected}, matched by reference
     * rather than {@code equals()} (see {@link TransmissionResult}). Driven by the chunk, so that the result
     * only holds elements of the chunk, each as many times as the chunk does.
     *
     * @param chunk    The dispatched chunk
     * @param rejected The elements the transport reported as rejected
     * @param <T>      The type of element
     * @return The chunk, partitioned into accepted and rejected elements
     */
    private static <T> Partition<T> partition(Chunk<T> chunk, List<T> rejected) {
        if (rejected.isEmpty()) {
            return new Partition<>(chunk.elements(), new Chunk<>(List.of(), List.of()));
        }
        Set<T> rejectedIdentity = Collections.newSetFromMap(new IdentityHashMap<>(rejected.size()));
        rejectedIdentity.addAll(rejected);
        List<T> accepted = new ArrayList<>(chunk.size());
        List<T> actuallyRejected = new ArrayList<>(Math.min(chunk.size(), rejected.size()));
        List<FailureRun> rejectedRuns = new ArrayList<>();
        FailureRun.Streaks streaks = new FailureRun.Streaks(chunk.runs());
        for (T element : chunk.elements()) {
            int streak = streaks.next();
            if (rejectedIdentity.contains(element)) {
                actuallyRejected.add(element);
                FailureRun.append(rejectedRuns, streak, 1);
            } else {
                accepted.add(element);
            }
        }
        return new Partition<>(accepted, new Chunk<>(actuallyRejected, rejectedRuns));
    }

    /**
     * Invokes the given listener notification, logging whatever it throws, so that a throwing
     * {@code onFailed} never skips the {@link #onTransmissionFailure(Chunk, Throwable)} following it.
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
     * Handles a failed transmission attempt: logs it with its cause, then discards the given elements that
     * reached {@code maxConsecutiveFailures}, each counting its own failures, and puts the others back for a
     * later retry, holding off the next attempt for as long as {@link #backoff} decides.
     *
     * @param chunk The elements that failed to be transmitted, with the failure streaks they had before
     * @param cause The exception responsible for the failure, or {@code null} if none was reported
     */
    private void onTransmissionFailure(Chunk<T> chunk, @Nullable Throwable cause) {
        failedTransmissions.increment();
        int maxFailures = readLimit(maxConsecutiveFailures, 0, "the maximum consecutive failures");
        List<T> givenUp = new ArrayList<>();
        List<T> retried = new ArrayList<>(chunk.size());
        List<FailureRun> retriedRuns = new ArrayList<>();
        int highestStreak = 0;
        FailureRun.Streaks streaks = new FailureRun.Streaks(chunk.runs());
        for (T element : chunk.elements()) {
            int streak = streaks.next() + 1;
            if (maxFailures > 0 && streak >= maxFailures) {
                givenUp.add(element);
            } else {
                retried.add(element);
                FailureRun.append(retriedRuns, streak, 1);
                highestStreak = Math.max(highestStreak, streak);
            }
        }

        if (!givenUp.isEmpty()) {
            log.error("Giving up on {} {}(s) after {} consecutive failed transmission attempts, they will be discarded",
                    givenUp.size(), elementNoun, maxFailures, cause);
            // Directly, under the permit the transmission still holds, so that awaitPendingTransmissions()
            // waits for it too
            drop(Collections.unmodifiableList(givenUp), "maximum consecutive transmission failures exceeded");
        }
        if (!retried.isEmpty()) {
            // Every attempt, a listener being otherwise the only way to learn of an outage before it costs
            // elements
            log.warn("Failed to transmit {} {}(s), {} consecutive failure(s) so far, retrying later",
                    retried.size(), elementNoun, highestStreak, cause);
            applyBackoff(highestStreak);
            requeue(new Chunk<>(retried, retriedRuns));
        }
    }

    /**
     * Holds off the next transmission for as long as {@link #backoff} decides for the given failure streak,
     * only ever pushing the deadline further out: a shorter delay, of another chunk failing for the first
     * time, must not cut short a longer one.
     *
     * @param consecutiveFailures The highest failure streak among the elements kept for a retry
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
        lock.lock();
        try {
            long current = retryNotBeforeNanos;
            if (current == NO_BACKOFF || deadline - current > 0) {
                retryNotBeforeNanos = deadline;
            }
        } finally {
            lock.unlock();
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
     * complete, so that the resources of the transport function can be released right after. It also waits for
     * the listener to return from the notifications of elements dropped before, which run on threads of their
     * own that the JVM exiting once closed would cut short.
     * <p>
     * What is still pending once that wait is over, put back by a failing final transmission or held by one
     * not completed in time, is reported through {@link BatcherListener#onDropped(List, String)}, giving a
     * dead-letter sink a last chance to save it. A transmission completing later has its result ignored, its
     * elements being possibly delivered as well as reported.
     *
     * @param timeout The maximum time to wait for pending transmissions and notifications to complete, or
     *                {@code null} to wait indefinitely, which is only safe for a transport always completing
     *                its future and a listener always returning
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
            // First: past it, a notification runs on the thread dropping its elements, so that the wait misses none
            transmissionExecutor.shutdown();
            awaitDropNotifications();
            reportRemainingAsDropped();
            reportInFlightAsDropped();
        }
    }

    /**
     * Waits, until {@link #closeDeadlineNanos} if any, for the drop notifications running on a thread of their
     * own (see {@link #notifyDropped(List, String)}), an interruption ending the wait.
     */
    private void awaitDropNotifications() {
        long deadline = closeDeadlineNanos;
        try {
            if (deadline == NO_DEADLINE) {
                dropNotificationPermits.acquire(MAX_CONCURRENT_DROP_NOTIFICATIONS);
            } else if (!dropNotificationPermits.tryAcquire(MAX_CONCURRENT_DROP_NOTIFICATIONS,
                    Math.max(0, deadline - System.nanoTime()), NANOSECONDS)) {
                log.warn("Gave up waiting for the listener to return from {} notification(s) of dropped {}s",
                        MAX_CONCURRENT_DROP_NOTIFICATIONS - dropNotificationPermits.availablePermits(), elementNoun);
                return;
            }
            dropNotificationPermits.release(MAX_CONCURRENT_DROP_NOTIFICATIONS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reports the elements of the transmissions still in flight once closed as dropped, on the calling thread,
     * rather than losing them along with a transport that never answers.
     */
    private void reportInFlightAsDropped() {
        List<T> abandoned = new ArrayList<>();
        for (Chunk<T> chunk : inFlight) {
            // Unless its result, arriving meanwhile, removed it first to handle them itself
            if (inFlight.remove(chunk)) {
                abandoned.addAll(chunk.elements());
            }
        }
        if (abandoned.isEmpty()) {
            return;
        }
        log.error("Discarding {} {}(s) whose transmission was still in flight once closed, they may still be delivered",
                abandoned.size(), elementNoun);
        drop(Collections.unmodifiableList(abandoned), "still in flight when closed");
    }

    /**
     * Reports what is left in the pending batch once closed as dropped, on the calling thread, the executor
     * being shut down by then.
     */
    private void reportRemainingAsDropped() {
        List<T> remaining;
        lock.lock();
        try {
            // Raised for an empty batch too, so that a later requeue reports its elements
            drained = true;
            if (pending.isEmpty()) {
                return;
            }
            remaining = pending.drain();
        } finally {
            lock.unlock();
        }
        log.error("Discarding {} {}(s) still pending once closed, they were never successfully transmitted",
                remaining.size(), elementNoun);
        drop(remaining, "still pending when closed");
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
            // Past one end of the clamp above, the upper one being where null would mean waiting indefinitely
            return timeout.isNegative() ? 0 : MAX_TIMEOUT_MILLIS;
        }
    }

}
