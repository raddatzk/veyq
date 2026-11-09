---
id: cr-gallery-filter-by-facts
title: A Memory's gallery narrows by capture day, year, place and kind, with the search's chips
status: done
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

Implemented 2026-11-09 in the worktree `search-design`, one phase. `pnpm run check` green in `src/web/` after
`pnpm run generate:api` (measured: 542 test files passed, 33 skipped; 6,561 tests); every `scripts/validate_*.py` but
`validate_guard_gaps.py` green with `/usr/bin/python3` (69 run, 0 red; no validator changed), the core-import guard among
them; `astro build` in `site/` green (209 pages).

**What was built, and the choices made (owner away; technical choices are mine).**
- **One grammar.** `features/memories/galleryFilter.ts` builds each medium's facts from Memories' own search document
  (`mediaDocuments`: the original's name, the effective capture time, the place), so the gallery and the search cannot
  disagree about a photo. It reuses core's `suggestChips` (year, place, kind), `satisfies`, `textRank`, and - newly
  exported from `core/search/query.ts` - `tailsOf`, `wordPrefix`, `MIN_TAIL` and `PER_TYPE`. The **day** chip is
  Memories' own (`DayChip`, `{ day: 'YYYY-MM-DD', label }`); core's union is unchanged.
- **Day and year in one zone.** The day key and the year come from one `Intl.DateTimeFormat` with the viewer's zone (a test
  names `Pacific/Auckland`); the year is written into the document's `facets.years`, so core's year chip reads the same
  zone as the day instead of `Date#getFullYear`. A day's label names its year where the Memory spans more than one year
  ("3. Mai 2024"), else "3. Mai"; two equal labels would otherwise be two different chips.
- **Suggestions.** Only for typed text, as in the search. The days come from the longest tail that names any ("4. mai" is
  the 4th, not every May day with "4." left over), at most core's three per type, oldest first; the year only where two or
  more occur and only one that occurs; photo or video only where both occur. Order: year, day, place, kind.
- **The text** matches the original's name only. A place match (core's `Rank.place`) does not count, since a place is a
  chip's. An unnamed medium gets no placeholder name, so "foto" matches nothing.
- **One predicate.** `galleryPredicate(marks, facts)` ANDs the mark filter with the facts, and `useMediaGalleryLive` takes
  it as `filter` (it was `markFilter`, a predicate over the id). So the grid, each tile's index and the lightbox snapshot
  narrow together, and the order is untouched. An in-flight upload stays out of any filtered view, as before. A blocked
  medium has no document, so a chip or text leaves it out; the mark filter alone does not.
- **Lazy.** The facts are a `computed` read only once a chip or text is in force, or text is typed for the suggestions.
- **The right.** `memoryRights().filterFacts`: true for a member on a Memory or a chapter (a pending deletion and Memories
  switched off included, since it is a read), false for every share role. It is in the member-only list of
  `rights.test.ts` and a row of `docs/apps/memories.md`'s matrix.
- **The field** is the layer's `VeyqTagsInput` in the gallery toolbar, before the sort and the mark filter. It reuses the
  shell search's part classes (`veyq-search__icon`, `__chip-icon`, `__option-label`, `__option-kind`, `__list`) and its
  magnifier icon; the layer has no filter icon, and one was not worth a new layer name.
- **Measured layout.** At 1280 px it is one row (the field 448 px, then sort and marks). At 390 px the field takes a row of
  its own at the page's width (358 px) and sort and marks wrap below, with no horizontal scroll. No collapse to an icon
  button was needed.
- **State** is two refs in `MemoryDetail`. They are cleared in the node/key watch (another Memory or chapter opens) and go
  with the component. Nothing is written to any storage.
- **E5.** With only the mark filter in force, its existing sentence stays. With a chip or text, one sentence names every
  filter in force through `Intl.ListFormat` ("Nichts in dieser Erinnerung passt zu 4. Mai und Roma.").

**Tests.**
- `galleryFilter.test.ts` (19): the day at both edges in Auckland, with a UTC control; the correction first; no capture
  time matches no day or year; day suggestions and the year in the label; the year offered only multi-year and taken in the
  zone; place (folded); kind only with both kinds; the text over the name only; a blocked medium; laziness; the AND.
