package es.bluesolution.dlq_streaming.dlq_drain.chaos;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver.DataPrepperDeadLetterReceiver;
import es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toxiproxy-based chaos tests for the Data Prepper receiver adapter.
 *
 * <h3>Architecture</h3>
 * <pre>
 * TCP-level chaos:   Receiver  →  Toxiproxy (Docker)  → [dummy upstream, not reached]
 * HTTP-level chaos:  Receiver  →  WireMock (in-process)
 * </pre>
 * Toxiproxy is used ONLY for TCP-level faults (connect timeout, connection reset).
 * It needs no real upstream because toxics fire BEFORE forwarding.
 * WireMock is used directly for HTTP-level tests (retry 503, bad request 400, read timeout).
 * This avoids container-to-host networking complexity.
 *
 * <h3>TDD findings and resulting production code changes</h3>
 * <ol>
 *   <li><b>No HTTP connect timeout</b> — {@link #connectTimeoutReturnsFailureWithinExpectedDuration}
 *       proved the application hangs without a configured connect timeout.
 *       Fix: {@code JdkClientHttpRequestFactory} with {@code connectTimeout} in
 *       {@code DlqDrainInfrastructureConfig}.</li>
 *   <li><b>No HTTP read timeout</b> — {@link #readTimeoutReturnsFailureWhenServerIsUnresponsive}
 *       proved the application hangs when a server accepts the connection but never replies.
 *       Fix: {@code factory.setReadTimeout(...)} in {@code DlqDrainInfrastructureConfig}.</li>
 *   <li><b>No retry for transient 503</b> — {@link #retriesOnTransient503AndEventuallySucceeds}
 *       proved that a single-attempt policy fails on pod restarts.
 *       Fix: exponential-backoff retry loop in {@code DataPrepperDeadLetterReceiver}.</li>
 *   <li><b>Non-retryable errors must not retry</b> — {@link #badRequestFailsImmediatelyWithoutRetry}
 *       verified that 4xx responses (permanent errors) do NOT trigger the retry loop.</li>
 *   <li><b>Connection reset resilience</b> — {@link #connectionResetReturnsFailureAndDoesNotHang}
 *       verified that mid-flight TCP resets are handled gracefully.</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
class DataPrepperNetworkChaosTest {

    private static final String INGEST_PATH = "/log/ingest";

    /** Short timeouts so chaos tests complete quickly (< 3 s per test). */
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS    = 2_000;

    // -------------------------------------------------------------------------
    // Toxiproxy — used ONLY for TCP-level tests (connect timeout, reset peer).
    // The proxy's "upstream" is never reached because toxics fire first.
    // -------------------------------------------------------------------------
    @Container
    static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0");

    static ToxiproxyContainer.ContainerProxy tcpProxy;  // proxy for TCP-chaos tests
    static String toxiproxyUrl;

    // -------------------------------------------------------------------------
    // WireMock — used for HTTP-level tests (retry, bad request, read timeout).
    // Runs in-process; receiver connects directly without going through Toxiproxy.
    // -------------------------------------------------------------------------
    static WireMockServer wireMock;
    static String wireMockUrl;

    @BeforeAll
    static void startServers() throws IOException {
        // WireMock: start on random port in the test JVM
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMockUrl = "http://localhost:" + wireMock.port() + INGEST_PATH;

        // Toxiproxy: create a proxy to a dummy upstream (Toxiproxy's own admin port —
        // it will never receive traffic because the toxic intercepts first).
        // Using port 8474 (Toxiproxy admin) as a convenient address that exists inside
        // the container. Only TCP-level tests use this proxy.
        tcpProxy = TOXIPROXY.getProxy("localhost", 8474);
        toxiproxyUrl = "http://" + TOXIPROXY.getHost() + ":"
                + tcpProxy.getProxyPort() + INGEST_PATH;
    }

    @AfterAll
    static void stopServers() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetWireMockStubs() {
        // Ensure no stubs or scenario state leaks between tests
        if (wireMock != null) wireMock.resetAll();
    }

    // =========================================================================
    // TCP-LEVEL CHAOS  (go through Toxiproxy; no WireMock needed)
    // =========================================================================

    /**
     * TDD driving test — TCP connect: Toxiproxy closes connection immediately.
     *
     * <p><b>Before fix</b>: with no {@code connectTimeout} on the {@link RestClient},
     * the receiver hangs indefinitely.
     * <b>After fix</b>: returns {@code EXTERNAL_SERVICE_ERROR} within the configured
     * connect-timeout window.</p>
     */
    @Test
    void connectTimeoutReturnsFailureWithinExpectedDuration() throws IOException {
        tcpProxy.toxics().timeout("c-timeout", ToxicDirection.UPSTREAM, 0);
        try {
            var start = Instant.now();
            var result = buildReceiver(toxiproxyUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 1)
                    .receive(command());
            var elapsed = Duration.between(start, Instant.now());

            assertThat(result.isFailure()).isTrue();
            assertThat(result.failure().code())
                    .isEqualTo(FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
            // Must complete within configured timeout + buffer
            assertThat(elapsed).isLessThan(Duration.ofMillis(CONNECT_TIMEOUT_MS * 3L));
        } finally {
            tcpProxy.toxics().get("c-timeout").remove();
        }
    }

    /**
     * TDD driving test — TCP connection reset.
     *
     * <p>Toxiproxy sends a TCP RST immediately after the client connects.
     * The receiver must handle {@link org.springframework.web.client.ResourceAccessException}
     * and return {@code EXTERNAL_SERVICE_ERROR} quickly.</p>
     */
    @Test
    void connectionResetReturnsFailureAndDoesNotHang() throws IOException {
        tcpProxy.toxics().resetPeer("c-reset", ToxicDirection.UPSTREAM, 0);
        try {
            var start = Instant.now();
            var result = buildReceiver(toxiproxyUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 2)
                    .receive(command());
            var elapsed = Duration.between(start, Instant.now());

            assertThat(result.isFailure()).isTrue();
            assertThat(result.failure().code())
                    .isEqualTo(FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
            // Must complete quickly — no hanging
            assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        } finally {
            tcpProxy.toxics().get("c-reset").remove();
        }
    }

    // =========================================================================
    // HTTP-LEVEL CHAOS  (go directly to WireMock)
    // =========================================================================

    /**
     * TDD driving test — Read timeout.
     *
     * <p>WireMock delays the response for 60 s.  With a 2 s read timeout on the
     * {@link RestClient}, the receiver must fail within the timeout window.</p>
     */
    @Test
    void readTimeoutReturnsFailureWhenServerIsUnresponsive() {
        wireMock.stubFor(post(INGEST_PATH)
                .willReturn(aResponse().withStatus(200).withFixedDelay(60_000)));

        var start = Instant.now();
        var result = buildReceiver(wireMockUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 1)
                .receive(command());
        var elapsed = Duration.between(start, Instant.now());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code())
                .isEqualTo(FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        assertThat(elapsed).isLessThan(Duration.ofMillis(READ_TIMEOUT_MS + 3_000L));
    }

    /**
     * TDD driving test — Transient 503 followed by eventual success.
     *
     * <p>Data Prepper returns 503 on the first two attempts then 200.
     * The retry loop must succeed on the third attempt.</p>
     */
    @Test
    void retriesOnTransient503AndEventuallySucceeds() {
        wireMock.stubFor(post(INGEST_PATH)
                .inScenario("503-then-200")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-attempt"));

        wireMock.stubFor(post(INGEST_PATH)
                .inScenario("503-then-200")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third-attempt"));

        wireMock.stubFor(post(INGEST_PATH)
                .inScenario("503-then-200")
                .whenScenarioStateIs("third-attempt")
                .willReturn(aResponse().withStatus(200)));

        var result = buildReceiver(wireMockUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 3)
                .receive(command());

        assertThat(result.isSuccess())
                .as("Expected success after retry but got: %s", result.isFailure() ? result.failure().message() : "")
                .isTrue();
    }

    /**
     * TDD driving test — Exhausted retries on persistent 503.
     */
    @Test
    void exhaustedRetriesOnPersistent503ReturnsFailure() {
        wireMock.stubFor(post(INGEST_PATH)
                .willReturn(aResponse().withStatus(503)));

        var result = buildReceiver(wireMockUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 2)
                .receive(command());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code())
                .isEqualTo(FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        assertThat(result.failure().message()).contains("2 attempt");
    }

    /**
     * TDD driving test — Non-retryable 400 Bad Request fails immediately.
     *
     * <p>4xx HTTP errors (except 429) are permanent errors; the retry loop must NOT
     * be triggered.</p>
     */
    @Test
    void badRequestFailsImmediatelyWithoutRetry() {
        wireMock.stubFor(post(INGEST_PATH)
                .willReturn(aResponse().withStatus(400)));

        var result = buildReceiver(wireMockUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 3)
                .receive(command());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code())
                .isEqualTo(FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        // Immediate failure message includes the status code, NOT "after N attempts"
        assertThat(result.failure().message())
                .contains("400")
                .doesNotContain("attempt(s)");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static DataPrepperDeadLetterReceiver buildReceiver(
            String url, int connectMs, int readMs, int maxAttempts) {
        var props = new DlqDrainProperties();
        props.getReceiver().getDataPrepper().setUrl(url);
        props.getReceiver().getDataPrepper().setConnectTimeoutMillis(connectMs);
        props.getReceiver().getDataPrepper().setReadTimeoutMillis(readMs);
        props.getReceiver().getDataPrepper().setMaxRetryAttempts(maxAttempts);
        props.getReceiver().getDataPrepper().setRetryInitialDelayMillis(0); // no sleep in tests
        props.getReceiver().getDataPrepper().setRetryMultiplier(1.0);

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readMs));
        var restClient = RestClient.builder().requestFactory(factory).build();

        return new DataPrepperDeadLetterReceiver(restClient, props);
    }

    private static ReceiveDeadLetterCommand command() {
        return ReceiveDeadLetterCommand.create(
                ProcessId.create("product-chaos_2026-05-24T10:00:00Z").value(),
                DeadLetterPayload.create("{\"chaos\":true}").value()).value();
    }
}
