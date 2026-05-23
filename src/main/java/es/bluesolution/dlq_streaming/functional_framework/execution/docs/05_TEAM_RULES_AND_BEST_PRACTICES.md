# 05: Team Rules & Best Practices (Mandatory)

> Copy this section to your team's coding standards.

---

## 🎯 Core Principle (Never Violate)

```
flatMap describes transformations.
within applies execution contexts.
```

If this is violated, the abstraction breaks.

---

## 🔴 MANDATORY Rules

### Rule 1: Purity of Stages

**Every stage must be pure**.

```java
// ✅ GOOD: Pure transformation
static Result<Order, Error> validate(Order order) {
    if (order.items().isEmpty()) {
        return Result.failure(VALIDATION_ERROR, "Empty");
    }
    return Result.success(order);
}

// ❌ BAD: Impure (side effects)
static Result<Order, Error> persist(Order order) {
    database.save(order);  // Side effect happens here
    return Result.success(order);
}

// ❌ BAD: Hidden execution
static Result<Order, Error> saveAndPublish(Order order) {
    return repository.save(order)
        .within(txContext);  // within is hidden!
}
```

**Enforcement**: Code review must catch any impure stages.

### Rule 2: Visibility of Execution Boundaries

**Every `within` must be visible at the handler level**.

```java
// ✅ GOOD: Explicit boundary
@Service
public class CreateOrderHandler {
    public Result<Order, Error> handle(CreateOrderCommand cmd) {
        return Result.of(cmd.order())
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::persist)
            .within(transactionContext);  // ← Visible
    }
}

// ❌ BAD: Hidden inside a stage
public class CreateOrderStages {
    public static Result<Order, Error> persist(Order order) {
        return repository.save(order)
            .within(txContext);  // ← Hidden!
    }
}
```

**Enforcement**: No `within` allowed inside `Stages` classes.

### Rule 3: Atomicity Grouping

**All atomic operations must precede the same `within`**.

```java
// ✅ GOOD: Both atomic together
result
    .flatMap(Stages::validateInventory)
    .flatMap(Stages::reserveStock)
    .flatMap(Stages::saveOrder)
    .within(txContext);

// ❌ BAD: Split atomic operation
result
    .flatMap(Stages::validateInventory)
    .flatMap(Stages::reserveStock)
    .within(txContext)

    .flatMap(Stages::saveOrder)  // Part of same atomic operation!
    .within(txContext);
```

**Enforcement**: If two stages are atomic, they go before the same `within`.

### Rule 4: Context Selection

**Choose the right context for your use case**.

```
Use Case                        Context
─────────────────────────────────────────────────
Single DB operation             TransactionExecutionContext
DB + External system (strong)   SagaExecutionContext
Events to publish               OutboxExecutionContext
Just data transformation        No context needed
Testing without effects         NoOpExecutionContext
Add observability               LoggingExecutionContext + others
```

**Enforcement**: Code review verifies correct context choice.

### Rule 5: No Micro-Scoping

**Don't create `within` for every single stage**.

```java
// ❌ BAD: Micro-scoping (every stage has within)
result
    .flatMap(a).within(tx)
    .flatMap(b).within(tx)
    .flatMap(c).within(tx);

// ✅ GOOD: One scope for atomic operations
result
    .flatMap(a)
    .flatMap(b)
    .flatMap(c)
    .within(tx);
```

**Enforcement**: Never have adjacent `flatMap` → `within` → `flatMap` patterns.

### Rule 7: No `@Transactional` Annotations

**Never use `@Transactional` on handlers, repositories, or service methods.**

Transaction management is done functionally through `.within(txContext)` or `.within(readOnlyContext)`.

```java
// ❌ FORBIDDEN: @Transactional on handler
@Transactional(readOnly = true)
public Result<Order> handle(GetOrderQuery query) { ... }

// ❌ FORBIDDEN: @Transactional on repository method
@Transactional
public Result<CustomerId> create(Customer c) { ... }

// ✅ CORRECT: Functional transaction management
public Result<Order> handle(GetOrderQuery query) {
    return Result.success(data)
        .flatMap(d -> Stages.retrieve(d, ports))
        .within(readOnlyContext)                   // ← Transaction applied here
        .flatMap(Stages::buildResult);
}
```

**Only exception**: `@Transactional` on Spring Data `@Modifying @Query` methods — this is required by the Spring Data JPA contract and is infrastructure-level, not application-level.

**Enforcement**: Zero `@Transactional` in any `*Handler.java`, `*RepositoryImpl.java`, or `*Internal.java` file.

### Rule 8: No JPA Relationship Annotations — Flat Entities Only

