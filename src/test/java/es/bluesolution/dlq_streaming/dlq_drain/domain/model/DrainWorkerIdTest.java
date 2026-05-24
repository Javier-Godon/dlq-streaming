package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrainWorkerIdTest {

    @Test
    void createsValidWorkerId() {
        var result = DrainWorkerId.create("worker-a");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo("worker-a");
    }

    @Test
    void trimsWhitespace() {
        var result = DrainWorkerId.create("  worker-a  ");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo("worker-a");
    }

    @Test
    void rejectsNull() {
        var result = DrainWorkerId.create(null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainWorkerId is required");
    }

    @Test
    void rejectsBlank() {
        var result = DrainWorkerId.create("   ");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainWorkerId is required");
    }

    @Test
    void rejectsValueExceeding100Characters() {
        var tooLong = "w".repeat(101);

        var result = DrainWorkerId.create(tooLong);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainWorkerId must not exceed 100 characters");
    }

    @Test
    void acceptsExactly100Characters() {
        var exactly100 = "w".repeat(100);

        var result = DrainWorkerId.create(exactly100);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).hasSize(100);
    }
}

