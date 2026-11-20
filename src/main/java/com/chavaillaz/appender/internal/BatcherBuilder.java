package com.chavaillaz.appender.internal;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.TransmissionResult;

/**
 * Builder for {@link Batcher}, preferred over a constructor taking every setting positionally, most of them
 * being counts of the same type, easy to swap by mistake. Its settings are read by the constructor of
 * {@link Batcher}, in the same package.
 *
 * @param <T> The type of element accumulated and transmitted
 */
public final class BatcherBuilder<T> {

    int flushThreshold = 1;
    int maxPendingElements = 10_000;
    int maxRequestElements = Integer.MAX_VALUE;
    int maxConsecutiveFailures = 5;
    long maxPendingBytes = Long.MAX_VALUE;
    long maxRequestBytes = Long.MAX_VALUE;
    @Nullable ToLongFunction<T> elementSize;
    int maxConcurrentTransmissions = 4;
    @Nullable Duration transmissionTimeout;
    BackoffPolicy backoff = BackoffPolicy.none();
    String transmissionThreadPrefix = "batcher-transmission-";
    @Nullable ThreadFactory transmissionThreadFactory;
    @Nullable ClassLoader contextClassLoader;
    String elementNoun = "element";
    @Nullable Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport;
    BatcherListener<T> listener = BatcherListener.noop();

    BatcherBuilder() {
        // Instantiated only through Batcher.builder()
    }

    /**
     * Sets the number of accumulated elements that triggers a transmission. Defaults to {@code 1} (no
     * batching).
     *
     * @param flushThreshold The flush threshold
     * @return This builder
     */
    public BatcherBuilder<T> flushThreshold(int flushThreshold) {
        this.flushThreshold = flushThreshold;
        return this;
    }

    /**
     * Sets the maximum number of elements a pending batch can hold in memory. Defaults to {@code 10'000}.
     *
     * @param maxPendingElements The maximum number of pending elements
     * @return This builder
     */
    public BatcherBuilder<T> maxPendingElements(int maxPendingElements) {
        this.maxPendingElements = maxPendingElements;
        return this;
    }

    /**
     * Sets the maximum number of elements sent together in a single request, a non-positive value
     * disabling chunking. Defaults to {@link Integer#MAX_VALUE} (no chunking).
     *
     * @param maxRequestElements The maximum number of elements per request
     * @return This builder
     */
    public BatcherBuilder<T> maxRequestElements(int maxRequestElements) {
        this.maxRequestElements = maxRequestElements;
        return this;
    }

    /**
     * Sets the maximum number of consecutive failed attempts allowed for the same elements before they
     * are discarded, a non-positive value disabling this limit. Defaults to {@code 5}.
     *
     * @param maxConsecutiveFailures The maximum number of consecutive failures
     * @return This builder
     */
    public BatcherBuilder<T> maxConsecutiveFailures(int maxConsecutiveFailures) {
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        return this;
    }

    /**
     * Sets the maximum estimated size a pending batch can reach in memory. Has no effect unless
     * {@link #elementSize(ToLongFunction)} is also set. Defaults to {@link Long#MAX_VALUE} (unbounded); a
     * non-positive value also disables the limit.
     *
     * @param maxPendingBytes The maximum pending size, in bytes
     * @return This builder
     */
    public BatcherBuilder<T> maxPendingBytes(long maxPendingBytes) {
        this.maxPendingBytes = maxPendingBytes;
        return this;
    }

    /**
     * Sets the maximum estimated size of a single request: a batch whose elements add up to more than this
     * is split into chunks that each stay under it. Has no effect unless
     * {@link #elementSize(ToLongFunction)} is also set. Defaults to {@link Long#MAX_VALUE} (unbounded); a
     * non-positive value also disables the limit.
     *
     * @param maxRequestBytes The maximum request size, in bytes
     * @return This builder
     */
    public BatcherBuilder<T> maxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
        return this;
    }

    /**
     * Sets how to measure one element, enabling the two byte limits above, which do nothing without it.
     * Unset by default, measuring costing something for every element: set it along with one of them, and
     * leave it {@code null} otherwise.
     * <p>
     * Expected to be cheap and free of side effects, being called for every element on the way in, and
     * again when a batch is split, trimmed or put back. A negative result counts as zero, and so does one
     * throwing, which is logged, so that a faulty measurement only makes the limits less accurate. A result
     * past 2 GiB counts as 2 GiB, more than any request carries.
     *
     * @param elementSize The measurement function, or {@code null} not to measure any element
     * @return This builder
     */
    public BatcherBuilder<T> elementSize(@Nullable ToLongFunction<T> elementSize) {
        this.elementSize = elementSize;
        return this;
    }

    /**
     * Sets the maximum number of batches transmitted concurrently; must be at least {@code 1}, or every
     * dispatch would block forever waiting for a permit that never opens. Defaults to {@code 4}.
     *
     * @param maxConcurrentTransmissions The maximum number of concurrent transmissions
     * @return This builder
     */
    public BatcherBuilder<T> maxConcurrentTransmissions(int maxConcurrentTransmissions) {
        this.maxConcurrentTransmissions = maxConcurrentTransmissions;
        return this;
    }

    /**
     * Sets how long a transmission waits for its answer before it counts as failed. Its
     * permit is then released and its elements are retried as after any failure, the thread still calling
     * the transport is interrupted and the future it returned is cancelled, an answer coming later being
     * ignored. Defaults to {@code null}, waiting indefinitely, as a duration that is not positive does.
     *
     * @param transmissionTimeout The transmission timeout, or {@code null} to wait indefinitely
     * @return This builder
     */
    public BatcherBuilder<T> transmissionTimeout(@Nullable Duration transmissionTimeout) {
        this.transmissionTimeout = transmissionTimeout;
        return this;
    }

    /**
     * Sets how long to hold off transmissions after a failure. Defaults to {@link BackoffPolicy#none()}.
     *
     * @param backoff The backoff policy
     * @return This builder
     */
    public BatcherBuilder<T> backoff(BackoffPolicy backoff) {
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
    public BatcherBuilder<T> transmissionThreadPrefix(String transmissionThreadPrefix) {
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
    BatcherBuilder<T> transmissionThreadFactory(ThreadFactory transmissionThreadFactory) {
        this.transmissionThreadFactory = transmissionThreadFactory;
        return this;
    }

    /**
     * Sets the context class loader of the threads calling the transport and the listener: the one that
     * loaded them, which a client looks its providers up through (see {@link ThreadFactories}). Defaults to
     * {@code null}, leaving these threads with the system class loader.
     *
     * @param contextClassLoader The context class loader, or {@code null} for the system class loader
     * @return This builder
     */
    public BatcherBuilder<T> contextClassLoader(@Nullable ClassLoader contextClassLoader) {
        this.contextClassLoader = contextClassLoader;
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
    public BatcherBuilder<T> elementNoun(String elementNoun) {
        this.elementNoun = elementNoun;
        return this;
    }

    /**
     * Sets the transport function, called on a virtual thread of its own with a batch (or chunk) to
     * transmit, as an unmodifiable list. It may complete the returned future before returning, blocking
     * that thread only, or later, the result then being handled on a virtual thread of this engine rather
     * than on the thread completing it. A future never completed holds its transmission permit until the
     * transmission timeout elapses, or for good without one, {@link Batcher#close(Duration)} only ceasing to
     * wait for it. Required, having no sensible default.
     *
     * @param transport The transport function
     * @return This builder
     */
    public BatcherBuilder<T> transport(Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport) {
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
    public BatcherBuilder<T> listener(BatcherListener<T> listener) {
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
