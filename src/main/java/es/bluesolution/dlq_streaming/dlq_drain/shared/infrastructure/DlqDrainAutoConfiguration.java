package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure;

import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence.JdbcDeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence.NoOpDeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver.InMemoryDeadLetterReceiver;
import es.bluesolution.dlq_streaming.functional_framework.execution.ExecutionContext;
import es.bluesolution.dlq_streaming.functional_framework.execution.NoOpExecutionContext;
import es.bluesolution.dlq_streaming.functional_framework.execution.TransactionExecutionContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for drain infrastructure beans that depend on auto-configured beans.
 *
 * <h3>Why auto-configuration instead of user @Configuration?</h3>
 * {@code @ConditionalOnBean} on {@code @Bean} methods in user {@code @Configuration} classes
 * is evaluated <em>before</em> Spring Boot auto-configurations have registered their beans
 * (e.g. {@code JdbcClient}, {@code PlatformTransactionManager}).
 * This causes silent condition failures — user conditions cannot see auto-configured beans.
 *
 * <p>Registered auto-configurations are processed by {@code AutoConfigurationImportSelector}
 * <em>after</em> all user configuration classes. By using {@link AutoConfigureAfter}, we ensure
 * {@link JdbcClient} and {@link PlatformTransactionManager} are already registered before
 * our conditions are evaluated.
 *
 * <h3>Bean ordering within this class</h3>
 * {@code @Bean} methods are evaluated top-to-bottom in {@code REGISTER_BEAN} phase.
 * {@code jdbcDeadLetterRepository} is declared first; if JdbcClient is available it registers.
 * {@code noOpDeadLetterRepository} is declared second; its {@code @ConditionalOnMissingBean}
 * correctly sees the already-registered Jdbc bean and skips itself.
 *
 * <h3>Fallback beans</h3>
 * {@link NoOpDeadLetterRepository} and {@link NoOpExecutionContext} are registered only when
 * no database is configured (e.g. {@code DlqStreamingApplicationTests} that excludes DataSource).
 * They return {@code SERVICE_UNAVAILABLE_ERROR} so the drain endpoint surfaces a clear message
 * instead of a {@code NullPointerException}.
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        // Spring Boot 3.x fallback class names (kept for compatibility during migration)
        "org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
})
public class DlqDrainAutoConfiguration {

    // ── Persistence ────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    JdbcDeadLetterRepository jdbcDeadLetterRepository(JdbcClient jdbcClient) {
        return new JdbcDeadLetterRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean(DeadLetterRepository.class)
    DeadLetterRepository noOpDeadLetterRepository() {
        return new NoOpDeadLetterRepository();
    }

    // ── Receiver ───────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(DeadLetterReceiver.class)
    InMemoryDeadLetterReceiver inMemoryDeadLetterReceiver() {
        return new InMemoryDeadLetterReceiver();
    }

    // ── Execution context ──────────────────────────────────────────────────

    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    TransactionExecutionContext transactionExecutionContext(PlatformTransactionManager transactionManager) {
        return TransactionExecutionContext.of(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionContext.class)
    ExecutionContext noOpExecutionContext() {
        return new NoOpExecutionContext();
    }
}

