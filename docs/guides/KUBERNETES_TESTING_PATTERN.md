# Kubernetes Testing Pattern Guide

A reusable, production-ready pattern for testing Java/Spring Boot applications in a real
Kubernetes cluster (k3s) as part of the normal Maven test lifecycle.

---

## Overview and philosophy

The **Kubernetes testing pattern** closes the gap between:

- **Unit/integration tests** — fast but cannot prove pod startup, probe configuration,
  security context, resource limits, or CronJob scheduling.
- **Manual staging deployments** — prove behaviour but are slow, non-repeatable, and
  gate on environment availability.

With this pattern, a single Maven command (`./mvnw test -Pkubernetes-tests`) spins up a
real, isolated Kubernetes cluster (k3s in Docker), deploys the application, runs a
structured set of assertions, and tears everything down. The cluster lifecycle is owned by
the test, not by a shared environment.

```
./mvnw test -Pkubernetes-tests
      │
      ├─ exec-maven-plugin: docker build -t myapp:k8s-test .
      │
      └─ surefire: KubernetesDeploymentTest.java
                      │
                      ├─ @Container K3sContainer (Testcontainers k3s)
                      │
                      ├─ docker save → k3s ctr images import
                      │
                      ├─ Fabric8 KubernetesClient
                      │    ├─ apply PostgreSQL manifest
                      │    ├─ apply app ConfigMap / Secret / Deployment
                      │    └─ apply CronJob manifest
                      │
                      ├─ Fabric8 LocalPortForward → pod:5432 (JDBC seed/verify)
                      │
                      └─ NodePort → pod:8080 / pod:8081 (REST assertions)
```

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **Docker** | 20.10+ | Must be running; no special daemon config needed |
| **Maven** | 3.9+ | Or the project's `./mvnw` wrapper |
| **Java** | 17+ | To compile and run tests |
| **Disk space** | 2–4 GB | Docker images: k3s (~200 MB) + app + databases |

No Kubernetes CLI (`kubectl`) or pre-existing cluster is required.

---

## Key dependencies (Maven)

```xml
<!-- Testcontainers k3s — lightweight Kubernetes cluster in Docker -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>k3s</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>

<!-- Fabric8 Kubernetes client — deploy manifests, port-forward, assertions -->
<dependency>
    <groupId>io.fabric8</groupId>
    <artifactId>kubernetes-client</artifactId>
    <version>7.2.0</version>
    <scope>test</scope>
</dependency>

<!-- Awaitility — polling assertions for async pod readiness -->
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>${awaitility.version}</version>
    <scope>test</scope>
</dependency>
```

---

## Maven profile — `kubernetes-tests`

The key requirement is that the Docker image is built **before** the tests run. The
`exec-maven-plugin` handles this inside a Maven profile so it only executes when the
profile is activated.

```xml
<profile>
    <id>kubernetes-tests</id>
    <build>
        <plugins>
            <!-- Step 1: configure Surefire to find only kubernetes tests -->
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <includes>
                        <include>**/kubernetes/**Test.java</include>
                    </includes>
                    <excludes combine.self="override"/>
                    <groups>kubernetes</groups>
                </configuration>
            </plugin>

            <!-- Step 2: build the Docker image before tests run -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <!-- Prune dangling images from previous build -->
                    <execution>
                        <id>docker-prune</id>
                        <phase>test-compile</phase>
                        <goals><goal>exec</goal></goals>
                        <configuration>
                            <executable>docker</executable>
                            <arguments>
                                <argument>image</argument>
                                <argument>prune</argument>
                                <argument>-f</argument>
                            </arguments>
                            <successCodes><successCode>0</successCode><successCode>1</successCode></successCodes>
                        </configuration>
                    </execution>
                    <!-- Build and tag the test image -->
                    <execution>
                        <id>docker-build</id>
                        <phase>test-compile</phase>
                        <goals><goal>exec</goal></goals>
                        <configuration>
                            <executable>docker</executable>
                            <arguments>
                                <argument>build</argument>
                                <argument>-t</argument>
                                <argument>myapp:k8s-test</argument>
                                <argument>.</argument>
                            </arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

### Excluding kubernetes tests from the default build

Add this to the default `maven-surefire-plugin` configuration to prevent the k3s cluster
from starting on every `./mvnw test` run:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <excludes>
            <exclude>**/kubernetes/**</exclude>
        </excludes>
    </configuration>
</plugin>
```

