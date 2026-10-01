---
id: cr-test-suite-runs-no-scheduled-jobs
title: The backend test suite runs no scheduled job on its own — every job body runs only when a test calls it
status: in-progress
satisfies: []
adrs: []
invariants: []
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **Halt the scheduler for the whole `%test` profile, or switch off only the sweep that deletes reclaim rows?**
   The flake needs only `StoreReclaimSweep`. The measured hazard is wider, though. The session purge runs
   during the suite on every app start, and `GrantDeliveryOriginator` creates grant-delivery jobs for every
   grant-less membership in the shared database at the same moments. Both change rows that other tests read.
   **Recommendation: the whole profile** (`start-mode=halted`, D1). It is one line, it covers the next
   `@Scheduled` job without anyone remembering to add a line, and every test that needs a job already calls
   `run()` itself.
2. **Two job bodies lose their only execution in the suite. File a row for direct tests, or fold them in?**
   `RecoveryPurge.run()` and `GrantDeliveryOriginator.run()` are called by no test (their store methods
   `expireOverdue`/`purgeTerminal` and `reconcileAllGrantDelivery`: 0 hits in `backend/src/test`). Today they
   run only because the scheduler fires them at startup, and nothing asserts on what they do: a throw is logged
   and the suite stays green. So halting loses no failing signal, only an unasserted execution.
   **Recommendation: a new inbox row, not this CR.** Tests that assert what those jobs do are feature work, and
   this CR is harness work.
