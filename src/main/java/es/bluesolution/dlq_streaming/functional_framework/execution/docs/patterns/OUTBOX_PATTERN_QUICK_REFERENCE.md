# Outbox Pattern - Quick Reference

## 📋 Implementation Checklist

### Phase 1: Setup (10 min)
- [ ] Aggregator implements OutboxAggregator
- [ ] Add `List<Supplier<Result<OutboxEntry>>> outboxEntries` field
- [ ] Implement `withOutboxEntry()` method
- [ ] Initialize outboxEntries in `initialize()` method
- [ ] Inject OutboxExecutionContext into handler

### Phase 2: Event Registration (5 min)
- [ ] Identify which stage persists (usually persist() or save())
- [ ] Change `.map()` to `.flatMap()`
- [ ] Wrap result in `Result.success(aggregator.withOutboxEntry(...))`
- [ ] Inside withOutboxEntry, create event supplier
- [ ] Serialize event to JSON payload

### Phase 3: Publishing (10 min)
- [ ] Create OutboxEventPublisher interface
- [ ] Implement async publishing logic
- [ ] Add `.peek()` to handler to trigger async publish
- [ ] Call outboxContext.cleanupOutbox() in finally block
- [ ] Implement retry/polling mechanism

### Phase 4: Testing (15 min)
- [ ] Test outbox entry registered after persist
- [ ] Test event not lost on publishing failure
- [ ] Test async publishing succeeds
- [ ] Test cleanup clears ThreadLocal

---

## 💻 Code Templates

### Aggregator Implementation
```java
@Builder
@With
public record {UseCase}Aggregator(
    {Repository} repository,
    {Command} command,
    {DomainObject} entity,
    TenantId persistedId,
    List<Supplier<Result<OutboxAggregator.OutboxEntry>>> outboxEntries
) implements OutboxAggregator {

    public static {UseCase}Aggregator initialize(...) {
        return {UseCase}Aggregator.builder()
                // ... other fields
                .outboxEntries(new ArrayList<>())
                .build();
    }

    @Override
    public {UseCase}Aggregator withOutboxEntry(
            Supplier<Result<OutboxAggregator.OutboxEntry>> entry) {
        var updated = new ArrayList<>(this.outboxEntries);
        updated.add(entry);
        return this.withOutboxEntries(updated);
    }
}
```

### Persist Stage with Event
```java
public static Result<{UseCase}Aggregator> persist({UseCase}Aggregator aggregator) {
    return aggregator.repository()
            .save(aggregator.entity())
            .flatMap(id -> {
                log.debug("Entity persisted, registering outbox event");
                return Result.success(aggregator.withPersistedId(id)
                        .withOutboxEntry(() -> {
                            var event = new OutboxAggregator.OutboxEntry(
                                    "EntityType",
                                    id.value().toString(),
                                    "EntityCreatedEvent",
                                    serializeEvent(aggregator.entity()),
                                    "entity-events"
                            );
                            return Result.success(event);
                        }));
            });
}

private static String serializeEvent(Entity entity) {
    return objectMapper.writeValueAsString(Map.of(
        "id", entity.id().value(),
        "name", entity.name().value()
    ));
}
```

### Handler with Async Publishing
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class {UseCase}Handler {

    private final OutboxExecutionContext outboxContext;
    private final {Repository} repository;
    private final OutboxEventPublisher eventPublisher;

    public Result<{UseCase}Result> handle({UseCase}Command command) {
        log.debug("Handling {}: {}", command.getClass().getSimpleName(), command);

        return Result.success({UseCase}Aggregator.initialize(repository, eventPublisher, command))
                .flatMap({UseCase}Stages::validate)
                .within(outboxContext)  // ← Outbox tracking
                .flatMap({UseCase}Stages::buildDomain)
                .flatMap({UseCase}Stages::persist)  // Registers event
                .flatMap({UseCase}Stages::buildResult)
                .peek(result -> publishOutboxEventsAsync())
                .peekFailure(error -> log.warn("Failed: {}", error.message()));
    }

    private void publishOutboxEventsAsync() {
        try {
            var entries = outboxContext.getOutboxEntries();
            asyncExecutor.execute(() -> {
                entries.forEach(entrySupplier -> {
                    entrySupplier.get()
                            .flatMap(eventPublisher::publish)
                            .peek(_ -> log.info("Event published"))
                            .peekFailure(e -> log.warn("Publish failed: {}", e.message()));
                });
            });
        } finally {
            outboxContext.cleanupOutbox();  // Always cleanup
        }
    }
}
```

---

## 🎯 Key Methods

### OutboxExecutionContext
```java
// Execute computation with outbox tracking
Result<T> execute(Supplier<Result<T>> computation)

// Register event for publishing
void registerOutboxEntry(Supplier<Result<OutboxEntry>> entry)