---

## Test class structure

### Annotations

```java
@Tag("kubernetes")               // JUnit 5 tag — filtered in/out by Surefire <groups>
@Slf4j
@Testcontainers(disabledWithoutDocker = true)  // skip gracefully when Docker is absent
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)  // explicit ordering (see below)
class MyAppKubernetesTest {
```

### Static fields

```java
static final String APP_IMAGE      = "myapp:k8s-test";
static final String POSTGRES_IMAGE = "postgres:17-alpine";
static final String K3S_IMAGE      = "rancher/k3s:v1.32.1-k3s1";

@Container
static final K3sContainer K3S = new K3sContainer(DockerImageName.parse(K3S_IMAGE))
        // IMPORTANT: withExposedPorts REPLACES the entire list.
        // Port 6443 (Kubernetes API) must always be included.
        // Add NodePorts only for services that the test JVM calls directly.
        .withExposedPorts(6443, 30080, 30081);

static KubernetesClient k8sClient;
static RestClient apiClient;
static RestClient mgmtClient;
```

### `@BeforeAll`

```java
@BeforeAll
static void setUpCluster() throws Exception {
    // 1. Build Fabric8 client from k3s kubeconfig.
    var config = Config.fromKubeconfig(K3S.getKubeConfigYaml());
    k8sClient = new KubernetesClientBuilder().withConfig(config).build();

    // 2. Pre-load Docker images into k3s containerd.
    //    Required because k3s uses its own containerd, not the host daemon.
    loadImageIntoK3s(POSTGRES_IMAGE);
    loadAppImageIntoK3s();

    // 3. Apply manifests.
    applyManifest("kubernetes/postgres.yaml");
    waitForDeploymentReady("default", "postgres", Duration.ofSeconds(120));

    applyManifest("kubernetes/app-config.yaml");
    applyManifest("kubernetes/app-deployment.yaml");
    waitForDeploymentReady("default", "myapp", Duration.ofSeconds(180));

    // 4. Configure REST clients.
    String host = K3S.getHost();
    apiClient  = RestClient.builder().baseUrl("http://" + host + ":" + K3S.getMappedPort(30080)).build();
    mgmtClient = RestClient.builder().baseUrl("http://" + host + ":" + K3S.getMappedPort(30081)).build();
}
```

---

## Test ordering and test categories

Tests are ordered from cheapest to most expensive:

| Orders | Category | Description |
|---|---|---|
| 1–5    | **Deployment validation** | Pod readiness, probe endpoints, security context, resource limits |
| 6–10   | **API smoke tests** | Endpoint reachability and response shape |
| 11–15  | **Operational scenarios** | Business flows with real DB seed/verify via JDBC |
| 16+    | **Resilience** | Pod restart recovery, CronJob trigger |

### Why ordered?

- Early tests assert the infrastructure works before spending time on business flows.
- If a pod never becomes Ready, assertions 1-2 fail fast with a clear diagnostic message —
  instead of every operational test timing out.
- Operational tests can assume infrastructure is healthy.

---

## Image loading — why `docker save` + `ctr images import`

k3s uses its own **containerd** runtime, which is isolated from the host Docker daemon.
Simply running `docker build` on the host does **not** make the image available to k3s.

The loading sequence:
1. `docker image inspect <image>` — check if the image already exists in the host daemon.
2. `docker pull <image>` — if not present, pull it (external registry images only).
3. `docker save -o /tmp/image.tar <image>` — serialize the image to a tar archive.
4. `K3S.copyFileToContainer(...)` — copy the tar into the k3s container (via Docker exec).
5. `k3s ctr --address /run/k3s/containerd/containerd.sock images import /tmp/image.tar`
   — import the tar into k3s containerd.

> **Caution**: The k3s containerd socket is at `/run/k3s/containerd/containerd.sock`, NOT
> `/run/containerd/containerd.sock`. The `ctr` binary is the standard containerd CLI; it is
> NOT a k3s sub-command. `k3s ctr` does not exist.

