---
id: cr-place-untouched-map-sets-no-point
title: An untouched map in the place editor sets no point; a stored point at the opening view's centre reads as none
status: done
satisfies: [uc.memory.place]
adrs: ["0016", "0018"]
invariants:
  - a position is stored only from a medoid of photo GPS or from a map the member moved (uc.memory.place E3)
  - no new request to a map or geocoding service; nothing changes on the server (the place is sealed meta)
  - no backend and no contract change
---

## Goal

Inbox #834, owner 2026-11-09: "Fix #834 als kleinen CR". [uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md)
E3 now states the rule.

1. **An untouched map sets no point.** The editor opens with the map on a Memory that has no photo GPS. If the member
   saves without moving the map, the place is stored as name-only (`derivedMode: 'auto'`, no `derived`), not as a
   `fixed` point at the view the editor opened on.
   - **Today:** the opening view is the world view (centre about (20, 0)), so the Memory lands in the Sahara on every
     map.
   - **Found by:** `cr-map-services-off`'s browser pass, which did exactly this to *Roma 2024* on the `veyq-search`
     stack.
2. **A point already stored that way reads as none.**
   - **The rule:** a `fixed` `derived` equal to the editor's opening-view centre, compared against the one constant
     the editor uses and not against a literal copy, is read as "no point". It is then no pin, no map position, and no
     facet for the search's place index if that reads coordinates.
   - **Why it is safe:** the server cannot fix sealed meta, and a real place at exactly that coordinate is
     implausible. Say in a code comment why the equality is safe and where the constant lives.
   - **Clean-up:** the next save of that Memory's place drops the point.

## Context (the implementer re-reads)

- **Where to look:** find the editor and its opening view with `rg -ln "MemoryLocationEditor|derivedMode|fixed" src/web/src/features/memories`.
  The world-view centre lives in or near `features/memories/components/SpaceMap.vue` and the location editor.
  - **Known gap:** `rg -n "center: \[20|20, 0\]" src/web/src` found only `SpaceMap.vue`. The editor's own default may
    be spelled differently; find the one constant both use, or make one.
- **Where the reads are:** `features/memories/memoryLocation.ts` (`normalizeLocation`, `unsealMemoryLocation`). The
  map's pins come from `spaceMap.ts`.
- **What "moved" means:** the editor must know whether the member moved the map (pan or zoom by the member, not a
  programmatic fit). Leaflet or MapLibre distinguish user moves from programmatic ones by their event `originalEvent`
  or a flag. Use whichever the map library here offers, and test it.

## Phases

Single phase (web).

- **Build:** the two items.
- **Tests:**
  - Save untouched with the map and no GPS, then the stored location has no `derived`, `derivedMode` is `auto`, and
    the name is kept.
  - **Control:** after a user move, a `fixed` point is stored.
  - A stored `fixed` point at the opening centre reads as none (no pin); a point a hair away still reads as a point.
  - Re-saving drops it.
  - With photo GPS, the medoid is kept as today.
- **Bites:**
  - Store the opening view again, and the untouched test goes red.
  - Drop the read-side rule, and the stored-point test goes red.
