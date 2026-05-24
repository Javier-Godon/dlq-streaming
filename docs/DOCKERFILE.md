# Dockerfile Deep Dive — dlq-streaming

A step-by-step explanation of every decision in the production Dockerfile,
with links to official documentation and the TDD findings that drove the choices.

**File**: [`Dockerfile`](../Dockerfile)

---

## Architecture: multi-stage build

```
Stage 1 – builder         Stage 2 – runtime
─────────────────         ─────────────────
eclipse-temurin:          alpine:3.21
  25-jdk-alpine
  │
  ├─ mvn dependency:go-offline  ←── Maven deps layer (cached unless pom.xml changes)
  ├─ mvn package -DskipTests    ←── Application fat JAR
  ├─ java -Djarmode=tools       ←── Spring Boot layer extraction (4 layers)
  │    extract --layers ...
  └─ jlink                      ←── Custom JRE (~65 MB vs. ~217 MB full JRE)
                                         │
                                         └─ /opt/jre-minimal
                                              ├─ COPY jlink JRE
                                              ├─ COPY Spring Boot layers (4 COPY, ordered by volatility)
                                              ├─ RUN fix AutoConfiguration.imports path
                                              ├─ USER appuser (UID 1001)
                                              └─ ENTRYPOINT JarLauncher
```

The multi-stage build means the runtime image **never contains** the JDK, Maven, or build
toolchain — only what is needed to run the application.

