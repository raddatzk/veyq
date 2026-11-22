---
id: cr-native-core-uis-through-the-registry
title: Apple's, Linux's and Windows' core UIs reach an application only through the registry — inbox #1066
status: in-progress
satisfies: []
adrs: ["0154", "0156", "0079"]
invariants:
  - "behaviour-preserving: no page, sheet, alert or control a person sees changes; a visible change is logged below with its reason"
  - "the core UI names no core application's type, Kotlin export or wire part but through its composition root; validate_mobile_ui_direction.py holds it per platform"
  - "nothing in src/mobile/core/ or src/mobile/apps/ changes (other sessions work there); what the registry lacks is added to the composition with a test"
---

# Apple's, Linux's and Windows' core UIs through the registry (inbox #1066)

**What.** ADR 0154 decision 3: a core UI reaches an application only through the platform's registry. Android holds it
since #1056 (each application's `Holding`, the UI guard's rule (c)). This CR does the same on Apple (its core UI named the
core applications' Kotlin types through the framework), Linux and Windows (their core UIs named applications' parts of the
bridge's wire), and gives the UI guard a rule (c) per platform.

**Done when.** `validate_mobile_ui_direction.py` is green with (c) on for Apple, Linux and Windows, red on the tree before
(measured), bitten per platform, pinned by `validate_guard_gaps.py`; the Apple builds (macOS ad hoc, iOS simulator SDK),
Linux `run.py build`/`test`, Windows `run.py build`/`test`, `:composition:jvmTest`, `:bridge:jvmTest` and every validator
green; the decision log harvested into ADR 0156 and ADR 0154 § Consequences; this file deleted.

## Decision log

Entries continue ADR 0156's log (37 was #1056's hand-off of this item); numbered so they move there as they are.

38. **2026-11-22 — A CR, and its substrate.** Branch `claude/coreui-registry` from `36516924f`, clean but for nothing of
    this work. CR-sized (three platforms, a registry member per platform, a guard rule per platform), so written, built,
    harvested, deleted, as #1056's was. Cost: none beyond the file.
