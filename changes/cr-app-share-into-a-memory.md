---
id: cr-app-share-into-a-memory
title: A Memory as a share target, and a drop onto a phone's gallery — each kind's share answer in the core, a Memory's files queued from disk, iOS's extension uploading in frames
status: planned
satisfies: []
adrs: ["0138", "0136", "0137", "0145", "0122", "0123", "0140"]
invariants:
  - "operator-blind E2EE: a shared or dropped file is sealed and uploaded exactly as a picked one is (MediaUploads → MediaUpload); the server sees what it sees of any upload"
  - "each kind's answer is its application's (ADR 0138 decision 2): core asks AppKinds which kinds take what, and never knows an application's rules"
  - "a share's files are read a frame at a time from where the system put them or from the app's one copy of them; no file is held whole beyond the Pot's bounded receipts (ADR 0138 decision 6)"
  - "nothing on disk the app did not choose (src/mobile/CLAUDE.md): a copy a share needs is the app's own, named there, removed once the queue lets the file go and swept at the next start"
  - "no word in an app but from src/mobile/words/words.json; each app follows its platform's guideline (ADR 0123 decision 9)"
  - "no pass signs in as the seeded principal or member: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

Inbox #893, the second CR of V2 block G, implemented after cr-app-memories-rest (done 2026-11-19, [ADR 0149](../docs/decisions/0149-the-apps-take-memories-rest-a-memory-re-keyed-downloaded-and-copied-its-marks-filter-and-contributions-and-tiles-the-webs-rules-in-the-core.md) decision 1). ADR
0138 decision 2 names a Memory's answer to a share — its photos — and decision 6 the extension's limit: a Memory's larger
files sealed and uploaded in frames from disk out of iOS's share extension. Neither is built: only a Pot takes a share.
Then a photo or a video shared from the phone's gallery (Android's share sheet, iOS's share extension) can be put into a
Memory or a chapter chosen in the pick mode, and photos dropped onto a Memory's gallery on a phone or a tablet (Android,
iPadOS; macOS shares the Apple shell) are uploaded into it — as the desktops already do (R6).

## Context & constraints

