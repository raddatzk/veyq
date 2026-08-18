---
id: cr-scope-erasure-fk-closure
title: A group erasure completes over the whole FK closure, and one stuck scope no longer halts the sweep
status: done
satisfies: [uc.spaces.delete-group]
adrs: ["0014", "0021", "0029"]
invariants: [operator-blind-e2ee, self-hostable-by-default]
---

## Goal

`ScopePurge.eraseScope` deletes eight tables by `scope_id` and ends with `ScopeEntity.deleteById`. Four tables in
the foreign-key closure below `veyq_scope` are in neither that list nor any other delete path, so that final
statement violates a foreign key and rolls the sweep back. Because `run()` wraps the **whole tick** in one
transaction, the rollback is not confined to the offending scope: one group that ever enqueued a background job
stops **every** group from ever being reclaimed, silently and permanently — under self-hosting, on the operator's
own disk. This CR closes the four gaps, isolates each scope in its own transaction so a failure costs one scope
instead of all of them, and promotes the closure rule to a guard so the next table referencing `veyq_scope`
cannot re-open it.

## Context & constraints

- [uc.spaces.delete-group](../docs/features/spaces/use-cases/uc.spaces.delete-group.md) — postconditions + main
  flow step 5 now state the total-erasure rule, the share semantics, and the isolation requirement this builds.