**JPA entities must NOT use ANY Hibernate/JPA relationship annotations: `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`, `@JoinColumn`.**

Entities are **flat** — they contain only scalar fields and plain `UUID` foreign keys. Parent-child relationships are managed entirely by application code: children are loaded/saved via their own Spring Data repositories.

```java
// ✅ CORRECT: Parent entity (OrderEntity) — flat, no child collections
@Entity
@Table(name = "purchase_order", schema = "orders")
public class OrderEntity {
    @Id
    private UUID orderId;
    private UUID tenantId;
    private UUID customerId;
    // ... only scalar fields and plain UUID FKs
    
    public Order toDomain(List<OrderLine> lines) { ... }  // Lines passed as parameter
}

// ✅ CORRECT: Child entity (OrderLineEntity) — plain FK column, no @ManyToOne
@Entity
@Table(name = "order_line", schema = "orders")
public class OrderLineEntity {
    @Id
    private UUID orderLineId;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;  // ← Plain UUID, not a relationship
}

// ✅ CORRECT: Repository loads children separately
private Order toDomainWithLines(OrderEntity entity) {
    var lineEntities = orderLineSpringRepository.findByOrderId(entity.getOrderId());
    var domainLines = lineEntities.stream().map(OrderLineEntity::toDomain).toList();
    return entity.toDomain(domainLines);
}

// ❌ FORBIDDEN: @OneToMany on parent
@OneToMany(mappedBy = "orderId", cascade = CascadeType.ALL)
private List<OrderLineEntity> orderLines;

// ❌ FORBIDDEN: @ManyToOne on child
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "order_id")
private OrderEntity order;

// ❌ FORBIDDEN: Any Hibernate relationship annotation
@JoinColumn, @ManyToMany, @OneToOne
```

**Why**:
- **No lazy-loading surprises** — `LazyInitializationException` is impossible when there are no lazy collections
- **No Hibernate session coupling** — entities work outside transactions
- **Explicit control** — you always know when children are loaded (you loaded them explicitly)
- **Simple entities** — no cascade complexity, no orphan removal gotchas
- **Independent child queries** — children have their own Spring Data repositories with `findByParentId()`
- **toDomain() is pure** — accepts children as a parameter, no side-effects

**Pattern for parent `toDomain()`**: Always accept `List<ChildDomain>` as a parameter:
- `OrderEntity.toDomain(List<OrderLine> lines)`
- `ReceiptEntity.toDomain(List<ReceiptLineCheck> lines)`
- `ShippingOrderEntity.toDomain(List<ShippingOrderLine> lines)`

**Pattern for `RepositoryImpl`**: Add a private `toDomainWithLines()` helper that loads children via Spring Data repository and passes them to `entity.toDomain(lines)`.

**Enforcement**: Zero `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`, `@JoinColumn` in any `*Entity.java` file.

### Rule 9: Documentation for Multi-Boundary Pipelines

**If you have multiple `within` calls, document why**.

```java
// ✅ GOOD: Documented boundaries
result
    .flatMap(validateOrder)
    .flatMap(createOrder)
    .within(databaseTransaction)     // Boundary: Order is durable

    .flatMap(publishOrderCreated)
    .within(outboxTransaction)       // Boundary: Event is durable

    .flatMap(sendConfirmationEmail);
```

**Enforcement**: Code review checks for this comment.

### Rule 10: Use `Result.pipeline()` or `txContext.execute()` for Deferred Computation

**CRITICAL: `Result.within(txContext)` on an eager chain does NOT defer pipeline execution.**

The old `Result.within()` captures the **already-evaluated** Result (now `@Deprecated(forRemoval = true)`):
```java
// Result.java — DEPRECATED, broken in Java due to eager evaluation
@Deprecated(since = "2026.04", forRemoval = true)
default Result<S> within(ExecutionContext ctx) {
    return ctx.execute(() -> this);  // ← "this" is already computed!
}
```

**The fix: `ResultPipeline`** — a lazy/deferred monad that composes Suppliers without executing:

```java
// ❌ BROKEN: pipeline executes BEFORE the transaction starts
return Result.success(data)
    .flatMap(d -> fetchOrder(d, ports))   // Runs eagerly, outside tx!
    .flatMap(Stages::validate)
    .within(txContext);                   // Wraps already-computed result

// ✅ PREFERRED: ResultPipeline defers computation correctly
return Result.pipeline(data)
    .flatMap(d -> fetchOrder(d, ports))   // Deferred — NOT executed yet
    .flatMap(Stages::validate)            // Deferred — NOT executed yet
    .flatMap(d -> persist(d, ports))      // Deferred — NOT executed yet
    .within(txContext)                    // ALL execute HERE, inside tx!
    .flatMap(Stages::buildResult);        // Pure: on Result, outside tx

// ✅ ALSO CORRECT: explicit txContext.execute()
return txContext.execute(() ->
    Result.success(data)
        .flatMap(d -> fetchOrder(d, ports))   // Runs inside tx
        .flatMap(Stages::validate)
)
.flatMap(Stages::buildResult);                // Pure: outside tx
```

