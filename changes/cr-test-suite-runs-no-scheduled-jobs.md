---
id: cr-test-suite-runs-no-scheduled-jobs
title: The backend test suite runs no scheduled job on its own — every job body runs only when a test calls it
status: planned
satisfies: []
adrs: []
invariants: []
---

## Open for the owner

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

## Phases

Single phase (backend only; no contract, no web).

- [ ] 1 backend —
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

## Definition of done

- `./gradlew check` green, with D2's test in it and 0 `SQLState: 57014` in the test XML.
- The live-defect red and both injection reds are recorded with the assertion that fired.
- **Harvest before deleting:** the mechanism lives in the `application-test.properties` comment and in D2's test
  KDoc. The owner's answers to questions 2 and 3 become rows (or not). Inbox #182's status cell is set to done
  with a one-line pointer to the test. No `docs/` state changes: no state doc describes the test harness's
  scheduler.

## Retrospective

*(written at close)*
