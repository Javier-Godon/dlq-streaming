# 02: The `within` Combinator (Complete Rules)

> Learn exactly how to use `within` safely and idiomatically.

---

## What `within` Does (Precise Definition)

```java
<T, E> Result<T, E> within(
    Function<Supplier<Result<T, E>>, Result<T, E>> executor
);
```

**In plain English**:

> Take the computation you've built so far,
> pass it to an executor that knows how to run it,
> and return a new Result.

**Example**:

```java
result
    .flatMap(validate)
    .flatMap(persist)
    .within(transactionContext::execute);
    // ↑ Freeze the computation.
    // Pass to executor.
    // Get a new Result back.
    // That Result can be transformed further.
```

---

## The Execution Model (Step by Step)

### What Happens When You Call `within`

```java
Result<Order, Error> step1 = Result.of(order)
    .flatMap(validate)
    .flatMap(reserveStock);

// At this point:
// - No validation has run
// - No stock reservation has run
// - The computation is just described

Result<Order, Error> step2 = step1.within(txContext::execute);

// NOW:
// 1. txContext.execute() is called with the computation
// 2. txContext starts a transaction
// 3. validate() runs inside the transaction
// 4. reserveStock() runs inside the transaction
// 5. If both succeed → transaction commits → returns Result.success()
// 6. If either fails → transaction rolls back → returns Result.failure()

// step2 is a NEW Result with the outcome
```

### After `within`, You Can Continue

```java
result
    .flatMap(validate)
    .flatMap(persist)
    .within(txContext)                  // ← Execution boundary

    .flatMap(publishEvent)              // ← Continues here (after TX)
    .flatMap(notifyCustomer);
```

The second set of stages:

- Runs **after** the first `within` completes
- Receives the Result from the transaction
- Is **not** inside the transaction

This is intentional and powerful.

---

## When to Use `within` (Decision Tree)

```
Does this computation need a managed context?
    │
    ├─ YES (transaction, saga, etc.)
    │   └─ Use within before those stages
    │
    └─ NO (pure transformation)
        └─ Use flatMap
```

### Examples

#### ✅ Good: Use `within` for transactions

```java
result
    .flatMap(validate)      // Pure
    .flatMap(persist)       // Needs TX
    .within(txContext)      // ← Apply context here
```

#### ✅ Good: Use `flatMap` for pure stages

```java
result
    .flatMap(validate)      // Pure
    .flatMap(transform)     // Pure
    .flatMap(map)           // Pure
```

#### ❌ Bad: Use `within` for everything

```java
result
    .flatMap(a).within(ctx)
    .flatMap(b).within(ctx)
    .flatMap(c).within(ctx)
```

If all stages need the same context → use one `within`.

#### ❌ Bad: Hide `within` inside a stage

```java
// DON'T DO THIS
static Result<Order, Error> persist(Order order) {
    return Result.of(order)
        .flatMap(saveToDb)
        .within(txContext);  // ❌ Hidden execution
}

// CORRECT: Keep within visible
static Result<Order, Error> persist(Order order) {
    return saveToDb(order);  // Just describe it
}

// Then in the handler:
result
    .flatMap(persist)
    .within(txContext);      // ✅ Visible
```

---

## The Mental Model (Repeat This)

```
flatMap = Describe a transformation
within  = Execute inside a context
```

**Every time you see `within`**, read it as:

> "Execute this computation inside [context]."

---

## Multiple `within` Calls (Complete Rules)

### Rule 1: One `within` = One Atomic Boundary

```java
// ✅ GOOD: Both stages atomic together
result
    .flatMap(saveOrder)
    .flatMap(saveLineItems)
    .within(txContext);
```

If stages must succeed or fail together, they belong before the same `within`.

### Rule 2: Valid Multi-Boundary Pattern

Multiple `within` calls are **good** when they represent **different boundaries**:

