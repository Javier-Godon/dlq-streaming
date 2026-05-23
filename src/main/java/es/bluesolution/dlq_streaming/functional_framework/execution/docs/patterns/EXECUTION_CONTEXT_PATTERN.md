# Execution Context & Functional Transaction Management

## Overview

This document explains the ExecutionContext pattern - a FP-aligned approach to transaction management that separates pure business logic from infrastructure concerns. Inspired by Haskell, F#, Elixir, and Erlang.

**TL;DR:** Remove `@Transactional`, inject `ExecutionContext`, call `.within(txContext)` at the end of your pipeline.

---

## Core Principle

```
Pure Functions (Stages)    → describe WHAT should happen → return Result<T>
ExecutionContext          → describe HOW it happens → wraps execution
Infrastructure (Spring)   → where effects actually occur → DB, transactions
```

**Never mix these layers.** This is what Haskell, F#, Elixir all do.

---

## Quick Start (5 minutes)

### Step 1: Inject ExecutionContext
```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final OrderRepository repository;
    private final TransactionExecutionContext txContext;  // ← Add this
}
```

### Step 2: Apply to Pipeline
```java
public Result<Order> handle(CreateOrderCommand cmd) {
    return CreateOrderAggregator.initialize(repository, cmd)
        .flatMap(CreateOrderStages::validate)
        .flatMap(CreateOrderStages::persist)
        .within(txContext);  // ← Add this one line
}
```

### Step 3: Write Pure Stages
```java
public final class CreateOrderStages {
    public static Result<Order> validate(CreateOrderState state) {
        // Just logic, no @Transactional
        return Result.success(...);
    }

    public static Result<Order> persist(CreateOrderState state) {
        // DB call wrapped in Result
        var saved = state.repository().save(...);
        return Result.success(saved);
    }
}
```

Done! That's the pattern.

---

## Architecture

### 4-Layer Model

```
┌─────────────────────────────────────┐
│ Handler                             │
│ .flatMap(Stage::validate)           │
│ .flatMap(Stage::persist)            │
│ .within(txContext) ← HERE            │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│ ExecutionContext (Effect Boundary)  │
│ • TransactionExecutionContext       │
│ • LoggingExecutionContext           │
│ • NoOpExecutionContext (testing)    │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│ Pure Stages (No annotations)        │
│ • Just business logic               │
│ • All return Result<T>              │
│ • Deterministic                     │
└─────────────────────────────────────┘
              ↓
┌─────────────────────────────────────┐
│ Spring/Infrastructure               │
│ • TransactionTemplate               │
│ • Database                          │
│ • Event publishing                  │
└─────────────────────────────────────┘
```

---

## Components

### ExecutionContext Interface
Describes HOW to execute a Result pipeline.

```java
public interface ExecutionContext {
    <T> Result<T> execute(Supplier<Result<T>> computation);
}
```

### TransactionExecutionContext
Spring-backed transaction executor.

```java
// Basic: read-write transaction
var ctx = TransactionExecutionContext.of(txManager);

// Read-only: optimized for queries
var ctx = TransactionExecutionContext.readOnly(txManager);

// High isolation: for critical operations
var ctx = TransactionExecutionContext.withIsolation(
    txManager,
    Connection.TRANSACTION_SERIALIZABLE
);
```

**Behavior:**
- Executes computation within Spring's TransactionTemplate
- Automatically rolls back if Result is a Failure
- Preserves functional semantics (errors as values, not exceptions)

### NoOpExecutionContext
No transaction wrapper - for testing and dry-runs.

```java
var noOpCtx = new NoOpExecutionContext();
var result = pipeline.within(noOpCtx);  // No transaction
```

### LoggingExecutionContext
Adds structured logging to any context.

```java
var txCtx = TransactionExecutionContext.of(txManager);
var loggedCtx = new LoggingExecutionContext(txCtx);

Result<Order> result = pipeline.within(loggedCtx);
// [DEBUG] Starting execution...
// [INFO] Execution completed successfully in 45ms
```

### ComposableExecutionContext
Base class for composing contexts (extend for custom behavior).

