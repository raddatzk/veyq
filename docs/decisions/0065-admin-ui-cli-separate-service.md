# ADR 0065 — Admin UI and CLI are a separate deployable, not backend surface

- Status: Accepted
- Date: 2026-09-07

## Context

Server-Owner administration (instance setup, tenant creation, member management) is built today as routes
inside the main SPA — `web/src/features/admin/**`, gated per-route with server-side authz on each call
(`web/src/router.ts` — "is the Server-Owner instance-administration page (cr-0050; tenant administration
lives there, cr-0009)... each surface enforces its own authz server-side (403)"). It is reachable at the same
origin, over the same public Traefik entrypoint, as every ordinary user-facing page.

Forces:

- **The admin surface is the highest-privilege code an instance serves**, yet it is exposed to every visitor
  who can reach the instance's public domain at all — reachability, not authorization, is the layer an
  operator would want to additionally restrict (firewall, SSH tunnel, a separate domain never linked from the
  public site) without touching the ordinary user-facing app.
- **A CLI is wanted alongside the UI**, for scripted/ops use, but a second implementation of tenant
  create/member-management logic is a second place to keep correct against the backend contract.
- **[ADR 0026](0026-client-trust-tiers.md)'s trust-tier framework applies to any client the operator
  serves**, and an admin surface split into its own origin is a new client artifact under that framework —
  worth stating explicitly rather than leaving implicit.
- **[ADR 0050](0050-operator-config-secret-encryption.md)'s config-secret KEK is a file on the app host**; a
  second component must not gain its own access path to it or to the DB, or the instance now has two places
  holding operator secrets instead of one.
- **`web/src/features/admin/**` is not actually admin-only** (measured at refine time, `git log` +
  cross-import grep): three core-app flows reachable by ordinary or anonymous users import from it —
  `TenantJoinView.vue` (accepting a tenant invite), `TermsAcceptView.vue` (any member re-accepting updated
  terms), and `activeSpace.ts` (reads `getTenantTerms`) — plus four files that are whole-instance
  backup/restore E2E rehearsals (ADR 0056) parked there only incidentally. A literal directory move would
  break all four. The one that reshapes this ADR's own reasoning: `setupServer.ts` (the first-run
  Server-Owner registration, `SetupView.vue`) performs a full cold account birth — identity keypair, Master
  Key wraps, Emergency Kit — the same `shared/crypto/v1` machinery as any member registration, which
  contradicts this ADR's original "admin touches no client-side crypto" premise. Resolved in Decision below by
  moving setup **with** the admin surface rather than leaving it behind, on the argument that first-run setup
  is if anything the *most* privileged single action an instance ever takes (it mints the account with total
  control) and is a natural fit for a restricted-reachability surface — and sharing the one crypto module
  genuinely needed, rather than either duplicating it or excluding setup from the move.
