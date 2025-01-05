package com.chavaillaz.appender;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import lombok.experimental.UtilityClass;

/**
 * Common utility methods.
 */
@UtilityClass
public class CommonUtils {

    /**
     * Searches an environment or system property value.
     *
     * @param key          The key to search in the properties
     * @param defaultValue The default value to use in case the given key is not found
     * @return The value found in environment/system properties or the given default value
     */
    public static String getProperty(String key, String defaultValue) {
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
