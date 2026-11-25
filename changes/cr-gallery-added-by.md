---
id: cr-gallery-added-by
title: The gallery filters by who added a medium — an added-by chip in the core's GalleryFilter and the web's twin
status: in-progress
satisfies: ["uc.memory.gallery"]
adrs: ["0149", "0155", "0125", "0083"]
invariants:
  - "no new server data and no new operation: the names are the lightbox's uploader read (the node's buckets, the space's members)"
  - "one predicate for grid and lightbox: the added-by chip is ANDed inside GalleryFilter.predicate / galleryPredicate"
---

> **Status: in-progress** — inbox #1356, owner go 2026-11-25.

## Goal

An *added by* chip in the gallery filter (`uc.memory.gallery` step 8): a medium's `created_by` owner key, named as the
Contributions tab names it, ANDed with the marks' mode, the day/year/place/kind chips and the free text, on the web and
every app.

## Plan

1. Core: `GalleryFilter.Chip.AddedBy`, `Fact.owner`, `Facts.contributors`, `contributorName`, `suggest(…, contributors)`;
   `GalleryModel.contributors()` + `State.uploaders`; `GalleryFilterTest` (bitten).
2. Platforms: Android `GalleryFilterBar`, Apple `GalleryFilterViews`, the bridge (Linux, Windows draw `ChipShown`).
3. Web: `galleryFilter.ts` twin + `MemoryDetail.vue` + `useUploaderNames.labelOf`; `galleryFilter.test.ts`.
4. Words EN+DE (`media_filter_added_by` ↔ `media.filter.addedBy`), uc step 8, the Use page EN+DE, harvest, delete.

## Decision log

- **D1 — the web keeps its TypeScript filter and gains a twin chip** (not moved onto the core). #1321 names why the
  filter stayed TypeScript (the day labels through `Intl`, `galleryChipLabel`); the move is that row's measured step,
  not this feature's. The chip is built in both with the same rule and twinned tests.
- **D2 — offered by the text, like the day chip.** Typing a name the text's longest tail begins at a word offers
  *Added by …*; no separate picker. Every other chip comes the same way, and each platform already draws suggestions.
  The placeholder (*Day, place, name …*) is unchanged: it names no year or kind either.
- **D3 — only where more than one contributed** (`Facts.contributors.size > 1`): one contributor narrows nothing. A
  medium without an owner key (a row from before the buckets) matches no added-by chip and adds no contributor.
- **D4 — the names are the Contributions tab's**: a member by name, one not named (who left, or the directory unread)
  *Member*, a guest by its sealed name marked *(guest)*, an unnamed guest *Contribution*
  (`GalleryFilter.contributorName`; the web maps `useUploaderNames`' labels the same way). The chip reads
  `media_filter_added_by` ("Added by {1}" / "Hinzugefügt von {1}") around the name on every platform.
- **D5 — the names come from the lightbox's uploader read**, made when the filter's text first asks and only where
  more than one contributed (`GalleryModel.contributors()`: each owner key asked once; the web's `uploaderNames.ensure`).
  The server sees a read the member already makes for the lightbox — no new data, no new operation. uc step 8's
  "nothing is fetched" now names this one read.
- **D6 — a guest gets no chip, because a guest gets no filter.** The owner's row says "a guest's gallery offers the chip
  too"; but a share guest has no filter field (owner 2026-11-08, ADR 0125 decision 7, ADR 0093's hosting reading "no
  directory, no search"), and a guest cannot read the buckets or the member directory a name comes from. The chip
  follows `filterFacts`: wherever a guest is given the filter, it comes with it. Whether a guest gets the filter is the
  owner's (product + the hosting reading), filed as inbox #1391.
- **D7 — the bridge renders the chip's words through the host** (`HostWords.say(notice)`, `HostRequest.WordsSay` with
  arguments, as an untitled track's title is said), so the wire's `ChipShown` stays a label: Linux and Windows draw
  the new chip with no host change.
- **D8 — `GalleryModel.suggestions(photo, video)` stays as an overload** beside the new
  `suggestions(photo, video, contributors)`: a host that draws no added-by chip yet (the TV form in flight) compiles
  unchanged, Swift included (Kotlin's default arguments do not reach Swift).
