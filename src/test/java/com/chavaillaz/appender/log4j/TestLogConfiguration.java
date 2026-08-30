package com.chavaillaz.appender.log4j;

import java.time.Duration;

import lombok.Builder;
import lombok.Value;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

import com.chavaillaz.appender.LogConfiguration;

/**
 * Minimal, real, immutable implementation of {@link LogConfiguration} used across the test suite instead
 * of mocking the interface, to keep tests closer to actual usage and avoid repeating stubbing boilerplate.
 */
@Value
@Builder
class TestLogConfiguration implements LogConfiguration {

    @Builder.Default String application = "test-application";
    @Builder.Default String host = "test-host";
    @Builder.Default String environment = "test-environment";
    @Builder.Default long flushThreshold = 10;
    @Builder.Default Duration flushInterval = Duration.ofSeconds(5);
    @Builder.Default @Nullable Level flushLevel = null;
    @Builder.Default int maxConcurrentTransmissions = 4;
    @Builder.Default int maxPendingDocuments = 10_000;
    @Builder.Default int maxRequestDocuments = Integer.MAX_VALUE;
    @Builder.Default int maxConsecutiveFailures = 5;
    @Builder.Default int maxQueuedEvents = 10_000;

}
