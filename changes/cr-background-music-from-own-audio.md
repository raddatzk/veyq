---
id: cr-background-music-from-own-audio
title: Background music from a node's own audio files — a Music tab in Memories, and core's shuffled, crossfading player that plays on across pages
status: in-progress
satisfies: ["uc.memory.background-music", "en.platform.audio-player"]
adrs: ["0135", "0130", "0047", "0093", "0082", "0109", "0079", "0033", "0026"]
invariants:
  - "the server is changed nowhere but in the export schema: no table, column, role, route or operation marks a medium as music (ADR 0135 decision 2)"
  - "core's player is handed URLs and titles, never a key; every source it opened is released when it unmounts (ADR 0135 decision 8)"
  - "a photo view shows only image and video media: an audio medium reaches no gallery tile, lightbox, search result or cover choice (ADR 0135 decision 2)"
  - "nothing is loaded from outside the instance (ADR 0135 decision 1)"
---

## Goal

Build [ADR 0135](../docs/decisions/0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md):

- **Music as media.** A Memory or Chapter takes any number of audio files as media, added from a new **Music** tab
  with their length and silence measured and sealed at upload ([uc.memory.background-music](../docs/features/memory/use-cases/uc.memory.background-music.md)).
- **Core's player.** Web core gets one player ([en.platform.audio-player](../docs/features/platform/enablers/en.platform.audio-player.md))
  that plays a queue shuffled, endlessly and crossfading, in a bar below the shell's footer that stays across pages.
- **Reports.** A reported track can be heard by the moderator.

**Builds on the streaming removal**, landed on this branch in `8a6b15652`..`09dcfe5a1` (ADR 0135 decision 1): the
streaming code, `app.footerBar` and `onSessionEnding` are gone, and the three `memories.music` capability ids are
retired (ADR 0088 decision 10) — this CR adds no capability entry.

## Context & constraints

Measured on `main` at `8574dda3b` (2026-11-14) by a delegated read, before the removal; the files it names were not
its subject. **Spot-check the first two before phase 2.**

- **Memories drops audio when files are picked.** `uploadQueue.ts` `isSupportedMediaFile` / `SUPPORTED_EXT`
  accept image and video only; `collectPickedFiles` drops the rest silently. `resolveMediaType` (`media.ts`) falls
  back to `sniffMediaType` (`download.ts`), which reads every `ftyp` brand but HEIC and `qt` as `video/mp4`, an M4A
  included. Core's `core/blob/sniff.ts` `sniffStream` tells M4A (`audio/mp4`), Ogg, WAV, FLAC, MP3 (ID3) and ADTS
  apart.
- **Tracks are the precedent.** ADR 0130 and `uc.memory.tracks` give a track its own picker (`MemoryTracks.vue`), its
  own batch key (`#tracks`, `enqueueTracks` in `MemoryDetail.vue`), its facts sealed at birth through
  `RestoredMediaFacts` / `facts`, and a filter. That filter is `isTrackMeta` in `track/gpx.ts`, read by
  `useMediaGalleryLive.ts` (`items` drops tracks, `tracks` keeps them) and `search.ts`. Search counts everything that
  is not a video as a photo.
- **The listed tier.** `MediaMeta` in `media.ts` is sealed as `media-meta` under `K_node`, capped at 512 base64
  characters (`contracts/api/memories/media.openapi.yaml`), and re-sealed as is by a re-key
  (`features/memories/rekey.ts`).
- **Playback.** `sw/register.ts` `registerPlayback({chunkCount, byteSize, mediaType}, provider)` returns a same-origin
  `/__media/<handle>`. The service worker answers `Range` with `206` from chunks the page decrypts. Without a
  controlling service worker the lightbox and the banner fall back to a whole-file `blob:` (`MediaLightbox.vue`).
  `isStreamableMediaType` admits one `audio/*` essence. The CSP allows `media-src 'self' blob:`, and the
  Permissions-Policy allows `autoplay=(self)`.
- **Web Audio, MediaSession and silence are new to the repo.** There is no `AudioContext`,
  `MediaElementAudioSourceNode`, `decodeAudioData`, `OfflineAudioContext` or `navigator.mediaSession` anywhere. That a
  `MediaElementAudioSourceNode` on a `/__media/` URL is untainted (not silent) is *remembered*: same origin, no CORS.
  Phase 3 measures it.
