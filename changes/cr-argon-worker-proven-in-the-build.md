---
id: cr-argon-worker-proven-in-the-build
title: A production build that loses the Argon2 worker goes red, and a worker that fails at run time says so
status: planned
satisfies: [uc.account.login, uc.account.unlock]
adrs: ["0065"]
invariants: [operator-blind E2EE]
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Open — added 2026-10-01 by the inbox #230 fold, not yet answered.** The CR below is written as if both
recommendations are taken; the go given for questions 1–2 does not cover them.

3. **Do the four page-thread sites join the worker here, and in which shape?** Re-measured on `7a34c57` (see
   *The four page-thread sites*): `trustDevice.ts:41`, `share.ts:136,148`, `accountBirth.ts:59,62,64` still call
   the synchronous derivation. One production-params derivation took 1.2–2.1 s on this Mac during the
   2026-10-01 refine wave (other agents building; the quiet 2026-09-28 figure was ~0.8 s), so account birth
   freezes the page for three of them — ~2.4 s quiet — while the user watches a busy state that cannot paint.
   - (a) **All four, one door each.** `trustThisDevice` awaits the existing `deviceKekAsync`; a new
     `sharesPwTokenAsync` (and a `sharesPwToken` op in `argon.worker.ts`) serves the share-password create and
     open; `buildAccount` itself becomes `async` and awaits `unlockKekAsync`, `recoveryKekAsync` and
     `deriveAuthKeypairAsync` — `accountBirth.ts` sits in the same package as `argonWorker.ts`, so it reaches the
     worker by a relative import, and every consumer bundles the same chunk (Decision 1's check is unchanged).
   - (b) As (a), but keep a synchronous `buildAccount` for the node seed scripts and add `buildAccountAsync`.
   - (c) Only `trustDevice` and `share`; account birth is once per account and may block.

   **Recommendation: (a).** Birth is the heaviest of the four, and both of its screens (web `TenantJoinView`,
   admin-web `SetupView`) show a busy state that the block keeps from painting. (b) leaves the page-thread door
   open beside the worker door, which is exactly what #230 found four times over; the node callers lose
   nothing, since they have no `Worker` and take the designed fallback, and the `.mts` scripts already use
   top-level `await`. Cost of (a): 18 `buildAccount(` call sites gain an `await` — 2 production, 4 seed-script,
   12 test/harness (locator in *The four page-thread sites*) — and `buildCreateServerOwnerRequest` in admin-web
   becomes `async`.
4. **What keeps a fifth page-thread site from appearing?** Nothing does today: #230 was found by reading.
   - (a) **An ESLint `no-restricted-syntax` door** (`veyq/argon-through-the-worker`), the
     `veyq/one-sign-out` shape: a `MemberExpression` on `v1` naming any of the five Argon2 entry points
     (`deriveAuthKeypair`, `kekPasswordSecret`, `kekDevicePassword`, `kekRecovery`, `sharesPwToken`) is an error
     in production source, plus `no-restricted-imports` on the same five as named imports of `@veyq/crypto/v1`
     or `./v1`. The rule object is written once in `packages/crypto` and imported by the three ESLint configs
     (`web`, `admin-web`, `packages/crypto`), so the five names cannot drift between them. Exempt: `v1.ts`,
     `argonWorker.ts`, `argon.worker.ts`, tests, `src/test/**`, `scripts/**`.
   - (b) A vitest test in `packages/crypto` that greps both consumers' `src`.
   - (c) Nothing; the review is the guard.

   **Recommendation: (a).** It is the repository's existing form for "only through this door"
   (`veyq/one-sign-out` in `web/eslint.config.js`, and `cr-one-settings-door-in-lint`), it matches the AST rather than text, and `npm run lint` already
   parses every file it needs. (b) reaches across the package boundary ADR 0065 draws. **Its limit, stated in
   the rule's comment:** a re-export under another name, or a `v1` namespace imported under another name,
   escapes it — the same class of limit `veyq/one-sign-out` states.

**Answered by the owner, 2026-10-01: questions 1–2 below — every recommendation taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

Refined from inbox #219 on 2026-09-28. **Nothing below is answered yet.**

1. **Which proof?** The row inferred that a worker that fails to load would go unseen. The refine **measured**
   one concrete way to lose it. Split `new Worker(new URL('./argon.worker.ts', import.meta.url), …)` into
   `const url = new URL(…); new Worker(url, …)`, and `vite build` still exits 0. It emits **no** worker chunk
   and inlines the raw **TypeScript source** into the main chunk as a `data:video/mp2t;base64,…` URL (`.ts` is
   read as an MPEG transport stream). `npm run check` stays green. In a browser that URL cannot run: it is TS,
   and `worker-src 'self'` refuses `data:`. The fallback then takes over without a word. What does this cost?
   One production-params derivation takes **~0.8 s** of blocked page thread (node 23 / V8 on this Mac, 810 /
   794 / 834 ms). A sign-in is two derivations, so ~1.6 s frozen, every time, on every device.
   - (a) **A dist check in both consumers' `npm run check`**, plus a **one-time warning** when a worker that
     existed fails, plus a **one-shot live proof** in a real browser, recorded here.
   - (b) (a) without the warning.
   - (c) A real-browser test harness (Playwright or Vitest browser mode) that signs in and asserts the worker
     answered.

   **Recommendation: (a).** The dist check catches the failure that was measured, where it happens (the build),
   at the cost of one short script. The warning is what turns *"loads"*, which no CI here can see, into
   something anyone with devtools can check. The live proof then needs no instrumentation. (c) proves the most,
   but it is a new dependency and a new CI surface for one assertion, and nothing else in the repo runs a
   browser today (`grep -rln -i "playwright\|puppeteer\|webdriver" --include=package.json .` → nothing).
2. **Where does the dist check live?** `web/` and `admin-web/` both bundle the worker. Both import
   `@veyq/crypto/argonWorker`, and the last `admin-web` build in the main checkout carries an `argon.worker-*.js`
   too.

   **Recommendation: once, in `packages/crypto/scripts/assert-argon-worker-in-dist.mjs <distDir>`, called by
   both consumers' `check` after their build.** The property belongs to the module that makes the
   `new Worker(new URL(…))` call. A copy per consumer is the drift the Emergency Kit codec already paid for
   (`packages/crypto/package.json` `//`). It stays inside ADR 0065's line: crypto, not UI. The web precedent is
   `.mjs` (`web/scripts/assert-csp-policy-in-dist.mjs`), not Python, because it runs inside `npm run check` on a
   fresh `dist/` that the Python trust-chain never builds.

The four production Argon2 call sites that never use the worker were proposed as a separate row (#230) and
**folded in on 2026-10-01** — questions 3 and 4, Decisions 5–7, phase 1b.

## Goal

Inbox #219. `@veyq/crypto/argonWorker` runs the Argon2id derivations in a module Worker and falls back to the
page thread whenever the worker is missing, cannot be built, or dies. The fallback is right: vitest, the e2e
suites and the admin CLI run with no `Worker` at all, and an old tab after an upgrade asks for a chunk the new
image no longer has. But it is silent. Nothing in CI builds, loads or observes the worker. After this CR a build
that stops emitting the worker is red in `npm run check`, and a worker that existed but failed prints one warning.
And (inbox #230, folded 2026-10-01) the worker is the only way any production code reaches Argon2: the four
call sites that bypass it today — device trust, share password create and open, account birth — go through it,
and a lint door keeps a fifth from appearing.

## Context & constraints

Measured on `64c1f65` (2026-09-28). The build was run in the refine worktree after `npm ci`. Each line can be
re-run:

- **How a production build bundles it.** `cd web && npx vite build` → rc 0, and
  `ls dist/assets | grep argon` → `argon.worker-Cu6Ft9kv.js` (43.65 kB). The chunk starts `(function(){`, an
  IIFE, which is Vite's default `worker.format`. It holds no `import` (`grep -oE "(^|[;}])import[ {*]"` → nothing)
  and one `unknown_op`, the dispatcher's sentinel from `argon.worker.ts`. The main chunk references it as
  `new Worker(new URL(\`/assets/argon.worker-Cu6Ft9kv.js\`,``+import.meta.url),{type:\`module\`})`
  (`grep -o 'new Worker([^)]*)[^)]*)' dist/assets/main-*.js`). An IIFE is loaded as a **module** worker, which
  runs it in strict mode. That is harmless for `@noble` (ESM, strict already), but no test proves that it
  evaluates. The live proof does.
- **The measured way to lose it** (Open question 1). The lever: `argonWorker.ts`'s constructor line is split
  into `const url = new URL('./argon.worker.ts', import.meta.url)` + `new Worker(url, { type: 'module' })`.
  Result: build rc 0, `ls dist/assets | grep argon` → nothing, and the main chunk carries
  `new URL(\`data:video/mp2t;base64,Ly8gV2ViIFdvcmtlci…\`` (the base64 decodes to `argon.worker.ts`'s own
  header comment). The tree was restored from a snapshot. `git status` was clean for `packages/` after.
- **The CSP the worker runs under.** `grep -n "worker-src" web/src/shared/security/csp.ts` → `'self'`, and the
  built `dist/content-security-policy.txt` carries `worker-src 'self'`. The backend shell puts the policy
  on every non-`/api` response, the worker's own script included (`AppShellRoutes.onHeadersEnd`), with
  `nosniff`, so a wrong MIME type is refused. admin-web: `default-src 'self'; script-src 'self'`
  (`admin-web/src/shared/security/csp.ts`).
- **The fallback paths**, all silent (`packages/crypto/src/argonWorker.ts`):
  - `typeof Worker === 'undefined'` → latch, page thread. Node: vitest, `run_web_e2e.py`'s suites, and the admin
    CLI. `admin-web/src/cli/adminSession.ts` says so on purpose.
  - The constructor throws → latch.
  - The worker's `error` event (load failure, crash) → latch, and every pending request finishes on its sync
    fallback.
  - `postMessage` throws → that one request finishes on the page thread.
  - Tested: only the `error`-event path (`argonWorker.test.ts`, the `DyingWorker` stub). No test observes that
    a fallback *happened*: the suite asserts only that the bytes match.
  - **Not measured:** whether a CSP-refused or 404 module worker surfaces as the `error` event in every engine,
    or as a constructor throw. Both are handled, so the answer changes nothing here.
- **What `npm run check` checks after the build today.** `grep -n '"check"' web/package.json admin-web/package.json`:
  web runs `verify:no-backdoor` and `verify:csp`, and neither looks at workers. admin-web runs `build` and
  `build:cli` with no dist assertion.
- **The consumers.** `grep -rn "Async(" web/src admin-web/src --include='*.ts' | grep -v '\.test\.' | grep -i 'kek\|keypair'`:
  `login.ts`, `unlock.ts` (floor + device), `coldCredential.ts`, `recovery.ts`, `changePassword.ts`,
  `rotateKeys.ts`, admin-web `features/auth/login.ts`.

### The four page-thread sites (inbox #230, folded 2026-10-01; measured on `7a34c57` unless marked)

Locator for the whole set — it must list only `argonWorker.ts`, `argon.worker.ts` and `src/test/` after phase 1b:
`grep -rnE "v1\.(deriveAuthKeypair|kekPasswordSecret|kekDevicePassword|kekRecovery|sharesPwToken)\(" web/src admin-web/src packages/crypto/src --include='*.ts' --include='*.vue' | grep -v '\.test\.'`
(today it also lists `web/src/test/e2eBootstrap.ts:102`, a harness line, exempt). `v1.ts`'s five functions
are the only `argon2id(` callers in production source (`grep -rn "argon2id(" packages/crypto/src web/src admin-web/src --include='*.ts' | grep -v '\.test\.'`),
so the five names are the whole entry set.

- **`web/src/features/account/trustDevice/trustDevice.ts:41`** — `v1.kekDevicePassword` inside the already
  `async` `trustThisDevice`. Reached from `LoginView.vue`'s `enrolConvenienceWrap` when *remember me* is off and
  the browser supports device trust (`grep -n 'trustThisDevice' web/src/views/LoginView.vue`), after the unlock
  succeeded, while the screen routes on. `deviceKekAsync` exists with the same signature: a one-line swap.
- **`web/src/features/memory/share.ts:136,148`** — `v1.sharesPwToken` in `buildPasswordFields` (create and
  change, `share.ts:251,329`) and `derivePasswordToken` (open, `ShareView.vue:339`, the anonymous viewer).
  `ShareView.vue` already yields 10 ms "to let the busy state paint before the (blocking) Argon2id" — the block
  is known and accepted there today. **The worker has no op for it** (`grep -n "op ===" packages/crypto/src/argon.worker.ts`
  → four ops), and the params are the same `v1.ARGON` (`grep -n 'export const sharesPwToken' -A1 packages/crypto/src/v1.ts`).
- **`packages/crypto/src/accountBirth.ts:59,62,64`** — three derivations in the **synchronous** `buildAccount`.
  Production callers: web `TenantJoinView.vue:125` (invite acceptance) and admin-web
  `features/setup/setupServer.ts:23` (first-run setup, via the sync `buildCreateServerOwnerRequest`). All 18
  call sites: `grep -rn "buildAccount(" web/src web/scripts admin-web/src packages/crypto/src | grep -v 'function buildAccount'`.
  **Can it reach a worker?** Yes, structurally: `argonWorker.ts` is a sibling module in the same package,
  its `new Worker(new URL('./argon.worker.ts', import.meta.url))` resolves relative to itself, not to its
  importer, and both consumers already bundle that chunk (Decision 1). What stands in the way is only that
  `buildAccount` is synchronous.
- **admin-web:** its sign-in already goes through `deriveAuthKeypairAsync`; setup is its only page-thread site.
  Its CSP has no `worker-src`, so it falls back to `script-src 'self'` (CSP3 fallback chain — remembered, not
  measured here), and the 2026-09-28 build measured an `argon.worker-*.js` in its `dist/`. The admin **CLI**
  imports neither `accountBirth` nor `setupServer` (`grep -rn "accountBirth\|setupServer" admin-web/src/cli` →
  nothing) and has no `Worker`, so it is unaffected either way.
- **The worker-side dispatcher is untested.** `argonWorker.test.ts` covers the sync fallback and a
  `DyingWorker`; nothing loads `argon.worker.ts` (`grep -n "argon.worker" packages/crypto/src/*.test.ts` →
  nothing). An op the front door sends but the dispatcher lacks is answered `ok: false, error: 'unknown_op'`,
  which `dispatch` **rejects** rather than falling back — so a missing `sharesPwToken` branch would fail every
  share-password create and open in a browser while every node test stays green (they never construct a
  `Worker`). Read from the code, not run.
- **Tests that mock the worker module:** `grep -rn "vi.mock('@veyq/crypto/argonWorker'" web/src admin-web/src`
  → `kdfProfile.test.ts`, `rotateKeys.test.ts`. Neither reaches `buildAccount`, `share.ts` or `trustDevice.ts`
  today; an implementer re-checks this after the swap, since a partial mock factory would now intercept them.
- **Cost, re-measured:** one `argon2id` at `t3 m32768 p1` in node 23.11.0, three runs: 2095 / 1181 / 1295 ms
  under the 2026-10-01 wave's parallel load (the scratch probe `argon_time.mjs`). The quiet 2026-09-28 figure,
  ~0.8 s, stays the one to quote; this one says only that a loaded machine makes it worse.

## Decisions

1. **The dist check** (Open question 2). `packages/crypto/scripts/assert-argon-worker-in-dist.mjs <distDir>` exits 1
   unless:
   - (i) exactly one `assets/argon.worker-*.js` exists;
   - (ii) it contains `unknown_op`, which ties the chunk to `argon.worker.ts` and not just to a file of that name;
   - (iii) some other `assets/*.js` references it by its emitted file name.

   Its docstring names what it does **not** prove: that a browser evaluates the chunk, or that the CSP admits it
   at run time. Both consumers get `"verify:argon-worker": "node ../packages/crypto/scripts/assert-argon-worker-in-dist.mjs dist"`,
   and `check` gains it after `build`. In admin-web it goes after `build` and before `build:cli`.
2. **One warning when a worker that existed fails.** In `argonWorker.ts`, the constructor `catch` and the `error`
   listener each call a module-level `warnOnce()`:
   `console.warn('[veyq] Argon2 worker unavailable — deriving on the page thread')`. There is **no** warning on
   `typeof Worker === 'undefined'`, which is the designed node path, and none on a per-request `postMessage`
   throw, which does not retire the worker. The message holds no secret, no parameters and no error text.
3. **The fallback itself stays exactly as it is.** An upgraded image that no longer has the old tab's chunk is
   the case it exists for. The warning then fires, and that is correct.
4. **The live proof**, one-shot and recorded in this CR, not automated. On an instance serving a production
   build (the owner's, or the agent's on explicit permission):
   - Sign in. `performance.getEntriesByType('resource').filter((e) => /argon\.worker-/.test(e.name))` gives
     exactly one entry with `responseStatus` 200, and the console carries no `[veyq] Argon2 worker` line.
   - Block `*argon.worker*` in devtools (request blocking), reload and sign in. The warning appears once and the
     sign-in still succeeds.
   - (#230 fold) In a fresh tab, open a password-protected share link and unlock it: the same resource entry
     appears, with no warning — the anonymous viewer reaches the worker too. Sign in with *remember me* off:
     no warning during the device enrolment.

5. **Every production Argon2 derivation goes through `argonWorker`** (question 3, inbox #230).
   - `argon.worker.ts` gains the op `sharesPwToken`, and `argonWorker.ts` the front door
     `sharesPwTokenAsync(password, salt, params?)`, the same shape as the other four.
   - `share.ts`: `buildPasswordFields` and `derivePasswordToken` become `async` and await it; their two
     callers already run in `async` functions. `ShareView.vue`'s 10 ms paint yield and its "(blocking)" comment
     go, since the derivation no longer blocks.
   - `trustDevice.ts:41` awaits `deviceKekAsync`.
   - `buildAccount` becomes `async` and awaits `unlockKekAsync`, `recoveryKekAsync` and `deriveAuthKeypairAsync`
     (relative import `./argonWorker`). No synchronous twin. The three run one after another on the one worker;
     running them in parallel would need a pool, which is not this CR.
   - **No new `satisfies:`.** Where the derivation runs is a property of `@veyq/crypto/argonWorker`, not of
     share-password, device trust or setup; filing it under each would be the duplicate-claim tell
     `changes/README`'s rule *a criterion belongs to the use-case that OWNS the property* names.
6. **The dispatcher answers every op the front door sends** — a `// @vitest-environment node` test that loads
   `argon.worker.ts` with a stubbed `self` (captures the `message` listener and `postMessage`), sends each op
   `argonWorker.ts` dispatches with `testArgon` params, and asserts `ok: true` with bytes equal to the sync
   path. The op names live once, as `ARGON_OPS` in a small `argonOps.ts` both files import (never
   `argon.worker.ts` importing `argonWorker.ts`, which would pull the `new Worker` call into the worker chunk);
   `dispatch` takes an `ArgonOp`, so tsc refuses an op outside the list, and the test iterates `ARGON_OPS` — a
   sixth op added to the list without a dispatcher branch turns it red.
7. **The lint door** (question 4): the rule object lives once in `packages/crypto` (a plain `.js` export beside
   `eslint.config.js`) and is spread into the three configs. Its comment states the limit question 4 names.

## Phases

- [ ] 1 crypto + web + admin-web: Decisions 1 and 2.
      Locators: `grep -n "new Worker\|addEventListener('error'\|catch {" packages/crypto/src/argonWorker.ts`;
      `grep -n '"check"' web/package.json admin-web/package.json`.

      **Order: red first.** The build-side defect is not live today, so the check's red comes from its lever.
      The warning is absent today, so its test is red on the untouched module.

      **Bites** (each is snapshot-and-restore, and its red is recorded in the commit body):
      - **The measured lever.** Split the constructor as in *Context*. `npm run build` stays rc 0 and
        `verify:argon-worker` goes **red** on (i), in both `web/` and `admin-web/`. Restore, and it is green.
      - **(iii) on its own.** In a scratch copy of a green `dist/`, rewrite the reference in the main chunk to
        another name. The check is red on (iii) and not on (i).
      - **(ii) on its own.** In the scratch copy, overwrite the worker chunk with `;`. Red on (ii).
      - **The warning** (`argonWorker.test.ts`, with `console.warn` spied):
        - the existing `DyingWorker` case also asserts **exactly one** warning across **two** requests. That is
          red today. The lever is deleting the `warnOnce()` call.
        - a new case with a `Worker` stub whose constructor throws: one warning, and the bytes are still
          identical.
        - the node-default cases (no `Worker` global): **zero** warnings. The lever is calling `warnOnce()` in the
          `typeof Worker === 'undefined'` branch, which turns it red.
      · Done when: `npm run check` is green in `packages/crypto`, `web` and `admin-web`, and every red above was
      seen.
- [ ] 1b crypto + web + admin-web: Decisions 5–7 (inbox #230). After phase 1, so the warning already covers
      the sites this phase moves onto the worker.
      Locators: the set locator in *The four page-thread sites*; `grep -n "op ===" packages/crypto/src/argon.worker.ts`;
      `grep -rn "buildAccount(" web/src web/scripts admin-web/src packages/crypto/src`.

      **Order: red first.** The dispatcher test (Decision 6) and the lint door (Decision 7) are written before
      the swap: the lint door is red on the untouched tree, naming the six lines of the set locator; the
      dispatcher test is green on the four existing ops and goes red the moment `sharesPwToken` joins
      `ARGON_OPS`, until its branch exists.

      **Bites** (snapshot-and-restore, reds recorded in the commit body):
      - **The dispatcher.** Delete the `sharesPwToken` branch from `argon.worker.ts` → the dispatcher test is red
        naming that op, while `argonWorker.test.ts`'s node cases stay green — the gap Context names, seen.
      - **The lint door, both spellings.** Re-introduce `v1.kekDevicePassword(` at `trustDevice.ts:41` → red;
        replace it with `import { kekDevicePassword } from '@veyq/crypto/v1'` and a bare call → red; the same
        line in a `*.test.ts` → green (exempt).
      - **Byte identity.** `sharesPwTokenAsync` equals `v1.sharesPwToken` on the sync fallback (a new case beside
        the two in `argonWorker.test.ts`); `buildAccount`'s existing `accountBirth.test.ts` cases pass with an
        `await` and no other change — the wraps still open under the sync derivations.
      - **Mocks.** Re-run `grep -rn "vi.mock('@veyq/crypto/argonWorker'" web/src admin-web/src` and confirm no
        mocked factory now sits under a `buildAccount`, `share.ts` or `trustDevice.ts` path; a test that needs it
        gets the real module, not a wider stub.
      · Done when: the set locator lists only `argonWorker.ts` and `argon.worker.ts` (plus the exempt
      `src/test/` harness), `npm run check` green in all three packages, the seed scripts still run under node
      (`web/scripts/seed-owner.mts` is the cheapest; it needs a running backend, so it is the implementer's
      call whether to run one or to rely on `bootstrap-user.mts`'s next use), every red above seen.
- [ ] 2 the live proof (Decision 4), pasted here. The owner or the agent needs an instance serving a production
      build. None is started by this CR's author without the owner's say.
      · Done when: both halves are recorded with the entry's `name` and `responseStatus` and the console line (or
      its absence).

## Definition of done

- Baseline before the first edit: the guard glob (measured green at refine on `64c1f65`) and `npm run check` in
  `packages/crypto`, `web` and `admin-web`. The last three were **not measured at refine**, and a red there is
  not this CR's.
- Phase 1's and phase 1b's bites seen red, then green.
- **No trust-chain guard.** The check is a build assertion beside `verify:csp`, and it runs where the build runs;
  the door (Decision 7) is a lint rule, and runs where the lint runs.
- `docs/inbox.md` #219 and #230 → done.

**Invariants held:** the derivations are byte-identical on both paths. The existing conformance and sync-fallback
tests are untouched, and nothing new leaves the device. The warning carries no secret.

**Not proven:** that every engine the product supports (WebKit, Gecko) evaluates the IIFE chunk as a module
worker. The live proof covers the one browser it runs in. Name that browser when recording it.

## Retrospective
