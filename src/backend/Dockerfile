# Recovered from `5432372d^` (deleted 2026-06-25 by a "cleanup" commit that was purging .derivedData junk and
# took two CI-referenced artifacts with it — this file and scripts/run_web_e2e.py). container.yml never stopped
# building `file: Dockerfile`, it just never fired on a non-main branch. Ported to today's tree; every delta
# from the recovered original is commented below.

# --- web -------------------------------------------------------------------------------------------------
# Was `frontend/` — the surface is `src/web/` since the greenfield reset.
FROM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS frontend-build
# The install is a WORKSPACE install since cr-admin-web-extraction (ADR 0065): the lockfile lives at the repo
# root and `src/web/` resolves `@veyq/crypto` through it, so the stage builds from /repo with src/web/ as a
# workspace rather than treating it as a standalone project. The image mirrors the checkout under /repo (ADR 0106
# decision 6): `/repo/contracts` keeps generate:api's `../../contracts/api` resolving from /repo/src/web exactly as
# it does in the checkout.
WORKDIR /repo/src/web
# The OpenAPI TypeScript client is GENERATED and deliberately never tracked (src/web/.gitignore:1-4, mirroring the
# backend), so a clean checkout has no src/api/generated. @openapitools/openapi-generator-cli is a Java tool,
# which is why this stage needs a JRE — the recovered original never did, because back then it did not generate.
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless \
    && rm -rf /var/lib/apt/lists/*
# The specs must land BEFORE `npm ci`: src/web/package.json has `"prepare": "npm run generate:api"`, which npm
# runs as part of the install, and generate:api reads `../../contracts/api/*.openapi.yaml` relative to src/web/.
# (Measured the hard way — with this COPY below the install, `npm ci` fails with "spec file is not found".)
COPY contracts/api /repo/contracts/api
# The generator JAR's verifier and its pin, before `npm ci` for the same reason as the specs: `prepare` runs
# generate:api, which runs ../../scripts/verify_openapi_generator_jar.mjs first (ADR 0057 §6).
COPY scripts/verify_openapi_generator_jar.mjs scripts/openapi_generator_jar.sha256 /repo/scripts/
# Every manifest the workspace resolution reads, and nothing else, so a source edit does not bust the install
# layer. src/packages/crypto/ is copied WHOLE rather than manifest-only: it is consumed as TypeScript source (no
# build step), and npm links it into node_modules at install time, so the sources have to be there already.
COPY package.json package-lock.json /repo/
COPY src/packages/crypto /repo/src/packages/crypto
# openapitools.json travels with the manifest: `prepare` runs generate:api during `npm ci`, and both the JAR
# verifier and the generator read the pinned version from it (without it the verifier fails the install).
COPY src/web/package.json src/web/openapitools.json ./
# Not run through scripts/npm_ci_no_deprecated.py, and covered anyway: this installs the root lockfile that
# ci.yml's `frontend` job scans through it on the same commit, and every job that publishes the image needs that
# job (ADR 0091 decision 2: the build itself no longer waits for the tests, so the premise is "before published").
# scripts/validate_npm_installs_gated.py holds both premises — keep `npm ci` (never `npm install`) and the COPY.
RUN npm ci
COPY src/web/ ./
# The platform's one major: vite.config.ts compiles the web gate's EXPECTED_MAJOR from the repository's `VERSION`,
# found by walking up from the client (ADR 0085 decision 1), so the build fails without it. After the install, so a
# new major does not bust that layer.
COPY VERSION /repo/VERSION
# verify:no-backdoor is the prod guard from `npm run check` that asserts the dev auto-unlock seam
# (__dev/seed-credentials) never reaches dist/. A release image is exactly where that must hold. verify:csp is
# its sibling for ADR 0069: the policy file exists and index.html has no CSP <meta> — the two things the
# backend's app shell refuses to boot without, caught here rather than at a self-hoster's first start.
# verify:license-sources holds that the bundle's licence file names each package's source (MPL-2.0 §3.2), which the
# runtime stage copies out of this dist.
RUN npm run build && npm run verify:no-backdoor && npm run verify:csp && npm run verify:license-sources

# --- backend (JVM) ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438 AS backend-build-base
WORKDIR /repo/src/backend
# gradle.properties (build-JVM heap) is not an optional extra, and neither is :core's pitest.scope.json:
# core/build.gradle.kts parses the PIT manifest at CONFIGURATION time, so even `gradlew help` dies without it. Both
# postdate the recovered original (the mutation gate is ADR 0045 / #99), which is why it did not copy them.
COPY src/backend/gradlew src/backend/gradlew.bat src/backend/build.gradle.kts src/backend/settings.gradle.kts src/backend/gradle.properties ./
COPY src/backend/gradle ./gradle
# The convention plugins every module applies (`veyq.module`, `veyq.openapi`): Gradle builds buildSrc before it
# configures any project, so the warm-up below compiles it (and fetches its plugins) into this cached layer.
# .dockerignore keeps a host's buildSrc/build and .gradle out.
COPY src/backend/buildSrc ./buildSrc
# Every module settings.gradle.kts includes needs its directory and build file here, or the warm-up dies configuring
# it ("Configuring project ':core' without an existing directory is not allowed"). The modules are project
# dependencies of the one fast-jar, not images of their own (ADR 0079); validate_backend_module_graph.py rule (e).
COPY src/backend/core/build.gradle.kts src/backend/core/pitest.scope.json ./core/
COPY src/backend/apps/memories/build.gradle.kts ./apps/memories/
COPY src/backend/apps/depot/build.gradle.kts ./apps/depot/
COPY src/backend/apps/expenses/build.gradle.kts ./apps/expenses/
# Cache-friendly warmup: downloads Gradle wrapper/distribution and plugin metadata once
RUN ./gradlew --no-daemon help

FROM backend-build-base AS backend-build-jvm
# Was `COPY docs/api ../docs/api`. The specs live in contracts/ (API-first, CLAUDE.md), and buildSrc's `veyq.openapi`
# reads them from `../../contracts/api` (`VeyqOpenApiExtension`) — absent during the warm-up above, which it tolerates.
COPY contracts/api /repo/contracts/api
# The capability catalog: buildSrc's `generateCapabilityRegister` builds :core's CapabilityIds and their EN/DE texts
# from it (ADR 0088 decision 13), so without it the compile has no register.
COPY contracts/platform/capabilities.json /repo/contracts/platform/capabilities.json
# veyq's two fixed texts, the operator section of the terms and the privacy notice (ADR 0035 decisions 6 and 8):
# :core's processResources puts them into the jar, and the server refuses to start without either.
COPY contracts/terms/operator-section.json contracts/terms/privacy-notice.json /repo/contracts/terms/
# The platform's one major: buildSrc's `generatePlatformMajor` compiles :core's PlatformMajor from it, the major
# GET /api/setup advertises (ADR 0085 decision 1), so without it the compile has no major.
COPY VERSION /repo/VERSION
# Two different `src`: the source's `src/backend/` is the repository's directory of code surfaces (ADR 0106); the
# target's `./src`, `./core/src` are a Gradle module's own sources inside the backend.
COPY src/backend/src ./src
COPY src/backend/core/src ./core/src
COPY src/backend/apps/memories/src ./apps/memories/src
COPY src/backend/apps/depot/src ./apps/depot/src
COPY src/backend/apps/expenses/src ./apps/expenses/src
# The license texts buildSrc's ThirdPartyLicenses appends to the backend's notices; `quarkusBuild` runs it after itself.
COPY src/backend/licenses ./licenses
COPY --from=frontend-build /repo/src/web/dist ./src/main/resources/META-INF/resources
# Dropped `-Dquarkus.package.type=fast-jar`: removed in Quarkus 3.x (the version is src/backend/gradle/libs.versions.toml's)
# and fast-jar is the default anyway — ci.yml builds the same artifact with a bare `quarkusBuild`.
RUN ./gradlew --no-daemon quarkusBuild -x test

# Removed: a native build (Mandrel) and its `runtime-native` stage, 2026-10-01 (inbox #200). No ADR
# chose it, it was never seen to build (it OOMs an 8 GB Docker VM), nothing tested it, and the release never
# published it. A native image returns only as a decision with its own CI build and e2e run (inbox #243).

# --- runtime (JVM) — the target ci.yml's `build` job builds -------------------------------------------------
# The LAST stage on purpose: a build with no `--target` (`docker build -f src/backend/Dockerfile .`) builds the last
# stage, so it builds what the release publishes. scripts/validate_images_run_unprivileged.py holds every published target to its file's last stage.
FROM eclipse-temurin:21-jre-jammy@sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f AS runtime-jvm
WORKDIR /work/
# curl: container healthcheck only. No ffmpeg — the operator-blind backend never transcodes (thumbnails and
# previews are client-generated blobs), so no server-side media binary is ever invoked.
# Dropped the original's /data/veyq-media dir + chown: media bytes go to an S3-compatible store, never to a
# local path (ADR 0013 / cr-0105 — BlobStoreStartupCheck aborts startup without a store endpoint), and
# `grep -rn veyq-media src/backend/src/main infra/ .github/` finds nothing that would mount it.
# /work MUST stay writable by the runtime user: veyq.security.config-secret-file defaults to
# `config-secret.key` relative to the process CWD and the server GENERATES it 0600 on first start
# (ADR 0050, the @WithDefault in VeyqConfig.kt's `Security` interface). A container without a persisted key
# path regenerates the KEK on every recreate, which the veyq_instance fingerprint then reports as MISMATCH.
#
# /var/lib/veyq is that path, and it exists here ONLY so a named volume mounted on it inherits uid 10001 —
# Docker seeds a fresh volume from the image directory it covers, ownership included, and a root-owned mount
# would leave the server unable to write its own key. Deployments set
# VEYQ_SECURITY_CONFIG_SECRET_FILE=/var/lib/veyq/config-secret.key and mount a volume here;
# infra/docker/docker-compose.prod.yml does exactly that, and scripts/validate_config_secret_persistence.py holds
# the three files to it.
#
# /etc/traefik/dynamic is the SECOND such path and was missing until 2026-09-08 — the tenant-ingress
# projection (ADR 0063) mounts a named volume there and the app writes the whole router set into it at
# startup. Without the directory in the image there is nothing for Docker to seed the volume from, so it came
# up root:root 0755 and the app — uid 10001 — died on its first write:
#   java.nio.file.AccessDeniedException: /etc/traefik/dynamic/.tenants.yml<random>.tmp
#       at TenantIngressProjector.writeAtomically(TenantIngressProjector.kt:134)
# Quarkus fails the startup event, the container restart-loops, and a FIRST-EVER `docker compose up` of the
# production stack therefore never had a running app. Measured, not reasoned: a fresh volume on this path
# probes NOT writable from uid 10001 while the sibling on /var/lib/veyq probes writable, which is the whole
# difference between a path this stage creates and one it does not. 0755, not 0700 like its sibling: this is
# routing config the traefik container reads (read-only mount), not a secret.
#
# scripts/validate_app_volume_ownership.py now derives this set from the compose file rather than trusting
# this comment, so the NEXT volume added to the app service cannot repeat it.
#
# NOT /work, and not a `VOLUME` declaration — two deliberate omissions:
#   - /work already holds quarkus-app/. A named volume mounted there is seeded from the image ONCE and then
#     never refreshed, so `up -d` with a newer tag would keep running the FIRST image's jar. An upgrade that
#     silently does nothing is worse than one that fails.
#   - `VOLUME /var/lib/veyq` would create an ANONYMOUS volume when nothing is mounted, which looks like
#     persistence (the key survives a restart) while `--rm` and `compose down -v` still discard it under a
#     name no `docker volume ls` identifies. The defect must stay visible where nothing mounts the path;
#     detection is the remedy ADR 0050's amendment chose.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 10001 veyq \
    && useradd -r -u 10001 -g veyq -s /usr/sbin/nologin veyq \
    && mkdir -p /var/lib/veyq /etc/traefik/dynamic \
    && chmod 0700 /var/lib/veyq \
    && chmod 0755 /etc/traefik/dynamic \
    && chown -R 10001:10001 /work /var/lib/veyq /etc/traefik/dynamic
COPY --from=backend-build-jvm --chown=10001:10001 /repo/src/backend/build/quarkus-app /work/quarkus-app
# The AGPL travels with the object code it covers (§4/§6): a pulled image is a conveyed copy. The same path in
# both images, beside the notices of every jar in /work/quarkus-app/lib (buildSrc's ThirdPartyLicenses) and of every
# package the web bundle carries (vite.config.ts's licence file, each with its source - ExifReader is MPL-2.0, §3.2).
# The app also serves that file, at /third-party-licenses.txt; the copy here is for a recipient who never starts it.
# The MariaDB driver (LGPL-2.1) stays a jar of its own in lib/main, which is what lets a recipient replace it.
COPY LICENSE NOTICE /usr/share/licenses/veyq/
COPY --from=backend-build-jvm /repo/src/backend/build/third-party-licenses/THIRD-PARTY-LICENSES.txt /usr/share/licenses/veyq/
COPY --from=frontend-build /repo/src/web/dist/third-party-licenses.txt /usr/share/licenses/veyq/THIRD-PARTY-LICENSES-WEB.txt
# The two stamps, after the last layer on purpose: a stamp declared before a RUN enters its cache key, which re-ran the
# `apt-get` layer above at every commit (measured in the heavy stage's round 2, 2026-10-24). Nothing above reads them.
# The labels are the image's identity as `docker inspect` shows it, set here only: no build step passes a label, and
# every OCI key a base image lends (Ubuntu's `version` 22.04) is overwritten. A label is config, never a cached layer,
# so it names the commit built whatever the cache held (validate_release_identity.py assertion 12, measured by
# scripts/image_identity_cache_proof.py).
ARG VEYQ_VERSION=""
ARG VEYQ_REVISION=""
ENV VEYQ_VERSION="${VEYQ_VERSION}"
ENV VEYQ_REVISION="${VEYQ_REVISION}"
LABEL org.opencontainers.image.title="veyq" \
      org.opencontainers.image.description="veyq application server" \
      org.opencontainers.image.version="${VEYQ_VERSION}" \
      org.opencontainers.image.revision="${VEYQ_REVISION}" \
      org.opencontainers.image.source="https://github.com/raddatzk/veyq" \
      org.opencontainers.image.licenses="AGPL-3.0-only"
USER 10001
# The public port only. The admin listener (veyq.admin-channel.port, 8090 — ADR 0065) is deliberately NOT
# exposed: `docker run -P` publishes every EXPOSEd port, and that one must only ever be reached by the admin
# container, which compose and the chart point at it by name.
EXPOSE 8080
# MaxRAMPercentage is a percentage of the CONTAINER memory limit (set via compose mem_limit) — without that
# limit the JVM would size the heap off host RAM. ExitOnOutOfMemoryError lets the orchestrator restart a
# wedged JVM instead of it limping on in an undefined state.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:InitialRAMPercentage=40.0", "-XX:+ExitOnOutOfMemoryError", "-Dquarkus.http.host=0.0.0.0", "-jar", "/work/quarkus-app/quarkus-run.jar"]
