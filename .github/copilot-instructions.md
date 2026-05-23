# Portable AI Contributor Instructions — Railway-Oriented Java/Spring Boot Architecture

> Use these instructions in any Java/Spring Boot project that vendors or mimics `{FRAMEWORK_PACKAGE}`.
> Replace placeholders such as `{PROJECT_NAME}`, `{BASE_PACKAGE}`, `{FRAMEWORK_PACKAGE}`, and `{context}` during installation.

---

## Architecture in one sentence

Build explicit vertical slices where domain rules are modeled with typed value objects, application flows are `Result<T>` pipelines, execution effects are applied only at handler boundaries with execution contexts, persistence is flat and explicit, and tests drive the implementation.

---

## Mandatory pre-task checklist

Before writing code:

- [ ] Read this file.
- [ ] Read `docs/{context}/README.md` or create it if this is a new context.
- [ ] Read the relevant auto-instruction file under `.github/instructions/`.
- [ ] Identify whether the task is a command, query, domain-only change, persistence change, or cross-context integration.
- [ ] Choose the correct `Result<T>` return type.
- [ ] Decide the transaction/execution boundary before coding.
- [ ] Write or update tests first whenever behavior changes.

After coding:

- [ ] No `Result<Void>`, `Result<Object>`, `Result.success(null)`, or fake `Result<String>` acknowledgements.
- [ ] No eager `Result.success(data).flatMap(...).within(txContext)`.
- [ ] No `@Transactional` except Spring Data `@Modifying @Query` methods.
- [ ] No JPA relationship annotations.
- [ ] No raw primitive business fields in domain aggregates/entities/policies.
- [ ] Controllers implement `*Spec` interfaces and map tenant/user context from security, not request payloads, when multi-tenancy exists.
- [ ] Cross-context calls use internal interfaces/DTOs or post-commit/outbox events.
- [ ] Tests cover success, validation, not-found/already-exists, authorization, tenant isolation if applicable, and persistence mapping.
- [ ] Docs are updated.

---

## 13 core commandments

1. **Result everywhere:** use `Result<T>` for business/application outcomes. Success values are meaningful and non-null.
2. **No fake success types:** no `Result<Void>`, `Result<Object>`, `Result.success(null)`, or `Result<String>` with `"OK"`.
3. **Correct Result API:** use `Result.success(v)`, `Result.failure(error)`, `result.isSuccess()`, `result.isFailure()`, `result.value()`, and `result.failure()`.
4. **Deferred execution:** use `Result.pipeline(data)...within(txContext)` or `txContext.execute(() -> Result.success(data).flatMap(...))`.
5. **Visible boundaries:** `.within(...)`/`txContext.execute(...)` appears in handlers, not hidden in stages.
6. **P3 vertical slice:** every use case is `Handler -> Data/Ports -> Stages`, with REST/spec files at the slice edge.
7. **Pure domain:** domain models are immutable records/classes with factory methods returning `Result<T>`; no thrown validation exceptions.
8. **No primitive business fields in domain:** use value objects, enums, and domain collections.
9. **Composition over inheritance:** use type discriminator enums + optional composed VOs for variants; avoid abstract domain hierarchies and strategy dispatch for business variants.
10. **Flat JPA:** no relationship annotations; use scalar columns and plain FK IDs; repositories load children explicitly.
11. **Clean context boundaries:** no direct domain imports across bounded contexts/modules; use interfaces/DTOs or post-commit events.
12. **TDD/BDD:** tests are part of the development process, not an afterthought.
13. **Docs are executable context:** module README files, architecture notes, and prompt/skill docs must evolve with code.

---

## `Result<T>` return decision tree

| Situation | Return |
|---|---|
| Create/save/update/delete aggregate | `Result<EntityId>` or `Result<UseCaseResult>` |
| Query/read model | `Result<QueryResult>` or `Result<List<QueryItem>>` |
| Use case exposed to API | `Result<UseCaseResult>` named record |
| Predicate query (`exists`, `isAllowed`) | `Result<Boolean>` |
| Count/release affected rows | `Result<Integer>` |
| Cross-context acknowledgement | `Result<NamedAckRecord>` |
| Anything else | A concrete named record with meaningful fields |

Forward failures across types with:

```java
return Result.failure(previous.failure());
```

---

## Canonical P3 use-case layout

```text
{context}/usecases/{use_case}/
  application/
    {UseCase}Command.java | {UseCase}Query.java
    {UseCase}Result.java
    {UseCase}Data.java
    {UseCase}Ports.java
    {UseCase}Stages.java
    {UseCase}Handler.java
  infrastructure/
    rest/
      {UseCase}Request.java
      {UseCase}Response.java
      {UseCase}Controller.java
      spec/{UseCase}Spec.java
    internal/{UseCase}Internal.java      # optional internal API implementation
    events/{UseCase}EventListener.java   # optional post-commit listener adapter
```

Handlers orchestrate. Data holds state. Ports hold dependencies. Stages contain business steps.

---

## Canonical handler shape

```java
@Service
public class CreateThingHandler {
    private final ThingRepository thingRepository;
    private final TransactionExecutionContext txContext;

    public CreateThingHandler(ThingRepository thingRepository,
                              TransactionExecutionContext txContext) {
        this.thingRepository = thingRepository;
        this.txContext = txContext;
    }

    public Result<CreateThingResult> handle(CreateThingCommand command) {
        if (command == null) {
            return Result.failure(VALIDATION_ERROR, "Command is mandatory", null);
        }

        var data = CreateThingData.initialize(command);
        var ports = CreateThingPorts.of(thingRepository);

        return Result.pipeline(data)
            .flatMap(CreateThingStages::parseCommand)       // pure
            .flatMap(d -> CreateThingStages.ensureUnique(d, ports))
            .flatMap(CreateThingStages::buildDomain)        // pure
            .flatMap(d -> CreateThingStages.persist(d, ports))
            .within(txContext)
            .flatMap(CreateThingStages::buildResult);
    }
}
```

Use `txContext.execute(() -> Result.success(data).flatMap(...))` only when the project has not adopted `Result.pipeline(...)` yet.

---

## Multi-tenancy rule, if applicable

- Tenant/user context comes from security/session context, never from request body/path for tenant-scoped self-service endpoints.
- `TenantId` is the first field of tenant-scoped aggregates.
- Every query filters by tenant.
- Every tenant-scoped unique constraint includes `tenant_id`.
- Tests prove tenant A cannot read/update/delete tenant B data.

---

## Verification commands

```bash
./mvnw compile -q
./mvnw test
./mvnw test -Pinclude-integration-tests -Dtest='*{Context}*IntegrationTest'
./mvnw test -Pacceptance-tests -Dcucumber.filter.tags='@{context}'
```

Adapt profiles and filters to the target project. Keep slow suites scoped.

