# 04: Execution Contexts Catalog (All Available)

> Reference guide for every execution context in the framework.

---

## Overview Table

| Context | Use Case | Complexity | Thread-Safe | Virtual Threads |
|---------|----------|-----------|------------|-----------------|
| **TransactionExecutionContext** | Database transactions | Low | ✅ Yes | ✅ Yes |
| **SagaExecutionContext** | Distributed transactions + rollback | Medium | ✅ Yes | ✅ Yes |
| **OutboxExecutionContext** | Durable event publishing | Medium | ✅ Yes | ✅ Yes |
| **LoggingExecutionContext** | Observability/tracing | Low | ✅ Yes | ✅ Yes |
| **NoOpExecutionContext** | Testing (no-op) | Low | ✅ Yes | ✅ Yes |
| **ComposableExecutionContext** | Combine multiple contexts | Medium | ✅ Yes | ✅ Yes |

---

## 1. TransactionExecutionContext (Fundamental)

### Purpose

Wraps pipeline execution inside a database transaction.

### When to Use

- ✅ Database operations must be atomic (all-or-nothing)
- ✅ Simple CRUD operations
- ✅ Single data source

### When NOT to Use

- ❌ Multiple external systems (use Saga instead)
- ❌ Events need to be published durably (use Outbox instead)

### Implementation

```java
public class TransactionExecutionContext implements ExecutionContext {
    private final TransactionTemplate template;

    public <T, E> Result<T, E> execute(
        Supplier<Result<T, E>> computation
    ) {
        return template.execute(status -> computation.get());
    }
}
```

### Usage Example

```java
@Service @RequiredArgsConstructor
public class CreateOrderHandler {
    private final TransactionExecutionContext txContext;

    public Result<Order, Error> handle(CreateOrderCommand cmd) {
        return Result.of(cmd.order())
            .flatMap(CreateOrderStages::validate)
            .flatMap(CreateOrderStages::reserveStock)
            .flatMap(CreateOrderStages::persist)
            .within(txContext);  // ← Apply transaction
    }
}
```

### How It Works

```
1. within(txContext) called
2. txContext.execute() invoked with computation
3. TransactionTemplate.execute() called
4. Database transaction starts
5. Computation runs (all stages execute)
6. If Result.success() → transaction commits
7. If Result.failure() → transaction rolls back
8. Result returned to caller
```

### Thread Safety

✅ **Fully thread-safe**

- Uses Spring's `TransactionTemplate`
- Transactions are thread-bound via `ThreadLocal`
- Each thread gets its own transaction

### Virtual Thread Compatibility

✅ **Fully compatible**

```java
// Safe with virtual threads
Executors.newVirtualThreadPerTaskExecutor()
    .execute(() -> pipeline.within(txContext));
```

Virtual threads have their own `ThreadLocal`, so transactions are isolated per virtual thread.

---

## 2. SagaExecutionContext (Distributed Transactions)

### Purpose

Handles **distributed transactions** across multiple systems with automatic compensation (rollback) if any step fails.

### When to Use

- ✅ Multiple systems must be updated atomically (DB + Keycloak)
- ✅ Automatic rollback is needed
- ✅ Strong consistency required
- ✅ One external system failure must not leave the other partially updated

### When NOT to Use

- ❌ Single database (use TransactionExecutionContext instead)
- ❌ Eventual consistency is acceptable (use Outbox instead)

### Key Concept: Compensating Transactions

Instead of database rollbacks, Saga uses **compensation**:

```
Step 1: Save order to DB
        Register compensation: Delete order from DB

Step 2: Update Keycloak
        Register compensation: Undo Keycloak update

Step 3: All succeed → No compensation needed

If Step 2 fails:
  1. Step 2 operation fails
  2. Compensation 2 executes (undo Keycloak update)
  3. Compensation 1 executes (delete order)
  4. Both systems back to original state
```

### Implementation Overview

```java
public class SagaExecutionContext implements ExecutionContext {
    private final ExecutionContext delegate;  // Wraps TransactionExecutionContext

    @Override
    public <T, E> Result<T, E> execute(
        Supplier<Result<T, E>> computation
    ) {
        try {
            // Execute computation inside delegate (TX)
            Result<T, E> result = delegate.execute(computation);

            // If failed, execute compensations in LIFO order
            if (result.isFailure()) {
                executeCompensations();
            }

            return result;
        } finally {
            cleanupCompensations();
        }
    }
}
```

### Usage Example

