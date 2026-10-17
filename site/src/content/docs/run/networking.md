---
title: Networking and TLS
description: How requests reach the instance on each target, the certificates, HSTS, the client address and source port the application sees, and what a proxy of your own changes.
sources:
  - docs/decisions/0063-database-driven-tenant-ingress.md
  - docs/features/admin/use-cases/uc.admin.tenant-reachability.md
  - docs/crosscutting/cc.security.rate-limit.md
  - docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md
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
The application works that address out itself ([ADR 0094](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)). It reads every `X-Forwarded-For` line and takes
the **rightmost** entry that the trusted-proxies list does not name. That entry was written by a proxy the request
really passed, so a client cannot choose it. The proxy that hands the request to the application is trusted because
of the network, never because of a header: on Docker Compose only Traefik reaches the application's public port, and
on Kubernetes the NetworkPolicy admits only your gateway. `Forwarded` and `X-Real-Ip` are ignored. If the entry it
stops at is not an IP address, the rate limit keys on the proxy that connected.

The list is one setting, `VEYQ_HTTP_TRUSTED_PROXIES`, which the chart renders from `app.trustedProxies`. It names the
hops **in front of** your gateway, such as a load balancer, as IP addresses and CIDR ranges. Leave it empty when the
gateway is the first hop. Never list the gateway itself or its pods: a range that survives a pod restart trusts every
pod in it. A hostname or a malformed entry stops the application at startup. Docker Compose has no such setting,
because a proxy in front of Traefik is outside the recipe (below).

What arrives, and what the rate limit keys on, by how each hop treats the header it receives:

| In front of the application | `X-Forwarded-For` as it arrives | Key | Source port |
|---|---|---|---|
| Traefik on Docker Compose (the recipe's plugin, below) | `203.0.113.77` | the client | Traefik's peer's |
| Traefik as your Kubernetes gateway | `203.0.113.77` | the client | unknown, unless a source is configured |
| A gateway that sends the PROXY protocol, `app.clientPort.source: proxyProtocol` | `203.0.113.77` | the client | the PROXY header's |
| A gateway that appends to what the client sent | `198.51.100.66, 203.0.113.77` | the client | unknown |
| A load balancer, then a gateway that replaces the header | `10.0.0.5` | the load balancer | unknown |
| A load balancer, then a gateway that appends, `app.trustedProxies: "10.0.0.5"` | `198.51.100.66, 203.0.113.77, 10.0.0.5` | the client | unknown |
| The same without `app.trustedProxies` | the same | the load balancer | unknown |
| A load balancer that writes `ip:port`, then a gateway that appends, listed | `198.51.100.66, 203.0.113.77:51234, 10.0.0.5` | the client | `51234` |

Traefik replaces the header from a peer it does not trust and adds no port; that much is measured. Whether your
gateway and load balancer replace or append, check in their documentation. Where the key is your load balancer, every
client behind it shares one bucket: list the load balancer.

- **The source port.** Behind carrier-grade NAT many subscribers share one address, and only the address with its
  source port and an exact time names one of them. The application keeps the port where the entry it stops at carries
  one: an AWS Application Load Balancer writes it when `routing.http.xff_client_port.enabled` is set, and an Azure
  Application Gateway always does. An entry with a port reads `203.0.113.77:51234`, or `[2001:db8::1]:443` for IPv6.
  A bare IPv6 address is never read as having a port, so a load balancer that writes `2001:db8::1:4711` without
  brackets hands the application a different address. The Application Gateway's IPv6 form is not documented: with
  IPv6 behind one, look at the header it sends. Where the entry carries no port, the one **source you configure**
  may state it (next section); otherwise the port is unknown. The rate limit keys on the address alone.
- **Keep the client's address up to the gateway.** A layer-4 load balancer in front of your gateway that rewrites
  the source address makes the gateway see a node, not the client, and no setting in veyq can repair that. Check
  the setting that keeps it: for a Kubernetes `LoadBalancer` Service, `externalTrafficPolicy: Local`, or the PROXY
  protocol where your load balancer and gateway support it.
- **Kubernetes without an enforced NetworkPolicy.** With `networkPolicy.enabled: false`, or a CNI that enforces no
  NetworkPolicy, any pod can reach the application and write the whole header. Nothing then tells it apart from your
  gateway, and the rate limit can key on a forged address. The chart's install notes say so.

The buckets live in memory, per application instance, and a restart clears them. Behind a NAT every client shares
one bucket.

### Where the source port comes from

One setting, `VEYQ_HTTP_CLIENT_PORT_SOURCE` (`app.clientPort.source` on Kubernetes), names the one source the
application believes for the port where the `X-Forwarded-For` entry carries none. It believes it only when the source
names the same IP as that entry; otherwise the port stays unknown. **It is configured, never detected**: Traefik and
most gateways pass a header they do not know straight through, so a header nobody overwrites is one any client writes
— and with its own address and another port, a client would name another subscriber behind the same NAT.

- **`none`** (the default): no source. A client-sent header is never read.
- **`header`**: your edge overwrites a header (`X-Veyq-Client-Addr` by default, `app.clientPort.header`) with the
  client's `address:port` on **every** request. **Docker Compose ships this, fixed:** the recipe's Traefik loads a small
  local plugin that travels with the release (`traefik-clientaddr.go` and its manifest, mounted read-only; nothing is
  fetched) and attaches it to the application's routes. Traefik files local plugins as `experimental`; the recipe pins
  Traefik's image, and a Traefik that can no longer load the plugin answers 404 for the instance rather than passing a
  forged header. On Kubernetes, choose `header` only if your edge overwrites it. Pointers to check in their own
  documentation: Envoy Gateway's `ClientTrafficPolicy` `headers.earlyRequestHeaders` can `set` it to
  `%DOWNSTREAM_DIRECT_REMOTE_ADDRESS%` (the address and port of the connection Envoy accepted — right only when
  nothing sits in front of Envoy), NGINX Gateway Fabric's `SnippetsFilter`, GCP's `{client_port}` and Cloudflare's
  `cf.edge.client_port`.
- **`proxyProtocol`** (Kubernetes): your gateway opens every connection to the application with a PROXY protocol
  header (v1 or v2), and the application reads the client's address and port from it. Envoy Gateway:
  a `BackendTrafficPolicy` with `proxyProtocol`, targeted at the application's routes by label — the chart's route
  carries `app.kubernetes.io/component: app`, and the routes the backend creates for tenants carry its owner label
  `app.kubernetes.io/managed-by`. Istio: a `DestinationRule` on `veyq-app` with `trafficPolicy.proxyProtocol`.
  **Send it to the application's routes only, never to the whole `Gateway`:** the object store speaks no PROXY
  protocol, and every upload and download would fail. The chart renders none of these objects. With PROXY on, the
  application closes every connection that does not start with a PROXY header, so the chart moves its probes to the
  admin listener ([Operations](/veyq/run/operations/#health)).

A port is only as good as the server's clock: carrier-grade NAT hands a port to the next subscriber quickly, so keep
the host's time synchronised (NTP).

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
accepted these routes. The Compose recipe's client-address plugin is proven on every run of the release's stack test:
the release's own Traefik and plugin replace a forged header with the sender's own address and port. No gateway has
yet sent the PROXY protocol to the chart, and the Envoy Gateway header above is read from its source, not run.
