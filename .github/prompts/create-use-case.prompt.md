# Prompt: Create a Railway/P3 Use Case

Use this prompt with any AI after installing the portable kit.

```text
Create the {UseCaseName} use case in the {context} context.

Business goal:
{describe the user/business outcome}

Command/query fields:
{list fields, types, validation rules}

Result fields:
{list meaningful result fields; no Result<Void>/Object/String ack}

Domain rules:
{invariants, status transitions, duplicate checks, tenant/security rules}

Persistence changes:
{tables/columns/indexes or "none"}

REST API:
{method path, request, response, status codes, roles or "internal only"}

Cross-context integration:
{Tier 1 interfaces or Tier 2 events or "none"}

Testing requirements:
- Domain tests for new VOs/aggregates
- Stages tests for every pure/impure stage
- Handler tests for success and every failure branch
- Controller + HTTP tests if REST-facing
- Integration tests if DB-backed
- BDD scenarios if the project uses Cucumber

Mandatory architecture:
- Follow `.github/copilot-instructions.md`
- Follow `.github/instructions/vertical-slice-use-case.instructions.md`
- Use `Result.pipeline(data)...within(txContext)` or `txContext.execute(...)`
- No `@Transactional` outside Spring Data `@Modifying @Query`
- No JPA relationship annotations
- No primitive business fields in domain records

After implementation, run scoped compile/tests and report results.
```