- **Read first:** ADR 0138 (all), ADR 0145 (uploads under way, a phone's process held), ADR 0137 (files by path),
  `src/mobile/CLAUDE.md` § Nothing on disk and § Each platform's guidelines.
- **Where what runs** (from `src/mobile/`, as `src/mobile/CLAUDE.md` § Prove your work names it): `ANDROID_HOME=$HOME/Library/Android/sdk
  ./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :android:assembleDebug`; the Apple builds after
  `./gradlew :core:assembleVeyqCoreDebugXCFramework`; live tests as a throwaway (`VEYQ_LIVE`); the share extension builds with the iOS target. **No iOS simulator
  boots on this Mac (#928)**: every iOS share and drop pass is the owner's on a device; the macOS drop runs here.
- **Out of scope:** a folder as a share target (Depot's answer, ADR 0138 decision 2: #987, new — this CR's hook takes it
  in one function); a share target on the desktops (GNOME has none; Windows' needs the packaged app's Share Target
  contract — `native-apps.md` says so, R6); music or GPX tracks shared into a Memory (their uploads check and measure
  first, `MediaUploads.uploadMusic`/`uploadTracks`).

## Measured at refine (2026-11-19)

Branch `claude/g-refine` at `42ac591de`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | **One hard-coded gate:** `fun takesShared(kind: String): Boolean = kind == PotSession.KIND`; the pick mode's dimming reads it on Android and Apple. | `grep -n 'fun takesShared' $C/AppKinds.kt`; `git grep -n 'takesShared' -- src/mobile/android src/mobile/apple/Veyq` |
| R2 | **The share inbox is the Pot's:** `expenses/ShareInbox.kt`' `Incoming(files: List<PotSession.PendingReceipt>, refused)`, `goesInto(view: PotView?)`, `takesDropOn`; a `PendingReceipt` holds its bytes; `PotShare.Intake` caps a share at 64 MiB and `Receipts.MAX_PER_ENTRY` files. | `grep -n 'class Incoming\|fun goesInto\|fun takesDropOn' $C/expenses/ShareInbox.kt`; `grep -n 'class PendingReceipt' $C/expenses/PotSession.kt`; `grep -n 'BUDGET\|MAX_FILES' $C/expenses/PotShare.kt` |
| R3 | **A Memory's upload reads frames from a source that outlives the pick:** `blob/UploadSource` (`read(from, length)`), `MediaUploads.upload(scopeId, nodeId, label, sources)` filtered by `MediaUpload.isMedia` (image/* or video/*), the source handed back through `door.left` ("a platform that copied a pick … removes the copy"); a phone's process held by `UploadsUnderway` (Android `UploadWork.kt`'s `dataSync` worker, Apple `ContinuedWork.swift`, app-only). | `sed -n 5,14p $C/blob/UploadSource.kt`; `grep -n 'fun upload(' $C/memories/MediaUploads.kt`; `sed -n 28,30p $C/memories/MediaUploads.kt`; `grep -n 'fun isMedia' $C/memories/MediaUpload.kt` |
| R4 | **Android:** the manifest's SEND/SEND_MULTIPLE take `image/*` and `application/pdf` only; `ShareIn.kt` reads each stream whole into a `ByteArray` because the read grant "does not outlast" the receiving activity; `MainActivity.onNewIntent` → `model.receiveShare`; **no drag and drop** anywhere (`onDrop` only removes a receipt). | `grep -n 'mimeType' src/mobile/android/src/main/AndroidManifest.xml`; `sed -n 14,16p src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/ShareIn.kt`; `git grep -n -i 'dragAndDrop\|DragEvent\|dropTarget' -- src/mobile/android` → none |
| R5 | **Apple:** `VeyqShare/Info.plist`'s activation rule takes `public.image` and `com.adobe.pdf` only; `IncomingFiles.read` loads each with `loadDataRepresentation` into memory (HEIC re-encoded); the extension runs `AppModel(inExtension: true)` (no offline root, no temp sweep); `receiveDrop` is the Pot's, on the shell's `.onDrop(of: IncomingFiles.accepted)`; `MemoryScreen.swift` takes no drop and uploads file URLs from `photosPicker` (a copy) and `fileImporter`. No background `URLSession` exists. | `grep -n NSExtensionActivationRule -A1 src/mobile/apple/VeyqShare/Info.plist`; `grep -n 'accepted\|loadDataRepresentation' src/mobile/apple/Veyq/IncomingFiles.swift`; `grep -n 'inExtension' src/mobile/apple/Veyq/AppModel.swift \| head`; `grep -n 'onDrop\|dropDestination' src/mobile/apple/Veyq/ShellView.swift src/mobile/apple/Veyq/MemoryScreen.swift`; `git grep -n 'URLSessionConfiguration.background' -- src/mobile/apple` → none |
| R6 | **The desktops already take a drop onto the gallery** (Linux `gtk::DropTarget` in `memory_page.rs`, Windows `AllowDrop` in `MemoryPage.cs`) and have no share target. | `git grep -n 'DropTarget::new' -- src/mobile/linux/src/memory_page.rs`; `git grep -n 'AllowDrop' -- src/mobile/windows/Veyq/MemoryPage.cs`; `git grep -n 'shareTarget' -- src/mobile/windows` → none |
| R7 | **Words** are the Pot's: `share_pick` ("Choose a Pot for this file"), `share_refused` ("only images and PDFs up to 10 MB"), `share_nothing`. No Use page tells sharing into veyq; `add-photos-and-videos.mdx` says a drop onto the Memory works (the web's and the desktops'). | `grep -n '"share_pick"\|"share_refused"\|"share_nothing"' src/mobile/words/words.json`; `sed -n 11,12p site/src/content/docs/memories/add-photos-and-videos.mdx` |

## Decisions

The ADR phase amends **ADR 0138** (decisions 2, 3, 6, 8, 9 and its Consequences); no new ADR — the decisions are that
ADR's, built.

### S1 — Each kind answers a share in its application's code; core asks

`AppKinds.takesShared(kind)` becomes `AppKinds.share(kind): ShareAnswer?` — `null` takes nothing; an answer names the
media types it takes, its per-file and per-share bounds and **how it takes them**: `Editor` (the Pot: bytes in memory
within `PotShare.Intake`, its editor's save) or `Queue` (a Memory and a chapter: `UploadSource`s handed to the target's
upload queue). Memories' answer (`memories/MemoryShare.kt`): images and videos (`MediaUpload.isMedia`), no size bound
beyond the upload's own, for a member who may upload there. The dimming reads `share(kind) != null` and the right.
`ShareInbox` leaves `expenses/` for `navigation/` holding `SharedFile`s (name, type, size, an `UploadSource`) and the
refused; the Pot reads its bounded receipts from them.
- Rejected: **a Memory branch beside the Pot's in core** — core would know two applications' rules (ADR 0138 decision 2).

### S2 — A Memory's flow: choose, then *Add here*

In the pick mode a Memory can be entered: its page opens with the shared files' count in the banner and **Add here**
(the target's own save, ADR 0138 decision 3) on the Memory and on each chapter; *Add here* queues the files through
`MediaUploads.upload` and ends the share (the gallery's placeholder tiles show them, ADR 0145). Leaving without it gives
the files back to the pick mode.
- Rejected: **queue on choosing a row** — a chapter could not be chosen (it lives inside the Memory's page), and decision
  3's "nothing until the target's save" would break.

### S3 — Android: video taken, files read from disk, the grant measured first

The manifest's two filters gain `video/*`. **Phase 3's first measurement:** whether a SEND's read grant outlives
`MainActivity` for the `UploadWorker` (the app left, the worker still reading). If it does, a Memory's sources read the
content URI in place (`ContentSource`, the pick's). If not (R4's comment says so for the activity), `ShareIn` streams each
file once into `noBackupFilesDir/share-in/<random>` — the app's one copy, removed when `door.left` lets it go and swept
at start — and the Pot reads its bytes from there as before. Either way nothing is read whole.
- **A drop:** `Modifier.dragAndDropTarget` (Compose foundation, in the BOM the app has; no new dependency) on the
  Memory's gallery uploads into it; on the shell it is a share (decision 9, as Apple's).

### S4 — iOS: the extension uploads in frames, and stays until it is done

The activation rule gains `public.movie`; `IncomingFiles` takes a Memory's files with `loadFileRepresentation`, copying
each into the extension's own temporary directory inside the callback (the system's URL is valid only there) — a JPEG
where offered as now. After *Add here* the extension **runs the core's upload queue itself** (the Pot's save already runs
the core in the extension) and shows its progress; it completes the request when the queue is done, and its cancel
cancels the queue with nothing left (the copies removed, the stale-session purge reclaiming an unattached upload).
Measure in the build (owner's device pass): the extension's memory over a 1 GB video stays far under the limit, since
frames are sealed one at a time.
- Rejected: **handing the files to the app through the app group** — the app cannot be opened from an extension; the
  files would wait, as plaintext in a shared container, until the person happens to open veyq. Rejected: **a background
  `URLSession`** — the core's client is not one, and its presigned frame uploads would need a second upload path.

### S5 — A drop onto Apple's gallery

`MemoryScreen` takes `.dropDestination(for:)` images and movies (iPadOS, macOS; an iPhone's drag between apps where the
system offers it) into that Memory; the shell's drop becomes a share for every kind with an answer.

### S6 — Words and what the server sees

`share_pick` becomes "Choose where these files go" / "Wähle, wohin diese Dateien gehören"; `share_refused` names the
kind's own reason; `share_add_here` "Add here" / "Hier hinzufügen" (*owner question 1*). The server sees an ordinary
upload.

## Phases

- [ ] **1 ADR 0138 amended** (decisions 2, 3, 6, 8, 9, Consequences), `native-apps.md` § Open, `src/mobile/CLAUDE.md`
  § Nothing on disk (S3's copy if taken, S4's). · Done when: every guard but guard_gaps green.
- [ ] **2 core** — S1, S2: `ShareAnswer`, `MemoryShare`, `ShareInbox` in `navigation/`, the pick mode's Memory page.
  Tests: `PotShareTest` unchanged and green (the refactor's proof); a Memory takes a video and refuses a PDF; a chapter
  takes; a read-only member's Memory dimmed; *Add here* queues once. **Bites:** (a) `share` returning the Pot's answer
  for a Memory → red; (b) *Add here* queuing twice → red.
- [ ] **3 Android** — S3 (the grant measured first, its result into ADR 0138), the drop. · Done when:
  `:android:assembleDebug`, the guidelines and words guards green; § Passes' Android rows.
- [ ] **4 Apple** — S4, S5. · Done when: both Apple builds green; the macOS drop clicked here; iOS the owner's.
- [ ] **5 the passes and harvest** — § Passes into ADR 0138's Consequences; a Use page "Share into veyq" (EN+DE) and
  `add-photos-and-videos.mdx`'s phone lines; the reuse sweep; #893 deleted; this CR deleted. · Done when: `pnpm run check`
  in `site/` and every guard but guard_gaps green.

## Passes

| Pass | Who | What it proves |
|---|---|---|
| Android emulator, a throwaway | agent | a photo and a 300 MB video shared from Files into a chapter, the app left at once, both in the gallery on the web; the app's files listed after (`run-as app.veyq find . -type f`): no copy left; a drop from a second window (split screen) onto the gallery |
| macOS build, a throwaway | agent | photos dropped from Finder onto a Memory's gallery |
| iPhone and iPad on a device | **owner** | a share from Photos of a long video into a Memory with the extension open to the end; the extension cancelled half-way (nothing in the gallery); a drop on the iPad |

## Owner questions

1. **Words:** "Choose where these files go" / "Wähle, wohin diese Dateien gehören" and "Add here" / "Hier hinzufügen".
   *Recommendation:* these.
2. **iOS keeps the share sheet open until a large video is uploaded** (S4): a minute-long upload shows its progress in
   the sheet; closing it cancels. *Recommendation:* yes — the only way a share extension finishes an upload without
   leaving the files behind as plaintext for the app to find later.

## Definition of done

Phases 1–5 checked; the bites seen red and green; the passes run as throwaways and erased, the owner's named open where
not run; the Use page in EN and DE; every guard but guard_gaps green. `git diff --stat` over `src/backend/`, `src/web/`
and `contracts/` is empty.

## Retrospective

_(filled at the end of the implementation)_
