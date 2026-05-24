# DLQ Drain — Bounded Context

## Purpose

Polls PostgreSQL for dead-letter records that failed delivery, streams them to a downstream
receiver (Data Prepper), and deletes them once acknowledged.  The drain is triggered by a
**Kubernetes CronJob** via `POST /drain/trigger` — no internal scheduler.

---

## Use cases

| Use case | Handler | Status |
|---|---|---|
| Drain dead letters | `DrainDeadLettersHandler` | ✅ implemented |

---

## Aggregates / value objects

| Class | Type | Description |
|---|---|---|
| `DeadLetterRecord` | Aggregate | Process ID + payload + occurrence time + attempt count |
| `ProcessId` | VO | `productReference_timestamp` format; rejects control chars (header-injection protection) |
| `DeadLetterPayload` | VO | Raw JSON string from the DLQ table |
| `DeadLetterOccurredAt` | VO | UTC instant |
| `DrainBatchSize` | VO | Max records per run (1–10 000) |
| `DrainWorkerId` | VO | Identifies the pod claiming records |
| `DrainLeaseDuration` | VO | Lease TTL; expired leases are reclaimed by any replica |
| `ReceiveDeadLetterCommand` | Command | Input to `DeadLetterReceiver.receive()` |
| `ReceiveDeadLetterAck` | VO | Confirmation reference returned by the receiver |

---

## Business invariants

- A record is claimed with `FOR UPDATE SKIP LOCKED` — each replica claims a disjoint set.
- A claim holds a lease until `lease_until`.  Expired leases are released on the next run.
- The drain loop stops immediately when the receiver returns failure
  (drain-stops-on-failure invariant). Transient receiver errors (503, 502, 429, 504,
  connection reset) are retried with exponential back-off before being declared a failure.
- Every record that is sent to the receiver is deleted from the DB.

---

## REST API

### `POST /drain/trigger`

Trigger a single drain batch.  Intended for Kubernetes CronJob.

**Request**: no body required.

**Auth**:
- Set `DLQ_DRAIN_API_KEY` env var → every request must carry `Authorization: Bearer <key>`.
- If unset, protect access with a Kubernetes NetworkPolicy.

**Response codes**:

| Code | Meaning |
|---|---|
| `200 OK` | Drain completed; body is `TriggerDrainResponse` |
| `503 Service Unavailable` | Receiver unavailable after all retries; K8s Job fails |
| `500 Internal Server Error` | Database or configuration failure |
| `401 Unauthorized` | Missing or invalid API key (when auth is enabled) |

**Kubernetes CronJob example**:
```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: dlq-drain
spec:
  schedule: "*/5 * * * *"          # every 5 minutes
  concurrencyPolicy: Forbid        # prevent overlapping runs on same pod
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 5
  jobTemplate:
    spec:
      activeDeadlineSeconds: 300   # hard timeout
      backoffLimit: 0              # no K8s-level retries (retry handled by the app)
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: trigger
              image: curlimages/curl:8
              command:
                - sh
                - -c
                - |
                  curl -sf -X POST \
                    -H "Authorization: Bearer ${DLQ_DRAIN_API_KEY}" \
                    http://dlq-streaming:8080/drain/trigger
              env:
                - name: DLQ_DRAIN_API_KEY
                  valueFrom:
                    secretKeyRef:
                      name: dlq-drain-secret
                      key: api-key
```

---

## Configuration reference

| Env var | Default | Description |
|---|---|---|
| `DLQ_DRAIN_API_KEY` | *(not set)* | Bearer API key for `/drain/trigger`; if unset, no auth |
| `DLQ_RECEIVER_TYPE` | `in-memory` | `in-memory` (no-op) or `dataprepper` |
| `DLQ_DATA_PREPPER_URL` | `http://localhost:2021/log/ingest` | Data Prepper ingest endpoint |
| `DLQ_DP_CONNECT_TIMEOUT_MS` | `5000` | HTTP connect timeout (ms) |
| `DLQ_DP_READ_TIMEOUT_MS` | `30000` | HTTP read/response timeout (ms) |
| `DLQ_DP_MAX_RETRY_ATTEMPTS` | `3` | Max delivery attempts per record (transient errors only) |
| `DLQ_DP_RETRY_INITIAL_DELAY_MS` | `500` | Initial back-off delay (ms) |
| `DLQ_DP_RETRY_MULTIPLIER` | `2.0` | Exponential back-off multiplier |
| `DLQ_BATCH_SIZE` | `100` | Records per drain run |
| `DLQ_WORKER_ID` | `dlq-drain-worker` | Worker identifier (use pod name for traceability) |
| `DLQ_LEASE_SECONDS` | `120` | Claim lease TTL in seconds |
| `DLQ_RELEASE_EXPIRED_LEASES` | `true` | Release expired leases at run start |
| `SERVER_PORT` | `8080` | Main HTTP port (drain trigger endpoint) |
| `MANAGEMENT_PORT` | `8081` | Actuator port (health, metrics, prometheus) |

