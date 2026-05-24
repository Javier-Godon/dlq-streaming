# DLQ Drain Testing Guide

## Why the tests are split

The DLQ drain has both business-critical behavior and heavy operational behavior. They are intentionally tested at different levels:

| Level | Files | Runs by default | Why |
|---|---|---:|---|
| Domain/unit | `*Test.java` under `domain/model` | Yes | Fast validation of value objects |
| Use-case unit | `DrainDeadLetters*Test.java` | Yes | Proves stop-on-first-receiver-failure and delete-after-success logic |
| Controller unit | `TriggerDrainControllerTest` | Yes | 200/503/500 HTTP status logic |
| Controller HTTP | `TriggerDrainControllerHttpTest` | Yes | Standalone MockMvc JSON body and status assertions |
| Adapter unit | `DataPrepperDeadLetterReceiverTest` | Yes | Verifies Data Prepper HTTP request shape and failure mapping |
| PostgreSQL integration | `DeadLetterRepositoryIntegrationTest` | Yes if Docker available | Proves `FOR UPDATE SKIP LOCKED`, leases, and deletes against real PostgreSQL |
| Network chaos (Data Prepper) | `DataPrepperNetworkChaosTest` | Yes if Docker available | Toxiproxy + WireMock: connect timeout, read timeout, 503 retry, 400 no-retry, TCP reset |
| Network chaos (PostgreSQL) | `PostgresNetworkChaosTest` | Yes if Docker available | Toxiproxy: DB connection drop, high latency — proving `socketTimeout` is mandatory |
| BDD/acceptance | `DlqDrainCucumberTest` + `.feature` | Yes | Documents business behavior in Gherkin |
| Kubernetes deployment | `KubernetesDeploymentTest` (Orders 1–9) | No, `-Pkubernetes-tests` | Deploys to k3s; proves health probes, security context, resource limits |
| Kubernetes operational | `KubernetesDeploymentTest` (Orders 10–16) | No, `-Pkubernetes-tests` | Exercises drain business behavior against the live cluster with real DB seeding |
| Kubernetes CronJob | `KubernetesDeploymentTest` (Order 17) | No, `-Pkubernetes-tests` | Creates a manual Job from the CronJob template; verifies Job.status.succeeded==1 |
| Kubernetes Data Prepper | `KubernetesDataPrepperTest` (Orders 1–4) | No, `-Pkubernetes-tests` | Deploys WireMock as mock Data Prepper; proves records forwarded with correct headers; 503 stops drain |
| Real E2E | `DlqDrainDataPrepperOpenSearchE2E` | No, opt-in | Requires external PostgreSQL + Data Prepper + OpenSearch |
| Large-volume simulation | `LargeVolumePostgresDrainSimulationE2E` | No, opt-in | Can insert and drain 1,000,000 PostgreSQL rows without Data Prepper/OpenSearch |

## Default verification

Run this before committing normal code changes:

```bash
./mvnw test
```

This runs unit tests, BDD tests, Spring context smoke test, adapter tests, network chaos tests, and PostgreSQL Testcontainers integration tests when Docker is available.

## Chaos tests only (Data Prepper + PostgreSQL)

```bash
./mvnw test -Dtest='DataPrepperNetworkChaosTest,PostgresNetworkChaosTest'
```

These tests require Docker (Toxiproxy + WireMock).

**TDD findings that produced production code changes:**

| Test | Finding | Fix |
|---|---|---|
| `connectTimeoutReturnsFailureWithinExpectedDuration` | No connect timeout → application hangs | `JdkClientHttpRequestFactory` with `connectTimeout` |
| `readTimeoutReturnsFailureWhenServerIsUnresponsive` | No read timeout → hangs on slow server | `factory.setReadTimeout(...)` |
| `retriesOnTransient503AndEventuallySucceeds` | No retry → fails on pod restarts | Exponential back-off retry loop |
| `badRequestFailsImmediatelyWithoutRetry` | 4xx must NOT retry | `isRetryable()` guard |
| `connectionDropReturnsFailure` (Postgres) | DB connection drop hangs indefinitely | `?socketTimeout=5` in JDBC URL |

## Kubernetes deployment tests

```bash
# Build the Docker image first (done automatically by the profile):
./mvnw test -Pkubernetes-tests
```