**When to use which:**
- `Result.pipeline(data).flatMap(...).within(txContext)` — **Preferred** for all handlers (clean fluent API, deferred)
- `txContext.execute(() -> pipeline)` — **Also correct** (equivalent, more verbose)
- `Result.success(data).flatMap(...).within(txContext)` — **DEPRECATED/BROKEN**, do not use

**Enforcement**: Handlers with database access MUST use `Result.pipeline()` or `txContext.execute()`.

> **FP analogy:** `ResultPipeline` is Java's equivalent of Haskell's `IO` monad or Scala's `ZIO` — 
> it describes effects without executing them. `.within()` is the `runIO`/`unsafeRunSync`.

---

## 🟡 STRONG Recommendations

### R1: Use Descriptive Names

```java
// ✅ GOOD: Clear intent
result
    .flatMap(Stages::validate)
    .flatMap(Stages::persist)
    .within(databaseTransactionContext);

// ❌ POOR: Unclear
result
    .flatMap(Stages::validate)
    .flatMap(Stages::persist)
    .within(ctx);
```

### R2: Extract Complex Pipelines to Methods

```java
// ✅ GOOD: Named method
private Result<Order, Error> createOrderPipeline(CreateOrderCommand cmd) {
    return Result.of(cmd.order())
        .flatMap(CreateOrderStages::validate)
        .flatMap(CreateOrderStages::persist)
        .within(transactionContext)
        .flatMap(CreateOrderStages::publishEvent);
}

public Result<Order, Error> handle(CreateOrderCommand cmd) {
    return createOrderPipeline(cmd);
}

// ✅ ALSO GOOD: Inline with clear comment
public Result<Order, Error> handle(CreateOrderCommand cmd) {
    return Result.of(cmd.order())
        // Persist order atomically
        .flatMap(CreateOrderStages::validate)
        .flatMap(CreateOrderStages::persist)
        .within(transactionContext)

        // Publish event after transaction
        .flatMap(CreateOrderStages::publishEvent);
}
```

### R3: Test Stages Before Using in Pipelines

```java
// ✅ GOOD: Test stages independently
@Test
void testValidateStage() {
    Result<Order, Error> result = CreateOrderStages.validate(order);
    assertTrue(result.isSuccess());
}

@Test
void testFullPipeline() {
    Result<Order, Error> result = handler.handle(cmd);
    assertTrue(result.isSuccess());
    verify(repository).save(...);
}
```

### R4: Use Immutable Aggregators

```java
// ✅ GOOD: Immutable state
public record CreateOrderAggregator(
    Order order,
    Set<Compensation> compensations
) {
    public CreateOrderAggregator withCompensation(Compensation c) {
        var newSet = new HashSet<>(compensations);
        newSet.add(c);
        return new CreateOrderAggregator(order, newSet);
    }
}

// ❌ BAD: Mutable state
public class CreateOrderAggregator {
    Order order;
    List<Compensation> compensations;  // Mutable!
}
```

---

## ✅ Anti-Patterns (Explicitly Forbidden)

### ❌ Anti-Pattern 1: Hidden `within`

```java
// FORBIDDEN
static Result<Order, Error> persist(Order order) {
    return repository.save(order)
        .within(txContext);  // NO!
}
```

**Why**: Violates visibility principle.

**Fix**: Remove from stage, apply at handler level.

### ❌ Anti-Pattern 2: `within` in Control Flow

```java
// FORBIDDEN
if (something) {
    result = result.within(txContext);  // NO!
}
```

**Why**: Execution boundaries must be deterministic and visible.

**Fix**: Move `within` outside the conditional, or use different pipelines.

### ❌ Anti-Pattern 3: Throwing Exceptions in Stages

```java
// FORBIDDEN
static Result<Order, Error> validate(Order order) {
    if (order == null) {
        throw new NullPointerException();  // NO!
    }
    return Result.success(order);
}

// CORRECT
static Result<Order, Error> validate(Order order) {
    if (order == null) {
        return Result.failure(VALIDATION_ERROR, "Order is null");
    }
    return Result.success(order);
}
```

**Why**: ROP uses Result, never exceptions.

### ❌ Anti-Pattern 4: Multiple `within` for Same Context

