---
title: Networking and TLS
description: How requests reach the instance on each target, the certificates, HSTS, the client address the rate limits see, and what a proxy of your own changes.
sources:
  - docs/decisions/0063-database-driven-tenant-ingress.md
  - docs/features/admin/use-cases/uc.admin.tenant-reachability.md
  - docs/crosscutting/cc.security.rate-limit.md
---

## How requests reach the instance

The application decides the routing. It writes one route for the instance's domain, one per tenant that is not
offline, one for the admin surface if it has a domain of its own, and the object store's route, and it rewrites them
on every tenant change and at every start
([ADR 0063](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0063-database-driven-tenant-ingress.md)). A
new tenant's domain answers without a restart and without editing anything.

**Docker Compose.** Traefik, the only container with public ports (80 and 443), reads one file the application
writes into the `veyq-traefik-dynamic` volume, and nothing else: no container mounts the Docker socket. **Never
edit that file.** It is a projection of the tenant table; the next tenant change or start overwrites it, and it is
not part of a backup, because the next start regenerates it. Store traffic goes to Garage under a path of each
domain (`/blobs/`), so the browser reaches the store on the instance's own origin. If the application cannot write
the file, it logs that loudly and every domain, the instance's own included, stays unrouted; the admin port
bypasses Traefik, so you keep a way in.

**Kubernetes.** A Gateway API implementation of your choice does the routing (Traefik is the example these pages
use, never a requirement). The chart renders one `Gateway`, `veyq`, of the `GatewayClass` you name in
`gateway.className`, and an `HTTPRoute` for the instance's domain, one for the store's host (`storeDomain`) and, if
set, one for the admin surface; the application writes one `HTTPRoute` per tenant into its own namespace, attached to
that `Gateway` — and under `per-domain` TLS a `ListenerSet` beside it. It may manage routes and `ListenerSet`s in that
namespace and nothing else; the `Gateway` is the chart's. The Gateway API CRDs (v1.5 or later, standard channel)
and the implementation are yours to install first
([Kubernetes](/veyq/run/kubernetes/)).

## TLS is required

The session cookie is `Secure`, and every invitation and recovery link is `https://…`. An instance served over plain
HTTP boots and serves pages, then silently breaks **Stay signed in on this device** and every mailed link.

**Docker Compose.** Traefik obtains a Let's Encrypt certificate per domain over the HTTP-01 challenge and redirects
port 80 to HTTPS. For that, port 80 must reach the host from the internet, and every domain must resolve to it
**before** it is used, the tenants' included: creating a tenant is its certificate order. Set `VEYQ_ACME_EMAIL`; while
testing, point `VEYQ_ACME_CASERVER` at Let's Encrypt's staging directory, and switch back before anyone uses the
instance (staging certificates are not trusted). Let's Encrypt locks a hostname out for an hour after five failed
validations, and issues at most 50 certificates per registered domain per week, which a burst of test tenants reaches.
If Traefik's log shows `permission denied` on `/acme`, the certificate volume is not owned by Traefik's user; the
`volume-ownership` one-shot sets that up on every `up`.

**Kubernetes.** TLS is a reference the chart and the application stamp, chosen by `gateway.tls.mode`:

- `none` (the default) — the `Gateway` listens on plain HTTP only. Right only where TLS ends before your gateway.
- `per-domain` — an HTTPS listener per host whose Secret is named after the host's route: the chart's own hosts on
  the `Gateway`, each tenant in a `ListenerSet` of its own. Every tenant domain gets its own certificate with no chart
  change — if your implementation supports `ListenerSet`, which not every released one does yet. When a tenant is
  taken offline its route and `ListenerSet` go, and cert-manager keeps the issued Secret unless it runs with
  `--enable-certificate-owner-ref`.
- `shared` — one HTTPS listener on the `Gateway` with one Secret you provide (`gateway.tls.secretName`), a wildcard
  certificate. Tenants on their own domains then route, and browsers refuse them.

Under `per-domain` and `shared` the chart redirects port 80 to HTTPS itself. With `gateway.tls.acme.enabled`, the
chart creates a cert-manager issuer of its own (HTTP-01 on the `Gateway`'s port-80 listener, `per-domain` only) and
stamps it on the `Gateway` and every tenant `ListenerSet`. cert-manager 1.21 or later must already run in the
cluster with its Gateway API and its (alpha) `ListenerSet` support on, port 80 of every host must reach the
`Gateway`, and a tenant's DNS must point here before the tenant is created. Watch the orders with
`kubectl -n veyq get certificate`.

## HSTS

The application and the admin surface send `Strict-Transport-Security` (180 days, no `includeSubDomains`, no
`preload`) themselves; neither Traefik nor the chart's `Gateway` adds anything. One switch, `VEYQ_HSTS_ENABLED` /
`hsts.enabled`, turns it off for both, and the admin container refuses to start on any value but `true` or
`false`. Turn it off only if a hostname of the instance is also served over plain HTTP, or a proxy of yours already
sets the header. A browser that saw it keeps it for 180 days.

## The client address and the rate limits

The public sign-in, sign-up and recovery endpoints are rate-limited per client address
([cc.security.rate-limit](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.rate-limit.md)).
The application takes that address from the proxy's forwarded header, so who may send that header matters:

- **Docker Compose** sets no list of trusted proxies; the container networks decide who can reach the application at
  all, and only Traefik can on the public side.
- **Kubernetes:** `app.trustedProxies` stays empty unless your proxy has a stable address. The NetworkPolicy then
  decides who reaches the application.

The buckets live in memory, per application instance, and a restart clears them. Behind a NAT every client shares
one bucket.

## A proxy of your own in front

- **Route only the public port, 8080.** Nothing but the admin container may ever reach 8090
  ([Hardening](/veyq/run/hardening/#keep-the-admin-listener-local)). The admin listener ignores forwarded headers by
  design: it trusts the socket's peer, never a header.
- **Turn HSTS off** in veyq if your proxy sets the header.
- **Let `/.well-known/acme-challenge/` through to Traefik on port 80,** or Let's Encrypt cannot validate.
- **Not configured by the recipes:** Traefik as shipped is not told to trust the forwarded headers of a proxy in
  front of it. With such a proxy, the rate limits may see the proxy's address for every client. This combination
  has not been tested.

## How far this is proven

The hardened Compose recipe was brought up once (2026-09-28, on Docker Desktop, with the staging directory set);
no certificate order against a public domain is recorded. On Kubernetes only the rendered chart is checked; no
`Gateway`, `ListenerSet`, certificate or NetworkPolicy has run in a cluster, and no gateway implementation has
accepted these routes.
