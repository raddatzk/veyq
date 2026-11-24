---
id: cr-web-memories-on-the-core
title: Memories' pure rules move onto the Kotlin core for the web — `@veyq/core-js/memories`, the TypeScript twins deleted
status: in-progress
satisfies: ["uc.memory.gallery", "uc.memory.place", "uc.memory.participants", "uc.memory.background-music"]
adrs: ["0155", "0156", "0079", "0136", "0154", "0135", "0046"]
invariants:
  - "operator-blind: the move changes where a rule runs (Kotlin over the web's own primitives), never what is read, sealed or sent"
  - "code a façade reaches carries no kotlinx.serialization, coroutines, Ktor or NotOnTheWeb refusal (ADR 0155 decision 4), held by the package's slice check"
  - "decision 8 is not crossed: no model exposing a flow, no coroutines into the web; what keeps state over time or awaits I/O stays TypeScript"
  - "a Long never crosses the façade; a size is a JavaScript number"
  - "Memories' module is never in the eager set: every visitor who opens no Memory pays nothing for it"
  - "a guest path that grows by more than Memories' deleted TypeScript × 3.0 plus its façade's floor stops the move before the web switch (owner 2026-11-24)"
  - "no behaviour a member or a guest sees changes"
---

> Owner go 2026-11-24 ("weiter mit Memories"): ADR 0155 decision 7.3's third application. Refine and build in one run;
> the implementer decides technical questions and logs them below (owner 2026-11-21). Decision 8's gate is measured here
> for the owner, not built.

## Goal

The web draws Memories' pure rules from `:apps:memories` (`@veyq/core-js/memories`, Memories' own module file
`kotlin_veyq_mobile_apps_memories.mjs`), as Depot's move did (ADR 0155 § Depot's move), and the TypeScript twins go.

## Refine — what of `src/web/src/features/memories/` crosses (measured at `b53b952f0`)

Each TypeScript file with a Kotlin twin (`grep -ohE '[A-Za-z0-9_]+\.(ts|vue)\b'` over `apps/memories/src/commonMain`),
read against it. A rule crosses only where it is pure, its twin is whole, and the twin's path is variant B:

| Web | Kotlin twin | Verdict |
|---|---|---|
| `mediaGridLayout.ts` `calculateJustifiedMediaLayout`, `galleryDisplayAspectRatio` | `MediaGridLayout.justify`, `displayAspect` | **moves** — whole, pure doubles; on the guest path (the share body's grid) |
| `memoryLocation.ts` `computeMedoid` | `MemoryLocation.medoid` | **moves** — whole but for the haversine's radians (`d*(π/180)` against the web's `(d*π)/180`): the web's form wins (ADR 0154's rule) |
| `memoryParticipants.ts` `mergeParticipants` | `Participants.merge` | **moves** — whole |
| `music/frames.ts` `mp3FrameLength`, `adtsFrame`, `adtsFrameLength`, `firstFrameChain`, the ADTS count | `music/Frames` | **moves** — whole; the count's per-slice step and end become pure functions the suspend count and the web's loop both run |
| `music/wav.ts` `parseWavHeader`, `wavSamples`, the end's byte count | `music/Wav.parseHeader`, `samples`, `readEnds`' count | **moves** — whole; the reads (`Blob.slice`) stay |
| `memoryLocation.ts` `normalizeLocation`, `boundPlaceName`, `isWorldViewCentre`; `memoryDate.ts` `normalizeDate`; `memoryParticipants.ts` `normalizeParticipants` | `MemoryLocation.normalize`/`bound`, `MemoryDate.normalize`, `Participants.normalize` | stay — the readers take kotlinx's `JsonElement` (variant A) and sit in the eager set (`levelRows.ts`, `searchFacets.ts`, `participantIndex.ts`); onto the JSON seam with their own move (inbox #1320) |
| `galleryFilter.ts`, `rights.ts`, `track/gpx.ts`, `track/trackLabel.ts`, `track/trackFacts.ts`, `music/audioFacts.ts`, `music/musicQueue.ts`, `spaceMap.ts`, `storedMatch.ts`, `banner/bannerModel.ts`, `media.ts`' capture time and order | `GalleryFilter`, `MemoryRights`, `Gpx`, `TrackLabel`, `TrackFacts`, `AudioFacts`, `MusicQueue`, `MemoriesMap`, `StoredMatch`, `Banner`, `MediaMetadata`/`MemoryMedia` | stay — no whole twin: each answers apart from the web (a rounding, a format, a missing case, a different shape) or reads variant A; building them whole in the core first is their own work (inbox #1321) |
| `galleryMedium.ts` `isGalleryMedium` | `GalleryMedium` | stays — a type test per item; a crossing would cost more than the rule (D4) |
| `useMediaGalleryLive.ts`, `useOptimisticTiles.ts`, `useMediaTrash.ts`, `itemFavorites*.ts`, `participantIndex.ts`, `placeName.ts`, `uploadQueue.ts`, `source.ts`, `share.ts`, components | `GalleryModel`, `ItemMarks`, `Contributions`, `MusicModel`, `TrackList`, `ParticipantIndex`, `PhotoPlace`, `MediaUploads` — models with flows or I/O | stay — decision 8 (its gate measured below) |

