# Saga Pattern - Quick Reference

## When to Use Saga

✅ Use when you need **atomic updates across multiple systems**:
- Database + Keycloak IAM
- Database + Message Queue
- Database + Cache
- Database + Multiple external APIs

❌ Don't use when:
- Single database operation (use `TransactionExecutionContext`)
- Read-only query (use `readOnlyContext`)
- Non-critical operations (use `transactionExecutionContext`)

## Implementation Checklist

### 1. Update Your Aggregator

```java
@Builder
@With
public record UpdateTenantAggregator(
        // ... existing fields ...
        List<Supplier<Result<Void>>> compensations  // ← ADD THIS
) implements SagaAggregator {  // ← ADD THIS INTERFACE

    @Override
    public UpdateTenantAggregator withCompensation(Supplier<Result<Void>> compensation) {
        var updated = new ArrayList<>(this.compensations);
        updated.add(compensation);
        return this.withCompensations(updated);
    }
}
```

### 2. Register Compensations in Stages

```java
public static Result<UpdateTenantAggregator> persist(UpdateTenantAggregator state) {
    var saved = repository.save(state.tenant());

    return Result.success(state
        .withTenant(saved)
        .withCompensation(() -> {  // ← ADD COMPENSATION
            repository.delete(saved.id());
            return Result.success(null);
        }));
}
```

### 3. Inject SagaExecutionContext

```java
@Service
@RequiredArgsConstructor
public class UpdateTenantHandler {
    private final SagaExecutionContext sagaContext;  // ← INJECT
    // ...
}
```

### 4. Apply .within(sagaContext)

```java
public Result<UpdateTenantResult> handle(UpdateTenantCommand command) {
    return Result.success(aggregator)
        .flatMap(UpdateTenantStages::validateTenantExists)
        .within(sagaContext)  // ← APPLY SAGA
        .flatMap(UpdateTenantStages::persist)  // + compensation
        .flatMap(UpdateTenantStages::updateKeycloak)  // + compensation
        .flatMap(UpdateTenantStages::buildResult);
}
```

## Key Points

| Aspect | Detail |
|--------|--------|
| **Initialization** | `new ArrayList<>()` when creating aggregator |
| **Registration** | `.withCompensation(() -> {...})` in each stage |
| **Execution** | `.within(sagaContext)` before operations |
| **Order** | Compensations execute in **reverse order** (LIFO) |
| **Failures** | If any stage fails, compensations still execute |
| **IdempotencyRequirement** | Compensations should be idempotent |

## Compensation Registration Pattern

```java
public static Result<State> operationName(State state) {
    // 1. Do the operation
    var result = externalService.doSomething();

    // 2. Register compensation
    return Result.success(state
        .withCompensation(() -> {
            // Undo what was done
            externalService.undo();
            return Result.success(null);
        }));
}
```

## Error Handling in Compensations

```java
// ✅ CORRECT - Return Result.failure()
.withCompensation(() -> {
    try {
        organizationIAM.delete(orgId);
        return Result.success(null);
    } catch (Exception e) {
        log.error("Compensation failed", e);
        return Result.failure(...);  // Don't throw!
    }
})

// ❌ WRONG - Throwing exception stops other compensations
.withCompensation(() -> {
    organizationIAM.delete(orgId);  // If throws, saga breaks
    return Result.success(null);
})
```

## Testing Sagas

```java
// Test happy path
@Test
void handleSuccessfully() {
    var result = handler.handle(command);
    assertTrue(result.isSuccess());
    // Verify both DB and external system updated
}

// Test compensation execution
@Test
void compensatesOnFailure() {
    when(keycloak.update(...)).thenThrow(exception);
    var result = handler.handle(command);

    assertTrue(result.isFailure());
    // Verify DB was rolled back (compensation executed)
    verify(repository).delete(...);
}
```

## Configuration (Spring)

```java
@Configuration
public class ExecutionContextConfiguration {

    @Bean
    public SagaExecutionContext sagaExecutionContext() {
        var txContext = TransactionExecutionContext.of(txManager);
        return new SagaExecutionContext(txContext);
    }

    @Bean("sagaWithLogging")
    public ExecutionContext sagaWithLogging() {
        return SagaExecutionContext.withLogging(
            TransactionExecutionContext.of(txManager)
        );
    }
}
```

## Documentation

For complete guide, see: [SAGA_PATTERN.md](SAGA_PATTERN.md)