```java
// ✅ GOOD: Saga orchestration
result
    .flatMap(validateOrder)
    .flatMap(createOrder)
    .within(dbTransaction)          // Boundary 1: DB commit

    .flatMap(publishOrderCreated)
    .within(outboxTransaction)      // Boundary 2: Event durability

    .flatMap(sendNotification);     // Outside any TX
```

Each `within` marks a **business checkpoint**. This is intentional.

### Rule 3: When NOT to Use Multiple `within`

❌ **Bad**: Tiny execution scopes

```java
// ❌ WRONG
result
    .flatMap(a).within(tx)
    .flatMap(b).within(tx)
    .flatMap(c).within(tx);
```

**Problem**: Fragmented execution, hard to reason about.

**Fix**: Group atomic stages.

```java
// ✅ CORRECT
result
    .flatMap(a)
    .flatMap(b)
    .flatMap(c)
    .within(tx);
```

---

## Common Mistakes & Fixes

### Mistake 1: Thinking `within` "Stops" the Pipeline

```java
// ❌ Misconception
result
    .flatMap(a)
    .flatMap(b)
    .within(tx)
    .flatMap(c);      // Is this inside the TX?
```

**Reality**: No. This is the correct interpretation:

```
Step 1: Describe a → b
Step 2: Execute inside TX
Step 3: Describe c
Step 4: (c hasn't executed yet)
```

If you want c inside the TX:

```java
// ✅ CORRECT
result
    .flatMap(a)
    .flatMap(b)
    .flatMap(c)       // Add c BEFORE within
    .within(tx);
```

### Mistake 2: Multiple `within` on Same Stages

```java
// ❌ CONFUSING
result
    .flatMap(a)
    .within(tx1)
    .flatMap(a)       // a already ran in tx1!
    .within(tx2);
```

**Reality**: The second `a` never runs because the first `within` already executed and produced a result.

**Better**:

```java
// ✅ CLEAR
result
    .flatMap(a)
    .within(tx1)
    .flatMap(b)       // Different stage, different context
    .within(tx2);
```

### Mistake 3: Confusing Execution with Description

```java
// ❌ WRONG THINKING
// "I'm executing persist inside a transaction"
result
    .flatMap(persist)
    .within(tx);

// ✅ RIGHT THINKING
// "I'm describing persist, then executing that description inside a transaction"
result
    .flatMap(persist)           // ← Describe
    .within(tx);                // ← Execute
```

This distinction matters for understanding composition.

---

## Best Practices (Mandatory)

### 1. Keep `within` At Orchestration Level

`within` belongs in **handlers**, not **stages**.

```java
// ✅ GOOD
@Service
public class CreateOrderHandler {
    public Result<Order, Error> handle(CreateOrderCommand cmd) {
        return Result.of(cmd.order())
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::persist)
            .within(transactionContext)     // ✅ Handler level
            .flatMap(CreateOrderStages::publishEvent);
    }
}

// ❌ BAD: within hidden in a stage
public class CreateOrderStages {
    public static Result<Order, Error> persist(Order order) {
        return saveToDb(order)
            .within(tx);                     // ❌ Hidden
    }
}
```

**Why**: Execution boundaries must be visible to understand the pipeline.

### 2. Group Atomic Operations Together

Operations that must succeed or fail together must be before the same `within`.

```java
// ✅ GOOD
result
    .flatMap(Stages::validateInventory)
    .flatMap(Stages::reserveStock)      // Both atomic
    .flatMap(Stages::saveOrder)         // Both atomic
    .within(transactionContext);

// ❌ BAD: Split atomic operation
result
    .flatMap(Stages::validateInventory)
    .within(tx1)
    .flatMap(Stages::reserveStock)
    .within(tx2);                       // Inventory checked but stock not reserved
```

### 3. Prefer Fewer, Wider Scopes

```java
// ✅ PREFERRED: One boundary
result
    .flatMap(a)
    .flatMap(b)
    .flatMap(c)
    .within(tx);

// ❌ DISCOURAGED: Multiple boundaries for the same context
result
    .flatMap(a).within(tx)
    .flatMap(b).within(tx)
    .flatMap(c).within(tx);
```

