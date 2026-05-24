# Kubernetes Deployment Guide — dlq-streaming

## Overview

`dlq-streaming` is deployed as a **long-running `Deployment`** that exposes a single REST endpoint,
triggered by a **Kubernetes `CronJob`** on a user-defined schedule. There is no internal scheduler.

```
CronJob (curlimages/curl)
    │   POST /drain/trigger
    ▼
dlq-streaming Deployment
    │   JDBC + Advisory Leases
    ├──► PostgreSQL (dead-letter source)
    │
    │   HTTP  POST /log/ingest
    └──► Data Prepper ──► OpenSearch (destination)
```

---

## Docker image

The image is built with a **multi-stage Dockerfile**:

| Stage | Base | Purpose |
|---|---|---|
| `builder` | `eclipse-temurin:25-jdk-alpine` | Compile with Maven, produce fat JAR |
| `extractor` | `eclipse-temurin:25-jre-alpine` | Extract Spring Boot layers |
| `runtime` | `eclipse-temurin:25-jre-alpine` | Minimal layered runtime image |

### Build

```bash
# Full build (runs Maven inside Docker — good for CI):
docker build -t dlq-streaming:latest .

# Quick build (uses pre-built jar — good for local iteration):
./mvnw package -DskipTests
docker build -t dlq-streaming:latest .
```

### Image characteristics

