---
id: cr-gallery-filter-by-facts
title: A Memory's gallery narrows by capture day, year, place and kind, with the search's chips
status: planned
satisfies: [uc.memory.gallery]
adrs: ["0105", "0125", "0068", "0079"]
invariants:
  - the filter reads only what the opened listing holds; nothing is fetched, sent or stored for it (ADR 0105, ADR 0125 decision 2)
  - the ordering rule is unchanged; the filter only removes tiles (uc.memory.gallery step 7's rule)
  - a share guest has no filter field (uc.memory.gallery step 8)
  - core imports no application; the gallery is Memories', the chip field and grammar are core's
---

## Goal

[uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) step 8 (owner, 2026-11-09, "1-8" of the
search follow-ups): a filter field in a Memory's gallery header, with the search's chip grammar.

- **Chips:** day, year (only when the Memory spans more than one), place, and kind (photo, video).
- **Free text:** the rest matches the original's name.
- **Combining:** it combines with the existing favorites filter.
- **Lightbox:** pages through the narrowed set.
- **Empty state:** names every active filter (E5).

## Context (substrate: branch `worktree-search-design` at the tip this CR is committed on; read-only survey still owed by the implementer)

- **The gallery:** `features/memories/components/MemoryDetail.vue`, `useMediaGalleryLive.ts` (items), and
  `openedListing.ts` (`openedMeta` → capturedAt, GPS, placeName). ADR 0105 sorts and filters the opened listing off
  the main thread's hot path.
- **The favorites filter** (step 7): find it with `rg -n "onlyMine|everyone|favoritesFilter" src/web/src/features/memories`.
  Read how it removes tiles and drives the empty state. The new filter composes with it and does not replace it.
- **The chip field and grammar:** core's `shared/ui/VeyqTagsInput.vue` (with its sticky highlight,
  `cr-search-sticky-highlight`) and `core/search/query.ts` (chip derivation), plus `shared/text/fold.ts`.
  - The gallery's chip kinds differ from the global search's: **day** is new; space and person do not apply.
  - So reuse the field, and reuse or extend the grammar's pieces where they fit. Do not fork a second copy of the chip
    logic. A day chip may live in Memories if core has no use for it.
- **Day:** the capture day is the item's `capturedAt` (override first, as the gallery shows it), in the viewer's time
  zone, as the tile and lightbox show dates. Items without a capture time match no day or year chip; say so in the
  chip's suggestion count if counts are shown.
- **Guests:** the share page mounts the same `MemoryDetail` with Memories' source (share view = member view). The field
  is a right of `memoryRights`, false for every share role and true for a member. Prove it with the guest-mode mount
  test pattern (`src/web/CLAUDE.md`).

## Design

- **The field:** in the gallery's header row (a contribution or the detail's own toolbar; follow where the favorites
  filter sits). It is collapsed to an icon button at compact widths if the header lacks room. Measure it, do not
  guess (ui-web-styleguide).
- **Suggestions:** derived from the Memory's opened items.
  - The days that occur (a chip reads "3. Mai", localized).
  - The years that occur, only when there are two or more.
  - The places that occur (prefix match on the folded text).
  - Photo and video, when both occur.
- **The predicate:** an item stays when it satisfies every chip, the favorites filter, and the folded text against the
  original's name.
- **State:** in memory, per open Memory. It is cleared on leaving the Memory and never written to storage.
- **The lightbox** pages through the filtered list.
- **i18n:** EN+DE in Memories' catalog.

## Phases

Single phase (web only).

- **Build:** the design above.
- **Tests:**
  - each chip's predicate, with day edges in a non-UTC zone;
  - the year chip offered only for a multi-year Memory;
  - combination with each favorites filter;
  - the empty state names every active filter;
  - the lightbox paging over the narrowed set;
  - no field in a guest mount (guest-mode harness), with a positive control: the member mount shows it;
  - nothing written to storage (spies plus a control).
- **Bite:** remove the favorites term from the predicate, and the combination test goes red.
- **Browser pass on the `veyq-search` stack:**
  - a Memory with photos on two days (upload a second photo with another capture time if needed);
  - a day chip narrows the grid;
  - the lightbox pages only those items;
  - the empty state with an impossible combination.

  Desktop and phone width, final screenshots only.
- **Docs:**
  - coverage `uc.memory.gallery.yaml`: a criterion with web refs; the backend part is "none, client-only", stated the
    way that file states its other client-only criteria;
  - the Use page for the gallery (find it by `git grep -ln "uc.memory.gallery" -- site/src/content/docs`), EN+DE.
- **Done when:** `pnpm run check` is green in `src/web/`, the core-import guard is green, and the trust-chain guards are
  green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

(filled by the implementer)
