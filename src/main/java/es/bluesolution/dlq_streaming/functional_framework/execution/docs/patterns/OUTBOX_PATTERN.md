# Outbox Pattern: Reliable Event Publishing

## 🎯 Purpose

The Outbox pattern solves the distributed systems problem: **How do we guarantee reliable event publishing after a database operation?**

### Problem Without Outbox

```java
// ❌ WRONG - Race condition problem
public Result<CreateTenantResult> handle(CreateTenantCommand cmd) {
    // 1. Save tenant to database
    tenantRepository.save(tenant);
    
    // 2. Publish event
    eventPublisher.publish(TenantCreatedEvent);  // ← What if this fails after DB commit?
    // Database has new tenant, but event never published
    // Other services never know tenant was created
    
    return Result.success(result);
}
```

**The Race Condition:**
- Database commit succeeds ✅
- Event publishing fails ❌
- **Result:** Inconsistency - database has data, but other systems don't know

### Solution: Outbox Table

```
BEFORE COMMIT:
┌─────────────────────────────────┐
│ tenants table                   │  ← New row added
│ ┌─────────────────────────────┐ │
│ │ Tenant ID: uuid-123         │ │
│ │ Name: "Acme Corp"           │ │
│ └─────────────────────────────┘ │
│                                 │
│ outbox table                    │  ← Event row added
│ ┌─────────────────────────────┐ │
│ │ Event: TenantCreatedEvent   │ │
│ │ Aggregate: Tenant(uuid-123) │ │
│ │ Published: false            │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
       ↓
    COMMIT (atomic)
       ↓
AFTER COMMIT (both persisted atomically):
┌─────────────────────────────────┐
│ tenants table                   │  ✅ Persisted
│ outbox table (published=false)  │  ✅ Persisted
└─────────────────────────────────┘
       ↓
ASYNC PUBLISHING:
┌─────────────────────────────────┐
│ Background process polls outbox │
│ For each unpublished event:     │
│   1. Publish to Kafka/RabbitMQ  │
│   2. Update outbox published=true
│ (separate transaction)          │
└─────────────────────────────────┘
```

## 🏗️ Architecture

### Components

```
Handler
  ↓
Stage 1: Business Logic (pure function)
  ↓
Stage 2: Persist to Database (within transaction)
  ├─ Save domain entity
  └─ Register event to outbox
  ↓
Stage 3: Build Result
  ↓
[TRANSACTION COMMITS - Database + Outbox persisted atomically]
  ↓
[ASYNC PUBLISHING - Outside transaction]
```

### Outbox Entry Structure

```java
record OutboxEntry(
    String aggregateType,      // "Tenant", "Order", "Customer"
    String aggregateId,        // UUID of aggregate root
    String eventType,          // "TenantCreatedEvent", "OrderShipped"
    String payload,            // JSON serialized event
    String topic               // "tenant-events", "order-events"
)
```

## 🔄 Execution Flow

### Happy Path (Outbox Pattern)

```
1. Receive CreateTenantCommand
   ↓
2. Validate input
   ↓
3. Build Tenant domain object
   ↓
4. [Within OutboxExecutionContext - transaction starts]
   ├─ 4a. Persist Tenant to database
   ├─ 4b. Register TenantCreatedEvent to outbox table
   │      └─ event waits with published=false flag
   └─ 4c. [COMMIT - both persist atomically]
   ↓
5. [Build result]
   ↓
6. [Async publishing - separate job polls outbox]
   ├─ Find unpublished events
   ├─ Publish to Kafka/RabbitMQ
   └─ Update outbox published=true
   ↓
RESULT: Strong durability + eventual consistency
```

### Failure Path (Database persists, async fails)

```
1-4. [Database + outbox committed]
   ↓
5. Async publishing fails (network error, Kafka down)
   ├─ Event stays in outbox with published=false
   ├─ Retry logic kicks in
   ├─ Next polling cycle tries again (or manual retry)
   └─ Eventually succeeds
   ↓
RESULT: Event never lost, eventual delivery guaranteed
```

## 💻 Implementation Pattern

### Step 1: Aggregator Implements OutboxAggregator

```java
@Builder
@With
public record CreateTenantAggregator(
    TenantRepository repository,
    EventPublisher eventPublisher,
    CreateTenantCommand command,
    Tenant tenant,
    TenantId persistedId,
    List<Supplier<Result<OutboxAggregator.OutboxEntry>>> outboxEntries
) implements OutboxAggregator {

    public static CreateTenantAggregator initialize(
            TenantRepository repository,
            EventPublisher eventPublisher,
            CreateTenantCommand command) {
        return CreateTenantAggregator.builder()
                .repository(repository)
                .eventPublisher(eventPublisher)
                .command(command)
                .outboxEntries(new ArrayList<>())
                .build();
    }

    @Override
    public CreateTenantAggregator withOutboxEntry(
            Supplier<Result<OutboxAggregator.OutboxEntry>> outboxEntry) {
        var updated = new ArrayList<>(this.outboxEntries);
        updated.add(outboxEntry);
        return this.withOutboxEntries(updated);
    }
}
```

