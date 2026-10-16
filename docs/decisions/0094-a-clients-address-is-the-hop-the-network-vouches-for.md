# ADR 0094 — A client's address is the hop the network vouches for

- Status: Accepted 2026-10-16; nothing of it is built
- Date: 2026-10-16

## Context

Two consumers need the address of the client that sent a request:

- **The guest-write record** (the moderation ADR, [0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)) keeps the address and time of a guest's write as evidence
  for an abuse report. A forged address there can name an innocent third party. Behind carrier-grade NAT many
  subscribers share one public IPv4 address, and only the address with its **source port** and an exact time names
  one of them (M8).
- **The per-IP rate limiter** (`RateLimitFilters.kt:80`, `request.remoteAddress()`) keys its bucket on it. A forged
  address there opens a fresh bucket per request.

Today the address comes from Quarkus. With `proxy-address-forwarding` on and no `trusted-proxies`, Quarkus honours
`X-Forwarded-For` from any peer and takes its **leftmost** entry (M1), which is the one a client writes itself. Both
targets turn forwarding on (M2). How far that is forgeable depends on the proxy in front of the app, which veyq
cannot choose on Kubernetes:

- A proxy that **replaces** the header from an untrusted peer leaves one entry, the real client. Traefik, as shipped
  on Compose, deletes every incoming `X-Forwarded-*` header from a peer outside its `trustedIPs` (M3), and Compose
  sets none (M4). **Compose is not forgeable today.**
- A proxy that **appends** keeps the client's entries and adds the peer it saw on the right (R1: Envoy-based
  gateways, a cloud load balancer). The leftmost entry is then the client's choice.
- A peer that reaches the app **without a proxy** writes the whole header. Only the network stops that: Compose's
  separate networks and the chart's NetworkPolicy ([ADR 0071](0071-runtime-hardening-on-both-targets.md)).

Nothing in the backend's main code reads the forwarded scheme, host or URI through Quarkus. `TenantHostResolver`
reads `X-Forwarded-Host` itself and `AdminChannel` reads the socket (M5). Quarkus's forwarding feeds only
`remoteAddress()`, and through it only the rate limiter.

## Decision

1. **veyq resolves a client's address itself, in one resolver in core, and both consumers use it.** The rate
   limiter and the guest-write record never read an address from anywhere else.
2. **The socket peer is vouched for by the network, never by a header.** On Compose, the only public peer of the
   app's port is Traefik. On Helm, the NetworkPolicy's `ingressFrom` selects the data-plane pods of the gateway
   implementation the operator picks ([ADR 0064](0064-kubernetes-helm-deployment-target.md)). That is ADR 0071's
   reachability, and it is the whole trust in the peer.
3. **The client is the rightmost `X-Forwarded-For` entry that is not a trusted proxy, with the port that entry
   carries.**
   - The entries are every `X-Forwarded-For` line, in order, split on commas, each trimmed. With none, the client
     is the peer, with the socket's port.
   - An entry is an IP address, with or without a port: `203.0.113.77`, `203.0.113.77:51234`, `[2001:db8::1]`,
     `[2001:db8::1]:443`, or a bare `2001:db8::1`. **A bare IPv6 address never carries a port**: its colons are
     never split (RFC 7239 § 6 writes a port only beside a bracketed one). A port is 1 to 65535. Anything else (a
     hostname, `unknown`, an empty entry, port 0) is not an IP address.
   - `trusted-proxies` lists the hops **in front of** the gateway, such as a load balancer: IP addresses and CIDR
     ranges, nothing else. Empty, the default on both targets, means the gateway is the only hop, and the
     rightmost entry is the client. Each listed entry is skipped from the right, compared by its IP alone, so a
     port on a listed hop changes nothing. If every entry is listed, the leftmost is the client.
   - The entry the walk stops at was written by a proxy the request really passed, so no client can choose it, and
     that holds for its port as much as for its address. A port anywhere else is never read.
   - If that entry is not an IP address, there is **no address**. The record then stores none, and the rate
     limiter keys on the peer. veyq never guesses.
   - If it carries no port, the port is **unknown**, and the address still stands. Most edges write none (M6, R1).
   - The rate limiter keys on the IP alone. A source port changes with every connection, so a key that held it
     would open a bucket per connection.
   - `Forwarded` (RFC 7239) and `X-Real-Ip` are ignored. Traefik neither writes `Forwarded` nor strips one (M3,
     M6), so on Compose a `Forwarded` that reaches the app is always the client's own.
