---
id: cr-the-node-header-on-every-app
title: A node's header and its actions on Linux and Windows — the sheets' rules moved into the core first, then bridged and drawn
status: planned
satisfies: []
adrs: ["0127", "0128", "0123", "0122", "0093"]
invariants:
  - every rule is the core's (ADR 0127 decision 1) — what Android's and Apple's app models still decide for the header and its sheets moves into core screen models first, both apps thinned in the same phase and unchanged on a device (decision 2)
  - the bridge only carries — each call one line onto a core model, no key, seal or token outward (SecretFreeSchemaTest); files by path, shown bytes beside the JSON (ADR 0128)
  - no word in a desktop but from src/mobile/words/words.json; each desktop follows its platform's guideline (ADR 0123 decision 9)
  - Android, the Apple app and the web behave as before, except where a rule they disagreed on is made one (D4); `./gradlew :core:jvmTest :core:macosArm64Test` and both desktops' `run.py test` stay green in every phase
---

## Goal

CR 3a of the owner's cut for bringing Linux and Windows to the Android and Apple apps (2026-11-11, "passt"; the
owner's "plane CR 3", 2026-11-11). The bridge's foundation (ADR 0128) and the device trust are done. This CR is
**a node's header and every action on it**, for every kind that has one: a Pot and a folder now. Depot's and Memories'
kinds reuse it in their own CRs. CR 3b, Expenses whole, follows.

