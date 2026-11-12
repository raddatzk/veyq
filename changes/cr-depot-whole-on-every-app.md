---
id: cr-depot-whole-on-every-app
title: Depot whole on Linux and Windows — the file page, uploads and drop boxes moved into the core first, then bridged and drawn
status: in-progress
satisfies: []
adrs: ["0127", "0128", "0123", "0122", "0047"]
invariants:
  - every rule is the core's (ADR 0127 decision 1) — what Android's and Apple's Depot screens still decide (the file page's states, the image edge, the header's file actions and their facts, the upload's settle and replace offer, the folder walk's rule and its preparing, the drop box page, the top-level "Add files only" form, the tray's acknowledge timer) moves into core screen models first, both apps thinned in the same phase and unchanged on a device (decision 2)
  - no plaintext at rest but where the person saves it (ADR 0047's rule, carried to every app) — a file is shown from memory or streamed through the core's `RangeReader`; no app writes a decrypted file to view or play it; the one other place a decrypted copy is written is a phone's "Send a copy", which the person asked for, and it is swept
  - the bridge only carries — each call one line onto a core model, no key, seal or token outward (SecretFreeSchemaTest); a file goes in as a path the host's picker or drop gave and out to a save dialog's path, shown bytes ride beside the JSON, a medium is read through a reader by id (ADR 0128)
  - no word in a desktop but from src/mobile/words/words.json; each desktop follows its platform's guideline (ADR 0123 decision 9)
  - no new dependency that is deprecated, vendor-locked or closed (root CLAUDE.md), each checked and recorded where it is added
  - Android, the Apple app and the web behave as before, except where a rule they disagreed on is made one (D9); `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` and both desktops' `run.py build` stay green in every phase
---

## Goal

The next CR of the owner's native catch-up (owner 2026-11-11: CR 3a → 3b → Depot → Memories → packaging, run
autonomously). CR 3a brought every node's header to the desktops, CR 3b Expenses whole. This CR brings **Depot whole**
to Linux and Windows, at the level Android and Apple have today (native-apps § Built, by area, **Depot**), and closes
what native-apps leaves open for Depot on the way:
- **a file's page:** its facts and uploader; shown in the app when it is an image, a PDF or a text; **played** when it
  is audio or video, streamed, never decrypted whole or to disk; everything else said and offered for saving;
- **a file's actions:** save a copy (the file itself, streamed to the save dialog's path, with progress and cancel),
  replace (or upload into an empty file), and the header's others (CR 3a's);
- **uploads:** files and a folder from the system's open dialog and from New; the upload queue with its progress,
  cancel, retry and the replace offer; **large files** read from the device at any offset, never held whole;
