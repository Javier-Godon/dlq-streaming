package es.bluesolution.dlq_streaming.dlq_drain.domain.repository;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.PrimaryWriteCommand;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.PrimaryWriteReceipt;
import es.bluesolution.dlq_streaming.functional_framework.Result;

@Deprecated(since = "2026.05", forRemoval = true)
public interface PrimaryStorageWriter {
    Result<PrimaryWriteReceipt> write(PrimaryWriteCommand command);
}