```java
@Service @RequiredArgsConstructor
public class UpdateTenantHandler {
    private final SagaExecutionContext sagaContext;

    public Result<Tenant, Error> handle(UpdateTenantCommand cmd) {
        return Result.of(cmd.tenant())
            .flatMap(UpdateTenantStages::validate)
            .flatMap(UpdateTenantStages::persistTenant)        // Register compensation
            .flatMap(UpdateTenantStages::updateOrganizationInIAM)  // Register compensation
            .within(sagaContext)  // ← Apply saga with compensations
            .flatMap(UpdateTenantStages::buildResult);
    }
}
```

### Stage Implementation (With Compensations)

```java
public static Result<Aggregator, Error> persistTenant(Aggregator agg) {
    return repository.save(agg.tenant())
        .flatMap(id -> {
            // Register compensation: revert changes
            agg.registerCompensation(() ->
                repository.update(agg.originalTenant())
            );
            return Result.success(agg.withPersistedId(id));
        });
}

public static Result<Aggregator, Error> updateOrganizationInIAM(Aggregator agg) {
    return iamService.updateOrganization(agg.iamUpdateRequest())
        .flatMap(_ -> {
            // Register compensation: undo IAM update
            agg.registerCompensation(() ->
                iamService.updateOrganization(agg.originalIAMState())
            );
            return Result.success(agg);
        });
}
```

### Execution Flow (Success Path)

```
1. persistTenant()
   ├─ Saves tenant to DB
   └─ Registers compensation A

2. updateOrganizationInIAM()
   ├─ Updates Keycloak
   └─ Registers compensation B

3. Both succeed
   └─ Compensations not executed
   └─ Both systems updated

4. Returns Result.success()
```

### Execution Flow (Failure Path)

```
1. persistTenant() ✅
   ├─ Saves tenant to DB
   └─ Registers compensation A

2. updateOrganizationInIAM() ❌
   ├─ Keycloak API fails
   └─ Registers compensation B (not used)

3. SagaContext catches failure
   └─ Executes compensations in LIFO:

   First: Compensation B (though not registered)
   Then:  Compensation A (LIFO = Last In First Out)
          ├─ Deletes tenant from DB
          └─ Reverts to original state

4. Both systems back to original state

5. Returns Result.failure()
```

### Thread Safety

✅ **Fully thread-safe**

- Uses `ThreadLocal` for compensation tracking
- Each request has its own compensation list
- Cleanup prevents memory leaks

### Virtual Thread Compatibility

✅ **Fully compatible**

Virtual threads have their own `ThreadLocal`, so compensations are isolated per virtual thread.

---

## 3. OutboxExecutionContext (Event Durability)

### Purpose

Publishes events **durably** by storing them in a database outbox table, then publishing asynchronously.

### When to Use

- ✅ Domain events must be published reliably
- ✅ Event loss is unacceptable
- ✅ Eventual consistency is acceptable
- ✅ Multiple downstream consumers

### When NOT to Use

- ❌ Immediate consistency required (use Saga instead)
- ❌ No events to publish (use TransactionExecutionContext instead)

### Problem It Solves

```
❌ Without Outbox:
  1. Save order to DB
  2. Publish event to Kafka
  Problem: DB commits, but Kafka publish fails
  Result: DB has order, but subscribers never know

✅ With Outbox:
  1. Save order to DB
  2. Save event to outbox table (SAME TRANSACTION)
  3. Commit (both atomic)
  4. Separate async process polls outbox
  5. Publishes events to Kafka
  6. Marks as published
  Result: No event loss, guaranteed delivery
```

### Implementation Overview

```java
public class OutboxExecutionContext implements ExecutionContext {
    private static final ThreadLocal<List<Supplier<Result<OutboxEntry>>>>
        OUTBOX_ENTRIES = new ThreadLocal<>();

    @Override
    public <T, E> Result<T, E> execute(
        Supplier<Result<T, E>> computation
    ) {
        try {
            Result<T, E> result = computation.get();

            if (result.isSuccess()) {
                // Get all registered entries
                var entries = getOutboxEntries();
                // Save to outbox table (part of same TX)
                entries.forEach(this::saveToOutbox);
            }

            return result;
        } finally {
            cleanupOutbox();
        }
    }
}
```

### Usage Example

```java
@Service @RequiredArgsConstructor
public class CreateTenantHandler {
    private final OutboxExecutionContext outboxContext;

    public Result<Tenant, Error> handle(CreateTenantCommand cmd) {
        return Result.of(cmd.tenant())
            .flatMap(CreateTenantStages::validate)
            .flatMap(CreateTenantStages::persist)    // Register event
            .within(outboxContext)  // ← Store event in outbox
            .flatMap(CreateTenantStages::buildResult);
    }
}
```

