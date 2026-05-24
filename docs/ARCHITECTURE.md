# Architecture Guide — dlq-streaming

> Deep-dive for developers and architects. Explains every design decision, pattern, and library choice.

---

## 1. The problem

Large PostgreSQL dead-letter tables accumulate when downstream pipeline consumers fail. The system needs to:

1. Read rows **incrementally** without loading everything into memory.
2. Forward each record to a receiver (Data Prepper → OpenSearch).
3. Delete a row **only** after the receiver acknowledges success.
4. **Stop immediately** when the receiver fails (do not flood a failing downstream).
5. **Survive crashes**: if the pod dies mid-batch, no rows are lost.
6. Be operated safely by **multiple concurrent replicas** without double-processing.

---

## 2. Solution: queue-drain with advisory leases

Instead of a long-running cursor or a message broker, the service uses a **claim-and-delete** pattern:

```
┌─────────────────────────────────┐
│ dead_letter_record               │
│  status = PENDING / PROCESSING   │
│  claimed_by, lease_until         │
└─────────────────────────────────┘
         │
    FOR UPDATE SKIP LOCKED          ← atomic, multi-replica safe
         │
    UPDATE status=PROCESSING        ← claim a batch
    lease_until = now + TTL         ← crash-recovery timer
         │
    HTTP POST /log/ingest           ← forward to Data Prepper
         │
    DELETE WHERE claimed_by=me      ← delete only on ACK
```

**Crash recovery**: if the pod dies, rows stay in `PROCESSING` with a past `lease_until`.  
On the next run, `releaseExpiredLeases` resets them to `PENDING` so another pod can claim them.

---

## 3. Domain-Driven Design (DDD)

### Bounded context

The entire application is one bounded context: **`dlq_drain`**.  
There are no cross-context calls — no shared kernel, no integration events in v1.

### Domain model

All business concepts are **typed value objects** (no raw primitives):

| Class | Purpose |
|---|---|
| `ProcessId` | `productRef_timestamp` format; rejects control chars (header-injection protection) |
| `DeadLetterPayload` | Non-blank JSON string |
| `DeadLetterOccurredAt` | Non-null `Instant` |
| `DrainBatchSize` | `1–1000` integer |
| `DrainWorkerId` | Non-blank string, max 100 chars |
| `DrainLeaseDuration` | `1 second – 1 hour` duration |
| `DeadLetterRecord` | Aggregate: processId + occurredAt + payload + attemptCount |
| `ReceiveDeadLetterCommand` | Input to the receiver port |
| `ReceiveDeadLetterAck` | Receiver confirmation |

**Factory pattern**: every value object exposes `static Result<T> create(...)`.  
Constructors are package-private (records). Validation never throws; it returns `Result.failure(...)`.

---

## 4. Railway-Oriented Programming (ROP)

### The problem with exceptions

Traditional Java code mixes business failures with infrastructure exceptions.  
A null pointer in a domain factory looks the same as a database timeout.  
Callers must catch broadly or risk swallowing failures.

### Result<T>

Every business operation returns `Result<T>`:

```java
// Success path
Result<ProcessId> id = ProcessId.create("order-1_2026-05-24T10:00:00Z");
id.isSuccess();   // true
id.value();       // ProcessId("order-1_2026-05-24T10:00:00Z")

// Failure path
Result<ProcessId> bad = ProcessId.create(null);
bad.isFailure();           // true
bad.failure().code();      // VALIDATION_ERROR
bad.failure().message();   // "ProcessId is required"
```

### Pipeline (flatMap composition)

Stages chain with `flatMap`. A failure short-circuits the rest:

```java
return Result.pipeline(data)
    .flatMap(DrainDeadLettersStages::parseCommand)          // pure validation
    .flatMap(d -> DrainDeadLettersStages.releaseExpiredLeases(d, ports))  // impure
    .flatMap(d -> DrainDeadLettersStages.claimBatch(d, ports))            // impure
    .within(txContext)                                       // transaction boundary
    .flatMap(DrainDeadLettersStages::buildResult);           // pure mapping
```

