---
id: cr-apps-gates-in-the-core
title: The apps' gates are the core's alone — drops, name dialogs, a link guest's rights, the identity after a reach ended, no dev host in a release
status: planned
satisfies: []
adrs: ["0154", "0136", "0138", "0150", "0137"]
invariants:
  - "a platform asks the core whether a target is offered and draws the answer; no platform computes a second answer (ADR 0154 decision 1)"
  - "the bridge judges every upload's scope and parent again on the call; a host that skipped the gate is refused"
  - "share view = member view: a guest's rights only remove functions, the same rule on every app (inbox #401)"
  - "no word in an app but from src/mobile/words/words.json"
  - "no pass signs in as the seeded principal: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

ADR 0154 decision 1, the audit's group A — the correctness and security half, first because two of its findings let a
client act on a target the core would refuse. After this CR every drop, every New Folder or rename dialog, a link
guest's Replace and *Add entry*, the shell after a reach ended and the sign-in's server field behave by one core rule on
all four apps, and the bridge refuses an upload into a target the core does not offer.

## Context & constraints

- **Read first:** ADR 0154 (whole), ADR 0138 (what a share or drop becomes), ADR 0150 (a received link's rules in the
  core), `src/mobile/CLAUDE.md` § Prove your work.
- **Order:** before `cr-apps-core-ui-layer`; its bridge phase touches `Bridge.kt`'s upload calls, so it runs after or
  beside — never in parallel with — `cr-app-offline-writes` phase 6.
- **Out of scope:** where New and Trash live, notice lifetimes (the later CRs); a drop's look.

## Measured at refine (2026-11-20)

Branch `claude/core-owns-refine` at `a75188eb8` (= `claude/windows-cr-f6c73c`). `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`, `M=src/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | The core's drop gate: `AddHere.shown` (nothing else open and `answer.mayChoose(place)`), `Level.drop`/`dropOrOffer` (sort, re-check, upload or screen), `ShellModel.dropLevel`. | `grep -rn "mayChoose\|dropOrOffer\|fun shown\|fun dropLevel" $C` |
| R2 | The desktops' own gate (`canCreateHere && creates has UploadFiles`) and their drop on any FOLDER row, the target never judged. | `grep -n "canCreateHere\|can_create_here" $M/windows/Veyq/ShellView.cs $M/linux/src/shell.rs`; `grep -n "OntoFolder\|onto_folder\|kind == \"FOLDER\"" $M/windows/Veyq/HomeView.cs $M/linux/src/home.rs $M/linux/src/drop.rs` |
| R3 | The bridge's upload calls pass `call.scopeId, call.parentId` to `upload`/`screen`/`screenFolder` unjudged; `DepotUploads.upload` checks only online/queues. | `grep -n "UploadFiles\|UploadFolder\|ImportScreen" $M/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Bridge.kt`; `grep -n "fun upload\|fun screen" $C/depot/DepotUploads.kt` |
| R4 | Two diverging drop sorts by file-system type (Windows: one import dialog per drop, awaited; Linux: one per call, fired unawaited); the gallery drop sorted on each platform through `MemoryShare.answer.sort`. | `$M/windows/Veyq/DropSort.cs`, `Drops.cs`; `$M/linux/src/drop.rs`; `grep -rn "MemoryShare.answer\|dropOnto" $M/android/src $M/apple/Veyq $M/windows/Veyq $M/linux/src` |
| R5 | New Folder's one `Notice?` (`createFolder`, `createFolderHere`) beside rename's `Renamed(result, notice)`; four ask-again loops; Apple's `try?` (a throw reads as made), Android uncaught, the desktops log only. | `grep -n "fun createFolder\|class Renamed" $C/navigation/ShellModel.kt`; `grep -n "try? await m.createFolderHere" $M/apple/Veyq/AppModel.swift`; `grep -rn "NameDialog\|folderNaming\|NewFolder(\|ask_name" $M/android/src $M/apple/Veyq $M/windows/Veyq $M/linux/src` |
| R6 | A guest's Replace: Android/Apple header + named, Linux `FileRights` (`facts.canWrite`), Windows role alone; *Add entry*: phones `PotView.canWrite` (named only), desktops also an unnamed READ_WRITE guest. The web offers both to a READ_WRITE guest and asks the name at the write (`features/expenses/potSession.ts`, "a guest names itself first"). | `grep -n "REPLACE\|replace_offered\|READ_WRITE" $M/apple/Veyq/GuestLinks.swift $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/GuestScreens.kt $M/windows/Veyq/ReceivedView.cs $M/linux/src/received.rs`; `grep -n "object FileRights\|writesEntries" -r $C` |
| R7 | After `Reach.Ended` Android and Apple compare the shell's tenant and the shown account by reference in `followEnded`; Linux, Windows and the bridge do nothing. | `grep -n "followEnded" -A8 $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/AppModel.kt $M/apple/Veyq/AppModel.swift`; `grep -rn "Reach\|followEnded" $M/linux/src $M/windows/Veyq $M/bridge/src/commonMain` |
| R8 | The dev host in a release: Android `BuildDefaults.SERVER` in `main`, Apple's unconditional `?? "veyq.localhost:8080"`; the desktops prefill only under a debug build. | `grep -rn "BuildDefaults\|veyq.localhost:8080" $M/android/src/main $M/apple/Veyq` |
| R9 | Tests over these paths (they change meaning). | `grep -rln "createFolder\|dropLevel\|dropOrOffer\|followEnded\|writesEntries\|FileRights" $M/core/src/commonTest $M/bridge/src/jvmTest $M/windows/Veyq.Tests` |

## Decisions

- **G1 — one drop entry in the core.** `ShellModel.drop(target, files)` takes the target the person dropped on — the
  shown level, a folder row, a gallery — judges it (`AddHere` for a level, the same `Place` judged for the row's folder,
  `MemoryShare`/`GalleryModel` rights for a gallery), sorts through the target's `ShareAnswer` (folders out first, a
  file without a path refused by name), and answers one typed outcome: uploaded, offered as an import (one offer per
  drop, the web's), or refused with its notice. The platforms hand files and a target and draw the outcome; `DropSort.cs`,
  `drop.rs`'s sort and the four gallery sorts go. A folder row is a drop target only where the core says so
  (`LevelRow.dropTarget`), never by its kind.
- **G2 — the bridge re-judges.** Every upload, folder upload and import screen judges `(scopeId, parentId)` against the
  same gate as G1 before anything is read; a target not offered answers `Refused(drop_not_here)`.
- **G3 — a name dialog's outcome is typed.** `createFolder`/`createFolderHere` answer `Named(problem: Notice?, notice:
  Notice?)` as rename's `Renamed` does: a `problem` keeps the dialog open with the word under the field, a `notice` closes
  it and is said; a thrown error is the core's to turn into a notice (`folder_refused`), never the platform's. One shared
  shape, `NameAnswer`, serves both.
- **G4 — a guest's rights are `FileRights` and `PotGuest` alone, the web's rule.** Replace is offered where
  `FileRights.replace` holds; *Add entry* where the link is READ_WRITE (`PotGuest.offersEntries`); a guest not yet named
  is asked its name at the write by the core's one naming flow, as the web does. No platform reads a role or a name.
- **G5 — the core follows a reach's end.** `SessionModel` decides what is shown after `Reach.Ended` (the tenant or
  account gone → the next opened one) and the platforms observe it; the bridge watches it for the desktops.
- **G6 — no dev default in a release.** The server field starts empty in a release on every app; a debug build may
  prefill (Android's `src/debug`, Apple's `#if DEBUG`), as the desktops do.

