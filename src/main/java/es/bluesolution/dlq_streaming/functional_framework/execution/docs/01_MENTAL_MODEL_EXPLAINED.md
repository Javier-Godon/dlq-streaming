# 01: Mental Model Explained (Complete)

> **This is the most important document.** If you understand this, everything else is details.

---

## The Problem (Concrete Example)

You're building an e-commerce system. You need to create an order.

### Attempt 1: Put everything in the handler

```java
@Service
public class CreateOrderHandler {
    private final OrderRepository repository;
    private final StockService stock;

    public Order create(Order order) {
        // 1. Validate
        if (order.items().isEmpty()) {
            throw new ValidationException("Order cannot be empty");
        }

        // 2. Reserve stock
        for (Item item : order.items()) {
            stock.reserve(item.id(), item.quantity());  // What if this fails?
        }

        // 3. Save order
        repository.save(order);  // Database is committed

        // 4. Publish event
        eventBus.publish(new OrderCreated(order));  // What if this fails?

        return order;  // Hope everything succeeded
    }
}
```

**Problems**:

- ❌ If `stock.reserve()` fails, order is still saved.
- ❌ If `publish()` fails, order is saved but event lost.
- ❌ Infrastructure (repositories, services) is tangled with business logic.
- ❌ Hard to test without mocking Spring.
- ❌ Impossible to reuse validation logic outside this handler.
- ❌ One line throws exceptions, making composition impossible.

### Attempt 2: Separate concerns (ROP style)

Idea: **Keep business logic pure. Inject execution concerns separately.**

```java
// Pure logic (no Spring, no database, no exceptions)
public class CreateOrderStages {
    public static Result<Order, Error> validate(Order order) {
        if (order.items().isEmpty()) {
            return Result.failure(VALIDATION_ERROR, "Order cannot be empty");
        }
        return Result.success(order);
    }

    public static Result<Order, Error> reserveStock(Order order) {
        // Check inventory (pure logic)
        for (Item item : order.items()) {
            if (inventory[item.id()] < item.quantity()) {
                return Result.failure(BUSINESS_RULE_ERROR,
                    "Insufficient stock for " + item.id());
            }
        }
        return Result.success(order);
    }

    public static Result<Order, Error> persist(Order order) {
        // This returns a description, not execution
        return repository.save(order);
    }

    public static Result<Order, Error> publishEvent(Order order) {
        // Also returns a description
        return eventBus.publish(new OrderCreated(order));
    }
}

// Handler orchestrates
@Service
public class CreateOrderHandler {
    private final OrderRepository repository;
    private final EventBus eventBus;
    private final TransactionContext txContext;

    public Result<Order, Error> handle(CreateOrderCommand cmd) {
        return Result.of(cmd.order())
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::reserveStock)
            .flatMap(CreateOrderStages::persist)
            .flatMap(CreateOrderStages::publishEvent);
    }
}
```

**Better**:

- ✅ Stages are pure (can test without Spring).
- ✅ No exceptions (easier to compose).
- ✅ Business logic is separate from infrastructure.

**But we still need transactions!**

---

## The Insight: Separate Description from Execution

Here's the key realization:

**What if the pipeline doesn't execute anything?**

What if it just **describes** what should happen?

```java
Result<Order, Error> description =
    Result.of(order)
        .flatMap(validate)
        .flatMap(reserveStock)
        .flatMap(persist)
        .flatMap(publishEvent);
```

This looks like it's doing work, but it's not (yet).

Each `flatMap` just builds a bigger description.

**The actual execution happens when we invoke `within`:**

```java
Result<Order, Error> result = description.within(txContext::execute);
```

Now the transaction is applied, execution happens, and we get a result.

---

## How This Maps to Your Brain (Three Views)

### View 1: The Functional Programmer's View

**Description** = Free Monad (computation AST)

```
                 ┌─ validate
                 ├─ reserveStock
describe()  →   ├─ persist
                 ├─ publishEvent
                 └─ (not executed yet)
```

**Execution** = Interpreter (walks the AST)

```
              ┌─────────────────────┐
interpret() →│ Start TX            │
             │ Execute all steps    │
             │ Commit or Rollback   │
             │ Return Result        │
             └─────────────────────┘
```

### View 2: The Java Developer's View (Streams)

You already understand this pattern:

```java
List<Integer> numbers = List.of(1, 2, 3, 4, 5);

Stream<Integer> pipeline = numbers.stream()
    .map(n -> n * 2)        // Described
    .filter(n -> n > 5)     // Described
    .map(n -> n - 1);       // Described

// ☝️ Nothing happens yet!

// Execution happens here:
List<Integer> result = pipeline.collect(Collectors.toList());
```

Your `within` is the same idea:

```java
Result<Order, Error> pipeline = Result.of(order)
    .flatMap(validate)      // Described
    .flatMap(persist)       // Described
    .flatMap(publish);      // Described

// ☝️ Nothing happens yet!

// Execution happens here:
Result<Order, Error> result = pipeline.within(txContext::execute);
```

### View 3: The Mental Image (Simplest)

Imagine the pipeline as a **sealed box**:

```
┌──────────────────────────────┐
│  Sealed Computation Box      │
│                              │
│  do validate                 │
│  do reserveStock             │
│  do persist                  │
│  do publishEvent             │
│                              │
│  (waiting to be opened)      │
└──────────────────────────────┘
```

`within` is the key that opens the box **inside a specific context**:

