package es.bluesolution.dlq_streaming.kubernetes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * JSON shape returned by {@code POST /drain/trigger}.
 * Mirrors {@code TriggerDrainResponse} — kept local to avoid a test compile
 * dependency on the production class.
 */
record DrainApiResponse(
        int releasedExpiredLeases,
        int claimedCount,
        int storedCount,
        int deletedCount,
        boolean stoppedBecauseReceiverFailed,
        String lastProcessedProcessId,
        String stopReason
) {}

/**
 * Kubernetes deployment tests for dlq-streaming.
 *
 * <h3>What is tested</h3>
 * <ol>
 *   <li>The application image can be deployed to a real Kubernetes cluster (k3s).</li>
 *   <li>Liveness and readiness probes become healthy after startup.</li>
 *   <li>The {@code POST /drain/trigger} endpoint responds with HTTP 200.</li>
 *   <li>The pod runs as a non-root user (Pod Security Standards compliance).</li>
 *   <li>The Deployment manifest defines resource requests and limits.</li>
 *   <li>Graceful shutdown: the pod terminates cleanly without errors.</li>
 *   <li>The Deployment manifest wires {@code DLQ_WORKER_ID} from {@code metadata.name}
 *       via the Kubernetes Downward API (not a static ConfigMap value).</li>
 *   <li>The running pod has {@code DLQ_WORKER_ID} set to its own pod name at runtime.</li>
 * </ol>
 *
 * <h3>Infrastructure</h3>
 * <pre>
 * Testcontainers k3s  — lightweight Kubernetes cluster in Docker.
 * Fabric8             — Kubernetes Java client for manifest application and assertions.
 * PostgreSQL          — deployed inside k3s (Testcontainers pulls the image).
 * dlq-streaming image — built from the project Dockerfile and loaded into k3s.
 * </pre>
 *
 * <h3>Image loading sequence</h3>
 * <ol>
 *   <li>The Maven {@code kubernetes-tests} profile runs {@code package} then
 *       {@code docker build -t dlq-streaming:k8s-test .} before the tests.</li>
 *   <li>{@link #loadAppImageIntoK3s()} saves the image to a tar file and imports
 *       it into k3s containerd using {@code k3s ctr images import}.</li>
 *   <li>The test deployment uses {@code imagePullPolicy: Never} so k3s never
 *       tries to pull from a registry.</li>
 * </ol>
 *
 * <h3>Port access</h3>
 * The k3s container exposes NodePort 30080 (API) and 30081 (management).
 * {@code K3sContainer.getMappedPort(30080)} returns the host-side mapped port.
 *
 * <h3>Running the tests</h3>
 * <pre>
 *   # Build the Docker image first
 *   docker build -t dlq-streaming:k8s-test .
 *
 *   # Then run Kubernetes tests
 *   ./mvnw test -Pkubernetes-tests
 * </pre>
 *
 * <h3>TDD findings</h3>
 * <ul>
 *   <li>{@link #podRunsAsNonRoot()} enforces the non-root security context added to
 *       the Dockerfile ({@code adduser -S -u 1001}).</li>
 *   <li>{@link #deploymentDefinesResourceRequestsAndLimits()} enforces that resource
 *       governance is present (prevents OOMKilled in burstable namespaces).</li>
 *   <li>{@link #readinessProbeBecomesHealthyAfterDatabaseConnection()} proved that
 *       {@code socketTimeout} in the JDBC URL is required; without it the readiness
 *       probe hangs indefinitely when the DB is slow to start.</li>
 *   <li>{@link #deploymentInjectsDlqWorkerIdFromPodNameViaDownwardApi()} enforces that
 *       {@code DLQ_WORKER_ID} is sourced from {@code metadata.name} (Downward API),
 *       not from a static ConfigMap value. This prevents two replicas from sharing
 *       the same {@code claimed_by} identity in the dead-letter lease table.</li>
 *   <li>{@link #runningPodHasDlqWorkerIdEqualToPodName()} proves the Downward API
 *       wiring is correct at runtime: {@code printenv DLQ_WORKER_ID} inside the
 *       container produces the actual Kubernetes pod name.</li>
 * </ul>
 */
