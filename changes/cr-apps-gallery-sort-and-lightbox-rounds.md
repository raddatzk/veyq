---
id: cr-apps-gallery-sort-and-lightbox-rounds
title: The apps draw the gallery's sort toggle, and the core's lightbox pages round its ends and shuffles its slideshow
status: in-progress
satisfies: ["uc.memory.gallery"]
adrs: ["0155", "0161", "0123"]
invariants:
  - "the paging round and the shuffle's rounds are the core's `Lightbox` (one copy for the four apps); no app keeps a rule of its own"
  - "the web's behaviour is unchanged; the core's answers are the web's, case for case (MediaLightbox.test.ts twinned)"
---

## Goal

Close three gaps of the four apps against the web's Memories gallery (owner 2026-11-26): the grid's newest/oldest toggle
(inbox #1344), the lightbox paging round its ends with the slideshow (PR #98), the slideshow's shuffle (PR #101). The
rules go into the core's `Lightbox` (inbox #1343's move, taken on demand, ADR 0155 decision 11); each app draws the
controls in its platform's form; the bridge carries them to Windows and Linux.

## Phases

1. Core + bridge: `Lightbox.next`/`previous`/`shuffle`, `State.canPage`/`canShuffle`/`shuffled`, no `ENDED`; a removed
   last medium gives way to the first; `Gallery.Sort`, `Lightbox.Step`, `Lightbox.Shuffle`. Done when: `:apps:memories`,
   `:bridge` tests green, bitten.
2. Words + apps: Android (phone, TV), Apple (iOS, macOS, tvOS), Linux, Windows. Done when: each builds, a pass where a
   device is reachable.
3. Harvest: ADR 0155 § the gallery model's move (#1343's state), native-apps.md, uc.memory.gallery, the Use pages, inbox.

## Decision log

- **D1 — The web keeps its own lightbox; the core gets the rules for the apps.** Moving `MediaLightbox.vue` onto the core's
  `Lightbox` would move its snapshot of the rows, its clock (which waits for a slow preview, the core's does not), its
  read-ahead cache, its EXIF deferral and its zoom reset at once — #1343's whole move, not this step's. Exporting only the
  round and the shuffle through `@veyq/core-js` would cost a façade entry and decision 10's guest-path measurement for two
  small functions the web already has right. So: the core holds the rules for the apps, twinned case for case with
  `MediaLightbox.test.ts`; #1343 stays open with what is left. *Cost:* two copies of the round and the shuffle (TypeScript
  and Kotlin) until #1343 moves.
- **D2 — `Slideshow.ENDED` is deleted, not kept unused** (the web dropped `ended`); the bridge's `SlideshowState` with it —
  a host that matched it no longer compiles, which is the point.
- **D3 — Paging round is the model's (`next`/`previous`, the bridge's `Lightbox.Step`)**, not each host's `(i ± 1) mod n`:
  the rule is the core's (ADR 0161), and `go(index)` stays for a pager that already knows its page.
- **D4 — `shuffle(true)` is refused below three media** (the web hides its button there); once on, it stays on if media
  go, as the web's does. Its random source is `kotlin.random.Random` (the web's `Math.random`: the order is no secret),
  settable by a test.
- **D5 — The tick's own `round.removeAt(0)` is kept though a bite showed it equivalent**: the next tick drops the shown
  head anyway (the web's loop does the same); kept to stay line for line with the web's `upcomingInRound` + `shift`.
