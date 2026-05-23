# ExecutionContext Architecture - Complete Developer Guide

## Table of Contents

1. [Conceptual Foundations](#conceptual-foundations)
2. [The Problem We're Solving](#the-problem-were-solving)
3. [Core Principles](#core-principles)
4. [Understanding Each Component](#understanding-each-component)
5. [How Components Work Together](#how-components-work-together)
6. [Implementation Walkthrough](#implementation-walkthrough)
7. [Real-World Scenarios](#real-world-scenarios)
8. [Testing Strategies](#testing-strategies)
9. [Advanced Patterns](#advanced-patterns)
10. [Anti-Patterns to Avoid](#anti-patterns-to-avoid)

---

## Conceptual Foundations

### The Traditional Problem in Spring Applications

When you write Spring applications with `@Transactional`, your code mixes two separate concerns:

1. **Business Logic Concern** - "What should happen?"
   - Validate an order
   - Check inventory
   - Calculate total

2. **Infrastructure Concern** - "How should it be executed?"
   - Start a database transaction
   - Rollback on error
   - Commit on success

Example of mixed concerns:
```java
@Service @Transactional
public class CreateOrderHandler {
    public void handle(CreateOrderCommand cmd) {
        // Business logic (validation, calculation)
        if (cmd.quantity() <= 0) throw new ValidationException();

        // Infrastructure (hidden in @Transactional)
        order.save();  // ← Transaction started by annotation
    }
    // ← Transaction commits here automatically
}
```

**Problems with this approach:**
- ❌ Transaction boundary is hidden (where does it start? when does it end?)
- ❌ Hard to test without full Spring context
- ❌ Can't change transaction behavior without changing code
- ❌ Mixes two unrelated concerns in one place
- ❌ Violates Single Responsibility Principle

### The Functional Programming Solution

Functional languages like Haskell, F#, and Elixir solved this by **separating description from execution**:

**Haskell example:**
```haskell
-- This describes WHAT should happen
validate :: Order -> Either Error Order
persist :: Order -> IO (Either Error Order)

-- This describes HOW to execute it
program = do
  validated <- validate order
  result <- persist validated
  return result

-- This is WHERE execution happens
runIO program  -- ← Execution boundary
```

**Key insight:** The business logic (validation, persistence) is described independently from how it's executed (with or without transactions, with logging, etc.).

### The Railway-Oriented Programming (ROP) Pattern

Your framework uses Result<T> to implement ROP:

```java
// Pure function that describes WHAT
public static Result<Order> validate(Order order) {
    if (order.quantity() <= 0) {
        return Result.failure(VALIDATION_ERROR, "Quantity must be > 0", null);
    }
    return Result.success(order);
}

// Another pure function
public static Result<Order> persist(Order order) {
    var saved = repository.save(order);
    return Result.success(saved);
}

// The pipeline describes the flow
var pipeline = aggregator.start()
    .flatMap(CreateOrderStages::validate)
    .flatMap(CreateOrderStages::persist);
```

**Key benefit:** The pipeline returns `Result<T>`, not void. It describes what happens without executing anything yet.

---

## The Problem We're Solving

### Current Challenge: How to Execute Pipelines with Transactions?

You have a pure pipeline that returns `Result<T>`:

```java
Result<Order> pipeline = aggregator.start()
    .flatMap(CreateOrderStages::validate)
    .flatMap(CreateOrderStages::persist);
```

But you need to execute it **within a database transaction**. How do you:
1. Add transaction management without adding `@Transactional` to the pipeline?
2. Make the transaction boundary explicit and visible?
3. Make it easy to test without Spring?
4. Make it composable (easy to add logging, caching, etc.)?

### The Solution: ExecutionContext

ExecutionContext solves this by:
- **Describing HOW to execute** (with transactions, logging, etc.)
- **Keeping pipelines pure** (no annotations on business logic)
- **Making boundaries explicit** (visible `.within()` call)
- **Enabling composition** (layer multiple contexts)

```java
// Same pure pipeline
Result<Order> pipeline = aggregator.start()
    .flatMap(CreateOrderStages::validate)
    .flatMap(CreateOrderStages::persist);

// Add execution context at the boundary
Result<Order> result = pipeline.within(txContext);  // ← HERE
```

---

## Core Principles

### Principle 1: Separation of Concerns

**Business Logic** should know nothing about transactions:
```java
public static Result<Order> validate(Order order) {
    // No mention of transactions
    // No Spring dependencies
    // Pure function
    return order.quantity() > 0
        ? Result.success(order)
        : Result.failure(VALIDATION_ERROR, "Invalid quantity", null);
}
```

**Execution Context** handles transactions:
```java
// In configuration, somewhere else
var txContext = TransactionExecutionContext.of(txManager);

// In handler, apply it
pipeline.within(txContext);
```

### Principle 2: Description vs. Execution

**Description (Pure):**
```java
// Returns Result<T>, but doesn't execute anything
Result<Order> pipeline = aggregator.start()
    .flatMap(Stages::validate)
    .flatMap(Stages::persist);
```

**Execution (With Side Effects):**
```java
// NOW things actually happen (DB calls, transactions)
Result<Order> result = pipeline.within(txContext);
```

This mirrors functional languages:
- **Haskell:** IO monad describes effects, runIO executes them
- **F#:** Computation expressions describe effects, `|>` runs them
- **Your framework:** Result<T> describes effects, `.within()` executes them

### Principle 3: Composability

You can compose contexts to add behavior:

```java
// Start with transaction context
var txContext = TransactionExecutionContext.of(txManager);

// Wrap with logging
var loggedContext = new LoggingExecutionContext(txContext);

// Execute with both
pipeline.within(loggedContext);
// Result: logging + transaction + business logic
```

This is like Unix pipes:
```bash
cat data | grep pattern | sort | uniq
```

Each component does one thing; they compose.

### Principle 4: Testing

Because logic and execution are separated, you can test differently:

```java
// Test pure logic (no Spring, no DB)
var result = CreateOrderStages.validate(order);

// Test handler logic (with no-op context)
var noOpCtx = new NoOpExecutionContext();
var result = pipeline.within(noOpCtx);

// Test full integration (with real context)
var txCtx = TransactionExecutionContext.of(txManager);
var result = pipeline.within(txCtx);
```

---

## Understanding Each Component

### 1. ExecutionContext Interface

**Location:** `framework/functional_framework/execution/ExecutionContext.java`

**Purpose:** Define the contract for "how to execute" a Result pipeline.

**Code:**
```java
public interface ExecutionContext {
    /**
     * Execute a Result-returning computation within this context.
     *
     * @param computation a pure function that returns a Result
     * @param <T> the success value type
     * @return the Result exactly as returned by computation
     */
    <T> Result<T> execute(Supplier<Result<T>> computation);
}
```

**What it means:**
- `Supplier<Result<T>>` = "A computation that will produce a Result when called"
- The context decides when and how to call it
- The context might wrap it with transactions, logging, caching, etc.

**Example - What's happening under the hood:**

```java
// This is what you write:
pipeline.within(txContext);

// This is what happens:
txContext.execute(() -> pipeline);  // ← Supplier is created
// Inside execute():
// 1. Start transaction
// 2. Call () -> pipeline  (the Supplier)
// 3. If Result is Failure, mark for rollback
// 4. Commit or rollback
// 5. Return Result
```

**Why use Supplier instead of just calling it?**
- Defers execution (transaction started at the right time)
- The context controls when/how it runs
- Enables wrapping with side effects (logging before/after)

### 2. TransactionExecutionContext

**Location:** `framework/functional_framework/execution/TransactionExecutionContext.java`

**Purpose:** Execute a Result pipeline within a Spring database transaction.

**Three Factory Methods:**

#### Basic Transaction
```java
var ctx = TransactionExecutionContext.of(txManager);
```

What happens:
1. Takes your `PlatformTransactionManager` (Spring's transaction manager)
2. Creates a `TransactionTemplate` configured for read-write transactions
3. When you call `.execute()`, it uses `transactionTemplate.execute()`

**Code flow:**
```java
// In TransactionExecutionContext.execute()
return transactionTemplate.execute(txStatus -> {
    Result<T> result = computation.get();  // Run the pipeline

    if (result.isFailure()) {
        txStatus.setRollbackOnly();  // Error = rollback
    }

    return result;  // Return the Result (success or failure)
});
```

**Key behavior:** If the Result is a Failure, the transaction automatically rolls back. This preserves functional semantics—errors are values, not exceptions.

#### Read-Only Transaction
```java
var ctx = TransactionExecutionContext.readOnly(txManager);
```

What happens:
1. Creates a `TransactionTemplate` with `readOnly = true`
2. Database optimizes queries (no locks, faster)
3. Prevents accidental writes

**When to use:**
- Query-only operations (GetOrder, ListProducts)
- Better performance for read-heavy operations

#### Custom Isolation Level
```java
var ctx = TransactionExecutionContext.withIsolation(
    txManager,
    Connection.TRANSACTION_SERIALIZABLE
);
```

What happens:
1. Creates a transaction with specified isolation level
2. SERIALIZABLE = highest isolation (slowest)
3. READ_UNCOMMITTED = lowest isolation (fastest, allows dirty reads)

**Isolation Levels (from weakest to strongest):**
- READ_UNCOMMITTED - "Dirty reads allowed" (risky)
- READ_COMMITTED - "Default, prevents dirty reads"
- REPEATABLE_READ - "Prevents dirty reads and non-repeatable reads"
- SERIALIZABLE - "Complete isolation, like sequential execution" (slow but safe)

**When to use:**
- Normal operations: default (READ_COMMITTED)
- Critical operations (payments, stock): SERIALIZABLE
- Read-only queries: read-only context

### 3. NoOpExecutionContext

**Location:** `framework/functional_framework/execution/NoOpExecutionContext.java`

**Purpose:** Execute without any transaction wrapper.

**Code:**
```java
public class NoOpExecutionContext implements ExecutionContext {
    @Override
    public <T> Result<T> execute(Supplier<Result<T>> computation) {
        return computation.get();  // Just call it, no wrapping
    }
}
```

**That's it—just execute the computation as-is.**

**When to use:**
- Unit tests (no Spring, no DB)
- Dry-run scenarios (execute logic without DB side effects)
- Testing pure stages independently

**Example - Unit test:**
```java
@Test
void validateRejectsEmptyOrders() {
    var noOpCtx = new NoOpExecutionContext();

    var result = CreateOrderStages.validate(invalidOrder)
        .within(noOpCtx);  // No transaction

    assertTrue(result.isFailure());
}
```

### 4. ComposableExecutionContext

**Location:** `framework/functional_framework/execution/ComposableExecutionContext.java`

**Purpose:** Base class for building context wrappers that compose.

**Code:**
```java
@RequiredArgsConstructor
public abstract class ComposableExecutionContext implements ExecutionContext {

    // The next context in the chain
    protected final ExecutionContext next;

    // Template method: subclasses override this
    protected abstract <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation);

    // Final method: calls the template method
    @Override
    public final <T> Result<T> execute(Supplier<Result<T>> computation) {
        return executeWithBehavior(computation);
    }
}
```

**Pattern: Template Method**

This uses the Template Method design pattern:
1. Subclasses override `executeWithBehavior()`
2. Can call `next.execute()` to delegate to next context
3. Can wrap behavior around it

**How composition works:**

```java
// Build a chain: Logging → Transaction → Computation
var txCtx = TransactionExecutionContext.of(txManager);
var loggedCtx = new LoggingExecutionContext(txCtx);

// When you call execute:
loggedCtx.execute(() -> pipeline);

// Execution flow:
// LoggingExecutionContext.executeWithBehavior()
//   ↓ logs "Starting..."
//   ↓ next.execute() → calls txCtx
//     TransactionExecutionContext.execute()
//       ↓ startTransaction()
//       ↓ computation.get() → pipeline
//       ↓ if failure: setRollbackOnly()
//   ↓ logs "Completed in 45ms"
```

**Why this pattern?**
- Each context adds one behavior
- Contexts are composed/stacked
- Easy to add new contexts (caching, metrics, etc.)

### 5. LoggingExecutionContext

**Location:** `framework/functional_framework/execution/LoggingExecutionContext.java`

**Purpose:** Add structured logging to any ExecutionContext.

**Code:**
```java
@Slf4j
public final class LoggingExecutionContext extends ComposableExecutionContext {

    @Override
    protected <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation) {
        long startTime = System.nanoTime();
        log.debug("Starting execution...");

        Result<T> result = next.execute(computation);  // ← Delegate

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        if (result.isSuccess()) {
            log.info("Execution completed successfully in {}ms", durationMs);
        } else {
            log.warn("Execution failed after {}ms: {}",
                durationMs, result.failure().message());
        }

        return result;
    }
}
```

**What it does:**
1. Records start time
2. Logs "Starting..."
3. Calls next context (which might be TransactionExecutionContext)
4. Records duration
5. Logs success/failure with timing

**Output example:**
```
[DEBUG] Starting execution...
[DEBUG] Starting execution within TransactionExecutionContext
[INFO] Execution completed successfully in 42ms
```

**Use case:**
```java
// For critical operations that need auditing
@Bean("auditedContext")
public ExecutionContext auditedTransactionContext() {
    var txCtx = TransactionExecutionContext.of(txManager);
    return new LoggingExecutionContext(txCtx);
}
```

### 6. Result#within() Method

**Location:** `framework/Result.java` (lines ~185-245)

**Purpose:** Bridge between pure pipelines and execution contexts.

**Code:**
```java
default Result<S> within(ExecutionContext executionContext) {
    return executionContext.execute(() -> this);
}
```

**That's it—just two lines!**

**What it does:**
1. Takes an ExecutionContext
2. Calls `executionContext.execute()` with the current Result as a Supplier
3. Returns the result of execution

**Example - Execution flow:**

```java
// You write:
aggregator.start()
    .flatMap(Stages::validate)
    .flatMap(Stages::persist)
    .within(txContext);

// What happens:
// 1. aggregator.start() → Result<State1>
// 2. .flatMap(Stages::validate) → Result<State2>
// 3. .flatMap(Stages::persist) → Result<State3>
// 4. .within(txContext) → Result<State3>
//
// Step 4 calls:
//   txContext.execute(() -> Result<State3>)
//
// Which:
//   - Starts transaction
//   - Returns Result<State3>
//   - Commits/rolls back
```

**Why `Supplier<Result<T>>`?**

Because `.within()` is called AFTER the pipeline completes:
```java
pipeline          // ← This is a Result<T> already
    .within()     // ← This applies the context

// It's like:
result.within(ctx)  // Apply context to existing result

// NOT:
within(ctx, pipeline)  // Create supplier first, then execute
```

### 7. ExecutionContextConfiguration

**Location:** `config/ExecutionContextConfiguration.java`

**Purpose:** Spring configuration that provides ExecutionContext beans.

**Pattern: Factory Beans**

```java
@Configuration @RequiredArgsConstructor
public class ExecutionContextConfiguration {

    private final PlatformTransactionManager txManager;

    @Bean
    public TransactionExecutionContext transactionExecutionContext() {
        return TransactionExecutionContext.of(txManager);
    }

    @Bean
    public NoOpExecutionContext noOpExecutionContext() {
        return new NoOpExecutionContext();
    }

    @Bean("readOnlyContext")
    public ExecutionContext readOnlyContext() {
        return TransactionExecutionContext.readOnly(txManager);
    }
}
```

**Why use beans?**
- Spring manages the lifecycle
- Easy to inject into handlers
- Can be easily swapped in tests
- Centralized configuration

**How to use:**
```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final TransactionExecutionContext txContext;  // ← Injected

    public Result<Order> handle(CreateOrderCommand cmd) {
        return pipeline.within(txContext);  // ← Use it
    }
}
```

---

## How Components Work Together

### The Complete Flow

Here's a complete request flow with ExecutionContext:

```
HTTP Request → Controller → Handler
                              ↓
                         Handler injects:
                         • OrderRepository
                         • TransactionExecutionContext
                              ↓
                         Handler.handle():
                         1. Create aggregator
                         2. Build pipeline (flatMap chain)
                         3. Call .within(txContext)
                              ↓
                         .within() calls:
                         txContext.execute(() -> pipeline)
                              ↓
                         TransactionExecutionContext.execute():
                         1. transactionTemplate.execute()
                         2. startTransaction()
                         3. computation.get() → pipeline
                              ↓
                         Pipeline executes:
                         Stages::validate → Result<State1>
                              ↓
                         .flatMap(Stages::validate)
                         Stages::enrich → Result<State2>
                              ↓
                         .flatMap(Stages::persist)
                         Stages::persist → Result<State3>
                         (DB INSERT happens here)
                              ↓
                         Back to TransactionExecutionContext:
                         4. Check if result.isFailure()
                         5. If failure: setRollbackOnly()
                         6. Commit or Rollback
                         7. Return Result<State3>
                              ↓
                         Handler returns Result<Order>
                              ↓
                         Controller converts to HTTP response
                              ↓
                         HTTP Response → Client
```

### Timing: When Does Each Thing Happen?

```java
// Step 1: Create pipeline (immediate, no DB)
Result<Order> pipeline = aggregator.start()
    .flatMap(Stages::validate)     // Pure function, no DB
    .flatMap(Stages::persist);     // Pure function, no DB
// → Result<T> is ready, nothing executed yet

// Step 2: Apply context (this triggers execution)
Result<Order> result = pipeline.within(txContext);
// ↓ Context starts transaction
// ↓ Pipeline actually executes (DB calls happen here)
// ↓ If failure, rollback
// ↓ Return result

// Step 3: Handle result
if (result.isSuccess()) {
    return ResponseEntity.ok(result.value());
} else {
    return ResponseEntity.badRequest().body(result.failure());
}
```

### Transaction Boundaries

```java
// Example: CreateOrderHandler

@Transactional            // ❌ DON'T use @Transactional
public class CreateOrderHandler {

    public Result<Order> handle(CreateOrderCommand cmd) {
        // Transaction NOT started yet

        var aggregator = CreateOrderAggregator.initialize(repo, cmd);

        Result<Order> pipeline = aggregator.start()
            .flatMap(CreateOrderStages::validate)  // No DB access yet
            .flatMap(CreateOrderStages::persist);  // No DB access yet

        // Transaction STARTS here
        Result<Order> result = pipeline.within(txContext);
        // Transaction ENDS here

        return result;
    }
}
```

---

## Implementation Walkthrough

### Building a Complete Handler from Scratch

Let's build `CreateOrderHandler` step by step.

#### Step 1: Define the Command (Input)

```java
package es.bluesolution.railway_framework.orders.usecases.create;

import lombok.Value;
import java.util.UUID;
import java.util.List;

@Value
public class CreateOrderCommand {
    UUID customerId;
    List<OrderLineItem> items;

    @Value
    public static class OrderLineItem {
        UUID productId;
        Integer quantity;
    }
}
```

**Responsibility:** Represent the user's request.

#### Step 2: Define the Result (Output)

```java
@Value
public class CreateOrderResult {
    Order order;
    LocalDateTime createdAt;
}
```

**Responsibility:** Represent the operation's outcome.

#### Step 3: Define the Aggregator (State Container)

```java
@Getter
@With  // Lombok: generates withField() methods
public class CreateOrderAggregator {
    private final OrderRepository repository;
    private final CatalogService catalogService;
    private final Order order;

    public static CreateOrderAggregator initialize(
            OrderRepository repository,
            CatalogService catalogService,
            CreateOrderCommand command) {

        var order = Order.create(command.customerId(), command.items());
        return new CreateOrderAggregator(repository, catalogService, order);
    }
}
```

**Responsibility:**
- Hold all state needed during the pipeline
- Provide immutable updates via `.withField()`

**How @With works:**
```java
// @With generates this:
public CreateOrderAggregator withOrder(Order newOrder) {
    return new CreateOrderAggregator(this.repository, this.catalogService, newOrder);
}
```

This allows:
```java
state.withOrder(updatedOrder)  // Returns new instance with updated order
```

#### Step 4: Define Pure Stages

```java
@Slf4j
public final class CreateOrderStages {

    private CreateOrderStages() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Stage 1: Validate the order
     *
     * Pure function:
     * - Input: CreateOrderAggregator state
     * - Output: Result<CreateOrderAggregator>
     * - No side effects
     * - No DB access
     * - No exceptions
     */
    public static Result<CreateOrderAggregator> validate(CreateOrderAggregator state) {
        var order = state.order();

        // Validation 1: Items not empty
        if (order.items().isEmpty()) {
            return Result.failure(
                FailureResultDescription.ErrorCode.VALIDATION_ERROR,
                "Order must have at least one item",
                null
            );
        }

        // Validation 2: Total amount positive
        if (order.totalAmount().isNegative()) {
            return Result.failure(
                FailureResultDescription.ErrorCode.VALIDATION_ERROR,
                "Total amount must be positive",
                null
            );
        }

        log.debug("Order validation passed");
        return Result.success(state);  // ← Return state unchanged
    }

    /**
     * Stage 2: Enrich with catalog data
     *
     * Calls external service but wraps result in Result<T>
     */
    public static Result<CreateOrderAggregator> enrich(CreateOrderAggregator state) {
        try {
            var catalogService = state.catalogService();
            var order = state.order();

            var enrichedItems = order.items().stream()
                .map(item -> {
                    var product = catalogService.findProduct(item.productId());
                    return item.withUnitPrice(product.price());
                })
                .toList();

            var enrichedOrder = order.withItems(enrichedItems);
            log.debug("Order enriched with catalog data");

            // ← Return state with updated order
            return Result.success(state.withOrder(enrichedOrder));

        } catch (Exception e) {
            log.error("Failed to enrich order", e);
            return Result.failure(
                FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Catalog service unavailable: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Stage 3: Persist to database
     *
     * This is where the DB call happens
     * Still wrapped in Result<T>
     * Still a pure function (from caller's perspective)
     */
    public static Result<CreateOrderAggregator> persist(CreateOrderAggregator state) {
        try {
            var repository = state.repository();
            var order = state.order();

            // ← DB call happens here
            var saved = repository.save(order);

            log.debug("Order persisted with ID: {}", saved.id());

            // ← Return state with persisted order
            return Result.success(state.withOrder(saved));

        } catch (Exception e) {
            log.error("Failed to persist order", e);
            return Result.failure(
                FailureResultDescription.ErrorCode.DATABASE_ERROR,
                "Failed to save order: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Stage 4: Publish domain event
     */
    public static Result<CreateOrderAggregator> publishEvent(CreateOrderAggregator state) {
        try {
            // Event publishing happens here (might be async)
            state.eventPublisher().publish(
                new OrderCreatedEvent(state.order())
            );

            log.info("Order created event published for ID: {}", state.order().id());
            return Result.success(state);

        } catch (Exception e) {
            // Publishing failure doesn't fail the order (outbox pattern)
            log.warn("Failed to publish event (non-fatal)", e);
            return Result.success(state);  // ← Ignore publishing errors
        }
    }
}
```

**Key characteristics of stages:**
- ✅ Static methods (no state)
- ✅ Return `Result<T>` (not void)
- ✅ No annotations (no @Transactional)
- ✅ All errors as Result.failure() (no exceptions thrown)
- ✅ Pure from caller's perspective

#### Step 5: Define the Handler

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateOrderHandler {

    // Dependencies
    private final OrderRepository orderRepository;
    private final CatalogService catalogService;
    private final EventPublisher eventPublisher;
    private final TransactionExecutionContext txContext;  // ← Key injection

    /**
     * Handle the create order command
     *
     * Returns Result<T> so errors propagate as values
     */
    public Result<CreateOrderResult> handle(CreateOrderCommand command) {

        // Step 1: Create aggregator (initialize state)
        var aggregator = CreateOrderAggregator.initialize(
            orderRepository,
            catalogService,
            eventPublisher,
            command
        );

        // Step 2: Build pure pipeline
        Result<CreateOrderAggregator> pipeline = Result.success(aggregator)
            .flatMap(CreateOrderStages::validate)      // Validation
            .flatMap(CreateOrderStages::enrich)        // Enrichment
            .flatMap(CreateOrderStages::persist)       // Persistence
            .flatMap(CreateOrderStages::publishEvent); // Event publishing

        // Step 3: Execute with transaction ← THE KEY PART
        Result<CreateOrderAggregator> result = pipeline
            .within(txContext);  // ← Transaction applied here

        // Step 4: Convert to result (map to response type)
        return result.map(finalState -> new CreateOrderResult(
            finalState.order(),
            LocalDateTime.now()
        ));
    }
}
```

**The pattern:**
1. Create state container
2. Build pipeline with stages
3. Apply `.within(txContext)`
4. Map to result type
5. Return Result<T>

#### Step 6: REST Controller

```java
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class CreateOrderController {

    private final CreateOrderHandler handler;

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request) {

        log.info("Creating order for customer: {}", request.customerId());

        // Convert request to command
        var command = request.toCommand();

        // Handle it
        var result = handler.handle(command);

        // Convert result to response
        return result.either(
            // Success path
            success -> ResponseEntity.ok(new CreateOrderResponse(
                success.order().id(),
                success.createdAt()
            )),

            // Failure path
            failure -> {
                log.warn("Order creation failed: {}", failure.message());
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse(failure.code(), failure.message()));
            }
        );
    }
}
```

**Controller responsibility:**
- Convert HTTP request to domain command
- Call handler
- Convert Result to HTTP response

---

## Real-World Scenarios

### Scenario 1: Standard CRUD Operation

**Goal:** Create an order with validation and persistence.

```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final OrderRepository repository;
    private final TransactionExecutionContext txContext;

    public Result<Order> handle(CreateOrderCommand cmd) {
        return CreateOrderAggregator.initialize(repository, cmd)
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::persist)
            .within(txContext);  // ← Read-write transaction
    }
}
```

**Configuration:**
```java
@Bean
public TransactionExecutionContext transactionExecutionContext() {
    return TransactionExecutionContext.of(txManager);
}
```

**Behavior:**
- Transaction starts when `.within()` is called
- Validation happens inside transaction
- Persistence happens inside transaction
- If validation fails: rollback
- If persistence fails: rollback

### Scenario 2: Conditional Transactions (Dry-Run)

**Goal:** Import orders with option to dry-run (no DB changes).

```java
@Service @RequiredArgsConstructor
public class ImportOrdersHandler {
    private final OrderRepository repository;
    private final TransactionExecutionContext txContext;
    private final NoOpExecutionContext noOpContext;

    public Result<List<Order>> handle(
            ImportOrdersCommand cmd,
            boolean isDryRun) {

        var context = isDryRun ? noOpContext : txContext;

        return importPipeline(cmd)
            .within(context);  // ← Conditional execution
    }

    private Result<List<Order>> importPipeline(ImportOrdersCommand cmd) {
        return Result.success(cmd.orders())
            .flatMap(ImportStages::validate)
            .flatMap(ImportStages::persist);
    }
}
```

**Behavior:**
- isDryRun=true: Stages execute without transaction (DB changes rollback naturally)
- isDryRun=false: Stages execute with transaction (DB changes persist)

### Scenario 3: Audited/Logged Operations

**Goal:** Critical operations (payments) with logging.

```java
@Service @RequiredArgsConstructor
public class ProcessPaymentHandler {
    private final PaymentRepository repository;
    @Qualifier("auditedContext")
    private final ExecutionContext auditedContext;

    public Result<Payment> handle(ProcessPaymentCommand cmd) {
        return paymentPipeline(cmd)
            .within(auditedContext);  // ← Logging + transaction
    }
}
```

**Configuration:**
```java
@Bean("auditedContext")
public ExecutionContext auditedTransactionContext() {
    var txCtx = TransactionExecutionContext.of(txManager);
    return new LoggingExecutionContext(txCtx);
}
```

**Output:**
```
[DEBUG] Starting execution...
[DEBUG] Starting execution within TransactionExecutionContext
[INFO] Execution completed successfully in 156ms
```

### Scenario 4: Read-Only Optimization

**Goal:** Query operation (no writes needed).

```java
@Service @RequiredArgsConstructor
public class GetOrderHandler {
    @Qualifier("readOnlyContext")
    private final ExecutionContext readOnlyContext;

    public Result<Order> handle(GetOrderQuery query) {
        return GetOrderStages.fetch(query)
            .within(readOnlyContext);  // ← Read-only transaction
    }
}
```

**Configuration:**
```java
@Bean("readOnlyContext")
public ExecutionContext readOnlyContext() {
    return TransactionExecutionContext.readOnly(txManager);
}
```

**Benefits:**
- Database knows it's read-only (fewer locks)
- Better performance
- Prevents accidental writes

### Scenario 5: Custom Isolation Level

**Goal:** Stock reservation (high concurrency, need isolation).

```java
@Service @RequiredArgsConstructor
public class ReserveStockHandler {
    @Qualifier("strictIsolationContext")
    private final ExecutionContext strictContext;

    public Result<Reservation> handle(ReserveStockCommand cmd) {
        return reservationPipeline(cmd)
            .within(strictContext);  // ← SERIALIZABLE isolation
    }
}
```

**Configuration:**
```java
@Bean("strictIsolationContext")
public ExecutionContext strictIsolationContext() {
    return TransactionExecutionContext.withIsolation(
        txManager,
        Connection.TRANSACTION_SERIALIZABLE
    );
}
```

**Behavior:**
- No other transaction can interfere
- Serializable execution (like sequential)
- Slower but perfectly isolated

---

## Testing Strategies

### Test 1: Pure Stage Unit Test

**Goal:** Test validation logic without Spring, without DB.

```java
@DisplayName("CreateOrderStages.validate")
class CreateOrderStagesValidateTest {

    @Test
    @DisplayName("should reject empty orders")
    void validateRejectsEmptyOrders() {
        // Arrange: Create a state with empty items
        var state = new CreateOrderAggregator(
            null,  // ← No repository needed
            null,  // ← No service needed
            Order.create(UUID.randomUUID(), List.of())  // ← Empty items
        );

        // Act: Call the pure stage
        var result = CreateOrderStages.validate(state);

        // Assert
        assertTrue(result.isFailure());
        assertEquals(VALIDATION_ERROR, result.failure().code());
        assertThat(result.failure().message())
            .contains("must have at least one item");
    }

    @Test
    @DisplayName("should reject negative amounts")
    void validateRejectsNegativeAmount() {
        var order = Order.create(
            UUID.randomUUID(),
            List.of(new OrderLineItem(UUID.randomUUID(), -5))  // ← Negative qty
        );
        var state = new CreateOrderAggregator(null, null, order);

        var result = CreateOrderStages.validate(state);

        assertTrue(result.isFailure());
    }

    @Test
    @DisplayName("should pass valid orders")
    void validatePassesValidOrders() {
        var order = Order.create(
            UUID.randomUUID(),
            List.of(new OrderLineItem(UUID.randomUUID(), 5))
        );
        var state = new CreateOrderAggregator(null, null, order);

        var result = CreateOrderStages.validate(state);

        assertTrue(result.isSuccess());
    }
}
```

**Characteristics:**
- ✅ No Spring bootstrap
- ✅ No database
- ✅ Tests pure logic
- ✅ Fast execution
- ✅ Can run hundreds in parallel

**Run time:** ~5ms per test

### Test 2: Handler Unit Test with Mocks

**Goal:** Test handler logic without Spring, with mocked dependencies.

```java
@DisplayName("CreateOrderHandler - Unit Tests")
class CreateOrderHandlerUnitTest {

    private CreateOrderHandler handler;
    private OrderRepository mockRepository;
    private CatalogService mockCatalog;

    @BeforeEach
    void setup() {
        mockRepository = mock(OrderRepository.class);
        mockCatalog = mock(CatalogService.class);

        var noOpCtx = new NoOpExecutionContext();
        handler = new CreateOrderHandler(
            mockRepository,
            mockCatalog,
            noOpCtx  // ← No-op, so no actual DB operations
        );
    }

    @Test
    @DisplayName("should fail if order is invalid")
    void handleRejectsInvalidOrder() {
        var cmd = new CreateOrderCommand(
            UUID.randomUUID(),
            List.of()  // ← Empty items (invalid)
        );

        var result = handler.handle(cmd);

        assertTrue(result.isFailure());
        assertEquals(VALIDATION_ERROR, result.failure().code());

        // Verify repository.save() was never called
        verify(mockRepository, never()).save(any());
    }

    @Test
    @DisplayName("should call repository.save() for valid orders")
    void handleCallsSaveForValidOrder() {
        // Arrange
        var order = Order.create(
            UUID.randomUUID(),
            List.of(new OrderLineItem(UUID.randomUUID(), 5))
        );
        var cmd = createValidCommand();

        when(mockRepository.save(any()))
            .thenReturn(order.withId(UUID.randomUUID()));

        // Act
        var result = handler.handle(cmd);

        // Assert
        assertTrue(result.isSuccess());
        verify(mockRepository, times(1)).save(any());
    }
}
```

**Characteristics:**
- ✅ No Spring context (faster)
- ✅ Uses mocks (no DB)
- ✅ Tests handler logic
- ✅ Can verify interactions (verify())

**Run time:** ~10ms per test

### Test 3: Integration Test with Spring & Database

**Goal:** Test complete flow with real Spring context and database.

```java
@SpringBootTest
@Testcontainers
@Tag("integration")
@DisplayName("CreateOrderHandler - Integration Tests")
class CreateOrderHandlerIntegrationTest {

    @Autowired
    private CreateOrderHandler handler;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private CatalogService catalogService;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("test_db")
        .withUsername("test")
        .withPassword("test");

    @Test
    @DisplayName("should persist order in database")
    void handlePersistsOrderInDatabase() {
        // Arrange: Create a valid command
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var cmd = new CreateOrderCommand(
            customerId,
            List.of(new CreateOrderCommand.OrderLineItem(productId, 5))
        );

        // Pre-populate catalog
        var product = new Product(productId, "Widget", 100.00);
        // ...save to DB

        // Act: Handle the command
        var result = handler.handle(cmd);

        // Assert: Verify success
        assertTrue(result.isSuccess());
        var order = result.value();

        // Verify persisted to database
        var savedOrder = repository.findById(order.id());
        assertTrue(savedOrder.isPresent());
        assertEquals(customerId, savedOrder.get().customerId());
    }

    @Test
    @DisplayName("should rollback on validation error")
    void handleRollsBackOnValidationError() {
        // Arrange: Create invalid command (empty items)
        var cmd = new CreateOrderCommand(
            UUID.randomUUID(),
            List.of()  // ← Empty
        );

        long countBefore = repository.count();

        // Act: Handle (should fail)
        var result = handler.handle(cmd);

        // Assert
        assertTrue(result.isFailure());

        // Verify nothing was persisted
        assertEquals(countBefore, repository.count());
    }

    @Test
    @DisplayName("should rollback on persistence error")
    @Transactional(propagation = NEVER)  // Prevent test transaction
    void handleRollsBackOnPersistenceError() {
        // This test would verify that if repository.save() throws,
        // the entire transaction rolls back

        // Implementation depends on your error scenario
    }
}
```

**Characteristics:**
- ✅ Full Spring context
- ✅ Real database (testcontainers)
- ✅ Tests complete flow
- ✅ Verifies transaction behavior
- ✅ Slower (needs DB)

**Run time:** ~500ms per test

### Test Pyramid

```
         /\
        /  \                   Integration Tests
       /    \                  (DatabaseTests)
      /      \                 ← Slow, comprehensive
     /        \
    /----------\
   /            \              Handler Unit Tests
  /              \             ← Fast, with mocks
 /----------------\
/                  \           Stage Unit Tests
/                  \           ← Very fast, pure
--------------------
```

**Recommendation:**
- 60% stage unit tests (pure)
- 30% handler unit tests (with mocks)
- 10% integration tests (full flow)

This gives you fast feedback while still testing integration.

---

## Advanced Patterns

### Pattern 1: Custom Execution Context

**Goal:** Add caching layer to prevent redundant operations.

```java
public class CachingExecutionContext extends ComposableExecutionContext {

    private final Cache cache;

    public CachingExecutionContext(ExecutionContext next, Cache cache) {
        super(next);
        this.cache = cache;
    }

    @Override
    protected <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation) {
        // Generate cache key (simplified)
        String cacheKey = computation.hashCode() + "";

        // Check cache
        Result<T> cached = (Result<T>) cache.get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for {}", cacheKey);
            return cached;
        }

        // Execute and cache
        log.debug("Cache miss for {}, executing...", cacheKey);
        Result<T> result = next.execute(computation);
        cache.put(cacheKey, result);

        return result;
    }
}
```

**Usage:**
```java
@Bean
public ExecutionContext cachedTransactionContext() {
    var txCtx = TransactionExecutionContext.of(txManager);
    var loggedCtx = new LoggingExecutionContext(txCtx);
    var cachedCtx = new CachingExecutionContext(loggedCtx, myCache);
    return cachedCtx;
}
```

**Execution flow:**
Cache → Logging → Transaction → Business Logic

### Pattern 2: Metrics/Observability Context

```java
public class MetricsExecutionContext extends ComposableExecutionContext {

    private final MeterRegistry meterRegistry;

    @Override
    protected <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation) {
        var timer = Timer.start();
        var result = next.execute(computation);
        timer.stop(meterRegistry.timer("execution.duration"));

        if (result.isSuccess()) {
            meterRegistry.counter("execution.success").increment();
        } else {
            meterRegistry.counter("execution.failure").increment();
        }

        return result;
    }
}
```

**Purpose:** Collect metrics for monitoring/alerting.

### Pattern 3: Retry/Resilience Context

```java
public class RetryExecutionContext extends ComposableExecutionContext {

    private final int maxRetries;
    private final Duration backoff;

    @Override
    protected <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return next.execute(computation);
            } catch (TemporaryException e) {
                if (attempt == maxRetries - 1) throw e;

                log.warn("Attempt {} failed, retrying...", attempt + 1);
                Thread.sleep(backoff.toMillis());
            }
        }
        // Unreachable
        throw new AssertionError();
    }
}
```

**Purpose:** Retry transient failures.

---

## Anti-Patterns to Avoid

### ❌ Anti-Pattern 1: Using @Transactional with ExecutionContext

```java
@Service @Transactional  // ❌ DON'T DO THIS
public class CreateOrderHandler {
    private final ExecutionContext txContext;

    public Result<Order> handle(CreateOrderCommand cmd) {
        return pipeline.within(txContext);  // ← Double-wrapped!
    }
}
```

**Problem:**
- Transaction applied twice (once by @Transactional, once by ExecutionContext)
- Confusing to read
- Potential for nesting issues

**Fix:**
```java
@Service  // ← Remove @Transactional
public class CreateOrderHandler {
    private final ExecutionContext txContext;

    public Result<Order> handle(CreateOrderCommand cmd) {
        return pipeline.within(txContext);  // ← Single wrap
    }
}
```

### ❌ Anti-Pattern 2: Forgetting to Inject ExecutionContext

```java
@Service
public class CreateOrderHandler {
    // ❌ txContext not injected!

    public Result<Order> handle(CreateOrderCommand cmd) {
        return pipeline.within(txContext);  // ← NullPointerException!
    }
}
```

**Fix:**
```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final ExecutionContext txContext;  // ← Inject it

    public Result<Order> handle(CreateOrderCommand cmd) {
        return pipeline.within(txContext);
    }
}
```

### ❌ Anti-Pattern 3: Stages That Know About Transactions

```java
@Slf4j
public final class CreateOrderStages {

