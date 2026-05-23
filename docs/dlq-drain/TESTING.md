# DLQ Drain Testing Guide

## Why the tests are split

The DLQ drain has both business-critical behavior and heavy operational behavior. They are intentionally tested at different levels:

| Level | Files | Runs by default | Why |
|---|---|---:|---|
| Domain/unit | `*Test.java` under `domain/model` | Yes | Fast validation of value objects |
| Use-case unit | `DrainDeadLetters*Test.java` | Yes | Proves stop-on-first-receiver-failure and delete-after-success logic |
| Adapter unit | `DataPrepperDeadLetterReceiverTest` | Yes | Verifies Data Prepper HTTP request shape and failure mapping |
| PostgreSQL integration | `DeadLetterRepositoryIntegrationTest` | Yes if Docker available | Proves `FOR UPDATE SKIP LOCKED`, leases, and deletes against real PostgreSQL |
| BDD/acceptance | `DlqDrainCucumberTest` + `.feature` | Yes | Documents business behavior in Gherkin |
| Real E2E | `DlqDrainDataPrepperOpenSearchE2E` | No, opt-in | Requires external PostgreSQL + Data Prepper + OpenSearch |
| Large-volume simulation | `LargeVolumePostgresDrainSimulationE2E` | No, opt-in | Can insert and drain 1,000,000 PostgreSQL rows without Data Prepper/OpenSearch |

## Default verification

Run this before committing normal code changes:

```bash
./mvnw test
```

This runs unit tests, BDD tests, Spring context smoke test, adapter tests, and PostgreSQL Testcontainers integration tests when Docker is available.

## Acceptance / BDD only

```bash
./mvnw test -Pacceptance-tests
```

Feature file:

```text
src/test/resources/features/dlq_drain/drain_dead_letters.feature
```

Current scenarios:

- successfully drain claimed records.
- stop immediately when the receiver fails.

## PostgreSQL integration only

```bash
./mvnw test -Dtest='DeadLetterRepositoryIntegrationTest'
```

This validates:

- batch claim limit.
- workers do not claim the same rows.
- expired leases are reclaimable.
- delete succeeds only for the worker that owns the claim.
- delete fails when another worker attempts to delete the row.

## Data Prepper adapter only

```bash
./mvnw test -Dtest='DataPrepperDeadLetterReceiverTest'
```

This validates:

- JSON is POSTed as `application/json`.
- `X-Process-Id` and `Idempotency-Key` headers are sent.
- Data Prepper failures map to `EXTERNAL_SERVICE_ERROR`.

## Real Data Prepper/OpenSearch E2E

This test is opt-in because it needs a real external stack. It is discovered by Failsafe only through the `e2e-tests` profile.

```bash
./mvnw verify -Pe2e-tests \
  -DDLQ_E2E_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/dlq \
  -DDLQ_E2E_POSTGRES_USERNAME=postgres \
  -DDLQ_E2E_POSTGRES_PASSWORD=postgres \
  -DDLQ_E2E_DATAPREPPER_URL=http://localhost:2021/log/ingest \
  -DDLQ_E2E_OPENSEARCH_URL=http://localhost:9200 \
  -DDLQ_E2E_OPENSEARCH_INDEX=dlq-drain-e2e \
  -Ddlq.e2e.row-count=1000
```

For the one-million-row real E2E, change:

```bash
-Ddlq.e2e.row-count=1000000
```

If the required properties are absent, this suite is skipped by assumptions. That is expected and intentional.

## Large-volume PostgreSQL simulation

This test does not use Data Prepper/OpenSearch. It validates the heavy PostgreSQL drain path with the real repository and handler, plus an in-memory counting receiver.

Smoke run:

```bash
./mvnw verify -Pe2e-tests \
  -Ddlq.e2e.large-volume.enabled=true \
  -Ddlq.e2e.large-volume.row-count=1000 \
  -Ddlq.e2e.large-volume.batch-size=500
```

One-million-row simulation:

```bash
./mvnw verify -Pe2e-tests \
  -Ddlq.e2e.large-volume.enabled=true \
  -Ddlq.e2e.large-volume.row-count=1000000 \
  -Ddlq.e2e.large-volume.batch-size=1000
```

This test prints rows/sec when it completes.

## Current verified status

Verified during implementation:

```bash
./mvnw test
./mvnw verify -Pe2e-tests -DskipTests=false
./mvnw verify -Pe2e-tests \
  -Ddlq.e2e.large-volume.enabled=true \
  -Ddlq.e2e.large-volume.row-count=1000 \
  -Ddlq.e2e.large-volume.batch-size=500
```

The large-volume PostgreSQL simulation was run in smoke mode with `1000` rows and passed. The run drained `1000` rows in `2` batches of `500` using a real PostgreSQL Testcontainer, the real claim/delete repository, the real handler, and an in-memory counting receiver.

The full `1000000`-row simulation was not run in this workspace to avoid a long-running local benchmark during normal development. It is implemented and opt-in via the command above.

The real Data Prepper/OpenSearch E2E was not run with one million rows because this workspace does not provide an external Data Prepper/OpenSearch stack. The E2E profile was verified to discover those tests and skip them when the required environment is absent.

Use the commands above to run the full one-million-row real E2E in an environment that has PostgreSQL, Data Prepper, and OpenSearch configured.


