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

### Phase 2 — Bridge and desktops: the header, pin, trash, rename, New folder, the trash sheet

**Done when:**
- `DispatchTest` and `LiveBridgeTest` (live: pin, trash, undo, restore, purge, rename, New folder, each read back) green.
- A pass on Linux (container) and on Windows (VM) of each action, de/en, light/dark.
- Builds and guards green.

### Phase 3 — Move, copy and a folder's download: `MoveSheet`, the storage check, one undo (D4); the core-run download

Android and Apple thinned.

**Done when:**
- `commonTest` pins each moved rule, bitten: the place's block, the guest folder's confirm, the copy's kind, no room,
  the undo window.
- Both apps unchanged, but for D4's window.
- Bridge + both desktops' pass: move and undo; copy of a Pot and a folder; no room (`VEYQ_LIVE_STORAGE_FIT`'s one-byte
  quota, put back however the pass ends); a folder's download, read back by `VEYQ_LIVE_EXPORT`'s checks.

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
