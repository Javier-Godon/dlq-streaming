package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;

import java.util.function.Supplier;

/**
 * SagaAggregator is a marker interface for aggregators that support saga compensations.
 *
 * Aggregators implementing this interface can track and execute compensation operations
 * as part of the saga pattern for distributed transactions.
 *
 * According to ROP Golden Rule: compensations must return meaningful output (IDs, aggregates),
 * never Result<Void>. This maintains functional composition and traceability.
 *
 * Implementation Pattern:
 * Implement this interface in your aggregator to enable compensation tracking:
 *
 * <pre>
 * @Builder
 * @With
 * public record UpdateTenantAggregator(
 *         TenantRepository tenantRepository,
 *         OrganizationIAM organizationIAM,
 *         UpdateTenantCommand command,
 *         Tenant tenant,
 *         List<Supplier<Result<?>>> compensations  // ← Generic wildcard, not Result<Void>
 * ) implements SagaAggregator {
 *
 *     public UpdateTenantAggregator withCompensation(Supplier<Result<?>> compensation) {
 *         var updated = new ArrayList<>(compensations);
 *         updated.add(compensation);
 *         return this.withCompensations(updated);
 *     }
 * }
 * </pre>
 *
 * Usage in Stages:
 * <pre>
 * public static Result<UpdateTenantAggregator> persist(UpdateTenantAggregator state) {
 *     var saved = repository.save(state.tenant());
 *
 *     // Register compensation: if saga fails, re-create the tenant
 *     return Result.success(state
 *         .withTenant(saved)
 *         .withCompensation(() -> {
 *             return repository.save(state.tenant());  // Returns Result<TenantId>
 *         }));
 * }
 *
 * public static Result<UpdateTenantAggregator> updateKeycloak(UpdateTenantAggregator state) {
 *     var updated = organizationIAM.update(state.tenant().organization());
 *
 *     // Register compensation: if saga fails, re-create organization
 *     return Result.success(state
 *         .withCompensation(() -> {
 *             return organizationIAM.create(state.tenant().organization());  // Returns Result<UUID>
 *         }));
 * }
 * </pre>
 *
 * Handler Usage:
 * <pre>
 * @Service
 * @RequiredArgsConstructor
 * public class UpdateTenantHandler {
 *
 *     private final SagaExecutionContext sagaContext;
 *
 *     public Result<UpdateTenantResult> handle(UpdateTenantCommand command) {
 *         return Result.pipeline(UpdateTenantAggregator.initialize(...))
 *             .flatMap(UpdateTenantStages::validateTenantExists)
 *             .flatMap(UpdateTenantStages::buildDomain)
 *             .flatMap(UpdateTenantStages::persist)  // Registers compensation
 *             .flatMap(UpdateTenantStages::updateKeycloak)  // Registers compensation
 *             .within(sagaContext)  // ← Saga starts here; all stages above are deferred
 *             // If updateKeycloak fails:
 *             // 1. Undo Keycloak update (compensation)
 *             // 2. Undo database persist (compensation)
 *             .flatMap(UpdateTenantStages::buildResult);
 *     }
 * }
 * </pre>
 *
 * Compensation Execution Order (LIFO - Last In First Out):
 * <pre>
 * persist() registers: "undo persist"
 * updateKeycloak() registers: "undo Keycloak"
 *
 * If updateKeycloak fails, execute:
 * 1. "undo Keycloak" (registered last, executed first)
 * 2. "undo persist" (registered first, executed last)
 * </pre>
 *
 * @see SagaExecutionContext for the context that manages compensations
 */
public interface SagaAggregator {

    /**
     * Add a compensation operation to execute if the saga fails.
     *
     * According to ROP Golden Rule: compensation returns meaningful output (ID, aggregate),
     * never Result<Void>. This maintains functional composition and traceability.
     *
     * @param compensation a function that undoes what the current operation did, returning meaningful output
     * @return a new aggregator instance with the compensation registered
     */
    SagaAggregator withCompensation(Supplier<Result<?>> compensation);
}