### Required datasource configuration

```yaml
spring:
  datasource:
    url: jdbc:postgresql://host:5432/db?socketTimeout=30   # TCP-level timeout (seconds)
    hikari:
      connection-timeout: 30000    # pool-level timeout (ms)
      maximum-pool-size: 5
```

> ⚠️  **`socketTimeout` is mandatory in production.**  Without it, the JDBC driver can
> block indefinitely on a network partition, causing the K8s Job to stall until
> `activeDeadlineSeconds` expires.  Validated by `PostgresNetworkChaosTest`.

---

## Persistence

| Table | Schema | Description |
|---|---|---|
| `dead_letter_record` | `dlq` | DLQ records with status, lease, worker, attempt count |

**Status machine**: `PENDING` → `PROCESSING` (claimed) → deleted (on success).

Expired `PROCESSING` records are returned to `PENDING` by `releaseExpiredLeases`.

---

## Security

### SQL injection
All queries use Spring `JdbcClient` **named parameters** (PreparedStatement).  No string
concatenation in SQL.  The `LIMIT :batchSize` clause is also a PreparedStatement parameter.
See `JdbcDeadLetterRepository` class-level Javadoc for the full analysis.

### HTTP header injection
`ProcessId` values are used as `X-Process-Id` and `Idempotency-Key` HTTP header values.
Protection is layered:
1. **Domain validation** (`ProcessId.create()`): rejects strings containing control
   characters (`< 0x20`), including CR and LF.
2. **Defence-in-depth sanitisation** (`DataPrepperDeadLetterReceiver`): strips `\r` and
   `\n` from any header value before transmission.

---

## Receiver retry policy

| Condition | Behaviour |
|---|---|
| HTTP 503, 502, 429, 504 | Retry with exponential back-off |
| `ResourceAccessException` (connection refused / reset) | Retry with exponential back-off |
| HTTP 400, 401, 403, 404, 500, etc. | Immediate failure, no retry |
| Read / connect timeout | Immediate failure, no retry |
| All retries exhausted | `Result.failure(EXTERNAL_SERVICE_ERROR, "... after N attempt(s)")` |

---

## Test pyramid

| Layer | Class | Coverage |
|---|---|---|
| Domain | `ProcessIdTest`, `DeadLetterOccurredAtTest` | VO factories, header-injection rejection |
| Stages | `DrainDeadLettersStagesTest` | Pure stage branches |
| Handler | `DrainDeadLettersHandlerTest` | Null command, repository/receiver failures |
| Controller unit | `TriggerDrainControllerTest` | HTTP status mapping |
| Controller HTTP | `TriggerDrainControllerHttpTest` | MockMvc: 200, 503, 500, 405 |
| Receiver unit | `DataPrepperDeadLetterReceiverTest` | Success, non-retryable 500 |
| Repository integration | `DeadLetterRepositoryIntegrationTest` | Full DB round-trip |
| Chaos — Data Prepper | `DataPrepperNetworkChaosTest` | Timeout, reset, retry 503, bad request |
| Chaos — PostgreSQL | `PostgresNetworkChaosTest` | DB connection drop, high latency |
| BDD | `drain_dead_letters.feature` | End-to-end acceptance via Cucumber |

---

## Verification commands

```bash
# Unit + integration tests
./mvnw test

# Integration tests only
./mvnw test -Pinclude-integration-tests -Dtest='*DeadLetterRepositoryIntegrationTest'

# Chaos tests (requires Docker)
./mvnw test -Dtest='*ChaosTest'

# BDD acceptance tests
./mvnw test -Pacceptance-tests -Dcucumber.filter.tags='@dlq-drain'
```