The `kubernetes-tests` Maven profile:
1. Builds the Docker image with `docker build -t dlq-streaming:k8s-test .`
2. Starts a k3s cluster (Testcontainers k3s — K8s 1.32 in Docker)
3. Loads the image into k3s containerd
4. Deploys PostgreSQL + dlq-streaming
5. Runs 16 assertions — 9 deployment probes followed by 7 operational scenarios
6. Tears down the cluster and removes the test image (`docker rmi dlq-streaming:k8s-test`)

**Deployment probes (Orders 1–9):**

| # | Test | What it proves |
|---|---|---|
| 1 | Pod becomes Ready | Startup probes pass within timeout |
| 2 | Liveness probe returns UP | `/actuator/health/liveness` is healthy |
| 3 | Readiness probe becomes healthy | `?socketTimeout` in JDBC URL prevents hang |
| 4 | `POST /drain/trigger` returns 200 | Drain endpoint is reachable and controller is registered |
| 5 | Response body contains drain fields | `claimedCount`, `storedCount`, etc. are serialised |
| 6 | Pod runs as UID 1001, non-root | Pod Security Standards compliance |
| 7 | Resource requests and limits defined | Prevents OOMKilled |
| 8 | Liveness, readiness, startup probes defined | All three probes present in deployment spec |
| 9 | Exactly one container | No unexpected sidecars injected |

**Operational scenarios (Orders 10–16):**

These tests use a Fabric8 `LocalPortForward` tunnel (postgres pod port 5432 → dynamic local port)
with a HikariCP datasource to seed and verify the `dlq.dead_letter_record` table directly from
the test JVM, exercising full end-to-end flows against the running cluster.

| # | Test | What it proves |
|---|---|---|
| 10 | Drain on empty table | Returns HTTP 200 with all counts = 0 |
| 11 | Full drain cycle | Seeds 3 records; all claimed, stored, and deleted in one call |
| 12 | Batch size enforcement | 15 records drain in exactly 2 runs (batch=10 from ConfigMap) |
| 13 | Idempotency | 3 consecutive calls on empty table all return 200, 0 counts |
| 14 | Concurrent drains | 2 parallel calls, total claimed = 10, zero double-processing (`SKIP LOCKED`) |
| 15 | Prometheus metrics | `/actuator/prometheus` exports JVM, process, and HTTP server metrics |
| 16 | Pod restart resilience | Kill pod, wait for replacement to be Ready, drain still works |

**CronJob (Order 17):**

| # | Test | What it proves |
|---|---|---|
| 17 | CronJob manual trigger | Creates a Job from the CronJob template; `Job.status.succeeded==1` after the curl command calls `/drain/trigger` |

## Kubernetes Data Prepper tests

`KubernetesDataPrepperTest` is a separate test class with its own isolated k3s cluster.
It deploys a WireMock instance inside k3s as a **mock Data Prepper**, configures
`DLQ_RECEIVER_TYPE=dataprepper`, and verifies the full HTTP receiver path against a real
Kubernetes network.

```bash
./mvnw test -Pkubernetes-tests -Dtest='KubernetesDataPrepperTest'
```

| # | Test | What it proves |
|---|---|---|
| 1 | All 5 records forwarded and cleared | storedCount=5, deletedCount=5, WireMock received 5 POST /log/ingest calls |
| 2 | Required headers sent | `X-Process-Id` and `Idempotency-Key` headers present and match process_id |
| 3 | 503 stops drain | When WireMock returns 503, drain returns `stoppedBecauseReceiverFailed=true`, records remain in DB |
| 4 | Idempotency with Data Prepper | Two consecutive drains: first processes 2 records, second finds 0 |

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

Verified during the latest session:

```bash
./mvnw test                   # 46 unit/integration/BDD tests
./mvnw test -Pkubernetes-tests  # 21 Kubernetes tests (17 deployment/operational + 4 Data Prepper)
```

The `kubernetes-tests` profile additionally verified:
- Full drain cycle (3 records → claimed=3, stored=3, deleted=3, table empty)
- Batch enforcement (15 records → 2 runs of 10 + 5)
- Concurrent drain calls (`SKIP LOCKED` prevents double-processing)
- Pod restart resilience (kill pod → replacement Ready → drain still works)
- Prometheus metrics endpoint serving JVM + HTTP metrics
- CronJob manual trigger (Job.status.succeeded==1 after curl POST /drain/trigger)
- Data Prepper integration (5 records → WireMock received 5 POST /log/ingest with correct headers)
- Data Prepper failure handling (503 → stoppedBecauseReceiverFailed=true, records remain in DB)

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

