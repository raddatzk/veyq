---
id: cr-visibility-poller
title: One visibility poller — a tick never overlaps another, and a stopped poller applies nothing
status: planned
satisfies: [uc.account.onboard-device, uc.memory.live-updates]
adrs: ["0033", "0059"]
invariants: []
---

## Goal

Five web pollers each hand-write the same loop: a `setInterval`, a `visibilitychange` listener that re-fires on
return, `stop`, and a `documentVisible` helper defined five times. None of them has an in-flight guard, so a
timer tick and a visibility tick, or two timer ticks around a slow request, run at once. None of them fences a
tick that is still running when `stop()` is called, so its result is applied after the stop.

For the pairing collect this is a real bug, measured at refine:
- the 2 s interval overlaps any collect slower than 2 s;
- if the server serializes the two collects, the loser's `404` flips the card to *gone* while the winner's
  onboarding tail is still running;
- if the server doesn't, the tail runs twice.

The server half is `cr-pairing-collect-single-use`.

After this CR, one `createVisibilityPoller` in `web/src/shared/state/` owns the loop:
- a tick never starts while one is in flight: it is skipped, not queued;
- `stop()` and a restart fence every in-flight tick, so its outcome is dropped.

All five pollers use it. Their intervals and visibility behaviour stay as they are.

## Context & constraints

- **The pollers:** `grep -rln "visibilitychange" web/src | grep -v '\.test\.'`. That finds the five in
  `features/tasks/`, `features/memory/` and `features/account/pairing/`. **A second angle found no sixth:**
  `grep -rn "setInterval" web/src admin-web/src | grep -v '\.test\.'` finds only these five. Their tests are
  `find web/src -name "*Polling.test.ts"`.
- **A factory, never a shared timer.** `grantEnvelopePolling.ts`'s header explains why the two pairing loops keep
  separate state. One loop reads the answer, which spends nothing; the other takes the grant, which spends the
  channel. A shared module-level timer would conflate the two silently, in the direction that loses the grant.
  `createVisibilityPoller` therefore returns an independent instance per call and holds no module-level state.
  Each poller keeps its own stop condition. What gets shared is the mechanism, not the state. Keep that header's
  argument, reworded to point at the factory.
- **Session-agnostic.** The envelope poller runs with neither a session nor a vault (its own header). The
  helper knows nothing about sessions. Stopping the session-bound pollers on sign-out is not this CR's concern:
  after [ADR 0070](../docs/decisions/0070-a-session-ends-with-a-new-document.md) a sign-out discards the page.
- **The fence is a signal, not a convention.** Each run gets an `AbortSignal`, and `stop()` or a restart aborts
  it. A tick checks it before applying anything (before `onEnvelope`, `onGone`, `onAnswer`, and before a store
  write). It may also pass it to the request. A tick whose side effects happen on the server mid-run (grant
  delivery, scope rotation) is not undone by the fence. There the in-flight guard is what matters, and the fence
  only stops local effects landing after a stop.
- **Behaviour to keep** — each of these has a criterion or a test that pins it:
  - [uc.memory.live-updates](../docs/features/memory/use-cases/uc.memory.live-updates.md): poll only in the
    foreground, stop on close, and E3: pause while hidden and tick at once on return;
  - [ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md);
  - the five existing `*Polling.test.ts` suites: the immediate first tick where there is one, skipping while
    hidden, re-firing on return, restart. Those suites stay green unchanged, and that is the migration's gate.
- **Out of scope:**
  - The scope-rotation poller can lease two rotation jobs for one scope at once. That is a server-side gap with
    its own inbox row (`ScopesStore.rotate` takes no expected epoch), and two tabs reach it without any
    overlap.
  - Adding the helper to Stryker's scope: `web/stryker.mutate.json` holds crypto logic only, per ADR 0045.

## Phases

The defect is live, so the order is guard → red → fix → green.

- [ ] 1 web — **the overlap test, red first.** In `grantEnvelopePolling.test.ts`, hold a collect open (a promise
      the test resolves by hand) and write three tests:
      - a `visibilitychange` and a timer tick during the held collect start no second request;
      - `stop()` during it, then resolving it with an envelope, fires no `onEnvelope`;
      - a restart during it, then resolving the *old* request, applies nothing from it.

      Run them against today's poller: they must go red. That is the live defect.
      · Done when: those tests are red on today's code, each for its own reason.
- [ ] 2 web — **the helper.** `web/src/shared/state/visibilityPoller.ts` (`createVisibilityPoller`) with its
      own unit tests: skip-not-queue, the fence on stop and on restart, the immediate first tick as an option,
      and hidden → no tick → visible → one tick. Test with fake timers and the `visibilityState` stub the five
      suites already use.

      **Bite:** remove the in-flight check and the skip test must go red; remove the abort on `stop()` and the
      fence test must go red.
      · Done when: `npm run check` is green.
- [ ] 3 web — **migrate all five.** Each poller becomes an instance of the helper, and its tick checks the signal
      before applying. The five `documentVisible` copies go.

      Locator for "done": `grep -rn "documentVisible\|visibilitychange" web/src | grep -v '\.test\.'` finds only
      `shared/state/visibilityPoller.ts`, and `grep -rn "setInterval" web/src | grep -v '\.test\.'` finds that file
      or nothing, if it schedules itself with `setTimeout`.

      The phase-1 tests go green. Add the same held-request test to `pairingAnswerPolling.test.ts`, which shares
      the collect endpoint.
      · Done when: `npm run check` is green, with all five existing suites unchanged and green.
- [ ] 4 coverage — `uc.account.onboard-device.yaml` stays `status: planned` (no CI run yet, as its header
      says). Its web gap text names the phase-1 tests under `a-pairing-is-single-use-short-lived-and-not-probeable`.
      `uc.memory.live-updates` cites the helper's tests wherever it cites a poller suite for the foreground/E3
      criteria (`grep -n "Polling.test" docs/coverage/features/memory/use-cases/uc.memory.live-updates.yaml`).
      · Done when: `python3 scripts/validate_coverage.py` and the trust-chain are green.

## Definition of done

- One poller mechanism: the phase-3 locators show a single site.
- A held-open request proves no overlap and no application after a stop, for the pairing pollers and for the
  helper itself.
- The five original suites are green, unchanged.
- `npm run check`, `validate_coverage.py` and the trust-chain are green.
- **No Python guard:** the phase-3 locator is the one-shot proof, and a sixth hand-written `setInterval` poller
  would be a new duplication rather than this one coming back. The helper's bite tests are the permanent proof
  of the property itself.

## Retrospective

