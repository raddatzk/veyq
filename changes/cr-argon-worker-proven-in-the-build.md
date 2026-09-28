---
id: cr-argon-worker-proven-in-the-build
title: A production build that loses the Argon2 worker goes red, and a worker that fails at run time says so
status: planned
satisfies: [uc.account.login, uc.account.unlock]
adrs: ["0065"]
invariants: [operator-blind E2EE]
---

## Open for the owner

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

Proposed as a **separate inbox row**, not folded in: four production Argon2 call sites never use the worker. See
*Out of scope*.

## Goal

Inbox #219. `@veyq/crypto/argonWorker` runs the Argon2id derivations in a module Worker and falls back to the
page thread whenever the worker is missing, cannot be built, or dies. The fallback is right: vitest, the e2e
suites and the admin CLI run with no `Worker` at all, and an old tab after an upgrade asks for a chunk the new
image no longer has. But it is silent. Nothing in CI builds, loads or observes the worker. After this CR a build
that stops emitting the worker is red in `npm run check`, and a worker that existed but failed prints one warning.

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
- **Out of scope, measured:** four production call sites run Argon2 on the page thread **with no worker at all**
  (`grep -rnE "v1\.(deriveAuthKeypair|kekPasswordSecret|kekDevicePassword|kekRecovery|sharesPwToken)\(" web/src admin-web/src packages/crypto/src --include='*.ts' --include='*.vue' | grep -v '\.test\.'`):
  - `web/src/features/account/trustDevice/trustDevice.ts:41`: the DEVICE-wrap enrolment on every sign-in with
    *remember me* off.
  - `web/src/features/memory/share.ts:136,148`: share-password create and open.
  - `packages/crypto/src/accountBirth.ts:59,62,64`: three derivations at account birth, in web register/accept
    and admin setup.
  - Not touched here. Proposed as an inbox row.

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
- [ ] 2 the live proof (Decision 4), pasted here. The owner or the agent needs an instance serving a production
      build. None is started by this CR's author without the owner's say.
      · Done when: both halves are recorded with the entry's `name` and `responseStatus` and the console line (or
      its absence).

## Definition of done

- Baseline before the first edit: the guard glob (measured green at refine on `64c1f65`) and `npm run check` in
  `packages/crypto`, `web` and `admin-web`. The last three were **not measured at refine**, and a red there is
  not this CR's.
- Phase 1's bites seen red, then green.
- **No trust-chain guard.** The check is a build assertion beside `verify:csp`, and it runs where the build runs.

**Invariants held:** the derivations are byte-identical on both paths. The existing conformance and sync-fallback
tests are untouched, and nothing new leaves the device. The warning carries no secret.

**Not proven:** that every engine the product supports (WebKit, Gecko) evaluates the IIFE chunk as a module
worker. The live proof covers the one browser it runs in. Name that browser when recording it.

## Retrospective
