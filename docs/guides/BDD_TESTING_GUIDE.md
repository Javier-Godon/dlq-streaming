# BDD Testing Guide — Cucumber with the Railway-Oriented Architecture

How to write, structure, and extend Cucumber BDD acceptance tests in this project.

---

## Why BDD here

The drain business rules are expressed in plain language that product and operations
teams can verify. The Gherkin feature files serve three purposes simultaneously:

1. **Living documentation** — describe what the system does, not how it does it.
2. **Executable specification** — run as part of the test suite with `./mvnw test -Pacceptance-tests`.
3. **Regression guard** — any refactoring that changes business behaviour breaks the scenarios.

---

## Directory layout

```
src/
  test/
    java/
      es/bluesolution/dlq_streaming/
        dlq_drain/
          bdd/
            DlqDrainCucumberTest.java     ← JUnit Platform Suite runner
            DlqDrainStepDefinitions.java  ← Gherkin step glue code

    resources/
      features/
        dlq_drain/
          drain_dead_letters.feature      ← Business scenarios in Gherkin
```

---

## Running the BDD tests

```bash
# Run all acceptance/BDD scenarios:
./mvnw test -Pacceptance-tests

# Run only the dlq_drain scenarios:
./mvnw test -Pacceptance-tests -Dcucumber.filter.tags='@dlq-drain'

# Run a specific scenario by name:
./mvnw test -Pacceptance-tests -Dcucumber.filter.scenario='Successfully drain claimed records'

# Verbose output with step timing:
./mvnw test -Pacceptance-tests -Dcucumber.plugin='pretty,summary'
```

---

## Dependencies

```xml
<!-- Cucumber JVM — step definition API -->
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-java</artifactId>
    <version>${cucumber.version}</version>
    <scope>test</scope>
</dependency>

<!-- Cucumber JUnit 5 Platform Engine -->
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-junit-platform-engine</artifactId>
    <version>${cucumber.version}</version>
    <scope>test</scope>
</dependency>

<!-- JUnit Platform Suite API — required for the runner class -->
<dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-suite</artifactId>
    <scope>test</scope>
</dependency>
```

