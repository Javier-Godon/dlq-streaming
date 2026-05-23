# DLQ Drain Context

## Purpose and boundary

This context drains a very large PostgreSQL dead-letter table into a downstream receiver without loading the table into memory and without losing records when the receiver becomes unavailable.

The required behavior is a reliable streaming drain pattern:

1. Read a small number of dead-letter records incrementally.
2. Send each record to the configured receiver.
3. Delete the dead-letter record only after the receiver acknowledges success.
4. Stop consuming immediately when the receiver fails.
5. Resume later safely, accepting at-least-once delivery and requiring idempotent receiver writes keyed by `process_id`.

This context does **not** own the receiver's domain model. It only owns the drain workflow, the dead-letter table access pattern, and the integration contract used to send JSON payloads to downstream receivers.

## Decision summary

Use **polling with short transactions, atomic claiming, leases, and idempotent writes**.

Do not use a single long-running PostgreSQL cursor for this workload. Cursor streaming is useful for read-only analytics or bounded exports, but a DLQ drain behaves like a queue: records are consumed, deleted, retried, and must stop quickly when the downstream system fails.

For the full E2E/performance/resilience strategy, including one-million-row PostgreSQL -> Data Prepper -> OpenSearch tests and chaos scenarios, see [`E2E_TEST_STRATEGY.md`](E2E_TEST_STRATEGY.md).

## Virtual threads and structured concurrency decision

Default decision: **do not parallelize the drain loop with virtual threads or structured concurrency**.

Virtual threads are excellent for reducing the cost of blocking I/O threads, but they do not reduce PostgreSQL row locks, receiver capacity, network bandwidth, or the need for backpressure. In this DLQ drain, the most important invariant is: **when the receiver fails, stop consuming immediately**. A parallel fan-out with many virtual threads would make that harder because records may already be in flight, increasing duplicate delivery, receiver pressure, and lease/delete race complexity.

Structured concurrency is also not used in the core drain because, on Java 25, it is still not a good fit for this strict sequential stop-on-first-failure drain unless the project explicitly opts into preview/incubator APIs and accepts more complex cancellation semantics.

Allowed usage:

- The scheduler may run on Spring virtual threads if the deployment enables Spring's virtual-thread support globally, but the drain handler still processes each claimed record sequentially.
- A future receiver adapter may use virtual threads internally for its own bounded I/O implementation, but it must expose the same `DeadLetterReceiver` contract and must not claim additional database rows while a previous receiver batch is failing.

Forbidden for now:

- One virtual thread per DLQ row for an unbounded table.
- Parallel receiver fan-out without a strict concurrency limit, idempotency, metrics, and cancellation tests.
- Claiming new batches while previous receiver writes are still in flight.

## Current implementation status

Implemented in this project:

- Domain value objects and records under `src/main/java/es/bluesolution/dlq_streaming/dlq_drain/domain/model/`.
- Repository and generic receiver ports under `src/main/java/es/bluesolution/dlq_streaming/dlq_drain/domain/repository/`.
- P3 application slice under `src/main/java/es/bluesolution/dlq_streaming/dlq_drain/usecases/drain_dead_letters/application/`.
- PostgreSQL JDBC repository under `src/main/java/es/bluesolution/dlq_streaming/dlq_drain/shared/infrastructure/persistence/`.
- Disabled-by-default scheduler under `src/main/java/es/bluesolution/dlq_streaming/dlq_drain/shared/infrastructure/scheduling/`.
- Flyway migration `src/main/resources/db/migration/V1__init_dlq_drain.sql`.
- Data Prepper receiver adapter under `src/main/java/es/bluesolution/dlq_streaming/dlq_drain/shared/infrastructure/receiver/`.
- Unit tests for `ProcessId`, timestamp validation, stages, and handler behavior.
- PostgreSQL integration tests for claim/delete/lease behavior.
- BDD feature scenarios for successful drain and stop-on-receiver-failure behavior.

The scheduler is disabled by default. Enable it only when a real PostgreSQL datasource and production primary-storage adapter are configured:

```yaml
dlq-drain:
  scheduler:
    enabled: true
    batch-size: 100
    worker-id: dlq-drain-worker-1
    lease-seconds: 120
    release-expired-leases: true
    fixed-delay-millis: 30000
    initial-delay-millis: 5000
```

