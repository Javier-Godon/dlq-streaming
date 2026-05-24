# Development Guide — dlq-streaming

> Everything you need to contribute to this project as a developer.

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| **Docker** (engine + CLI) | Any recent | [docker.com](https://www.docker.com/get-started) |
| **Java 25 JDK** | 25+ (for IDE) | [temurin.net](https://adoptium.net/) or `sdk install java 25-tem` |
| **IDE** | IntelliJ IDEA recommended | Lombok plugin required |

> **You do NOT need Maven installed.** The project ships a Maven wrapper (`./mvnw`).  
> **You do NOT need kubectl, kind, or any Kubernetes tools** — the K8s tests use Testcontainers k3s.

---

## IDE setup

### IntelliJ IDEA

1. Open the project root (`pom.xml` → "Open as Maven Project").
2. Install the **Lombok plugin** (Settings → Plugins → search "Lombok").
3. Enable annotation processing (Settings → Build → Compiler → Annotation Processors → Enable).
4. Mark `src/test/resources` as "Test Resources Root" if not already.

### Lombok in the IDE

The project uses Lombok for:
- `@Slf4j` — generates `private static final Logger log = LoggerFactory.getLogger(...)`.
- `@RequiredArgsConstructor` — generates a constructor for all `private final` fields (used for Spring dependency injection).

Without the Lombok plugin, IntelliJ will show red errors in Spring beans even though the code compiles correctly.

---

## Running tests

### Default suite (unit + integration + BDD)

```bash
./mvnw test
```

Runs 46 tests including:
- Domain value-object unit tests (no Spring, no Docker)
- Stages and handler tests (no Spring, no Docker)
- Controller unit and MockMvc HTTP tests (no Spring)
- `DataPrepperDeadLetterReceiverTest` (WireMock — in-process, no Docker)
- `DeadLetterRepositoryIntegrationTest` (Testcontainers PostgreSQL — requires Docker)
- `DataPrepperNetworkChaosTest` (Testcontainers Toxiproxy + WireMock — requires Docker)
- `PostgresNetworkChaosTest` (Testcontainers Toxiproxy + PostgreSQL — requires Docker)
- `DlqDrainCucumberTest` (BDD — Testcontainers PostgreSQL)

### Kubernetes deployment tests

```bash
./mvnw test -Pkubernetes-tests
```

The `kubernetes-tests` Maven profile automatically:
1. Compiles the project.
2. Runs `docker build -t dlq-streaming:k8s-test .` (builds the Docker image).
3. Starts k3s (lightweight Kubernetes) in a Docker container via Testcontainers.
4. Loads `dlq-streaming:k8s-test` and `postgres:17-alpine` into k3s containerd.
5. Deploys PostgreSQL and dlq-streaming using test manifests from `src/test/resources/kubernetes/`.
6. Runs 9 assertions (health probes, drain trigger, security context, resource limits, etc.).
7. Tears down everything.

**Only Docker is required** — no kubectl, no kind, no external cluster.

### BDD acceptance tests

```bash
./mvnw test -Pacceptance-tests
```

### Scoped test runs

```bash
# A single test class
./mvnw test -Dtest=DrainDeadLettersHandlerTest

# Multiple classes
./mvnw test -Dtest='DataPrepperNetworkChaosTest,PostgresNetworkChaosTest'

# Integration only
./mvnw test -Dtest=DeadLetterRepositoryIntegrationTest
```

---

## Building the Docker image

```bash
# Full build (Maven runs inside Docker — slow on first run, cached after)
docker build -t dlq-streaming:latest .

# Faster iteration: build JAR locally first, then Docker uses it
./mvnw package -DskipTests
docker build -t dlq-streaming:latest .
```

The Dockerfile is a **multi-stage build**:
1. **Stage 1 (builder)**: Maven + JDK 25 — downloads dependencies, compiles, produces the fat JAR.
2. **Stage 2 (extractor)**: JRE 25 — extracts Spring Boot layers for cache-friendly COPY.
3. **Stage 3 (runtime)**: Minimal JRE 25 Alpine — runs the application as a non-root user.

---

## Local development with Docker Compose

```bash
# Start infrastructure (PostgreSQL + Data Prepper + OpenSearch)
docker compose up -d postgres opensearch data-prepper

# Run the application locally
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/dlq
export SPRING_DATASOURCE_USERNAME=dlq_user
export SPRING_DATASOURCE_PASSWORD=change_me
export DLQ_RECEIVER_TYPE=dataprepper
export DLQ_DATA_PREPPER_URL=http://localhost:2021/log/ingest
./mvnw spring-boot:run

# Trigger a manual drain run
curl -sf -X POST http://localhost:8080/drain/trigger | jq
```

---

## Code patterns

### 1. Railway-Oriented Programming (ROP)

Every business operation returns `Result<T>`, never throws business exceptions.

```java
// ✅ Correct
public static Result<ProcessId> create(@Nullable String value) {
    if (value == null || value.isBlank()) {
        return Result.failure(VALIDATION_ERROR, "ProcessId is required", null);
    }
    return Result.success(new ProcessId(value.trim()));
}

// ❌ Wrong — throws instead of returning failure
public static ProcessId create(String value) {
    if (value == null) throw new IllegalArgumentException("...");
    return new ProcessId(value);
}
```

### 2. Forwarding failures across types

When a stage produces `Result<A>` and you need `Result<B>`:

```java
var id = SomeValueObject.create(rawValue);
if (id.isFailure()) {
    return Result.failure(id.failure()); // ✅ forward the existing failure
}
```

Never call `id.value()` without checking `isSuccess()` first.

### 3. Handler structure

```java
@Service
@RequiredArgsConstructor  // Lombok generates constructor for Spring DI
public class SomeHandler {
    private final SomeRepository repository;
    private final ExecutionContext txContext;
    private final Clock clock;

    public Result<SomeResult> handle(SomeCommand command) {
        if (command == null) {
            return Result.failure(VALIDATION_ERROR, "Command is mandatory", null);
        }

        var data = SomeData.initialize(command);
        var ports = SomePorts.of(repository, clock);

        return Result.pipeline(data)
            .flatMap(SomeStages::parseCommand)
            .flatMap(d -> SomeStages.loadDependencies(d, ports))
            .flatMap(SomeStages::validateBusinessRules)
            .flatMap(d -> SomeStages.persist(d, ports))
            .within(txContext)
            .flatMap(SomeStages::buildResult);
    }
}
```

### 4. Domain value objects

```java
public record SomeName(String value) {
    public static Result<SomeName> create(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "SomeName is required", null);
        }
        var normalized = value.trim();
        if (normalized.length() > 200) {
            return Result.failure(VALIDATION_ERROR, "SomeName must not exceed 200 characters", null);
        }
        return Result.success(new SomeName(normalized));
    }
}
```

### 5. Repository interfaces

```java
public interface SomeRepository {
    Result<List<SomeRecord>> findAll(); // always Result — never raw type
    Result<SomeId> save(SomeAggregate aggregate);
    Result<SomeId> delete(SomeId id);
}
```

Repository **implementations** catch JDBC exceptions and return `Result.failure(DATABASE_ERROR, ...)`.  
They never throw from public methods.

### 6. Tests for stages

Pure stages (no I/O):
```java
@Test
void parseCommandFailsOnEmptyWorkerId() {
    var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(10, "", 60, false));
    var result = DrainDeadLettersStages.parseCommand(data);
    assertThat(result.isFailure()).isTrue();
    assertThat(result.failure().message()).contains("DrainWorkerId");
}
```

No `@Mock`, no `when(...)`, no Spring context.

Impure stages (with I/O):
```java
@Test
void claimBatchReturnsEmptyWhenRepositoryReturnsNothing() {
    var ports = new DrainDeadLettersPorts(fakeRepository, fakeReceiver, FIXED_CLOCK);
    var data = DrainDeadLettersData.initialize(command).withParsed(batchSize, workerId, leaseDuration);
    var result = DrainDeadLettersStages.claimBatch(data, ports);
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.value().claimedRecords()).isEmpty();
}
```

Mock only `DrainDeadLettersPorts` dependencies.

### 7. Controller tests

HTTP tests use standalone MockMvc:

```java
this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
    .setControllerAdvice(new RailwayErrorHandlingConfig())
    .build();

mockMvc.perform(post("/drain/trigger"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.claimedCount").value(2));
```

No `@SpringBootTest`, no full context — fast and isolated.

---

## Adding a new use case

1. Create the slice directory: `{context}/usecases/{use_case}/application/` and `infrastructure/rest/`.
2. Implement: `Command`, `Result`, `Data`, `Ports`, `Stages`, `Handler`.
3. Add REST: `Request`, `Response`, `Controller`, `spec/Spec`.
4. Write tests in this order: domain → stages → handler → controller HTTP → integration → BDD.
5. Update `docs/{context}/README.md`.

See [`.github/instructions/vertical-slice-use-case.instructions.md`](.github/instructions/vertical-slice-use-case.instructions.md) for the detailed checklist.

---

## Adding a new bounded context

1. Read [`.github/instructions/new-bounded-context.instructions.md`](.github/instructions/new-bounded-context.instructions.md).
2. Create `docs/{context}/README.md` first.
3. Create the package structure under `src/main/java/es/bluesolution/dlq_streaming/{context}/`.
4. Follow the creation sequence: docs → domain → repositories → use cases → persistence → REST → integration tests.

---

## SonarQube / code quality

The project follows these rules (enforced in code review, not yet in CI):

| Rule | Pattern |
|---|---|
| No mutable static arrays | Use `Set.of(...)` instead of `int[]` for constant sets |
| Method references over lambdas | `ProcessId::value` not `p -> p.value()` |
| No unused private methods | Remove or inline (e.g., `deadLetterRecordRowMapper()` was inlined) |
| `@Nullable` on nullable parameters | Required by JSpecify + IntelliJ analysis |
| No raw `Optional` as record components | Avoid in Result types where possible |

---

## Verification commands (CI parity)

```bash
# Everything that runs in CI
./mvnw test

# Kubernetes deployment
./mvnw test -Pkubernetes-tests

# BDD only
./mvnw test -Pacceptance-tests

# E2E (requires real infrastructure)
./mvnw verify -Pe2e-tests \
  -DDLQ_E2E_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/dlq \
  -DDLQ_E2E_POSTGRES_USERNAME=dlq_user \
  -DDLQ_E2E_POSTGRES_PASSWORD=change_me \
  -DDLQ_E2E_DATAPREPPER_URL=http://localhost:2021/log/ingest \
  -DDLQ_E2E_OPENSEARCH_URL=http://localhost:9200 \
  -Ddlq.e2e.row-count=1000
```

