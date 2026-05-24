package es.bluesolution.dlq_streaming.kubernetes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kubernetes integration test for the <b>Data Prepper receiver path</b>.
 *
 * <h3>What is tested</h3>
 * <ol>
 *   <li>Records seeded in PostgreSQL are forwarded to a <em>mock Data Prepper</em>
 *       (WireMock) via HTTP and deleted from the database only after the receiver
 *       acknowledges them (HTTP&nbsp;200).</li>
 *   <li>The drain sends the required {@code X-Process-Id} and {@code Idempotency-Key}
 *       headers so Data Prepper can deduplicate reprocessed events.</li>
 *   <li>When the receiver returns HTTP&nbsp;503, the drain stops processing and
 *       returns {@code stoppedBecauseReceiverFailed=true}; records remain in the
 *       database for the next scheduled drain run.</li>
 * </ol>
 *
 * <h3>Infrastructure</h3>
 * <pre>
 * Testcontainers k3s  — isolated k3s cluster (separate from KubernetesDeploymentTest).
 * WireMock standalone — simulates Data Prepper POST /log/ingest.
 * PostgreSQL          — deployed inside k3s.
 * dlq-streaming       — deployed with DLQ_RECEIVER_TYPE=dataprepper.
 * </pre>
 *
 * <h3>Access pattern</h3>
 * All external access from the test JVM uses Fabric8 {@link LocalPortForward} — no
 * extra NodePorts are needed, keeping the K3sContainer definition minimal.
 *
 * <pre>
 *   Test JVM
 *     | LocalPortForward → dlq-streaming-dp pod :8080  (REST API)
 *     | LocalPortForward → postgres pod         :5432  (JDBC seed/verify)
 *     | LocalPortForward → mock-data-prepper pod :8080 (WireMock admin API)
 * </pre>
 *
 * <h3>Running</h3>
 * <pre>
 *   ./mvnw test -Pkubernetes-tests
 * </pre>
 *
 * <h3>WireMock admin API used by this test class</h3>
 * <ul>
 *   <li>{@code POST /__admin/mappings} — register a stub mapping.</li>
 *   <li>{@code POST /__admin/mappings/reset} — reset to initial (empty) state.</li>
 *   <li>{@code DELETE /__admin/requests} — clear the request journal (WireMock 3.x).</li>
 *   <li>{@code POST /__admin/requests/count} — count requests matching a pattern.</li>
 *   <li>{@code GET  /__admin/requests} — retrieve recorded request details.</li>
 * </ul>
 */