4. **Quarkus's `proxy-address-forwarding` is off on both targets.** There is one reading of the header, not two.
   `request.remoteAddress()` then returns the peer: useless as a client address, but never forged. That is why no
   guard forbids calling it.
5. **The setting is veyq's, not Quarkus's:** `veyq.http.trusted-proxies` (`VEYQ_HTTP_TRUSTED_PROXIES`). The chart
   value `app.trustedProxies` renders it. Compose ships no knob: a proxy in front of Traefik would also need
   Traefik's `trustedIPs`, and that combination stays outside the recipe, as `networking.md` already says. A
   malformed entry, such as a hostname, stops the app at startup.
6. **Helm without an enforced NetworkPolicy is documented, not refused.** With `networkPolicy.enabled=false`, or
   with a CNI that enforces none, any pod can reach the app and write the whole header, and no list can tell it
   apart from the gateway. The chart's NOTES and the Run docs say so. Refusing to render would only push operators
   to list a pod range, which trusts every pod (ADR 0071 § 4).

What each topology yields (R1 marks the appending ones):

| Topology | Arrives at the app | Leftmost (today) | This rule | Port |
|---|---|---|---|---|
| Compose, or a Traefik gateway | `203.0.113.77` | client | client | unknown (M6) |
| Appending gateway | `198.51.100.66, 203.0.113.77` | **forged** | client | unknown |
| LB → replacing gateway | `10.0.0.5` | LB | LB | unknown |
| LB → appending chain, `trusted-proxies=10.0.0.5` | `198.51.100.66, 203.0.113.77, 10.0.0.5` | **forged** | client | unknown |
| the same, no `trusted-proxies` | the same | **forged** | LB | unknown |
| LB writing `ip:port` (M7) → appending gateway, `trusted-proxies=10.0.0.5` | `198.51.100.66, 203.0.113.77:51234, 10.0.0.5` | **forged** | client | `51234` |
| A pod past an unenforced policy | `198.51.100.66` | **forged** | **forged** (decision 6) | the pod's choice |

The rule never does worse than today, and where it falls short it yields the operator's own load balancer, never
a third party. Where a port is unknown, it is because no hop wrote one, never because veyq dropped it.

## Rejected alternatives

- **Quarkus's leftmost entry with `trusted-proxies`.** Its check covers the peer, not the chain, so an appending
  edge still hands the app a forged leftmost entry.
- **The trust list must also name the peer.** The gateway's peer address is an ephemeral pod IP. A list that
  survives a restart covers the pod range and trusts every pod (ADR 0071 § 4). This was this refinement's own
  first draft.
- **Trust private ranges by default.** A LAN client behind an appending gateway is itself private, so the walk
  would step past it to a forged entry.
- **A live preflight** that sends a forged header to the instance's own domain at startup. It measures only the
  hairpin path, never a pod that reaches the app directly. It fails without hairpin NAT, which is common at home,
  and it checks once what can change at runtime.
- **The operator attests that the topology is safe.** A wrong value yields forged evidence. What is left of
  attestation is `trusted-proxies`, and it can only add hops, never vouch for the peer.
