---
id: cr-admin-api-only-through-the-admin-container
title: The Server-Owner API answers only through the admin container
status: in-progress
satisfies: [uc.admin.admin-channel]
adrs: ["0065", "0008", "0064"]
invariants: [self-hostable by default, API-first, operator-blind E2EE]
---

## Goal

Every Server-Owner operation is reachable from the internet today. The `admin` container proxies `/api/` to the
same backend the public origin routes to, so tenant administration sits behind the owner's login alone, and
`POST /api/setup/owner` sits behind nothing — the first caller after `up -d` becomes Server Owner. After this CR
the backend runs a second, internal **admin listener**. Only the admin container is pointed at it, and the
Server-Owner operations — the ten role-gated ones plus `createServerOwner` — answer only for requests that
arrived there, classified by the socket and never by a header. The public origin 404s them. Which operations
those are is declared per operation in `contracts/` (`x-veyq-channel: admin`), derived by the backend at build
time, and held to the backend's Server-Owner gate by a sweep. Web's own call into that surface, the `serverRole`
probe, is deleted.

## Context & constraints

- **The decision and its reasons live in [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)**
  § Decision (the admin-channel bullet: mechanism, operation set, 401-vs-404, deployment, residuals, expiry
  condition) and § Consequences (the five rejected alternatives). Behaviour, failure modes and acceptance
  criteria: [uc.admin.admin-channel](../docs/features/admin/use-cases/uc.admin.admin-channel.md). Neither is
  restated here.
- **Measured at refine, 2026-09-23**, by a throwaway `@QuarkusTest` on this branch (removed, never committed):
  - a Vert.x server started with `VertxHttpRecorder.getRootHandler()` serves the full pipeline — JAX-RS 200,
    auth 401 without a session and 200 with an owner session;
  - with proxy-address forwarding on, a forged `Forwarded` / `X-Forwarded-Host` rewrote `host()` and
    `remoteAddress()` (the latter read `10.0.0.1` from `for=`) and left `localAddress()` untouched;
  - `@RolesAllowed` refused an anonymous request before a post-match `@ServerRequestFilter` ran, at default
    priority **and** at priority 100;
  - `SimpleResourceInfo.methodName` equals the operationId (`getSetupStatus`, `getInstanceRelease`,
    `listTenants`).
  **Not measured:** dev-mode hot reload of a hand-started listener (phase 2 owes a live check).
- **Trap — read the peer from the connection, never from the request.** `request.remoteAddress()` is the
  forwarded-parsed client address, so anyone who writes `Forwarded: for=10.0.0.1` looks private through it. The
  peer rule reads `request.connection().remoteAddress()`, and the port rule reads `localAddress()`; both delegate
  to the socket in Quarkus' `ForwardedServerRequestWrapper`. This sentence belongs as a comment at the
  predicate's line.
- **API-first and ADR 0008:** the marker is reachability, not a role; the role string stays out of the spec.
  No generated code changes from it — `typescript-fetch` and `jaxrs-spec` ignore operation-level extensions.
- **Self-hostable:** no new secret and no variable an operator has to set. Everything an operator sees change is
  a default shipped inside the compose file and the chart.
- **Neighbours.** #168 (sign-out) is being refined over `web/src/features/account/login/signOut.ts`, which calls
  `resetServerRole` — this CR deletes that call, and whichever lands second rebases. #158 adds an app-shell piece
  to the backend's HTTP layer; it will answer on the admin listener too, which is harmless because the admin
  nginx proxies only `/api/`. #155 (quotas) will add Server-Owner operations, and the sweep makes their marker
  mandatory.
- **Out of scope:** the channel secret (rejected; the fallback if `getRootHandler()` ever disappears);
  enforcing uc A5 (own-domain before setup); concurrent first-setup calls (inferred at refine: a primary-key
  violation, so a 500 instead of the 409 — reachable only from the admin container after this CR).

## Phases

**Order: listener → clients → gate, on purpose.** The listener first serves *everything* (phase 2), so every
client can move onto it (phase 3) while both doors still answer; only then does the public door start refusing
(phase 4). Any other order leaves a window in which the e2e job, the seed scripts or the admin container point
at a door that is closed or does not exist yet.

