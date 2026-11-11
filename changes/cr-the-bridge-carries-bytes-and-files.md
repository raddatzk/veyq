---
id: cr-the-bridge-carries-bytes-and-files
title: The bridge carries what the UI shows as bytes, a blob's ranges, and files by path — proved by avatars and a Pot's download on Linux and Windows
status: in-progress
satisfies: []
adrs: ["0127", "0123", "0122"]
invariants:
  - every rule stays the core's — the bridge only carries; a rule found in a host or in the dispatch moves into the core with the test that pins it (ADR 0127 decision 1)
  - no key, seal or token crosses the bridge, and no bytes travel inside JSON — a payload rides beside it (SecretFreeSchemaTest)
  - the bridge stays one for Linux and Windows — bridge.h's six functions keep their signatures; the Rust and C# types stay generated, never written by hand
  - Android, the Apple app, the Linux app and the Windows app behave as before wherever this CR does not add to them; `./gradlew :core:jvmTest :core:macosArm64Test`, `python3 src/mobile/linux/run.py test` and `python3 src/mobile/windows/run.py test` stay green in every phase
  - a file's content never crosses the bridge — the host names a path, the core opens it (D3)
---

## Goal

The first of the owner's cut for bringing Linux and Windows to the Android and Apple apps (2026-11-11, "passt"):
1. **this CR, the bridge's foundation**;
2. device trust;
3. the node header and Expenses whole;
4. Depot;
5. Memories;
6. packaging (Flatpak, MSIX).

Linux and Windows go together in each, so they never drift apart.

Today the bridge carries navigation and reads as JSON and nothing else. Every capability after this one needs one of
three things the bridge cannot do yet:
- **bytes the UI shows:** an avatar, a cover, a tile, a receipt;
- **a blob read in ranges:** a video the player pulls, a large file shown;
- **a file on the device:** a download written, an upload read.

