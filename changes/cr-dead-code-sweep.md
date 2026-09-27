---
id: cr-dead-code-sweep
title: Delete the code nothing reaches, on all three surfaces
status: planned
satisfies: []
adrs: ["0065", "0007"]
invariants: [API-first]
---

## Goal

Inbox #170. Each surface carries code that no production path reaches:
- **admin-web:** an HTML sanitiser, 104 of its 198 i18n keys (copies of web flows that ADR 0065 keeps in web),
  and the undo half of `useToast`. (Its `parseKit` is `cr-emergency-kit-codec-in-crypto`'s, see Decision 4.)
- **web:** the instance API client, `inviteLink`, the Setup→login prefill, `startDayOf`, `mediaSwReady`, and
  about 36 i18n keys.
- **backend:** `NodesStore.scopeExists` and `TenantStore.tenantOwnerCount`.

Delete all of it. The no-sharing rule between web and admin-web stays, and deleting the copies nothing uses is
what keeps that rule cheap. Each package's catalog test also gains the reverse assertion: every key is used
somewhere. A lift of a whole group then cannot come back silently.

## Context & constraints

- **Refined state:** [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) (no sharing except
  `@veyq/crypto`) and [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md) (it cites the
  backend method this CR deletes). The decision to delete was taken on 2026-09-23 and stands; do not reopen it.
- **Measured on** `main` at `54fe415`, in worktree `claude/beautiful-yonath-d0124b`, plus the refine commit.
  Re-run every locator. Spot-check the one row the whole CR leans on: that `setAuthPrefill` has no caller in
  `web/`.
- **Behaviour-preserving by construction.** For every item, its locator returns only the definition itself and
  that item's own test.
- **Eligible for same-session implementation** (mechanical, behaviour-preserving, three strong gates). It still
  needs the owner's explicit go.
- **Neighbours.** `cr-web-test-support` rewrites the i18n stubs in `TenantMembersPanel.test.ts` and
  `LoginView.test.ts`, which this CR also touches (a stale `vi.mock` each). The overlap is textual only;
  whichever lands second rebases. Before starting, run `grep -ln '<path>' changes/cr-*.md` for every path
  below.

## Decisions

1. **Delete; do not deprecate or comment out.** Nothing here has a caller to warn.
2. **web stops generating the instance client.**
   - Remove the `instance.openapi.yaml` step from `web/package.json` `generate:api`, and remove `instanceApi`
     plus its import from `web/src/api/client.ts`.
   - The spec stays: admin-web and the backend generate from it.
   - Delete `web/src/api/generated/instance/` before running the check. The directory is gitignored, so a stale
     copy would make a missed import type-check green.
3. **The web prefill is dead as a whole module, not as one branch.**
   - `setAuthPrefill` has no caller in `web/`: Setup moved to admin-web, and admin-web's own copy is live and
     stays.
   - So `web/src/app/authPrefill.ts` goes, along with `LoginView`'s prefill block in `onMounted` (and its
     comment) and `LoginView.test.ts`'s `vi.mock('../app/authPrefill', …)`.
4. **`parseKit` is not this CR's.** `cr-emergency-kit-codec-in-crypto` moves the kit codec into `@veyq/crypto`
   with a fixed-literal test vector and deletes admin-web's copy — the owner's call on #169 (2026-09-23), which
   supersedes the two-literal golden token this decision first carried. This CR lands **after** that one and has
   nothing left to do for the kit.

