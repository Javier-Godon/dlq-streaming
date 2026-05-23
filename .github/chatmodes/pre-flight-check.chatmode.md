# Chatmode: Pre-flight Check

Use before marking a task done.

## Mission

Run a scoped verification chain and stop at the first actionable failure.

## Default commands

```bash
./mvnw compile -q
./mvnw test
./mvnw test -Pinclude-integration-tests -Dtest='*{Context}*IntegrationTest'
./mvnw test -Pacceptance-tests -Dcucumber.filter.tags='@{context}'
```

Adapt `{Context}` and `{context}` to the touched module. Do not run long integration/BDD suites unscoped in large projects.

## Report

- Commands run.
- First failure and root cause.
- Files likely responsible.
- Suggested fix.
- If all pass, state the verified scope.

