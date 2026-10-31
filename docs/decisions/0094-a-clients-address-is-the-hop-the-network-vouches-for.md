# ADR 0094 — A client's address is the hop the network vouches for

- Status: Accepted
- Built: yes
- Date: 2026-10-16
- Model: [cc.privacy.retention](../crosscutting/cc.privacy.retention.md)

## Context

Two consumers need the address of the client that sent a request:

- **The guest-write record** (the moderation ADR, [0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)) keeps the address and time of a guest's write as evidence
  for an abuse report. A forged address there can name an innocent third party. Behind carrier-grade NAT many
  subscribers share one public IPv4 address, and only the address with its **source port** and an exact time names
  one of them.
- **The per-IP rate limiter** keys its bucket on it. A forged address there opens a fresh bucket per request.

Before this ADR the address came from Quarkus (`request.remoteAddress()`). With `proxy-address-forwarding` on and no
`trusted-proxies`, Quarkus honours `X-Forwarded-For` from any peer and takes its **leftmost** entry, which is the
one a client writes itself. Both targets turned forwarding on. How far that is forgeable depends on the proxy in front of the app, which veyq
cannot choose on Kubernetes:

- A proxy that **replaces** the header from an untrusted peer leaves one entry, the real client. Traefik, as shipped
  on Compose, deletes every incoming `X-Forwarded-*` header from a peer outside its `trustedIPs`, and Compose
  sets none. **On Compose even the leftmost entry is not forgeable.**
- A proxy that **appends** keeps the client's entries and adds the peer it saw on the right (Envoy-based
  gateways, a cloud load balancer). The leftmost entry is then the client's choice.
- A peer that reaches the app **without a proxy** writes the whole header. Only the network stops that: Compose's
  separate networks and the chart's NetworkPolicy ([ADR 0071](0071-runtime-hardening-on-both-targets.md)).

Nothing in the backend reads the forwarded scheme, host or URI through Quarkus: the tenant's host is read from
`X-Forwarded-Host` directly and the admin channel reads the socket. Quarkus's forwarding fed only
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
   - An entry is an IP address, with or without a port. **A bare IPv6 address never carries a port**: its colons are
     never split (RFC 7239 § 6 writes a port only beside a bracketed one). Anything else is not an IP address; the
     grammar is [cc.privacy.retention](../crosscutting/cc.privacy.retention.md) § A client's address.
   - `trusted-proxies` lists the hops **in front of** the gateway, such as a load balancer: IP addresses and CIDR
     ranges, nothing else. Empty, the default on both targets, means the gateway is the only hop, and the
     rightmost entry is the client. Each listed entry is skipped from the right, compared by its IP alone, so a
     port on a listed hop changes nothing. If every entry is listed, the leftmost is the client.
   - The entry the walk stops at was written by a proxy the request really passed, so no client can choose it, and
     that holds for its port as much as for its address. A port anywhere else is never read, except the configured
     source of decision 7, and only for the stop entry.
   - If that entry is not an IP address, there is **no address**. The record then stores none, and the rate
     limiter keys on the peer. veyq never guesses.
   - If it carries no port, the port is the configured source's (decision 7) or **unknown**, and the address still
     stands. Most edges write none into `X-Forwarded-For`.
   - The rate limiter keys on the IP alone. A source port changes with every connection, so a key that held it
     would open a bucket per connection.
   - `Forwarded` (RFC 7239) and `X-Real-Ip` are ignored. Traefik neither writes `Forwarded` nor strips one, so on
     Compose a `Forwarded` that reaches the app is always the client's own.
4. **Quarkus's `proxy-address-forwarding` is off on both targets.** There is one reading of the header, not two.
   `request.remoteAddress()` then returns the peer: useless as a client address, but never forged. That is why no
   guard forbids calling it.
5. **The setting is veyq's, not Quarkus's:** `veyq.http.trusted-proxies`, which the chart's `app.trustedProxies`
   renders. Compose ships no knob: a proxy in front of Traefik would also need
   Traefik's `trustedIPs`, and that combination stays outside the recipe, as `networking.md` already says. A
   malformed entry, such as a hostname, stops the app at startup.
6. **Helm without an enforced NetworkPolicy is documented, not refused.** With `networkPolicy.enabled=false`, or
   with a CNI that enforces none, any pod can reach the app and write the whole header, and no list can tell it
   apart from the gateway. The chart's NOTES and the Run docs say so. Refusing to render would only push operators
   to list a pod range, which trusts every pod (ADR 0071 § 4).
