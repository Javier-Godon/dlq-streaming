package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessIdTest {
    @Test
    void createsValidProcessId() {
        var result = ProcessId.create("product-123_2026-05-23T10:15:30Z");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo("product-123_2026-05-23T10:15:30Z");
        assertThat(result.value().productReference()).isEqualTo("product-123");
        assertThat(result.value().timestampReference()).isEqualTo("2026-05-23T10:15:30Z");
    }

    @Test
    void rejectsNullProcessId() {
        var result = ProcessId.create(null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId is required");
    }

    @Test
    void rejectsBlankProcessId() {
        var result = ProcessId.create("   ");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId is required");
    }

    @Test
    void rejectsProcessIdWithoutTimestampSeparator() {
        var result = ProcessId.create("productOnly");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId must follow productReference_timestamp");
    }

    @Test
    void rejectsProcessIdWithoutProductReference() {
        var result = ProcessId.create("_2026-05-23T10:15:30Z");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId must follow productReference_timestamp");
    }

    @Test
    void rejectsProcessIdWithoutTimestampReference() {
        var result = ProcessId.create("product-123_");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId must follow productReference_timestamp");
    }
}

