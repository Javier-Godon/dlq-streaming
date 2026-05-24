# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] — 2026-05-25

Initial production release of the **DLQ Streaming** service.

### Added

#### DLQ Drain bounded context

- **Domain model** — `DeadLetterRecord`, `DrainBatchSize`, `DrainLeaseDuration`, `DrainWorkerId`,
  `ProcessId`, `DeadLetterOccurredAt`, `DeadLetterPayload`, `ReceiveDeadLetterAck`, `ReceiveDeadLetterCommand`
  as typed value objects and records returning `Result<T>` from factory methods.
- **Railway-Oriented Programming (ROP) functional framework** — `Result<T>`, `ResultPipeline<T>`,
  `TransactionExecutionContext`, `LoggingExecutionContext`, `ComposableExecutionContext`,
  `RailwayErrorResponseBuilder`, and supporting utilities bundled for reuse across bounded contexts.
- **`DrainDeadLetters` use case** — P3 vertical slice:
  - `DrainDeadLettersCommand` / `DrainDeadLettersResult`
  - `DrainDeadLettersData` / `DrainDeadLettersPorts`
  - `DrainDeadLettersStages` (parse, release expired leases, claim batch, receive, delete, build result)
  - `DrainDeadLettersHandler` with `Result.pipeline(...).within(txContext)`
- **REST API** — `POST /drain/trigger` endpoint:
  - `TriggerDrainController` implementing `TriggerDrainSpec`
  - `TriggerDrainRequest` / `TriggerDrainResponse`
  - API-key authentication via `ApiKeyAuthFilter`
- **Dead-letter repository** — `DeadLetterRepository` (domain interface) + `DeadLetterJpaRepository`
  (flat JPA implementation, no relationship annotations) using `FOR UPDATE SKIP LOCKED` for
  concurrent-safe batch claiming.
- **Data Prepper receiver adapter** — `DataPrepperDeadLetterReceiver` with exponential-backoff retry
  for transient 503 errors, configurable connect/read timeouts, and `X-Process-Id` /
  `Idempotency-Key` headers.
- **Flyway migration** — `V1__init_dlq_drain.sql` creating the `dlq.dead_letter_record` table with
  a leasing schema (`claimed_by`, `claimed_at`, `lease_duration_seconds`).
- **Kubernetes manifests** — `k8s/base/` with `Deployment`, `CronJob`, `Service`, `ConfigMap`,
  `NetworkPolicy`, `ServiceAccount`, `Namespace`, and `Kustomization`.

#### Test pyramid (227 default tests)

- Domain/value-object unit tests for all VOs and the `DeadLetterRecord` aggregate.
- Stages unit tests covering all `DrainDeadLettersStages` branches.
- Handler unit tests covering null command, DB failure, receiver failure, and full-drain paths.
- Controller unit and HTTP tests via standalone MockMvc.
- `ApiKeyAuthFilter` unit tests — 401/200 paths.
- `DataPrepperDeadLetterReceiver` unit tests — success, non-retryable error, 503 retry, exhausted retry.
- `DeadLetterRepositoryIntegrationTest` — real PostgreSQL (Testcontainers): `SKIP LOCKED`,
  expired-lease release, owner-delete, non-owner-delete.
- Network chaos tests — `DataPrepperNetworkChaosTest` (Toxiproxy + WireMock): connect timeout,
  read timeout, 503 retry, exhausted retry, 400 no-retry, TCP reset.
- Network chaos tests — `PostgresNetworkChaosTest` (Toxiproxy): DB connection drop, high latency.
- BDD/acceptance tests — 4 Gherkin scenarios covering normal drain, receiver failure, empty table,
  and batch-size limit.
- Spring context smoke test.

#### Kubernetes test suite (21 tests, `-Pkubernetes-tests`)

- `KubernetesDeploymentTest` — 17 tests covering pod readiness, liveness/readiness/startup probes,
  security context (UID 1001, non-root), resource limits, drain endpoint reachability, drain
  response schema, operational drain scenarios (empty table, full cycle, batch enforcement,
  idempotency, concurrent drains, Prometheus metrics, pod restart resilience), and CronJob.
- `KubernetesDataPrepperTest` — 4 tests covering records forwarded with correct headers,
  503 stops drain, and idempotency with a WireMock mock Data Prepper.

#### E2E test suite (`-Pe2e-tests`)

- `DlqDrainDataPrepperOpenSearchE2E` — opt-in real end-to-end test against external
  PostgreSQL + Data Prepper + OpenSearch stack (skipped automatically when environment
  variables are absent).
- `LargeVolumePostgresDrainSimulationE2E` — opt-in large-volume simulation (default disabled,
  enabled with `-Ddlq.e2e.large-volume.enabled=true`).

#### Documentation

- `docs/ARCHITECTURE.md` — system architecture overview.
- `docs/DEVELOPMENT.md` — local development guide.
- `docs/dlq-drain/README.md` — context boundary, aggregates, use cases, persistence, security.
- `docs/dlq-drain/TESTING.md` — test pyramid, chaos tests, Kubernetes tests, E2E tests,
  current verified status.
- `docs/dlq-drain/E2E_TEST_STRATEGY.md` — E2E test strategy document.
- `docs/dlq-drain/KUBERNETES.md` — Kubernetes deployment guide.
- `docs/guides/BDD_TESTING_GUIDE.md` — BDD testing guide.
- `docs/guides/KUBERNETES_TESTING_PATTERN.md` — Kubernetes testing pattern guide.

### Fixed

- **WireMock 3.x API compatibility** — `KubernetesDataPrepperTest.resetWireMockState()` was
  calling `POST /__admin/requests/reset` which returns 404 in WireMock 3.x. Fixed to use
  `DELETE /__admin/requests` (the correct WireMock 3.x endpoint).
- **Docker image auto-build in Kubernetes tests** — Both `KubernetesDeploymentTest` and
  `KubernetesDataPrepperTest` now auto-build `dlq-streaming:k8s-test` from the project
  `Dockerfile` when the image is not found locally. This makes the Kubernetes test suite
  self-contained: the previous test class's `@AfterAll` can delete the image, and the next
  class rebuilds it on demand.

### Technical decisions

- **Flat JPA** — no `@JoinColumn`, `@ManyToOne`, `@OneToMany`, or any relationship annotations.
  Parent-child loading is explicit in the repository implementation.
- **ROP over exceptions** — all validation and business errors are modeled as `Result.failure(...)`.
  No validation exceptions are thrown from domain constructors or application stages.
- **Deferred transaction** — `Result.pipeline(data)...within(txContext)` ensures all pipeline
  stages run inside the transaction. No `@Transactional` except Spring Data `@Modifying @Query`.
- **JaCoCo gate** — ≥85% instruction coverage, ≥80% branch coverage measured on business code.
  Framework utility classes (shared ROP plumbing, HTTP error mappers, infrastructure config)
  are excluded because they are not exercised by any DLQ drain business use case.

---

## [0.0.1-SNAPSHOT] — development

Initial development snapshot. Not released.

