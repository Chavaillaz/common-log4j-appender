package com.chavaillaz.appender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DeliveryHealth}, what an appender and a delivery answer a health indicator or an alert with.
 */
@DisplayName("DeliveryHealth")
class DeliveryHealthTest {

    @Test
    @DisplayName("Keeps the status and the time since the last success it is created with")
    void testKeepsWhatItIsCreatedWith() {
        // when
        DeliveryHealth health = DeliveryHealth.of(DeliveryHealth.Status.BACKING_OFF, Duration.ofSeconds(90));

        // then
        assertThat(health.status()).isEqualTo(DeliveryHealth.Status.BACKING_OFF);
        assertThat(health.sinceLastSuccess()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("Cannot be created without a status or a time since the last success, naming what is missing")
    void testRejectsWhatIsMissing() {
        // then
        assertThatThrownBy(() -> DeliveryHealth.of(null, Duration.ZERO))
                .isInstanceOf(NullPointerException.class).hasMessageStartingWith("status");
        assertThatThrownBy(() -> DeliveryHealth.of(DeliveryHealth.Status.UP, null))
                .isInstanceOf(NullPointerException.class).hasMessageStartingWith("sinceLastSuccess");
    }

    @Test
    @DisplayName("Equals a health with the same status and time, and none differing by either")
    void testEqualityComparesBoth() {
        // given
        DeliveryHealth health = DeliveryHealth.of(DeliveryHealth.Status.RETRYING, Duration.ofMinutes(2));

        // then
        assertThat(DeliveryHealth.of(DeliveryHealth.Status.RETRYING, Duration.ofSeconds(120)))
                .isNotSameAs(health).isEqualTo(health).hasSameHashCodeAs(health);
        assertThat(health)
                .isNotEqualTo(DeliveryHealth.of(DeliveryHealth.Status.BACKING_OFF, Duration.ofMinutes(2)))
                .isNotEqualTo(DeliveryHealth.of(DeliveryHealth.Status.RETRYING, Duration.ofMinutes(3)))
                .isNotEqualTo(null)
                .isNotEqualTo("health");
        assertThat(health.hashCode())
                .isNotEqualTo(DeliveryHealth.of(DeliveryHealth.Status.RETRYING, Duration.ofMinutes(3)).hashCode());
    }

    @Test
    @DisplayName("Gives its status and the time since the last success when printed")
    void testToStringGivesBoth() {
        // then
        assertThat(DeliveryHealth.of(DeliveryHealth.Status.DOWN, Duration.ofSeconds(5)))
                .hasToString("DeliveryHealth[status=DOWN, sinceLastSuccess=PT5S]");
    }

}
