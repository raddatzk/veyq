---
id: cr-admin-web-extraction
title: Extract the admin surface into its own frontend package + container
status: planned
satisfies: [uc.admin.setup-server, uc.admin.create-tenant, uc.admin.tenant-mail-settings, uc.admin.tenant-terms, uc.admin.suspend-tenant, uc.admin.tenant-reachability, uc.admin.identify-instance-release]
adrs: ["0065"]
invariants: [operator-blind everywhere, self-hostable by default]
---

## Goal

The Server-Owner administration surface (tenant CRUD, mail settings, terms authoring, release info, and
first-run setup) moves out of the main SPA into its own package (`admin-web/`) and its own container
(`admin`), reachable localhost-only by default or on an optional `ADMIN_DOMAIN`. The CLI entry point in the
same container is a **separate, follow-up CR** (ADR 0065 Consequences) — this CR is the web extraction only.

## Context & constraints

- [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) — the decision this CR implements,
  including the refine-session correction that `web/src/features/admin/**` is **not** cleanly admin-only
  (three core-app flows and four unrelated E2E tests live there today) and that first-run setup moves **with**
  the admin surface, which is why a minimal npm workspace for `shared/crypto/v1` + `accountBirth` is now part
  of the decision.
- The use-cases this CR's move affects: [uc.admin.setup-server](../docs/features/admin/use-cases/uc.admin.setup-server.md),
  [uc.admin.create-tenant](../docs/features/admin/use-cases/uc.admin.create-tenant.md),
  [uc.admin.tenant-mail-settings](../docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md),
  [uc.admin.tenant-terms](../docs/features/admin/use-cases/uc.admin.tenant-terms.md),
  [uc.admin.suspend-tenant](../docs/features/admin/use-cases/uc.admin.suspend-tenant.md),
  [uc.admin.tenant-reachability](../docs/features/admin/use-cases/uc.admin.tenant-reachability.md),
  [uc.admin.identify-instance-release](../docs/features/admin/use-cases/uc.admin.identify-instance-release.md)
  — none of their flows change; only where the code implementing them lives, so every coverage ref pointing at
  a moved test file needs its path corrected (Phase 5).
- Invariants at risk: **operator-blind everywhere** (`admin-web` must stay a pure Admin-API client — no DB,
  no KEK, and the extracted crypto package must not become a second place holding key material logic that
  drifts from `web/`'s); **self-hostable by default** (the default exposure mode must not require DNS/TLS to
  already exist — this is what makes moving setup here a genuine improvement, not a regression, per ADR 0065
  Decision).

## Decisions

**1 — Reorganize `web/` first, in place, before anything moves (behavior-preserving).** Per the ADR's Context
finding, `web/src/features/admin/**` mixes three kinds of code. Locate the exact current membership with
`find web/src/features/admin -type f` (22 files, 2466 lines as of this refine — re-run, don't trust the
number) and sort into:
   - **Moves to `admin-web/` as-is** (Phase 3): `createTenant/{TenantDetailPanel,TenantMailSettingsPanel,
     TenantTermsPanel,CreateTenantDialog}.vue` + their tests, the **admin-only** exports of `createTenant/tenants.ts`
     (create/list/resend/reassign/revoke/change-domain/suspend/reactivate/mail-settings-CRUD/terms-authoring),
     `instance/*` (release info), `setupServer/*` (first-run setup — moves per ADR 0065's Decision, not left
     behind), `serverRole.ts`'s **admin-side** usage.
   - **Relocates within `web/`, out of `features/admin/`** (this phase): the **accept**-side exports of
     `tenants.ts` (`previewInvite`, `acceptInvite`, `registerAndAccept`, `bornTenantScope`, `tenantError` —
     used by `TenantJoinView.vue`) and of `terms.ts`/`TenantTermsPanel`'s accept path (used by
     `TermsAcceptView.vue`, `activeSpace.ts`'s `getTenantTerms`) — into a new, accurately-named location (e.g.
     `features/tenantInvite/`; the CR's implementer names it, per "decisions over keystrokes"). The four
     backup/restore E2E rehearsals (`restore.*.e2e.test.ts`, `legthree.*.e2e.test.ts`) relocate to wherever
     `web/`'s other whole-instance E2E suites live (locate with
     `grep -rl "describe.skipIf(!process.env.VEYQ_E2E)" web/src` for the sibling convention to match).
   - **Stays where it is, used by both sides**: `serverRole.ts`'s lightweight probe (used by `SettingsHubView`
     to show/hide a link to the admin surface, and by `TermsAcceptView`'s `resetServerRole`) — `admin-web`
     performs its own independent probe rather than sharing this file.
   Done when: `npm run check` is green with **zero behavior change** — this phase only moves files and updates
   imports, it does not yet create `admin-web/` or touch routing/containers. `SettingsHubView.vue`'s admin
   sections keep working exactly as today (they move in Phase 3, not this one).

