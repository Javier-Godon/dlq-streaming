---
applyTo: "src/main/java/**/domain/**,src/main/java/**/usecases/**,src/main/java/**/infrastructure/**,src/main/resources/**/db/migration/**,docs/**/README.md"
---

# New Bounded Context / Module Instructions

Use this when creating a new bounded context, business module, or independent feature area.

## Documentation first

Create `docs/{context}/README.md` before code with:

- Purpose and boundary.
- Owned aggregates/entities/value objects.
- Use case list.
- Business invariants and status machines.
- Persistence tables and unique constraints.
- Cross-context integrations.
- Security/tenant behavior.
- Verification commands.

If it publishes/consumes events or internal APIs, add `docs/{context}/EVENT_INTEGRATION.md`.

## Package structure

```text
src/main/java/{BASE_PACKAGE}/{context}/
  domain/
    model/
    repository/
  usecases/
    {use_case}/
      application/
      infrastructure/rest/spec/
      infrastructure/internal/     # optional
      infrastructure/events/       # optional
  shared/infrastructure/persistence/
    entity/
    springdata/

src/main/resources/db/migration/
  V{version}__init_{context}.sql

src/test/java/{BASE_PACKAGE}/{context}/
  domain/model/
  usecases/{use_case}/application/
  usecases/{use_case}/infrastructure/rest/
  integration/

src/test/resources/features/{context}/
```

Adapt migration layout to the target project. During early definition phases, prefer one editable initial migration per context if that is the team's policy.

## Creation sequence

1. Docs and BDD scenarios.
2. Domain VOs and aggregate factories with tests.
3. Repository interfaces.
4. First P3 use case and tests.
5. Persistence entities/repositories/migration.
6. REST API/spec/controller if needed.
7. Integration tests.
8. Cross-context interfaces/events if needed.
9. Verification commands.

## Completion gate

- [ ] Context README exists.
- [ ] Domain model has no primitive business fields.
- [ ] Every domain factory returns `Result<T>`.
- [ ] Every use case follows P3.
- [ ] Every controller has a Spec.
- [ ] Persistence is flat JPA.
- [ ] All tenant-scoped queries and constraints include tenant if applicable.
- [ ] Cross-context calls use internal interfaces/DTOs or post-commit events.
- [ ] Test pyramid exists for implemented behavior.
- [ ] Compile and scoped tests pass.

