---
id: cr-doc-citations-resolve-from-code
title: A use-case or enabler id cited from code resolves, checked by the guard that already does it for ADRs
status: planned
satisfies: []
adrs: []
invariants: []
---

## Open for the owner

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **A guard at all?** The row weighed it both ways against `docs/ai/README.md` rule 3 (*"no guard unless a
   human would notice the defect"*). **Recommendation: yes, because this is not a new guard.**
   `scripts/validate_adr_citations.py` already walks exactly these surfaces for `ADR NNNN`, with the same
   *"must resolve"* rule. It even carries the narrative-register exclusion this needs. Teaching it a second id
   shape costs about fifteen lines, which is the smallest thing rule 3 could ask for. As for "nobody noticed":
   the readers of a code comment's `uc.` id are the agents who follow it, and two of them did notice
   (the row's own account).
2. **Rename the guard?** Once it checks more than ADRs, the name lies. **Recommendation: rename it to
   `validate_doc_citations.py`.** The name has two readers today, `docs/decisions/README.md:9` and inbox
   row #95. Locator: `grep -rn 'validate_adr_citations' --include='*.md' --include='*.py' --include='*.yml' .`,
   with `node_modules` excluded.
3. **Where do the two ids that never existed point?**
   - `uc.platform.app-shell` (`web/src/app/boot.ts:1`, `web/src/features/account/instance/instance.ts:1`)
     describes the boot's setup-status check and the advertised suites. **Recommendation:**
     `uc.admin.setup-server` (its step 1 is *"the client checks setup status"*) plus
     `uc.platform.capability-discovery` for the suites.
   - `uc.account.profile` (`web/src/features/account/profile/ProfilePanel.vue:2`) is the *"Your profile"* panel,
     where you edit your username and real name. **Recommendation:** point it at `en.spaces.member-identity`,
     which owns both fields. Separately, the fact that a user-facing panel has **no use-case** is a docs gap. It
     goes to the inbox (proposed row) and not into this CR.

## Goal

Inbox #122. After this CR, a `uc.`/`en.`/`cc.` id cited in a load-bearing file must name a doc that exists, or
the trust-chain run is red. That covers a code comment, a spec description and an ADR body. The 7 ids that
dangle today are repointed. And the scan reaches the two workspace packages that the ADR half has not seen
since the package split.

## Context & constraints

- **Measured on** `faf9ef4`, in worktree `worktree-agent-a988acad05eff2335`, 2026-09-28. The measurement was a
  scratch script: an id regex `(?<![\w.$/-])(uc|en)\.([a-z0-9-]+)\.([a-z0-9-]+)`, kept only where group 2
  names a directory under `docs/features/`, and checked against the stems of
  `docs/features/*/{use-cases,enablers}/*.md`. Re-run it the same way.
- **The row's numbers still hold, and one site is new.** 73 distinct ids are cited from 413 files across
  `web`, `admin-web`, `packages`, `backend/src`, `contracts`, `scripts` and `infra`/`charts`. **7 dangle, at 12
  sites**; the row counted 11. The new site is `contracts/api/scopes.openapi.yaml:263`, which the row's scope
  (`web/src`, `backend/src/main`, `scripts`) did not read.

  | Cited | Kind | Sites | Repoint to |
  |---|---|---|---|
  | `en.memory.cover` | wrong prefix | `web/src/features/memory/components/MemoryCoverImage.vue:2` | `uc.memory.cover` |
  | `en.memory.date` | wrong prefix | `web/src/features/memory/memoryDate.ts:1` | `uc.memory.date` |
  | `en.spaces.add-group-member` | wrong prefix | `backend/…/spaces/scope/ScopesHandler.kt:298`, `contracts/api/scopes.openapi.yaml:263` | `uc.spaces.add-group-member` |
  | `uc.spaces.personal-scope` | wrong prefix | `web/src/features/spaces/scopes.ts:1` | `en.spaces.personal-scope` |
  | `uc.memory.date-place` | split since | `web/src/features/memory/{useMemoryDate.ts:1, memoryDate.ts:1, components/MemoryDetail.vue:193, components/MemoryDateEditor.vue:2}` | `uc.memory.date` and/or `uc.memory.place`: read each site |
  | `uc.platform.app-shell` | never existed | `web/src/app/boot.ts:1`, `web/src/features/account/instance/instance.ts:1` | Q3 |
  | `uc.account.profile` | never existed | `web/src/features/account/profile/ProfilePanel.vue:2` | Q3 |

- **The anchor is not optional.** Unanchored, the same regex also reads `uc.parent.parent`
  (`scripts/validate_coverage.py:770`, a Python attribute chain). That is the row's *"`.value.trim()`"* trap,
  measured. Anchored on a feature directory, it had **0 false positives** over the 413 files.
- **`cc.` ids:** 5 are cited from code and all resolve (`grep -rhoE "\bcc\.[a-z0-9-]+(\.[a-z0-9-]+)*" web/src
  admin-web/src backend/src packages/crypto/src contracts scripts`). Two of the 5 are written with a trailing
  `.md`.
- **Docs prose:** the same ids in `docs/` + `wiki/` + `changes/` dangle only in `docs/inbox.md`, at rows
  #122 and #139, where an inbox row quotes an id as an example. That is the register the ADR half already
  excludes.
- **A second finding, from reading the guard:** `validate_adr_citations.py`'s `SCAN` names `web/src` and not
  `admin-web/src` or `packages/crypto/src`. Those two cite `ADR NNNN` **129 times** (`grep -rhoE "\bADR
  [0-9]{4}\b" admin-web/src packages/crypto/src | wc -l` → 69 + 60). All 129 resolve today, but nothing checks
  them. The scan list predates `7c121fe` (admin-web) and `d9ef2e9` (the crypto package). This is
  `changes/README.md` § *a guard derives its anchor set from the code*, missed once already.

## Decisions

1. **Generalize `validate_adr_citations.py` into `validate_doc_citations.py`** (Q2). There is one walk and one
   exclusion list, and two id shapes resolve against their own homes:
   - `ADR NNNN` → `docs/decisions/NNNN-*.md`, unchanged.
   - `(uc|en)\.<feature>\.<name>` → a stem under `docs/features/<feature>/{use-cases,enablers}/`, where
     `<feature>` is **derived** from the directories of `docs/features/`.
   - `cc\.<first>…` → a stem under `docs/crosscutting/`, where `<first>` is derived from the existing `cc.*.md`
     stems' first segment. A trailing `.md` is stripped before resolving.
   - The lookbehind above keeps `foo.uc.x` out.
2. **Scan roots:** keep `contracts/`, `backend/src`, `docs/features/`, `docs/crosscutting/` and the ADR
   bodies. Replace the hard-coded `web/src` with **every workspace's `src/`**, read from the root
   `package.json` `workspaces`. That derivation brings in `admin-web/src` and `packages/crypto/src`, and every
   future package. `scripts/` stays out, as today: its docstrings narrate retired ids (`ADR 0009` in this very
   guard).
3. **The limit, for the docstring.** Four things are not checked:
   - An id under a feature directory that does not exist (`uc.nosuchfeature.x`). The anchor that stops the
     false positives is also what makes this blind spot.
   - An id in `scripts/` or in the narrative registers.
   - Whether the cited doc is the *right* one: resolution, never aptness, as `validate_doc_links.py` says of
     itself.
   - History: a doc deleted after a green run.
4. **The repointing** follows the table and Q3. Each `date-place` site is read and pointed at the half it
   actually implements.

## Phases

- [ ] 1 guard, red: Decisions 1–3. · Done when: on the untouched tree the new guard prints **exactly the 12
      sites** of the table (the live defect is its red) and exits 1, and every other `validate_*.py` is green.
      Paste the output here.
- [ ] 2 fix: Decision 4, plus the rename's readers (Q2 locator). The contracts edit is description-only;
      `npm run generate:api` in `web/` and `./gradlew check` in `backend/` confirm nothing else moved.
      · Done when: the guard is green, `./gradlew check` and `npm run check` (`web/`) are green, and
      `validate_guard_gaps.py` is green with the new case below.

## Definition of done

- Before the first edit, run the guard baseline and paste it: `for f in scripts/validate_*.py; do python3 "$f";
  done`. It ends green.
- **Red first:** Phase 1's output.
- **Bites.** Each is snapshot-and-restore, each has its own lever, and each is recorded here:
  - **(a) Wrong prefix, after the fix.** Put `// en.memory.date` in a `web/src` comment. Red, naming the file
    and line.
  - **(b) The derived scan root.** Put `// ADR 9999` in an `admin-web/src` file. Red. Under the old hard-coded
    `SCAN` this was green, which shows the derivation is load-bearing.
  - **(c) The anchor, both ways.** Put `uc.nosuchfeature.x` in a comment: **green**, which is the documented
    limit. Put `uc.memory.nosuchdoc` in one: red.
- **Pin the closed hole** in `scripts/validate_guard_gaps.py` as a *rejected* case: a `web/src` comment citing
  a non-existent `uc.memory.*` id must be refused. Pin (c)'s first half as an *open* gap, so that tightening the
  anchor later flips it visibly.
- Inbox #122 → done. This file is deleted after harvest.

## Retrospective

_(filled at implement)_
