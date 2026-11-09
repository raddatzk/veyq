---
id: cr-map-services-off
title: With the map services off, a member names a place by hand; the choice reads "Map services on / off"
status: done
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

Implemented 2026-11-09 in the worktree `search-design`, on `a2043cf71`, in one commit.

**What was built.**
- `useMemoryLocation.openLocationEditor` opens the editor at once; the consent dialog in `MemoryDetail.vue` (and
  `consentDialogOpen` / `enableMaps`) is gone. The editor's A4 path already drew `MapConsentPrompt` where the map would stand
  when the map is offered but off (reached only by a withdrawal until now), so A6 is that state reached from the start - no
  fork.
- `MapConsentPrompt` names the hosts (`memory.place.consentHosts` / `consentHostsTiles`), on every map's offer, not only the
  editor's; where a renewal line (`MapConsentRenewal`) shows, it names them instead, so they are not said twice.
- `MemoryLocationEditor` watches `withMap`: turned on in the open editor, it frames and resolves as an opening with the map
  does. A typed name stays `manual`; with no name the map may name the place (`geocoded`); with no position the place
  becomes `fixed`, as on an opening with the map.
- Wording (EN/DE): settings `maps` *Map services* / *Kartendienste*, its switch *Map services in this browser* /
  *Kartendienste in diesem Browser*; the offer *Map services are off* (its heading on a level's map) and *Turn map services
  on* / *Kartendienste einschalten*; the map control *Turn map services off* / *Kartendienste ausschalten*. The body keeps
  what the services receive and the IP. The section's search words keep *Karte und Ortsnamen* / *map and place names*.
- L1's `search.spec.ts` no longer switches the space's Maps off: a new browser's map services are off, so the editor is
  the name field already (A6). The capability helpers, the catalog read and the `afterAll` repair are gone - simpler, and
  no longer tied to the tenant switch. Not run on a lane instance (see below).
- The docs-site shot `memories-maps-consent` became `memories-map-services-off` (the editor with the offer); its two stale
  PNGs were removed, so the page shows the placeholder until the next `pnpm run capture`.
- Docs: the Use page EN+DE (section *Map services* / *Kartendienste*), uc A5's and ADR 0016/0018's label names,
  `apps/memories.md`, `security-model.md`, `features/settings/_index.md`, the styleguide's rail line; coverage
  `map-services-off-names-by-hand` (web refs only, as the file states client-only criteria).

**Tests and bites.** `pnpm run check` in `src/web/` green (543 files, 6622 tests); `tests/e2e`'s check green (181 tests in
40 files); 69 guards green with `/usr/bin/python3` (`validate_guard_gaps.py` skipped, no validator changed). Bite 1 (the
CR's): `openLocationEditor` returning while the services are off (the old dialog's gate) - both A6 tests in
`MemoryDetail.test.ts` red; restored byte-equal to the snapshot, `git status --porcelain` unchanged. Bite 2: the editor's
`watch(withMap)` disabled - the two turn-on tests of `MemoryLocationEditor.mapServicesOff.test.ts` red; restored byte-equal.

**Browser pass** (`veyq-search` stack, desktop 1280, German): every A6 check PASS - the chip opens the name field and the
offer naming `tile.openstreetmap.org` and `nominatim.openstreetmap.org`, no map, a typed place saved, nothing turned on, **no
request to any outside host** on the off-path; turning them on in the editor drew the map and kept the name; the settings
search found *Kartendienste* by "Karte und Orts" and opened it. The stack's hosts are third-party, so every non-local
request was intercepted in the browser (tiles a blank PNG, the geocoder `{}`, the rest aborted): nothing left the machine,
and the map in the shot is blank for that reason.

**Found, not fixed (pre-existing, both paths).** An editor that opens with the map for a Memory without photo GPS starts
`fixed`, and the map's opening frame (`whenReady`, `user: false`) sets the position: saving without panning pins the place
at the world view's centre (20, 0). The pass did exactly that on *Roma 2024* (its place is now *Lido di Ostia* at that pin).
A6's turn-on path inherits it by design (one behaviour). Fix in one place - an opening frame is no position when nothing
was framed - as its own small CR (it disables Save until a pan, a UX choice).

**Owner questions (the conservative default built).**
1. The wording: *Map services are off* / *Turn map services on* / *Turn map services off* (de *Kartendienste sind aus* /
   *einschalten* / *ausschalten*), the switch *Map services in this browser*. *Your consent was given for other
   services…* (`mapsGivenFor`) and *nothing to consent to* were kept: accurate in law, and not named by the CR.
2. Naming the hosts in every offer, not only the editor's - recommendation: keep, ADR 0016 says turning on names them.

**What went wrong.**
- The L1 spec changed but lane L1 was not run on an instance (it needs both images rebuilt for one test); its check and
  `--list` are green. Next time: when a CR simplifies an L1 spec, the brief says whether the lane run is expected, so the
  cost is decided up front.
- The pass's first navigation used a remembered URL (`#reisen/memories/rom-2024`) that no longer resolves; the search got
  there. Next time: start a pass from the search, not from a stored path.
