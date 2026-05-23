---
name: vertical-slice-generator
description: Use before creating a new command/query use case. Generates or validates the P3 slice: Command/Query, Result, Data, Ports, Stages, Handler, REST DTOs, Controller, Spec, tests, and docs.
---

# Skill: Vertical Slice Generator

## Inputs to collect

- Context/module name.
- Use case name.
- Command or query.
- REST endpoint, method, auth roles, and status codes.
- Aggregate(s) touched.
- Repository/internal API dependencies.
- Business rules and error cases.
- Tenant/security behavior.
- Persistence changes.
- BDD scenarios.

## Generation sequence

1. Update or create `docs/{context}/README.md` with the intended use case.
2. Write BDD scenario(s) in `src/test/resources/features/{context}/` if the project uses Cucumber.
3. Write domain tests for any new VOs/aggregates.
4. Create application files:
   - `{UseCase}Command` or `{UseCase}Query`
   - `{UseCase}Result`
   - `{UseCase}Data`
   - `{UseCase}Ports`
   - `{UseCase}Stages`
   - `{UseCase}Handler`
5. Write stages and handler tests.
6. Create REST request/response/spec/controller if API-facing.
7. Write controller unit and HTTP tests.
8. Add persistence/migration/repository implementation if DB-backed.
9. Write integration tests.
10. Run verification commands.

## Required P3 invariant

A generated use case is incomplete unless the handler pipeline reads top-to-bottom and every stage has one clear responsibility.

```java
return Result.pipeline(data)
    .flatMap(Stages::parse)
    .flatMap(d -> Stages.fetchSomething(d, ports))
    .flatMap(Stages::validateBusinessRule)
    .flatMap(d -> Stages.persist(d, ports))
    .within(txContext)
    .flatMap(Stages::buildResult);
```

## Completion gate

- [ ] All P3 files exist.
- [ ] Tests exist for changed behavior.
- [ ] Persistence is flat and tenant-safe if applicable.
- [ ] OpenAPI spec exists for controllers.
- [ ] Docs updated.
- [ ] Compile/tests run.

