package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.infrastructure.rest;

import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersCommand;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersHandler;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersResult;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.DATABASE_ERROR;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriggerDrainControllerTest {

    @Mock
    DrainDeadLettersHandler handler;

    @Mock
    DlqDrainProperties properties;

    @Mock
    DlqDrainProperties.Scheduler schedulerConfig;

    @InjectMocks
    TriggerDrainController controller;

    @Test
    void returns200WhenAllRecordsDrained() {
        configureScheduler(100, "worker", 120, true);
        when(handler.handle(any(DrainDeadLettersCommand.class))).thenReturn(Result.success(
                successResult(3, 3, 3, false)));

        var response = controller.trigger();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().claimedCount()).isEqualTo(3);
        assertThat(response.getBody().storedCount()).isEqualTo(3);
        assertThat(response.getBody().deletedCount()).isEqualTo(3);
        assertThat(response.getBody().stoppedBecauseReceiverFailed()).isFalse();
    }

    @Test
    void returns200WithZeroCountsWhenNothingToDrain() {
        configureScheduler(100, "worker", 120, false);
        when(handler.handle(any())).thenReturn(Result.success(
                successResult(0, 0, 0, false)));

        var response = controller.trigger();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().claimedCount()).isZero();
    }

    @Test
    void returns503WhenReceiverFailed() {
        configureScheduler(50, "worker-a", 60, true);
        when(handler.handle(any())).thenReturn(Result.success(
                successResult(2, 1, 1, true)));

        var response = controller.trigger();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().stoppedBecauseReceiverFailed()).isTrue();
    }

    @Test
    void returns500WhenHandlerReturnsFailure() {
        configureScheduler(100, "worker", 120, false);
        when(handler.handle(any())).thenReturn(
                Result.failure(DATABASE_ERROR, "Connection pool exhausted", null));

        var response = controller.trigger();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void returns400WhenCommandValidationFails() {
        configureScheduler(0, "worker", 120, false); // batchSize=0 is invalid
        when(handler.handle(any())).thenReturn(
                Result.failure(VALIDATION_ERROR, "DrainBatchSize must be greater than zero", null));

        var response = controller.trigger();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNull();
    }

    private void configureScheduler(int batchSize, String workerId, long leaseSeconds, boolean releaseExpired) {
        when(properties.getScheduler()).thenReturn(schedulerConfig);
        when(schedulerConfig.getBatchSize()).thenReturn(batchSize);
        when(schedulerConfig.getWorkerId()).thenReturn(workerId);
        when(schedulerConfig.getLeaseSeconds()).thenReturn(leaseSeconds);
        when(schedulerConfig.isReleaseExpiredLeases()).thenReturn(releaseExpired);
    }

    private static DrainDeadLettersResult successResult(
            int claimed, int stored, int deleted, boolean receiverFailed) {
        return new DrainDeadLettersResult(
                0, claimed, stored, deleted, receiverFailed,
                claimed > 0 ? Optional.of("product-1_ts") : Optional.empty(),
                receiverFailed ? Optional.of("receiver down") : Optional.empty());
    }
}