The included in-memory receiver is only a safe placeholder. For the first real receiver implementation, set `dlq-drain.receiver.type=dataprepper` and configure the Data Prepper HTTP endpoint.

```yaml
dlq-drain:
  receiver:
    type: dataprepper
    data-prepper:
      url: http://dataprepper:2021/log/ingest
```

### Recommended pattern

```text
Scheduler / operator trigger
  -> DrainDeadLettersHandler
      -> claim a small batch in PostgreSQL using SKIP LOCKED + lease
      -> for each claimed record, sequentially:
           -> send JSON payload to receiver using process_id as idempotency key
           -> delete the DLQ row only after successful receiver acknowledgement
           -> if receiver write fails: stop the drain loop immediately
      -> release or let leases expire for records not completed
```

### Why this is preferred

| Concern | Cursor streaming | Spring Batch cursor reader | Polling/chunk queue semantics |
|---|---|---|---|
| Memory safety | Good with fetch size | Good with cursor reader | Good with small batches |
| Transaction length | Risky if cursor spans the drain | Risky with `JdbcCursorItemReader` | Short and explicit |
| Locks | Can be long-lived | Can be long-lived | Short row locks during claim/delete |
| Stop on receiver failure | Possible but cursor remains operational concern | Possible but job semantics may hide queue behavior | Natural: stop loop immediately |
| Resume after crash | Requires careful cursor/job state | Supported by Batch metadata but still needs idempotency | Natural via undeleted rows and expired leases |
| Horizontal scaling | Harder | Possible but custom | Natural with `FOR UPDATE SKIP LOCKED` |
| Operational model | ETL/export mindset | Batch job mindset | Queue drain mindset |

## Spring Batch position

Spring Batch can be useful for scheduling, job metadata, retries, observability, and operator controls, but avoid using it as a classic ETL cursor over the whole DLQ table.

If Spring Batch is used, prefer one of these shapes:

1. **Tasklet-based drain**: a tasklet repeatedly calls the application handler until no records remain, a max-record limit is reached, or the receiver fails.
2. **Custom chunk reader/writer**: the reader claims records atomically with `SKIP LOCKED`; the writer sends to the receiver and deletes successful rows.

Avoid:

- `JdbcCursorItemReader` for the entire DLQ table.
- Huge commit intervals.
- Holding a database transaction open while a long stream writes to an external system.
- Treating DLQ drain as a full-table ETL export.

## Domain model

Initial domain facts are intentionally small because the payload is not important yet.

### Aggregate/value objects

- `DeadLetterRecord`
  - `ProcessId processId` — business/idempotency key. Current format: `productReference_timestamp`.
  - `DeadLetterOccurredAt occurredAt` — timestamp of the original dead-letter fact.
  - `DeadLetterPayload payload` — opaque JSON payload for the receiver adapter.
  - `DrainLease lease` — optional lease metadata when a row is claimed.

### Important note about identifiers

`process_id` is a good idempotency key, but it should not be the only operational ordering key for a very large drain table unless its format is guaranteed to be unique, immutable, and correctly sortable.

Recommended table shape includes a monotonic technical key:

```sql
CREATE TABLE dlq.dead_letter_record (
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
    CONSTRAINT uk_dead_letter_process UNIQUE (process_id)
);

CREATE INDEX idx_dead_letter_pending
    ON dlq.dead_letter_record (status, dlq_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_dead_letter_occurred_at
    ON dlq.dead_letter_record (occurred_at);

CREATE INDEX idx_dead_letter_lease_expired
    ON dlq.dead_letter_record (lease_until)
    WHERE status = 'PROCESSING';
```

If the existing table only has `process_id`, keep `process_id` as the idempotency key and add `dlq_id`, `occurred_at`, and `payload JSONB` before implementing a high-volume drain.

## Repository contract

The domain repository should expose queue semantics with `Result<T>` return values:

```java
public interface DeadLetterRepository {
    Result<List<DeadLetterRecord>> claimNextBatch(DrainBatchSize batchSize, DrainWorkerId workerId, DrainLeaseDuration leaseDuration, Instant claimedAt);
    Result<ProcessId> deleteClaimed(ProcessId processId, DrainWorkerId workerId);
    Result<Integer> releaseExpiredLeases(Instant now);
}
```

