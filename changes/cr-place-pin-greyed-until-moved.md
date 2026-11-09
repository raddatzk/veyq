---
id: cr-place-pin-greyed-until-moved
title: The place editor's centre pin is greyed out until the member moves the map
status: done
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

Implemented 2026-11-09 in the worktree `search-design`, on `8dcfc1393`, in one commit.

**What was built.**
- **One signal.** The editor's `pointSet` (`Boolean(anchorPoint)`) is the one answer to "is a point set": the pill's
  *Name only*, the greyed pin and the hint all read it. With no medoid it turns true only at the first frame the member
  produced (`onFrame`'s `user` guard from the previous CR), so no second notion of "moved" exists.
- **The pin.** `MemoryMap` takes `crosshairUnset`; the crosshair then carries `is-unset` - `--app-muted` at 0.6 opacity
  with a lighter shadow, a 0.2 s colour/opacity transition only under `prefers-reduced-motion: no-preference`. The
  rule sits beside the crosshair's own rule in `MemoryMap.vue`'s scoped style, not in `memories.css`: the crosshair's
  whole look already lives there, and a global rule for a scoped element would tie with it on specificity and win by
  load order. Tokens only, so no dark twin.
- **The hint.** *Move the map to set the position* / *Karte verschieben, um die Position zu setzen*
  (`memory.place.moveToSet`), a chip right under the pin's tip, styled as the pill (`--app-text` on `--app-surface`,
  a pair the contrast guard already holds), `pointer-events: none` so a drag starting on it moves the map. The wording is
  the CR's default - an owner question.
- **Docs.** Coverage `untouched-map-sets-no-point` extended (same refs). The Use page EN+DE, step 2: without such photos
  the pin is greyed out, move the map.

**Tests and bites.** Three cases in `MemoryLocationEditor.untouchedMap.test.ts` (no point: greyed + hint + *Name only*;
a move that is not the member's leaves it greyed, their first move draws it set, drops the hint, flips the pill; a
medoid - also after a move - and a stored point: never greyed). Bites on copies (`*.bite1.*`, deleted after): the pin
always drawn normally - the first two red; always greyed - the last two red.

**Browser pass** (Vite 8189, `?devlogin`, profile `profile-p21`, German; tiles a blank PNG, the geocoder `{}`, all else
aborted in the browser - 14 outside requests, all to `tile.openstreetmap.org` / `nominatim.openstreetmap.org`, none left).
*Roma 2024* (a name only), light and dark: before a drag the crosshair is `is-unset` (light `rgb(102,112,133)`, dark
`rgb(154,167,181)`, opacity 0.6), the hint shows, the pill reads *Nur ein Name*; after a mouse drag the accent colour at
opacity 1, no hint, *Eigener Ort*. Cancelled, nothing saved. Shots `p21-{light,dark}-{before,after}.png`.

**What went wrong.**
- The hint wrapped to two lines in the first shot: an absolutely placed box at `left: 50%` shrinks to fit the half that
  is left. `width: max-content` with a `max-width` fixed it. A shot at the first pass caught it; no unit test can.
- One of four pass runs timed out reading the pin after the dark drag; three re-runs were green and it did not reproduce.
- `pnpm run check` was red only in the parallel implementer's in-progress search files (lint stops the chain), so its
  steps ran one by one on this change.
- On dark the greyed pin over a light tile is faint; the hint carries the meaning. An owner may want it darker.
