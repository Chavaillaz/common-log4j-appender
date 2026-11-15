package com.chavaillaz.appender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers {@link DeliveryOptions}, the value the pipeline tuning settings moved into when they stopped being
 * one default method per knob on {@link LogConfiguration}. The defaults themselves are asserted here
 * because they are the contract for every appender that never touches them: silently changing one changes
 * the behavior of every such appender, so it should have to break a test to do so.
 */
@DisplayName("DeliveryOptions")
class DeliveryOptionsTest {

    @Test
    @DisplayName("Defaults to the values documented as the pipeline's out-of-the-box behavior")
    void testDefaults() {
        DeliveryOptions options = DeliveryOptions.defaults();

        assertThat(options.flushThreshold()).isEqualTo(1);
        assertThat(options.flushInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(options.flushLevel()).isNull();
        assertThat(options.maxConcurrentTransmissions()).isEqualTo(4);
        assertThat(options.maxPendingDocuments()).isEqualTo(10_000);
        assertThat(options.maxRequestDocuments()).isEqualTo(Integer.MAX_VALUE);
        assertThat(options.maxConsecutiveFailures()).isEqualTo(5);
        assertThat(options.maxQueuedEvents()).isEqualTo(10_000);
        assertThat(options.retryBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(options.maxRetryBackoff()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.closeTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("A configuration that overrides nothing gets exactly those defaults")
    void testConfigurationDefaultsToTheSameOptions() {
        // given: the minimum a LogConfiguration can implement, now that tuning lives in one value
        LogConfiguration configuration = new LogConfiguration() {

            @Override
            public String getApplication() {
                return "application";
            }

            @Override
            public String getHost() {
                return "host";
            }

            @Override
            public String getEnvironment() {
                return "environment";
            }

        };

        // then
        assertThat(configuration.getDeliveryOptions()).isEqualTo(DeliveryOptions.defaults());

        // and: the same instance on every read, since it is read several times per document and the
        // interface asks implementations not to build a new one each time -- its own default included
        assertThat(configuration.getDeliveryOptions()).isSameAs(configuration.getDeliveryOptions());
    }

    @Test
    @DisplayName("Derives a new instance overriding one setting and carrying the rest over")
    void testWithCarriesEveryOtherSettingOver() throws Exception {
        // given: every setting away from its default, so that one left behind comes back as another value
        DeliveryOptions original = everySettingAwayFromItsDefault();
        assertThat(settings()).extracting(Method::getName).contains("flushThreshold", "closeTimeout");
        for (Method setting : settings()) {
            assertThat(setting.invoke(original))
                    .as("The " + setting.getName() + " this test derives from should not be the default one")
                    .isNotEqualTo(setting.invoke(DeliveryOptions.defaults()));
        }

        // when
        DeliveryOptions derived = original.with().maxPendingDocuments(50_000).build();

        // then: only the overridden setting differs, the rest (including a deliberately null one) is kept
        assertThat(derived.maxPendingDocuments()).isEqualTo(50_000);
        assertThat(derived.closeTimeout()).isNull();
        assertThat(derived.with().maxPendingDocuments(1_000).build()).isEqualTo(original);
        assertThat(derived).isNotSameAs(original);
    }

    @Test
    @DisplayName("Equals options built with the same settings, with the same hash code")
    void testEqualsOptionsWithTheSameSettings() {
        // given
        DeliveryOptions original = everySettingAwayFromItsDefault();

        // when
        DeliveryOptions same = everySettingAwayFromItsDefault();

        // then
        assertThat(same).isNotSameAs(original).isEqualTo(original).hasSameHashCodeAs(original);
        assertThat(original).isNotEqualTo(null).isNotEqualTo("options");
        assertThat(original.hashCode()).isNotEqualTo(DeliveryOptions.defaults().hashCode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleSettings")
    @DisplayName("Differs from options a single setting tells apart")
    void testDiffersByASingleSetting(String setting, UnaryOperator<DeliveryOptions.Builder> change) {
        // given
        DeliveryOptions original = everySettingAwayFromItsDefault();

        // when: that setting alone takes another value
        DeliveryOptions other = change.apply(original.with()).build();

        // then
        assertThat(other).as("Options differing by their " + setting).isNotEqualTo(original);
    }

    static Stream<Arguments> singleSettings() {
        return Stream.of(
                arguments("flushThreshold", setting(builder -> builder.flushThreshold(501))),
                arguments("flushInterval", setting(builder -> builder.flushInterval(Duration.ofSeconds(8)))),
                arguments("flushLevel", setting(builder -> builder.flushLevel(null))),
                arguments("maxConcurrentTransmissions", setting(builder -> builder.maxConcurrentTransmissions(3))),
                arguments("maxPendingDocuments", setting(builder -> builder.maxPendingDocuments(1_001))),
                arguments("maxRequestDocuments", setting(builder -> builder.maxRequestDocuments(251))),
                arguments("maxPendingBytes", setting(builder -> builder.maxPendingBytes(4_000_001))),
                arguments("maxRequestBytes", setting(builder -> builder.maxRequestBytes(1_000_001))),
                arguments("maxConsecutiveFailures", setting(builder -> builder.maxConsecutiveFailures(10))),
                arguments("maxQueuedEvents", setting(builder -> builder.maxQueuedEvents(3_001))),
                arguments("retryBackoff", setting(builder -> builder.retryBackoff(Duration.ofMillis(301)))),
                arguments("maxRetryBackoff", setting(builder -> builder.maxRetryBackoff(Duration.ofMinutes(3)))),
                arguments("closeTimeout", setting(builder -> builder.closeTimeout(Duration.ofSeconds(1)))));
    }

    @Test
    @DisplayName("Compares every setting it has, none being left out of the comparison")
    void testEverySettingIsCompared() {
        // then: a setting added later joins the cases above, or this fails
        assertThat(singleSettings().map(arguments -> (String) arguments.get()[0]))
                .containsExactlyInAnyOrderElementsOf(settings().stream().map(Method::getName).toList());
    }

    @Test
    @DisplayName("Lists every setting with its value when printed")
    void testToStringListsEverySetting() {
        // when
        String printed = everySettingAwayFromItsDefault().toString();

        // then
        assertThat(printed).startsWith("DeliveryOptions[").endsWith("]")
                .contains("flushThreshold=500", "flushInterval=PT7S", "flushLevel=WARN", "maxPendingBytes=4000000",
                        "retryBackoff=PT0.3S", "closeTimeout=null");
        for (Method setting : settings()) {
            assertThat(printed).contains(setting.getName() + "=");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfRangeSettings")
    @DisplayName("Rejects a setting out of its range when built, naming it")
    void testRejectsOutOfRangeSettings(String setting, UnaryOperator<DeliveryOptions.Builder> misconfiguration) {
        // given
        var builder = misconfiguration.apply(DeliveryOptions.builder());

        // when
        var exception = catchThrowable(builder::build);

        // then: a configuration naming it fails to load, rather than a delivery misbehaving once it runs
        assertThat(exception).isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(setting);
    }

    static Stream<Arguments> outOfRangeSettings() {
        return Stream.of(
                arguments("flushThreshold", setting(builder -> builder.flushThreshold(0))),
                arguments("flushInterval", setting(builder -> builder.flushInterval(Duration.ZERO))),
                arguments("flushInterval", setting(builder -> builder.flushInterval(Duration.ofNanos(999_999)))),
                arguments("flushInterval", setting(builder -> builder.flushInterval(Duration.ofSeconds(Long.MAX_VALUE)))),
                arguments("maxConcurrentTransmissions", setting(builder -> builder.maxConcurrentTransmissions(0))),
                arguments("maxPendingDocuments", setting(builder -> builder.maxPendingDocuments(0))),
                arguments("maxRequestDocuments", setting(builder -> builder.maxRequestDocuments(-1))),
                arguments("maxPendingBytes", setting(builder -> builder.maxPendingBytes(0))),
                arguments("maxRequestBytes", setting(builder -> builder.maxRequestBytes(Long.MIN_VALUE))),
                arguments("maxConsecutiveFailures", setting(builder -> builder.maxConsecutiveFailures(0))),
                arguments("maxQueuedEvents", setting(builder -> builder.maxQueuedEvents(0))),
                arguments("retryBackoff", setting(builder -> builder.retryBackoff(Duration.ofMillis(-1)))),
                arguments("maxRetryBackoff", setting(builder -> builder.maxRetryBackoff(Duration.ofMillis(-1)))),
                arguments("closeTimeout", setting(builder -> builder.closeTimeout(Duration.ofMillis(-1)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSettings")
    @DisplayName("Rejects a missing setting when built, naming it")
    void testRejectsMissingSettings(String setting, UnaryOperator<DeliveryOptions.Builder> misconfiguration) {
        // given
        var builder = misconfiguration.apply(DeliveryOptions.builder());

        // when
        var exception = catchThrowable(builder::build);

        // then
        assertThat(exception).isInstanceOf(NullPointerException.class).hasMessageStartingWith(setting);
    }

    static Stream<Arguments> missingSettings() {
        return Stream.of(
                arguments("flushInterval", setting(builder -> builder.flushInterval(null))),
                arguments("retryBackoff", setting(builder -> builder.retryBackoff(null))),
                arguments("maxRetryBackoff", setting(builder -> builder.maxRetryBackoff(null))));
    }

    @Test
    @DisplayName("Accepts every setting at the edge of its range")
    void testAcceptsTheEdgesOfEveryRange() {
        // when
        var smallest = DeliveryOptions.builder()
                .flushThreshold(1)
                .flushInterval(Duration.ofMillis(1))
                .maxConcurrentTransmissions(1)
                .maxPendingDocuments(1)
                .maxRequestDocuments(1)
                .maxPendingBytes(1)
                .maxRequestBytes(1)
                .maxConsecutiveFailures(1)
                .maxQueuedEvents(1)
                .retryBackoff(Duration.ZERO)
                .maxRetryBackoff(Duration.ZERO)
                .closeTimeout(Duration.ZERO)
                .build();
        var largest = DeliveryOptions.builder()
                .flushThreshold(Integer.MAX_VALUE)
                .maxConcurrentTransmissions(Integer.MAX_VALUE)
                .maxConsecutiveFailures(Integer.MAX_VALUE)
                .maxQueuedEvents(Integer.MAX_VALUE)
                .closeTimeout(null)
                .build();

        // then
        assertThat(smallest.closeTimeout()).isEqualTo(Duration.ZERO);
        assertThat(largest.closeTimeout()).isNull();
    }

    /**
     * Options whose every setting differs from its default.
     */
    private static DeliveryOptions everySettingAwayFromItsDefault() {
        return DeliveryOptions.builder()
                .flushThreshold(500)
                .flushInterval(Duration.ofSeconds(7))
                .flushLevel(Level.WARN)
                .maxConcurrentTransmissions(2)
                .maxPendingDocuments(1_000)
                .maxRequestDocuments(250)
                .maxPendingBytes(4_000_000)
                .maxRequestBytes(1_000_000)
                .maxConsecutiveFailures(9)
                .maxQueuedEvents(3_000)
                .retryBackoff(Duration.ofMillis(300))
                .maxRetryBackoff(Duration.ofMinutes(2))
                .closeTimeout(null)
                .build();
    }

    /**
     * The accessors of the settings: the public methods without argument the class declares, but for the one
     * deriving a builder and those every object has.
     */
    private static List<Method> settings() {
        return Arrays.stream(DeliveryOptions.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> !Set.of("with", "hashCode", "toString").contains(method.getName()))
                .toList();
    }

    /**
     * Types a change to a builder, the lambdas of the arguments above having no target type of their own.
     */
    private static UnaryOperator<DeliveryOptions.Builder> setting(UnaryOperator<DeliveryOptions.Builder> change) {
        return change;
    }

}
