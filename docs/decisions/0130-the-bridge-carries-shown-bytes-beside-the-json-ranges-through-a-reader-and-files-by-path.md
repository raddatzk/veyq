# ADR 0130 — The bridge carries shown bytes beside the JSON, a blob's ranges through a reader, and files by path the core opens
- Status: Accepted
- Built: partly (not built: decision 2's opening calls, which are Depot's and Memories'); decision 3's reading of a
  picked file by a receipt's open dialog (`Editor.AddReceipts`, 2026-11-12) and a cover's encoded picture
  (`Header.CoverSave`)
- Date: 2026-11-11
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

[ADR 0129](0129-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md) decision 5 puts the Linux
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
     none without reading its JSON. Every `Answer.Bytes` has a payload; no other answer has one.
   - **An update never carries a payload.** State is JSON; a picture is asked for. The state names what there is to
     ask for: `ShellShown.pictures` (and `Update.Pot.pictures`) list the user ids whose picture the core holds,
     `ShellShown.me` the signed-in member's; `Avatar.Picture(userId)` answers the bytes. A changed set is a new update,
     and the host asks again.
   - **Each host reads the payload once, in its generated layer.** `Bindings.kt` writes Rust's `payload(bytes, len) ->
     Option<Vec<u8>>` and C#'s `Native.Payload(byte*, nuint) -> byte[]?`, both copying before the callback returns
     (bridge.h's memory rule). `bridge::call` answers `(Answer, Option<Vec<u8>>)`, `Core.CallAsync` `(Answer, byte[]?)`:
     no screen touches a raw pointer.
   - `SecretFreeSchemaTest` refuses a byte array anywhere on the wire — a field, a list's element, a map's value.
2. **A blob read in ranges goes through a reader the bridge holds by id.** A call that opens one answers
   `Answer.Reader(reader, size, mediaType)`; `Reader.Read(reader, position, length)` answers `Answer.Bytes` with that
   range — fewer bytes only at the end, an empty payload past it — through the core's `RangeReader`, whose frame cache
   stays the core's; `Reader.Close(reader)` frees it, and every reader of a tenant closes when the tenant does, as its
   Pots do. The registry and the two generic calls are built; `Bridge.openReader` is the door the opening calls (a
   Depot file, a Memory's medium) will use, and `DispatchTest` uses until then.
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
   - **No write is cancelled yet.** A download runs to its end or fails, and the partial file is removed. Cancelling
     comes with the first consumer that needs it (Depot's large files).
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
  Rust and in C# again. It reads ADR 0129 decision 1's "the platform's side of a file" as **the dialog and the grant are
  the platform's, the bytes the core's** — the reading the Apple app already followed, the path still the system's
  choice. Whether a packaged app's path still opens so (a Flatpak's document-portal path, an MSIX app's picked file)
  is not measured yet; packaging (native-apps § Open) checks it.
- **Decision 4 instead of generating the dispatch** (KSP over the core's models): completeness is already the
  compiler's — a call without a line does not compile —, the wire's samples are already derived (`WireTest`), and a
  generator would add a build dependency and a second language for the wire to save one line per call. The line is
  where the bridge decides nothing: hand-written, it is read in review as such; generated, a rule hiding in a model's
  signature would cross unread.
- **When the pictures are read is the core's** (`session/AvatarsModel.kt`, held as `TenantSession.pictures`; ADR 0129
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
- Decoding a picture is the platform's (ADR 0129 decision 1), and WebP is not every platform's own: GTK 4.22 decodes it
  through glycin's loaders, Windows through the Store's Webp Image Extensions. Packaging must carry both (native-apps
  § Open).

## Alternatives considered

- **Base64 in the JSON**, **whole blobs only**, **streaming a file across**, **a generated dispatch**: each rejected in
  the Consequences above.
- **The host opens the file and hands the core a descriptor.** Closer to a sandbox's grant (a portal's descriptor, a
  `StorageFile` stream), but a third shape beside path and bytes, and nothing measured needs it: the apps run
  unpackaged, and a document portal hands a path a plain open reaches (remembered, not measured here).
