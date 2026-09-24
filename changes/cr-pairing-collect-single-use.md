---
id: cr-pairing-collect-single-use
title: Collecting a pairing grant spends the channel atomically — two racing collects get one grant
status: planned
satisfies: [uc.account.onboard-device]
adrs: ["0059"]
invariants: [operator-blind E2EE, API-first]
---

## Goal

The pairing ceremony is single-use: handing over the grant spends the channel, and the contract promises that
*"a second collect answers as a dead channel does"* (`contracts/api/auth.openapi.yaml`, `collectPairingChannel`;
[ADR 0059](../docs/decisions/0059-device-linking.md) §8). The backend keeps that promise only when the two
collects arrive one after the other:
- `PairingHandler.collect` reads the row without a lock;
- `PairingStore.markCollected` then flips the status unconditionally
  (`update … set status = COLLECTED where id = ?`);
- the envelope is returned whether or not that flip changed anything.

So two collects that arrive together both read `SEALED` and both receive the grant. On the joining device this
runs the onboarding tail twice, which `web/src/features/account/pairing/onboardTail.ts` rules out in its own
words: an orphaned device wrap, two register writes, and a token adopted twice. That last one can reach
distance zero on the next refresh, and reuse detection then revokes the new device's family (ADR 0004).

The client makes the race likely. Its collect poller ticks every 2 s on `setInterval` and has no in-flight guard,
so any collect slower than 2 s overlaps the next one (inbox #174; the web half is `cr-visibility-poller`).
Fixing the client alone would leave the server's promise untrue for every other way two collects can meet.

After this CR the flip is a compare-and-set, and only the collect that wins it gets the envelope. The loser
answers exactly as a spent channel does.

## Context & constraints

- **No contract change.** The contract already says it. This CR makes the backend honour it
  (`grep -n "a second collect" contracts/api/auth.openapi.yaml`).
- **The precedent to mirror, including its defences:** `LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`.
  It releases two requests through a `CyclicBarrier(2)`, then asserts exactly one winner and exactly one
  *specific* loser code. The loser code is the negative's anchor: a bare "one of them failed" is satisfied by a
  boot error just as well.
- **The sibling writes are already compare-and-sets:** `recordAnswer` and `recordEnvelope` guard on the
  expected status (`grep -n "and status = ?4" backend/src/main/kotlin/com/veyq/app/features/account/pairing/PairingStore.kt`).
  `markCollected` is the one that doesn't: `grep -n -A5 "fun markCollected" …/PairingStore.kt`.
- **Reading the answer still spends nothing.** Only the envelope branch changes. The trusted device polls the
  same endpoint for the answer, and `PairingSliceTest.readingTheAnswerSpendsNothingButTakingTheGrantSpendsTheChannel`
  pins that half.
- **Out of scope:** the client poller (`cr-visibility-poller`). Also a second compare-and-set gap found in the
  same review: `ScopesStore.rotate` takes no expected epoch (its own inbox row).

## Phases

- [ ] 1 backend — **test first, against today's code.**
      - Add `PairingSliceTest.concurrentCollectsHandOverTheGrantOnce`. Take a channel to `SEALED` through the
        real verbs, release two collects through a barrier, and assert exactly one `200` carrying the envelope
        and exactly one `404` with the dead-channel code.
      - Keep a live control in the same test: a fresh channel's single collect still gets the envelope.

      **Run it on today's code first.** It must go red, with two `200`s. If it is green on H2 because both reads
      don't land before either update, the test is not proving the race. Then add a store-level test as the
      bite: two `markCollected` calls on one `SEALED` row, and the second must report that it changed nothing.
      Record which of the two tests actually bit in the retrospective ([changes/README](README.md) § *A bite test
      you cannot make red is telling you the design is not the one the doc describes*).
      · Done when: the new test(s) are red on today's code, with that failure.
- [ ] 2 backend — **the compare-and-set.** `markCollected` guards on `status = SEALED` and returns whether it
      changed a row. `collect` hands over the envelope only when it did; otherwise it answers as the dead
      channel does, which is `null` → the same 404. The answer-only branch is unchanged.
      · Done when: `./gradlew check` is green, including the phase-1 tests and every existing `PairingSliceTest`
      case.
- [ ] 3 coverage — `uc.account.onboard-device.yaml` is no longer `planned` (2026-09-24, ADR 0040's note on
      which rows a run has admitted): the new test is cited as a backend ref under
      `a-pairing-is-single-use-short-lived-and-not-probeable`, beside that criterion's other `PairingSliceTest`
      refs.
      · Done when: `python3 scripts/validate_coverage.py` and the trust-chain are green.

## Definition of done

`./gradlew check` is green. Two concurrent collects of one sealed channel yield exactly one grant, proven by a
test that was red before the fix. The coverage gap text names that test. No new guard: the slice test is the
permanent proof, and it runs in `./gradlew check`.

## Retrospective

