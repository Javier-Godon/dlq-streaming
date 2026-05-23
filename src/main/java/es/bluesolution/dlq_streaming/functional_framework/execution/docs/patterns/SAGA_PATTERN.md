# Saga Pattern - Distributed Transactions in FP/ROP

## Overview

The **Saga pattern** solves the distributed transaction problem in a functional way. It's perfect for operations that span multiple systems (database + Keycloak, database + message queue, etc.) where you need strong consistency guarantees.

## The Problem

Traditional ACID transactions work for single databases. But when you need to update **two separate systems atomically**, you have a problem:

```java
// ❌ NOT ATOMIC - Both systems are separate
database.save(tenant);       // Succeeds
keycloak.update(org);        // Fails!
// Result: Database has new data, Keycloak is out of sync
```

Database transactions can't rollback Keycloak, and vice versa.

## The Saga Solution

A Saga is a **sequence of local transactions** where each transaction has a corresponding **compensation** (the opposite operation):

```
Database Save ← (compensation: delete from DB)
Keycloak Update ← (compensation: delete from Keycloak)

If Keycloak fails:
1. Undo Keycloak update
2. Undo database save
(In reverse order - LIFO)
```

**Result: Strong consistency across both systems.**

## How It Works

### Step 1: Define Your Aggregator with Compensations

```java
@Builder
@With
public record UpdateTenantAggregator(
        TenantRepository tenantRepository,
        OrganizationIAM organizationIAM,
        UpdateTenantCommand command,
        Tenant tenant,
        Tenant existingTenant,
        TenantId persistedId,
        List<Supplier<Result<Void>>> compensations  // ← NEW
) implements SagaAggregator {  // ← NEW

    /**
     * Register a compensation operation.
     * This is called by stages to say: "if saga fails, run this to undo me"
     */
    @Override
    public UpdateTenantAggregator withCompensation(Supplier<Result<Void>> compensation) {
        var updated = new ArrayList<>(this.compensations);
        updated.add(compensation);
        return this.withCompensations(updated);
    }
}
```

**What changed:**
- Added `List<Supplier<Result<Void>>> compensations` field
- Implemented `SagaAggregator` interface
- Added `withCompensation()` method

### Step 2: Register Compensations in Stages

Each stage registers what to do if the saga fails:

```java
@Slf4j
public final class UpdateTenantStages {

    /**
     * Persist tenant to database.
     * If saga fails after this, we need to delete the tenant.
     */
    public static Result<UpdateTenantAggregator> persist(UpdateTenantAggregator state) {
        try {
            var saved = state.tenantRepository().save(state.tenant());
            log.debug("Tenant persisted with ID: {}", saved.id());

            // Register compensation: if saga fails, delete this tenant
            return Result.success(state
                .withTenant(saved)
                .withCompensation(() -> {
                    log.debug("Compensation: deleting tenant {}", saved.id());
                    state.tenantRepository().delete(saved.id());
                    return Result.success(null);
                }));

        } catch (Exception e) {
            log.error("Failed to persist tenant", e);
            return Result.failure(DATABASE_ERROR, "Failed to save tenant: " + e.getMessage(), e);
        }
    }

    /**
     * Update organization in Keycloak.
     * If saga fails after this, we need to undo the Keycloak update.
     */
    public static Result<UpdateTenantAggregator> updateOrganizationInIAM(UpdateTenantAggregator state) {
        try {
            var organizationIAM = state.organizationIAM();
            var organization = state.tenant().organization();

            // Update Keycloak
            var updated = organizationIAM.updateOrganization(organization);
            log.debug("Organization updated in Keycloak: {}", updated.id());

            // Register compensation: if saga fails, delete from Keycloak
            return Result.success(state
                .withCompensation(() -> {
                    log.debug("Compensation: deleting organization {} from Keycloak", organization.id());
                    organizationIAM.deleteOrganization(organization.id());
                    return Result.success(null);
                }));

        } catch (KeycloakException e) {
            log.error("Failed to update organization in Keycloak", e);
            return Result.failure(EXTERNAL_SERVICE_ERROR,
                "Keycloak update failed: " + e.getMessage(), e);
        }
    }

    // Other stages...
    public static Result<UpdateTenantAggregator> validateTenantExists(UpdateTenantAggregator state) {
        // No compensation needed - just validation
        return state.tenantRepository().findById(state.command().tenantId())
            .map(existing -> state.withExistingTenant(existing))
            .orElseGet(() -> Result.failure(NOT_FOUND, "Tenant not found", null));
    }
}
```

