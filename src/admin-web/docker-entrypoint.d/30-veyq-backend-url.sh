#!/bin/sh
# Render nginx.conf.template with the two values an operator sets: where the backend lives, and the deployment's
# HSTS switch.
#
# Not the nginx image's built-in template mechanism (/etc/nginx/templates + 20-envsubst-on-templates.sh): that
# one substitutes every environment variable it finds unless NGINX_ENVSUBST_FILTER is set, and an nginx config
# is full of `$host`, `$uri`, `$remote_addr` — a blanket envsubst blanks them and produces a config that starts
# and then proxies to nowhere. Naming the variables explicitly is both narrower and self-evident. The
# template therefore lives OUTSIDE /etc/nginx/templates so the built-in script never sees it.
set -eu

# `app:8090` is the backend's ADMIN LISTENER under its service name in infra/docker/docker-compose.prod.yml — never its
# public port, which 404s every Server-Owner operation (ADR 0065). A default, not a fallback to something
# wrong: the overwhelmingly common case is the bundled stack, and an operator running the container by hand
# sets BACKEND_URL.
: "${BACKEND_URL:=http://app:8090}"
export BACKEND_URL

# The nameservers the config's `resolver` directive needs, taken from the container's own /etc/resolv.conf
# rather than hardcoding Docker's embedded 127.0.0.11 — the same image runs under Kubernetes (ADR 0064), where
# that address does not exist. IPv6 addresses are bracketed, as the directive requires. Falling back to
# Docker's embedded resolver is better than emitting an empty directive, which nginx rejects outright.
VEYQ_RESOLVERS=$(awk '/^nameserver/ { print ($2 ~ /:/) ? "[" $2 "]" : $2 }' /etc/resolv.conf | tr '\n' ' ')
: "${VEYQ_RESOLVERS:=127.0.0.11}"
export VEYQ_RESOLVERS

# The deployment's HSTS switch — fed from the same key as the app's (`VEYQ_HSTS_ENABLED` in infra/docker/.env,
# `hsts.enabled` in the chart; https://raddatzk.github.io/veyq/run/new-instance/), so one value governs both origins;
# docs/crosscutting/cc.security.csp.md § admin-web says why there is one and not two. Exactly `true` (the default
# when unset or empty) or `false`. Anything else stops the container here, naming the variable — the image's
# entrypoint runs hooks under `set -e` — because a mistyped security switch must fail loudly, never land silently
# on or silently off. The template holds the directive itself; this only decides whether it is rendered whole or
# commented out.
: "${VEYQ_HSTS_ENABLED:=true}"
case "$VEYQ_HSTS_ENABLED" in
  true) VEYQ_HSTS_OFF='' ;;
  false) VEYQ_HSTS_OFF='#' ;;
  *)
    echo "veyq-admin: VEYQ_HSTS_ENABLED must be 'true' or 'false', got '${VEYQ_HSTS_ENABLED}'" >&2
    exit 1
    ;;
esac
export VEYQ_HSTS_OFF

envsubst '${BACKEND_URL} ${VEYQ_RESOLVERS} ${VEYQ_HSTS_OFF}' < /etc/nginx/veyq.conf.template > /etc/nginx/conf.d/default.conf
echo "veyq-admin: proxying /api/* to ${BACKEND_URL} (resolver: ${VEYQ_RESOLVERS}); HSTS: ${VEYQ_HSTS_ENABLED}"
