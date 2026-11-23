---
id: cr-native-test-races
title: The two reds under load in the native core's tests — a marks read landing over a mark just made, and the bridge's account flows test
status: in-progress
satisfies: ["en.memory.favorites"]
adrs: ["0153", "0149", "0156"]
invariants:
  - "a mark the person made while a read of the marks was in flight is never put back by that read"
  - "no test that keeps anything reads how full the host's volume is (SizedVolume)"
---

## Goal

Inbox #1100 and #1101: find each red's cause deterministically and fix it where it lives.

- **#1100** `QueuedOverlayTest` (`composition`) red once under load: `marks[x2]?.mine` null right after an offline
  `toggleMark`.
- **#1101** `AccountFlowsDispatchTest` (`bridge`) past its 10 s under load.

## Plan

1. #1100: reproduce by holding the marks listing at a gate (no load, no loop); fix in `ItemMarks` / `GalleryModel`;
   bite the new tests.
2. #1101: measure what the test spends its time on; fix the cause.
3. The live tests that keep anything on the host volume move to `SizedVolume`.
4. Harvest into ADR 0153 (Consequences), inbox rows to done, this CR deleted.

## Decision log

1. **#1100 is a product race, not only the test's.** `GalleryModel.read` launched `readMarks(here.marks)` with the
   outbox's marks taken *before* the listing; `ItemMarks.load` then assigned the listed sets whole after its suspending
   `store.list()`. A `toggleMark` offline between the two (`queueMark` then `setLocal`) was overwritten by the listing
   and not restored by the stale overlay — the person's mark vanished until the next read. The test met it because
   `waitFor { marksLoaded }` was already true from the first read while `reload()`'s marks read was still in flight.
   Online the same: a listing taken before a toggle's put, landing after it, put the landed mark back.
2. **The fix lives in `ItemMarks`:** its state (sets, loaded, a count of local edits) is one value swapped atomically
   (`MutableStateFlow.update`: the page's reads run on `Dispatchers.Default` while a tap marks from another thread);
   `load` notes the edit count before listing and, if the own set changed meanwhile, takes every other member's set
   from the read and keeps the shown own set. A failed toggle puts back only the own set, not the others' a read
   brought meanwhile.
3. **The offline overlay is read after the listing, inside `load`** (`load(overlay)`), never a copy taken before it —
   a mark discarded from the tray while the listing was in flight no longer comes back.
4. **Reproduced by schedule, not load:** `ItemMarksTest`'s played listing waits on a `CompletableDeferred` gate under
   `runTest`; three tests, each bitten (see the class's KDoc).
5. **#1101 is the test JVM's cold start.** Measured with a probe around `signedIn` (removed after): the class runs
   first in the bridge's JVM, the first sign-in 295 ms, the next four 0 ms, the class 2.7 s at load 7–10; Argon2 stays at
   the suite's one parameter set (a wrap at any other is refused before Argon runs). The JVM's first bridge call waits up
   to 60 s (`Drive.kt`), every later one keeps its 10 s. Rejected: every budget raised; a warm-up per class.
6. **Seven live tests move to `SizedVolume`** — each opened its offline store over the host's volume.