### ExecutionContext

`ExecutionContext` is the abstraction for how a pipeline executes:

| Implementation | Behaviour |
|---|---|
| `TransactionExecutionContext` | Wraps in a Spring `@Transactional` boundary |
| `NoOpExecutionContext` | Runs the supplier directly (used when no DB is configured) |
| `LoggingExecutionContext` | Adds log spans (composable) |

This decouples transaction management from business logic — stages never have `@Transactional`.

---

## 5. P3 vertical slice

Every use case follows the **P3 pattern** (Pipeline, Ports, Persistent):

```
drain_dead_letters/
  application/
    DrainDeadLettersCommand.java    # input (primitives allowed)
    DrainDeadLettersResult.java     # output (named record)
    DrainDeadLettersData.java       # immutable pipeline state
    DrainDeadLettersPorts.java      # dependencies: repository, receiver, clock
    DrainDeadLettersStages.java     # pure + impure static steps
    DrainDeadLettersHandler.java    # orchestrator: null check → pipeline → result
  infrastructure/
    rest/
      TriggerDrainResponse.java     # JSON response DTO
      TriggerDrainController.java   # HTTP → Command → Response
      spec/TriggerDrainSpec.java    # OpenAPI contract interface
```

### Why static stages?

Static methods in `DrainDeadLettersStages` are:
- **Independently testable**: no Spring context, no mocks for pure stages.
- **Explicit**: every dependency is a parameter, nothing hidden.
- **Composable**: `flatMap` chains them into a pipeline.

### Why Data holds pipeline state?

`DrainDeadLettersData` is an immutable record passed through stages.  
Each stage returns a new `Data` instance with modified fields.  
This makes data flow explicit and the pipeline easy to debug (log Data at any point).

---

## 6. Persistence: flat JDBC

### No ORM

No Hibernate, no JPA relationship annotations. Reasons:

| JPA feature | Why avoided |
|---|---|
| `@ManyToOne / @OneToMany` | Implicit N+1 queries, hidden lazy-load failure |
| `EntityManager.merge/flush` | Unpredictable SQL execution timing |
| JPQL polymorphism | Opaque query generation |
| Cascade | Accidental delete/update propagation |

### JdbcClient (Spring 6.1+)

`JdbcClient` is a fluent, modern alternative to `JdbcTemplate`:

```java
jdbcClient.sql(CLAIM_NEXT_BATCH_SQL)
    .param("batchSize", batchSize.value())
    .param("workerId", workerId.value())
    .param("claimedAt", Timestamp.from(claimedAt))
    .param("leaseUntil", Timestamp.from(leaseUntil))
    .query(this::mapDeadLetterRecord)
    .list();
```

- All parameters are **named** → PreparedStatement → SQL-injection safe.
- `RowMapper` is a private method reference → no wrapper class.
- No Hibernate session state → fully deterministic.

### Flyway migrations

Schema changes are managed by **Flyway**, which applies sequential SQL scripts at startup.  
Scripts live in `src/main/resources/db/migration/`.  
The schema for the DLQ drain is `dlq` (isolated from other schemas).

---

## 7. Spring Boot auto-configuration: the ordering problem

### Background

Spring Boot provides two categories of bean registration:

1. **User `@Configuration` classes** — scanned by `@ComponentScan`, processed first.
2. **Auto-configurations** — registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, processed after all user configs.

### The ordering problem

`@ConditionalOnBean(JdbcClient.class)` in a user `@Configuration` class **always fails** because:

- User configuration processing happens during the component-scan phase.
- `JdbcClientAutoConfiguration` (which registers `JdbcClient`) runs **after** all user configs.
- At the time the condition is evaluated, `JdbcClient` doesn't exist yet → condition fails silently.