// Get all registered entries (for async publishing)
List<Supplier<Result<OutboxEntry>>> getOutboxEntries()

// Clean up ThreadLocal after publishing
void cleanupOutbox()

// Get count of pending entries
int getOutboxEntryCount()
```

### OutboxAggregator
```java
// Register event supplier
OutboxAggregator withOutboxEntry(Supplier<Result<OutboxEntry>> entry)

// Event data
record OutboxEntry(
    String aggregateType,
    String aggregateId,
    String eventType,
    String payload,
    String topic
)
```

---

## 🧪 Testing Patterns

### Test Outbox Entry Registration
```java
@Test
void testEventRegistered() {
    when(repository.save(any())).thenReturn(Result.success(id));
    
    var result = handler.handle(command).within(outboxContext);
    
    assertTrue(result.isSuccess());
    assertEquals(1, outboxContext.getOutboxEntryCount());
}
```

### Test Async Publishing
```java
@Test
void testAsyncPublishing() {
    var result = handler.handle(command).within(outboxContext);
    
    var entries = outboxContext.getOutboxEntries();
    entries.forEach(supplier -> {
        var entry = supplier.get().get();
        assertNotNull(entry.payload());
    });
}
```

### Test Publishing Failure Handled
```java
@Test
void testPublishingFailureNotLostEvent() {
    when(eventPublisher.publish(any()))
        .thenReturn(Result.failure(...));
    
    var entry = outboxContext.getOutboxEntries().get(0).get().get();
    var result = eventPublisher.publish(entry);
    
    assertTrue(result.isFailure());
    // Event still in outbox for retry
}
```

---

## ⚙️ Spring Injection

### Default (with transactions)
```java
@Service
@RequiredArgsConstructor
public class Handler {
    private final OutboxExecutionContext outboxContext;
}
```

### With Logging
```java
@Service
@RequiredArgsConstructor
public class Handler {
    @Qualifier("outboxWithLogging")
    private final ExecutionContext outboxContext;
}
```

---

## 🔄 Outbox Entry Lifecycle

```
1. CREATE (in aggregator)
   → Supplier<Result<OutboxEntry>>

2. REGISTER (in persist stage)
   → aggregator.withOutboxEntry(supplier)

3. STORE (in database)
   → outbox table: published=false

4. RETRIEVE (in polling job)
   → Find all unpublished entries

5. PUBLISH (async job)
   → Send to message broker

6. MARK (update outbox table)
   → Set published=true
```

---

## ⚠️ Common Pitfalls

### ❌ Forgetting to Cleanup
```java
// WRONG - ThreadLocal leak
handler.handle(command);
outboxContext.getOutboxEntries();  // Stale from previous request!
```

### ✅ Always Cleanup
```java
// CORRECT
try {
    publishOutboxEventsAsync();
} finally {
    outboxContext.cleanupOutbox();  // Always
}
```

---

### ❌ Publishing in Same Transaction
```java
// WRONG - If publish fails, DB changes roll back
return Result.success(aggregator)
    .flatMap(persist)
    .peek(result -> eventPublisher.publish(entry));  // In same TX
```

### ✅ Publish After Commit
```java
// CORRECT - Separate async transaction
return Result.success(aggregator)
    .flatMap(persist)
    .peek(result -> asyncPublish(getOutboxEntries()));  // After commit
```

---

### ❌ Not Serializing Events
```java
// WRONG - Fragile and hard to deserialize
"payload": "TenantCreatedEvent(...)"
```

### ✅ Proper JSON Serialization
```java
// CORRECT - Structured and parseable
"payload": "{\"id\":\"...\",\"name\":\"Acme\",\"createdAt\":\"2025-12-28T...\"}"
```

---

## 📊 Beans in ExecutionContextConfiguration

| Bean Name | Purpose |
|-----------|---------|
| `outboxExecutionContext` | Standard outbox with transactions |
| `outboxWithLogging` | Outbox with debug logging |

**Inject with:**
```java
@Qualifier("outboxWithLogging")
private ExecutionContext outboxContext;
```

---

## 📚 Related Documentation

- **OUTBOX_PATTERN.md** - Complete guide with examples
- **SAGA_PATTERN.md** - Saga pattern (strong consistency)
- **EXECUTION_CONTEXT_DEVELOPER_GUIDE.md** - All contexts
- **architecture.instructions.md** - ROP principles

---

## ✅ Quick Start (5 Steps)

1. **Aggregator:** Add `implements OutboxAggregator` + `outboxEntries` field
2. **Stage:** Change `.map()` → `.flatMap()` with `withOutboxEntry()`
3. **Handler:** Inject `OutboxExecutionContext`, add `.within()`
4. **Publishing:** Call async publish in `.peek()` + cleanup in finally
5. **Test:** Verify entry registered and event not lost

**Time to implement:** ~30 minutes