- **Tabs and the header.** Tabs are hard-coded in `MemoryDetail.vue` (the `activeTab` union, the `<VeyqTab>` list, a
  panel each, the reset on a node switch, `tabScroll.ts`). The header's action order is `header.order` in
  `features/memories/register.ts`. MEMORY and CHAPTER share `MemoryDetailHost`. A guest gets the same `MemoryDetail`
  through `core/sharing/ShareTreePage.vue`.
- **Reporting.** `MemoryDetailHost.vue` `onReportMedium` reports an `ITEM` with `mediumReportKeys`. The moderator's
  `MediumReview.vue` (`app.itemReview`) shows a preview or a file icon and "Download original"; it plays nothing.
- **Export.** `exportImport.ts` writes each medium's original with its `blob-meta` type. `$defs.medium` in
  `contracts/export/memories/content.schema.json` has `additionalProperties: false`, and `track` is the additive
  precedent.
- **The shell after the removal.** `AppShell.vue` mounts nothing below its footer. The condition its footer bar
  used (signed in and the vault unlocked) is the player's.
- **Not measured at refine:** the exit codes of `pnpm run check` and `./gradlew check` on the tree the removal
  leaves. Phase 2 records them before its first edit.

## Decisions (technical, settled here)

1. **One predicate decides what a photo view shows: `isGalleryMedium`** (an `image/*` or `video/*` `blob-meta`
   type). It replaces the track-only exclusions in `useMediaGalleryLive.ts` and `search.ts`. A type no one has
   decided on stays out of every photo view by default. Tracks and music each keep their own positive predicate
   (`isTrackMeta`, a new `isAudioMeta`) for their own lists. A unit test feeds an `audio/mpeg`, an `application/gpx+xml`
   and an unknown type through the gallery's `items` and the search classifier, with an image and a video as the
   positive controls.
2. **The audio upload path** lives in `features/memories/music/`.
   - **Picking:** the Music tab's picker uses `accept="audio/*"` and its own batch key `#music`, enqueued as
     `enqueueTracks` does.
   - **Typing:** the type is `sniffStream`'s `audio/*`, read from the file's head. Memories' `sniffMediaType` learns
     M4A as `audio/mp4`, so no path seals an M4A as video.
   - **Refusals:** a file over 200 MiB, or one `sniffStream` does not type as audio, is refused before upload.
   - **The gallery:** its pickers and the Beiträge drop keep refusing audio. `isSupportedMediaFile` is unchanged.
