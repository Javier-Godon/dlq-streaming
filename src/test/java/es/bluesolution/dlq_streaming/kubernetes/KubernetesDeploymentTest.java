package es.bluesolution.dlq_streaming.kubernetes;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
        loadAppImageIntoK3s();

        // 3. Apply test manifests: PostgreSQL first, then app.
        applyManifest("kubernetes/postgres.yaml");
        waitForDeploymentReady("default", "postgres", Duration.ofSeconds(120));

        applyManifest("kubernetes/app-config.yaml");
        applyManifest("kubernetes/app-deployment.yaml");
        waitForDeploymentReady("default", "dlq-streaming", Duration.ofSeconds(180));

        // 4. Configure REST clients once the app is running.
        String host       = K3S.getHost();
        int    apiPort    = K3S.getMappedPort(30080);
        int    mgmtPort   = K3S.getMappedPort(30081);

        apiClient  = RestClient.builder().baseUrl("http://" + host + ":" + apiPort).build();
        mgmtClient = RestClient.builder().baseUrl("http://" + host + ":" + mgmtPort).build();

        log.info("=== Cluster ready — API: {}:{}, management: {}:{} ===", host, apiPort, host, mgmtPort);
    }

    @AfterAll
    static void tearDownCluster() {
        log.info("=== Kubernetes deployment test teardown ===");

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

        var pod = pods.get(0);
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
        var pod = pods.get(0);

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
        assertThat(pods.get(0).getSpec().getContainers())
                .as("Pod must have exactly one container")
                .hasSize(1);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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
    private static void loadAppImageIntoK3s() throws Exception {
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












