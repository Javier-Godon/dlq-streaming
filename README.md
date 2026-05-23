# dlq-streaming

Reliable Java 25 / Spring Boot DLQ drain for moving very large PostgreSQL dead-letter tables into downstream receivers without loading the table into memory.

The first real receiver adapter targets **Data Prepper**, which then forwards the JSON payloads to **OpenSearch**.

## Problem this project solves

Given a large PostgreSQL dead-letter table, the application must:

1. read rows incrementally;
2. send each JSON payload to a receiver;
3. delete a DLQ row only after the receiver acknowledges success;
4. stop immediately when the receiver fails;
5. resume later without losing data.

The delivery model is **at-least-once**. Receiver-side idempotency must use `process_id`.

## Architecture decision

Use a queue-drain pattern, not a classic long-running cursor/ETL stream:

- small polling batches;
- short transactions;
- `FOR UPDATE SKIP LOCKED`;
- claim leases;
- sequential receiver writes for strong backpressure;
- delete-after-success only;
- retry via undeleted rows and expired leases.

See the full context docs:

- [`docs/dlq-drain/README.md`](docs/dlq-drain/README.md)
- [`docs/dlq-drain/TESTING.md`](docs/dlq-drain/TESTING.md)
- [`docs/dlq-drain/E2E_TEST_STRATEGY.md`](docs/dlq-drain/E2E_TEST_STRATEGY.md)

## PostgreSQL table shape

The Flyway migration creates `dlq.dead_letter_record` with:

- `dlq_id` — monotonic technical key for ordering;
- `process_id` — idempotency key, expected shape `productReference_timestamp`;
- `occurred_at` — original DLQ timestamp;
- `payload JSONB` — JSON sent to the receiver;
- `status`, `claimed_by`, `lease_until`, `attempt_count` — queue/lease metadata.

## Receiver abstraction

The drain depends on a generic port:

```java
public interface DeadLetterReceiver {
	Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command);
}
```

Implemented receivers:

- in-memory placeholder for local/default startup;
- Data Prepper HTTP receiver.

Data Prepper configuration:

```yaml
dlq-drain:
  receiver:
	type: dataprepper
	data-prepper:
	  url: http://dataprepper:2021/log/ingest
```

The scheduler is disabled by default:

```yaml
dlq-drain:
  scheduler:
	enabled: false
```

Enable it only after PostgreSQL and a production receiver are configured.

## Virtual threads decision

The core drain loop intentionally does **not** parallelize rows with virtual threads or structured concurrency.

Reason: the bottleneck is PostgreSQL locks, receiver capacity, network behavior, and backpressure — not Java thread cost. Parallel fan-out would make “stop immediately when the receiver fails” harder and could create many in-flight duplicate deliveries.

Virtual threads may still be used by Spring infrastructure or future bounded receiver internals, but the handler processes claimed rows sequentially.

## Run tests

Default verification:

```bash
./mvnw test
```

BDD acceptance tests:

```bash
./mvnw test -Pacceptance-tests
```

PostgreSQL integration test:

```bash
./mvnw test -Dtest='DeadLetterRepositoryIntegrationTest'
```

Data Prepper adapter test:

```bash
./mvnw test -Dtest='DataPrepperDeadLetterReceiverTest'
```

## Opt-in E2E and large-volume tests

Real PostgreSQL -> Data Prepper -> OpenSearch E2E smoke:

```bash
./mvnw verify -Pe2e-tests \
  -DDLQ_E2E_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/dlq \
  -DDLQ_E2E_POSTGRES_USERNAME=postgres \
  -DDLQ_E2E_POSTGRES_PASSWORD=postgres \
  -DDLQ_E2E_DATAPREPPER_URL=http://localhost:2021/log/ingest \
  -DDLQ_E2E_OPENSEARCH_URL=http://localhost:9200 \
  -Ddlq.e2e.row-count=1000
```

One-million-row real E2E:

```bash
./mvnw verify -Pe2e-tests \
  -DDLQ_E2E_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/dlq \
  -DDLQ_E2E_POSTGRES_USERNAME=postgres \
  -DDLQ_E2E_POSTGRES_PASSWORD=postgres \
  -DDLQ_E2E_DATAPREPPER_URL=http://localhost:2021/log/ingest \
  -DDLQ_E2E_OPENSEARCH_URL=http://localhost:9200 \
  -Ddlq.e2e.row-count=1000000
```

One-million-row PostgreSQL simulation without Data Prepper/OpenSearch:

```bash
./mvnw verify -Pe2e-tests \
  -Ddlq.e2e.large-volume.enabled=true \
  -Ddlq.e2e.large-volume.row-count=1000000 \
  -Ddlq.e2e.large-volume.batch-size=1000
```

The real Data Prepper/OpenSearch E2E tests are skipped unless the required external environment variables/properties are present. This is intentional.

## Current maturity

Implemented and tested:

- domain value objects;
- P3 drain use case;
- PostgreSQL JDBC repository;
- Data Prepper receiver adapter;
- disabled-by-default scheduler;
- BDD acceptance scenarios;
- PostgreSQL Testcontainers integration tests;
- opt-in real E2E harness;
- opt-in large-volume PostgreSQL simulation harness.

Next production hardening steps:

- version-control a Data Prepper pipeline and OpenSearch index template;
- add Toxiproxy-based network chaos tests;
- add metrics for backlog, claimed, sent, deleted, failed, and rows/sec;
- add operational endpoints to pause/resume the drain if needed.
