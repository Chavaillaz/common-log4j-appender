package com.chavaillaz.appender.log4j;

import java.time.Duration;

import com.chavaillaz.appender.LogConfiguration;
import lombok.Getter;
import lombok.Setter;

/**
 * Minimal, real implementation of {@link LogConfiguration} used across the test suite instead of mocking
 * the interface, to keep tests closer to actual usage and avoid repeating stubbing boilerplate.
 */
@Getter
@Setter
class TestLogConfiguration implements LogConfiguration {

    private String application = "test-application";
    private String host = "test-host";
    private String environment = "test-environment";
    private long flushThreshold = 10;
    private Duration flushInterval = Duration.ofSeconds(5);
    private int maxConcurrentTransmissions = 4;
    private int maxBatchSize = 10_000;
    private int maxConsecutiveFailures = 5;

}
