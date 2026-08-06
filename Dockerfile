# Recovered from `5432372d^` (deleted 2026-06-25 by a "cleanup" commit that was purging .derivedData junk and
# took two CI-referenced artifacts with it — this file and scripts/run_web_e2e.py). container.yml never stopped
# building `file: Dockerfile`, it just never fired on a non-main branch. Ported to today's tree; every delta
# from the recovered original is commented below.

# --- web -------------------------------------------------------------------------------------------------
# Was `frontend/` — the surface is `web/` since the greenfield reset.
FROM node:22-bookworm-slim AS frontend-build
WORKDIR /web
# The OpenAPI TypeScript client is GENERATED and deliberately never tracked (web/.gitignore:1-4, mirroring the
# backend), so a clean checkout has no src/api/generated. @openapitools/openapi-generator-cli is a Java tool,
# which is why this stage needs a JRE — the recovered original never did, because back then it did not generate.
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless \
    && rm -rf /var/lib/apt/lists/*
# The specs must land BEFORE `npm ci`: web/package.json:13 has `"prepare": "npm run generate:api"`, which npm
# runs as part of the install, and generate:api reads `../contracts/api/*.openapi.yaml` relative to web/.
# (Measured the hard way — with this COPY below the install, `npm ci` fails with "spec file is not found".)
COPY contracts/api /contracts/api
COPY web/package.json web/package-lock.json* ./
RUN npm ci
COPY web/ ./
# verify:no-backdoor is the prod guard from `npm run check` that asserts the dev auto-unlock seam
# (__dev/seed-credentials) never reaches dist/. A release image is exactly where that must hold.
RUN npm run build && npm run verify:no-backdoor

# --- backend (JVM) ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS backend-build-base
WORKDIR /code/backend
# gradle.properties (build-JVM heap) and pitest.scope.json are NOT optional extras: build.gradle.kts:73 parses
# the PIT manifest at CONFIGURATION time, so even `gradlew help` dies without it. Both postdate the recovered
# original (the mutation gate is ADR 0045 / #99), which is why it did not copy them.
COPY backend/gradlew backend/gradlew.bat backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties backend/pitest.scope.json ./
COPY backend/gradle ./gradle
# Cache-friendly warmup: downloads Gradle wrapper/distribution and plugin metadata once
RUN ./gradlew --no-daemon help

FROM backend-build-base AS backend-build-jvm
# Was `COPY docs/api ../docs/api`. The specs live in contracts/ (API-first, CLAUDE.md), and
# backend/gradle/openapi.gradle.kts:22 reads `fileTree("../contracts/api")`.
COPY contracts/api ../contracts/api
COPY backend/src ./src
COPY --from=frontend-build /web/dist ./src/main/resources/META-INF/resources
# Dropped `-Dquarkus.package.type=fast-jar`: removed in Quarkus 3.x (this is 3.36.0, backend/build.gradle.kts:26)
# and fast-jar is the default anyway — ci.yml builds the same artifact with a bare `quarkusBuild`.
RUN ./gradlew --no-daemon quarkusBuild -x test

# --- backend (native) ------------------------------------------------------------------------------------
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 AS backend-build-native-base
WORKDIR /code/backend
COPY --chown=quarkus:quarkus backend/gradlew backend/gradlew.bat backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties backend/pitest.scope.json ./
COPY --chown=quarkus:quarkus backend/gradle ./gradle
USER quarkus
RUN ./gradlew --no-daemon help

FROM backend-build-native-base AS backend-build-native
COPY --chown=quarkus:quarkus contracts/api ../contracts/api
COPY --chown=quarkus:quarkus backend/src ./src
COPY --chown=quarkus:quarkus --from=frontend-build /web/dist ./src/main/resources/META-INF/resources
RUN ./gradlew --no-daemon quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -x test

# --- runtime (JVM) — the target container.yml builds --------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime-jvm
ARG VEYQ_VERSION=""
ARG VEYQ_REVISION=""
ENV VEYQ_VERSION="${VEYQ_VERSION}"
ENV VEYQ_REVISION="${VEYQ_REVISION}"
WORKDIR /work/
# curl: container healthcheck only. No ffmpeg — the operator-blind backend never transcodes (thumbnails and
# previews are client-generated blobs), so no server-side media binary is ever invoked.
# Dropped the original's /data/veyq-media dir + chown: media bytes go to an S3-compatible store, never to a
# local path (ADR 0013 / cr-0105 — BlobStoreStartupCheck aborts startup without a store endpoint), and
# `grep -rn veyq-media backend/src/main infra/ .github/` finds nothing that would mount it.
# /work MUST stay writable by the runtime user: veyq.security.config-secret-file defaults to
# `config-secret.key` relative to the process CWD and the server GENERATES it 0600 on first start
# (ADR 0050, VeyqConfig.kt:224). A container without a persistent /work regenerates the KEK on every
# recreate, which the veyq_instance fingerprint then reports as MISMATCH — mount it, or set the path.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 10001 veyq \
    && useradd -r -u 10001 -g veyq -s /usr/sbin/nologin veyq \
    && chown -R 10001:10001 /work
COPY --from=backend-build-jvm --chown=10001:10001 /code/backend/build/quarkus-app /work/quarkus-app
USER 10001
EXPOSE 8080
# MaxRAMPercentage is a percentage of the CONTAINER memory limit (set via compose mem_limit) — without that
# limit the JVM would size the heap off host RAM. ExitOnOutOfMemoryError lets the orchestrator restart a
# wedged JVM instead of it limping on in an undefined state.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:InitialRAMPercentage=40.0", "-XX:+ExitOnOutOfMemoryError", "-Dquarkus.http.host=0.0.0.0", "-jar", "/work/quarkus-app/quarkus-run.jar"]

# --- runtime (native) ---------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.5 AS runtime-native
ARG VEYQ_VERSION=""
ARG VEYQ_REVISION=""
ENV VEYQ_VERSION="${VEYQ_VERSION}"
ENV VEYQ_REVISION="${VEYQ_REVISION}"
WORKDIR /work/
# curl: healthcheck only; no ffmpeg (see runtime-jvm — no server-side transcode in an operator-blind core).
RUN microdnf install -y curl && microdnf clean all
COPY --from=backend-build-native --chown=10001:10001 /code/backend/build/*-runner /work/application
RUN chmod 0755 /work/application && chown -R 10001:10001 /work
USER 10001
EXPOSE 8080
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
