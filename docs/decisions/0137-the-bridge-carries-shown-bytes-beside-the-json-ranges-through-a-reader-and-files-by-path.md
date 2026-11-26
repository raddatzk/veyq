# ADR 0137 — The bridge carries shown bytes beside the JSON, a blob's ranges through a reader, and files by path the core opens
- Status: Accepted
- Built: partly (not built: decision 2's opening calls, which are Depot's and Memories'); decision 3's reading of a
  picked file by a receipt's open dialog (`Editor.AddReceipts`, 2026-11-12) and a cover's encoded picture
  (`Header.CoverSave`)
- Date: 2026-11-11
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

[ADR 0161](0161-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md) decision 5 puts the Linux
and the Windows app over one narrow bridge: six C functions (`bridge.h`), a call with its JSON answer, and a watch
whose updates are JSON. Its first slice carried navigation and reads and nothing else. Every capability after it needs
one of three things more:

- **bytes a UI shows** — a member's picture, a cover, a tile, a receipt;
- **a blob read in ranges** — a video a player pulls, a large file shown;
- **a file on the device** — a download written, an upload read.

`bridge.h` already had a byte payload beside the JSON on every callback and on `veyq_call`/`veyq_reply`; nothing used
it. The core already handed bytes out whole (`MemberAvatars.pictureOf`), through a sink (`PotExport.download`) and in
ranges (`RangeReader`), and the Apple app already let the core write a download through the path the system's dialog
handed over (`AppleDepot`, `fopen`). Some 60 calls are still to come, so the dispatch's shape was decided here too.

## Decision

1. **Bytes a UI shows ride beside the JSON, never in it.** A call whose answer is such bytes answers
   `Answer.Bytes(mediaType)`, and the bytes are that callback's payload (`bytes`, `len`).
   - `mediaType` is the core's sniffed type (`blob/Sniff.kt`), null where the core does not know it. It is not named
     `type`, because `type` is the wire's class discriminator and kotlinx.serialization refuses a variant whose field
     shares it.
   - **NULL means no payload; a non-NULL pointer with length 0 is an empty one.** `Exports.kt` hands an empty payload
     as a pointer to a byte never read, so a range past a blob's end (no bytes) is told from an answer that carries
     none without reading its JSON. Every `Answer.Bytes` has a payload, and so has an `Answer.RowCover` that sent its
     picture (a list row's cover, below); no other answer has one.
   - **An update never carries a payload.** State is JSON; a picture is asked for. The state names what there is to
     ask for: `ShellShown.pictures` (and `Update.Pot.pictures`) list the user ids whose picture the core holds,
     `ShellShown.me` the signed-in member's; `Avatar.Picture(userId)` answers the bytes. A changed set is a new update,
     and the host asks again.
   - **Each host reads the payload once, in its generated layer.** `Bindings.kt` writes Rust's `payload(bytes, len) ->
     Option<Vec<u8>>` and C#'s `Native.Payload(byte*, nuint) -> byte[]?`, both copying before the callback returns
     (bridge.h's memory rule). `bridge::call` answers `(Answer, Option<Vec<u8>>)`, `Core.CallAsync` `(Answer, byte[]?)`:
     no screen touches a raw pointer.
   - **Bytes ride inward the same way, beside a call's JSON.** A camera's frame is `Scan.Frame`'s payload on
     `veyq_call` ([ADR 0141](0141-qr-codes-are-read-and-drawn-by-the-core.md) decision 5), never in its JSON; `Exports.kt`
     copies it before `veyq_call` returns (NULL none, length 0 empty, over 64 MiB not read), and `Bridge.call` takes it
     as `inbound`. Only luminance goes in this way, so nothing a code says comes back out.
   - `SecretFreeSchemaTest` refuses a byte array anywhere on the wire — a field, a list's element, a map's value.
2. **A blob read in ranges goes through a reader the bridge holds by id.** A call that opens one answers
   `Answer.Reader(reader, size, mediaType)`; `Reader.Read(reader, position, length)` answers `Answer.Bytes` with that
   range — fewer bytes only at the end, an empty payload past it — through the core's `RangeReader`, whose frame cache
   stays the core's; `Reader.Close(reader)` frees it, and every reader of a tenant closes when the tenant does, as its
   Pots do. `Bridge.openReader` is the door every opening call uses: Depot's `File.Reader` the first, Memories' `Medium.Reader`
   the second, and a reader a page opened closes with that page too.
3. **A file on the device is a path the host names, and the core opens it.** The host's dialog gives a path — GTK's
   `FileDialog` (the xdg portal where one runs; under Flatpak the document portal's path), WinUI's `FileSavePicker` /
   `FileOpenPicker` — and the core opens it with the platform's own call. Nothing of a file's content crosses the
   bridge. `Pot.Download(pot, path, deletedName)` writes a Pot's ZIP so; the name the dialog suggests is the core's
   (`Pot.ArchiveName`).
   - **The writer and the reader are core's, in `nativeMain`:** `blob/FileSink.kt` writes what a producer hands it and
     removes the file when the producer throws or its answer is not kept, so nothing is left at the path on a failure;
     `blob/FileSource.kt` is an `UploadSource` over a path, read at any offset. The Apple app's three copies of the
     writer (`AppleDepot`, `FileDownloads`, `AppleMemories`) and its `FileUploadSource` now stand on them.
   - **The one call that differs is behind `blob/Files.kt`** (`openFile`, `removeFile`, `seekFile`): POSIX `fopen`,
     `remove`, `fseeko` for Apple and Linux (`posixMain`), `_wfopen`, `_wremove`, `_fseeki64` for Windows (`mingwMain`).
     **A path is UTF-8 on the wire and UTF-16 to Windows:** mingw's `fopen` reads its argument in the ANSI code page,
     so a name outside it is written under mojibake (measured 2026-11-11, below).
   - **Where only the host can draw a file, the core asks it, and the drawn pixels ride inward beside the reply.** An
     Emergency Kit picked on a desktop is read by the core by path (`Session.SignInKit`, under the kit's file cap), and
     its read order — the core's — needs a PDF page or a picture as luminance, which only the platform's renderer draws
     (poppler, `Windows.Data.Pdf`). So the core sends host requests naming the path (`HostRequest.PdfPages`, `PdfPage(path,
     page, width)`, `PictureLuma(path, longSide)`), and the host answers `HostReply.Pixels(width, height)` with the
     luminance as `veyq_reply`'s payload, drawn into memory, never a file. Outward cross a path and a page number only;
     no kit byte, token or key. The one rule stays one function on all four apps: the phones draw in process through the
     same `KitPictures` interface the bridge implements over these requests.
   - **A write that can be long is cancellable; the rest run to their end.** A file's Save a copy is
     `File.Save(file, path)` → `Saving(save)`, followed by `Watched.Save` and stopped by `Save.Cancel`, which answers once
     the partial file is gone; a node's ZIP, a receipt and a picture run to their end or fail, and the partial file is
     removed either way.
4. **The dispatch stays hand-written, one line per call; no generator.** `Bridge.dispatch` is a `when` over the sealed
   `Call`, each line onto a core model. Revisited if the dispatch grows beyond one line per call.

## Consequences

- **Measured 2026-11-11** (ADR 0123's Consequences, the bridge, the Linux app and the Windows app, hold each run):
  - `./gradlew :bridge:jvmTest`: `WireTest`'s samples hold every new variant; `SecretFreeSchemaTest` refuses a byte
    array (bitten by an `image: ByteArray` field on `Answer.Bytes`); `DispatchTest` reads a reader of three frames and
    12,345 B backwards in uneven ranges to the same bytes, an empty payload past the end, `NO_SUCH_READER` after a
    close, a switch and a sign-out (bitten by a `closeTenant` that kept the readers); `bridge.h` unchanged against both
    Kotlin/Native headers.
  - With `VEYQ_LIVE`, `LiveBridgeTest` reads the principal's picture through `Avatar.Picture` (2,628 B, `image/webp`),
    its SHA-256 the core's own read's, and downloads Amsterdam through `Pot.Download` (6,089 B, its CSV to the
    manifest's size and SHA-256); a path whose directory is missing answers `Failed` and leaves nothing.
  - `FilesTest` (`nativeTest`; `:core:macosArm64Test`, `linux/run.py test` and `windows/run.py test`, each `--tests
    '*FilesTest*'`) on macOS, Linux and Windows writes uneven frames through `Büro € 中文.bin`, reads them
    back at offsets, and leaves nothing when the producer throws or the answer is not kept; it lists the directory
    through the platform's own calls (`opendir`, `FindFirstFileW`). Its bite on Windows, plain `fopen`, wrote the file
    as `BÃ¼ro â‚¬ ä¸­æ–‡.bin` on the German VM: a round trip through one call would have passed.
  - Both hosts' copy tests (`linux/run.py build`'s `cargo test`, `windows/run.py build`'s `PayloadTest`) hold an empty
    payload apart from NULL (Rust's `Some([])`, C#'s empty array), each bitten.
  - The Pot's ZIP written by the Linux app and by the Windows app each imports into the web (`web:run`, headless
    Chrome: New, Upload files, the archive) as a Pot of Amsterdam's 5 entries, Anna owed 67,34 €, Ben owing it.
- **Decision 1 instead of base64 in the JSON:** bytes in JSON cost a third more and a decode on each side, and a
  generated type with a byte field would let a key's bytes ride the same way unnoticed; the schema test now refuses
  any. A payload beside the JSON was already in `bridge.h`, so no signature changed.
- **Decision 2 instead of whole blobs:** a video or a large file does not fit one answer, and a player pulls what it
  needs; the reader keeps the core's frame cache, so the host never sees a frame boundary.
- **Decision 3 instead of streaming a file across** (host requests and chunked replies): that would be a protocol
  with backpressure, cancellation and partial-file cleanup on both sides, and the platform's file handling written in
  Rust and in C# again. It reads ADR 0161 decision 1's "the platform's side of a file" as **the dialog and the grant are
  the platform's, the bytes the core's** — the reading the Apple app already followed, the path still the system's
  choice. Whether a packaged app's path still opens so (a Flatpak's document-portal path, an MSIX app's picked file)
  is not measured yet; packaging (native-apps § Open) checks it.
- **Decision 4 instead of generating the dispatch** (KSP over the core's models): completeness is already the
  compiler's — a call without a line does not compile —, the wire's samples are already derived (`WireTest`), and a
  generator would add a build dependency and a second language for the wire to save one line per call. The line is
  where the bridge decides nothing: hand-written, it is read in review as such; generated, a rule hiding in a model's
  signature would cross unread.
- **When the pictures are read is the core's** (`session/AvatarsModel.kt`, held as `TenantSession.pictures`; ADR 0161
  decision 1): synced when the tenant opens and again when `peopleRead` gets a different (member, revision) set. Android
  and Apple watch it instead of syncing themselves. **A picture replaced under the same user id sends no signal yet:**
  the id set is unchanged, so no update tells a host to ask again. Nothing reads the people anew today; the first
  caller of `AvatarsModel.peopleRead` owes the hosts that signal (a revision per id).
- **A receipt goes in by path and out as shown bytes** (2026-11-12, Expenses on the desktops; ADR 0123's Consequences,
  the Linux app and the Windows app):
  - `Editor.AddReceipts(editor, paths)` takes every path one open dialog gave (both desktops' dialogs pick several, as
    Android's `OpenMultipleDocuments`), so the 101st is refused by the core's count within one pick, never by a host
    looping. The core reads each at most `Receipts.MAX_BYTES` through `FileSource`; nothing of its content crosses.
    **A desktop's path names no type, so the type is the bytes'** (`Receipts.picked`): a name from the path's last part,
    the type from `mediaTypeOf`, bytes that are no image and no PDF refused whatever the extension claims (a renamed
    text file would pass by extension). `DispatchTest`'s files record the most the core asks to read: the core's own
    refusal of a file over the cap hid a bridge that read the file whole, so the first bite missed.
  - `Receipt.Open(pot, receipt)` answers `Answer.Bytes(mediaType)` with the receipt beside the JSON (decision 1): a
    receipt is at most 10 MiB and decoded whole, so no reader; the reader stays for Depot's and Memories' large blobs.
    `Receipt.Pages(total)` → `ReceiptPages(shown, cut)`: a host knows the page count only once its renderer opened the
    document, and the cap and its words are the core's, never a constant copied into Rust and C#.
  - `Receipt.Save(pot, receipt, path)` writes through `FileSink` (`NO_RECEIPT`, `NOT_WRITTEN`, nothing left at the path).
  - The native read joined each 64 KiB part onto what was read so far (`Exports.kt`): harmless for a cover, 160 copies
    for a 10 MiB receipt. It joins once.
  - Measured: `DispatchTest.aReceiptIsAddedByPathOpenedAndSavedThroughCalls` (11 mutations of `Bridge.kt`, each red);
    with `VEYQ_LIVE`, `LiveBridgeTest.aReceiptIsAttachedShownAndSavedThroughCalls` attached a JPEG and a two-page PDF by
    path, opened each (`image/jpeg`, `application/pdf`) and saved it to a path at its file's SHA-256, and under a held
    one-byte quota saved the entry with the receipt named skipped; `SecretFreeSchemaTest` unchanged.
- **A Depot file is shown as bytes, played through a reader, saved and uploaded by path** (2026-11-12, Depot on the
  desktops; the models ADR 0161's Consequences, the screens ADR 0123's, the Linux app and the Windows app):
  - **The first reader opened:** `File.Reader(file)` → `Answer.Reader(reader, size, mediaType)` over the page's own
    `RangeReader` (one frame cache per page), refused `NO_MEDIUM` where the page shows none. A host holds one frame more as
    its window, so a player's 4 KiB reads (GStreamer's block size) cost one call per 256 KiB; a read blocks only the
    player's own thread, never the UI thread. **A page's readers close with the page in the bridge** (`File.Close`), not
    only by the host: a rule the core can hold is the core's. An image's or a PDF's bytes are `File.Bytes` (decision 1).
  - **The first cancelled write:** `File.Save` / `Watched.Save` / `Save.Cancel` (decision 3), the save a core model
    (`FileSave`) in a scope of its own, so it outlives its page and stops with its tenant.
  - **Uploads by path, at any offset:** `Upload.Files(scopeId, parentId, label, paths)`, `Upload.Folder(scopeId, parentId,
    path)`, `Upload.Replace(file, path)` after `File.ChooseReplace(file)`, each answered `Queued(count)` once queued. The
    core opens each path as a `FileSource` (core's `blob/PickedPaths`: its size and modified time the system's, its type
    claimed by its name through `MediaTypes`, never by the bytes, since a claim is sealed as one) and reads it a frame at a
    time, so a file of any size is never held whole. The level's label is the host's (the core has no localized space
    word to give).
  - **`listDirectory` and `pathFacts` join `blob/Files.kt`:** `opendir`/`readdir` and `lstat` (`posixMain`; Apple's
    `st_mtimespec`, Linux's `st_mtim`), `FindFirstFileW`/`FindNextFileW` and `GetFileAttributesExW` (`mingwMain`, UTF-16
    as `_wfopen`; a reparse point — a symbolic link, a junction — is a link). `PickedPaths.walk` is a desktop's folder
    walk: every entry a `FolderUpload.Found`, a link reported and never followed, an unlistable directory said; the rule
    what is kept is `FolderUpload.keeps`, the phones' too.
  - **Found on the large file, in the core's HTTP:** the bearer client sets its own `HttpTimeout` (connect 30 s, 10 min
    without a byte, no total) — a 2 GiB + 1 `completeFile` outlived OkHttp's default 10 s read timeout, since the server
    checks every chunk first (inbox #903) — and `ApplicationBlobs.FrameSource` signs its presigned GETs again at each lapse
    (400, 401, 403), where a read had signed at most twice and a save longer than the URLs' 600 s failed (Linux, 2.07 GB of
    2.15).
  - Measured: `DispatchTest`'s file, medium and upload cases (`aFileIsOpenedShownSaved…`, `aMediumIsReadThroughItsPagesReader…`,
    `filesAFolderAndAReplaceAreUploadedByPath…`; 14, 4 and 8 mutations, each red); `FilesTest.aPickedDirectoryIsWalked…`
    on macOS, Linux and Windows (`--tests '*FilesTest*'`); with `VEYQ_LIVE`, `LiveBridgeTest`'s `aFileIs…` (a 300 MiB
    file saved at its SHA-256 in 16.6 s, a second save cancelled at 20 MiB with nothing at its path), `aMediumIs…`
    (`VEYQ_LIVE_MEDIUM`: a 636 MB WebM uploaded by path, its ranges across a frame's edge and at the end at the file's
    SHA-256, the reader refused after `File.Close`) and `filesAndAFolder…` (`VEYQ_LIVE_UPLOAD=1`: a folder with a hidden
    file and a non-ASCII subfolder, its link said; a 400 MiB upload cancelled with nothing left; 2 GiB + 1 byte uploaded in
    158 s and saved back in 132 s at its SHA-256); `SecretFreeSchemaTest` unchanged.
- **A Memory's media: the second reader, tiers the host draws, uploads by path** (2026-11-13/15, Memories on the
  desktops; the models ADR 0161's Consequences, the screens ADR 0123's, the Linux app and the Windows app):
  - **The second reader:** `Medium.Reader(lightbox)` → `Answer.Reader` through `Bridge.openReader`, refused `NO_MEDIUM`
    for a picture. A reader a lightbox opened is refused and dropped once the lightbox's model no longer holds it (paged
    away, stopped, closed, its gallery or its tenant closed): the model holds the rule, the bridge follows it.
  - **Drawn pixels ride inward for an upload too:** `HostRequest.MediaTiers(path, mediaType, previewEdge, previewQuality,
    thumbnailEdge, thumbnailQuality, posterAtMs)` → `HostReply.Tiers(width, height, previewSize)` with `preview ‖
    thumbnail` as the reply's payload, as `HostReply.Pixels`; `HostRequest.MediaDuration(path)` tells a video's length,
    so the poster's time stays the core's (`MediaUpload.posterAtMs`). The bridge's `HostTiers` is the core's
    `MediaUpload.Deriver` over the host, asking on a scope of its own — never the queue's deriving thread, never the UI's;
    a reply that is no set of both tiers, a silent host or a source without a path sends the original alone (the phones'
    rule for what they cannot decode). The spec rides in the request, so no host copies a number. Rejected: the host
    deriving every tier before it calls (every preview of a batch held at once).
  - `Memory.Upload(gallery, paths)` → `Queued(count, notice)`: each path a `FileSource` read at offsets (`PathSource`
    keeps the path for the host's tiers); a path no medium by `isMedia` is left out and said (`memory_not_media`).
  - **A gallery's grid crosses laid out:** `Gallery.Layout(gallery, width, top, viewHeight)` → `Answer.GridLayout(rows,
    height, count)`, only the rows of the view's window, each tile's frame; a host places frames and lays out nothing. A
    tile is `Gallery.Tile`, a chapter's cover `Gallery.ChapterCover`, each `Answer.Bytes`; a row names blob ids to key
    a decoded cache by, never bytes.
  - **A list row's cover:** `Shell.RowCover(nodeId, held)` → `Answer.RowCover(frame, crop, sent, mediaType)` (core's
    `NodeCover.row`), the stored picture beside the JSON only where the host does not hold that frame, so a list drawn
    anew on every state opens a cover's blob only once it changed — the second answer with a payload (decision 1).
  - Measured: `MemoriesDispatchTest` (every Memories call and watch over a played Memory and a played host through a
    real `Host`; 17 mutations of the dispatch and 5 of the row cover, each red), `WireTest`'s samples,
    `SecretFreeSchemaTest` unchanged; with `VEYQ_LIVE` and `VEYQ_LIVE_MEMORY_WEBM=<file>`,
    `LiveBridgeTest.aMemoryIsMadeFilledByPathPlayedSavedAndEmptiedThroughCalls` (a 599 MB VP9 WebM and three photos up
    by path with the played host's tiers, each tile at its thumbnail's SHA-256, a range across a frame's edge and the
    saved original at the file's, a second save cancelled with nothing left, a trash undone, a chapter made; purged).
    A played upload job must hold the core job's own once-only lock: the queue prepares ahead while the run awaits the
    same work, so a job without it asked the host twice.
- Decoding a picture is the platform's (ADR 0161 decision 1), and WebP is not every platform's own: GTK 4.22 decodes it
  through glycin's loaders, Windows through the Store's Webp Image Extensions. Packaging must carry both (native-apps
  § Open).

## Alternatives considered

- **Base64 in the JSON**, **whole blobs only**, **streaming a file across**, **a generated dispatch**: each rejected in
  the Consequences above.
- **The host opens the file and hands the core a descriptor.** Closer to a sandbox's grant (a portal's descriptor, a
  `StorageFile` stream), but a third shape beside path and bytes, and nothing measured needs it: a sandboxed app's
  picked file still arrives as a path a plain open reaches — measured 2026-11-13 in the installed Flatpak (the document
  portal's `/run/user/<uid>/doc/<id>/…` path read and written by the core: a receipt, a saved copy, an upload, a drop)
  and in the installed MSIX (a kit picked by path), ADR 0123's Consequences.