This caused the `JdbcDeadLetterRepository` to never be registered, silently breaking the entire bean chain.

### The fix: DlqDrainAutoConfiguration

```java
@AutoConfiguration
@AutoConfigureAfter(name = {
    "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
})
public class DlqDrainAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    JdbcDeadLetterRepository jdbcDeadLetterRepository(JdbcClient jdbcClient) { ... }

    @Bean
    @ConditionalOnMissingBean(DeadLetterRepository.class)
    DeadLetterRepository noOpDeadLetterRepository() { ... }

    // ...
}
```

Auto-configurations are processed **after** all user configs. `@AutoConfigureAfter` ensures that, within the auto-config phase, JDBC beans are registered before our conditions are evaluated.

`@ConditionalOnMissingBean(DeadLetterRepository.class)` sees the already-registered `JdbcDeadLetterRepository` and correctly skips the no-op fallback.

### Fallback beans

When no database is configured (e.g., smoke tests that exclude DataSource):
- `NoOpDeadLetterRepository` is registered → returns `SERVICE_UNAVAILABLE_ERROR`.
- `NoOpExecutionContext` is registered → runs the supplier directly.

This prevents `NullPointerException` and gives a clear error message to callers.

---

## 8. Kubernetes-native design

### No internal scheduler

The application does **not** contain a `@Scheduled` bean or any polling loop.  
Instead, a **Kubernetes CronJob** calls `POST /drain/trigger` on a schedule.

**Advantages over an internal scheduler:**
- `kubectl get cronjobs` shows last schedule, last success, active count.
- Failed Jobs create K8s events → Prometheus alerts.
- `concurrencyPolicy: Forbid` prevents overlapping runs.
- Pod restart policy controls retry behaviour.
- The service itself is stateless and restartable at any time.

### Health probes on a separate port

The `MANAGEMENT_PORT` (default 8081) exposes `/actuator/health/liveness` and `/actuator/health/readiness` on a different port from the API (8080). This avoids mixing operational signals with business traffic.

### CronJob vs. `POST /drain/trigger`

The trigger endpoint is the operational interface. The CronJob is just a scheduler that calls it:

```yaml
containers:
  - name: trigger
    image: curlimages/curl:8
    command:
      - curl -sf -X POST http://dlq-streaming:8080/drain/trigger
```

This separation means:
- You can trigger manually (`curl -X POST ...`) without restarting anything.
- You can change the schedule without touching application code.
- You can replace the CronJob with an event-driven trigger later.

---

## 9. Security design

### SQL injection prevention

All SQL uses Spring `JdbcClient` named parameters. No string concatenation in SQL strings.  
`LIMIT :batchSize` is also a PreparedStatement parameter — PostgreSQL handles it safely.

### HTTP header injection prevention

`ProcessId` values are forwarded as `X-Process-Id` and `Idempotency-Key` HTTP headers.  
Two layers of protection:
1. **Domain**: `ProcessId.create()` rejects strings with control characters (`< 0x20`), including CR and LF.
2. **Adapter**: `DataPrepperDeadLetterReceiver` strips `\r` and `\n` as defence-in-depth.

### API key authentication

`ApiKeyAuthFilter` (a Servlet `OncePerRequestFilter`) is registered **only** when `DLQ_DRAIN_API_KEY` is set.  
When absent, use a Kubernetes NetworkPolicy to restrict access to the drain endpoint.

### Pod Security Standards

The Docker image and Kubernetes manifests comply with the PSS `restricted` profile:
- `runAsNonRoot: true`, `runAsUser: 1001`
- `readOnlyRootFilesystem: true` (with `/tmp` as `emptyDir`)
- `allowPrivilegeEscalation: false`
- `capabilities.drop: [ALL]`
- `automountServiceAccountToken: false`

---

## 10. Test strategy

Tests are written **before or alongside** production code (TDD/BDD). The pyramid is:

```
            ┌────────────────┐
            │ Kubernetes E2E │  deployment tests (k3s via Testcontainers)
          ┌─┤                ├─┐
          │ └────────────────┘ │
          │   ┌────────────┐   │
          │   │ Chaos tests│   │  Toxiproxy: DB + receiver faults
          │ ┌─┤            ├─┐ │
          │ │ └────────────┘ │ │
          │ │  ┌──────────┐  │ │
          │ │  │Integration│  │ │  real PostgreSQL (Testcontainers)
          │ │ ─┤          ├─ │ │
          │ │  └──────────┘  │ │
          │ │    ┌───────┐   │ │
          │ │    │  BDD  │   │ │  Cucumber + real handler
          │ │  ──┤       ├── │ │
          │ │    └───────┘   │ │
          │ │  ┌──────────┐  │ │
          │ │  │  Unit    │  │ │  domain, stages, handler, controller, HTTP
          └─┘  └──────────┘  └─┘
```

### TDD findings that drove production code changes

| Test | Finding | Production fix |
|---|---|---|
| `connectTimeoutReturnsFailureWithinExpectedDuration` | Hung forever | `JdkClientHttpRequestFactory` with `connectTimeout` |
| `readTimeoutReturnsFailureWhenServerIsUnresponsive` | Hung on slow server | `factory.setReadTimeout(...)` |
| `retriesOnTransient503AndEventuallySucceeds` | Failed on pod restarts | Exponential back-off retry loop |
| `badRequestFailsImmediatelyWithoutRetry` | 4xx retried incorrectly | `isRetryableHttpStatus()` guard |
| `connectionDropReturnsFailure` (PostgreSQL) | Hang on DB network drop | `?socketTimeout=5` in JDBC URL |
| `readinessProbeBecomesHealthyAfterDatabaseConnection` (K8s) | Readiness probe hung | `?socketTimeout=5` confirmed via real k3s deployment |
| `drainTriggerEndpointReturns200` (K8s) | 404 → 503 → 200 | `@AutoConfiguration @AutoConfigureAfter` for JDBC ordering |

---

## 11. Library guide

| Library | Version | Why |
|---|---|---|
| **Spring Boot 4** | 4.1.0-RC1 | Framework for auto-configuration, web, actuator, test |
| **Spring JDBC** | (boot-managed) | `JdbcClient` — flat, safe, modern JDBC |
| **Flyway** | (boot-managed) | Database schema migration at startup |
| **PostgreSQL JDBC** | (boot-managed) | Driver for `jdbc:postgresql://` URLs |
| **Lombok** | 1.18.42 | `@Slf4j`, `@RequiredArgsConstructor` — reduce boilerplate in Spring beans |
| **JSpecify** | 1.0.0 | `@Nullable` annotations for static-analysis tools (SonarQube, IntelliJ) |
| **Spring Boot Actuator** | (boot-managed) | `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` |
| **Testcontainers** | 1.21.3 | Docker-based PostgreSQL, Toxiproxy, k3s for tests |
| **WireMock** | 3.9.2 | HTTP server stub for Data Prepper receiver tests |
| **Awaitility** | (boot-managed) | Fluent async assertions in Kubernetes deployment tests |
| **Fabric8 Kubernetes Client** | 7.2.0 | Apply K8s manifests and assert cluster state in K8s tests |
| **Cucumber** | 7.30.0 | BDD: Gherkin feature files → Java step definitions |

### Lombok usage guide

Lombok is used to eliminate Java boilerplate **only where it adds clarity**:

| Annotation | Used on | Purpose |
|---|---|---|
| `@Slf4j` | `@Service`, `@Component`, `@RestController` classes | Generates `private static final Logger log = ...` |
| `@RequiredArgsConstructor` | Spring beans with `private final` fields | Generates a constructor for dependency injection |

