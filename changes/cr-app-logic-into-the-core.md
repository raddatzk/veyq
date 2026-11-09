---
id: cr-app-logic-into-the-core
title: Every rule the two app models write twice moves into the Kotlin core; Android and Apple become thin UIs
status: in-progress
satisfies: []
adrs: ["0127", "0123", "0122"]
invariants:
  - behaviour stays as it is on both apps, but where the two differ today (§ Differences, each settled once here)
  - no rule is moved without the commonTest that pins it; a slice that thins a platform is proved on its device
  - no key material and no plaintext leave the core's session for a platform beyond what it shows today
  - no new feature lands in an app model while this CR runs (ADR 0127 decision 6)
---

## Goal

ADR 0127 decision 1: everything but drawing and the device belongs to the core. Today `android/…/AppModel.kt` and
`apple/Veyq/AppModel.swift` (~1,500 lines each) and the screen files around them hold the same rules twice. This CR
moves them, one slice at a time, into core screen models (decision 2: state as a flow and a watch, actions as
functions, tested in `commonTest`), and leaves each app model with only pickers, decoders, file I/O, image caches and
observation. Windows and Linux (decisions 4 and 5) start after it, on the models it leaves.

## Slices

Each slice ends green (`:core:jvmTest :core:macosArm64Test`, `:android:assembleDebug`, both Xcode builds, the guards)
with both apps behaving as before on a device for what it touched, and commits on its own.

1. **Done 2026-11-09.** **Notices as keys.** A core `Notice(key, args, action)` and one function per outcome table: trash, restore and
   purge, move, rename, copy (Pot, folder), create folder, folder upload, a Depot item settled (exists → replace,
   stale → replace anyway), an upload item's problem, a link minted or changed, sign-in reasons, a Pot write and a
   Pot's creation. Both apps look their words up by key (their catalogs for now; decision 3's one catalog is slice
   10). Android's screen tables and Apple's model sentences go.
2. **Form done 2026-11-09** (`LinkForm`, `ShareWords`; the actions move with the shell, slice 4). **Links and re-keys.** `LinkForm` (the new-link and edit rules: password required, minimum length, keep/remove on
   edit, the last allowed day, end of day, write-only for a drop box) and the link actions with the re-key resume.
3. **Done 2026-11-09, with slice 4's lists and navigation** (`navigation/ShellModel.kt`: favorite, trash and its undo, the trash sheet, rename, move, new folder, copy; a page whose node went leaves, a folder on the path follows). **Node actions.** Favorite, trash with its undo, restore, rename (and the move a rename may imply), move with its
   way back, copy, create folder, report; the header's rights input (what a kind shares, covers, copies, downloads).
4. **Lists and navigation done 2026-11-09 (`ShellModel`); left: row details as data, the covers' index, "New", the link actions.** **Shell.** The lists (`Raw`, rebuild, row details as data), navigation, the drawer, refresh after a write, the
   covers' index (decoding stays native), search's name filter, "New", picking a place for shared files.
5. **Done 2026-11-09** (`expenses/ShareInbox.kt`). **Share-in.** The incoming/taken state machine and the one fitting rule; readers stay native.
6. **Load, live poll (Swift's own loop gone), refresh, writes, creation, rates, receipts and copy done 2026-11-09 (`expenses/PotModel.kt`); left: the minor units table, entry rows as data.** **Pot.** Load, the live poll as a core watch (Swift's own loop goes), writes, creation (its parent from the shell),
   exchange rates, receipts, the minor units of a currency (one core table), entry rows as data.
7. **A file's view (`FileView.open`, both apps; the drift settled: core decides and reads) and a settled upload's offer (`DepotFiles.offerFor`, no test yet: its jobs need a session) done 2026-11-10; left: file facts as data, the uploader's wording, drop boxes, send-a-copy.** **Depot.** Files and their facts, uploader, the file view's decision and reads, uploads and their settling, drop
   boxes, send-a-copy's state.
8. **Memories.** Gallery, chapters, details rows and uploaders as data, the upload's refresh rule.
9. **Session.** Start and resume, sign-in with the trust fallback, unlock, switching, sign-out, managed servers, the
   per-tenant reset; the platform supplies `ThisDevice` and whether it is the share extension. Last: it touches key
   material; proved by `DeviceTrustLiveTest` and both devlogin passes.
10. **Done 2026-11-09, ahead of its turn** (slice 1 needed its keys, and the Apple app had no German). **One catalog.** The words move into the core's catalog and are generated into `strings.xml` and the String
    Catalog (decision 3); a guard holds that no platform carries a word of its own beyond the generated ones.

## Differences, settled here

- After a trash of the open node: the shell goes to its parent, read anew (Apple's way).
- Shared files fitting a Pot: one rule — `PotShare` with the file count and byte budget Apple applies.
- A file's view: the core decides and reads; the platform decodes and draws.
- A Pot's parent when created from a level: the level shown.
- A currency's minor units: one core table (the web's), not `java.util.Currency` or `NumberFormatter`.
- A drop onto an open Pot: the state machine is the core's; Android's drop stays open (ADR 0126).

## Done when

Both app models hold no rule a `commonTest` could hold; [native-apps](../docs/crosscutting/native-apps.md) § The split
says so; this CR is deleted.
