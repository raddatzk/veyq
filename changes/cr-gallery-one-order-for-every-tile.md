---
id: cr-gallery-one-order-for-every-tile
title: one order for every grid tile — an in-flight upload sorts by capture time instead of pinning to the front
status: planned
satisfies: ["uc.memory.gallery", "uc.memory.upload"]
adrs: ["0033", "0044"]
invariants:
  - operator-blind — the sort key is derived on device from bytes the user already holds; nothing new crosses the wire, and no unsealed timestamp reaches the server
  - "one rule, one implementation — the gallery and the share viewer must not end up with two orderings; the same is true of the capture-time key, which `sealMetadata` and the in-flight tile now BOTH read"
  - "reuse the existing UI ([ui-web-styleguide](../docs/crosscutting/ui-web-styleguide.md) § Reuse first) — this CR adds no new control, no scroll behaviour, no new tile state"
  - "no contract change — `contracts/` is untouched, so `backend/` is untouched"
---

## Goal

Picking several files into a Memory shows them at once as optimistic tiles, and today every tile that *finishes*
jumps **behind** the ones still uploading. The cause is not the date sort: `useMediaGalleryLive.ts:109` builds
the grid as a hard two-block concatenation — every optimistic tile, then every listed media, each block sorted
on its own. A file leaves the front block the instant its `mediaId` lists, and reappears at the top of the
second block, which sits behind all the tiles still in flight. With ten files picked, that is ten backwards
jumps, followed by one wholesale re-shuffle when the last tile hands off and the front block empties.

This CR removes the front block. Every tile — in flight or listed — sorts by one key, and a tile that appears
already sits where it will stay: finishing an upload changes its **content** (local preview → real decrypted
thumbnail), never its **place**. The key is available before a byte is uploaded, because it is derived on device
from the file itself — `EXIF DateTimeOriginal`, else the file's own date — which is the identical rule
`sealMetadata` will seal into the metadata tier (`media.ts:287`). The same defect and the same fix apply to the
anonymous contributor viewer, which carries its own copy of the two-block layout (`ShareView.vue:153`).

## Context & constraints

- Ordering is owned by **[uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)** — Main
  flow step 2 and the quality note *"One order for every tile — including one that is still uploading"*, both
  written in the refinement this CR follows. **[uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md)**
  steps 2 + 5 and **[uc.memory.live-updates](../docs/features/memory/use-cases/uc.memory.live-updates.md)** step 4
  now point at that rule instead of restating a placement.
- The capture-time tier itself (`wrappedMeta`: geometry + capture time + GPS, cr-0066/0068) is described only in
  coverage AC prose — **no ADR owns it**. This CR does not create one: it changes *where a tile is drawn*, not
  what is sealed or how. The gap is real and larger than this work; it belongs in the inbox, not here.
- [ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md) is *why* this is a client-side
  sort at all — the capture time is sealed, so the server cannot order for us.
  [ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md) governs the poll that
  auto-inserts a tile into an open grid; that insert must land under the same one rule.
- **Explicitly out of scope — visibility.** With one list, a freshly picked tile appears at its capture-time
  place, which for older photos in a full Memory may be below the fold: the user picks and sees nothing move.
  The existing TopBar upload tray remains the feedback channel. A scroll-to-first-new-tile was weighed and
  **dropped by decision** — `grep -rn "scrollIntoView\|scrollTo(" web/src` returns nothing, so it would be a new
  UI primitive with no precedent in the client, introduced on suspicion rather than on a felt cost. If it proves
  necessary in use, that is its own CR with evidence behind it.

### Substrate

Measured **2026-09-20 against `main`**, working tree **dirty and shared** — and it moved *while this CR was
being written*, which is why the hazard is stated rather than assumed. Over the course of one grounding session
a **parallel session** committed four unrelated working-tree edits, deleted one finished CR and opened two new
ones (`changes/` went from three untracked CRs to four, with different names). The only uncommitted work that is
**this** CR's is the refinement of the three use-case `.md` files it builds on.

Every code line this CR cites was **re-verified against the tree after that drift** (`useMediaGalleryLive.ts`
109 + 164, `ShareView.vue` 153, `media.ts` 287, `MemoryDetail.optimistic.test.ts` 134) — all still stand. Line
numbers drift; re-read the surrounding code rather than trusting the number, and expect the neighbour's tip to
be ahead of yours. Therefore: **stage by explicit path, never `git add -A`, and address a commit by hash, never
by `HEAD~n`.**

### Inventory — the locators, not their output

| Question | Command |
|---|---|
| Which surfaces build the two-block grid? | `grep -rn "optimisticTiles" web/src \| grep -v "^web/src/features/memory/useOptimisticTiles.ts"` |
| Where is the claim written, in code and in docs? | `grep -rni "pinned ahead\|ahead of the listed\|pin ahead of\|freshest" --exclude-dir=node_modules --exclude-dir=.git .` |
| Which sites feed the capture-time fallback? | `grep -rn "lastModified" web/src/features/memory` |
| Which tests assert grid order today? | `grep -rln "sortDir\|date position\|ahead of the listed\|newest" web/src/features/memory/*.test.ts web/src/features/memory/components/*.test.ts` |

Two angles were run at refine time and they answer differently, which is the point: the **symbol** angle
(`optimisticTiles`) finds the two layout sites in `useMediaGalleryLive.ts` and `ShareView.vue`. The **claim**
angle finds those plus four prose sites the symbol angle cannot see — three code comments, one test comment,
and the coverage acceptance criterion `uc.memory.upload.yaml#optimistic-in-grid-tiles`, which is where *"pinned
ahead of the listed media"* is actually **decided**. A sweep keyed only on the symbol would have shipped the
code change and left the binding rule asserting the opposite.

