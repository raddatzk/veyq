---
id: cr-gpx-tracks
title: GPX tracks on a Memory's map, beside its photos — a track is a medium, read and drawn on the device
status: in-progress
satisfies: ["uc.memory.tracks"]
adrs: ["0130", "0016", "0018", "0113"]
invariants:
  - operator-blind - the server stores a track as a medium and cannot tell it from one; no type, name or coordinate leaves the device unsealed
  - no new contract, table, role, route or seal purpose (ADR 0130 decision 1)
  - no new consent: every map's one per-browser consent (ADR 0016)
  - share view = member view: a guest's Map tab is the member's with fewer actions
---

## Goal

Inbox #781 (owner 2026-10-29, direction 2026-11-12: "like the old veyq, with the same UX — with E2EE"): a Memory's GPX
tracks drawn on its own map beside its photos, several per Memory and per chapter, listed with their numbers, hidden or
shown, downloaded, removed. Decided in [ADR 0130](../docs/decisions/0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md);
the behaviour is [uc.memory.tracks](../docs/features/memory/use-cases/uc.memory.tracks.md).

## Phases

The decision makes a track a medium, so the contracts and backend phases are empty: what the server does with a track is
what it does with a medium, pinned by the media tests the coverage cites (`MediaSliceTest`, `MediaTrashSliceTest`).

1. **Web.** `features/memories/track/` (the GPX model, the numbers, the tab's reads), `useMediaGalleryLive` separates
   `tracks` from `items`, `MemoryMap` draws `tracks`, `MemoryTracks.vue` is the Map tab, `MemoryDetail` wires the add
   (the one upload queue, a batch key of its own), the removal (a medium's) and the report (a medium's). Memories' catalog
   gains `memory.tracks.*` (EN+DE). Done when: `pnpm run check` green in `src/web`.
2. **Docs, site, L1.** The ADR, the use case and its coverage, `docs/apps/memories.md` (owns, the map, the rights
   matrix), the feature index, ADR 0018's and uc.memory.place's lists of maps, the inbox (#781 deleted, #841 filed). The
   Use pages `memories/add-gpx-tracks.mdx` EN+DE, linked from the Memories index and the sidebar. Lane L1's
   `tests/e2e/tests/features/memories/memory-tracks.spec.ts`, written and listed, not run. Done when: the guard glob is
   green and `pnpm run check` in `tests/e2e` lists the spec.

## Locators

- Every view of photos reads `items` of `useMediaGalleryLive`: `grep -rn "items\b" src/web/src/features/memories/components/MemoryDetail.vue`.
- The lists of maps the docs keep: `grep -rn "photo panel's" docs site/src/content`.
- Tab-list assertions: `grep -rn "tabs(w)).toEqual" src/web/src/features/memories`.

## Retrospective

- The brief's first reach - "reuse the attachment machinery" - met two dead ends a read of the seams found before any code:
  a node attachment holds one blob per slot, and an own-item role of core's blob service on Memories' kinds would make two
  upload completions accept one kind (ADR 0113 decision 4). The medium was the reuse, and it is also the more blind one.
