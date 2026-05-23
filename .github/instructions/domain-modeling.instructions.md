---
applyTo: "**/domain/model/**,**/domain/repository/**"
---

# Domain Modeling Instructions

## Aggregate and entity rules

- Domain aggregate roots, domain entities, child entities, and domain policy records expose only:
  - value objects,
  - enums,
  - collections of domain types,
  - nullable composed value objects where a variant requires optional data.
- Do not expose raw `UUID`, `String`, `BigDecimal`, `int`, `long`, `boolean`, `LocalDate`, or `Instant` for business concepts.
- Use immutable records/classes.
- Use `Result<T> create(...)` factories for validation.
- Do not throw validation exceptions from constructors or compact constructors.
- For variants, use composition: discriminator enum + nullable composed VOs, not inheritance.

## ID value object pattern

```java
public record ThingId(UUID value) {
    public static Result<ThingId> create(@Nullable UUID value) {
        if (value == null) {
            return Result.failure(VALIDATION_ERROR, "ThingId is required", null);
        }
        return Result.success(new ThingId(value));
    }

    public static Result<ThingId> create(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "ThingId is required", null);
        }
        try {
            return Result.success(new ThingId(UUID.fromString(value)));
        } catch (IllegalArgumentException e) {
            return Result.failure(VALIDATION_ERROR, "ThingId must be a valid UUID", e);
        }
    }
}
```

## String value object pattern

```java
public record ThingName(String value) {
    public static Result<ThingName> create(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "ThingName is required", null);
        }
        var normalized = value.trim();
        if (normalized.length() > 200) {
            return Result.failure(VALIDATION_ERROR, "ThingName must not exceed 200 characters", null);
        }
        return Result.success(new ThingName(normalized));
    }
}
```

## Aggregate pattern

```java
public record Thing(
    TenantId tenantId,
    ThingId thingId,
    ThingName name,
    ThingStatus status,
    ThingType type,
    @Nullable BusinessProfile businessProfile
) {
    public static Result<Thing> create(
            @Nullable TenantId tenantId,
            @Nullable ThingId thingId,
            @Nullable ThingName name,
            @Nullable ThingStatus status,
            @Nullable ThingType type,
            @Nullable BusinessProfile businessProfile) {
        if (tenantId == null) return Result.failure(VALIDATION_ERROR, "TenantId is required", null);
        if (thingId == null) return Result.failure(VALIDATION_ERROR, "ThingId is required", null);
        if (name == null) return Result.failure(VALIDATION_ERROR, "ThingName is required", null);
        if (status == null) return Result.failure(VALIDATION_ERROR, "ThingStatus is required", null);
        if (type == null) return Result.failure(VALIDATION_ERROR, "ThingType is required", null);
        if (type == ThingType.BUSINESS && businessProfile == null) {
            return Result.failure(BUSINESS_RULE_ERROR, "Business things require a business profile", null);
        }
        if (type != ThingType.BUSINESS && businessProfile != null) {
            return Result.failure(BUSINESS_RULE_ERROR, "Only business things can have a business profile", null);
        }
        return Result.success(new Thing(tenantId, thingId, name, status, type, businessProfile));
    }
}
```

## Repository interface rules

```java
public interface ThingRepository {
    Result<ThingId> save(Thing thing);
    Result<Thing> findByTenantAndId(TenantId tenantId, ThingId thingId);
    Result<List<Thing>> findByTenant(TenantId tenantId);
    Result<Boolean> existsByTenantAndId(TenantId tenantId, ThingId thingId);
    Result<ThingId> delete(TenantId tenantId, ThingId thingId);
}
```

- Repository interfaces live in domain.
- All methods return `Result<T>`.
- All tenant-scoped queries accept `TenantId`.