```java
result = sealedBox.within(transactionContext::open);
```

When the box opens inside the transaction context:

- The transaction starts
- All steps execute
- The transaction commits (or rolls back)
- The box is replaced with a result

---

## Why This Matters: The Real Example

### Without Execution Contexts

```java
@Transactional  // This applies to the entire method
public Order create(CreateOrderCommand cmd) {
    // All of these run inside ONE transaction
    Order validated = validate(cmd.order());
    Order reserved = reserveStock(validated);
    Order saved = repository.save(reserved);
    publishEvent(saved);
    return saved;
}
```

**Problems**:

- ❌ If `publishEvent` fails, the database rolls back.
- ❌ `@Transactional` is method-bound, not pipeline-bound.
- ❌ Hard to test (need `@SpringBootTest`).
- ❌ Can't reuse validation outside this method.

### With Execution Contexts

```java
public Result<Order, Error> handle(CreateOrderCommand cmd) {
    return Result.of(cmd.order())
        .flatMap(CreateOrderStages::validate)
        .flatMap(CreateOrderStages::reserveStock)
        .within(txContext)              // ← TX commits here

        .flatMap(CreateOrderStages::publishEvent);  // ← Outside TX
}
```

**Benefits**:

- ✅ Only DB operations are in the transaction.
- ✅ Event publishing happens **after** the transaction commits.
- ✅ If event publishing fails, DB is still committed.
- ✅ Can test stages without Spring.
- ✅ Can reuse stages in other handlers.

---

## The Core Principle (Repeat This)

> **flatMap describes a computation.**
> **within executes a computation inside a context.**

Everything else follows from this.

---

## What "Execution Context" Means (Formally)

An **execution context** is a strategy for running a computation:

```java
public interface ExecutionContext {
    <T, E> Result<T, E> execute(
        Supplier<Result<T, E>> computation
    );
}
```

Translation:

```java
// "Given a computation (as a supplier),
// execute it however you want and return a result."
```

Example implementations:

```java
// 1. Database transaction
public class TransactionContext implements ExecutionContext {
    public <T, E> Result<T, E> execute(Supplier<Result<T, E>> computation) {
        return template.execute(status -> computation.get());
    }
}

// 2. Saga (distributed transaction)
public class SagaContext implements ExecutionContext {
    public <T, E> Result<T, E> execute(Supplier<Result<T, E>> computation) {
        Result<T, E> result = delegate.execute(computation);
        if (result.isFailure()) {
            executeCompensations();  // Rollback
        }
        return result;
    }
}

// 3. Logging (observability)
public class LoggingContext implements ExecutionContext {
    public <T, E> Result<T, E> execute(Supplier<Result<T, E>> computation) {
        log.info("Starting");
        Result<T, E> result = computation.get();
        log.info("Finished: {}", result);
        return result;
    }
}
```

Each context defines **how** to execute, but stages don't care **which** context they're in.

---

## Multiple Contexts (How They Compose)

You can apply multiple contexts:

```java
Result<Order, Error> result =
    Result.of(order)
        .flatMap(validate)
        .flatMap(persist)
        .within(transactionContext)          // 1. Apply TX

        .flatMap(publishEvent)
        .within(outboxContext)               // 2. Apply Outbox

        .flatMap(sendNotification)
        .within(loggingContext);             // 3. Apply Logging
```

Each `within`:

- Executes all previous stages inside that context
- Produces a result
- That result becomes the input for the next stage

This is **compositional**: you can stack contexts without changing stages.

---

## The Mental Checklist (Before Writing Code)

Ask yourself:

1. **Is this stage pure?** (No DB, no API calls, no side effects)
   - Yes → `flatMap(stage)`
   - No → Move it into a context

2. **Do these stages need to be atomic?** (All succeed or all fail together)
   - Yes → Put them before the same `within`
   - No → Use separate `within` calls

3. **What context does this need?** (TX, Saga, Outbox, etc.)
   - Choose the right `ExecutionContext` implementation

4. **Is the boundary explicit?**
   - Yes → Your design is good
   - No → Rethink the structure

---

## Common Misconception (Clear This Up Now)

**Myth**: "`within` terminates the pipeline."

**Reality**: `within` produces a new `Result` that can be further transformed.

```java
result
    .flatMap(a)
    .flatMap(b)
    .within(tx)              // ← Doesn't stop here
    .flatMap(c)              // ← Continues here
    .flatMap(d);
```

Read as:

1. Describe: a, b
2. Execute inside TX
3. Describe: c, d
4. (Potentially) execute inside another context

---

## One More Visual (The Most Accurate)

```
          DESCRIPTION PHASE              DESCRIPTION PHASE
          (pure, no effects)             (pure, no effects)

    flatMap(validate)               flatMap(publish)
    flatMap(reserve)        →       flatMap(notify)
    ↓                                       ↓
    ├─ Result produced         ├─ Result produced
    │
    ↓
  within(tx)
    ├─ Execute inside TX
    ├─ DB operations
    ├─ Commit or rollback
    ├─ Return result
```

---

## Summary (Repeat to Your Team)

| Aspect | Meaning |
|--------|---------|
| **Stages** | Pure transformations, no side effects |
| **flatMap** | Compose stages (building description) |
| **within** | Apply execution context (running description) |
| **Result** | Value that can be further transformed |
| **Composition** | Stack multiple contexts |

---

**Next**: Read `02_WITHIN_COMBINATOR.md` to learn the exact rules for using `within`.
