# ADR 0105 — A gallery renders what is near the viewport, and opens its listing off the main thread

- Status: Accepted
- Built: yes
- Date: 2026-10-19
- Model: [ui-web-styleguide](../crosscutting/ui-web-styleguide.md)

## Context

The volume measurement opened a Chapter of 1,000 photos under a 4× CPU throttle — a mid-range phone — and the last
thumbnail arrived after 70–75 s, with 52–62 s of long tasks; at 10,000 the page stopped answering for over 30 minutes.
The grid rendered **every** tile (13,000 DOM nodes at 1,000 photos, 37,000 at 3,000), and **all** of its crypto ran on
the main thread: each item's sealed meta was unsealed — again on every recompute of the sort, the aspect ratios, the
layout, the GPS points and the date range, none of which was memoised — each original's blob meta was unwrapped to tell
a video, and every thumbnail was fetched and opened at once, unbounded. Object URLs were never revoked for a tile that
scrolled away.

The order of the grid is the sealed capture time ([uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md)),
which only the device reads, so **every** item's meta must be opened before the first tile can be placed. Thumbnails
need not be: a tile far from the viewport is never seen until the user scrolls to it.

## Decision

1. **Every view over a list of media renders only what is near the viewport.** The grid already computes an absolute
   position for every tile (justified rows); it keeps doing so for the whole list — the container
   keeps its true height, the scrollbar its truth — and mounts only the tiles whose rows intersect the viewport plus an
   overscan. The windowing is veyq's own, over that layout: a library would replace a layout veyq needs anyway for
   justified rows. `@tanstack/vue-virtual` is maintained and vendor-neutral and would be acceptable under the
   dependency rule, but adds a second layout model for none of the justified layout's needs.
2. **A thumbnail is fetched and opened only for a tile near the viewport**, nearest first, a bounded number in flight,
   and dropped from the queue when its tile leaves the window. Its object URL lives in a store of bounded size and is
   revoked when evicted; a tile that comes back opens again.
3. **The listing is opened in a dedicated worker** — the sealed metas (capture time, aspect, GPS), the originals' blob
   meta (the media type), and the thumbnail frames — memoised per seal, not per revision (a meta edit is a new seal and
   is opened anew; a share guest's rows carry no revision and are kept as well), so a sort, a filter or a resize never
   opens anything twice. The worker receives the open Memory's content keys (`K_node`, and the keys of its
   uploaders' buckets, derived on the page as today) and forgets them when the Memory closes. It is the page's own
   origin and the page's own code, so it is the page's trust tier ([ADR 0026](0026-client-trust-tiers.md)) — no key
   leaves the browser, and no new party sees one.
4. **The grid appears when its order is final** — every page listed and every meta opened — and never reflows a placed
   tile because a later page arrived; until then it shows the existing loading state. This is the gallery's "one
   order, no privileged front" obligation applied to loading.
5. **One tab stop, and the arrow keys move through the grid** (the owner's choice): a virtualized grid cannot
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

The 4× throttle is Chromium's emulation, and it slows the page's main thread only, never a worker (measured in the
[published stage map](../../site/src/content/docs/security/how-veyq-is-tested.md)). So a 4× number is a slower main
thread, never a slower device: the listing worker's part of it runs at desktop speed, and what a real phone does is
read on one. How the targets are judged is [uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md)
§ Quality notes.

## Consequences

- The DOM size no longer grows with the Chapter, and the main thread no longer pays for crypto it does not show
  ([uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md) § Quality notes holds the reading against
  § Targets).
- Every view over the list — the gallery, the trash, the contributors' view, a guest's share — goes through the same
  windowing and the same opened listing; a new view over media that renders every item, or opens a meta on the page,
  is a defect against this record.
- The worker is a second place content keys live in the tab; it holds those of the open Memory only and drops them on
  close, refusing every later request for it. A build check proves it is bundled, and the content security policy
  admits it as a same-origin worker ([cc.security.csp](../crosscutting/cc.security.csp.md)). Without a worker, or when
  it fails, everything opens on the page thread as before — slower, never wrong.
- **Rejected:** decrypt-on-visible for the metas too (the order needs them all, decision 4); a library grid (decision 1);
  a thumbnail cache across Memories (the store is bounded per open Memory; caching across them keeps keys and pixels
  past their view).
