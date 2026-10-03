package com.chavaillaz.appender.internal;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * The limits of a {@link Batcher} read again on every use, so that they can be changed at runtime, each falling
 * back to the value leaving it unenforced for now when its supplier throws: these suppliers are application code
 * reading a configuration, and the request limits are read once a batch was swapped out, which an exception
 * would otherwise carry away.
 * <p>
 * The fallback is always the value meaning unbounded or disabled, never one that discards: going unenforced for
 * a while costs memory or an oversized request, where the alternative costs elements.
 */
final class DynamicLimits {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    private final IntSupplier flushThreshold;
    private final IntSupplier maxPendingElements;
    private final IntSupplier maxRequestElements;
    private final IntSupplier maxConsecutiveFailures;
    private final LongSupplier maxPendingBytes;
    private final LongSupplier maxRequestBytes;

    /**
     * Creates the limits read from the given suppliers (see {@link Batcher.Builder} for what each one means).
     *
     * @param flushThreshold         The supplier of the number of accumulated elements triggering a transmission
     * @param maxPendingElements     The supplier of the maximum number of pending elements
     * @param maxRequestElements     The supplier of the maximum number of elements per request
     * @param maxConsecutiveFailures The supplier of the maximum number of consecutive failures
     * @param maxPendingBytes        The supplier of the maximum pending size, in bytes
     * @param maxRequestBytes        The supplier of the maximum request size, in bytes
     */
    DynamicLimits(IntSupplier flushThreshold, IntSupplier maxPendingElements, IntSupplier maxRequestElements,
                  IntSupplier maxConsecutiveFailures, LongSupplier maxPendingBytes, LongSupplier maxRequestBytes) {
        this.flushThreshold = flushThreshold;
        this.maxPendingElements = maxPendingElements;
        this.maxRequestElements = maxRequestElements;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.maxPendingBytes = maxPendingBytes;
        this.maxRequestBytes = maxRequestBytes;
    }

    /**
     * Reads the number of accumulated elements that triggers a transmission.
     *
     * @return The flush threshold, or {@link Integer#MAX_VALUE} if it cannot be read
     */
    int flushThreshold() {
        return read(flushThreshold, Integer.MAX_VALUE, "the flush threshold");
    }

    /**
     * Reads the maximum number of elements the pending batch holds.
     *
     * @return The maximum number of pending elements, or {@link Integer#MAX_VALUE} if it cannot be read
     */
    int maxPendingElements() {
        return read(maxPendingElements, Integer.MAX_VALUE, "the maximum pending elements");
    }

    /**
     * Reads the maximum number of elements sent together in a single request.
     *
     * @return The maximum number of elements per request, a non-positive value disabling chunking, or
     *         {@link Integer#MAX_VALUE} if it cannot be read
     */
    int maxRequestElements() {
        return read(maxRequestElements, Integer.MAX_VALUE, "the maximum request elements");
    }

    /**
     * Reads the maximum number of consecutive failed attempts for the same elements before they are discarded.
     *
     * @return The maximum number of consecutive failures, a non-positive value disabling the limit, or {@code 0}
     *         if it cannot be read
     */
    int maxConsecutiveFailures() {
        return read(maxConsecutiveFailures, 0, "the maximum consecutive failures");
    }

    /**
     * Reads the maximum estimated size of the pending batch.
     *
     * @return The maximum pending size in bytes, a non-positive value disabling the limit, or
     *         {@link Long#MAX_VALUE} if it cannot be read
     */
    long maxPendingBytes() {
        return read(maxPendingBytes, Long.MAX_VALUE, "the maximum pending bytes");
    }

    /**
     * Reads the maximum estimated size of a single request.
     *
     * @return The maximum request size in bytes, a non-positive value disabling the limit, or
     *         {@link Long#MAX_VALUE} if it cannot be read
     */
    long maxRequestBytes() {
        return read(maxRequestBytes, Long.MAX_VALUE, "the maximum request bytes");
    }

    /**
     * Reads one of the count limits, a supplier throwing leaving the limit unenforced for now.
     *
     * @param limit      The supplier to read
     * @param unenforced The value to fall back to, leaving the limit unenforced
     * @param name       The name of the limit, used to phrase the warning logged
     * @return The limit, or {@code unenforced} if the supplier threw
     */
    private static int read(IntSupplier limit, int unenforced, String name) {
        try {
            return limit.getAsInt();
        } catch (Throwable e) {
            log.warn("Unhandled exception thrown while reading {}, leaving it unenforced for now", name, e);
            return unenforced;
        }
    }

    /**
     * Reads one of the byte limits (see {@link #read(IntSupplier, int, String)}).
     *
     * @param limit      The supplier to read
     * @param unenforced The value to fall back to, leaving the limit unenforced
     * @param name       The name of the limit, used to phrase the warning logged
     * @return The limit, or {@code unenforced} if the supplier threw
     */
    private static long read(LongSupplier limit, long unenforced, String name) {
        try {
            return limit.getAsLong();
        } catch (Throwable e) {
            log.warn("Unhandled exception thrown while reading {}, leaving it unenforced for now", name, e);
            return unenforced;
        }
    }

}
