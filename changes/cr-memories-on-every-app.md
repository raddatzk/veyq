---
id: cr-memories-on-every-app
title: Memories on every app — the gallery, the lightbox and the uploads moved into core screen models, then bridged and drawn on Linux and Windows
status: in-progress
satisfies: []
adrs: ["0136", "0137", "0123", "0122", "0138", "0047", "0080", "0105"]
invariants:
  - every rule is the core's (ADR 0136 decisions 1 and 2) — what Android's and Apple's Memories still decide in their app models and views (the gallery's reads and when it reads anew, the lightbox's page, its picture and its reader, the details, the save of an original, the upload's settle, the tier spec) moves into core screen models first, both phones thinned in the same phase and unchanged on a device
  - share view = member view (owner 2026-10-09, inbox #401) — one gallery model for a member and a link's guest, whose rights only remove functions; the rights are the web's `memoryRights`, fail-closed, ported once into the core
  - no plaintext at rest but where the person saves it (ADR 0047's Consequences, every app) — tiles and previews in memory, a video through the core's `RangeReader`, an original written only to the path a save dialog gave (or iOS's swept `fileMover` directory)
  - the bridge only carries — one line per call onto a core model, no key, seal or token outward (`SecretFreeSchemaTest`); shown bytes beside the JSON, a medium through a reader by id, files by path (ADR 0137)
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

The order is ADR 0136 decision 6's, as the Depot CR's: the moved rules into the core first with both phones thinned onto
them, the new functions into the core, then the bridge, Linux, Windows, the phones drawing the new functions, the docs.

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` at `9037319b2` (the Depot CR harvested and deleted at `d313c1f4f`; the
  account settings' phases 3 and 4 merged; tree clean). The Depot CR's file page, reader players, `FileSave`, uploads by
  path, `DepotUploads` tray and drop handlers, and CR 3a's header, cover dialog and share sheet are this CR's floor.
- **Read first:**
  - ADR 0136 (decisions 1, 2, 6 and the Consequences' Depot paragraph — the pattern), ADR 0137 (all four decisions and its
    Depot Consequences), ADR 0047's Consequences (the apps' rule), ADR 0138 decision 2 (a Memory as a share target —
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

ADR 0136 decision 2's shape (state a flow and a watch, actions plain functions, tested in `commonTest` over a gallery the
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
- **The decoded picture's cache stays the platform's** (decoding is, ADR 0136 decision 1); the core caches no bytes: a
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
  acknowledge for Memories' batches too (ADR 0136's Consequences); renaming it is churn in four apps for no rule — left.
- **The desktops derive through a host request:** `HostRequest.MediaTiers(path, type, spec)` → `HostReply.Tiers(width,
  height, previewSize)` with the payload `preview ‖ thumbnail` beside the JSON (ADR 0137 decision 3 extended inward, as
  `HostReply.Pixels`), or `Tiers(null…)` where the platform does not decode it (the original then goes alone, today's rule).
  The bridge's `Deriver` asks it on the queue's prepare thread, never the UI thread. Linux: the picture through glycin
  (`gdk::Texture`, its orientation applied), scaled, encoded by gdk-pixbuf's `savev("jpeg")` as the cover is; a video's
  poster from a GStreamer pipeline over the file (`uridecodebin ! videoconvert ! appsink`, seeked to `POSTER_AT`), crates
  already linked. Windows: `BitmapDecoder` with `RespectExifOrientation` and `BitmapEncoder`'s JPEG, as the cover; a
  poster by `MediaClip.CreateFromFileAsync` + `MediaComposition.GetThumbnailAsync`, else (measured first, D8) the
  `MediaPlayer` frame server. Rejected: tiers made in the core (a JPEG codec and a video decoder in Kotlin/Native — two
  native dependencies for what each platform has, against ADR 0136 decision 1); the host deriving every tier before it calls
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

- **Linux (GNOME HIG):** the gallery the justified grid (D14: core's `GalleryModel.layout`, its rows placed by the app — a
  `gtk::ListView` of row widgets, each a `gtk::Fixed` of `gtk::Picture` tiles at their frames, a video's badge —, only the
  rows near the viewport built; superseding the square `gtk::GridView` first planned here), the
  chapters an `AdwPreferencesGroup` of rows with their covers above it, the count in the subtitle; New's New Memory / New
  chapter an `AdwAlertDialog` with an entry; "Add photos and videos" the page's header button; the lightbox an
  `AdwNavigationPage` over black with a `gtk::Picture` in a zoomable `gtk::ScrolledWindow` (Ctrl+wheel, pinch, `+`/`-`/`0`),
  ←/→ and swipe, a header bar with Slideshow, Details (an `AdwDialog` with an `AdwPreferencesGroup`), Save, Trash; Undo in an
  `AdwToast`.
- **Windows (Fluent):** the gallery the justified grid (D14: an `ItemsRepeater` over `GalleryModel.layout`'s rows, each a
  `Canvas` of tiles at their frames — virtualized by the repeater; superseding the `UniformGridLayout` first planned), the chapters a
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
share target (ADR 0138 decision 2) and a drop onto a phone's gallery. Each is an inbox row (#890–#893), D3 already holding
their rights. Rejected: one CR for all of it — four independent slices whose proofs share nothing, which the CR rule calls
separate CRs.

### D13 — `satisfies: []`, as the Depot CR

The Memories use cases are satisfied by the web; this CR brings platform parity, whose record is native-apps' table and §
Built, by area. Where a phone's or a desktop's pass proves a step the use case names, native-apps cites it; no coverage YAML
changes.

### D14 — The web's justified grid on every app, phones included

The web lays a gallery out as a justified grid (`MediaGrid.vue` over `mediaGridLayout.ts`, ADR 0105: aspects kept, each row
filled to the width, the last row at the target height, only the rows near the viewport mounted); the apps drew square
tiles. **Every app draws the justified grid** — the phones too, with the smaller target row height the web already gives a
narrow grid (150 up to a width of 720, else 200, in the platform's own unit: two to four tiles a phone's row, as Google
Photos and Flickr show), one look across web and apps. Per ADR 0136 the layout is logic: `memories/MediaGridLayout.kt`
ports `mediaGridLayout.ts` once (bounded aspect 3:4 … 4:3, the 4:3 fallback, the 8 gap, `MediaGrid.vue`'s target and its
`windowRows` as `Layout.window`), held to the web's own cases in `commonTest`; `GalleryModel.Row.aspect` is the medium's
sealed dimensions (`MemoryMedia.Meta.width`/`height`, already read) bounded; `GalleryModel.layout(rows, width)` hands a
platform each row with its tiles' frames. The platform draws the rows through its lazy list (Compose's `LazyColumn`,
SwiftUI's `LazyVStack`), which builds only the rows near the viewport — ADR 0105's windowing for free; a desktop placing
tiles itself asks `Layout.window`. Rejected: a square grid on phones beside the justified one elsewhere (two looks for one
gallery; the coordinator under the owner's mandate, 2026-11-13); a per-platform layout (four copies of a rule).

### D15 — A Mac's lightbox opens in the window, a window of its own only when asked

Owner (2026-11-13): on a Mac the lightbox came up as a sheet, which reads as a detached second window — "auf mac wird die
Preview einer Galerie aus der App als zweites Fenster herausgelöst, das würde ich lieber optional über einen Klick machen".
**On a Mac the lightbox opens in the main window's content, where the gallery was** (a navigation destination: the toolbar's
back and Escape return to the gallery — the HIG's in-window navigation), and **in a window of its own only when asked**:
"Open in New Window" in the lightbox's own bar (beside Close, Details and Save — a Mac draws its actions there, `07e492ebe`) and in the File menu (the focused scene's), in a tile's context menu, and on
a ⌘-click of a tile (the HIG's modifier for a new window). The window is a value-based `WindowGroup` whose value carries
the opaque ids alone (space, node, medium — never a name, a key or bytes) and which the system does not restore at the next
start; it opens **its own** `GalleryModel` over the shell (`ShellModel.galleryAt` resolves the ids against the lists) and
its own core `Lightbox` on the medium (`GalleryModel.lightboxOn`, which waits for the gallery's first read), so the main
window's page and the window go independently. The reader-lifetime rules of phase 1 hold for both: a window's lightbox
closes its reader when it pages away or stops, the window's close closes its lightbox and gallery, and the tenant's close
closes every window's gallery (and the window with it). iOS is unchanged (full screen).

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

**Status: built and unit-proven, every new check bitten; both phones thinned and building, Android's page driven offline
over a played gallery (2026-11-13). The live case is green (2026-11-13, below); the signed-in device passes are pending.**

**Built:**
- **core `memories/GalleryModel.kt`** — `GalleryModel(node, door)`, opened by `ShellModel.galleryModel(ref, uploads)`:
  `State(rows, chapters, failed)`, each `Row` what a screen draws (id, name, type, video, blocked, `tile`/`picture` — the
  blob ids of the grid's and the lightbox's tier, which a platform's decoded cache keys by —, capture time, size, `said`:
  `memory_blocked` for a blocked one). It reads anew on each revision of the shell's lists and when `MediaUploads` says a
  medium of this device is in **this** node, nothing else; a Memory's chapters (`MediaDetails.chapters`, the space's nodes
  read only for a MEMORY). `tile(mediaId)` reads the grid's tier when the platform asks (the core caches no bytes).
  `lightbox(index)` → **`Lightbox`**: `State(rows, index, reader, save)`, the index kept by the medium's id across a
  re-read and given to the one now at its place when it went; `go`, `picture(mediaId)`, `play()` (the original's
  `RangeReader`, only for a video not blocked, held as `State.reader`), `stop()`, `details(mediaId)` (`MediaDetails.rows`
  with the EXIF tier read once per medium, the uploaders read once per gallery for every owner key it shows — a key not
  read yet asked with the next), `downloadName()`, `save(into)` (Depot's `FileSave` over the original's frames under
  `MediaDetails.downloadName`, outliving the lightbox), `close()`. The model closes the reader when the lightbox pages
  away, the player's view leaves (`stop`), the lightbox or the gallery closes (the tenant's close closes the gallery), or a
  re-read takes the playing medium away. **`RangeReader.close()`** is new: frames dropped, every read after throws.
- **core `memories/MediaUploads.kt`** — `ShellModel.mediaUploads(queue, deriver, left, changed)`, one per open tenant over
  the app's one queue: `upload(scopeId, nodeId, label, sources)` queues `MediaUpload.Job`s (a source that is no medium by
  `isMedia` is handed back, not queued); the settle is the core's: a medium in names its node on `uploaded` (the gallery
  reads), a batch's end `changed` (the lists), and each picked file is handed back to the platform (`left`) once the queue
  is done with it — DONE or CANCELLED, or gone from the queue after it FAILED (abandoned), read from the queue's state —,
  every one still held on `close()` (the tenant closes), none on `handOver(next)` (the same tenant opened again over the
  same queue, its successor following them). A failed one's file stays for its retry.
- **core `MediaUpload`** — the tier spec: `PREVIEW_EDGE` 2048, `PREVIEW_QUALITY` 0.85, `THUMBNAIL_EDGE` 512,
  `THUMBNAIL_QUALITY` 0.8, `POSTER_AT_MS` 100 and `posterAtMs(duration)` = min(100 ms, duration / 2); `MediaTierSpecTest`
  (jvmTest) reads `src/web/src/shared/image/mediaDeriver.ts` itself and holds them to it.
- **core `appleMain` `AppleMemories`** — `tile`, `picture` (as `NSData`, off the main thread), `source(url, …)`,
  `save(lightbox, path)` (`Lightbox.save` into `FileSink`, awaited to its end), `readRange`, `derived`, `urlOf`; its
  `read`, `uploadJob` and `download` are gone.
- **Android** — `MemoryScreen` draws `GalleryModel` (`MemoryGallery`) and `Lightbox`; the pager pages the model and follows
  its index; the video plays `State.reader`; the details are `Lightbox.details`; Save goes through `CreateDocument` into
  `AppModel.saveMedium` (`Lightbox.save`, the document deleted where it did not end SAVED, the end said as before).
  `AppModel` lost `memoryGallery`, `scopeNodes`, `mediaExif`, `mediaUploaders`, `downloadMedium`, `mediaReader`,
  `galleryRevision`; it holds one `GalleryModel` (closed on the next and with the tenant) and one `MediaUploads` (its
  `left` does nothing: Android reads the picker's URIs in place). `MediaDerive` reads the core's spec. A debug-only
  harness, `DevMemory` (extra `veyq.devMemory=1`; logcat `veyq-memory`; a release stub), draws `MemoryGallery` over a
  played gallery: two chapters, three photos drawn on the device (one with camera, place and GPS, three uploaders: a
  member, a guest, one who left), a blocked medium, and a video pushed to the app's external files (`veyq-memory.mp4`).
- **Apple** — `MemoryScreen` draws `GalleryModel` through `AppModel.galleryState` (a watch, as the file page) and
  `Lightbox` through `LightboxBox`; `MediaTiers` reads the core's spec (`posterAtMs` for the poster's time). **macOS saves
  an original through `NSSavePanel` into `FileSink`** (`AppleMemories.save`), iOS keeps `fileMover` from the swept
  `tmp/downloads/`. **The Photos picker's copies go to `tmp/picked/<uuid>/`** (`PickedMedia`), removed by
  `AppModel.removePicked` (only a directory of `tmp/picked/`) when `MediaUploads` hands the file back — done, cancelled,
  abandoned, the tenant closed — and by name at start (`sweepTemporary` names `picked`); a pick whose page went before it
  was queued is removed at once.

**Deviation:** D1/D5 put the settle in `GalleryModel`; it sits in `MediaUploads`, which outlives a page (an upload goes on
while the person looks elsewhere), and `GalleryModel` reads on its `uploaded`. A re-read on a DONE now happens only for the
gallery of the node the medium went into — the phones re-read any gallery on screen. The inventory's first locator still
finds, by name, what stays the platform's by D1, D5 and D7: the decoded-picture cache (`mediaPicture`, now keyed by the
row's blob id), the platform's pick handed on (`uploadMedia` → `GalleryModel.upload`), `PickedMedia`, iOS's
`downloadDir`, reads of `MediaUpload.PREVIEW_EDGE`, and the words `memory_gallery_*`; no rule and no copied number.

**Met:** `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest
:android:assembleDebug :android:compileReleaseKotlin` green from `src/mobile` (core jvm 592 tests, 5 skipped — the live
cases; macosArm64 411; bridge 73, 1 skipped). New: `GalleryModelTest` (10), `MediaUploadsTest` (6), `MediaTierSpecTest` (2).
**Bitten**, each by a script (mutation → `./gradlew :core:jvmTest --tests <class>` red → restored with `/bin/cp -f`), 27 then 11
mutations, all red but one — `MediaUploads`' first form, followed through its settle callbacks, let a failed one's file go
unbitten; it was rewritten to follow the queue's state and that mutation bit in the second run: the tile read from the lightbox's tier; a re-read on any node's upload; none on the lists' revision; a
chapter reading chapters; the index not kept by id; a re-read that took the playing video, paging away, `stop` and the
lightbox's close each leaving the reader open; a picture opening a reader; a closed `RangeReader` still reading; blocked not
said, a blocked original saved; the uploaders read per medium, the EXIF every time; the save without progress, under
another name, `downloadName` not the saved one; the gallery not told of an upload in, the lists not at a batch's end; a
failed one's file handed back while a retry may read it; an abandoned one, a cancelled one, the tenant's close handing back
nothing; one not yet in the queue's state handed back; a hand-over handing back or dropping the files; a non-medium queued;
the preview's quality, the thumbnail's edge and the poster's halving off the web's. The Apple app builds for macOS
(`xcodebuild -project Veyq.xcodeproj -scheme Veyq -destination 'platform=macOS' -derivedDataPath ../build/apple-mac
CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= CODE_SIGN_ENTITLEMENTS= build`, from `src/mobile/apple`) and
for the iPhone 18 Pro simulator (`-destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath
../build/apple-sim`). `windows/run.py build` green (Veyq.Bridge.Tests and Veyq.Tests passed). `linux/run.py build`: clippy
`-D warnings` and the build green, `cargo test` 42 of 43 — `kit_drawing`'s core read of the printed kit hung past its
60 s twice (load 7–10), the hang that test's own comment records for 3 of 7 builds on 2026-11-13; nothing of Linux's or the
bridge's surface changed here. The guards (`/usr/bin/python3 scripts/validate_*.py` but guard_gaps) green before each
commit. **Android, offline** (throwaway AVD `veyq_memory_tmp`, Pixel 8, API 36; deleted after) over `DevMemory`
(`adb shell am start -n me.raddatz.veyq/me.raddatz.veyq.mobile.android.MainActivity --es veyq.devMemory 1`, a 20 s
1280×720 MP4 made by ffmpeg pushed to `/sdcard/Android/data/me.raddatz.veyq/files/veyq-memory.mp4`), read as text by
`uiautomator dump` and logcat `veyq-memory`: the chapters, "5 items", the grid newest first with the blocked one last, each
tile its thumbnail's read (none for the blocked one); the lightbox ("2 of 5") read the preview; the details with camera,
focal length, aperture, exposure, ISO, flash, altitude, "Added by Ada" before the place and position — the uploaders read
once for all three keys, a second medium's details ("A guest") reading none; the video played through the core's reader to
its end, sought back to 0:10 and played on; paged away and back it showed its play button again (the reader released);
`beach.jpg` saved through the system's dialog (34 029 B, the size its details said), `clip.mp4` saved at the pushed file's
SHA-256 (`50add77b…`), "“clip.mp4” is downloaded." said; `run-as me.raddatz.veyq ls -laR cache files` empty but
`profileInstalled`. **Apple, offline:** the macOS build started with a `$TMPDIR/picked/0000-test/a.jpg` planted — gone at
start (the sweep by name); the app stopped by its PID.
**Live (2026-11-13, measured):** from `src/mobile`, `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main
checkout>/.run/.env VEYQ_LIVE_MEMORY='App memory 7341' ./gradlew :core:jvmTest --tests
'me.raddatz.veyq.mobile.memories.MemoryLiveTest' --rerun` green, both cases (`aMemoryIsReadThroughItsScreenModels`,
`aMemoryGalleryIsRead`). The Memory was made for it in the principal's personal space by a scratch `tsx` script over the
web's `createMemory` and filled by `MemoryUploadLiveTest` (`VEYQ_LIVE_MEMORY_UPLOAD`: the EXIF fixture photo with drawn
tiers and `dated.mp4` with a poster), trashed and purged after. Read: 2 media, every tile and picture, the photo's 24
detail rows (camera, place, uploader `MEMBER`), the video's reader opened and closed when paged away, the video's original
saved at its SHA-256. **Not proven by it:** a range *across* a frame's edge — the fixture video (2 339 B) is shorter than
one frame, so the read lands at its end; a device pass's > 200 MiB MP4 is what crosses one. The three memory live tests
read `VEYQ_ENV_FILE` before the checkout's `.run/.env` now (a worktree has none), as `LiveSignInTest` does. **Pending,
devices** (signed in, per the Done-when): make `Android memory` / `Apple memory` on the web with three fixture photos (one
with GPS and EXIF, `MediaMetadataFixtureTest`'s) and an MP4 > 200 MiB (`ffmpeg`); Android on a throwaway AVD
(`android/devlogin.py`), Apple on the iPhone 18 Pro (`apple/devlogin.py 'iPhone 18 Pro'`) and the macOS build: the grid in
order, the lightbox paged, the video played and sought near its end, the details with camera, place and uploader, an
original saved (its SHA-256 the upload's; on macOS through the save panel), a Photos pick uploaded and its
`tmp/picked/<uuid>/` gone once it is in (`xcrun simctl get_app_container booted me.raddatz.veyq data` + `ls tmp`), and
`run-as me.raddatz.veyq ls cache` / the container's `tmp` with nothing decrypted during and after.
**Device passes (2026-11-13, the dev backend up; measured).** The Memory was made and filled by `DevMemoryTest`
(`VEYQ_LIVE=… VEYQ_DEV_MEMORY_FILL=<dir> ./gradlew :core:jvmTest --tests '*DevMemoryTest*' --rerun`: "App memory 84367" by
`ShellModel.createMemory`, then `a-exif.jpg` (the web's EXIF fixture), two ffmpeg photos and `d-big.mp4` — 241 108 268 B,
1280×720, 50 s, made with `/opt/homebrew/bin/ffmpeg` — uploaded by `MediaUpload.upload`, the tiers ffmpeg sidecars), and
purged after by `VEYQ_DEV_MEMORY_PURGE`. **Android** (throwaway AVD, `devlogin.py`): the grid "4 items" newest first; the
lightbox paged ("1 of 4" … "4 of 4"); the video played through the core's reader, sought to 0:42 and playing on (0:44 — the
emulator under a load of 100+); the details of `a-exif.jpg`: camera Canon EOS R, lens, exposure, place "48.136944,
11.575000", "Added by Kevin Raddatz"; both originals saved through the system's dialog — `a-exif.jpg` and `d-big.mp4` at the
uploads' SHA-256 (`c7b895d6…`, `9ea762fd…`, `sha256sum` on the device); "Add photos" → the photo picker → one photo →
"5 items" with its name kept; `run-as me.raddatz.veyq ls -laR cache files` before and after: only `profileInstalled` (and
the pass's own marker). **iOS** (throwaway iPhone 18 Pro, German): the grid, the lightbox paged ("2 von 5" → "1 von 5"),
the video played; the details with camera, place and "Hinzugefügt von"; `a-exif.jpg` saved through the save sheet into "On
My iPhone" at the upload's SHA-256; a Photos pick ("+" → Fotomediathek → one photo → ✓) uploaded ("6 Objekte"), the
container's `tmp/picked/` and `tmp/downloads/` empty after. **macOS** (the ad hoc build, German): the grid; the lightbox;
the video played; the details; both originals saved through the save panel at the uploads' SHA-256 (the copies removed).
**Fixed on the way (macOS, `MemoryScreen.swift`):** a Mac's sheet drew the lightbox's toolbar as a light bottom bar with
one primary button — Save was missing and Close and Details were white on white — and the details sheet collapsed to one
row's height with its Done white on white too. The lightbox now has a bar of its own on the Mac (Close, Details, Save)
and the details sheet a minimum size and its own Done. Seen red on the device before the change (the unchanged build),
green after. **Not done:** seeking near the end on iOS and macOS (the simulator tool's screenshots lag the player's
controls; AVKit's controls on the Mac expose nothing to accessibility) — the seek across frames is Android's above.
**Found, not fixed:** the Apple app's `URLSession` (Ktor's Darwin engine) keeps a `URLCache` on disk —
`Library/Caches/me.raddatz.veyq/Cache.db` and `fsCachedData/` held the `blob-downloads-batch`, `exif` and `members`
responses and two blobs' ciphertext from the object store after the pass. Nothing decrypted, but metadata the session
read sits on disk; a decision for the core's HTTP client (no cache, or an in-memory one), named here.
The box stays open for the Apple seek.

**Retrospective:** "the settle is `GalleryModel`'s" did not survive its first test: an upload outlives the page it was
started from, so the settle needed an owner per tenant (`MediaUploads`), and the gallery only listens. Following the
queue's *state* rather than its settle callbacks made the picked files' lifetime survive a tenant opened again over the same
queue (`handOver`) — callbacks bind to the scope that enqueued. A race guard (a job not yet in the queue's state is not
gone) bit only once the test published a queue state ahead of the enqueue; an unconfined lane cannot carry
`limitedParallelism`. The Android harness over a played `GalleryModel.Door` drew every state offline in minutes; Apple got
none (a Swift `Door` over suspend functions is the cost), so Apple's taps stay with the live pass.

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

**Status: built and unit-proven, every new check bitten (2026-11-13). The live case is green (2026-11-13, below). No app draws the new functions yet (phases 4–6).**

**Built:**
- **core `memories/MemoryRights.kt`** — the web's `rights.ts` ported, fail-closed: `of(access, kind)` over `Access.Member`
  (`readonly`: a pending deletion removes every write and the delete; `memoriesOn`: Memories switched off reads only but
  still trashes; `inIdentityFolder`: no Memory there), `Access.Guest(role)` (READ, READ_WRITE, WRITE_ONLY) and `Access.None`,
  on a MEMORY, a CHAPTER, a FOLDER and a space's top (null): navigate, download, upload, `deleteMedia` NONE/OWN/ANY,
  createMemory, createChapter, and the fields no app draws yet (editMeta, setBanner, mark, filterFacts, participants,
  mediaTrash, buckets). The node-generic rights (share, rename, move, delete, cover) stay core's header's.
- **core `memories/MemoryCreate.kt`** — `offered(parentKind)`: a Memory where its placement allows one (a space's top, a
  folder), a chapter only in a Memory; `check` before anything is sent (placement, an empty name, a sibling the loaded tree
  shows folding to the same name, said `node_name_taken_by` with the sibling's noun: `memory_a_memory`/`memory_a_chapter`);
  `refused` as `MemoryNodeDialog` says it (409 `memory_slug_taken`, 422 `node_illegal_structure` — `NodeCreate.Reason`
  gained `NOT_ALLOWED` for it —, else `node_save_failed`); `create` over a tenant through `NodeCreate` with
  `memories/<slug>`, the new node returned to open. `ShellModel.createMemory(scopeId, parentId, name)` and
  `createMemoryNode`; `GalleryModel.createChapter(name)` only where `creates` (a Memory, the rights' createChapter) says so.
- **core `GalleryModel`** — opened with an `access` (the apps: a member; `ShellModel.galleryModel` passes Memories' switch
  and the identity folder) and a monotonic `now`: `State.rights`; `headerActions` (`upload` where the rights upload, for
  the header's plan beside core's); `creates`; `upload` refused without the right; a reader and a save refused without
  `download`. **Trash with Undo** (D6): `trash(mediaId)` by `deleteMedia` — ANY: out of the rows at once (kept out of a
  racing read until the listing drops it), `deleteMedia`, `State.undo` (`memory_moved_to_trash`) held for
  `ShellModel.TRASH_UNDO_MS` on the model's clock, a trash that did not go dropping its Undo and reading anew (the web's);
  `undo()` restores and reads anew, a refused restore said with its reason (`memory_restore_refused` with
  `memory_refused_gone`/`_scope_deleting`/`_blocked`/`_failed`, the medium by its name or `memory_unnamed`); OWN: asked
  first (`State.purging`, `purgeQuestion`, `cancelAsk`, `purge()`), only a medium `Door.isOwn` names, no Undo, a failure
  said (`memory_delete_own_failed`) with the tile kept. **The live poll** (D1): `live()` / `startLive()` — core's `LivePoll`
  at `ITEMS`, each tick `Door.moved(listed)` (`MemoryMedia.moved`: `listNodeMedia` with the listing's media revision as
  `If-None-Match`, `limit 1`; a `304` reads nothing), the gallery read anew only when it moved; it ends when the page
  closes. `MemoryMedia.list` now keeps the FIRST page's revisions (the web's: read before its rows), not the last's.
  `MemoryMedia.trash`/`restore` map `deleteMedia`/`restoreMedia` to `GalleryModel.Removal`. The door's new members have
  fail-closed defaults (Android's debug `DevMemory` harness needs none of them).
- **core `Lightbox`** — **the slideshow** (D2): `slideshow(on)`, `State.slideshow` PLAYING/PAUSED/ENDED, the model's clock
  stepping every `Lightbox.SLIDESHOW_MS` (5 s, pinned to `MediaLightbox.vue`'s `SLIDESHOW_INTERVAL_MS` by
  `LightboxPinsTest`, which also pins `TRASH_UNDO_MS` to the gallery's `useUndo`), started only where a next medium
  exists, a playing video stopped first, a video's poster held one interval and never played, ending ON the last medium;
  `go` and `play` pause it. `trash()` deletes the current medium as its gallery does.
- **Links** (D10): `AppKinds.header(MEMORY/CHAPTER).shareable` is true. The share sheet on a Memory or a chapter lists
  every role in one list (`ShareLinks.list(writeOnly = null)`), its form offers READ, READ_WRITE (the tenant's contribute)
  and WRITE_ONLY (its drop-box capability) — the last a link on the node itself (`LinkForm.uploadLink`: its name the
  member's optional label; `LinkRow.mint(dropBoxes = false)`: no drop box made) whose guests seal to the node's own drop
  key: `ShareLinks.dropFields` (extracted from `mint`) makes it with the first link and seals it under the node's key bound
  to the node, every later link reuses it. An upload-only row is neither edited nor renewed (core's `ShareLinkChanges`
  refuses both: a new link instead). `ShareWords` for a Memory and a chapter (`share_memory_*`, `share_chapter_*`, the
  web's `share.*`), with the upload-only role's own name and hint.
- **Words** (`words.json`, English and German, the web's): `memory_new`, `memory_add_chapter`, `memory_create_*_title`,
  `memory_a_memory`, `memory_a_chapter`, `memory_slug_taken`, `memory_moved_to_trash`, `memory_restore_refused`,
  `memory_refused_*`, `memory_unnamed`, `memory_delete_own_*`, `share_memory_*`, `share_chapter_*`.

**Deviation:** D4 has `AppKinds.Create` gain `MEMORY` and `CHAPTER`; it did not here. That enum crosses the bridge
(`ShellShown.creates`) and Linux's `draw_new` matches its generated Rust enum exhaustively, so two new entries would turn
the Linux build red until a desktop draws them — and this phase was not to touch `src/mobile/linux`. The rule is the
core's now (`MemoryCreate.offered`, `GalleryModel.creates`, `ShellModel.createMemory`); the two entries join
`AppKinds.Create`, `createsWhere` and `ShellShown.creates` in phase 3 with the bridge, where Linux's match gains its arms in
the same change. An upload-only link on a Memory is neither edited nor renewed in the apps (the web edits its name and note
and renews it): core's `ShareLinkChanges` refuses both for a WRITE_ONLY link, as for a drop box's; switching it off and
minting a new one stays — porting the WRITE_ONLY renew (the drop key's public half sealed anew) is left to phase 3's
`Share.*` if a desktop pass misses it.

**Met:** `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest
:core:compileKotlinLinuxX64 :core:compileKotlinMingwX64 :android:assembleDebug` green from `src/mobile` (core jvm 619 tests,
6 skipped — the live cases, `MemoryActionsLiveTest` among them; macosArm64 435; bridge 73, 1 skipped). New: `MemoryRightsTest`
(6), `MemoryCreateTest` (3), `GalleryActionsTest` (11, virtual time), `DropFieldsTest` (2, over the real suite),
`ShareSheetTest` +2 (the Memory's and the chapter's sheet), `LightboxPinsTest` (2, jvm: the web's files read);
`ShellWordsTest` and `NodeHeaderModelTest` now hold a Memory and a chapter shareable. **Bitten**, by a script (mutation →
`./gradlew :core:jvmTest --tests <its class>` red by the named case → restored with `/bin/cp -f`), 33 mutations, all red: a
contributor granted `editMeta`, an uploader reading media, a contributor deleting another's medium, an unknown role given a
viewer's rights; a chapter offered anywhere, no collision checked, a 409 said as failed; a viewer uploading, an uploader
saving an original, a chapter made in a chapter; a trashed medium left in the rows, the Undo outliving its window, a failed
trash keeping its Undo, a refused restore said without its reason; a guest's delete trashed without asking, another's
medium asked for purge, a declined purge still purging; the poll reading every tick, the poll outliving the page; the
slideshow's interval off the web's, running past the last, a `go` or a `play` not pausing it, starting it leaving a video
playing; a Memory's upload-only making a drop box, not offered, needing a name; an upload-only link renewable, a Memory's
sheet in a folder's words; every link a new drop key, a later link replacing it, the drop key bound to no node; a Memory not
shareable. The full run above ran on the restored tree. Guards (`/usr/bin/python3 scripts/validate_*.py` but guard_gaps)
green before each commit. Not run here (this phase changed no surface they build): `linux/run.py build`,
`windows/run.py build`, the Apple builds — the core's Kotlin/Native targets for Linux and Windows compiled, and no bridge
shape changed (`SecretFreeSchemaTest` unchanged).

**Live (2026-11-13, measured):** from `src/mobile`, `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main
checkout>/.run/.env VEYQ_LIVE_MEMORY_GUESTS=<file> ./gradlew :core:jvmTest --tests
'me.raddatz.veyq.mobile.memories.MemoryActionsLiveTest' --rerun` green (44 s), the web guest driven by a scratch Playwright
script in headless system Chrome (`channel: 'chrome'`, a fresh profile): the link read from the file and opened on Vite's
origin (`:8081`), the name "Live guest" given, `exif-sample.jpg` set on the page's file input; the core read the guest's
photo (tile and picture 757 B) under its `K_up`, and the Memory ("App memory 57307") was trashed and purged by the test's
`finally`. What the run does: a web guest uploads one photo through the address
written to that file (`VEYQ_LIVE_MEMORY_WAIT_S`, default 300): "App memory <n>" made at the default space's top, its name
then taken, a chapter, two fixture photos, one trashed and undone, one trashed and purged, two WRITE_ONLY links on one drop
key, the guest's photo read through `GalleryModel` under its `K_up`, the Memory trashed and purged.

**Retrospective:** the CR's D4 named the enum the wire carries as the place of a core rule; the rule needed no enum, and
the enum's move belongs to the phase that changes the wire. A Door that grows a phase at a time took fail-closed defaults so
a harness written against it (Android's `DevMemory`) needs no change — absent door, nothing offered, as the web's sources.
The drop key was inside `ShareLinks.mint`, reachable only live; extracting `dropFields` let the "first sets, second
reuses, bound to its node" rule be held in `commonTest` over the real suite. `MemoryMedia.list` kept the last page's
revisions — harmless before a poll existed, a missed change once one does.

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

**Status: built, unit-proven and live-proven, every new check bitten (2026-11-13). No desktop draws Memories yet
(phases 4–5): both hosts answer the new host
requests `Failed` (an original then goes up alone) and leave New Memory out of their New.**

**Built:**
- **Wire** (`bridge/.../Wire.kt`): `Shell.CreateMemory`; `Gallery.Open/Close/Reload/Tile/ChapterCover/Trash/Undo/Purge/
  CancelAsk/Seen/CreateChapter`; `Memory.Upload(gallery, paths)`; `Lightbox.Open/Close/Go/Picture/Slideshow/Details/Trash/Stop`;
  `Medium.Reader`, `Medium.Save` (onto the Depot CR's `Saving`/`Watched.Save`/`Save.Cancel`); `Watched.Gallery`,
  `Watched.GalleryLive` (the poll inside the watch's job, as `PotLive`), `Watched.Lightbox`; `Update.Gallery(GalleryShown)`
  with `GalleryRow`s (blob ids to key a decoded cache by, never bytes), `Update.Lightbox(LightboxShown)` with
  `SlideshowState`; answers `GalleryOpened`, `LightboxOpened`, `Created(node, notice)`, `MediumDetails(DetailRow…)`;
  `Queued` gained `notice` (what was left out); `Why` gained `NO_SUCH_GALLERY`, `NO_SUCH_LIGHTBOX`, `NOT_OFFERED`;
  `Header.Facts` gained `gallery` (the page's own "Add photos" joins the header's plan, as a file page's Replace).
  `HostRequest.MediaDuration(path)` and `HostRequest.MediaTiers(path, mediaType, previewEdge, previewQuality,
  thumbnailEdge, thumbnailQuality, posterAtMs)` → `HostReply.Tiers(width, height, previewSize)` with `preview ‖ thumbnail`
  as the reply's payload.
- **Dispatch** (`Bridge.kt`): one line per call onto `GalleryModel`, `Lightbox`, `MediaUploads`, `ShellModel.createMemory`;
  a `Tenant` gained `mediaUploads`, `gallery`, `createMemory`; the bridge holds one `MediaUploads` per open tenant over the
  app's one queue (the tray stays `DepotUploads`'), the galleries, their lightboxes and each lightbox reader's owner. A
  reader a lightbox opened is refused (and dropped) once the lightbox's model no longer holds it — paged away, stopped,
  closed, its gallery or tenant closed. `Memory.Upload` wraps each opened path as a `PathSource` (the path for the host,
  the reads at offsets through `FileSource`).
- **Host** (`Host.kt`): `HostTiers`, core's `MediaUpload.Deriver` over the host — `derive` returns at once, the asks run on
  a scope of their own; a video's poster time is `MediaUpload.posterAtMs` of the length the host tells, so the rule stays
  the core's; a reply that is no set of both tiers, or a source with no path, goes up alone. `NoTiers` for a composition
  without a host. `Exports.kt` hands the library `HostTiers(h)`.
- **Core:** `AppKinds.Create` gained `MEMORY` (`memory_new`) and `CHAPTER` (`memory_add_chapter`); `createsWhere` (a
  desktop's one New) appends `MEMORY` at every level, a chapter stays a Memory's page's own (`GalleryModel.creates`).
  `GalleryModel` gained `label(id)` (its upload's words), `deletes(mediaId)` (a row's delete offered: any for a member, a
  guest's own) and `chapterCover(nodeId)` (only a listed chapter's, `Door.cover` → `NodeCover.openNode` in
  `ShellModel.galleryModel`); `MediaUploads.notTaken(count)` with the new word `memory_not_media` (English, German).
- **Apps:** Android's Home New `when` gained the two arms (not in its lists: phase 6); Linux's `draw_new` skips them
  (`continue`), Windows filters them out of its New — the desktops' Memories are phases 4–5.

**Deviation:** D9's `Gallery.Open(scopeId, nodeId)` takes the `NodeAt` (kind and name are the model's), as `File.Open`;
`Gallery.CreateChapter`/`Shell.CreateMemory` answer `Created(node, notice)` rather than `Stored`, since the host opens the
node made; `Memory.Upload` takes no label (the batch is the node's name, `MediaUploads`'). A chapter row carries no
cover presence: `Gallery.ChapterCover` is refused `NO_COVER` where there is none (the presence needs the node's meta read).
D9's DispatchTest cases live in a class of their own, `MemoriesDispatchTest` (DispatchTest is 2 500 lines). "Answered once
queued" holds at Depot's level: the count is what `GalleryModel.upload` queued; the queue's own enqueue is asynchronous
in both.

**Met:** `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest
:android:assembleDebug` green from `src/mobile` (core jvm 620 tests, 6 skipped — the live cases; macosArm64 436; bridge 76,
2 skipped — `LiveBridgeTest`'s Memory case among them). `linux/run.py build` green (clippy `-D warnings`, `cargo test` 43
passed — the kit drawing's hang did not show; Linux's `header.rs` and `uploads.rs` take `Header.Facts`' `gallery: None` and
`Queued`'s `notice`), `linux/run.py bridge` green, `windows/run.py build` green (Veyq.Bridge.Tests and Veyq.Tests passed; the generated C# makes every field a required
member, so `NodeHeader.cs` sets `gallery = null`). Guards (`/usr/bin/python3
scripts/validate_*.py` but guard_gaps) green. New: `MemoriesDispatchTest` (2: every D9 call
and watch over a played Memory and a played host through a real `Host`; the tiers asked off the deriving thread),
`GalleryActionsTest` +1, `ShellWordsTest` +`createsWhere`, `WireTest`'s samples of every new shape (the Rust half reads
them). **Bitten**, by a script (mutation → its class red → restored with `/bin/cp -f`), 17 mutations: a tile read from the
lightbox's tier; a reader kept when paged away; a page kept past its tenant; a path read whole; an upload answered with
what it was handed, not what was queued; the Undo kept past its window; a chapter offered outside a Memory; the live poll
not run; the page's upload missing from the header's plan; the tiers asked on the deriving thread; the payload taken
whole as the preview; the poster's time not the core's; a key-named field on a gallery's row (`SecretFreeSchemaTest`,
unchanged, covers the new shapes by derivation); New Memory not among a level's creates; a guest deleting another's
medium; a cover read for a node that is no chapter; nothing said of files not taken.
After that merge (its one conflict, `Exports.kt`: `Started.start` kept, `HostTiers(h)` added) the proof ran again, all
green: the four Gradle tasks (core jvm 620, macosArm64 436), `linux/run.py build` (43 passed), `windows/run.py build` (UAC
now on the VM: the build ran unchanged, Veyq.Bridge.Tests and Veyq.Tests passed), the guards (`validate_release_identity`
timed out its `git init` once under the Windows build's load and passed alone).
**Live, met** (after the merge of the bridge's host race fix, `bca4c0f66`: `HostTiers` asks through the host `Started`
publishes before composing — `Exports.kt` hands it the same `h` — and a silent host's `Host.Silent` is caught by its
`runCatching`, so the original goes up alone): `LiveBridgeTest.aMemoryIsMadeFilledByPathPlayedSavedAndEmptiedThroughCalls`
green against the dev backend in 92 s, from `src/mobile`: `/opt/homebrew/bin/ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=30
-t 300 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 16M -minrate 16M -maxrate 16M <scratch>/veyq-600.webm` (599 086 462 B),
then `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main checkout>/.run/.env VEYQ_LIVE_MEMORY_WEBM=<that file>
./gradlew :bridge:jvmTest --tests '*LiveBridgeTest.aMemoryIsMade*' --rerun`: `App memory 68547` made, its name then
refused as taken, three photos and the WebM in after 50 s with the played host's tiers, each tile at its thumbnail's
SHA-256, a range across a frame's edge at the file's, the original saved at the file's SHA-256 and a second save cancelled
with nothing left, a photo trashed and undone, a chapter made; trashed and purged (`TRASHED`, `PURGED`); the WebM deleted
after. Reported skipped without `VEYQ_LIVE` and `VEYQ_LIVE_MEMORY_WEBM`.

**Retrospective:** the first played upload job prepared twice — the queue prepares ahead while the run awaits the same
work — so a played job must hold the core job's own once-only lock, or the host is asked twice. A medium leaves the rows a
moment before its Undo is offered (two state updates): a test waiting for "gone and no Undo" must first see the offer, or
it takes the instant between them for the window's end. The poll's 20 s cadence would make a dispatch test slow; a clock
whose first reading is the poll's start and every later one long after makes it tick at once, with no test hook in the
model.

### Phase 4 — Linux: Memories drawn

D8 (Linux) first, then D11's screens over phase 3's calls: New Memory and New chapter, the gallery with its chapters' covers and
the live poll, the hero, the lightbox (zoom, slideshow, video, details, save, trash), adding through the dialog and a drop,
the share sheet's Memory roles, Memories' list with covers.

**Done when:**
- D8's Linux measurements recorded in the phase's retrospective; `cargo test` holds the tiers' host (an image turned by its
  EXIF, a video's poster at `POSTER_AT`, an undecodable file answering no tiers) and the drop's sort, each bitten.
- The gallery is the justified grid (D14): `GalleryModel.layout`'s rows drawn as they come, at the window's width, only the
  rows near the viewport built (`Layout.window` or the list's own recycling); a resize lays it out anew; no layout
  arithmetic in Rust beyond placing a frame (the bridge carries a row's aspect or the layout itself — phase 4 picks which,
  bridge-side, and `WireTest` samples it).
- `linux/run.py build` (clippy `-D warnings`, `cargo test`) green; `scripts/validate_mobile_words.py` and
  `validate_mobile_platform_guidelines.py` green. A MEMORY no longer opens "not built".
- Container (Broadway on 8097), en/light and de/dark, in `Linux memory` made from New: a chapter made; photos (one turned
  by EXIF, one with GPS) and the 600 MiB WebM and an MP4 added through the dialog, their tiles drawn from the tiers this
  host made; a photo uploaded from the web appears within one poll; the lightbox paged, zoomed, the slideshow run to its end,
  the video played and seeked, the details, an original saved at its SHA-256, a medium trashed and undone; a READ link and a
  WRITE_ONLY link made, the guest's upload seen; the app's peak memory (`VmHWM`) below 400 MiB through the WebM's play and
  upload; no plaintext written (`find / -xdev -newer <marker>` in the writable paths). In the Fedora VM's session: a drop of
  three photos from Files onto the gallery and the video's sound.

**Status: built, unit-proven and passed in the container (en/light, de/dark) and the Fedora VM's session, every new check
bitten (2026-11-14).** Memories' list draws no cover thumbnails yet (below); the GPX/music rule of the 2026-11-14 decision is
in the core for every app.

**Built:**
- **Bridge** — `Gallery.Layout(gallery, width, top, viewHeight)` → `Answer.GridLayout(rows, height, count)`: the core lays the
  gallery's rows out (`GalleryModel.layout`, D14) at the host's width and hands only the rows of the view's window
  (`MediaGridLayout.Layout.window`, ADR 0105), each `GridRow(index, top, height, tiles: GridTile(mediaId, left, width))`; the
  host places frames and lays out nothing (`WireTest` samples both shapes, `MemoriesDispatchTest` holds the order, the
  justified width, the widow's height, the gap, another width and the window).
- **Core** — `memories/GalleryMedium`: a GPX track (`application/gpx+xml`, or a `.gpx` name under a generic XML or byte type)
  and a music track (`audio/*`) are no photo view's media, the web's `isGalleryMedium` (ADR 0135 decision 2, ADR 0130);
  `GalleryModel`'s rows — so the grid, the count and the lightbox on every app — leave them out, an unclaimed type stays a row.
  A picked or dropped audio file is no medium to upload (`MediaUpload.isMedia`, held).
- **Linux** (`src/mobile/linux/src`): `memory_page` (a Memory's or a chapter's page: the head's cover, the chapters as a
  boxed list with their covers through `Gallery.ChapterCover`, the justified grid as a `gtk::Fixed` of row widgets placed at
  the core's frames, only the window's rows mounted, laid out anew on a width change (a frame tick) and on the scroll, tiles
  decoded by GTK and held at 400 px, at most 400 of them, least recently shown out; "Add photos" — the header's own `upload`
  — through GTK's file dialog filtered to images and videos; a drop of files onto the page as one `Memory.Upload`, a folder
  said with `memory_drop_folder` and not taken; the trash's Undo toast held while the state offers it; the live poll's watch
  while the page stands; Refresh reads it anew); `lightbox` (an `AdwNavigationPage` pushed over the content, over black:
  Previous/Next, ←/→ and a swipe, zoom by Ctrl+wheel, pinch and `+`/`-`/`0`, the core's slideshow, Details in an
  `AdwDialog`, Save through GTK's save dialog with the file page's progress toast, Move to trash; a video's poster with Play,
  played through `player`); `player` (the file page's `gtk::Video` over the core's reader, made shared); `media_tiers` (the
  host's `Media.Tiers`/`Media.Duration`, see the measurements); New Memory in the shell's New (`Shell.CreateMemory`, the
  made Memory opens), Add chapter as the page's own creating action (`Gallery.CreateChapter`); `header::DRAWN` gains
  `upload`. Words (English, German): `memory_slideshow_start`/`_pause`, `memory_previous`, `memory_next`, `memory_trash`,
  `memory_drop_folder`. `run.py build` runs `SANDBOX_SKIPS` in the Fedora container after the SDK's pass.

**D8's Linux measurements (2026-11-13, the Fedora 44 container and the GNOME 51 SDK's build sandbox):**
- An 80×40 JPEG with EXIF orientation 6: `gdk::Texture` (GTK's own JPEG loader, in both) keeps it 80×40 — **GTK does not
  turn by EXIF**; gdk-pixbuf (through glycin) reads orientation 6 and `apply_embedded_orientation` gives 40×80. The tiers
  therefore decode through gdk-pixbuf.
- **HEIC does not decode in the Fedora container**: glycin-heif is there, libheif has no HEVC decoder ("a suitable decoder
  plugin is libde265"); such a photo goes up alone with its icon (the phones' rule). In the GNOME 51 runtime not measured
  (the build sandbox's glycin cannot start a loader at all).
- **gdk-pixbuf's JPEG saver goes through glycin in the GNOME 51 runtime too**: a save from raw pixels in the build sandbox
  failed "Loader process exited early" (no portal there); in the Fedora container it saves. The installed Flatpak has the
  portal glycin needs (the avatars' WebP, `SANDBOX_SKIPS`' first entry); the tiers' tests run in the Fedora container.
- The GStreamer poster: the SDK carries `playbin`, `appsink`, `videoflip`, `vp9dec`, `qtdemux` and `avdec_h264` (no
  `openh264dec`); the container `openh264dec`. A WebM's and an MP4's poster made in the pass (1920×1080, 1280×720), the
  test's VP8 clip's frame at 1.5 s blue and at 0.1 s red.

**Met:** `./gradlew --continue :core:jvmTest :bridge:jvmTest` from `src/mobile`: bridge 78 (2 skipped, the live cases) green; core
662 (10 skipped) with one failure, `DeviceLinkTest.a_double_tap_or_a_race_of_confirmations_delegates_once`, the one phase 6
already found red alone in `session/DeviceLink`, which this phase does not touch — left to its owner.
`python3 src/mobile/linux/run.py build` green (clippy `-D warnings`; the SDK's `cargo test` 46 passed with 5 skipped, the
Fedora container's 5 skipped ones passed). Guards (`/usr/bin/python3 scripts/validate_*.py` but guard_gaps) green.
**Bitten** (mutation → its test red → restored with `/bin/cp -f`), 17 of 17 red: the layout's window not applied, its width
ignored (`MemoriesDispatchTest`); a photo not turned by its EXIF, a poster not sought, the tiers packed thumbnail first, an
undecodable file answered with a size, a photo enlarged (`media_tiers`); a dropped folder sent, a dropped folder not said,
the held tiles unbounded, a shown tile not made the most recent (`memory_page`); `upload` not drawn (`header`); the gallery
without the GPX/music filter, a `.gpx` name under a generic type kept, an audio type not normalised, audio taken as a medium
(core).
**Container pass** (`VEYQ_LIVE=http://veyq.localhost:8080 run.py run --port 8097 --devlogin --no-secret-service`, driven from
headless Chrome, the app's stderr read first): "App memory 41207" made from New (opened, "0 items"); a chapter "Day one"
made with Add chapter (opened, back shows it in the Memory's chapters); through the dialog the web's GPS/EXIF fixture, a
1600×900 JPEG stored with orientation 6 (tiers 900×1600, drawn upright in the grid and the lightbox), a 20 s H.264/AAC MP4
(poster 1280×720) and a 602 163 153 B VP9/Opus WebM (poster 1920×1080, the upload done) — the grid justified (a widow row at
200), each tile from this host's thumbnail; the lightbox "3 of 4" paged, zoomed (1.25, 1.56, 1.95 by `+` and Ctrl+wheel,
`0` back), the slideshow run from the third to the last ("ENDED" on 4 of 4), the details (camera, lens, focal length,
aperture, "Added by"), the WebM played through the core's reader and sought to 269 s of 300 s (the seek took about a
minute to land — 16 Mb/s read through the reader a range at a time), the GPS photo's original saved through GTK's save
dialog at its SHA-256 (`c7b895d6…`, equal to the file uploaded), a medium trashed ("Moved to trash" with Undo) and undone
(back to its row); a READ link and a WRITE_ONLY link made in the share sheet (their addresses read back by
`LiveBridgeTest.theLinksOfAFolder*`), a guest's photo added through the WRITE_ONLY link on the web's guest page (headless
Chrome, Vite's origin) shown in the gallery by the live poll within 6 s, its row justified across the width. The app's
`VmHWM` 406 208 kB (397 MiB) after the WebM's upload, play and seek — under 400 MiB, narrowly. Files written in the container
after a marker (`find / -xdev -newer`, less `/proc`, `/sys`, `/dev` and the picked files): the saved original, GTK's
`settings/keyfile`, GStreamer's registry, the device store and GTK's `recently-used.xbel` — nothing decrypted. German/dark:
the words ("4 Objekte", "Kapitel", the lightbox's), ←/→ paging, Escape back to the gallery, the window made narrower laid
the grid out anew (744 px: three tiles and a widow). **Fixed on the way:** ← and → did not page (the picture's scroll took
them first: the lightbox's shortcuts run in the capture phase now); a narrower window did not shrink the page (the grid's
rows handed their width on as the page's minimum through `hscrollbar Never`: `External` now) — each seen red in the pass,
green after. **Fedora VM** (GNOME 51 session, the debug build beside its catalogs, a forwarder of mine on 192.168.64.1 and
the VM's own, both stopped by PID after): three photos dragged from Files onto the gallery went up as one batch
(`memory upload: 3 files`, "7 items", tiles from this host's tiers); the MP4 played in the lightbox and its sound read from
the default sink's monitor: −21.1 dBFS RMS, ~436 Hz (the clip's 440 Hz sine), the stream `veyq`. The Memory was trashed and
purged after (`DevMemoryTest`, `VEYQ_DEV_MEMORY_PURGE`).

**Deviation:** D10's "each Memory row in Memories' list its cover thumbnail" is not built: the bridge has no row-cover read
(the phones read `NodeCover.openFramed` in their app models), and adding one is a wire change Windows draws in phase 5 —
left to phase 5 with the bridge call, both desktops at once; a chapter row draws its cover. D11's `gtk::ListView` of rows
became a `gtk::Fixed` of the window's rows the core names (D14's own alternative, `Layout.window`): a `ListView` would have
had to recycle the page's head (the hero, the chapters) as an item. The guest's own purge (`purging`) is drawn but not
driven: the apps open a gallery as a member only (D3).

**Retrospective:** GTK's own loader not turning a JPEG by its EXIF was the phase's first surprise — the cover dialog had it
too and nobody saw it; and in the GNOME 51 runtime gdk-pixbuf saves through glycin as well, so "the cover's `savev` was
passed" held only for the container. A test that needs glycin belongs where a pass runs the app, which `run.py build` now
does for `SANDBOX_SKIPS`. Two pass bugs (keys taken by a scroll, a laid-out width becoming a minimum) were visible only
with a real window — the unit tests could not see either. The crash of the Mac mid-phase left a bite's mutation in
`Bridge.kt` twice: a bite script's backup now lives beside the build, and the tree is diffed before anything is committed.

### Phase 5 — Windows: Memories drawn

D8 (Windows) first, then the same screens as phase 4 in Fluent.

**Done when:**
- D8's Windows measurements recorded; Veyq.Tests holds the tiers' host and the drop's sort, each bitten.
- The gallery is the justified grid (D14), as phase 4's: the core's layout drawn, virtualized, laid out anew on a resize,
  no layout arithmetic in C# beyond placing a frame.
- `windows/run.py build` (Veyq.Tests, the NuGet deprecation gate) green; both guards green. A MEMORY no longer opens
  "not built".
- The VM, en/light and de/dark, in `Windows memory`: phase 4's pass through `Veyq.Drive` (its pickers driven as the Depot
  pass drove them); peak working set's growth over idle not scaling with the file (the Depot CR's bound); `%TEMP%` listed
  before and after showing nothing decrypted; the drop held by its Veyq.Tests case (native-apps' UAC owner step stands).

### Phase 6 — The phones draw the new functions

**Landed ahead of the rest (2026-11-13): D14 on both phones and D15 on the Mac** — the justified grid drawn on Android
and Apple from core's `GalleryModel.layout`, and the Mac's lightbox inline with "Open in New Window". Built and proven
below; the rest of the phase is as it stands.

**Built (D14, D15):**
- **core** `memories/MediaGridLayout.kt` (the web's layout, `targetHeight`, `Layout.rows`/`window`/`nearestFirst`);
  `GalleryModel.Row.aspect`, `GalleryModel.layout(rows, width)`; `GalleryModel.lightboxOn(mediaId)` (a lightbox on a
  medium, before the first read too); `Lightbox.node`; `ShellModel.galleryAt(scopeId, nodeId)` (a listed Memory or chapter
  by its ids, nothing else).
- **Android** — `MemoryScreen` draws the layout's rows as items of a `LazyColumn` (`CreateAction`, whose list state
  collapses the button; `CreateGridAction` had no caller left and went), each row a `Box` of tiles at their frames, 8 dp
  margins (the grid's gap); `DevMemory` plays a portrait and a square photo besides the landscape ones.
- **Apple** — `MemoryScreen` draws the rows in a `LazyVStack` at the width `onGeometryChange` measures. **macOS:** a tile
  opens the lightbox as the page's `navigationDestination` (back and Escape return), its own bar's (after the merge of `07e492ebe`: Close, Details, Save in a bar of the lightbox's own on a Mac) and the File menu's
  "Open in New Window" (`LightboxCommands`, a focused-scene value), a tile's context menu and a ⌘-click open
  `LightboxWindow` through `openWindow(value: LightboxWindowValue)` (ids only; `WindowGroup(id: "lightbox", for:)` with
  `.restorationBehavior(.disabled)`); `AppModel.windowLightbox` opens the window's own gallery and lightbox,
  `closeWindowGallery` closes them with the window, and the tenant's close closes every window's gallery (the window then
  dismisses itself). The word `memory_open_in_window` (English, German). iOS unchanged (full screen).

**Met (2026-11-13):** `MediaGridLayoutTest` (9, commonTest; jvm and macosArm64) holds the web's cases: the bounded aspects,
portrait and landscape shapes, a full row justified to the width, the widow at the target height, no width no tiles, the
2,000-square geometry (400 rows of 193.6, the whole height, about three views of rows mounted at the top and at 40 000,
nearest the centre first, nothing past the end), a phone's 2/3/4 tiles a row, the gallery's rows by their sealed
dimensions, `lightboxOn` before and after the first read, `galleryAt`. **Bitten**, by a script (mutation →
`./gradlew :core:jvmTest --tests '*MediaGridLayoutTest'` red → restored with `/bin/cp -f`), 10 of 10 red: the aspect
unbounded, a full row not justified, the widow stretched, the phone threshold off by one, no gap between rows, the window
mounting every row, nearest not first, a row's aspect unbounded, `lightboxOn` ignoring the medium, `galleryAt` taking any
kind. `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --continue :core:jvmTest :core:macosArm64Test :bridge:jvmTest
:android:assembleDebug` from `src/mobile`: macosArm64 468 passed, bridge 78 (2 skipped), Android assembled; core jvm 657
with one failure, `DeviceLinkTest.a_double_tap_or_a_race_of_confirmations_delegates_once`, red alone as well and in a part
this change does not touch (`session/DeviceLink`, the branch's tip `cb9c63d8f`) — left to its owner. The Apple app built
for macOS and the iPhone 18 Pro simulator (the commands of phase 1). Guards (`/usr/bin/python3 scripts/validate_*.py` but
guard_gaps) green. No bridge shape changed, so the desktops' builds were not rerun. **Android** (throwaway AVD
`veyq_justified_tmp`, Pixel 8, API 36, deleted after) over `DevMemory`, read by `uiautomator dump`: at 411 dp, row one
beach and dunes (4:3 each, 381 px high, 508 px wide, 21 px = 8 dp gaps and margins), row two pier (3:4), shell (1:1) and the
blocked one, justified to 1 058 px; pier's tile opened "3 of 5". **macOS** (the ad-hoc build signed in through
`DevLogin`'s `VEYQ_DEV_*`, driven through the accessibility tree): an `App memory 94531` with nine photos of four shapes
(made, filled and purged by a scratch live case over `LiveRun`) drawn in justified rows (four tiles 130 pt high in a
596 pt grid); a tile opened the lightbox in the window's content ("3 von 9", the window's title), "In neuem Fenster
öffnen" in its toolbar (before that merge) returned the main window to the gallery and opened a second window on "Shape 3.jpg" ("3 von 9 –
App memory 94531") that read its picture; closing that window left the main one; the toolbar's back returned from an
inline lightbox to the gallery. **Not driven here** (the background control cannot send them; owner's step on a Mac in
front): Escape in the inline lightbox, the File menu's item (disabled while the app is not the focused one, as designed),
a tile's context menu and a ⌘-click. The macOS app's sign-in added a device to the dev account, which the pass did not
remove.

Phase 2's functions on Android and Apple (D11's phone line): New Memory and New chapter, chapters with covers, the live poll,
zoom and the slideshow, trash with Undo, a Memory's links in the share sheet.

**Done when:**
- No rule in the apps (the inventory's first locator unchanged from phase 1; `grep -nE 'SLIDESHOW|5000|5_000|TRASH_UNDO|6_000'` over both apps finds none).
- Builds as phase 1. Android (throwaway AVD) and Apple, in `Android memory` / `Apple memory` made from New: a chapter made
  (on Apple driven where the simulator tool types — else by Android and the core's tests, recorded), zoom and the slideshow,
  a web upload appearing within one poll, a medium trashed and undone (Undo on Apple within the tool's lag or recorded as
  the Depot CR recorded it), a WRITE_ONLY link made and a guest's upload seen.

### Phase 7 — The docs

- ADR 0136's Consequences: Memories' screen models (`GalleryModel`, `Lightbox`, `MemoryRights`, the create, the trash), one
  paragraph as Depot's.
- ADR 0137's Consequences: the second reader caller, `HostRequest.MediaTiers`'s payload inward, `Memory.Upload`.
- ADR 0047's Consequences: Memories under the apps' rule; Apple's `tmp/picked/` (D5).
- ADR 0123's Consequences: Memories on the desktops, D8's measurements.
- ADR 0138's Consequences: decision 2's Memory still not built (#893).
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
- The owner (2026-11-13, in chat): a Mac's lightbox opens in the main window, a window of its own only on a click (D15);
  iOS unchanged.
- The coordinator (2026-11-13, under that mandate): the web's justified grid on every app, phones included, the phones at
  the web's narrow target height (D14); the desktops draw it in phases 4 and 5.
- The coordinator (2026-11-14, after `main` moved): `main` made a GPX track (ADR 0130) and a music track (ADR 0135) media of a
  Memory or Chapter, recognised by their sealed `blob-meta` type (`application/gpx+xml`, `audio/*`) and kept out of every
  photo view — the gallery, the lightbox, the cover choice, the space's media list (ADR 0135 decision 2; an unclaimed type
  stays a tile). The apps do the same, in the core (`GalleryModel`/`Lightbox`, held to the web's rule by a case per type),
  from the phase that touches the gallery next (phase 4) for every app; the apps' media picker refuses audio as the web's
  gallery picker does. The Music tab and core's player (#906) and the tracks and their map (#907) on the apps are not this
  CR; the journal on the apps is `main`'s #855.
- 1: as recommended — the hero shows only once a cover exists, on the desktops too.
- 2: as recommended — the D12 cut holds; curation is the next CR (#890).
- 3: as recommended — a folder dropped on a gallery is said not to be taken; no folder upload into a Memory in this CR.

## Retrospective