```java
private static void loadImageIntoK3s(String imageName) throws Exception {
    boolean locallyAvailable = runProcess(
            List.of("docker", "image", "inspect", "--format", "{{.Id}}", imageName),
            "docker inspect " + imageName) == 0;

    if (!locallyAvailable) {
        runProcess(List.of("docker", "pull", imageName), "docker pull " + imageName);
    }

    Path tarPath = Files.createTempFile("k8s-image-", ".tar");
    try {
        runProcess(List.of("docker", "save", "-o", tarPath.toString(), imageName),
                   "docker save " + imageName);

        K3S.copyFileToContainer(MountableFile.forHostPath(tarPath), "/tmp/image-import.tar");

        var result = K3S.execInContainer(
                "ctr",
                "--address", "/run/k3s/containerd/containerd.sock",
                "images", "import", "/tmp/image-import.tar");

        if (result.getExitCode() != 0) {
            throw new IllegalStateException("ctr import failed: " + result.getStderr());
        }
    } finally {
        Files.deleteIfExists(tarPath);
    }
}
```

### `imagePullPolicy: Never` in test manifests

After loading the image into k3s containerd, all test manifests must set:

```yaml
imagePullPolicy: Never
```

This tells kubelet to use the already-imported image rather than trying to pull from a
registry (which would fail because the test image is not pushed to any registry).

---

## Accessing services from the test JVM

There are two approaches:

### NodePorts (simple, fewer dependencies)

Add NodePort services to the test manifest and expose those ports on the `K3sContainer`:

```yaml
# test manifest
apiVersion: v1
kind: Service
spec:
  type: NodePort
  ports:
    - port: 8080
      nodePort: 30080
```

```java
// K3sContainer setup
.withExposedPorts(6443, 30080)

// RestClient
RestClient.builder()
    .baseUrl("http://" + K3S.getHost() + ":" + K3S.getMappedPort(30080))
    .build();
```

**When to use**: Main application API and management ports that every test needs throughout
the test lifecycle.

### Fabric8 LocalPortForward (flexible, no NodePort needed)

```java
var forward = k8sClient.pods().inNamespace("default")
        .withName(podName).portForward(5432);

var dataSource = new HikariDataSource(/* url: "jdbc:postgresql://127.0.0.1:" + forward.getLocalPort() */);
```

**When to use**:
- When you need access to a port that does not warrant a NodePort (e.g., PostgreSQL for
  direct DB seeding/verification, WireMock admin API).
- When a separate deployment exists only for specific tests and you want to avoid exposing
  additional NodePorts on the K3sContainer.
- After pod restarts — re-open the forward to the new pod by name.

---

## Waiting for readiness

Always use `Awaitility` rather than `Thread.sleep()`:

```java
await()
    .atMost(Duration.ofSeconds(180))
    .pollInterval(Duration.ofSeconds(2))
    .pollDelay(Duration.ofSeconds(2))
    .untilAsserted(() -> {
        Deployment d = k8sClient.apps().deployments()
                .inNamespace(namespace).withName(name).get();
        assertThat(d).isNotNull();
        Integer available = d.getStatus().getAvailableReplicas();
        Integer desired   = d.getSpec().getReplicas();
        assertThat(available).isNotNull().isEqualTo(desired);
    });
```

Add a **diagnostics block** in the catch clause:

```java
} catch (Exception e) {
    // Log pod status and last N lines of container logs before re-throwing.
    // This saves 30 minutes of debugging when a test fails in CI.
    k8sClient.pods().inNamespace(namespace).withLabel("app", name).list().getItems()
        .forEach(pod -> {
            log.error("Pod '{}' phase={}", pod.getMetadata().getName(), pod.getStatus().getPhase());
            pod.getStatus().getContainerStatuses().forEach(cs ->
                log.error("  container '{}' ready={} state={}", cs.getName(), cs.getReady(), cs.getState()));
            try {
                String logs = k8sClient.pods().inNamespace(namespace)
                    .withName(pod.getMetadata().getName()).getLog(true);
                log.error("  Previous logs (last 30):\n{}",
                    logs.lines().skip(Math.max(0, logs.lines().count() - 30))
                        .reduce("", (a, b) -> a + "\n" + b));
            } catch (Exception ex) { /* ignore */ }
        });
    throw e;
}
```

---

## Deployment validation tests — checklist

These tests run on every project that uses this pattern. They verify deployment manifests
directly rather than just application behaviour, catching issues that unit/integration tests
cannot see.

