package com.chavaillaz.appender.internal;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.status.StatusLogger;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.TransmissionResult;

/**
 * One transmission of a chunk by a {@link Batcher}: it calls the transport on the thread running it, the one it is
 * created on, and hands the outcome of the attempt over once, its answer or its timeout, whichever comes first. The
 * outcome is handled on that thread if the transport answered before returning, or on a thread of the engine
 * otherwise, never on the thread of the client completing the future, nor on the one timing it out. Once timed
 * out, it ends what the transport still does: it interrupts the thread still calling the transport, and cancels the
 * future the transport returned.
 *
 * @param <T> The type of element transmitted
 */
final class Transmission<T> {

    /**
     * The status logger of Log4j2 rather than an application logger, which would feed these diagnostics back
     * into the appender they are about (see {@link Batcher}).
     */
    private static final Logger log = StatusLogger.getLogger();

    /**
     * What every transmission of an engine shares.
     *
     * @param transport     The transport function, handed the elements of the chunk as an unmodifiable list
     * @param handling      Runs the handling of an outcome away from the thread completing it
     * @param timeouts      Times the transmissions out, on a single virtual thread of its own, or {@code null}
     *                      without a timeout. Rather than
     *                      {@link CompletableFuture#orTimeout(long, java.util.concurrent.TimeUnit)}, whose thread is
     *                      shared by the whole JVM and outlives every delivery: created by the first transmission to
     *                      need it, it would keep what that thread carries, its context class loader included,
     *                      reachable for good
     * @param timeoutMillis How long a transmission waits for its answer before it counts as failed, in
     *                      milliseconds, or {@code 0} to wait indefinitely
     * @param elementNoun   The singular noun describing one element, used only to phrase log messages
     * @param <T>           The type of element transmitted
     */
    record Context<T>(Function<List<T>, CompletableFuture<TransmissionResult<T>>> transport, Executor handling,
                      @Nullable ScheduledThreadPoolExecutor timeouts, long timeoutMillis, String elementNoun) {

    }

    /**
     * Handles the outcome of a transmission, called once.
     *
     * @param <T> The type of element transmitted
     */
    @FunctionalInterface
    interface OutcomeHandler<T> {

        /**
         * Handles the outcome of a transmission.
         *
         * @param chunk      The chunk that was transmitted
         * @param result     The result the attempt completed with, or {@code null} if it completed exceptionally
         *                   or with {@code null} itself
         * @param throwable  The exception the attempt completed with, or {@code null} if it completed normally
         * @param startNanos The {@link System#nanoTime()} reading the transmission started at
         */
        void handle(Chunk<T> chunk, @Nullable TransmissionResult<T> result, @Nullable Throwable throwable, long startNanos);

    }

    private final Context<T> context;

    /**
     * The chunk transmitted, registered as in flight.
     */
    private final Chunk<T> chunk;

    private final OutcomeHandler<T> handler;

    /**
     * The thread calling the transport, the one running the transmission, which creates it.
     */
    private final Thread thread = Thread.currentThread();

    /**
     * Whether the transport returned, or threw.
     */
    private volatile boolean over;

    /**
     * The future the transport returned, {@code null} until it does, or if it returned none.
     */
    private volatile @Nullable CompletableFuture<TransmissionResult<T>> answer;

    /**
     * Creates a transmission, on the thread running it.
     *
     * @param context What every transmission of the engine shares
     * @param chunk   The chunk to transmit, registered as in flight
     * @param handler Handles the outcome of the attempt, once
     */
    Transmission(Context<T> context, Chunk<T> chunk, OutcomeHandler<T> handler) {
        this.context = context;
        this.chunk = chunk;
        this.handler = handler;
    }