**Pattern:**
- Do the operation (persist, update)
- Register what to do if saga fails
- Return `Result.success(state.withCompensation(...))`
- If operation fails, return `Result.failure(...)`

### Step 3: Use SagaExecutionContext in Handler

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateTenantHandler {

    private final TenantRepository tenantRepository;
    private final OrganizationIAM organizationIAM;
    private final SagaExecutionContext sagaContext;  // ← Inject saga context

    public Result<UpdateTenantResult> handle(UpdateTenantCommand command) {
        log.debug("Handling UpdateTenant command for tenant ID: {}", command.tenantId());

        return Result.success(UpdateTenantAggregator.initialize(
                tenantRepository,
                organizationIAM,
                command,
                new ArrayList<>()  // ← Initialize empty compensations list
            ))
            .flatMap(UpdateTenantStages::validateTenantExists)
            .flatMap(UpdateTenantStages::buildDomain)
            .flatMap(UpdateTenantStages::checkAliasUnique)
            .within(sagaContext)  // ← Saga execution starts here
            .flatMap(UpdateTenantStages::persist)  // Registers: delete tenant
            .flatMap(UpdateTenantStages::updateOrganizationInIAM)  // Registers: delete from Keycloak
            .flatMap(UpdateTenantStages::buildResult)
            .peek(result -> log.info("Successfully updated tenant: {} ({})",
                    result.tenantName(), result.tenantId()))
            .peekFailure(error -> log.warn("Failed to update tenant (compensations executed): {}",
                    error.message()));
    }
}
```

**Execution flow:**

```
┌─────────────────────────────────────────────────────┐
│ validateTenantExists()                              │
│ buildDomain()                                       │
│ checkAliasUnique()                                  │
│ (No compensations registered - just validation)     │
└──────────────── ↓ ───────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│ SAGA CONTEXT STARTS (.within(sagaContext))           │
├──────────────────────────────────────────────────────┤
│ persist(state)                                       │
│ ↓ registers: "delete tenant from DB"                │
│ ↓ compensation stack: [delete tenant]               │
│                                                      │
│ updateOrganizationInIAM(state)                       │
│ ↓ registers: "delete org from Keycloak"             │
│ ↓ compensation stack: [delete tenant, delete org]   │
│                                                      │
│ buildResult(state)                                  │
│ ↓ Success!                                           │
│ ↓ Compensation stack cleared (saga succeeded)       │
├──────────────────────────────────────────────────────┤
│ SAGA CONTEXT ENDS                                    │
└──────────────────────────────────────────────────────┘

If updateOrganizationInIAM fails:
┌──────────────────────────────────────────────────────┐
│ SAGA COMPENSATION PHASE (reverse order)              │
├──────────────────────────────────────────────────────┤
│ 1. Execute: "delete org from Keycloak"              │
│ 2. Execute: "delete tenant from DB"                 │
│                                                      │
│ Result: Both systems rolled back to before saga     │
└──────────────────────────────────────────────────────┘
```

### Step 4: Configure SagaExecutionContext Bean

```java
@Configuration
@RequiredArgsConstructor
public class ExecutionContextConfiguration {

    private final PlatformTransactionManager txManager;

    /**
     * Create a SagaExecutionContext that executes within a database transaction.
     *
     * This means:
     * - Database operations are atomic (within txContext)
     * - Compensations only run if saga fails
     * - Keycloak updates can be compensated
     */
    @Bean
    public SagaExecutionContext sagaExecutionContext() {
        var txContext = TransactionExecutionContext.of(txManager);
        return new SagaExecutionContext(txContext);
    }

    /**
     * Create a SagaExecutionContext with logging.
     * Useful for auditing distributed transactions.
     */
    @Bean("sagaWithLogging")
    public ExecutionContext sagaWithLogging() {
        return SagaExecutionContext.withLogging(
            TransactionExecutionContext.of(txManager)
        );
    }
}
```

## Real-World Example: Complete Scenario

**Scenario:** Update tenant with three operations:
1. Validate tenant exists
2. Update database
3. Update Keycloak organization
4. Publish event (non-critical)

```java
@Service
@RequiredArgsConstructor
public class UpdateTenantHandler {