@Tag("kubernetes")
@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KubernetesDeploymentTest {


    // ── Image names ────────────────────────────────────────────────────────
    /** Image tag that the Maven profile builds before these tests. */
    static final String APP_IMAGE      = "dlq-streaming:k8s-test";

    /** PostgreSQL image — pre-loaded into k3s to avoid an in-container hub pull. */
    static final String POSTGRES_IMAGE = "postgres:17-alpine";

    /**
     * Curl image used by the CronJob to POST to /drain/trigger.
     * Pre-loaded into k3s so the CronJob Job pods start with imagePullPolicy: Never.
     */
    static final String CURL_IMAGE = "curlimages/curl:8.14.1";

    /**
     * K3s version to use. Tracks the same Kubernetes minor release as the
     * production cluster (see {@code k8s/base/deployment.yaml}).
     * Aligned with {@code kindest/node:v1.33.0} used in idp-concept.
     */
    static final String K3S_IMAGE  = "rancher/k3s:v1.32.1-k3s1";

    // ── K3s container ─────────────────────────────────────────────────────
    /**
     * K3s container with NodePorts 30080 (API) and 30081 (management) exposed
     * to the host via dynamically assigned ports.
     *
     * <p>K3sContainer already sets privileged mode and disables Traefik by default.
     *
     * <p><b>Important</b>: {@code withExposedPorts} REPLACES the whole port list, so port
     * 6443 (the Kubernetes API server) must be included explicitly — otherwise the
     * K3sContainer startup check ({@code containerIsStarted}) fails with
     * "Requested port (6443) is not mapped". Without port 6443, the kubeconfig
     * returned by {@code getKubeConfigYaml()} cannot communicate with the API server.
     */
    @Container
    static final K3sContainer K3S = new K3sContainer(DockerImageName.parse(K3S_IMAGE))
            .withExposedPorts(6443, 30080, 30081);

    // ── Kubernetes client ──────────────────────────────────────────────────
    static KubernetesClient k8sClient;

    // ── Rest clients for HTTP assertions ──────────────────────────────────
    static RestClient apiClient;
    static RestClient mgmtClient;

    // ── Direct PostgreSQL access for operational tests ─────────────────────
    /**
     * Fabric8 port-forward that tunnels the postgres pod's port 5432 to a
     * dynamically assigned local port. Opened once in {@link #setUpCluster()}
     * and closed in {@link #tearDownCluster()}.
     */
    static LocalPortForward pgPortForward;
    /** HikariCP datasource pointing at the tunnelled PostgreSQL port. */
    static HikariDataSource pgDataSource;
    /** Spring JdbcClient for seeding and verifying the dead-letter table. */
    static JdbcClient pgJdbcClient;

    /** Jackson ObjectMapper for parsing JSON responses from the drain API. */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── Setup ──────────────────────────────────────────────────────────────

    @BeforeAll
    static void setUpCluster() throws Exception {
        log.info("=== Kubernetes deployment test setup starting ===");

        // 1. Build the kubeconfig from k3s and create the fabric8 client.
        var kubeConfig = K3S.getKubeConfigYaml();
        var config = Config.fromKubeconfig(kubeConfig);
        k8sClient = new KubernetesClientBuilder().withConfig(config).build();

        // 2. Load images into k3s containerd to avoid in-container registry pulls.
        //    Pre-loading both images makes startup deterministic and avoids hub rate-limits.
        loadImageIntoK3s(POSTGRES_IMAGE);
        loadImageIntoK3s(CURL_IMAGE);       // required by the CronJob test (Order 17)
        loadAppImageIntoK3s();

        // 3. Apply test manifests: PostgreSQL first, then app.
        applyManifest("kubernetes/postgres.yaml");
        waitForDeploymentReady("default", "postgres", Duration.ofSeconds(120));

        applyManifest("kubernetes/app-config.yaml");
        applyManifest("kubernetes/app-deployment.yaml");
        waitForDeploymentReady("default", "dlq-streaming", Duration.ofSeconds(180));

        // 4b. Apply the CronJob used by Order 17.
        //     curlimages/curl is already loaded in step 2.
        applyManifest("kubernetes/cronjob-test.yaml");

        // 4. Configure REST clients once the app is running.
        String host       = K3S.getHost();
        int    apiPort    = K3S.getMappedPort(30080);
        int    mgmtPort   = K3S.getMappedPort(30081);

        apiClient  = RestClient.builder().baseUrl("http://" + host + ":" + apiPort).build();
        mgmtClient = RestClient.builder().baseUrl("http://" + host + ":" + mgmtPort).build();

        // 5. Open a direct JDBC tunnel to PostgreSQL inside k3s.
        //    Fabric8 LocalPortForward tunnels postgres:5432 -> 127.0.0.1:<dynamic>.
        //    This lets operational tests seed and verify data without needing a
        //    NodePort for PostgreSQL in the test manifests.
        openPostgresPortForward();

        log.info("=== Cluster ready — API: {}:{}, management: {}:{} ===", host, apiPort, host, mgmtPort);
    }

    @AfterAll
    static void tearDownCluster() {
        log.info("=== Kubernetes deployment test teardown ===");

        // Close the PostgreSQL JDBC tunnel opened for operational tests.
        closePostgresPortForward();

        // Close the Fabric8 client (frees HTTP connections to the k3s API server).
        if (k8sClient != null) {
            try {
                k8sClient.close();
            } catch (Exception e) {
                log.warn("Error closing k8s client: {}", e.getMessage());
            }
        }

        // Remove the test image built by the Maven profile.
        // This prevents accumulating a new dlq-streaming:k8s-test image on the
        // developer's machine after every test run.
        try {
            int rc = runProcess(List.of("docker", "rmi", APP_IMAGE), "docker rmi " + APP_IMAGE);
            if (rc == 0) {
                log.info("Test image {} removed.", APP_IMAGE);
            } else {
                log.warn("Could not remove test image {} (exit {}) — may not exist.", APP_IMAGE, rc);
            }
        } catch (Exception e) {
            log.warn("Could not remove test image {}: {}", APP_IMAGE, e.getMessage());
        }

        // Prune dangling (untagged) images left over when the build tag was moved
        // to the new image on the re-run (previous image becomes <none>:<none>).
        try {
            runProcess(List.of("docker", "image", "prune", "-f"), "docker image prune -f");
            log.info("Dangling images pruned.");
        } catch (Exception e) {
            log.warn("Could not prune dangling images: {}", e.getMessage());
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Pod becomes Ready after startup probes pass")
    void podBecomesReady() {
        var pods = k8sClient.pods()
                .inNamespace("default")
                .withLabel("app", "dlq-streaming")
                .list()
                .getItems();

        assertThat(pods)
                .as("At least one dlq-streaming pod must exist")
                .isNotEmpty();

        var pod = pods.getFirst();
        boolean isReady = pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

        assertThat(isReady)
                .as("Pod should be in Ready state: %s", pod.getMetadata().getName())
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("Liveness probe returns UP on /actuator/health/liveness")
    void livenessProbeReturnsUp() {
        var body = mgmtClient.get()
                .uri("/actuator/health/liveness")
                .retrieve()
                .body(String.class);

        assertThat(body)
                .as("Liveness endpoint must report UP")
                .contains("\"status\":\"UP\"");
    }

    @Test
    @Order(3)
    @DisplayName("Readiness probe becomes healthy after database connection is established")
    void readinessProbeBecomesHealthyAfterDatabaseConnection() {
        // This test validates that the socketTimeout in the JDBC URL prevents
        // the readiness probe from hanging when the DB is slow to accept connections.
        // Finding: without ?socketTimeout=5, the HikariCP startup hangs >30 s and
        // the readiness probe fails, causing the deployment to roll back.
        var body = mgmtClient.get()
                .uri("/actuator/health/readiness")
                .retrieve()
                .body(String.class);

        assertThat(body)
                .as("Readiness endpoint must report UP (socketTimeout in JDBC URL prevents hang)")
                .contains("\"status\":\"UP\"");
    }

    @Test
    @Order(4)
    @DisplayName("POST /drain/trigger returns 200 with drain result")
    void drainTriggerEndpointReturns200() {
        // Diagnostic: log registered mappings to understand missing controllers.
        try {
            String mappings = mgmtClient.get().uri("/actuator/mappings").retrieve().body(String.class);
            boolean hasDrain = mappings != null && mappings.contains("/drain/trigger");
            if (!hasDrain) {
                String beans = mgmtClient.get().uri("/actuator/beans").retrieve().body(String.class);
                log.warn("=== /drain/trigger NOT in mappings! Registered beans excerpt: {}",
                        beans == null ? "null" : beans.substring(0, Math.min(2000, beans.length())));
            } else {
                log.info("=== /drain/trigger is registered in mappings.");
            }
        } catch (Exception diagEx) {
            log.warn("Could not fetch actuator mappings: {}", diagEx.getMessage());
        }

        var response = apiClient.post()
                .uri("/drain/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("POST /drain/trigger must return 2xx")
                .isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("POST /drain/trigger response body contains expected fields")
    void drainTriggerResponseBodyContainsDrainFields() {
        var body = apiClient.post()
                .uri("/drain/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);

        assertThat(body)
                .as("Drain response must include claimedCount")
                .contains("claimedCount")
                .contains("storedCount")
                .contains("stoppedBecauseReceiverFailed");
    }

    @Test
    @Order(6)
    @DisplayName("Pod runs as non-root user (UID 1001) — Pod Security Standards compliance")
    void podRunsAsNonRoot() {
        var pods = k8sClient.pods()
                .inNamespace("default")
                .withLabel("app", "dlq-streaming")
                .list()
                .getItems();

        assertThat(pods).isNotEmpty();
        var pod = pods.getFirst();

        // Check pod-level spec
        var podSpec = pod.getSpec();
        assertThat(podSpec.getAutomountServiceAccountToken())
                .as("Service account token must not be auto-mounted")
                .isNotEqualTo(Boolean.TRUE);

        // Check container-level security context
        var container = podSpec.getContainers().stream()
                .filter(c -> "dlq-streaming".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Container dlq-streaming not found"));

        var sc = container.getSecurityContext();
        assertThat(sc).as("Container security context must be defined").isNotNull();
        assertThat(sc.getRunAsNonRoot()).as("Must run as non-root").isTrue();
        assertThat(sc.getRunAsUser()).as("UID must be 1001").isEqualTo(1001L);
        assertThat(sc.getAllowPrivilegeEscalation()).as("No privilege escalation").isFalse();
        assertThat(sc.getReadOnlyRootFilesystem()).as("Read-only root filesystem").isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("Deployment defines resource requests and limits (prevents OOMKilled)")
    void deploymentDefinesResourceRequestsAndLimits() {
        var deployment = k8sClient.apps().deployments()
                .inNamespace("default")
                .withName("dlq-streaming")
                .get();

        assertThat(deployment).as("Deployment must exist").isNotNull();

        var container = deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                .filter(c -> "dlq-streaming".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Container dlq-streaming not found"));

        var resources = container.getResources();
        assertThat(resources.getRequests()).as("Resource requests must be set").isNotNull().isNotEmpty();
        assertThat(resources.getLimits()).as("Resource limits must be set").isNotNull().isNotEmpty();

        assertThat(resources.getRequests()).containsKey("memory");
        assertThat(resources.getLimits()).containsKey("memory");
    }

    @Test
    @Order(8)
    @DisplayName("Deployment defines liveness and readiness probes")
    void deploymentDefinesHealthProbes() {
        var deployment = k8sClient.apps().deployments()
                .inNamespace("default")
                .withName("dlq-streaming")
                .get();

        var container = deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                .filter(c -> "dlq-streaming".equals(c.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(container.getLivenessProbe())
                .as("Liveness probe must be defined")
                .isNotNull();
        assertThat(container.getLivenessProbe().getHttpGet())
                .as("Liveness probe must use HTTP GET")
                .isNotNull();
        assertThat(container.getLivenessProbe().getHttpGet().getPath())
                .isEqualTo("/actuator/health/liveness");

        assertThat(container.getReadinessProbe())
                .as("Readiness probe must be defined")
                .isNotNull();
        assertThat(container.getReadinessProbe().getHttpGet().getPath())
                .isEqualTo("/actuator/health/readiness");

        assertThat(container.getStartupProbe())
                .as("Startup probe must be defined to give JVM+Flyway time to start")
                .isNotNull();
    }

    @Test
    @Order(9)
    @DisplayName("Pod only runs one container (no unexpected sidecars)")
    void podHasExactlyOneContainer() {
        var pods = k8sClient.pods()
                .inNamespace("default")
                .withLabel("app", "dlq-streaming")
                .list()
                .getItems();

        assertThat(pods).isNotEmpty();
        assertThat(pods.getFirst().getSpec().getContainers())
                .as("Pod must have exactly one container")
                .hasSize(1);
    }

    // ── Operational tests (Orders 10–16) ────────────────────────────────
    // These tests exercise the application's business behaviour while it runs
    // in the real k3s cluster with a real PostgreSQL database.  They use the
    // Fabric8 LocalPortForward tunnel (postgres pod port 5432 → localhost)
    // to seed and verify data via JDBC, and the REST clients to call the API.

    @Test
    @Order(10)
    @DisplayName("Drain on empty table returns zero counts and HTTP 200")
    void drainOnEmptyTableReturnsZeroCounts() throws Exception {
        cleanDeadLetterTable();

        var response = callDrainApi();

        assertThat(response.claimedCount()).as("claimedCount").isZero();
        assertThat(response.storedCount()).as("storedCount").isZero();
        assertThat(response.deletedCount()).as("deletedCount").isZero();
        assertThat(response.stoppedBecauseReceiverFailed()).as("stoppedBecauseReceiverFailed").isFalse();
    }

    @Test
    @Order(11)
    @DisplayName("Drain consumes all 3 pending records and clears the table")
    void drainConsumesAllPendingRecordsAndClearsTable() throws Exception {
        cleanDeadLetterTable();
        seedPendingRecord("product-1_2026-05-23T10:15:30Z");
        seedPendingRecord("product-2_2026-05-23T10:16:30Z");
        seedPendingRecord("product-3_2026-05-23T10:17:30Z");

        assertThat(countAllRecords()).as("3 records seeded").isEqualTo(3);

        var response = callDrainApi();

        assertThat(response.claimedCount()).as("All 3 claimed").isEqualTo(3);
        assertThat(response.storedCount()).as("All 3 stored by receiver").isEqualTo(3);
        assertThat(response.deletedCount()).as("All 3 deleted from DB after dispatch").isEqualTo(3);
        assertThat(response.stoppedBecauseReceiverFailed()).as("Receiver did not fail").isFalse();
        assertThat(countAllRecords()).as("Table empty after drain").isZero();
    }

    @Test
    @Order(12)
    @DisplayName("Drain respects configured batch size: 15 records need 2 runs with batch=10")
    void drainRespectsBatchSizeAndRequiresMultipleRunsFor15Records() throws Exception {
        cleanDeadLetterTable();
        for (int i = 1; i <= 15; i++) {
            seedPendingRecord("product-" + i + "_2026-05-24T10:00:00Z");
        }
        assertThat(countAllRecords()).as("15 records seeded").isEqualTo(15);

        // First run: batch size is 10 (from ConfigMap DLQ_BATCH_SIZE=10)
        var first = callDrainApi();
        assertThat(first.claimedCount()).as("First run claims 10").isEqualTo(10);
        assertThat(first.deletedCount()).as("First run deletes 10").isEqualTo(10);
        assertThat(countAllRecords()).as("5 records remain after first run").isEqualTo(5);

        // Second run: remaining 5
        var second = callDrainApi();
        assertThat(second.claimedCount()).as("Second run claims remaining 5").isEqualTo(5);
        assertThat(second.deletedCount()).as("Second run deletes remaining 5").isEqualTo(5);
        assertThat(countAllRecords()).as("Table empty after second run").isZero();
    }

    @Test
    @Order(13)
    @DisplayName("Drain is idempotent: three consecutive calls on empty table all return 200 with zero counts")
    void drainIsIdempotentOnEmptyTable() throws Exception {
        cleanDeadLetterTable();

        for (int i = 1; i <= 3; i++) {
            var response = callDrainApi();
            assertThat(response.claimedCount())
                    .as("Run %d: claimedCount must be 0", i)
                    .isZero();
            assertThat(response.stoppedBecauseReceiverFailed())
                    .as("Run %d: must not stop due to receiver failure", i)
                    .isFalse();
        }
    }

    @Test
    @Order(14)
    @DisplayName("Concurrent drain calls do not double-process the same records (SKIP LOCKED semantics)")
    void concurrentDrainCallsDoNotDoubleProcessRecords() throws Exception {
        cleanDeadLetterTable();
        // Seed exactly 10 records — enough for two concurrent workers to compete over.
        for (int i = 1; i <= 10; i++) {
            seedPendingRecord("concurrent-" + i + "_2026-05-24T12:00:00Z");
        }

        // Fire two drain calls simultaneously.
        // With FOR UPDATE SKIP LOCKED, one worker claims all 10 (batch=10)
        // and the other finds nothing (0).  Either way, total must equal 10.
        var futureA = CompletableFuture.supplyAsync(() -> {
            try { return callDrainApi(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        var futureB = CompletableFuture.supplyAsync(() -> {
            try { return callDrainApi(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        var a = futureA.get(30, TimeUnit.SECONDS);
        var b = futureB.get(30, TimeUnit.SECONDS);

        int totalClaimed = a.claimedCount() + b.claimedCount();
        int totalDeleted = a.deletedCount() + b.deletedCount();

        assertThat(totalClaimed)
                .as("Total claimed across both workers must equal 10 (no double-claim)")
                .isEqualTo(10);
        assertThat(totalDeleted)
                .as("Total deleted must equal 10 (each record deleted exactly once)")
                .isEqualTo(10);
        assertThat(countAllRecords())
                .as("Table must be empty — all records processed exactly once")
                .isZero();
    }

    @Test
    @Order(15)
    @DisplayName("Prometheus metrics endpoint exposes JVM and Spring Boot metrics")
    void prometheusMetricsEndpointExposesExpectedMetrics() {
        var body = mgmtClient.get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);

        assertThat(body)
                .as("Prometheus endpoint must export JVM memory metrics")
                .contains("jvm_memory_used_bytes")
                .as("Prometheus endpoint must export process uptime")
                .contains("process_uptime_seconds")
                .as("Prometheus endpoint must export HTTP server metrics")
                .contains("http_server_requests_seconds");
    }

    @Test
    @Order(16)
    @DisplayName("Pod recovers after restart: drain still returns 200 (restart resilience)")
    void podRecoveryAfterRestartDrainStillWorks() throws Exception {
        cleanDeadLetterTable();
        seedPendingRecord("restart-test_2026-05-24T13:00:00Z");

        // Delete the current pod — the Deployment controller will create a replacement.
        var pods = k8sClient.pods()
                .inNamespace("default")
                .withLabel("app", "dlq-streaming")
                .list()
                .getItems();
        assertThat(pods).as("Pod must exist before restart").isNotEmpty();
        var podName = pods.getFirst().getMetadata().getName();

        log.info("Deleting pod {} to trigger restart...", podName);
        k8sClient.pods().inNamespace("default").withName(podName).delete();

        // Wait for the old pod to disappear and a new one to become Ready.
        await()
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    var current = k8sClient.pods()
                            .inNamespace("default")
                            .withLabel("app", "dlq-streaming")
                            .list()
                            .getItems();
                    // Must have a pod with a different name (old one deleted) that is Ready.
                    assertThat(current)
                            .as("Replacement pod must exist")
                            .anyMatch(p -> !podName.equals(p.getMetadata().getName())
                                    && p.getStatus().getConditions().stream()
                                            .anyMatch(c -> "Ready".equals(c.getType())
                                                       && "True".equals(c.getStatus())));
                });

        log.info("Replacement pod is Ready.");

        // Re-open the port-forward because it targets the old pod by name.
        closePostgresPortForward();
        openPostgresPortForward();

        // Drain the pre-seeded record to verify full operational recovery.
        var response = callDrainApi();

        assertThat(response.claimedCount())
                .as("After restart, drain must process the pre-seeded record")
                .isEqualTo(1);
        assertThat(response.stoppedBecauseReceiverFailed())
                .as("Receiver must not fail after restart")
                .isFalse();
    }

    @Test
    @Order(17)
    @DisplayName("CronJob: manual Job trigger calls POST /drain/trigger and completes with exit 0")
    void cronJobJobCompletesSuccessfully() throws Exception {
        // Pre-condition: the CronJob must exist (applied during setUpCluster).
        var cronJob = k8sClient.batch().v1().cronjobs()
                .inNamespace("default").withName("dlq-drain-trigger").get();
        assertThat(cronJob)
                .as("CronJob dlq-drain-trigger must exist in namespace default")
                .isNotNull();

        // Create a manual Job from the CronJob's jobTemplate.
        // This mirrors what the Kubernetes scheduler does when the cron schedule fires.
        String jobName = "dlq-drain-manual-" + System.currentTimeMillis();
        var job = new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace("default")
                .endMetadata()
                .withSpec(cronJob.getSpec().getJobTemplate().getSpec())
                .build();

        k8sClient.batch().v1().jobs().inNamespace("default").resource(job).create();
        log.info("Created manual Job '{}' from CronJob dlq-drain-trigger.", jobName);

        // Wait for the Job to succeed. The Job runs curl POST /drain/trigger
        // and exits 0 on HTTP 200, non-0 on HTTP 4xx/5xx (curl --fail).
        await()
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    var jobStatus = k8sClient.batch().v1().jobs()
                            .inNamespace("default").withName(jobName).get().getStatus();

                    assertThat(jobStatus.getSucceeded())
                            .as("CronJob manual trigger Job '%s' must complete with succeeded=1", jobName)
                            .isEqualTo(1);
                });

        log.info("CronJob manual trigger Job '{}' completed successfully.", jobName);
    }

    @Test
    @Order(18)
    @DisplayName("Deployment wires DLQ_WORKER_ID from metadata.name via Kubernetes Downward API — not a static ConfigMap value")
    void deploymentInjectsDlqWorkerIdFromPodNameViaDownwardApi() {
        // WHAT this test proves:
        //   The Deployment spec declares DLQ_WORKER_ID as a Downward API field reference
        //   (valueFrom.fieldRef.fieldPath = "metadata.name"), NOT as a static string or
        //   as a ConfigMap key.
        //
        // WHY this matters:
        //   DLQ_WORKER_ID is stored as claimed_by in the dead_letter_record table.
        //   If all replicas share the same static worker ID, the DELETE:
        //     DELETE FROM dlq.dead_letter_record WHERE claimed_by = :workerId
        //   can delete records claimed by a different replica, or a replica cannot
        //   delete its own records if another replica overwrites the claimed_by field.
        //   With Downward API, each pod is named uniquely by Kubernetes, so the lease
        //   ownership is always unambiguous.
        var deployment = k8sClient.apps().deployments()
                .inNamespace("default")
                .withName("dlq-streaming")
                .get();

        assertThat(deployment).as("Deployment must exist").isNotNull();

        var container = deployment.getSpec().getTemplate().getSpec().getContainers().stream()
                .filter(c -> "dlq-streaming".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Container 'dlq-streaming' not found in deployment spec"));

        var dlqWorkerIdEnv = container.getEnv().stream()
                .filter(e -> "DLQ_WORKER_ID".equals(e.getName()))
                .findFirst()
                .orElse(null);

        assertThat(dlqWorkerIdEnv)
                .as("DLQ_WORKER_ID env entry must be defined in the Deployment container spec")
                .isNotNull();

        assertThat(dlqWorkerIdEnv.getValue())
                .as("DLQ_WORKER_ID must not be a static string — it must use valueFrom (Downward API)")
                .isNullOrEmpty();

        assertThat(dlqWorkerIdEnv.getValueFrom())
                .as("DLQ_WORKER_ID must use valueFrom (Downward API), not a hard-coded value")
                .isNotNull();

        assertThat(dlqWorkerIdEnv.getValueFrom().getFieldRef())
                .as("DLQ_WORKER_ID.valueFrom must reference a pod field (fieldRef)")
                .isNotNull();

        assertThat(dlqWorkerIdEnv.getValueFrom().getFieldRef().getFieldPath())
                .as("DLQ_WORKER_ID must be sourced from metadata.name so each pod gets its own unique identity")
                .isEqualTo("metadata.name");
    }

    @Test
    @Order(19)
    @DisplayName("Running pod has DLQ_WORKER_ID set to its own pod name at runtime (Downward API verified end-to-end)")
    void runningPodHasDlqWorkerIdEqualToPodName() throws Exception {
        // WHAT this test proves:
        //   The Downward API wiring is correct end-to-end: the value that the JVM
        //   sees for DLQ_WORKER_ID at runtime equals the actual Kubernetes pod name.
        //   This is verified by exec-ing "printenv DLQ_WORKER_ID" inside the running
        //   container and comparing it to the pod name returned by the Kubernetes API.
        //
        // HOW the Downward API works:
        //   Kubernetes resolves fieldRef.fieldPath=metadata.name before starting the
        //   container. The kubelet injects the resolved pod name into the container
        //   environment at startup. The JVM reads this env var when Spring Boot starts
        //   and binds it to dlq-drain.scheduler.worker-id via @ConfigurationProperties.
        //
        // WHY exec is used instead of the actuator /env endpoint:
        //   exec reads the raw OS-level environment variable, bypassing Spring's
        //   property relaxed-binding. This gives a lower-level, more direct proof
        //   that the Kubernetes runtime injected the value, independent of Spring's
        //   property source resolution.
        var pods = k8sClient.pods()
                .inNamespace("default")
                .withLabel("app", "dlq-streaming")
                .withLabel("app.kubernetes.io/component", "app")  // exclude cronjob-runner pods
                .list()
                .getItems()
                .stream()
                .filter(p -> "Running".equals(p.getStatus().getPhase()))
                .toList();

        assertThat(pods).as("At least one dlq-streaming app pod must be running").isNotEmpty();
        var pod = pods.getFirst();
        var podName = pod.getMetadata().getName();

        log.info("Verifying DLQ_WORKER_ID in pod '{}' via exec...", podName);

        var outputStream = new ByteArrayOutputStream();
        try (ExecWatch exec = k8sClient.pods()
                .inNamespace("default")
                .withName(podName)
                .inContainer("dlq-streaming")
                .writingOutput(outputStream)
                .exec("printenv", "DLQ_WORKER_ID")) {

            // Wait for the exec to complete (printenv exits immediately).
            Integer exitCode = exec.exitCode().get(10, TimeUnit.SECONDS);

            assertThat(exitCode)
                    .as("printenv DLQ_WORKER_ID must exit 0 (env var must be set in container)")
                    .isZero();
        }

        String actualWorkerId = outputStream.toString(StandardCharsets.UTF_8).trim();
        log.info("Pod '{}' has DLQ_WORKER_ID='{}'", podName, actualWorkerId);

        assertThat(actualWorkerId)
                .as("DLQ_WORKER_ID must equal the pod name '%s' — Downward API wiring is correct end-to-end", podName)
                .isEqualTo(podName);
    }

    // ── Helpers ────────────────────────────────────────────────────────────


    // ── PostgreSQL port-forward + DB helpers ────────────────────────────────

    /**
     * Opens a Fabric8 {@link LocalPortForward} from the PostgreSQL pod's port 5432
     * to a dynamically assigned local port, then creates a HikariCP datasource and
     * Spring {@link JdbcClient} so operational tests can seed and verify data without
     * needing a NodePort for PostgreSQL in the test manifests.
     *
     * <p>The port-forward is re-used by all operational tests; it is closed in
     * {@link #tearDownCluster()} and may be re-opened in {@link #podRecoveryAfterRestartDrainStillWorks()}
     * because the port-forward targets the pod by name — after a pod restart a new
     * name is assigned and the old forward becomes stale.
     */
    static void openPostgresPortForward() {
        var pgPods = k8sClient.pods()
                .inNamespace("default")
                .withLabel("app", "postgres")
                .list()
                .getItems();
        assertThat(pgPods).as("PostgreSQL pod must exist when opening port-forward").isNotEmpty();
        var pgPodName = pgPods.getFirst().getMetadata().getName();

        pgPortForward = k8sClient.pods()
                .inNamespace("default")
                .withName(pgPodName)
                .portForward(5432);

        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + pgPortForward.getLocalPort() + "/dlq");
        cfg.setUsername("dlq_user");
        cfg.setPassword("test_password");
        cfg.setMaximumPoolSize(3);
        cfg.setConnectionTimeout(10_000);
        pgDataSource  = new HikariDataSource(cfg);
        pgJdbcClient  = JdbcClient.create(pgDataSource);
        log.info("PostgreSQL port-forward open: pod={}, localPort={}", pgPodName, pgPortForward.getLocalPort());
    }

    /**
     * Closes the HikariCP pool and the Fabric8 port-forward opened in
     * {@link #openPostgresPortForward()}.  Safe to call multiple times (idempotent).
     */
    static void closePostgresPortForward() {
        if (pgDataSource != null && !pgDataSource.isClosed()) {
            pgDataSource.close();
        }
        if (pgPortForward != null) {
            try {
                pgPortForward.close();
            } catch (Exception e) {
                log.warn("Error closing PostgreSQL port-forward: {}", e.getMessage());
            }
        }
    }

    /**
     * Truncates the {@code dlq.dead_letter_record} table via the port-forwarded
     * JDBC connection. Used at the start of each operational test to ensure a
     * clean slate regardless of the previous test's outcome.
     */
    private static void cleanDeadLetterTable() {
        pgJdbcClient.sql("TRUNCATE TABLE dlq.dead_letter_record RESTART IDENTITY").update();
        log.debug("dead_letter_record table truncated.");
    }

    /**
     * Inserts one {@code PENDING} dead-letter record with a minimal JSON payload.
     * {@code occurred_at} uses the column default ({@code now()}).
     *
     * @param processId value for the {@code process_id} column
     *                  (format: {@code <topic>_<ISO-8601-timestamp>})
     */
    private static void seedPendingRecord(String processId) {
        pgJdbcClient.sql("""
                INSERT INTO dlq.dead_letter_record(process_id, payload, status)
                VALUES (:processId, CAST(:payload AS jsonb), 'PENDING')
                """)
                .param("processId", processId)
                .param("payload", "{\"event\":\"test\",\"processId\":\"" + processId + "\"}")
                .update();
    }

    /** Returns the total number of rows in {@code dlq.dead_letter_record}. */
    private static int countAllRecords() {
        return pgJdbcClient
                .sql("SELECT COUNT(*) FROM dlq.dead_letter_record")
                .query(Integer.class)
                .single();
    }

    /**
     * Calls {@code POST /drain/trigger} and returns the parsed {@link DrainApiResponse}.
     *
     * @throws Exception if the HTTP call fails or JSON cannot be parsed
     */
    private static DrainApiResponse callDrainApi() throws Exception {
        var json = apiClient.post()
                .uri("/drain/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        assertThat(json).as("Drain API response body must not be null").isNotNull();
        return MAPPER.readValue(json, DrainApiResponse.class);
    }

    // ── Image helpers ───────────────────────────────────────────────────────

    /**
     * Saves the app Docker image to a tar file, copies it to the k3s container,
     * and imports it into k3s containerd.
     *
     * <p>This avoids the need for a Docker registry in CI. The image is available
     * to k3s pods with {@code imagePullPolicy: Never}.
     *
     * <p>The containerd socket in k3s is at {@code /run/k3s/containerd/containerd.sock},
     * NOT at the default {@code /run/containerd/containerd.sock}. The standalone
     * {@code ctr} binary (from containerd) requires this socket path explicitly.
     * Using {@code k3s ctr} is not valid — {@code ctr} is not a k3s sub-command.
     */
    /**
     * Ensures the application Docker image {@value APP_IMAGE} exists locally,
     * building it from the project Dockerfile if necessary, then loads it into k3s.
     *
     * <p>This method is self-contained: it never attempts a registry pull for the
     * application image (which is a locally-built image with no registry backing).
     */
    private static void loadAppImageIntoK3s() throws Exception {
        boolean locallyAvailable = runProcess(
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", APP_IMAGE),
                "docker image inspect " + APP_IMAGE
        ) == 0;

        if (!locallyAvailable) {
            log.info("Image '{}' not found locally — building from Dockerfile...", APP_IMAGE);
            // Locate project root (directory containing pom.xml).
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
     * Pulls the given Docker image to the host daemon (if not already present),
     * saves it to a tar, copies the tar into k3s, and imports it into containerd.
     *
     * <p>Pre-loading images avoids in-container Docker Hub pulls which are slow
     * inside k3s and subject to rate limiting.
     *
     * <p>For locally-built images (e.g. {@code dlq-streaming:k8s-test}) that do
     * not exist in any registry, the pull step is skipped automatically.
     *
     * @param imageName Docker image name (e.g. {@code "postgres:17-alpine"})
     */
    private static void loadImageIntoK3s(String imageName) throws Exception {
        log.info("Loading image '{}' into k3s...", imageName);

        // Only pull if the image is NOT already present in the local daemon.
        // docker image inspect exits 0 when the image is found locally.
        boolean locallyAvailable = runProcess(
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", imageName),
                "docker image inspect " + imageName
        ) == 0;

        if (!locallyAvailable) {
            log.info("Image '{}' not found locally — pulling from registry...", imageName);
            int pullRc = runProcess(List.of("docker", "pull", imageName), "docker pull " + imageName);
            if (pullRc != 0) {
                throw new IllegalStateException("docker pull failed for image: " + imageName);
            }
        } else {
            log.debug("Image '{}' already available locally — skipping pull.", imageName);
        }

        Path tarPath = Files.createTempFile("k8s-image-", ".tar");
        try {
            var saveResult = runProcess(
                    List.of("docker", "save", "-o", tarPath.toString(), imageName),
                    "docker save " + imageName
            );
            if (saveResult != 0) {
                throw new IllegalStateException("docker save failed for image: " + imageName);
            }

            K3S.copyFileToContainer(MountableFile.forHostPath(tarPath), "/tmp/image-import.tar");

            var importResult = K3S.execInContainer(
                    "ctr",
                    "--address", "/run/k3s/containerd/containerd.sock",
                    "images", "import",
                    "/tmp/image-import.tar"
            );
            if (importResult.getExitCode() != 0) {
                throw new IllegalStateException(
                        "ctr images import failed for '" + imageName + "': " + importResult.getStderr()
                );
            }
            log.info("Image '{}' loaded into k3s successfully.", imageName);
        } finally {
            Files.deleteIfExists(tarPath);
        }
    }

    /**
     * Applies a YAML manifest from the test classpath to the k3s cluster.
     * Handles multi-document YAML (separated by {@code ---}) via fabric8's
     * {@link KubernetesClient#load(InputStream)}.
     */
    private static void applyManifest(String classpathResource) {
        log.info("Applying manifest: {}", classpathResource);
        try (var stream = KubernetesDeploymentTest.class
                .getClassLoader()
                .getResourceAsStream(classpathResource)) {

            assertThat(stream)
                    .as("Manifest resource must be on classpath: " + classpathResource)
                    .isNotNull();

            k8sClient.load(stream).serverSideApply();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply manifest: " + classpathResource, e);
        }
    }

    /**
     * Waits until the named Deployment in the given namespace has all replicas
     * available, up to {@code timeout}. Polls every 2 seconds.
     * Logs pod events on failure to help diagnose startup problems.
     */
    private static void waitForDeploymentReady(String namespace, String name, Duration timeout) {
        log.info("Waiting for Deployment {}/{} to become ready (timeout: {})...", namespace, name, timeout);

        try {
            await()
                    .atMost(timeout)
                    .pollInterval(Duration.ofSeconds(2))
                    .pollDelay(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        Deployment d = k8sClient.apps().deployments()
                                .inNamespace(namespace)
                                .withName(name)
                                .get();

                        assertThat(d).as("Deployment %s/%s must exist", namespace, name).isNotNull();

                        Integer desired   = d.getSpec().getReplicas();
                        Integer available = d.getStatus().getAvailableReplicas();

                        assertThat(available)
                                .as("Deployment %s/%s: available=%d, desired=%d", namespace, name, available, desired)
                                .isNotNull()
                                .isEqualTo(desired);
                    });
        } catch (Exception e) {
            // Log pod events and state to help diagnose startup failure.
            try {
                var pods = k8sClient.pods().inNamespace(namespace)
                        .withLabel("app", name).list().getItems();
                for (Pod pod : pods) {
                    log.error("Pod '{}' phase={} conditions={}",
                            pod.getMetadata().getName(),
                            pod.getStatus().getPhase(),
                            pod.getStatus().getConditions());
                    for (var cs : pod.getStatus().getContainerStatuses()) {
                        log.error("  container '{}' ready={} state={} restarts={}",
                                cs.getName(), cs.getReady(), cs.getState(), cs.getRestartCount());
                    }
                    // Collect last container logs to identify crash cause.
                    try {
                        String logs = k8sClient.pods().inNamespace(namespace)
                                .withName(pod.getMetadata().getName())
                                .getLog(true);
                        if (logs != null && !logs.isBlank()) {
                            log.error("  Previous container logs (last 50 lines):\n{}",
                                    logs.lines().reduce("", (a, b) -> a + "\n" + b)
                                            .lines().skip(Math.max(0, logs.lines().count() - 50))
                                            .reduce("", (a, b) -> a + "\n" + b));
                        }
                    } catch (Exception logEx) {
                        log.warn("  Could not get container logs: {}", logEx.getMessage());
                    }
                }
                var events = k8sClient.v1().events().inNamespace(namespace).list().getItems();
                events.stream()
                        .filter(ev -> ev.getInvolvedObject().getName().startsWith(name))
                        .forEach(ev -> log.error("  Event: reason={} message={}", ev.getReason(), ev.getMessage()));
            } catch (Exception diagEx) {
                log.warn("Could not collect diagnostics: {}", diagEx.getMessage());
            }
            throw e;
        }

        log.info("Deployment {}/{} is ready.", namespace, name);
    }

    /**
     * Runs an external process and returns its exit code.
     */
    private static int runProcess(List<String> command, String description) throws Exception {
        log.debug("Running: {}", String.join(" ", command));
        var pb = new ProcessBuilder(command)
                .directory(new File("."))
                .redirectErrorStream(true);

        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        var exited = process.waitFor(120, TimeUnit.SECONDS);

        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException(description + " timed out after 120 s");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("{} failed (exit {}): {}", description, exitCode, output);
        }
        return exitCode;
    }
}