- **Moving the UI did not move the API** (measured 2026-09-23, the security/reuse review): the `admin`
  container's nginx proxies `/api/` to the **same** backend the public origin routes to with no path
  restriction (the `veyq-app` Traefik router; `path: /` in the chart's app Ingress). So the first force was
  half-kept — every Server-Owner operation stayed reachable from the internet behind the owner's login, and
  `POST /api/setup/owner` behind nothing: it is `@PermitAll`, guarded only by "setup not yet complete", so
  whoever calls it first after `up -d` becomes Server Owner, on a domain that is in public CT logs minutes after
  ACME issuance. Two facts constrain the fix. **Nothing a request carries identifies its door:** proxy-address
  forwarding is on on both targets, so `Host`, the client address and the scheme are values the client writes
  (`X-Forwarded-*`, `Forwarded`). **Quarkus has one HTTP listener for application resources:** one port, one
  TLS port, one domain socket, all feeding the same router; its management interface serves Vert.x routes,
  never JAX-RS.

## Decision

**The admin surface becomes its own container (`admin`), and stays a pure client of the backend Admin API — no
direct DB or KEK access. The backend answers that API only through it (the *admin channel*, below).**

- **Web UI — a new package, sharing only crypto with `web/` via a minimal npm workspace.** `admin-web/` gets
  its own `package.json` + Vite build + `index.html`, its own narrow generated API client (from
  `contracts/api/tenants.openapi.yaml` + `contracts/api/instance.openapi.yaml` + `contracts/api/auth.openapi.yaml`,
  mirroring `scopes.openapi.yaml`'s
  `ScopeKind` if the moved code still needs it), and its own router/state/UI — none of that is shared with
  `web/`. The **one** thing that is: `shared/crypto/v1` + `accountBirth` are extracted into a small internal
  workspace package both `web/` and `admin-web/` depend on — the only genuinely shared surface, and the one
  place duplication would be a security defect rather than a maintenance nuisance, per this ADR's own
  Context finding. Everything else stays independent per package on purpose (no shared UI-component library,
  no shared router) — the point of the workspace is that one crypto module, not general code-sharing.
  - **What moves**: the genuinely Server-Owner-only, ongoing-administration surface — tenant CRUD (create,
    list, resend, reassign, revoke, change-domain, suspend, reactivate) and release info — **and** first-run
    setup (`setupServer.ts`/`SetupView.vue`, Context above).
  - **What stays in `web/`**: the tenant-invite **accept** side (`previewInvite`, `acceptInvite`,
    `registerAndAccept`, `bornTenantScope`, `tenantError` — reachable by an anonymous invitee, not a Server
    Owner), the terms **accept** side and `getTenantTerms` (any member, not an admin action), the four
    backup/restore E2E rehearsals (unrelated to this feature, parked under `features/admin/` only
    incidentally), and — least obviously — **per-tenant mail settings and terms authoring**. Those two read
    `activeSpace.tenantId` and hang off the settings hub's `ScopeKind.Tenant` category rather than behind a
    Server-Owner check; the backend gates both with `403 not_tenant_owner`, not with the
    Server-Owner gate; and a tenant owner is by construction an ordinary user who accepted an invite
    (uc.admin.create-tenant is two-party). Moving them would take a tenant's own email and terms
    configuration away from every owner who is not also the Server Owner — a product change, which this
    decision is not. Everything in this list is relocated out of `features/admin/**` into an accurate home
    inside `web/`; the exact destination is the CR's inventory, not this ADR's.

    **The rule the split follows, and the one to apply to anything moved later: ask which gate the BACKEND
    applies — the Server-Owner gate, or `not_tenant_owner` — never which directory the file happens to sit
    in.** The Context finding's cross-import method is right as far as it reaches: grepping for imports *into*
    `features/admin/**` finds the anonymous and ordinary-member callers. It is structurally blind to a file
    that is **owner-scoped by the state it reads** and that nothing imports from outside, which is exactly
    what those two panels are — a **fourth** audience, the tenant owner, that an import graph cannot show.
  - **`web/` carries no "is this caller a Server Owner" probe.** It used to (`serverRole.ts`, calling
    `listTenants` to show Server Owners a settings note that administration lives elsewhere); under the admin
    channel that call can only ever 404, so the probe and the note are deleted rather than re-pointed — the
    Server Owner is by construction the person who ran setup in the admin container. `admin-web` keeps its own
    probe, which it makes through the admin channel.
  It reverse-proxies `/api/*` back to the backend's **admin listener** (below) from **its own origin** (whatever
  domain or `localhost` reaches the `admin` container) — **nginx**, serving the built static assets and
  reverse-proxying `/api/*`, with the backend's address templated into its config at container start
  (`envsubst` over an `nginx.conf.template`, reading a `BACKEND_URL` env var) — so the browser session stays same-origin
  regardless of where the container is reached from, avoiding the cross-origin refresh-cookie cost the
  trusted-web SaaS track already flagged as expensive (inbox #53: "cross-origin auth (breaks the same-origin
  cr-0055 refresh-cookie)"). Authentication is the existing Server-Owner session, unchanged — first-run setup
  simply happens to be the one Admin API call that needs no prior session at all.

  **An "unchanged" auth model still costs this package a client of its own**, which is the easy thing to miss
  when listing what it generates: without `auth.openapi.yaml` nothing here can obtain a bearer token and every
  tenant call is a 401. So `admin-web` carries its own **minimal** Model B sign-in — the same
  challenge/verify endpoints and the same `deriveAuthKeypair` from the shared crypto package, and
  deliberately none of `web/`'s passkeys, "remember me" device-key wrap, session vault or password recovery.
  This surface holds no vault to unlock, and each of those would be another place touching key material.