The header and its actions:
- **the header itself:** the plan, its order and labels, a kind's rights;
- **pin** (Favorite);
- **trash with undo**, and the space's **trash sheet** (restore, purge);
- **rename**;
- **New folder**;
- **move**, and **copy** with the storage check;
- **download** of a folder (a Pot's exists);
- **share links:** list, mint, edit, renew, off/on, delete, the re-key, and its **background resume**;
- **report**;
- **cover:** set, crop, remove.

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` at `443cfab35`. It holds both desktops' first slice, ADR 0128's
  channels and the device trust, all harvested.
- **Read first:**
  - ADR 0127 (decisions 1 and 2), ADR 0128, ADR 0122;
  - `src/mobile/CLAUDE.md` § Each platform's guidelines: a node's header is core's `navigation/NodeHeader.kt`; each app's
    `NodeHeaderActions` draws it; a kind only registers its page;
  - the gap list's rows (A) in this CR's R-table.
- **Where what runs:** as in the slices.
  - The Kotlin runs on this Mac.
  - Linux runs in the container (Broadway, the browser pane or headless Chrome).
  - Windows runs on the ARM64 VM.
  - Android runs on a throwaway AVD (never `veyq_pixel`, whose PIN is unknown); Apple on a throwaway simulator.
  - The dev backend (`veyq.localhost:8080`) is never started twice or stopped.

## Measured at refine (2026-11-11)

From a read of the core, both app models and the bridge (an Explore pass). Cites are into `src/mobile/`, the core being
`core/src/commonMain/kotlin/me/raddatz/veyq/mobile/`.

| # | What | Where |
|---|---|---|
| R1 | **The bridge carries none of (A).** It has Pot.Open/Refresh/Close/Download/ArchiveName, Avatar.Picture, Reader.*, the session and Manage calls, and the shell's navigation. | `bridge/.../Wire.kt` |
| R2 | **Rules already the core's, with tests:** `NodeHeader.rights/plan/label` (`NodeHeaderTest` 11); `ShellModel.toggleFavorite`, `trash`/`undoTrash` (`TRASH_UNDO_MS = 6000`), `rename` → `Renamed(notice, linksFailed)`, `createFolder` → `Notice?`, `move` → `Moved`, `duplicate`; `TrashSheet`, a full screen model (`TrashSheetTest`); `NodeMove` (`NodeMoveTest` 8); `SubtreeCopy.preview`, `StorageFit.noRoomForCopy`, `AppKinds.lossesOfCopy`; `ShareLinks`, `ShareLinkChanges`, `LinkForm` (`LinkFormTest`), `LinkRow` (`LinkRowTest`), `ShareWords`; `NodeRekey.resume`; `NodeReport`; `NodeExport`; `NodeCover`, `CoverFrame` (`CoverFrameTest` 7). | the core |
| R3 | **Still decided in both app models** (a third and fourth copy would follow; ADR 0127 decision 1 makes each a defect): | |
|  | — **the header's inputs:** `rights()` composed from pinned, links on, `reportOffered`, the identity folder; a kind's doors (a Pot's cover and copy only while `canWrite`, a folder's copy and download, a file's only with content) | `android/.../NodeHeader.kt:117-127`, `KindPages.kt:31-152`; `apple/Veyq/NodeHeader.swift` |
|  | — **pin's** optimistic heart, its revert and its failure notice | `NodeHeader.kt:154-163` |
|  | — **trash's** undo timer, and closing the open Pot when it is gone | `NodeHeader.kt:238-253`, `AppModel.kt:419`; `NodeHeader.swift:559-583`, `AppModel.swift:522` |
|  | — **the move/copy sheet:** the browser's state (tree, place, problem, the guest folder's confirm), which copy a kind uses, the no-room text, the left-out lines | `NodeMoveSheet.kt:53-196`, `AppModel.kt:1264-1284` |
|  | — **the move's undo, which differs between the apps:** Android a `SnackbarDuration.Long`, Apple a 6 s notice | `NodeHeader.kt:214`; `AppModel.swift:771-783` |
|  | — **the share sheet whole:** busy row, the fresh URL, the confirms, the re-read after each write, the drop box's path, the Pot re-read after off/renew/delete | `ShareSheet.kt:81-235`, `AppModel.kt:474-551`; `AppModel.swift:594-666` |
|  | — **the re-key resume** at tenant open and its follow-up | `AppModel.kt:343, 502-512`; `AppModel.swift:608-618` |
|  | — **report:** whether it is offered (read once per tenant), **the privacy URL built in the app** (`"$origin/privacy#$anchor"`), the form's state | `AppModel.kt:448-463`; `AppModel.swift:554-569` |
|  | — **the cover spec written twice** (long edge 2048, JPEG quality 0.85), the covers' diff and thumbnails | `AppModel.kt:1033-1123`; `AppModel.swift:1130-1152`, `CoverImages.swift` |
|  | — **a folder's download** composed in the app model, and deleting a half-written archive | `AppModel.kt:1246-1262`, `NodeHeader.kt:139-146` |
| R4 | **Test gaps:** no common test pins `mint`, `edit`, `renew`, `reactivate` or `delete` of a link (only live tests reach them). | `core/src/commonTest` |
| R5 | **The desktops' native needs:** a clipboard (links); a system share (links; Windows' `DataTransferManager`, which an unpackaged app reaches through `IDataTransferManagerInterop` with the HWND; GNOME has no share sheet); opening a URL (the report's privacy link); a picture picker, decode, crop and JPEG encode (cover). | — |

## Decisions

### D1 — The core takes what R3 lists, as screen models, before any desktop draws it

Each item is ADR 0127 decision 2's shape: a core class with a state flow, a `watch`, and actions as plain functions,
tested in `commonTest`. Android and Apple are thinned in the same phase and run unchanged on a device:
- **`NodeHeaderModel`** for an open node. It holds:
  - its rights composed in the core: pinned, links on, `reportOffered` read once per tenant, the identity folder, and a
    kind's doors from `AppKinds` plus the open model's `canWrite`;
  - the plan and the labels;
  - **pin** with its optimistic state, revert and notice;
  - **trash** with the undo window the core runs (`TRASH_UNDO_MS`), closing the open node when it is gone;
  - **rename**.
- **`MoveSheet`**: the browser's tree, place, block and problem; the guest folder's confirm; **move and copy** through it,
  copy choosing the kind's way (`PotModel.duplicate` / `SubtreeCopy`); the storage check's answer as a notice with its
  number (the platform formats the size); the left-out lines; and **one undo** (D4).
- **`ShareSheet`**: the links listed, mint, edit, renew, off/on and delete; the busy row; the fresh URL; the confirms as
  state; the re-read after each write; the drop box's "add files only" path; and the open Pot re-read after a re-key.
  - R4's gap is closed: `commonTest` cases for each write over a played server.
- **`ReportSheet`**: offered or not, the form's rules (`NodeReport`), and **the privacy URL built in the core** from the
  tenant's origin and `PRIVACY_ANCHOR`.
- **The cover spec** (long edge, quality, the thumbnail size) becomes core constants with the covers' diff
  (`NodeCover.changes`). Decoding, cropping by hand and encoding stay the platform's (ADR 0127 decision 1: decoding
  pictures is the device's), the crop's math being `CoverFrame`'s.
- **The re-key resume** starts in the core when a tenant opens, re-reading what it touched. No app starts it any more.
- **A folder's download** is one core call over `NodeExport` into ADR 0128's `FileSink`, removing a half-written archive.

### D2 — The bridge carries the models, one line each

- Each model is `Watched` by an id the bridge holds, as Pots are: `Watched.Header(ref)`, `Watched.Move(id)`,
  `Watched.Share(id)`, `Watched.Report(id)`, `Watched.Trash(scopeId)`.
- Each action is a `Call` onto it.
- **Covers:**
  - A cover's bytes ride ADR 0128's payload, through a reader for the full picture and `Answer.Bytes` for a thumbnail.
  - A new cover goes in **as a path the host's picker gave plus the host's encoded JPEG at that path**. The host decodes,
    crops and encodes to the core's constants, then names the file. The core seals it.
- `SecretFreeSchemaTest` holds: a share link's URL with its `#k=` key **does** cross outward. It is what the person copies.
  It is a key by its nature, so the test's allowance is written once, named, and bitten, never a blanket exception.

### D3 — The desktops' native parts

- **Clipboard:** GTK `gdk::Clipboard::set_text`; WinUI `DataPackage` + `Clipboard.SetContent`.
- **Share a link:**
  - Windows `DataTransferManager` via `IDataTransferManagerInterop.ShowShareUIForWindow` (HWND).
  - Linux has no share sheet in GNOME, so copy is the share there (GNOME HIG: a "Copy link" action). Said so, not faked.
- **Open the privacy URL:** `gtk::UriLauncher`; `Windows.System.Launcher.LaunchUriAsync`.
- **Cover:**
  - Pick through the portal / `FileOpenPicker`.
  - Decode: `gdk::Texture` / glycin; `BitmapDecoder` (WIC).
  - The crop: a pan-and-zoom view over the picture, its pose `CoverFrame`'s, asked of the core per change or mirrored
    from its pure math, decided in Phase 5 by what the call rate allows.
  - Encode JPEG at the core's quality: gdk-pixbuf's saver / WIC `BitmapEncoder`.
  - WebP covers decode as avatars do. Windows needs the Store's "Webp Image Extensions": ADR 0128's trap.

### D4 — One undo for a move: the core's window, as for a trash

- Android shows a move's undo as a long snackbar; Apple for 6 s (R3).
- The core runs both a trash's and a move's undo for `TRASH_UNDO_MS` (6 s) and drops it after. Android's snackbar shows
  for that window.
- A technical unification, recorded for the owner's veto. The window is the core's already for a trash.

### D5 — The screens, each to its platform's guideline

- **Linux (GNOME HIG):**
  - the header's actions in the header bar and its menu;
  - sheets as `AdwDialog`s;
  - the undo as an `AdwToast` with its button;
  - the trash sheet as a dialog from the space's menu.
- **Windows (Fluent):**
  - a `CommandBar` with primary and secondary commands;
  - sheets as `ContentDialog`s or pages (WinUI shows one `ContentDialog` at a time: Phase 7's trap);
  - the undo as an `InfoBar` with an action.
- **Every word** from `words.json`, reusing Android's and Apple's keys.

## Owner steps

None. Throwaway emulators and simulators; the VMs are disposable (owner 2026-11-10).

## Inventory (locators — run at implement time)

- `grep -n "rights(\|reportOffered\|linkRules\|inIdentityFolder" src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/*.kt src/mobile/apple/Veyq/*.swift`
- `grep -rn "SnackbarDuration\|undo" src/mobile/android/.../NodeHeader.kt src/mobile/apple/Veyq/AppModel.swift`
- `grep -rn "2048\|0.85\|privacy#" src/mobile/android src/mobile/apple`

## Phases

### Phase 1 — The header in the core: `NodeHeaderModel` (plan, pin, trash + undo, rename), New folder, the trash sheet's wiring

Android and Apple thinned onto it.

**Done when:**
- `commonTest` pins each moved rule with its bite: the rights per kind and state; pin's revert; the undo window run by the
  core; the open node closed after its trash; rename's notice when a link kept the old name.
- `./gradlew :core:jvmTest :core:macosArm64Test` green.
- Android and Apple: the header, pin, trash with undo, the space's trash, rename and New folder unchanged on a device,
  read as text first.

**Status: done (2026-11-11).**

**Built:**
- core `navigation/NodeHeaderModel.kt`: state (`pinned`, `rights`, `undo`, `gone`), `watch`, `update(facts)`, `plan`,
  `label`, `deleteTitle`/`deleteQuestion`, `toggleFavorite`, `trash`, `undoTrash`, `rename`; over a `Door` that
  `ShellModel.header(node, facts, closeOpen, changed)` builds. `AppKinds.Facts`/`Doors`/`doors`/`coverSettable` hold a
  kind's doors; `ShellModel.reportOffered()` reads the moderation key once per tenant (a failed read is asked again by the
  next header); `ShellModel.createFolderHere(name)` takes the shell's place.
- Android and Apple draw the header from it. Gone from `AppModel.kt`/`.swift`: `isFavorite`, `toggleFavorite`,
  `inIdentityFolder`, `trash`/`restore`, `rename`, `reportOffered`/`readReportOffered`; from `NodeHeader.kt`/`.swift` and
  `KindPages`: the rights' composition, the optimistic heart, the undo timer, the delete's words, the contribution's
  `cover`/`rename`/`deleteTitle`/`deleteQuestion`. A kind's page hands only `facts` and its doors' closures.
- Apple: the header's view redraws on `AppModel.headerRevision`, which a watch on each handed-out header moves.

**Met:**
- `NodeHeaderModelTest` (5 cases), each rule bitten (removed → its case red → restored): a Pot's copy only while
  writable (`AppKinds.doors`), pin's revert, the undo window's end, `closeOpen` after a trash, rename's
  `SHARE_NAME_RESEAL_FAILED` — measured with a script over `./gradlew :core:jvmTest --tests '*NodeHeaderModelTest*'`.
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green (NodeHeaderModelTest 5/5 on both targets).
- `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug` green; `xcodebuild -scheme Veyq` for an iOS 27
  simulator (signed) and `-target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO` green.
- `linux/run.py build` and `windows/run.py build` green (Windows: Veyq.Tests 13/13).
- `scripts/validate_mobile_words.py`, `validate_mobile_platform_guidelines.py` green.
- Android (throwaway AVD `veyq_hdr`, deleted), read through `uiautomator dump`: New folder (and its "name taken"), the
  folder's plan (Favorite, Share | Download, Rename, Set cover | Move, Copy, Report, Delete folder) and a Pot's, pin and
  unpin read back by a fresh header, rename (a sibling's name refused in the dialog; the title follows), trash with
  Undo restoring it, trash left to its window (the snackbar gone), the space's trash: restore, then purge.
- Apple (throwaway iPhone 18 Pro / iOS 27, deleted): New folder, the level's one "More" menu with Report, pin and unpin,
  rename twice (Report kept), trash with its notice and "Rückgängig" gone after the window, restore and purge from the
  space's trash. Undo itself was not tapped in time on the simulator (the tool's round trip exceeds 6 s; Android's
  pass and the core's test hold it).

### Phase 2 — Bridge and desktops: the header, pin, trash, rename, New folder, the trash sheet

**Done when:**
- `DispatchTest` and `LiveBridgeTest` (live: pin, trash, undo, restore, purge, rename, New folder, each read back) green.
- A pass on Linux (container) and on Windows (VM) of each action, de/en, light/dark.
- Builds and guards green.

**Status: done (2026-11-11).**

**Built:**
- core: `TrashSheet.purge(row)` (the row confirmed is handed in; `purge()` reading the question is gone) and
  `TrashSheet.row(id)`; Android's and Apple's trash dialogs call it, which makes `1c17a82df` structural. `NodeAt`,
  `AppKinds.Facts` and `AppKinds.Create` are `@Serializable` for the wire.
- Android: a guest's folder removed from a drop box's page goes through the header's `trash(…)` helper with the shell's
  snackbar (`LocalShellNotices`), its Undo held for the core's window, as Apple's notice does.
- bridge: `Header.Open(node, facts, maxButtons)` → `HeaderOpened`, `Header.Facts/Close/Pin/Undo/Rename`, `Header.Trash` →
  `Trashed(notice, undoable)`, `Shell.CreateFolder`, `Trash.Open` → `TrashOpened`, `Trash.Close/Restore/Type/Retry/
  CancelRename/Purge(row)`; `Watched.Header(id)` (`HeaderShown`: node, pinned, the plan with each action's words, the
  delete's question, the undo while offered, gone) and `Watched.Trash(id)` (`TrashShown`: rows with `restorable` and
  `retryable`, busy, error, renaming, retryName). `ShellShown` gains `creates` by kind, `canCreateHere` and `headed`.
  `Tenant` takes the header, trash and new-folder doors (the live ones over `ShellModel`, a test's played).
- Linux (`header.rs`, `trash.rs`): the header bar's buttons and More menu, `AdwAlertDialog` for rename, delete and New
  folder (asked again with the core's problem), an `AdwToast` with Undo held until the state's undo ends, the space's
  trash an `AdwDialog` from the switcher's menu with "Delete forever?" an `AdwAlertDialog`. `said::instant_day`.
- Windows (`NodeHeader.cs`, `TrashDialog.cs`, `Dialogs.cs`, `HeaderPlan.cs`): the `CommandBar`'s primary and secondary
  commands, `ContentDialog`s through `Dialogs.Show` (one at a time), the `InfoBar`'s Undo, the trash a `ContentDialog`
  from the switcher with "Delete forever?" asked in place. `Said.InstantDay`; `CreateAction.Menu` takes a disabled kind.
- **Not drawn yet (both desktops):** share, move, copy, report, cover and a folder's download — left out of the bar
  (`header::DRAWN`, `HeaderPlan.Drawn`), not drawn as commands answering "not yet": a dead control breaks both
  guidelines, and each later phase adds its id. A Pot's Download is drawn (it existed: `Pot.Download`). New's Pot entry is
  shown disabled.

**Met:**
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green. `TrashSheetTest` (4) bitten: `purge(row)` reading
  `current.purging` again → 2 red. `DispatchTest` (16) drives every new call over a played door (header, trash, new folder),
  bitten: `Header.Undo` and `Trash.Purge` made no-ops → 2 red.
- `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main>/.run/.env ./gradlew :bridge:jvmTest --tests
  '*LiveBridgeTest.aFolderIs*' --rerun`: New folder "App header <n>", pin and unpin (each read back by a refresh and a
  fresh header), rename, trash, undo, trash again with the undo left to the core's window (offered 6007 ms), restore from
  the space's trash, trash and purge — each read back, the folder purged at the end.
- `linux/run.py build` green (clippy `-D warnings`; `cargo test` 18 incl. `header::tests`, `said`'s instant day);
  `windows/run.py build` green (Veyq.Tests 15/15 incl. `HeaderPlanTest`, `InstantDay`).
- `scripts/validate_mobile_words.py`, `validate_mobile_platform_guidelines.py` green.
- `ANDROID_HOME=… ./gradlew :android:assembleDebug` and `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO`
  green; a signed iOS 27 simulator build green.
- Linux (container, `run --devlogin --no-secret-service`, Broadway on 8097 driven from headless system Chrome; stderr read
  before each shot): a folder ("Linux pass") made in Personal, pinned, renamed, trashed with Undo, the Undo left to its
  window (the toast dismissed with the state), restored from the space's trash, trashed and undone; a Pot (Sri Lanka)
  unpinned and pinned, renamed and back, trashed and undone; en/light and de/dark (the trash's dates, "Rückgängig", the
  purge's question); the folder purged at the end.
- Windows (VM, `run --devlogin`, then `--devlogin --unlock`; `run.py drive`): the same on a folder ("Windows pass") and on
  Sri Lanka, en/light and de/dark, the offer answered "Not now", the folder purged; ended with Stop trusting
  (`manage servers: None`). No Hello prompt was raised.
- Android's and Apple's additions by compile and `TrashSheetTest`/`NodeHeaderModelTest` only: the iOS simulator tool's
  access was not granted in this session (the throwaway `veyq_p2` was created, the app installed and signed in, then
  deleted), and no drop box with a guest's folder was found in the dev instance (`DropBoxLiveTest` against
  `App dropre 56950/Box` found none), so Android's drop-box Undo is proven by compile and the shared `trash(…)` helper.

### Phase 3 — Move, copy and a folder's download: `MoveSheet`, the storage check, one undo (D4); the core-run download

Android and Apple thinned.

**Done when:**
- `commonTest` pins each moved rule, bitten: the place's block, the guest folder's confirm, the copy's kind, no room,
  the undo window.
- Both apps unchanged, but for D4's window.
- Bridge + both desktops' pass: move and undo; copy of a Pot and a folder; no room (`VEYQ_LIVE_STORAGE_FIT`'s one-byte
  quota, put back however the pass ends); a folder's download, read back by `VEYQ_LIVE_EXPORT`'s checks.

**Status: built (2026-11-11); the Windows pass is open** — the VM rebooted at 19:31 and sits at its sign-in screen, so
the scheduled task that starts the app in the logged-on session cannot run (`veyq-app wrote no app.json.started`); an
agent never types its password. Someone logs on at the VM's console, then the pass below runs.

**Built:**
- core, fix first: `Shell.leave()` and `ShellModel.afterTrash(shell, nodeId)` — an object opened at the top
  (`openAtTop`) and trashed leaves for its application's list (it stayed open on a closed model, "Loading"); its undo
  brings it back to that list. Every app follows (the shell is the core's).
- core `navigation/MoveSheet.kt` (ADR 0127 decision 2): state (`loaded`, `at`, `trail` of `Crumb`s, `places`, `block`,
  `problem`, `noRoom` as `Notice(COPY_NO_ROOM, Bytes)`, `losses`, `leftOut`, `busy`, `confirming`, `done`, `undo`,
  `canChoose`), `watch`, `load`, `go`, `up`, `choose`, `confirm`, `cancelConfirm`, `undo`, the words (`title`, `hint`,
  `submit`, `empty`, `confirmQuestion`, `hereWord`). The copy's way is `AppKinds.copiesByOpenModel` (a Pot by its open
  `PotModel.duplicate`, the rest `SubtreeCopy`); the move's undo runs for `TRASH_UNDO_MS` (D4). Its reads and writes run
  on `Dispatchers.Default`. `ShellModel.moveSheet(node, copying, pot, deletedName, changed)` builds it (UUIDs from the
  suite's randomness, `NodeCreate.uuid`); `ShellModel.download(ref, deletedName, nowMs, sink)` writes a node's ZIP, and
  `nativeMain`'s `ShellModel.download(…, path)` into `FileSink` (a half-written archive removed). `Notices.downloaded`,
  `downloadLeftOut`.
- Android and Apple thinned: `NodeMoveSheet` draws the core's sheet (Apple's `MoveBox` mirrors it); gone from the app
  models and the kinds' contributions: the placement, the move, both copies, the copy's notes and no-room text, Apple's
  `MoveBack` and its timer, Android's `rootName`, `CopyNotes`. Android's move undo is the shell's snackbar held for the
  core's window through the trash's `offerUndo` (D4); Apple's is its shell notice watched on the sheet's `undo`. A
  folder's download is `ShellModel.download` (Apple `FileDownloads.subtree`).
- bridge: `Move.Open(node, copy, deletedName)` → `MoveOpened`, `Move.Close/Go/Up/Here/Confirm/CancelConfirm/Undo`;
  `Here`/`Confirm` answer `MoveSent(done, undoable, confirming, problem)`; `Watched.Move(id)` → `MoveShown`;
  `Header.Download(header, path, deletedName)` → `Downloaded(written, notice, leftOut)`; `HeaderShown.archiveName`. A
  Pot's copy takes the Pot model the host opened for it.
- Linux (`move_sheet.rs`): an `AdwDialog` holding an `AdwNavigationView` whose pages follow the core's trail (back is
  up; `sync` tested), the bottom bar's reason and Move here / Copy here, the guest folder's `AdwAlertDialog`, the
  move's Undo an `AdwToast` held for the core's window; a folder's download through GTK's save dialog.
- Windows (`MoveDialog.cs`): a `ContentDialog` with a `BreadcrumbBar`, the places as buttons, the primary button the
  write (kept open until the core answered), the guest folder's confirm in place, the move's Undo the `InfoBar`'s
  action; a folder's download through the save picker. Fix 2: the bar's secondary commands come from the plan's groups
  (`HeaderPlan.Primary`/`Secondary`), so a folder's More and a Pot's are alike.

**Met:**
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green. `MoveSheetTest` (5) on both targets, each rule
  bitten by a script (place's block, guest folder's confirm, copy's kind, no room, undo window — each mutation red,
  restored). `ShellModelTest`'s top-trash case bitten (`leave()` back to `goBack()` → red). `DispatchTest` (17) drives
  every new call; bitten: `Move.Undo` and `Move.Here` made no-ops, and a failed download kept → red.
- `VEYQ_LIVE=… VEYQ_ENV_FILE=<main>/.run/.env ./gradlew :bridge:jvmTest --tests '*LiveBridgeTest.aPotIsCopied*' --rerun`:
  "App move <n>" made, Amsterdam copied by its open Pot model, the copy moved in and undone (read back), moved again
  with the undo left to the window (offered 6011 ms), the folder copied with the Pot in it, the folder downloaded and
  read back (every payload to its SHA-256), a path that does not open failed with nothing written; all purged.
- No room: `DepotLiveTest.aCopyOrAFolder*` with `VEYQ_LIVE_STORAGE_FIT="Reisen/AppUpUi"` and `…_HOLD_S` held the
  one-byte quota (put back, exit 0) while `VEYQ_LIVE_NO_ROOM=1 … --tests '*LiveBridgeTest.aCopyWithoutRoom*'` saw
  Amsterdam's copy said not to fit (`Bytes(365)`), "Copy here" not choosable, nothing written.
- `linux/run.py build` green (clippy `-D warnings`, `cargo test` 19); `windows/run.py build` green (Veyq.Tests 16,
  `HeaderPlanTest` bitten: the separator's rule and the download's kind mutated → both cases red).
- `:android:assembleDebug` green; `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO` and a signed iOS 27
  simulator build green. Both guards green.
- Android (throwaway AVD `veyq_p3`, deleted): the folder's More (Download, Rename, Set cover | Move to…, Copy to…,
  Report, Delete folder), the move sheet at the folder's place ("It is already here.", the folder itself "the item
  itself"), moved into Reisen and back with Undo (read back), the undo's snackbar ended by the core's window (logcat: the
  offer to the snackbar's end 4.8 s after the recomposition), a folder's copy ("What does not come along" with its
  lines) and a Pot's (its two losses), both trashed after, a folder's download through the system's save dialog
  (`App folder.zip` read back with `unzip -l`).
- Linux (container, Broadway on 8097, headless system Chrome): move into Reisen with the toast and its Undo, back to the
  root through the navigation view's back and undone (log `move undo: Said(None)`), a folder's and a Pot's copy, no
  room under the held quota ("…for this copy (75 bytes)…", Copy here insensitive), a folder's download through GTK's
  save dialog read back from the container; en/light and de/dark ("Hierher kopieren", "Rückgängig").
- Apple: the throwaway iPhone 18 Pro's signed build installed and signed in (`devlogin.py`) up to the offer to stay
  signed in; the simulator tool's access was not granted and `simctl` cannot tap, so nothing past the offer was driven
  on Apple — the sheet's rules are the core's tests', the Swift its compile.
- Not met: the Windows pass (see the status).

### Phase 4 — Share links and the re-key's resume: `ShareSheet`, R4's tests, the core-run resume

Android and Apple thinned.

**Done when:**
- `commonTest` covers mint, edit, renew, reactivate and delete over a played server, each bitten.
- The resume runs at tenant open in the core (`SubtreeRekeyLiveTest` green with the app models no longer starting it).
- Bridge + both desktops' pass: list, mint (copy to the clipboard; Windows' share sheet once), edit, renew, off/on,
  delete. A link opened in the web's guest page after mint and after renew (the old one opens nothing).

### Phase 5 — Report and cover: `ReportSheet` with the core's privacy URL; the cover's spec in the core; the desktops' picker, crop and encode

**Done when:**
- `commonTest` covers the report's rules and URL, and the cover's constants and diff, bitten.
- Both apps unchanged.
- Bridge + both desktops' pass:
  - a report sent once (each run leaves one for the dev moderators: once, not in a loop), the privacy link opened;
  - a cover set from a picked picture, cropped, read back by `CoverCropLiveTest`'s checks, and removed;
  - de/en, light/dark.

### Phase 6 — The docs

- ADR 0127's Consequences: the screen models moved.
- ADR 0123's Consequences: the desktops' header.
- `src/mobile/CLAUDE.md`: the models, the bridge calls, the traps.
- native-apps: the rows and § Open.
- The CR's harvest, then the CR is deleted.

**Done when** the trust-chain guards are green.

## Not in this CR

- **CR 3b, Expenses whole:** the entry editor's screen model (the largest move left: the form, the rate fill, its
  refusals), a Pot's writes, settle-up, history, receipts with PDF render, people and settings, Pot create, copy and the
  live poll on the desktops.
