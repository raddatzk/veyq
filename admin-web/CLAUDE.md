# admin-web (Vue 3 + PrimeVue)

The Server-Owner administration surface, extracted from `web/` by
[ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md). Loaded in addition to the root
`CLAUDE.md` when working here.

## What this surface is — and what it must never become
- **A pure client of the existing backend Admin API.** No DB access, no config-secret KEK, no backend
  surface of its own. If a change here needs a new backend endpoint, that is a backend CR, not this one.
- **Four generated clients and no more** — setup, auth, tenants, instance. There is no content, media,
  sharing or scopes client here, and that absence is load-bearing: this app cannot reach user content even
  by mistake. Adding a fifth spec is a decision, not a convenience.
- **No vault, no content key, ever.** The one place key material is touched is first-run setup, which
  performs a real cold account birth through `@veyq/crypto` — the shared package, so there is exactly one
  copy of that code. Never re-implement or fork it here; that would be the security defect the workspace
  exists to prevent.
- **Reachability is the operator's choice, not this app's.** Localhost-only by default, optionally an
  `ADMIN_DOMAIN`. So this app never links to itself from `web/`, and never assumes an address.

## The split, and the trap in it
`web/src/features/admin/**` was **not** admin-only, which is why a directory move would have broken the app.
The line that actually holds:

- **Here:** what only the **Server Owner** does — tenant CRUD (create/list/resend/reassign/revoke/
  change-domain/suspend/reactivate), release info, first-run setup.
- **In `web/`:** what an **ordinary or anonymous user** does — accepting a tenant invite, accepting terms —
  *and* what a **tenant OWNER** does for their own tenant: terms authoring and per-tenant mail settings.
  Those two look like administration and are not: the backend gates them with `403 not_tenant_owner`, and a
  tenant owner is an ordinary user who accepted an invite. Moving them here would take a tenant's own
  settings away from everyone who is not also the Server Owner. ADR 0065's own "what moves" list got this
  wrong; the code is the authority.

**Before moving anything else across this line, check which gate the BACKEND applies** — Server-Owner or
`not_tenant_owner` — not which directory the file happens to sit in.

## Prove your work
- `npm run check` (lint + typecheck + test + build). `npm ci` runs from the **repo root** (npm workspaces).
- API client is **generated** and **not tracked** — `npm run generate:api` from `../contracts/api/*.openapi.yaml`
  (and automatically on install via `prepare`). Never hand-write it. As in `web/`, **`npm run check` does not
  regenerate**: change a contract and a green check is a green run against stale code.
- The root **Server-Owner gate** (`src/app/serverRole.ts` + `AdminConsoleView`) is a courtesy over an
  authority that lives server-side. It decides which of three honest screens to show; it never decides
  access. `AdminConsoleView.test.ts` pins one case per verdict — keep it that way.

## Conventions
- Styles, `httpError`, `inviteLink`, the async-state helpers and the Emergency-Kit module are **copies** of
  `web/`'s, on purpose: ADR 0065 keeps everything but the crypto independent per package, so a redesign of
  one surface is never a regression in the other. Do not build a shared UI package. The kit module is the
  **write half only** — no `importKitFromFile`, and therefore no `jsqr`, which `web/CLAUDE.md` carries as a
  single argued exception to the no-unmaintained-dependency rule. An exception earned once does not travel.
- **A green `npm run check` here does NOT prove this package's dependencies are declared.** npm workspaces
  hoist, so an import satisfied by `web/`'s copy in the root `node_modules` resolves locally and fails only
  in `docker build -f admin-web/Dockerfile .`, which never sees `web/`. Measured: `jsqr` built green here and
  red in the container. Run the container build before believing an added import.
- i18n is this package's own catalog (EN + DE) in `src/i18n/index.ts`, lifted from `web/`'s for the strings
  that moved with their views.

Stack: Vue 3, PrimeVue, vue-router. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
