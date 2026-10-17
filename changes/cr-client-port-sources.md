---
id: cr-client-port-sources
title: The client's source port where no hop writes it into X-Forwarded-For — PROXY protocol, a header the edge overwrites, a local Traefik plugin
status: in-progress
satisfies: ["cc.security.rate-limit", "uc.admin.admin-channel"]
adrs: ["0094", "0093", "0071", "0065", "0064", "0057", "0090", "0092"]
invariants:
  - "Operator-blind E2EE: unchanged. The port is a value the server already observes on the socket; this CR only decides which hop's statement of it is believed. No wire shape changes, so there is no contracts phase and no web phase"
  - "Self-hostable by default: the Traefik plugin is ~30 lines of Go that travel in the signed recipe as two flat files and load with no network (measured on an --internal Docker network, M11); a remote catalog plugin stays rejected (ADR 0094) and a guard refuses one. Traefik's `experimental.localPlugins` is the named risk (Open O2)"
  - "Every infra decision has an answer on both targets: Compose gets the header source through the plugin; Helm gets `app.clientPort.*` with the PROXY protocol or a header the operator's edge writes, and its probes move off a PROXY listener"
  - "Logs name no person (ADR 0092): no address and no port reaches a log line, the startup refusal included"
  - "No deprecated dependencies: no dependency is added. netty-codec-haproxy already comes with quarkus-netty (research M); Yaegi is Traefik's own"
---

## Goal

Behind carrier-grade NAT an IPv4 address names no subscriber without its source port and an exact time (ADR 0094
M8), and the guest-write record of [ADR 0093 § 10](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
keeps `client_port` for that reason. Today [ADR 0094](../docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)
decision 3 knows a port only where a load balancer writes `ip:port` into the stop entry of `X-Forwarded-For` (AWS
ALB on request, Azure Application Gateway always). Traefik and the common gateways write none (M6), so on Compose and
behind them the port is unknown. After this CR the port can come from **one configured source**, believed only for the
entry the walk stopped at and only when the source's IP equals that entry's IP: the **PROXY protocol** from the
gateway to the app (Helm: Envoy Gateway, Istio), or a **header the edge overwrites on every request**
(`X-Veyq-Client-Addr`; on Compose a local Traefik plugin in the recipe). The admin channel is made PROXY-safe on the
way, the chart's probes move to a listener without PROXY, and the stack lane proves a client cannot forge the header
through the recipe's Traefik. This lands **before** the moderation CR, which is the port's first consumer.

## Context & constraints

- **The concept is ADR 0094's, not this CR's.** Its § Consequences already names the three sources (PROXY with the peer
  read from `remoteAddress(true)` and the probes moved, a header read only when the operator says the edge writes it,
  a local Traefik plugin on Compose, chart values choosing). Phase 4 turns that bullet into **decision 7**, edited in
  place, and narrows decision 3's *"A port anywhere else is never read"*. The rule details below come from the
  source-port research (measured parts cited, remembered parts marked) and from this refine's own measurements (§
  Measured at refine).
- **The concerns it serves:** [cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md) (the resolver
  lives under its § Verified by; the limiter keeps keying on the IP alone) and
  [uc.admin.admin-channel](../docs/features/admin/use-cases/uc.admin.admin-channel.md) (step 3: "classifies the request
  **by its socket**" — under PROXY the socket's addresses are overridden, M10, so the predicate must read the real
  ones). No use case owns the port; the guest-write record's use case is the moderation CR's.
