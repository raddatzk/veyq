---
id: cr-admin-cli
title: Admin CLI — the same container, the same generated client
status: done
satisfies: [uc.admin.create-tenant, uc.admin.suspend-tenant, uc.admin.identify-instance-release]
adrs: ["0065"]
invariants: [operator-blind everywhere]
---

## Goal

A `veyq-admin-cli` entry point ships inside the existing `admin` container/image, wrapping the same nine
Admin API operations `admin-web`'s UI already calls (tenant CRUD + release info), for scripted/ops use — the
second half of [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md), following the web
extraction CR that already landed.

## Context & constraints

- [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)'s CLI decision (amended this refine
  session with the concrete mechanics: fresh-login-per-invocation, esbuild bundling, `node:util.parseArgs`,
  Node added to the `nginx:alpine` runtime stage).
- [admin-web/CLAUDE.md](../admin-web/CLAUDE.md) — **the authority on which operations are genuinely
  Server-Owner-only.** Its own corrected split: tenant CRUD + release info + setup live in `admin-web`; mail
  settings and terms authoring are **tenant-owner** actions (`403 not_tenant_owner`, not the Server-Owner
  gate) and correctly stayed in `web/` — the CLI wraps **only** what's actually in `admin-web` today, not
  ADR 0065's original (corrected-since) list.
- `admin-web/src/features/tenants/tenants.ts` + `admin-web/src/features/instance/release.ts` — the nine
  operations to wrap, already located: `createTenant`, `listTenants`, `resendTenantInvite`,
  `reassignTenantInvite`, `changeTenantDomain`, `revokeTenant`, `suspendTenant`, `reactivateTenant`,
  `getInstanceRelease` (`grep -n "tenantsApi()\.\|instanceApi()\." admin-web/src/features/tenants/tenants.ts
  admin-web/src/features/instance/release.ts` — re-run at implement time, this is a floor not a frozen tally).
- `admin-web/src/features/auth/login.ts` — the login flow the CLI reuses verbatim in spirit (challenge →
  `@veyq/crypto` derive → sign → verify), adapted to a non-reactive, non-browser session holder.
- Invariant at risk: **operator-blind everywhere** — the CLI is exactly as much a "pure Admin-API client" as
  `admin-web` (ADR 0065): no DB, no KEK, no content-key path. Setup is deliberately **excluded** from the CLI
  (Decision below) specifically because it's the one flow that touches key material and needs an interactive
  Emergency-Kit confirmation a CLI can't meaningfully provide.

## Decisions

- **Setup is not a CLI command.** It's a one-time, interactive ceremony (generate keys, force-confirm the
  saved Emergency Kit) — the CLI stays scoped to ongoing administration, matching ADR 0065's Forces
  ("scripted/ops use"), not the cold-boot flow. If this is ever revisited, it's a new decision, not an
  oversight to fix here.
