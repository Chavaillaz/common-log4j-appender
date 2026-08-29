package com.chavaillaz.appender;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Common utility methods.
 */
@UtilityClass
public class CommonUtils {

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
     *
     * @return The host name or {@code localhost} if it cannot be determined
     */
    public static String getInitialHostname() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

}
