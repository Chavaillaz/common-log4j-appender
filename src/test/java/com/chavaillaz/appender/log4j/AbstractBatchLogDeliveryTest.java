package com.chavaillaz.appender.log4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.chavaillaz.appender.LogConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.jupiter.api.Test;

@Slf4j
class AbstractBatchLogDeliveryTest {

    @Test
    void testNotSendingBelowThreshold() {
        // given
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(2L);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(Map.of());

        // then
        assertEquals(0, logDelivery.countBulkSent.getValue());
    }

    @Test
    void testNotSendingEmpty() {
        // given
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(2L);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.flush();

        // then
        assertEquals(0, logDelivery.countBulkSent.getValue());
    }

    @Test
    void testSendingReachedThreshold() {
        // given
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(2L);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());

        // then
        assertEquals(1, logDelivery.countBulkSent.getValue());
    }

    @Test
    void testSendingForcedFlush() {
        // given
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(2L);
        var logDelivery = new TestLogDelivery(configuration);

        // when
        logDelivery.send(Map.of());
        logDelivery.flush();

        // then
        assertEquals(1, logDelivery.countBulkSent.getValue());
    }

    @Test
    void testSendingRetried() {
        // given
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(2L);
        var logDelivery = new TestLogDelivery(configuration);
        logDelivery.bulkResult = false;

        // when
        logDelivery.send(Map.of());
        logDelivery.send(Map.of());
        logDelivery.bulkResult = true;
        logDelivery.send(Map.of());

        // then
        assertEquals(2, logDelivery.countBulkSent.getValue());
    }

    static class TestLogDelivery extends AbstractBatchLogDelivery<LogConfiguration> {

        MutableInt countBulkSent = new MutableInt();
        boolean bulkResult = true;

        protected TestLogDelivery(LogConfiguration configuration) {
            super(configuration);
        }

        @Override
        protected boolean sendBulk(List<Map<String, Object>> documents) {
            countBulkSent.increment();
            return bulkResult;
        }

    }

}
