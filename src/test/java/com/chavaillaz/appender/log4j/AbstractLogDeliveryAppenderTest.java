package com.chavaillaz.appender.log4j;

import static java.lang.Thread.sleep;
import static org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.chavaillaz.appender.LogConfiguration;
import com.chavaillaz.appender.LogDelivery;
import lombok.Getter;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;

class AbstractLogDeliveryAppenderTest {

    @Test
    void testWithThreadPool_WaitFlush() throws Exception {
        // given
        var logEvent = mock(LogEvent.class);
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(10L);
        when(configuration.getFlushInterval()).thenReturn(Duration.ofMillis(200));
        var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

        // when
        appender.start();
        appender.append(logEvent);
        sleep(300);

        // then
        assertEquals(1, appender.getCountTaskExecuted().getValue());
        verify(appender.getLogDeliveryHandler()).flush();

        // when
        appender.stop();

        // then
        verify(appender.getLogDeliveryHandler()).close();
        verifyNoMoreInteractions(appender.getLogDeliveryHandler());
    }

    @Test
    void testWithThreadPool_DoNotWaitFlush() throws Exception {
        // given
        var logEvent = mock(LogEvent.class);
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(10L);
        when(configuration.getFlushInterval()).thenReturn(Duration.ofMillis(1_000));
        var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

        // when
        appender.start();
        appender.append(logEvent);

        // then
        assertEquals(0, appender.getCountTaskExecuted().getValue());
        verifyNoMoreInteractions(appender.getLogDeliveryHandler());

        // when
        appender.stop();

        // then
        verify(appender.getLogDeliveryHandler()).close();
        verifyNoMoreInteractions(appender.getLogDeliveryHandler());
    }

    @Test
    void testWithoutThreadPool() throws Exception {
        // given
        var logEvent = mock(LogEvent.class);
        var configuration = mock(LogConfiguration.class);
        when(configuration.getFlushThreshold()).thenReturn(1L);
        when(configuration.getFlushInterval()).thenReturn(Duration.ofMillis(1_000));
        var appender = new LogDeliveryAppender("appender-name", null, createDefaultLayout(), configuration);

        // when
        appender.start();
        appender.append(logEvent);
        sleep(100);

        // then
        assertEquals(1, appender.getCountTaskExecuted().getValue());
        verify(appender.getLogDeliveryHandler(), never()).flush();

        // when
        appender.stop();

        // then
        verify(appender.getLogDeliveryHandler()).close();
        verifyNoMoreInteractions(appender.getLogDeliveryHandler());
    }

    @Getter
    static class LogDeliveryAppender extends AbstractLogDeliveryAppender<LogConfiguration> {

        LogDelivery logDeliveryHandler = mock(LogDelivery.class);
        MutableInt countTaskExecuted = new MutableInt();

        protected LogDeliveryAppender(String name, Filter filter, Layout layout, LogConfiguration configuration) {
            super(name, filter, layout, configuration);
        }

        @Override
        public LogDelivery createDeliveryHandler() {
            return logDeliveryHandler;
        }

        @Override
        public Runnable createDeliveryTask(LogEvent loggingEvent) {
            return () -> countTaskExecuted.increment();
        }

    }

}
