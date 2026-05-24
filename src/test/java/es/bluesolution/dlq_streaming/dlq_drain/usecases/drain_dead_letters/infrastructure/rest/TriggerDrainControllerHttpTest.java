package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.infrastructure.rest;

import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersHandler;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersResult;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import es.bluesolution.dlq_streaming.functional_framework.RailwayErrorHandlingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.DATABASE_ERROR;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc HTTP-level tests for {@link TriggerDrainController}.
 *
 * <p>No Spring context is started — all dependencies are mocked.
 * This verifies HTTP contract: status codes, response body shape, and routing.</p>
 */
class TriggerDrainControllerHttpTest {

    private MockMvc mockMvc;
    private DrainDeadLettersHandler handler;

    @BeforeEach
    void setUp() {
        handler = mock(DrainDeadLettersHandler.class);
        var properties = defaultProperties();
        var controller = new TriggerDrainController(handler, properties);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new RailwayErrorHandlingConfig())
                .build();
    }

    @Test
    void postTriggerReturns200WithBodyWhenDrainSucceeds() throws Exception {
        when(handler.handle(any())).thenReturn(Result.success(
                new DrainDeadLettersResult(2, 5, 5, 5, false,
                        Optional.of("product-5_ts"), Optional.empty())));

        mockMvc.perform(post("/drain/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedCount").value(5))
                .andExpect(jsonPath("$.storedCount").value(5))
                .andExpect(jsonPath("$.deletedCount").value(5))
                .andExpect(jsonPath("$.stoppedBecauseReceiverFailed").value(false))
                .andExpect(jsonPath("$.releasedExpiredLeases").value(2));
    }

    @Test
    void postTriggerReturns200WhenNothingToDrain() throws Exception {
        when(handler.handle(any())).thenReturn(Result.success(
                new DrainDeadLettersResult(0, 0, 0, 0, false, Optional.empty(), Optional.empty())));

        mockMvc.perform(post("/drain/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedCount").value(0))
                .andExpect(jsonPath("$.stoppedBecauseReceiverFailed").value(false));
    }

    @Test
    void postTriggerReturns503WhenReceiverFailed() throws Exception {
        when(handler.handle(any())).thenReturn(Result.success(
                new DrainDeadLettersResult(0, 3, 2, 2, true,
                        Optional.of("product-3_ts"), Optional.of("Data Prepper unavailable"))));

        mockMvc.perform(post("/drain/trigger"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.stoppedBecauseReceiverFailed").value(true))
                .andExpect(jsonPath("$.stopReason").value("Data Prepper unavailable"));
    }

    @Test
    void postTriggerReturns500WhenHandlerReturnsFailure() throws Exception {
        when(handler.handle(any())).thenReturn(
                Result.failure(DATABASE_ERROR, "Database not reachable", null));

        mockMvc.perform(post("/drain/trigger"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void postTriggerReturns400WhenValidationFails() throws Exception {
        when(handler.handle(any())).thenReturn(
                Result.failure(VALIDATION_ERROR, "DrainBatchSize must be greater than zero", null));

        mockMvc.perform(post("/drain/trigger"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOnTriggerEndpointIsNotAllowed() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/drain/trigger"))
                .andExpect(status().isMethodNotAllowed());
    }

    private static DlqDrainProperties defaultProperties() {
        var p = new DlqDrainProperties();
        p.getScheduler().setBatchSize(100);
        p.getScheduler().setWorkerId("test-worker");
        p.getScheduler().setLeaseSeconds(120);
        p.getScheduler().setReleaseExpiredLeases(true);
        return p;
    }
}

