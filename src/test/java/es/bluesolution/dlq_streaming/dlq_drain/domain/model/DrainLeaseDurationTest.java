package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DrainLeaseDurationTest {

    @Test
    void createsValidDurationFromDuration() {
        var result = DrainLeaseDuration.create(Duration.ofMinutes(5));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void createsValidDurationFromSeconds() {
        var result = DrainLeaseDuration.create(120L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value().getSeconds()).isEqualTo(120);
    }

    @Test
    void acceptsMinimumOneSec() {
        var result = DrainLeaseDuration.create(1L);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void acceptsMaximumOneHour() {
        var result = DrainLeaseDuration.create(Duration.ofHours(1));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void rejectsNullDuration() {
        var result = DrainLeaseDuration.create((Duration) null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainLeaseDuration is required");
    }

    @Test
    void rejectsZeroSeconds() {
        var result = DrainLeaseDuration.create(0L);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainLeaseDuration must be greater than zero seconds");
    }

    @Test
    void rejectsNegativeSeconds() {
        var result = DrainLeaseDuration.create(-5L);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainLeaseDuration must be greater than zero seconds");
    }

    @Test
    void rejectsDurationBelowOneSecond() {
        var result = DrainLeaseDuration.create(Duration.ofMillis(500));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainLeaseDuration must be at least one second");
    }

    @Test
    void rejectsDurationExceedingOneHour() {
        var result = DrainLeaseDuration.create(Duration.ofHours(1).plusSeconds(1));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainLeaseDuration must not exceed one hour");
    }
}