7. **Where the stop entry carries no port, one configured source may state it**: `none` (the default),
   `proxy-protocol` or `header`, and for `header` a configured header name.
   - The walk of decision 3 is unchanged. The source speaks only when the stop entry carries no port, and only if
     its IP **equals** the stop entry's; otherwise the port stays unknown. A stop entry's own port wins, and the
     source never changes the address.
   - `proxy-protocol`: the PROXY header's source, which Vert.x hands out as the connection's `remoteAddress()`.
     It is also the peer: only the gateway reaches the port (decision 2), and Vert.x closes every connection that
     does not open with a PROXY header, so it is the network-vouched hop's own statement of its peer. With no
     `X-Forwarded-For` the client is that source, with its port. A `LOCAL` or `UNKNOWN` header (a gateway's health
     check) leaves the real peer.
   - `header`: exactly one line holding one entry, which carries a port. Two lines, a comma, an entry without a port
     or a value that is no address say nothing.
   - `none`: the header is **never read**, whatever arrives. Traefik passes an unknown header through, so a client
     that sends its own address with another port would name another subscriber on the same carrier-grade NAT
     address; the IP check cannot stop that, only the edge's overwrite can. That is why the source is **configured,
     never detected**.
   - The two settings agree or the app does not start: `proxy-protocol` exactly when Quarkus's PROXY protocol is
     on, refused at startup naming both keys and no address.
   - **The admin channel reads the connection's real addresses.** Under PROXY the plain ones are the PROXY header's
     destination and source: a header naming the admin port and a private client would open the Server-Owner operations
     on the public port.
   - **Compose: `header`, a literal with no knob** (as decision 5). A local Traefik plugin deletes the configured
     header and sets it to the accepted connection's `address:port` on every request. It travels in the release
     recipe, mounted read-only; nothing is fetched. The app's routing file attaches it to every app router exactly
     when the app's own source is `header`, and hands it the header name the app reads — one setting of one process,
     so the app never reads a header Traefik does not overwrite. Never on the store or the admin router. A guard holds
     the recipe to it, and the stack test forges the header through the release's own Traefik.
   - **Helm: the same three sources, as chart values.** The PROXY protocol renders both keys and moves the three
     probes to the admin listener: a kubelet probe sends no
     PROXY header, and the public port closes such a connection unanswered. The chart renders no gateway object; the
     operator aims PROXY at the app's routes, never at the store's, which speaks none.

What each topology yields:

| Topology | Arrives at the app | Quarkus's leftmost (rejected) | This rule | Port |
|---|---|---|---|---|
| Compose (the plugin, decision 7) | `203.0.113.77` | client | client | the plugin's |
| A Traefik gateway | `203.0.113.77` | client | client | unknown |
| A gateway sending PROXY, `source=proxy-protocol` | `203.0.113.77` | client | client | the PROXY source's |
| Appending gateway | `198.51.100.66, 203.0.113.77` | **forged** | client | unknown |
| LB → replacing gateway | `10.0.0.5` | LB | LB | unknown |
| LB → appending chain, `trusted-proxies=10.0.0.5` | `198.51.100.66, 203.0.113.77, 10.0.0.5` | **forged** | client | unknown |
| the same, no `trusted-proxies` | the same | **forged** | LB | unknown |
| LB writing `ip:port` → appending gateway, `trusted-proxies=10.0.0.5` | `198.51.100.66, 203.0.113.77:51234, 10.0.0.5` | **forged** | client | `51234` |
| A pod past an unenforced policy | `198.51.100.66` | **forged** | **forged** (decision 6) | the pod's choice |

The rule never does worse than the leftmost entry, and where it falls short it yields the operator's own load balancer,
never a third party. Where a port is unknown, it is because no hop wrote one and no configured source stated it, never
because veyq dropped it.

## Rejected alternatives

- **Quarkus's leftmost entry with `trusted-proxies`.** Its check covers the peer, not the chain, so an appending
  edge still hands the app a forged leftmost entry.
- **The trust list must also name the peer.** The gateway's peer address is an ephemeral pod IP. A list that
  survives a restart covers the pod range and trusts every pod (ADR 0071 § 4).
- **Trust private ranges by default.** A LAN client behind an appending gateway is itself private, so the walk
  would step past it to a forged entry.
- **A live preflight** that sends a forged header to the instance's own domain at startup. It measures only the
  hairpin path, never a pod that reaches the app directly. It fails without hairpin NAT, which is common at home,
  and it checks once what can change at runtime.
- **The operator attests that the topology is safe.** A wrong value yields forged evidence. What is left of
  attestation is `trusted-proxies`, and it can only add hops, never vouch for the peer.
