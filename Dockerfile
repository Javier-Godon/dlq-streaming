# =============================================================================
# dlq-streaming — Production Dockerfile
# =============================================================================
#
# Multi-stage build:
#   1. builder  — Maven + JDK 25: compiles the project, extracts Spring Boot
#                 layers, and produces a minimal custom JRE via jlink.
#   2. runtime  — Bare Alpine 3.21: copies only the jlink JRE (~65 MB) and
#                 the four Spring Boot layers. No full JRE image needed.
#
# Image size vs. eclipse-temurin:25-jre-alpine base:
#   eclipse-temurin:25-jre-alpine base layer : ~217 MB
#   jlink custom JRE (java.base + app modules): ~ 65 MB  ← 3× smaller JRE
#   Alpine base + ca-certificates + tzdata    :  ~ 9 MB
#   Resulting total                           : ~100 MB   (was ~251 MB)
#
# jlink modules selected (Spring Boot 4 + JDBC + Actuator + HTTP client):
#   java.base, java.compiler, java.desktop, java.instrument, java.management,
#   java.management.rmi, java.naming, java.net.http, java.prefs, java.rmi,
#   java.scripting, java.security.jgss, java.security.sasl, java.sql,
#   java.transaction.xa, java.xml, java.xml.crypto, jdk.attach,
#   jdk.crypto.cryptoki, jdk.crypto.ec, jdk.httpserver, jdk.jfr,
#   jdk.management, jdk.management.agent, jdk.naming.dns, jdk.net,
#   jdk.unsupported, jdk.zipfs
#
# Spring Boot layers (ordered least-volatile → most-volatile) are placed in
# separate COPY instructions so Docker / BuildKit caches them independently.
# A code-only change does NOT invalidate the dependencies layer.
#
# JVM configuration (JAVA_TOOL_OPTIONS):
#   -XX:+UseContainerSupport      honour cgroup CPU/memory limits
#   -XX:MaxRAMPercentage=75       use up to 75 % of the container memory heap
#   -XX:+UseZGC                   low-pause GC (ZGC is always generational in Java 24+)
#   -Djava.security.egd=...       faster SecureRandom for TLS handshakes
#
# Exposed ports:
#   8080  — HTTP API  (POST /drain/trigger)
#   8081  — Management / Actuator (liveness, readiness, health, metrics)
#
# K8s liveness probe: GET http://<pod>:8081/actuator/health/liveness
# K8s readiness probe: GET http://<pod>:8081/actuator/health/readiness
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 — Build, extract layers, and create minimal JRE
# Maven dependency layer is cached separately so successive builds are fast
# as long as pom.xml has not changed.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Download Maven dependencies before copying sources (layer cache optimisation).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline --no-transfer-progress -q

# Build the application (skip tests; behaviour is tested in the CI pipeline).
COPY src ./src
RUN ./mvnw package --no-transfer-progress -DskipTests -q