    private final TenantRepository repository;
    private final OrganizationIAM organizationIAM;
    private final EventPublisher eventPublisher;
    private final SagaExecutionContext sagaContext;

    public Result<UpdateTenantResult> handle(UpdateTenantCommand command) {
        return Result.success(UpdateTenantAggregator.initialize(
                repository, organizationIAM, command, new ArrayList<>()))
            // OUTSIDE saga: just validation
            .flatMap(UpdateTenantStages::validateTenantExists)
            .flatMap(UpdateTenantStages::buildDomain)
            .within(sagaContext)  // ← SAGA STARTS
            // INSIDE saga: critical operations
            .flatMap(UpdateTenantStages::persist)  // + compensation
            .flatMap(UpdateTenantStages::updateOrganizationInIAM)  // + compensation
            // INSIDE saga: non-critical operations
            .flatMap(state -> {
                // Event publishing failure is not fatal
                try {
                    eventPublisher.publish(new TenantUpdatedEvent(state.tenant()));
                    log.info("Event published");
                } catch (Exception e) {
                    log.warn("Event publishing failed (non-fatal)", e);
                }
                return Result.success(state);
            })
            .flatMap(UpdateTenantStages::buildResult);
    }
}

@Slf4j
public final class UpdateTenantStages {

    public static Result<UpdateTenantAggregator> persist(UpdateTenantAggregator state) {
        var saved = repository.save(state.tenant());
        log.debug("Persisted, registering compensation");

        return Result.success(state
            .withTenant(saved)
            .withCompensation(() -> {
                log.debug("COMPENSATION: Deleting tenant {}", saved.id());
                repository.delete(saved.id());
                return Result.success(null);
            }));
    }

