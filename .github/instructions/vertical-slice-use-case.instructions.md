---
applyTo: "**/usecases/**"
---

# Vertical Slice Use Case Instructions

Use this pattern for every application use case.

## Required layout

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
```

## Responsibilities

| File | Responsibility |
|---|---|
| Command/Query | Input from API/internal caller; primitives allowed |
| Result | Named output record; primitives allowed for DTO boundary |
| Data | Immutable pipeline state; no dependencies |
| Ports | Dependencies only: repositories, internal APIs, publishers, clocks, policies |
| Stages | Static pure/impure steps; no hidden transactions |
| Handler | Null check, initialize data/ports, execute pipeline, build result |
| Request/Response | REST boundary mapping and validation |
| Spec | OpenAPI annotations and response contract |
| Controller | Auth/security context, command mapping, response mapping |

## Data record

```java
@With
@Builder
public record CreateThingData(
    CreateThingCommand command,
    @Nullable TenantId tenantId,
    @Nullable Thing thing,
    @Nullable ThingId thingId
) {
    public static CreateThingData initialize(CreateThingCommand command) {
        return new CreateThingData(command, null, null, null);
    }
}
```

## Ports record

```java
public record CreateThingPorts(
    ThingRepository thingRepository,
    Clock clock
) {
    public static CreateThingPorts of(ThingRepository thingRepository, Clock clock) {
        return new CreateThingPorts(thingRepository, clock);
    }
}
```

## Stages

```java
final class CreateThingStages {
    private CreateThingStages() { }

    static Result<CreateThingData> parseCommand(CreateThingData data) { ... }       // pure
    static Result<CreateThingData> validateBusinessRules(CreateThingData data) { ... } // pure
    static Result<CreateThingData> ensureUnique(CreateThingData data, CreateThingPorts ports) { ... } // impure
    static Result<CreateThingData> persist(CreateThingData data, CreateThingPorts ports) { ... } // impure
    static Result<CreateThingResult> buildResult(CreateThingData data) { ... }      // pure
}
```

## Handler

```java
public Result<CreateThingResult> handle(CreateThingCommand command) {
    if (command == null) {
        return Result.failure(VALIDATION_ERROR, "Command is mandatory", null);
    }
    var data = CreateThingData.initialize(command);
    var ports = CreateThingPorts.of(thingRepository, clock);

    return Result.pipeline(data)
        .flatMap(CreateThingStages::parseCommand)
        .flatMap(d -> CreateThingStages.ensureUnique(d, ports))
        .flatMap(CreateThingStages::validateBusinessRules)
        .flatMap(d -> CreateThingStages.persist(d, ports))
        .within(txContext)
        .flatMap(CreateThingStages::buildResult);
}
```

## Controller rules

- Every controller implements a `*Spec.java` interface.
- Tenant/user/security context comes from the security service, not from request body, when multi-tenancy exists.
- Use `RailwayResponseBuilder` or a consistent mapping from `FailureResultDescription.ErrorCode` to HTTP status.
- Use `MockMvcBuilders.standaloneSetup(controller)` for HTTP tests.

## Done checklist

- [ ] All application files created.
- [ ] REST files created if API-facing.
- [ ] Domain/stage/handler/controller/HTTP/integration/BDD tests added as applicable.
- [ ] Documentation updated.
- [ ] Compile and scoped tests run.