```java
public abstract class ComposableExecutionContext implements ExecutionContext {
    protected final ExecutionContext next;
    protected abstract <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation);
}
```

### Result#within()
Bridge method that applies an ExecutionContext to a Result pipeline.

```java
Result<T> within(ExecutionContext executionContext)
```

---

## Configuration

Create beans in your Spring configuration:

```java
@Configuration
@RequiredArgsConstructor
public class ExecutionContextConfiguration {

    private final PlatformTransactionManager txManager;

    // Default transaction context
    @Bean
    public TransactionExecutionContext transactionExecutionContext() {
        return TransactionExecutionContext.of(txManager);
    }

    // For testing without transactions
    @Bean
    public NoOpExecutionContext noOpExecutionContext() {
        return new NoOpExecutionContext();
    }

    // Transaction + logging
    @Bean("auditedContext")
    public ExecutionContext auditedTransactionContext() {
        var txCtx = TransactionExecutionContext.of(txManager);
        return new LoggingExecutionContext(txCtx);
    }

    // Read-only optimization
    @Bean("readOnlyContext")
    public ExecutionContext readOnlyContext() {
        return TransactionExecutionContext.readOnly(txManager);
    }
}
```

---

## Usage Patterns

### Pattern 1: Standard CRUD Handler

```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final OrderRepository repository;
    private final TransactionExecutionContext txContext;

    public Result<Order> handle(CreateOrderCommand cmd) {
        return CreateOrderAggregator.initialize(repository, cmd)
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::persist)
            .within(txContext);  // ← Transaction here
    }
}
```

### Pattern 2: Read-Only Handler

```java
@Service @RequiredArgsConstructor
public class GetOrderHandler {
    @Qualifier("readOnlyContext")
    private final ExecutionContext readOnlyCtx;

    public Result<Order> handle(GetOrderQuery query) {
        return GetOrderStages.fetch(query)
            .within(readOnlyCtx);  // ← Optimized read
    }
}
```

### Pattern 3: Conditional Transactions

```java
@Service @RequiredArgsConstructor
public class ImportOrdersHandler {
    private final TransactionExecutionContext txContext;
    private final NoOpExecutionContext noOpContext;

    public Result<List<Order>> handle(ImportCmd cmd, boolean isDryRun) {
        var context = isDryRun ? noOpContext : txContext;

        return importPipeline(cmd)
            .within(context);  // ← Conditional
    }
}
```

### Pattern 4: Audited Execution

```java
@Service @RequiredArgsConstructor
public class ProcessPaymentHandler {
    @Qualifier("auditedContext")
    private final ExecutionContext auditedCtx;

    public Result<Payment> handle(PaymentCommand cmd) {
        return paymentPipeline(cmd)
            .within(auditedCtx);  // ← Logging + transaction
    }
}
```

---

## Testing

### Unit Test: Pure Stages (No Spring Context)

```java
class CreateOrderStagesTest {

    @Test
    void validateRejectsEmptyOrders() {
        var state = new CreateOrderState(...);

        var result = CreateOrderStages.validate(state);

        assertTrue(result.isFailure());
        assertEquals(VALIDATION_ERROR, result.failure().code());
    }
}
```

**Benefits:**
- No Spring startup time
- No database needed
- Can run hundreds in parallel
- Test pure logic independently

### Integration Test: With Spring & Database

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
class CreateOrderHandlerIntegrationTest {

    @Autowired private CreateOrderHandler handler;
    @Autowired private OrderRepository repository;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("test_db")
        .withUsername("test")
        .withPassword("test");