- **The chart refuses `networkPolicy.enabled=false`** without a trust list. See decision 6.
- **A port at the stop is dropped, or makes the entry no address.** The entry is proxy-written, so keeping its port
  opens no forgery, and the port is exactly the part that names a subscriber behind carrier-grade NAT. An AWS
  Application Load Balancer writes it on request and an Azure Application Gateway always.
- **Read the port from `Forwarded` (RFC 7239).** No edge measured writes it, and Traefik passes a client's own
  through, so on Compose one that arrives is always client-written.
- **Traefik's access log**, which holds the client port. Logs name no person
  ([ADR 0092](0092-logs-are-operational-and-name-no-person.md)), and it would be a second store of the evidence.
- **A Traefik plugin from the remote plugin catalog.** Traefik fetches it at start: a start-time dependency on
  central infrastructure and a supply chain, against self-hostable by default. A local plugin that travels in the
  recipe is what decision 7 ships, and rule 5 of `validate_compose_posture.py` refuses a remote one.
- **The RiskIdent `traefik-remoteaddr-plugin` as it is.** Its `ip` and `port` headers cut an IPv6 peer at the first
  colon (`[2001` and `db8::1]:4711`). Only its `address` header is right.
- **A TCP router with the PROXY protocol on Compose.** The blob router routes by path on the app's own host, and a
  TCP router sees no path. Store and app would need separate origins only for the port.
- **Detect the source** — believe the header when it arrives, the PROXY header when a connection opens with one. A
  header Traefik passed through looks exactly like one it wrote; only the operator knows which edge overwrites it.
- **An opt-in echo of the resolved address** (a response header on `GET /api/setup`), to prove the Compose join end
  to end before anything records the port. It is wire shape and a production path only diagnosis uses. The stack
  lane forges the header through the release's own Traefik and plugin instead, and the one join that leaves out —
  the routing file attaches the plugin exactly when the app reads the header — is one setting of one process.
- **The probes on the Quarkus management interface, or a `tcpSocket` probe, under PROXY.** The first is a third
  listener with nothing to serve (the backend has no health extension); the second passes on connect and stops
  proving that Flyway has migrated.

## Consequences

- **The rate limiter changes on one topology.** Behind an appending chain without `trusted-proxies`, every client
  shares the load balancer's bucket. Under the leftmost entry each client had its own bucket, forgeable. The Run docs
  name the setting.
- **The record's address is only as strong as the network on Helm** (decision 6). On Compose it is strong by
  construction.
- **A layer-4 load balancer in front of the gateway that rewrites the source address** makes the gateway see a
  node, not the client. The address is then a node's: useless, harmless, and no list in veyq can fix it.
  Keeping the client's source address is part of setting up the gateway, and the Run docs say so.
- **ADR 0071 § 4 changes meaning.** `trustedProxies` now names the hops in front of the gateway, and its property
  is veyq's. The selector-based policy remains what confines the peer.
- **The port is known where a hop writes it into the stop entry, where the client is the peer, and where the
  configured source states it** (decision 7): on Compose through the plugin, on Helm with the PROXY protocol or a
  header the operator's edge overwrites. Behind every other edge it is unknown, and the record keeps the
  address alone.
- **Traefik files local plugins under `experimental`.** Its image is digest-pinned, so an upgrade is a deliberate
  change. A dropped feature fails loudly: a middleware naming a plugin Traefik did not load fails its routers, and
  the domain answers 404. A changed behaviour reddens the stack lane's `client port` stage.
- **Under the PROXY protocol the probes prove the process and the database, not the public listener.** A public
  bind failure fails the boot too: Quarkus fails startup on a bind failure, which uc.admin.admin-channel E2 shows for
  the admin listener.
- **A port is only as good as the server's clock.** Carrier-grade NAT hands a port to the next subscriber quickly, so
  the record keeps its time in milliseconds UTC (ADR 0093 § 10) and the Run docs ask for NTP.
- The Run pages carry the rule for the operator: [Networking and TLS](../../site/src/content/docs/run/networking.md)
  (the rule, the topology table, the source port and its sources),
  [Hardening](../../site/src/content/docs/run/hardening.md) (what `app.trustedProxies` lists),
  [Configuration](../../site/src/content/docs/run/configuration.md) (the client-port settings),
  [Operations](../../site/src/content/docs/run/operations.md) (the probe under PROXY) and
  [Kubernetes](../../site/src/content/docs/run/kubernetes.md) (where PROXY is chosen).
