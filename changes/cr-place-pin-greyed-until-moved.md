---
id: cr-place-pin-greyed-until-moved
title: The place editor's centre pin is greyed out until the member moves the map
status: planned
satisfies: [uc.memory.place]
adrs: ["0016", "0018"]
invariants:
  - the pin's look follows the same "the member moved the map" signal that decides whether a point is stored (cr-place-untouched-map-sets-no-point); never a second notion of it
  - dark mode through the layer's tokens (ui-web-styleguide), no raw colour
  - no backend and no contract change
---

## Goal

[uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md) E3 (owner, 2026-11-09). After
`cr-place-untouched-map-sets-no-point`, the editor says "Nur ein Name - ohne Position" while a full pin still stands at
the map's centre, so it reads as a point that is not there.

- **No point set:** the centre pin is drawn greyed out (muted, reduced opacity), and a short hint near it or in the
  pill says to move the map to set the position (de "Karte verschieben, um die Position zu setzen").
- **After the member's first move,** the pin is drawn as today, and the pill switches as it already does.
- **With a point set when the editor opens** (a medoid or a stored point), the pin is drawn normally from the start.

## Context

- **Where it lives:** `src/web/src/features/memories/components/MemoryLocationEditor.vue` and `MemoryMap.vue`, as
  `cr-place-untouched-map-sets-no-point` left them (read its `## Retrospective`: how a user move is detected, and the
  pill text). Reuse that state; do not add a second one.
- **Tokens:** `src/web/src/styles/ui.css`, `dark.css` and `docs/crosscutting/ui-web-styleguide.md`.
- **The owner's running instance:** a backend on 8180 and Vite on 8189, from this worktree. The owner sees changes
  live.

## Phases

Single phase (web).

- **Build:** the greyed pin and the hint, EN+DE in Memories' catalog.
- **Tests:**
  - With no point, the pin carries the greyed state and the hint shows.
  - After a user move, it does not.
  - With a medoid, it never does.
- **Bite:** draw the pin normally regardless, and the greyed test goes red. Run it on a copy, never on a file the
  owner's Vite serves.
- **Browser pass:** on `http://veyq.localhost:8189/?devlogin` (do not restart it). Open the place editor of a Memory
  without GPS, with the map services on and the tile and geocoder hosts mocked in the browser so nothing leaves the
  machine. Take a screenshot before and after a drag, in light mode and in dark mode.
- **Docs:** the Use page `memories/set-the-location.mdx` EN+DE if it describes the pin; coverage `uc.memory.place.yaml`,
  extending the E3 criterion.
- **Done when:** `pnpm run check` is green in `src/web/`, and the trust-chain guards are green (`/usr/bin/python3`,
  guard_gaps skipped).

## Retrospective

(filled by the implementer)
