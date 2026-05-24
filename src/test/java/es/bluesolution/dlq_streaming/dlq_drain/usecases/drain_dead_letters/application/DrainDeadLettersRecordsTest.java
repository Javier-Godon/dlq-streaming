package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DrainDeadLettersData} and {@link DrainDeadLettersResult} compact constructors.
 *
 * <p>The compact constructors contain defensive null-guards that replace {@code null} with
 * sensible defaults ({@code List.of()}, {@code Optional.empty()}). These branches are only
 * reachable via the canonical constructor, so they are tested here explicitly.</p>
 */
class DrainDeadLettersRecordsTest {

    private static final DrainDeadLettersCommand COMMAND =
            new DrainDeadLettersCommand(10, "worker-1", 120L, true);

    // ── DrainDeadLettersData null guards ───────────────────────────────────────

    @Test
    void drainDeadLettersDataDefaultsNullClaimedRecordsToEmptyList() {
        var data = new DrainDeadLettersData(
                COMMAND,
                null, null, null,
                0, null /* ← null claimedRecords */, 0, 0, false,
                Optional.empty(), Optional.empty());

        assertThat(data.claimedRecords()).isNotNull().isEmpty();
    }

    @Test
    void drainDeadLettersDataDefaultsNullLastProcessedToEmpty() {
        var data = new DrainDeadLettersData(
                COMMAND,
                null, null, null,
                0, null, 0, 0, false,
                null /* ← null lastProcessedProcessId */, Optional.empty());

        assertThat(data.lastProcessedProcessId()).isEqualTo(Optional.empty());
    }

    @Test
    void drainDeadLettersDataDefaultsNullStopReasonToEmpty() {
        var data = new DrainDeadLettersData(
                COMMAND,
                null, null, null,
                0, null, 0, 0, false,
                Optional.empty(), null /* ← null stopReason */);

        assertThat(data.stopReason()).isEqualTo(Optional.empty());
    }

    // ── DrainDeadLettersResult null guards ─────────────────────────────────────

    @Test
    void drainDeadLettersResultDefaultsNullLastProcessedToEmpty() {
        var result = new DrainDeadLettersResult(
                0, 0, 0, 0, false,
                null /* ← null */, Optional.empty());

        assertThat(result.lastProcessedProcessId()).isEqualTo(Optional.empty());
    }

    @Test
    void drainDeadLettersResultDefaultsNullStopReasonToEmpty() {
        var result = new DrainDeadLettersResult(
                0, 0, 0, 0, false,
                Optional.empty(), null /* ← null */);

        assertThat(result.stopReason()).isEqualTo(Optional.empty());
    }

    @Test
    void drainDeadLettersResultPreservesNonNullOptionals() {
        var result = new DrainDeadLettersResult(
                2, 5, 5, 5, false,
                Optional.of("product-1_2026-05-23T10:15:30Z"),
                Optional.of("batch complete"));

        assertThat(result.releasedExpiredLeases()).isEqualTo(2);
        assertThat(result.claimedCount()).isEqualTo(5);
        assertThat(result.lastProcessedProcessId()).isEqualTo(Optional.of("product-1_2026-05-23T10:15:30Z"));
        assertThat(result.stopReason()).isEqualTo(Optional.of("batch complete"));
    }
}


