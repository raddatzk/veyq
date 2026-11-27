---
id: cr-apps-capture-time-shift
title: The apps shift the capture time of a whole selection in the Contributions view, the web's rule in the Kotlin core
status: in-progress
satisfies: ["uc.memory.buckets"]
adrs: ["0144", "0149", "0155", "0161", "0077"]
invariants:
  - "each medium is one ordinary sealed meta write at the revision the selection was read at (ADR 0077): no bulk write, no offset the server sees"
  - "the shift's arithmetic answers as the web's `captureTimeShift.ts` does, case for case"
---

## Goal

Close the apps' gap against main's PR #97 (owner 2026-11-26): a member selects media in the Contributions view and
corrects the capture time of all of them through one, every other medium following by that medium's offset
(uc.memory.buckets § Correct a selection's capture time). The rule goes into the Kotlin core (`CaptureTimeShift`, the
web's `planCaptureTimeShift`/`offsetParts` case for case; `SelectionShift`, the run); each app offers it as a selection
action of its Contributions view, in its platform's controls.

## Phases

1. Core + bridge: `memories/CaptureTimeShift.kt`, `Contributions` selection (`picked`, `toggle`, `clearPicked`,
   `shift`), `GalleryModel.selectionShift`, `CaptureTime.write(exact)`, `GalleryModel.Door.shiftCaptureTime`; the wire's
   `Buckets.Toggle|ClearPicked|Shift`, `CaptureTime.Shift*`, `Watched/Update.CaptureTimeShift`; the words.
2. Android, Apple, Linux, Windows: a Select mode in the Contributions grid, the bar's "Shift capture time", the dialog.
3. Harvest: uc.memory.buckets, ADR 0149 (the Contributions' rest), native-apps, the Use pages EN+DE; delete this CR.

## Decision log

- **The anchor is the first selected medium with a capture time** (in the order the member selected them), not a medium
  the member pages to in a lightbox as on the web: the apps' Contributions view has no lightbox over the selection
  alone, and "select first the photo whose right time you know" keeps the web's one-medium-sets-the-offset rule with
  one control. A selection with no dated medium is offered no shift (the web would offer "only this one", which the
  details' own editor already is).
- **The dialog asks the time first, then the choice** (*Only this one* / *All N, shifted by …*), as the web's save
  does; the undated count beside the choice.
- **The Undo is in the dialog's last step**, not a timed bar: one surface per platform and no timer of the platform's
  own (ADR 0154 decision 4). It reads the gallery anew and refuses a medium whose override is no longer the one the
  shift wrote (a peer's change meanwhile) — named, as the web's Undo refuses through the write's revision.
- **A shift write keeps its seconds** (`CaptureTime.write(exact = true)`, `Door.shiftCaptureTime`): the core's single
  correction holds to the minute, the web's shift writes `effective + offset` exactly; online only, never queued (the
  web's is online only too).
- **A refused medium is named by its sealed file name, else "Item n" by its place in the selection** (the web numbers
  by the grid): the apps' view has the selection's order at hand, not the web's grid numbering.
- **The wire's names stay under the `Buckets` and `CaptureTime` prefixes** (`Buckets.Shift`, `CaptureTime.ShiftAll`, …),
  which `validate_mobile_ui_direction.py`'s table already gives to Memories.
- Concurrency 4, as the web's `SHIFT_CONCURRENCY`.

## Retrospective

(filled at harvest)
