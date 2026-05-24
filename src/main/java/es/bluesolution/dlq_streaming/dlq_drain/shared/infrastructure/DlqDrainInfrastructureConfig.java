package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure;

import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.security.ApiKeyAuthFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(DlqDrainProperties.class)
public class DlqDrainInfrastructureConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // ── Data Prepper REST client ───────────────────────────────────────────

    /**
     * Pre-configured {@link RestClient} for the Data Prepper receiver.
     *
     * <p>Uses the JDK {@link HttpClient} with explicit connect and read timeouts so the
     * drain loop never hangs indefinitely on an unreachable or slow Data Prepper instance.
     * Without these timeouts the application would block until the OS TCP stack gives up
     * (which can be minutes), causing the K8s CronJob job to stall.</p>
     *
     * <p>Timeouts are driven by {@code dlq-drain.receiver.data-prepper.*} configuration
     * properties and can be tuned via environment variables:
     * {@code DLQ_DP_CONNECT_TIMEOUT_MS} and {@code DLQ_DP_READ_TIMEOUT_MS}.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "dlq-drain.receiver", name = "type", havingValue = "dataprepper")
    RestClient dataPrepperRestClient(DlqDrainProperties properties) {
        var dp = properties.getReceiver().getDataPrepper();

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(dp.getConnectTimeoutMillis()))
                .build();

        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(dp.getReadTimeoutMillis()));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    // ── Security ───────────────────────────────────────────────────────────

    /**
     * Registers the {@link ApiKeyAuthFilter} only when {@code dlq-drain.api-key} is configured.
     *
     * <p>The filter protects {@code /drain/**} endpoints. Actuator endpoints on the separate
     * management port are not affected.</p>
     *
     * <p>When no API key is configured the endpoint is unprotected — use a Kubernetes
     * NetworkPolicy to restrict access by namespace/pod-label instead.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "dlq-drain", name = "api-key")
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(DlqDrainProperties properties) {
        var filter = new ApiKeyAuthFilter(properties.getApiKey());
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/drain/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
