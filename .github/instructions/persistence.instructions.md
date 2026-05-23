---
applyTo: "**/*Entity.java,**/*Repository*.java,**/*SpringRepository.java,**/persistence/**,**/db/migration/**"
---

# Persistence Instructions

## Flat JPA only

Forbidden in entity files:

```java
@JoinColumn
@JoinTable
@ManyToOne
@OneToMany
@OneToOne
@ManyToMany
```

Use scalar columns and plain FK IDs.

## Entity pattern

```java
@Entity
@Table(name = "thing", schema = "{context}")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThingEntity {
    @Id
    @Column(name = "thing_id", nullable = false)
    private UUID thingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ThingStatus status;

    @Column(name = "parent_thing_id")
    private UUID parentThingId; // plain FK, no relationship annotation

    public Thing toDomain() {
        return Thing.create(
            new TenantId(tenantId),
            new ThingId(thingId),
            new ThingName(name),
            status,
            ThingType.STANDARD,
            null
        ).value();
    }

    public static ThingEntity fromDomain(Thing thing) {
        return ThingEntity.builder()
            .tenantId(thing.tenantId().value())
            .thingId(thing.thingId().value())
            .name(thing.name().value())
            .status(thing.status())
            .build();
    }
}
```

Use `.value()` in trusted entity mapping only after domain values were already validated or loaded from trusted constraints. If data may be invalid, return `Result<T>` from mapper methods.

## Parent-child loading

- Parent entity `toDomain(...)` accepts children as parameters.
- Repository implementation loads children explicitly with child Spring Data repositories.
- Save parent and children explicitly.
- Delete or replace child rows explicitly, scoped by tenant and parent ID.

```java
private Thing toDomainWithLines(ThingEntity entity) {
    var lines = lineSpringRepository.findByTenantIdAndThingId(entity.getTenantId(), entity.getThingId())
        .stream()
        .map(ThingLineEntity::toDomain)
        .toList();
    return entity.toDomain(lines);
}
```

## Repository implementation

- Convert infrastructure exceptions to `Result.failure(DATABASE_ERROR, ...)`.
- Do not throw from repository methods.
- Do not annotate repository implementations with `@Transactional`.
- Return IDs for save/update/delete.
- Keep tenant filters in every query.

## Spring Data interface

`@Transactional` is allowed only for Spring Data methods with `@Modifying @Query`.

```java
public interface ThingSpringRepository extends JpaRepository<ThingEntity, UUID> {
    Optional<ThingEntity> findByTenantIdAndThingId(UUID tenantId, UUID thingId);
    boolean existsByTenantIdAndName(UUID tenantId, String name);

    @Modifying
    @Query("DELETE FROM ThingEntity t WHERE t.tenantId = :tenantId AND t.thingId = :thingId")
    @Transactional
    int deleteByTenantIdAndThingId(UUID tenantId, UUID thingId);
}
```

## Flyway rules

- One schema per bounded context/module if the target project uses schemas.
- Every tenant-scoped unique constraint includes `tenant_id`.
- Add indexes for `tenant_id` and parent IDs.
- Avoid cross-schema foreign keys across bounded contexts; store plain IDs.

```sql
CREATE SCHEMA IF NOT EXISTS {context};

CREATE TABLE {context}.thing (
    thing_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(50) NOT NULL,
    parent_thing_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_thing_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX idx_thing_tenant ON {context}.thing(tenant_id);
CREATE INDEX idx_thing_parent ON {context}.thing(parent_thing_id);
```