@Tag("kubernetes")
@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KubernetesDataPrepperTest {

    // ── Image names ────────────────────────────────────────────────────────
    static final String APP_IMAGE       = "dlq-streaming:k8s-test";
    static final String POSTGRES_IMAGE  = "postgres:17-alpine";
    static final String WIREMOCK_IMAGE  = "wiremock/wiremock:3.13.0";
    static final String K3S_IMAGE       = "rancher/k3s:v1.32.1-k3s1";

    // ── K3s container ─────────────────────────────────────────────────────
    /**
     * Dedicated k3s cluster for Data Prepper tests.
     * Only port 6443 (Kubernetes API) needs to be exposed; all other access
     * is via Fabric8 LocalPortForward.
     */
    @Container
    static final K3sContainer K3S = new K3sContainer(DockerImageName.parse(K3S_IMAGE));

    static KubernetesClient k8sClient;

    // ── Port-forward tunnels ───────────────────────────────────────────────
    static LocalPortForward appPortForward;
    static LocalPortForward pgPortForward;
    static LocalPortForward wmPortForward;

    // ── Clients ────────────────────────────────────────────────────────────
    /** REST client pointing at the dlq-streaming-dp pod via port-forward. */
    static RestClient appApiClient;
    /** REST client pointing at the WireMock admin API via port-forward. */
    static RestClient wmAdminClient;
    /** JDBC access to the PostgreSQL pod via port-forward (seed/verify). */
    static HikariDataSource pgDataSource;
    static JdbcClient pgJdbcClient;

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── Setup ──────────────────────────────────────────────────────────────

    @BeforeAll
    static void setUpCluster() throws Exception {
        log.info("=== KubernetesDataPrepperTest setup starting ===");

        // 1. Build the Fabric8 client from the k3s kubeconfig.
        var config = Config.fromKubeconfig(K3S.getKubeConfigYaml());
        k8sClient = new KubernetesClientBuilder().withConfig(config).build();

        // 2. Pre-load all required images into k3s containerd.
        //    This prevents in-container Docker Hub pulls which are slow and
        //    subject to rate limiting.
        loadImageIntoK3s(POSTGRES_IMAGE);
        loadImageIntoK3s(WIREMOCK_IMAGE);
        loadAppImageIntoK3s();

        // 3. Deploy PostgreSQL.
        applyManifest("kubernetes/postgres.yaml");
        waitForDeploymentReady("default", "postgres", Duration.ofSeconds(120));

        // 4. Deploy mock Data Prepper (WireMock).
        applyManifest("kubernetes/mock-data-prepper.yaml");
        waitForDeploymentReady("default", "mock-data-prepper", Duration.ofSeconds(90));

        // 5. Deploy the app with DLQ_RECEIVER_TYPE=dataprepper.
        applyManifest("kubernetes/app-config.yaml");           // re-use: Secret with DB creds
        applyManifest("kubernetes/app-config-dataprepper.yaml"); // data-prepper ConfigMap
        applyManifest("kubernetes/app-deployment-dataprepper.yaml");
        waitForDeploymentReady("default", "dlq-streaming-dp", Duration.ofSeconds(180));

        // 6. Open Fabric8 port-forward tunnels.
        openPostgresPortForward();
        openAppPortForward();
        openWireMockPortForward();

        log.info("=== KubernetesDataPrepperTest cluster ready ===");
    }

    @AfterAll
    static void tearDownCluster() {
        log.info("=== KubernetesDataPrepperTest teardown ===");
        closeAllPortForwards();
        if (k8sClient != null) {
            try { k8sClient.close(); } catch (Exception e) {
                log.warn("Error closing k8s client: {}", e.getMessage());
            }
        }
        // Clean up test Docker image.
        try {
            int rc = runProcess(List.of("docker", "rmi", APP_IMAGE), "docker rmi " + APP_IMAGE);
            if (rc == 0) log.info("Test image {} removed.", APP_IMAGE);
        } catch (Exception e) {
            log.warn("Could not remove test image {}: {}", APP_IMAGE, e.getMessage());
        }
        try {
            runProcess(List.of("docker", "image", "prune", "-f"), "docker image prune -f");
        } catch (Exception e) {
            log.warn("Could not prune dangling images: {}", e.getMessage());
        }
    }

    /**
     * Reset WireMock state before each test:
     * <ol>
     *   <li>Clear the request journal so assertions are scoped to the current test.</li>
     *   <li>Reset stub mappings to the empty-file state and re-add the default 200 OK stub.</li>
     * </ol>
     */
    @BeforeEach
    void resetWireMockState() {
        // Clear recorded (past) requests.
        // WireMock 3.x replaced POST /__admin/requests/reset with DELETE /__admin/requests.
        wmAdminClient.delete().uri("/__admin/requests")
                .retrieve().toBodilessEntity();
        // Wipe all dynamic stubs.
        wmAdminClient.post().uri("/__admin/mappings/reset")
                .retrieve().toBodilessEntity();
        // Re-register the default happy-path stub.
        addWireMockStub(200, "{}", null);
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Drain forwards all 5 records to Data Prepper and clears the table")
    void drainWithDataPrepperForwardsAllRecordsAndClearsTable() throws Exception {
        cleanDeadLetterTable();
        for (int i = 1; i <= 5; i++) {
            seedPendingRecord("dp-test-" + i + "_2026-05-24T10:00:00Z");
        }
        assertThat(countAllRecords()).as("5 records seeded").isEqualTo(5);

        var response = callDrainApi();

        assertThat(response.storedCount())
                .as("All 5 records must be stored (forwarded to Data Prepper)")
                .isEqualTo(5);
        assertThat(response.deletedCount())
                .as("All 5 records must be deleted from DB after receiver ACK")
                .isEqualTo(5);
        assertThat(response.stoppedBecauseReceiverFailed())
                .as("Drain must not stop on failure — WireMock returns 200")
                .isFalse();
        assertThat(countAllRecords())
                .as("Table must be empty after successful drain")
                .isZero();

        // Verify WireMock received exactly 5 POST /log/ingest requests.
        assertThat(countWireMockRequests("/log/ingest", "POST"))
                .as("WireMock must have received exactly 5 POST /log/ingest calls")
                .isEqualTo(5);
    }

    @Test
    @Order(2)
    @DisplayName("Drain sends X-Process-Id and Idempotency-Key headers to Data Prepper")
    void drainSendsRequiredHeadersToDataPrepper() throws Exception {
        String processId = "header-check_2026-05-24T11:00:00Z";
        cleanDeadLetterTable();
        seedPendingRecord(processId);

        callDrainApi();

        // Retrieve recorded requests from WireMock.
        var requestsJson = wmAdminClient.get()
                .uri("/__admin/requests")
                .retrieve()
                .body(String.class);
        assertThat(requestsJson).as("WireMock requests body must not be null").isNotNull();

        var root = MAPPER.readTree(requestsJson);
        var requests = root.path("requests");
        assertThat(requests.isArray() && !requests.isEmpty())
                .as("WireMock must have recorded at least 1 request")
                .isTrue();

        // Inspect the first (only) recorded request's headers.
        JsonNode firstReq = requests.get(0).path("request");
        JsonNode headers  = firstReq.path("headers");

        assertThat(headers.has("X-Process-Id"))
                .as("X-Process-Id header must be sent to Data Prepper")
                .isTrue();
        assertThat(headers.has("Idempotency-Key"))
                .as("Idempotency-Key header must be sent to Data Prepper")
                .isTrue();

        // Verify the header value matches the process ID.
        String sentProcessId = headers.path("X-Process-Id").asText();
        assertThat(sentProcessId)
                .as("X-Process-Id header must match the record's process_id")
                .isEqualTo(processId);
    }

    @Test
    @Order(3)
    @DisplayName("Drain stops when Data Prepper returns 503 — stoppedBecauseReceiverFailed=true")
    void drainStopsWhenDataPrepperReturns503() throws Exception {
        // Override the default stub with a 503 response.
        // priority=1 ensures this mapping wins over any lower-priority stubs.
        addWireMockStub(503, "Service Unavailable", 1);

        cleanDeadLetterTable();
        seedPendingRecord("will-fail_2026-05-24T12:00:00Z");
        seedPendingRecord("also-fail_2026-05-24T12:01:00Z");

        // Use exchange() here because RestClient.retrieve() throws by default on 5xx.
        // The drain endpoint returns HTTP 503 when the receiver fails.
        var statusCode = new int[1];
        var responseBody = new String[1];
        appApiClient.post()
                .uri("/drain/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange((ignoredReq, response) -> {
                    statusCode[0] = response.getStatusCode().value();
                    responseBody[0] = new String(response.getBody().readAllBytes());
                    return null;
                });

        assertThat(statusCode[0])
                .as("Drain must return HTTP 503 when the receiver fails")
                .isEqualTo(503);

        var drainResponse = MAPPER.readValue(responseBody[0], DrainApiResponse.class);
        assertThat(drainResponse.stoppedBecauseReceiverFailed())
                .as("Drain must report stoppedBecauseReceiverFailed=true")
                .isTrue();

        // Records must remain in the DB — they were NOT deleted because the receiver failed.
        assertThat(countAllRecords())
                .as("Records must remain in DB when the receiver fails (no delete without ACK)")
                .isGreaterThan(0);
    }

    @Test
    @Order(4)
    @DisplayName("Drain is idempotent with Data Prepper: two consecutive calls both succeed")
    void drainIsIdempotentWithDataPrepper() throws Exception {
        cleanDeadLetterTable();
        seedPendingRecord("idempotent-1_2026-05-24T13:00:00Z");
        seedPendingRecord("idempotent-2_2026-05-24T13:01:00Z");

        // First drain: consumes both records.
        var first = callDrainApi();
        assertThat(first.storedCount()).as("First drain must store 2 records").isEqualTo(2);
        assertThat(countAllRecords()).as("Table must be empty after first drain").isZero();

        // Second drain on empty table: idempotent.
        var second = callDrainApi();
        assertThat(second.claimedCount()).as("Second drain: nothing to claim").isZero();
        assertThat(second.stoppedBecauseReceiverFailed()).as("No failure on empty table").isFalse();
    }

    // ── Port-forward management ────────────────────────────────────────────

    static void openAppPortForward() {
        var pods = k8sClient.pods().inNamespace("default")
                .withLabel("app", "dlq-streaming-dp").list().getItems();
        assertThat(pods).as("dlq-streaming-dp pod must exist").isNotEmpty();

        var podName = pods.getFirst().getMetadata().getName();
        appPortForward = k8sClient.pods().inNamespace("default")
                .withName(podName).portForward(8080);
        appApiClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + appPortForward.getLocalPort())
                .build();
        log.info("App port-forward open: pod={}, localPort={}", podName, appPortForward.getLocalPort());
    }

    static void openPostgresPortForward() {
        var pgPods = k8sClient.pods().inNamespace("default")
                .withLabel("app", "postgres").list().getItems();
        assertThat(pgPods).as("PostgreSQL pod must exist").isNotEmpty();

        var pgPodName = pgPods.getFirst().getMetadata().getName();
        pgPortForward = k8sClient.pods().inNamespace("default")
                .withName(pgPodName).portForward(5432);

        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + pgPortForward.getLocalPort() + "/dlq");
        cfg.setUsername("dlq_user");
        cfg.setPassword("test_password");
        cfg.setMaximumPoolSize(3);
        cfg.setConnectionTimeout(10_000);
        pgDataSource = new HikariDataSource(cfg);
        pgJdbcClient = JdbcClient.create(pgDataSource);
        log.info("PostgreSQL port-forward open: pod={}, localPort={}", pgPodName, pgPortForward.getLocalPort());
    }

    static void openWireMockPortForward() {
        var wmPods = k8sClient.pods().inNamespace("default")
                .withLabel("app", "mock-data-prepper").list().getItems();
        assertThat(wmPods).as("mock-data-prepper pod must exist").isNotEmpty();

        var wmPodName = wmPods.getFirst().getMetadata().getName();
        wmPortForward = k8sClient.pods().inNamespace("default")
                .withName(wmPodName).portForward(8080);
        wmAdminClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + wmPortForward.getLocalPort())
                .build();
        log.info("WireMock port-forward open: pod={}, localPort={}", wmPodName, wmPortForward.getLocalPort());
    }

    static void closeAllPortForwards() {
        if (pgDataSource != null && !pgDataSource.isClosed()) pgDataSource.close();
        for (LocalPortForward pf : List.of(pgPortForward, appPortForward, wmPortForward)) {
            if (pf != null) {
                try { pf.close(); } catch (Exception e) {
                    log.warn("Error closing port-forward: {}", e.getMessage());
                }
            }
        }
    }

    // ── WireMock helpers ───────────────────────────────────────────────────

    /**
     * Registers a stub mapping on the WireMock admin API for {@code POST /log/ingest}.
     *
     * @param status   HTTP response status (e.g. 200, 503)
     * @param body     response body string
     * @param priority optional priority (null = default; lower number = higher priority)
     */
    static void addWireMockStub(int status, String body, Integer priority) {
        var priorityField = priority != null ? "\"priority\": " + priority + "," : "";
        var stub = """
                {
                  %s
                  "request": {"method": "POST", "urlPath": "/log/ingest"},
                  "response": {
                    "status": %d,
                    "headers": {"Content-Type": "application/json"},
                    "body": %s
                  }
                }
                """.formatted(priorityField, status, "\"" + body + "\"");

        wmAdminClient.post()
                .uri("/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Returns the number of WireMock recorded requests matching the given URL path and method.
     */
    static int countWireMockRequests(String urlPath, String method) {
        var countRequest = """
                {"urlPath": "%s", "method": "%s"}
                """.formatted(urlPath, method);

        var responseBody = wmAdminClient.post()
                .uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(countRequest)
                .retrieve()
                .body(String.class);

        try {
            return MAPPER.readTree(responseBody).path("count").asInt();
        } catch (Exception e) {
            log.warn("Failed to parse WireMock count response: {}", responseBody, e);
            return -1;
        }
    }

    // ── DB helpers ─────────────────────────────────────────────────────────

    private static void cleanDeadLetterTable() {
        pgJdbcClient.sql("TRUNCATE TABLE dlq.dead_letter_record RESTART IDENTITY").update();
    }

    private static void seedPendingRecord(String processId) {
        pgJdbcClient.sql("""
                INSERT INTO dlq.dead_letter_record(process_id, payload, status)
                VALUES (:processId, CAST(:payload AS jsonb), 'PENDING')
                """)
                .param("processId", processId)
                .param("payload", "{\"event\":\"test\",\"processId\":\"" + processId + "\"}")
                .update();
    }

    private static int countAllRecords() {
        return pgJdbcClient
                .sql("SELECT COUNT(*) FROM dlq.dead_letter_record")
                .query(Integer.class)
                .single();
    }

    private static DrainApiResponse callDrainApi() throws Exception {
        var json = appApiClient.post()
                .uri("/drain/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        assertThat(json).as("Drain API response body must not be null").isNotNull();
        return MAPPER.readValue(json, DrainApiResponse.class);
    }

    // ── Image helpers (duplicated from KubernetesDeploymentTest for self-containment) ──

    /**
     * Ensures the application Docker image {@value APP_IMAGE} exists locally,
     * building it from the project Dockerfile if necessary, then loads it into k3s.
     */
    private static void loadAppImageIntoK3s() throws Exception {
        boolean locallyAvailable = runProcess(
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", APP_IMAGE),
                "docker image inspect " + APP_IMAGE
        ) == 0;

        if (!locallyAvailable) {
            log.info("Image '{}' not found locally — building from Dockerfile...", APP_IMAGE);
            File projectRoot = new File(System.getProperty("user.dir"));
            int buildRc = runProcess(
                    List.of("docker", "build", "-t", APP_IMAGE, projectRoot.getAbsolutePath()),
                    "docker build -t " + APP_IMAGE
            );
            if (buildRc != 0) {
                throw new IllegalStateException("docker build failed for image: " + APP_IMAGE);
            }
        } else {
            log.debug("Image '{}' already available locally — skipping build.", APP_IMAGE);
        }

        loadImageIntoK3s(APP_IMAGE);
    }

    /**
     * Saves the Docker image to a tar, copies it into k3s, and imports it into containerd.
     * If the image is not present locally it is pulled first.
     *
     * <p>The containerd socket in k3s is at {@code /run/k3s/containerd/containerd.sock}.
     * {@code ctr} is the containerd CLI (not a k3s sub-command).
     */
    private static void loadImageIntoK3s(String imageName) throws Exception {
        log.info("Loading image '{}' into k3s...", imageName);

        boolean locallyAvailable = runProcess(
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", imageName),
                "docker image inspect " + imageName
        ) == 0;

        if (!locallyAvailable) {
            log.info("Image '{}' not found locally — pulling...", imageName);
            int pullRc = runProcess(List.of("docker", "pull", imageName), "docker pull " + imageName);
            if (pullRc != 0) throw new IllegalStateException("docker pull failed for: " + imageName);
        }

        Path tarPath = Files.createTempFile("k8s-image-", ".tar");
        try {
            if (runProcess(List.of("docker", "save", "-o", tarPath.toString(), imageName),
                    "docker save " + imageName) != 0) {
                throw new IllegalStateException("docker save failed for: " + imageName);
            }

            K3S.copyFileToContainer(MountableFile.forHostPath(tarPath), "/tmp/image-import.tar");

            var result = K3S.execInContainer(
                    "ctr", "--address", "/run/k3s/containerd/containerd.sock",
                    "images", "import", "/tmp/image-import.tar");

            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "ctr images import failed for '" + imageName + "': " + result.getStderr());
            }
            log.info("Image '{}' loaded into k3s successfully.", imageName);
        } finally {
            Files.deleteIfExists(tarPath);
        }
    }

    private static void applyManifest(String classpathResource) {
        log.info("Applying manifest: {}", classpathResource);
        try (var stream = KubernetesDataPrepperTest.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            assertThat(stream)
                    .as("Manifest resource must be on classpath: " + classpathResource)
                    .isNotNull();
            k8sClient.load(stream).serverSideApply();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply manifest: " + classpathResource, e);
        }
    }

    private static void waitForDeploymentReady(String namespace, String name, Duration timeout) {
        log.info("Waiting for Deployment {}/{} to become ready (timeout: {})...", namespace, name, timeout);
        try {
            await()
                    .atMost(timeout)
                    .pollInterval(Duration.ofSeconds(2))
                    .pollDelay(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        Deployment d = k8sClient.apps().deployments()
                                .inNamespace(namespace).withName(name).get();
                        assertThat(d).as("Deployment %s/%s must exist", namespace, name).isNotNull();
                        Integer available = d.getStatus().getAvailableReplicas();
                        Integer desired   = d.getSpec().getReplicas();
                        assertThat(available)
                                .as("Deployment %s/%s: available=%d desired=%d", namespace, name, available, desired)
                                .isNotNull().isEqualTo(desired);
                    });
        } catch (Exception e) {
            logDeploymentDiagnostics(namespace, name);
            throw e;
        }
        log.info("Deployment {}/{} is ready.", namespace, name);
    }

    private static void logDeploymentDiagnostics(String namespace, String name) {
        try {
            k8sClient.pods().inNamespace(namespace).withLabel("app", name).list().getItems()
                    .forEach(pod -> {
                        log.error("Pod '{}' phase={}", pod.getMetadata().getName(), pod.getStatus().getPhase());
                        pod.getStatus().getContainerStatuses().forEach(cs ->
                                log.error("  container '{}' ready={} state={}", cs.getName(), cs.getReady(), cs.getState()));
                        try {
                            String logs = k8sClient.pods().inNamespace(namespace)
                                    .withName(pod.getMetadata().getName()).getLog(true);
                            if (logs != null && !logs.isBlank()) log.error("  Logs (last 30 lines):\n{}",
                                    logs.lines().skip(Math.max(0, logs.lines().count() - 30))
                                            .reduce("", (a, b) -> a + "\n" + b));
                        } catch (Exception ex) { log.warn("  Could not get logs: {}", ex.getMessage()); }
                    });
        } catch (Exception ex) { log.warn("Could not collect diagnostics: {}", ex.getMessage()); }
    }

    private static int runProcess(List<String> command, String description) throws Exception {
        var pb = new ProcessBuilder(command).directory(new File(".")).redirectErrorStream(true);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(description + " timed out");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) log.error("{} failed (exit {}): {}", description, exitCode, output);
        return exitCode;
    }
}






