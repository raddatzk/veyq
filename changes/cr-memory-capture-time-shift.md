---
id: cr-memory-capture-time-shift
title: A selection's capture time is corrected through one of its media — the rest follow by the same offset
status: done
satisfies: ["uc.memory.buckets"]
adrs: ["0077", "0034", "0012"]
invariants:
  - "the shift is client-only: each medium is one updateMediaMeta with the revision it read; no new operation, no bulk write, no server-visible offset (the server sees N ordinary sealed meta writes, as it sees N lightbox corrections)"
  - "a medium is never shifted against a capture time it no longer has: a peer's change of its override since the selection was read refuses it, and it is named"
  - "a medium without a capture time is never given one by a shift"
  - "the editor (single and shifted) is offered only where rights.editMeta and the source's mediaMeta door both hold"
---

## Goal

Build [uc.memory.buckets](../docs/features/memory/use-cases/uc.memory.buckets.md) § *Correct a selection's capture
time*: in the Beiträge tab a member selects media, corrects the capture time of **any one** of them in the lightbox,
and may carry that medium's offset to every other selected medium. A camera with a wrong clock is fixed in one edit
instead of one per photo. Web only; no contract, no backend change.

## Context & constraints

Measured 2026-11-25 on `claude/memory-bulk-edit-actions-39a608` at `6444d9689` (clean before this refine):

- **The per-medium write exists and stays the only write.** `setMediaMeta` (`src/web/src/features/memories/media.ts`)
  re-seals the whole raw `wrappedMeta` with `capturedAtOverride` set or deleted, through core's `writeSealedMeta`
  (re-bases on a `409 stale_revision` up to five times; throws `MetaConflictError` when a peer changed
  `capturedAtOverride` itself). It is reached through the source's `mediaMeta` door (`source.ts`, `set:`), which a
  guest's source does not carry. Server: `PUT …/media/{mediaId}/meta` (`updateMediaMeta`), `WRITE`-checked.
- **No bulk meta write exists**, and none is added: the selection's trash already runs N single writes under
  `Promise.allSettled` and names refusals through `refusalsByReason` (`mediaRefusal.ts`) — the pattern to follow.
- **The lightbox lives in `MemoryDetail.vue`**, not in `MemoryBuckets.vue`: the organizer emits
  `openLightbox({ items, index })` and holds the selection (`selected`, a `Set` of media ids) itself.
- **`effectiveCapturedAt` = `capturedAtOverride ?? capturedAt`** (`media.ts`); a medium with neither is undated.
- **Found in passing, fixed here (same editor, same right):** `MemoryDetail.vue` hands the lightbox `source.mediaMeta`
  without checking `can.editMeta` — the track-label editor beside it does check. While a scope is pending deletion or
  the application is switched off, the lightbox offers an edit the server refuses. The shift would inherit that.
- **Found in passing, not fixed here:** a meta write moves the medium to the end of the listing order (`MediaStore`
  `placeAt` in `setMeta`), and the Beiträge grid shows listing order (only the Gallery tab sorts by capture time). So a
  shifted selection jumps to the grid's end. A single correction does the same today. Filed as an inbox row by this
  refine (see Decision log); out of this CR.
- Guests: the Beiträge tab is members-only (`rights.ts` `buckets`), and no guest route writes media meta — nothing to
  decide for a link.

## Decisions (settled here)

1. **Anchor = the medium the member edits** (owner 2026-11-25: "korrigiert dann irgendeines davon"). Offset =
   new time − that medium's effective capture time before the edit, in ms.
2. **Entry points.** (a) The lightbox's existing capture-time editor: when the opened medium is in a selection of two
   or more, Save offers *only this one* / *all N shifted by +1 h 12 min*. (b) A **"Shift capture time"** button in the
   selection bar (disabled under two selected or without `editMeta`) opens the lightbox on the first selected medium
   in grid order with the editor open. The selection reaches the lightbox through the `openLightbox` payload (an
   optional set of ids) — the lightbox does not reach into the organizer.
