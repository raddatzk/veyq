---
id: cr-client-address-resolver
title: One core resolver for a client's address — the rightmost X-Forwarded-For hop no client can write
status: planned
satisfies: ["cc.security.rate-limit"]
adrs: ["0094", "0071", "0051", "0065", "0093", "0092", "0064"]
invariants:
  - "Operator-blind E2EE: the resolver only chooses which of the addresses the server already sees it keys on; it stores nothing and adds no field to any wire shape"
  - "Self-hostable by default: no dependency is added, Compose ships no new knob, and each infra change has its answer on both targets"
  - "Logs name no person (ADR 0092, Proposed): the resolver and its startup refusal write no client address to any log line"
---

## Goal

Build [ADR 0094](../docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md). Today the per-IP
rate limiter keys on `request.remoteAddress()`, which Quarkus's `proxy-address-forwarding` fills with the
**leftmost** `X-Forwarded-For` entry, the one a client writes itself. Behind any proxy that appends instead of
replacing, every request can open a fresh bucket. After this CR, veyq resolves the client's address itself, in
one function in core: the **rightmost** `X-Forwarded-For` entry that `veyq.http.trusted-proxies` does not list. The
socket peer is vouched for by the network, never by a header. Quarkus's forwarding is off on both targets. The rate
limiter is the first consumer. The second, the guest-write record of
[ADR 0093 § 10](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md),
is the moderation CR's (planned, not written) and reads the same resolver. No wire shape changes, so there is no
contracts phase and no web phase.

## Context & constraints

- **The rule is ADR 0094's decisions 1–6 and its topology table. This CR does not restate them.** The ADR's
  § Inventory is the starting list. Its line numbers have drifted, and it misses some sites (§ What the ADR's
  inventory misses, below), so the locators here replace it.
- **The concern it serves:** [cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md), the per-IP
  bound and its "Trusts the client IP" limit. A cross-cutting concern has no `docs/coverage/` file, so its
  § Verified by is where the new tests are named. No use case owns the client address. The guest-write record's
  use case does not exist yet and belongs to the moderation CR.
- **Reachability is ADR 0071's and is already guarded.** On Compose, `validate_compose_posture.py` rule 4 holds
  the networks: the app is on `edge`, `db` and `admin`, and its peers there are Traefik, Garage and the admin
  container (`grep -n 'networks:' infra/docker-compose.prod.yml`). On Helm, `scripts/chart_meta_check.py` holds
  "the public port admitted from ingressFrom exactly". Decision 2 needs no new guard. This CR changes neither
  guard.
- **The admin path is a second appending proxy, and the rule already covers it.** admin-web's nginx proxies `/api/`
  to the admin listener with `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for`
  (`grep -n 'X-Forwarded-For\|X-Real-IP' admin-web/nginx.conf.template`). So the rightmost entry is the peer nginx
  saw, and anything the browser sent sits to its left. `X-Real-IP` is ignored (decision 3). nginx is unchanged.
  ADR 0094's table does not list this row, and the Run docs need none, because the admin surface is reached only
  over localhost or a port-forward.
- **`request.connection().remoteAddress()` is the socket, whatever Quarkus's forwarding does.** `AdminChannel.kt`
  reads it the same way, and its comment records the measurement: a forged header moved `remoteAddress()` and left
  `connection()` untouched (`grep -n 'connection().remoteAddress()' backend/core/src/main/kotlin/me/raddatz/veyq/core/adminchannel/AdminChannel.kt`).
  So a forwarding setting someone turns back on cannot reach the resolver. That is why, as ADR 0094 decision 4
  says, no guard forbids the setting or `remoteAddress()`.