    @Transactional  // ❌ NO! Stages don't manage transactions
    public static Result<Order> persist(Order order) {
        return Result.success(repository.save(order));
    }
}
```

**Problem:**
- Violates pure function principle
- Stages become untestable
- Transaction boundary not clear

**Fix:**
```java
public final class CreateOrderStages {

    // ✅ No @Transactional, just pure logic
    public static Result<Order> persist(Order order) {
        var saved = repository.save(order);
        return Result.success(saved);
    }
}
```

### ❌ Anti-Pattern 4: Mixing Handler Logic with Stage Logic

```java
@Service
public class CreateOrderHandler {

    public Result<Order> handle(CreateOrderCommand cmd) {
        // ❌ Validation logic in handler, not stage
        if (cmd.items().isEmpty()) {
            return Result.failure(...);
        }

        return pipeline.within(txContext);
    }
}
```

**Problem:**
- Handler becomes bloated
- Validation not reusable
- Can't test validation independently

**Fix:**
```java
@Service
public class CreateOrderHandler {

    public Result<Order> handle(CreateOrderCommand cmd) {
        // ✅ Delegate to stages
        return aggregator.start()
            .flatMap(CreateOrderStages::validate)  // ← Here
            .flatMap(CreateOrderStages::persist)
            .within(txContext);
    }
}
```

### ❌ Anti-Pattern 5: Ignoring Errors in Stages

```java
public static Result<Order> enrich(Order order) {
    try {
        var enriched = catalogService.enrich(order);  // Might fail
        return Result.success(enriched);
    } catch (Exception e) {
        // ❌ Silently ignore error!
        return Result.success(order);  // Return success anyway
    }
}
```

**Problem:**
- Error information lost
- Pipeline continues with incomplete data
- Debugging nightmare

**Fix:**
```java
public static Result<Order> enrich(Order order) {
    try {
        var enriched = catalogService.enrich(order);
        return Result.success(enriched);
    } catch (ServiceUnavailableException e) {
        // ✅ Only ignore if truly non-critical
        log.warn("Catalog service unavailable, continuing with unernriched data");
        return Result.success(order);
    } catch (Exception e) {
        // ✅ Propagate unexpected errors
        log.error("Enrichment failed", e);
        return Result.failure(EXTERNAL_SERVICE_ERROR, e.getMessage(), e);
    }
}
```

### ❌ Anti-Pattern 6: Creating ExecutionContext Inside Handler

```java
@Service
public class CreateOrderHandler {
    private final PlatformTransactionManager txManager;