- **Base**: `eclipse-temurin:25-jre-alpine` (~180 MB)
- **Non-root user**: UID 1001 (`appuser`)
- **Read-only root filesystem**: temporary files go to an `emptyDir` volume at `/tmp`
- **JVM flags**: container-aware heap (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75`),
  ZGC (`-XX:+UseZGC -XX:+ZGenerational`)
- **Spring Boot layered extraction**: Docker cache reuses stable dependency layers across builds

---

## Kubernetes manifests

All manifests live under `k8s/`:

```
k8s/
  base/                          # Kustomize base (cluster-agnostic)
    namespace.yaml
    serviceaccount.yaml
    configmap.yaml
    secret.example.yaml          # Template only — fill in real values
    deployment.yaml
    service.yaml
    cronjob.yaml
    networkpolicy.yaml
    kustomization.yaml
  overlays/
    local/                       # local kind/minikube overlay
      kustomization.yaml
```

### Applying the base

```bash
# Review what will be applied:
kustomize build k8s/base

# Apply (supply the Secret separately after filling in values):
kubectl -n dlq-streaming create secret generic dlq-streaming-secret \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://postgres:5432/dlq?socketTimeout=5' \
  --from-literal=SPRING_DATASOURCE_USERNAME='dlq_user' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='CHANGE_ME' \
  --from-literal=DLQ_DRAIN_API_KEY=''

kubectl apply -k k8s/base
```

### Local development (kind / minikube)

```bash
# Load image and apply local overlay
kind load docker-image dlq-streaming:local
kubectl apply -k k8s/overlays/local
# App API:        http://localhost:30080/drain/trigger
# Management:     http://localhost:30081/actuator/health
```

---

## Configuration reference

| Env var | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | — | JDBC URL. **Always include `?socketTimeout=5`** (proved necessary by `PostgresNetworkChaosTest`) |
| `SPRING_DATASOURCE_USERNAME` | — | DB user |
| `SPRING_DATASOURCE_PASSWORD` | — | DB password (Secret) |
| `DLQ_RECEIVER_TYPE` | `in-memory` | `dataprepper` or `in-memory` |
| `DLQ_DATA_PREPPER_URL` | — | Data Prepper ingest endpoint |
| `DLQ_DP_CONNECT_TIMEOUT_MS` | `5000` | HTTP connect timeout (ms) |
| `DLQ_DP_READ_TIMEOUT_MS` | `30000` | HTTP read timeout (ms) |
| `DLQ_DP_MAX_RETRY_ATTEMPTS` | `3` | Max retries on 503/502/429/504 |
| `DLQ_DP_RETRY_INITIAL_DELAY_MS` | `500` | Initial retry delay (ms) |
| `DLQ_DP_RETRY_MULTIPLIER` | `2.0` | Exponential back-off multiplier |
| `DLQ_BATCH_SIZE` | `100` | Records per drain batch |
| `DLQ_WORKER_ID` | `dlq-drain-worker` | Lease worker identity |
| `DLQ_LEASE_SECONDS` | `120` | Lease duration (seconds) |
| `DLQ_RELEASE_EXPIRED_LEASES` | `true` | Release stale leases on startup |
| `DLQ_DRAIN_API_KEY` | (empty) | Bearer token for `/drain/trigger`. Empty = no auth |
| `SERVER_PORT` | `8080` | HTTP API port |
| `MANAGEMENT_PORT` | `8081` | Actuator port (separate from API) |

---

## CronJob — drain scheduling

The CronJob in `k8s/base/cronjob.yaml` runs `curlimages/curl` and POSTs to `/drain/trigger`.

```yaml
schedule: "*/5 * * * *"   # every 5 minutes — adjust to SLA
concurrencyPolicy: Forbid  # skip if previous job is still running
backoffLimit: 1            # retry once on transient failure
activeDeadlineSeconds: 300 # kill after 5 minutes
```

**Why a CronJob instead of an internal scheduler?**  
The K8s CronJob provides:
- Visibility: last-schedule / last-success shown by `kubectl get cronjobs`
- Failure alerting: failed Jobs trigger K8s events and Prometheus alerts
- Concurrency control: `concurrencyPolicy: Forbid` prevents overlap
- No in-process threading: simpler deployment, easier to reason about

The drain endpoint returns:
- `200 OK` — drain completed (even if 0 records found)
- `503 Service Unavailable` — drain stopped because the **receiver failed**
  (the CronJob marks this Job as failed, making the problem visible)
- `500 Internal Server Error` — unexpected infrastructure error

---

## Health probes

| Probe | Endpoint | Port | Behaviour |
|---|---|---|---|
| Startup | `GET /actuator/health/liveness` | 8081 | Allow 90 s (18 × 5 s) for JVM + Flyway |
| Liveness | `GET /actuator/health/liveness` | 8081 | Restart pod after 3 consecutive failures |
| Readiness | `GET /actuator/health/readiness` | 8081 | Remove from Service endpoints until ready |

---

## Security

| Concern | Mitigation |
|---|---|
| Non-root | `runAsUser: 1001`, `runAsNonRoot: true` |
| No privilege escalation | `allowPrivilegeEscalation: false` |
| Read-only filesystem | `readOnlyRootFilesystem: true`, `/tmp` as `emptyDir` |
| Capabilities | `drop: [ALL]` |
| Pod Security Standards | Compliant with `restricted` profile |
| Service account | Dedicated SA, `automountServiceAccountToken: false` |
| Network | NetworkPolicy restricts ingress to CronJob pod + Prometheus; egress to DNS, PostgreSQL, Data Prepper |
| API key | Optional Bearer token via `DLQ_DRAIN_API_KEY`; use NetworkPolicy as primary protection |

---

## Kubernetes deployment tests

Deployment behaviour is verified by `KubernetesDeploymentTest` using **Testcontainers k3s** —
a lightweight Kubernetes cluster running in a Docker container.

### What is tested

| Test | Validates |
|---|---|
| `podBecomesReady` | Pod transitions to `Ready` after startup probes pass |
| `livenessProbeReturnsUp` | `/actuator/health/liveness` returns `{"status":"UP"}` |
| `readinessProbeBecomesHealthyAfterDatabaseConnection` | `socketTimeout` in JDBC URL prevents readiness hang |
| `drainTriggerEndpointReturns200` | `POST /drain/trigger` returns HTTP 200 |
| `drainTriggerResponseBodyContainsDrainFields` | Response contains `claimedCount`, `storedCount`, etc. |
| `podRunsAsNonRoot` | UID 1001, `runAsNonRoot=true`, no privilege escalation |
| `deploymentDefinesResourceRequestsAndLimits` | Resource governance prevents OOMKilled |
| `deploymentDefinesHealthProbes` | All three probes are configured |
| `podHasExactlyOneContainer` | No unexpected sidecars injected |

### Running

```bash
# Prerequisites: Docker daemon running, no pre-existing dlq-streaming:k8s-test image needed.
./mvnw test -Pkubernetes-tests

# The profile automatically:
#   1. Compiles the project
#   2. docker build -t dlq-streaming:k8s-test .
#   3. Starts k3s via Testcontainers
#   4. Loads the image into k3s containerd
#   5. Deploys PostgreSQL + dlq-streaming
#   6. Runs all 9 assertions
#   7. Destroys the k3s cluster
```

### TDD findings from deployment tests

| Finding | Test | Fix applied |
|---|---|---|
| JDBC `socketTimeout` is required | `readinessProbeBecomesHealthyAfterDatabaseConnection` | Added `?socketTimeout=5` to JDBC URL in `secret.example.yaml` and test config |
| Image must run as UID 1001 | `podRunsAsNonRoot` | `adduser -S -u 1001` in Dockerfile |
| Resource limits prevent OOMKilled | `deploymentDefinesResourceRequestsAndLimits` | `256Mi` limit in `app-deployment.yaml` |
| Read-only root filesystem requires `/tmp` volume | `podRunsAsNonRoot` | `emptyDir` for `/tmp` in deployment |

---

## Resource sizing

| Env | CPU request | CPU limit | Memory request | Memory limit |
|---|---|---|---|---|
| Production | `100m` | `500m` | `256Mi` | `256Mi` |
| Local / test | `50m` | `500m` | `128Mi` | `256Mi` |

Tune `DLQ_BATCH_SIZE` and memory to match your dead-letter table size:

- 500-record batches with average 1 KB payload ≈ 0.5 MB per batch in heap
- ZGC overhead ≈ 20-30% extra heap
- 256 Mi is sufficient for batches up to 5 000 records at 1 KB each

---

## Troubleshooting

**Pod stays in `Pending`**  
→ Check resource requests vs. node capacity: `kubectl describe pod -n dlq-streaming`

**Pod crashes with `OOMKilled`**  
→ Increase memory limit or reduce `DLQ_BATCH_SIZE`

**Readiness probe fails indefinitely after deploy**  
→ Verify `?socketTimeout=5` is in `SPRING_DATASOURCE_URL` (without it HikariCP hangs)  
→ Check PostgreSQL connectivity: `kubectl exec -n dlq-streaming deploy/dlq-streaming -- wget -qO- http://localhost:8081/actuator/health`

**CronJob shows `LAST SCHEDULE: <never>`**  
→ Check `kubectl get events -n dlq-streaming --sort-by=.lastTimestamp`

**POST /drain/trigger returns 503**  
→ The receiver (Data Prepper) failed. Check `DLQ_DATA_PREPPER_URL` and Data Prepper logs.  
→ The CronJob will retry once (`backoffLimit: 1`) and then mark the Job as failed.

