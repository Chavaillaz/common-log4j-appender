package com.chavaillaz.appender.log4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(converter.convert(event)).isEqualTo(Map.of("application", "my-application"));
    }

    @Test
    @DisplayName("Takes the constructor declaring the most specific type of the configuration")
    void testPrefersTheMostSpecificConstructor() throws Exception {
        // When
        LogConverter converter = LogConverter.newInstance(OverloadedConverter.class.getName(), CONFIGURATION);

        // Then
        assertThat(((OverloadedConverter) converter).taken).isEqualTo("TestLogConfiguration");
    }

    @Test
    @DisplayName("Refuses to choose between constructors taking unrelated types of the configuration")
    void testRefusesAnAmbiguousConstructor() {
        // Given: a configuration implementing two interfaces, each taken by a constructor of its own
        var configuration = new LabeledConfiguration();

        // When / Then: rather than taking whichever the JVM happens to list first
        assertThatThrownBy(() -> LogConverter.newInstance(AmbiguousConverter.class.getName(), configuration))
                .isInstanceOf(NoSuchMethodException.class)
                .hasMessageContaining("none more specific than the others");
    }

    @Test
    @DisplayName("Creates the default converter by its name too")
    void testCreatesTheDefaultConverter() throws Exception {
        // When / Then
        assertThat(LogConverter.newInstance(DefaultLogConverter.class.getName(), CONFIGURATION))
                .isInstanceOf(DefaultLogConverter.class);
    }

    @Test
    @DisplayName("Refuses a converter without a constructor taking the configuration")
    void testRequiresAConstructorTakingTheConfiguration() {
        // When / Then: rather than creating one that would convert without it
        assertThatThrownBy(() -> LogConverter.newInstance(UnconfiguredConverter.class.getName(), CONFIGURATION))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("Reports the failure of the constructor of the converter")
    void testReportsAFailingConstructor() {
        // When / Then
        assertThatThrownBy(() -> LogConverter.newInstance(FailingConverter.class.getName(), CONFIGURATION))
                .isInstanceOf(InvocationTargetException.class);
    }

    @Test
    @DisplayName("Refuses a class that is not a converter, or cannot be found")
    void testRefusesWhatIsNotAConverter() {
        // When / Then
        assertThatThrownBy(() -> LogConverter.newInstance(String.class.getName(), CONFIGURATION))
                .isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> LogConverter.newInstance("does.not.Exist", CONFIGURATION))
                .isInstanceOf(ClassNotFoundException.class);
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

    interface Labeled {

    }

    static class LabeledConfiguration implements LogConfiguration, Labeled {

        @Override
        public String getApplication() {
            return "my-application";
        }

        @Override
        public String getHost() {
            return "my-host";
        }

        @Override
        public String getEnvironment() {
            return "my-environment";
        }

    }

    public static class AmbiguousConverter implements LogConverter {

        public AmbiguousConverter(LogConfiguration configuration) {
            // Either one would do, as far as the configuration is concerned
        }

        public AmbiguousConverter(Labeled configuration) {
            // Either one would do, as far as the configuration is concerned
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
