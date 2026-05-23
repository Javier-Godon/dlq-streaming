# DLQ Drain E2E Test Strategy

## Decision

Do **not** put million-row PostgreSQL + Data Prepper + OpenSearch + chaos tests in the default integration test suite.

Use this split instead:

| Suite | Purpose | Runs by default? | Examples |
|---|---|---:|---|
| Unit | Domain/stage/handler branches | Yes | `ProcessIdTest`, handler stop-on-failure |
| Integration | One real adapter or database boundary | Yes, if Docker available | PostgreSQL `SKIP LOCKED` claim/delete/lease tests |
| Acceptance / BDD | Business behavior with controlled fakes | Yes or CI acceptance profile | successful drain, stop on receiver failure |
| E2E | Real topology and operational confidence | No, opt-in/nightly/manual | PostgreSQL -> Data Prepper -> OpenSearch |
| Performance / soak | Volume and long-running behavior | No, scheduled/nightly | 1,000,000+ rows, backlog drain rate |
| Resilience / chaos | Failure-mode confidence | No, dedicated environment | Toxiproxy latency/cut/reset, Data Prepper 5xx, OpenSearch slow indexing |

Reason: real E2E and chaos tests are valuable but slow, environment-dependent, and can be flaky. They should not block every local `./mvnw test` run.

## Realistic E2E topology

```text
PostgreSQL dlq.dead_letter_record
  -> DlqDrainDataPrepperOpenSearchE2E / application handler
  -> DataPrepperDeadLetterReceiver
  -> Data Prepper HTTP source
  -> OpenSearch sink
  -> OpenSearch _count/_search assertions
```

The E2E test should prove:

1. Rows are inserted into PostgreSQL with:
   - `dlq_id` technical ordering key.
   - `process_id` idempotency key.
   - `occurred_at` timestamp.
   - `payload JSONB`.
2. The drain claims rows in small batches.
3. The JSON reaches Data Prepper.
4. Data Prepper writes to OpenSearch.
5. OpenSearch eventually contains the expected documents.
6. DLQ rows are deleted only after receiver success.
7. On receiver failure, rows remain in PostgreSQL for retry.

## Implemented opt-in E2E harness

`src/test/java/es/bluesolution/dlq_streaming/dlq_drain/e2e/DlqDrainDataPrepperOpenSearchE2E.java`

This test is intentionally named `*E2E.java` and runs only with the `e2e-tests` Maven profile.

It expects an external real stack, so the project is not coupled to one specific Data Prepper/OpenSearch Docker image version in the normal build.

There is also a large-volume PostgreSQL simulation:

`src/test/java/es/bluesolution/dlq_streaming/dlq_drain/e2e/LargeVolumePostgresDrainSimulationE2E.java`

This test uses a real PostgreSQL Testcontainer and the real claim/delete repository plus the real application handler, but replaces Data Prepper/OpenSearch with an in-memory counting receiver. It is useful to validate PostgreSQL batching, delete-after-success behavior, and memory/backlog behavior without needing a full external observability stack.

Required properties or environment variables:

| Name | Description |
|---|---|
| `DLQ_E2E_POSTGRES_JDBC_URL` | PostgreSQL JDBC URL |
| `DLQ_E2E_POSTGRES_USERNAME` | PostgreSQL user |
| `DLQ_E2E_POSTGRES_PASSWORD` | PostgreSQL password |
| `DLQ_E2E_DATAPREPPER_URL` | Data Prepper HTTP source URL |
| `DLQ_E2E_OPENSEARCH_URL` | OpenSearch base URL |
| `DLQ_E2E_OPENSEARCH_INDEX` | OpenSearch index, default `dlq-drain-e2e` |
| `dlq.e2e.row-count` | Row count, default `1000000` |
| `dlq.e2e.batch-size` | Drain batch size, default `500` |
| `DLQ_E2E_BROKEN_DATAPREPPER_URL` | Optional broken/proxied URL for communication-failure scenario |

Run:

```bash
./mvnw verify -Pe2e-tests \
  -DDLQ_E2E_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/dlq \
  -DDLQ_E2E_POSTGRES_USERNAME=postgres \
  -DDLQ_E2E_POSTGRES_PASSWORD=postgres \
  -DDLQ_E2E_DATAPREPPER_URL=http://localhost:2021/log/ingest \
  -DDLQ_E2E_OPENSEARCH_URL=http://localhost:9200 \
  -DDLQ_E2E_OPENSEARCH_INDEX=dlq-drain-e2e \
  -Ddlq.e2e.row-count=1000000 \
  -Ddlq.e2e.batch-size=500
```

For a fast smoke run:

```bash
./mvnw verify -Pe2e-tests \
  -DDLQ_E2E_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/dlq \
  -DDLQ_E2E_POSTGRES_USERNAME=postgres \
  -DDLQ_E2E_POSTGRES_PASSWORD=postgres \
  -DDLQ_E2E_DATAPREPPER_URL=http://localhost:2021/log/ingest \
  -DDLQ_E2E_OPENSEARCH_URL=http://localhost:9200 \
  -Ddlq.e2e.row-count=1000
```

Run the large-volume PostgreSQL simulation with a smaller smoke size:

```bash
./mvnw verify -Pe2e-tests \
  -Ddlq.e2e.large-volume.enabled=true \
  -Ddlq.e2e.large-volume.row-count=1000 \
  -Ddlq.e2e.large-volume.batch-size=500
```

Run the one-million-row simulation:

```bash
./mvnw verify -Pe2e-tests \
  -Ddlq.e2e.large-volume.enabled=true \
  -Ddlq.e2e.large-volume.row-count=1000000 \
  -Ddlq.e2e.large-volume.batch-size=1000
```

### What is expected to be skipped

When `./mvnw verify -Pe2e-tests` is run without the external Data Prepper/OpenSearch/PostgreSQL properties, `DlqDrainDataPrepperOpenSearchE2E` is discovered and skipped by JUnit assumptions. That is intentional.

When `dlq.e2e.large-volume.enabled` is not `true`, `LargeVolumePostgresDrainSimulationE2E` is also discovered and skipped. That prevents accidental one-million-row tests in local development or normal CI.

## Data Prepper API assertions

The implemented receiver sends:

- HTTP `POST` to `dlq-drain.receiver.data-prepper.url`.
- `Content-Type: application/json`.
- Header `X-Process-Id: <process_id>`.
- Header `Idempotency-Key: <process_id>`.
- Body: exact PostgreSQL `payload` JSON.

Adapter-level tests verify the HTTP contract with `MockRestServiceServer`.

E2E tests verify that the final document count appears in OpenSearch. A stronger future assertion should query by `process_id` when the Data Prepper pipeline maps `process_id` into the indexed document or metadata.

## Failure and chaos matrix

| Scenario | Tool | Expected behavior |
|---|---|---|
| Data Prepper returns 5xx | WireMock / mock server / real DP misconfigured | Drain stops; failed row remains in DLQ |
| Data Prepper times out | Toxiproxy timeout/toxic or firewall rule | Drain stops; claimed rows retry after lease expiry |
| Connection reset | Toxiproxy reset peer | Drain stops; no delete for failed row |
| High latency | Toxiproxy latency toxic | Drain slows; does not claim unlimited rows |
| OpenSearch unavailable behind Data Prepper | Real DP + OpenSearch stopped | DP should fail/queue depending pipeline; DLQ behavior depends on DP HTTP response |
| Process crash after receiver success before delete | Kill app/test process or inject failure before delete | Duplicate delivery possible; receiver must be idempotent by `process_id` |
| PostgreSQL restart during claim | Docker restart / Chaos Mesh | Claim returns database failure; no receiver writes attempted |
| Lease expiry | Integration test / E2E sleep | Claimed rows become reclaimable |
| One million rows | E2E/performance profile | All rows eventually deleted from DLQ and visible in OpenSearch |

## Recommended tooling

- **Testcontainers PostgreSQL**: integration tests, already implemented.
- **MockRestServiceServer/WireMock**: adapter and acceptance tests for Data Prepper HTTP behavior.
- **Real Data Prepper + OpenSearch**: E2E profile only.
- **Toxiproxy**: deterministic network latency, cut, reset, timeout tests between the drain and Data Prepper.
- **Chaos Mesh / Chaos Monkey**: later, cluster-level chaos in Kubernetes or staging; not local default tests.
- **Resilience4j**: useful for receiver timeout/circuit-breaker policies if added to production code later; not required for current sequential backpressure design.

## Where each requested case belongs

- `1,000,000` rows: **E2E/performance**, not default integration.
- Real PostgreSQL claim/delete behavior: **integration**.
- Real Data Prepper HTTP response handling: **adapter integration/acceptance**.
- JSON reaches OpenSearch: **E2E**.
- Data Prepper 2xx/4xx/5xx/timeouts: **adapter tests + acceptance; selected E2E**.
- Bad communication / network partitions: **resilience/chaos E2E**.
- Business rule “stop on first receiver failure”: **unit + BDD acceptance + selected E2E**.

## Completion criteria for a production-grade E2E environment

- Data Prepper pipeline configuration is version-controlled next to the E2E environment.
- OpenSearch index template/mapping is version-controlled.
- E2E run exports metrics: rows/sec, receiver latency, delete latency, backlog, failures.
- E2E has a small smoke mode and a large `1,000,000` row mode.
- Chaos scenarios are tagged and can be run independently.
- E2E data uses unique `process_id` prefixes per run and cleans up OpenSearch indexes after completion.