| Test | k8s API used | What it proves |
|---|---|---|
| `podBecomesReady` | `pod.getStatus().getConditions()` | Startup + readiness probes pass |
| `livenessProbeReturnsUp` | `GET /actuator/health/liveness` | App reports healthy after startup |
| `readinessProbeBecomesHealthy` | `GET /actuator/health/readiness` | DB connection is healthy (proves `socketTimeout` in JDBC URL) |
| `apiEndpointReturns200` | HTTP via NodePort | Controller is registered and reachable |
| `podRunsAsNonRoot` | `pod.getSpec().getContainers().getFirst().getSecurityContext()` | `runAsNonRoot=true, runAsUser=1001, allowPrivilegeEscalation=false, readOnlyRootFilesystem=true` |
| `deploymentDefinesResourceRequestsAndLimits` | `deployment.getSpec().getTemplate().getSpec()` | Memory `requests` and `limits` are set (prevents OOMKilled) |
| `deploymentDefinesHealthProbes` | `container.getLivenessProbe()`, `.getReadinessProbe()`, `.getStartupProbe()` | All three probes defined |
| `podHasExactlyOneContainer` | `pod.getSpec().getContainers().size()` | No unexpected sidecars injected by admission webhooks |

---

## Operational scenario tests — JDBC seeding + drain verification

For applications that consume from a database, the pattern uses a Fabric8 `LocalPortForward`
to the database pod and a real JDBC connection:

```java
static LocalPortForward pgPortForward;
static JdbcClient pgJdbcClient;

static void openPostgresPortForward() {
    var pgPod = k8sClient.pods().inNamespace("default")
            .withLabel("app", "postgres").list().getItems().getFirst();
    pgPortForward = k8sClient.pods().inNamespace("default")
            .withName(pgPod.getMetadata().getName()).portForward(5432);
    var cfg = new HikariConfig();
    cfg.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + pgPortForward.getLocalPort() + "/mydb");
    cfg.setUsername("myuser");
    cfg.setPassword("mypassword");
    pgJdbcClient = JdbcClient.create(new HikariDataSource(cfg));
}
```

Seed data in tests:
```java
pgJdbcClient.sql("INSERT INTO myschema.my_table(id, status) VALUES (:id, 'PENDING')")
        .param("id", UUID.randomUUID())
        .update();
```

Verify data:
```java
int count = pgJdbcClient.sql("SELECT COUNT(*) FROM myschema.my_table").query(Integer.class).single();
assertThat(count).isZero();
```

---

## CronJob test pattern

The canonical way to test a Kubernetes CronJob without waiting for its schedule:

```java
// Get the CronJob from the cluster.
var cronJob = k8sClient.batch().v1().cronjobs()
        .inNamespace("default").withName("my-cronjob").get();

// Create a manual Job from the CronJob's jobTemplate.
// This mirrors exactly what the scheduler does when the cron expression fires.
String jobName = "manual-" + System.currentTimeMillis();
var job = new JobBuilder()
        .withNewMetadata()
            .withName(jobName).withNamespace("default")
        .endMetadata()
        .withSpec(cronJob.getSpec().getJobTemplate().getSpec())
        .build();
k8sClient.batch().v1().jobs().inNamespace("default").resource(job).create();

// Wait for the Job to complete (succeeded=1).
await()
    .atMost(Duration.ofSeconds(120))
    .pollInterval(Duration.ofSeconds(3))
    .untilAsserted(() -> {
        var status = k8sClient.batch().v1().jobs()
                .inNamespace("default").withName(jobName).get().getStatus();
        assertThat(status.getSucceeded()).isEqualTo(1);
    });
```

**Note**: ensure the CronJob container image is pre-loaded into k3s containerd and the
manifest uses `imagePullPolicy: Never`.

---

## Mock external service in the cluster (WireMock pattern)

When the application calls an external HTTP service (e.g., Data Prepper, an email service,
a webhook), you can deploy a WireMock instance inside the k3s cluster instead of reaching
a real external endpoint.

### Why in-cluster instead of a Testcontainers WireMock?

Running WireMock inside the cluster lets the application under test call it using the
same service URL format as production (`http://my-service.my-namespace:port/path`),
validating DNS resolution, service routing, and HTTP client configuration under real
Kubernetes networking.