    @Test
    void handlePersistsOrderInTransaction() {
        var cmd = new CreateOrderCommand(...);

        var result = handler.handle(cmd);

        assertTrue(result.isSuccess());
        var saved = repository.findById(result.value().id());
        assertTrue(saved.isPresent());
    }
}
```

### Handler Unit Test (No Spring, Mocks)

```java
@Test
void handleValidationError() {
    var noOpCtx = new NoOpExecutionContext();
    var mockRepository = mock(OrderRepository.class);
    var handler = new CreateOrderHandler(mockRepository, noOpCtx);

    var result = handler.handle(invalidCommand());

    assertTrue(result.isFailure());
    assertEquals(VALIDATION_ERROR, result.failure().code());
}
```

---

## Migration from @Transactional

### Before (Mixed Concerns)
```java
@Service @Transactional
public class CreateOrderHandler {
    public void handle(CreateOrderCommand cmd) {
        // Transaction hidden in annotation
        // Business logic mixed with infrastructure
    }
}
```

### After (Separated Concerns)
```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final ExecutionContext txContext;

    public Result<Order> handle(CreateOrderCommand cmd) {
        // Pure pipeline
        return aggregator.start()
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::persist)
            .within(txContext);  // Transaction explicit
    }
}
```

**Per-handler checklist:**
- [ ] Remove `@Transactional`
- [ ] Inject `ExecutionContext`
- [ ] Return `Result<T>` instead of `void`
- [ ] Add `.within(txContext)` at end
- [ ] Update tests
- [ ] Compile: `mvn clean compile -q`
- [ ] Test: `mvn test -Pinclude-integration-tests`

---

## FP Language Alignment

### Haskell
```haskell
validate :: Order -> Either Error Order
persist :: Order -> IO (Either Error Order)

program = do
  order1 <- validate order
  order2 <- persist order1
  return order2

runIO program  -- ← Effect runner (our ExecutionContext)
```

### F#
```fsharp
result {
  let! o1 = validate order
  let! o2 = persist o1
  return o2
} |> transaction  -- ← Execution boundary (our .within())
```

### Java (with ExecutionContext)
```java
aggregator.start()
  .flatMap(CreateOrderStages::validate)
  .flatMap(CreateOrderStages::persist)
  .within(txContext);  // ← Effect boundary
```

---

## Common Patterns Reference

| Need | Solution |
|------|----------|
| Normal CRUD | `TransactionExecutionContext.of(txManager)` |
| Testing | `NoOpExecutionContext` |
| Read-only optimization | `TransactionExecutionContext.readOnly(txManager)` |
| High isolation (SERIALIZABLE) | `TransactionExecutionContext.withIsolation(txManager, TRANSACTION_SERIALIZABLE)` |
| Logging + transaction | `new LoggingExecutionContext(txContext)` |
| Custom behavior | Extend `ComposableExecutionContext` |

---

## Key Design Principles

1. **Pure functions are testable** - No @Transactional annotations anywhere on business logic
2. **Transactions are infrastructure** - Applied at boundaries, not inside stages
3. **Effects are explicit** - `.within()` makes transaction boundaries visible
4. **Composition is powerful** - Easy to add logging, caching, metrics
5. **Functional semantics** - Errors are Result values, not exceptions

---

## Benefits

✅ **Pure Functions** - Easy to test, reason about, reuse
✅ **Explicit** - Transaction boundaries visible in code
✅ **Testable** - NoOp context for unit tests
✅ **Composable** - Add logging, caching, metrics easily
✅ **Functional** - Aligns with Haskell, F#, Elixir
✅ **Maintainable** - Clear separation of concerns
✅ **Observable** - Execution flow is transparent

---

## Implementation Files

| File | Purpose |
|------|---------|
| `framework/functional_framework/execution/ExecutionContext.java` | Core interface |
| `framework/functional_framework/execution/TransactionExecutionContext.java` | Spring transactions |
| `framework/functional_framework/execution/NoOpExecutionContext.java` | Testing |
| `framework/functional_framework/execution/ComposableExecutionContext.java` | Composition base |
| `framework/functional_framework/execution/LoggingExecutionContext.java` | Logging wrapper |
| `framework/Result.java` | The `.within()` method |
| `config/ExecutionContextConfiguration.java` | Spring beans |

---

## See Also

- Your framework's copilot-instructions.md for coding standards
- RAILWAY_ORIENTED_PROGRAMMING_COMPREHENSIVE_GUIDE.md for overall ROP patterns
- Test examples in `src/test/java/` for implementation patterns
