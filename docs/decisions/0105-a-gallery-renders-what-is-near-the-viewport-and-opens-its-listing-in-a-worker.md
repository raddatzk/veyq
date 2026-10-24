# ADR 0105 — A gallery renders what is near the viewport, and opens its listing off the main thread

- Status: Accepted 2026-10-19 — decided by the owner (the keyboard model of decision 5, the targets below) and built
  in the web client the same day. The volume harness read the targets at the V1 heavy stage (2026-10-24) and every one
  held, at 1,000, 3,000 and 10,000 photos, desktop and 4× ([uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md)
  § Quality notes holds the reading).
- Date: 2026-10-19

Provenance: **(M)** measured in the worktree `cr-gallery-virtualization` names — the command is in its grounding table;
**(R)** remembered, measured by the CR before it binds.

## Context

The volume measurement (inbox #489) opened a Chapter of 1,000 photos under a 4× CPU throttle — a mid-range phone — and
the last thumbnail arrived after 70–75 s, with 52–62 s of long tasks; at 10,000 the page stopped answering for over 30
minutes. The grid renders **every** tile (M: `MediaGrid.vue` loops over all positioned items; 13,000 DOM nodes at 1,000
photos, 37,000 at 3,000), and **all** of its crypto runs on the main thread (M): each item's sealed meta is unsealed by
`unsealMeta` — again on every recompute of the sort, the aspect ratios, the layout, the GPS points and the date range,
none of which is memoised — each original's blob meta is unwrapped to tell a video, and every thumbnail is fetched and
opened at once in one unbounded `Promise.all`. Object URLs are never revoked for a tile that scrolled away, and the URL
store copies its map on every insert.

The order of the grid is the sealed capture time ([uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md)),
which only the device reads, so **every** item's meta must be opened before the first tile can be placed. Thumbnails
need not be: a tile far from the viewport is never seen until the user scrolls to it.

## Decision

1. **Every view over a list of media renders only what is near the viewport.** The grid already computes an absolute
   position for every tile (`mediaGridLayout.ts`, justified rows); it keeps doing so for the whole list — the container
   keeps its true height, the scrollbar its truth — and mounts only the tiles whose rows intersect the viewport plus an
   overscan. The windowing is veyq's own, over that layout: a library would replace a layout veyq needs anyway for
   justified rows. PrimeVue's `VirtualScroller` expects items of one size (R); `@tanstack/vue-virtual` is maintained and
   vendor-neutral and would be acceptable under the dependency rule, but adds a second layout model for none of the
   justified layout's needs.
2. **A thumbnail is fetched and opened only for a tile near the viewport**, nearest first, a bounded number in flight,
   and dropped from the queue when its tile leaves the window. Its object URL lives in a store of bounded size and is
   revoked when evicted; a tile that comes back opens again.
3. **The listing is opened in a dedicated worker** — the sealed metas (capture time, aspect, GPS), the originals' blob
   meta (the media type), and the thumbnail frames — memoised per seal (a listed tier by its `wrappedMeta`, a blob meta
   by its blob and its `wrappedBlobMeta`, so a meta edit at a new `metaRevision` is opened anew and a share guest's
   rows, which carry no revision, are kept as well), so a sort, a filter or a resize never opens anything twice
   (`src/web/src/features/memories/openedListing.ts`). The worker receives the open Memory's content keys (`K_node`, and the keys of its
   uploaders' buckets, derived on the page as today) and forgets them when the Memory closes. It is the page's own
   origin and the page's own code, so it is the page's trust tier ([ADR 0026](0026-client-trust-tiers.md)) — no key
   leaves the browser, and no new party sees one.
4. **The grid appears when its order is final** — every page listed and every meta opened — and never reflows a placed
   tile because a later page arrived; until then it shows the existing loading state. This is the gallery's "one
   order, no privileged front" obligation applied to loading.
5. **One tab stop, and the arrow keys move through the grid** (the owner's choice, 2026-10-19): a virtualized grid cannot
   offer a tab stop per item, because the item is not in the DOM. The WAI-ARIA grid: the tab order holds one tile —
   its open button first, then its own mark, checkbox or menu, so no control of a tile loses its keyboard path — and
   the arrows, Home/End and PageUp/PageDown move that stop. A screen reader is told the grid's size and each tile's
   position, not only the rendered tiles. The lightbox takes the focus while it is open; closing it scrolls the item it
   showed last into view and returns focus to it.

## Targets

On the volume harness's local recipe (two cores), Chromium; the reading is taken at the V1 heavy stage.

- Desktop, at 1,000 and 3,000 photos: the grid shown (its order final) within 2 s of the list, *viewport complete*
  (every mounted tile in the viewport shows its image) within 3 s of navigation, *end complete* within 2 s of a jump to
  the end; at 10,000: within 4 s, 5 s and 2 s.
- Under a 4× CPU throttle (a mid-range phone), at 1,000 and 3,000: *viewport complete* within 8 s, no long task over
  500 ms after it, under 5 s of long tasks in a scroll through the whole Chapter; 10,000 is reported, not judged.
- Every stage: fewer than 3,000 DOM nodes at both moments, independent of the Chapter's size; no failed request.

The 4× throttle is Chromium's emulation, and it slows the page's main thread only, never a worker: at the V1 heavy
stage the Argon2id worker took 5,354 ms at throttle 1 and 5,413 ms at throttle 4 while the main thread rose ×4.5, and
the listing worker took 271 ms at both. So a 4× number is a slower main thread, never a slower device: the listing
worker's part of it runs at desktop speed, and what a real phone does is read on one.

The judge that applies them is `scripts/measure_volume.py judge` while the harness exists.

## Consequences

- The DOM size no longer grows with the Chapter, and the main thread no longer pays for crypto it does not show; the volume
  harness measured both against § Targets at the V1 heavy stage, and both held (inbox #489).
- Every view over the list — the gallery, the trash, the contributors' view, a guest's share — goes through the same
  windowing and the same opened listing; a new view over media that renders every item, or opens a meta on the page,
  is a defect against this record.
- The worker is a second place content keys live in the tab; it holds those of the open Memory only and drops them on
  close, refusing every later request for it. A build check proves it is bundled (`verify:listing-worker`, as
  `verify:media-worker` does for the deriving worker), and `verify:csp` that the policy admits it (`worker-src 'self'`).
  Without a worker, or when it fails, everything opens on the page thread as before — slower, never wrong.
- **Rejected:** decrypt-on-visible for the metas too (the order needs them all, decision 4); a library grid (decision 1);
  a thumbnail cache across Memories (the store is bounded per open Memory; caching across them keeps keys and pixels
  past their view).