    public Result<Order> handle(CreateOrderCommand cmd) {
        // ❌ Creating context every time
        var ctx = new TransactionExecutionContext(
            new TransactionTemplate(txManager)
        );

        return pipeline.within(ctx);
    }
}
```

**Problem:**
- Creates new context for each request (wasteful)
- Can't customize easily (in one place)
- Makes testing harder

**Fix:**
```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final ExecutionContext txContext;  // ✅ Inject once

    public Result<Order> handle(CreateOrderCommand cmd) {
        return pipeline.within(txContext);
    }
}
```

---

## Summary

### The Big Picture

```
┌─────────────────────────────────────────────────────────┐
│ Railway-Oriented Programming Framework                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│ Business Logic (Stages)                                │
│ • Pure functions                                       │
│ • No annotations                                       │
│ • Return Result<T>                                     │
│ • No side effects                                      │
│                                                         │
│         ↓ (flatMap chains)                             │
│                                                         │
│ Effect Boundary (ExecutionContext)                     │
│ • Wraps pipelines                                      │
│ • Manages transactions                                 │
│ • Composable (logging, caching, etc.)                 │
│ • Applied via .within()                                │
│                                                         │
│         ↓ (execute)                                    │
│                                                         │
│ Infrastructure (Spring, Database)                      │
│ • Transactions start/stop                              │
│ • Database operations occur                            │
│ • Events published                                     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Key Takeaways

1. **Pure functions describe WHAT** (stages)
2. **ExecutionContext describes HOW** (transaction, logging, etc.)
3. **They're never mixed** (no @Transactional on handlers)
4. **Results propagate values, not exceptions**
5. **Everything is testable** (stages, handlers, integration)
6. **Composition enables extensibility** (add logging, caching easily)

### Quick Checklist for New Handlers

- [ ] Create Command class (input)
- [ ] Create Result class (output)
- [ ] Create Aggregator class (state)
- [ ] Create Stages class (pure logic)
- [ ] Create Handler class (orchestration)
- [ ] Inject ExecutionContext
- [ ] Build pipeline with stages
- [ ] Apply `.within(txContext)`
- [ ] Create Controller
- [ ] Write tests (stages, handler, integration)

---

**You now understand the complete ExecutionContext architecture. Use this knowledge to build pure, testable, maintainable handlers.**
