---
applyTo: "docs/**/*.md,**/README.md,**/EVENT_INTEGRATION.md"
---

# Documentation Instructions

Documentation is part of the architecture and must stay current with code.

## Required per context/module

`docs/{context}/README.md` should include:

- Context purpose and boundaries.
- Aggregates, entities, value objects, and repositories.
- Use case list and status.
- Business rules and invariants.
- Persistence tables and important constraints.
- Security and tenancy rules if applicable.
- Test commands scoped to the context.

If cross-context integration exists, add `docs/{context}/EVENT_INTEGRATION.md` with:

- Tier 1 interfaces consumed/exposed.
- Tier 2 events published/consumed.
- Idempotency keys and failure policy.
- Source facts carried across boundaries.

## Keep docs in sync when code changes

Update docs when you:

- Add, remove, or rename a use case.
- Change aggregate fields, value objects, or status transitions.
- Change repository queries, table constraints, or migrations.
- Add/modify cross-context interfaces or events.
- Add a new architectural pattern.

## Documentation style

- Prefer concise, operational rules over abstract essays.
- Include examples that an AI can copy safely.
- Call out forbidden patterns.
- Include verification commands.