- Depot's and Memories' kinds' pages and their own header doors (their CRs; they reuse this header).
- Drag and drop (Depot's CR, its first consumer).

## Owner questions

None open. D4 unifies the move's undo to the core's 6 s window, technical and open to the owner's veto. Linux's "share"
is copy, since GNOME has no share sheet (D3).

## Retrospective

### Phase 1 (2026-11-11)

- **Two Apple defects the pass found.** (1) The first Apple thinning followed the header through a box started on
  appear; a rename hands the view another header (keyed by name), the box kept the old one, and Report vanished from the
  menu until the page was reopened. A view observing a core model it does not own needs a watch the owner holds (now
  `AppModel.headerRevision`), not one tied to the view's lifecycle. (2) Pre-existing: "Delete forever" in Apple's space
  trash sent nothing — the confirmation dialog's binding ran `cancelPurge()` before the button's action, so `purge()`
  found no row. Fixed in Swift by asking about the row again in the action; a core `purge(row)` would make it
  structural (Phase 2's bridge call could take the row too).
- **The apps disagree on a guest's folder removed from a drop box's page**: Apple's notice offers Undo, Android says
  nothing. Not a header rule; both kept as they were (now both through `NodeHeaderModel.trash`). Worth one rule later.
- **Simulator taps**: `tap` with `duration: 0.15` registered reliably where a plain tap was often lost; a 6 s window
  is out of reach of the tool's round trip, so timed flows are proven on Android and in `commonTest`.
- **Deviation:** the report's offer is cached only once read; a failed read is asked again by the next header (both
  apps kept a failure as "not offered" for the whole tenant). Report is a legal duty, so the safe side.

### Phase 2 (2026-11-11)

- **An answer can overtake a watch's update.** Linux's first trash toast had no Undo: the host read the offer from the
  watched state, whose update arrived after `Header.Trash`'s answer. The answer now says `undoable` (as Apple reads
  `header.current.undo` after the trash), and a host ends the Undo only on the state's transition from offered to not,
  so an update from before the trash ends nothing.