All persistence methods convert database exceptions into `Result.failure(DATABASE_ERROR, ...)` and do not throw application-level validation exceptions.

## Claim query

Prefer atomic claim-and-return instead of `SELECT` followed by later update.

```sql
WITH next_rows AS (
    SELECT dlq_id
    FROM dlq.dead_letter_record
    WHERE status = 'PENDING'
       OR (status = 'PROCESSING' AND lease_until < now())
    ORDER BY dlq_id
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
UPDATE dlq.dead_letter_record d
SET status = 'PROCESSING',
    claimed_by = :workerId,
    lease_until = now() + (:leaseSeconds * interval '1 second'),
    attempt_count = d.attempt_count + 1,
    updated_at = now()
FROM next_rows n
WHERE d.dlq_id = n.dlq_id
RETURNING d.dlq_id, d.process_id, d.occurred_at, d.payload, d.attempt_count;
```

This keeps the transaction short: lock, claim, return, commit.

## Delete after successful receiver acknowledgement

Delete by both `process_id` and `claimed_by` so one worker cannot delete another worker's lease.

```sql
DELETE FROM dlq.dead_letter_record
WHERE process_id = :processId
  AND status = 'PROCESSING'
  AND claimed_by = :workerId;
```

A delete count of `1` means the record was removed. A delete count of `0` should be treated as a concurrency/lease failure and returned as a `Result.failure(CONFLICT, ...)` or equivalent business failure.

## Receiver contract

Receiver adapters must be idempotent:

```java
public interface DeadLetterReceiver {
    Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command);
}

public record ReceiveDeadLetterCommand(ProcessId processId, DeadLetterPayload payload) { }
public record ReceiveDeadLetterAck(ProcessId processId, String receiverReference) { }
```

`process_id` is the idempotency key. If a previous attempt wrote successfully but the application crashed before deleting the DLQ row, the next drain attempt must receive a successful idempotent acknowledgement from the receiver and then delete the DLQ row.

### Data Prepper receiver

The first concrete receiver is `DataPrepperDeadLetterReceiver`:

- Sends `payload` as `application/json` to `dlq-drain.receiver.data-prepper.url`.
- Sends `X-Process-Id` and `Idempotency-Key` headers with the `process_id` value.
- Treats any HTTP/client exception as `EXTERNAL_SERVICE_ERROR`.
- Returns `ReceiveDeadLetterAck` only after Data Prepper returns a successful HTTP response.

Data Prepper is in front of OpenSearch, so OpenSearch is not called directly by this bounded context.

## Use cases

### `drain_dead_letters`

Application slice:

```text
dlq_drain/usecases/drain_dead_letters/application/
  DrainDeadLettersCommand.java
  DrainDeadLettersResult.java
  DrainDeadLettersData.java
  DrainDeadLettersPorts.java
  DrainDeadLettersStages.java
  DrainDeadLettersHandler.java
```

Suggested result:

```java
public record DrainDeadLettersResult(
    int claimedCount,
    int storedCount,
    int deletedCount,
    boolean stoppedBecauseReceiverFailed,
    String lastProcessedProcessId
) { }
```

Do not use `Result<Void>`, `Result<Object>`, `Result.success(null)`, or a fake `Result<String>` acknowledgment.

### Handler flow

The handler should make execution boundaries visible:

```text
validate command
release expired leases, if configured
claim batch within transaction
for each claimed record:
  send to receiver outside the claim transaction
  if receiver fails, stop immediately and return failure/result summary
  delete claimed record within a short transaction
build meaningful result
```

For implementation, the drain loop may be a service-level orchestration around smaller P3 use cases, or a single P3 use case that uses explicit transaction contexts for claim and delete stages. Do not hide transaction boundaries inside stages.

## Failure semantics

| Failure point | Expected behavior | Recovery |
|---|---|---|
| Claim query fails | Return database failure; no receiver writes attempted | Retry later |
| Receiver write fails | Stop consuming immediately | Claimed but undeleted rows retry after lease expiry or explicit release |
| Receiver write succeeds, delete succeeds | Record is drained | No retry |
| Receiver write succeeds, delete fails | Return failure after successful receiver acknowledgement | Retry later; receiver must be idempotent by `process_id` |
| Process crashes after claim | Claimed rows become visible after `lease_until` | Retry later |
| Process crashes after receiver write before delete | Duplicate delivery possible | Receiver idempotency returns success, then delete |

