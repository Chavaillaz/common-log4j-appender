package com.chavaillaz.appender.internal;

import java.util.concurrent.ThreadFactory;

import org.jspecify.annotations.Nullable;

/**
 * Gives the threads of an appender and of its delivery the context class loader they run with.
 * <p>
 * These threads take neither the inheritable thread-locals nor the context class loader of the thread creating
 * them, which they would keep reachable for as long as they run: the class loader of a web application
 * undeployed since, when the thread deploying it started the appender. Left at that, they run with the system
 * class loader, which sees nothing of an application loaded by class loaders of its own, an executable JAR
 * nesting its libraries or a web application: a client looking its providers up through the context class
 * loader, as {@link java.util.ServiceLoader#load(Class)} does, then finds them on every thread but these.
 * <p>
 * They are given the class loader of the appender, or of the delivery, instead: the one that loaded the code
 * they run, and its client with it. Their threads reach it already, through the appender itself, so that it
 * keeps nothing alive that they did not.
 */
public final class ThreadFactories {

    private ThreadFactories() {
        // Static methods only
    }

    /**
     * Makes the threads of the given factory start with the given context class loader.
     *
     * @param factory            The factory creating the threads
     * @param contextClassLoader The context class loader to give them, or {@code null} to leave them with the
     *                           one the factory gives them
     * @return A factory creating the same threads, with that context class loader
     */
    public static ThreadFactory withContextClassLoader(ThreadFactory factory, @Nullable ClassLoader contextClassLoader) {
        if (contextClassLoader == null) {
            return factory;
        }
        return task -> {
            Thread thread = factory.newThread(task);
            if (thread != null) {
                thread.setContextClassLoader(contextClassLoader);
            }
            return thread;
        };
    }

}