**Reference**: [Docker multi-stage builds](https://docs.docker.com/build/building/multi-stage/)

---

## Stage 1: builder

### Base image selection

```dockerfile
FROM eclipse-temurin:25-jdk-alpine AS builder
```

- **Eclipse Temurin** is the production-grade OpenJDK distribution maintained by the
  Adoptium project. It is the successor to AdoptOpenJDK and the recommended JDK for
  production workloads.
  → [Eclipse Temurin](https://adoptium.net/temurin/)
  → [eclipse-temurin Docker Hub](https://hub.docker.com/_/eclipse-temurin)

- **Java 25** is used because `jlink` with `--compress=zip-6` produces smaller JREs in
  Java 22+ (improved compression algorithm).
  → [Java 25 release notes](https://openjdk.org/projects/jdk/25/)

- **Alpine** variant reduces the builder layer size. The builder stage's size does not affect
  the final image, but smaller layers mean faster CI cache pulls.
  → [alpine Docker Hub](https://hub.docker.com/_/alpine)

### Layer caching: dependency download before source copy

```dockerfile
WORKDIR /build

# Download Maven dependencies BEFORE copying source code.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline --no-transfer-progress -q
```

This is the most important cache optimisation in the Dockerfile.

**Why**: Docker/BuildKit caches each `RUN`, `COPY`, and `ADD` layer. If the `pom.xml` has
not changed, the `dependency:go-offline` layer is reused from cache. A typical Maven download
takes 60–120 seconds; with caching it takes ~0 seconds.

The source copy comes AFTER the dependency download:

```dockerfile
COPY src ./src
RUN ./mvnw package --no-transfer-progress -DskipTests -q
```

**Rule**: copy files that change less frequently before files that change more frequently.
pom.xml changes less often than source code.

**Reference**:
- [Docker layer caching](https://docs.docker.com/build/cache/)
- [Maven wrapper](https://maven.apache.org/wrapper/)

### `-DskipTests` in the Docker build

Tests are NOT run inside Docker. Reasons:
1. The CI pipeline runs tests separately with proper Testcontainers support.
2. Docker build does not have access to Docker-in-Docker by default.
3. Build time is a concern in CI — tests should not block image delivery.

### Spring Boot layer extraction with `jarmode=tools`

```dockerfile
RUN java -Djarmode=tools -jar target/*.jar extract \
      --layers --launcher --destination /build/extracted
```

Spring Boot 3.3+ ships `jarmode=tools` (replaces the older `jarmode=layertools`).

This extracts the fat JAR into 4 directories ordered by change frequency:

```
extracted/
  dependencies/           ← Third-party JARs (SNAPSHOT-free, most stable)
  spring-boot-loader/     ← Spring Boot launcher classes
  snapshot-dependencies/  ← SNAPSHOT JARs (if any in pom.xml)
  application/            ← Application classes + resources (most volatile)
```

**Why four directories?** Each directory becomes a separate Docker `COPY` instruction in
Stage 2. If only application code changes, the `dependencies/` layer (the heaviest, ~50–100
MB) is reused from cache. The rebuild only re-pushes the lightweight `application/` layer.

**Reference**:
- [Spring Boot Docker packaging](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)
- [Spring Boot layers](https://docs.spring.io/spring-boot/reference/packaging/container-images/efficient-images.html#packaging.container-images.efficient-images.layering)

### `jlink` — custom minimal JRE

```dockerfile
RUN jlink \
      --add-modules \
        java.base,java.compiler,java.desktop,java.instrument,\
        java.management,java.management.rmi,java.naming,java.net.http,\
        ...
      --no-header-files \
      --no-man-pages \
      --compress=zip-6 \
      --strip-debug \
      --output /opt/jre-minimal
```

`jlink` builds a **custom JRE** containing only the modules the application needs.

| Option | Effect |
|---|---|
| `--add-modules` | List of Java modules to include (see below) |
| `--no-header-files` | Exclude C header files (not needed at runtime) |
| `--no-man-pages` | Exclude man pages |
| `--compress=zip-6` | ZIP compression level 6 (default zip-6 in Java 22+) |
| `--strip-debug` | Remove debug symbols (~20% size reduction) |

**Result**: ~65 MB custom JRE vs. ~217 MB for `eclipse-temurin:25-jre-alpine`.

**Reference**:
- [jlink documentation](https://docs.oracle.com/en/java/docs/books/tutorial/deployment/selfContainedApps/index.html)
- [jlink man page](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html)

#### Module selection rationale

| Module | Why required |
|---|---|
| `java.base` | Mandatory — contains `java.lang`, `java.util`, `java.io`, etc. |
| `java.compiler` | Used by some reflection-heavy libraries (Groovy, expression languages) |
| `java.desktop` | AWT stubs required by some classpath scanners at startup |
| `java.instrument` | Spring AOP / byte-buddy / AspectJ instrumentation |
| `java.management` | JMX, used by Spring Boot Actuator and metrics |
| `java.management.rmi` | Required transitively by `java.management` |
| `java.naming` | JNDI (DataSource lookup, Tomcat embedded) |
| `java.net.http` | JDK `HttpClient` — used by the Data Prepper HTTP receiver |
| `java.prefs` | User preferences API (required by some transitive dependencies) |
| `java.rmi` | Required transitively by `java.management` |
| `java.scripting` | Groovy/Nashorn stubs in Spring Framework |
| `java.security.jgss` | GSSAPI / Kerberos (JDBC auth with some PG configurations) |
| `java.security.sasl` | SASL authentication (JDBC) |
| `java.sql` | JDBC API, required by Spring JDBC and Flyway |
| `java.transaction.xa` | XA transaction interfaces (required by Spring TX) |
| `java.xml` | XML parsing — Spring application context, Flyway SQL |
| `java.xml.crypto` | XML digital signatures (required by some Spring Security modules) |
| `jdk.attach` | JVM attach API (Spring devtools hot reload, profiling agents) |
| `jdk.crypto.cryptoki` | PKCS#11 provider (TLS cipher suites) |
| `jdk.crypto.ec` | Elliptic curve cryptography (TLS ECDHE key exchange) |
| `jdk.httpserver` | Embedded HTTP server stubs |
| `jdk.jfr` | Java Flight Recorder — Actuator metrics and CPU profiling |
| `jdk.management` | Extended JVM management |
| `jdk.management.agent` | JVM attach agent |
| `jdk.naming.dns` | DNS-based JNDI service provider |
| `jdk.net` | Extended socket options (TCP_NODELAY, SO_REUSEPORT) |
| `jdk.unsupported` | `sun.misc.Unsafe` — required by Netty, Kryo, Hibernate |
| `jdk.zipfs` | ZIP filesystem — Spring classpath scanning of JARs inside the launcher |

> **Finding missing modules**: if the application starts and immediately throws
> `ClassNotFoundException` or `NoClassDefFoundError`, add the missing module to the list and
> rebuild. Use `jdeps` on the fat JAR to discover module dependencies automatically:
> `jdeps --ignore-missing-deps --print-module-deps target/*.jar`

**Reference**: [Java Platform Module System](https://openjdk.org/projects/jigsaw/quick-start)

---

## Stage 2: runtime

### Base image

```dockerfile
FROM alpine:3.21
```

- `alpine:3.21` is ~7 MB. It provides:
  - `sh` + BusyBox utilities (needed for `HEALTHCHECK` with `wget`)
  - Package manager (`apk`) for installing `ca-certificates` and `tzdata`
  - No JVM, no build tools, no JDK — all stripped by the multi-stage pattern

- Why not `eclipse-temurin:25-jre-alpine`? Because `jlink` produces a smaller, more
  focused JRE. The full JRE image is ~217 MB; the custom jlink JRE is ~65 MB.

**Reference**: [Alpine Linux Docker image](https://hub.docker.com/_/alpine)

### OCI image labels

```dockerfile
LABEL org.opencontainers.image.title="dlq-streaming" \
      org.opencontainers.image.description="..." \
      org.opencontainers.image.source="https://github.com/..."
```

OCI (Open Container Initiative) standard labels provide metadata visible via
`docker inspect`, GitHub Packages, and container security scanners.

**Reference**: [OCI image spec annotations](https://specs.opencontainers.org/image-spec/annotations/)

### Runtime dependencies

```dockerfile
RUN apk add --no-cache ca-certificates tzdata
```

- **`ca-certificates`**: TLS trust store for outbound HTTPS connections (Data Prepper,
  external APIs). Without this, `javax.net.ssl.SSLHandshakeException: PKIX path building failed`
  occurs on any HTTPS call.
- **`tzdata`**: Time-zone database. Required by Flyway (timestamp column handling) and any
  code that formats `ZonedDateTime`.
- **`--no-cache`**: Do not cache the apk package index. Keeps the layer small (no index file
  written to disk).

**Reference**: [Alpine Linux packages](https://pkgs.alpinelinux.org/packages)

### Non-root user creation

```dockerfile
RUN addgroup -S -g 1001 appgroup && \
    adduser  -S -u 1001 -G appgroup -H -D appuser
```

| Flag | Meaning |
|---|---|
| `-S` | System account (no password, no home) |
| `-u 1001` | Fixed UID — matched in Kubernetes `runAsUser: 1001` and tested by `podRunsAsNonRoot` |
| `-G appgroup` | Assign to the `appgroup` group |
| `-H` | No home directory (nothing to create) |
| `-D` | No password |

**Why UID 1001?** UIDs 0–999 are reserved for system accounts in Alpine (root, daemon, etc.).
1001 is a safe non-conflicting choice. It MUST match the `runAsUser` in the Kubernetes
Deployment manifest and be tested by a Kubernetes test.

**TDD finding**: `KubernetesDeploymentTest.podRunsAsNonRoot()` verifies `runAsUser: 1001`.
This test drove the choice of UID 1001 in the Dockerfile.

**Reference**: [Alpine adduser](https://wiki.alpinelinux.org/wiki/Setting_up_a_new_user)

### Custom JRE copy

```dockerfile
COPY --from=builder /opt/jre-minimal /opt/jre-minimal

ENV JAVA_HOME=/opt/jre-minimal
ENV PATH="${JAVA_HOME}/bin:${PATH}"
```

Copies only the custom jlink JRE from the builder stage. The JDK itself is not copied.

`PATH` is updated so `java` is on the path (useful for debugging with `docker exec`).

### Spring Boot layer copy (ordered by volatility)

```dockerfile
WORKDIR /app

COPY --from=builder --chown=appuser:appgroup /build/extracted/dependencies/            ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/spring-boot-loader/      ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/snapshot-dependencies/   ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/application/             ./
```

**Order matters for Docker layer cache**: layers are invalidated from the first changed layer
downward. `dependencies/` changes only when `pom.xml` changes; `application/` changes on
every source code edit. Placing stable layers first means that a code change only
invalidates the `application/` layer.

**`--chown=appuser:appgroup`**: files are owned by the non-root user from the moment they
are copied. This avoids needing a `RUN chown -R` command which would create an extra layer.

### AutoConfiguration import path fix

```dockerfile
RUN IMPORTS=/app/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports && \
    DEST=/app/BOOT-INF/classes/META-INF/spring && \
    if [ -f "$IMPORTS" ]; then mkdir -p "$DEST" && cp "$IMPORTS" "$DEST/"; fi
```

**Problem**: Spring Boot's `spring-boot-maven-plugin` places auto-configuration import files
at the JAR root level (`META-INF/spring/*.imports`). When running from an exploded directory
via `JarLauncher`, the `LaunchedURLClassLoader` only scans `BOOT-INF/classes/` and
`BOOT-INF/lib/*.jar` — the root directories are NOT on the classpath. This means custom
`@AutoConfiguration` classes are silently not discovered.

**Fix**: Copy the `.imports` file into `BOOT-INF/classes/META-INF/spring/` so the launched
classloader can find it alongside the Spring Boot auto-configurations.

**Reference**: [Spring Boot auto-configuration](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)

### Switch to non-root user

```dockerfile
USER appuser
```

All subsequent commands and the `ENTRYPOINT` run as `appuser` (UID 1001). This is required
by Kubernetes Pod Security Standards (`restricted` profile: `runAsNonRoot: true`).

**Reference**: [Kubernetes Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/)

### JVM flags

```dockerfile
ENV JAVA_TOOL_OPTIONS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75 \
  -XX:+UseZGC \
  -Djava.security.egd=file:/dev/./urandom \
  -Djdk.httpclient.keepalive.timeout=30"
```

| Flag | Purpose |
|---|---|
| `-XX:+UseContainerSupport` | Read cgroup CPU and memory limits from the container. Without this, the JVM uses host resources and may allocate an enormous heap. Available since Java 10, enabled by default since Java 11. |
| `-XX:MaxRAMPercentage=75` | Limit the JVM heap to 75% of the container's memory limit. Leaves room for non-heap memory: JVM native memory, ZGC regions, code cache, Metaspace. **Validated by `deploymentDefinesResourceRequestsAndLimits`** which catches missing memory limits that would cause `OOMKilled`. |
| `-XX:+UseZGC` | ZGC is a low-latency GC with sub-millisecond pause times. It is ideal for services with latency-sensitive drain loops. In Java 24+, ZGC is always generational (the old `-XX:+ZGenerational` flag was removed). |
| `-Djava.security.egd=file:/dev/./urandom` | Use `/dev/urandom` instead of `/dev/random` for the entropy source. `/dev/random` blocks when the entropy pool is empty, causing 30+ second TLS handshake delays in containerised environments. The `./` in the path bypasses a JDK path canonicalisation check. |
| `-Djdk.httpclient.keepalive.timeout=30` | JDK HttpClient connection keep-alive timeout. Prevents idle connections from being held open indefinitely between drain runs. |

**Reference**:
- [UseContainerSupport](https://www.oracle.com/java/technologies/javase/11-relnote-issues.html#JDK-8146115)
- [ZGC](https://wiki.openjdk.org/display/zgc)
- [MaxRAMPercentage](https://docs.oracle.com/en/java/javase/25/vm/java-virtual-machine-technology-guide.html)

### Exposed ports

```dockerfile
EXPOSE 8080 8081
```

- **8080**: Main HTTP API (`POST /drain/trigger`).
- **8081**: Actuator management port (liveness, readiness, metrics, Prometheus).

Separating ports allows the Kubernetes `Service` to restrict external access to port 8080
while liveness/readiness probes reach 8081 directly from kubelets without going through
the Service load balancer.

**Reference**: [Spring Boot management server port](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html#actuator.monitoring.customizing-management-server-address)

### HEALTHCHECK

```dockerfile
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1
```

A Docker-native health check is **belt-and-suspenders** alongside the Kubernetes probes.
It is used when:
- Running the container with `docker run` without a Kubernetes orchestrator.
- Local development with `docker-compose`.
- CI smoke tests before promoting the image.

Uses `wget` (from BusyBox in Alpine base) rather than `curl` to avoid adding another
package dependency.

**Note**: Kubernetes uses its own `livenessProbe` / `readinessProbe` / `startupProbe`
definitions in the Deployment manifest. The `HEALTHCHECK` is not visible to Kubernetes.

**Reference**: [Docker HEALTHCHECK](https://docs.docker.com/reference/dockerfile/#healthcheck)

### ENTRYPOINT

```dockerfile
ENTRYPOINT ["/opt/jre-minimal/bin/java", "org.springframework.boot.loader.launch.JarLauncher"]
```

- Uses the absolute path to `java` from the custom jlink JRE (`/opt/jre-minimal/bin/java`)
  rather than relying on `PATH`. This is more reliable in environments where `PATH` may
  be modified.
- `org.springframework.boot.loader.launch.JarLauncher` is the Spring Boot 3.2+ launcher
  class for exploded JARs. It sets up the `LaunchedURLClassLoader` that scans
  `BOOT-INF/classes/` and `BOOT-INF/lib/*.jar`.

**Why exec form `[...]` not shell form?** The exec form runs the process as PID 1, which
means it receives Unix signals (e.g. `SIGTERM` for graceful shutdown) directly. Shell form
(`CMD java ...`) runs `sh -c "java ..."` as PID 1, and the JVM becomes a child process that
may not receive the signal correctly.

**Reference**:
- [Spring Boot JarLauncher](https://docs.spring.io/spring-boot/reference/packaging/executable-jars.html)
- [Docker ENTRYPOINT exec form](https://docs.docker.com/reference/dockerfile/#entrypoint)
- [Kubernetes graceful shutdown](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/)

### `terminationGracePeriodSeconds`

The Deployment manifest sets `terminationGracePeriodSeconds: 30`. This matches the JVM
shutdown hook timeout, allowing in-flight `POST /drain/trigger` requests to complete before
the pod is killed.

**Reference**: [Kubernetes pod termination](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination)

---

## Resulting image size breakdown

| Component | Size |
|---|---|
| Alpine 3.21 base | ~7 MB |
| ca-certificates + tzdata | ~2 MB |
| Custom jlink JRE | ~65 MB |
| Spring Boot layers (deps + loader + app) | ~80–120 MB |
| **Total** | **~160–200 MB** |

vs. `eclipse-temurin:25-jre-alpine` base approach:

| Component | Size |
|---|---|
| eclipse-temurin JRE layer | ~217 MB |
| Spring Boot layers | ~80–120 MB |
| **Total** | **~297–337 MB** |

→ **~30–35% smaller image** using the custom JRE.

---

## Build commands

```bash
# Full build (Maven inside Docker — for CI and clean environments):
docker build -t dlq-streaming:latest .

# Fast local build (use pre-built JAR — skip Maven inside Docker):
./mvnw package -DskipTests
docker build -t dlq-streaming:local .

# Build for Kubernetes tests:
docker build -t dlq-streaming:k8s-test .

# Inspect layers:
docker history dlq-streaming:latest

# Check image size:
docker images dlq-streaming

# Verify JRE modules:
docker run --rm dlq-streaming:latest /opt/jre-minimal/bin/java --list-modules

# Verify non-root user:
docker run --rm dlq-streaming:latest id
# Expected: uid=1001(appuser) gid=1001(appgroup)
```

---

## Security checklist

| Concern | Mitigation | Dockerfile line |
|---|---|---|
| Non-root execution | `adduser -S -u 1001 appuser` + `USER appuser` | Stage 2 |
| No privilege escalation | Enforced at Kubernetes level via `allowPrivilegeEscalation: false` | Deployment manifest |
| Read-only root filesystem | Enforced at Kubernetes level via `readOnlyRootFilesystem: true` + `/tmp emptyDir` | Deployment manifest |
| No JDK in runtime image | Multi-stage build; only jlink JRE copied | Stage 2 COPY from builder |
| No build tools in runtime | Same as above | Stage 2 |
| Minimal Alpine base | No package manager artifacts; only `ca-certificates` and `tzdata` added | Stage 2 RUN apk |
| TLS trust store | `ca-certificates` package | Stage 2 RUN apk |
| Entropy for TLS | `-Djava.security.egd=file:/dev/./urandom` | ENV JAVA_TOOL_OPTIONS |
| Capabilities | `drop: [ALL]` in Kubernetes securityContext | Deployment manifest |
| OCI labels for traceability | `LABEL org.opencontainers.image.*` | Stage 2 LABEL |

