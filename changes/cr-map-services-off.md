---
id: cr-map-services-off
title: With the map services off, a member names a place by hand; the choice reads "Map services on / off"
status: planned
satisfies: [uc.memory.place]
adrs: ["0016", "0018", "0088"]
invariants:
  - with the map services off, no tile and no geocoding request leaves the device (ADR 0016) - the new path adds none
  - turning them on still names the hosts and what they receive (GDPR Art. 4(11), 7; ADR 0016) - the wording changes, the consent does not
  - declining costs the map and nothing else; resolved names stay visible as text (ADR 0016, owner's fairness argument recorded as rejected)
  - no backend and no contract change
---

## Goal

[uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md) A6 and
[ADR 0016](../docs/decisions/0016-geocoding-provider-neutrality.md) (owner, 2026-11-09).

1. **The place editor without the map services.** On a server with a map, a member whose map services are off gets
   the name field of a server without a map (A4) instead of the consent dialog, which today offers only *Cancel* or
   *Turn on* (found by `cr-search-e2e`'s L1 run).
   - **The offer:** where the map would stand, the editor offers to turn the map services on, naming the hosts
     (A5's text).
   - **Turning them on there** shows the map in the same editor and keeps the typed name.
   - **Leaving them off** saves the typed place (`manual`).
2. **The wording.** The settings section, the map's control and the prompt call the choice *Map services* (de
   *Kartendienste*), on or off. The settings section is today *Map and place names* / *Karte und Ortsnamen*
   (`src/web/src/i18n/index.ts`, `maps`).
   - **The settings word list:** `features/settings/sections.ts` keeps the old words as search words of that section,
     so "Karte" still finds it.
   - **The prompt and the turn-on text** keep what they say about hosts and IP. Only the framing changes: a setting,
     not a cookie banner.

## Context (the implementer re-reads)

- **Where the editor and consent live:** find them with `rg -ln "consent|mapConsent|MapConsent" src/web/src` and
  `rg -n "maps" src/web/src/i18n/index.ts`. The A4 path (a server without a map: no consent dialog, the name field) is
  the model, so reuse it and do not fork it.
- **The withdrawal in an open editor already keeps the name field** (A5). The new path is the same state reached from
  the start.
- **The tests:**
  - the place editor's and the consent's existing tests;
  - L1's `tests/e2e/tests/features/platform/search.spec.ts`, which today turns the space's map off (A4) to type a place
    by hand. With A6 it can type it with the member's map services off instead. Simplify it if that is cleaner, and
    say so.
  - `rg -ln "Map and place names|Karte und Ortsnamen|turnOn|consent" src/web/src tests/e2e` finds every label that
    moves.
- **The site:** `site/src/content/docs/memories/set-the-location.mdx` and its `de/` twin, plus the settings page that
  names the section (`git grep -ln "Map and place names\|Karte und Ortsnamen" -- site`).

## Phases

Single phase (web, site).

- **Build:** the two items.
- **Tests:**
  - With the map services off on a map server: the editor shows the name field and the turn-on offer, and saving a
    typed name writes `manual`.
  - **A negative with its control:** no tile or geocoder request is made (a fetch spy). The control: the same editor
    with the services on asks the geocoder.
  - Turning them on in the editor keeps the typed name.
  - The labels in EN and DE.
  - The settings search still finds the section by "Karte".
- **Bite:** route the off-state back to the old dialog. The name-field test must go red.
- **Browser pass on the `veyq-search` stack:** a member with the map services off sets a place by hand on a map
  server, and turning them on in the editor shows the map. Desktop, final screenshots only. Turning the maps on loads
  third-party tiles; if the stack has no tile host configured, say so and pass the off-path only.
- **Docs:**
  - the Use pages in EN and DE;
  - coverage `uc.memory.place.yaml`, a criterion for A6 with web refs, its backend part "none", stated as that file
    states client-only criteria.
- **Done when:** `pnpm run check` is green in `src/web/`; `tests/e2e`'s check is green if a spec changed; the
  trust-chain guards are green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

(filled by the implementer)