3. **The facts** come from a pure, unit-tested `audioFacts.ts`.
   - **Decoding:** `new OfflineAudioContext(1, 1, 8000).decodeAudioData(bytes)`. A decode resamples to its context's
     rate, so a long track stays a few hundred KB per minute.
   - **The scan:** 20 ms windows from each end. A window is silent when its peak over every channel stays below
     `10^(-60/20)`. The trimmed span keeps at least 1 s, or the whole file.
   - **The result:** `durationMs`, `trimStartMs` and `trimEndMs`, as integers.
   - **Refusals:** a file that fails to decode is refused (E1).
   - **Where it is sealed:** the three facts go into `MediaMeta` and are sealed through `facts` at birth. A test pins
     their worst case inside the 512-character cap.
   - **What phase 2 measures** in Chrome and in Safari (macOS, and an iPhone if one can be reached): memory and time
     for a 10-minute 320 kbit/s MP3 and a 200 MiB WAV, recorded in the commit. If the WAV fails on a phone, the cap
     comes down to what passed, and ADR 0135 decision 2 is edited with it.
   - **Measured at implement (phase 2, 2026-11-14) — contradicts the memory premise; open, handed back.** Peak growth
     of the decoding process's RSS over its idle baseline, sampled every 50 ms (`ps`), and the decode time the page
     reported, for `OfflineAudioContext(1, 1, rate).decodeAudioData` on generated fixtures (a 10-minute 320 kbit/s MP3,
     24.0 MB; a 19.8-minute 44.1 kHz stereo 16-bit WAV, 209.6 MB, just under 200 MiB). Chrome 157 headless (system
     Chrome through Playwright) and the real Safari 27.2 on macOS (the page opened in the owner's Safari, results posted
     back); no iPhone reachable; Playwright's WebKit build was not installed for this Playwright version.

     | Browser | File | 8 kHz context | 44.1 kHz context (control) |
     |---|---|---|---|
     | Chrome 157 | MP3 10 min | +539 MiB, 532 ms | +578 MiB, 438 ms |
     | Chrome 157 | WAV 200 MiB | +1404 MiB, 433 ms | +1297 MiB, 268 ms |
     | Safari 27.2 | MP3 10 min | +136 MiB, 3.9 s | +453 MiB, 3.2 s |
     | Safari 27.2 | WAV 200 MiB | +1528 MiB, 1.6 s | +1858 MiB, 1.2 s |

     The low rate does not bound the peak in Chrome (it decodes at the file's rate, then resamples), and bounds it in
     Safari for the MP3 only; the 200 MiB WAV peaks at 1.4–1.5 GB in both. "A few hundred KB per minute" holds for the
     decoded buffer that is kept, not for the decode. The scan itself costs 0–2 ms.
4. **The Music tab** is `MemoryMusic.vue`, a panel `music` beside `map`.
   - **The list:** the node's audio media from `useMediaGalleryLive` (a `music` computed beside `tracks`), each with
     its title (the `blob-meta` name without its extension) and its length.
   - **Each track's menu:** download, delete (to the media trash, as a track is deleted) and report (`onReportMedium`).
   - **Adding:** **Add music** where `memoryRights.upload` allows it.
   - **When the tab shows:** where the node has music or the viewer may add some.
   - **Starting:** pressing a track starts the queue with that track (decision 6).
5. **The header button** is the action `music` (icon `music`), shown where the node has music, in `header.order` where
   the streaming buttons were. It toggles: when core plays *this* node's queue it stops it, otherwise it starts it.
6. **Core's player** lives in `src/web/src/core/audio/`, as the enabler describes.
   - **Pure modules,** unit-tested without a browser:
     - `order.ts`: the permutation per round from `crypto.getRandomValues`, the never-repeat rule, next, and previous
       with its 3 s rule;
     - `fades.ts`: a fade's start and length from the trimmed spans, a hand-press or a seek.
   - **The engine:** `player.ts` drives two alternating `<audio>` elements through one `AudioContext`. Its API:
     - `play(queue: { key, tracks, startId? })` replaces the queue playing;
     - `stop()`;
     - a reactive `nowPlaying` exposes `{ key, title, … }`, so the header knows which node's queue plays.
   - **The bar:** `AudioBar.vue`, mounted by `AppShell.vue` (signed in and unlocked) and `ShareView.vue`.
   - **Opening tracks:** `play` creates or resumes the `AudioContext` **synchronously in the click's call stack**,
     before any `await`. iOS refuses a context resumed later. Each track's `open()` is Memories', built as the lightbox
     builds its playback (`registerPlayback` with the medium's chunk provider, a `blob:` fallback). Its `release()`
     revokes the handle.
   - **Missing or unusable facts:** a track whose facts are absent (an import that could not decode) plays its whole
     file, with the duration read from the element.
7. **The MediaSession** gets the metadata (the title, and the node's name as the album) and the actions `play`,
   `pause`, `nexttrack`, `previoustrack` and `seekto`. `setPositionState` is updated on the trimmed span, when a track
   starts, on a seek, and once a second while playing.
8. **The moderator hears a reported track.** `MediumReview.vue` gets an `<audio controls>` for an `audio/*` medium,
   over a `blob:` of the original it already decrypts for **Download original**. A review is rare, so it does not need
   a stream.
9. **Export.** `$defs.medium` gains `audio: { durationMs, trimStartMs, trimEndMs }` (integers ≥ 0,
   `additionalProperties: false`), written for every audio medium that has facts. The import restores them through
   `RestoredMediaFacts`, and where they are absent it runs `audioFacts` on the bytes. A decode that fails there still
   imports the file, without facts (decision 6).

## Inventory (locators — run, read as lists)

| What | Command |
|---|---|
| Photo-view readers that filter (become `isGalleryMedium`) | `git grep -n -e isTrackMeta -e isVideoMediaType -- src/web/src/features/memories` |
| Every reader of the live lists (which list each takes) | `git grep -ln -e useMediaGalleryLive -e listNodeMedia -e listScopeMedia -- src/web/src` |
| The track precedent to mirror | `git grep -ln -e enqueueTracks -e "#tracks" -e MemoryTracks -- src/web/src` |
| Audio typing | `git grep -n -e sniffMediaType -e sniffStream -e resolveMediaType -e EXT_MIME -- src/web/src` |
| Playback registration | `git grep -ln -e registerPlayback -e revokePlayback -- src/web/src` |
| Tabs and header order | `git grep -n -e "activeTab" -e "VeyqTab " -e "header.order" -- src/web/src/features/memories` |
| The moderator's review | `git grep -ln -e itemReview -e MediumReview -- src/web/src` |
| Export field precedent | `git grep -n -e '"track"' -e restoredFacts -e RestoredMediaFacts -- contracts src/web/src/features/memories` |
| Site pages | `git grep -ln -i -e "gpx" -e "track" -- site/src/content/docs/memories` (the tracks page is the model for a new Use page) |

## Phases

**Phase 1 — contracts.** Decision 9's schema field. Done when the contracts guards are green and one export test in
`exportImport.test.ts` writes and reads the field (red until phase 2 builds it: name that line).

**Phase 2 — Memories' audio media.** Decisions 1–4 and 9, without playback.
- **Unit tests:** `audioFacts` on generated PCM (leading and trailing silence, all silence, under one second of
  content, a decode failure); the typing of an M4A, an MP3 and a WAV head; the refusal reasons; the predicate test of
  decision 1; the listed-tier cap; export with and without facts.
- **Done when:**
  - `pnpm run check` is green;
  - decision 3's measurement is recorded;
  - a browser pass on the dev instance: two MP3s and an M4A added in a Memory's Music tab show with titles and
    lengths; none is a gallery tile or a search hit; the same Memory through a `READ` link lists them with no add or
    delete; a delete moves one to the media trash; the console has no error.

**Phase 3 — core's player.** Decisions 5–7.
- **Unit tests:** `order.ts` (a round is a permutation; across 1,000 rounds of 2–5 tracks a round's first is never the
  previous last; previous at 2 s and at 4 s); `fades.ts` (the 5 s fade, 1 s on a press, a short track, a seek into the
  window); the player against stubbed media elements and a stubbed `AudioContext` (a failing `open()` skips; a new
  queue replaces; an unmount releases every source; a context is resumed before the first `await`).
- **Measured in the browser pass:** an `AnalyserNode` after a `MediaElementAudioSourceNode` on a `/__media/` URL reads
  non-zero samples (decision 6's taint question, measured), with the `blob:` fallback as the control.
- **Done when:**
  - `pnpm run check` is green;
  - a browser pass on the dev instance in Chrome and Safari: the header button starts the music, and the bar shows the
    first title; a track crossfades into the next (two tracks of a few seconds of tone, shortened fades are fine for
    the pass); the music keeps playing through a move to Settings and into another space; dragging the progress bar
    seeks; a lock ends it; a `READ` guest of the share page hears the same Memory.

**Phase 4 — review, docs, site, coverage, e2e.**
- **The work:** decision 8.
- **The e2e spec:** lane L1's `tests/e2e/tests/features/memories/memory-background-music.spec.ts` adds two short
  generated tracks, starts the music and waits for the bar's title to change, tagged with the coverage criteria.
- **Coverage:** `uc.memory.background-music`'s coverage file loses `status: planned`, and each gap becomes refs or a
  named gap.
- **Status lines:** ADR 0135 `Built: yes`; the enabler's and the use case's status.
- **State docs:** `docs/apps/memories.md` (a track of music among the media; the rights row);
  `en.memory.media` (audio media, the `isGalleryMedium` rule); `uc.memory.tracks` (it points at the shared predicate);
  `docs/apps/README.md` (core's player as what an application may use).
- **Site:** a Use page *Add background music* (EN+DE) written for this design, not restored from the withdrawn
  streaming page, with its sidebar line and the two Memories index links. Security: *what the server sees* names the
  reads of a playing track among the media reads it already lists.
- **Done when:**
  - every `scripts/validate_*.py` guard and the site's own check are green;
  - `pnpm run check` is green;
  - the spec is green once against a local lane run.

## Definition of done

Every phase's done-when is met. The browser passes are recorded with what they showed. This CR's lessons are moved to
where they bind, and this file is deleted.

## Retrospective

_(filled by the implementer)_
