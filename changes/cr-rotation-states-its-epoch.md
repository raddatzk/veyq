---
id: cr-rotation-states-its-epoch
title: A rotation states the epoch it creates, and the server commits only the next one
status: done
satisfies: [uc.spaces.manage-group-members]
adrs: ["0024", "0021", "0072"]
invariants: [operator-blind E2EE, API-first]
---

## Goal

A scope rotation mints `E_{n+1}` on the device and posts the chain-link `symWrap(E_{n+1}, E_n)`, where `E_n` is
the device's **own grant**: `deriveEpochKeys` returns `current: grantedEpoch`. The scope's epoch plays no part.
The request names no epoch, and the server stores the link at whatever `currentEpoch + 1` the row holds at
commit time (`RotationPolicy.plan`). So a rotation built on a superseded epoch still commits:
- it lost a race: two owners, two tabs, a job reclaimed after its lease lapsed, or a panel removal meeting a
  queued job;
- or it rode a grant that an earlier rotation skipped.

Its link then chains `E_{n+2}` to `E_n`, and its re-grants overwrite the winner's, so everything sealed under
`E_{n+1}` becomes unreachable for every member. That is data loss, not a leak.

`ScopesSliceTest.aRotationBuiltOnASupersededEpochIs409AndLeavesTheChainIntact` reproduces it and is **red on
today's code**. It was run during refinement: the second owner's commit, built on epoch 0, answers `201` where
the test expects `409`.

After this CR the request states the epoch it creates. The server commits it only as the scope's current
epoch + 1 and otherwise answers `409 stale_epoch` with nothing stored. The device re-opens and rebuilds once,
from a **fresh** key. The rule and its reasons are [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) §4;
the flow is [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md) E3.

## Context & constraints

- **The field is the new epoch, `epoch`.** It is not the base. It is the value
  [`cr-seal-binds-its-object`](cr-seal-binds-its-object.md) binds into the link's and the grants' seals, and it
  mirrors `ScopeRotated.epoch`. That CR lands **after** this one, adds no field to `rotateScope`, and no longer
  carries a `rotateScope` bite. Both directions are pinned here.
- **The decision lives in `RotationPolicy`; the atomicity lives in `ScopesStore`.** The policy already owns "the
  next epoch", and its `currentEpoch + 1` is the line that becomes the check. It is in the PIT scope
  (`backend/pitest.scope.json`), per ADR 0045 §2. The store reads the scope row with
  `LockModeType.PESSIMISTIC_WRITE` (precedent: `grep -rn PESSIMISTIC_WRITE backend/src/main`). Two in-flight
  commits then serialize, and the loser sees the winner's epoch, which is a clean `409`. `ux_scope_epoch` stays
  as the backstop. Without the lock the chain still holds, because the loser hits the index, but it answers
  an error the client does not retry (predicted to be a `500`; the concurrency bite in phase 2 measures it).
- **`409 stale_epoch` already exists with this meaning** on `renameGroup`, and so does the client's retry-once
  shape: `grep -n 'export async function renameGroup' -A22 web/src/features/spaces/scopes.ts`. Mirror both. The
  difference is where the key is minted: **inside** the attempt. The refused attempt's wraps already reached the
  server, and one may be addressed to a member removed in the meantime (ADR 0024 §4).
- **The directory may be reused across the retry.** At commit, the server drops every grant whose membership is
  gone or still `pendingConsent` (`RotationPolicy`). A stale `remaining` list is therefore harmless against an
  honest server. Against a malicious one it is #161 (b)'s problem, not this CR's.
- **A job whose rotation stays stale is declined, not failed.** A worker whose grant lags cannot perform it.
  `declineTask` releases the job for a current key-holder without spending an attempt. Precedent:
  `grep -n declineTask web/src/features/tasks/grantDeliveryWorker.ts`.
- **Coordination.**
  - [`cr-admission-is-a-key-holder-act`](cr-admission-is-a-key-holder-act.md) adds admission re-tags to the
    rotation build. They must stay inside the retried attempt, so that a retry re-tags under its fresh key.
  - #161 (b)'s client-side epoch floor is orthogonal: this server check protects nothing against a malicious
    operator. What holds there is `cr-seal-binds-its-object`'s binding plus #161's floor.
- **The residual this CR makes visible, not fixed here:** inbox #178. A panel removal whose rotation fails
  leaves no job behind. After this CR, an owner whose grant lags hits that path on every attempt, instead of
  silently breaking the chain.
- **Out of scope:** `renameGroup`/`setScopeIcon` check the epoch in the handler and write in a separate
  transaction. A rotation landing between the two stores metadata under the old epoch. The window is one
  request and the exposure is metadata against a malicious operator.

## Phases

- [x] 1 contracts — `RotateScopeRequest` gains a required `epoch` (integer, `minimum: 1`, annotated like
      `nameEpoch`), and `rotateScope` gains the `409 stale_epoch` response. Rewrite the description: it
      "commits the stated epoch iff it is the next one"; it no longer "bumps to the next index". The field is a
      shape change, so both surfaces must follow. Expected reds until phases 2–3: the web call sites that omit
      `epoch` (TypeScript), and every backend test that posts a rotation without it (400). Measure both; don't
      trust this prediction.
      · Done when: `npm run generate:api`, `./gradlew compileKotlin compileTestKotlin` and every
      `scripts/validate_*.py` are green.