- **What the CR builds on (measured on `6b67a082`):**
  - The resolver: `ClientAddress.resolve(peer, forwardedFor, trusted)` → `Resolved(ip, port?)`, pure, in the backend
    mutation scope (`grep -n 'ClientAddress' backend/core/pitest.scope.json`). Its accessor `clientAddress()` and
    `socketPeer()` read `connection().remoteAddress()` (`grep -n 'fun HttpServerRequest' backend/core/src/main/kotlin/me/raddatz/veyq/core/http/ClientAddress.kt`).
    One caller: `grep -rn 'clientAddress(\|socketPeer(' backend/core/src/main backend/apps/memories/src/main` →
    `RateLimitFilters.kt:88` only.
  - The admin channel predicate reads `request.localAddress()` and `connection().remoteAddress()`
    (`grep -n 'isAdminChannel(request' backend/core/src/main/kotlin/me/raddatz/veyq/core/adminchannel/AdminChannel.kt`).
  - The admin listener is a second Vert.x server built from `HttpServerOptions()` defaults plus the host, running
    Quarkus's root handler (`grep -n 'createHttpServer' backend/core/src/main/kotlin/me/raddatz/veyq/core/adminchannel/AdminListener.kt`).
    So `quarkus.http.proxy.use-proxy-protocol` never reaches it, and `GET /api/setup` answers there exactly as on the
    public port (uc.admin.admin-channel A4: setup status answers on both channels, "both targets' probes poll it").
  - The config group: `VeyqConfig.Http` (`grep -n 'interface Http' -A12 backend/core/src/main/kotlin/me/raddatz/veyq/core/config/VeyqConfig.kt`).
    SmallRye refuses a `veyq.*` key no mapping claims (SRCFG00050), so a test profile may set
    `veyq.http.client-port.*` only once the mapping declares it.
  - Compose: Traefik is `traefik:v3.6@sha256:31267173…` under the `hardened` anchor (`read_only`, `cap_drop: [ALL]`,
    `no-new-privileges`), uid 65532, one provider `--providers.file.directory=/etc/traefik/dynamic`
    (`grep -n 'providers.file\|user: "65532\|x-hardened' infra/docker-compose.prod.yml`). The app sets no
    `QUARKUS_HTTP_PROXY_*` (`grep -c QUARKUS_HTTP_PROXY infra/docker-compose.prod.yml` → 0).
  - The rendered routing file: `TraefikFileIngressSink.render` already defines a middleware and attaches it per router
    (`grep -n 'INERT\|middleware' backend/core/src/main/kotlin/me/raddatz/veyq/core/tenant/TraefikFileIngressSink.kt`).
  - The chart's three probes `httpGet /api/setup` on `port: http`
    (`grep -n 'port: http' charts/veyq/templates/app-deployment.yaml` → `:119`, `:125`, `:132`), and the admin port
    `8090` named `admin` (`grep -n 'name: admin' charts/veyq/templates/app-deployment.yaml`). The NetworkPolicy admits
    the admin port from the admin pod alone (`python3 scripts/chart_meta_check.py` → "admitted by policy from the
    admin pod alone").
  - The release recipe is **derived from the compose file, and every file it reads must be a flat name beside it**:
    `release_assets.py` refuses a path with a directory in it, and a name GitHub would rename (a leading period)
    (`sed -n 16,27p scripts/release_assets.py`). So the plugin cannot travel as a directory or as a file named
    `.traefik.yml` — it travels as two flat files mounted file by file (M11 proves the shape works).
- **Sibling CRs on the same files** (§ *Two CRs that write the same page*): `cr-kubernetes-backup-gate` edits
  `charts/veyq/values.schema.json` and `application.properties`; `cr-signed-release-manifest` and
  `cr-kubernetes-backup-gate` edit `scripts/run_stack_e2e.py` (the install path; this CR adds a stage before it). This
  CR touches only the `app.clientPort` subtree of the schema, the `# Per-IP rate limit` block of the properties, and a
  new stage of the lane. The second to land rebases onto the first and re-reads the shared file. The moderation CR
  (not written) consumes the port; § For the moderation CR in ADR 0094 binds it (phase 4).

### Decisions this CR takes (each with the line that settles it)

- **D1 — one configured source, named by the operator.** `veyq.http.client-port.source` = `none` (default) |
  `proxy-protocol` | `header` (`VEYQ_HTTP_CLIENT_PORT_SOURCE`), and `veyq.http.client-port.header` (default
  `X-Veyq-Client-Addr`, `VEYQ_HTTP_CLIENT_PORT_HEADER`), both on `VeyqConfig.Http`. The default header name is a
  `const val` in `ClientAddress` that `@WithDefault` references, so the literal has one home (D7).
- **D2 — the rule, an extension of decision 3 that keeps its order.**
  - The walk is unchanged. Only when the stop entry carries **no** port does the source speak, and only if the
    source's IP **equals** the stop entry's IP; otherwise the port stays unknown. A stop entry's own port always wins.
  - `proxy-protocol`: the source is the PROXY header's source address, which Vert.x hands out as
    `connection().remoteAddress()` (M10). It is also the **peer** the resolver sees: only the gateway can reach the port
    (ADR 0071) and Vert.x drops every connection that does not open with a PROXY header (M10), so it is the
    network-vouched hop's own statement of its peer. With no `X-Forwarded-For`, the client is therefore the PROXY
    source with its port. A PROXY `LOCAL`/`UNKNOWN` header (a gateway's health check) leaves the real peer (M10).
  - `header`: exactly one value, parsed by `ClientAddress.entry`, carrying a port. Two lines, a comma, a value without
    a port, or one that is not an address → the port is unknown.
  - `none`: the configured header is **never read**, whatever arrives. Traefik passes an unknown header through, so a
    client-sent `X-Veyq-Client-Addr: <its own IP>:<another port>` would otherwise name another subscriber on the same
    CGNAT address — the IP check cannot stop that, only the edge's overwrite can. That is why the source is
    configured, never detected.
  - The rate limiter keys on the IP alone, unchanged (decision 3).
- **D3 — PROXY and the source agree or the app does not start.** `source=proxy-protocol` without
  `quarkus.http.proxy.use-proxy-protocol=true`, or that key on with any other source, stops the boot with a message
  naming both keys and no address. A `StartupEvent` observer that throws is the precedent for failing startup
  (`grep -n 'Uncaught on purpose' backend/core/src/main/kotlin/me/raddatz/veyq/core/adminchannel/AdminListener.kt`).
  `quarkus.management.proxy.use-proxy-protocol` is not used (there is no management interface).
- **D4 — the admin channel reads the real socket.** `AdminChannel.isAdminChannel(request, …)` reads
  `connection().localAddress(true)` and `connection().remoteAddress(true)` (both exist: M10). Under PROXY, today's
  `request.localAddress()` returns the PROXY header's **destination** (M10): a gateway that writes the admin port as
  its destination for a private client would open the Server-Owner operations on the public port. Latent until
  someone turns PROXY on — which this CR makes a supported setting, so it is fixed here, live-defect first (B4).
- **D5 — the chart's probes leave the public listener only under PROXY.** With `app.clientPort.source: proxyProtocol`
  the three probes become `httpGet /api/setup` on `port: admin`; otherwise they stay on `port: http`. A kubelet
  probe speaks no PROXY, and the public port then closes its connection unanswered (M10). Rejected: the Quarkus
  management interface (a third listener, and nothing to serve on it — the backend has no smallrye-health, `values.yaml`'s
  probe comment); a `tcpSocket` probe (passes on connect, so it stops proving Flyway has migrated). The residual,
  stated in the Run docs: under PROXY the probes prove the process and the database, not the public listener — a
  public bind failure still fails startup [R: Quarkus fails the boot on a bind failure; measured for the admin
  listener only, uc E2].
- **D6 — Compose: the header source through a local plugin that travels as two flat files.**
  - `infra/traefik-clientaddr.go` (package `clientaddr`, `net.SplitHostPort` + `net.JoinHostPort`, deletes then sets
    `X-Veyq-Client-Addr` on every request; M11 ran this shape) and `infra/traefik-clientaddr.yml` (the manifest:
    `type: middleware`, `import` = the module name). No `go.mod` (M11: loads without one).
  - Mounted file by file, `:ro`, to `/plugins-local/src/<module>/clientaddr.go` and `/plugins-local/src/<module>/.traefik.yml`
    (Traefik's working directory is `/`, M11). Declared by
    `--experimental.localplugins.clientaddr.modulename=<module>`. The module name is a path under
    `github.com/raddatzk/veyq/` that nothing fetches (M11 loaded it with no route out).
  - The app's env sets `VEYQ_HTTP_CLIENT_PORT_SOURCE: header` as a literal: **no knob** on Compose, as ADR 0094 decision
    5 has none for the trust list.
  - `TraefikFileIngressSink` renders the `veyq-client-addr` middleware and attaches it to every `*-app` router **iff**
    the app's own `client-port.source` is `header` — the same config value in the same process, so the app never reads
    a header its own routing file does not make Traefik overwrite. Never on the blobs or admin routers.
  - A middleware naming a plugin Traefik did not declare fails that router loudly (`plugin: unknown plugin type`, the
    domain answers 404, M11) — so a missing declaration is an outage, not a forgery. The silent failure is the other
    one (the app reads, nobody overwrites); D6's "iff" and the posture rule (phase 2) close it.
- **D7 — rule D grows by the port header.** `validate_lookup_single_home.py` rule D also holds the literal
  `X-Veyq-Client-Addr` (any case) to `core/http/ClientAddress.kt`: a second, unconditional reader in the moderation
  CR would honour a forged port, and no test that sends no such header notices. Same shape the owner took for rule D
  (`grep -n 'D\. \*\*A client' scripts/validate_lookup_single_home.py`).
- **D8 — Helm: values, never a gateway object.** `app.clientPort.source: none | proxyProtocol | header` (schema
  enum, default `none`) and `app.clientPort.header` (string, default `X-Veyq-Client-Addr`). The chart renders the env
  (and `QUARKUS_HTTP_PROXY_USE_PROXY_PROTOCOL=true` for `proxyProtocol`) and moves the probes (D5); it renders **no**
  `BackendTrafficPolicy`, `DestinationRule` or filter — `gateway.className` is "never interpreted"
  (`grep -n 'Never interpreted' charts/veyq/values.yaml`). NOTES, under `proxyProtocol`: the gateway must send PROXY v2
  to the app's routes only, never to the store, or every request fails.
- **D9 — what the Run docs may name.** Measured mechanisms only: Envoy Gateway `BackendTrafficPolicy.spec.proxyProtocol`
  targeted at the app routes by label (the chart route's `app.kubernetes.io/component: app` and the sink's
  `app.kubernetes.io/managed-by` owner label, `grep -n 'OWNER_LABEL =' backend/core/src/main/kotlin/me/raddatz/veyq/core/tenant/KubernetesGatewaySink.kt`),
  never the Gateway (the store would receive PROXY); Istio `DestinationRule.trafficPolicy.proxyProtocol` on
  `veyq-app`. For the header source, the requirement ("your edge overwrites it on every request, or never set
  `header`") and, as pointers to check in their own docs, NGINX Gateway Fabric's `SnippetsFilter`, GCP's
  `{client_port}` and Cloudflare's `cf.edge.client_port`. Envoy's `%DOWNSTREAM_DIRECT_REMOTE_PORT%` header route is
  named only if phase 4's measure-first step confirms it; Azure's IPv6 form stays R4's caveat.

## Measured at refine (substrate: worktree `agent-a175d172daff841cf` at `6b67a082`, clean; ADR 0093 lines re-measured after merging `8cadad88`)

- **M10 — Vert.x 4.5.27 / Netty 4.1.133 / Quarkus 3.36.0 sources** (from the Gradle cache's `-sources.jar`s):
  - `HAProxyMessageCompletionHandler.decode`: a TCP4/TCP6 header sets **both** `REMOTE_ADDRESS_OVERRIDE` (source) and
    `LOCAL_ADDRESS_OVERRIDE` (destination); `UNKNOWN` sets neither; a DGRAM header closes.
  - `ConnectionBase.java:614-680`: `remoteAddress()`/`localAddress()` return the overrides; `remoteAddress(true)` /
    `localAddress(true)` return the channel's real addresses. `HttpConnection.java:299, 311` declare both.
  - `HttpServerWorker.java:103-121`: with `useProxyProtocol`, the PROXY decoder runs first and a failure is handled
    without configuring HTTP ("HAProxyMessageDecoder already did" close). `HAProxyMessageDecoder.java:168-177, 233-260,
    304-306`: anything without the v2 signature is read as a v1 line, a line not starting `PROXY` throws, and `fail`
    closes the connection at once. So a plain HTTP client gets **no response**, only a close.
  - `NetServerOptions.java:71`: the PROXY wait defaults to 10 s; Quarkus sets only `setUseProxyProtocol`
    (`HttpServerOptionsUtils.java:378`, `:435` for the management interface).
- **M11 — Traefik v3.6 (the recipe's digest) with a local plugin, run hardened** (`--read-only`, `--cap-drop ALL`,
  `no-new-privileges`, `--user 65532:65532`), the plugin as two flat files bind-mounted `:ro` into
  `/plugins-local/src/github.com/raddatzk/veyq/clientaddr/`, the D6 code, a Node echo upstream:
  - `docker image inspect … --format '{{.Config.WorkingDir}}'` → `/`.
  - Static config logged `experimental.localPlugins.clientaddr.moduleName`; "Plugins loaded". A request carrying
    `X-Veyq-Client-Addr: 203.0.113.77:4711` (and a second casing) reached the upstream as `172.20.0.1:63392`, the peer.
  - The same without `go.mod`: loads and overwrites.
  - On a `docker network create --internal` network (no route out): loads; a sender in a container saw its own
    `localAddress:localPort` `172.20.0.4:49444` echoed back as the header, the forged value gone. This is the oracle
    phase 3 uses.
  - The middleware referenced but the plugin not declared: `ERR plugin: unknown plugin type: clientaddr` on that
    router, and the request answers **404**.
- **M12** `release_assets.py` (`sed -n 16,27p scripts/release_assets.py`, `ASSET_NAME` at `:80`): the recipe is every
  file the compose file reads, each a flat name beside it; a directory or a leading-period name is refused.

Remembered, measured in the phase that relies on it: **R5** a kubelet probe from the node passes a NetworkPolicy
that admits the admin port from the admin pod alone (Kubernetes documents node traffic as always allowed; phase 2
quotes the docs page or, if it says otherwise, adds the admission and a chart_meta_check line); **R6** with PROXY off,
a `PROXY TCP4 …` line arrives as a malformed request line and is answered 400 (B2 measures it before pinning it);
**R1/R4** as in ADR 0094.

## Inventory — locators (each run at refine on `6b67a082`; re-run before the first edit)

| Class | Locator | Verdict |
|---|---|---|
| Readers of a socket address in main code | `grep -rn -e remoteAddress -e localAddress backend/core/src/main backend/apps/memories/src/main --include='*.kt'` | `ClientAddress.kt` (`socketPeer`): **changes** (D2). `AdminChannel.kt:26`: **changes** (D4). Its comment at `:21-25`: **rewritten** (it says `localAddress()` is the socket, false under PROXY) |
| Second angle: every PROXY or plugin spelling | `git grep -n -i -e 'proxy-protocol' -e 'PROXY_PROTOCOL' -e 'proxyProtocol' -e 'localplugins' -e 'experimental\.' -- backend charts infra scripts site docs ':!docs/inbox.md'` | Only ADR 0094/0093 prose today. Phase 1–4 add every hit |
| Guards that anchor on what changes | `grep -ln -i -e 'x-forwarded-for' -e 'proxy-protocol' -e 'localplugins' -e 'clientPort' -e 'readinessProbe' -e 'port: http' scripts/*.py` | `validate_lookup_single_home.py` (rule D, extended D7). `chart_meta_check.py` names probes nowhere today (gains the D5 assertion). `validate_compose_posture.py` gains rule 5. `release_assets.py`/`validate_release_identity.py` pick the two new files up by derivation, unedited |
| The rendered middleware | `grep -n 'INERT\|fun render\|router(' backend/core/src/main/kotlin/me/raddatz/veyq/core/tenant/TraefikFileIngressSink.kt` and its tests `ls backend/core/src/test/kotlin/me/raddatz/veyq/core/tenant/ \| grep -i traefik` | **changes** (D6) |
| Chart probes and values | `grep -n 'port: http\|trustedProxies' charts/veyq/templates/app-deployment.yaml`; `grep -n 'trustedProxies' charts/veyq/values.yaml charts/veyq/values.schema.json`; `ls charts/veyq/ci/` | probes conditional (D5); `app.clientPort` beside `trustedProxies`; a ci values file with `proxyProtocol` so chart_meta_check renders that branch |
| Prose that says the port is unknown on Compose / that the socket is the address | `git grep -n -i -e 'port is unknown' -e 'port unknown' -e 'unknown (M6)' -e 'write none' -e 'writes none' -e 'adds no port' -e 'until a second source' -e 'inbox #472' -e '(inbox #472)' -- docs site/src/content/docs charts infra backend/core/src/main ':!docs/inbox.md'` | ADR 0094 `:4`, `:58`, `:79`, `:88`, `:136`; ADR 0093 `:237-242`, `:465-471` (open legal question 4; re-measured after merging `8cadad88`); `run/networking.md:104`, `:113`. Each **edited** in phase 4 |
| Site pages that speak for the docs this CR edits | `git grep -ln -e 0094-a-clients -e 0093-a-report -e uc.admin.admin-channel -e cc.security.rate-limit -- site/src/content/docs` | `run/networking.md`, `run/configuration.md` **edited**; `run/operations.md` (the probe, `:13-21`) and `run/kubernetes.md` **edited**; `run/hardening.md`, `run/first-run.mdx`, `security/known-limitations.md`, `security/threat-model.md` **checked, unchanged unless the sweep finds a port claim** |

## Decided with the owner

Owner 2026-10-17 ("Passt", to "alles wie empfohlen"): O1 (a) an echo stage in the stack run; O2 (a) Traefik's `experimental.localPlugins` in the default
compose recipe, the risk named. The options stay below as the record.

- **O1 — What observes the plugin's overwrite in the stack lane?** Before the moderation CR nothing in the app keeps
  or returns the port, so an end-to-end "the app saw the real port" has no observable yet.
  - (a) **A stage of the stack lane that runs the release's own Traefik and plugin against an echo.** After `assets`,
    it reads the Traefik service from the run's `src/` compose file (image digest, `--experimental.localplugins.*`
    flags, the plugin mounts) and starts it hardened as there, on a run-private `--internal` network, with a router
    through `veyq-client-addr` to an echo container (the Playwright image the overlay already pins, `node`). A sender in
    a container sends a forged `X-Veyq-Client-Addr` and must see exactly its own `localAddress:localPort` echoed (M11
    ran this). The control: a second router without the middleware echoes the forged value, so the oracle can see a
    forgery. The app half is proven by slices (B3), the join by D6's "iff" (B7) and the posture rule; the moderation
    CR's own stack scenario then asserts the recorded port equals the browser's socket port, which ADR 0094's § For the
    moderation CR is extended to require.
  - (b) **An opt-in echo** `veyq.http.client-address.echo` (off by default): `GET /api/setup` answers a response header
    with the address the app resolved for that request; the stack overlay turns it on and a spec in the browser
    container compares it with its socket's local port, end to end in this CR. It doubles as an operator's check of an
    unproven Helm gateway. Cost: a response header is wire shape, so a contracts phase (API-first), a setting, and a
    production code path that only diagnosis uses. It reveals to a client only its own address.
  - (c) Defer the bite to the moderation CR; Compose ships `header` unproven end to end until then.
  - **Recommendation: (a).** It sends a forged header through the very files the release ships, adds no production
    surface and no contracts phase, and the one join it does not run end to end (sink renders iff app reads) is a
    single config value in one process, held by B7. (b) is worth its own inbox row if a cluster run ever shows
    operators cannot tell "port unknown" from "gateway misconfigured".
- **O2 — Is Traefik's `experimental.localPlugins` acceptable in the default Compose recipe?** Traefik files local
  plugins under `experimental.*`; a Traefik upgrade could change or drop it.
  - (a) Yes: Compose ships the plugin and `source: header`. Traefik is digest-pinned, an upgrade is a deliberate change,
    a dropped feature fails loudly (M11: the app's routers answer 404, so the stack lane is red), and a changed
    behaviour reddens O1's stage.
  - (b) No: Compose stays `none` and the port is unknown there; only Helm with PROXY or a header gets one.
  - **Recommendation: (a).** Compose is the default self-hosted target and the one where the address is strong by
    construction (ADR 0094 Consequences); leaving its port unknown leaves most instances unable to name a CGNAT
    subscriber, which is the owner's "Carrier-Grade-NAT sollten wir beachten". The risk is named in ADR 0094 and the Run
    docs, and the stack lane is its alarm.

## Phases

- [x] **1 backend — the source setting, the rule, the admin channel on the real socket.**
  - **Order:** B4 red first (live defect, D4), then the config, the resolver and the refusal, then the rest.
  - **Build:** D1 on `VeyqConfig.Http` (an enum converter; an unknown value fails the mapping); D2 in
    `ClientAddress.resolve` as one more parameter (the port source, already chosen by the accessor) — pure, no config;
    the accessor picks the source by the setting; D3's observer; D4 in `AdminChannel`.
  - **Rewrite:** `ClientAddress.kt`'s KDoc on the accessor ("reads the SOCKET peer … no setting can reach this
    resolver" is no longer true under PROXY: say which address it reads under which source); `AdminChannel.kt`'s
    comment at the call; the `# Per-IP rate limit` comment in `application.properties` names the source setting.
  - **Mutation:** `ClientAddress` stays in `backend/core/pitest.scope.json`; write the new branch with plain loops and
    non-inline calls (cr-client-address-resolver's retrospective: Kotlin inline constructs left 15 equivalent mutants)
    and run `./gradlew :core:pitest` before the bites.
  - **Tests, each marked with how it bites:**
    - **B1 `ClientAddressTest` (unit, new code):** stop without port + source with equal IP → the source's port; unequal
      IP → unknown; stop **with** port + a source → the stop's port; source without a port, two values, a comma, not an
      address → unknown; no `X-Forwarded-For` + a PROXY peer → the peer with its port; a bare IPv6 header value never
      split. Bitten by two levers in a detached worktree: "drop the IP-equality check" (the unequal row red) and "let the
      source override a stop port" (that row red).
    - **B2 PROXY slice (new code), its own profile** (`quarkus.http.proxy.use-proxy-protocol=true`,
      `veyq.http.client-port.source=proxy-protocol`). The observable is a **test-scope** resource that returns
      `clientAddress()` (test-scope resources are outside the rate-limit posture sweep, `trust-chain` skill §
      rate-limit). Raw socket to the public port:
      - `PROXY TCP4 203.0.113.77 10.0.0.1 51234 8080\r\n` + a request carrying `X-Forwarded-For: 203.0.113.77` →
        `(203.0.113.77, 51234)`; the same without `X-Forwarded-For` → the same; `X-Forwarded-For: 198.51.100.66` → that
        IP, port unknown (unequal IP).
      - **The probe bite:** without a PROXY line the public port closes with no status line (M10), and `GET /api/setup`
        on the admin listener answers 200 in the same instance — the probes stay green with PROXY on. Assert the close
        by "no bytes, then EOF", not by "it threw" (§ a negative anchors on the specific observable).
      - **A PROXY header from a peer the app does not expect is refused:** in the default profile (PROXY off) a
        `PROXY TCP4 198.51.100.66 … 4711` line answers what R6 measures first (expected 400) and yields no address.
    - **B3 header slice (new code):** profile `source=header`. `X-Forwarded-For: 203.0.113.77` +
      `X-Veyq-Client-Addr: 203.0.113.77:51234` → port 51234; with `198.51.100.66:51234` → unknown. **The forged-header
      bite:** in the default profile (`none`) the same two headers → port unknown — a client-sent header is never
      honoured. Its lever: "read the header regardless of the source", which must turn this case red.
    - **B4 (live defect), admin channel under PROXY**, in B2's profile with only the Quarkus key at first (the source
      key does not exist on the untouched tree, SRCFG00050): `PROXY TCP4 10.0.0.9 10.0.0.1 51234 <admin port>` (the
      port read from `AdminListener.port()`) + a Server-Owner-marked call on the **public** port. Untouched tree: the
      call is admitted (red, `request.localAddress()` is the PROXY destination). Fixed: the 404 an unknown route gets.
      Positive control as `AdminChannelForgedHeadersSliceTest` has it: the same call on the admin listener answers.
      Then the profile gains the source key with D3.
    - **B5 limiter under PROXY:** two PROXY connections `203.0.113.77:1111` and `:2222` share one bucket (B2's
      profile). Lever: key on `ip:port` → red.
    - **B6 (one-shot, recorded in the retrospective) — D3 stops the boot:** build the fast-jar once
      (`./gradlew quarkusBuild`, no tests), boot it three times differing only in the two keys: source `proxy-protocol`
      with PROXY off, and PROXY on with source `none`, each a non-zero exit whose output names both keys; the control
      (both agreeing) gets past config and fails later on the absent database without naming them. Predicate on the
      named key, never on "the boot failed" (§ a bite test must tell "the guard held" apart from "nothing ran").
    - **B7 sink (unit):** `source=header` renders `veyq-client-addr` with `plugin: clientaddr` and attaches it to every
      `*-app` router, never to `*-blobs` or the admin router; `none` and `proxy-protocol` render neither. Lever: render
      unconditionally → the `none` case red.
  - **D7:** rule D extended and its docstring's three fields updated; its first run before `PORT_HEADER` exists in
    `ClientAddress.kt` is red on the missing home; bitten by the literal added to `RateLimitFilters.kt`;
    `validate_guard_gaps.py --force` green.
  - **Done when:** baseline first — `./gradlew check` in `backend/` on a detached worktree of `HEAD`, its exit code
    recorded. Then `./gradlew check` green with B1–B5 and B7 and the existing admin-channel and rate-limit classes;
    `./gradlew :core:pitest` and `python3 scripts/mutation_meta_check_backend.py` green; B6 recorded;
    `python3 scripts/validate_lookup_single_home.py`, `python3 scripts/validate_ratelimit_core.py` green. Neither
    target sets the new keys yet, so both keep working unchanged.
- [x] **2 infra + chart — the plugin in the recipe, the values on Helm, and the guards.**
  - **Must not land before phase 1** (the app would refuse an unknown `veyq.http.client-port.*`).
  - **Compose (D6):** the two plugin files beside the compose file; Traefik's flag and two `:ro` file mounts; the app's
    `VEYQ_HTTP_CLIENT_PORT_SOURCE: header`, with a comment pointing at ADR 0094 decision 7. No `QUARKUS_HTTP_PROXY_*`.
  - **`validate_compose_posture.py` rule 5:** the app's source is `header` **iff** the proxy declares
    `--experimental.localplugins.<n>.modulename=<m>` and mounts, read-only, both a `.go` file and `.traefik.yml` under
    `/plugins-local/src/<m>/`, and the mounted manifest's `import` is `<m>`; **no** `--experimental.plugins.` (remote)
    flag on any service; **no** service sets `QUARKUS_HTTP_PROXY_USE_PROXY_PROTOCOL` (Traefik sends no PROXY to an HTTP
    service, M6). The remote-plugin refusal is the silent erosion (a start-time network dependency nobody notices until
    an instance starts offline); the rest fail loudly but cheaply here. Bites, each by snapshot-and-restore on a copy:
    drop the manifest mount; add a remote-plugin flag; set the source to `none` with the plugin declared; set
    `QUARKUS_HTTP_PROXY_USE_PROXY_PROTOCOL`. First run on the untouched tree green (the source is absent and no plugin
    declared — the "iff" holds). Its docstring's three fields extended, the limit included (it reads the file; whether
    Traefik loads the plugin is phase 3's stage).
  - **Helm (D5, D8):** `values.yaml` + `values.schema.json` (`app.clientPort.source` enum, `app.clientPort.header`
    string); `app-deployment.yaml` renders `VEYQ_HTTP_CLIENT_PORT_SOURCE` (`proxy-protocol` / `header`; nothing for
    `none`), `VEYQ_HTTP_CLIENT_PORT_HEADER` only when it differs from the default, `QUARKUS_HTTP_PROXY_USE_PROXY_PROTOCOL`
    only for `proxyProtocol`, and the probes' port by D5; NOTES under `proxyProtocol` (D8). A ci values file with
    `proxyProtocol` so `chart_meta_check.py` renders that branch, and its assertion: PROXY env present **iff** probes on
    `admin` **iff** source `proxyProtocol`; the default render keeps all three probes on `http` (the control).
  - **R5 first:** quote the Kubernetes NetworkPolicy docs on node-to-pod traffic in the retrospective. If node traffic
    is not always allowed, the admin port's policy gains the probe's admission and the chart_meta_check sentence
    "admitted by policy from the admin pod alone" changes with it — say so in the hand-off rather than deciding it.
  - **Done when:** `python3 scripts/validate_compose_posture.py`, `python3 scripts/chart_meta_check.py`,
    `python3 scripts/validate_release_identity.py`, `python3 scripts/validate_run_config_reference.py` green (the last
    after phase 4's rows — run it again there), each new posture bite recorded red then green;
    `python3 scripts/release_assets.py`'s derivation lists the two plugin files (`python3 -c 'import sys; sys.path.insert(0,"scripts"); import release_assets as r; print(sorted(r.recipe_set()))'`);
    `helm template` with `--set app.clientPort.source=proxyProtocol` over `charts/veyq/ci/lint-values.yaml` renders the
    PROXY env and three probes on `admin`, and `--set app.clientPort.source=bogus` is refused by the schema;
    `docker compose -f infra/docker-compose.prod.yml config --no-interpolate` shows both mounts.
- [ ] **3 stack — the forged header through the release's Traefik (O1 (a); re-plan if the owner picks otherwise).**
  - A stage in `scripts/run_stack_e2e.py` after `assets`, as O1 (a) describes, reading everything from the run's `src/`
    compose file — never a second copy of the flags. Run-private network and containers under the run's project, torn
    down by the run's teardown and checked by its `host` stage.
  - **Bite and control in the same stage:** the forged value is gone and the echoed value equals the sender's own
    `localAddress:localPort` (exact equality, so a header set to a constant or to the IP alone is red); the route
    without the middleware echoes the forged value (the oracle sees a forgery). Lever to bite the stage once: a plugin
    file whose `ServeHTTP` skips the `Del`/`Set` (in a detached worktree's recipe) → the stage red by name.
  - **Done when:** `python3 scripts/run_stack_e2e.py --app <built> --admin <built>` green on this host with the stage's
    lines in the log (read the log, not the tick), and the lever's red recorded; the existing stages unchanged. Docker
    needed: name in the hand-off if the implementing session has none, and split the no-daemon half (the compose
    reading) into a unit the session can run (§ Split an expensive live proof).
- [ ] **4 docs — ADR 0094 decision 7, ADR 0093's open question, the concerns, the Run pages.**
  - **Measure-first (D9):** at the Envoy version Envoy Gateway v1.9.2 pins, read the formatter for
    `DOWNSTREAM_DIRECT_REMOTE_PORT`; if it is the physical peer's port and EG passes header values unescaped (research
    M), `run/networking.md` may name it as a header source for EG without PROXY; else it is not named.
  - **ADR 0094:** decision 7 (D1–D3, D6's "iff", the configured-never-detected reason), edited in place; decision 3's
    "A port anywhere else is never read" narrowed to "…except the configured source of decision 7, and only for the
    stop entry"; the topology table's Port column (Compose: known via the plugin; a PROXY gateway: known; the rest
    unchanged); § Consequences' #472 bullet replaced by what is built, with the `experimental` risk (O2) and D5's probe
    residual; Rejected alternatives gain "detect the source" and, if O1 (a), the echo; § For the moderation CR gains the
    end-to-end port assertion; M10–M12 into the ledger; the status line.
  - **ADR 0093:** § 10's port sentence and open legal question 4 narrowed: where the port comes from is decided
    (ADR 0094 decision 7); what stays open is legal, not technical. `:237-242` likewise.
  - **cc.security.rate-limit § Verified by:** B1–B5 by method name. **uc.admin.admin-channel:** step 3 says "by its
    real socket, whatever the PROXY protocol states"; a new acceptance criterion
    `a-proxy-header-cannot-name-the-admin-listener` with B4's method in its coverage YAML.
  - **Site pages:** `run/networking.md` § The client address (the port column for Compose, the source setting, the
    configured-never-detected rule, PROXY on Helm with D9's examples and the store warning, NTP); `run/configuration.md`
    (`VEYQ_HTTP_CLIENT_PORT_SOURCE` is fixed on Compose — say so — and the two `app.clientPort.*` rows);
    `run/operations.md` (the probe moves to the admin listener under PROXY, and what it then proves);
    `run/kubernetes.md` (where PROXY is chosen); `run/networking.md` § How far this is proven (the plugin is proven in the
    stack lane; no gateway has sent PROXY to the chart). English-only Run pages, so no DE twin. No Use page: no member
    or guest sees anything different until the moderation CR's disclosure.
  - **Done when:** the prose locator re-run, each remaining hit spared by name; `cd site && npm run check` green;
    `python3 scripts/validate_coverage.py` green; every `scripts/validate_*.py` green, `validate_guard_gaps.py --force`
    once (phase 1 and 2 changed `scripts/`).

## Definition of done

- B1–B7 green with each bite recorded in the retrospective by lever and result; B4's live red recorded before the fix.
- Compose ships the plugin as two flat recipe files, declared and mounted read-only, the app on `header`, no knob, and
  the posture rule holds it; the stack lane's stage shows a forged `X-Veyq-Client-Addr` replaced by the sender's own
  address and port, with its control (per O1).
- The chart renders `app.clientPort.*`, PROXY only for `proxyProtocol`, the probes on the admin listener exactly then,
  no gateway-implementation object; `chart_meta_check.py` holds it.
- Rule D holds the port header's literal; `validate_guard_gaps.py --force` green.
- ADR 0094 decision 7 and its status, ADR 0093 § 10 and question 4, cc.security.rate-limit, uc.admin.admin-channel and
  its coverage, and the Run pages updated; `site/` `npm run check` and `validate_coverage.py` green.
- **No CI run exists in this repository** (no remote). "Green" means the local run over the committed tree:
  `python3 scripts/run_pipeline.py`, or the per-phase commands above; the stack lane by `run_stack_e2e.py` (or
  `run_pipeline.py --job stack`) on a host with Docker.

Implementation has the owner's go (2026-10-16).

## Retrospective

(filled at close)