Delivery guarantee: **at-least-once** from DLQ to the receiver. Exactly-once effects are achieved only if the receiver honors `process_id` as an idempotency key.

## Batch sizing and backpressure

Initial defaults:

- `batchSize`: 100–500 rows.
- `leaseDuration`: 2–5 minutes, longer than expected worst-case write time for a batch.
- `maxRecordsPerRun`: configurable safety limit for scheduled runs.
- `pollInterval`: short when backlog exists, longer when empty.
- `concurrency`: start with 1 worker; scale horizontally only after idempotency and metrics are proven.

Backpressure rule:

- The first receiver failure stops the current drain run.
- The scheduler should delay the next run or use exponential backoff.
- Do not keep reading/claiming new rows while the receiver is failing.

## Persistence rules

- Use flat JDBC/JPA mappings only; no JPA relationships are needed.
- Add indexes for claim predicates: status, lease, and ordering key.
- Keep transactions short.
- Do not annotate repository implementations with `@Transactional`; use execution contexts at handler boundaries.
- Do not delete before the receiver acknowledges success.

## Testing strategy

Minimum tests for the first implementation:

1. `ProcessIdTest`
   - valid `productReference_timestamp`.
   - null/blank/malformed values.
2. `DrainDeadLettersStagesTest`
   - command validation.
   - receiver failure stops the loop.
   - delete is skipped when write fails.
   - delete failure is surfaced after successful primary write.
3. `DrainDeadLettersHandlerTest`
   - null command.
   - empty batch.
   - successful batch.
   - receiver failure after N successes stops further writes.
4. `DeadLetterRepositoryIntegrationTest`
   - claims only the configured limit.
   - concurrent workers do not claim the same rows (`SKIP LOCKED`).
   - expired leases are reclaimable.
   - successful delete removes only the claimed worker's row.
5. Optional Spring Batch test, if Batch is used as the scheduler/orchestrator.

## Implementation plan

### Phase 1 — Architecture and schema

- Create `docs/dlq-drain/README.md` with this decision.
- Add Flyway migration for `dlq.dead_letter_record` or adapt the existing table.
- Prefer adding `dlq_id`, `status`, `claimed_by`, `lease_until`, `attempt_count`, `created_at`, and `updated_at` if they do not exist.

### Phase 2 — Domain and ports

- Add value objects: `ProcessId`, `DrainBatchSize`, `DrainWorkerId`, `DrainLeaseDuration`.
- Add `DeadLetterRecord` aggregate/record.
- Add repository port `DeadLetterRepository`.
- Add receiver port `DeadLetterReceiver` returning `Result<ReceiveDeadLetterAck>`.

### Phase 3 — Drain use case

- Add P3 use case `drain_dead_letters`.
- Implement stages for validation, lease cleanup, claim batch, write each record, delete successes, and build summary.
- Keep claim/delete database operations in short transaction execution contexts.
- Stop immediately on first receiver failure.

### Phase 4 — Infrastructure

- Implement PostgreSQL repository using JDBC or flat JPA with native SQL for `UPDATE ... RETURNING`.
- Implement in-memory receiver placeholder and Data Prepper receiver adapter.
- Add scheduler or Spring Batch Tasklet orchestration.
- Expose operational endpoint only if needed, with explicit authorization.

### Phase 5 — Verification and operations

- Add integration tests with PostgreSQL Testcontainers.
- Add metrics: claimed, stored, deleted, failed, lease-expired, backlog count, run duration.
- Add logs keyed by `process_id` and `worker_id`.
- Document runbook actions: pause drain, resume drain, inspect stuck leases, replay a process.

## Verification commands

Adapt the filters once the implementation classes exist:

```bash
./mvnw compile -q
./mvnw test
./mvnw test -Dtest='*DlqDrain*Test,*DeadLetter*Test'
./mvnw verify -Pe2e-tests -Ddlq.e2e.row-count=1000
```