### Stage Implementation (Registering Events)

```java
public static Result<Aggregator, Error> persist(Aggregator agg) {
    return repository.save(agg.tenant())
        .flatMap(id -> {
            // Register event to be stored in outbox
            agg.registerOutboxEntry(() ->
                Result.success(new OutboxEntry(
                    aggregateType = "Tenant",
                    aggregateId = id.toString(),
                    eventType = "TenantCreatedEvent",
                    payload = serialize(agg.tenant()),
                    topic = "tenant-events"
                ))
            );
            return Result.success(agg.withPersistedId(id));
        });
}
```

### Execution Flow

```
Step 1: Business Operations (within OutboxContext)
  ├─ Validate tenant
  ├─ Save to database
  └─ Register events to outbox

Step 2: Commit (single transaction)
  ├─ Database: tenant record
  ├─ Database: outbox entries (published=false)
  └─ Commit both atomically

Step 3: Async Publishing (separate process)
  ├─ Poll outbox for unpublished events
  ├─ Publish to Kafka/RabbitMQ
  ├─ Mark as published=true
  └─ Delete after TTL (if needed)

Result: Durable, no event loss
```

### Thread Safety

✅ **Fully thread-safe**

- Uses `ThreadLocal` for entry tracking
- Each request has its own entry list
- Cleanup prevents memory leaks

### Virtual Thread Compatibility

✅ **Fully compatible**

Virtual threads have their own `ThreadLocal`, so entries are isolated per virtual thread.

---

## 4. LoggingExecutionContext (Observability)

### Purpose

Logs execution of pipelines for observability and debugging.

### When to Use

- ✅ Need to trace pipeline execution
- ✅ Debugging complex workflows
- ✅ Performance monitoring

### Usage Example

```java
result
    .flatMap(Stages::validate)
    .flatMap(Stages::persist)
    .within(loggingContext);  // Logs: Starting, completion, duration
```

### Output Example

```
INFO: Executing pipeline
INFO: Pipeline completed in 234ms
INFO: Result: success
```

---

## 5. NoOpExecutionContext (Testing)

### Purpose

No-op executor for testing (does nothing, just returns result).

### When to Use

- ✅ Unit testing stages
- ✅ Don't want actual side effects
- ✅ Pure logic testing

### Usage Example

```java
@Test
void testValidation() {
    Result<Order, Error> result = Result.of(order)
        .flatMap(CreateOrderStages::validate)
        .within(noOpContext);  // Does nothing

    assertTrue(result.isSuccess());
}
```

---

## 6. ComposableExecutionContext (Combining Contexts)

### Purpose

Combines multiple execution contexts.

### When to Use

- ✅ Need multiple execution strategies (TX + Logging + Tracing)
- ✅ Composing contexts dynamically

### Usage Example

```java
var composed = ComposableExecutionContext.compose(
    transactionContext,
    loggingContext,
    tracingContext
);

result
    .flatMap(Stages::validate)
    .flatMap(Stages::persist)
    .within(composed);  // All three applied
```

---

## Selection Decision Tree

```
Do you need atomicity?

    NO → Use pure flatMap
    │
    YES → Is it a single database?
        │
        NO (multiple systems) → Do you need immediate consistency?
        │                           │
        │                           YES → Use SagaExecutionContext
        │                           │
        │                           NO → Use OutboxExecutionContext
        │
        YES (single DB) → Use TransactionExecutionContext


Need additional observability?

    → Wrap with LoggingExecutionContext
    → Or use ComposableExecutionContext
```

---

## Comparison Matrix

| Aspect | TX Context | Saga Context | Outbox Context |
|--------|-----------|--------------|----------------|
| **Atomicity** | DB only | DB + External | DB + Async |
| **Consistency** | Strong | Strong | Eventual |
| **Rollback** | DB rollback | Compensations | Re-publish |
| **Use Case** | Simple CRUD | Keycloak + DB | Events |
| **Complexity** | Low | Medium | Medium |
| **Latency** | Immediate | Immediate | Eventual |

---

## Next Steps

1. **Quick Implementation**: See `patterns/` folder for concrete examples
2. **Team Rules**: See `TEAM_RULES_AND_BEST_PRACTICES.md`
3. **Virtual Threads**: See `04_VIRTUAL_THREADS_VALIDATED.md`

---

**Choose the right context for your use case!**