5. **The reverse catalog assertion is the i18n locator and, afterwards, the guard.**
   - **Predicate.** A key is referenced when either:
     - its full dotted path appears as a quoted literal (`'…'`, `"…"`, `` `…` ``) in a non-test, non-generated
       `.ts`/`.vue` file under `src/`, other than the catalog itself; or
     - it starts with the head of a template literal `` `a.b.${ `` found there.
   - **Why any literal, and not only `t('…')`:** keys travel through variables. In admin-web,
     `TenantDetailPanel`'s `successKey` and `StoreProvisioningPanel`'s `shown.key` carry keys, and
     `passwordHintKey()` returns one.
   - **Order.** Write the assertion first and run it on the untouched tree. It lists every key to delete.
   - **When it lists a key the app does use** (a spelling the predicate does not know), widen the predicate and
     record the spelling in the test's comment. Never allowlist a key: a false positive is explained, not
     excluded.
   - **Where it lives.** In admin-web it joins the existing `src/i18n/i18n.test.ts`, whose header says *three
     assertions*; make that four. In web it goes in a new `src/i18n/i18n.test.ts` carrying only this
     assertion. Whether web also needs the forward check is `cr-web-test-support`'s question, not this CR's.
   - **Delete from `en` and `de` alike.** web's `const de: typeof en` makes the typecheck enforce that.
   - **Its limit, for the test's comment:** a template-literal prefix counts every key under it as used. web
     builds `tenant.join.*` dynamically, so a dead key there is invisible.
6. **`tenantOwnerCount` takes its prose with it.**
   - `TenantStore`'s `isOwner` carries two stacked KDoc blocks. Keep the second one, minus its clause
     *"mirrors `tenantOwnerCount`"*, and delete the first.
   - ADR 0007's tenant-authority bullet says *"mirroring `tenantOwnerCount = ownerCount(t)`"*. **Narrow** the
     sentence by deleting that clause. The rule it states (`isTenantOwner(u, t) = isScopeOwner(u, t)`) is
     unchanged.

## Inventory

Every locator must return nothing but the definition and that item's own test. The one listed exception is the
dead `vi.mock` in `TenantMembersPanel.test.ts`, which goes too.

| Surface | Item | Locator |
|---|---|---|
| admin-web | `src/shared/markdown.ts` (importers, not mentions — the word `markdown` is also a catalog key) | `grep -rn "shared/markdown\|/markdown'" admin-web/src` |
| admin-web | undo half of `useToast` (`showUndo`, `dismissUndo`, `UNDO_GROUP`, `ShowUndoOptions`, header lines) | `grep -rn "showUndo\|dismissUndo\|UNDO_GROUP\|ShowUndoOptions" admin-web/src \| grep -v shared/state/useToast.ts` |
| admin-web | dead i18n keys | Decision 5's assertion, run on the untouched tree |
| web | `instanceApi` + the instance generate step | `grep -rn "instanceApi\|InstanceApi\|generated/instance" web/src web/scripts \| grep -v '/api/generated/'` |
| web | `src/shared/inviteLink.ts` + `inviteLink.test.ts` | `grep -rn inviteLink web/src \| grep -v '^web/src/shared/inviteLink'` (only `TenantMembersPanel.test.ts`'s `vi.mock`) |
| web | `src/app/authPrefill.ts` + the `LoginView` block | `grep -rn "setAuthPrefill\|takeAuthPrefill\|app/authPrefill" web/src` |
| web | `startDayOf` (`features/memory/memoryDate.ts`) | `grep -rn startDayOf web/src` |
| web | `mediaSwReady` (`sw/register.ts`) | `grep -rn mediaSwReady web/src` |
| web | dead i18n keys | Decision 5's assertion, run on the untouched tree |
| backend | `NodesStore.scopeExists` | `grep -rn "\.scopeExists\|fun scopeExists" backend/src` (`MemberRemoveSliceTest` has its own private `scopeExists` and is not a caller) |
| backend | `TenantStore.tenantOwnerCount` | `grep -rn tenantOwnerCount backend/src docs contracts` |

Also run `grep -rn '<symbol>' docs changes scripts contracts wiki` for each symbol. At refine the only non-code
site found was ADR 0007 (Decision 6); `docs/inbox.md` #170 names them all and gets its status update.

## Phases

- [x] 1 admin-web: Decision 5, plus the markdown sanitiser and the undo half. · Done when: `npm run
      check` in `admin-web/` is green, and the new assertion was red on the untouched tree with its list
      recorded in the retrospective.
- [x] 2 web: Decisions 2, 3 and 5, plus `inviteLink`, `startDayOf` and
      `mediaSwReady`. · Done when: `npm run generate:api` + `npm run check` in `web/` are green over a
      `src/api/generated/` holding no `instance/`, and the new assertion was red on the untouched tree with its
      list recorded.
- [x] 3 backend + prose: the two methods and Decision 6. · Done when: `./gradlew check` is green and the
      `@Test` count is unchanged (Definition of done). ADR 0007 is narrowed, and
      `admin-web/CLAUDE.md` stays true. Inbox #170 → done.

## Definition of done

- Before the first edit, run the baseline commands and paste their output into the retrospective:
  - `for f in scripts/validate_*.py; do python3 "$f"; done`
  - `grep -rc '@Test' backend/src/test --include='*.kt' | awk -F: '{s+=$2} END {print s}'`
  - `npx vitest run` in `web/` and `admin-web/`, recording each "Tests N passed" line.
- **The counts may only fall by the tests deleted with their subject**: `inviteLink.test.ts`. Say which
  tests went with which subject.
- All three gates are green, and so are all trust-chain guards.
- **Guards.** The two reverse catalog assertions are the permanent guard, for the one kind of dead code here
  that grows by whole groups. The code deletions are a one-shot proof, and that suffices: a dead function has
  no structural invariant to protect. A general dead-code tool (e.g. knip) was deliberately not adopted, since
  there is no pain yet that it answers.
- No coverage file changes: no acceptance criterion cites a deleted test. The
  `uc.admin.create-tenant.yaml` ref `src/shared/inviteLink.test.ts` is **admin-web's** file and stays.

## Retrospective

**Baseline** (main `f7edfb7`, 2026-09-27, before the first edit): 39/39 guards exit 0 (the run overlapped my
first admin-web edits, so it is a baseline of `f7edfb7` only for the guards that ran first); `@Test` = 796;
web `Tests 1212 passed | 13 skipped (1225)`; admin-web `Tests 119 passed (119)`.
**After:** 39/39 guards exit 0; `@Test` = 796, `./gradlew check` BUILD SUCCESSFUL (824 tests, 0 failures);
web `Tests 1213 passed | 13 skipped (1226)`; admin-web `Tests 120 passed (120)`. No test fell: the one
predicted to go, web's `inviteLink.test.ts`, was already gone (below). Each count rose by the new assertion.

**Red lists on the untouched tree.** admin-web: 43 of 146 keys (`common.save/show/hide/undo/more/unreachable`,
`tenant.manageTitle/manageSub/inviteSent/edit/reassign/reassignTitle/revoked/changeDomain`, and 29
`tenant.join.*`). web: 35 of 818, of which 3 were false positives (`shell.themeSystem/Light/Dark`, built as
`` `shell.theme${Pref}` `` by `ProfileMenu`) — the predicate learned mid-segment heads, the list became 32.

- **Planning — the inventory was stale on arrival in three rows.** web's `inviteLink` + test and the
  `TenantMembersPanel` mock were deleted by `cr-client-hygiene` (`7ccc504`) the day this CR was refined;
  admin-web's catalog shrank 198 → 146 (`291f7c0`, `31fffa8`, `3e9f2ed`), so "104 dead" became 43; and the
  DoD's count prediction named a test that no longer existed. Root cause: refine and a neighbour's implement
  ran the same day, and the CR froze a number beside each locator. The locators themselves held — which is the
  rule working. *Next time:* a CR refined while a neighbour is mid-implement names that neighbour and says
  which rows it may already consume.
- **Planning — the predicate spec carried a known-shape false positive.** Decision 5 defined the template head
  as `` `a.b.${ ``; web's `` `shell.theme${ `` is the one spelling that breaks it, and the refine's "~36" was
  measured with it. The CR's own "widen, never allowlist" order caught it cleanly.
- **Execution — my miss:** a split `git add` failed on an already-`git rm`'d path, and the following
  `git commit` swept in a *later* phase's staged `git rm` (web's `authPrefill.ts`). Undone by `reset --soft`
  to the parent hash. *Next time:* `git rm` stages at once, so stage a phase's deletions only when that phase
  commits, and check `git status --short` between add and commit.
