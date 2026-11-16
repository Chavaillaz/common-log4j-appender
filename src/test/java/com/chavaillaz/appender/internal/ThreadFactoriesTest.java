package com.chavaillaz.appender.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ThreadFactories")
class ThreadFactoriesTest {

    @Test
    @DisplayName("Gives the threads of a factory the context class loader asked for")
    void testThreadsTakeTheContextClassLoaderGiven() {
        // given: a factory whose threads take nothing of the thread creating them, the system class loader then
        ThreadFactory factory = Thread.ofVirtual().name("worker").inheritInheritableThreadLocals(false).factory();
        var applicationClassLoader = new ClassLoader() {
        };

        // when
        Thread thread = ThreadFactories.withContextClassLoader(factory, applicationClassLoader).newThread(() -> {
        });

        // then: the same thread, but for its context class loader
        assertThat(thread.getContextClassLoader()).isSameAs(applicationClassLoader);
        assertThat(thread.getName()).isEqualTo("worker");
        assertThat(thread.isVirtual()).isTrue();
    }

    @Test
    @DisplayName("Leaves the threads of a factory as they are without a context class loader")
    void testThreadsAreLeftAsTheyAreWithoutAContextClassLoader() {
        // given
        ThreadFactory factory = Thread.ofVirtual().inheritInheritableThreadLocals(false).factory();

        // when
        ThreadFactory same = ThreadFactories.withContextClassLoader(factory, null);

        // then
        assertThat(same).isSameAs(factory);
        assertThat(same.newThread(() -> {
        }).getContextClassLoader()).isSameAs(ClassLoader.getSystemClassLoader());
    }

    @Test
    @DisplayName("Hands over a thread its factory failed to create as it is")
    void testThreadTheFactoryFailedToCreateIsHandedOver() {
        // given: a factory answering null, as its contract allows when it refuses to create a thread
        ThreadFactory refusing = task -> null;

        // when
        Thread thread = ThreadFactories.withContextClassLoader(refusing, new ClassLoader() {
        }).newThread(() -> {
        });

        // then
        assertThat(thread).isNull();
    }

}
