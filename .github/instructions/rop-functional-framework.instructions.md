---
applyTo: "**/*.java"
---

# ROP Functional Framework Instructions

Use these rules whenever code uses `{FRAMEWORK_PACKAGE}` or an equivalent `Result<T>`/execution-context framework.

## Mental model

- `Result<T>` describes success/failure as values.
- `ResultPipeline<T>` describes a lazy computation.
- `ExecutionContext` defines how the computation runs: transaction, logging, outbox, saga, no-op testing.
- `flatMap` describes transformations.
- `within` applies execution contexts.

## Correct APIs

```java
Result.success(value)
Result.failure(errorCode, message, exception)
Result.failure(failureDescription)
result.isSuccess()
result.isFailure()
result.value()
result.failure()
result.map(...)
result.flatMap(...)
result.peek(...)
result.peekFailure(...)
Result.pipeline(data).flatMap(...).within(txContext)
```

There is no `Result.error()` method. To forward a failure from `Result<A>` into `Result<B>`:

```java
return Result.failure(previous.failure());
```

## Deferred transaction rule

```java
// ✅ Correct: all stages execute inside the transaction
return Result.pipeline(data)
    .flatMap(d -> Stages.fetch(d, ports))
    .flatMap(Stages::validate)
    .flatMap(d -> Stages.persist(d, ports))
    .within(txContext)
    .flatMap(Stages::buildResult);

// ✅ Also valid when ResultPipeline is unavailable
return txContext.execute(() ->
    Result.success(data)
        .flatMap(d -> Stages.fetch(d, ports))
        .flatMap(Stages::validate)
        .flatMap(d -> Stages.persist(d, ports))
).flatMap(Stages::buildResult);

// ❌ Wrong: DB calls run before the transaction starts
return Result.success(data)
    .flatMap(d -> Stages.fetch(d, ports))
    .flatMap(d -> Stages.persist(d, ports))
    .within(txContext);
```

## Stage purity

- Pure stage: receives `Data`, returns `Result<Data>` or `Result<Output>`, no I/O.
- Impure stage: receives `Data + Ports`, returns `Result<Data>`, performs repository/API calls.
- No stage owns transaction execution.
- No stage uses Spring annotations.

## Error handling

- Validate with `Result.failure(...)`, not thrown exceptions.
- Repository implementations may catch infrastructure exceptions and convert them to `Result.failure(DATABASE_ERROR, ...)`.
- Unexpected exceptions at execution boundaries are converted to `Result.failure(UNKNOWN_ERROR, ...)` by contexts where supported.

## Review checklist

- [ ] Meaningful success type.
- [ ] No `Result.success(null)`.
- [ ] No `Result<Void>` or `Result<Object>`.
- [ ] Failures forwarded with `Result.failure(previous.failure())`.
- [ ] Execution boundary is visible in handler.
- [ ] `Result.pipeline(...)` is used for transaction-scoped pipelines.

