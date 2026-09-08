#!/bin/sh
# Render nginx.conf.template with the ONE value an operator sets: where the backend lives.
#
# Not the nginx image's built-in template mechanism (/etc/nginx/templates + 20-envsubst-on-templates.sh): that
# one substitutes every environment variable it finds unless NGINX_ENVSUBST_FILTER is set, and an nginx config
# is full of `$host`, `$uri`, `$remote_addr` — a blanket envsubst blanks them and produces a config that starts
# and then proxies to nowhere. Naming the one variable explicitly is both narrower and self-evident. The
# template therefore lives OUTSIDE /etc/nginx/templates so the built-in script never sees it.
set -eu

# `app:8080` is the service name in infra/docker-compose.prod.yml. A default, not a fallback to something
# wrong: the overwhelmingly common case is the bundled stack, and an operator running the container by hand
# sets BACKEND_URL.
: "${BACKEND_URL:=http://app:8080}"
export BACKEND_URL

# The nameservers the config's `resolver` directive needs, taken from the container's own /etc/resolv.conf
# rather than hardcoding Docker's embedded 127.0.0.11 — the same image runs under Kubernetes (ADR 0064), where
# that address does not exist. IPv6 addresses are bracketed, as the directive requires. Falling back to
# Docker's embedded resolver is better than emitting an empty directive, which nginx rejects outright.
VEYQ_RESOLVERS=$(awk '/^nameserver/ { print ($2 ~ /:/) ? "[" $2 "]" : $2 }' /etc/resolv.conf | tr '\n' ' ')
: "${VEYQ_RESOLVERS:=127.0.0.11}"
export VEYQ_RESOLVERS

envsubst '${BACKEND_URL} ${VEYQ_RESOLVERS}' < /etc/nginx/veyq.conf.template > /etc/nginx/conf.d/default.conf
echo "veyq-admin: proxying /api/* to ${BACKEND_URL} (resolver: ${VEYQ_RESOLVERS})"