- **drag and drop** on the desktops: files and folders dropped onto a level or a folder's row upload there;
- **drop boxes:** the drop box page (the guests' folders, a folder moved out, a folder deleted asked), "Add files only"
  at a space's top level, a file's and a drop box's links through CR 3a's share sheet.

The order is CR 3b's: the core first (screen models, both phones thinned onto them and passed on a device), then the
bridge and the two desktops slice by slice, then the docs, the harvest and the CR deleted.

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` at `aac00d3e9` (CR 3b harvested and deleted, tree clean). CR 3a's
  header, move sheet and share sheet and CR 3b's open dialog (`Editor.AddReceipts`), PDF renderers and `receipt_viewer`
  are this CR's floor.
- **Read first:**
  - ADR 0127 (decisions 1, 2 and 6), ADR 0128 (all four decisions), ADR 0047 (no plaintext at rest, ranges through
    the holder of the key), ADR 0123 decision 9;
  - `src/mobile/CLAUDE.md` whole: the bridge, the traps (an answer overtaking an update, one `ContentDialog` at a time,
    decrypting off the caller's thread, a payload's NULL vs empty, Broadway's clipboard and hover, the common file dialog
    not pressed through UI Automation, mingw's `fopen`, GTK's dialog state dropped through weak handlers), each
    platform's guideline;
  - `docs/apps/depot.md` (How a file is stored, The viewer, Guests' rights matrix, The drop box, Open questions);
  - the web's Depot as the reference behaviour: `src/web/src/features/depot/` (`view.ts`, `useFileViewer.ts`,
    `FileViewer.vue`, `uploadFile.ts`, `uploadFolder.ts`, `levelRows.ts`, `dropBox.ts`, `uploader.ts`) and core's
    `LevelView.vue` (the drop).
- **Where what runs:** as in CR 3b.
  - The Kotlin runs on this Mac.
  - Linux runs in the container: `linux/run.py run --port 8097 --devlogin --no-secret-service`, driven from headless
    system Chrome when the browser pane is hidden. Broadway carries no drop from the host and no sound: the drop and
    audio are measured in a real session (D7).
  - Windows runs on the ARM64 VM (`windows/run.py run --devlogin`, `run.py drive`), x64 under emulation.
  - Android on a throwaway AVD (never `veyq_pixel`); Apple on a throwaway iPhone 18 Pro simulator (iOS 27) and the
    ad-hoc macOS build.
  - The dev backend (`veyq.localhost:8080`) is never started twice or stopped.
- **The live tests write only into their own folders.** Each live case makes a folder `App depot <n>` at a space's top
  level (`Shell.CreateFolder`), writes there, and trashes and purges it at the end; a seeded node is never written. Each
  desktop pass does the same by hand (`Linux depot`, `Windows depot`). Large and media test files are generated on this
  Mac (`ffmpeg`, present at `/opt/homebrew/bin/ffmpeg`; `dd`), never taken from a person's files.

## Measured at refine (2026-11-12)

From two Explore passes over the core, both phones, the bridge, both desktops and the web, their key numbers checked
again here. Cites are into `src/mobile/`, the core being `core/src/commonMain/kotlin/me/raddatz/veyq/mobile/`.
Commands, from `src/mobile` unless named:
- `find core/src -path '*mobile/depot*' -o -path '*mobile/blob*' | xargs wc -l`; `grep -rnE 'class (Depot|File|Folder|DropBox|Upload)\w*Model' core/src` (no hit)
- `grep -rilE 'depot|upload|FileView|dropbox|viewer|pdf' android/src --include='*.kt' | xargs wc -l`; the same over `apple/Veyq/*.swift`
- `grep -nE 'view-|cacheDir|temporaryDirectory|VideoView|AVPlayer\(url|PDFView|PdfRenderer' android/src/main/kotlin/me/raddatz/veyq/mobile/android/FileScreen.kt apple/Veyq/AppModel.swift apple/Veyq/FileScreen.swift`
- `grep -n 'MediaDataSource\|resourceLoader\|RangeReader' android/src/main/kotlin/me/raddatz/veyq/mobile/android/MemoryScreen.kt apple/Veyq/MemoryScreen.swift`
- `grep -n 'SerialName' bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt | grep -iE 'reader|file|upload|depot|drop'`; `grep -rn openReader bridge/src`
- `sed -n 118,127p bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Bridge.kt`; `sed -n 432,442p core/src/commonMain/kotlin/me/raddatz/veyq/mobile/navigation/ShellModel.kt`
- `grep -rniE 'depot|upload|drop_?box|MediaFile|gtk::Video|DropTarget|gstreamer' linux/src linux/Containerfile`; `grep -rnE 'MediaPlayerElement|AllowDrop|DragOver' windows`
- `grep -n 'Creates' core/src/commonMain/kotlin/me/raddatz/veyq/mobile/AppKinds.kt`
- `/usr/bin/python3 -c "import json; d=json.load(open('words/words.json')); print(len([k for k in d if k.startswith(('depot_','file_','upload_','dropbox_','tab_depot'))]))"`; `grep -rnoE 'words::(depot|file|upload|dropbox)_\w+' linux/src`; `grep -rnoE 'Words\.(Depot|File|Upload|Dropbox)\w+' windows/Veyq`

| # | What | Where |
|---|---|---|
| R1 | **The core holds Depot's rules as functions, with no screen model.** `DepotFiles` (368 lines: `rowDetails`, `facts`, `list`, `read`, `downloadName`, `offerFor`, `collision`, `upload`, `replace`, the queue's jobs), `FileView` (93: `decide` by the sniffed bytes, `open`, `IMAGE_CAP`/`PDF_CAP` 64 MiB, `TEXT_CAP` 1 MiB), `FolderUpload` (142: `plan`, `prepare`), `DropBoxes` (74), `Uploaders` (62), `DepotExport` (105). The one stateful model is `blob/UploadQueue` (411, `state` + `watch` + actions). `ShareSheet` (276) and `LinkForm` already cover a folder's, a file's and a drop box's links. | `depot/`, `blob/`, `navigation/ShareSheet.kt` |
| R2 | **Tests:** `commonTest` has `FileViewTest` (6), `FolderUploadTest` (4), `DepotWordsTest` (4), `RangeReaderTest` (4), `UploadQueueTest` (10); `jvmTest` live only: `DepotLiveTest` (3), `DropBoxLiveTest` (4). **`offerFor` and `collision` have no test.** | `core/src/commonTest/.../depot`, `blob/` |
| R3 | **Still decided in both apps** (a third and fourth copy would follow): | |
|  | — **the file page's state machine:** Android's private `Shown` loaded in a `LaunchedEffect`; Apple's `FileShown` built in `AppModel.fileView` | `android/.../FileScreen.kt:46-77`; `apple/Veyq/FileScreen.swift:6-14`, `AppModel.swift:813-830` |
|  | — **the image's edge:** Android halves to ≤ 4096 px, Apple `CoverImages.image(maxPixels: 4096)` | `FileScreen.kt:176-178`; `AppModel.swift:818` |
|  | — **the file header's contributions:** Replace (its word `DepotFiles.replaceWord`), Send, the file's own Download and its name, and the header's `Facts` — Android `Facts(hasContent)`, Apple `Facts(canWrite: true, hasContent)` | `android/.../KindPages.kt:66-93`; `apple/Veyq/NodeHeader.swift:70, 98-105` |
|  | — **the upload's settle:** lists refreshed, `offerFor`, the replace offer as a snackbar/notice, the item removed | `android/.../AppModel.kt:519-549`; `AppModel.swift:936-960` |
|  | — **the folder upload:** the walk (Android's `DocumentsContract`, Apple's `FileManager.enumerator` with hidden files), `preparing` app state, the root's name (Android an English literal `"Folder"`), its failure word (`share_failed` on Android) | `AppModel.kt:556-617`; `AppModel.swift:965-995` |
|  | — **the tray's acknowledge timer** runs in the view | `android/.../UploadTray.kt:57-63`; `apple/Veyq/UploadsView.swift:22-28` |
|  | — **the drop box page:** its folders and their reload trigger (Android `rowDetails`, Apple `listsRevision` and the move sheet's dismissal), the delete confirm with its own `Facts` | `android/.../DropBoxScreen.kt:58, 115-125`; `apple/Veyq/DropBoxScreen.swift:48-71` |
|  | — **the top-level "Add files only" sheet:** its own form, saving, refusal and fresh address; Android's clock `System.currentTimeMillis()` | `android/.../ShareSheet.kt:208-240`; `apple/Veyq/ShareSheet.swift:171-215` |
|  | — **the app-held `files` map** a file's page and header read | `AppModel.kt:760`; `AppModel.swift:785` |
| R4 | **Both phones write a decrypted file to view or play it.** A PDF and a medium go to `cacheDir/view-<uuid>.<ext>` / `temporaryDirectory/view-<uuid>.<ext>`, removed when the page goes, never swept at start; Android plays it in a `VideoView`, Apple in `AVPlayer(url:)`, Apple's PDF in PDFKit from that URL. Memories on the same phones already streams through the core's `RangeReader` (Android a `MediaDataSource`, Apple an `AVAssetResourceLoader`). Apple's download also passes through `temporaryDirectory/<uuid>/` before `.fileMover`. | `FileScreen.kt:140-192`; `AppModel.swift:825-840`; `FileScreen.swift:55, 68-78`; `MemoryScreen.kt:302, 373`; `MemoryScreen.swift:280-382` |
| R5 | **The apps disagree** (beyond R3's): Send on every Android device, on iOS only on Apple (none on macOS); a drop box folder's delete word (`dropbox_delete_folder` / `peopleDelete`), the tray's close (`upload_close` / `receiptsClose`); a replace's expected blob (read when the picker opens on Android, when it returns on Apple — the web's is "the blob the member saw", `uploadFile.ts:16`). | as R3's files |
| R6 | **The bridge has no Depot call.** 131 calls, none for a file, an upload, a drop box or a reader's opening; the Reader registry and `Reader.Read`/`Reader.Close` are built and only `DispatchTest` opens one (`openReader`, four test lines). `FileSource` is read whole (`DeviceFiles.read`, capped) by `Editor.AddReceipts` and `Header.CoverSave`; `FileSink` writes `Receipt.Save`, `Pot.Download`, `Header.Download`. No upload queue, no write cancelled. | `bridge/.../Wire.kt`, `Bridge.kt:147-161, 188, 418-422, 535` |
| R7 | **A file's header Download on the desktops writes the web's ZIP**, where both phones write the file itself through the kind's own contribution: `Header.Download` → `ShellModel.download` → `NodeExport.walk`/`write` for every kind. | `Bridge.kt:124, 467-471`; `ShellModel.kt:432-442`; `KindPages.kt:78-81` |
| R8 | **Both desktops show Depot's objects as "not built".** Depot is in the drawer and a folder is a shell level; a FILE or a DEPOT_DROPBOX opens a "not built" page under the generic header (with no Replace). New offers a folder and a Pot only: `AppKinds.drawerCreates`; `UPLOAD_FILES`, `UPLOAD_FOLDER` are only in `homeCreates`. | `linux/src/shell.rs:568-570`; `windows/Veyq/ShellView.cs:446, 546`; `AppKinds.kt:145-148` |
| R9 | **Nothing on the desktops plays, takes a drop or speaks Depot's words.** No `gtk::MediaFile`/`gtk::Video`/`DropTarget`, no GStreamer in the Containerfile (poppler-glib is there); no `MediaPlayerElement`, `AllowDrop` or `DragOver`; Windows is unpackaged (`WindowsPackageType None`, self-contained). `Cargo.toml`: gtk4 0.11, libadwaita 0.9, poppler-rs 0.26, async-channel, serde, serde_json. NuGet: Windows App SDK 2.6.5, SDK build tools, MSTest. | `linux/Cargo.toml`, `linux/Containerfile`; `windows/Directory.Packages.props`, `Veyq.csproj:14-16` |
| R10 | **The words are there:** 68 Depot keys (`depot_`, `file_`, `upload_`, `dropbox_`, `tab_depot`), all 68 with German; the desktops use none of them by name. | `words/words.json` |
| R11 | **The web beyond the apps:** multi-select with move/copy/delete and dragging a node onto a folder (inbox #421, both open), a dropped veyq download read as an import, pdf.js's text layer and password prompt, a text's copy and wrap. Neither the web nor the apps have versions or thumbnails (`depot.md` Open questions). | `src/web/src/core/components/LevelView.vue:62-96`; `core/export/importSource.ts:89-108`; `features/depot/FileViewer.vue` |
| R12 | **No published page speaks for the native apps** (CR 3b's R12, unchanged: `git grep -li "linux\|windows" -- site/src/content/docs` finds only `run/operations.md`). | `site/` |

Remembered, not measured at refine (each a phase's first measurement, D5–D7): GTK's GStreamer media backend asserted on
an input stream (`gtk_media_file_set_input_stream`, GNOME/gtk issue #4062, filed against 4.3; whether 4.22 fixed it is
unknown); `MediaPlayerElement` is in the Windows App SDK since 1.5 and plays from a `MediaSource` over an
`IRandomAccessStream`; GTK's `DropTarget` takes a `gdk::FileList`, WinUI's `DataPackageView.GetStorageItemsAsync`
storage items with a `Path`, unpackaged.

## Decisions

### D1 — A file's page is a core screen model, `FileModel`

ADR 0127 decision 2's shape, tested in `commonTest` over a played door:
- **State:** loading; the decided view (`FileView.decide`, unchanged): an image's bytes (whole, ≤ `IMAGE_CAP`), a
  text's decoded first `TEXT_CAP` bytes and whether it was cut, a PDF's bytes (whole, ≤ `PDF_CAP`), a medium's
  `RangeReader` with its size and sniffed type, or the message (too large, empty, unsupported); the facts (size, type,
  modified, the uploader's line through `Uploaders`); failed; the header's file facts (D3); the save in progress (D4).
- **Actions:** `open(node)`, `reload` (the shell's lists changed: the file read anew, a replaced blob shown), `close`.
- **One edge for a shown image:** `FileView.IMAGE_EDGE = 4096`, the longest side a platform decodes to (both phones
  already use it); decoding stays the platform's.
- **One PDF page bound:** `FileView.PDF_PAGES = 1000` (the web's pdf.js cap, `depot.md` § The viewer); a viewer renders
  pages as they come into view and says so past the bound. A receipt keeps `Receipts.PDF_PAGES_SHOWN = 20`.
- The app-held `files` maps (R3) go: the page and the header read the model.

### D2 — Uploads, folders and drop boxes are the core's too

- **`DepotUploads`** (a screen model over `UploadQueue`): `upload(parent, sources)`, `uploadFolder(parent, entries,
  rootName)`, `replace(file, source)` with the expected blob **the one shown when Replace was chosen** (the web's "the
  blob the member saw", R5); the settle (lists read anew, `offerFor`'s replace offer as state with its action, the item
  removed); `preparing` and the folder's outcome notice (`FolderUpload.prepare`'s report, its own words, never
  `share_failed`); the tray's acknowledge timer (`ACKNOWLEDGE_AFTER_MS`, now run by the model on the core's clock, not
  by a view). `offerFor` and `collision` gain their `commonTest` (R2).
- **The folder walk's rule is the core's:** what a walk keeps (regular files; hidden files kept, as the web's directory
  input and Apple keep them; links never followed; an unreadable entry reported, the rest uploaded) is
  `FolderUpload.keeps`; the root's name is the picked directory's last part, never a literal. A phone still enumerates
  through its own door (`DocumentsContract`, security-scoped URLs) and hands entries the core filters; on the desktops the
  core walks the path itself (D6).
- **`DropBoxPage`** (a screen model): the guests' folders (`DropBoxes.folders`), read anew on the shell's revision
  (one trigger for both apps), a folder moved out through `MoveSheet`, a folder's delete asked (`dropbox_delete_folder`,
  `dropbox_delete_folder_confirm`) and done through the header's trash with the core's facts.
- **"Add files only" at a space's top level** joins `ShareSheet` as `ShareSheet.topLevel(scopeId)` (`uploadOnly`, its
  form, saving, refusal and fresh address the sheet's own), its day the core's clock; both apps' own sheets go.

### D3 — The header's file actions are the core's plan

`NodeHeader`'s plan gains a FILE's **Replace** (worded by `replaceWord`: Upload into an empty file), **Send a copy**
(the phones only, where the platform has a share sheet: every Android, iOS — not macOS, not the desktops, D10) and the
kind's own **Download**: a FILE downloads **the file itself** under `DepotFiles.downloadName`, not the web's ZIP. This
fixes R7 for every app at once (`ShellModel.download` asks the kind; a folder, a Pot and a drop box keep the ZIP). The
header's `Facts` for a file and a guest's folder are computed by the core from the rights matrix (`depot.md` § Guests);
both apps stop passing their own (R3, R5).

### D4 — No plaintext at rest but where the person saves it

ADR 0047's rule, now every app's:
- **View:** an image, a text and a PDF are held in memory only (all three capped, D1); a medium is streamed through the
  core's `RangeReader`, only the frames a range needs decrypted, in a bounded cache.
- **The phones:** Android plays a file through the `MediaDataSource` over `RangeReader` Memories uses (`MemoryScreen.kt`'s
  `ReaderSource`, made shared) and renders a PDF from a `memfd` (`android.system.Os.memfd_create`, API 30+, minSdk 31)
  handed to `PdfRenderer`; Apple plays through the `AVAssetResourceLoader` Memories uses and opens a PDF from `Data`
  (`PDFDocument(data:)`). Both `view-*` files go. Apple's macOS download writes to the save panel's URL through the core's
  `FileSink`; iOS keeps `.fileMover` (it has no other way to a place the person picks) with its temporary directory
  swept at every start, as Android's `cacheDir/shared` is. **Send a copy** writes the copy the person sends, swept at start
  on both phones.
- **The desktops:** an image, a text and a PDF arrive as `Answer.Bytes` (ADR 0128 decision 1), a medium through a reader
  (decision 2), a copy is written only to the save dialog's path through `FileSink` (decision 3).
- **Saving a large file is cancellable:** `File.Save(file, path)` answers a save id at once; `Watched.Save(id)` reports
  bytes written and the end; `Save.Cancel(id)` stops it and `FileSink` removes the partial file (native-apps § Open, "Cancelling a
  write", closed by this first consumer). The header's ZIP downloads keep their one-shot calls.
- Rejected: handing a file to the system's viewer or player ("Open with"), which needs a decrypted file another app
  reads; a temp file "deleted after", which a crash leaves.

### D5 — The bridge carries the models, one line each

- **Held by id:** `File.Open(node)` → `Answer.FileOpened(file)`, `Watched.File(file)` → `Update.File(FileShown)`;
  `File.Bytes(file)` → `Answer.Bytes(mediaType)` (an image, a text is in the state, a PDF); `File.Reader(file)` →
  `Answer.Reader(reader, size, mediaType)`, **the first caller of `Bridge.openReader`**; `File.Close`.
- **Saves:** `File.Save(file, path)` → `Answer.Saving(save)`; `Watched.Save(save)`; `Save.Cancel(save)`.
- **Uploads:** `Watched.Uploads` → `Update.Uploads(UploadsShown)` (the queue's batches and items as both phones draw them,
  each status line a `Notice`, the replace offer); `Upload.Files(parent, paths)`, `Upload.Folder(parent, path)`,
  `Upload.Replace(file, path)`, `Upload.Cancel/Retry/CancelAll/Acknowledge/TakeOffer`. A path is opened by the core as an
  `UploadSource` (`FileSource`, read at any offset): nothing of a file crosses, and a file of any size is never held whole.
- **Drop boxes:** `DropBox.Open(node)`, `Watched.DropBox`, `DropBox.MoveOut/AskDelete/Delete/CancelAsk/Close`; the top level's
  share sheet through `Share.OpenTopLevel(scopeId)` onto CR 3a's `Share.*` calls.
- **New:** the shell's state names which creates a level offers (`homeCreates` where the core offers them), so the
  desktops' New gains Upload files and Upload folder where the phones have them.
- **Answers say what the host acts on at once** (CR 3a's trap): `Upload.*` answers with the batch it queued or the
  refusal, never only through a later update.
- **A reader's lifetime is its page's:** a host closes its readers with the page; every reader closes with the tenant
  (ADR 0128 decision 2). A medium's read blocks only the player's own thread on the bridge's answer, never the UI thread
  (GTK's main loop, WinUI's dispatcher), and a bridge answer never needs that thread to arrive.
- `SecretFreeSchemaTest` holds unchanged.

### D6 — The folder walk on the desktops is the core's own

`blob/Files.kt` gains `listDirectory(path)` beside `openFile`/`removeFile`/`seekFile`: POSIX `opendir`/`readdir`/`lstat`
(`posixMain`), `FindFirstFileW`/`FindNextFileW` (`mingwMain`, UTF-16 as `_wfopen`), a link reported as one and never
followed. `FolderUpload` walks with it and applies D2's `keeps`; `FilesTest` (`nativeTest`) gains a nested directory
with a non-ASCII name, a hidden file, a link and an unreadable entry, on macOS, Linux and Windows.

### D7 — Playing a medium on the desktops: the platform's own player over the core's reader

- **Linux, first choice: `gtk::MediaFile::for_input_stream`** over a Rust `gio::InputStream` + `gio::Seekable` subclass
  whose reads are `Reader.Read` ranges, drawn by `gtk::Video` with GTK's own controls; GTK's media backend is GStreamer.
  **Phase 4 measures first** whether GTK 4.22's GStreamer backend plays from a stream (issue #4062, remembered); if it does
  not, **the fallback** is a `gtk::MediaStream` subclass over a gstreamer-rs `playbin3` whose source is an `appsrc` in
  random-access mode fed by `Reader.Read`, its video through `gtk4paintablesink`, still drawn by `gtk::Video`. The
  fallback adds `gstreamer`, `gstreamer-app` and `gst-plugin-gtk4` (the GStreamer project's own Rust bindings and plugin,
  MIT/Apache, on freedesktop.org's GitLab beside GStreamer); each crate's version, licence, last release and that it is
  neither deprecated nor archived are read from crates.io and its repository at that point and recorded. The
  Containerfile gains GStreamer's base and good plugins either way. Rejected: GStreamer's `giostreamsrc` behind a URI we
  build (the same pipeline with less control over seeking), and a file on disk.
- **Windows: `MediaPlayerElement`** (Windows App SDK, no NuGet package added) with
  `MediaSource.CreateFromStream(stream, mediaType)` over the app's own `IRandomAccessStream` whose `ReadAsync` reads
  `Reader.Read` ranges. **Phase 4 measures first** that an unpackaged, self-contained x64 build plays a WebM and an MP4
  through it on the VM; if it does not, Phase 4 stops and records it — `InMemoryRandomAccessStream` (the whole file in
  memory) and a temp file are not fallbacks.
- **Codecs are the system's** (Owner question 2): the app ships none. What the platform cannot decode says
  `file_unsupported` with Save a copy, as Android's `OTHER`.
- Broadway draws video frames into the browser and has no sound: the Linux container proves frames and seeking; audio is
  heard in the Fedora VM's session (D8).

### D8 — Drag and drop on the desktops, proved where a drop can happen

- **Linux:** a `gtk::DropTarget` for `gdk::FileList` on a level's list and on each folder row (the row highlighted while
  over it), `ACTION_COPY`; each dropped `gio::File`'s path goes to `Upload.Files` or, for a directory, `Upload.Folder`.
  A drop with no local path (a portal-less remote URI) is refused with a word. **Windows:** `AllowDrop` on the level's list
  and each folder row, `DragOver` accepting `StorageItems` as Copy, `Drop` reading `GetStorageItemsAsync()` and handing
  each `Path` the same way.
- **Where it is proved:** Broadway takes no drop from the host, so the Linux drop is driven in the Fedora 45 VM's
  Wayland session (`ssh kevin@192.168.64.9`; the container-built arm64 binary run there, its GTK 4.24 and libadwaita
  1.10 newer than the container's), the same session that hears audio (D7) and uses GTK's dialogs through the portal.
  **If the binary does not run there**, that is recorded, the drop is held by `cargo test` (a `gdk::FileList` handed to the
  handler reaches `Upload.*`), and the visible drop stays in native-apps § Open with the desktop-session slice. On Windows,
  `Veyq.Drive` gains a `drag` step (SendInput from an Explorer window onto the app); if UI Automation cannot drive it, the
  drop is held by a Veyq.Tests case and stays open the same way.
- A dropped veyq download is uploaded as the file it is (import is not built on any app, R11).

### D9 — Where the apps disagree, one rule (R3, R5)

- The expected blob of a replace: the one shown when Replace was chosen (the web's).
- A drop box folder's delete says `dropbox_delete_folder`; the tray's close `upload_close`.
- The drop box page reads anew on the shell's revision.
- A folder upload's root is the directory's own name; its failure its own words.
- The header's facts for a file and a guest's folder are the core's (D3).
- Technical unifications, recorded for the owner's veto.

### D10 — The screens, each to its platform's guideline

- **Linux (GNOME HIG):**
  - a file's page shows its content in place: a `gtk::Picture` (image), a read-only monospace `gtk::TextView` in a
    `gtk::ScrolledWindow` (text, the cut said in an `AdwBanner`), the PDF's pages in a `gtk::ScrolledWindow` rendered
    lazily by `receipt_viewer`'s poppler code (made shared), a `gtk::Video` (medium); the facts as an `AdwPreferencesGroup`
    under it; Save a copy and Replace in the header bar's actions (CR 3a's header);
  - a save's progress and cancel in an `AdwToast` with a Cancel button, then "Copy saved";
  - the upload queue a header bar `gtk::MenuButton` (shown while the queue is not empty) opening a
    popover list of batches and items with Cancel, Retry, Cancel all; the replace offer an `AdwToast` with its action;
  - New's Upload files / Upload folder open GTK's `FileDialog` (`open_multiple`, `select_folder`);
  - the drop box page an `AdwPreferencesGroup` of the guests' folders with a menu (Move out, Delete) and the hint.
- **Windows (Fluent):**
  - a file's page in the content frame: `Image`, a read-only `TextBox` (monospace, the cut in an `InfoBar`), the PDF's
    pages in a `ScrollViewer` (`PdfPages`, lazily), a `MediaPlayerElement` with transport controls; the facts beneath;
    Save a copy and Replace in the content `CommandBar`;
  - a save's progress an `InfoBar` with a `ProgressBar` and Cancel;
  - the upload queue an `AppBarButton` with a `Flyout` (items, Cancel, Retry, Cancel all), the offer an `InfoBar` action;
  - New's Upload files / Upload folder through `FileOpenPicker` (multiple) and `FolderPicker`, initialised with the
    window's handle as the save picker is;
  - the drop box page a `ListView` of folders with a `MenuFlyout`; deletes asked by a `ContentDialog` (one at a time).
  - No new control library.
- **Every word** from `words.json`, reusing the phones' keys (R10); a key a desktop control needs and no phone has is
  added in English and German.
- **Send a copy is not offered on the desktops** (Owner question 1): GNOME has no share target, and Windows' share
  needs a decrypted file another app reads. Save a copy is the desktops' way out.

### D11 — `satisfies: []`, as CR 3a and CR 3b

The Depot use cases (`uc.depot.*`) are satisfied by the web and both phones; this CR brings platform parity, whose
record is native-apps' table and § Built, by area.

## Owner steps

None. Throwaway emulators and simulators; the VMs are disposable (owner 2026-11-10).

## Inventory (locators — run at implement time)

- What the phones still decide: `grep -nE 'Shown|FileShown|maxPixels|4096|inSampleSize|view-|temporaryDirectory|cacheDir|VideoView|AVPlayer\(url|PDFView\(|depotSettled|offerFor|_preparing|preparing|"Folder"|ACKNOWLEDGE|acknowledge|dropBoxFolders|TopLevelUploadSheet|uploadOnly|currentTimeMillis|Facts\(' src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/*.kt src/mobile/apple/Veyq/*.swift`
- The core's names a move touches, tests included: `grep -rln 'FileView\|DepotFiles\|FolderUpload\|DropBoxes\|UploadQueue\|ShareSheet\|NodeHeader\b\|ShellModel.download\|downloadName' src/mobile/core/src src/mobile/bridge/src --include=*.kt`
- The bridge's surface: `grep -nE 'Reader|Header.Download|DeviceFiles|openReader|FileSink|FileSource' src/mobile/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/*.kt`
- The desktops' not-built doors and New: `grep -rnE 'not_built|NotBuilt|drawerCreates|creates|DEPOT_DROPBOX|"FILE"' src/mobile/linux/src src/mobile/windows/Veyq`
- Words to reuse: `/usr/bin/python3 -c "import json; d=json.load(open('src/mobile/words/words.json')); print(sorted(k for k in d if k.startswith(('depot_','file_','upload_','dropbox_','tab_depot'))))"`
- The flow surfaces: `grep -n 'Depot\|Drag and drop\|Cancelling a write\|calls that open a reader' docs/crosscutting/native-apps.md docs/inbox.md`

## Phases

### Phase 1 — The file page in the core: `FileModel`, the header's file actions, no plaintext to view

Android and Apple thinned.

**Done when:**
- `commonTest` pins each moved rule, each bitten (mutated → its case red → restored, by a script): the view decided by
  the bytes, not the claim (kept from `FileViewTest`); an image's bytes and `IMAGE_EDGE`; a text cut at `TEXT_CAP` and
  said; a PDF's bytes and `PDF_PAGES`; a medium opened as a reader of the file's size; a too large, empty and unsupported
  file's message; the uploader's line; `reload` showing a replaced blob; the header plan of a FILE (Replace worded by
  `replaceWord`, Send only where the platform says it shares, the file's own Download and name) and its facts from the
  rights matrix for a member, a READ and a READ_WRITE guest; `ShellModel.download` of a FILE writing the file itself (the
  ZIP of a folder unchanged).
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green.
- Gone from the apps (the inventory's first locator finds none of these): `Shown`/`FileShown`, both 4096s, `view-` files,
  `VideoView` over a file, `AVPlayer(url:)` over a file, PDFKit from a URL, the app-held `files` maps, the apps' own
  `Facts` for a file. Android's PDF from a `memfd`, its medium through the shared `ReaderSource`; Apple's through the
  resource loader and `PDFDocument(data:)`; the macOS download through `FileSink`; Send's copies and iOS's download
  temp swept at start.
- `:android:assembleDebug`, `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO` and a signed iOS 27 simulator
  build green; both desktops' `run.py build` green (only the R7 fix reaches them).
- Android (throwaway AVD, deleted after) and Apple (throwaway iPhone 18 Pro and the macOS build), in an `App depot <n>`
  folder: a JPEG, a 1 MiB+ text (cut said), a PDF, an MP4 and a WebM of > 200 MiB (played, seeked near the end), a ZIP
  (unsupported, offered) — each opened, read as text first (`uiautomator dump` / accessibility); `run-as … ls cache` and
  the simulator container's `tmp` list no `view-*` file during and after playback; Download of a file writes the file
  (its SHA-256 the original's); Send a copy on Android and iOS; unchanged from before otherwise.

**Status: done (2026-11-12).**

**Built:**
- core `depot/FileModel.kt` (ADR 0127 decision 2): state (`shown` — `Loading`, `Failed`, `NotUploaded`, `Message(kind)`,
  `Image(bytes, mediaType, edge)`, `Text(text, truncated)`, `Pdf(bytes, pages)`, `Media(reader, mediaType)` —, `facts`,
  `uploader`, `header`), `watch`, `reload` (the file read anew, its content only where the blob changed), `close`,
  `replace(source)` (expected over the blob shown); over a `Door` (file, inIdentityFolder, read, reader, uploader,
  replaceJob). `FileRights`: the opened-file rows of the rights matrix (member, READ, READ_WRITE; replace never in an
  identity folder, copy a member's only, Send a copy only with content and where the platform shares). `Header`: the
  doors' `Facts`, the own actions `replace` and `send` (menu), their words, `downloadName`, `mediaType`.
- `FileView.IMAGE_EDGE` (4096), `PDF_PAGES` (1000) with `pagesShown`/`pagesCut` (`file_pages_cut`, en + de); `FileView.open`
  over a frame reader, an image and a PDF read whole (capped). `DepotFiles.one`, `reader`, `download`, `offerAfter`,
  `take` (the app holds no files map; an offer's file is read when it is made and when it is taken).
- `ShellModel.download` asks the kind (`AppKinds.downloadsItself`/`ownDownload`, `ShellModel.downloadAs`): a FILE writes
  the file itself, any other node its ZIP. `NodeHeaderModel.State.downloadName` (`AppKinds.downloadName`); the bridge's
  `HeaderShown.archiveName` follows it, so the desktops' Download of a file is the file under its own name (R7).
  `ShellModel.fileModel(node, shares)`.
- Android: `FileScreen` and the file's `KindPage` drawn from `FileModel`; a PDF through a `memfd` into `PdfRenderer`; a
  medium through `ReaderPlayer` (Memories' player moved out of `MemoryScreen`, shared); the files map, `openFileView`,
  `readFile`, `downloadFile`, `uploader` gone; Send's copy is the core's download; `view-*` left by earlier builds swept.
- Apple: `FileScreen` drawn from `FileModel` (`AppModel.fileModel`/`fileState`, the state through the watch only); a PDF by
  `PDFDocument(data:)` (`PDFPages(data:maxPages:)`; the URL initialiser gone); a medium through `ReaderPlayback.swift`
  (moved out of `MemoryScreen`), a format iOS does not play said `file_unsupported`; a long text a line a row. The header's
  download door writes to a path: a Mac's `NSSavePanel` path through `FileSink`, iOS `tmp/downloads/<uuid>/` for
  `fileMover`; `tmp/shared`, `tmp/downloads` and earlier builds' `view-*` and download directories swept at start.
  `AppleDepot` keeps `source(…)` and `bytes(shown)`; its `download`, `view`, `read` and `replaceJob` are gone.
- Beside the phase (the coordinator's item, the owner's report): a list keeps its scroll when an object opened from it is
  closed — Android `ListStates`, Apple `KeepsScroll`.

**Met:**
- `FileModelTest` 11/11 and `FileViewTest` green; 24 mutations by `scratchpad/d1/bite.py` (`/bin/cp -f` restore), all 24 bit
  — the first run's "a text cut at TEXT_CAP" missed as an equivalent mutant (the cap is four whole frames, so dropping the
  `copyOf` changed nothing) and was replaced by the frames a text reads, which bit (`bite-run1.log`, `bite.log`).
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green.
- The inventory's first locator finds none of Phase 1's in the file page: no `Shown`/`FileShown`, no `view-` file written,
  no `VideoView`, no `AVPlayer(url:)`, no PDFKit from a URL, no files map, no app-made `Facts` for a file; its remaining hits
  are other areas' (Memories' tile decode at 4096, the drop box folder's `Facts()` — Phase 2 —, a Pot's `Facts`, and
  Android's receipt PDF in `cacheDir`, an Expenses finding, below).
- `:android:assembleDebug`, `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO` and the signed iOS simulator
  build (`-scheme Veyq`, iPhone 18 Pro) green; `linux/run.py build` (clippy, `cargo test` 30 passed) and `windows/run.py
  build` (Veyq.Bridge.Tests and Veyq.Tests passed) green; `validate_mobile_words`, `validate_mobile_platform_guidelines`,
  `validate_changes` green.
- Android (throwaway AVD `veyq_d1_throwaway`, Android 36, deleted after), in `App depot 1`, read through `uiautomator dump`:
  a 3000×2000 JPEG, a 1.38 MB text ("Showing only the first megabyte…"), a three-page PDF (pages drawn from the `memfd`),
  a 248 MB MP4 and a 222 MB WebM (VP9) played and seeked to 01:46 of 01:50 and 01:31 of 01:35, a ZIP ("This file cannot
  be shown here…"); the header's Download of the ZIP wrote `App file 6.zip (1)` at the original's SHA-256 (`f4d32d8e…`);
  Send a copy opened the system's share sheet with the file; `run-as … ls cache` empty during and after every view and
  playback, `cache/shared/<uuid>/App file 6.zip` after Send, empty again after a restart. The header's menu: Share,
  Replace…, Rename, Send a copy…, Move, Copy, Report, Delete file.
- Apple (the booted iPhone 18 Pro, iOS 27, German): the JPEG, the cut text (after the fix below), the PDF, the MP4 shown
  playing, the WebM said "Diese Datei kann hier nicht angezeigt werden…"; the container's `tmp` held no `view-*` file and no
  download directory during and after (only the system's `MediaCache` and `TemporaryItems`).
- The scroll: on the simulator Expenses scrolled to its bottom, "Ui pot" opened, back — still at the bottom
  (`scratchpad/d1/ios-list-kept-after-back.png`); on the emulator Expenses at its bottom, "Ui pot (3)" opened, back — the
  bottom rows still shown.

### Phase 2 — Uploads, folders and drop boxes in the core: `DepotUploads`, `FolderUpload.keeps`, `DropBoxPage`, the top level's sheet, D9

Android and Apple thinned.

**Done when:**
- `commonTest` pins, bitten: `offerFor` and `collision` (R2); the settle's replace offer and its action; a replace's
  expected blob the one shown when chosen (a peer's replace in between answered stale, nothing overwritten); `preparing`
  and the folder's outcome words; `keeps` (hidden kept, a link and an unreadable entry reported, the root's own name);
  the acknowledge timer on virtual time; the drop box page read anew on the revision, a folder's delete only after
  asking; `ShareSheet.topLevel` minting a drop box with its first link on the core's day.
- Gone from the apps: `depotSettled`'s offer logic, `_preparing`/`preparing` app state, the `"Folder"` literal,
  `share_failed` on a folder upload, the views' acknowledge timers, `dropBoxFolders` and its two triggers, both top-level
  sheets' own state, `peopleDelete` and `receiptsClose` in Depot.
- Builds as Phase 1. Android and Apple in an `App depot <n>` folder: files and a nested folder (with a hidden file)
  uploaded, a same-named file offered for replace and replaced, a replace raced from the web answered stale, a cancel
  and a retry, the tray acknowledged; a drop box made at the top level, a guest's upload through the web's guest page,
  its folder moved out and another deleted asked; read back on the web.

**Status: done (2026-11-12).**

**Built:**
- core `depot/DepotUploads.kt` (ADR 0127 decision 2, over the app's `UploadQueue`): `upload(scope, parent, label, sources)`,
  `uploadFolder(scope, parent, shownName, path, found)` (`preparing` while the folders are made, the walk's left-out entries
  and the outcome said in Depot's words), `replace(fileModel, source)`, each Depot item's settle (`Door.changed`, the offer
  read anew — `offerAfter`/`take` moved here from `DepotFiles` —, the item out of the tray), what it says one at a time
  (`State.said`; `take(said)`, `seen(id)`), the tray's acknowledge on the model's clock, and the queue's
  cancel/retry/cancelAll/acknowledge. `ShellModel.depotUploads(queue, changed)`.
- `FolderUpload.Found`/`Kind`, `keeps`, `walked`, `rootName`; `Notices.folderLeftOut`; words `depot_folder_link_skipped`,
  `depot_folder_unreadable`, `dropbox_delete_folder_accept` (en + de).
- `FileModel.chooseReplace()`: the content shown when Replace is chosen is what `replace(source)` expects (D9, the web's).
- `depot/DropBoxPage.kt`: the guests' folders read anew on each revision of the shell's lists (`ShellModel.dropBoxPage`,
  the lists' identity), `askDelete`/`cancelAsk`/`delete(folder)` (the node to trash, only when asked), `at(folder)` for the
  move sheet, `folderFacts` (the core's `Facts` of a guest's folder).
- `ShareSheet.topLevel(scopeId)` (`atTopLevel`: its form open from the start, uploadOnly, today the core's clock's, nothing
  listed, the drop box made at the space's top); `ShellModel.topLevelShareSheet`.
- `DepotFiles.UploadJob`/`ReplaceJob` take their sender (a test plays the server); `collision` over a node list; an
  `UploadJob` gives up the file it made when cancelled (`abandon`), and every give-up runs `NonCancellable`.
- Android: `UploadTray` and the snackbar draw `DepotUploads`; the folder walk hands `Found` entries (no `"Folder"`); the drop
  box page and the top-level sheet are the core's; Replace chosen before the picker; a receipt's PDF through `OpenPdf`'s
  `memfd` (the brief's own fix).
- Apple: the uploads button draws `DepotUploads`; what it says is an alert of its own per said; the walk reports links and
  unreadable entries; the drop box page (`DropBoxBox`) and the top-level sheet are the core's; Replace chosen before the
  importer, the chosen file kept past the importer's dismissal; the move sheet's move-out question cancels only on Cancel.

**Met:**
- `DepotUploadsTest` 9, `DropBoxPageTest` 2, `FolderUploadTest` +2, `ShareSheetTest` +1, `NoticesTest` green; 27 mutations by
  `scratchpad/d2/bite.py` (`/bin/cp -f` restore), all 27 bit (`bite.log`, `bite-abandon.log`).
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :android:assembleDebug` green; `xcodebuild -target Veyq -sdk
  macosx CODE_SIGNING_ALLOWED=NO` and the signed iOS simulator build (`-scheme Veyq`, iPhone 18 Pro) green; `linux/run.py
  build` (clippy, `cargo test` 30 passed) and `windows/run.py build` (Veyq.Bridge.Tests, Veyq.Tests passed) green;
  `validate_mobile_words`, `validate_mobile_platform_guidelines`, `validate_changes` green.
- Gone from the apps (inventory's first locator): `depotSettled`, `_preparing`/`preparing`, `"Folder"`, `share_failed` on a
  folder, both views' acknowledge timers, `dropBoxFolders` and its triggers, both top-level sheets' own state,
  `peopleDelete` and `receiptsClose` in Depot; the remaining hits are other areas' (a Pot's `Facts`, Expenses' people).
- Android (throwaway AVD `veyq_d2_throwaway`, Android 36, deleted after), in `App depot 2`, read through `uiautomator dump`:
  two files uploaded and the tray gone by itself; a same-named `alpha.txt` offered ("Replace file") and replaced (34 B);
  `Trip2` uploaded under its own name with `.hidden` and `sub/c.txt`; a 24 MiB upload cancelled (after the fix: no file
  left), another failed in airplane mode and retried (25.2 MB on the web); Replace chosen, the web replaced the file meanwhile,
  the pick answered "someone else replaced this file" and "Replace anyway" wrote over theirs; a drop box made at the top level
  with its link, two guests dropped through the web's guest page, one folder moved out into `App depot 2`, the other deleted
  after asking (its undo offered); read back on the web. A receipt PDF (two pages) drawn with `run-as … ls cache` empty while
  shown and after.
- Apple (the booted iPhone 18 Pro, iOS 27, German): `Trip3` uploaded under its own name with `.hidden` and `sub/c.txt`, its
  symlink said ("„link-to-hosts“ ist eine Verknüpfung und wurde ausgelassen"); two same-named files offered one after the
  other, one seen, one taken; Replace chosen, the web replaced meanwhile, "Trotzdem ersetzen" offered and taken (11 B on the
  web); two guests' folders listed, one deleted after "Dirk App löschen", one moved out into `App depot 2`; read back on the
  web. Apple's receipt view shows a PDF from `Data` already (`PDFPages(data:)`), so it writes none.

### Phase 3 — Bridge and desktops: a file's page (image, text, PDF, the rest), Save a copy with progress and cancel

**Done when:**
- `DispatchTest` drives `File.Open/Bytes/Close`, `Watched.File`, `File.Save`, `Watched.Save`, `Save.Cancel` over a played
  file, bitten (the bytes not the file's; no media type; a cancelled save leaving a file; a save answering before it
  started; the FILE header's Download a ZIP again → red); `WireTest` samples each new shape; `SecretFreeSchemaTest`
  unchanged and green.
- `LiveBridgeTest.aFileIsShownAndSavedThroughCalls` (`VEYQ_LIVE`): in an `App depot <n>` folder (its files uploaded by
  the core's `DepotFiles.upload` until Phase 5 brings the calls), a JPEG, a text over `TEXT_CAP` and a PDF opened (each
  payload's SHA-256 the original's prefix or whole), a 300 MiB file saved to a path at its SHA-256, a second save
  cancelled midway and nothing left at its path; the folder purged.
- `linux/run.py build` (clippy `-D warnings`, `cargo test`) and `windows/run.py build` (Veyq.Tests) green; both guards
  (`scripts/validate_mobile_words.py`, `validate_mobile_platform_guidelines.py`) green.
- Linux (container, Broadway on 8097) and Windows (VM), each en/light and de/dark, in `Linux depot` / `Windows depot`:
  the JPEG, the cut text (its banner), a 30-page PDF (pages drawn as scrolled), a ZIP (unsupported, Save a copy), an
  empty file; Save a copy of a 300 MiB file with its progress and Cancel (nothing left, measured in the container / on the
  VM), then saved whole at its SHA-256; the header's Download of the file writes the file, not a ZIP.
- The owner's scroll report (Phase 1 fixed it on Android and Apple) met on both desktops: a long list scrolled to its
  bottom, an object near the bottom opened, back — the list still at the bottom; fixed in the phase where it is not.

**Status: done (2026-11-12).**

**Built:**
- core `depot/FileSave.kt`: a copy saved where the person chose, its content a frame at a time into the host's sink, its
  state (`written`/`total`/`fraction`, `end` SAVED, FAILED or CANCELLED, what it says: `download_saving` with both sizes,
  then `download_done`/`download_failed`/`download_cancelled`), `cancel()` returning once the partial file is gone; it runs
  in a scope of its own, so a save outlives its page. `FileModel.save(into)` (null where nothing is uploaded), `bytes`,
  `mediaType`, `State.revision` (turned when other content shows), `FileModel.words`/`cut` (what the page says where it shows
  no content — a medium included, for a platform that plays none — and above a cut text). Words `download_saving`,
  `download_cancelled`, `download_cancel` (en + de). `ShellModel.downloadAs` public.
- bridge: `File.Open` → `FileOpened`, `Watched.File` → `Update.File(FileShown)`, `File.Bytes` (an image's or a PDF's bytes
  beside the answer), `File.Pages` (`FileView.pagesShown`/`pagesCut`, answered as `ReceiptPages`), `File.Close`,
  `File.Save(file, path)` → `Saving(save)` once it runs, `Watched.Save` → `Update.Save(SaveShown)`, `Save.Cancel`; every page
  and save closed with its tenant (a save cancelled, its partial file removed). `Tenant.file` (`shares = false`).
- Linux `file_page.rs`: the page drawn from `FileShown` (a `gtk::Picture` held at the core's edge — scaled down through cairo
  past it —, a monospace `gtk::TextView` under an `AdwBanner`, a PDF's pages as frames rendered by `receipt_viewer::render`
  when within a screen of the view and let go past three, an `AdwStatusPage` with the core's words); its facts one row beneath.
  The header's download of a FILE is drawn (`win.file-save`): GTK's save dialog, then an `AdwToast` with the progress and
  Cancel, then how it ended. `shell::ListScrolls`: each list put back where its place's list stood.
- Windows `FilePage.cs`: the same in Fluent (`Image` decoded at the edge through `BitmapDecoder`'s size first, a virtualized
  `ListView` a line a row under an `InfoBar`, the PDF's pages lazily through `PdfPages`), the save's `InfoBar` with a
  `ProgressBar` and Cancel (`ShellView.Saving`); `HeaderPlan.ByPage` (a Pot's and a file's download are their page's);
  `ListScrolls.cs` (the rule, tested) with `ShellView.Keep`. `Veyq.Drive` gains `scroll <id> [percent]` (UIA's ScrollPattern).

**Met:**
- `DispatchTest.aFileIsOpenedShownSavedWithItsProgressAndCancelledThroughCalls`, `WireTest` (each new shape sampled),
  `SecretFreeSchemaTest` unchanged and green; `FileModelTest` +4 (`aCopyIsSavedFrameByFrame…`, `aCancelledCopyLeavesNothing…`,
  `theBytesAHostShows…`, `whatThePageSays…`). 14 mutations by `scratchpad/d3/bite.py` (`/bin/cp -f` restore), all 14 bit —
  among them the Done-when's five: the bytes not the file's, no media type, a cancelled save leaving its file, a save answered
  before it is held, a FILE's header download a ZIP again (`bite.log`); then core and bridge green again.
- `VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew :bridge:jvmTest --tests '*LiveBridgeTest.aFileIs*' --rerun`: in `App depot
  21970` a JPEG (`84454c0a…`), a 1.25 MB text (its first 1 MiB at the original's prefix SHA-256, the cut said) and a 30-page
  PDF (`07723747…`) shown; a 300 MiB file saved in 16.6 s at its SHA-256 (`e72b2954…`), a second save cancelled at 20 MiB with
  nothing at its path; the folder purged.
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :android:assembleDebug` green; `linux/run.py build` (clippy,
  `cargo test` 32 passed) and `windows/run.py build` (Veyq.Bridge.Tests, Veyq.Tests passed) green; `validate_mobile_words`,
  `validate_mobile_platform_guidelines`, `validate_changes` green. Linux bites (`put_back`, `place_of`, a FILE's download
  action) and Windows bites (`ListScrolls.PutBack`, `Place`, `HeaderPlan.ByPage`), all at once in one build each: every one of
  the six tests red, green after the restore (`lbite.log`, `wbite.log`).
- Linux (container, Broadway on 8097), en/light and de/dark, in `Linux depot` (filled by `VEYQ_LIVE_DEPOT_PASS`, purged after
  with `VEYQ_LIVE_DEPOT_PASS_PURGE=1`): the 3000×2000 JPEG, `long.txt` with "Showing only the first megabyte…" / "Angezeigt
  wird nur das erste Megabyte…", the 30-page PDF (en: stderr pages 1–4 held at the top, 23–30 at the end, the first ones let
  go; de: drawn, not scrolled), the ZIP ("This file cannot be shown here…"), the empty file ("This file is
  empty."); Save a copy of the 300 MiB file to `/tmp`: cancelled at 17 MB (en) and 43 MB (de) with `/tmp` holding nothing
  after, then saved whole at `e72b2954…` (`sha256sum` in the container); the header's download of `archive.zip` wrote the ZIP
  itself (`unzip -l`: `inside.txt`), not veyq's archive.
- Windows (the VM, x64 under Prism), en/light and de/dark, in `Windows depot`: the same files and words; the text a line a row
  (13,108 lines, after the fix below, both themes); the PDF drawn in both, scrolled to its end in en/light (`scroll file-pdf
  100`: pages 26–30 held, the first let go); Save a copy through the save picker into Downloads: cancelled at 26 MB / 35 MB with
  nothing in Downloads after (`Get-ChildItem`), saved whole at `E72B2954…` (`Get-FileHash`) in both runs; `archive.zip`'s
  download held `inside.txt`, 143 B.
- The scroll: Linux, Home of Familie Raddatz scrolled to its bottom, "Ui pot (3)" opened, back — **met: the list stood at its
  top** (`a05`, `a07`: the scroll bar's thumb at the bottom, the list drawn at its top, after a first fix); after the fix,
  "Gescanntes Dokument.pdf" opened from the bottom and back — the bottom rows shown (`c03-back`, stderr `put back at 603`).
  Windows, the same list at its bottom (`x03`), the PDF opened (`x04`), back — the bottom rows shown (`x05`, `put back at 469`).

### Phase 4 — Bridge and desktops: a medium played through a reader

**Done when:**
- **First, each recorded either way (D7):** in the container, whether `gtk::MediaFile::for_input_stream` plays a WebM from
  a seekable Rust stream under GTK 4.22 (else the fallback, its crates checked and recorded); on the VM, whether an
  unpackaged self-contained x64 build plays a WebM and an MP4 from the app's `IRandomAccessStream` in `MediaPlayerElement`
  (else the phase stops and says so).
- `DispatchTest` drives `File.Reader` → `Answer.Reader` → `Reader.Read`/`Close` over a played file (the first
  `openReader` caller), bitten (a range off by one frame; a reader not closed with its tenant → red).
- `LiveBridgeTest.aMediumIsReadInRangesThroughCalls`: a 600 MiB WebM uploaded into an `App depot <n>`, its reader's
  ranges at the start, across a frame's edge and at the end equal to the original's bytes; the folder purged.
- `cargo test` holds the Rust stream (seek, short read at the end, a range across frames over a played bridge); a
  Veyq.Tests case the C# stream the same way. Builds and guards as Phase 3.
- Linux and Windows, en/light and de/dark: the 600 MiB WebM (VP9/Opus) and an MP4 (H.264/AAC) played and seeked to near
  the end, the app's peak memory read (`VmHWM` in the container, `PeakWorkingSet64` on the VM) below 400 MiB, no
  plaintext file written (`find / -newer` in the container's writable paths, the VM's `%TEMP%` listed before and after);
  a codec the system lacks says `file_unsupported` with Save a copy. Linux: frames drawn and the position advancing
  under Broadway; audio heard in Phase 6's VM session.

### Phase 5 — Bridge and desktops: uploads (files, a folder, replace, large files) and the queue

**Done when:**
- `listDirectory` and the desktop walk (D6) in `FilesTest` on macOS, Linux and Windows (bitten: a link followed → red).
- `DispatchTest` drives `Upload.Files/Folder/Replace/Cancel/Retry/CancelAll/Acknowledge/TakeOffer` and
  `Watched.Uploads` over a played queue, bitten (a path read whole instead of at offsets — the test's file records the
  reads; an upload answered before it was queued; the offer not the core's → red).
- `LiveBridgeTest.filesAndAFolderAreUploadedByPathThroughCalls`: in an `App depot <n>`, three files and a nested
  folder with a hidden file and a non-ASCII name uploaded by path and read back at their SHA-256; a same-named file
  offered and replaced; a replace raced by a second session answered stale; one cancelled and retried; a 2 GiB + 1 byte
  file uploaded by path and saved back at its SHA-256; with the one-byte quota held (`VEYQ_LIVE_STORAGE_FIT`, put back
  however the run ends) the folder upload said before anything is sent; the folder purged.
- New offers Upload files and Upload folder on both desktops where the phones do. Builds and guards as Phase 3.
- Linux and Windows, en/light and de/dark: files and a folder picked through the system's dialogs, the queue drawn with
  progress, Cancel, Retry and Cancel all, the replace offer taken; the 2 GiB + 1 byte file uploaded and saved back with
  the app's peak memory below 400 MiB (as Phase 4); read back on the web.

### Phase 6 — Drag and drop on both desktops, and the Linux session

**Done when:**
- `cargo test`: a `gdk::FileList` of two files and a directory handed to the drop handler reaches `Upload.Files` and
  `Upload.Folder` with their paths; a URI without a path refused. Veyq.Tests: the same over the C# handler's paths.
  Each bitten (the directory sent as a file → red).
- **Linux in the Fedora 45 VM's Wayland session (D8), first recorded either way:** the container's arm64 build runs there
  against the dev instance (the Mac's backend reached as from the container). Then: files and a folder dragged from
  Files (Nautilus) onto a level and onto a folder row, uploaded there; the open and save dialogs through the portal; a
  medium's audio heard; en/light and de/dark.
- **Windows:** `Veyq.Drive drag` drops two files and a folder from Explorer onto a level and onto a folder row, uploaded
  there, en/light and de/dark; or, where SendInput's drag does not reach the app, that recorded and the Veyq.Tests case
  standing for it.
- Builds and guards as Phase 3.

### Phase 7 — Bridge and desktops: drop boxes and shares

**Done when:**
- `DispatchTest` drives `DropBox.Open/MoveOut/AskDelete/Delete/CancelAsk/Close`, `Watched.DropBox` and
  `Share.OpenTopLevel`, bitten (a delete without asking; a folder list not read anew on the revision → red).
- `LiveBridgeTest.aDropBoxIsMadeFilledAndEmptiedThroughCalls`: a drop box made at a space's top level with its first
  link, a guest's upload through the core's guest path, the guest's folder listed with its uploader, moved out into an
  `App depot <n>` and another deleted after asking; a file's READ link minted and read back; everything purged.
- Builds and guards as Phase 3. A FILE and a DEPOT_DROPBOX no longer open "not built" on either desktop.
- Linux and Windows, en/light and de/dark: the drop box made from the top level's share (its address shown once), a
  guest's upload through the web's guest page, the page listing it with the guest's name, a folder moved out and another
  deleted asked; a file's link made, edited and deleted through the share sheet. Windows ends with Stop trusting.

### Phase 8 — The docs

- ADR 0127's Consequences: Depot's screen models moved (`FileModel`, `DepotUploads`, `DropBoxPage`, the top level's
  sheet, the header's file actions); its Built line.
- ADR 0128's Consequences: the first reader opened (`File.Reader`), the first cancelled write (`Save.Cancel`), uploads by
  path at any offset, `listDirectory`.
- ADR 0047's Consequences: the rule carried to every app (D4).
- ADR 0123's Consequences: Depot on the desktops; D7's players and what Phase 4 measured; D8's drop.
- `src/mobile/CLAUDE.md`: the models, the bridge calls, the traps this CR met.
- native-apps: the Linux and Windows rows (Depot whole), § Built, by area (Depot, Uploads), § Open (the Windows and Linux
  "other screens" lines, "Drag and drop", "Cancelling a write", "The calls that open a reader" each narrowed to what
  stays: Memories; the Flatpak's carry list gains GStreamer's plugins and, if the fallback was taken, `gst-plugin-gtk4`;
  MSIX's notes gain the media codecs).
- `docs/apps/depot.md` only where a rule moved changes what it says (D9).
- Published pages: none (R12); said so in the harvest.
- The CR's harvest, then the CR is deleted.

**Done when** the trust-chain guards are green.

## Not in this CR

- **Multi-select and dragging a node onto a folder to move it** (inbox #421; the web's, R11).
- **Importing a download** (a dropped or picked veyq ZIP read as an import; native-apps § Open, Depot).
- **A drop onto the phones' Depot** (Android has none; Apple's drop takes images and PDFs into a Pot only).
- **pdf.js's extras:** a PDF's text layer and its password prompt; a text's copy and wrap toggle.
- **A READ_WRITE guest's own name as uploader** (native-apps § Open, Depot).
- **Uploads that go on in the background** on the phones; placeholder tiles (Memories).
- **Send a copy on the desktops** (Owner question 1) and "Open with" another app (D4).
- **Packaging:** the Flatpak's codecs and plugins and MSIX's media extensions are the packaging CR's; this CR names them
  in native-apps § Open.

## Owner questions

1. **Send a copy on the desktops?** GNOME has no share target; Windows' share sheet needs a decrypted temporary file
   another app reads. **Default planned:** not offered; Save a copy (the save dialog) is the desktops' way out, as on macOS
   today.
2. **Media codecs (patents):** the app ships no codec and plays what the system decodes (GStreamer's plugins on Linux,
   Media Foundation and the Store's extensions on Windows); a file nothing decodes says so and offers Save a copy.
   **Default planned:** exactly that; whether the Flatpak pulls the runtime's codec extension is the packaging CR's
   question.
3. **A dropped veyq download** (the web reads it as an import): **default planned:** the apps upload it as the file it is
   until import is built.

D9's unifications and D3's fix (a file's Download is the file, not a ZIP, on every app) are technical and open to the
owner's veto.

## Retrospective

### Phase 1 (2026-11-12)

- **The opened file's rights are a function of their own, `FileRights`**, the matrix's opened-file rows for a member, a
  READ and a READ_WRITE guest, though only members use the apps: the header's `Facts` and own actions come from it, so no
  app builds them. Decided here.
- **A member replaces nothing in a guest's identity folder** (the matrix's "member, guest's identity folder" column), where
  both apps offered Replace on every file. A D9 unification, open to the owner's veto.
- **A file's own header actions stay the kind's** (`FileModel.Header.own`, plan-ordered by `AppKinds.headerOrder`), not
  core ids in `NodeHeader`: Replace and Send are Depot's, as the web's file page contributes them. The download is core's,
  and only its content and name are the kind's (`AppKinds.ownDownload`/`downloadName`).
- **Send a copy and Download are one path:** Send's copy is `ShellModel.download` of the file into the shared directory, so
  no app reads a file's frames itself any more.
- **The replace offer reads its file when made and when taken** (`DepotFiles.offerAfter`, `take`): the apps held a map of
  every file only for that. Phase 2's `DepotUploads` takes these over.
- **A medium iOS does not play says so** (WebM: AVFoundation plays none): the asset's `isPlayable` through the resource
  loader, then `file_unsupported` — D7's rule, met on Apple first. Before, a crossed-out player stood there.
- **A text of a megabyte drew nothing on iOS** in one `Text` (measured: the banner shown, the text blank, unchanged after
  10 s); it is a line a row in a `LazyVStack` now. The defect predates this CR (the same view drew it).
- **Earlier builds' leftovers are swept:** Android `cacheDir/view-*`, Apple `tmp/view-*` and a download's `<uuid>`
  directories — a decrypted copy the old page left at rest (measured: one `view-….pdf` from 2026-11-09 in the simulator's
  container before this build).
- **Deviations:** the Apple pass ran on the booted iPhone 18 Pro the brief named, which stays installed and signed in; the
  macOS build was compiled, not run (no pass on the Mac: its download through the save panel is unproved by hand). On
  Apple no seek near the end (the tool's screenshots lag the player's controls), no Download and no Send a copy (both need a
  system sheet the tool cannot drive reliably); Android's pass and the core's tests hold them. The swiftshader emulator
  decodes 720p at a fraction of real time (75 % frames dropped), so the seeks are proved by the player's position, a frame
  drawn at the start. One MP4 and one WebM of more than 200 MB each, not a pair per format.
- **Beside the phase — the owner's report (the coordinator's item): a list lost its scroll** when an object opened from it
  closed. Cause: the shell shows an object in its list's place and the bar is the object's (ADR 0122), so the list's tab and
  `NavigationStack` (iOS) or composable (Android) leave the tree and come back new. Android keeps each list's
  `LazyListState` in the shell by key; on iOS `ScrollViewReader.scrollTo` and `scrollPosition` ran (logged) and moved
  nothing, so `KeepsScroll` keeps the offset of the list's own `UIScrollView` (found beside its background a moment after
  it joins the window). Android had the same structure; that it reset there is reasoned, not measured before the fix.
- **Found, left for its own CR:** Android's receipt viewer writes a decrypted receipt PDF to `cacheDir`
  (`EntryReceipts.kt`, `createTempFile("receipt", ".pdf")`) — the same rule's breach in Expenses; the `memfd` path here is
  its answer. The Apple app's URL cache (`Library/Caches/<bundle>/fsCachedData`) holds responses, ciphertext only.
- **Left in the dev instance by my runs:** nothing; `App depot 1` with its six files was trashed and deleted forever. The
  throwaway AVD is deleted.

### Phase 2 (2026-11-12)

- **The tray is one model, `DepotUploads`, over the app-wide queue, one per open tenant** (`ShellModel.depotUploads`): it
  mirrors the whole queue (Memories' batches too) and runs the acknowledge for all of it; only Depot's items get its settle.
  Decided here: a model that knew only Depot's jobs would have left the tray's timer in the views for Memories.
- **What the uploads say is a queue of `Said`, one shown at a time**, answered by `take(said)` (the said handed in, never read
  back — a dismissal may run first) or `seen(id)`; the folder's outcome and the replace offer are both one. The apps had a
  snackbar channel (Android) and the shell's notice (Apple); Apple's needed an alert of its own (below).
- **The walk's rule is the core's, the walk the platform's** (D2): a phone hands `Found` entries — file, directory, link,
  unreadable — and `FolderUpload.keeps` decides; a link and an unreadable entry are said in two new words, never silently
  dropped. Android's provider lists hidden files (measured: `.hidden` uploaded from `Trip2`); it reports no links.
- **`rootName` is the picker's shown name, else the path's last part** (`/`, a backslash, `:` part it), so a document id
  like `primary:Download/Trip` names `Trip` and no literal remains.
- **A replace's expectation is fixed by `FileModel.chooseReplace()`**, which the header's action calls before the picker
  opens; a `replace` without a choice is refused (null), so a desktop's `Upload.Replace` (Phase 5) has to choose first. D9.
- **A drop box page reads anew on the shell's lists' revision** (the `Lists` object's identity: a navigation keeps it, a read
  makes a new one), and a guest folder's header uses the core's `DropBoxPage.FOLDER_FACTS` (no write, its download).
- **`ShareSheet.topLevel` is the share sheet in a mode** (`atTopLevel`), not a sheet of its own: its form, save, refusal and
  fresh address are the sheet's; the door makes the drop box under no parent.
- **Found and fixed on the way:** an upload cancelled mid-way left an empty "not uploaded yet" file (the queue's `abandon`
  was never implemented for Depot, and the upload's own give-up ran in the cancelled coroutine) — `UploadJob.abandon` and
  `NonCancellable` give-ups, bitten; on Apple the second offer of a batch never showed, a file picked for Replace was dropped
  (the importer's binding cleared `replacing` before its completion), and a guest's folder was never moved out (the move
  sheet's question cancelled before its accept) — each fixed and passed on the simulator. All predate this phase except the
  second offer, which the old notice path had as well.
- **Left as found:** a failed upload whose give-up cannot reach the server (offline) leaves its empty file, and its retry
  collides with it and offers the replace (measured in airplane mode); whether the web does the same is unchecked — a later
  phase's or an inbox item. A guest's new folder shows on a phone only after its tree is read anew (nothing polls). A drop box made from
  the top level on iOS was not driven (the simulator tool cannot type its name); Android and `ShareSheetTest` hold it.
  Android's typed name lost its last character once (`App drop 2` became `App drop`), an `adb input` artefact.
- **Deviations:** the Apple pass ran on the booted iPhone 18 Pro the brief named (it stays installed and signed in) and no
  cancel/retry was driven there (the simulator tool cannot take the network away); the macOS build was compiled, not run.
  Both passes worked in one folder, `App depot 2`, Apple's beside Android's.
- **Left in the dev instance by my runs:** nothing of this phase: `App depot 2` (with `AppPotD2`, the moved-out guest folders,
  `Trip2`, `Trip3`, the big files) and the drop box `App drop` were trashed and deleted forever on the web; their guests'
  folders went with them. The throwaway AVD is deleted; the simulator's `AppDepot3` test files removed.

### Phase 3 (2026-11-12)

- **"Save a copy" is the header's download of a file on the desktops** (`win.file-save`, `HeaderPlan.ByPage`), as a Pot's
  download is its page's: one action, the file itself, with progress and Cancel. The one-shot `Header.Download` stays and
  writes a FILE's own bytes too (`ShellModel.downloadAs`, now public, is what `DispatchTest` bites through). Decided here.
- **A save is a core model of its own, `FileSave`, in a scope of its own**: it outlives its page (the toast or `InfoBar` stays
  in the shell), stops with the tenant, and `cancel()` returns once the sink removed the partial file — so `Save.Cancel`'s
  answer is the host's proof that nothing is left. Its words (`download_saving` with both sizes, `download_cancelled`,
  `download_cancel`) are new keys, en + de.
- **What the page says where it shows no content is the core's** (`FileModel.words`, `cut`), a medium included: until Phase 4
  plays one, a desktop says `file_unsupported` beside Save a copy (D7's fallback words). `File.Pages` answers with the
  receipt's `ReceiptPages` shape (shown, cut) rather than a twin.
- **A PDF's pages are frames sized from the page, rendered when within a screen of the view and let go past three** (both
  desktops), so a 1,000-page PDF holds a few pages' pixels; the bound itself is the core's.
- **The owner's scroll report, met on Linux before the fix** (the list at its top after back), fixed on both desktops by
  `ListScrolls` (by place: an application's list in a space, Home's level by space, folder and filter). Windows was not
  measured before its fix: the same rebuild-per-draw structure, reasoned.
- **Found and fixed on the way:** GTK took a scroll value set inside the adjustment's `changed` during an allocation into the
  scroll bar but not the list (the list drawn at its top under a thumb at the bottom) — set on the next turn of the loop; one
  WinUI `TextBox` holding a megabyte drew only its first line (measured after 15 s) — a virtualized `ListView` a line a row,
  Apple's answer of Phase 1; GTK's `property` row style put the facts small and the uploader large — plain row.
- **Found, left:** a session's tree is cached and `Shell.Refresh` does not read it anew, so a desktop does not see another
  device's upload until it signs in again (`LiveBridgeTest` uploads before the bridge signs in for that reason) — Phase 5
  (uploads seen by the queue's settle) and Phase 7 (a guest's drop) meet it; `FileModel.reload` is not called by the bridge
  yet (no `File.Reload`), which a replace (Phase 5) needs. The Linux container has no monospace font: the text's
  `monospace` falls back to the proportional one (container only). An image past `IMAGE_EDGE` was not on a device (the pass's
  JPEG is 3000×2000): the scale-down (`file_page::within_edge`, Windows' `DecodePixelWidth`) is unexercised there. Windows'
  file page shows the level's New (as every non-Pot page does).
- **Deviations:** the Windows de/dark PDF was not scrolled (`Veyq.Drive` gained its `scroll` step after that run), nor the
  Linux de/dark one; both scrolled in en/light. The Windows "Not now" pressed in the first run was cleared from the VM's
  `device-store.json` after the pass (a backup beside it, `.bak-d3`), so the next offer shows.
- **Left in the dev instance by my runs:** nothing: `App depot 96533` and `App depot 21970` (the live case's) and `Linux depot`,
  `Windows depot` (the passes') trashed and purged; saved copies removed from the container's `/tmp` and the VM's Downloads.

