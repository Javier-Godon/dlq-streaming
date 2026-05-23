# 03: Theoretical Foundation (Algebraic Effects)

> **For the curious**: Where does this design come from? What's the theory?

---

## The Big Idea (5-Minute Summary)

You're implementing **algebraic effects and handlers** — a formal FP concept — but in idiomatic Java.

**The problem algebraic effects solve**:

In functional programming, side effects are problematic:

- ❌ I/O can't be pure
- ❌ State mutations aren't composable
- ❌ Exceptions break type safety

**The FP solution**: Separate the **description** of effects from their **execution**.

**Your implementation**: That's exactly what `within` does.

```
Within framework:

  Stages (pure)
    ↓
  Describe transformations (flatMap)
    ↓
  Pass to execution context (within)
    ↓
  Context executes side effects (transactionContext, etc.)
```

---

## Background: Effect Systems in FP Languages

### Haskell (The Classic Example)

Haskell forces separation of pure and effectful code:

```haskell
-- Pure (no side effects)
addNumbers :: Int -> Int -> Int
addNumbers a b = a + b

-- Effectful (wrapped in IO monad)
printAndAdd :: Int -> Int -> IO Int
printAndAdd a b = do
    putStrLn ("Adding " ++ show a ++ " and " ++ show b)
    return (a + b)
```

**Key**: The type system enforces separation.

- `addNumbers` is pure (no `IO`)
- `printAndAdd` is effectful (has `IO`)
- Effects are explicit in the type signature

### How Effects Are Executed

```haskell
-- Pure description
computation = do
    x <- readFile "data.txt"
    let y = process x
    writeFile "output.txt" y
    return y

-- Execution happens only at the top level
main :: IO ()
main = computation  -- Effects run here
```

Everything above is a **description**. Effects only happen when `main` runs.

### The Monadic Pattern

Haskell uses **monads** to thread effects through pure code:

```haskell
readFile "data.txt"
  >>= (\x -> process x)
  >>= (\y -> writeFile "output.txt" y)
  >>= (\z -> return z)
```

Translation to your code:

```java
// Haskell monad operation (>>=) = your flatMap
Result.success(file)
    .flatMap(FileStages::read)
    .flatMap(FileStages::process)
    .flatMap(FileStages::write)
```

**Both** separate description from execution.

---

## Algebraic Effects & Handlers (The Formal Model)

### What Are Algebraic Effects?

**Algebraic effects** formally represent side effects as **operations** (not exceptions).

Instead of:

```java
throw new DatabaseException("Connection failed");
```

With algebraic effects, you describe:

```
I need to perform a database operation.
Whoever is running this computation will handle it.
```

### Effect Handlers (Interpreters)

**Handlers** execute those operations:

```
Computation: "Perform operation X"
Handler:     "I know how to do X. Here's the result."
```

Example:

```
Computation: "Write to database"
Handler:     "I'll use TransactionTemplate to execute this"
```

### Formal Definition

**Effect** = An operation that needs interpretation
**Handler** = An interpreter that executes effects

```
Computation + Handler = Execution
```

---

## How Your Framework Maps to Algebraic Effects

### Layer 1: Pure Computation (Effect-Free)

```java
static Result<Order, Error> validate(Order order) {
    // No effects: just data transformation
    if (order.items().isEmpty()) {
        return Result.failure("Empty order");
    }
    return Result.success(order);
}
```

This is **pure** in the algebraic effects sense:

- No operations that need interpretation
- Completely deterministic
- Can be executed anywhere

### Layer 2: Effectful Computation (Described, Not Executed)

```java
static Result<Order, Error> persist(Order order) {
    // This DESCRIBES a persistence effect
    // It doesn't actually do anything yet
    return repository.save(order);
}
```

The computation **describes** a database operation, but:

- The actual database call hasn't happened
- The repository method returns a `Result` (description)
- Execution is deferred

### Layer 3: Handler (Execution Context)

```java
public class TransactionExecutionContext {
    public <T, E> Result<T, E> execute(
        Supplier<Result<T, E>> computation
    ) {
        // This is a HANDLER
        // It interprets the computation
        return template.execute(status -> computation.get());
    }
}
```

When you call:

```java
pipeline.within(txContext::execute);
```

You're passing:

1. **Computation**: The pipeline (a description of effects)
2. **Handler**: The context (knows how to execute those effects)

The handler:

- Runs inside a transaction
- Interprets the persist operation
- Returns the result

---

## Why Separate Description from Execution?

### Problem: Tangled Concerns

```java
// ❌ Description and execution mixed
public Result<Order, Error> persist(Order order) {
    try {
        database.beginTransaction();
        Order saved = database.save(order);
        database.commit();
        return Result.success(saved);
    } catch (Exception e) {
        database.rollback();
        return Result.failure("Save failed");
    }
}
```

**Issues**:

- Stage knows about transactions
- Can't reuse stage without transaction
- Hard to test (mocking database)
- Can't apply different execution strategies

### Solution: Separate Concerns

```java
// ✅ Pure description
public static Result<Order, Error> persist(Order order) {
    return repository.save(order);
}

// ✅ Execution handled by context
pipeline
    .flatMap(persist)
    .within(transactionContext);
```

**Benefits**:

- Stage is purely functional
- Can reuse in different contexts
- Easy to test (mock repository returns Result)
- Can apply multiple handlers

---

## The Monad Pattern (Your Implementation)

Your framework uses **monads** (similar to Haskell):

