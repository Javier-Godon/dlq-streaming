# Prompt: Create a Bounded Context / Module

```text
Create the {context} bounded context/module using the portable Railway-Oriented Java/Spring Boot architecture.

Context purpose:
{business purpose and boundary}

Aggregates/entities/VOs:
{list the domain concepts and invariants}

Initial use cases:
{list command/query use cases in priority order}

Persistence:
{tables, schemas, constraints, indexes, migration policy}

Security/tenancy:
{tenant source, roles, isolation requirements}

Cross-context integration:
{Tier 1 interfaces, Tier 2 events, snapshots, or none}

Testing:
{BDD scenarios, integration tests, domain/stages/handler/controller tests}

Mandatory instructions:
- Read `.github/copilot-instructions.md`
- Read `.github/instructions/new-bounded-context.instructions.md`
- Read `.github/instructions/domain-modeling.instructions.md`
- Read `.github/instructions/persistence.instructions.md`
- Read `.github/instructions/testing.instructions.md`
- Follow P3 vertical slices for every use case
- Use `Result<T>` and deferred execution contexts
- No JPA relationship annotations
- No primitive business fields in domain records

Deliver code, docs, tests, and scoped verification results.
```