## Phases

- [x] **1 core** — G1, G3, G4, G5. Tests: a drop on a folder the core does not offer (a guest's identity folder) refused;
  one import offer per drop with a ZIP and a folder in it; the gallery drop keeping media and naming the rest; a name
  problem vs a refusal vs a thrown error; a READ_WRITE unnamed guest offered *Add entry* and asked its name at the
  write; a reach ended for the shown tenant moves the shell. · Done when: `:core:jvmTest :core:macosArm64Test` green.
  **Bites:** (a) `dropTarget` true for every FOLDER → the identity-folder case red; (b) `Named` collapsed back to one
  notice → the dialog-stays-open case red; (c) the reach's follow removed → the moved-shell case red.
  **Built** 2026-11-20 (`claude/gates-core`): G1 `navigation/Drop.kt` (`Dropped` File/Folder/NotLocal/Unread,
  `DropTarget` Level(label)/Row(folder)/Gallery, `DropOutcome` Uploaded/Offered/Refused, the one sort `Drops`) behind
  `ShellModel.drop(target, items, depot, left, deletedName)`, its gate `ShellModel.takesUpload(scopeId, parentId)` (G2's
  re-judgement for the bridge) and `ShellModel.dropTarget(row)`; word `drop_not_here`. G3 `navigation/NameAnswer.kt`
  (`problem`, `notice`, `queued`, `keepsOpen`) answered by `ShellModel.createFolder`/`createFolderHere`,
  `NodeHeaderModel.rename` (its `Renamed` gone) and `GuestLevel.createFolder`. G4 `sharing/GuestNaming.kt`
  (`GuestAccess.naming`: `asking`, `request`, `name` → `NameAnswer`, `decline`; word `share_guest_name_empty`),
  `GuestAccess.replaceAsking`, `PotGuest.offersEntries`, `PotDoor.offersEntries`/`named()`, `PotSession`/`PotView.offersEntries`,
  `WriteOutcome.UNNAMED` (the editor stays open). G5 `SessionModel.followed`/`watchFollowed`. Tests:
  `GatesInTheCoreTest` (4), `SessionModelTest` (+2), `GuestDoorsTest` (+1); the offline createFolder cases read
  `.queued`. Bites (a)–(c) seen red, plus one per further new test (`GuestPotDoor.offersEntries` back to
  `writesEntries`; every dropped folder screened; the gallery's folder notices dropped), each restored with `/bin/cp -f`.
  **Deviations:** (1) no `LevelRow` type exists — a row's gate is `ShellModel.dropTarget(row: NodeAt)`. (2) A level's
  batch label stays the platform's drawn title (`DropTarget.Level(label)`); a row's is its folder's name. (3) A refused
  drop hands nothing back, so a phone may still put a level's refused drop into the pick mode. (4) The phones' unread
  items come as `Dropped.Unread(name)` (said by `Notices.notRead`), not a count. (5) A thrown create error also takes the
  account offline (the reach's own rule), so the next create queues. **For phase 2 (bridge does not compile):**
  `Bridge.kt` `createFolder` (line ~237) and `ReceivedNewFolder` (~2221) now get a `NameAnswer` — answer it as
  `Answer.Renamed(problem, notice)`-shaped (keep `queued`); `ReceivedReplace`/`ReceivedNewFolder` read role and session
  themselves — the replace goes through `access.replaceAsking` and `access.naming` needs a wire.
- [x] **2 bridge** — G1's call (`Drop`), G2's re-judgement on every upload call, G3's answer, G5's watch. Tests: an
  `UploadFiles` into a scope the core does not offer refused before any read. · Done when: `:bridge:jvmTest` green.
  **Bite:** the re-judgement removed → that test red.
  **Built** 2026-11-20 (`claude/gates-core`). The wire phases 3–6 use: G1 `Call.Drop(on, items, deletedName)` — `DropOn`
  `Level(label)`/`Row(folder: NodeAt)`/`Gallery(gallery)`, `DropItem` `File(path)`/`Folder(path)`/`NotLocal(name)` (the
  host tells a file from a folder by its file system, nothing more) → `Answer.Dropped(queued, notices, import?, refused)`
  (an offered import held for `Watched.Import`); the shell's gates `ShellShown.dropsHere` (`dropLevel`) and `dropRows`
  (the level's folder ids `dropTarget` offers). G2 `Upload.Files`, `Upload.Folder`, `Import.Screen`,
  `Import.ScreenFolder` judge `(scopeId, parentId)` through `Tenant.takesUpload` (`ShellModel.takesUpload`; fail-closed
  for a tenant without a shell) before a path is opened or walked → `Refused(DROP_NOT_HERE)`. G3 `Answer.Renamed` became
  `Answer.Named(problem, notice, queued, keepsOpen)`, answered by `Header.Rename`, `Shell.CreateFolder` and
  `Received.NewFolder`. G4 `Received.Replace` goes through `GuestAccess.replaceAsking` (no role or session read in the
  bridge; it answers once the naming did), `ReceivedShown.naming` (`GuestNaming.asking`) with `Received.NamingSave(name)`
  → `Answer.Named` and `Received.NamingDecline`. G5 the bridge collects `SessionModel.followed` and opens that tenant as
  a sign-in's. The upload calls moved into `Bridge.uploadCall` (the dispatch's method hit the JVM's size). Tests
  (`DispatchTest`): `anUploadCallIntoATargetTheCoreDoesNotOfferIsRefusedBeforeAnyRead`,
  `aDropHandsTheItemsAndTheTargetToTheCoresOneDrop`, `aReachThatEndedForTheShownAccountMovesTheBridgesTenantToTheNextOne`;
  `ReceivedBridgeTest.aSignedOutGuestReplacesTheFileOnScreen` rewritten for the naming at the write (declined, empty name
  kept open, named). Bites seen red, each restored with `/bin/cp -f`: the re-judgement removed; the `followed` watch
  removed; `replaceAsking` back to a session check + `replace`; `naming` not carried; a dropped folder not walked; a file
  the drop did not keep not closed. **Platform code** changed only to compile with the renamed answer: Linux `header.rs`,
  `shell.rs`, `received.rs` and Windows `NodeHeader.cs`, `ShellView.cs`, `ReceivedView.cs` read `Answer.Named` where they
  read `Renamed`/`Said` (problem → ask again, notice → said). **Left to phases 3–6:** nothing calls `Drop`, the naming
  dialog or `dropRows` yet; `Received.Name` (the explicit naming, WRITE_ONLY's uploader name among it) still branches on
  the role in the bridge; `dropsHere`/`dropRows` are held by no bridge test (no played tenant has a shell) — the
  platform phases' passes show them.
- [x] **3 Android** — the gallery and level drops through G1, the name dialogs through G3, the guest gates through G4,
  `followEnded` gone, `BuildDefaults` into `src/debug`. · Done when: `:android:assembleDebug` and
  `:android:compileReleaseKotlin` (the release variant, `src/release` exists; remembered, not run at refine), the words and guidelines guards green; `grep -rn "veyq.localhost" android/src/main` empty.
  **Built** 2026-11-20 (`claude/k-android`): every drop is `AppModel.drop(DropTarget, ShareIn.Drop)` over
  `ShellModel.drop` — the level (`DropTarget.Level`, a `Refused` one left to the pick mode, deviation 3 of phase 1), a
  folder row (`Modifier.rowDrop`, attached only where `ShellModel.dropTarget` says), the gallery (`DropTarget.Gallery`);
  `ShareIn.dropOf` hands each clip item as `Dropped.File` or `Dropped.Unread(name)`; `dropOntoLevel`/`dropOnto` and the
  app's `MemoryShare` sort gone (an `Offered` never comes: the phones' `DepotUploads` offers no import). New Folder (shell and
  a guest's level) draws `NameAnswer` (`NameAnswerDialog`: a problem keeps it open, a notice is said); a guest's Replace is
  offered where `FileRights.replace` holds and runs `GuestAccess.replaceAsking`; `NamingAsked` draws `GuestNaming.asking`
  (save `name`, cancel `decline`), the explicit naming line shows where `GuestNaming.offered`; *Add entry* reads
  `PotView.offersEntries`; `followEnded` replaced by observing `SessionModel.followed`; `BuildDefaults` in `src/debug`
  (the dev host) and `src/release` (empty). **Core added** (minimal, other platforms may want it): `GuestNaming.offered`
  (no app reads the link's role for the naming line), asserted in `GuestDoorsTest` and bitten (always true → red).
  Measured: `./gradlew :android:assembleDebug :android:compileReleaseKotlin` green, `:core:jvmTest :core:macosArm64Test`
  green, `validate_mobile_platform_guidelines.py`/`validate_mobile_words.py` OK, the `veyq.localhost` grep empty.
  **Pass** (throwaway API 36 emulator, deleted after; throwaway account `--with-member`, erased): New Folder with a taken
  name — the dialog stayed open with *Something with this name is already here.* under the field; a READ_WRITE link of
  the owner's folder opened in the app, unnamed: *Replace…* offered on its file, the pick asked *What is your name?* at the
  write, named, the upload worker ran (logcat `UploadWorker` SUCCESS). **Not clicked:** the four drops (a drag from
  another app needs split screen, not drivable headless over adb), a guest's *Add entry* asking at the save, New Folder
  refused offline (offline it now queues), the release build's empty server field (compiled, not installed).
  **Deviations:** a guest's level New and a guest's gallery *Add* still show only once named (`GuestLevel.writes &&
  named`, `canAdd = session != null`): their core writes need a session, not the naming flow — inbox row.
- [ ] **4 Apple** — the same; `try?` gone; the prefill under `#if DEBUG`. · Done when: both Apple builds green.
- [ ] **5 Linux** — the drop through the bridge's `Drop`, `drop.rs`' sort gone, the folder row's drop from
  `dropTarget`, the dialogs and guest gates likewise, `file_page`/`header::say`'s log-only paths replaced by said
  notices. · Done when: `python3 linux/run.py test` and `build` green.
- [ ] **6 Windows** — the same; `DropSort.cs` gone; `ReceivedView`'s role checks gone. · Done when: `python3
  windows/run.py build` green on the VM.
- [ ] **7 the passes** — § Passes; results into ADR 0154's Consequences.
- [ ] **8 harvest** — ADR 0154's `Built:` (decision 1); `native-apps.md`; `src/mobile/CLAUDE.md` § Traps loses any line
  this removes; inbox #1039 measured again on the Mac and closed or narrowed. · Done when: every guard but guard_gaps green.

## Passes

Each a throwaway, erased after. Android, Linux and Windows: a ZIP and a folder dropped together onto a level (one
import offer); a file dropped onto a folder row and onto a guest's identity folder row (refused, said); New Folder with
a taken name (stays open, the word under the field) and offline-refused (closes, said); a READ_WRITE link opened unnamed:
Replace on a file outside an identity folder, *Add entry* asks the name at the save. Apple: the same on the Mac (drop)
and iPhone (dialogs, guest), or named open. A release build of Android and Apple opened: the server field empty.

## Owner questions

None — every rule follows the web (G4's named-at-write is `potSession.ts`'s) or a core rule already decided.

## Definition of done

Phases 1–8 checked; every bite seen red and green; the passes run as throwaways and erased; every guard but guard_gaps
green; `git diff --stat` over `src/backend/`, `src/web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
