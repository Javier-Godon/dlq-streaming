---
applyTo: "**/shared_kernel/**,**/internal/**,**/events/**,**/integration/**"
---

# Cross-Context Integration Instructions

Use these rules when bounded contexts/modules communicate.

## Decision guide

```text
Does the caller need a Result<T> answer to continue its pipeline?
  Yes -> Tier 1 synchronous internal interface
  No  -> Is the publisher valid even if the consumer fails?
          Yes -> Tier 2 post-commit/outbox domain event
          No  -> Tier 1 synchronous internal interface
```

## Tier 1: synchronous internal interface

- Interface lives in a shared/internal API package, not in the caller or target domain package.
- Return `Result<T>` with a meaningful DTO, never domain objects.
- Caller depends on the interface only.
- Target context implements the interface by delegating to its handler.

```java
public interface ProductLookup {
    Result<ProductLookupResponse> getProduct(UUID tenantId, UUID productId);
}

public record ProductLookupResponse(UUID productId, String sku, String name) { }
```

## Tier 2: post-commit/outbox event

- Publish after `Result.pipeline(...).within(txContext)` or from a committed outbox row.
- Event is lean: IDs, source facts, minimal immutable context.
- Listener is an adapter: event -> command/query -> handler.
- Listener catches/logs unexpected exceptions and never propagates them to the publisher.
- Do not use `@TransactionalEventListener` or listener-level `@Transactional` boundaries unless the target project has explicitly designed and tested that policy.

```java
@EventListener
public void onThingCreated(ThingCreatedEvent event) {
    try {
        handler.handle(new ReactToThingCommand(event.tenantId().toString(), event.thingId().toString()))
            .peekFailure(f -> log.warn("Failed to react to thing {}: {}", event.thingId(), f.message()));
    } catch (RuntimeException e) {
        log.error("Unexpected listener failure for thing {}", event.thingId(), e);
    }
}
```

## Snapshot pattern

When a context needs historical accuracy, store immutable snapshot scalars at creation time. Do not query another context at read time for historical fields.

## Forbidden

- Direct imports from another context's domain package.
- Fat events containing full aggregates.
- Event publication inside an uncommitted transaction unless it is persisted as outbox data.
- Listener exceptions escaping to publisher.
- External brokers for in-process modular-monolith flows unless explicitly required and tested.