**Why**: Each `within` is a semantic boundary. Use them intentionally.

### 4. Name Your Contexts Clearly

```java
// ✅ GOOD: Clear intent
result
    .flatMap(createOrder)
    .flatMap(saveLineItems)
    .within(databaseTransaction)            // Name says what it is

    .flatMap(publishOrderCreatedEvent)
    .within(outboxWithDurability);          // Clear intent

// ❌ BAD: Unclear names
result
    .flatMap(createOrder)
    .within(ctx1)                          // What is ctx1?
    .flatMap(publish)
    .within(ctx2);                         // What is ctx2?
```

### 5. Document Multi-Boundary Pipelines

When using multiple `within`, explain the business boundaries:

```java
// Create order atomically with line items
result
    .flatMap(Stages::validateOrder)
    .flatMap(Stages::persistOrder)          // DB commit here
    .within(databaseTransaction)

    // Publish event atomically (with message durability)
    .flatMap(Stages::publishOrderCreated)
    .within(outboxTransaction)

    // Send notification (outside any TX)
    .flatMap(Stages::sendConfirmationEmail);
```

Each comment explains why there's a boundary.

---

## The Execution Model (Detailed)

### Single `within` Flow

```
Pipeline:
  flatMap(validate)
  flatMap(persist)
  .within(tx)

Execution:
  1. Caller invokes: pipeline.within(tx::execute)
  2. Result calls: tx.execute(computation)
  3. tx starts a database transaction
  4. tx calls: computation.get()  // Suppliers are lazy
  5. Stages run in sequence:
     - validate(order) → Result<Order, Error>
     - If Result.success → persist(resultOfValidate)
     - If Result.failure → stop, return failure
  6. All stages complete
  7. tx commits the transaction
  8. tx returns the final Result
  9. Caller gets the committed Result
  10. Can chain more stages after within
```

### Multiple `within` Flow

```
Pipeline:
  flatMap(a)
  flatMap(b)
  .within(tx1)
  .flatMap(c)
  .within(tx2)

Execution:
  1. First within(tx1):
     - Execute a, b inside TX1
     - Commit TX1
     - Get Result (let's say it's success)

  2. flatMap(c):
     - Stage c hasn't run yet
     - It's described based on the Result from TX1
     - Still inside the "description phase"

  3. Second within(tx2):
     - Execute c inside TX2
     - Get final Result
```

**Key insight**: Between the two `within` calls, you're still describing operations. The second execution doesn't know about the first.

---

## Validation Rules (Checklist Before Committing)

- [ ] Every `within` has a clear, documentable business reason
- [ ] All atomic operations are before the same `within`
- [ ] `within` is not hidden inside a stage
- [ ] Context names are clear and intentional
- [ ] Multiple `within` calls (if any) are documented
- [ ] No pointless `within` chaining
- [ ] Stages remain pure (no infrastructure)

---

## Virtual Threads & `within` (Safety Guaranteed)

✅ `within` is **fully compatible** with virtual threads:

```java
// Safe to use with virtual thread executors
executor.execute(() ->
    pipeline.within(txContext)
);

// TransactionTemplate uses ThreadLocal internally
// Virtual threads have their own ThreadLocal per thread
// This is safe and efficient
```

See `04_VIRTUAL_THREADS_VALIDATED.md` for complete validation.

---

## Decision Tree (Quick Reference)

```
Does this value need infrastructure (DB, API, etc.)?

    NO → Use flatMap
    │   Return Result.success(value)
    │
    YES → Does it need to be atomic with other operations?
        │
        NO → Create a separate within
        │
        YES → Put it before the same within
```

---

## One-Liner Rule

> **flatMap describes. within executes.**

If this statement is true for your code, you're using it correctly.

---

**Next**: See the `patterns/` folder for concrete patterns (Saga, Outbox, etc.).
