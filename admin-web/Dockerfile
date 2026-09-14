# The Server-Owner administration surface as its own image (ADR 0065) — `ghcr.io/<owner>/veyq-admin`,
# versioned and signed the same way as the app image (ADR 0057).
#
# Separate from the root Dockerfile on purpose: that one builds a JVM application server with the app SPA baked
# into its resources, this one is static assets plus a reverse proxy. They share no stage and must not: the
# whole point of the extraction is that an operator can firewall this container off the internet without
# touching the app.

# --- build ------------------------------------------------------------------------------------------------
# Mirrors the root Dockerfile's frontend-build stage, including its two hard-won details: the OpenAPI client is
# GENERATED and never tracked (so the stage needs a JRE for openapi-generator), and the specs must land BEFORE
# `npm ci` because `prepare` runs generate:api during the install.
FROM node:22-bookworm-slim AS build
WORKDIR /src/admin-web
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless \
    && rm -rf /var/lib/apt/lists/*
COPY contracts/api /src/contracts/api
# Every manifest the workspace resolution reads, and nothing else, so a source edit does not bust the install
# layer. packages/crypto/ is copied WHOLE rather than manifest-only: it is consumed as TypeScript source (no
# build step), and npm links it into node_modules at install time, so the sources have to be there already.
COPY package.json package-lock.json /src/
COPY packages/crypto /src/packages/crypto
COPY admin-web/package.json ./
# Plain `npm ci`, and NOT `--omit=optional`: rolldown (Vite 8's bundler) ships its native binding as an
# optional platform dependency, so omitting optionals produces an install that resolves and then dies with
# "Cannot find native binding" at the first `vite build`. Measured here, on the first build of this file.
# web/'s manifest is deliberately absent from the context — npm skips the workspace it cannot see, and this
# image has no business carrying the app's dependencies.
RUN npm ci
COPY admin-web/ ./
# Two artifacts out of one install: the static site nginx serves, and the `veyq-admin` CLI bundle — the
# same generated client, reached from a Node entry point instead of a browser one (ADR 0065). Bundled
# HERE, at image build time, and never transpiled at container runtime: a shipped feature must not make
# TypeScript tooling part of what the image runs.
RUN npm run build && npm run build:cli

# --- runtime ----------------------------------------------------------------------------------------------
# nginx, pinned by minor: serving static files and proxying one path is the entirety of what this ORIGIN
# does. It is not the entirety of the image any more — `apk add nodejs` below is a real, named cost taken
# on knowingly (ADR 0065 decided the CLI ships in this container rather than a second one).
#
# This comment used to say a Node runtime here would put "a second application server on the highest-
# privilege origin". That worry was about a SERVER, and it still stands: Node gets no port, no listener, no
# entrypoint hook and no place in CMD. It runs only when an operator invokes the CLI by hand. The origin
# this container exposes is served by nginx alone, exactly as before.
#
# The UNPRIVILEGED variant, not the stock `nginx:<minor>-alpine` it replaced: the stock image runs its master
# as root and binds port 80, which a `restricted` PodSecurity namespace refuses outright (`runAsNonRoot` is
# mandatory there) — and this is the surface where first-run setup happens, so ADR 0064's chart would have
# installed an instance nobody can set up. `nginxinc/nginx-unprivileged` is upstream nginx's own image
# (actively maintained, vendor-neutral, same minor pin): uid 101, listens on 8080, pid file under /tmp, and
# /etc/nginx/conf.d writable by 101 — which is all the entrypoint hook below needs, measured before switching.
# Under compose nothing changes except the container-side port; scripts/validate_images_run_unprivileged.py
# holds every runtime stage in this repository to this.
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime-nginx
# The base ends on `USER 101`, and `apk add` as 101 fails (measured) — so root for the build steps, and back
# to 101 at the very end of this stage. BOTH switches are load-bearing: without the first the build dies on
# its first RUN, without the second the image silently runs as root again, with a green build on top.
USER root
RUN apk add --no-cache nodejs

# The base image's default site would otherwise also listen on 8080 and win by being first.
RUN rm -f /etc/nginx/conf.d/default.conf

COPY --from=build /src/admin-web/dist /usr/share/nginx/html
# OUTSIDE /etc/nginx/templates on purpose — see the entrypoint hook: the image's built-in template renderer
# would substitute every env var it finds, including nginx's own `$host`/`$uri`.
COPY admin-web/nginx.conf.template /etc/nginx/veyq.conf.template
COPY admin-web/docker-entrypoint.d/30-veyq-backend-url.sh /docker-entrypoint.d/30-veyq-backend-url.sh
RUN chmod +x /docker-entrypoint.d/30-veyq-backend-url.sh

# The CLI: one self-contained ESM bundle plus the two lines that make it a command. `type: module` is
# stated rather than inferred — modern Node would sniff the ESM syntax and be right, but a shipped
# artifact should not depend on a heuristic that a future runtime may narrow. The wrapper is what makes
# `docker compose exec admin veyq-admin tenant list` work, which is how ADR 0065 expects this to be
# reached (localhost-only by default: no router, no port, an operator at the host).
COPY --from=build /src/admin-web/dist-cli/cli.js /usr/local/lib/veyq-admin/cli.js
RUN printf '{"type":"module"}\n' > /usr/local/lib/veyq-admin/package.json \
    && printf '#!/bin/sh\nexec node /usr/local/lib/veyq-admin/cli.js "$@"\n' > /usr/local/bin/veyq-admin \
    && chmod +x /usr/local/bin/veyq-admin

# Same build-args the app image takes (ADR 0057), so `docker inspect` names the release this container came
# from — the two images ship as one release and must be identifiable as such.
ARG VEYQ_VERSION
ARG VEYQ_REVISION
LABEL org.opencontainers.image.title="veyq-admin" \
      org.opencontainers.image.description="veyq Server-Owner administration surface" \
      org.opencontainers.image.version="${VEYQ_VERSION}" \
      org.opencontainers.image.revision="${VEYQ_REVISION}"
ENV VEYQ_VERSION=${VEYQ_VERSION}

# Back to the base image's unprivileged user — the LAST instruction that changes who runs, on purpose (see the
# `USER root` above). 8080 is what the unprivileged base listens on and what admin-web/nginx.conf.template
# binds; the compose file maps it to the host's 127.0.0.1:${ADMIN_PORT}.
USER 101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=10s \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
