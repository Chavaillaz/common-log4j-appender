package com.chavaillaz.appender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TransmissionResult")
class TransmissionResultTest {

    @Test
    @DisplayName("Keeps a partial failure as it was returned, however it was built")
    void testPartialFailureKeepsACopyOfTheRejectedElements() {
        // given: a list the transport keeps using once it returned the result, built without the factory
        var rejected = new ArrayList<>(List.of("first", "second"));
        var result = new TransmissionResult.PartialFailure<>(rejected, null);

        // when
        rejected.clear();

        // then: what the engine retries is what was rejected, and nothing can change it any longer
        assertThat(result.rejected()).containsExactly("first", "second");
        assertThatThrownBy(() -> result.rejected().add("third")).isInstanceOf(UnsupportedOperationException.class);
    }

}
