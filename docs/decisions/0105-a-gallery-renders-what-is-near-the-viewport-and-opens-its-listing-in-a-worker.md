# ADR 0105 — A gallery renders what is near the viewport, and opens its listing off the main thread

- Status: Proposed 2026-10-19 with `cr-gallery-virtualization`, whose owner questions Q1–Q2 (keyboard model, targets)
  it does not decide. Nothing is built.
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
   meta (the media type), and the thumbnail frames — memoised per `(mediaId, metaRevision)`, so a sort, a filter or a
   resize never opens anything twice. The worker receives the open Memory's content keys (`K_node`, and the keys of its
   uploaders' buckets, derived on the page as today) and forgets them when the Memory closes. It is the page's own
   origin and the page's own code, so it is the page's trust tier ([ADR 0026](0026-client-trust-tiers.md)) — no key
   leaves the browser, and no new party sees one.
4. **The grid appears when its order is final** — every page listed and every meta opened — and never reflows a placed
   tile because a later page arrived; until then it shows the existing loading state. This is the gallery's "one
   order, no privileged front" obligation applied to loading.
5. **One tab stop, and the arrow keys move through the grid** (Q1's recommendation): a virtualized grid cannot offer a tab
   stop per item, because the item is not in the DOM. A screen reader is told the grid's size and each tile's position,
   not only the rendered tiles. Closing the lightbox scrolls its item into view and returns focus to it.

## Consequences

- The DOM size no longer grows with the Chapter, and the main thread no longer pays for crypto it does not show; the CR
  measures both against targets (Q2).
- Every view over the list — the gallery, the trash, the contributors' view, a guest's share — goes through the same
  windowing and the same opened listing; a new view over media that renders every item, or opens a meta on the page,
  is a defect against this record.
- The worker is a second place content keys live in the tab; it holds those of the open Memory only and drops them on
  close. A build check proves it is bundled (as `verify:media-worker` does for the deriving worker).
- **Rejected:** decrypt-on-visible for the metas too (the order needs them all, decision 4); a library grid (decision 1);
  a thumbnail cache across Memories (the store is bounded per open Memory; caching across them keeps keys and pixels
  past their view).