- **Browser pass:** reuse the owner's running instance at `http://veyq.localhost:8189/?devlogin`. Do NOT stop or
  restart its backend (8180) or Vite (8189).
  - *Roma 2024* on that stack carries the bad (20, 0) pin. After the fix it shows no pin on the level's map, and
    re-saving its place removes the point for good.
  - With the map services on, mock the tile and geocoder hosts in the browser (as `cr-map-services-off`'s pass did);
    no request may leave the machine.
  - Desktop, one final screenshot.
- **Docs:** coverage `uc.memory.place.yaml` (a criterion for the E3 rule, with web refs; the backend part "none"); the
  Use page `memories/set-the-location.mdx` EN+DE only if it describes setting a point.
- **Done when:** `pnpm run check` is green in `src/web/`, and the trust-chain guards are green (`/usr/bin/python3`,
  guard_gaps skipped).

## Retrospective

Implemented 2026-11-09 in the worktree `search-design`, on `8e5a37eb7`, in one commit.

**What was built.**
- **One constant.** `WORLD_VIEW` (`{ center: (20, 0), zoom: 2 }`) in `features/memories/memoryLocation.ts`; the editor's
  private `WORLD` and `SpaceMap`'s literal `setView([20, 0], 2)` both read it now. Its comment says why the equality is
  safe and that the centre must not change (old points would no longer be recognised).
- **Read side.** `normalizeLocation` reads a `fixed` `derived` exactly equal to `WORLD_VIEW.center` as no point: `auto`, no
  `derived` (a name only, so the first geotagged photo gives it the medoid). Every reader goes through it (the level's and a
  person's map via `unsealMemoryLocation`, the header, export/import, the participant index, the search, which reads only
  the name). The name-required check runs on the stored mode first, so an invalid `fixed` place does not become "pending".
- **Write side - how a member's move is told.** Leaflet tells a user pan (`dragstart`) but no user zoom (wheel, zoom
  buttons, double click, keys, pinch all call the same `setView` a programmatic move does). So `MemoryMap` marks the next
  frame `user` after the member's own input on its container - `pointerdown`, `wheel`, `keydown`, caught in the capture
  phase (the zoom buttons stop their events from bubbling) - and clears the mark after each reported frame and before every
  programmatic `setView` / `panTo` / `fitPoints`. The old `dragstart` flag is gone (a `pointerdown` precedes every drag).
- **Editor.** A frame that is not the member's never creates a position (`onFrame`: `!f.user && !derived` returns), so the
  opening view is neither stored nor looked up at the geocoder. `save` stores `derivedMode: 'auto'` when there is no point;
  Save needs only a name (E3: saved untouched it is a name only - no "disable Save until a pan", which the previous CR's
  retro had guessed). The pill reads *Name only — no position* while the map sets no point. The turn-on path (A6) is fixed
  by the same line.
- **Docs.** Coverage `untouched-map-sets-no-point` (web refs; backend none, client-only). The Use page EN+DE: one sentence
  in step 2 (without geotagged photos the map shows the world; left as it is, the place is a name only).

**Tests and bites.** `pnpm run check` in `src/web/` green (544 files, 6633 tests, build and dist asserts); 69 guards green
with `/usr/bin/python3` (`validate_guard_gaps.py` skipped, no validator changed). New
`MemoryLocationEditor.untouchedMap.test.ts` (real `MemoryMap` over a fake Leaflet that keeps its view): untouched save,
the pan and wheel controls, a move without input sets nothing, a stored opening-view point re-saved drops it, the medoid kept,
a hand-set point kept. `memoryLocation.test.ts` (equality vs a hair away, `auto` untouched), `spaceMap.test.ts` (no pin, a
hair away pinned). `MemoryLocationEditor.withdraw.test.ts` now pans before it expects a pending lookup (its opening frame
used to schedule one - the defect). Bites on copies next to the served files (`*.bite1/2.*`, deleted after; `git status`
and the two files' hashes equal before/after): bite 1 (the `onFrame` guard removed) - the untouched test red on the stored
value; bite 2 (the read rule removed, swapped in by `vi.mock`) - the stored-point tests in all three suites red.

**Browser pass** (`veyq-search` stack, Vite 8189, desktop 1280, German; every outside request answered or aborted in the
browser - tiles a blank PNG, the geocoder `{}`). *Roma 2024* held raw `{ name: 'Lido di Ostia', manual, fixed, (20, 0) }`.
Before the re-save the map model pinned nothing and the editor's pill read *Nur ein Name*; the level's map (*Reisen*,
Kartenansicht, services on) shows *Noch keine Orte* - no Memory of the space has a point. Re-saved untouched: after a reload
the raw meta is `{ name: 'Lido di Ostia', manual, auto }`, no `derived`. With the real Leaflet a mouse drag and a wheel zoom
each flip the pill to *Eigener Ort* (cancelled, nothing saved). Shot: `p19-desktop-roma-name-only.png`.

**What went wrong.**
- My first pass step expected the level's map to be drawn; with no pin in the space it shows its empty panel instead. A
  one-line probe of `SpaceMap`'s `v-if` chain would have said so. Next time: read a view's empty-state branch before a pass
  asserts what it draws.
- The crosshair still stands in the centre while no point is set; the pill says *Name only*, which I judged enough - an owner
  may want the crosshair dimmed until the first move (not built, a UX choice).
