# AI Quick Reference — Portable Railway Architecture

## Read order

1. `.github/copilot-instructions.md`
2. `docs/{context}/README.md`
3. Matching `.github/instructions/*.instructions.md`
4. Matching `.github/skills/*/SKILL.md` for implementation playbooks

## Instruction routing

| Work type | Instruction file |
|---|---|
| Framework/ROP usage | `instructions/rop-functional-framework.instructions.md` |
| New use case | `instructions/vertical-slice-use-case.instructions.md` |
| New context/module | `instructions/new-bounded-context.instructions.md` |
| Domain model | `instructions/domain-modeling.instructions.md` |
| Persistence/migrations | `instructions/persistence.instructions.md` |
| Security/tenancy | `instructions/security-tenancy.instructions.md` |
| Cross-context integration | `instructions/cross-context-integration.instructions.md` |
| Tests | `instructions/testing.instructions.md` |

## Golden rules

- `Result<T>` everywhere; success value is meaningful and non-null.
- Use `Result.pipeline(data)...within(txContext)` for transaction-scoped flows.
- Use `Handler -> Data/Ports -> Stages` for every use case.
- Domain records expose VOs/enums/domain collections, not raw primitives.
- JPA entities are flat; no relationship annotations.
- Cross-context communication uses interfaces/DTOs or post-commit events.
- Tests drive code: domain, stages, handler, controller, HTTP, integration, BDD.

## Forbidden

```text
Result<Void>
Result<Object>
Result.success(null)
Result.error(...)
Result.success(data).flatMap(...).within(txContext)
@Transactional on handlers/services/repository implementations/listeners
@ManyToOne / @OneToMany / @OneToOne / @ManyToMany / @JoinColumn / @JoinTable
raw UUID/String/BigDecimal/int/long/boolean/LocalDate/Instant business fields in domain aggregates/entities/policies
strategy/inheritance dispatch for domain variants
```

## Use-case files

```text
application/{UseCase}Command.java
application/{UseCase}Result.java
application/{UseCase}Data.java
application/{UseCase}Ports.java
application/{UseCase}Stages.java
application/{UseCase}Handler.java
infrastructure/rest/{UseCase}Request.java
infrastructure/rest/{UseCase}Response.java
infrastructure/rest/{UseCase}Controller.java
infrastructure/rest/spec/{UseCase}Spec.java
```

## Return type decision

| Operation | Result type |
|---|---|
| Use case | `Result<UseCaseResult>` |
| Query | `Result<QueryResult>` |
| Save/update/delete | `Result<EntityId>` |
| Predicate | `Result<Boolean>` |
| Release/count | `Result<Integer>` |
| Cross-context ack | `Result<NamedAckRecord>` |

## Handler skeleton

```java
return Result.pipeline(data)
    .flatMap(Stages::validateInput)
    .flatMap(d -> Stages.fetch(d, ports))
    .flatMap(Stages::applyBusinessRules)
    .flatMap(d -> Stages.persist(d, ports))
    .within(txContext)
    .flatMap(Stages::buildResult);
```

## Test commands

```bash
./mvnw compile -q
./mvnw test
./mvnw test -Pinclude-integration-tests -Dtest='*{Context}*IntegrationTest'
./mvnw test -Pacceptance-tests -Dcucumber.filter.tags='@{context}'
```


