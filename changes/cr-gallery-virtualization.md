---
id: cr-gallery-virtualization
title: The gallery mounts only what is near the viewport and opens its listing in a worker
status: planned
satisfies: ["uc.memory.gallery", "uc.memory.buckets", "uc.memory.delete-media"]
adrs: ["0105", "0047", "0026", "0068", "0033"]
invariants:
  - one order for every tile - a placed tile never moves because a later page or meta arrived
  - share view = member view - a guest's gallery is the same windowed grid over the same opened listing
  - content keys reach no party they do not reach today; the worker holds the open Memory's keys only, and drops them on close
  - no new dependency (CLAUDE.md's dependency rule) - the windowing is veyq's own over its justified layout
---

## Goal

Inbox #489, measured by `cr-volume-measurement-local`: 1,000 photos under a 4× CPU throttle (a mid-range phone) take 70–75 s
to the last thumbnail with 52–62 s of long tasks; the grid mounts every tile (13,000 DOM nodes at 1,000 photos, 37,000 at
3,000); at 10,000 the throttled page stopped answering for over 30 minutes. Build
[ADR 0105](../docs/decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)
(proposed): the grid keeps its justified layout for the whole list but mounts only the tiles near the viewport; a
thumbnail is fetched and opened only for those, nearest first; the sealed metas, the originals' blob meta and the frames
are opened in a dedicated worker, each once; the grid appears when its order is final; one tab stop and arrow keys,
focus returned from the lightbox. Measured with the volume harness at 1,000 / 3,000 / 10,000, desktop and 4×.

## Context & constraints

- State: [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) (the order is the sealed capture
  time; "one order for every tile"; the lightbox pages through the grid order), [uc.memory.buckets](../docs/features/memory/use-cases/uc.memory.buckets.md)
  (the contributors' view and its selection), [uc.memory.delete-media](../docs/features/memory/use-cases/uc.memory.delete-media.md)
  (the trash grid and its selection), [ADR 0047](../docs/decisions/0047-inline-video-playback-range-streaming.md) (the
  lightbox's video playback, unchanged), [ADR 0068](../docs/decisions/0068-favorites-two-confidentiality-levels.md) (the
  mark filter works on ids, unaffected), [ADR 0026](../docs/decisions/0026-client-trust-tiers.md) (the worker is the
  page's tier); `docs/crosscutting/ui-web-styleguide.md` § Reuse first and the accessibility rules (visible focus,
  accessible names, predictable keyboard); `web/CLAUDE.md` (click the rendered element; a guest-mode mount proves it
  calls no authenticated client).
- **#488 is being implemented now** (batched, bounded, retried presigns in `useThumbnailPipeline.ts` and
  `core/blob/transport.ts`). This CR's thumbnail queue (decision 3) calls the transport #488 leaves; every sentence
  here about presigns holds *until #488 lands* and is re-read against the merged tree before phase 2's first edit
  (changes/README § A sibling CR still in flight). Phase 1 measures after #488, not before.
- **`cr-gallery-list-paging`** delivers the list in pages and keeps the first page's revisions. This CR does not depend on
  it: with one answer or with pages, the grid appears when every item's meta is open (decision 5). Whichever lands
  second re-reads `useMediaGalleryLive.ts`'s load path.
- **`cr-volume-measurement-cax` phase 5 deletes the harness** this CR measures with; this CR's phase 1 reshapes the
  harness's gallery metric and its phase 5 runs the acceptance, so that deletion waits for this CR (that CR says so).
- **Not this CR:** the lightbox's focus trap (it has none, and no focus return; this CR adds the return because windowing
  needs it, the trap is a separate accessibility row if the owner wants one); timeline headers (there are none); the
  write-only guest page (`MemoryShareBody.vue` renders only the guest's own optimistic tiles).

### Measured substrate (worktree at `6fb7c8dd`, clean; #488 not merged)

| Fact the plan leans on | Locator | At refine |
|---|---|---|
| The grid mounts every positioned tile; the layout is absolute, justified, computed for all | `grep -n 'v-for' web/src/features/memory/components/MediaGrid.vue`; `grep -n 'export function calculateJustifiedMediaLayout' web/src/features/memory/mediaGridLayout.ts` | one loop over all |
| `MediaGrid`'s callers: the gallery, the trash, the contributors' view | `grep -rn '<MediaGrid' web/src` | `MemoryDetail.vue` (2), `MemoryBuckets.vue` |
| Elements per tile: 5–6 in the gallery, 8 in trash/contributors with their selection overlay | `sed -n 43,66p web/src/features/memory/components/MediaTile.vue` | — |
| No ARIA role on the grid; one native button per tile ("Open item {index}"); no arrow keys, no roving tabindex | `grep -n 'role=\|@keydown\|tabindex' web/src/features/memory/components/MediaGrid.vue web/src/features/memory/components/MediaTile.vue web/src/features/memory/components/MemoryDetail.vue` | `role="group"` on the mark filter only |
| The lightbox: the whole sorted list as a prop, `±1` paging, a window `keydown`, no focus handling | `grep -n 'items:\|startIndex\|keydown' web/src/features/memory/components/MediaLightbox.vue`; `grep -c focus web/src/features/memory/components/MediaLightbox.vue` | 0 focus |
| Metas unsealed on the main thread at every recompute, no memo | `grep -rn 'unsealMeta(' web/src \| grep -v '\.test\.'` (sort, aspect, layout, GPS, date range, contributors' layout) | 14 sites |
| The video test unwraps every original's blob key | `grep -n 'videoIdSet\|originalMediaType' web/src/features/memory/components/MemoryDetail.vue web/src/features/memory/media.ts` | per item |
| Thumbnails: one unbounded `Promise.all`; the trash has its own | `grep -n 'Promise.all' web/src/features/memory/useThumbnailPipeline.ts web/src/features/memory/useMediaTrash.ts` | 2 (the first changes with #488) |
| Object URLs: never revoked on scroll; the store copies its map on every insert | `grep -n 'new Map' web/src/shared/state/useObjectUrls.ts` | 3 copies |
| No virtualization anywhere, no list library; the one near-viewport pattern is the PDF pages' observer | `grep -rn 'IntersectionObserver\|VirtualScroller\|vue-virtual\|tanstack' web/src web/package.json` | `PdfPages.vue` only |
| The one existing worker is upload-side (derive tiers), with a build check | `grep -rn 'new Worker' web/src`; `grep -n '"verify:' web/package.json` | `mediaDeriveWorker.ts`; `verify:media-worker` |
| The guest's gallery is the same `MemoryDetail` over a guest source | `grep -n 'MemoryDetail' web/src/core/sharing/ShareTreePage.vue` | one mount |
| The harness counts every tile in the DOM, and "last thumbnail" is all of them at once | `grep -n "querySelectorAll('.media-tile\|lastThumbnailMs\|domNodes" e2e/measure/volume.ts`; `grep -n 'lastThumbnailMs\|listMs' scripts/measure_volume.py` | breaks under windowing |
| e2e specs reach the grid as "Open item 1" | `grep -rn "Open item\|media-tile" e2e/tests` | item 1 only |
| The crypto per item, main thread (temporary bench) | `npx vitest bench src/features/memory/media.bench.ts` in `web/` | 0.47 ms a 40 KB frame; 5.1 s for 10,000 metas, keys and frames (refine's reading, M4 Pro) |

## Decisions this CR takes

1. **Windowing over the existing layout** (ADR 0105 decision 1). `MediaGrid` keeps `calculateJustifiedMediaLayout` for the
   whole list and its container's full height, finds the scroll container it lives in (the implementer locates it — the
   page or an app pane), and mounts the tiles of the rows that intersect the viewport plus one viewport of overscan each
   way, recomputed on scroll (one per animation frame) and resize. Tile keys stay `mediaId`, so a mounted tile's state
   survives a scroll that keeps it. All three callers get it by construction.
2. **The opener worker** (ADR 0105 decision 3): a module worker beside `mediaDeriveWorker.ts`, with the same
   lazy-singleton, `seq`-keyed protocol and the same fall-back to the page thread on an infrastructure failure. Messages:
   *open a Memory* (its keys; derived on the page as `mediaKey` does today, including a guest upload's key through the
   drop key), *open listing items* (metas → capture time, aspect, GPS, place; blob meta → media type; batched),
   *open a frame* (ciphertext chunks in, plaintext bytes out, transferred), *close a Memory* (keys and memo dropped). The
   page keeps one memo of the opened values per `(mediaId, metaRevision)`; the 14 `unsealMeta` call sites read it. A
   `verify:` build check proves the worker is in the bundle; `verify:csp` covers its `worker-src`. A pool is not built
   unless phase 2's reading shows one worker is the bottleneck at 10,000 metas.
3. **Thumbnails on visibility** (ADR 0105 decision 2): a queue fed by the mounted window, ordered by distance from the
   viewport's centre, with the in-flight bound and the presign batching #488 provides; a tile leaving the window leaves
   the queue; the trash's own loader uses the same queue. Object URLs move to one bounded store (twice the largest window
   seen, at least 300) that revokes on eviction, and whose reactivity does not copy its map per insert.
4. **Keyboard and screen reader** (Q1's recommendation): the grid is one tab stop with a roving `tabindex`; arrows move
   left/right in grid order and up/down to the nearest tile of the adjacent row (scrolling it in), Home/End to the first
   and last item, PageUp/PageDown by one viewport; Enter/Space opens as the button does. ARIA's grid pattern: `role="grid"`
   with `aria-rowcount` of all rows, `aria-rowindex` on each mounted row, the tile's existing button inside its cell, its
   name unchanged ("Open item {index}" — the index already counts the whole list). Selection in the trash and the
   contributors' view keeps its checkboxes; Space on a focused tile there toggles them as a click does. Closing the
   lightbox scrolls its last item into view and focuses its tile.
5. **The grid appears when its order is final** (ADR 0105 decision 4): every page listed and every meta opened; until
   then the existing loading state. Live updates (ADR 0033's delta) and finished uploads insert into the final order as
   today; a tile above the viewport inserting shifts content, so the window keeps the first visible tile's position
   (anchor by `mediaId`, not by pixel).
6. **The harness measures what a windowed grid can promise** (phase 1): *viewport complete* — every mounted tile inside
   the viewport shows a decrypted image; *end complete* — the same after a jump to the end; *DOM nodes* at both moments;
   long tasks (count, total, max) up to *viewport complete* and during a scripted scroll through the whole Chapter. "Last
   thumbnail of all" is retired for a windowed grid. `measure_volume.py judge` applies Q2's targets.

## Open questions (owner)

- **Q1: Keyboard model.** (a) The WAI-ARIA grid: one tab stop, arrows, Home/End, PageUp/Down (decision 4); (b) keep a tab
  stop per tile, which under windowing reaches only the mounted ones — a keyboard user could not get past the overscan.
  **Recommendation:** (a). It changes what the keyboard does on this screen, so the Use page *Look through the gallery*
  gains a line (EN and DE).
- **Q2: The targets.** The CAX CR's rule says a 3,000-photo Chapter's list within 2 s and its last thumbnail within 15 s on
  a desktop browser. Proposed for the windowed grid, on the local recipe (two cores), Chromium:
  - desktop, at 1,000 and 3,000: the grid shown (order final) within 2 s of the list, *viewport complete* within 3 s of
    navigation, *end complete* within 2 s of the jump; at 10,000: within 4 s / 5 s / 2 s;
  - 4× throttle, at 1,000 and 3,000: *viewport complete* within 8 s, no long task over 500 ms after it, under 5 s of long
    tasks in the scroll-through; 10,000 reported, not judged;
  - every stage: DOM nodes under 3,000, independent of the Chapter's size; no failed request.
  **Recommendation:** these, with the readings of phase 1 (after #488) as the baseline they are compared to.

## Phases

- [ ] **0 docs** — ADR 0105 written at refine (proposed). Here: uc.memory.gallery's quality notes gain *bounded
  rendering* (the obligation of ADR 0105 decision 1, one paragraph pointing there) and, if Q1 is (a), the keyboard in the
  main flow; coverage criteria `the-grid-mounts-only-the-window`, `thumbnails-open-near-the-viewport`,
  `the-grid-is-one-tab-stop-with-arrow-keys`, `focus-returns-from-the-lightbox` (gallery),
  `trash-grid-is-windowed` (delete-media), `contributors-grid-is-windowed` (buckets), gapped until their phases.
  · Done when: `validate_coverage.py`, `validate_feature_format.py` green.
- [ ] **1 harness and baseline** — decision 6 in `e2e/measure/volume.ts` and the judge; then the baseline on `main`
  **after #488 merged**: photos 1,000 / 3,000 / 10,000, desktop and 4×, three runs each (the CAX CR's § Local pre-check
  procedure, browser leg only). **Bite the measurement** (changes/README § bite the MEASUREMENT): on a scratch build that
  mounts every tile, the DOM-node clause is red; on one that never decrypts the window, *viewport complete* never fires and
  the run ends as a stall, not a green. · Done when: both bites observed; the baseline readings recorded outside git and
  summarised in the retrospective.
- [ ] **2 web: the opener worker** — decision 2, behaviour-preserving: every tile still mounted, every thumbnail still
  opened, only where the crypto runs and how often changes. · Done when: `npm run check` green (with the new `verify:`);
  unit tests: the memo opens a meta once across sort, filter and resize (bitten: memo off → the count test red); the
  fall-back runs on the page thread when the worker fails to start (bitten: fall-back off → red); the worker drops its
  keys on close (a message after close is refused). The harness at 1,000 × 4×: long tasks fall against phase 1 — the
  reading recorded, no target judged yet.
- [ ] **3 web: windowing, visibility, keyboard** — decisions 1, 3, 4, 5 in `MediaGrid` / `MediaTile` / the gallery.
  · Done when: `npm run check` green; component tests that **mount the real grid and scroll it** (not a stubbed
  window): at 2,000 items the mounted tiles stay within the window's count (bitten: windowing off → red), a scroll
  enqueues only the new window's thumbnails nearest first, an evicted URL is revoked (bitten: no revoke → red); the
  keyboard test presses the keys on the rendered grid (ArrowDown from the last row's tile moves to the nearest, Home/End,
  Tab leaves the grid in one step); the lightbox close focuses the item's tile after scrolling it in; a guest-mode mount
  of the gallery calls no authenticated client (`guardedClients`). e2e specs that open "Open item 1" stay green.
  **Browser pass** (dev stack with S3, a Chapter of 3,000 seeded by `web/scripts/seed-volume.mts`, a member and a READ
  guest link): scroll to the end and back, the first and last rows show their thumbnails; the keyboard path from the
  first tile to the last with Tab, arrows, End, Enter; VoiceOver or NVDA announces the grid's size and a tile's position;
  a second member's upload appears in place; the place editor's GPS points and the auto date still read the whole list.
- [ ] **4 web: the other grids** — the trash and the contributors' view on the same window and queue (decision 3's trash
  loader), their select-all still selecting every item by id. · Done when: `npm run check` green; their tests extended
  (select-all at 2,000 items selects 2,000 with ~a window mounted); **browser pass** on the same Chapter: trash 1,000
  items, restore a selection across the window's edge.
- [ ] **5 measure and record** — the harness at 1,000 / 3,000 / 10,000, desktop and 4×, three runs each, judged by Q2's
  targets. uc.memory.gallery's "Seen in a browser" sentences updated; the Use page *Look through the gallery*
  (`site/src/content/docs/memories/the-gallery.mdx`, EN and DE) gains the keyboard line if Q1 is (a)
  (`git grep -ln 'uc.memory.gallery' -- site/src/content/docs` lists the pages that speak for it); inbox #489 done.
  · Done when: every target met or the miss handed back with its reading; every `scripts/validate_*.py` except
  `validate_guard_gaps.py` green; `npm run check` in `site/` green.

## Definition of done

Every phase's Done-when; the harness's readings at the three stages recorded in the retrospective against phase 1's
baseline; the bites observed as written; inbox #489 done; `cr-volume-measurement-cax` free to delete the harness.

## Retrospective

_Filled at implementation._
