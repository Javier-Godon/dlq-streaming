package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure;

import es.bluesolution.dlq_streaming.functional_framework.execution.TransactionExecutionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DlqDrainProperties.class)
public class DlqDrainInfrastructureConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    TransactionExecutionContext transactionExecutionContext(PlatformTransactionManager transactionManager) {
        return TransactionExecutionContext.of(transactionManager);
    }
}