39. **2026-11-22 — The classification.** A read-only sweep (an Explore agent) classified every application-shaped name in
    each core UI: an application's type or wire part the core UI holds or drives (moved), a platform or composition type
    that only sounds applicative (`ShellModel`, `AddHere`, `DropTarget`, `QueuedOpen`, `MusicPlayer`, `MapServices`,
    `UploadQueue` — kept), a kind literal or an action's name (kept: the guard's documented limit, as Android kept
    `"DEPOT_DROPBOX"`). It found what the inbox row did not: `AppleMemories` and `AppleDepot` are application objects
    (`…mobile.memories`, `…mobile.depot`) whose generic helpers (watch a state, a picked file's source) the core UI used
    everywhere, and `AppleSessions.openReceipt`/`AppleAudio.floatWav` are application extensions. Measured with the new
    guard on the tree before (a throwaway worktree of `36516924f` with this CR's guard): **200 violations** — Apple 184 in
    19 files (`AppModel.swift` 118, `AppModelHeld.swift` 20, `GuestLinks.swift` 14, …; 2 of them Depot's UI naming
    Memories' `AppleMemories`, rule (b)), Linux 9 in 4 (`home.rs`, `receipt_viewer.rs`, `said.rs`, `uploads.rs`), Windows 7
    in 3 (`RowDates.cs`, `Uploads.cs`, `ReceivedView.cs`). After: 0.
40. **2026-11-22 — Apple's mechanism.** Options: (a) a base class `Holding` as Android's; (b) a protocol `Holding` with its
    defaults in an extension, each application's an `@Observable final class`, registered as `ApplicationUi.holding: (AppModel)
    -> any Holding`, built once (lazily, at first use) in registration order (`Registry.registered()`), reached by the
    application through an extension it declares (`model.memories`, `model.depot`, `model.expenses`) over `AppModel.held(T.self)`
    — the type as the key, Android's `HoldingKey`. Chosen (b): `@Observable` on a subclass of a plain class is the macro's
    weak spot, and a protocol's requirements dispatch to the conformer as an abstract class's open members do; SwiftUI
    observes each holding's own stored properties, so views redraw as before. Evidence: measured, both Apple builds green.
    Cost: `AppModel` opens `refreshLists`, `readDetails`, `syncInbox`, `galleryMusic`, `Picked`, `levelLabel`, `sharedDir`
    as internal and gains `setRowDetail`, `shareTaken`, `holdingsOpen`, `levelUploads`, `deriver`, `editing`.
41. **2026-11-22 — The tenant's moments on Apple.** Android's hooks, the same names: `leave` (before a tenant is shown:
    Memories closes its gallery and the lightbox windows' galleries, Depot its file page), `shown` (Expenses' `PotModel`,
    Depot's `DepotUploads`, Memories' `MediaUploads` handed over or closed as before), `locked` (Memories' curation dialogs),
    `signedOut` (Depot's uploads and file, Memories' gallery and media; the Pot stays as before), `listsRead` (the file page
    reloads). `show(_:)` now asks `leave` before the shell is built and `shown` after; the maps, the share's handoffs and the
    music, which the old `openMedia` called, are core's and run after every `shown`. The two sign-out blocks are one
    `closeTenant()`. Evidence: measured by reading each removed line against its hook (`git diff` of `AppModel.swift`).
    Cost: an open gallery closes a moment earlier in `show` (before the shell, not after the uploads) — nothing draws between.
42. **2026-11-22 — The flows Apple's core asks.** `addHereTarget` (Memories' gallery on screen, else the level),
    `addHere` (Memories a gallery, Depot a level, each in the app and in the share extension; the holding ends the share
    through `AppModel.shareTaken`), `drop` (Memories a gallery, Depot a level or a row; none answering, the composition's
    drop with no uploads), `pickUp` (a handoff's Memory or folder), `takesDrop` (an open Pot), `moveSheet` (a Pot's copy by
    the open Pot, every other kind's with `pot: nil`, as Android's entry 32 measured), `trashed`, `openQueued` (a held Pot
    entry), `adopt`/`release` (a link's gallery, file and Pot held in the member's place), `editing` (Expenses' editor open
    over a guest's page), `levelUploads` (Depot's tray and New's uploads), `deriver` (Memories' `MediaDeriver`; a Mac's
    Depot import and a link guest's upload ask it). Two answers differ from before by reasoning: a level's *Add here* says
    no `MediaUploads.notTaken` (a level takes any file, `FolderShare.answer`'s intake is `{ true }`, so its refused count is
    0 — Android's entry 32); the handoffs are picked up once the shell is built (`shellModel != nil`) instead of once both
    Memories' and Depot's uploads exist — both are built in `shown`, before any pick-up. Evidence: measured by reading.
43. **2026-11-22 — `ApplePlatform`, the minimum the registry lacked.** The core UI watched states, made a picked file's
    source, read a reader's range, handed derived tiers and walked a dropped folder through `AppleMemories`/`AppleDepot`.
    Options: name them in core through the registry (each a closure — noise for what no application decides); move the
    helpers out of `apps/` (barred: other sessions work there); add them to the composition's `appleMain`. Chosen the
    composition: `composition/…/ApplePlatform.kt` (`watch`, `source`, `urlOf`, `readRange`, `derived`, `found` over its own
    `Entry`/`EntryKind`, so core's folder walk names no Depot enum), held by `ApplePlatformTest` (appleTest). Every Swift
    `CoreState` watch goes through it, an application's too, since `CoreState` takes its `Watch`. Cost: `AppleMemories`'
    `watch`/`source`/`urlOf`/`readRange`/`derived` and `AppleDepot`'s `source`/`found`/`FolderFile` are no longer called
    from Swift — left in `apps/` (not this CR's to touch), a cleanup for whoever next edits those files.
44. **2026-11-22 — What an application presents over the shell.** Depot's uploads' alert (`DepotUploads.said`), "Send a
    Copy…"'s question and share sheet, and a file's Replace picker sat in `ShellView`. Options: core draws them over a
    protocol (it would name Depot's `Said`); the application draws them in a slot. Chosen a slot, `ApplicationUi.shell`,
    drawn in the shell's `.background` (where the uploads' alert already sat on a view of its own); the folder's
    "preparing" overlay and the tray read `LevelUploads.tray` (`TrayShown`, Android's shape). Cost: none seen — an alert
    or a picker presents the same from any view of the window (unclicked, below).
45. **2026-11-22 — The link bar's per-kind actions.** `KindPage.guestActions` (Android's slot): Depot's file download and
    replace (`GuestFileActions`, moved from `GuestLinks.swift`), Memories' music button; drawn where they were, before
    *Keep*, in the same order. Cost: the toolbar group gets them through an `AnyView` (unclicked, below).
46. **2026-11-22 — The small files.** `AppModelHeld.swift` → `Apps/Memories/MemoriesHeld.swift`; `MediaTiers.swift`
    (`MediaUpload`'s spec) and `AssetMeasure` (a Memories protocol) → `Apps/Memories/`, the asset's length staying core's
    `AssetLength` for the debug build's `DevAudio`; `MemoryDateText` and `MapMath.framing` (`MemoryLocation`'s world view) →
    `Apps/Memories/MemoriesFormats.swift`; the Depot tab's icon reads the kind literal `"DEPOT_DROPBOX"` (Android's entry 34).
47. **2026-11-22 — Linux.** The core UI named Memories' dates (`home.rs`, `said.rs`), Depot's Replace (`uploads.rs`) and
    Expenses' receipts (`receipt_viewer.rs`). Moved: a member's and a guest's Replace → `apps/depot/replace.rs` (the guest's
    reached through the page, `Page::act("replace")` on a `GuestFile` wrapper, so `received.rs` names no Depot call); the
    receipt viewer → `apps/expenses/receipt_viewer.rs`, its page rendering staying core's as `core_ui/pdf_render.rs` (a
    file's PDF and the Emergency Kit draw it, with its two tests); a Memory's date text and the ask → `apps/memories/dates.rs`,
    `home.rs` keeping only the rows' lines by node id (`lines_asked`, `lines_arrived`) — Memories' list asked them already
    (`ApplicationList::around`), so no registry member was needed. Left, named: `header::doer`'s action names and kind
    literals (strings, not types — the guard's limit), the generic Refresh's action named `pot-refresh`, the WRITE_ONLY
    upload pages (core kinds, ADR 0154), and `bridge/mod.rs`'s quiet `Answer::PotOpened` (the host's mirror of the whole
    wire, not read). Cost: the dates' stderr line says how many dated, no longer of how many.
48. **2026-11-22 — Windows.** `RowDates` → core's `RowLines` over a new `ApplicationUi.RowLines` (Memories registers
    `MemoryDates.Lines`, which holds the date text); Replace → `Apps/Depot/FileReplace.cs` (`Member`, `Guest`),
    `PageHost.Replace` removed, `GuestHost.Received` and `KindBody.GuestReplace` added so the link's bar asks the page. Left:
    `HeaderPlan.DownloadsByPage`'s kind literals, `ShellView`'s held Pot entry (it holds the composition's `QueuedOpen`
    and calls the page's generic `OpenHeld`), `Words.PotRefresh` as Refresh's label. Cost: the list redraws when a row's
    line differs, no longer when a date's record differs with the same text — nothing visible.
49. **2026-11-22 — The guard.** `validate_mobile_ui_direction.py` rule (c) per platform. Apple: an index of what each
    `src/mobile/apps/<application>` module hands Objective-C (top-level non-private, non-internal classes, interfaces,
    objects, type aliases, a file's `…Kt` facade; top-level extensions as a `.member(` call; `@HiddenFromObjC`/`@PlatformApi`
    none), less the platform's and composition's names; (b) reads it too. Its one exception: `.memories`/`.depot`/`.expenses`
    (an application's holding extension) are `ApplicationId`'s cases as well. Linux and Windows: the wire's serial names,
    owned by prefix (`WIRE_APPLICATIONS`, the rest `WIRE_PLATFORM`; an unclassified dotted prefix is red), read where a file
    names the wire (`wire::`, a variant's `Call::`/`Answer::`/…; on Windows a variant, or a type bare unless the name is a
    prefix alone, which .NET's `File` would match); `ReceiptPages` is shared (`WIRE_SHARED`: Depot's `File.Pages` answers
    with it); Linux's `src/bridge/` is not read. Options for the desktops: the generated bindings' types (no checkout holds
    them: generated at build); the Kotlin bridge's dispatch (names the models, not the hosts' names); the serial names
    (chosen: the wire file is the generator's input). Bites in the real tree, each restored with `/bin/cp -f`: Apple core
    `GalleryModel` → red (c); Apple core `.replaceAsking(` → red (c); Apple Expenses `FileModel` → red (b); Linux core
    `Call::FileChooseReplace` → red (c); Linux core `use wire::{MemoryDateShown, …}` → red (c); Windows core
    `Call.FileChooseReplace` → red (c); `GalleryModel` in a Swift comment → green. Cases 118–125 in
    `validate_guard_gaps.py`. Limits, documented in the docstring: a concatenated nested name (`GalleryModelState`), an
    extension property, a wire type carried from an application's package as a field (`PotView.SettleRow`), a prefix-less
    name starting with a platform prefix (`PersonMemoriesShown`).
