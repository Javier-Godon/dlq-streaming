# dlq-streaming

> **Reliable PostgreSQL → Data Prepper / OpenSearch dead-letter drain.**  
> Java 25 · Spring Boot 4 · Railway-Oriented Programming · Kubernetes-native.

---

## What does this do?

When an event-streaming pipeline fails to process a message, it writes it to a **dead-letter queue (DLQ) table** in PostgreSQL.  
`dlq-streaming` **drains** that table: it reads each failed message, forwards it to a **receiver** (Data Prepper → OpenSearch), and deletes the row only after the receiver confirms success.

```
Kubernetes CronJob  ──POST /drain/trigger──►  dlq-streaming
                                                │
                                    FOR UPDATE SKIP LOCKED
                                                │
                                          PostgreSQL (DLQ)
                                                │
                                     HTTP POST /log/ingest
                                                │
                                          Data Prepper
                                                │
                                           OpenSearch
```

**Delivery guarantee**: at-least-once. Receiver must be idempotent on `process_id`.

---

## Requirements to run everything (including Kubernetes tests)

| Requirement | Why |
|---|---|
| **Docker** (engine + CLI) | Testcontainers, image builds, k3s in Docker |
| **Java 25 JDK** *(only for IDE / direct `javac` usage)* | Not needed when using `./mvnw` — Maven wrapper downloads its own distribution |

> **No other tools need to be installed.**  
> The Maven wrapper (`./mvnw`) handles Maven. Docker handles everything else (PostgreSQL, k3s, WireMock, Toxiproxy).

---

## Quick start

```bash
# 1. Run all default tests (unit, integration, BDD — Docker required for containers)
./mvnw test

# 2. Run Kubernetes deployment tests (builds Docker image, starts k3s, deploys, asserts)
./mvnw test -Pkubernetes-tests

# 3. Run BDD acceptance tests only
./mvnw test -Pacceptance-tests

# 4. Generate the Spring Boot fat JAR
./mvnw package -DskipTests
```

---

## Documentation index

| Document | Audience | Description |
|---|---|---|
| **This file** | Everyone | Overview, quick start, test commands |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Developers / Architects | Full architectural deep-dive: DDD, ROP, P3, auto-configuration |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | Developers | Local setup, IDE, library guide, code patterns |
| [`docs/dlq-drain/README.md`](docs/dlq-drain/README.md) | Developers | DLQ Drain bounded context: domain, use cases, API |
| [`docs/dlq-drain/TESTING.md`](docs/dlq-drain/TESTING.md) | Developers / QA | Full test pyramid explanation and commands |
| [`docs/dlq-drain/KUBERNETES.md`](docs/dlq-drain/KUBERNETES.md) | DevOps / Platform | Kubernetes deployment, health probes, resource sizing |
| [`docs/dlq-drain/E2E_TEST_STRATEGY.md`](docs/dlq-drain/E2E_TEST_STRATEGY.md) | Developers / QA | End-to-end strategy with real infrastructure |

---

## Architecture in one paragraph

The service is a single **Spring Boot application** with a single bounded context (`dlq_drain`).  
Business logic uses **Railway-Oriented Programming** (ROP): every operation returns `Result<T>` — either a success with a typed value or a failure with an error code and message. There are no thrown exceptions in domain code.  
The drain use case is structured as a **P3 vertical slice** (Handler → Data → Ports → Stages), a testable, composition-based approach where each stage is a pure or impure static function.  
Persistence uses **flat JDBC** (`JdbcClient`) — no ORM, no eager loading, no relationship annotations.  
The application is **Kubernetes-native**: no internal scheduler, triggered by a CronJob via `POST /drain/trigger`.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design rationale.

---

## Test pyramid

