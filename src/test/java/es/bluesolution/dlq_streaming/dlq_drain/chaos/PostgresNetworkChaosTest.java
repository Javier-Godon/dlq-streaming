package es.bluesolution.dlq_streaming.dlq_drain.chaos;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence.JdbcDeadLetterRepository;
import es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toxiproxy-based chaos tests for the PostgreSQL repository layer.
 *
 * <h3>TDD findings and resulting production code changes</h3>
 * <ol>
 *   <li><b>No socket timeout on JDBC</b> — without {@code socketTimeout} in the JDBC URL,
 *       {@code claimNextBatch} hangs indefinitely when the DB network is disrupted.
 *       {@link #claimBatchReturnsFailureOnDatabaseConnectionDrop} drove the finding that
 *       production datasources MUST include {@code ?socketTimeout=N} in the JDBC URL.
 *       See the README production deployment section for the required HikariCP configuration.</li>
 *   <li><b>Repository exception handling</b> — chaos tests confirmed that all PostgreSQL
 *       exceptions are correctly converted to {@code Result.failure(DATABASE_ERROR, ...)}
 *       and never thrown as unchecked exceptions.</li>
 * </ol>
 *
 * <h3>Network topology</h3>
 * <pre>
 *   JdbcClient  →  Toxiproxy (container)  →  PostgreSQL (container)
 * </pre>
 * Both containers share a Docker {@link Network} so Toxiproxy can address PostgreSQL
 * by container alias.
 *
 * <h3>Production guidance driven by these tests</h3>
 * Configure these HikariCP properties in production:
 * <pre>
 * spring.datasource.hikari.connection-timeout=30000      # 30 s pool-level
 * spring.datasource.hikari.data-source-properties.socketTimeout=30   # 30 s TCP-level
 * # OR include in the JDBC URL:
 * SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/db?socketTimeout=30
 * </pre>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresNetworkChaosTest {

    /** Socket timeout in seconds used in the test JDBC URL. */
    private static final int SOCKET_TIMEOUT_SECONDS = 3;

    static Network network;

    @Container
    static PostgreSQLContainer<?> POSTGRESQL;

    @Container
    static ToxiproxyContainer TOXIPROXY;

    static {
        network = Network.newNetwork();
        POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine")
                .withNetwork(network)
                .withNetworkAliases("postgres");
        TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
                .withNetwork(network);
    }

    static HikariDataSource dataSource;
    static JdbcDeadLetterRepository repository;
    static ToxiproxyContainer.ContainerProxy pgProxy;

    @BeforeAll
    static void setUp() throws IOException {
        pgProxy = TOXIPROXY.getProxy("postgres", 5432);

        // Datasource pointing at Toxiproxy with a short socket timeout so tests run fast.
        var jdbcUrl = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + pgProxy.getProxyPort()
                + "/" + POSTGRESQL.getDatabaseName()
                + "?socketTimeout=" + SOCKET_TIMEOUT_SECONDS;  // TCP-level timeout in seconds

        var hikari = new HikariConfig();
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(POSTGRESQL.getUsername());
        hikari.setPassword(POSTGRESQL.getPassword());
        hikari.setMaximumPoolSize(2);
        hikari.setConnectionTimeout(5_000); // pool-level timeout
        dataSource = new HikariDataSource(hikari);

        // Run migrations on the real PG (not through proxy — proxy will be disrupted in tests)
        Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        repository = new JdbcDeadLetterRepository(JdbcClient.create(dataSource));
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    /**
     * TDD driving test — Connection drop.
     *
     * <p><b>Before finding</b>: without {@code socketTimeout} in the JDBC URL, this test
     * hangs indefinitely (the OS TCP stack silently drops writes with no error).<br>
     * <b>After fix</b>: with {@code socketTimeout=3} the JDBC call fails within ~3 s
     * and the repository wraps the exception in {@code Result.failure(DATABASE_ERROR, ...)}.</p>
     */
    @Test
    void claimBatchReturnsFailureOnDatabaseConnectionDrop() throws IOException {
        // Add a Toxiproxy "timeout" toxic that closes all new connections immediately.
        pgProxy.toxics().timeout("pg-drop", ToxicDirection.UPSTREAM, 0);
        try {
            // Evict any pooled connections so a new one must be acquired through Toxiproxy
            dataSource.getHikariPoolMXBean().softEvictConnections();

            var start = Instant.now();
            var result = repository.claimNextBatch(
                    DrainBatchSize.create(10).value(),
                    DrainWorkerId.create("chaos-worker").value(),
                    DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                    Instant.now());
            var elapsed = Duration.between(start, Instant.now());

            assertThat(result.isFailure()).isTrue();
            assertThat(result.failure().code())
                    .isEqualTo(FailureResultDescription.ErrorCode.DATABASE_ERROR);
            // Must complete within socket_timeout + pool_timeout + buffer
            assertThat(elapsed).isLessThan(Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS + 8L));
        } finally {
            pgProxy.toxics().get("pg-drop").remove();
        }
    }

    /**
     * TDD driving test — High latency on PostgreSQL connection.
     *
     * <p>Simulates a heavily loaded or network-degraded PostgreSQL host.
     * The repository must return failure within the socket timeout window.</p>
     */
    @Test
    void claimBatchReturnsFailureOnHighLatency() throws IOException {
        // 10 s latency — larger than our 3 s socket timeout (latency API: name, direction, latencyMs)
        pgProxy.toxics().latency("pg-latency", ToxicDirection.UPSTREAM, 10_000);
        try {
            dataSource.getHikariPoolMXBean().softEvictConnections();

            var start = Instant.now();
            var result = repository.claimNextBatch(
                    DrainBatchSize.create(10).value(),
                    DrainWorkerId.create("chaos-worker").value(),
                    DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                    Instant.now());
            var elapsed = Duration.between(start, Instant.now());

            assertThat(result.isFailure()).isTrue();
            assertThat(result.failure().code())
                    .isEqualTo(FailureResultDescription.ErrorCode.DATABASE_ERROR);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS + 8L));
        } finally {
            pgProxy.toxics().get("pg-latency").remove();
        }
    }

    /**
     * Sanity check — normal operation works when network is healthy.
     */
    @Test
    void claimBatchSucceedsWhenNetworkIsHealthy() {
        var result = repository.claimNextBatch(
                DrainBatchSize.create(10).value(),
                DrainWorkerId.create("chaos-worker").value(),
                DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                Instant.now());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEmpty(); // table is empty in chaos tests
    }

    /**
     * Sanity check — releaseExpiredLeases works when network is healthy.
     */
    @Test
    void releaseExpiredLeasesReturnsZeroOnEmptyTable() {
        var result = repository.releaseExpiredLeases(Instant.now());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isZero();
    }
}


