CREATE SCHEMA IF NOT EXISTS dlq;

CREATE TABLE IF NOT EXISTS dlq.dead_letter_record (
    dlq_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_id VARCHAR(300) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    claimed_by VARCHAR(100),
    lease_until TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uk_dead_letter_process UNIQUE (process_id),
    CONSTRAINT ck_dead_letter_status CHECK (status IN ('PENDING', 'PROCESSING'))
);

CREATE INDEX IF NOT EXISTS idx_dead_letter_pending
    ON dlq.dead_letter_record (status, dlq_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_dead_letter_occurred_at
    ON dlq.dead_letter_record (occurred_at);

CREATE INDEX IF NOT EXISTS idx_dead_letter_processing_lease
    ON dlq.dead_letter_record (lease_until)
    WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_dead_letter_claimed_by
    ON dlq.dead_letter_record (claimed_by)
    WHERE status = 'PROCESSING';