Lombok is **not used** on domain records (they use Java's compact constructor syntax)
or on `@Configuration` classes (bean methods are explicit).

### JSpecify @Nullable

`@Nullable` from JSpecify (not Spring's) marks parameters that legitimately accept null.  
Used in domain factory methods (`ProcessId.create(@Nullable String value)`) so static analysis tools can track null-safety through the codebase.

### Testcontainers

Every Docker-dependent test uses Testcontainers to spin up isolated containers:

| Module | Test class | Container |
|---|---|---|
| `junit-jupiter` | All Testcontainer tests | Container lifecycle management |
| `postgresql` | `DeadLetterRepositoryIntegrationTest` | Real PostgreSQL |
| `toxiproxy` | `DataPrepperNetworkChaosTest`, `PostgresNetworkChaosTest` | Network fault injection |
| `k3s` | `KubernetesDeploymentTest` | Lightweight Kubernetes (k3s) |

Testcontainers automatically discovers Docker via the `DOCKER_HOST` socket.  
No Docker CLI commands are needed for normal tests — only the K8s tests use `docker save` to load images into k3s containerd.

### Cucumber / BDD

Feature files live in `src/test/resources/features/dlq_drain/`.  
Step definitions in `DlqDrainStepDefinitions` use the real Handler with Testcontainers PostgreSQL.  
This proves that the Gherkin business language matches the production code behaviour.

---

## 12. Package structure

```
src/main/java/es/bluesolution/dlq_streaming/
│
├── DlqStreamingApplication.java           # Spring Boot entry point
│
├── functional_framework/                  # ROP framework (Result<T>, ExecutionContext)
│   ├── Result.java
│   ├── ResultPipeline.java
│   ├── FailureResultDescription.java
│   ├── execution/
│   │   ├── ExecutionContext.java           # interface
│   │   ├── TransactionExecutionContext.java
│   │   ├── NoOpExecutionContext.java
│   │   └── ...
│   └── ...
│
└── dlq_drain/                             # dlq_drain bounded context
    ├── domain/
    │   ├── model/                         # value objects + aggregate
    │   └── repository/                    # repository + receiver interfaces
    │
    ├── usecases/
    │   └── drain_dead_letters/
    │       ├── application/               # Command, Result, Data, Ports, Stages, Handler
    │       └── infrastructure/
    │           └── rest/                  # Controller, Response, Spec
    │
    └── shared/
        └── infrastructure/
            ├── DlqDrainAutoConfiguration.java   # @AutoConfiguration (JDBC conditional beans)
            ├── DlqDrainInfrastructureConfig.java # @Configuration (framework beans)
            ├── DlqDrainProperties.java           # @ConfigurationProperties
            ├── persistence/
            │   ├── JdbcDeadLetterRepository.java
            │   └── NoOpDeadLetterRepository.java
            ├── receiver/
            │   ├── InMemoryDeadLetterReceiver.java
            │   └── DataPrepperDeadLetterReceiver.java
            └── security/
                └── ApiKeyAuthFilter.java
```

---

## 13. Decision log

| Decision | Alternatives considered | Reason chosen |
|---|---|---|
| Flat JDBC via `JdbcClient` | JPA/Hibernate, JOOQ | Explicit, predictable, no lazy-load, aligns with flat-JPA rule |
| `FOR UPDATE SKIP LOCKED` | Redis lock, DB advisory lock, token bucket | Built into PostgreSQL, no extra infrastructure |
| No internal scheduler | `@Scheduled`, Quartz | K8s CronJob provides visibility, concurrency control, and alerting |
| `Result<T>` everywhere | Exceptions, `Optional<T>` | Explicit, composable, type-safe failure representation |
| Static stage methods | Instance methods, strategy pattern | Testable without mocks, no hidden state |
| `@AutoConfiguration @AutoConfigureAfter` | `@Configuration @Bean` with conditions | Only auto-configs see auto-configured beans at condition-evaluation time |
| Testcontainers k3s for K8s tests | kind, minikube, real cluster | Single Docker dependency, no tools to install, works in CI |