This CR builds those three channels once, fixed in an ADR. It proves each one end to end by the smallest consumer
that draws something real:
- **the members' pictures** (avatars) on the Pot's people and in the switcher — the bytes channel;
- **a Pot's download as ZIP** through each platform's save dialog — the files channel;
- the **range reader** and the **upload source by path** at the bridge only (`DispatchTest`, `LiveBridgeTest`).
  Their screens are Depot's and Memories' CRs.

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` at `23d8be9a0`, which holds Linux's and Windows' first slice, both
  harvested. Implementation starts from that branch or from whatever carries it then.
- **Read first:**
  - ADR 0127, in full;
  - ADR 0123's Consequences ("the core on Linux", "the core on Windows", "the bridge", "the Linux app", "the Windows
    app");
  - `src/mobile/CLAUDE.md`: the bridge, `linux/`, `windows/` and every trap;
  - [native-apps](../docs/crosscutting/native-apps.md).
- **Where what runs:** as in the slices.
  - The Kotlin and the bridge's JVM tests run on this Mac.
  - Linux runs in the Fedora 44 container (`linux/run.py`, Broadway into the browser pane).
  - Windows runs on the ARM64 VM (`windows/run.py`). The owner declared both test VMs disposable (2026-11-10).
- **The dev backend** (`veyq.localhost:8080`) belongs to whichever session runs it. It is never started twice and
  never stopped by this CR.
- **Not here:**
  - device trust (CR 2: the core reaches libsecret and DPAPI itself; the key never crosses);
  - any other screen: the node header, a Pot's writes, Depot, Memories;
  - the upload queue's composition in the bridge (Depot's CR, its first consumer);
  - packaging.

## Measured at refine (2026-11-11)

| # | What | How |
|---|---|---|
| R1 | The payload beside the JSON exists in all five places but carries nothing: `veyq_call`, the answer and update callbacks, the host function and `veyq_reply` in `bridge.h`. `Exports.kt` passes `null, 0u` everywhere and ignores what `veyq_reply` and `veyq_call` are handed. | `bridge/bridge.h`; `grep -n "null, 0u" bridge/src/bridgedMain/.../Exports.kt` |
| R2 | The hosts ignore it too: Rust's `answered`/`updated`/`host` take `_bytes, _len`; C#'s `Answered`/`Updated`/`Host` take `bytes, len` and do not read them. | `grep -n "_bytes" linux/src/bridge/mod.rs`; `windows/Veyq.Bridge/Core.cs` |
| R3 | The core hands bytes out in three shapes. Whole: `MemberAvatars.pictureOf`, `PotModel.openReceipt`, `ApplicationBlobs.download`. Through a sink: `NodeExport.write` / `PotExport.download` (`sink: (ByteArray) -> Unit`, not suspending), `DepotFiles.read`. In ranges: `RangeReader.read(position, length)`. Bytes come in through `UploadSource.read(from, length)`. | `grep -rn "fun .*ByteArray" core/src/commonMain` |
| R4 | **A precedent for files by path:** the Apple app hands the core a path and the core writes the download itself — `AppleDepot.download(tenant, file, path)` with `fopen(path, "wb")`, removing a partial file on failure (`core/src/appleMain/.../depot/AppleDepot.kt`). It also reads a picked folder's files by path (`FileUploadSource`). | read |
| R5 | When to sync the members' pictures is decided in each app: Android's `AppModel` calls `t.avatars.sync(t.people.values)` when a tenant opens and keeps `pictures()` in a flow of its own (`AppModel.kt:512–521`); Apple's `AppModel` does the same. A rule in two apps, so a defect under ADR 0127 decision 1 before a third and fourth copy. | `grep -n avatars android/.../AppModel.kt`, `apple/Veyq/AppModel.swift` |
| R6 | The wire has no call that opens a reader or names a file, and the bridge composes no `UploadQueue`. `Tenant` holds only `ShellModel` and `PotModel`. | `bridge/src/commonMain/.../Wire.kt`, `Bridge.kt` |

## Decisions

### D1 — Three channels, one each for what crosses

1. **Shown bytes ride beside the JSON.** A call whose answer is bytes the UI shows answers
   `Answer.Bytes(type: String?)`, with the bytes as the callback's payload (`bytes`, `len`), never in the JSON.
   - `type` is the core's sniffed media type (`blob/Sniff.kt`), or null where the core does not know it.
   - An update never carries a payload: state is JSON, pictures are asked for.
   - The host copies the payload during the callback (bridge.h's memory rule, unchanged).
   - `SecretFreeSchemaTest` gains the case that a `ByteArray` field anywhere on the wire is refused. It already refuses
     bytes in JSON, so this case is its bite.
2. **A blob read in ranges goes through a reader the bridge holds by id.**
   - A call that opens one answers `Answer.Reader(reader: Long, size: Long, type: String?)`.
   - `Reader.Read(reader, position, length)` answers `Answer.Bytes` with that range, through the core's `RangeReader`,
     whose frame cache stays the core's.
   - `Reader.Close(reader)` frees it. Every reader of a tenant closes when the tenant does, as its Pots do.
   - This CR builds the registry and the two generic calls. The calls that open a reader on a Depot file or a
     Memory's medium are their CRs'. Until then `DispatchTest` opens one through a test door.
3. **A file on the device is a path the host names, and the core opens it** (D3). Nothing of a file's content crosses.

### D2 — The dispatch stays hand-written, one line per call (no generator)

About 60 calls are still to come, so the question was whether to generate the dispatch from the core's models. No:
- **Completeness is already the compiler's.** `dispatch` is a `when` over the sealed `Call`, so a call without a
  line does not compile.
- **The samples are derived** (`WireTest`).
- **A generator would add KSP**, a build dependency and a second language for the wire, to save one line per call.
- **The line is where the bridge decides nothing.** Hand-written, it is read in review as such. Generated, a rule
  hiding in a model's signature would cross unread.

Revisited if a CR finds the dispatch growing beyond one line per call.

### D3 — Files by path, opened by the core (`bridgedMain`), the Apple precedent hoisted

- **The host's dialog gives a path, and the core opens it.**
  - The host's dialog is GTK's `FileChooserNative` through the xdg portal, or WinUI's `FileSavePicker` /
    `FileOpenPicker`.
  - Under Flatpak the document portal's path; in the container and on Windows the real path.
  - The core opens the file with the platform's own call: POSIX `open`/`pread`/`write` on Linux,
    `_wfopen`/`_fseeki64` on Windows. The path is UTF-8 on the wire and UTF-16 to Windows (D4).
- **The writer from `AppleDepot` moves to `nativeMain`** as `blob/FileSink.kt` (write as read, remove a partial file
  when it did not finish), with `FileUploadSource` beside it as `blob/FileSource.kt` (an `UploadSource` over a path,
  read at any offset). Apple keeps its behaviour, now over the shared code. Linux and Windows reach them from
  `bridgedMain`.
- **Why not stream a file across** with host requests and chunked replies:
  - It would be a protocol with backpressure, cancellation and partial-file cleanup on both sides.
  - It would put the platform's file handling into Rust and C# twice.
  - ADR 0127 decision 1 gives "the platform's side of a file" to the platform, yet Apple already lets the core write
    through the path the system handed it, and the path is still the system's choice.
  - The ADR records the reading: the dialog and the grant are the platform's, the bytes the core's.
- **Cancelling a write:** none in this CR. A download runs to its end or fails, and the partial file is removed.
  Cancel arrives with the first consumer that needs it (Depot's large files).

### D4 — Windows paths are UTF-16 at the call

- mingw's `fopen` takes the ANSI code page, so a path outside it would not open (remembered, not yet measured here).
  `FileSink`/`FileSource`'s `mingwMain` side converts the wire's UTF-8 to UTF-16 and calls `_wfopen`.
- **Phase 1's first measurement:** a write and read-back through a path with `ü`, `€` and a CJK character, in
  `mingwTest` on the VM (`windows/run.py test`), and the same red through plain `fopen` as the bite.

### D5 — When the pictures are read is the core's

R5's rule moves into the core:
- **The tenant's session** (`TenantSession`, or a small `Avatars` model it holds) syncs `MemberAvatars` when it opens
  and again after the people are read anew. It exposes `pictures` as a state flow and a watch (ADR 0127 decision 2).
- **Android and Apple are thinned in the same phase:** their `AppModel`s collect it instead of syncing themselves.
  Both run on a device unchanged (ADR 0127 decision 2's proof).
- **The bridge sends the ids, never the bytes:** `ShellShown` and the Pot's people carry the user ids that have a
  picture.
  - `Avatar.Picture(userId)` answers `Answer.Bytes` with the picture.
  - When the set of ids changes, the shell's update says so and the host asks again.
  - The bytes never ride an update (D1.1).

### D6 — The consumers

- **The avatars:**
  - On the Pot's people: Linux's `pot` Settings rows, Windows' `PotPage` people. Each person shows their picture
    where it is known, the letter otherwise, as Android and Apple draw it.
  - In the switcher: the signed-in member's own picture, beside the tenant.
  - The image is decoded by the platform: `gdk::Texture::from_bytes`, and WinUI's `BitmapImage.SetSourceAsync` over
    an in-memory stream.
- **A Pot's download:**
  - A `Pot.Download(pot, path)` call over `PotExport.download`, with the sink being D3's `FileSink`. It answers
    `Done` or `Failed`; nothing is left at the path on failure.
  - The host offers it where Android does, in the Pot's actions (Linux: the Pot page's menu; Windows: its
    `CommandBar`). The save dialog suggests the core's archive name (`export/ArchiveName.kt`). The name is a core
    answer (`Pot.ArchiveName`) for that reason: a host does not build it.
  - **The proof is the web's own import of that ZIP** (`VEYQ_LIVE_EXPORT`'s reader, then the web's "Upload files"
    import), as the Android download was proved.

### D7 — The hosts read the payload once, in the generated layer

- The bindings writer (`Bindings.kt`) gains the payload in each generated callback signature:
  - Rust: `Option<&[u8]>`, copied into a `Vec<u8>` before the call returns;
  - C#: a `ReadOnlySpan<byte>` copied into a `byte[]`.
- `Core.call` / `CallAsync` answer `(Answer, byte[]?)` on both hosts, so no screen touches a raw pointer.

## Owner steps

None. Everything runs on this Mac, the container and the disposable VMs.

## Inventory (locators — run at implement time, not counts)

- `grep -rn "null, 0u" src/mobile/bridge/src/bridgedMain`: every place the payload is dropped today.
- `grep -rn "_bytes\|bytes, nuint len" src/mobile/linux/src/bridge src/mobile/windows/Veyq.Bridge`: the hosts' sides.
- `grep -rn "avatars" src/mobile/android/src/main src/mobile/apple/Veyq`: what D5 thins.
- `grep -rn "fopen\|FileUploadSource" src/mobile/core/src/appleMain`: what D3 hoists.

## Phases

### Phase 1 — The core: files by path, the pictures' rule — done (2026-11-11)

- D4's measurement first: the UTF-16 path on the VM, its bite through plain `fopen`.
- `blob/FileSink.kt` and `blob/FileSource.kt` in `nativeMain`, with `posix` and `mingw` actuals where the call
  differs. `AppleDepot` uses them, and its own `fopen` code is gone.
- D5: the core syncs the pictures. Android and Apple are thinned.

**Done when:**
- `./gradlew :core:jvmTest :core:macosArm64Test`, `linux/run.py test` and `windows/run.py test` are green.
- A new `nativeTest` writes, reads at offsets and reads back a file of uneven frames through a non-ASCII path on all
  three. It removes a partial file when the sink fails mid-way.
- D5's rule has its test (`commonTest`: synced on open, again after the people change, not twice for an unchanged
  set), bitten.
- Android (emulator) and the Apple app (simulator) show the same avatars as before on a Pot's people
  (`VEYQ_LIVE_AVATARS=1` once to make sure the dev instance has a picture).

**Built (Phase 1):**
- **`posixMain` joins the hierarchy** (`core/build.gradle.kts`: `group("posix") { apple; linux }` beside `bridged`): the
  one call that differs sits behind `blob/Files.kt`'s `openFile`/`removeFile`/`seekFile` — `Files.posix.kt` (`fopen`,
  `remove`, `fseeko`) and `Files.mingw.kt` (`_wfopen`/`_wremove` over `wcstr`, `_fseeki64`). Everything else of a file is
  `nativeMain`. No new dependency: the compiles resolved nothing the verification metadata lacked (`verify-metadata`
  true, every metadata compile green), so no cold metadata write was run.
- **`FileSink`** (`open(path)`, `write`, `close(keep)`, `fill(kept, produce)`, `write(path, kept, produce)`): a
  producer that throws, or an answer `kept` refuses, leaves nothing at the path. **`FileSource`** is an open class;
  Apple's `FileUploadSource` subclasses it and holds the security scope in its `opening`/`closing` hooks.
- **Every Apple `fopen` went, not only `AppleDepot`'s:** `export/FileDownloads.kt` (a Pot, a subtree) and
  `AppleMemories.download` wrote the same loop; all three are `FileSink` now, Swift's calls unchanged.
- **D5 is `session/AvatarsModel.kt`, held as `TenantSession.pictures`:** `load()` syncs over the people read at open,
  `peopleRead(people)` syncs again only when the (member, revision) set differs from the last synced one; `state` and
  `watch` per ADR 0127 decision 2. Nothing reads the people anew today (`TenantSession.people` is read once at open), so
  `peopleRead` has no caller yet: it is the door Phase 2's shell update uses. A sync that fails is not retried for an
  unchanged set — as before, where each app synced once per tenant open.
- **Android** observes `t.pictures.state` and starts `load()` (`AppModel.watchAvatars`); **Apple** watches it and reads
  `AppleSessions.pictures(tenant)` (its `syncAvatars` is gone).

**Met (2026-11-11, commits `b2d976338`, `bcfb4aa22`):**
- [x] Green: `./gradlew :core:jvmTest :core:macosArm64Test :android:assembleDebug :android:testDebugUnitTest` →
  `BUILD SUCCESSFUL`; `/usr/bin/python3 src/mobile/linux/run.py test` → `184 tests from 40 test cases ran`, `PASSED 184`;
  `/usr/bin/python3 src/mobile/windows/run.py test` → the same 184, `PASSED`. `xcodebuild -project apple/Veyq.xcodeproj
  -target Veyq -sdk iphonesimulator build CODE_SIGNING_ALLOWED=NO` → `BUILD SUCCEEDED`.
- [x] D4 first: `windows/run.py test --tests '*FilesTest*'` → 4 passed on the VM. **Bite:** `Files.mingw.kt` over plain
  `fopen`/`remove` → red, `Expected <[Büro € 中文.bin]>, actual <[BÃ¼ro â‚¬ ä¸­æ–‡.bin]>` (the UTF-8 bytes read as
  CP-1252 on the German VM; the file was written and listed under the other name), restored, green.
- [x] `FilesTest` (`nativeTest`, its directory listed by the platform's own calls — `opendir`/`readdir`, Win32's
  `FindFirstFileW` — never the calls under test) on all three: macOS (`:core:macosArm64Test --tests '*FilesTest*'`, 4/4),
  Linux (`linux/run.py test --tests '*FilesTest*'`, 4/4), Windows (above): uneven frames (1 B … 128 KiB, one empty)
  written, read backwards in growing pieces, whole and past the end, through `Büro € 中文.bin`; a sink that throws
  mid-way and an answer not kept leave the directory empty. **Bite:** `FileSink.close` without its `removeFile` → both
  leave-nothing cases red on macOS, restored, green.
- [x] `AvatarsModelTest` (`commonTest`, 4 cases: synced on open, again after a change — a picture set, removed, a member
  joined —, not for the same set read again or reordered, a throwing sync shows the letters). **Bites:** without the
  unchanged-set check → `anUnchangedSetIsNotSyncedTwice` red; `load()` without its sync → the open and change cases red;
  each restored, green (`./gradlew :core:jvmTest --tests '*AvatarsModelTest*'`).
- [x] Android and Apple show the picture on a Pot's people, against the dev instance (`veyq.localhost:8080`):
  - The instance had no picture (`veyq_tenant_profile`: revision 2, `sealed_avatar` NULL), so one was set through the
    web (Settings, your profile: a blue disc on white, headless Chrome) → revision 3. The Pot "App link" had no people:
    the seeded principal was added there as a member (Android's Add member), so a person row carries a user id.
  - Android (`uiautomator dump`): Amsterdam's Anna and Ben keep their letters (`A`, `B`: no member behind them); "App
    link"'s `Kevin Raddatz` row has no letter, its avatar an image — the screenshot shows the disc. Apple (iPhone 18 Pro
    simulator, `apple/devlogin.py`): the same row, the same disc; Personen in German.
  - **How it ran, not as briefed:** the branch's `VERSION` is 0, `main`'s backend (the only dev backend; none was
    running, one was started from the main checkout and stopped after) is major 1, so every app from this branch is
    refused at sign-in (`server major 1, app 0`). The two apps were built once with `VERSION` set to 1 in the working
    tree (reverted right after the build, never committed); no contract differs between the merge base and `main`.
    `veyq_pixel` was locked with a PIN no file names, so the Android pass ran on a throwaway AVD of the same image
    (`veyq_check`, deleted after).

### Phase 2 — The bridge: the three channels — done (2026-11-11)

- **Wire:** `Answer.Bytes`, `Answer.Reader`, `Reader.Read` / `Reader.Close`, `Avatar.Picture`, `Pot.Download`,
  `Pot.ArchiveName`. `ShellShown` and the Pot's people carry the picture ids.
- **`Exports.kt`** passes the payload outward, and reads it inward where a call has one (none yet; bridge.h unchanged).
- **The reader registry**, closed with the tenant.
- **`Bindings.kt`:** D7 for both hosts.

**Done when:**
- `./gradlew :bridge:jvmTest` is green: `WireTest`'s samples include the new variants, and `SecretFreeSchemaTest`
  refuses a `ByteArray` field (its bite: one added, red, removed).
- `DispatchTest` covers each new call over the played door: a reader opened through the test door is read
  backwards in uneven ranges to the same bytes, and closed with the tenant.
- `BridgeHeaderTest` is green: bridge.h unchanged against both headers.
- `VEYQ_LIVE`'s `LiveBridgeTest`:
  - reads a member's picture through `Avatar.Picture`, its SHA-256 equal to the core's own read;
  - downloads Amsterdam through `Pot.Download` into a temp path, read back by the same checks as `VEYQ_LIVE_EXPORT`:
    every payload's SHA-256 against the manifest.
- `linux/run.py bridge` and `windows/run.py bridge` are green (exports unchanged), and their C smoke still signs in.

**Built (Phase 2):**
- **Wire** (`Wire.kt`): `Pot.Download(pot, path, deletedName)`, `Pot.ArchiveName(pot)`, `Avatar.Picture(userId)`,
  `Reader.Read(reader, position, length)`, `Reader.Close(reader)`; `Answer.Name`, `Answer.Bytes(mediaType)`,
  `Answer.Reader(reader, size, mediaType)`; `Why` gains `NO_SUCH_READER`, `NO_PICTURE`, `NOTHING_TO_DOWNLOAD`.
  `ShellShown` gains `me` (the signed-in member's user id, for the switcher) and `pictures` (the ids whose picture the
  core holds), `Update.Pot` gains `pictures` (the same ids; empty while nothing is open).
- **The payload** (`Bridge.call`): a call's answer is `(json, payload)`; the payload is non-null exactly for
  `Answer.Bytes` (an empty one included), null for everything else. `Exports.kt` hands an empty payload as a pointer to
  a byte never read with length 0, so NULL means "no payload" and a host needs no JSON to tell. Inward, nothing is read.
- **The core does the work, the dispatch one line each:** `PotModel.download(deletedName, sink)` and
  `PotModel.archiveName()` (`NodeExport.fileName`) are new in the core; `Pot.Download` writes through a `DeviceFiles`
  door — core's `FileSink.write(path, kept)` in `bridgedMain`, a JVM stand-in in `LiveBridgeTest` (FileSink is
  `nativeMain`, `Bridge.kt` `commonMain`). `Avatar.Picture` answers from `AvatarsModel.state` with `Sniff.image`'s type.
- **The tenant's parts** (`Tenant`): `shell` and `pot` nullable (a test's tenant has neither), `me`, `pictures`
  (`AvatarsModel.state`) and `loadPictures` (`AvatarsModel.load`), launched as the tenant opens.
- **The reader registry**: `Bridge.openReader(RangeReader, mediaType)` is the door the opening calls of Depot and
  Memories will use and the test uses now; `closeTenant` empties it with the Pots. `RangeReader`'s constructor is public.
- **D7** (`Bindings.kt`): from bridge.h's callbacks that carry `const uint8_t *`, `size_t`, the generator writes Rust's
  `payload(bytes, len) -> Option<Vec<u8>>` (the `Option<&[u8]>` copied) and C#'s `Native.Payload(byte*, nuint) ->
  byte[]?` (the span copied). `bridge::call` answers `Result<(Answer, Option<Vec<u8>>), Unreadable>`, `Core.CallAsync`
  `Task<(Answer Answer, byte[]? Payload)>`; the screens' four call sites each drop the payload.

**Met (2026-11-11, commits `76451f3e8`, `f0f0d1d5c`, `626c7980d`):**
- [x] `./gradlew :bridge:jvmTest` → `BUILD SUCCESSFUL`: BridgeHeaderTest 4, DispatchTest 8, SecretFreeSchemaTest 4,
  WireTest 2, LiveBridgeTest 1, no failure. WireTest's samples hold every new variant (its variant check is the proof).
  `git diff bridge/bridge.h` empty; BridgeHeaderTest green against both Kotlin/Native headers.
- [x] SecretFreeSchemaTest's new case refuses a byte array as a field, a list's element and a map's value. **Bites:**
  `Answer.Bytes` given a field `image: ByteArray` → `nothingTheCoreSendsCarriesAKeyASealATokenASecretOrBytes` red; the
  check without its descent into lists and maps → the new case red; each restored, green.
- [x] DispatchTest over the played door: a reader of 3 frames + 12,345 B read from the end to the start in ranges of 1,
  7, 65,537, a frame + 1, 100,003, 3 and a frame − 1 bytes → the same bytes; fewer at the end, an empty payload past
  it; closed, a switch and a sign-out each leave `NO_SUCH_READER`. A member's picture answers `Bytes` with its sniffed
  type and the bytes; none answers `NO_PICTURE`; Pot.Download/ArchiveName refuse `NO_SUCH_POT`. **Bites:** `closeTenant`
  without `readers.removeAll()` → the reader case red; `opened` without `loadPictures` → the picture case red; restored.
- [x] `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main>/.run/.env ./gradlew :bridge:jvmTest --tests
  '*LiveBridgeTest*' --rerun` → green: the principal's picture through `Avatar.Picture`, 2,628 B `image/webp`, SHA-256
  `d040a7ee…edeff3`, equal to a sign-in of the core's own (`tenant.avatars.sync`, `pictureOf`); "App link"'s update holds
  the principal among its people and its id among the pictures; `Pot.ArchiveName` → `Amsterdam.zip`; Amsterdam through
  `Pot.Download` → 6,089 B of ZIP, its one payload (the CSV; no receipt) to the manifest's size and SHA-256; a path whose
  directory is missing → `Failed`, nothing written. **Bite:** the picture's last byte cut in the dispatch → red
  ("the bridge's picture is not the core's"), restored.
- [x] `linux/run.py bridge` and `windows/run.py bridge` with `VEYQ_LIVE` → green: 12 symbols (Linux), 7 exports
  (Windows), "the library answered every step" through the sign-in on both.
- [x] `linux/run.py build` → clippy `-D warnings`, `cargo test` 8 passed, among them
  `an_answers_payload_is_copied_before_the_callback_returns` (the core's buffer cleared and dropped after the callback,
  NULL → None, an empty payload → `Some([])`). **Bite:** an empty payload read as None → red (`left: None, right:
  Some([])`), restored. `windows/run.py build` → no package deprecated or vulnerable; Veyq.Bridge.Tests here 3 + 1
  skipped (CoreTest is Windows-only), on the VM 4/4; Veyq.Tests 6/6. `PayloadTest` is the C# copy's test. **Bite:** the
  generated `Payload` null for length 0 → red here, restored.
- [x] `./gradlew :core:jvmTest :core:macosArm64Test` → `BUILD SUCCESSFUL`; `linux/run.py test` and `windows/run.py test`
  → 185 tests, `PASSED` on both. Android and the Apple app are untouched (the core only gained two `PotModel` functions
  and a public constructor).

### Phase 3 — Linux: avatars and the Pot's download

**Done when** a pass in the container (Broadway, the browser pane) has run against the dev instance:
- the people with their pictures, the letter where none;
- the switcher's own picture;
- the download through GTK's save dialog into the container's home, copied out and imported by the web (D6);
- German and English, light and dark, the narrow and the wide form;
- `run.py build` (clippy `-D warnings`, `cargo test` with a test of the payload's copy), and both guards green.

### Phase 4 — Windows: the same

**Done when** the same pass has run on the VM:
- WinUI's `FileSavePicker`, given the window's handle (`InitializeWithWindow`, an unpackaged app's need);
- the ZIP copied back to the Mac and imported by the web;
- `run.py build` and both guards green.

### Phase 5 — The docs

- **A new ADR:** "The bridge carries shown bytes beside the JSON, a blob's ranges through a reader, and files by path
  the core opens" — D1, D2, D3 with their reasons.
- **ADR 0127's Consequences:** the reading of decision 1 for files (D3).
- **ADR 0123's Consequences, the bridge:** the channels as built and measured.
- **`src/mobile/CLAUDE.md`:**
  - the bridge's channels;
  - `blob/FileSink`/`FileSource`;
  - the pictures' rule in the core;
  - the new traps.
- **native-apps:** Linux's and Windows' rows gain avatars and a Pot's download; Open drops what is done.
- **The CR's harvest:** traps to comments and rules, then the CR is deleted.

**Done when** the trust-chain guards are green (`/usr/bin/python3 scripts/validate_*.py` as the `trust-chain` job runs
them).

## Not in this CR

- Cancelling a running write (D3: with Depot's large files).
- The calls that open a reader on a file or a medium, and the video player (Depot, Memories).
- The upload queue in the bridge, and any upload screen (Depot).
- Covers, tiles and receipts as UI. They ride D1.1 unchanged when their screens come (CR 3–5).
- Device trust (CR 2), packaging (CR 6).

## Owner questions

None open. The cut and its order are the owner's (2026-11-11, "passt"). D1–D7 are technical, decided here, and open
to the owner's veto before "go".

## Retrospective

### Phase 1 (2026-11-11)

- **D4 held, and it is not a failure but a wrong name.** Plain `fopen` on mingw did not refuse `Büro € 中文.bin`: it wrote
  and would have read back a file named in CP-1252 mojibake. A round trip through the same call would have passed; the
  test is only a test because it reads the directory through Windows' own wide listing.
- **The hoist found three copies, not one** (`AppleDepot`, `FileDownloads`, `AppleMemories`): the CR's inventory grep
  named them, R4 only the first.
- **The branch cannot sign in to `main`'s backend** (VERSION 0 against major 1): a live phase of this CR needs `main`
  merged into the branch first, or each live run rebuilds with `VERSION` 1 as Phase 1 did. Phase 2's `LiveBridgeTest`
  and Phase 3/4's passes meet it. (Merged before Phase 2: `ed45847d9`.)

### Phase 2 (2026-11-11)

- **`Pot.Download` needs the host's word for an erased person:** the CSV carries it and the words are the platform's,
  so the call is `Pot.Download(pot, path, deletedName)`, not the CR's `(pot, path)` — as Android and Apple pass
  `pot_deleted_person` to `PotExport` today.
- **`type` is the wire's tag**, so `Answer.Bytes`' and `Answer.Reader`'s media type is `mediaType`: kotlinx refuses a
  variant whose field shares the class discriminator's name.
- **An empty payload is still one.** A range past the end is no bytes, not no answer: `Exports.kt` hands a pointer with
  length 0, and both hosts' tests hold `Some([])`/an empty array apart from NULL.
- **A state the host watches arrives once per change, so a test that reads a watch for a later fact misses it** — the
  pictures' update came while `LiveBridgeTest` waited for the open object; it reads a fresh watch, which delivers the
  state now.
- **Open for Phase 3/4:** a picture replaced under the same user id keeps the id set, so no update tells the host to
  ask again. Nothing does this today (`AvatarsModel.peopleRead` still has no caller: the people are read once per open);
  the first caller of `peopleRead` owes the host that signal (a revision per id, say).

