---
name: railway-functional-architecture
description: Use when implementing or reviewing Java/Spring Boot code that uses Railway-Oriented Programming, Result pipelines, ExecutionContext, vertical slices, DDD, flat JPA persistence, or clean modular boundaries.
---

# Skill: Railway Functional Architecture

## Goal

Make generated code look like it was written by a senior maintainer of a Railway-Oriented Java/Spring Boot modular monolith.

## Read first

1. `.github/copilot-instructions.md`
2. `.github/AI_QUICK_REFERENCE.md`
3. `.github/instructions/rop-functional-framework.instructions.md`
4. Any file-pattern-specific instruction that applies to the files being edited

## Implementation rules

- Start from behavior and tests.
- Model domain facts with VOs before writing handlers.
- Keep handlers thin and explicit.
- Keep stages static and cohesive.
- Use Ports records for all dependencies.
- Put transaction boundaries in handlers only.
- Keep persistence outside domain.
- Keep REST DTOs outside domain.
- Keep cross-context contracts in shared/internal API packages.

## Architecture review checklist

### ROP

- [ ] Every business path returns `Result<T>`.
- [ ] Success type is meaningful.
- [ ] Failures are values, not thrown validation exceptions.
- [ ] Failure forwarding uses `Result.failure(previous.failure())`.

### Execution contexts

- [ ] Transaction scope uses `Result.pipeline(...).within(txContext)` or `txContext.execute(...)`.
- [ ] No eager `Result.success(...).flatMap(...).within(...)`.
- [ ] No hidden `.within(...)` in stages/repositories.

### Domain

- [ ] No primitive business fields in aggregates/entities/policies.
- [ ] Factories return `Result<T>`.
- [ ] Variants use composition, not inheritance/strategy dispatch.

### Persistence

- [ ] JPA entities are flat.
- [ ] No relationship annotations.
- [ ] Tenant filters and constraints exist where applicable.

### Testing

- [ ] Pure stages have no mocks.
- [ ] Handler tests cover orchestration branches.
- [ ] HTTP tests use standalone MockMvc.
- [ ] Integration tests prove DB mapping and tenant isolation where applicable.

## Output expectation

When asked to implement, edit files directly and verify with compile/tests. When asked to review, produce CRITICAL/WARNING/INFO findings with file references and concrete fixes.

