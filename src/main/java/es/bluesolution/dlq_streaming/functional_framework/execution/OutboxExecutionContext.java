package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Execution Context implementing the Outbox Pattern for reliable event publishing.
 *
 * The Outbox pattern ensures reliable delivery of domain events with transactional guarantees:
 *
 * EXECUTION FLOW:
 * 1. Execute business computation (within transaction boundary)
 * 2. Collect outbox entries (events to be published)
 * 3. Commit transaction (includes outbox entries in database)
 * 4. OUTSIDE transaction: publish events asynchronously
 * 5. Mark outbox entries as published in separate transaction
 *
 * GUARANTEES:
 * - Events are durably stored before publishing (no event loss)
 * - Business operation and event storage happen atomically
 * - Publishing happens asynchronously (non-blocking)
 * - Supports eventual consistency patterns
 *
 * USAGE PATTERN:
 * ```java
 * Result<Aggregator> result = Result.pipeline(aggregator)
 *     .flatMap(stage1)
 *     .flatMap(stage2)
 *     .flatMap(persistStage)   // persist() registers outbox entries
 *     .within(outboxContext)  // ← Outbox tracking + transaction boundary
 *     // After .within() completes, outbox entries can be published asynchronously
 *     .peek(result -> publishOutboxEntries(outboxContext.getOutboxEntries()));
 * ```
 *
 * THREAD SAFETY:
 * Uses ThreadLocal to track outbox entries per request thread,
 * ensuring isolation across concurrent requests.
 *
 * @see OutboxAggregator for aggregator contract
 * @see ExecutionContext for base strategy pattern
 */
@Slf4j
@NullMarked
public class OutboxExecutionContext implements ExecutionContext {

    private static final ThreadLocal<List<Supplier<Result<OutboxAggregator.OutboxEntry>>>> OUTBOX_ENTRIES =
            ThreadLocal.withInitial(ArrayList::new);

    private final ExecutionContext delegate;
    private final boolean enableLogging;

    /**
     * Create OutboxExecutionContext with optional logging.
     *
     * @param delegate underlying execution context (typically TransactionExecutionContext)
     * @param enableLogging whether to log outbox operation details
     */
    public OutboxExecutionContext(ExecutionContext delegate, boolean enableLogging) {
        this.delegate = delegate;
        this.enableLogging = enableLogging;
    }

    /**
     * Create OutboxExecutionContext with database transactions.
     *
     * @param delegate transaction context (required for outbox persistence)
     * @return configured outbox execution context
     */
    public static OutboxExecutionContext withTransactions(ExecutionContext delegate) {
        return new OutboxExecutionContext(delegate, false);
    }

    /**
     * Create OutboxExecutionContext with transactions and logging.
     *
     * @param delegate transaction context
     * @return configured outbox execution context with logging
     */
    public static OutboxExecutionContext withLogging(ExecutionContext delegate) {
        return new OutboxExecutionContext(delegate, true);
    }

    @Override
    public <T> Result<T> execute(Supplier<Result<T>> computation) {
        if (log.isDebugEnabled()) {
            log.debug("Executing within Outbox context (entries will be stored durably)");
        }

        // Each execution owns its outbox entries. This prevents a previous failed
        // operation or forgotten cleanup from leaking events into the next request
        // handled by the same thread.
        OUTBOX_ENTRIES.get().clear();

        try {
            // Execute computation with transaction support
            Result<T> result = delegate.execute(computation);

            if (result.isSuccess() && enableLogging) {
                int entryCount = OUTBOX_ENTRIES.get().size();
                log.debug("Outbox context completed: {} event entries registered", entryCount);
            }

            if (result.isFailure()) {
                resetOutbox();
            }

            return result;
        } catch (RuntimeException e) {
            resetOutbox();
            throw e;
        } finally {
            // Success path: DO NOT CLEAR YET - entries are needed for async publishing.
            // Client code is responsible for calling publishOutboxEntries()
            // then cleanupOutbox() when publishing completes.
            // Failure/exception paths are cleared above because no event from a failed
            // business operation may be published.
        }
    }

    /**
     * Register an outbox entry (event to be published asynchronously).
     *
     * Called from stages via aggregator.withOutboxEntry().
     * Entry is stored in ThreadLocal and later persisted to database.
     *
     * @param outboxEntry supplier that creates the outbox entry
     */
    public void registerOutboxEntry(Supplier<Result<OutboxAggregator.OutboxEntry>> outboxEntry) {
        OUTBOX_ENTRIES.get().add(outboxEntry);
        if (enableLogging) {
            log.debug("Outbox entry registered (total: {})", OUTBOX_ENTRIES.get().size());
        }
    }

    /**
     * Get all registered outbox entries.
     *
     * Called after successful business operation to publish events asynchronously.
     * Entries should be published in a separate asynchronous transaction.
     *
     * @return list of outbox entry suppliers
     */
    public List<Supplier<Result<OutboxAggregator.OutboxEntry>>> getOutboxEntries() {
        return new ArrayList<>(OUTBOX_ENTRIES.get());
    }

    /**
     * Cleanup outbox entries after publishing.
     *
     * IMPORTANT: Call this only after events have been published successfully.
     * Removes ThreadLocal state to prevent memory leaks.
     *
     * Typical usage:
     * ```java
     * try {
     *     publishOutboxEntries(outboxContext.getOutboxEntries());
     * } finally {
     *     outboxContext.cleanupOutbox();
     * }
     * ```
     */
    public void cleanupOutbox() {
        int count = OUTBOX_ENTRIES.get().size();
        OUTBOX_ENTRIES.remove();
        if (enableLogging) {
            log.debug("Outbox cleanup completed (cleared {} entries from ThreadLocal)", count);
        }
    }

    /**
     * Reset outbox state (for testing or error scenarios).
     * Clears all registered entries from ThreadLocal.
     */
    public void resetOutbox() {
        OUTBOX_ENTRIES.get().clear();
        if (enableLogging) {
            log.debug("Outbox state reset");
        }
    }

    /**
     * Get count of registered outbox entries (useful for metrics/logging).
     */
    public int getOutboxEntryCount() {
        return OUTBOX_ENTRIES.get().size();
    }
}
