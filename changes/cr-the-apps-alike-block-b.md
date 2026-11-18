---
id: cr-the-apps-alike-block-b
title: The apps alike (V2 block B) — the desktops' folder hero and row covers, the phones' top-level share from the core, a drop box and a replaced picture signalled, Linux's foreign currency, save progress and payer row (#901)
status: in-progress
satisfies: ["uc.platform.node-cover", "uc.depot.drop-box", "uc.spaces.set-profile-picture"]
adrs: ["0122", "0123", "0136", "0137", "0081"]
invariants:
  - "the server sees nothing new: the drop box's follow is the conditional `listNodes` the gallery's live poll already sends (ADR 0081), the people's re-read the member list every tenant open already reads"
  - "nothing is asked of the server while no screen shows the drop box (the live poll's visibility gate, core's LivePoll)"
  - "a rule the shells decide is the core's (ADR 0136 decision 1): no platform keeps its own copy of the top-level share's condition"
---

## Goal

Make the four apps behave alike where block B of the V2 order (`docs/inbox.md` § V2 — the apps' order) names a
difference, each fixed where it lives, with the failing test first wherever a harness exists. #885 (Windows without the
WebP extension) is its own CR, `cr-windows-webp-pictures`, because it holds a product question.

## Context & constraints

- Where each gap is written: `docs/crosscutting/native-apps.md` § Open (the desktops' cover, Depot's top-level share and a
  guest's new folder, the bridge's replaced picture, Linux's foreign currency and its upload toasts), inbox **#901**.
- Machine (remembered, `src/mobile/CLAUDE.md` § Prove your work): `ANDROID_HOME=$HOME/Library/Android/sdk`; guards under
  `/usr/bin/python3`; no iOS simulator boots on this Mac, Apple's click is the owner's (#925); dev backend
  `veyq.localhost:8080`, the Vite (and the store's way) `veyq.localhost:8081`; Windows VM passes with own tasks and dir.

## Re-derived (measured at `43918c9b0`)

- **The cover.** The core answers a row's cover for any node it holds (`NodeCover.row(tenant, nodeId, held)`, by node id in
  every scope tree) and a header's for any node (`Header.CoverPicture`). The desktops ask it only for a Memory's row
  (`grep -n 'MEMORY' src/mobile/linux/src/home.rs src/mobile/windows/Veyq/HomeView.cs`) and place the header's hero only on
  a Pot's and a Memory's page (`grep -n 'hero(' src/mobile/linux/src/shell.rs`; `grep -n '\.Hero(' src/mobile/windows/Veyq/ShellView.cs`).
  Android places it on a folder level, a drop box, a Pot, a Memory (and its Music and Map views) and Pot settings
  (`grep -rn LocalNodeHero src/mobile/android/src/main`), and every folder and object row draws a cover where one is set
  (`ShellScreen.kt`'s `LocalCovers`). A folder's level on the desktops already holds the folder's header (`ShellShown.headed`
  is any node on screen but a space's root).
- **The top-level share.** Android (`ShellScreen.kt`) and Apple (`ShellView.swift`) test
  `folder == nil && rules.on && rules.dropBox` inside their own "level" branch, which also excludes a person's page; the
  core's `ShellModel.offersTopLevelShare` (the desktops' `ShellShown.topLevelShare`) does **not** exclude a person's page:
  `Shell.openPerson` keeps `application` and `space` with an empty path, so `topLevelShared` holds there — on Linux and
  Windows the share's button stands over a profile opened from the top level. A defect in the core, fixed with the move.
- **The drop box.** Block A's follow (`GalleryModel.Door.treeMoved` → `TenantSession.treeMoved` → `ShellModel.refresh`) runs
  only in an open Memory's poll. `DropBoxPage` reads anew on each revision of the shell's lists and nothing polls it
  (`grep -n 'live\|LivePoll' src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/depot/DropBoxPage.kt`: nothing).
  A guest's new folder is a node of the scope's tree, so the tree's revision moves with it (ADR 0081).
- **The replaced picture.** `AvatarsModel.peopleRead` has one caller, `load()` at the tenant's open
  (`grep -rn peopleRead src/mobile --include=*.kt --include=*.swift`); the people are read once, in `VeyqAccount.open`
  (`TenantSession.people`, a constructor value). The bridge sends the held pictures' **ids** (`ShellShown.pictures`), and
  both desktops ask again only when that id set changes (`linux/src/avatars.rs` `Held::listed`, `windows/Veyq/AvatarsHeld.cs`).
  So two things are missing: anything that reads the people anew, and a signal when a picture changes under the same id.
- **Money.** Linux writes a currency other than the locale's own by its code (`said::money`); so does Windows, on purpose
  (`Said.Money`: `CurrencyFormatter` wrote CAD "12,50 $" in de-DE, ADR 0123's Consequences, the Windows app). Android
  (`NumberFormat.getCurrencyInstance`) and Apple write CLDR's symbol through ICU (`€1,234.56` under en_US, `CA$` for CAD in
  de), as the web's `Intl` does. ICU is on both desktops: `libicuuc.so.77` in the Fedora 44 image (`docker run --rm
  veyq-linux-dev rpm -q libicu` → `libicu-77.1-4.fc44`), `libicuuc.so.78` in the GNOME 51 runtime and SDK (the
  `veyq-flatpak-aarch64` volume), and Windows' own `icu.dll` (System32 since Windows 10 1903, its C API unversioned; .NET
  itself globalizes through it).
- **The save's toast.** `file_page::saving` puts a save's progress in the shell's `AdwToastOverlay` with `timeout(0)`; the
  overlay shows one toast at a time, so every later toast (an upload's offer or outcome) waits for the save's end. Its four
  callers: `grep -n 'file_page::saving\|fn saving' src/mobile/linux/src/*.rs`. Windows shows a save in an `InfoBar` of its own
  (`ShellView.Saving`), apart from what is said.
- **#901.** A payer is an `AdwComboRow` titled "Person" whose chosen person sits at the row's end, beside the amount's entry
  and the remove button (`entry_editor.rs`, the payers' loop).

## Decisions (technical, settled here)

1. **Covers on the desktops, as on the phones.** Every list row — a folder's, a drop box's and every object's, wherever
   `home::object_row`/`folder_row` (Linux) and `HomeView.ObjectRow`/`FolderRow` (Windows) draw it (Home's level, an
   application's list, Search, a person's page) — draws its kind's icon, replaced by the node's cover once `Shell.RowCover`
   names one (the existing `cover_prefix` / `RowCovers.Prefix`). The header's hero (`Header::hero` / `NodeHeader.Hero`) is
   placed first on a **folder's level** (Home inside a folder) and on a **drop box's page**, as on the Pot's and the Memory's.
   No new call: the core already answers both.
2. **The top-level share is the core's on every platform.** `ShellModel.topLevelShared` is false while a person's page shows;
   Android and Apple ask `ShellModel.offersTopLevelShare(shell)` (through their `AppModel`) instead of their own condition.
3. **A drop box follows the tree while it shows.** `DropBoxPage.live()` (and `startLive()`), the gallery's shape: a
   `LivePoll` at `LivePoll.ITEMS` whose tick asks `Door.treeMoved()` — `ShellModel` answers with `TenantSession.treeMoved` and
   `refresh()` where it moved; the page reads anew on the lists' new revision as now. The bridge adds `Watched.DropBoxLive(box)`
   (a watch with no update of its own, as `GalleryLive`); Linux and Windows watch it while the page stands; Android and Apple
   run it while their screen shows it.
4. **The people are read anew on a person's refresh, and a replaced picture is signalled.** `TenantSession.readPeople()` reads
   the tenant's members again (the open's own read and `People.of`) and keeps them as `people`; `ShellModel.refresh(people =
   true)` — the desktops' Refresh (`Shell.Refresh`) and the phones' pull to refresh — then hands them to
   `pictures.peopleRead`. The other refreshes (an upload's settle, a write, the trees' follow) do not: one extra request per
   person's refresh, never per tick. `AvatarsModel` keeps a **round**, moved with every sync that changed what it holds;
   `ShellShown.picturesRound` carries it, and both desktops' `Held` asks the whole set again when the set **or** the round
   moved. The phones observe the pictures' map and need nothing.
5. **A foreign currency by its symbol on both desktops, from the system's ICU** (CLDR, the data Android, Apple and the web's
   `Intl` write from): `ucurr_getName(code, locale, UCURR_SYMBOL_NAME)` for the locale the money is written in (Linux: the
   `LC_MONETARY` locale's name; Windows: the formatter's language and region). Where ICU answers the code itself (CHF), or
   is missing or fails, the code stays as now. Placed as the locale places its own symbol (Linux: `lconv`'s
   `p_cs_precedes`/`p_sep_by_space`; Windows: `CurrencyFormatter` with the symbol swapped in for the code). Linux links the
   runtime's `libicuuc` (its versioned symbol named by `build.rs` from the library it builds against); Windows calls
   `icu.dll` by P/Invoke. No package is added.
6. **A save's progress on Linux is an `AdwBanner`**, one per running save under the content's header bar (its progress as
   the title, Cancel as its button), as Windows' `InfoBar`; its outcome is a toast. The toast overlay then holds only what is
   said, so an upload's toast is never queued behind a save.
7. **#901** — a payer's row shows its person as the row's **subtitle** (`AdwComboRow:use-subtitle`), under the title
   "Person", so the row's end holds only the amount and the remove button. The dialog's width stays.

## Inventory (locators)

- Row covers and the hero: `grep -n 'fn object_row\|fn folder_row\|cover_prefix' src/mobile/linux/src/*.rs`;
  `grep -n 'ObjectRow\|FolderRow\|RowCovers.Prefix' src/mobile/windows/Veyq/*.cs`; the hero's placement
  `grep -n 'hero\|Hero' src/mobile/linux/src/{shell,drop_box,home}.rs src/mobile/windows/Veyq/{ShellView,DropBoxPage,HomeView}.cs`.
- The top-level share: `grep -rn 'rules.dropBox\|offersTopLevelShare\|topLevelShared' src/mobile --include=*.kt --include=*.swift`.
- The drop box: `grep -rn 'DropBoxPage\|DropBox.Open\|DropBoxOpen' src/mobile --include=*.kt --include=*.swift --include=*.rs --include=*.cs`.
- The pictures: `grep -rn 'pictures\b\|peopleRead\|\.people\b' src/mobile --include=*.kt --include=*.rs --include=*.cs | grep -v '/build/'`.
- Money: `grep -rn 'fn money\|Said.Money' src/mobile/linux/src src/mobile/windows`.
- The save: `grep -n 'saving(' src/mobile/linux/src/*.rs`.
- Site: `grep -rln -i 'cover\|drop box\|refresh' site/src/content/docs/{spaces,depot,de/spaces,de/depot}` — "Give a folder a
  cover" already says a cover shows in the list and on the page; nothing a reader does changes, so no page is expected to.

## Phases

- [ ] 1 core + bridge — decisions 2–4 in the core and the wire, each red first: `ShellModelTest` (a person's page offers no
  top-level share), `DropBoxPageTest` (a moved tree reads the page anew, a `304` reads nothing; the poll ends at close),
  `AvatarsModelTest` (a picture replaced under the same id moves the round; an unchanged set does not), the bridge's
  `DispatchTest`/`WireTest` for `Watched.DropBoxLive` and `picturesRound`. · Done when: `./gradlew :core:jvmTest :bridge:jvmTest` green.
- [ ] 2 phones — Android and Apple ask the core for the top-level share, run the drop box's live poll while it shows, and
  their pull to refresh reads the people. · Done when: `:android:assembleDebug` and Apple's macOS and iOS-simulator builds green.
- [ ] 3 Linux — decisions 1, 3, 4 (host side), 5, 6, 7; `said`'s test red first for `€1,234.56` under en_US; `avatars::Held`'s
  test red first for a moved round. · Done when: `linux/run.py build` green (clippy, tests).
- [ ] 4 Windows — decisions 1, 3, 4, 5; `AvatarsTest` red first for a moved round, `SaidTest` for the symbol.
  · Done when: `windows/run.py build` green (with own tasks and dir).
- [ ] 5 passes — Android on a throwaway AVD (a folder's cover in a row and its hero, the top-level share on the top level and
  not on a profile, a guest's folder appearing in an open drop box, pull to refresh); Linux under Broadway and Windows on the
  VM (the same, plus a foreign-currency Pot, a save with an upload beside it, the payer row; a picture replaced on the web
  shown after Refresh). Apple built, its click the owner's (#925).
- [ ] 6 harvest — native-apps § Open's rows removed or rewritten, ADR 0123's Consequences (the desktops' money, the Linux
  save banner), `src/mobile/CLAUDE.md` where a seam changed (`Watched.DropBoxLive`, `picturesRound`), inbox #901 deleted and
  block B marked done, lessons placed, this CR deleted; every `scripts/validate_*.py` but guard_gaps green.

## Definition of done

The phases' checks green; the passes recorded in ADR 0123's Consequences or native-apps where they bind; no doc keeps a
claim this CR made false (`grep -rn 'only after a refresh\|by its code' docs src/mobile/CLAUDE.md`); no site page affected
(the reader's steps are unchanged), or the page updated EN+DE.

## Retrospective

(filled at the end)