- `MemoryDetail.filter.test.ts` (7, real field, keyboard Enter on the highlighted suggestion):
  - the member's field (the positive control);
  - a day chip narrows the grid, and the lightbox is handed only those two at the right index;
  - the text narrows live;
  - the combination with "only mine", "everyone's" and "all";
  - E5 naming every filter;
  - cleared on another Memory;
  - no storage write (spies on both storages and `indexedDB.open`, with a control write each spy sees).
- `MemoryDetail.guest.test.ts`: the field is absent for READ and READ_WRITE guests, beside a control that the toolbar is
  drawn. `rights.test.ts` has the new rows.

**Bites** (snapshot-and-restore, `scratchpad/p13_bites.py`; tree unchanged after the run):
- B1, the favorites term removed from `galleryPredicate`: the unit AND test and three mount tests red.
- B2, a READ guest granted `filterFacts`: 5 guest tests and 3 rights tests red.
- B3, the day read in UTC: both zone tests red.
- B4, the chips written to sessionStorage: the storage test red.
- B5, the lightbox snapshot over every item: the lightbox test red.
- B6, a year offered for one year: the year test red.
- B7, the chips left out of E5: the E5 test red.
- B8, the chips kept across Memories: the clearing test red.

B4 first stayed **green**: a spy on `Storage.prototype.setItem` saw a direct call in the test, but in a run of the whole
file it missed the component's own `sessionStorage.setItem`. In a single-test run it was red. The spies now sit on each
storage instance, and the bite is red.

**Browser pass** (`veyq-search` stack, the worktree backend 8180 and Vite 8189, headless Chrome, German UI;
`search-pass/p13-pass.mjs`). The seed (`p13-seed.mjs`) uploaded `kolosseum.jpg` and `forum.jpg` into *Reisen / Roma 2024*
through the gallery's picker, then set the capture days through `setMediaMeta`: `strand.jpg` and `kolosseum.jpg` on 3 May
2024, `forum.jpg` on 4 May.

All checks pass at 1280 px (18) and 390 px:
- "3. mai" offers the day chip first, and Enter makes it;
- the grid narrows to 2, and the lightbox pages 1 → 2 with "next" then disabled;
- Backspace removes the chip;
- 4. Mai with Roma empties the grid, and the empty state names both;
- "kolos" keeps one photo;
- while filtering, the only request is the tree's own `GET …/nodes` revalidation;
- back in the Memory, the filter is gone, and no storage key exists;
- no console error.

The pass found one defect, fixed: on the phone, the search's `.veyq-search` (no wrap, for the top bar) cut two chips to
"4. …" and "Ro…". The field now drops that wrapper class and wraps as the layer does. Screenshots in the scratchpad's
`search-pass/`: `p13-desktop-day-chip`, `p13-desktop-lightbox`, `p13-desktop-empty`, `p13-phone-day-chip`,
`p13-phone-empty`.

**Docs.**
- Coverage criterion `gallery-narrows-by-facts`, web refs only: client-only, like the file's other client-only criteria.
- The use case's step 8: zone, label year, undated and blocked media.
- The rights matrix row in `docs/apps/memories.md`.
- `src/web/CLAUDE.md`'s search paragraph points at the one grammar.
- The Use page *Look through the gallery*, EN+DE: a new section, *Narrow the grid* / *Das Raster eingrenzen*.

**Owner questions (each with the conservative default built).**
1. The empty-state sentence and the mark filter's words in it ("Nichts in dieser Erinnerung passt zu 3. Mai, Roma und
   deine Markierungen."). Recommendation: keep. The mark filter's own two sentences stay when it is the only filter.
2. Suggestions appear only once something is typed, and at most three days ("Mai" offers the first three May days).
   Recommendation: keep for now. If browsing by day turns out to be the main use, offer every day on focus as a follow-up.
3. Delete this CR once read. Its binding lessons are in `galleryFilter.ts`'s header, the coverage criterion,
   `src/web/CLAUDE.md` and the B4 comment in `MemoryDetail.filter.test.ts`.

**Next time:** a storage "nothing written" test spies on the storage instances, never `Storage.prototype`, and its bite
runs against the whole file, not one test.
