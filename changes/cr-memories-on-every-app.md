---
id: cr-memories-on-every-app
title: Memories on every app — the gallery, the lightbox and the uploads moved into core screen models, then bridged and drawn on Linux and Windows
status: planned
satisfies: []
adrs: ["0130", "0131", "0123", "0122", "0132", "0047", "0080"]
invariants:
  - every rule is the core's (ADR 0130 decisions 1 and 2) — what Android's and Apple's Memories still decide in their app models and views (the gallery's reads and when it reads anew, the lightbox's page, its picture and its reader, the details, the save of an original, the upload's settle, the tier spec) moves into core screen models first, both phones thinned in the same phase and unchanged on a device
  - share view = member view (owner 2026-10-09, inbox #401) — one gallery model for a member and a link's guest, whose rights only remove functions; the rights are the web's `memoryRights`, fail-closed, ported once into the core
  - no plaintext at rest but where the person saves it (ADR 0047's Consequences, every app) — tiles and previews in memory, a video through the core's `RangeReader`, an original written only to the path a save dialog gave (or iOS's swept `fileMover` directory)
  - the bridge only carries — one line per call onto a core model, no key, seal or token outward (`SecretFreeSchemaTest`); shown bytes beside the JSON, a medium through a reader by id, files by path (ADR 0131)
  - Memories' words — Memory, Chapter; never "Album"; "application", never "plugin" (root CLAUDE.md, ADR 0086); every word from `src/mobile/words/words.json`, each desktop to its platform's guideline (ADR 0123 decision 9)
  - no new dependency that is deprecated, vendor-locked or closed (root CLAUDE.md); none is planned (D8), and one a phase finds it needs is checked and recorded where it is added
  - Android, the Apple app, the web and Depot and Expenses on the desktops behave as before; `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` and both desktops' `run.py build` stay green in every phase
---

## Goal

The next CR of the owner's native catch-up (owner 2026-11-11: CR 3a → 3b → Depot → **Memories** → packaging; inbox #438).
CR 3a brought a node's header to the desktops, CR 3b Expenses whole, the Depot CR Depot whole. This CR brings the
**Memories** application to Linux and Windows at the level Android and Apple have it today (native-apps § Built, by area,
**Memories**: the gallery with chapters, the lightbox with details, video and saving the original, adding photos and
videos with their metadata read on the device), and closes on every app what a member uses a Memory for every day and
the apps still lack against the web:

- **a Memory and a chapter made** from New (no app can make one today);
- **the gallery:** chapters with their covers, the media as tiles of their derivatives, read anew live (the web's poll);
- **the lightbox:** paging, zoom, the slideshow, a video **played through the core's reader**, the details, the original
  saved, a medium **trashed with Undo**;
- **adding photos and videos:** the system's dialogs, a drop onto the gallery (desktops), the tiers derived by the platform,
  the metadata read by the core, the app's one upload queue;
- **the cover** (the header's, already built) and the page's hero; **sharing** a Memory or a chapter through the share sheet
  (READ, READ_WRITE, WRITE_ONLY) — the Memory's page itself built as one model for member and guest (share view = member
  view: rights only remove functions).

The order is ADR 0130 decision 6's, as the Depot CR's: the moved rules into the core first with both phones thinned onto
them, the new functions into the core, then the bridge, Linux, Windows, the phones drawing the new functions, the docs.

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` at `9037319b2` (the Depot CR harvested and deleted at `d313c1f4f`; the
  account settings' phases 3 and 4 merged; tree clean). The Depot CR's file page, reader players, `FileSave`, uploads by
  path, `DepotUploads` tray and drop handlers, and CR 3a's header, cover dialog and share sheet are this CR's floor.
- **Read first:**
  - ADR 0130 (decisions 1, 2, 6 and the Consequences' Depot paragraph — the pattern), ADR 0131 (all four decisions and its
    Depot Consequences), ADR 0047's Consequences (the apps' rule), ADR 0132 decision 2 (a Memory as a share target —
    left out here, D12), ADR 0080 decision 6 with [memories](../docs/apps/memories.md) § What a guest may do (the role
    matrix the rights port);
  - `src/mobile/CLAUDE.md` whole: the bridge paragraph and the traps (an answer overtaking an update, one `ContentDialog` at
    a time, decrypting off the caller's thread, a payload's NULL vs empty, GTK's dialog state through weak handlers, the
    common file dialog not pressed through UI Automation, mingw's `fopen`);
  - the use cases under `docs/features/memory/use-cases/` this CR reaches: `uc.memory.gallery`, `uc.memory.browse`,
    `uc.memory.chapters`, `uc.memory.upload`, `uc.memory.cover`, `uc.memory.delete-media`, `uc.memory.live-updates`; the
    enablers `en.memory.media`, `en.memory.derivatives`;
  - the web as the reference behaviour, `src/web/src/features/memories/`: `rights.ts` and `source.ts` (one view, rights
    remove), `components/MemoryDetail.vue`, `MediaGrid.vue`, `MediaLightbox.vue` (zoom, the slideshow at
    `SLIDESHOW_INTERVAL_MS`), `components/MemoryNodeDialog.vue` (New Memory, New chapter, a name's collision),
    `mediaRevisionPolling.ts` (`ITEMS_POLL`), `download.ts`, `components/ShareDialog.vue` and `ShareCreateDialog.vue` (a
    Memory's links), `shared/image/mediaDeriver.ts` (the tiers).
- **Where what runs:** as in the Depot CR. The Kotlin on this Mac. Linux in the container (`linux/run.py run --port 8097
  --devlogin --no-secret-service`, driven from headless system Chrome when the browser pane is hidden); a drop and sound in
  the Fedora 45 VM's session (`ssh kevin@192.168.64.9`, Mutter's RemoteDesktop API as the Depot pass drove it). Windows on
  the ARM64 VM (`windows/run.py run --devlogin`, `run.py drive`), x64 under emulation. Android on a throwaway AVD (never
  `veyq_pixel`, deleted after); Apple on the booted iPhone 18 Pro simulator and the ad-hoc macOS build. The dev backend
  (`veyq.localhost:8080`) is never started twice or stopped.
- **The live tests write only into their own Memories.** Each live case makes a Memory `App memory <n>` at a space's top
  level, writes there, and trashes and purges it at the end; a seeded node is never written. Each desktop pass does the same
  by hand (`Linux memory`, `Windows memory`), each phone pass `Android memory` / `Apple memory`. Media are generated on this
  Mac (`ffmpeg` at `/opt/homebrew/bin/ffmpeg`) or taken from the core's own metadata fixtures
  (`grep -rn "fixture" src/mobile/core/src/jvmTest/kotlin/me/raddatz/veyq/mobile/memories/MediaMetadataFixtureTest.kt`),
  never from a person's files.

## Measured at refine (2026-11-13)

Two Explore passes (the web against both phones and the core; the desktops, the bridge and the core), their key numbers
checked again here. Cites into `src/mobile/`; the core is `core/src/commonMain/kotlin/me/raddatz/veyq/mobile/`. Commands,
from `src/mobile` unless named:
- `find core/src -ipath '*memor*' | xargs wc -l`; `grep -rli memor android/src/main | xargs wc -l`; `grep -li memor apple/Veyq/*.swift | xargs wc -l`
- `grep -nE "memoryGallery|uploadMedia|mediaPicture|mediaReader|downloadMedium|mediaUploaders|galleryRevision|mediaExif|scopeNodes|mediaAccessAsked" android/src/main/kotlin/me/raddatz/veyq/mobile/android/AppModel.kt apple/Veyq/AppModel.swift`
- `sed -n 120,240p core/src/commonMain/kotlin/me/raddatz/veyq/mobile/AppKinds.kt`
- `grep -n -i "memor\|media\|gallery\|Reader\|Upload\|Luma\|Pdf\|HostRequest" bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt`
- `grep -n "not_built" linux/src/shell.rs`; `grep -n "NotBuilt(" windows/Veyq/ShellView.cs`
- `grep -rn "savev\|Texture::from_bytes\|MediaFile\|appsink" linux/src`; `grep -rn "BitmapEncoder\|BitmapDecoder\|MediaPlayerElement\|GetThumbnailAsync" windows/Veyq`; `grep -n gstreamer linux/Cargo.toml`
- `/usr/bin/python3 -c "import json; d=json.load(open('words/words.json')); k=[x for x in d if x.startswith('memory_')]; print(len(k), sum(1 for x in k if d[x].get('de')))"`; `grep -rn "words::memory" linux/src`; `grep -rn "Words.Memory" windows/Veyq`
- from the repo root: `grep -rl restoreMedia src/web/src | grep -v test`; `grep -n SLIDESHOW_INTERVAL_MS src/web/src/features/memories/components/MediaLightbox.vue`

| # | What | Where |
|---|---|---|
| R1 | **The core holds Memories as functions, with no screen model.** `MemoryMedia` (209 lines: `list` every page of a node's media each under the key its origin fixes, `order`, `read`, `reader` → `RangeReader`, `stream`, `Item.gridBlob`/`lightboxBlob`), `MediaDetails` (241: rows, `exif`, `uploaders`, `uploaderAt`, `chapters`, `downloadName`), `MediaMetadata` (140), `MediaUpload` (295: `Job` over a `Deriver` and an `UploadSource`, `typeOf`, `isMedia`, `sniff`); `Gallery.mediaRevision`/`deleteRevision` are read and used by nothing. `session/LivePoll` (the web's `ITEMS_POLL`) and `PotModel.live()` are the poll's pattern; `session/NodeCreate.create(…, kind, name, tokenInput, …)` makes any kind. | `memories/`, `session/` |
| R2 | **Tests:** `commonTest` `MemoryMediaTest` (3), `MediaUploadTest` (4), `MediaDetailsTest` (4), `MediaDetailsShownTest` (3); `jvmTest` live `MemoryLiveTest`, `MemoryUploadLiveTest`, `MediaMetadataFixtureTest` (6); `appleTest` `MediaMetadataNativeTest` (2). | `core/src/*Test/.../memories` |
| R3 | **Still decided in both phones** (a third and fourth copy would follow): the gallery read and its triggers (`listsRevision`/`rowDetails` + `galleryRevision`); the chapters composed from `scopeNodes`; a decoded-picture cache (Android `LruCache` 48 MB, Apple `NSCache`) and which tier a tile and a page read; the lightbox's page, its playing state and its reader; the details' loads (Android reads the uploaders once for every item at the lightbox's open, Apple per item in the details); the save of an original; the upload's settle (`galleryRevision` on DONE, the lists at the batch's end); Apple's removal of the Photos picker's copies; the tier spec copied in both (2048 / 0.85, 512 / 0.8, the poster at `min(0.1 s, duration / 2)`). | `android/.../AppModel.kt:541, 749-862`, `MemoryScreen.kt`, `MediaDerive.kt:22-26`; `apple/Veyq/AppModel.swift:719-801`, `MemoryScreen.swift`, `MediaTiers.swift:14-17` |
| R4 | **The phones disagree:** Android picks through the photo picker only and asks once for full media access (`mediaAccessAsked`, a getter that writes); Apple offers Photos and Files and hands no modified time for a Photos pick. Android streams a saved original to the document the person made; **Apple writes it to `tmp/downloads/<uuid>/` for `fileMover` on iOS and on macOS** (the Depot CR moved only a file's macOS download onto the save panel through `FileSink`). The caption's date in two formats. | `MemoryScreen.kt:107-116, 194-209`; `AppModel.swift:751-760, 774`; `MemoryScreen.swift:177, 222-233` |
| R5 | **Apple copies each Photos pick into `tmp/<uuid>/`** (`PickedMedia`, `FileRepresentation`: the transfer's own file is gone once it returns) and removes it when its item is done or cancelled; a failed one is kept for a retry. That it is swept at start is an accident: `sweepTemporary` removes every UUID-named directory of `tmp` for an older build's downloads. The copy is the person's own undecrypted original, never a decrypted veyq copy. | `MemoryScreen.swift:95-109`; `AppModel.swift:779-783, 965-980` |
| R6 | **Both phones lack, against the web:** making a Memory or a chapter (`AppKinds.Create` has FOLDER, POT, UPLOAD_FILES, UPLOAD_FOLDER only; no word for either); zoom and the slideshow; trashing a medium (the web: trash with Undo, `restoreMedia` reached only from the gallery's Undo, `source.ts`); the live poll; a Memory's links (`AppKinds.header(MEMORY/CHAPTER).shareable = false`); chapters drawn without their covers. Not reached here (D12): date, place and map, participants, a medium's capture time, the banner, item marks, buckets, the gallery's filter, export, import and copy, the re-key. | `AppKinds.kt:164-202, 226`; the web's files above |
| R7 | **The header of a Memory and a chapter** contributes nothing of its own (`KindPages.kt:53-57`, `NodeHeader.swift:46-53`): core's plan gives favourite, rename, cover, move, report, delete; `headerOrder`'s `upload`, `share`, `download`, `banner`, `copy` are never filled. The hero is drawn only once a cover exists (`NodeHeroRow`, `LocalNodeHero`); the web always frames one. | `AppKinds.kt:139, 201-202, 226` |
| R8 | **The bridge has no Memories call.** `Bridge.openReader`'s doc names "a Memory's medium" as the next caller; `Header.CoverPicture`, `File.Bytes`, `Avatar.Picture` and `Receipt.Open` answer `Answer.Bytes`; `HostRequest.PictureLuma`/`PdfPage` are the only requests a host answers with pixels (`HostReply.Pixels`, a reply payload). `Upload.*` and `Watched.Uploads` are built; `DepotUploads` already mirrors the whole queue and `Notices.isMedium` words a medium's refusal, but nothing enqueues a `MediaUpload.Job` over the bridge. `PickedPaths.source(path)` opens a picked file as an `UploadSource`. | `bridge/.../Wire.kt`, `Bridge.kt:269, 612-616, 780-793`; `depot/DepotUploads.kt:101-102`; `words/Notices.kt:417` |
| R9 | **Both desktops open a Memory as "not built"** (Linux `shell.rs:714`, Windows `ShellView.cs:561`); a chapter never shows on a level (placed only in a Memory). No desktop row draws a cover or a thumbnail (native-apps § Open); only the Pot page draws a hero. | `linux/src/shell.rs`, `home.rs`; `windows/Veyq/ShellView.cs`, `HomeView.cs` |
| R10 | **What the desktops already have for a medium:** Linux decodes pictures through `gdk::Texture::from_bytes` (glycin's loaders), encodes a JPEG through gdk-pixbuf's `savev` (`cover.rs:432-439`), plays through `gtk::MediaFile::for_input_stream` over `reader_stream.rs`, and links gstreamer, gstreamer-app and gstreamer-video 0.25 (the QR camera's appsink); no frame of a video file is grabbed. Windows decodes through `BitmapDecoder`/`BitmapImage`, encodes a JPEG through `BitmapEncoder` (`CoverDialog.cs:342-348`), plays through `MediaPlayerElement` over `ReaderStream.cs`; no video frame grabbed. Packages: Windows App SDK 2.6.5, SDK build tools, MSTest. | `linux/Cargo.toml:27-29`; `windows/Directory.Packages.props` |
| R11 | **The words:** 53 `memory_` keys, all with German; no desktop uses one by name. | `words/words.json` |
| R12 | **No published page speaks for the native apps' Memories** (the Depot CR's R12, unchanged: `git grep -li "linux\|windows" -- site/src/content/docs`); the desktop packages' page is `site/src/content/docs/getting-started/install-the-app.mdx`, which names no application's screens. | `site/` |

Remembered, not measured at refine (each a phase's first measurement, D8): whether glycin in the GNOME 51 runtime and the
Fedora 44 container decodes HEIC/HEIF; whether gdk-pixbuf's JPEG saver is in the GNOME 51 runtime (the cover's `savev` was
passed in the container, not in the Flatpak); whether `Windows.Media.Editing.MediaClip.CreateFromFileAsync` with
`MediaComposition.GetThumbnailAsync` runs in an unpackaged app; whether `BitmapDecoder` applies an EXIF orientation with
`ExifOrientationMode.RespectExifOrientation`.

## Decisions

### D1 — A Memory's page is a core screen model, `GalleryModel`

ADR 0130 decision 2's shape (state a flow and a watch, actions plain functions, tested in `commonTest` over a gallery the
test plays), opened by `ShellModel.galleryModel(ref)` for a MEMORY or a CHAPTER:
- **State:** loading; the media as shown rows (id, a video or not, a tile to ask for or the icon, the name, blocked); the
  chapters (a Memory's own, each with its cover's presence — `MediaDetails.chapters`, made the model's); the count; failed;
  the rights (D3); what is said (a `Said` queue as `DepotUploads`', D6's Undo among it).
- **Actions:** `tile(mediaId)` → the grid tier's bytes (`gridBlob`, read whole as today, decoded by the platform);
  `reload`; `live()` — the web's poll (`LivePoll` at `ITEMS_POLL`, `listNodeMedia` with the gallery's revisions; nothing
  read while no revision moved), a watch whose job is the poll as `Watched.PotLive` is, ended when the page closes;
  `upload(sources)` and `uploadPaths(paths)` (D5); `trash(mediaId)` / `undo()` (D6); `createChapter(name)` (D4);
  `lightbox(index)` (D2).
- **When it reads anew is the core's:** on the shell's lists' revision, on an own upload's DONE (the phones'
  `galleryRevision` goes), on the poll's moved revision; the lists themselves once a batch ends (the Memory's date range).
- **The decoded picture's cache stays the platform's** (decoding is, ADR 0130 decision 1); the core caches no bytes: a
  tile is read when the platform asks for it, as the phones do today.

### D2 — The lightbox is the gallery's, `Lightbox`

- **State:** the current index over the gallery's rows (kept when the gallery reads anew: by media id, a removed one
  moving to its neighbour, the web's rule); the page's picture to ask for (`lightboxBlob`) or "a video without a poster";
  blocked said (`memory_blocked`); the caption (name, effective capture time as an instant the platform formats); the
  slideshow's state; the details (D2b); the save in progress (a `FileSave`, D7).
- **Actions:** `go(index)`, `picture()` → bytes, `reader()` → the original's `RangeReader` for a video, **closed by the
  model when the page is paged away, the lightbox closes or the tenant does** (the Depot CR's rule: a reader's lifetime is
  its page's, held by the core); `slideshow(on)`; `details()`; `save(path)` / `save(sink)`; `trash()` (D6).
- **The slideshow is the web's** (`MediaLightbox.vue`): `SLIDESHOW_INTERVAL_MS` (5 s, read from that file at implement and
  pinned as `Lightbox.SLIDESHOW_MS`), a video shows its poster for one interval and never plays by itself, it stops on the
  last item, any `go` pauses it; the model runs its clock, no view keeps a timer.
- **Zoom is the platform's** (a gesture and a view: pinch, double-tap, Ctrl+wheel, `+`/`-`), to each guideline; the model
  knows nothing of it. The web's `lightboxZoom.ts` is pointer arithmetic, not a rule.
- **D2b — the details:** `MediaDetails.rows` with the EXIF tier read once per medium when first shown, the uploaders read
  **once per gallery** for every owner key (Android's way, one request, not one per item), "Uploaded by" before the place
  (`uploaderAt`); the platform formats sizes and moments as today (`MediaDetails.shown`).

### D3 — Share view = member view: one model, rights only remove

`memories/MemoryRights` ports the web's `rights.ts` (fail-closed: every field false unless granted) for a member and for each
link role on a MEMORY, a CHAPTER and a FOLDER root, from [memories](../docs/apps/memories.md) § What a guest may do:
navigate, download, upload, `deleteMedia` none/own/any, createMemory, createChapter, and the fields this CR draws no
control for (editMeta, setBanner, mark, filter) — ported now, so D12's CRs add no rule. `GalleryModel` and `Lightbox` show
an action only where it says yes; a guest's `deleteMedia: own` is a purge (`deleteShareMedia`), confirmed, without Undo, as
the web's. **The apps open a gallery as a member only today** (a received link is #435's); the guest rows are held by
`commonTest` against the matrix and no app draws a guest-only screen, so the CR that lets an app open a link adds a source,
never a view. Rejected: a member-only model now and a guest model later — the second view is what inbox #401 forbids.

### D4 — A Memory and a chapter are made from New

- `AppKinds.Create` gains `MEMORY` (New Memory: at a space's top and in a folder, where `placement` allows, on Home and in
  Memories' list) and `CHAPTER` (New chapter: on a Memory's page, its page's own create action beside "Add").
  `createsWhere` and `ShellShown.creates` carry them (its stale "`drawerCreates`" doc corrected).
- The make is `NodeCreate.create` with `AppKinds.tokenInput` (`memories/<slug>`) through `ShellModel.createMemory` and
  `GalleryModel.createChapter`; a name's collision and refusal said as the web's `MemoryNodeDialog` says them; the new node
  opens (the web's). The words are the web's (`features/memories/i18n.ts`: `create`, its German), added to `words.json` in
  English and German.

### D5 — Adding photos and videos: the platform picks and derives, the core does the rest

- **The tier spec is the core's:** `MediaUpload.PREVIEW_EDGE` 2048, `PREVIEW_QUALITY` 0.85, `THUMBNAIL_EDGE` 512,
  `THUMBNAIL_QUALITY` 0.8, `POSTER_AT` = `min(0.1 s, duration / 2)` (the web's `mediaDeriver.ts`); Android's `MediaDerive`
  and Apple's `MediaTiers` read them, and the desktops get them in the request. Decoding and encoding stay the platform's.
- **The settle is the core's** (`GalleryModel`, D1): every Memories job on the app's one queue reads its gallery anew when
  DONE and the lists when its batch ends. The tray stays `DepotUploads`, which already mirrors the whole queue and runs its
  acknowledge for Memories' batches too (ADR 0130's Consequences); renaming it is churn in four apps for no rule — left.
- **The desktops derive through a host request:** `HostRequest.MediaTiers(path, type, spec)` → `HostReply.Tiers(width,
  height, previewSize)` with the payload `preview ‖ thumbnail` beside the JSON (ADR 0131 decision 3 extended inward, as
  `HostReply.Pixels`), or `Tiers(null…)` where the platform does not decode it (the original then goes alone, today's rule).
  The bridge's `Deriver` asks it on the queue's prepare thread, never the UI thread. Linux: the picture through glycin
  (`gdk::Texture`, its orientation applied), scaled, encoded by gdk-pixbuf's `savev("jpeg")` as the cover is; a video's
  poster from a GStreamer pipeline over the file (`uridecodebin ! videoconvert ! appsink`, seeked to `POSTER_AT`), crates
  already linked. Windows: `BitmapDecoder` with `RespectExifOrientation` and `BitmapEncoder`'s JPEG, as the cover; a
  poster by `MediaClip.CreateFromFileAsync` + `MediaComposition.GetThumbnailAsync`, else (measured first, D8) the
  `MediaPlayer` frame server. Rejected: tiers made in the core (a JPEG codec and a video decoder in Kotlin/Native — two
  native dependencies for what each platform has, against ADR 0130 decision 1); the host deriving every tier before it calls
  (every preview of a batch held at once).
- **Calls:** `Memory.Upload(gallery, label, paths)` → `Queued(count)` (a path not a medium by `isMedia` is skipped and said,
  never sent), the core opening each as a `FileSource` (`PickedPaths`) read at offsets. A desktop's pickers: GTK's
  `FileDialog.open_multiple` filtered to images and videos, WinUI's `FileOpenPicker` likewise. **A drop onto the gallery**
  (desktops) takes the dropped files through the same call; a dropped folder is said and not taken (Owner question 3).
- **Apple's Photos copies (R5) — decided:** not ADR 0047's subject (the person's own original, never a decrypted veyq
  copy), but its sweep must not be an accident. The copies go to `tmp/picked/<uuid>/`, named in `sweepTemporary`, each
  removed when its item is done, cancelled or abandoned (a failed one kept for its retry), all of them when the tenant
  closes. Rejected: `PHAsset` resources streamed (needs the photo library's permission, where the picker needs none) and
  `loadTransferable(Data)` (a video whole in memory). Android reads the picker's URIs at offsets and copies nothing.

### D6 — A medium trashed with Undo

`GalleryModel.trash(mediaId)` / `Lightbox.trash()`: `deleteMedia`, the medium out of the rows at once, Undo held for the
core's `TRASH_UNDO_MS` (6 s, a node's trash's) through `restoreMedia`, then dropped; a host shows Undo while the state
holds it and keeps no clock. As the web, restore is reached only from that Undo (`source.ts`); a trashed medium waits out
the node trash's retention (ADR 0113 decision 5). A guest's own-media purge (D3) is confirmed and has no Undo.

### D7 — Saving an original; playing a video

- **Save:** `Lightbox.save` is the Depot CR's `FileSave` over the original's frames (`MemoryMedia.stream`) under
  `MediaDetails.downloadName`, with its progress and Cancel, outliving the lightbox, stopping with the tenant. Desktops:
  `Medium.Save(lightbox, path)` → `Saving(save)`, `Watched.Save`, `Save.Cancel` (the Depot calls). Android keeps its
  document stream. **Apple on macOS** writes to the save panel's URL through `FileSink` (as `AppleDepot`), iOS keeps
  `fileMover` from the swept `tmp/downloads/` (R4).
- **Play:** `Medium.Reader(lightbox)` → `Answer.Reader(reader, size, mediaType)` through `Bridge.openReader`, refused
  `NO_MEDIUM` for a picture; the reader closes with the page (D2). The desktops' players are the Depot ones made shared:
  Linux `gtk::Video` over `gtk::MediaFile::for_input_stream(reader_stream)`, Windows `MediaPlayerElement` over
  `ReaderStream`; no autoplay; a codec the system lacks says `memory_video_failed` with Save. The phones keep
  `ReaderPlayer`/`ReaderPlayback` over the model's reader.

### D8 — What each desktop proves before it builds on it

Each a phase's first measurement, recorded either way: Linux — glycin's HEIC decode and gdk-pixbuf's JPEG save in the GNOME
51 SDK `run.py build` uses and in the container (where HEIC does not decode, the original goes alone with its icon, as on a
phone that cannot decode it; recorded, not worked around); the GStreamer poster of an MP4 and a WebM. Windows — EXIF
orientation through `BitmapDecoder`; the poster through `MediaComposition.GetThumbnailAsync` in the unpackaged app, else the
frame server; HEIC through the HEIF extension where installed. **No new crate or NuGet package is planned**; one a
measurement forces is read for licence, last release and deprecation and recorded at the line that adds it.

### D9 — The bridge carries the models, one line each

- **Held by id:** `Gallery.Open(scopeId, nodeId)` → `GalleryOpened(gallery)`, `Watched.Gallery(gallery)` →
  `Update.Gallery(GalleryShown)`; `Watched.GalleryLive(gallery)` (the poll, a watch with no update of its own);
  `Gallery.Tile(gallery, mediaId)` and `Gallery.ChapterCover(gallery, nodeId)` → `Answer.Bytes`; `Gallery.Trash/Undo`,
  `Gallery.CreateChapter(name)` → `Stored(stored, notice)`; `Gallery.Close`.
- `Lightbox.Open(gallery, index)` → `LightboxOpened(lightbox)`, `Watched.Lightbox` → `Update.Lightbox(LightboxShown)`;
  `Lightbox.Go/Picture/Slideshow/Details/Trash/Close`; `Medium.Reader`, `Medium.Save`.
- `Memory.Upload(gallery, label, paths)`; `Shell.CreateMemory(scopeId, parentId, name)`; the share sheet through CR 3a's
  `Share.*` once `shareable` is true (D10).
- `HostRequest.MediaTiers` (D5). **Answers say what the host acts on at once** (CR 3a's trap); edits applied in the order
  called (the Depot CR's `Share.Label` trap). `SecretFreeSchemaTest` holds unchanged; a row's bytes never ride in an update.

### D10 — A Memory's and a chapter's header, and its links

- `AppKinds.header(MEMORY/CHAPTER).shareable` becomes true: the share sheet offers READ, READ_WRITE and WRITE_ONLY as the
  web's `ShareCreateDialog` does for these kinds; a WRITE_ONLY link on a Memory or chapter seals to **the node's own drop
  key**, made and set by its first uploader link (the web's `share.ts`, [memories](../docs/apps/memories.md) § the role
  matrix) — ported into `ShareLinks`, its seal context `drop-private-key` the conformance-pinned one.
  `ShareSheetTest` gains the Memory's roles; a live case reads a WRITE_ONLY guest's upload back on the web's gallery.
- **Upload** joins the header's plan as the page's own action where `headerOrder` puts it (New's "Add" on the phones stays
  where it is: one action, two places is the web's). Download, copy and banner stay closed (D12).
- **The hero stays "only once a cover exists"** on every app (Owner question 1); the desktops draw it on a Memory's page as on
  a Pot's, and each chapter row and each Memory row in Memories' list its cover thumbnail through the core's row-cover read
  the phones use (`grep -rn "THUMBNAIL_EDGE\|coverOf\|rowCover" src/mobile/core/src/commonMain` at implement), asked as
  `Answer.Bytes`.

### D11 — The screens, each to its platform's guideline

- **Linux (GNOME HIG):** the gallery a `gtk::GridView` of square `gtk::Picture` tiles (a video's badge), virtualized, the
  chapters an `AdwPreferencesGroup` of rows with their covers above it, the count in the subtitle; New's New Memory / New
  chapter an `AdwAlertDialog` with an entry; "Add photos and videos" the page's header button; the lightbox an
  `AdwNavigationPage` over black with a `gtk::Picture` in a zoomable `gtk::ScrolledWindow` (Ctrl+wheel, pinch, `+`/`-`/`0`),
  ←/→ and swipe, a header bar with Slideshow, Details (an `AdwDialog` with an `AdwPreferencesGroup`), Save, Trash; Undo in an
  `AdwToast`.
- **Windows (Fluent):** the gallery an `ItemsRepeater` with a `UniformGridLayout` of tiles (virtualized), the chapters a
  `ListView` above it; New Memory / New chapter a `ContentDialog` (one at a time); the lightbox a page over black with a
  `ScrollViewer` (zoom), `FlipView` paging, a `CommandBar` with Slideshow, Details (a `TeachingTip` or pane), Save, Trash;
  Undo an `InfoBar` action.
- **The phones** draw the new functions with what they already use: New's menus, `HorizontalPager`/`TabView` with the
  platform's zoom (Compose's `transformable`, SwiftUI's `MagnifyGesture` over a `ScrollView`), Undo as the trash's.
- **Every word** from `words.json`, reusing `memory_*` (R11); a new one in English and German. No new control library.

### D12 — Not this CR's: the rest of what the web has, each its own CR

Date, place with the map and its consent, participants and a medium's capture time (one curation CR: they share the
node's sealed meta and its 409 merge, `uc.memory.concurrent-curation`); the banner (ADR 0102); item marks, buckets and the
gallery's filter; export, import and copy of a Memory; its re-key (`NodeRekey` leaves it to a web device); a Memory as a
share target (ADR 0132 decision 2) and a drop onto a phone's gallery. Each is an inbox row (#890–#893), D3 already holding
their rights. Rejected: one CR for all of it — four independent slices whose proofs share nothing, which the CR rule calls
separate CRs.

### D13 — `satisfies: []`, as the Depot CR

The Memories use cases are satisfied by the web; this CR brings platform parity, whose record is native-apps' table and §
Built, by area. Where a phone's or a desktop's pass proves a step the use case names, native-apps cites it; no coverage YAML
changes.

## Owner steps

None. Throwaway emulators; the VMs are disposable (owner 2026-11-10). The Windows drop stays the owner step native-apps
already names (UAC on); this CR's drop is held by its test there, as the Depot CR's.

## Inventory (locators — run at implement time)

- What the phones still decide: `grep -nE 'memoryGallery|galleryRevision|mediaPictures|mediaPicture|mediaExif|mediaUploaders|downloadMedium|mediaReader|uploadMedia|scopeNodes|PREVIEW_EDGE|previewEdge|THUMB_EDGE|thumbEdge|2048|0\.85|PickedMedia|downloadDir' src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/*.kt src/mobile/apple/Veyq/*.swift`
- The core's names a move touches, tests included: `grep -rln 'MemoryMedia\|MediaDetails\|MediaUpload\|AppKinds.Create\|createsWhere\|homeCreates\|shareable\|FileSave' src/mobile/core/src src/mobile/bridge/src src/mobile/android/src src/mobile/apple`
- The bridge's surface: `grep -nE 'openReader|HostRequest|HostReply|Upload\.|Save\.|creates' src/mobile/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/*.kt`
- The desktops' not-built doors, New and the players to share: `grep -rnE 'not_built|NotBuilt|creates|reader_stream|ReaderStream|MediaFile|MediaPlayerElement' src/mobile/linux/src src/mobile/windows/Veyq`
- The web's rules to port: `grep -n 'SLIDESHOW\|ITEMS_POLL\|deleteMedia\|restoreMedia' src/web/src/features/memories/components/MediaLightbox.vue src/web/src/features/memories/source.ts src/web/src/features/memories/mediaRevisionPolling.ts`; `src/web/src/features/memories/rights.ts` whole
- Words to reuse: `/usr/bin/python3 -c "import json; d=json.load(open('src/mobile/words/words.json')); print(sorted(k for k in d if k.startswith(('memory_','tab_memories','app_memories','cover_'))))"`
- The flow surfaces: `grep -n 'Memories\|Memory' docs/crosscutting/native-apps.md docs/inbox.md`; the site: `git grep -li "memor" -- site/src/content/docs | xargs grep -li "app\b\|android\|iphone\|linux\|windows"`

## Phases

### Phase 1 — The moved rules in the core: `GalleryModel`, `Lightbox`, the details, the save, the tier spec; Android and Apple thinned

What R3 lists moves (D1, D2 without the slideshow, D2b, D5's spec and settle, D7's save); D5's Apple copies; R4's macOS save.
No new function reaches a screen.

**Done when:**
- `commonTest` pins each moved rule, each bitten (mutated → its case red → restored, by a script): the rows in the grid's
  order and tiers; a re-read on the lists' revision and on an own upload's DONE, not otherwise; the chapters of a Memory and
  none of a chapter; the lightbox's index kept by id across a re-read, its neighbour after a removal; a video's reader
  opened only for a video and closed when paged away and when the lightbox closes; blocked said; the uploaders read once per
  gallery; the save's name, progress and cancel (`FileSave` reused); the spec constants the ones `MediaUpload.Derived` names.
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green; `:android:assembleDebug`, `xcodebuild -target Veyq
  -sdk macosx CODE_SIGNING_ALLOWED=NO` and the iOS simulator build green; both desktops' `run.py build` green.
- Gone from the apps (the inventory's first locator finds none): `memoryGallery`, `galleryRevision`, the apps' gallery and
  lightbox state, `mediaUploaders`/`mediaExif`/`downloadMedium`/`mediaReader` as app logic, the copied spec numbers; Apple's
  copies under `tmp/picked/`, swept by name; macOS's save through the panel and `FileSink`.
- Android (throwaway AVD) and Apple (the iPhone 18 Pro, the macOS build run this time), in `Android memory` / `Apple memory`
  made on the web: three photos (one with GPS and EXIF from the fixtures) and an MP4 of > 200 MiB uploaded, the grid in
  order, the lightbox paged, the video played and seeked near the end, the details with camera, place and uploader, an
  original saved (its SHA-256 the upload's); read as text first (`uiautomator dump` / accessibility); `run-as … ls cache` and
  the simulator container's `tmp` show nothing decrypted during and after; unchanged from before otherwise.

### Phase 2 — The new functions in the core: create, rights, trash with Undo, the live poll, the slideshow, a Memory's links

D3, D4, D6, D1's `live()`, D2's slideshow, D10's links and header. No app draws them yet (phase 6 and the desktops do).

**Done when:**
- `commonTest`, bitten: `MemoryRights` for a member and each role on each root kind against the matrix (a guest granted
  `editMeta`, a WRITE_ONLY guest reading media, a READ_WRITE guest deleting another's medium → red); `Create.MEMORY` offered
  exactly where `placement` allows, `CHAPTER` only on a Memory; a collision said; trash out of the rows at once, Undo within
  `TRASH_UNDO_MS` and not after (virtual time), a guest's own purge confirmed and without Undo; the poll reading only when a
  revision moved, ended with the page; the slideshow on virtual time (5 s, a video's poster one interval, stopped at the
  last, paused by `go`); `ShareSheetTest`'s Memory roles, the first WRITE_ONLY link setting the node's drop key and the
  second reusing it.
- `MemoryLiveTest` (`VEYQ_LIVE`): an `App memory <n>` made by `ShellModel.createMemory`, a chapter in it, a photo uploaded,
  trashed and undone, a second trashed for good; a WRITE_ONLY link minted, a guest's upload through the web's guest page
  (`VEYQ_LIVE_MEMORY_GUESTS`, the Depot CR's way) read back by `GalleryModel` under the guest's `K_up`; purged.
- Builds as phase 1.

### Phase 3 — The bridge: gallery, lightbox, uploads by path with the host's tiers, save, reader, create, links

D9.

**Done when:**
- `DispatchTest` drives every call of D9 over a played gallery and a played host answering `MediaTiers`, bitten (a tile's
  bytes not the tier's; a reader not closed when paged away or with its tenant; a path read whole instead of at offsets — the
  test's file records the reads; tiers asked on the calling thread; an upload answered before it was queued; Undo after its
  window; a chapter offered outside a Memory → red); `WireTest` samples each new shape; `SecretFreeSchemaTest` unchanged.
- `LiveBridgeTest.aMemoryIsMadeFilledByPathPlayedSavedAndEmptiedThroughCalls`: in an `App memory <n>`, three photos and a
  600 MiB WebM uploaded by path with the played host's tiers, read back as tiles, a range of the video across a frame's
  edge at the file's SHA-256, the original saved and a second save cancelled with nothing left, one medium trashed and
  undone, a chapter made; purged.
- Builds as phase 1.

### Phase 4 — Linux: Memories drawn

D8 (Linux) first, then D11's screens over phase 3's calls: New Memory and New chapter, the gallery with its chapters' covers and
the live poll, the hero, the lightbox (zoom, slideshow, video, details, save, trash), adding through the dialog and a drop,
the share sheet's Memory roles, Memories' list with covers.

**Done when:**
- D8's Linux measurements recorded in the phase's retrospective; `cargo test` holds the tiers' host (an image turned by its
  EXIF, a video's poster at `POSTER_AT`, an undecodable file answering no tiers) and the drop's sort, each bitten.
- `linux/run.py build` (clippy `-D warnings`, `cargo test`) green; `scripts/validate_mobile_words.py` and
  `validate_mobile_platform_guidelines.py` green. A MEMORY no longer opens "not built".
- Container (Broadway on 8097), en/light and de/dark, in `Linux memory` made from New: a chapter made; photos (one turned
  by EXIF, one with GPS) and the 600 MiB WebM and an MP4 added through the dialog, their tiles drawn from the tiers this
  host made; a photo uploaded from the web appears within one poll; the lightbox paged, zoomed, the slideshow run to its end,
  the video played and seeked, the details, an original saved at its SHA-256, a medium trashed and undone; a READ link and a
  WRITE_ONLY link made, the guest's upload seen; the app's peak memory (`VmHWM`) below 400 MiB through the WebM's play and
  upload; no plaintext written (`find / -xdev -newer <marker>` in the writable paths). In the Fedora VM's session: a drop of
  three photos from Files onto the gallery and the video's sound.

### Phase 5 — Windows: Memories drawn

D8 (Windows) first, then the same screens as phase 4 in Fluent.

**Done when:**
- D8's Windows measurements recorded; Veyq.Tests holds the tiers' host and the drop's sort, each bitten.
- `windows/run.py build` (Veyq.Tests, the NuGet deprecation gate) green; both guards green. A MEMORY no longer opens
  "not built".
- The VM, en/light and de/dark, in `Windows memory`: phase 4's pass through `Veyq.Drive` (its pickers driven as the Depot
  pass drove them); peak working set's growth over idle not scaling with the file (the Depot CR's bound); `%TEMP%` listed
  before and after showing nothing decrypted; the drop held by its Veyq.Tests case (native-apps' UAC owner step stands).

### Phase 6 — The phones draw the new functions

Phase 2's functions on Android and Apple (D11's phone line): New Memory and New chapter, chapters with covers, the live poll,
zoom and the slideshow, trash with Undo, a Memory's links in the share sheet.

**Done when:**
- No rule in the apps (the inventory's first locator unchanged from phase 1; `grep -nE 'SLIDESHOW|5000|5_000|TRASH_UNDO|6_000'` over both apps finds none).
- Builds as phase 1. Android (throwaway AVD) and Apple, in `Android memory` / `Apple memory` made from New: a chapter made
  (on Apple driven where the simulator tool types — else by Android and the core's tests, recorded), zoom and the slideshow,
  a web upload appearing within one poll, a medium trashed and undone (Undo on Apple within the tool's lag or recorded as
  the Depot CR recorded it), a WRITE_ONLY link made and a guest's upload seen.

### Phase 7 — The docs

- ADR 0130's Consequences: Memories' screen models (`GalleryModel`, `Lightbox`, `MemoryRights`, the create, the trash), one
  paragraph as Depot's.
- ADR 0131's Consequences: the second reader caller, `HostRequest.MediaTiers`'s payload inward, `Memory.Upload`.
- ADR 0047's Consequences: Memories under the apps' rule; Apple's `tmp/picked/` (D5).
- ADR 0123's Consequences: Memories on the desktops, D8's measurements.
- ADR 0132's Consequences: decision 2's Memory still not built (#893).
- `src/mobile/CLAUDE.md`: the models, the calls, the traps met.
- native-apps: the Linux and Windows rows, § Built, by area (Memories, Sharing, Uploads), § Open (the "other screens"
  lines narrowed; the bridge's "A Memory's medium through a reader" removed; the Memories line narrowed to D12's rows;
  the Flatpak's and MSIX's notes gain what D8 found about HEIC).
- `docs/apps/memories.md` only where a rule moved changes what it says.
- Published pages: none expected (R12); the phase re-runs R12's locator and says so in the harvest.
- The harvest, then the CR deleted.

**Done when** `/usr/bin/python3 scripts/validate_*.py` (guard_gaps skipped unless a guard changed) is green.

## Not in this CR

D12's list (inbox #890–#893); opening a received link in the app (#435, D3 keeps it a source); uploads that go on in the
background on the phones and placeholder tiles (native-apps § Open, Uploads); the small playable video tier (#493); one
gallery over a Memory and its chapters (#16); batched presigning of tiles (a measured slowness first, if the passes show
one).

## Owner questions

1. **The hero:** the apps draw a Memory's hero only once it has a cover; the web always frames one (16:9) and opens the
   cover dialog from it. **Recommended:** keep the apps' (an empty frame costs a phone's first screen; the cover stays one
   tap away in the header), on the desktops too.
2. **Scope:** curation (date, place and map, participants, capture time), the banner, marks with buckets and the filter,
   export/import/copy with the re-key, and a Memory as a share target each go to their own CR (D12, #890–#893).
   **Recommended:** yes — this CR is already seven phases; curation next, since it is what a member misses first.
3. **A folder dropped onto a gallery (desktops):** **Recommended:** said and not taken (a drop of photos is the case; a
   folder's walk into one Memory flattens it, which the web does not offer either). The alternative: its media walked by
   `FolderUpload.keeps` and the rest said.

D2's slideshow rule (the web's), D2b's one uploader read per gallery (Android's), D5's tier spec in the core, D6's Undo window
(the node trash's 6 s) and D7's macOS save are technical unifications, open to the owner's veto.


**Decided** (the coordinator, 2026-11-13, under the owner's standing mandate; the owner may overturn any):
- 1: as recommended — the hero shows only once a cover exists, on the desktops too.
- 2: as recommended — the D12 cut holds; curation is the next CR (#890).
- 3: as recommended — a folder dropped on a gallery is said not to be taken; no folder upload into a Memory in this CR.

## Retrospective
