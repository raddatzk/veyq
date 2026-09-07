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

## Decision

**The admin surface becomes its own container (`admin`), and stays a pure client of the existing backend
Admin API — no new backend surface, no direct DB or KEK access.**

- **Web UI.** `web/src/features/admin/**` moves into its own frontend build, served by its own container. It
  reverse-proxies `/api/*` back to the instance's backend from **its own origin** (whatever domain or
  `localhost` reaches the `admin` container), so the browser session stays same-origin regardless of where
  the container is reached from — this avoids reopening the cross-origin refresh-cookie cost the trusted-web
  SaaS track already flagged as expensive (inbox #53: "cross-origin auth (breaks the same-origin cr-0055
  refresh-cookie)"). Authentication is the existing Server-Owner session, unchanged.
- **CLI.** The same container ships a CLI entry point that is **also** an HTTP client of the backend Admin
  API (using a Server-Owner credential supplied to the container) — no duplicated business logic, no
  independent authz decisions. A break-glass, direct-DB/file CLI mode (for when the backend itself is down)
  is an explicit **non-goal** of this ADR: it would reopen the "second component touches secrets" problem
  this decision exists to avoid, and nothing today needs it.
- **Exposure is an operator choice, not a build-time one — two modes of the same image:**
  - **Localhost-only.** No Traefik/Ingress router at all; reached via `docker compose exec`, a port bound to
    `127.0.0.1`, or an SSH tunnel.
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