### Manifest

```yaml
# mock-my-service.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mock-my-service
  namespace: default
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mock-my-service
  template:
    metadata:
      labels:
        app: mock-my-service
    spec:
      containers:
        - name: wiremock
          image: wiremock/wiremock:3.13.0
          imagePullPolicy: IfNotPresent
          args: ["--port", "8080", "--verbose"]
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /__admin/mappings
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 3
          volumeMounts:
            - name: wiremock-data
              mountPath: /home/wiremock
      volumes:
        - name: wiremock-data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: mock-my-service
  namespace: default
spec:
  type: ClusterIP
  selector:
    app: mock-my-service
  ports:
    - port: 9090          # match the expected service port
      targetPort: 8080    # WireMock internal port
```

### Test class setup

```java
static LocalPortForward wmPortForward;
static RestClient wmAdminClient;

// After deploying and waiting for readiness:
var wmPod = k8sClient.pods().inNamespace("default")
        .withLabel("app", "mock-my-service").list().getItems().getFirst();
wmPortForward = k8sClient.pods().inNamespace("default")
        .withName(wmPod.getMetadata().getName()).portForward(8080);
wmAdminClient = RestClient.builder()
        .baseUrl("http://127.0.0.1:" + wmPortForward.getLocalPort())
        .build();
```

### Configuring stubs per test

```java
@BeforeEach
void resetWireMockState() {
    wmAdminClient.post().uri("/__admin/requests/reset").retrieve().toBodilessEntity();
    wmAdminClient.post().uri("/__admin/mappings/reset").retrieve().toBodilessEntity();
    // Re-add default happy-path stub:
    wmAdminClient.post().uri("/__admin/mappings")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {
                  "request": {"method": "POST", "urlPath": "/api/ingest"},
                  "response": {"status": 200, "headers": {"Content-Type": "application/json"}, "body": "{}"}
                }
                """)
            .retrieve().toBodilessEntity();
}
```

### Verifying WireMock received requests

```java
int count = countWireMockRequests("/api/ingest", "POST");
assertThat(count).isEqualTo(3);

// Count helper:
private static int countWireMockRequests(String urlPath, String method) {
    var body = wmAdminClient.post()
            .uri("/__admin/requests/count")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"urlPath": "%s", "method": "%s"}""".formatted(urlPath, method))
            .retrieve()
            .body(String.class);
    return MAPPER.readTree(body).path("count").asInt();
}
```

### Simulating failures

```java
// Add a high-priority 503 stub (overrides the default 200 stub):
wmAdminClient.post().uri("/__admin/mappings")
        .contentType(MediaType.APPLICATION_JSON)
        .body("""
            {
              "priority": 1,
              "request": {"method": "POST", "urlPath": "/api/ingest"},
              "response": {"status": 503, "body": "Service Unavailable"}
            }
            """)
        .retrieve().toBodilessEntity();
```

---

## Cleanup and teardown

```java
@AfterAll
static void tearDownCluster() {
    // Close JDBC pool and port-forward tunnels.
    if (pgDataSource != null && !pgDataSource.isClosed()) pgDataSource.close();
    for (var pf : List.of(pgPortForward, wmPortForward)) {
        if (pf != null) { try { pf.close(); } catch (Exception e) { /* log */ } }
    }

    // Close Fabric8 client (frees HTTP connections to the API server).
    if (k8sClient != null) { try { k8sClient.close(); } catch (Exception e) { /* log */ } }

    // Remove the test image to prevent accumulation on the developer's machine.
    runProcess(List.of("docker", "rmi", APP_IMAGE), "docker rmi " + APP_IMAGE);
    runProcess(List.of("docker", "image", "prune", "-f"), "docker image prune -f");
}
```

---

## Test manifests — best practices

Keep test manifests separate from production manifests:

```
src/test/resources/kubernetes/
  postgres.yaml           — PostgreSQL deployment + service
  app-config.yaml         — ConfigMap + Secret for test env (in-memory receiver)
  app-deployment.yaml     — Deployment + NodePort service (imagePullPolicy: Never)
  cronjob-test.yaml       — CronJob (adapted for test namespace + imagePullPolicy: Never)
  mock-data-prepper.yaml  — WireMock deployment + ClusterIP service
  app-config-dataprepper.yaml  — ConfigMap for data-prepper integration tests
  app-deployment-dataprepper.yaml  — Separate deployment for DP tests
```

