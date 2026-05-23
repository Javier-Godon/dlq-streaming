# Execution Context Framework — Start Here

> **Goal**: Understand how to execute pure pipelines inside managed contexts (transactions, security, tracing, etc.)

## 🎯 Problem We Solve

You have a pure pipeline:

```java
Result<Order, Error> result =
    Result.of(order)
        .flatMap(Stages::validate)
        .flatMap(Stages::reserveStock)
        .flatMap(Stages::persist)
        .flatMap(Stages::publishEvent);
```

Each stage is **pure**: no database calls, no side effects, just data transformation.

**But**: At some point, `persist` needs to save to a database. How do you:

- Apply a database transaction?
- Keep stages pure?
- Remain testable?
- Stay composable?

That's what **execution contexts** solve.

---

## 🔑 Core Idea (Plain English)

**Separate** what a computation does from **how** it runs.

| Aspect | Meaning |
|--------|---------|
| **What** | Pure stages describing transformations |
| **How** | Execution contexts defining runtime behavior |

Example:

```java
result
    .flatMap(Stages::validate)
    .flatMap(Stages::persist)
    .within(transactionContext)  // ← How: apply a transaction
    .flatMap(Stages::publishEvent);
```

The stages don't know about transactions. The transaction is applied **around** them.

---

## 🌳 Mental Model (Critical)

Think of a pipeline as having two layers:

**Layer 1: Description** (what)
```
validate → reserveStock → persist → publishEvent
```

This is pure, composable, testable.

**Layer 2: Execution** (how)
```
Execute Layer 1 inside a database transaction
```

This is where side effects happen.

The `within` method connects these layers:

```java
description.within(howToExecute)
```

---

## 📚 Reading Path

| Level | File | Time | Purpose |
|-------|------|------|---------|
| **1. Beginner** | `01_MENTAL_MODEL_EXPLAINED.md` | 10 min | Understand the concept deeply |
| **2. Intermediate** | `02_WITHIN_COMBINATOR.md` | 20 min | Learn the `within` method |
| **3. Reference** | `patterns/` folder | 30 min | See concrete patterns |
| **4. Expert** | `03_THEORETICAL_FOUNDATION.md` | 40 min | Understand algebraic effects |
| **5. Production** | `TEAM_RULES_AND_BEST_PRACTICES.md` | 15 min | Coding standards |

---

## ⚡ 30-Second Quick Start

```java
// 1. Define pure stages (no infrastructure)
public static Result<Order, Error> validate(Order order) {
    if (order.items().isEmpty()) {
        return Result.failure(VALIDATION_ERROR, "Order empty");
    }
    return Result.success(order);
}

// 2. Build a pipeline
Result<Order, Error> pipeline =
    Result.of(myOrder)
        .flatMap(Stages::validate)
        .flatMap(Stages::persist)
        .flatMap(Stages::publishEvent);

// 3. Execute inside a transaction
Result<Order, Error> result = pipeline.within(txContext::execute);

// Done. No @Transactional, no proxies, no magic.
```

---

## 🏗️ Available Execution Contexts

| Context | Use Case | Example |
|---------|----------|---------|
| **TransactionExecutionContext** | Database transactions | ACID operations |
| **SagaExecutionContext** | Distributed transactions | DB + Keycloak + rollback |
| **OutboxExecutionContext** | Event durability | Publish events atomically |
| **LoggingExecutionContext** | Observability | Trace execution |
| **NoOpExecutionContext** | Testing | Bypass all effects |
| **ComposableExecutionContext** | Combine contexts | TX + Logging + Tracing |

---

## 🔄 Execution Flow (Visual)

```
┌─────────────────────────────────────────┐
│  Pipeline Definition (Pure)             │
│                                         │
│  flatMap(validate)                      │
│  flatMap(reserveStock)                  │
│  flatMap(persist)                       │
│  flatMap(publishEvent)                  │
└──────────────┬──────────────────────────┘
               │
               │ .within(txContext)
               ↓
┌──────────────────────────────────────────┐
│  Execution Context (Effectful)          │
│                                          │
│  1. Acquire database connection          │
│  2. Start transaction                    │
│  3. Execute pipeline steps               │
│  4. Commit or rollback                   │
└──────────────┬───────────────────────────┘
               │
               ↓
     Result<T, Error> returned
               │
               ↓ Can continue with more stages
        .flatMap(nextStage)
```

---

## ✅ Why This Design?

| Benefit | Explanation |
|---------|-------------|
| **Purity** | Stages have no infrastructure dependencies |
| **Testability** | Test stages without mocking Spring |
| **Composability** | Mix and match contexts freely |
| **Explicitness** | Execution boundaries are visible |
| **Flexibility** | Easy to swap execution strategies |
| **Safety** | ThreadLocal isolation per request |

---

## 🎓 Next Steps

1. **Read**: `01_MENTAL_MODEL_EXPLAINED.md`
2. **Learn**: `02_WITHIN_COMBINATOR.md`
3. **Explore**: `patterns/SAGA_PATTERN.md` or `patterns/OUTBOX_PATTERN.md`
4. **Implement**: Choose a context and use it in your handler
5. **Review**: `TEAM_RULES_AND_BEST_PRACTICES.md` before committing

---

## ❓ FAQ

**Q: Is this specific to this project?**
A: No. This framework is generic and can be used in any Java/Spring project.

**Q: Do I have to use `within`?**
A: No. For simple cases, you can use `@Transactional` on methods. But `within` is more composable.

**Q: Is this production-ready?**
A: Yes. It's tested with virtual threads and Spring Boot 4.

**Q: How does this relate to FP concepts?**
A: This is a Java interpretation of **algebraic effects and effect handlers**. Read `03_THEORETICAL_FOUNDATION.md` for details.

---

**Ready to learn? Start with `01_MENTAL_MODEL_EXPLAINED.md`.**
