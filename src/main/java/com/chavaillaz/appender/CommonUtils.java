package com.chavaillaz.appender;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * Common utility methods.
 */
public final class CommonUtils {

    /**
     * Maximum time {@link #getInitialHostname()} waits for the reverse DNS lookup before falling back to
     * {@code localhost}, so a slow or unresponsive DNS server cannot delay application startup indefinitely.
     */
    private static final Duration HOSTNAME_LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private CommonUtils() {
        // Static methods only
    }

    /**
     * Searches an environment or system property value, giving priority to the environment variable if both
     * are set.
     *
     * @param key          The key to search (used as-is for both, so must be a valid name for both, e.g.
     *                     uppercase letters, digits and underscores only)
     * @param defaultValue The default value if the given key is not found, or {@code null}
     * @return The value found, the given default, or {@code null} if neither is found
     */
    public static @Nullable String getProperty(String key, @Nullable String defaultValue) {
        return Optional.ofNullable(System.getenv(key)).orElse(System.getProperty(key, defaultValue));
    }

    /**
     * Gets the current host name of the machine, or {@code localhost} if it cannot be determined within
     * {@link #HOSTNAME_LOOKUP_TIMEOUT}: the reverse DNS lookup of {@link InetAddress#getCanonicalHostName()}
     * has no bound of its own, and this typically runs while the application starts.
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
     * Runs the given lookup on a separate thread, waiting at most the given timeout before falling back to
     * {@code localhost}. A lookup ignoring interruption, as the native calls behind DNS resolution do, keeps
     * running on its own thread past the timeout: this bounds the wait of the caller, not the lookup.
     *
     * @param timeout The maximum time to wait for the lookup to complete
     * @param lookup  The lookup to run, expected to already have a fallback for its own failures
     * @return The result of the lookup, or {@code localhost} if it timed out or was interrupted
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