**Hot paths** (decision 7.3's time check, each over a named, bounded real-use corpus): the grid's layout of a 10,000-photo
gallery at three widths (360, 1280, 2560 CSS px); the medoid of 500 GPS points (a long trip's photos); the WAV samples of
30 s at each end of a 48 kHz stereo file in 16 and 24 bit; the first frame chain over a 4 MiB tail slice (MP3 and ADTS,
the chain at its end); the ADTS count of a 10-minute file.

**Vectors** (`contracts/memories/v1-vectors.json`): `grid`, `aspect`, `medoid`, `participants`, `frames`, `wav`; written by
the core's `MemoriesVectorsTest`, answered by the package's `memories.test.ts` and the web's `features/memories/vectors.test.ts`.

## Phases

1. **Core** — the pure parts the façade needs (`displayAspect` over a double, the ADTS count's step, the WAV end's bytes,
   all `internal`: no public signature changes, so no Apple build), the medoid's radians, the vectors.
   *Done when:* `:apps:memories:jvmTest`, `macosArm64Test` green, the vectors rewritten byte for byte.
2. **Package** — the façade (`apps/memories/src/jsMain/…/memories/web/`), `./memories` in `package.json`, the slice
   check's row, the package's vectors test, the cross-entry names. *Done when:* `pnpm run build && pnpm run check` green.
3. **Gate before the switch** — the guest paths measured with the façade bundled against the owner's stop rule.
4. **Web** — the five files as glue, the medoid's glue off the eager `memoryLocation.ts`, the participants' merge loaded
   with the write; the web's vectors test. *Done when:* `pnpm run check` green, the twins' tests passing through the glue.
5. **Gate and harvest** — sizes (7.3, decision 10's guest paths and member-only chunks), times, bites, three
   byte-identical builds, decision 8's measured gate; ADR 0155 § Memories' move, `docs/apps/memories.md`, inbox; this
   file deleted.

## Decision log

- **D1 — The set: five rules with a whole twin on variant B, nothing else.** *Q:* what crosses? *Choice:* the grid's
  geometry, the medoid, the participants' merge, the audio frame headers and the WAV reading. *Why:* decision 1 deletes a
  twin; the readers of the sealed meta take kotlinx's tree (variant A, decision 4) and are eager, and the other rules have
  no whole twin — each is its own work (#1320, #1321). *Cost:* a small move in bytes; its point is one rule each.
- **D2 — Memories' module stays off the eager set.** `memoryLocation.ts` and `memoryParticipants.ts` are eager (the
  level's rows, the search's facets); a static import of the façade there would make every visitor load Memories' module.
  The medoid's glue is a file of its own (`medoid.ts`) that only the place's composable imports; the merge is loaded by the
  participants' write (`await import`), which awaits a request anyway. *Cost:* two test files import the medoid from its
  new file.
- **D3 — The pure parts are `internal`.** The façade lives in the module's own `jsMain`, so the step functions it needs
  (`Frames.adtsCount`, `Wav.endBytes`, `MediaGridLayout.displayAspectOf`) are `internal` and the apps' suspend paths run
  them; no public Kotlin signature changes, so the Apple framework's header is unchanged and no Xcode build is owed.
- **D4 — `isGalleryMedium` stays.** A type test on one object per gallery item; crossing it means one call into the
  module per item for a rule of three comparisons. The apps' `GalleryMedium` and it are held by the same kinds.