- [x] 2 backend — **guard → red → fix → green.**
      - **Live defect:** delete the `@Disabled` line on
        `ScopesSliceTest.aRotationBuiltOnASupersededEpochIs409AndLeavesTheChainIntact`, run it, and record the
        red. The first failing assertion must be the second owner's `statusCode(409)` (today `201`). A red
        anywhere else landed on a setup step and is not the bite.
      - **Fix:** `RotationPolicy.plan` takes the stated epoch and answers stale unless it is `current + 1`.
        `ScopesStore.rotate` locks the row, asks the policy and writes nothing on stale. The handler maps stale
        to `409 stale_epoch`. Correct `ScopesResource`'s `@RateLimitExempt` reason: its "already serialized by
        the epoch chain" is false today, and after this phase it is the stated epoch that serializes.
      - **`RotationPolicyTest`:** add the next epoch (planned), `current` (stale, the #177 case) and `current + 2`
        (stale). Both directions are needed so that neither `<=` nor `>=` survives. The mutation gate is their
        bite.
      - **Concurrency, by injection:** mirror `LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`. Release
        two rotations stating the same epoch through a `CyclicBarrier(2)` and assert exactly one `201` and
        exactly one `409 stale_epoch`. The lever: drop the lock (snapshot-and-restore), expecting a `500` from
        `ux_scope_epoch`. If H2 never overlaps the two reads, the test cannot be made red. Record that in the
        retrospective rather than weakening it.
      - Every existing rotate body gains `epoch`. Locator from two angles, whose union is the list:
        `grep -rn 'post("/api/scopes/[^"]*/epochs")' backend/src/test` and
        `grep -rln '"wrappedPrev"' backend/src/test`. The second finds `OperatorBlindProbeTest`'s probe table,
        which the first misses.
      · Done when: `./gradlew check` is green, with the red recorded above.
- [x] 3 web — `rotateGroupForRemoval` and `rekeyAlone` commit through **one** function. It opens, mints, builds,
      states `epoch: opened.epochs.current + 1`, commits, and on `stale_epoch` does all of that once more.
      `isStaleEpoch`'s comment stops saying "under a rename". `runScopeRotationWorker` declines a job whose
      rotation is still stale and fails any other error, as today.
      Locator: `grep -rn 'rotateScope(' web/src --include='*.ts' | grep -v generated`, whose non-test hits are
      the two call sites.
      Tests:
      - `scopes.test.ts`: the commit states `current + 1`. A first `409 stale_epoch` re-opens and commits again.
        The two attempts' new epoch keys **differ**: open the caller's own grant from each payload to show it.
        **Lever, by injection:** hoist the mint out of the loop, which must redden the key check. A second
        stale answer throws after exactly two commits.
      - `scopeRotationWorker.test.ts`: stale → `declineTask`, not `failTask`. The existing `rotate-failed` case
        is the control.
      - `rotateKeys.test.ts`: `rekeyAlone` states the epoch.
      · Done when: `npm run check` (web) is green.
- [x] 4 coverage — `uc.spaces.manage-group-members.yaml` gains a criterion: *a rotation commits only as the
      scope's current epoch + 1, anything else is refused with nothing stored, and the device retries once from
      a fresh key*. Refs: the two slice tests, the `RotationPolicyTest` cases, and the web cases above. It joins
      the rotation criteria already there. One of them, `queued-rotation-backstop`, is already triggered from
      outside this use-case (a tenant owner's cut). Opening a use-case for one more criterion would split the
      mechanism's coverage in two.
      · Done when: `python3 scripts/validate_coverage.py` and the trust-chain are green.

## Definition of done

`./gradlew check` and `npm run check` (web) are green. A rotation built on a superseded epoch is refused with
nothing stored, proven by a slice test that was red before the fix. The policy's check is mutation-verified.
A retry never reuses a key whose wraps already left the device. No new guard: the slice tests run in
`./gradlew check` and are the permanent proof. Inbox #177 is closed and #178 is left open.

## Retrospective

- **Planning — the key-reuse lever was one form short.** "Hoist the mint out of the loop" leaves the per-attempt
  `finally { newKey.fill(0) }` in place, and that form retries with the wiped all-zero buffer: it differs from the
  first key and chains consistently, so both assertions the CR named stayed green. Measured by injecting both
  forms; fixed with an "is not the zeroed buffer" assertion. Harvested into `changes/README.md` (§ A control's
  LEVER must itself be bitten). *Next time:* a lever that moves a secret's lifetime is written with and without
  the cleanup that bounded it.
- **Planning — a test inventory that met a module mock.** "`rotateKeys.test.ts`: `rekeyAlone` states the epoch"
  was unreachable as the suite stood: it mocked `spaces/scopes` wholesale, and once both callers route through
  one function in that module the epoch is stated inside it, where a partial mock cannot reach (ESM-internal
  call). The suite now runs the real scope layer with only API and vault mocked. *Next time:* a CR that funnels
  call sites into one function greps the suites that `vi.mock` that function's module.
- **Execution — a foreground bite outran its timeout.** Three sequential slice runs at 3–4 min each (the machine
  shared with three parallel sessions) exceeded the 10-min Bash limit and spilled to the background mid-restore;
  the restore still ran and was confirmed by bytes and `git status`. *Next time:* repeated slice-level bites are
  backgrounded from the start.