# Extract Spring Boot layers so the runtime stage can copy them with
# fine-grained cache control.
# jarmode=tools produces four directories:
#   dependencies/           — third-party JARs  (most stable)
#   spring-boot-loader/     — Spring Boot loader classes
#   snapshot-dependencies/  — SNAPSHOT dependencies (if any)
#   application/            — application classes and resources (most volatile)
RUN java -Djarmode=tools -jar target/*.jar extract \
      --layers --launcher --destination /build/extracted

# Build a minimal custom JRE that includes only the modules required by
# the application. This reduces the JRE footprint from ~198 MB (full JRE)
# to ~65 MB (jlink + zip-6 compression + stripped debug info).
#
# Module selection rationale:
#   java.base          — mandatory
#   java.compiler      — needed at runtime by some reflection-heavy libs
#   java.desktop       — AWT stubs required by certain classpath scanners
#   java.instrument    — Spring AOP / byte-buddy instrumentation
#   java.management*   — JMX + Actuator metrics
#   java.naming        — JNDI (DataSource lookup, embedded Tomcat)
#   java.net.http      — JDK HttpClient (receiver HTTP calls)
#   java.rmi           — required by java.management.rmi
#   java.scripting     — some Groovy/Nashorn stubs in Spring
#   java.security.*    — GSSAPI / SASL (JDBC auth)
#   java.sql           — JDBC API + Flyway
#   java.transaction.xa— XA transaction interfaces
#   java.xml*          — XML parsing (Spring context, Flyway)
#   jdk.attach         — JVM attach API (used by Spring devtools / agents)
#   jdk.crypto.*       — TLS cipher suites (EC + PKCS11)
#   jdk.httpserver     — embedded HTTP server stubs
#   jdk.jfr            — Java Flight Recorder (Actuator metrics)
#   jdk.management*    — extended JVM management
#   jdk.naming.dns     — DNS-based JNDI
#   jdk.net            — extended socket options
#   jdk.unsupported    — sun.misc.Unsafe (required by Netty / Kryo / etc.)
#   jdk.zipfs          — ZIP filesystem (Spring classpath scanning of JARs)
RUN jlink \
      --add-modules \
        java.base,java.compiler,java.desktop,java.instrument,\
java.management,java.management.rmi,java.naming,java.net.http,\
java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,\
java.sql,java.transaction.xa,java.xml,java.xml.crypto,\
jdk.attach,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.jfr,\
jdk.management,jdk.management.agent,jdk.naming.dns,jdk.net,\
jdk.unsupported,jdk.zipfs \
      --no-header-files \
      --no-man-pages \
      --compress=zip-6 \
      --strip-debug \
      --output /opt/jre-minimal

# -----------------------------------------------------------------------------
# Stage 2 — Runtime
# Bare Alpine + custom JRE (~65 MB) + Spring Boot layers.  No full JRE image.
# -----------------------------------------------------------------------------
FROM alpine:3.21

LABEL org.opencontainers.image.title="dlq-streaming" \
      org.opencontainers.image.description="DLQ drain service: streams dead-letter records from PostgreSQL to Data Prepper / OpenSearch." \
      org.opencontainers.image.source="https://github.com/bluesolution/dlq-streaming"

# Install only what is strictly necessary:
#   ca-certificates — TLS trust store for outbound HTTPS calls
#   tzdata          — time-zone data (Flyway timestamp handling)
# BusyBox wget is already present in Alpine base (used for HEALTHCHECK).
RUN apk add --no-cache ca-certificates tzdata

# Create a dedicated non-root user. UID/GID 1001 is arbitrary but avoids
# common internal Alpine accounts (root=0, daemon=1, ...).
RUN addgroup -S -g 1001 appgroup && \
    adduser  -S -u 1001 -G appgroup -H -D appuser

# Copy the jlink-generated custom JRE (~65 MB, Java 25).
COPY --from=builder /opt/jre-minimal /opt/jre-minimal

ENV JAVA_HOME=/opt/jre-minimal
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

# Copy layers from least to most volatile so Docker cache is maximally reused.
COPY --from=builder --chown=appuser:appgroup /build/extracted/dependencies/            ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/spring-boot-loader/      ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/snapshot-dependencies/   ./
COPY --from=builder --chown=appuser:appgroup /build/extracted/application/             ./

# Spring Boot's spring-boot-maven-plugin places META-INF/spring/*.imports at the
# ROOT of the fat JAR (not under BOOT-INF/classes/). When running via JarLauncher
# from an exploded directory, the LaunchedURLClassLoader's classpath only includes
# BOOT-INF/classes/ and BOOT-INF/lib/*.jar — the root is NOT on the classpath.
# This means custom @AutoConfiguration classes registered in
# src/main/resources/META-INF/spring/ are NOT discoverable by ImportCandidates.
#
# Fix: copy the imports file into BOOT-INF/classes/META-INF/spring/ so the
# LaunchedURLClassLoader finds it alongside the Spring Boot auto-configurations
# from the lib JARs. Multiple .imports files are merged by ImportCandidates.load().
RUN IMPORTS=/app/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports && \
    DEST=/app/BOOT-INF/classes/META-INF/spring && \
    if [ -f "$IMPORTS" ]; then mkdir -p "$DEST" && cp "$IMPORTS" "$DEST/"; fi

USER appuser

# JVM flags — container-aware heap sizing and low-pause GC.
# Note: -XX:+ZGenerational was removed in Java 24; ZGC is now always generational.
ENV JAVA_TOOL_OPTIONS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75 \
  -XX:+UseZGC \
  -Djava.security.egd=file:/dev/./urandom \
  -Djdk.httpclient.keepalive.timeout=30"

# Spring profile placeholder — override via K8s env var:
#   env: [{name: SPRING_PROFILES_ACTIVE, value: "k8s"}]
ENV SPRING_PROFILES_ACTIVE=""

EXPOSE 8080 8081

# Docker-native liveness check (belt-and-suspenders alongside K8s probes).
# Uses wget (BusyBox) which is included in Alpine base.
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["/opt/jre-minimal/bin/java", "org.springframework.boot.loader.launch.JarLauncher"]

