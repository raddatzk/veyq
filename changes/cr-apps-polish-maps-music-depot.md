---
id: cr-apps-polish-maps-music-depot
title: The apps' polish, half 2 — maps open framed, tiles across launches, a desktop request log, decoded tracks trimmed, Depot's viewer extras (#945, #939, #947, #932)
status: in-progress
satisfies: ["uc.memory.place", "uc.memory.tracks", "uc.memory.background-music", "uc.depot.view-file"]
adrs: ["0143", "0140", "0142"]
invariants:
  - "no tile, decoded sample, password or request line reaches the veyq server"
  - "nothing kept at rest the app did not choose: a tile only sealed in ADR 0140's store, a slice of audio and a PDF's password only in memory"
---

## Goal

V2 block C, half 2 (inbox § V2 — the apps' order): five polish items the native-apps § Open names — **#945**, **#939**,
**#947**, **#932** and Depot's viewer extras (a PDF's text layer and password prompt, a text's copy and wrap toggle).

## Decisions (re-derived against `57a7de3fd`, measured where a command is named)

- **D1 (#945).** A map that frames what is still read opens on **no view** — no tile asked — until it is known, then on
  its framing (`MemoryLocation.opening`, `TrackList.State.framed`); the world view only where there is nothing to frame.
  The bridge's `HeldMap` retries its opening as its overlays move; Android's `MapCamera.opened()`. The Memories map on the
  desktops already opened on its read pins (`Call.MapOpenMemories` reads them first). Apple's MapKit is not the core's map.
- **D2 (#939).** Tiles **enter ADR 0140's automatic tier, sealed**, per account and tenant, named by the served template and
  `z/x/y` (`KeptRead.TILE`): counted against the limit, evicted least recently viewed first, dropped by Clear cache and a
  wipe, never kept at the limit `0` or where the device keeps no trust; kept by the sweep while their tenant is listed. The
  per-device consent is held at the per-account store by dropping a tenant's kept tiles whenever its map services are not
  shown for want of the consent or the service (this account at once at a withdrawal, another at its next open), and those
  of another template when the served one changed. **No kept tile is shown offline, from any host**: the store is read only
  behind the tile gate (consent, hosts, online) — `tile.openstreetmap.org` forbids offline use, and D9 draws no map
  offline anyway. Never fetched ahead. Apple: MapKit keeps its own cache in `geod` (ADR 0143's residual), nothing here.
- **D3 (#947).** A debug build's request log on Linux and Windows: `Config.netLog` (the host's debug build alone sets it)
  installs a Ktor plugin on every client the core makes; each request to a host outside `localhost`/`*.localhost` is one
  `Net.Log` line told to the host (Linux stderr, Windows `log.txt`): method, address, the `User-Agent` sent, whether a
  `Referer`, cookie or `Authorization` went, the status; a presigned query never written.
- **D4 (#932).** The trim of a decoded format from its **two ends as the platform decodes them** (`MusicUpload.Measure.ends`,
  each end a WAV the core's `Wav` reads): Android `MediaExtractor` + `MediaCodec`, Apple `AVAudioFile` at a frame
  position, Windows `MediaTranscoder` trimmed to the slice, Linux GStreamer seeked to it (`Media.Slice`). Never the whole
  file; a platform that decodes nothing seals untrimmed, as before.
- **D5 (Depot).** Text: *Copy* (what is shown) and *Wrap lines* (on at first) on every app. PDF: the password asked in the
  page where the platform's renderer takes one (PDFKit, Android 15's `LoadParams`, `Windows.Data.Pdf`, poppler), else said;
  the text layer where the platform's view offers selection (PDFKit's `PDFView`); Windows.Data.Pdf has no text API.

## Phases

1. Maps (#945, #939) — core, bridge, Android; red first (`MapDispatchTest`, `MapTilesTest`, `MapTileShelfTest`).
2. The request log (#947) — bridge, Linux, Windows; red first (`NetLogTest`).
3. Decoded tracks trimmed (#932) — core, bridge, four platforms; red first (`AudioFactsTest`, `MusicDispatchTest`).
4. Depot's viewer extras — words, four platforms.
5. Harvest: ADR 0143/0140/0142 amended, native-apps § Open, `src/mobile/CLAUDE.md`, site EN+DE where noticed, inbox rows
   deleted, this CR deleted.

## Done when

`./gradlew :core:jvmTest :bridge:jvmTest :android:assembleDebug`, `linux/run.py build`, `windows/run.py build`, Apple's
macOS and iOS-simulator builds green; the passes reachable run; every `scripts/validate_*.py` but guard_gaps green.
