---
id: cr-place-untouched-map-sets-no-point
title: An untouched map in the place editor sets no point; a stored point at the opening view's centre reads as none
status: planned
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

(filled by the implementer)