**2 — Extract `shared/crypto/v1` + `accountBirth` into an internal workspace package.** `web/` and (from
Phase 3) `admin-web/` both depend on it; this is the **only** shared package — no shared UI/router/state.
Locate every current import site first: `grep -rln "shared/crypto/v1\|accountBirth" web/src` (the CR's
inventory, not a guess) — every one of those files' import path changes to the new package name, and this is
the phase where a workspace (`npm workspaces` in a root `package.json`, or the minimal equivalent) gets
introduced into this repo for the first time. Done when: `npm run check` (web) is green with the crypto
import path changed and no behavioral diff (same functions, same exports, moved verbatim — this phase does
not refactor the crypto code itself).

**3 — Create `admin-web/`: new package, own generated API client, move the admin-only files from Decision 1.**
Own `package.json`, `vite.config.ts`, `index.html`, `tsconfig.json`; its own `openapitools.json` (copy
`web/openapitools.json`'s generator-cli version pin) and a `generate:api` script scoped to
`tenants.openapi.yaml` + `instance.openapi.yaml` (+ `scopes.openapi.yaml` only if the moved code still
references `ScopeKind` — re-check after Phase 1's split, since some of that usage may have been in the parts
that stayed). Depends on Phase 2's crypto package for `setupServer.ts`'s account birth. Reconstructs the
Server-Owner gate that today lives implicitly in `SettingsHubView.vue` (there is no router-level artifact to
lift — `probeServerRole()` gates at data-fetch time, per the ADR's Context finding) as `admin-web`'s own root
gate. Done when: `admin-web`'s own `npm run check` (build + tests, mirroring `web/`'s script names) is green,
and `web/`'s `SettingsHubView.vue` no longer imports anything from a (now-deleted) `features/admin/` — `grep -rn
"features/admin" web/src` returns nothing.

**4 — Container + infra.** New root-level `admin-web/Dockerfile` (multi-stage: Node build stage mirroring the
main `Dockerfile`'s `frontend-build` lines 8–25, then an `nginx:<pinned>-alpine` runtime stage) +
`admin-web/nginx.conf.template` (static assets + `/api/*` reverse-proxy, `BACKEND_URL` substituted via
`envsubst` at container start — greenfield, no in-repo precedent, per the refine's grounding). Add `.dockerignore`
entry for `admin-web/src/api/generated` (mirrors the existing `web/src/api/generated` exclusion,
`.dockerignore:14`). `infra/docker-compose.prod.yml` gains an `admin` service: the `veyq.stack=prod` Traefik
discovery label always present, router labels added **only** when `ADMIN_DOMAIN` is set (own-domain mode);
otherwise a `127.0.0.1`-bound port (localhost-only mode, no precedent in this file — the first `ports:` entry
besides `traefik`'s own). `.github/workflows/container.yml` gains a second `build`+`publish` job pair (mirrors
lines 121–287 exactly, parameterized to `ghcr.io/<owner>/veyq-admin`) — today's workflow hardcodes the image
name in 4 places (locate with `grep -n "github.repository_owner.*veyq\b" .github/workflows/container.yml`);
decide at implement time whether to parameterize the existing job via a matrix or duplicate it, per that
grep's actual site count. Done when: `docker compose -f infra/docker-compose.prod.yml config -q` validates
with `ADMIN_DOMAIN` unset AND set (both modes), and `docker build -f admin-web/Dockerfile .` succeeds locally.

**5 — Coverage: new surface + path fixes across THREE feature areas, not just admin's own.** `scripts/validate_coverage.py`
gains a third surface, `admin-web` (`SURFACES = {"backend", "web", "admin-web"}`), resolved identically to
`web`'s rule but rooted at `ADMIN_WEB = ROOT / "admin-web"` (`scripts/validate_coverage.py:55,60`, and the
resolution branch at `:170`). Then fix every coverage ref a moved file breaks — measured, not assumed:
`grep -rln "src/features/admin/" docs/coverage/` returns **9 files today**, not just the 7 under
`docs/coverage/features/admin/`: `docs/coverage/features/spaces/use-cases/uc.spaces.add-member.yaml:96` cites
`tenants.test.ts` (an **accept**-side test, per Decision 1 — this ref's path changes to wherever Phase 1
relocated it, and its `area` stays `web`, not `admin-web`) and `docs/coverage/features/account/use-cases/uc.account.accept-terms.yaml:19,24`
cites `terms.test.ts` (same — stays `web`, relocated path). The remaining 7, under `docs/coverage/features/admin/`,
split by which side of Decision 1 their cited file landed on: most move to `area: admin-web` with an
`admin-web/`-relative path; any citing an accept-side file stays `area: web`. Done when:
`python3 scripts/validate_coverage.py` is green, and a fresh `grep -rln "src/features/admin/" docs/coverage/`
returns nothing (the directory no longer exists to be cited).

## Phases

- [ ] 1 web — reorganize `features/admin/**` in place (Decision 1) · Done when: `npm run check` green, zero
      behavior change.
- [ ] 2 web — extract the crypto workspace package (Decision 2) · Done when: `npm run check` green.
- [ ] 3 web — create `admin-web/`, move the admin-only files (Decision 3) · Done when: `admin-web`'s own
      check is green, `web/` no longer references `features/admin`.
- [ ] 4 infra — container + docker-compose + CI (Decision 4) · Done when: both compose configs validate, the
      admin image builds locally.
- [ ] 5 coverage — new `admin-web` surface + fix all 9 broken refs across 3 feature areas (Decision 5) ·
      Done when: `python3 scripts/validate_coverage.py` green.

## Bite tests

- **The Server-Owner gate is reachable from `admin-web`'s own root, not only from inside `SettingsHubView`'s
  nav — live defect.** Before Phase 3, `admin-web` doesn't exist, so this is red by construction; after, load
  `admin-web`'s root path as a non-Server-Owner session and assert a refusal (mirroring the backend's own 403,
  per ADR 0065 "authorization is enforced server-side") rather than a blank or crashing page — the router-level
  artifact this CR reconstructs from what was previously only a data-fetch-time check.
- **Setup is reachable with zero prior state (fresh instance, empty DB) via `admin-web`'s localhost-only
  default — live defect proving the sequencing claim in ADR 0065 Decision.** Bring up the compose stack with
  `ADMIN_DOMAIN` unset and `VEYQ_DOMAIN` pointed at a domain with **no DNS record** (simulating "not yet
  propagated"): assert setup still completes via `http://localhost:<admin-port>`. This is the concrete proof
  behind the "no DNS/TLS dependency for first-run setup" claim — an assertion, not a name for one, per this
  repo's own rule against unrun claims.
- **The crypto workspace package is bit-for-bit the same code, not a fork — injection.** After Phase 2, diff
  the extracted package's `accountBirth`/`v1` exports against `git show <pre-phase-2 commit>:web/src/shared/crypto/v1/...`
  — byte-identical modulo the import path change. Then, as the actual bite: inject a one-character change into
  the workspace package's Argon2id parameters and assert **both** `web/`'s and `admin-web`'s test suites that
  exercise account birth go red — proving there is truly one copy, not two that happen to agree today.
- **A coverage ref this CR did NOT touch still resolves — negative control for Decision 5.** After fixing the
  9 known-broken refs, `python3 scripts/validate_coverage.py` must be green; if it is green only because the
  9 fixes happened to be the complete set, that's the claim — assert it by re-running the same
  `grep -rln "src/features/admin/" docs/coverage/` from Decision 5's locator (not a different one) and
  confirming empty, so "green" and "the sweep was complete" are the same evidence, not two unrelated ones.

## Definition of done

`web/`'s and `admin-web`'s own checks green, `docker compose config -q` green for both exposure modes, the
full trust-chain guard glob green (`for f in scripts/validate_*.py; do python3 "$f"; done`), and
`docs/coverage/` consistent across all three affected feature areas (spaces, account, admin) with no dangling
`src/features/admin/` reference anywhere in the tree (`grep -rln "features/admin" .` returns nothing outside
`admin-web/`'s own new tree, if the directory name is reused there — decide the name at implement time so this
grep stays meaningful). The `admin-web` surface addition to `scripts/validate_coverage.py` is itself a
permanent guard extension, not a one-shot check.

## Retrospective

_Filled at implementation close._