- **The config surface:** `VeyqConfig.Http` already owns `veyq.http.*`, with `hsts()` as its one group
  (`grep -n 'interface Http' -A3 backend/core/src/main/kotlin/me/raddatz/veyq/core/config/VeyqConfig.kt`).
  `trustedProxies()` joins it there. The filters read `VeyqConfig` through the programmatic lookup their file
  documents, because a JAX-RS `@Provider` cannot inject the mapping
  (`grep -n 'getConfigMapping' backend/core/src/main/kotlin/me/raddatz/veyq/core/ratelimit/RateLimitFilters.kt`).
  SmallRye refuses a `veyq.*` key that no mapping claims (`VeyqConfig.kt`'s class doc, SRCFG00050). So the key
  must be declared on the mapping before any profile sets it.
- **Rate-limit state stays in `core/ratelimit/`** (`validate_ratelimit_core.py`). The resolver lives in `core/http/`
  and holds no state, so that guard is not touched.
- **Sibling CRs on the same files** (§ *Two CRs that write the same page*):
  - `cr-veyqctl` phase 6 adds a `VEYQ_RELEASE` row to `site/src/content/docs/run/configuration.md`. This CR edits
    only the `app.trustedProxies` row of that page.
  - `cr-stack-e2e` reads `infra/docker-compose.prod.yml` through its locators and the recipe its lane installs. It
    sends no `X-Forwarded-For` (`git grep -n -i 'forwarded' -- e2e scripts/run_stack_e2e.py scripts/e2e_sweeps.py`
    → empty). Its lane drives logins through Traefik from one browser, so it keys one bucket before this CR and
    after it.
  - The parallel refine of ADR 0093 § 9 may edit ADR 0093. This CR edits one sentence in § 10.
  - The second CR to land rebases onto the first and re-reads the shared file.
  - The moderation CR consumes `ClientAddress` (planned, not written). Its guest-write slice is specified in ADR
    0094 § Inventory "For the moderation CR". It is not built here.

### Inventory — locators (each run at refine on `a7bbca08`; re-run before the first edit)

| Class | Locator | Verdict |
|---|---|---|
| Main-code readers of a client address | `grep -rn 'remoteAddress()' backend --include='*.kt' \| grep -v /build/` | `RateLimitFilters.kt` (the IP filter's key): **changes**. `AdminChannel.kt` (the socket, plus its comment): **kept** |
| Second angle: who reads `X-Forwarded-For` | `git grep -n -i 'x-forwarded-for' -- backend charts infra admin-web` | Only tests and admin-web's nginx. No main-code reader exists today, so `ClientAddress.kt` becomes the first one |
| Quarkus proxy settings, every spelling | `git grep -n -i -e 'quarkus\.http\.proxy\.' -e 'QUARKUS_HTTP_PROXY_' -- . ':!docs/decisions/'` | Compose `PROXY_ADDRESS_FORWARDING` and chart `PROXY_ADDRESS_FORWARDING` + `TRUSTED_PROXIES`: **dropped**. `AuthRateLimitTest` and `AuthRateLimitRefillTest` profiles: drop **both** `proxy-address-forwarding` **and** `allow-x-forwarded` (the ADR names only the first). `AdminChannelForgedHeadersSliceTest`: **kept on** (ADR 0094 § Inventory). The `application.properties` comment: rewritten |
| Traefik's forwarded-header trust | `git grep -n -i -e 'trustedIPs' -e 'forwardedHeaders' -- infra charts backend/core/src/main` | Empty (ADR 0094 M4). Compose stays without it (decision 5) |
| The chart value | `git grep -n 'trustedProxies' -- charts site docs` | `values.yaml`, `values.schema.json` (kept `type: string`), `app-deployment.yaml`, the Run pages, ADR 0071 |
| Prose that says forwarding is on, or that the leftmost/forwarded address is the key | `git grep -n -i -E -e 'leftmost' -e 'forwarded (client )?address' -e 'forwarded header' -e 'trusts the client ip' -e 'stable (proxy )?address' -e 'believes any peer' -e 'forwarding (is )?on[ ,.]' -e 'address.forwarding' -- . ':!docs/decisions/0094*' ':!changes/' ':!docs/inbox.md'` (no `\b`: macOS's ERE reads it as a letter and drops ADR 0065:48) | Phase 3's site list. Spared by name below |
| Second angle on the prose: per-IP/remote-address claims | `git grep -n -i -e 'per-ip' -e 'client ip' -e 'client remote address' -e 'remote address' -- docs site/src/content/docs charts infra backend/core/src/main ':!docs/decisions/0094*' ':!docs/inbox.md'` | `RateLimit.kt` KDoc on `IP` ("Client remote address"): **narrowed**. ADR 0051's "`IP` keys on the remote address": **narrowed**. cc.security.rate-limit's key table: **narrowed**. Every other per-IP hit stays true (the bound is still per address) |
| Guards that anchor on what changes | `grep -ln -i -e trustedProxies -e ADDRESS_FORWARDING -e remoteAddress -e x-forwarded-for scripts/*.py` (empty) | None anchors on the changed symbols. `chart_meta_check.py`'s three "forge the forwarded client address" sentences stay true under decision 6: **spared** |
| Site pages that speak for the edited docs | `git grep -ln -e cc.security.rate-limit -e 0071-runtime-hardening -e 0094-a-clients -- site/src/content/docs` | `run/networking.md`, `run/hardening.md`, `run/kubernetes.md`, `run/configuration.md`, `security/known-limitations.md`: **edited**. `security/threat-model.md` (per-IP limiting, honestly limited) and `run/new-instance.mdx`: **checked, unchanged** |

**Spared by name.** Each of these hits is still true after the CR:
- ADR 0092 M18 (`:259`) and ADR 0094 M1–M5: measurement ledgers describe the tree they ran on.
- ADR 0092 `:31` (Garage's log row) and `:117` (the sharpened slice).
- ADR 0065:259 (`isSSL()` on the management interface).
- `run/operations.md:45` (admin nginx logs the forwarded header, which is unchanged).
- `run/networking.md`'s "A proxy of your own in front" bullets: the admin listener ignores forwarded headers, and
  Traefik replaces them from an untrusted peer.
- `docs/coverage/.../uc.admin.admin-channel.yaml:33` ("with proxy-address forwarding on" describes the test
  profile, which stays on).
- The three sentences in `chart_meta_check.py` (above).

### What the ADR's inventory misses (measured here)

- The two rate-limit test profiles also set `quarkus.http.proxy.allow-x-forwarded`. Dropping only
  `proxy-address-forwarding` leaves a dead key.
- Stale claims the ADR does not list:
  - `AdminChannelForgedHeadersSliceTest`'s class doc: "Both shipped targets run with proxy-address forwarding on".
  - `RateLimit.kt`'s `IP` KDoc.
  - ADR 0051 Decision ("`IP` keys on the remote address").
  - ADR 0065 Context: "proxy-address forwarding is on on both targets".
  - ADR 0093 § 10 and ADR 0092 § 2: the present-tense "Quarkus takes the leftmost `X-Forwarded-For` entry".
  - `security/known-limitations.md` § Rate limiting is per instance: "a peer inside the trusted proxy set can forge"
    and **Closed by:** "an address-based trusted-proxies default".
  - The two comments that justify the dropped env (`docker-compose.prod.yml` above `PROXY_ADDRESS_FORWARDING`,
    `app-deployment.yaml` above it).
- `run/hardening.md`'s heading `## Trust only a stable proxy address` is the anchor
  `/veyq/run/hardening/#trust-only-a-stable-proxy-address` that `run/configuration.md:74` links to. A renamed
  heading moves the anchor, so both are edited together.
- Line drift: the `application.properties` comment is at the `# Per-IP rate limit` block
  (`grep -n 'Per-IP rate limit' backend/core/src/main/resources/application.properties`), not `:131-137`. The chart
  value is at `grep -n 'trustedProxies' charts/veyq/values.yaml`, not `:50-54`.

### Decisions this CR takes that the ADR leaves to the build (stated, not silent)

- **Resolution is one pure function**, `ClientAddress.resolve(peer, xffLines, trusted)` → an `(ip, port?)` or none
  (O3 (c): `port == null` means the port is unknown), in `backend/core/.../core/http/ClientAddress.kt`. Beside it sits a thin request accessor over
  `request.connection().remoteAddress()` and `request.headers().getAll("X-Forwarded-For")`. The pure function is
  what the unit test and the mutation gate (if the owner says so, O2) see. The accessor holds no decision.
- **Parsing never resolves a name.** An entry is a literal or nothing, exactly as `AdminChannel.literal` parses one
  (a dotted quad, or a string containing `:`, handed to `InetAddress.getByName`, which then parses and never looks
  up). Reuse it or move it to `core/http`; never copy it. A copy is two parsers that will one day disagree.
  - Entries are trimmed.
  - An empty entry is not an IP address. So `a,,b`, or a trailing comma, at the stop yields none, by decision 3.
  - IPv4-mapped IPv6 is normalised on the entries, the trust list and the peer alike, so `::ffff:10.0.0.5` matches
    a listed `10.0.0.5`.
- **The trust list is one SmallRye-converted type on the mapping**:
  `veyq.http.trusted-proxies`, `Optional<List<…>>`, empty by default. Each element is an IP or a CIDR, parsed by
  that same literal parser plus a prefix length. A hostname, a bad prefix or an empty element fails the
  conversion. SmallRye validates every mapping at boot, so the refusal is the mapping itself, with no startup
  observer to forget. Remembered, not measured: that a converter failure stops the boot rather than the first
  read. Phase 1 measures it (bite B5).
- **The rate limiter's key when there is no address:** the peer's literal (decision 3). When the peer is not an
  inet socket, it is today's `"unknown"`, unchanged.
- **The chart keeps `app.trustedProxies` a comma-separated string** (`values.schema.json` unchanged). The app's
  startup refusal is the one validation of its entries. A schema pattern would be a second parser of the same
  grammar.

## Decided with the owner, and what is still open

Owner 2026-10-16 ("Bei den drei offenen Punkten gehe ich überall mit"): **O1 (a)** and **O2 (a)** as recommended.
**O3 was reopened** by the owner's "Carrier-Grade-NAT sollten wir beachten" — behind CGNAT an address names no one
without its source port, so (a)'s dropping of a proxy-written `ip:port` loses exactly the part that attributes. The
source-port research settled it: **O3 (c)**, decided by the orchestrator under that direction, and ADR 0094 decision 3
was amended in place. Every other port source (the PROXY protocol, a trusted header, a local Traefik plugin, chart
values) is inbox #472, a follow-up before the moderation CR.

- **O1 — Should a guard hold decision 1 ("one resolver, both consumers")?**
  - (a) Extend `scripts/validate_lookup_single_home.py` with a rule D: a string literal naming `X-Forwarded-For`
    (any case) occurs in main Kotlin only in `core/http/ClientAddress.kt`, and the home must itself carry it, the
    rule shape the guard already has for A–C.
  - (b) No guard, and the moderation CR's review carries it.
  - **Recommendation: (a).** The defect it stops is the one ADR 0093 § 10 exists against. A second hand parse in the
    moderation CR takes the leftmost entry again and records a forged address as evidence. No human notices that,
    because every test that sends a single-entry header stays green. The guard exists, a rule there is a few lines,
    and its first run on the untouched tree is red ("nothing there to be the only one") until `ClientAddress.kt`
    exists. That is the live-defect order.
  - Cost: `validate_guard_gaps.py --force` runs once, because `scripts/` changes. Its alias limit (a) applies to
    the new rule too: `const val H = "X-Forwarded-For"` read as `getAll(H)`.
- **O2 — Should `ClientAddress`'s pure function join the backend mutation scope (`backend/core/pitest.scope.json`)?**
  - (a) Yes: an entry `me.raddatz.veyq.core.http.ClientAddress` without a trailing `*`, as `TokenBucket` has
    none, so the request accessor stays out if it is a separate class. Its test is `ClientAddressTest`.
  - (b) No: the table-driven unit test plus the two named levers (B4) suffice.
  - **Recommendation: (a).** It is exactly the "curated pure-decision tier" the manifest's comment defines. Its
    output becomes evidence that can name a person. A table test with one missing row (the trust-list skip, the
    stop at a non-IP) is the weakness mutation finds and review does not. Cost: `mutation-backend.yml` runs on the
    path, and a survivor is a phase-1 red.
- **O3 — What happens to an entry that carries a port (`203.0.113.77:51234`, `[2001:db8::1]:443`) at the stop?**
  - (a) Strict, as ADR 0094 decision 3 reads: it is not an IP address, so there is no address. The limiter keys on
    the peer and the record stores nothing.
  - (b) Strip one port suffix before parsing.
  - (c) Parse the port and **keep** it: IPv4 `a.b.c.d:p`, IPv6 only bracketed `[v6]:p`, a bare IPv6 never split,
    a port 1–65535. The resolver returns `(ip, port?)` from the start, so its API breaks once, not twice. The limiter
    keys on the IP alone. `Forwarded` stays ignored.
  - **Decided: (c).** The entry is proxy-written, so its port opens no forgery; AWS ALB (on request) and Azure
    Application Gateway (always) are measured to write it (ADR 0094 M7); and it is the part that attributes behind
    CGNAT (M8).

## Phases

- [ ] **1 backend — the resolver, its setting, the limiter on it.**
  - **Order:** write B1 red first, then the fix, then the other tests. B1 is a live defect.
  - **Build:**
    - `ClientAddress` (above).
    - `VeyqConfig.Http.trustedProxies()` with its converter.
    - `IpRateLimitFilter` keys on the resolver.
  - **Rewrite:**
    - `RateLimitFilters.kt`'s class doc for the IP filter (the "trusts the client IP" paragraph).
    - The `RateLimitKey.IP` KDoc in `RateLimit.kt`.
    - The `# Per-IP rate limit` comment in `application.properties`: it names the resolver and the one setting, and
      no Quarkus key.
  - **Test profiles:**
    - `AuthRateLimitTest` and `AuthRateLimitRefillTest`: drop both Quarkus proxy keys and narrow their class docs
      (single-entry headers from loopback, empty trust list, so the entry is the rightmost).
    - `AdminChannelForgedHeadersSliceTest`: keep its profile and narrow its class doc. The profile turns on every
      forwarding source Quarkus has as the worst case, not because a target ships it.
    - Write no client address into a log line.
  - **Tests, each marked with how it bites:**
    - **B1 (live defect), in `AuthRateLimitTest`'s profile** (limit 3, empty trust list; reusing it saves a Quarkus
      boot):
      - Three challenges carrying `X-Forwarded-For: 198.51.100.66, 203.0.113.77`, then one carrying
        `198.51.100.99, 203.0.113.77`, get **429** (`code` = `rate_limited`). A new forged leftmost entry opens no
        bucket.
      - On the untouched tree it is red both ways: with the profile's forwarding the key is the leftmost entry
        (the 4th request is 200), and without it every request keys `127.0.0.1`.
      - Use addresses no other method of the class uses.
    - **B2 (control on B1's predicate):** `198.51.100.66, 203.0.113.78` after B1's three gets **200**, so the key is
      not one global bucket.
    - **B3 (live defect), its own profile** with `veyq.http.trusted-proxies=10.0.0.5` and limit 3:
      - Three requests carrying `198.51.100.66, 203.0.113.77, 10.0.0.5`, then `198.51.100.99, 203.0.113.77, 10.0.0.5`,
        get **429**.
      - Control: `198.51.100.66, 203.0.113.78, 10.0.0.5` gets **200**. Without the skip, every request keys
        `10.0.0.5` and the control is red.
      - The profile is also the wiring proof that the mapping parses a listed entry.
    - **B4 `ClientAddressTest` (unit, new code):**
      - Every row of ADR 0094's topology table, with the "Arrives at the app" column as the input.
      - No header.
      - Two header lines (order kept).
      - A non-IP entry at the stop (`unknown`, a hostname, an empty entry, `1.2.3.4:0`, `1.2.3.4:`) → none.
      - The port forms (O3 (c)): `203.0.113.77:51234` → (…, 51234); `[2001:db8::1]:443` → (…, 443);
        `[2001:db8::1]` → (…, unknown); a bare `2001:db8::1` is never split; `198.51.100.66:4711, 203.0.113.77` →
        (203.0.113.77, unknown), the port left of the stop never read; a listed hop with a port is skipped.
      - The limiter's key unchanged by a port (a slice: `203.0.113.77:1111` and `:2222` at the stop share one bucket).
      - A non-IP entry left of the stop → ignored.
      - Every entry listed → the leftmost.
      - IPv4-mapped normalisation on both sides.
      - A CIDR match and a CIDR miss.
      - The converter refusing a hostname, `10.0.0.0/33` and an empty element.
      - Bitten by the two levers ADR 0094 names, each applied in a detached worktree:
        - "resolve to the leftmost entry": B1 and the appending-gateway row are red;
        - "resolve to the peer": B2 and every forwarded row are red.
      - Plus O2's mutation run if (a).
    - **B5 (one-shot, measured into the retrospective) — a malformed entry stops the boot:**
      - Build the fast-jar once (`./gradlew quarkusBuild`, no tests). Boot it twice with only
        `VEYQ_HTTP_TRUSTED_PROXIES` differing.
      - `gateway.local`: a non-zero exit whose output names `veyq.http.trusted-proxies`, before any datasource line.
      - The control, `10.0.0.5`: past config, failing later on the absent database without naming the key.
      - "The boot failed" alone proves nothing (§ *a bite test must tell "the guard held" apart from "nothing
        ran"*).
    - **The existing per-IP tests keep their bite after the Quarkus keys go:** with the resolver forced to the peer,
      `oneIpTrippingTheLimitDoesNotThrottleAnother` is red. Same worktree.
  - **If O1 (a):**
    - Rule D in `validate_lookup_single_home.py`, its docstring's three fields extended.
    - Its first run before `ClientAddress.kt` exists is red.
    - Bitten by an `X-Forwarded-For` literal added to `RateLimitFilters.kt`.
    - `validate_guard_gaps.py --force` green.
  - **If O2 (a):** the manifest entry, and `mutation-backend.yml`'s gate green on it.
  - **Done when:**
    - Baseline first: `./gradlew check` in `backend/` on a detached worktree of `HEAD` before the first edit, its
      exit code recorded. At refine only the four touched classes were run: `./gradlew :core:test --tests
      '*AuthRateLimit*' --tests '*AdminChannelForgedHeadersSliceTest' --tests '*RateLimitPosture*'` → exit 0.
    - `./gradlew check` green, with B1–B4 and the existing rate-limit and admin-channel classes in it.
    - B5 recorded.
    - `python3 scripts/validate_lookup_single_home.py` and `python3 scripts/validate_ratelimit_core.py` green.
    - Compose and the chart still turn Quarkus forwarding on at the end of this phase. That is harmless:
      the resolver reads `connection()`, and `remoteAddress()` has no reader left. The commit leaves both targets
      working.
- [ ] **2 infra + chart — forwarding off on both targets, the setting on Helm.**
  - **Compose:**
    - Delete `QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING` and the comment above it from
      `infra/docker-compose.prod.yml`.
    - Add no variable (decision 5).
  - **Helm:**
    - In `app-deployment.yaml`, delete `QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING` and its comment, and render
      `VEYQ_HTTP_TRUSTED_PROXIES` from `app.trustedProxies` only when set (the `with` block stays, renamed).
    - Rewrite `values.yaml`'s comment on `app.trustedProxies`. It lists the hops **in front of** the gateway (a load
      balancer), IPs and CIDRs. It never lists the gateway's own pod range. Empty means the gateway is the only
      hop.
    - `NOTES.txt` gains, under `{{- if not .Values.networkPolicy.enabled }}`, the decision-6 line: without an
      enforced NetworkPolicy any pod can reach the app and write the whole forwarded header, so the per-IP limit,
      and later the guest-write record, can carry a forged address.
  - **Must not land before phase 1.** Without the resolver, forwarding off keys every client on the gateway.
  - **Done when** (at refine `python3 scripts/chart_meta_check.py` → `chart meta check OK`):
    - `python3 scripts/chart_meta_check.py` green.
    - `helm template` with `--set app.trustedProxies=10.0.0.5` over `charts/veyq/ci/lint-values.yaml` renders
      `VEYQ_HTTP_TRUSTED_PROXIES` and no `QUARKUS_HTTP_PROXY_`. Without the value, neither renders.
    - `helm install veyq charts/veyq --dry-run=client -n veyq -f charts/veyq/ci/lint-values.yaml --set
      networkPolicy.enabled=false` prints the NOTES line. With `enabled` true it does not (the control). Measured at
      refine: a client dry-run prints NOTES on helm v4.0.1.
    - `docker compose -f infra/docker-compose.prod.yml config --no-interpolate | grep -c QUARKUS_HTTP_PROXY` → 0.
    - `validate_compose_posture.py` and `validate_run_config_reference.py` green.
- [ ] **3 docs — state and the Run and Security pages, in the same CR.**
  - **Superseded prose:** each site in the inventory's prose rows, re-swept here (the CR wrote new prose in phase
    1). The edit shape per site:
    - `cc.security.rate-limit.md`:
      - Key table `IP` row: narrowed to "the client address `ClientAddress` resolves (ADR 0094)".
      - "Trusts the client IP" bullet: rewritten to decision 3 and decision 6's residual.
      - The deferred "address-based `trusted-proxies` default": deleted as resolved, with the reason that the
        list names hops in front of the gateway and none by default.
      - § Verified by: the "test profile enables `proxy-address-forwarding`" clause rewritten, and B1–B4 added by
        method name.
    - ADR 0071:
      - Decision 4 and the Context sentence that keys on the forwarded address: edited in place. `trustedProxies`
        now names the hops in front of the gateway, and its property is veyq's. The selector-based policy still
        confines the peer.
    - ADR 0051 Decision (`IP` keys on the remote address), ADR 0065 Context ("forwarding is on on both targets"),
      ADR 0093 § 10 and ADR 0092 § 2 ("Quarkus takes the leftmost …"): each narrowed to a clause that keeps its
      reason and drops the present tense. The ADR 0092 edit is one sentence of a Proposed draft, so tell the
      moderation refine.
    - ADR 0094: its status line becomes what is built.
  - **Site pages** (§ *The published docs move with the change*):
    - `run/networking.md` § The client address and the rate limits:
      - The rule.
      - ADR 0094's topology table, with topologies named by **behaviour** (replacing, appending) and only Traefik
        by name (measured, M3). R1–R3 are remembered, so the page names no other product's behaviour as fact.
      - The shared-bucket consequence behind an appending chain without `trusted-proxies`.
      - Keeping the client's source address in front of the gateway (R3: `externalTrafficPolicy: Local` or the
        PROXY protocol, worded as the setting to check).
      - The port column (ADR 0094's table), "port unknown" as the normal case, the AppGW IPv6 caveat (R4), and NTP.
      - Its "A proxy of your own in front" bullet stays true (Traefik replaces).
    - `run/hardening.md`: the section and its anchor, renamed together with the `run/configuration.md:74` link.
    - `run/kubernetes.md`: the `app.trustedProxies` sentence, plus decision 6 where `networkPolicy.enabled: false`
      is described.
    - `run/configuration.md`: the `app.trustedProxies` row only (cr-veyqctl owns its new row).
    - `security/known-limitations.md` § Rate limiting is per instance: the forgeable-key sentence becomes decision
      6's residual, and **Closed by:** is rewritten (the trust default is decided, so what still closes the
      limitation is a distributed store).
    - Every page is English-only Run/Security, so there is no DE twin.
  - **Done when:**
    - `for f in scripts/validate_*.py; do python3 "$f"; done` green, `validate_guard_gaps.py` skipping unless O1 (a)
      changed `scripts/`.
    - `npm run check` in `site/` green.
    - The prose sweep re-run, with every remaining hit spared by name.

## Definition of done

- B1–B5 green, with each bite recorded in the retrospective by lever and result. The existing rate-limit and
  admin-channel tests are green without Quarkus forwarding in their profiles, except the admin-channel class, which
  keeps it on purpose.
- Neither target sets a `QUARKUS_HTTP_PROXY_*` value. The chart renders `VEYQ_HTTP_TRUSTED_PROXIES` from
  `app.trustedProxies` only when set. Compose has no knob.
- O1's guard, if chosen, is in the glob and bitten. A one-shot proof is not enough there, because the second
  consumer is a CR not yet written.
- No `docs/coverage/` file changes. `cc.security.rate-limit` § Verified by names the new tests, and
  `python3 scripts/validate_coverage.py` stays green.
- The Run and Security pages above are updated and `site/` `npm run check` is green. No Use page is affected,
  because no member or guest sees or does anything different.
- The ADR 0094 status line states what is built.
- **No CI run exists in this repository** (no remote). "Green" means the local run over the committed tree:
  `python3 scripts/run_pipeline.py`, or the per-phase commands above.

Implementation has the owner's go (2026-10-16).

## Retrospective

(filled at implementation)