### Step 2: Persist Stage Registers Event

```java
public static Result<CreateTenantAggregator> persist(CreateTenantAggregator aggregator) {
    // Persist tenant
    return aggregator.repository()
            .save(aggregator.tenant())
            .flatMap(tenantId -> {
                log.debug("Tenant persisted, registering outbox event");
                
                // Register event to outbox
                return Result.success(aggregator.withPersistedId(tenantId)
                        .withOutboxEntry(() -> {
                            // Create event to be published
                            var event = new TenantCreatedEvent(
                                    aggregateType: "Tenant",
                                    aggregateId: tenantId.value().toString(),
                                    eventType: "TenantCreatedEvent",
                                    payload: serializeEvent(aggregator.tenant()),
                                    topic: "tenant-events"
                            );
                            return Result.success(event);
                        }));
            });
}
```

### Step 3: Handler Publishes Events Asynchronously

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateTenantHandler {

    private final OutboxExecutionContext outboxContext;
    private final TenantRepository repository;
    private final OutboxEventPublisher eventPublisher;

    public Result<CreateTenantResult> handle(CreateTenantCommand command) {
        log.debug("Creating tenant: {}", command.name());

        return Result.success(CreateTenantAggregator.initialize(repository, eventPublisher, command))
                .flatMap(CreateTenantStages::validate)
                .within(outboxContext)  // ← Outbox tracking starts
                .flatMap(CreateTenantStages::buildDomain)
                .flatMap(CreateTenantStages::persist)  // Registers outbox event
                .flatMap(CreateTenantStages::buildResult)
                .peek(result -> {
                    // Publish events asynchronously after transaction commits
                    publishOutboxEventsAsync(outboxContext.getOutboxEntries());
                    outboxContext.cleanupOutbox();  // Clear ThreadLocal
                })
                .peekFailure(error -> log.warn("Failed to create tenant: {}", error.message()));
    }

    private void publishOutboxEventsAsync(
            List<Supplier<Result<OutboxAggregator.OutboxEntry>>> entries) {
        // Run in async task (thread pool, async servlet, etc.)
        asyncExecutor.execute(() -> {
            entries.forEach(entrySupplier -> {
                entrySupplier.get()
                        .flatMap(eventPublisher::publish)
                        .peek(_ -> log.info("Event published: {}", eventSupplier.get().eventType()))
                        .peekFailure(error -> log.warn("Failed to publish event: {}", error.message()));
            });
        });
    }
}
```

## 📊 Comparison: Outbox vs Alternatives

| Pattern | Consistency | Event Loss Risk | Complexity | Best For |
|---------|-------------|-----------------|-----------|----------|
| **Outbox** | Eventual | None (durable) | Medium | Event-driven, non-critical events |
| **Saga** | Strong | N/A | High | Distributed transactions, critical operations |
| **Direct Publish** | Eventual | High | Low | Testing, non-critical events |
| **Event Sourcing** | Event-based | None | Very High | Audit trail, complete history needed |

**Key Differences from Saga:**
- **Saga:** Compensations for ROLLBACK (strong consistency)
- **Outbox:** Events for PUBLISH (eventual consistency)

### When to Use Outbox:
✅ Domain events (TenantCreated, OrderShipped)
✅ Non-critical notifications
✅ Asynchronous processing
✅ Event-driven architecture
✅ Need eventual consistency

### When NOT to Use Outbox:
❌ Distributed transactions (use Saga)
❌ Immediate consistency required
❌ Real-time guarantees needed
❌ Complex compensations

## 🧪 Testing Strategies

### Test 1: Outbox Entry Registered

```java
@Test
void testOutboxEventRegisteredAfterPersist() {
    // Given: Valid command, mocked repository and outbox context
    when(repository.save(any())).thenReturn(Result.success(tenantId));
    
    // When
    var result = handler.handle(command).within(outboxContext);
    
    // Then
    assertTrue(result.isSuccess());
    assertEquals(1, outboxContext.getOutboxEntryCount());
    
    var entry = outboxContext.getOutboxEntries().get(0).get().get();
    assertEquals("TenantCreatedEvent", entry.eventType());
    assertEquals(tenantId.value().toString(), entry.aggregateId());
}
```

### Test 2: Async Publishing After Commit

```java
@Test
void testAsyncPublishingAfterTransaction() {
    // Given: Transaction succeeds, outbox has event
    var result = handler.handle(command).within(outboxContext);
    
    // When: Simulate async publishing
    var entries = outboxContext.getOutboxEntries();
    entries.forEach(entrySupplier -> {
        var event = entrySupplier.get().get();
        eventPublisher.publish(event);
    });
    
    // Then: Event was published
    verify(eventPublisher).publish(any(OutboxAggregator.OutboxEntry.class));
}
```

### Test 3: Event Not Lost on Publishing Failure

```java
@Test
void testEventNotLostOnPublishingFailure() {
    // Given: Publishing fails
    when(eventPublisher.publish(any())).thenReturn(
        Result.failure(new Error("Network error"))
    );
    
    // When: Try to publish outbox event
    var entry = outboxContext.getOutboxEntries().get(0).get().get();
    var publishResult = eventPublisher.publish(entry);
    
    // Then: Event still in outbox, can retry
    assertTrue(publishResult.isFailure());
    assertEquals(1, outboxContext.getOutboxEntryCount());  // Still there
    
    // Can retry later
    when(eventPublisher.publish(any())).thenReturn(Result.success(null));
    var retryResult = eventPublisher.publish(entry);
    assertTrue(retryResult.isSuccess());
}
```

## 🛡️ Best Practices

### 1. **Serialize Events Carefully**
```java
// ✅ GOOD - Explicit serialization
private String serializeEvent(Tenant tenant) {
    return objectMapper.writeValueAsString(
        Map.of(
            "id", tenant.id().value(),
            "name", tenant.name().value(),
            "createdAt", Instant.now()
        )
    );
}

