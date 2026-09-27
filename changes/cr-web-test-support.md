---
id: cr-web-test-support
title: web tests mount with the real i18n catalog and fake HTTP failures one way
status: planned
satisfies: []
adrs: []
invariants: []
---

## Goal

Inbox #171, web half. 32 web tests hand-write a stub i18n catalog, and one mocks `t`. 13 stub keys in 7 of
those files no longer exist in the real catalog: `members.copyLink`, from the retired copy-link, is one of
them. So those tests pass against strings the app no longer shows. Separately, `httpError` fakes are redefined
in 10 test files in four different shapes, and two of those shapes cannot carry the error code that
`shared/httpError.ts` decodes.

After this CR:
- a test mounts with the **real** catalog through one imported helper, and rendering a key the catalog lacks
  fails the test;
- HTTP failures are faked by one shared `httpError`;
- a lint rule keeps stub catalogs from coming back.

It is test-only.

## Context & constraints

- **Refined state:** [developer-styleguide](../docs/crosscutting/developer-styleguide.md) §10 Testing, *"Test
  data comes from one fixture per surface, not a local copy"* (written in this refine).
- **Measured on** `main` at `54fe415`, in worktree `claude/beautiful-yonath-d0124b`. Re-run the locators.
- **admin-web is out of scope.** It has 4 stub catalogs, none stale, and 3 fakes. Its `i18n.test.ts` already
  resolves every literal key, and ADR 0065 would give it its own copy of any helper, so it is not worth doing
  now.
- **Neighbours.**
  - `cr-dead-code-sweep` landed 2026-09-27 (inbox #170): `LoginView.test.ts` lost its dead `authPrefill`
    mock, 32 catalog keys went, and web gained `src/i18n/i18n.test.ts` with the reverse "every key is used"
    assertion only — the forward check is still this CR's question.
  - Before the first edit, run `grep -ln 'test.ts' changes/cr-*.md`.

## Decisions

1. **An imported helper in `web/src/test/`, not a global setup file.**
   - 45 files already install i18n per mount, so a global plugin would install it twice.
   - Stryker runs vitest with the same `setupFiles` (`web/vite.config.ts`), so anything global also runs in
     every mutation run.
2. **The helper installs the app's own `i18n` singleton**, with the locale set to `en` on every call. A fresh
   instance built from the same messages would not do, because non-component code reads the singleton
   (`onboardTail.ts`, `unlock.ts`: `grep -rn 'i18n.global' web/src --include='*.ts' | grep -v test`). A fresh
   instance would leave those on another catalog.
   - A key the catalog lacks must **fail the test**. Vue may catch an error thrown from a render function, so
     phase 1's bite decides between a `missing` handler that throws and one that records into a list asserted
     empty after each test. Pick whichever the bite proves actually reddens a test.
3. **Text assertions follow the real catalog.**
   - 157 stub strings in 22 files differ from the real English (measured at refine).
   - A test asserts either through the catalog (`i18n.global.t('key')`) or against the real English. Choose per
     file; either way the assertion now tracks what users read.
   - **Two absence checks are vacuous, so delete them:**
     - `TenantMembersPanel.test.ts` asserts no button reads *"Copy link"*;
     - `InviteMemberDialog.test.ts` asserts the same, with no stub key at all.

     The key was retired, so nothing can render that text, and the checks are green on every tree.
   - `MemoryDetail.optimistic.test.ts`'s *"Uploading"* absence checks become **real** under the real catalog
     (`media.uploadStatus.uploading` = *"Uploading…"*). Keep them.
4. **One `httpError(status, code?)`.** It returns an `Error` carrying a real `Response`, with a JSON body when
   a `code` is given. That is the shape `shared/httpError.ts` reads: it checks `e.response.status`, and for the
   code it calls `res.clone().json()`, with no `instanceof`, because each generated package has its own
   `ResponseError`.
   - It replaces the 10 named definitions.
   - The 19 look-alikes under other names that hand-build `{ response: { status, clone } }` migrate too, where
     they build an HTTP failure.
   - Tests that construct the generated `ResponseError` stay: that is the real class.
5. **The guard is a lint rule, in `web/eslint.config.js`.** For `src/**/*.test.ts` it forbids:
   - importing `createI18n` from `vue-i18n`;
   - `vi.mock('vue-i18n', …)`.

   It runs inside `npm run check`, so it needs no new script.
   - `httpError` gets no guard. A stray local fake costs only its own test, and its author sees the shape they
     wrote. That is why the one-shot proof suffices there.

## Inventory

- **Stub catalogs:** `grep -rl 'createI18n(' web/src --include='*.test.ts'`.
- **Mocked `t`:** `grep -rl "vi.mock('vue-i18n'" web/src --include='*.test.ts'`.
- **Real-catalog tests already** (these move onto the helper for the locator and the missing-key rule):
  `grep -rlE "from '[^']*i18n'" web/src --include='*.test.ts' | xargs grep -l 'i18n.global.locale'`.
- **Named fakes:** `grep -rnE "(function|const|let) +httpError\b" web/src --include='*.test.ts'`.
- **Look-alikes:** `grep -rlE "response: *(\{ *status|new Response)" web/src --include='*.test.ts'`.
- **Test-count baseline:** the "Tests N passed" line of `npx vitest run` in `web/`, run before the first edit.

## Phases

- [ ] 1 helper + bite (Decisions 1, 2 and 4, no migrations yet). Write one throwaway test that mounts a template
      rendering a key the catalog lacks through the helper. It must fail **for the missing key**, and the
      failure message must name the key. Then delete the throwaway test. · Done when: `npm run check` is green
      and the bite's red output is recorded.
- [ ] 2 i18n migration (Decision 3), one commit per feature directory. · Done when: `npm run check` is green,
      the test count is unchanged except for the deleted vacuous assertions (name them), and `grep -rl
      'createI18n(' web/src --include='*.test.ts'` → empty.
- [ ] 3 `httpError` migration (Decision 4). · Done when: `npm run check` is green and the named-fake locator
      returns only the helper.
- [ ] 4 lint rule (Decision 5). Bite it by re-adding a `createI18n` import to one test and reading the lint
      error. · Done when: `npm run check` is green with the rule active and the bite recorded. Inbox #171 (web
      half) → done.

## Definition of done

- Before the first edit, run the baseline and paste its output: `for f in scripts/validate_*.py; do python3
  "$f"; done`, and the vitest "Tests N passed" line.
- `npm run check` in `web/` is green, and so is every trust-chain guard.
- The missing-key bite and the lint bite were both red for the named reason.
- This file is deleted once done. It satisfies no use-case, so there is no coverage change.

## Retrospective

_(filled at implement)_