- **The chart refuses `networkPolicy.enabled=false`** without a trust list. See decision 6.
- **A port at the stop is dropped, or makes the entry no address.** The entry is proxy-written, so keeping its port
  opens no forgery, and the port is exactly the part that names a subscriber behind carrier-grade NAT (M8). An AWS
  Application Load Balancer writes it on request and an Azure Application Gateway always (M7). This was this
  ADR's own first reading of decision 3.
- **Read the port from `Forwarded` (RFC 7239).** No edge measured writes it (M6), and Traefik passes a client's own
  through (M3), so on Compose one that arrives is always client-written.
- **Traefik's access log**, which holds the client port. Logs name no person
  ([ADR 0092](0092-logs-are-operational-and-name-no-person.md)), and it would be a second store of the evidence.
- **A Traefik plugin from the remote plugin catalog.** Traefik fetches it at start: a start-time dependency on
  central infrastructure and a supply chain, against self-hostable by default. A local plugin that travels in the
  recipe is not rejected; it is one of the follow-up's sources (§ Consequences).
- **The RiskIdent `traefik-remoteaddr-plugin` as it is.** Its `ip` and `port` headers cut an IPv6 peer at the first
  colon (`[2001` and `db8::1]:4711`, M9). Only its `address` header is right.
- **A TCP router with the PROXY protocol on Compose.** The blob router routes by path on the app's own host, and a
  TCP router sees no path. Store and app would need separate origins only for the port.

## Consequences

- **The rate limiter changes on one topology.** Behind an appending chain without `trusted-proxies`, every client
  shares the load balancer's bucket. Today each client gets its own bucket, forgeable. No cluster has run the chart
  yet (`networking.md` § How far this is proven), and the Run docs name the setting.
- **The record's address is only as strong as the network on Helm** (decision 6). On Compose it is strong by
  construction.
- **A layer-4 load balancer in front of the gateway that rewrites the source address** makes the gateway see a
  node, not the client (R3). The address is then a node's: useless, harmless, and no list in veyq can fix it.
  Keeping the client's source address is part of setting up the gateway, and the Run docs say so.
- **ADR 0071 § 4 changes meaning.** `trustedProxies` now names the hops in front of the gateway, and its property
  is veyq's. The selector-based policy remains what confines the peer.
