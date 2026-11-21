---
id: cr-apps-structure-from-core-plans
title: Every screen's places, order, sections and actions come from core plans the apps draw whole — header actions an enum, no whitelist, New, Trash, Settings and sign-in as the web places them, a structure guard
status: planned
satisfies: []
adrs: ["0154", "0136", "0122", "0110", "0022"]
invariants:
  - "a platform draws every entry of a core plan, in its order; presence, order and grouping are the core's, the control's look the platform's (ADR 0154 decision 2)"
  - "a plan's order is the web's, held by a core test that reads the web's own registration"
  - "no word in an app but from src/mobile/words/words.json; each platform's guideline (ADR 0123 decision 9)"
  - "no pass signs in as the seeded principal: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

ADR 0154 decision 2 and the structure half of decision 6 (the audit's group B). After this CR the four apps show the
same places, order, sections and actions on every screen the audit found deciding them per platform: a node's header
(Delete last everywhere, the banner and the bookmark's actions where the plan puts them), a level's New and its tools
(Trash among them), the Settings page, the sign-in's buttons, a level row's and a medium tile's menu, a share link row's
menu. Each is a core plan of enum entries drawn exhaustively in the platform's core UI (`cr-apps-core-ui-layer`), and a
guard refuses a fallback arm or a whitelist over a plan.

## Context & constraints

- **Read first:** ADR 0154 decisions 2 and 6; ADR 0110 (a header shows as many actions as fit); ADR 0122 decision 2
  (the drawer); the web's `src/web/src/core/node/nodeHeader.ts` (`placeCoreActions`, `orderHeaderActions`,
  `partitionHeaderActions`), `core/node/coreNodeActions.ts`, `core/node/levelRow.ts`, `core/node/LevelView.vue`,
  `features/settings/sections.ts`, `views/LoginView.vue`, each application's `register.ts` (`header.order`).
- **Order:** after `cr-apps-core-ui-layer` (each plan is then drawn once, in the core UI).
- **Out of scope:** notices' lifetimes and text formats (`cr-apps-notices-and-formats-from-the-core`); how many header
  actions fit inline (the platform's room, ADR 0110 — Apple's 0 in a folder's toolbar is a look, not a structure).

## Measured at refine (2026-11-20)

Branch `claude/core-owns-refine` at `a75188eb8`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`, `M=src/mobile`, `W=src/web/src`.

| # | What | Where / command |
|---|---|---|
| R1 | The core's lists a UI iterates today (`NodeHeader.plan`, `headerOrder`, `createsWhere`, `DrawerSection.shown`, `barTabs`, `SearchShown`, `SettingsSection.offered`, `ShareSheet.rows`, `TrashSheet.rows`, `ViewMode.LEVEL`, `GalleryModel.headerActions`/`creates`, …). | `grep -rnE "fun (plan\|shown\|offered\|createsWhere\|headerOrder)\b\|val (barTabs\|LEVEL\|creates\|headerActions\|homeCreates\|drawerCreates)\b" $C` |
| R2 | Header action ids are `const val` strings, so every platform has a fallback arm: Android `else -> dialog = id`, Apple `default:`, Linux `_ => format!("win.header-{id}")`, Windows `_ => new SymbolIcon(Symbol.More)`. | `grep -n "const val" $C/navigation/NodeHeader.kt`; `grep -rnE "else ->\|default:\|_ =>" $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/NodeHeader.kt $M/apple/Veyq/NodeHeader.swift $M/linux/src/core_ui/header.rs $M/windows/Veyq/NodeHeader*.cs` |
| R3 | The whitelists: Linux `DRAWN`, Windows `Drawn`, the same 13 ids, missing `banner` and `open-own-page`. | `grep -rnE "\bDRAWN\b\|\bDrawn\b\|IsDrawn" $M/linux/src $M/windows/Veyq` |
| R4 | Windows appends the banner and a kept link's report entries after the plan's last group (after Delete); Linux draws the banner and the bookmark's actions in the page body. | `grep -n "memory-banner-edit\|report" $M/windows/Veyq/Core/ShellView.cs`; `grep -n "banner\|report\|open" $M/linux/src/apps/memories/memory_page.rs $M/linux/src/core_ui/bookmark_page.rs \| head -20` |
| R5 | New: Android and Apple the level's (`createsWhere(isHome=true)`) and the drawer's; Linux one in the sidebar header; Windows one in the content's command bar, hidden on a Pot, Memory or bookmark. The web: the level's head only (`LevelView.vue`); ADR 0122 decision 2: the drawer's New below the switcher. | `grep -rn "createsWhere\|CreateAction\|createMenu\|create_action" $M/android/src/main $M/apple/Veyq $M/linux/src $M/windows/Veyq \| grep -v "^.*//"`; `grep -n "create" $W/core/node/LevelView.vue \| head` |
| R6 | Trash: Android and Apple a level toolbar icon; Linux and Windows an item of the switcher. The web: the level's tools row (tabs left; Select, Trash, the view switch right), gate `trashOffered`. | `grep -rn -i "trash" $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/ShellScreen.kt $M/apple/Veyq/ShellView.swift $M/linux/src/core_ui/shell.rs $M/windows/Veyq/Core/ShellView.cs \| grep -i "icon\|item\|button\|menu" \| head`; `sed -n 747,770p $W/core/node/LevelView.vue` |
| R7 | Settings: nobody draws `SettingsSection.offered()` for the page; four orders (Apple = the core enum; Android security before storage; Linux and Windows add-device before sessions). The web's personal order: appearance, maps, sessions, password, pairing, security, (trust reset), profile, (moderation), erase; then the tenant's sections. | `grep -rn "SettingsSection" $M/android/src/main $M/apple/Veyq $M/linux/src $M/windows/Veyq`; `sed -n 147,192p $W/features/settings/sections.ts` |
| R8 | Sign-in: Android and Apple the link entries first, then Submit; Linux and Windows Submit first. The web: Submit first. | `grep -n "Button\|button" $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/AppRoot.kt $M/apple/Veyq/SignInView.swift $M/linux/src/core_ui/sign_in.rs $M/windows/Veyq/Core/SignInView.cs \| head -40`; `sed -n 513,578p $W/views/LoginView.vue` |
| R9 | Tile and row menus: a medium tile on the web has none (open, a mark toggle); a level row's menu is rename, move, the rest, a separator, report, delete (`levelRow.ts`). The apps: Apple a Mark context menu (+ macOS *Open in New Window*), Windows Mark while marking, Android and Linux none. | `sed -n 24,29p $W/core/node/levelRow.ts`; `sed -n 196,215p $W/core/node/levelRow.ts`; `grep -rn "contextMenu\|ContextFlyout\|MenuFlyout\|combinedClickable" $M/android/src/main $M/apple/Veyq $M/windows/Veyq \| head -20` |
| R10 | Fallback arms in the platforms overall (the guard's first run separates those over plan enums). | `grep -rnE '^\s*else\s*->' $M/android/src/main --include='*.kt' \| wc -l`; `grep -rnE '^\s*default\s*:' $M/apple/Veyq $M/windows/Veyq \| wc -l`; `grep -rnE '\b_\s*=>' $M/linux/src $M/windows/Veyq \| wc -l` |

## Decisions

- **S1 — header actions are an enum.** `HeaderAction` replaces `NodeHeader`'s string ids (the own ids `banner`,
  `open-own-page`, `report-shared`, `music`, `upload`, … among them); `NodeHeader.Plan(buttons, menu, last)` holds
  entries, Delete always in `last`. A kept link's report entries, the banner and the bookmark's actions are entries of
  the plan, placed by `AppKinds.headerOrder` — which a core test holds to each application's `register.ts`
  `header.order` and to `coreNodeActions.ts` (as `MediaTierSpecTest` reads the web). The whitelists, Windows' trailing
  entries and Linux's body buttons go.
- **S2 — a level's plan.** `LevelPlan` (core, per shown level): its New (`creates`), its tools in the web's order
  (Select, Trash where `trashOffered`'s rule holds, the view switch) and its tabs. The drawer's New is
  `DrawerSection`'s entry. Where each sits is Q1/Q2's answer, the same on all four.
- **S3 — Settings is `SettingsSection.offered()`, in Q3's order**, drawn as the page on every app (search already reads
  it). An app-only section (this device, saved keys, offline storage) has its place in the enum.
- **S4 — sign-in is `SignInPlan`**: the entries the gate offers (Submit, Cancel, scan a link, show a link, open a
  link, the kit, …) in Q4's order.
- **S5 — row, tile and link-row menus are plans.** `LevelRow.actions` (the web's `levelRow.ts` order),
  `GalleryModel.tileActions(medium)` (mark where the rights allow; nothing else, as the web), `ShareSheet.rowActions(row)`.
  A platform draws a plan through its own gesture (a context menu, a long press, a flyout); a Mac's *Open in New
  Window* is a platform affordance (ADR 0154 decision 2), drawn beside the plan, named in the plan's docs.
- **S6 — the structure guard.** `scripts/validate_mobile_structure.py`: (a) no fallback arm (`else ->`, `default:`,
  `_ =>`) in a `when`/`switch`/`match` over a plan enum (the enums read from the core's `@Plan`-annotated declarations,
  never listed); (b) no collection literal of plan entries in a platform (a whitelist); (c) no `filter`/`sorted`/
  `Where`/`OrderBy`/`retain` applied to a plan's list in a platform. Compilers carry the rest: Kotlin `when` and Swift
  `switch` are exhaustive without a fallback; Rust `match` too; C# gets `CS8509` and `CS8524` as errors. The bridge
  generates each plan enum into C# and Rust, so a new entry fails the desktops' build until drawn.

## Phases

- [x] **1 core** — S1–S5; the orders' tests reading the web; Q1–Q4's answers. · Done when: `:core:jvmTest
  :core:macosArm64Test` green. **Bites:** (a) Memory's `headerOrder` swapped against `register.ts` → the web-order test
  red; (b) Delete out of `last` → red; (c) `SettingsSection` order changed → its test red.
  **Built** (2026-11-20). `@Plan` (`navigation/Plan.kt`) marks a plan's enum: `HeaderAction` (core's ten, the pages' own
  `UPLOAD`, `MUSIC`, `BANNER`, `REPLACE`, `SEND`, `OPEN_OWN_PAGE`, `REPORT_SHARED`; serial names the web's ids, each its
  `slot`), `LevelTool` (`TRASH`, `VIEW`), `DrawerSection` (`NEW` first), `SettingsSection`, `SignInEntry`, `LinkAction`,
  `TileAction`, `AppKinds.Create`. S1: `NodeHeader.Plan` of `HeaderAction`, `headerOrder` of them; the pages hand their own
  (`GalleryModel.headerActions` with the banner, `FileModel.Header.own`, `BookmarkPage.headerActions`, each with its
  `label`); a kept link's report is its name's (`BOOKMARK_REPORT_NAME`), its share's right after it, the delete last. S2:
  `ShellModel.levelPlan(shell)` → `LevelPlan(creates, tools)` (New where `canCreateHere`, the trash where
  `trashOffered`, the view switch but on the Memories map), `drawerCreates()` and `drawerSections(lists)` (`Lists`' own
  getter is internal now). S3: `SettingsSection` in the web's order with the app-only ones beside their kin and `SIGN_OUT`
  last (`search_words_sign_out`), drawn from `AccountSettings.State.sections(maps, appearance)`. S4: `SignInPlan.entries`
  (Submit, Cancel while adding, show a link, scan one with a camera, the kit's open and scan, *Open a link*). S5:
  `LevelRow.plan(rights)` (`ShellModel.rowPlan(row)`), `GalleryModel.tileActions(row)`, `ShareSheet.rowActions(row)`.
  Tests: `PlansAreTheWebsTest` (jvmTest: each kind's `header.order` from its registration, core's merge order from
  `coreNodeActions.ts`, the Settings order from `sections.ts`), `NodeHeaderTest`, `PlansTest`, `QueuedOverlayTest`'s tile.
  Each bite seen red (`eachKindsHeaderOrderIsItsRegistrations`; five `NodeHeaderTest` cases; `theSettingsSections…`).
  Android and Apple compile on it with their behaviour kept (their own entries now `HeaderAction`s, a `NEW` arm drawing
  nothing until phase 4). **Deviations:** no `SELECT` tool — no app level selects (#1049); a level's tabs stay
  `Shell.barTabs` (the bar's, ADR 0122), no `LevelPlan` field; the account's address sits beside it as `ACCOUNT`, `EMAIL`,
  `PASSWORD`.
- [x] **2 bridge** — the enums and plans on the wire, generated into C# and Rust. · Done when: `:bridge:jvmTest` green.
  **Built** (2026-11-20). `HeaderShown.PlanAction.id` is a `HeaderAction`; `Call.HeaderFacts` takes the kept link's page
  (`bookmark`), whose own entries and words join the plan as a file's and a gallery's do; `ShellShown.level`
  (`ShownLevel(creates, tools)`) and `drawerCreates`, `drawerSections` the shell's (`NEW` first); `SettingsShown.sections`
  (no appearance on a desktop, the maps' consent where `ShellModel.mapsOffered`); `Call.RowPlan` → `Answer.RowPlan` (each
  entry worded) and `Call.SignInPlan(adding, camera)` → `Answer.SignInPlan`; `GalleryRow.tileActions`,
  `ShareShown.ShownLink.actions`; a gallery's creates read as `Create.valueOf` (no filter over the enum). Every enum is
  generated into `bridge.rs` and `Bridge.g.cs` with the wire's ids. Tests: `DispatchTest` (the sign-in's and a row's
  plan; the header's entries as enums), `WireTest`'s samples carry each new type, `SecretFreeSchemaTest`'s gallery row,
  and the core's `ShellModelTest` for `levelPlan`, `drawerSections`, `drawerCreates`, `rowPlan` (bitten: the trash
  dropped → red). Linux and Windows compile with their drawing kept: a plan entry's wire id (`header::wire_id`,
  `HeaderPlan.Id`) feeds their whitelists until phases 6 and 7 replace them; their initializers carry the new fields
  (`bookmark: None`, `sections: vec![]`/`[]`, `tile_actions`, `actions`). `linux/run.py bridge` and `build` green,
  `windows/run.py build` green on the VM (Veyq.Tests 80, PdfWebTest), both Apple builds green — after phase 1's Swift
  named two entries as Kotlin/Native does not: the framework mangles `COPY` to `theCopy` and `DELETE` to `delete_`.
- [x] **3 the guard** — S6, on for the core and the bridge, each platform switched on in its phase. · Done when: the
  guard green; `validate_guard_gaps.py` run once. **Bites:** a `default:` planted over `HeaderAction` in a Swift file,
  a whitelist array in Rust, a `.filter` on a plan in Kotlin — each red alone.
  **Built** (2026-11-20). `scripts/validate_mobile_structure.py` (its docstring the rules and limits) reads the `@Plan`
  enums, their entries and serial names from the core's main sources and each one's name per language (Swift's
  lowerCamel or Kotlin/Native's mangled `theCopy`/`delete_`; Rust's and C#'s the generated names), and the plans' lists
  from the core's and the wire's `List<…>` declarations: (a) no `else ->`/`default:`/`_ =>` in a `when`/`switch`/`match`
  whose arms name a plan's entry — on the core, the bridge and each platform switched on; (b) no collection literal whose
  elements are all a plan's entries, nor one of two or more strings that are all a plan's ids (a registration keyed by an
  entry is none); (c) no filter or sort in the statement that reads a plan's list — (b) and (c) on the bridge and the
  platforms. `PLATFORMS` all off (phases 4–7 switch each on); `--platform` previews one, `--paths` reads the files given.
  Its first run found the bridge filtering `Create.entries` for a gallery's New (now `Create.valueOf`). Bites, each red
  alone and green restored: a `default:` over `HeaderAction` in `apple/…/BookmarkScreen.swift` (`--paths`); an array of
  two `HeaderAction`s in `linux/…/said.rs` (`--paths`; it first went unseen behind a `crate::…` path, now read); a
  `.filter` on `p.buttons` in the bridge (the full run); an `else ->` in `NodeHeader.label` (the core); a `_ =>` over
  `HeaderAction` in a C# switch expression (`--paths`). The preview per platform: Android 7, Apple 6, Linux 5, Windows 6
  — the phases' work. Every guard green, `validate_guard_gaps.py` run once (104 cases hold). **Open:** a Swift `switch`
  over a Kotlin enum is no exhaustive one and needs `default:` to compile (#1050) — Apple's phase needs an exhaustive Swift
  form first.
- [ ] **4 Android** — every plan drawn in the core UI; the guard on for Android. · Done when: `:android:assembleDebug`,
  the words, guidelines, direction and structure guards green.
- [ ] **5 Apple** — the same. · Done when: both Apple builds green, the guards green.
- [ ] **6 Linux** — the same. · Done when: `python3 linux/run.py test` and `build` green, the guards green.
- [ ] **7 Windows** — the same; `CS8509`/`CS8524` as errors. · Done when: `python3 windows/run.py build` green on the
  VM, the guards green. **Bite:** a `HeaderAction` arm removed from the C# switch → the build red.
- [ ] **8 the passes** — § Passes.
- [ ] **9 harvest** — ADR 0154's `Built:` (decision 2, the structure guard); `native-apps.md`; `src/mobile/CLAUDE.md`
  § Each platform's guidelines states what is the platform's (the look) and what is the plan's; the Use pages that name
  where New, Trash or a Settings section is (`grep -rln -i "trash\|papierkorb\|settings\|einstellungen" site/src/content/docs/getting-started site/src/content/docs/de/getting-started`), EN+DE. · Done when: every guard but guard_gaps green.

## Passes

A throwaway on each of Android, Linux and Windows, Apple's the owner's or named open: Home, a folder, a Memory, a Pot, a
file and a bookmark — each header's entries in the web's order with Delete last and the banner where the plan puts it;
New and Trash where Q1/Q2 put them; Settings in Q3's order; the sign-in in Q4's; a level row's menu and a medium tile's
plan. The same screens on the web beside each, compared entry by entry.

## Owner questions

1. **Where New lives.** The web: in the level's head; ADR 0122 (owner 2026-11-08): also the drawer's New below the
   switcher. Today Android and Apple have both, Linux only the sidebar's, Windows only the command bar's (hidden on an
   object). *Recommendation:* both on every app — the level's New where the web puts it and the drawer's per ADR 0122 —
   since each answers a different place (here vs anywhere).
2. **Where Trash lives.** The web: the level's tools row (Select, Trash, the view switch). Android and Apple: the level's
   toolbar; Linux and Windows: the switcher's menu. *Recommendation:* the web's — the level's tools on every app.
3. **The Settings order.** The web's personal order is appearance, maps, sessions, password, add a device, security,
   profile, erase; the apps' four orders begin with the account. *Recommendation:* the web's order, the app-only
   sections beside their kin — this device and saved keys after sessions, offline storage after maps — and the account
   and address (the apps' own *Account* section) where the web's password is.
4. **The sign-in's order.** The web: Submit first, then the other ways in. *Recommendation:* the web's — Submit,
   Cancel, then the link and kit entries — on every app (Android and Apple change).

**Answered (owner 2026-11-20): every question as recommended.**

## Definition of done

Phases 1–9 checked; every bite seen red and green; the passes run as throwaways and erased; every guard but guard_gaps
green; `git diff --stat` over `src/backend/`, `src/web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
