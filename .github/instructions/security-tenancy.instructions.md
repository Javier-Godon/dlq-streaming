---
applyTo: "**/infrastructure/rest/**,**/*Controller.java,**/*Spec.java,**/*Repository*.java,**/*SpringRepository.java,**/domain/model/**"
---

# Security and Tenancy Instructions

Use this when code is tenant-scoped, user-scoped, role-protected, or exposed through REST.

## Tenant source

- Tenant ID comes from the authenticated security context/session, not from request body/path/query for tenant self-service endpoints.
- Platform/admin cross-tenant endpoints must be explicitly named, documented, and role-protected.
- Domain aggregates place `TenantId` first when tenant-scoped.
- Repositories filter by tenant for every tenant-scoped query.
- Unique constraints include `tenant_id` for tenant-scoped uniqueness.

## Controller rules

- Controller maps security context -> command/query.
- Controller does not trust request-provided tenant IDs.
- Every endpoint has authorization annotations or equivalent route-level policy.
- Spec includes 401/403 responses.
- Tests cover auth failure and tenant isolation where applicable.

## Repository rules

```java
Optional<ThingEntity> findByTenantIdAndThingId(UUID tenantId, UUID thingId);
boolean existsByTenantIdAndName(UUID tenantId, String name);
```

No unscoped `findById(id)` for tenant-owned data unless used only by controlled internal maintenance code and documented.

## Tests

- Tenant A creates data.
- Tenant B cannot read/update/delete it.
- Controller rejects missing/invalid auth.
- Admin endpoints require admin role and document cross-tenant behavior.

## Forbidden

- `tenantId` in tenant self-service request body.
- `tenantId` path variable for normal tenant user endpoints.
- Repository queries that omit tenant filters.
- Unique constraints missing tenant column.
- Tests that only check happy-path auth.