- **ADR 0092 § 2 points here,** and its R7 is now measured (M3).
- **The port is known only where a hop writes it into the stop entry, or where the client is the peer.** On
  Compose and behind the common gateways it is unknown (M6, R1), and the record keeps the address alone. The other
  sources are a follow-up that lands before the moderation CR (inbox #472):
  - the PROXY protocol from the gateway to the app (`quarkus.http.proxy.use-proxy-protocol`). The socket peer is
    then read from `connection().remoteAddress(true)`, and the kubelet probes move to a listener without it;
  - a header the edge overwrites on every request, read only when the operator says the edge writes it. On Compose
    that is a local Traefik plugin in the recipe;
  - the chart values that choose between them.
- **A port is only as good as the server's clock.** Carrier-grade NAT hands a port to the next subscriber quickly, so
  the record keeps its time in milliseconds UTC (ADR 0093 § 10) and the Run docs ask for NTP.
- **The tests can no longer forge an address by accident.** The rate-limit tests send `X-Forwarded-For` from
  loopback with an empty trust list, so their single entry is the rightmost. They keep working without turning on
  Quarkus forwarding.

## Inventory for the CR (not built)

This CR touches `app-deployment.yaml`, `values.yaml`, `NOTES.txt`, `networking.md` and `kubernetes.md`, and so does
`cr-helm-gateway-api`. The two run one after the other, never side by side.

- **Resolver**: `backend/core/.../http/ClientAddress.kt`. A pure function of (socket peer, `X-Forwarded-For`
  lines, trust list) returning an address with its port (or the port unknown), or none, plus a request-scoped accessor over the socket peer
  (`request.connection().remoteAddress()`, as `AdminChannel.kt:25` does). IPv4-mapped IPv6 is normalised.
- **Config**: `veyq.http.trusted-proxies`, a list of IPs and CIDRs validated at startup. The comment at
  `application.properties:131-137` is rewritten.
- **Rate limiter**: `RateLimitFilters.kt:80` keys on the resolver. Its class doc (`:52-56`) is rewritten.
- **Tests**:
  - `ClientAddressTest` (unit): every row of the table above, the no-header case, a non-IP entry at the stop, an
    entry before a listed hop, and startup refusal of a hostname. The port forms: `[2001:db8::1]:443` parsed, a
    bare IPv6 never split, a port left of the stop never read, and the limiter's key unchanged by a port.
  - The rate-limit slice (the bite): with a limit of 3, three requests carrying `198.51.100.66, 203.0.113.77`,
    then one carrying `198.51.100.99, 203.0.113.77`, must get **429**, because a new forged leftmost entry opens no
    bucket. The positive control: `198.51.100.66, 203.0.113.78` gets **200**, so the key is not one global bucket.
  - Bite-proof both: resolve to the leftmost entry and the 429 fails; resolve to the peer and the control fails.
  - One profile with `trusted-proxies=10.0.0.5` sends `198.51.100.66, 203.0.113.77, 10.0.0.5` and is keyed on
    `203.0.113.77`.
  - `AuthRateLimitTest`, `AuthRateLimitRefillTest`: drop `proxy-address-forwarding`.
    `AdminChannelForgedHeadersSliceTest` keeps it on, because it proves the admin channel ignores forwarding.
- **For the moderation CR, not this one**: the guest-write slice sends `198.51.100.66, 203.0.113.77`. It asserts
  that `203.0.113.77` is recorded (the control) and that `198.51.100.66` is in no record and no log. A stop entry
  `203.0.113.77:51234` records port `51234`; `198.51.100.66:4711, 203.0.113.77` records no port, and `4711` is in no
  record and no log. When the resolver yields no address, nothing is recorded; when it yields no port, the address
  is recorded alone. This replaces ADR 0092's single-address slice.
- **Compose**: drop `QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING` (`infra/docker-compose.prod.yml:129-130`). No new
  variable.
- **Helm**:
  - `app-deployment.yaml:69-76`: drop forwarding and render `VEYQ_HTTP_TRUSTED_PROXIES`.
  - `values.yaml:50-54`: the comment says the value names the hops in front of the gateway, never its pod range.
  - `NOTES.txt`: a line when `networkPolicy.enabled=false`.
- **Run docs**:
  - `networking.md` § The client address and the rate limits: the rule, the topology table, the shared-bucket
    consequence, and keeping the client's source address in front of the gateway (R3).
  - `hardening.md` § Trust only a stable proxy address (`:53-57`): name the hops in front of the gateway.
  - `kubernetes.md:19` and `configuration.md:74`: the value's new meaning.
- **State docs**:
  - `cc.security.rate-limit.md`: the "Trusts the client IP" bullet, the deferred "address-based `trusted-proxies`
    default" (`:164`) resolved, and § Verified by (`:171`).
  - ADR 0071 decision 4 and its Context, edited in place.

## Measurement ledger

Substrate: branch `claude/vigorous-kepler-251ad0` at `35c1253a`, clean.

- **M1** Quarkus 3.36.0 sources (`backend/gradle/libs.versions.toml:6`), measured by the logging session (ADR 0092
  M18):
  - `ForwardingProxyOptions.java:50` honours `X-Forwarded-For` by default.
  - `:61-62` builds no check without `trusted-proxies`.
  - `ForwardedParser.java:291,318-321` takes the first entry.
- **M2** `grep -rn -i -E 'address-forwarding|ADDRESS_FORWARDING' --exclude-dir=build --exclude-dir=node_modules .`
  finds `infra/docker-compose.prod.yml:130`, `charts/veyq/templates/app-deployment.yaml:70`, and three test
  profiles (`AuthRateLimitTest.kt:36`, `AuthRateLimitRefillTest.kt:32`, `AdminChannelForgedHeadersSliceTest.kt:30`).
- **M3** Traefik v3.6.0 `pkg/middlewares/forwardedheaders/forwarded_header.go` (fetched from
  `raw.githubusercontent.com/traefik/traefik/v3.6.0/...`):
  - `:191-195`: unless `insecure` or a trusted peer, it deletes every header in `xHeaders`.
  - `:31-43`: `xHeaders` holds the `X-Forwarded-*` family and `X-Real-Ip`, and not `Forwarded`.
- **M4** `grep -n -i -E 'trustedIPs|forwardedHeaders' infra/docker-compose.prod.yml` finds nothing.
- **M5** `grep -rn -E '\.scheme\(\)|absoluteURI|isSSL|getBaseUri|getRequestUri|getAbsolutePath|\.authority\(\)' backend --include='*.kt'`,
  over main sources, finds nothing.
  - `grep -rn 'remoteAddress()' backend --include='*.kt'`, over main sources, finds only `RateLimitFilters.kt:80`
    and the comment in `AdminChannel.kt:20`.

- **M6** Traefik v3.6.0 sources, measured by the source-port research (substrate: `cr-prioritization-fad81b` at
  `96d580cd`):
  - `pkg/proxy/fast/proxy.go:215-227`, and Go's `net/http/httputil/reverseproxy.go:81`: `X-Forwarded-For` gets the
    peer's IP from `net.SplitHostPort`, never its port.
  - `forwarded_header.go:121-135, 170-171`: `X-Forwarded-Port` is the server's port, not the client's.
    `:142-146`: `X-Real-Ip` is the IP alone.
  - No writer of `Forwarded` in the middleware code. `pkg/config/dynamic/http_config.go` has no `proxyProtocol`: an
    HTTP service gets no PROXY protocol, only a TCP one (`tcp_config.go:90-92`).
- **M7** Load balancers that write a port into `X-Forwarded-For`:
  - AWS Application Load Balancer, attribute `routing.http.xff_client_port.enabled`: `12.34.56.78:8080`, an IPv6
    entry bracketed, in the `append` mode only
    ([docs](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html)).
  - Azure Application Gateway, always: "a comma-separated list of IP:port"
    ([docs](https://learn.microsoft.com/en-us/azure/application-gateway/how-application-gateway-works)). Its IPv6
    form is not documented (R4).
- **M8** BT-Drs. 21/6581 (the German IP-retention bill, not passed), § 177(1) TKG-E and its reasoning (pp. 42, 59):
  behind NAT an access provider can name a subscriber with certainty only from the IP address, the port and a
  timestamp to the second with its time zone. RFC 7239 § 6 gives the same reason for its port: "the identification
  of the endpoint in a NATed environment".
- **M9** `github.com/RiskIdent/traefik-remoteaddr-plugin` v0.1.1, `plugin.go:53-66`: `strings.Cut(req.RemoteAddr, ":")`
  splits the peer at its first colon.

## Remembered (measure before it drives code)

- **R1**: Envoy and Envoy-based gateways, with no trusted hops configured, append the peer to `X-Forwarded-For`.
  So does a cloud HTTP load balancer. The CR's unit table does not depend on it; the Run docs' wording does.
- **R2**: Go's `httputil.ReverseProxy`, which Traefik forwards through, appends the peer to `X-Forwarded-For` after
  the middleware in M3 has cleared it.
- **R3**: A Kubernetes `LoadBalancer` Service with `externalTrafficPolicy: Cluster` rewrites the source address to a
  node. `Local`, or the PROXY protocol where the implementation supports it, keeps the client's address.
- **R4**: An Azure Application Gateway might write an IPv6 entry unbracketed, as `2001:db8::1:4711`. That parses as
  another valid IPv6 address, so the Run docs ask an operator behind one with IPv6 to look at the header.