    public static Result<UpdateTenantAggregator> updateOrganizationInIAM(UpdateTenantAggregator state) {
        var org = state.tenant().organization();
        var updated = organizationIAM.update(org);
        log.debug("Updated Keycloak, registering compensation");

        return Result.success(state
            .withCompensation(() -> {
                log.debug("COMPENSATION: Deleting organization {} from Keycloak", org.id());
                organizationIAM.delete(org.id());
                return Result.success(null);
            }));
    }
}
```

**Execution scenarios:**

**✅ Happy path (all succeed):**
```
persist() → OK, register compensation
updateOrganizationInIAM() → OK, register compensation
publishEvent() → OK
buildResult() → OK
Result.success(UpdateTenantResult)
Compensations cleared (saga succeeded)
```

**❌ Keycloak fails:**
```
persist() → OK, register compensation
updateOrganizationInIAM() → FAILS, return Result.failure()
SagaExecutionContext catches failure:
  1. Execute compensation: delete from Keycloak (does nothing, wasn't created)
  2. Execute compensation: delete from database
Result.failure(EXTERNAL_SERVICE_ERROR, "Keycloak update failed")
Database is rolled back
```

**❌ Database fails:**
```
persist() → FAILS, return Result.failure()
SagaExecutionContext catches failure:
  No compensations registered yet
Result.failure(DATABASE_ERROR, "Failed to save tenant")
```

## Key Design Principles

### 1. **Compensations are Functional**

Each compensation is a pure function (from caller's perspective):
```java
Supplier<Result<Void>> compensation = () -> {
    // Undo the operation
    // Return success or failure
    return Result.success(null);
};
```

### 2. **LIFO Execution (Stack-Based)**

Compensations execute in reverse order (Last In, First Out):
```
Register: [operation1, operation2, operation3]
If fails:
Execute: operation3, operation2, operation1  ← Reverse order
```

### 3. **Failures Don't Stop Compensations**

If one compensation fails, others still execute:
```java
compensations.forEach(comp -> {
    try {
        comp.get();  // Execute even if others failed
    } catch (Exception e) {
        log.error("Compensation failed, continuing...");
    }
});
```

### 4. **Separation of Concerns**

- **Before saga:** Validation, business rules
- **Inside saga:** Database writes, external API calls
- **Compensations:** Undo operations

## Anti-Patterns to Avoid

### ❌ Registering Compensation for Read Operations

```java
// ❌ WRONG - reads don't need compensation
public static Result<State> query(State state) {
    var data = repository.query();
    return Result.success(state
        .withCompensation(() -> repository.query())  // ← Unnecessary
    );
}
```

### ❌ Registering Compensation in Validation Stages

```java
// ❌ WRONG - validation has no side effects
public static Result<State> validate(State state) {
    if (state.isInvalid()) {
        return Result.failure(...);
    }
    return Result.success(state);  // ← No compensation needed
}
```

### ❌ Compensation That Throws Exceptions

```java
// ❌ WRONG - compensation should not throw
.withCompensation(() -> {
    throw new Exception("Undo failed");  // ← Breaks saga
})

// ✅ CORRECT - catch and wrap
.withCompensation(() -> {
    try {
        organizationIAM.delete(id);
        return Result.success(null);
    } catch (Exception e) {
        log.error("Compensation failed", e);
        return Result.failure(...);  // ← Return failure, don't throw
    }
})
```

### ❌ Compensations That Depend on External State

```java
// ❌ WRONG - compensation can't assume state hasn't changed
.withCompensation(() -> {
    organizationIAM.delete(orgId);  // What if deleted by someone else?
    return Result.success(null);
})

// ✅ CORRECT - idempotent compensation
.withCompensation(() -> {
    try {
        organizationIAM.delete(orgId);
    } catch (NotFoundException e) {
        // Already deleted, that's fine
        log.debug("Organization already deleted");
    }
    return Result.success(null);
})
```

## Testing Sagas

### Test 1: Happy Path (All Succeed)

```java
@Test
void handleSuccessfully() {
    var command = validUpdateCommand();
    var result = handler.handle(command);

    assertTrue(result.isSuccess());
    // Verify both DB and Keycloak were updated
    assertThat(repository.findById(id)).isPresent();
    assertThat(keycloak.getOrganization(id)).isNotNull();
}
```

### Test 2: Keycloak Fails (Compensation Executes)

```java
@Test
void keycloakFailureTriggersCompensation() {
    // Arrange: Keycloak will fail
    when(organizationIAM.update(any())).thenThrow(KeycloakException.class);

    // Act
    var result = handler.handle(validUpdateCommand());

    // Assert
    assertTrue(result.isFailure());
    assertEquals(EXTERNAL_SERVICE_ERROR, result.failure().code());

    // Verify database was rolled back (compensation executed)
    assertThat(repository.count()).isEqualTo(countBefore);
}
```

### Test 3: Compensation Failure Doesn't Stop Other Compensations

```java
@Test
void compensationFailureDoesNotStopOthers() {
    // Arrange: Keycloak delete will fail, but DB delete should still try
    when(organizationIAM.deleteOrganization(any()))
        .thenThrow(RuntimeException.class);

    // Act
    var result = handler.handle(commandThatFailsOnKeycloak());

    // Assert
    assertTrue(result.isFailure());

    // Verify DB compensation was still attempted
    verify(repository, times(1)).delete(any());
}
```

## Comparing Saga vs Other Patterns

| Pattern | Consistency | Complexity | Rollback | Best For |
|---------|------------|-----------|---------|----------|
| **Saga** | Strong | Medium | Automatic | Multi-system updates (DB + API) |
| **Outbox** | Eventual | Low | Retry-based | Async event propagation |
| **DB Txn Only** | Strong | Low | ACID | Single database |
| **Best Effort** | Weak | Very Low | Manual | Non-critical operations |

**Use Saga when:**
- ✅ You need strong consistency across multiple systems
- ✅ Operations must all succeed or all rollback
- ✅ Synchronous execution is acceptable
- ✅ Compensation operations are deterministic

**Use Outbox when:**
- ✅ Eventual consistency is acceptable
- ✅ You want eventual consistency without tight coupling
- ✅ High throughput is critical
- ✅ Async propagation is desired

## Summary

**Saga Pattern in FP/ROP:**
- Pure functions describe operations
- Compensations are registered, not executed immediately
- If any operation fails, compensations execute in reverse
- Strong consistency across distributed systems
- Fully functional and composable

**This is the FP way to handle distributed transactions.**