    /**
     * Creates the scheduler timing the transmissions out, whose single thread is a virtual one, started with
     * the first transmission and taking nothing of the thread starting it.
     *
     * @param threadPrefix       The name prefix of the threads running transmissions, which its thread is named
     *                           after
     * @param contextClassLoader The context class loader of its thread, which cancels the futures of the
     *                           transport, or {@code null} for the system class loader
     * @return The scheduler
     */
    static ScheduledThreadPoolExecutor newTimeoutScheduler(String threadPrefix, @Nullable ClassLoader contextClassLoader) {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, ThreadFactories.withContextClassLoader(
                Thread.ofVirtual().name(threadPrefix + "timeout").inheritInheritableThreadLocals(false).factory(),
                contextClassLoader));
        // A transmission answered in time leaves nothing behind, where its timeout would otherwise keep its
        // elements reachable until it elapses
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * Calls the transport function and hands the outcome of the attempt over, its answer or its timeout,
     * whichever comes first: on this thread if the transport answered before returning, or on a thread of the
     * engine otherwise, never on the thread of the client completing the future, nor on the one timing it out.
     */
    void run() {
        long start = System.nanoTime();
        CompletableFuture<TransmissionResult<T>> outcome = new CompletableFuture<>();
        outcome.whenComplete((result, throwable) -> {
            Runnable handling = () -> handler.handle(chunk, result, throwable, start);
            if (Thread.currentThread() == thread) {
                // Answered before the transport returned
                handling.run();
            } else {
                // The thread completing it belongs to the client, an I/O thread serving other requests, or times
                // every transmission out: neither is held up by the listener, a dead-letter write or a requeue
                // waiting for the lock
                executeOffTheCompletingThread(handling);
            }
        });
        scheduleTimeout(outcome);
        try {
            // Unmodifiable (see Chunk), what the transport is handed being what gets retried or reported
            CompletableFuture<TransmissionResult<T>> returned = context.transport().apply(chunk.elements());
            returned(returned);
            if (returned == null) {
                outcome.complete(null);
                return;
            }
            returned.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    outcome.completeExceptionally(throwable);
                } else {
                    outcome.complete(result);
                }
            });
            if (outcome.isDone() && !returned.isDone()) {
                // Timed out as the transport returned, before its timeout could see this future
                returned.cancel(true);
            }
        } catch (Throwable e) {
            // Throwable: an Error, from a class of the transport missing say, would otherwise leave the attempt
            // without an outcome and its permit taken for good, a few of them wedging every later dispatch
            returned(null);
            outcome.completeExceptionally(e);
        }
    }

    /**
     * Records that the transport returned, or threw.
     *
     * @param future The future it returned, or {@code null} if it returned none, or threw
     */
    private void returned(@Nullable CompletableFuture<TransmissionResult<T>> future) {
        this.answer = future;
        this.over = true;
    }

    /**
     * Ends what the transport still does for a transmission that timed out: interrupts the thread it keeps
     * waiting, and cancels the future it returned, which a client aborts its request on if it can.
     */
    private void abandon() {
        if (!over) {
            thread.interrupt();
        }
        CompletableFuture<TransmissionResult<T>> pending = answer;
        if (pending != null) {
            pending.cancel(true);
        }
    }

    /**
     * Schedules the timeout of this transmission, which its outcome cancels when the answer comes first. Without
     * a scheduler (see {@link Context#timeouts()}), the transmission waits for its answer for as long as it takes.
     *
     * @param outcome The outcome of the attempt, which the timeout completes as a failure
     */
    private void scheduleTimeout(CompletableFuture<TransmissionResult<T>> outcome) {
        ScheduledThreadPoolExecutor scheduler = context.timeouts();
        if (scheduler == null) {
            return;
        }
        try {
            ScheduledFuture<?> timeout = scheduler.schedule(() -> timeOut(outcome), context.timeoutMillis(), MILLISECONDS);
            outcome.whenComplete((result, throwable) -> timeout.cancel(false));
        } catch (Throwable e) {
            // Throwable: an Error starting the thread of the scheduler. Transmitted without a timeout rather
            // than not at all, as when a close, which reports the transmission itself, shut the scheduler down
            if (!(e instanceof RejectedExecutionException)) {
                log.warn("Unable to schedule the timeout of a transmission of {} {}(s), waiting for its answer without one",
                        chunk.size(), context.elementNoun(), e);
            }
        }
    }

    /**
     * Fails this transmission, which was not answered in time, unless its answer just came, and ends what its
     * transport still does. Runs on the thread timing the transmissions out, which only hands the failure over.
     *
     * @param outcome The outcome of the attempt
     */
    private void timeOut(CompletableFuture<TransmissionResult<T>> outcome) {
        TimeoutException timeout = new TimeoutException("No answer to the transmission of " + chunk.size() + " "
                + context.elementNoun() + "(s) within " + Duration.ofMillis(context.timeoutMillis()));
        if (!outcome.completeExceptionally(timeout)) {
            return;
        }
        try {
            abandon();
        } catch (Throwable e) {
            // Throwable: cancelling runs code of the client, which must not end the thread timing the others out
            log.warn("Unhandled exception while abandoning a transmission of {} {}(s) that timed out",
                    chunk.size(), context.elementNoun(), e);
        }
    }

    /**
     * Runs the handling of the outcome of this transmission on a thread of the engine, or on the calling thread
     * once that fails, the executor being shut down by a close whose wait is over: an outcome never handled would
     * lose its elements, and the permit it holds.
     *
     * @param task The handling of the outcome
     */
    private void executeOffTheCompletingThread(Runnable task) {
        try {
            context.handling().execute(task);
        } catch (Throwable e) {
            // Throwable: an Error starting the thread would be caught by the future, which then skips the task
            task.run();
        }
    }

}
