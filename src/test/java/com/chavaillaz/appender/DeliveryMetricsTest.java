package com.chavaillaz.appender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers {@link DeliveryMetrics}, the snapshot an appender and a delivery hand to a metrics library, and the
 * builder a delivery tracking its own values creates it through.
 */
@DisplayName("DeliveryMetrics")
class DeliveryMetricsTest {

    @Test
    @DisplayName("Starts every value at zero, as the metrics of a pipeline tracking none")
    void testEveryValueStartsAtZero() throws Exception {
        // when
        DeliveryMetrics metrics = DeliveryMetrics.builder().build();

        // then
        assertThat(values()).extracting(Method::getName).contains("queuedEvents", "droppedDocuments");
        for (Method value : values()) {
            assertThat(((Number) value.invoke(metrics)).longValue()).as(value.getName()).isZero();
        }
        assertThat(metrics).isEqualTo(DeliveryMetrics.NONE);
    }

    @Test
    @DisplayName("Keeps each value under the name it was set by")
    void testEachValueIsKeptUnderItsName() {
        // when
        DeliveryMetrics metrics = everyValueSet();

        // then
        assertThat(metrics.queuedEvents()).isEqualTo(1);
        assertThat(metrics.pendingDocuments()).isEqualTo(2);
        assertThat(metrics.pendingBytes()).isEqualTo(3_000_000_000L);
        assertThat(metrics.transmissionsInFlight()).isEqualTo(4);
        assertThat(metrics.transmittedDocuments()).isEqualTo(5_000_000_000L);
        assertThat(metrics.failedTransmissions()).isEqualTo(6_000_000_000L);
        assertThat(metrics.droppedDocuments()).isEqualTo(7_000_000_000L);
    }

    @Test
    @DisplayName("Derives metrics replacing some values and carrying the others over")
    void testWithCarriesEveryOtherValueOver() throws Exception {
        // given: every value away from zero, so that one left behind comes back as zero
        DeliveryMetrics original = everyValueSet();
        for (Method value : values()) {
            assertThat(((Number) value.invoke(original)).longValue()).as(value.getName()).isNotZero();
        }

        // when
        DeliveryMetrics derived = original.with().queuedEvents(10).droppedDocuments(70).build();

        // then
        assertThat(derived.queuedEvents()).isEqualTo(10);
        assertThat(derived.droppedDocuments()).isEqualTo(70);
        assertThat(derived.with().queuedEvents(1).droppedDocuments(7_000_000_000L).build()).isEqualTo(original);
        assertThat(derived).isNotSameAs(original);
    }

    @Test
    @DisplayName("Equals metrics with the same values, with the same hash code")
    void testEqualsMetricsWithTheSameValues() {
        // given
        DeliveryMetrics original = everyValueSet();

        // when
        DeliveryMetrics same = everyValueSet();

        // then
        assertThat(same).isNotSameAs(original).isEqualTo(original).hasSameHashCodeAs(original);
        assertThat(original).isNotEqualTo(null).isNotEqualTo("metrics");
        assertThat(original.hashCode()).isNotEqualTo(DeliveryMetrics.NONE.hashCode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleValues")
    @DisplayName("Differs from metrics a single value tells apart")
    void testDiffersByASingleValue(String value, UnaryOperator<DeliveryMetrics.Builder> change) {
        // given
        DeliveryMetrics original = everyValueSet();

        // when: that value alone changes
        DeliveryMetrics other = change.apply(original.with()).build();

        // then
        assertThat(other).as("Metrics differing by their " + value).isNotEqualTo(original);
    }

    static Stream<Arguments> singleValues() {
        return Stream.of(
                arguments("queuedEvents", value(builder -> builder.queuedEvents(0))),
                arguments("pendingDocuments", value(builder -> builder.pendingDocuments(0))),
                arguments("pendingBytes", value(builder -> builder.pendingBytes(0))),
                arguments("transmissionsInFlight", value(builder -> builder.transmissionsInFlight(0))),
                arguments("transmittedDocuments", value(builder -> builder.transmittedDocuments(0))),
                arguments("failedTransmissions", value(builder -> builder.failedTransmissions(0))),
                arguments("droppedDocuments", value(builder -> builder.droppedDocuments(0))));
    }

    @Test
    @DisplayName("Compares every value it has, none being left out of the comparison")
    void testEveryValueIsCompared() {
        // then: a value added later joins the cases above, or this fails
        assertThat(singleValues().map(arguments -> (String) arguments.get()[0]))
                .containsExactlyInAnyOrderElementsOf(values().stream().map(Method::getName).toList());
    }

    @Test
    @DisplayName("Lists every value with its name when printed")
    void testToStringListsEveryValue() {
        // when
        String printed = everyValueSet().toString();

        // then
        assertThat(printed).isEqualTo("DeliveryMetrics[queuedEvents=1, pendingDocuments=2, pendingBytes=3000000000, "
                + "transmissionsInFlight=4, transmittedDocuments=5000000000, failedTransmissions=6000000000, "
                + "droppedDocuments=7000000000]");
        for (Method value : values()) {
            assertThat(printed).contains(value.getName() + "=");
        }
    }

    /**
     * Metrics whose every value is set, each to a number of its own, those counting without bound past what an
     * {@code int} holds.
     */
    private static DeliveryMetrics everyValueSet() {
        return DeliveryMetrics.builder()
                .queuedEvents(1)
                .pendingDocuments(2)
                .pendingBytes(3_000_000_000L)
                .transmissionsInFlight(4)
                .transmittedDocuments(5_000_000_000L)
                .failedTransmissions(6_000_000_000L)
                .droppedDocuments(7_000_000_000L)
                .build();
    }

    /**
     * The accessors of the values: the public methods without argument the class declares, but for the one
     * deriving a builder and those every object has.
     */
    private static List<Method> values() {
        return Arrays.stream(DeliveryMetrics.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> !Set.of("with", "hashCode", "toString").contains(method.getName()))
                .toList();
    }

    /**
     * Types a change to a builder, the lambdas of the arguments above having no target type of their own.
     */
    private static UnaryOperator<DeliveryMetrics.Builder> value(UnaryOperator<DeliveryMetrics.Builder> change) {
        return change;
    }

}