### Test manifest conventions

| Convention | Reason |
|---|---|
| `namespace: default` | Avoid namespace creation overhead in tests |
| `imagePullPolicy: Never` | No registry needed; images pre-loaded via `docker save` |
| No `imagePullSecrets` | Test cluster is isolated; no auth needed |
| Reduced resource limits | Test cluster runs on developer machine; reduce memory |
| Reduced timeouts and retries | Faster test feedback; configure separately from production |
| No Kustomize overlays | Manifests are test-specific; no need for layering |

### Security context in test manifests

Apply the same security context as production to test that the application actually runs
correctly with these constraints:

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1001
  runAsGroup: 1001
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: [ALL]
```

This is how the `podRunsAsNonRoot` test can catch mismatches between the Dockerfile
user and the deployment spec.

---

## Exporting this pattern to a new project

1. **Copy the Maven profile** (`kubernetes-tests`) into `pom.xml`.
2. **Add dependencies**: `k3s`, `kubernetes-client`, `awaitility`.
3. **Create `src/test/resources/kubernetes/`** with manifests for your app + databases.
4. **Copy the test class template** and adapt identifiers (image name, app label, namespace).
5. **Start with deployment validation tests** (pod readiness, probes, security, resources).
6. **Add operational tests** using LocalPortForward + JdbcClient if the app uses a database.
7. **Add CronJob test** if the app is scheduled via CronJob.
8. **Add mock service test** if the app calls an external HTTP service.

---

## Common pitfalls

| Pitfall | Symptom | Fix |
|---|---|---|
| Image not loaded before deployment | Pod stuck in `ImagePullBackOff` | Call `loadImageIntoK3s()` before `applyManifest()` |
| Wrong containerd socket path | `ctr import` fails with "connection refused" | Use `/run/k3s/containerd/containerd.sock`, not `/run/containerd/containerd.sock` |
| Port 6443 missing from `withExposedPorts` | `Requested port (6443) is not mapped` | Always include 6443 when calling `withExposedPorts()` |
| `imagePullPolicy: Always` in test manifest | `ImagePullBackOff` (image only in containerd, not registry) | Change to `Never` or `IfNotPresent` |
| JDBC `socketTimeout` missing | Readiness probe hangs indefinitely | Add `?socketTimeout=5` to the JDBC URL |
| `@BeforeAll` → `@Container` order | k3s not started when setup runs | `K3sContainer` is a `@Container` static field; Testcontainers starts it before `@BeforeAll` |
| Port-forward stale after pod restart | `Connection refused` after pod kill test | Re-open the port-forward by new pod name (see `podRecoveryAfterRestartDrainStillWorks`) |
| WireMock stub priority | Newer stub overrides existing stub | Set `"priority": 1` for high-priority failure stubs |
| WireMock `/home/wiremock` read-only | Cannot write runtime stub files | Mount an `emptyDir` to `/home/wiremock` and load stubs via admin API |

---

## Reference implementation

See `dlq-streaming` for a fully working example:

| File | Description |
|---|---|
| `src/test/java/.../kubernetes/KubernetesDeploymentTest.java` | 17 tests (deployment + operational + CronJob) |
| `src/test/java/.../kubernetes/KubernetesDataPrepperTest.java` | 4 tests (WireMock mock Data Prepper integration) |
| `src/test/resources/kubernetes/` | 7 test manifests |
| `pom.xml` `kubernetes-tests` profile | Full Maven profile with exec-maven-plugin |

---

## Verification commands

```bash
# Run all kubernetes tests (builds Docker image first):
./mvnw test -Pkubernetes-tests

# Run only a specific test class:
./mvnw test -Pkubernetes-tests -Dtest='KubernetesDeploymentTest'
./mvnw test -Pkubernetes-tests -Dtest='KubernetesDataPrepperTest'

# Run a specific test method:
./mvnw test -Pkubernetes-tests -Dtest='KubernetesDeploymentTest#podRunsAsNonRoot'

# Watch test output without progress bars:
./mvnw test -Pkubernetes-tests --no-transfer-progress 2>&1 | tee k8s-test.log
```