```java
// Monadic structure:
Result<Order, Error> computation =
    Result.of(order)                    // lift value into Result
        .flatMap(validate)              // monadic bind (>>=)
        .flatMap(persist)               // monadic bind (>>=)
        .flatMap(publishEvent);         // monadic bind (>>=)
```

**What makes this monadic**:

1. **Associativity**: Chain operations without nesting
   ```java
   a.flatMap(b).flatMap(c)  ==  a.flatMap(x -> b.flatMap(c))
   ```

2. **Identity**: Wrapping and unwrapping neutral
   ```java
   Result.success(x).flatMap(f)  ==  f.apply(x)
   ```

3. **Composition**: Operations can be composed
   ```java
   a.flatMap(b).flatMap(c)  ==  a.flatMap(x -> b(x).flatMap(c))
   ```

This is exactly how Haskell monads work, adapted for Java.

---

## Free Monad Pattern (Advanced)

Your framework is also a **free monad** pattern:

**Free monad** = A monad that represents any computation without executing it.

```
Your pipeline:
  Result.of(order)
    .flatMap(validate)      <- Describe
    .flatMap(persist)       <- Describe

  This builds an AST-like structure:

    AndThen(
      validate,
      AndThen(
        persist,
        Return(order)
      )
    )
```

When you call `within`, you're **interpreting** that AST with a handler.

---

## Effect Handler Patterns

### Pattern 1: Single Effect Type

One kind of effect (e.g., database):

```java
static <T> Result<T, Error> persist(T value) {
    return repository.save(value);  // Database effect
}

// Handler
pipeline
    .flatMap(persist)
    .within(transactionContext);    // Interprets database effect
```

### Pattern 2: Multiple Effect Types

Different effects in one pipeline:

```java
// Database effect
static Result<Order, Error> persistOrder(Order o) {
    return repository.save(o);
}

// API effect
static Result<Order, Error> notifyKeycloak(Order o) {
    return keycloakService.updateUser(o);
}

// File effect
static Result<Order, Error> logEvent(Order o) {
    return fileSystem.write(o);
}

// Handlers (executed in sequence)
pipeline
    .flatMap(persistOrder)
    .flatMap(notifyKeycloak)
    .within(sagaContext)            // Handles DB + API together

    .flatMap(logEvent)
    .within(fileSystemContext);     // Handles file I/O
```

Each handler interprets its effects.

### Pattern 3: Stacked Handlers

Multiple handlers on top of each other:

```java
// All stages
result
    .flatMap(validate)
    .flatMap(persist)
    .flatMap(publishEvent)

    // Apply first handler (database)
    .within(databaseContext)

    // Apply second handler (logging)
    .within(loggingContext)

    // Apply third handler (tracing)
    .within(tracingContext);
```

Execution order:

1. Tracing handler wraps logging handler
2. Logging handler wraps database handler
3. Database handler executes the stages
4. Results bubble up through all handlers

---

## The Separation Principle (Core)

> **Effects should be describable without being executable.**

Your framework enforces this:

```
                Describable?    Executable?
Pure stages     ✅ Yes          ❌ No (just transformations)
Stages calling
  repository    ✅ Yes          ❌ No (returns Result, not data)
within(context) ✅ Yes          ✅ Yes (actually runs)
```

This principle enables:

- **Composability**: Any stage works with any context
- **Testability**: Test description without execution
- **Reusability**: Use stages in different pipelines
- **Clarity**: Effects are explicit

---

## Comparison with Other Patterns

| Pattern | Description | Execution | Example |
|---------|-------------|-----------|---------|
| **Algebraic Effects** | Formal FP model | Handler interprets | Your framework |
| **Free Monad** | AST-like structure | Interpreter walks tree | Data structure pipeline |
| **Monad (Traditional)** | Sequencing context | `flatMap` chains | Haskell do-notation |
| **Exceptions** | Implicit control flow | Caught at boundary | Java try-catch |
| **Callbacks** | Nested execution | Hell of callbacks | Node.js callbacks |

Your framework combines the best of:

- **Formal rigor** (algebraic effects)
- **Java idiomaticity** (interfaces and composition)
- **Monad benefits** (composability)
- **Simplicity** (no macros or advanced FP)

---

## Why This Matters for Your Team

1. **You're not inventing something new**: This is formalized in academic FP
2. **It's proven**: Haskell uses this pattern for 30+ years
3. **It's compositional**: Any context works with any stage
4. **It's testable**: Pure and effectful are separated
5. **It's thread-safe**: ThreadLocal isolation per request

---

## Further Reading (If Curious)

| Resource | Topic | Difficulty |
|----------|-------|------------|
| "Programming with Algebraic Effects and Handlers" (Bauer & Pretnar) | Formal foundations | Advanced |
| "An Introduction to Algebraic Effects and Handlers" (Pretnar) | Tutorial | Intermediate |
| Haskell documentation | Monad pattern | Intermediate |
| Your own code | Practical application | Easy |

---

## TL;DR

1. You're implementing **algebraic effects** (formal FP concept)
2. **Effects** = operations that need interpretation (persist, publish, etc.)
3. **Handlers** = execution contexts that interpret effects (txContext, outboxContext, etc.)
4. **Separation** = Describe in stages, execute in handlers
5. **Benefit** = Composable, testable, reusable pure functions

---

**Ready for practical patterns? Go to `patterns/` folder.**

**Ready for team rules? Go to `TEAM_RULES_AND_BEST_PRACTICES.md`.**
