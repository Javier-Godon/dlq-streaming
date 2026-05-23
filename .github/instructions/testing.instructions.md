---
applyTo: "**/*Test.java,**/*HttpTest.java,**/*IntegrationTest.java,**/features/**/*.feature"
---

# Testing Instructions

Tests are the development process. Write them before or alongside implementation.

## Pyramid

| Layer | Pattern | Spring? | Mocks |
|---|---|---:|---|
| Domain | `*Test.java` | No | None |
| Stages | `*StagesTest.java` | No | Ports only for impure stages |
| Handler | `*HandlerTest.java` | No | Repositories/internal APIs/context |
| Controller unit | `*ControllerTest.java` | No | Handler + security service |
| Controller HTTP | `*ControllerHttpTest.java` | No full context | Standalone MockMvc |
| Integration | `*IntegrationTest.java` | Yes | No mocks; Testcontainers |
| BDD | `features/{context}/*.feature` | Yes | Real services where feasible |

## Domain tests

- No mocks.
- Test every VO factory: valid, null, blank, malformed, min/max.
- Test aggregate `create(...)`: valid, every required null, business invariants.
- Test status transitions.

## Stages tests

- Pure stages: no `@Mock`, no `when(...)`.
- Impure stages: mock only Ports dependencies.
- Cover success and failure branches.

## Handler tests

- Mock all dependencies.
- Mock `txContext.execute(...)` to run the supplier immediately when using `txContext.execute`.
- If using `Result.pipeline(...).within(txContext)`, mock the execution context's `execute(Supplier<Result<T>>)` to run the supplier.
- Cover null command/query.
- Cover every repository/internal API failure branch.
- Verify skipped side effects when validation fails.

## Controller tests

- Every test calls the controller method.
- Assert HTTP status and body.
- Never use `Result.success(null)` in test setup.
- Use real result records with real field values.

## Controller HTTP tests

Use standalone MockMvc:

```java
mockMvc = MockMvcBuilders.standaloneSetup(controller)
    .setControllerAdvice(new RailwayErrorHandlingConfig())
    .build();
```

Do not use `@WebMvcTest` unless the target project explicitly supports and standardizes it.

Minimum HTTP cases:

1. valid request → 200/201/204
2. invalid JSON → 400
3. missing required field → 400
4. handler validation failure → 400
5. not found → 404
6. already exists/conflict → 409
7. authorization failure → 403 where applicable

## Integration tests

- Use PostgreSQL Testcontainers for DB-backed flows.
- Run the real handler/repository/migrations.
- Prove tenant isolation if multi-tenant.
- External adapters require provider-compatible contract tests.

## Forbidden test patterns

```text
Result.success(null)
Result.success("OK") for domain success
assertThat(controller).isNotNull() as the only assertion
@Disabled without a dated reason and tracking issue
Mocks in pure domain/stage tests
Unscoped long-running integration/BDD suites in large repos
```

## Commands

```bash
./mvnw test
./mvnw test -Pinclude-integration-tests -Dtest='*{Context}*IntegrationTest'
./mvnw test -Pacceptance-tests -Dcucumber.filter.tags='@{context}'
```