// ❌ BAD - Implicit serialization
private String serializeEvent(Tenant tenant) {
    return tenant.toString();  // Fragile
}
```

### 2. **Use Separate Transaction for Publishing**
```java
// ✅ GOOD - Separate transaction
@Transactional(propagation = Propagation.REQUIRES_NEW)
public Result<Void> publishOutboxEvent(OutboxEntry entry) {
    // Publish event
    return eventPublisher.publish(entry)
            .flatMap(_ -> markAsPublished(entry));
}

// ❌ BAD - Same transaction
public void publishWithoutNewTransaction(OutboxEntry entry) {
    eventPublisher.publish(entry);  // If fails, rolls back DB changes too
    markAsPublished(entry);
}
```

### 3. **Implement Polling Correctly**
```java
// ✅ GOOD - Handles partial failures
@Scheduled(fixedRate = 5000)
public void pollAndPublishOutbox() {
    List<OutboxEntry> entries = outboxRepository.findUnpublished();
    
    entries.forEach(entry -> {
        try {
            eventPublisher.publish(entry);
            outboxRepository.markPublished(entry.id());
        } catch (Exception e) {
            log.warn("Failed to publish event {}, will retry", entry.id(), e);
            // Leave unpublished - will retry next cycle
        }
    });
}
```

### 4. **Handle Cleanup Carefully**
```java
// ✅ GOOD - Cleanup in finally block
public Result<T> handle(Command cmd) {
    try {
        return pipeline.within(outboxContext)
                .peek(_ -> publishOutboxEventsAsync(outboxContext.getOutboxEntries()));
    } finally {
        outboxContext.cleanupOutbox();  // Always cleanup
    }
}
```

## 📈 Monitoring & Metrics

### What to Monitor

```java
// 1. Outbox size (unpublished events)
metrics.gauge("outbox.unpublished.count", 
    () -> outboxRepository.countUnpublished());

// 2. Publishing latency
var startTime = Instant.now();
eventPublisher.publish(event);
var latency = Duration.between(startTime, Instant.now());
metrics.timer("outbox.publish.latency").record(latency);

// 3. Publishing failures
try {
    eventPublisher.publish(event);
} catch (Exception e) {
    metrics.counter("outbox.publish.failures").increment();
}

// 4. Events published per second
metrics.counter("outbox.events.published").increment();
```

## 📚 Related Patterns

- **Saga Pattern:** Compensations for strong consistency
- **Event Sourcing:** Complete event history
- **CQRS:** Separate read/write models
- **Change Data Capture (CDC):** Database-level event capture

## 🎓 Key Learnings

1. **Durability First:** Always persist before publishing
2. **Separate Transactions:** Publishing in different transaction than persistence
3. **Retry Logic:** Implement polling for failed publishes
4. **Idempotent Publishing:** Events should be safely re-published
5. **Monitoring:** Track unpublished events to catch failures early

## ✅ Checklist for Implementation

- [ ] Create OutboxAggregator implementation
- [ ] Add outboxEntries field to aggregator
- [ ] Implement withOutboxEntry() method
- [ ] Update persist stage to register events
- [ ] Create OutboxEntry record with all fields
- [ ] Implement event serialization
- [ ] Add async publishing logic
- [ ] Create polling/background job
- [ ] Implement retry mechanism
- [ ] Add comprehensive monitoring
- [ ] Test happy path (event published)
- [ ] Test failure path (event not lost)
- [ ] Test cleanup (ThreadLocal cleared)

---

**Status:** ✅ Framework pattern ready for implementation
**Related Files:**
- OutboxExecutionContext.java - Core executor
- OutboxAggregator.java - Interface contract
- ExecutionContextConfiguration.java - Spring beans