- [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §4/§6 (group lifecycle),
  [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md) (the job queue that owns `veyq_job`),
  [ADR 0029](../docs/decisions/0029-public-viewer-share.md) §6 (shares are personal-scope-only in v1 — which is
  why the share arm is dormant *today* and not why it is safe).
- **Operator-blindness is the reason the share rows go, not an obstacle to it.** A surviving `veyq_share` row for
  an erased scope holds `owner_account`, `node_id`, `sealed_name` and `created_at` — metadata about a group whose
  erasure the owner requested. There is no capability left to revoke, so retention protects nothing and leaks
  something.
- **The mechanism is explicit deletes in `eraseScope`, not `ON DELETE CASCADE`.** `eraseScope` already *is* the
  enumerated, ordered list; the defect is a gap in that list and the repair belongs where the list lives. A
  cascade would change what deleting a scope means for every future path, silently — and for `veyq_share`
  specifically it would make a revocation record vanish as a side effect of an unrelated operation.
- **`NodeSubtreePurge` is the isolation precedent in the same tree** (`QuarkusTransaction`, not `@Transactional`
  on `run()`). The comment at `ScopePurge.kt:39-42` explains the *current* tick-wide design; it is superseded by
  this CR and must be rewritten, not left standing next to the new shape.
- **A caught-and-continued failure must be logged.** Swallowing it here would rebuild `docs/inbox.md` #130's
  defect (a silent `runCatching`) in a new place. Isolation without a log is a worse bug than no isolation.

## Locators — run here, outputs recorded

| Question | Command | Output at `0bd3241` |
|---|---|---|
| Which tables does the FK closure below `veyq_scope` reach, and which does `eraseScope` miss? | the closure probe (see § Guard) | **12 tables**; 8 deleted, 1 `CASCADE`, **4 missing** |
| Does anything anywhere delete a job or a share row? | `grep -rn --include='*.kt' -E 'JobEntity\.(delete\|deleteAll)\|ShareEntity\.(delete\|deleteAll)' backend/src` | nothing (exit 1), main *and* test |
| Do the two child tables cascade from their parents? | `grep -rn -iE 'REFERENCES veyq_(job\|share)' backend/src/main/resources/db/migration/` | `V30__job_decline.sql:14`, `V32__contributor_share.sql:13` — neither has an `ON DELETE` clause |

The four missing tables, with the FK that holds the scope row hostage:

| Table | FK → | Locator | Reachable today? |
|---|---|---|---|
| `veyq_job` | `veyq_scope` | `V20__background_jobs.sql:26` | **Yes** — `TasksStore.originateScopeRotation` enqueues per scope; `uc.spaces.delete-group` A4 leaves key rotation unguarded during pending-deletion, so a job can even arrive *inside* the grace window |
| `veyq_job_decline` | `veyq_job` | `V30__job_decline.sql:14` | Yes, via a declined job — and it blocks the `veyq_job` delete this CR adds, so it is not optional |
| `veyq_share` | `veyq_scope` | `V31__share.sql:20` | Dormant (v1 shares are personal-scope-only, `eraseScope` runs on GROUP) — a schema bomb armed for the moment sharing reaches group scopes |
| `veyq_share_identity` | `veyq_share` | `V32__contributor_share.sql:13` | Same, and blocks the `veyq_share` delete |

**The inbox row said two; the closure says four.** The row asked one-hop ("what references `veyq_scope`?"); the
question that holds is transitive ("what is reachable by FK *from* `veyq_scope`?"). Deleting `veyq_job` without
`veyq_job_decline` first simply moves the violation one table down.

## Guard

`scripts/validate_scope_erasure_arity.py` — every table in the FK closure below `veyq_scope` is either named by
`ScopePurge.eraseScope` or carries `ON DELETE CASCADE`; anything else is a table that can block the erasure.

**The predicate is not described here, it is executable:**
[`cr-scope-erasure-fk-closure.probe.py`](cr-scope-erasure-fk-closure.probe.py), the refine-time run against the
untouched tree — the one that corrected this row from two arms to four (`python3` it; exit 1 and the four
tables). The guard ships that predicate. The probe's docstring carries the **two shapes that failed first** —
one-hop, and table-name-as-written-at-`CREATE TABLE` against V9/V21's rebuild-and-rename — and those belong in
the guard's own docstring as its limit, because they are the two the next reader will reach for.

## Phases

Ordering is deliberate and follows `changes/README.md` § *where the defect is still live, order it guard → red →
fix → green*: **the isolation fix is staged before the FK fix, because the job-FK defect is the live lever that
makes the isolation test red.** Repair the FK first and that bite has to be reconstructed by injection.

- [x] **1 backend — the guard, red first.** Add `scripts/validate_scope_erasure_arity.py`, porting the probe's
      predicate **and its two documented limits**; record its first run naming the four tables. The probe is then
      a second copy of a live rule and dies with this CR. · Done when: it prints exactly those four and the rest
      of the chain is green
      (`for f in scripts/validate_*.py; do python3 "$f"; done` → 26 green + this one red).
- [x] **2 backend — tick isolation.** Bite-test (**live defect**, red on the untouched tree): two purgeable group
      scopes, one carrying a job row; the *clean* one must still be erased. Fix: `run()` drops its tick-wide
      `@Transactional` and erases each scope in its own `QuarkusTransaction.requiringNew()`, catching, **logging**
      and continuing past a failure — mirroring `NodeSubtreePurge`. Rewrite the `:39-42` comment. `purge(scopeId)`
      stays the transactional external entry. · Done when: the clean scope is erased while the poisoned one is
      not, and `./gradlew check` shows only the still-unfixed FK bites red.
- [x] **3 backend — the job family.** Bite-test (**live defect**, already written and measured red at
      `ScopesSliceTest.purgeErasesAGroupThatEverEnqueuedAJob`, currently in the working tree): extend it to seed a
      **decline** on the job as well, so one test covers the whole `veyq_job` → `veyq_job_decline` chain and
      cannot go green on half a fix. Fix: delete declines by job id, then jobs by `scope_id`, both before
      `ScopeEntity.deleteById`. · Done when: red before, green after; the guard drops to two.
- [x] **4 backend — the share family.** Bite-test (**live defect, seeded arm** — not the real path, and the CR
      says so on purpose: v1 cannot create a share on a GROUP scope, so the test seeds `ShareEntity` +
      `ShareIdentityEntity` directly. It pins the *schema* requirement, which is the thing that will still be
      true when sharing reaches group scopes). Fix: delete share identities by share id, then shares by
      `scope_id`. · Done when: red before, green after; **the guard goes green**.
- [x] **5 coverage + docs.** Update
      [uc.spaces.delete-group.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.delete-group.yaml):
      `hard-purge-erases` names jobs and shares, plus a new criterion for the isolation. Correct `docs/inbox.md`
      #132 (two arms → four, the tick-wide blast radius, the H2-not-MariaDB caveat) and delete it on completion.
      · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

`./gradlew check` green; the full trust chain green **including** the new guard
(`for f in scripts/validate_*.py; do python3 "$f"; done`); `docs/coverage/` updated for `uc.spaces.delete-group`
with `python3 scripts/validate_coverage.py` green. The structural invariant — *every table reachable by FK from
`veyq_scope` is erased with it or cascades* — is carried by
`scripts/validate_scope_erasure_arity.py`, not by this file.

**One limit stays open and must be stated, not quietly inherited:** every test here runs on **H2**
(`application-test.properties`, `MODE=MariaDB`), while production is **MariaDB 11.4**
(`infra/docker-compose.prod.yml`). H2 did enforce `FK_JOB_SCOPE` — the red is a real
`JdbcSQLIntegrityConstraintViolationException` — and with identical DDL and non-deferrable foreign keys in both
engines, that red transfers. The asymmetry runs the other way: a **green** on H2 would not have cleared
production. So the greens this CR ends on prove the fix under H2; nothing in this repository executes it against
MariaDB, and no claim here should be read as if something did.

## Retrospective

**Planning — the phase-2 bite was levered on a defect this CR's own later phases remove.** Phase 2 specified
*"two purgeable group scopes, one carrying a job row"* with a done-when of *"the clean scope is erased **while
the poisoned one is not**"*. That assertion is true only between phases 2 and 3: phase 3 makes the job-carrying
scope erasable, so the test would either have inverted (red on a correct tree) or degenerated into *"two clean
scopes both get erased"*, which pins nothing about isolation. The property outlives the CR and the lever did
not. It cost a re-designed lever at implement time — a refusing `BlobStore` through the existing
`RecordingBlobStore` seam, which the CR never scoped — where one question at refine would have caught it. That
question is now `changes/README.md` § *a bite's lever must survive the CR's own later phases*.

**Planning — the implement brief's central ordering claim was true of the planned CR, not the built one.** It
said the job-FK defect *is* the live lever that reddens the isolation bite, and that repairing the FK first
would destroy the evidence. The phase order was kept and is still right, but the premise is not: the tick-wide
transaction is its own live defect and reddens an isolation bite against **any** erasure that throws. Nothing
was lost — the reasoning simply pointed at a different lever than the one that shipped.

**Execution — retiring the inbox row was not a one-line delete.** Three places cited `#132` (the bite's KDoc,
the new guard's docstring, and row #130's own prose), and #130 additionally carried *measured* line anchors into
`ScopePurge.kt` that phase 2 had moved — `@Transactional at :43/:50` describes a shape that no longer exists.
Grepping the row number **before** deleting it is what found all four; deleting first would have left a guard
docstring pointing at nothing and a neighbouring row asserting something false about the code this CR changed.

**What went right, and is worth keeping:** every phase's red landed on the assertion it was supposed to. The
guard's first run *was* the discovery, the bite and the fix-list in one output; the job bite's red moved from
`purge.run()` to its own assertion the moment isolation landed, which is the isolation working, visible without
being asserted for.

**Next time:** at refine, for every bite-test, name the lever **and the phase after which that lever stops being
pullable**. If the answer is "a phase inside this CR", the bite needs a different lever before the CR is frozen.
