# Portable `.github` AI Payload

Copy or merge this directory into a Java/Spring Boot project root to activate reusable Railway-Oriented Architecture rules for AI tools.

## Files

| Path | Purpose |
|---|---|
| `copilot-instructions.md` | Master instructions loaded by GitHub Copilot and useful for all AIs |
| `AI_QUICK_REFERENCE.md` | Compact checklist and forbidden patterns |
| `instructions/*.instructions.md` | Auto-activating rules by file glob |
| `skills/*/SKILL.md` | Playbooks for architecture implementation and vertical slices |
| `prompts/*.prompt.md` | Copy/paste prompts for common tasks |
| `chatmodes/*.chatmode.md` | Agent workflow descriptions |

## Included generic instruction files

| File | Activates on |
|---|---|
| `instructions/rop-functional-framework.instructions.md` | Java files using `Result`/execution contexts |
| `instructions/vertical-slice-use-case.instructions.md` | `**/usecases/**` |
| `instructions/new-bounded-context.instructions.md` | New context/module packages, migrations, docs |
| `instructions/domain-modeling.instructions.md` | `**/domain/model/**`, `**/domain/repository/**` |
| `instructions/persistence.instructions.md` | JPA entities, Spring Data repos, migrations |
| `instructions/security-tenancy.instructions.md` | Controllers/specs/repos/domain models with tenant/security behavior |
| `instructions/cross-context-integration.instructions.md` | Shared APIs, internals, events |
| `instructions/testing.instructions.md` | Tests and feature files |
| `instructions/documentation.instructions.md` | Docs/README/integration notes |

## Prompt templates

| File | Use |
|---|---|
| `prompts/create-use-case.prompt.md` | Create a single P3 use case |
| `prompts/create-bounded-context.prompt.md` | Create a new context/module |
| `prompts/review-architecture.prompt.md` | Review a change for architecture compliance |

## Install checklist

- [ ] Copy this `.github/` folder to the target project root.
- [ ] Copy `../AGENTS.md` to the target project root.
- [ ] Optionally copy `../.cursorrules` and `../.aider.conf.yml` for Cursor/Aider.
- [ ] Replace placeholders in `copilot-instructions.md`:
  - `{PROJECT_NAME}`
  - `{BASE_PACKAGE}`
  - `{FRAMEWORK_PACKAGE}`
  - `{context}` / `{Context}`
- [ ] Ensure the target project has or vendors the expected `Result<T>` and execution-context classes.
- [ ] Run compile/tests after the first generated change.

## Expected target package

If the target project vendors this repository's framework unchanged:

```text
{FRAMEWORK_PACKAGE}=es.bluesolution.railway_framework.framework.functional_framework
```