| Layer | Tests | Runs by default |
|---|---|---|
| Domain value objects | `ProcessIdTest`, `DeadLetterOccurredAtTest` | ✅ |
| Stages (pure functions) | `DrainDeadLettersStagesTest` | ✅ |
| Handler (orchestration) | `DrainDeadLettersHandlerTest` | ✅ |
| Controller (HTTP status) | `TriggerDrainControllerTest` | ✅ |
| Controller (MockMvc HTTP) | `TriggerDrainControllerHttpTest` | ✅ |
| Receiver (HTTP adapter) | `DataPrepperDeadLetterReceiverTest` | ✅ |
| PostgreSQL integration | `DeadLetterRepositoryIntegrationTest` | ✅ (Docker) |
| Network chaos — Data Prepper | `DataPrepperNetworkChaosTest` | ✅ (Docker) |
| Network chaos — PostgreSQL | `PostgresNetworkChaosTest` | ✅ (Docker) |
| BDD acceptance | `DlqDrainCucumberTest` | ✅ |
| Kubernetes deployment | `KubernetesDeploymentTest` | `-Pkubernetes-tests` |
| Real E2E (full stack) | `DlqDrainDataPrepperOpenSearchE2E` | `-Pe2e-tests` |
| Large-volume simulation | `LargeVolumePostgresDrainSimulationE2E` | `-Pe2e-tests` |

---

## Configuration reference

### Required

| Environment variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL — **always include `?socketTimeout=5`** |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL user |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password |

### Optional

| Environment variable | Default | Description |
|---|---|---|
| `DLQ_RECEIVER_TYPE` | `in-memory` | `in-memory` (no-op) or `dataprepper` |
| `DLQ_DATA_PREPPER_URL` | `http://localhost:2021/log/ingest` | Data Prepper ingest endpoint |
| `DLQ_DP_CONNECT_TIMEOUT_MS` | `5000` | HTTP connect timeout (ms) |
| `DLQ_DP_READ_TIMEOUT_MS` | `30000` | HTTP read timeout (ms) |
| `DLQ_DP_MAX_RETRY_ATTEMPTS` | `3` | Max retries (transient HTTP errors only) |
| `DLQ_BATCH_SIZE` | `100` | Records per drain batch |
| `DLQ_WORKER_ID` | `dlq-drain-worker` | Worker identity (use pod name in K8s) |
| `DLQ_LEASE_SECONDS` | `120` | Claim lease TTL (seconds) |
| `DLQ_RELEASE_EXPIRED_LEASES` | `true` | Release stale leases at each run start |
| `DLQ_DRAIN_API_KEY` | *(not set)* | Bearer API key for `/drain/trigger` |
| `SERVER_PORT` | `8080` | Main HTTP port |
| `MANAGEMENT_PORT` | `8081` | Actuator (health, metrics, prometheus) port |

> ⚠️ **`?socketTimeout=5` in the JDBC URL is mandatory in production.**  
> Without it, HikariCP can block indefinitely on a network partition. Validated by `PostgresNetworkChaosTest`.

---

## Kubernetes quick-start

```bash
# Build the image
docker build -t dlq-streaming:latest .

# Apply via Kustomize
kubectl apply -k k8s/base

# Trigger drain manually
kubectl run drain-test --rm -it --image=curlimages/curl -- \
  curl -sf -X POST http://dlq-streaming:8080/drain/trigger
```

See [`docs/dlq-drain/KUBERNETES.md`](docs/dlq-drain/KUBERNETES.md) for the full deployment guide.

---

## PostgreSQL table shape

Flyway migration `V1__init_dlq_drain.sql` creates `dlq.dead_letter_record`:

| Column | Type | Description |
|---|---|---|
| `dlq_id` | `BIGINT GENERATED ALWAYS AS IDENTITY` | Monotonic sort key (claim ordering) |
| `process_id` | `VARCHAR(300) UNIQUE` | Idempotency key (`productRef_timestamp`) |
| `occurred_at` | `TIMESTAMPTZ` | Original DLQ timestamp |
| `payload` | `JSONB` | JSON body forwarded to the receiver |
| `status` | `VARCHAR(30)` | `PENDING` or `PROCESSING` |
| `claimed_by` | `VARCHAR(100)` | Worker that holds the current lease |
| `lease_until` | `TIMESTAMPTZ` | Lease expiry (for crash recovery) |
| `attempt_count` | `INTEGER` | Incremented on each claim |

---

## Why this design?

- **No ORM / JPA**: flat JDBC is explicit, predictable, and avoids N+1 and lazy-load surprises.
- **No internal scheduler**: the Kubernetes CronJob gives visibility (`kubectl get cronjobs`), failure alerting, and concurrency control for free.
- **`FOR UPDATE SKIP LOCKED`**: each pod claims a disjoint set of rows — safe for multiple concurrent replicas without a distributed lock.
- **Delete-after-success only**: rows survive pod crashes; expired leases are reclaimed on the next run.
- **Result everywhere**: no `null` returns, no thrown business exceptions — failures are values.