- **A wire class named `Action`** clashed with `System.Action` in the generated C# (`PlanAction` now): a nested wire
  class's simple name is the hosts' type name; avoid the platforms' common names.
- **A server instant is no ISO day:** the trash rows showed `2026-11-11T16:46:58Z` raw through `said::day`; both desktops
  now say an instant's local day (`instant_day`, `InstantDay`), as Android and Apple do.
- **Open:** a Pot opened from the sidebar (`openAtTop`) and then trashed stays the open object — `ShellModel.trash` goes
  back only where `canGoBack`, and the Pot model is closed — so the page shows "Loading" until another place is chosen,
  also after Undo. It is the core's rule, on every app; a fix (back to the application's list) is a core change for the
  owner to place.
- **Deviations:** headers and trash sheets are watched by an id the bridge holds (`Watched.Header(id)`,
  `Watched.Trash(id)`), as Pots are (D2's own wording), not by ref or scope: a rename changes the ref, and a header must
  outlive its page while its Undo stands. The header's state carries no undo deadline: the host shows Undo until the
  core's state ends it, so there is no second clock. The `Trash.Type`/`Retry` pair carries the new name through the core
  (its `retryable` rule) rather than a `Restore(row, name)`.

### Phase 3 (2026-11-11)

- **The two apps disagreed beyond D4 once, and the core settled it:** Apple's notice offered Undo after a guest's
  folder was moved out of a drop box, which always failed (nothing brings it back into the drop box; the server refuses
  the placement). The core offers no undo for a move that ended a guest's folder; Apple shows its notice without Undo,
  Android (whose drop-box page said nothing) is unchanged.
- **Decrypting on the main thread:** the copy's preview and storage walk ran in Android's `LaunchedEffect`, as the old
  app model's did, and the slow emulator stopped answering; a screen model that decrypts runs off its caller's thread
  (`withContext(Dispatchers.Default)`).
- **An emulator under load is no clock:** a screencap took ~6 s on the swiftshader AVD, so a 6 s toast is measured from
  logcat timestamps, never from screenshots.
- **`DepotLiveTest`'s storage check read only `<repo>/.run/.env`**, which a worktree lacks: it reads `VEYQ_ENV_FILE`
  first now. Its quota hold is what a parallel pass needs (`VEYQ_LIVE_NO_ROOM` in the bridge test, the desktops' copy).
- **The bridge test's own leftovers:** its clean-up first purged every "App move <n>" row in the trash, which also took
  two Pots `VEYQ_LIVE_MOVE` leaves trashed on purpose; it now purges only folders of its own name form.
- **Windows' separator:** the folder's More lacked the separator its Pot's had (Phase 2's shot); the bar now builds its
  secondary commands from the plan's groups (`HeaderPlan.Secondary`, tested). Its look on the VM is unverified until
  the pass runs.
- **Deviations:** a Pot's copy is found by the bridge among the Pot models the host opened (no `pot` field on
  `Move.Open`); the sheet's UUIDs come from the suite (`NodeCreate.uuid`), not the platform's; `Answer.Downloaded`
  carries `written` so a host never reads a word's key for its severity.
