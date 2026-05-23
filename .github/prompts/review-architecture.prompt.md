# Prompt: Review Railway Architecture Compliance

```text
Review the current changes for compliance with the portable Railway-Oriented Java/Spring Boot architecture.

Scope:
{files, module, PR, or git diff range}

Use these rules:
- `.github/copilot-instructions.md`
- `.github/AI_QUICK_REFERENCE.md`
- Relevant `.github/instructions/*.instructions.md`

Report findings grouped as:

CRITICAL — must fix before merge
- Result/ROP violations
- eager transaction boundary
- hidden transaction boundary
- @Transactional in forbidden layer
- JPA relationship annotations
- primitive business fields in domain aggregates/entities/policies
- cross-context direct domain imports
- missing mandatory tests

WARNING — should fix
- weak naming
- unclear stage responsibility
- missing docs
- missing tenant isolation tests
- oversized DTO/event

INFO — improvement ideas
- refactoring suggestions
- simplifications

For every finding include:
- file path
- symbol or line reference if known
- why it violates the rule
- concrete fix

If you edit code, run compile and scoped tests after fixes.
```