**Spot-check one row** — the second command, since this CR's whole argument rests on the claim being written in
more places than the code. Run it before the first edit; if its output is smaller than the sites phase 4 names,
the site list is wrong, not the grep.

### Traps

- **`useMediaGalleryLive.test.ts` mocks `useOptimisticTiles` away** (it returns `computed(() => [])`, line 21ff),
  and `ShareView.test.ts` does the same. Neither can see tile order, so the ordering bite **cannot** live there.
  It belongs at DOM level in `MemoryDetail.optimistic.test.ts`, which mounts the real grid.
- **The lightbox index rides on the media-only order.** `layoutItems` hands each media tile an `index` into
  `sortedItems`, and `openLightboxAt` snapshots `sortedItems` (`useMediaGalleryLive.ts:164`). Because both kinds
  of tile sort on the same key, the media order *inside* the merged list stays identical to `sortedItems` — so
  this index stays correct **by construction**. That is a property to preserve deliberately, not a coincidence
  to rely on silently: if a later change ever sorts the two kinds differently, a click opens the wrong photo.
- **A video has no EXIF.** `readExif` returns early for a non-image, so its key is the file date on both sides —
  parity holds without a special case, and must not acquire one.

## Phases

- [ ] 1 web — **one capture-time key, one implementation.** Export from `media.ts` the rule
      `sealMetadata` already applies (EXIF date, else the file's date, plus the `'exif' | 'file'` source) and
      have `sealMetadata` itself consume it, so there is exactly one definition. `useOptimisticTiles` probes it
      per picked file in the pass that already probes dimensions + the video poster, and `OptimisticTileVM`
      carries the resulting key. · **Bite (injection, B2):** the tile's key equals the sealed `capturedAt` for
      the same file. Lever: drop `lastModified` from the tile's own call — that argument survives every later
      phase, so the bite cannot be disarmed by this CR's own completion. · Done when: `npm run check` green in
      `web/`, B2 green and demonstrably red under its lever.
- [ ] 2 web — **the merged list in the Memory gallery.** `layoutItems` becomes one sorted sequence over both
      tile kinds under the existing `sortDir`; undated tiles keep sorting last. The media tile's `index` and the
      lightbox snapshot stay keyed on the media-only order (see Traps). · **Bite (live defect, B1):** a completed
      upload keeps its grid slot — with two files in flight and one settling, the settled tile's DOM position is
      unchanged before and after reconcile. This reddens on the untouched tree; run it first, and read *which*
      assertion reddens, not merely that the test did. · Done when: `npm run check` green, B1 red before the
      change and green after.
- [ ] 3 web — **the same in `ShareView.vue`.** Its local `layoutItems` + `sortedItems` (hardcoded newest-first,
      no toggle) take the identical merge. · **Bite (live defect, B3):** B1's assertion at the share viewer.
      `ShareView.test.ts` mocks the tiles away, so this needs the mock lifted for that case or a sibling file
      that mounts the real grid — decide which at implement time and say so in the retro. · Done when:
      `npm run check` green.
- [ ] 4 docs + coverage — **supersede the claim, at every site the second locator returns.** The shape of each
      correction: `uc.memory.upload.yaml#optimistic-in-grid-tiles` is **narrowed** — the clause *"pinned ahead of
      the listed media"* is **deleted** and replaced by a pointer to the gallery's rule, the rest of the
      criterion (per-file reconcile, no empty-flash, no duplicate) stands unchanged.
      `uc.memory.gallery.yaml#date-sorted-grid` is **extended** with the in-flight half and gains the new bite as
      a ref. The three code comments (`useOptimisticTiles.ts:5`, `optimisticUpload.ts:5` + `:17`,
      `useMediaGalleryLive.ts:107`) and `ShareView.vue:152` are **rewritten** in the phase that touches their
      file, not in a sweep at the end. The existing assertion `MemoryDetail.optimistic.test.ts:134` (*"pinned
      ahead of the listed media (first tile in DOM order)"*) is **replaced**, not deleted — the test keeps
      proving that a tile appears immediately; only its expected *place* changes. · **Re-run the claim locator
      at this phase, not only from refine** — phases 1–3 author new comments about ordering, so the corpus this
      phase corrects is one the earlier phases have already grown. · Done when:
      `python3 scripts/validate_coverage.py` green and the claim locator returns no site still asserting a
      front-pinned tile.

## Definition of done

`npm run check` green in `web/`; `backend/` and `contracts/` untouched (a `git status` proving it). Every
guard green: run `for f in scripts/validate_*.py; do python3 "$f"; done` — and run it **before the first edit**
too, so a red afterwards is attributable to this CR rather than inherited. Both acceptance criteria updated in
`docs/coverage/` with the new bites as refs and `python3 scripts/validate_coverage.py` green.

No permanent guard is promoted here. The structural invariant this CR rests on — *one capture-time key, one
implementation* — is enforced by construction rather than by a check: phase 1 leaves a single exported
definition that both call sites consume, so a second definition would have to be deliberately written rather
than accidentally drifted into. B2 is the bite that proves the two call sites agree; a grep-based guard on top
of that would assert the absence of code nobody has a reason to write.

Live proof, once the phases are green: pick several photos into a Memory with the dev backend and watch a
middle one settle — its tile must not move. A tile that finishes and stays put is the whole deliverable.

## Retrospective

_To be filled at implement time — what went wrong in planning, in execution, and in process/collaboration
(did we honour the conventions we already agreed: language, workflow, the memory notes?), root cause, and one
concrete "next time"._