```java
// FORBIDDEN (usually)
result
    .flatMap(a).within(tx)
    .flatMap(b).within(tx)
    .flatMap(c).within(tx);
```

**Why**: Fragmented execution, harder to reason about.

**Fix**: Group before one `within`.

```java
// CORRECT
result
    .flatMap(a)
    .flatMap(b)
    .flatMap(c)
    .within(tx);
```

### ❌ Anti-Pattern 5: Mixed Execution Models in Same Handler

```java
// FORBIDDEN (usually)
@Service
public class CreateOrderHandler {
    @Transactional  // Method-level
    public Result<Order, Error> handle(CreateOrderCommand cmd) {
        return result
            .flatMap(stages)
            .within(txContext);  // Also within!
    }
}
```

**Why**: Mixing `@Transactional` and `within` is confusing.

**Fix**: Use one or the other (prefer `within`).

---

## 🧪 Testing Rules

### Rule T1: Test Stages Independently

```java
// ✅ GOOD: Pure unit test, no Spring
class CreateOrderStagesTest {
    @Test
    void testValidate_EmptyOrder() {
        Order empty = new Order();
        Result<Order, Error> result = CreateOrderStages.validate(empty);
        assertTrue(result.isFailure());
    }

    @Test
    void testValidate_ValidOrder() {
        Order valid = new Order(List.of(item1, item2));
        Result<Order, Error> result = CreateOrderStages.validate(valid);
        assertTrue(result.isSuccess());
    }
}
```

### Rule T2: Test Full Pipeline with Mock Context

```java
// ✅ GOOD: Full pipeline test
class CreateOrderHandlerTest {
    @Test
    void testCreateOrder_Success() {
        Result<Order, Error> result = handler.handle(cmd);
        assertTrue(result.isSuccess());
        verify(repository).save(...);
    }
}
```

### Rule T3: Test Failure Paths

```java
// ✅ GOOD: Test both success and failure
@Test
void testCreateOrder_ValidationFails() {
    Order invalid = new Order();  // Empty
    Result<Order, Error> result = handler.handle(
        new CreateOrderCommand(invalid)
    );
    assertTrue(result.isFailure());
    verify(repository, never()).save(...);  // Not called
}
```

---

## 🚀 Virtual Thread Safety (Validated)

✅ **All execution contexts are virtual thread safe.**

```java
// Safe to use with virtual threads
Executors.newVirtualThreadPerTaskExecutor()
    .execute(() -> {
        Result<Order, Error> result = pipeline
            .flatMap(Stages::validate)
            .flatMap(Stages::persist)
            .within(transactionContext);  // ✅ Safe
        handleResult(result);
    });
```

**Why**:

- Each virtual thread has its own `ThreadLocal`
- Transaction binding is thread-local
- Compensation tracking is thread-local
- No shared mutable state

---

## 📋 Code Review Checklist

- [ ] All stages are pure (no side effects)
- [ ] No `within` inside any `Stages` class
- [ ] All atomic operations before same `within`
- [ ] Right context chosen for the use case
- [ ] No micro-scoping (tiny `within` blocks)
- [ ] Multi-boundary pipelines are documented
- [ ] No exceptions thrown in stages (Result instead)
- [ ] **No `@Transactional` anywhere** (use `.within(txContext)` or `.within(readOnlyContext)`)
- [ ] **No `@JoinColumn`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`** in JPA entities
- [ ] JPA parent-child uses `@OneToMany(mappedBy = "fieldName")` only
- [ ] Global BDD tests pass for cross-BC flows
- [ ] Test stages independently
- [ ] Test full pipeline
- [ ] Test failure paths

---

## 🎓 Team Training

Ensure all developers:

1. ✅ Read `01_MENTAL_MODEL_EXPLAINED.md`
2. ✅ Understand `flatMap` vs `within` distinction
3. ✅ Know which context to use when
4. ✅ Follow the mandatory rules above
5. ✅ Can explain this to a new team member

---

## 🆘 Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| "Stages have infrastructure dependencies" | Forgot to separate concerns | Move DB/API calls to context |
| "`within` hidden in stage" | Misunderstanding of visibility | Move `within` to handler |
| "Split atomic operations" | Multiple `within` on same context | Group before same `within` |
| "Test is slow" | Using full Spring context | Test stages independently |
| "Virtual thread deadlock" | Shared mutable state | Use immutable aggregators |

---

## 📞 Questions?

Ask: "Is this pure? Is this visible? Is this grouped correctly?"

If yes to all three → Your design is good.

---

**Now ready for production! Review `TEAM_RULES_AND_BEST_PRACTICES.md` with your team before deploying.**