**Reference**: [Cucumber Java documentation](https://cucumber.io/docs/cucumber/)

---

## The runner class

```java
@Suite
@IncludeEngines("cucumber")
@SelectPackages("features.dlq_drain")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME,
                        value = "es.bluesolution.dlq_streaming.dlq_drain.bdd")
@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME,
                        value = "pretty")
class DlqDrainCucumberTest {
}
```

| Annotation | Purpose |
|---|---|
| `@Suite` | JUnit Platform Suite — discovers and delegates to Cucumber |
| `@IncludeEngines("cucumber")` | Use the Cucumber JUnit 5 engine |
| `@SelectPackages("features.dlq_drain")` | Glob for `.feature` files under `src/test/resources/features/dlq_drain/` (the engine maps `features.*` to `src/test/resources/features/.*`) |
| `@ConfigurationParameter(GLUE)` | Package containing `@Given/@When/@Then` step definitions |
| `@ConfigurationParameter(PLUGIN)` | Output format — `pretty` prints coloured step-by-step output |

**Tip**: to run with HTML report output add `html:target/cucumber-reports.html` to the plugin value.

---

## Writing feature files

### Structure convention

```gherkin
Feature: <capability in business terms>
  <optional description: why this capability exists>

  Scenario: <happy path>
    Given  <precondition>
    And    <additional precondition>
    When   <the actor takes an action>
    Then   <observable outcome>
    And    <additional assertion>

  Scenario: <sad path / edge case>
    ...
```

### Data tables

Use Cucumber `DataTable` for structured input:

```gherkin
Given the dead-letter table has pending records
  | processId                         |
  | product-1_2026-05-23T10:15:30Z    |
  | product-2_2026-05-23T10:16:30Z    |
```

In step definition:
```java
@Given("the dead-letter table has pending records")
public void theDeadLetterTableHasPendingRecords(DataTable dataTable) {
    dataTable.asMaps().forEach(row -> repository.records.add(record(row.get("processId"))));
}
```

### Tags

Tag scenarios with the bounded context name:

```gherkin
@dlq-drain
Scenario: Successfully drain claimed records
  ...
```

This lets you run only the tag: `-Dcucumber.filter.tags='@dlq-drain'`.

---

## Step definitions — design rules

### No Spring context

BDD tests in this project run WITHOUT a Spring application context. The step definitions
wire together domain objects, use cases, and fakes manually:

```java
@Before
public void reset() {
    repository = new ScenarioDeadLetterRepository();
    receiver   = new ScenarioReceiver();
    result     = null;
}

@When("the drain runs with batch size {int}")
public void theDrainRunsWithBatchSize(int batchSize) {
    var handler = new DrainDeadLettersHandler(
            repository,
            receiver,
            new NoOpExecutionContext(),    // ← test execution context (no transaction)
            Clock.fixed(Instant.parse("2026-05-23T10:20:00Z"), ZoneOffset.UTC));

    result = handler.handle(new DrainDeadLettersCommand(batchSize, "bdd-worker", 60, false));
}
```

**Why no Spring context?**
- BDD scenarios describe business behaviour, not infrastructure wiring.
- No Spring context = no Testcontainers = tests run in milliseconds.
- Failures are crystal-clear (domain logic, not Spring misconfiguration).

### Fake (in-scenario) implementations

Provide lightweight in-scenario implementations of repository/receiver interfaces:

```java
private static final class ScenarioDeadLetterRepository implements DeadLetterRepository {
    private final List<DeadLetterRecord> records       = new ArrayList<>();
    private final List<String>           deletedProcessIds = new ArrayList<>();

    @Override
    public Result<List<DeadLetterRecord>> claimNextBatch(
            DrainBatchSize batchSize, DrainWorkerId workerId,
            DrainLeaseDuration leaseDuration, Instant claimedAt) {
        return Result.success(records.stream().limit(batchSize.value()).toList());
    }

    @Override
    public Result<ProcessId> deleteClaimed(ProcessId processId, DrainWorkerId workerId) {
        deletedProcessIds.add(processId.value());
        return Result.success(processId);
    }

    @Override
    public Result<Integer> releaseExpiredLeases(Instant now) { return Result.success(0); }
}
```

**Rules for fakes**:
- Visible to the scenario's assertions (package-private inner class, fields accessible).
- No framework, no thread-safety (scenarios are single-threaded).
- Named with `Scenario` prefix to distinguish from production code.
- Fail explicitly: don't silently ignore errors (return `Result.failure` when the scenario needs it).

### `NoOpExecutionContext`

The `DrainDeadLettersHandler` requires an `ExecutionContext` for its `Result.pipeline(...).within(txContext)` boundary. In BDD tests, use `NoOpExecutionContext`:

```java
new NoOpExecutionContext()
```

This runs all pipeline stages synchronously in the current thread with no transaction.
Using `NoOpExecutionContext` ensures BDD tests are deterministic and zero-overhead.

**Reference**: [ROP Functional Framework instructions](../../.github/instructions/rop-functional-framework.instructions.md)

---

## Assertions in step definitions

Use AssertJ:

```java
@Then("{int} records are received")
public void recordsAreReceived(int expectedReceived) {
    assertThat(result.isSuccess())
            .as("Drain result must be successful")
            .isTrue();
    assertThat(receiver.receivedProcessIds)
            .as("Number of records received by the receiver")
            .hasSize(expectedReceived);
}

@Then("the drain stops because the receiver failed at {string}")
public void theDrainStopsBecauseTheReceiverFailedAt(String processId) {
    assertThat(result.value().stoppedBecauseReceiverFailed()).isTrue();
    assertThat(result.value().lastProcessedProcessId()).contains(processId);
}
```

**Rules**:
- Every `@Then` makes a specific assertion — no `assertThat(result).isNotNull()` as the only check.
- Use `.as("description")` so failures are readable in the test report.
- Inspect the `Result<T>.value()` or `Result<T>.failure()` directly — no exceptions for flow control.

---

## Adding a new scenario

### Step 1: write the scenario in the feature file

```gherkin
Scenario: Drain releases expired leases before claiming new records
  Given the dead-letter table has 2 expired-claimed records
  And the dead-letter table has 3 pending records
  When the drain runs with lease release enabled and batch size 10
  Then 2 leases are released
  And 3 records are claimed and stored
  And the drain does not stop because the receiver failed
```

### Step 2: run Cucumber to see "undefined" steps

```bash
./mvnw test -Pacceptance-tests
```

Cucumber prints:

```
Undefined steps:
You can implement missing steps with the snippets below:

@Given("the dead-letter table has {int} expired-claimed records")
public void theDeadLetterTableHas_ExpiredClaimedRecords(int arg0) {
    // Write code here that turns the phrase above into concrete actions
    throw new io.cucumber.java.PendingException();
}
```

### Step 3: implement the step definitions

Add the new step methods to `DlqDrainStepDefinitions.java`:

```java
@Given("the dead-letter table has {int} expired-claimed records")
public void theDeadLetterTableHasExpiredClaimedRecords(int count) {
    for (int i = 0; i < count; i++) {
        repository.expiredClaimedRecords.add(record("expired-" + i + "_2026-05-24T10:00:00Z"));
    }
}
```

Update `ScenarioDeadLetterRepository.releaseExpiredLeases()` to return the count.

### Step 4: run again and confirm green

```bash
./mvnw test -Pacceptance-tests
```

---

## Adding a new feature file (new bounded context)

1. Create `src/test/resources/features/{context}/{capability}.feature`.
2. Create `src/test/java/{package}/{context}/bdd/{Context}CucumberTest.java`:
   ```java
   @Suite
   @IncludeEngines("cucumber")
   @SelectPackages("features.{context}")
   @ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "{package}.{context}.bdd")
   @ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME, value = "pretty")
   class {Context}CucumberTest { }
   ```
3. Create `src/test/java/{package}/{context}/bdd/{Context}StepDefinitions.java`.
4. Run `./mvnw test -Pacceptance-tests`.

---

## Connecting BDD to the P3 vertical slice

The BDD step glue code wires to the **handler** layer only. It does not call controllers,
REST endpoints, or Spring beans:

```
Feature file  →  @When step  →  Handler.handle(Command)  →  Stages
                                      ↑
                  ScenarioRepository (fake)
                  ScenarioReceiver   (fake)
                  NoOpExecutionContext
```

This means:
- The full application pipeline is exercised (command parsing, stages, result building).
- Infrastructure concerns (DB, HTTP) are faked — tests are deterministic and fast.
- If a stage changes its contract, the BDD tests catch it.

For full infrastructure coverage (real DB + real receiver), see:
- `DeadLetterRepositoryIntegrationTest` — real PostgreSQL via Testcontainers
- `DataPrepperDeadLetterReceiverTest` — real HTTP via MockRestServiceServer
- `KubernetesDeploymentTest` / `KubernetesDataPrepperTest` — real Kubernetes + real DB

---

## Current scenarios

**Feature file**: `src/test/resources/features/dlq_drain/drain_dead_letters.feature`

| Scenario | What it proves |
|---|---|
| Successfully drain claimed records | 2 records seeded → both received → both deleted → `stoppedBecauseReceiverFailed=false` |
| Stop immediately when the receiver fails | 3 records seeded → receiver fails on #2 → 2 received, 1 deleted, `stoppedBecauseReceiverFailed=true`, `lastProcessedProcessId` matches |

---

## Pitfalls

| Pitfall | Symptom | Fix |
|---|---|---|
| Spring context loaded in `@Before` | Slow startup, Testcontainers launched | Remove `@SpringBootTest`; use manual wiring in `@Before` |
| Shared mutable state between scenarios | Test pollution — scenario 2 sees data from scenario 1 | Reset state in `@Before` (not `@After`) |
| `Result.success(null)` in fake setup | NPE in assertion | Use meaningful result records: `Result.success(new MyResult(...))` |
| Step regex too greedy | Unintended step matching | Use word boundaries: `{int}`, `{string}`, `{word}` instead of `(.*)` |
| Missing Glue package | `Undefined steps` even though source exists | Check `@ConfigurationParameter(GLUE)` points to the correct package |
| `DataTable` column mismatch | `NullPointerException` in `row.get("column")` | Ensure feature file column header matches exactly (case-sensitive) |