- [x] 1 contracts — mark with `x-veyq-channel: admin` every operation whose backend gate is the Server-Owner role,
      plus `createServerOwner`. Locator (a method with no annotation of its own under the class-level
      `@RolesAllowed` carries the gate):
      `for f in $(find backend/src/main -name TenantsResource.kt -o -name InstanceResource.kt -o -name SetupResource.kt); do echo "== $f"; grep -nE '^\s*@(RolesAllowed|PermitAll|Authenticated)|override fun [a-zA-Z]+\(' "$f"; done`.
      At refine the second angle — the operations `admin-web/src` calls — agreed exactly, plus `getSetupStatus`.
      **Not marked:** `getSetupStatus` (web boot, both targets' probes) and the sign-in operations. Each spec that
      uses the marker carries one YAML comment pointing at ADR 0065 — its only documentation home besides the ADR.
      · Done when: `npm run generate:api` + `npm run check` green in `web/` and `admin-web/`, `./gradlew check`
      green (the extension generates nothing), trust-chain guards green.
- [x] 2 backend — the admin listener, refusing nothing yet.
      - `veyq.admin-channel.port`: default `8090`, `%test` `0`. Not 9000 (Quarkus' management default), not 8081
        (compose's `ADMIN_PORT` host default).
      - A second Vert.x HTTP server whose request handler is `getRootHandler()`. Suggested hook: observe
        `io.quarkus.vertx.http.HttpServerStart` — public API, it carries the main server's `HttpServerOptions` —
        and copy those options, changing only the port, so host and limits match. **Believed, not measured:** the
        spike used `StartupEvent` and default options.
      - Close it on shutdown. If the bind fails, fail startup (uc E2). Expose the bound port for tests.
      - The channel predicate: the request arrived on the bound port **and** its socket peer is not globally
        routable. Non-global means loopback, RFC 1918, 100.64/10, link-local and `fc00::/7`; the last is explicit
        because Java's `isSiteLocalAddress` knows only the deprecated `fec0::/10` for IPv6. Unit-tested per range.
      - Tests: the listener serves an unmarked operation and a role-gated one with an owner session; the bind
        failure fails startup.
      - **Live check — a machine hint for this CR's form:** run `quarkusDev` in the implement worktree (never the
        user's main checkout), edit a resource, and confirm the listener still answers after the reload.
      · Done when: `./gradlew check` green and the live check seen.
- [x] 3 clients + deployment — every Server-Owner caller moves onto the listener; web drops its own call.
      - **admin-web:** the entrypoint's `BACKEND_URL` default, the CLI's `DEFAULT_BACKEND_URL` and its help text
        (with `adminSession.test.ts`'s pin), `vite.config.ts`'s `VEYQ_API_TARGET` default, and
        `.run/Run Admin.run.xml`. Locator:
        `grep -rnE 'BACKEND_URL|app:8080|veyq-app:8080|VEYQ_API_TARGET' admin-web/src admin-web/docker-entrypoint.d admin-web/vite.config.ts admin-web/nginx.conf.template infra/docker-compose.prod.yml charts/veyq/templates .run`.
      - **compose:** the admin service's `BACKEND_URL` points at the listener. The root `Dockerfile` does **not**
        `EXPOSE` it, with a comment saying why (`docker run -P` would publish it). Traefik's
        `loadbalancer.server.port=8080` already pins the public router, and tenant routes point at
        `veyq-app@docker`, so they inherit it. If `cr-traefik-file-provider-only` lands first, that pin is the
        ingress sink's `veyq-app` service URL instead; check it names the public port.
      - **chart:** an `admin` containerPort on the app Deployment, and a Service of its own for it — not a second
        port on `veyq-app`, so no Ingress aimed at the app Service can reach it. The admin Deployment's
        `BACKEND_URL` points at that Service. A NetworkPolicy selecting the app pods admits the public port from
        anywhere and the admin port only from the admin pod. `scripts/chart_meta_check.py` gains two assertions:
        no rendered Ingress names the admin Service, and the policy admits only the admin pod to the admin port.
        It is hand-run — no workflow executes it, which inbox #26 already records — so run it. If
        `cr-runtime-hardening-both-targets` lands first, extend its policy for the app pods rather than adding
        a second one; that CR's § Context says how the two merge.
      - **live e2e + seeds:** `scripts/run_web_e2e.py` boots each jar with an admin port of its own and hands the
        suites an admin base. `web/src/test/e2eBootstrap.ts` births the owner and the tenant through it. The dev
        seed scripts get an admin base, defaulting to the listener's dev port. Locator, two angles, both derived
        from phase 1's marker:
        `OPS=$(python3 -c "import yaml,glob; print('|'.join(op['operationId'] for f in glob.glob('contracts/api/*.openapi.yaml') for p in (yaml.safe_load(open(f)).get('paths') or {}).values() for op in p.values() if isinstance(op, dict) and op.get('x-veyq-channel') == 'admin'))")`,
        then `grep -rlnE "\.($OPS)\(" web/src web/scripts scripts '--include=*.ts' '--include=*.mts' '--include=*.vue' | grep -v api/generated`
        (generated-client calls) and
        `grep -rlnE "api/setup/owner|/api/tenants['\"\`/]|/api/tenants\$|/api/instance" web/src web/scripts scripts admin-web/src '--include=*.ts' '--include=*.mts' '--include=*.vue' '--include=*.py' | grep -v api/generated`
        (raw URLs). At refine the second angle also listed `scripts/instance_backup.py`. That is a false positive:
        the script names `GET /api/tenants/{id}/mail-settings`, a tenant-owner operation, in a message.
      - **web:** delete `web/src/app/serverRole.ts`, the `SettingsHubView` note and its tests, and every
        `resetServerRole` call. Locator:
        `grep -rn 'serverRole\|resetServerRole' web/src '--include=*.ts' '--include=*.vue' | grep -v api/generated`.
        The comment in `tenantTerms.ts` that cites `serverRole.canManage` is rewritten, not left pointing at a
        deleted symbol.
      · Done when: `npm run check` green in `web/` and `admin-web/`, `./gradlew check` green,
      `python3 scripts/run_web_e2e.py` green (every suite births through the listener), and `helm template` +
      `python3 scripts/chart_meta_check.py` green.
- [x] 4 backend — the gate.
      - **The set, derived:** `backend/gradle/openapi.gradle.kts` reads every spec's operations — swagger-parser is
        already on its classpath through openapi-generator — and emits the operationIds marked
        `x-veyq-channel: admin` as generated Kotlin. Any other value of the extension fails the build. Nothing
        lists the set by hand.
      - **The filter:** a post-match `@ServerRequestFilter` keyed on the matched resource method's name (which
        equals the operationId — measured). Off the admin channel it answers a marked operation with the same 404
        an unmatched `/api` path gets. A pre-match path matcher is deliberately not built (ADR 0065: uc A1 is
        accepted).
      - **Migrate the tests first:** every backend test that calls a marked operation calls it on the listener,
        through one shared test helper rather than a port per file. Locator, keyed on method + path of the
        marked operations:
        `grep -rnoE '(post|get|delete)\("/api/tenants"\)|(post|get|delete)\("/api/tenants/\$[a-zA-Z{}.]+"\)|/invite/resend|/invite/reassign|/change-domain|/suspend"|/reactivate"|/api/instance/[a-z-]+|/api/setup/owner' backend/src/test`.
        The two broader angles were run at refine and **both over-include**, which is why this one is keyed on
        method + path. The bare `/api/tenants` prefix catches tests of the *public* tenant operations
        (`member-invites`, `accept`) and a comment that names the spec file. `SERVER_OWNER` catches tests that seed
        an owner only as a user (login, recovery). The last word is the build: once the filter lands, any
        public-port call to a marked operation goes red. Green on the phase-3 tree, because both doors still
        answer.
      - **Then the acceptance tests, red first** — the defect is live, so guard → red → fix → green. On the
        phase-3 tree, `setup-answers-only-on-the-admin-channel`'s negative fails because the public door answers
        201. Record that red.
      - **The sweep** `AdminChannelPostureSweepTest`, next to `RateLimitPostureSweepTest` — its model, which
        already reflects over `@Path` resources and the generated interfaces. The effective gate is the method's
        annotation, else the class's. A Server-Owner gate implies membership of the set, and every member of the
        set must name a resource method that exists.
      - **Bites, each with its lever:**
        - *sweep* — injection via the spec, by snapshot-and-restore: drop one marker from `tenants.openapi.yaml` →
          red naming that operationId; put a marker on an operationId no resource implements → red.
        - *socket, not headers* — injection via the predicate: read `request.remoteAddress()` and `host()` instead
          of the socket → the forged-header test goes red. Its test profile turns proxy-address forwarding on;
          without it the forwarding layer rewrites nothing and the bite passes vacuously.
        - *peer rule* — make "globally routable" always false → the global-peer unit case goes red.
        - *refusal shape* — give the refusal a body of its own → the unknown-route comparison goes red.
      · Done when: `./gradlew check` green, and every red above recorded in this file before it goes.
- [ ] 5 docs — the prose that describes the old reachability.
      - `docs/crosscutting/cc.security.authorization.md`: layer 1 gains the admin channel in front of the
        Server-Owner role (reachability, one sentence and a pointer); § Known limits' *authorization is not in the
        contract* gains the marker's distinction; § Verified by gains the sweep.
      - `infra/README.md` § Setting up a brand-new instance: the admin listener exists and nothing but the admin
        container may be pointed at it (a self-hoster's own proxy routes the public port only); `ADMIN_DOMAIN` is
        set after setup; the paragraph saying the settings page tells users that administration lives elsewhere is
        rewritten, because that note is gone. § Running on Kubernetes: the admin Service and the NetworkPolicy,
        and that a CNI which does not enforce policies leaves the port reachable from any pod. The dev-seeding
        section's base URLs.
      - The uc's index entry moves to **built**.
      - Locator for stale prose, two keys, because this CR retires two things — the old backend address and web's
        probe: `grep -rnE 'administration lives elsewhere|app:8080|veyq-app:8080' docs infra charts admin-web wiki | grep -v node_modules`
        and `grep -rn 'serverRole' docs web`. `admin-web`'s own `serverRole` is not stale: it stays, and makes its
        probe through the admin channel.
      · Done when: trust-chain guards green.
- [ ] 6 coverage — `uc.admin.admin-channel.yaml` drops `status: planned`, every criterion gets its refs, and the
      backend gap goes. The web gap stays, with its reason.
      · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

- Every phase is done. Phase 4's red-first and its four bites are recorded red → green.
- **Live, once, on the built images**, because nothing in CI runs a production stack — measured at refine:
  `container.yml` only builds, publishes and signs. Bring up `infra/docker-compose.prod.yml` on a scratch `.env`,
  then:
  - `POST /api/setup/owner` through Traefik on `VEYQ_DOMAIN` → 404;
  - the same request through `http://localhost:${ADMIN_PORT}` → 201. `SetupServerSliceTest`'s request body is
    enough, since the backend checks shape, not keys;
  - `veyq-admin tenant list` inside the admin container → answers.

  Machine hint, which holds for this CR's form: the Docker keychain credential helper can stall `build` / `pull`
  silently.
- `helm template` + `python3 scripts/chart_meta_check.py` green with the two new assertions.
- `docs/coverage/` updated, with `python3 scripts/validate_coverage.py` green; the trust-chain guards green.
- The one-shot proof is already permanent: the sweep holds the gate to the marker on every build. No web guard is
  owed — a web call to a marked operation 404s against the dev backend too, so it cannot land unnoticed.
- Harvest before deleting this file: inbox #152 → DONE with its commits; any trap the implementation met goes to
  a comment at its line.

**Not proven by any of the above, and named so nobody reads it as covered:**
- NetworkPolicy enforcement — no cluster runs here; the render assertion shows the policy exists, not that a CNI
  honours it.
- The NAT residual (ADR 0065 (iii)): a NAT that rewrites a public peer to a private one defeats the peer rule by
  design.
- Dev hot reload beyond phase 2's one live check.
- A Quarkus upgrade that removes `getRootHandler()` — loud at compile time, but only on the day it happens.

## Record (measured, implement session 2026-09-24)

- **Phase 2 live check** (quarkusDev in the implement worktree): both ports answered before a reload; after a
  reload forced by a real code change both served the new code. The request that TRIGGERS a reload through the
  admin port loses its connection (the listener closes with the old app); through 8080 it is held — dev-only,
  written into `AdminListener`'s KDoc. With 8090 held by another socket the boot failed naming
  `localhost:8090 (veyq.admin-channel.port)`, and 8080 answered nothing (uc E2, live).
- **Chart (xii)/(xiii), red first:** on the untouched chart `chart_meta_check.py` failed "the app container
  declares no containerPort 8090"; after the fix nine injections (second port on `veyq-app`, an Ingress naming the
  admin Service, BACKEND_URL at the public port, admin rule open / admitting the app pods / a portless allow-all,
  public rule dropped, no policy, port undeclared) each reddened with its own message; tree unchanged after.
- **Phase 4 red first:** on the phase-3 tree `SetupServerSliceTest.setupIsAnUnknownRouteOnThePublicPort` failed
  "Expected status code <404> but was <201>" (and `createsOwner` then 409, the owner already taken); the 64
  migrated tests were green.
- **The refusal shape, measured:** the first filter answered an empty 404; an unknown `/api` path answers Vert.x's
  `text/html; charset=utf-8` "Resource not found" page, so `theRefusalIsIdenticalToAnUnknownRoute` and the
  per-operation loop went red on it. Fixed by answering the same shape; the test compares against the live page.
- **Phase 4 bites** (targeted Gradle run each, snapshot-and-restore, red read by test name from the JUnit XML, tree
  unchanged after): marker dropped from `suspendTenant` → `everyServerOwnerGatedOperationCarriesTheMarker` named
  `TenantsResource.suspendTenant`; a marked operation on a new, unimplemented tag → `everyMarkerNamesAResource…`
  named `gateOnNothing`; predicate reading `host()`/`remoteAddress()` → forged headers got `200` instead of `404`;
  "never globally routable" → 13 global peers accepted; a refusal body of its own → `theRefusalIsIdentical…` red.

## Retrospective

To be filled at implement close.
