package com.chavaillaz.appender.log4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.appender.LogConfiguration;

@DisplayName("LogConverter")
class LogConverterTest {

    private static final TestLogConfiguration CONFIGURATION = TestLogConfiguration.builder()
            .application("my-application")
            .build();

    @Test
    @DisplayName("Creates the converter named through its constructor taking the configuration")
    void testCreatesTheConverterWithItsConfiguration() throws Exception {
        // When
        LogConverter converter = LogConverter.newInstance(ApplicationConverter.class.getName(), CONFIGURATION);

        // Then
        LogEvent event = Log4jLogEvent.newBuilder().setMessage(new SimpleMessage("hello")).build();
        assertEquals(Map.of("application", "my-application"), converter.convert(event));
    }

    @Test
    @DisplayName("Takes the constructor declaring the most specific type of the configuration")
    void testPrefersTheMostSpecificConstructor() throws Exception {
        // When
        LogConverter converter = LogConverter.newInstance(OverloadedConverter.class.getName(), CONFIGURATION);

        // Then
        assertEquals("TestLogConfiguration", ((OverloadedConverter) converter).taken);
    }

    @Test
    @DisplayName("Creates the default converter by its name too")
    void testCreatesTheDefaultConverter() throws Exception {
        // When / Then
        assertInstanceOf(DefaultLogConverter.class, LogConverter.newInstance(DefaultLogConverter.class.getName(), CONFIGURATION));
    }

    @Test
    @DisplayName("Refuses a converter without a constructor taking the configuration")
    void testRequiresAConstructorTakingTheConfiguration() {
        // When / Then: rather than creating one that would convert without it
        assertThrows(NoSuchMethodException.class,
                () -> LogConverter.newInstance(UnconfiguredConverter.class.getName(), CONFIGURATION));
    }

    @Test
    @DisplayName("Reports the failure of the constructor of the converter")
    void testReportsAFailingConstructor() {
        // When / Then
        assertThrows(InvocationTargetException.class,
                () -> LogConverter.newInstance(FailingConverter.class.getName(), CONFIGURATION));
    }

    @Test
    @DisplayName("Refuses a class that is not a converter, or cannot be found")
    void testRefusesWhatIsNotAConverter() {
        // When / Then
        assertThrows(ClassCastException.class, () -> LogConverter.newInstance(String.class.getName(), CONFIGURATION));
        assertThrows(ClassNotFoundException.class, () -> LogConverter.newInstance("does.not.Exist", CONFIGURATION));
    }

    public static class ApplicationConverter implements LogConverter {

        private final LogConfiguration configuration;

        public ApplicationConverter(LogConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public Map<String, Object> convert(LogEvent event) {
            return Map.of("application", configuration.getApplication());
        }

    }

    public static class OverloadedConverter implements LogConverter {

        private final String taken;

        public OverloadedConverter(Object configuration) {
            this.taken = "Object";
        }

        public OverloadedConverter(LogConfiguration configuration) {
            this.taken = "LogConfiguration";
        }

        public OverloadedConverter(TestLogConfiguration configuration) {
            this.taken = "TestLogConfiguration";
        }

        @Override
        public Map<String, Object> convert(LogEvent event) {
            return Map.of();
        }

    }

    public static class UnconfiguredConverter implements LogConverter {

        @Override
        public Map<String, Object> convert(LogEvent event) {
            return Map.of();
        }

    }

    public static class FailingConverter implements LogConverter {

        public FailingConverter(LogConfiguration configuration) {
            throw new IllegalStateException("Simulated failure");
        }

        @Override
        public Map<String, Object> convert(LogEvent event) {
            return Map.of();
        }

    }

}
