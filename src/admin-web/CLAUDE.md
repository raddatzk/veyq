# admin-web (Vue 3 + its own UI layer on Reka UI)

The Server-Owner administration surface, extracted from `src/web/` by
[ADR 0065](../../docs/decisions/0065-admin-ui-cli-separate-service.md). Loaded in addition to the root
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
  `ADMIN_DOMAIN`. So this app never links to itself from `src/web/`, and never assumes an address.

## The split, and the trap in it
`src/web/src/features/admin/**` was **not** admin-only, which is why a directory move would have broken the app.
The line that actually holds:

- **Here:** what only the **Server Owner** does — tenant CRUD (create/list/resend/reassign/revoke/
  change-domain/suspend/reactivate), a tenant's deletion (its typed domain, offline at once, stoppable until the purge;
  over content with its statement of reasons, ADR 0101 decision 10) and the owner invite to a member of an ownerless
  tenant, an account's erasure by address with the pending erasures and their stop (ADR 0101), each tenant's storage
  quota (ADR 0073 §1), release info and the release check's notices and switch, the instance's own mail to the Server
  Owner (ADR 0097), the config-secret state, the object store and its check (ADR 0089), the capability switches
  (ADR 0088), first-run setup.
- **In `src/web/`:** what an **ordinary or anonymous user** does — accepting a tenant invite, accepting terms —
  *and* what a **tenant OWNER** does for their own tenant: terms authoring, per-tenant mail settings, and the
  per-link storage limit (which the owner distributes below the quota, and reads the quota beside).
  Those two look like administration and are not: the backend gates them with `403 not_tenant_owner`, and a
  tenant owner is an ordinary user who accepted an invite. Moving them here would take a tenant's own
  settings away from everyone who is not also the Server Owner. ADR 0065's own "what moves" list got this
  wrong; the code is the authority.

**Before moving anything else across this line, check which gate the BACKEND applies** — Server-Owner or
`not_tenant_owner` — not which directory the file happens to sit in.

## Two artifacts, one package
The Vite build is not the only output any more: `npm run build:cli` bundles `src/cli/` into
`dist-cli/cli.js`, the `veyq-admin` CLI the image installs on `PATH` (ADR 0065). It is the same generated
client and the same feature facades the views call — its own code is argument parsing and output formatting,
nothing else. **A command that needs a rule of its own means the rule belongs in the facade**, where the UI
gets it too. Setup is not a command and is not an oversight: it mints key material and force-confirms an
Emergency Kit, which a script cannot meaningfully do.

Two things the bundle taught, both measured, both live in comments at the lines that need them:
`@veyq/crypto` is consumed as TypeScript **source** with extensionless imports, so plain `node` cannot import
it at all (only a bundler resolves it — `src/cli/main.ts`); and the bundle carries Vue, unrendered, because
the CLI signs in through the app's own `login()` and that reaches `app/session.ts`'s `reactive()`. That is
1.5 MB of dead weight traded for there being exactly one sign-in path, which is the trade ADR 0065 wants.

## Run it locally
`./gradlew admin:run` from the repo root (the launcher, [`infra/README.md`](../../infra/README.md)), or `npm run dev`
here: Vite on `VITE_ADMIN_PORT` (5174) with `/api` proxied to `VEYQ_API_TARGET` (`http://127.0.0.1:8090`, the
backend's **admin listener** — its public port 404s every Server-Owner operation) — the same single-origin shape
nginx gives the container, so the refresh cookie behaves identically either way. **On a fresh instance this is the
surface you open first**: first-run setup lives here, so the app at `veyq.localhost` has no Server Owner to log in
as yet (ADR 0065). `./gradlew admin:bootstrap` (`npm run generate:api`) regenerates this package's untracked client
after a contracts change.

## Prove your work
- `npm run check` (lint + typecheck + test + build + build:cli). `npm ci` runs from the **repo root** (npm
  workspaces). The CLI bundle is in `check` on purpose — an import that only the CLI reaches is otherwise
  proven by nothing until the image build.
- API client is **generated** and **not tracked** — `npm run generate:api` from `../../contracts/api/*.openapi.yaml`
  (and automatically on install via `prepare`). Never hand-write it. As in `src/web/`, **`npm run check` does not
  regenerate**: change a contract and a green check is a green run against stale code.
- The root **Server-Owner gate** (`src/app/serverRole.ts` + `AdminConsoleView`) is a courtesy over an
  authority that lives server-side. It decides which of three honest screens to show; it never decides
  access. `AdminConsoleView.test.ts` pins one case per verdict — keep it that way.

## Conventions
- Styles, `httpError`, `inviteLink`, the async-state helpers, the confirmation channel
  (`shared/state/useConfirm.ts` + `shared/ui/ConfirmDialog.vue`) and the Emergency-Kit module are **copies** of
  `src/web/`'s, on purpose: ADR 0065 keeps everything but the crypto independent per package, so a redesign of
  one surface is never a regression in the other. Do not build a shared UI package. Two later ports follow the
  same rule and carry their own traps: `.veyq-shell` + `.veyq-shell-inner` in `style.css` (web's
  `.public-shell`, which web splits across `layout.css` and a scoped block — kept together here, because the
  half that landed in web's own `style.css` went dead unnoticed), and `shared/state/systemTheme.ts` (web's
  `useThemeState` reduced to the system-following half: no preference, no switcher, because there is no
  settings screen to drive one). `layout.css` is still **not** imported and should stay that way — the rest of
  it is the Memory shell. And `systemTheme.ts` must stay reachable from `main.ts` alone: it imports SVGs with
  Vite's `?raw`, which the CLI's bare esbuild bundle cannot resolve. The kit module is the
  **write half only** — no `importKitFromFile`, and therefore no `jsqr`, which `src/web/CLAUDE.md` carries as a
  single argued exception to the no-unmaintained-dependency rule. An exception earned once does not travel.
- **A green `npm run check` here does NOT prove this package's dependencies are declared.** npm workspaces
  hoist, so an import satisfied by `src/web/`'s copy in the root `node_modules` resolves locally and fails only
  in `docker build -f src/admin-web/Dockerfile .`, which never sees `src/web/`. Measured: `jsqr` built green here and
  red in the container. Run the container build before believing an added import — and that now covers the
  CLI bundle too, which the image builds from its own install, one that has never seen `src/web/`.
- i18n is this package's own catalog (EN + DE) in `src/i18n/index.ts`, lifted from `src/web/`'s for the strings
  that moved with their views.

Stack: Vue 3, the UI layer in `src/shared/ui/` (its own copy, Reka UI and Lucide underneath, ADR 0107), vue-router. Working rules: [`../../docs/ai/README.md`](../../docs/ai/README.md).