- **Auth: fresh login per invocation, credentials from the environment, nothing written to disk.**
  `VEYQ_ADMIN_EMAIL` + `VEYQ_ADMIN_PASSWORD` env vars (supplied to the container by the operator — a Server
  Owner credential, same as ADR 0065's Forces already named); each command run logs in, gets an access
  token, makes the one call, exits. No refresh, no token cache, no "remember me" — a session that outlives
  one process is a new kind of credential-at-rest this ADR's whole point is to avoid multiplying. The
  Argon2id cost per invocation (~1–3s, `ARGON = { t: 3, m: 32768 }` over pure-JS `@noble/hashes`) is accepted
  explicitly, not a defect to optimize away later without a fresh decision.
- **The same `@veyq/crypto/argonWorker` import as `admin-web`'s login, unmodified.** It already degrades to
  the synchronous path when `typeof Worker === 'undefined'` (`argonWorker.ts`'s own comment names
  "vitest/node" as this exact case) — a plain Node.js CLI process needs zero crypto-path branching of its own.
  Confirm this holds rather than assuming: the CR's bite test (below) is exactly this claim, run for real.
- **Command surface**: `veyq-admin tenant create|list|resend|reassign|change-domain|revoke|suspend|reactivate`
  + `veyq-admin release`, each a thin wrapper: parse args → call the same generated `tenantsApi()`/
  `instanceApi()` factories `admin-web`'s UI uses → print the JSON result (or a one-line error) → exit code
  0/1. No new business logic — the CLI's own code is argument parsing and output formatting, nothing else.
- **`node:util.parseArgs`, not a third-party framework.** Node-core, zero dependencies, always maintained —
  nine subcommands over flat flags is well within what it handles; a framework (commander/yargs) would be a
  dependency bought for nothing at this size.
- **Bundled with esbuild** (already resolvable as a transitive Vite dependency — confirm with
  `npm ls esbuild --workspace=admin-web` before adding it as a direct devDependency, since a transitive
  version isn't a stable contract to build against) into a single Node-executable file
  (`admin-web/dist-cli/cli.js`) at image-build time — never `tsx`/`ts-node` at container runtime, which would
  make TS transpilation a runtime dependency of a shipped feature.
- **`BACKEND_URL` is the one shared source of truth**, reused as-is (`admin-web/docker-entrypoint.d/30-veyq-backend-url.sh`
  already defaults it to `http://app:8080`) — the CLI reads the same env var nginx's entrypoint script does,
  rather than inventing a second variable that could drift from it. The CLI talks to the backend **directly**
  (it's a server-side process in the same container), never through its own nginx.
- **Runtime image gains Node.js.** `admin-web/Dockerfile`'s `runtime-nginx` stage
  (`FROM nginx:1.29-alpine`, `admin-web/Dockerfile:37`) installs `nodejs` via `apk add --no-cache nodejs` — a
  real, named cost (a second application runtime on the image the extraction was partly meant to keep
  minimal), accepted because ADR 0065 already decided the CLI ships in this container, not a separate one.
  The Dockerfile's own comment at line 35–36 ("a Node runtime here would put a second application server on
  the highest-privilege origin") needs a one-line correction alongside this change — it was true before this
  CR and stops being true after, and a stale comment asserting the opposite of the code next to it is worse
  than no comment.

## Phases

- [x] 1 admin-web — `src/cli/` entry point + the nine subcommands + login adapter (non-reactive session
      holder) · Done when: `npm run check` (admin-web) green, including unit tests per subcommand (mock the
      generated API clients, assert the right operation + args + exit code).
- [x] 2 admin-web — esbuild bundle script (`build:cli` in `package.json`) · Done when:
      `node admin-web/dist-cli/cli.js --help` runs standalone (no `node_modules` resolution needed beyond
      what's bundled in — confirm by running it from a directory with no `node_modules` present).
- [x] 3 infra — `admin-web/Dockerfile`'s `runtime-nginx` stage installs `nodejs`, copies the bundle in, fixes
      the now-stale "no Node runtime" comment · Done when: `docker build -f admin-web/Dockerfile .` succeeds
      and `docker run --rm --entrypoint node <image> /path/to/cli.js --help` prints usage.
- [x] 4 coverage — add CLI test refs (`area: admin-web`) to the existing criteria in
      `uc.admin.create-tenant.yaml`, `uc.admin.suspend-tenant.yaml`, `uc.admin.identify-instance-release.yaml`
      — no new acceptance criteria, since the CLI exercises the same already-covered behavior through a
      second caller, not new behavior · Done when: `python3 scripts/validate_coverage.py` green.

## Bite tests

- **The Argon2id degrade-to-sync claim, run for real — live defect if wrong.** Before writing any CLI-specific
  crypto handling, write a one-line Node script that imports `@veyq/crypto/argonWorker` and calls
  `deriveAuthKeypairAsync` outside any bundler/browser context, and confirm it resolves correctly (proving
  `typeof Worker === 'undefined'` really is true in plain Node and the fallback really fires) — before
  assuming the CLI needs zero crypto-path work, per this repo's own rule against unrun claims about
  environment behavior.
- **The bundle is genuinely standalone — live defect.** Run `node dist-cli/cli.js` from a directory with
  `node_modules` deleted or renamed. If esbuild missed marking something external that should have been
  bundled (or vice versa), this fails with a `MODULE_NOT_FOUND` the container build's own `npm ci` layer
  would otherwise silently paper over.
- **Wrong or missing `VEYQ_ADMIN_PASSWORD` fails loudly, not silently.** A malformed/absent credential must
  produce the same generic 401-shaped message the web login does (non-enumerable, per `login.ts`'s own
  comment) with a non-zero exit code — not an uncaught stack trace, not a silent empty success.
- **`admin-web/CLAUDE.md`'s hoisting trap applies here too.** After phase 1, run the actual
  `docker build -f admin-web/Dockerfile .` before trusting a local `npm run check` green — the same
  workspace-hoisting mirage that bit `jsqr` (green locally, red in the container) can hide a CLI-only
  dependency the local install happened to satisfy from `web/`'s hoisted tree.

## Definition of done

`admin-web`'s own check green, the container build succeeds and the bundled CLI runs standalone (both bite
tests above passing), the full trust-chain guard glob green
(`for f in scripts/validate_*.py; do python3 "$f"; done`), and `docs/coverage/` updated for the three
`satisfies:` use-cases with the new CLI test refs.

## Retrospective

**Planning — the coverage phase was written from the use-case list, not from the coverage files.** Phase 4
said "add CLI test refs to the existing criteria" in three files. Two took refs. The third,
`uc.admin.identify-instance-release.yaml`, could not: it is `status: planned` and declares `admin-web` a
**gap**, and `validate_coverage.py` fails a surface that is both covered and a gap — correctly, since the
file's own reasoning ("no CI run has ever executed here") applies to the CLI test exactly as it does to the
panel test. The ref went into the gap prose instead. Root cause: refine read the `satisfies:` list and
assumed three files of the same shape. **Fix:** a coverage phase names each file's *state* — implemented vs
planned, which areas are already gaps — not just its name, because that state decides whether a ref is even
legal.

**Planning — the CR told me to re-measure the operation tally but not the dependency premise.** It said, in
bold, to re-run the locator for the nine operations (which held: still exactly nine). It did not say to
re-check ADR 0065's "esbuild is already a transitive Vite dependency" — and that was false: Vite 8 bundles
with rolldown, `npm ls esbuild` is empty across the whole tree, so esbuild is a genuine new devDependency.
The decision survives on its own merits and the ADR now carries a measured amendment, but the *cost* it was
accepted under had changed. Harvested as a rule in `changes/README.md`.

**Execution — I shipped a bundle that could not run, and only running it caught that.** The esbuild
`--define` lives inside a JSON string inside a shell command, the shell ate a quoting layer, and esbuild
happily defined `process.env.NODE_ENV` to the bare identifier `production`. The build printed a size and
exited 0; the artifact died on its first line. `npm run check` would never have caught it. Also in this
family: `dist-cli/` was a new build output that nothing knew about, so eslint linted the bundled copy of
every dependency and `.gitignore`/`.dockerignore` would have shipped it. **Fix:** when adding a build output
directory, grep for the existing one (`dist`) across every ignore file and config in the *same* edit — and
never call a build phase done on the build exiting 0. Harvested as a rule.

**Process — an hour lost to a build I had made unobservable.** I ran `docker build ... | tail -25`, which
buffers everything until the pipe closes, so a hang looked exactly like progress for forty minutes. The real
cause was machine-local (the macOS keychain credential helper hanging, which also stalled every `docker
pull`); the reason it cost so much was mine. **Next time:** any long external command whose failure mode is
"hangs" gets `--progress=plain` (or equivalent) into a log file, watched, from the first invocation — never a
piped tail. Nothing here was a convention we had already agreed and ignored; the working rules (locator over
tally, bite tests before trusting a design, guards green per phase, commits straight to `main` by phase) were
followed, and the bite tests earned their keep three times: the argon fallback, the standalone bundle, and
the mutation checks that made each load-bearing CLI assertion go red on demand.
