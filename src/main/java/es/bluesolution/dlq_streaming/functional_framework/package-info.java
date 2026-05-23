/**
 * Functional ROP framework primitives.
 *
 * <p>This package owns the reusable railway-oriented/functional building blocks used
 * by all BCs: {@link es.bluesolution.dlq_streaming.functional_framework.Result},
 * {@link es.bluesolution.dlq_streaming.functional_framework.ResultPipeline},
 * failure descriptions, HTTP response mapping helpers, and execution-context
 * integration under {@code functional_framework.execution}.</p>
 *
 * <p>Boundary rule: keep this package business-free. Do not add use cases,
 * aggregates, BC-specific policies, legal-ledger workflows, or orchestration here.</p>
 */
package es.bluesolution.dlq_streaming.functional_framework;

