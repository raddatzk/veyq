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
RUN npm run build

# --- runtime ----------------------------------------------------------------------------------------------
# nginx, pinned by minor: serving static files and proxying one path is the entirety of the job, and a Node
# runtime here would put a second application server on the highest-privilege origin an instance serves.
FROM nginx:1.29-alpine AS runtime-nginx

# The stock default site would otherwise also listen on 80 and win by being first.
RUN rm -f /etc/nginx/conf.d/default.conf

COPY --from=build /src/admin-web/dist /usr/share/nginx/html
# OUTSIDE /etc/nginx/templates on purpose — see the entrypoint hook: the image's built-in template renderer
# would substitute every env var it finds, including nginx's own `$host`/`$uri`.
COPY admin-web/nginx.conf.template /etc/nginx/veyq.conf.template
COPY admin-web/docker-entrypoint.d/30-veyq-backend-url.sh /docker-entrypoint.d/30-veyq-backend-url.sh
RUN chmod +x /docker-entrypoint.d/30-veyq-backend-url.sh

# Same build-args the app image takes (ADR 0057), so `docker inspect` names the release this container came
# from — the two images ship as one release and must be identifiable as such.
ARG VEYQ_VERSION
ARG VEYQ_REVISION
LABEL org.opencontainers.image.title="veyq-admin" \
      org.opencontainers.image.description="veyq Server-Owner administration surface" \
      org.opencontainers.image.version="${VEYQ_VERSION}" \
      org.opencontainers.image.revision="${VEYQ_REVISION}"
ENV VEYQ_VERSION=${VEYQ_VERSION}

EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=10s \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