3. **Per other medium:** undated → skipped and counted; else new override = effective + offset; if that equals its own
   original `capturedAt`, the override is **cleared** (a reset), so "Corrected" stays truthful.
4. **Conflict rule.** The shift writes each medium through `setMediaMeta` with the `capturedAtOverride` it was read
   with; a `MetaConflictError` refuses that medium (not re-shifted from the peer's value). Epoch refusals go through the
   existing `writeAtNodeEpoch` path. Refusals are named one line per reason via `refusalsByReason`.
5. **Concurrency:** bounded (a small fixed pool, e.g. 4), with a progress count in the save; no cap on the selection
   size beyond what the selection already allows. The memory's `auto` date reconcile runs once after the batch, not
   per medium (implementer: confirm against `memoryDate.ts`'s reconcile trigger).
6. **Undo bar** like the selection's trash: restores each written medium's previous override (or none) with the same
   per-medium write and the same refusal naming. Media refused by the shift are not touched by Undo.
7. **Reset to original stays per medium.** A shift is an offset; a reset is not.
8. **The shift computation is a pure function** in its own module beside `media.ts` (input: anchor before/after, the
   selection's opened metas; output: per medium `set | clear | skip-undated`), unit-tested without a DOM.

## Inventory (locators — run, read as lists)

| What | Command (from the repo root) |
|---|---|
| Writes and readers of the override | `git grep -nE "setMediaMeta\(\|capturedAtOverride" -- src/web/src` |
| Lightbox wiring and the selection's partial-failure pattern | `git grep -nE "openLightbox\|onCapturedAtUpdated\|refusalsByReason" -- src/web/src` |
| The editMeta gates beside the lightbox | `git grep -nE "editMeta\|mediaMeta" -- src/web/src/features/memories/components/MemoryDetail.vue` |
| Tests on the per-item correction (extend, don't duplicate) | `git grep -nlE "capturedAtOverride\|correct the capture time\|MetaConflictError" -- src/web/src tests/e2e` |
| Site pages naming capture time / the selection (EN+DE) | `git grep -nliE "capture time\|Aufnahmezeit\|sub-bucket\|Unter-Bucket" -- site/src/content` |
| i18n keys (EN+DE together) | `src/web/src/features/memories/i18n.ts` (`editCapturedAt`, `memory.buckets.*`) |

## Phases

Single phase (web + docs), committed in two commits if convenient: the `editMeta` gate fix first, then the shift.

- The gate fix: the lightbox gets `source.mediaMeta` only where `can.editMeta` holds; a test that mounts the detail with
  `editMeta: false` finds no editor (bite: drop the check, the test goes red).
- The shift module + its unit tests: offset sign both ways, clear-when-equal, undated skipped, anchor undated → no shift
  offered, ids outside the selection untouched.
- Lightbox + organizer: the two-choice save, the selection-bar button, progress, refusal naming, Undo. Component tests
  in `MediaLightbox.test.ts` / `MemoryBuckets.test.ts`: a peer conflict on one of three refuses that one and names it
  while the other two are written (positive control on the same run); Undo restores both.
- An L1 e2e (`tests/e2e/tests/features/memories/`): select three, shift through the middle one, reload, assert all
  three corrected times and their order — today no e2e saves an override at all.
- Docs: `uc.memory.buckets` § *Correct a selection's capture time* loses "planned"; its coverage file gains the
  criteria with refs (`docs/coverage/features/memory/use-cases/uc.memory.buckets.yaml`); `en.memory.buckets`'s
  "planned" sentence becomes built; the site's `memories/contributions.mdx` and `memories/the-gallery.mdx` (EN + DE)
  name the shift in Use-area tone.

## Definition of done

- `pnpm run check` in `src/web/` green; the L1 spec green through the worktree's own e2e orchestrator (machine hint:
  pin `VEYQ_INFRA_DIR` to the main checkout's `infra/docker`).
- A browser pass clicked: select three in Beiträge, shift via the lightbox, see "+… " offered, the three corrected, Undo.
- `scripts/validate_changes.py`, `validate_coverage.py` and the rest of the trust chain green.
- The PR carries a `## Release note` (Use-area tone, EN).
- This CR deleted; the inbox row it filed stays.

## Decision log

- 2026-11-25 (owner): the anchor is any selected medium the member edits; undated media stay as they are.
- 2026-11-25 (refine): entry points, clear-when-equal, conflict-refuses, Undo, per-medium writes only — decided here,
  reported to the owner. The `editMeta` gate gap folded in; the listing-order jump filed as inbox #863.
- 2026-11-25 (refine): `uc.memory.buckets` and `en.memory.buckets` said capture time is edited by "any key-holder";
  the code is a `WRITE` member — the docs were corrected in this refine.
- 2026-11-26 (implement): the offset is taken against the anchor's effective time **as the minute-granular editor
  showed it** (floored to the minute), and applied to every selected medium, the anchor included, so seconds are kept
  and the offset written out is the one the member saw. The CR's "in ms" over the raw time would have shifted by
  seconds nobody saw. Recorded in uc.memory.buckets step 3.
- 2026-11-26 (implement): the selection is read **once, when Save is pressed** (a copy of each item, `ShiftRead`): the
  live poll patches listed items in place, so a run that re-read them could shift onto a peer's tier that arrived
  between the question and the choice. Bitten by its own test.
- 2026-11-26 (implement): the run and its Undo live in the detail (`useCaptureTimeShift`), not the lightbox: the Undo
  bar outlives a closed lightbox. Two new refusal reasons, `changed` (MetaConflictError) and `unreadable`.
- 2026-11-26 (implement): decision 5 confirmed against the code — the auto date reconcile runs at the gallery's list
  refreshes (`useMediaGalleryLive` `onSettle`, one at a time), never on a meta write; the shift adds no settle call.
- 2026-11-26 (implement): two findings from the browser pass on the release candidate, not fixed here, filed as inbox
  #864 (Escape that closes the lightbox also dismisses the Undo bar) and #865 (the date field's calendar covers the
  lightbox's Save).

## Retrospective

- **Planning — the offset was specified at a granularity the control does not have.** Decision 1 said "new time minus
  the anchor's effective time, in ms"; the editor is minute-granular, so a photo taken at 10:03:27 would have moved the
  selection by +1 h 11 min 33 s while the member saw 10:03 → 11:15. Root cause: the refine reasoned about the stored
  value, not the control the input arrives through. Fix: harvested as one sentence in `docs/ai/README.md`'s refinement
  checklist (name the control's granularity when a decision computes from what it shows).
- **Planning — "with the override it was read with" did not say when "read" is.** The live poll patches listed items in
  place, shared with the lightbox's list, so a run that re-read them at the choice could have sealed the shift onto a
  peer's tier that arrived between the question and the choice. Found while writing the conflict test, fixed by reading
  once at Save (`ShiftRead`), bitten. The trap now sits as a comment at both lines.
- **Execution — the live substrate was not the one planned.** The brief's machine hint (the owner's dev backend serves
  the pass) did not hold: that backend answered 500 on every route (a neighbour's contract change hot-reloaded into it,
  `ClassNotFoundException: PinUnlockRequest`). Rather than restart a backend compiling another session's half-done
  tree, the pass ran on the L1 release candidate (`run_stack_e2e.py --keep`), which is what ships. The first L1 run was
  red on two real-browser facts no unit test sees (a tile's box drawn over its checkbox; the calendar over Save) — the
  spec now does what a person does, and #865 records the second. Process: the commit after that run died on a 1Password
  signing error, so the last commits (spec fix, harvest, inbox rows, this retrospective, the CR's deletion) were left
  for the owner's session, as the brief says.
- **Next time:** a refine that adds a write over a reactive list names who else patches that list and when the write
  reads it; and a brief's live-pass hint names a fallback substrate (the L1 stack with `--keep`) for when the shared dev
  backend is down.