- **CLI — Node.js, in the same package, added in a follow-up CR.** The same container's build ships a CLI
  entry point that is **also** an HTTP client of the backend Admin API (using a Server-Owner credential
  supplied to the container) — written in Node.js specifically so it can import the **same generated API
  client** `admin-web/`'s UI uses, rather than a second implementation in another language: "no duplicated
  business logic" (Forces) means the same code, not merely the same wire contract. This is real code reuse,
  not just parity — worth stating because the repo's general convention is Python for scripts/tooling, which
  this deliberately departs from as a shipped product feature, not dev tooling. A break-glass, direct-DB/file
  CLI mode (for when the backend itself is down) is an explicit **non-goal** of this ADR: it would reopen the
  "second component touches secrets" problem this decision exists to avoid, and nothing today needs it.
  - **The Argon2id login (`@veyq/crypto/argonWorker`) already degrades to its synchronous path when no
    `Worker` global exists** (`argonWorker.ts`'s own comment names "vitest/node" as exactly this case) — a
    plain Node.js process needs no CLI-specific crypto path at all, just the same import
    `admin-web`'s `login.ts` uses.
  - **Auth model: a fresh login per invocation, credentials from the environment, no token ever written to
    disk.** `ARGON = { t: 3, m: 32768, p: 1 }` over the pure-JS `@noble/hashes` implementation costs roughly
    1–3s per derivation — a real, named cost, accepted deliberately for an occasional ops tool rather than
    building a token cache (a new "credential material at rest" case this ADR's whole point is to avoid
    multiplying) to save it. Revisit only if real usage shows this cost actually blocking a workflow.
  - **Bundled with esbuild** into a single Node-executable file at image-build time — not run via
    `tsx`/`ts-node` at container runtime, which would make TS transpilation a runtime dependency of a shipped
    feature rather than a build-time step.

    **esbuild is a genuine new direct devDependency, not a free one** — a tempting thing to assume and
    measurably false here: `npm ls esbuild --workspace=admin-web` returns empty and no `esbuild` directory
    exists anywhere in the installed tree, because Vite 8 bundles with **rolldown**. It earns its place on its
    own merits instead — build-time only, never in the runtime image, actively maintained and vendor-neutral.
    The alternative actually installed, rolldown, is deliberately not used: it is Vite's bundler by way of
    Vite, not a tool this repo has chosen, and pinning a CLI's build to whatever bundler Vite ships next is
    the same mistake in a new place.
  - **The runtime image gains a real, named cost: Node.js alongside nginx.** The final stage is
    `nginxinc/nginx-unprivileged:<pinned>-alpine` (serving static assets + reverse-proxying, Decision above —
    the *unprivileged* variant, uid 101 on port 8080, because a stock nginx runs as root and binds 80, which a
    `restricted` PodSecurity namespace refuses and which would leave [ADR 0064](0064-kubernetes-helm-deployment-target.md)'s
    chart unable to schedule the one surface first-run setup lives on; `scripts/validate_images_run_unprivileged.py`
    holds every runtime stage to it); the CLI needs a Node runtime to execute in, so that stage installs
    `nodejs` (Alpine's package, actively maintained, already the distro's own vendor-neutral build) — a heavier
    image than "just nginx," accepted because ADR 0065 already decided the CLI ships in this container, not a
    second one.
  - **Argument parsing: `node:util.parseArgs`, not a third-party CLI framework.** Node-core, zero
    dependencies, actively maintained by definition — the command surface (tenant CRUD and release info;
    mirrors whatever `admin-web/src/api/client.ts`'s factories are actually called for, located at CR time) is
    small enough that a framework like commander/yargs buys nothing here. Nine operations, as built: eight
    tenant verbs plus `release`. **Mail settings and terms authoring are not among them** for the same reason
    they never left `web/`: they are **tenant-owner** actions (`403 not_tenant_owner`), so they were never
    `admin-web`'s to wrap. **Setup is excluded too**, and that one is a decision rather than a consequence: it
    is an interactive ceremony that mints key material and force-confirms a saved Emergency Kit, which a
    script cannot meaningfully do.
- **Exposure is an operator choice, not a build-time one — two modes of the same image:**
  - **Localhost-only (the default).** No Traefik/Ingress router at all; reached via `docker compose exec`, a
    port bound to `127.0.0.1`, or an SSH tunnel. Setup moving here is a genuine sequencing improvement, not
    just a security posture: first-run setup no longer needs `VEYQ_DOMAIN`'s DNS/TLS to be live at all
    (`docker compose up` + `http://localhost:PORT` is enough), where today it does, because setup lives in
    the main SPA served at that domain.
  - **Own domain.** An optional static `ADMIN_DOMAIN` + router/TLS label, the same fixed-domain pattern
    `VEYQ_DOMAIN` used before [ADR 0063](0063-database-driven-tenant-ingress.md) — the admin surface is 1:1
    with the instance, not per-tenant, so it needs none of that ADR's dynamic multi-domain machinery. Set it
    **after** first-run setup: this mode puts the admin channel on the internet (residual (iv) below).
- **Trust tier: unchanged, not new.** Per [ADR 0026](0026-client-trust-tiers.md), a self-hosted operator
  running the admin container themselves is the *Self-hosted* tier — moot, operator = user — exactly as the
  embedded admin pages are today. Extraction changes **reachability**, not **trust tier**; no new tier is
  introduced by this ADR.
- **The backend answers the Server-Owner surface only on the admin channel** — the property the first force
  asked for, held where every deployment has it in common rather than at an edge a self-hoster may replace.
  The behaviour, its failure modes and its acceptance criteria are
  [uc.admin.admin-channel](../features/admin/use-cases/uc.admin.admin-channel.md)'s; what is decided here:
  - **The channel is a second backend listener, read from the socket.** The backend starts one more Vert.x
    HTTP server (default port `8090`) whose request handler is Quarkus' own root handler
    (`VertxHttpRecorder.getRootHandler()` — the handler Quarkus installs on its HTTP, TLS and domain-socket
    servers), so it runs the identical pipeline: forwarding, security, JAX-RS. A request is on the admin channel
    **iff** it arrived on that listener **and** its socket peer is not globally routable — read from
    `localAddress()` and `connection().remoteAddress()`, never from `remoteAddress()` or any header, which the
    forwarding layer rewrites from client-written values. Measured in a refine spike: a forged `Forwarded` /
    `X-Forwarded-Host` moved `host()` and `remoteAddress()` and left `localAddress()` untouched. The peer rule is
    a belt for topologies the shipped targets do not use (host networking, a published port) — see residuals.
  - **Which operations: every operation whose backend gate is the Server-Owner gate, plus `createServerOwner`**,
    which is anonymous by nature and the one this decision exists for. The split rule above applies unchanged —
    ask which gate the backend applies. `getSetupStatus` stays on both channels (the web app boots from it; the
    probes of both targets poll it), and so does sign-in, which the admin surface and the CLI need too.
  - **Declared per operation in `contracts/` (`x-veyq-channel: admin`), derived — never listed — by the
    backend, and held to the gate.** The backend builds its set from the specs at build time; a posture sweep
    fails when an operation carries the Server-Owner gate without the marker, or a marker names no resource
    method. This is **not** the authorization-in-the-spec that [ADR 0008](0008-centralized-http-auth.md)
    rejected: the marker names which door answers, not a role, the role string stays out of the published
    contract, and the sweep reads the annotation rather than the spec.
  - **The public channel refuses with 404, from a filter keyed on the matched resource method** — so no path
    spelling routes around it. `@RolesAllowed` runs before any post-match filter (measured, independent of
    filter priority), so an anonymous call to a role-gated operation still gets the 401 it always got and
    executes nothing; a uniform 404 would need a second, path-template matcher in front of JAX-RS, and buys
    nothing an attacker lacks — the operation list is in a public repository.
  - **Setup therefore needs no bootstrap token.** The window "first caller after `up -d`" closes because the
    public origin never answers the call; the one door that does is the one the operator reaches privately.
  - **Deployment.** The listener is not `EXPOSE`d (so `docker run -P` does not publish it); compose points the
    admin container's `BACKEND_URL` — and the CLI's default — at it; the chart gives it a Service of its own, so
    no Ingress aimed at the app Service (the chart's, a projected tenant one, or an operator's) can reach it, and
    a NetworkPolicy admitting only the admin pod to that port.
  - **Residuals, named rather than discovered.** (i) Compose declares no `networks:`, so every container of the
    stack reaches the listener; Traefik terminates TLS and MariaDB holds the database, so reaching it adds
    nothing they lack. (ii) On a cluster whose CNI does not enforce NetworkPolicy, any pod in the cluster
    reaches it. (iii) NAT that rewrites a public peer to a private address (Docker's userland proxy) defeats the
    peer rule — which is why it is a belt and the guarantee is that the shipped targets route nothing to the
    listener. (iv) **Own-domain mode puts the admin container itself on the internet**, and with it the admin
    channel: before setup that reopens exactly the window this closes, so setup runs over the localhost path
    first and `ADMIN_DOMAIN` is set afterwards — documented, not enforced.
  - **Expiry condition.** `getRootHandler()` is public but lives in a `runtime` package, not documented API.
    An upgrade that removes it fails compilation, loudly; the fallback then is the channel secret rejected
    below — rejected for its costs, not because it fails.

## Consequences

- **Smaller public attack surface on the main origin** — it does not answer the Server-Owner API at all, and an
  operator can firewall the entire admin container off from the internet without touching the user-facing app.
- **Every client of the Server-Owner API goes through the admin listener**, including the ones that are not the
  admin surface: the live e2e harness and the dev seed scripts birth instances and tenants through it, and a
  backend slice test that calls a Server-Owner operation does so on it.
- **A new deployable, and a new release artifact** (`ghcr.io/<owner>/veyq-admin`), versioned and signed the
  same way as the app image ([ADR 0057](0057-release-identity-and-deployment-recipe.md)) — named as a
  consequence here; the exact CI wiring is a future CR's inventory, not this ADR's.
- **Migration cost.** `web/src/features/admin/**`, its router entries and its tests move to a new frontend
  package — scoped and grepped at CR time, not estimated here (`docs/ai/README.md`'s "decisions over
  keystrokes").
- **Two CRs, not one — the UI move is independently shippable.** The web extraction (new package, container,
  nginx, exposure modes) stands on its own the moment it's done: an operator can already firewall the admin
  surface off the main origin. The CLI is additive to the same container's build and lands in a follow-up CR
  once the extraction has landed — "genuinely independent work" per `changes/README.md`, not a phase split.
- **Ships as part of [ADR 0064](0064-kubernetes-helm-deployment-target.md)'s Helm chart too**, with the same
  two exposure modes expressed as chart values.
- **Rejected — keep it embedded, gate exposure with a feature flag.** Cheaper to build, but the flag itself
  ships in the main image and origin, so a compromised main-app deployment (a supply-chain issue in a
  dependency, a misconfigured proxy) still exposes admin-capable code at the same reachability as the rest of
  the app — the whole point was to remove that coupling, not re-implement it as a runtime toggle.
- **Rejected — exclude the admin paths at the edge** (a Traefik rule, an Ingress path). It holds only for the
  edge this repository ships; a self-hoster's own reverse proxy loses it silently.
- **Rejected — a channel secret the admin nginx injects as a header.** It is topology-independent, which is its
  real strength. But it is new credential material at rest in three places (`.env`, the chart Secret, the
  rendered nginx config) — the multiplication the CLI's no-token-cache decision above refuses — and a leaked copy
  makes the **public** origin an admin door. A listener has nothing to leak.
- **Rejected — let the admin container hold state itself** (its own database or KEK path). It breaks the second
  force, and it drops the owner's login as a layer.
- **Rejected — a bootstrap token for setup.** Once the public origin never answers setup, a token only adds a
  secret to distribute for a window the channel already closes.
- **Rejected — the second listeners Quarkus already has.** The domain socket needs a volume both containers
  mount, which on Helm means one pod — no longer a separate deployable. The TLS port needs a certificate, and
  its `isSSL()` is derived from forwarded headers. The management interface serves no JAX-RS.

## Related

- [uc.admin.admin-channel](../features/admin/use-cases/uc.admin.admin-channel.md) (the property the admin
  channel holds, and its failure modes).
- [ADR 0008](0008-centralized-http-auth.md) (authorization stays out of the contract; the channel marker is
  reachability, not a role).
- [ADR 0026](0026-client-trust-tiers.md) (trust tiers this container inherits, not extends).
- [ADR 0050](0050-operator-config-secret-encryption.md) (the secret this ADR deliberately keeps
  single-homed).
- [ADR 0063](0063-database-driven-tenant-ingress.md), [ADR 0064](0064-kubernetes-helm-deployment-target.md)
  (the two deployment targets this container ships on).
