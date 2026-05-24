package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrainBatchSizeTest {

    @Test
    void createsValidBatchSize() {
        var result = DrainBatchSize.create(100);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo(100);
    }

    @Test
    void acceptsMinimumValidBatchSize() {
        var result = DrainBatchSize.create(1);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo(1);
    }

    @Test
    void acceptsMaximumValidBatchSize() {
        var result = DrainBatchSize.create(1_000);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo(1_000);
    }

    @Test
    void rejectsZero() {
        var result = DrainBatchSize.create(0);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainBatchSize must be greater than zero");
    }

    @Test
    void rejectsNegativeValue() {
        var result = DrainBatchSize.create(-1);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainBatchSize must be greater than zero");
    }

    @Test
    void rejectsValueExceedingMaximum() {
        var result = DrainBatchSize.create(1_001);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainBatchSize must not exceed 1000");
    }
}

