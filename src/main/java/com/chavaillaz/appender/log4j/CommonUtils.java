package com.chavaillaz.appender.log4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * Resolves the defaults of the attributes of an appender plugin: a variable of the environment or a system
 * property, and the name of the host the application runs on.
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
        String variable = System.getenv(key);
        return variable != null ? variable : System.getProperty(key, defaultValue);
    }

    /**
     * Gets the name of the host the application runs on, looked up once and shared by every later call: the
     * reverse DNS lookup of {@link InetAddress#getCanonicalHostName()} can take seconds, and the name does not
     * change while the application runs. Once per class loader holding this library, so once per JVM for an
     * application on its own.
     * <p>
     * A call waits at most {@link #HOSTNAME_LOOKUP_TIMEOUT} for the lookup, which has no bound of its own and
     * typically runs while the application starts, returning {@code localhost} past it: the lookup goes on, and
     * a later call gets its result.
     *
     * @return The host name, or {@code localhost} if it cannot be determined within a reasonable time
     */
    public static String getInitialHostname() {
        return await(HostName.LOOKUP, HOSTNAME_LOOKUP_TIMEOUT);
    }

    /**
     * Holds the lookup of the host name, started the first time {@link #getInitialHostname()} is called.
     */
    private static final class HostName {

        private static final Future<String> LOOKUP = start(() -> {
            try {
                return InetAddress.getLocalHost().getCanonicalHostName();
            } catch (UnknownHostException e) {
                return "localhost";
            }
        });

    }

    /**
     * Starts the given lookup on a virtual thread of its own, which does not take the thread-locals nor the
     * context class loader of the calling thread, the lookup possibly outliving it.
     *
     * @param lookup The lookup to run, expected to already have a fallback for its own failures
     * @return The lookup, running
     */
    static Future<String> start(Callable<String> lookup) {
        FutureTask<String> task = new FutureTask<>(lookup);
        Thread.ofVirtual()
                .name("log-delivery-host-name-lookup")
                .inheritInheritableThreadLocals(false)
                .start(task);
        return task;
    }

    /**
     * Waits at most the given timeout for the given lookup before falling back to {@code localhost}. A lookup
     * ignoring interruption, as the native calls behind DNS resolution do, keeps running past the timeout:
     * this bounds the wait of the caller, not the lookup.
     *
     * @param lookup  The lookup to wait for
     * @param timeout The maximum time to wait for it to complete
     * @return The result of the lookup, or {@code localhost} if it failed, timed out or was interrupted
     */
    static String await(Future<String> lookup, Duration timeout) {
        try {
            return lookup.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "localhost";
        } catch (Exception e) {
            return "localhost";
        }
    }

}
