package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.NullMarked;

import java.util.function.Supplier;

/**
 * Interface contract for aggregators supporting Outbox Pattern.
 *
 * The Outbox pattern enables reliable event publishing with transactional guarantees:
 * - Events are written to an outbox table within the same database transaction
 * - A separate process polls and publishes events asynchronously
 * - Decouples business operations from event publishing
 *
 * Implementations should track events/messages to be published after successful persistence.
 *
 * @see OutboxExecutionContext for execution strategy
 */
@NullMarked
public interface OutboxAggregator {

    /**
     * Register an outbox entry (event/message) to be published asynchronously.
     *
     * Outbox entries are published AFTER successful database commit in a separate transaction,
     * ensuring transactional consistency and eventual event delivery.
     *
     * @param outboxEntry supplier that returns Result with event to publish
     * @return updated aggregator with outbox entry registered
     */
    OutboxAggregator withOutboxEntry(Supplier<Result<OutboxEntry>> outboxEntry);

    /**
     * Data class representing a single outbox entry.
     * Contains the event/message payload to be published asynchronously.
     *
     * @param aggregateType type of aggregate that generated this event (e.g., "Tenant")
     * @param aggregateId   ID of the aggregate (e.g., tenant ID)
     * @param eventType     type of event (e.g., "TenantCreated", "TenantUpdated")
     * @param payload       serialized event payload (JSON)
     * @param topic         destination topic/channel for this event (e.g., "tenant-events")
     */
    record OutboxEntry(
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            String topic
    ) {}
}
