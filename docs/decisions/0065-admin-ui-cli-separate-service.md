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

## Decision

**The admin surface becomes its own container (`admin`), and stays a pure client of the existing backend
Admin API — no new backend surface, no direct DB or KEK access.**

- **Web UI — a new package, sharing only crypto with `web/` via a minimal npm workspace.** `admin-web/` gets
  its own `package.json` + Vite build + `index.html`, its own narrow generated API client (from
  `contracts/api/tenants.openapi.yaml` + `contracts/api/instance.openapi.yaml`, mirroring `scopes.openapi.yaml`'s
  `ScopeKind` if the moved code still needs it), and its own router/state/UI — none of that is shared with
  `web/`. The **one** thing that is: `shared/crypto/v1` + `accountBirth` are extracted into a small internal
  workspace package both `web/` and `admin-web/` depend on — the only genuinely shared surface, and the one
  place duplication would be a security defect rather than a maintenance nuisance, per this ADR's own
  Context finding. Everything else stays independent per package on purpose (no shared UI-component library,
  no shared router) — the point of the workspace is that one crypto module, not general code-sharing.
  - **What moves**: the genuinely Server-Owner-only, ongoing-administration surface — tenant CRUD (create,
    list, resend, reassign, revoke, change-domain, suspend, reactivate), ~~per-tenant mail settings, terms
    authoring~~ (struck 2026-09-08 — see the amendment below), release info — **and** first-run setup
    (`setupServer.ts`/`SetupView.vue`, Context above).
  - **What stays in `web/`**: the tenant-invite **accept** side (`previewInvite`, `acceptInvite`,
    `registerAndAccept`, `bornTenantScope`, `tenantError` — reachable by an anonymous invitee, not a Server
    Owner), the terms **accept** side and `getTenantTerms` (any member, not an admin action), and the four
    backup/restore E2E rehearsals (unrelated to this feature, parked under `features/admin/` only
    incidentally). These are relocated out of `features/admin/**` into an accurate home inside `web/` — the
    exact destination is the CR's inventory, not this ADR's.

    **Amended 2026-09-08, at implementation, measured against the code: per-tenant mail settings and terms
    authoring stay in `web/` too.** The "what moves" list above named them, and that was wrong. Both panels
    read `activeSpace.tenantId` and hang off the settings hub's `ScopeKind.Tenant` category — **not** behind
    `serverRole.canManage`; the backend gates both with `403 not_tenant_owner`, not with the Server-Owner
    gate; and a tenant owner is by construction an ordinary user who accepted an invite (uc.admin.create-tenant
    is two-party). So there is a **fourth** audience the Context finding above missed — the tenant owner — and
    moving those two panels would have taken a tenant's own email and terms configuration away from every
    owner who is not also the Server Owner. That is a product change, not a refactor, and nothing here asked
    for it. **The rule the split actually follows, and the one to apply to anything moved later: ask which
    gate the BACKEND applies — the Server-Owner gate, or `not_tenant_owner` — never which directory the file
    happens to sit in.** The Context finding's method was right and its net was one audience too small: it
    grepped for cross-imports *into* `features/admin/**`, which finds the anonymous and ordinary-member
    callers but not a file that is itself owner-scoped by the state it reads.
  - The lightweight "is this caller a Server Owner" probe (`serverRole.ts`) stays in `web/` too — it is what
    lets the main SPA conditionally show a link *to* the admin surface, and `TermsAcceptView`'s
    `resetServerRole` call needs it regardless. `admin-web` performs its own independent probe against the
    same backend endpoint on load; the two are not shared state, since a separate origin does not even share
    the session cookie once `ADMIN_DOMAIN` differs from the app's.
  It reverse-proxies `/api/*` back to the instance's backend from **its own origin** (whatever domain or
  `localhost` reaches the `admin` container) — **nginx**, serving the built static assets and reverse-proxying
  `/api/*`, with the backend's address templated into its config at container start (`envsubst` over an
  `nginx.conf.template`, reading a `BACKEND_URL` env var) — so the browser session stays same-origin
  regardless of where the container is reached from, avoiding the cross-origin refresh-cookie cost the
  trusted-web SaaS track already flagged as expensive (inbox #53: "cross-origin auth (breaks the same-origin
  cr-0055 refresh-cookie)"). Authentication is the existing Server-Owner session, unchanged — first-run setup
  simply happens to be the one Admin API call that needs no prior session at all.

  **Amended 2026-09-08, at implementation: "unchanged" still costs this package a client.** The generated-client
  list above (tenants + instance) named no auth spec, and without one nothing here can obtain a bearer token —
  every tenant call would be 401. So `admin-web` also generates from `auth.openapi.yaml` and carries its own
  **minimal** Model B sign-in: the same challenge/verify endpoints and the same `deriveAuthKeypair` from the
  shared crypto package, and deliberately none of `web/`'s passkeys, "remember me" device-key wrap, session
  vault or password recovery — this surface holds no vault to unlock, and each of those would be another place
  touching key material.
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

    **Amended 2026-09-09, at implementation, measured: ~~already a transitive Vite dependency, not a new
    tool~~ is false.** `npm ls esbuild --workspace=admin-web` returns empty and no `esbuild` directory exists
    anywhere in the installed tree: Vite 8 bundles with **rolldown**, so the premise that made esbuild free
    was true of Vite 6 and is not true here. esbuild is therefore a genuine new direct devDependency of
    `admin-web` — build-time only, never in the runtime image, actively maintained and vendor-neutral, so it
    passes the dependency rule on its own merits rather than on being already present. The decision stands;
    its justification was one version out of date. The alternative actually available — reusing rolldown,
    which *is* installed — was not taken: it is Vite's bundler by way of Vite, not a tool this repo has chosen,
    and pinning a CLI's build to whatever bundler Vite ships next is the same mistake in a new place.
  - **The runtime image gains a real, named cost: Node.js alongside nginx.** The final stage was
    `nginx:<pinned>-alpine` (serving static assets + reverse-proxying, Decision above); the CLI needs a Node
    runtime to execute in, so that stage installs `nodejs` (Alpine's package, actively maintained,
    already the distro's own vendor-neutral build) — a heavier image than "just nginx," accepted because ADR
    0065 already decided the CLI ships in this container, not a second one.
  - **Argument parsing: `node:util.parseArgs`, not a third-party CLI framework.** Node-core, zero
    dependencies, actively maintained by definition — the command surface (tenant CRUD, ~~mail settings,
    terms,~~ release info; mirrors whatever `admin-web/src/api/client.ts`'s factories are actually called for,
    located at CR time) is small enough that a framework like commander/yargs buys nothing here. Nine
    operations, as built: eight tenant verbs plus `release`. The struck two are the same correction the
    "what moves" amendment above already made — mail settings and terms authoring are **tenant-owner** actions
    (`403 not_tenant_owner`) that never left `web/`, so they were never `admin-web`'s to wrap. **Setup is
    excluded too**, and that one is a decision rather than a correction: it is an interactive ceremony that
    mints key material and force-confirms a saved Emergency Kit, which a script cannot meaningfully do.
- **Exposure is an operator choice, not a build-time one — two modes of the same image:**
  - **Localhost-only (the default).** No Traefik/Ingress router at all; reached via `docker compose exec`, a
    port bound to `127.0.0.1`, or an SSH tunnel. Setup moving here is a genuine sequencing improvement, not
    just a security posture: first-run setup no longer needs `VEYQ_DOMAIN`'s DNS/TLS to be live at all
    (`docker compose up` + `http://localhost:PORT` is enough), where today it does, because setup lives in
    the main SPA served at that domain.
  - **Own domain.** An optional static `ADMIN_DOMAIN` + router/TLS label, the same fixed-domain pattern
    `VEYQ_DOMAIN` used before [ADR 0063](0063-database-driven-tenant-ingress.md) — the admin surface is 1:1
    with the instance, not per-tenant, so it needs none of that ADR's dynamic multi-domain machinery.
- **Trust tier: unchanged, not new.** Per [ADR 0026](0026-client-trust-tiers.md), a self-hosted operator
  running the admin container themselves is the *Self-hosted* tier — moot, operator = user — exactly as the
  embedded admin pages are today. Extraction changes **reachability**, not **trust tier**; no new tier is
  introduced by this ADR.
- **Backend Admin API is unchanged.** This is a deployment-topology decision, not a contract change — the
  endpoints `web/src/features/admin/**` already calls keep their existing shape and authz.

## Consequences

- **Smaller public attack surface on the main origin** — an operator can firewall the entire admin container
  off from the internet without touching the user-facing app.
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

## Related

- [ADR 0026](0026-client-trust-tiers.md) (trust tiers this container inherits, not extends).
- [ADR 0050](0050-operator-config-secret-encryption.md) (the secret this ADR deliberately keeps
  single-homed).
- [ADR 0063](0063-database-driven-tenant-ingress.md), [ADR 0064](0064-kubernetes-helm-deployment-target.md)
  (the two deployment targets this container ships on).