3. **The session-family purge needs ≥ 9 s on H2 against the late-suite database. Chase it?** It is a double
   `NOT IN (subquery)` delete (`SessionService.kt:281-284`). Whether it is slow on MariaDB at production scale is
   **not measured**. **Recommendation: a new inbox row.** After this CR the suite no longer runs it
   unasked, so its cost stops hiding inside the suite. The question left is a production one.
   *(Refined 2026-09-28 as inbox #218 → [`cr-session-purge-cost-on-mariadb`](cr-session-purge-cost-on-mariadb.md).
   Its H2 measurement puts the statement at ≤ 81 ms on 16 000 families, so the ≥ 9 s above was not the
   statement's own work; see that CR.)*

**Open — added by the inbox #217 refine, 2026-09-28. Phase 1 keeps its go; this question gates only Phase 2.**

4. **Fold #217's direct tests into this CR as Phase 2, reversing question 2's answer?** The #217 refine measured
   that the gap is wider than question 2 says. **Four** `run()` bodies lose their only execution in the suite,
   not two. `SessionPurge.run()` and `TenantInvitePurge.run()` are called by no test either. Their service and
   store methods are tested one by one, but the composition is not: the call order and the `@Transactional`
   wrapper that cr-0053 put on `run()`. Locator: `grep -rlw 'SessionPurge\|TenantInvitePurge\|RecoveryPurge\|GrantDeliveryOriginator' backend/src/test`
   → 0 files; the full table is in *Context*.
   **Recommendation: fold, as Phase 2 (D4–D6).** The change that removes an execution is the natural owner of
   replacing it. The tests are small, backend-only and in the same harness knowledge (the shared H2, what a
   job's `run()` touches beyond its own fixtures). Phase 1 still lands first and alone, because halting loses no
   failing signal (question 2's measured point), so Phase 2 never blocks the flake fix. If you keep question 2's
   answer instead, #217 stays a row and this section goes.

## Goal

A full `./gradlew check` logs `SQLState 57014` on the session-family purge in every run, and
`MediaBudgetBranchSliceTest` has twice gone red in a full run and green alone (inbox #182). Both come from one
fact, measured at refine: **`%test` runs the ten `@Scheduled` jobs.** Quarkus' simple scheduler fires every
`every = …` job at its first trigger check, at most about one second after **each** application start. A suite
with 26 `@TestProfile`s starts the application about 27 times, so each job runs about 27 times against the
JVM-shared H2 database, on worker threads, while tests run. After this CR the `%test` scheduler starts **halted**:
the triggers are still built, so a malformed interval still fails startup, but no job fires unless a test calls
its `run()`. A test pins that state.

## Context & constraints

- **Measured at refine** (worktree at `faf9ef4`, Quarkus 3.36.0; scheduler source read from the
  `quarkus-scheduler-3.36.0-sources.jar` in the Gradle cache):
  - **The scheduler runs in `%test`.** A full `./gradlew check` was green (919 tests, `BUILD SUCCESSFUL in 11m
    31s`) and logged the 57014 twice. Its stack runs through
    `SessionPurge_ScheduledInvoker_run_….invokeBean` → `io.quarkus.scheduler.common.runtime.OffloadingInvoker`, so
    the scheduler invoked it and no test did. `application-test.properties` sets no `quarkus.scheduler.*`
    (`grep -rn scheduler backend/src/main/resources backend/src/test` → 0 config lines).
  - **The 57014 is a cancellation at app stop, not a timeout.** Its cause chain is `57014` → `ARJUNA016045:
    attempted rollback` → H2 `90007 The object is already closed`, and it lands about 100 ms after the next test
    class begins: in `MediaBudgetBranchSliceTest` (class start 09:55:32.348, error at .449) and in
    `InstanceReleaseUnstampedSliceTest` (21.682 / .763). In both cases a profile switch closed the datasource
    under a purge that the **previous** app's startup had fired. Before `MediaBudgetBranchSliceTest` that app
    lived 10.6 s, so the delete ran **≥ 9 s** on H2 before it was killed. H2's message ("canceled or the session
    timed out") and the row's word "timeout" both mislead here. **The count depends on load, and it counts
    killed purges.** Three unmodified full runs gave **2, 2 and 23**. In the heavily loaded runs the profile apps
    lived about 10 s each and most purges finished. In the lighter run (test span 2 m 53 s) the profile apps lived
    4–6 s, and the purge was killed at **23 of the ~25** profile switches after the default-profile block, from
    `InstanceReleaseUnstampedSliceTest` to `TenantIngressUnwritablePathTest`. So on nearly every app start the
    purge burns CPU for the app's whole lifetime and never commits. The table it would clean keeps growing
    (`TestFixtures.bearer` mints a session family per call, `TestFixtures.kt:192-193`).
  - **Why every start fires every job:** `SimpleScheduler` checks triggers every `CHECK_PERIOD = 1000` ms, and the
    first check comes at the next whole second after `StartupEvent` (`SimpleScheduler.java:72, 208-216`). An
    `IntervalTrigger` fires at its first check, because its start is the scheduler start plus `delayed`, which no
    job sets. Every veyq job uses `every = "{…}"` with `1h` or `5m` (`application.properties:47-221`), so within
    one app lifetime only the startup fire happens, and that fire comes with every restart.
  - **App starts per suite:** 26 distinct `@TestProfile`s (`grep -rh '@TestProfile(' backend/src/test | sort -u |
    wc -l`), plus the default profile.
  - **What `halted` does:** the executor is created, `running = false`, and **the triggers are still created**:
    `createTrigger` → `parseEveryAsMillis` runs for every `@Scheduled` method (`SimpleScheduler.java:134-163`).
    So a bad interval still fails startup. `checkTriggers` then skips them all while paused (`:232`).
    `enabled=false` returns before any trigger is built (`:94-98`), which drops that validation; this is why D1
    picks `halted`. `isStarted()` is true and `isRunning()` is false in `halted`; `isStarted()` is false when
    disabled (`:167, 314`).
  - **Env passthrough works for measurement:** `QUARKUS_SCHEDULER_ENABLED=false ./gradlew test --rerun --tests
    '…MediaBudgetBranchSliceTest'` logged `Simple scheduler is disabled by config property` in that class's
    XML, and the class passed 3/3.
  - **The fix, previewed through that passthrough:** `QUARKUS_SCHEDULER_START_MODE=halted ./gradlew test --rerun`
    gave **919 tests, 0 failed, 0 × 57014**, with a test span of **1 m 57 s**. The unmodified run just before it
    took 2 m 53 s. Load differs between runs, so read the timing as indicative only. No test depends on a job
    firing on its own. Phase 1 still makes the change in the properties file, where it is reviewed and
    versioned, and does not rely on an env var.
  - **The hazard, seen directly** (measured 2026-09-28 by the #218 refine, details in
    [`cr-session-purge-cost-on-mariadb`](cr-session-purge-cost-on-mariadb.md) § Context). A scratch test seeded
    20 token-less session families and counted what a rolled-back purge would delete. Twelve times in a row it
    counts 20 in `halted` mode. In `normal` mode the count fell to 0 midway: the startup-fired `SessionPurge`
    had committed their deletion under the test.
- **The flake, by elimination (measured locators, reasoning not reproduced):**
  - The failing assertions are both "exactly one `StoreReclaimEntity` for this storage key"
    (`MediaBudgetBranchSliceTest.kt:91` and `:135`/`:156` in today's file). Reclaim rows are due the instant they
    are written (`StoreReclaim.kt:119-121`, `nextAttemptAt <= now`).
  - The only production code that deletes a reclaim row is `StoreReclaimStore.settle`
    (`StoreReclaim.kt:134-135`). Its one caller is `StoreReclaimSweep.run` (`StoreReclaimSweep.kt:63`)
    (`grep -rn 'StoreReclaimEntity.delete' backend/src/main`).
  - The five test-side deleters (`deleteAll`/`deleteById` in `NodeTrashSliceTest`, `MediaTrashSliceTest`,
    `UploadSessionPurgeSliceTest`, `StoreReclaimSweepSliceTest` ×2) and the explicit `sweep.run()` calls all run
    on the test thread. The suite runs sequentially (no `maxParallelForks`, no `junit-platform.properties`). So
    the only thing that can remove the row **between** the test's commit and its assertion is the
    scheduler-fired sweep in `MediaBudgetBranchSliceTest`'s own profile app.
  - **Not reproduced.** 0 reds in the 3 full runs at refine. The row saw 2 reds across an unrecorded number of
    runs. The fix is justified by the mechanism: it removes the only concurrent deleter. It is **not** justified
    by a before/after flake rate, which is not affordable at ~5–16 min per run and a rare red.
- **Two test doubles already defend against this concurrency, in three comments:** `RecordingBlobStore.kt:57-58, 63-64`
  and `RefusingStoreReclaimStore.kt:38-39` justify `synchronized`/`@Volatile` with "a `@Scheduled` sweep can …
  fire from a scheduler thread". After this CR that holds only when a test starts the scheduler.
- **Coverage (see owner question 2):** direct `run()` callers exist for `ScopePurge`, `NodeSubtreePurge`,
  `PairingPurge`, `UploadSessionPurge`, `MediaTrashPurge` and `StoreReclaimSweep`. `SessionPurge`'s four service
  methods are called directly by `SessionPurgeTest`, and `TenantInvitePurge`'s store methods once each.
  `RecoveryPurge` and `GrantDeliveryOriginator` have none. Locator: for each `@Scheduled` class
  (`grep -rln '@Scheduled' backend/src/main`), `grep -rn '<Class>\|<its store methods>' backend/src/test`.
- **Every job's direct-test status after the halt** (measured 2026-09-28 at `64c1f65` by the #217 refine;
  `@Scheduled(` hits: `grep -rn '@Scheduled(' backend/src/main` → 10 jobs, plus a KDoc mention in
  `VeyqConfig.kt`; `StoreProvisioningRunner` is deliberately *not* `@Scheduled`, its KDoc says why). "`run()`
  calls" counts `grep -oE '\b(purge|trashPurge|sweep)\.(run|purge)\(' <test file> | sort | uniq -c`, read per
  injected variable (`grep -rnE 'lateinit var \w+: <Class>\b' backend/src/test`):

  | Job (`backend/src/main/kotlin/com/veyq/app/…`) | Direct `run()` test | Only its parts tested | After the halt |
  |---|---|---|---|
  | `features/spaces/scope/ScopePurge.kt` | `ScopesSliceTest` (7× `purge.run()`, 3× `purge.purge(`) | `MemberRemoveSliceTest` (`purge.purge(`) | covered |
  | `features/content/NodeSubtreePurge.kt` | `NodeTrashSliceTest` (2× `purge.run()`) | — | covered |
  | `features/account/pairing/PairingPurge.kt` | `PairingPurgeTest` (3× `purge.run()`) | — | covered |
  | `features/media/UploadSessionPurge.kt` | `UploadSessionPurgeSliceTest` (5× `purge.run()`) | — | covered |
  | `features/media/MediaTrashPurge.kt` | `MediaTrashSliceTest` (1× `trashPurge.run()`) | `store.purgeTrashedBefore` once | covered |
  | `shared/blobstore/StoreReclaimSweep.kt` | `StoreReclaimSweepSliceTest` (5×), `UploadSessionPurgeSliceTest` (5×), `NodeTrashSliceTest`, `MediaTrashSliceTest` (1× each) | — | covered |
  | `features/account/login/SessionPurge.kt` | **none** (`grep -rlw SessionPurge backend/src/test` → 0) | `SessionPurgeTest` calls the four `SessionService.purge*` methods | **`run()` never executes** |
  | `features/admin/tenant/TenantInvitePurge.kt` | **none** (`grep -rlw TenantInvitePurge backend/src/test` → 0) | `TenantsSliceTest.kt:209-210` calls both store methods | **`run()` never executes** |
  | `features/account/recovery/RecoveryPurge.kt` | **none** (→ 0) | **none**: `store.expireOverdue(` / `purgeTerminal(` → 0 hits | **nothing executes** |
  | `features/tasks/GrantDeliveryOriginator.kt` | **none** (→ 0) | per-scope `reconcileGrantDelivery` only (`ConsentGroupAddSliceTest`, 4×); `reconcileAllGrantDelivery` → 0 hits | **its scope selection never executes** |

  The four bottom rows are Phase 2 (owner question 4). A `run()` adds two things its parts do not have. The
  first is the **call order**: `RecoveryPurge.kt` and `SessionPurge.kt` both say theirs matters. The second is
  the **one-transaction wrapper** (`SessionPurge.kt:18-20`, `RecoveryPurge.kt:24-25`: without it the second
  nested call fails with "EntityManager is closed", cr-0053; *remembered from those comments, not reproduced*).
  `reconcileAllGrantDelivery` holds the only logic of its own in the originator: it picks the scopes as
  grant-less ∪ rotated (`TasksStore.kt:468-472`).
- **Out of scope:** the `%e2e` profile. `application-e2e.properties` is its own profile, not layered on `%test`,
  and a live instance *should* run its jobs. Also out of scope: the purge's query shape (owner question 3).
- **Gate baseline:** `./gradlew check` on `faf9ef4` → `BUILD SUCCESSFUL`, 919 tests, 0 failed (measured).

## Decisions

- **D1: `%test.quarkus.scheduler.start-mode=halted`** in `backend/src/main/resources/application-test.properties`,
  with a comment that names the mechanism (every job fires at each of the suite's ~27 app starts, on worker
  threads, against the shared H2) and the proof test. Rejected alternatives:
  - `quarkus.scheduler.enabled=false` drops interval validation (above).
  - Per-job `%test.veyq.*.every=off` works because `SchedulerUtils.isOff` accepts `off`/`disabled`, but it is ten
    keys, and an eleventh job would silently run until someone added its line.
  - A test-side retry or wait in `MediaBudgetBranchSliceTest` would leave the other jobs running.
- **D2: the proof is a state test, not a flake-rate measurement.** A new `@QuarkusTest`
  (`backend/src/test/kotlin/com/veyq/app/shared/config/ScheduledJobsHaltedInTestTest.kt`, default profile) injects
  `io.quarkus.scheduler.Scheduler` and asserts, in this order:
  1. `isStarted()` is true. This tells halted apart from disabled.
  2. `getScheduledJobs()` is non-empty. This proves the triggers were built, so interval validation still ran.
  3. `isRunning()` is false.

  On today's tree assertion 3 is the one that fails first; 1 and 2 hold there.
- **D3: the three defensive comments are reworded, and the synchronization stays.** It costs nothing, and a future
  test may `resume()` the scheduler. The new wording says the concurrency exists only when a test starts the
  scheduler, and names D2's test as the place that pins `%test` halted. The code does not change.

*D4–D6 apply only if owner question 4 is answered "fold". Each test calls the bean's `run()` on the test thread,
the way the six covered jobs' tests already do. None of them adds a production code path.*

- **D4: `RecoveryPurgeTest`** (new, `backend/src/test/kotlin/com/veyq/app/features/account/recovery/`, default
  profile, `@Inject RecoveryPurge`). It seeds `AccountRecoveryEntity` rows directly, one user per row, which keeps
  the fixture inside the "at most one PENDING per user" rule (`Entities.kt:49`). The `%test` retention is the
  default `P7D`, because `application-test.properties` does not override it (`grep -n recovery
  backend/src/main/resources/application-test.properties` → 0). Rows, and what one `run()` must leave:
  - R1 PENDING, expires in 1 h → still PENDING.
  - R2 PENDING, expired 1 min ago, created 1 h ago → EXPIRED and **kept**, because it is inside retention.
  - R3 CONSUMED, created 8 d ago → deleted.
  - R4 CONSUMED, created 1 d ago → kept.
  - R5 PENDING, created 8 d ago, expired 8 d ago + 1 h → **deleted in the same run**. This row pins the order:
    expire first, then purge. With the calls swapped, R5 survives as EXPIRED.
- **D5: `GrantDeliveryOriginatorTest`** (new, `backend/src/test/kotlin/com/veyq/app/features/tasks/`, default
  profile). Scope A has `currentEpoch 0`. It holds M1 grant-less, M2 grant-less with `pendingConsent`, and M3
  granted. Scope B has `currentEpoch 2`. It holds M4 with `grantedEpoch 1` (stale), M5 with `grantedEpoch 2`, and
  M6 grant-less, which already has a PENDING grant-delivery job. After `run()`:
  - M1 and M4 each have exactly one PENDING `grant-delivery` job.
  - M2, M3 and M5 have none.
  - M6 still has exactly one.

  A second `run()` changes none of these counts. Every assertion counts **by `resourceRef`** and never reads
  the return value. `reconcileAllGrantDelivery` walks the whole shared database, so its count includes other
  tests' leftover grant-less memberships. That is the same reach the halt removes from startup. **The test
  writes its restore in the same edit** (§ *a test that mutates shared singleton/global state writes its
  restore in the same edit*): it snapshots the ids of the `grant-delivery` jobs before the first `run()`, and in
  a `finally` it deletes every job that is new, whatever membership it belongs to. After that, no later test
  can see a job this test created. The restore is the guarantee; the reads are narrow anyway. `grep -rn
  'JobEntity\.\(count\|find\|listAll\)' backend/src/test` gave 7 hits at refine, each filtered by job id,
  `scopeId` or `resourceRef`, and the claim endpoint the task tests drive is bounded by the caller's scopes.
- **D6: the two jobs whose parts are already tested get their `run()` called instead**, with no new test class.
  - `SessionPurgeTest` replaces its four direct `SessionService.purge*` calls (`SessionPurgeTest.kt:49-52`) with
    `@Inject SessionPurge` + `purge.run()`. Every assertion stays, and the service methods stay covered through
    `run()`.
  - `TenantsSliceTest.kt:209-213` keeps its two store calls, because it asserts their return values. After them
    it adds one invite seeded like the overdue one, calls `purge.run()`, and asserts that this invite is gone.
    Read how `purgeTerminalInvites` picks its cutoff first, and seed the row so that only the order "expire,
    then purge" deletes it.

## Phases

Backend only (no contract, no web). Phase 1 is the approved change. Phase 2 exists only if owner question 4 is
answered "fold". The two phases are independent, so Phase 1 lands first and alone.

- [x] 1 backend —
  1. **Red first (live defect).** Write D2's test against the unchanged `application-test.properties` and run
     `./gradlew test --tests '*ScheduledJobsHaltedInTestTest'`. **Expected: red on assertion 3 (`isRunning()`),
     with 1 and 2 green.** Record which assertion fired (§ *read which assertion reddened*).
  2. **Fix.** Add D1's property and comment.
  3. **Green.** The same test passes.
  4. **Re-bite by injection, two levers, snapshot-and-restore.** (a) `start-mode=normal` must redden assertion 3.
     (b) `quarkus.scheduler.enabled=false` instead of the start mode must redden assertion **1**. Lever (b) is the
     proof that the test tells "halted" apart from "nothing is there" (§ *a bite test must tell "the guard held"
     apart from "nothing ran"*).
  5. **The observable the row was written from.** Run a full `./gradlew check` and count `SQLState: 57014` in
     `build/test-results/test/*.xml`: **expected 0**, against 2, 2 and 23 in the three refine runs. A non-zero count
     means some path still starts the scheduler. Find it before closing.
  6. **Reword the comments** of D3 (`RecordingBlobStore.kt`, `RefusingStoreReclaimStore.kt`).
  · Done when: steps 1–5 are recorded (red assertion, both injection reds, 57014 count), and `./gradlew check` is
    green.

- [ ] 2 backend — **the four `run()` bodies the halt leaves unexecuted get a direct test** (D4–D6; only if
  owner question 4 is answered "fold").
  1. **Re-measure the gap on the tree you start from.** Run the four `grep -rlw` locators in *Context* again.
     A row that no longer reads 0 has been covered meanwhile, so drop it from this phase and say so.
  2. **Write D4, D5 and D6.** They test behaviour that already exists, so they should go green straight away.
     A red here is a **finding**, not a step in the plan. Stop and report it; do not fix production code inside
     this phase.
  3. **Bite every test by injection** (snapshot-and-restore production code, never `git checkout`). Record which
     assertion fired for each lever:
     - D4 (a): swap the two calls in `RecoveryPurge.run()` → the R5 assertion must go red.
     - D4 (b): delete the `expireOverdue` call → R2 must go red.
     - D5 (a): drop `+ rotated` in `reconcileAllGrantDelivery` → M4 must go red.
     - D5 (b): drop `grantless` from it → M1 must go red.
     - D5 (c): delete the `if (live > 0) continue` in `reconcileGrantDelivery` → M6 (and the second-run check)
       must go red.
     - D6 (a): move `purgeEmptyFamilies()` to the first line of `SessionPurge.run()` → "empty family kept" must
       go red.
     - D6 (b): swap the two calls in `TenantInvitePurge.run()` → the added invite assertion must go red.
     - **Transaction-wrapper lever:** remove `@Transactional` from `RecoveryPurge.run()`, then from
       `SessionPurge.run()`. The comments say the second nested call then fails with "EntityManager is closed".
       That is *remembered from the comment*; on a test thread it may not reproduce. If neither test goes red,
       apply § *a named lever that moves nothing is EITHER a design question or an unasserted property*. The
       expected answer is an unasserted property of the scheduler thread only. Record it in the test's KDoc and
       do not build a scheduler-thread harness for it.
  4. **Prove D5's restore.** D5 also seeds a *foreign* grant-less membership MF in a scope C that none of its
     assertions reads. MF stands in for another test's leftover row. As its last step, after the restore, the
     test asserts two things: MF has no job, and no `grant-delivery` job exists outside the pre-run snapshot.
     Before the restore, MF has a job; assert that too, so the check cannot pass because nothing ran. Remove
     the restore, and the final assertion must go red. This is § *a bite test must tell "the guard held" apart
     from "nothing ran"*, applied to a restore.
  · Done when: the three test changes are in, every lever above is recorded with its red (or with its
    documented "moves nothing" verdict), and `./gradlew check` is green.

## Definition of done

- `./gradlew check` green, with D2's test in it and 0 `SQLState: 57014` in the test XML.
- The live-defect red and both injection reds are recorded with the assertion that fired.
- *(Phase 2, if folded)* No `@Scheduled` job's `run()` is left without a test that calls it. Re-run the table's
  locators: each of the ten jobs names at least one test file that calls its `run()`. Every D4–D6 lever is
  recorded. Inbox #217's status cell is set to done, with a pointer to the three tests.
- **Harvest before deleting:** the mechanism lives in the `application-test.properties` comment and in D2's test
  KDoc. The owner's answers to questions 2 and 3 become rows (or not). Inbox #182's status cell is set to done
  with a one-line pointer to the test. No `docs/` state changes: no state doc describes the test harness's
  scheduler.

## Retrospective

*(written at close)*
