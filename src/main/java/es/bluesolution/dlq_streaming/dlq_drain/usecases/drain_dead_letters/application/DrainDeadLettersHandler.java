package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import es.bluesolution.dlq_streaming.functional_framework.execution.ExecutionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Clock;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

@Service
@ConditionalOnBean({DeadLetterRepository.class, DeadLetterReceiver.class, ExecutionContext.class, Clock.class})
public class DrainDeadLettersHandler {
    private final DeadLetterRepository deadLetterRepository;
    private final DeadLetterReceiver deadLetterReceiver;
    private final ExecutionContext txContext;
    private final Clock clock;

    public DrainDeadLettersHandler(
            DeadLetterRepository deadLetterRepository,
            DeadLetterReceiver deadLetterReceiver,
            ExecutionContext txContext,
            Clock clock) {
        this.deadLetterRepository = deadLetterRepository;
        this.deadLetterReceiver = deadLetterReceiver;
        this.txContext = txContext;
        this.clock = clock;
    }

    public Result<DrainDeadLettersResult> handle(DrainDeadLettersCommand command) {
        if (command == null) {
            return Result.failure(VALIDATION_ERROR, "Command is mandatory", null);
        }

        var data = DrainDeadLettersData.initialize(command);
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, clock);

        var claimed = txContext.execute(() -> DrainDeadLettersStages.parseCommand(data)
                .flatMap(d -> DrainDeadLettersStages.releaseExpiredLeases(d, ports))
                .flatMap(d -> DrainDeadLettersStages.claimBatch(d, ports)));

        if (claimed.isFailure()) {
            return Result.failure(claimed.failure());
        }

        var current = claimed.value();
        for (var record : current.claimedRecords()) {
            var receiveCommand = DrainDeadLettersStages.buildReceiveCommand(record);
            if (receiveCommand.isFailure()) {
                return Result.failure(receiveCommand.failure());
            }

            var receiveResult = deadLetterReceiver.receive(receiveCommand.value());
            if (receiveResult.isFailure()) {
                current = DrainDeadLettersStages.markReceiverFailure(current, record, receiveResult.failure().message());
                return DrainDeadLettersStages.buildResult(current);
            }

            current = DrainDeadLettersStages.markStored(current, record);

            var dataAfterStore = current;
            var deleted = txContext.execute(() -> DrainDeadLettersStages.deleteClaimed(dataAfterStore, record, ports));
            if (deleted.isFailure()) {
                return Result.failure(deleted.failure());
            }
            current = deleted.value();
        }

        return DrainDeadLettersStages.buildResult(current);
    }
}




