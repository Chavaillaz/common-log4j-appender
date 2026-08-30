package com.chavaillaz.appender;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Common utility methods.
 */
@UtilityClass
public class CommonUtils {

    /**
     * Maximum time to wait for the reverse DNS lookup performed by {@link #getInitialHostname()} before
     * falling back to {@code localhost}, so that a slow or unresponsive DNS server cannot delay starting
     * the whole application by an unbounded amount of time.
     */
    private static final Duration HOSTNAME_LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Searches an environment or system property value, giving priority to the environment variable of the
     * given name if both are set.
     *
     * @param key          The key to search in the properties (used as-is for both the environment
     *                     variable and the system property, so it must be a valid name for both, e.g. one
     *                     using only uppercase letters, digits and underscores)
     * @param defaultValue The default value to use in case the given key is not found, or {@code null}
     * @return The value found in environment/system properties, the given default value, or {@code null}
     *         if neither is found and {@code defaultValue} was {@code null}
     */
    public static @Nullable String getProperty(String key, @Nullable String defaultValue) {
        return Optional.ofNullable(System.getenv(key)).orElse(System.getProperty(key, defaultValue));
    }

    /**
     * Gets the current host name of the machine.
     * <p>
     * {@link InetAddress#getCanonicalHostName()} performs a reverse DNS lookup, which (unlike a failure
     * reported as an {@link UnknownHostException}) has no built-in bound on how long it can take: a slow or
     * unreachable DNS server can block for a very long time, or indefinitely. Since this is typically called
     * once while an application is starting up (to fill in a default value for a logging configuration),
     * that would otherwise delay the whole application's startup by an unpredictable amount for a problem
     * entirely unrelated to it.
     *
     * @return The host name, or {@code localhost} if it cannot be determined within a reasonable time
     */
    public static String getInitialHostname() {
        return withTimeout(HOSTNAME_LOOKUP_TIMEOUT, () -> {
            try {
                return InetAddress.getLocalHost().getCanonicalHostName();
            } catch (UnknownHostException e) {
                return "localhost";
            }
        });
    }

    /**
     * Runs the given lookup on a separate thread, waiting at most the given timeout for it to complete
     * before falling back to {@code localhost} instead.
     * <p>
     * The lookup keeps running on its own thread past the timeout if it does not react to interruption
     * (true of the blocking native calls behind DNS resolution): this bounds how long the caller waits, not
     * how long the lookup itself takes, trading a single (bounded to one per timed-out call, and typically
     * only ever exercised at most a few times over an application's lifetime) leaked thread for never
     * blocking application startup indefinitely.
     *
     * @param timeout The maximum time to wait for the lookup to complete
     * @param lookup  The lookup to run, itself expected to already have a fallback for its own failures
     * @return The result of the lookup, or {@code localhost} if it did not complete within the timeout or
     *         was itself interrupted while waiting
     */
    static String withTimeout(Duration timeout, Callable<String> lookup) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return executor.submit(lookup).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "localhost";
        } catch (Exception e) {
            return "localhost";
        } finally {
            executor.shutdown();
        }
    }

}
