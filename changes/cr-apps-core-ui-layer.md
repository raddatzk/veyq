---
id: cr-apps-core-ui-layer
title: Each app gets a core UI its applications plug into, as the web's — the frame, header and shared sheets the core UI's, one directory and one registration per application, the direction guarded
status: planned
satisfies: []
adrs: ["0154", "0136", "0079", "0122"]
invariants:
  - "an application's UI depends on its platform's core UI and the Kotlin core, never on another application's UI; the core UI reaches applications only through the one registry (ADR 0154 decision 3)"
  - "the move changes no behaviour: every screen the same before and after, proved by each platform's tests and one pass"
  - "no word in an app but from src/mobile/words/words.json"
---

## Goal

ADR 0154 decision 3 (the owner, 2026-11-20: "in every app a core of UI that the applications hang onto, so they all work
alike and look alike — exactly as in the web"). Today each platform is one flat directory; Android and Apple dispatch a
kind to a `KindPage(header, body)`, Linux and Windows dispatch inline in the shell (Windows a second time for a received
link), and the Linux and Windows pages place the header's hero themselves. After this CR each platform has a core UI
(shell, frame, header, level, rows and tiles, the shared sheets and dialogs, the notice host, New, upload/drop/import, the
tray) and one directory per application that registers its kinds' page bodies and own sheets — the web's
`core/applications/registry.ts` and `features/<owner>` — and a guard holds the direction. **Behaviour-preserving:** the
structure work that changes what is drawn is `cr-apps-structure-from-core-plans`, after this one, so each plan is then
drawn once, in the core UI.

## Context & constraints

- **Read first:** ADR 0154 decision 3; the web's `src/web/src/core/applications/registry.ts` (`KindDescriptor`,
  `registerApplication`), `src/web/src/applications.ts`, `core/node/components/NodePage.vue`;
  `scripts/validate_web_core_imports_no_applications.py` and `scripts/validate_backend_module_graph.py` (the two guards
  this one mirrors); `scripts/validate_mobile_search_names_no_application.py` (the core's one direction guard today).
- **Order:** after `cr-apps-gates-in-the-core` and `cr-app-offline-writes` (both done 2026-11-20; their platform phases
  edited the drop, dialog and tray files this one moves).
- **A move is a move.** Each platform's phase moves files with `git mv` and changes only what the move needs (package
  or namespace, visibility, the registry); a rule change found on the way is filed, not made.
- **Out of scope:** what any screen shows or in which order (the next CR); a platform's look.

## Measured at refine (2026-11-20)

Branch `claude/core-owns-refine` at `a75188eb8`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`, `M=src/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | The web: a registry of `KindDescriptor`s (page `detail`, `header.order`, `list.rows`, `createDialog`, `shareDialog`, `levelActions`, …), one `register*` per application called from `applications.ts`; `NodePage.vue` draws the header and mounts the detail. | `grep -n register src/web/src/applications.ts`; `sed -n 175,302p src/web/src/core/applications/registry.ts \| grep -E "^  [a-zA-Z]+\??:"` |
| R2 | Each platform flat; source files (non-test) Android ~75, Apple ~57, Linux ~64, Windows ~95. | `find $M/android/src/main -name '*.kt' \| wc -l`; `find $M/apple/Veyq -name '*.swift' \| wc -l`; `find $M/linux/src -name '*.rs' \| wc -l`; `find $M/windows/Veyq -name '*.cs' -not -path '*/obj/*' -not -path '*/bin/*' \| wc -l` |
| R3 | The kind dispatch: Android `KindPages.of` (`when(kind)`), Apple `KindPage.of` (in `NodeHeader.swift`), Linux inline `match` in `shell.rs`, Windows an `if/else` chain in `ShellView.cs` and a second in `ReceivedView.cs`. | `grep -n "fun of\|static func of" $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/KindPages.kt $M/apple/Veyq/NodeHeader.swift`; `grep -n "EXPENSES_POT\|\"MEMORY\"\|\"FILE\"\|DEPOT_DROPBOX" $M/linux/src/shell.rs $M/windows/Veyq/ShellView.cs $M/windows/Veyq/ReceivedView.cs` |
| R4 | Pages place the core header's hero themselves (Linux, Windows). | `grep -rn "Header::hero\|NodeHeader.Hero" $M/linux/src $M/windows/Veyq` |
| R5 | Crossings today: Linux Memories → Depot (`file_page::within_edge`, `file_page::saving`), Linux core → Memories (`raster_map.rs` → `memory_page::open_node`), Apple Memories → a Depot word; in the Kotlin core `GalleryModel` imports `depot.FileSave`. | `grep -nE "file_page::\|memory_page::" $M/linux/src/*.rs`; `grep -n "mobile.depot" $C/memories/*.kt` |
| R6 | The Kotlin core's files outside an application package importing one (AppKinds is the named composition root; the rest are the question). | `grep -rln "import me.raddatz.veyq.mobile.\(memories\|expenses\|depot\)" $C \| grep -vE "/(memories\|expenses\|depot)/"` |
| R7 | Which files are an application's (a lower bound by name; the phase's own reading decides). | `find $M/android/src/main $M/apple/Veyq $M/linux/src $M/windows/Veyq -not -path '*/obj/*' \| grep -iE "memor\|gallery\|music\|pot\|entry\|receipt\|settle\|depot\|file_?(page\|screen)\|drop_?box\|contribution\|curation\|map"` |
| R8 | No guard reads a platform's direction today. | `ls scripts \| grep -i mobile` |

## Decisions

- **L1 — the layout.** Per platform a core UI and `apps/<application>` with the core's application ids (`memories`,
  `depot`, `expenses`): Android packages `…android.core` and `…android.apps.<app>`; Apple groups `Veyq/Core`,
  `Veyq/Apps/<App>` (XcodeGen's sources); Linux modules `src/core_ui/` and `src/apps/<app>/`; Windows folders and
  namespaces `Veyq.Core`, `Veyq.Apps.<App>`. A file belongs to an application when it draws only that application's
  kinds or screens; a file two applications use is core UI (the map view, the media viewer, the receipt picture) and
  names no application.
- **L2 — one registration per application, the web's shape.** Each application exposes one `register(registry)` that
  registers, per kind, its page body and its own sheets and dialogs (the web's `detail`, `createDialog`,
  `shareDialog` where it has its own) — nothing the core already plans (header order, creates, share answers stay the
  Kotlin core's `AppKinds`). The platform's one composition root (`Applications.kt`/`.swift`/`.rs`/`.cs`) calls each
  `register`, as `src/web/src/applications.ts` does. The shell, the received-link view and every other dispatch ask the
  registry; Windows' second chain goes.
- **L3 — the frame is the core UI's.** A kind's page is the core UI's frame — the header with its hero, the notices,
  the tray — around the registered body; no application page places the hero or a header itself.
- **L4 — the Kotlin core's direction.** An application package imports no other (`FileSave` moves to `blob/`); the
  core's files outside the application packages reach applications only through `AppKinds` and the registration tables
  it owns. Each of R6's files is moved behind an `AppKinds` hook or named in the guard as a composition root with its
  reason; a hook that needs a design of its own is handed back.
- **L5 — the guard.** `scripts/validate_mobile_ui_direction.py`, the web guard's rules per platform, the applications
  derived from the Kotlin core's application packages (never listed): (a) core UI names no `apps/` symbol outside the
  composition root; (b) an application names no other application's symbol; (c) the Kotlin core as L4. Per language:
  Kotlin and C# by import/namespace and type reference, Rust by `crate::apps::<x>` paths, Swift (one module, no imports)
  by an index of the type names each `Apps/<App>` declares, referenced outside it. A platform's rules are switched on in
  its phase, so the guard is never born red.

## Phases

- [x] **1 core** — L4: `FileSave` to `blob/`, R6's files resolved; the guard with rule (c). · Done when: `:core:jvmTest
  :core:macosArm64Test`, `:bridge:jvmTest` green; the guard green. **Bite:** an `import …mobile.depot.*` planted in a
  `memories/` file → red.
  **Built** (2026-11-20): `depot/FileSave.kt` → `blob/FileSave.kt` (`git mv`, the package and its imports — core, bridge,
  Android; Swift sees the same exported name); `scripts/validate_mobile_ui_direction.py` with rule (c): (c1) no application
  package names another's package or generated client (`api.<app>`), no exception; (c2) core outside the application
  packages names one only in `CORE_ROOTS`, each entry with its reason — `AppKinds.kt` the composition root, and R6's
  sixteen other files plus `offline/KeptRead.kt` (its serializers are the applications' generated models; R6's grep missed
  `api.<app>`) **handed back**: each one's hook behind `AppKinds` is a design of its own (ShellModel's open screen models,
  the one drop and Add here, GuestPage's per-kind models, the notices, the tray, the row details, the link form, the
  folder walk, the four Swift adapters in `appleMain`), not a move; an entry that is missing or names no application any
  more is red, so the list only shrinks; and every core file's `package` is its directory's. `offline/Queued.kt` names
  an application only in a KDoc (masked), so it is no root. Bites, each red then restored green: `blob.FileSave` →
  `depot.FileSave` in `memories/GalleryModel.kt` (c1); `api.expenses.models.PotRevisionPage` in `memories/MemoryShare.kt`
  (c1, the client); `import …memories.GalleryModel` in `navigation/Home.kt` (c2); `session/LinkForm.kt` emptied of its
  application names (a stale root); a CORE_ROOTS path that does not exist; `memories/MemoryShare.kt` declaring the
  package `…depot` (the package rule). Checks: `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest
  :android:assembleDebug -Pkotlin.incremental=false` BUILD SUCCESSFUL; every `scripts/validate_*.py` but guard_gaps green.
- [x] **2 Android** — L1–L3, rules (a) and (b) on for Android. · Done when: `:android:assembleDebug`, its unit tests,
  the words, guidelines and direction guards green. **Bites:** a Memories file using a Pot composable → red; the shell
  naming `MemoryScreen` → red.
  **Built** (2026-11-20). Layout (`src/mobile/android/src/<set>/kotlin/me/raddatz/veyq/mobile/android/`): the root
  package keeps only what the system names by class — `MainActivity`, `MusicService` (`MusicPlayback.kt`), the
  WorkManager workers (`UploadWork`, `OfflineWork`, `BackgroundWork`: a persisted job names its class, so moving them
  would break a queued one across an update) —, the debug/release `Dev*` doors and the composition root
  `Applications.kt`; `core/` (package `…android.core`) the core UI, 46 files; `apps/memories/` (the gallery, curation,
  places, map views, music view, filter bar, contributions, `MemoriesUi.kt`, `PersonMemoriesSection.kt`, and the
  debug/release `DevMemory`), `apps/expenses/` (Pot page, settings, create sheet, entry editor, history, receipts,
  `ExpensesUi.kt`), `apps/depot/` (file page, drop box page, `DepotUi.kt`). Moved with `git mv`; what two applications or
  core used left an application's file for core: the music bar (`core/MusicBar.kt`, the core `MusicPlayer`'s), the
  formats (`core/Formats.kt`: `separator`, `money`, `localDate`, `memoryDateText` — the shell's rows and the person
  section use the last), the PDF opener (`core/PdfDocument.kt`, `OpenPdf`: a file's page, a receipt, the kit).
  The registry (`core/Registry.kt`, the web's `registry.ts`): per kind a `KindPage(header, body, guest)` —
  `header: @Composable (NodeAt, AppModel) -> HeaderContribution?` (what the page contributes to core's header),
  `body: @Composable (ObjectRef, BarTab?, AppModel) -> Unit`, `guest: @Composable (GuestPage, GuestScreen, AppModel,
  SnackbarHostState) -> Unit` (a link's page of the kind) —, and per application an `ApplicationUi(id, list, creates,
  personSection)` — its own drawer list (null: core's arranged list), `creates: Map<AppKinds.Create, CreateDialog>`
  (`CreateDialog = @Composable (Screen.Signed, AppModel, space, folder, onClose) -> Unit`), its section of a person's
  profile drawn under core's frame. `Registry.kind(page, vararg kind)` and `.application(ui)` refuse a second
  registration; `seal()` ends registering, and a lookup before it fails. Each application has one
  `XxxUi.register(registry)`; `Applications.register()` (called first in `MainActivity.onCreate`) registers core's
  kinds (`KindPages.register`: folder, bookmark), then Memories, Depot, Expenses, then seals; `Applications.devPage`
  hands MainActivity the debug Memory harness. The shell's object page and both list panes, Home's New (`creating:
  AppKinds.Create?` → `Registry.create`), a link's page (`GuestPageBody`: Level and NotShown core's, every other
  variant the kind's `guest`) and the person screen ask the registry; KindPages names no kind of an application.
  Left in core, named here: the guest bar's file actions and music action (`GuestFileActions`, `GuestMusicAction`:
  header actions, the header being core's, over core-Kotlin models only — the structure CR's header plan takes them),
  the kind icons and `TREE_PAGES` (kind literals; no rule reads literals yet), `AppModel`'s per-application methods
  (it names the Kotlin core's application packages, not `apps/`). Rules (a) and (b) on for Android
  (`PLATFORMS["android"]`). Bites, each red then restored green: `import …android.apps.expenses.PotScreen` in
  `apps/memories/MemoryScreen.kt` (b); `import …android.apps.memories.MemoryScreen` in `core/ShellScreen.kt` (a); a
  qualified `…android.apps.depot.DepotUi` in `core/AppRoot.kt` (a); `Applications.kt` without `ExpensesUi` (the root
  registers every application); an `apps/music/` file (no application); `apps/depot/DropBoxScreen.kt` declaring `…core`
  (the package rule); `apps/depot/` emptied (an application without UI files). Checks: `./gradlew :core:jvmTest
  :core:macosArm64Test :bridge:jvmTest :android:assembleDebug :android:testDebugUnitTest -Pkotlin.incremental=false`
  BUILD SUCCESSFUL (the app has no unit-test sources); every `scripts/validate_*.py` but guard_gaps green
  (`validate_mobile_words.py`, `validate_mobile_platform_guidelines.py` read `rglob`, so the subdirectories stay read).
  Smoke on a throwaway API 36 AVD (`veyq_coreui`, deleted after) with a throwaway account (erased after): signed in
  (devlogin), Home; New → New pot (Expenses' registered sheet) → the Pot's page (header "In USD", Entries/Overview/
  Settings); New → New Memory (Memories' registered dialog) → the Memory's page (facts, Photos/Music/Map/Contributions);
  New folder → the folder's level; after a relaunch and an unlock the drawer's Expenses and Memories lists (registered)
  and Memories' Map tab. Not run: a received link's guest pages (the registry's `guest`), a Depot file's page (no file
  uploaded: the system picker), and the CR's before/after screenshots (phase 6).
- [x] **3 Apple** — the same. · Done when: both Apple builds green, the guards green. **Bite:** a Memories view using
  `PotScreen` → red.
  **Built** (2026-11-20). Layout (`src/mobile/apple/Veyq/`, XcodeGen's groups from the directories): the root keeps the
  files `project.yml` names by path per target (`VeyqApp`, `ContinuedWork`, `BackgroundSync`, `DevLogin`, `DevAudio`: the
  share extension's excludes) and the composition root `Applications.swift`; `Core/` the core UI, 45 files;
  `Apps/Memories/` (gallery, curation, map view, music view, filter, contributions, `MemoryPlaces.swift` — the facts line,
  date and place editors, the bar's Map tab, the pin map, a photo's map, split out of `MapViews.swift` —,
  `PersonMemoriesSection.swift`, `MemoriesUi.swift`), `Apps/Expenses/` (Pot page, settings, create sheet, entry editor,
  history, receipts, `ExpensesUi.swift`), `Apps/Depot/` (file and drop box pages, `DepotUi.swift`). Moved with `git mv`
  (one commit of pure renames, then the edits); what core or two applications use left an application's file for core:
  the music bar with the lightbox's compact controls (`Core/MusicBar.swift`), the formats (`Core/Formats.swift`: `money`,
  `localDay`, `say(piece:)`, `decimalSeparator`, `MemoryDateText`), `CoreState`, and — named here, as Android's
  `AppModel` keeps its per-application methods — the holders `AppModel` stores for Memories' sheets and windows
  (`Core/AppModelHeld.swift`: `ParticipantsEdit`, `BannerEdit`, `BannerPicture`, `BannerImages`, `LightboxWindowValue`;
  a stored property cannot live in an application's extension). The registry (`Core/Registry.swift`): per kind a
  `KindPage(header, body, guest)` — `guest: (GuestPage, GuestHeld, AppModel) -> AnyView` —, per application an
  `ApplicationUi(id, list, creates, personSection)` — `list: (AppModel, key) -> AnyView` (Memories' answers its Map tab
  on `memories.map`), `creates: [AppKinds.Create: CreateDialog]`, `personSection: (PersonProfile, AppModel,
  PersonFrame) -> AnyView` (core's frame and safety number handed in) —; `Registry.kind`/`.application` refuse a second
  registration (`precondition`), `seal()` ends registering and a lookup before it fails. `Applications.register()` runs
  first in `VeyqApp.init` and in the share extension's `viewDidLoad`: core's kinds (`KindPages`: folder, bookmark), then
  Memories, Depot, Expenses, then seals; on a Mac `Applications.commands`/`.windows` hand the app Memories' lightbox
  command and window (a scene is a static type, not a registry entry). The shell's object page and header, its
  application lists, Home's New (one `.sheet(item:)` over `Registry.create`), a link's page (`GuestPageBody`: Level and
  NotShown core's, every other variant the kind's `guest`; `ObjectRef.guest` the link's ref) and the person screen ask the
  registry; `KindPage.of`'s switch is gone. Left in core, named: the link bar's file and music actions in `GuestLinks.swift`
  (as Android's), `AppModel`'s per-application methods. Rules (a) and (b) on for Apple (`PLATFORMS["apple"]`; Swift by
  an index of each `Apps/<App>/`'s non-private top-level names and its top-level extensions' members, comments and
  string contents masked, an interpolation read as code). Bites, each red then restored green (`/bin/cp -f`): `typealias
  … = PotScreen` in `Apps/Memories/MemoryScreen.swift` (b); `… = MemoryScreen` in `Core/ShellView.swift` (a);
  `model.personMemories(…)` (Memories' `AppModel` extension) in `Core/PersonScreen.swift` (a); `"\(DepotUi.self)"` in
  core (a, the interpolation); `func money(_:)` in `Apps/Expenses/PotScreen.swift` (a name declared by two sides);
  `ExpensesList` in `Apps/Depot/DepotUi.swift` (b); `Applications.swift` without `ExpensesUi`; an `Apps/Music/` file;
  `Apps/Depot/` emptied — and green with the names in a comment and a plain string. Checks: `./gradlew
  :core:assembleVeyqCoreDebugXCFramework -Pkotlin.incremental=false` BUILD SUCCESSFUL, `xcodegen`, the macOS ad-hoc
  `xcodebuild … -destination 'platform=macOS,arch=arm64' … build` and `-sdk iphonesimulator CODE_SIGNING_ALLOWED=NO
  build` (the share extension's target with it) BUILD SUCCEEDED; every `scripts/validate_*.py` green, guard_gaps
  included. Not run: a click-through (a Mac UI pass needs full-screen control, an agent's build only) — unclicked: a
  Memory's, a Pot's, a Depot file's and a drop box's page, the drawer's Expenses and Memories lists and the Map tab,
  Home's New pot and New Memory, a received link's guest pages, a person's profile with Memories' section, the Mac's
  lightbox window and its menu command, the share extension.
- [x] **4 Linux** — the same; `raster_map.rs`' and `memory_page.rs`' crossings resolved through the registry or core
  UI. · Done when: `python3 linux/run.py test` and `build` green, the guards green. **Bite:** `crate::apps::depot::…`
  from `apps/memories` → red.
  **Built** (2026-11-20). Layout (`src/mobile/linux/src/`): `lib.rs` (the module map: `applications`, `bridge`, `core_ui`,
  and `apps { depot, expenses, memories }` inline, so nothing sits under `apps/` outside an application), `main.rs`,
  `bridge/` (the host requests; a host, read as core), the composition root `applications.rs` (each application's
  `register`, then `seal`; called first in `main`), `core_ui/` (48 files with `mod.rs` and the new `registry.rs`) —
  not `core/`: a crate module named `core` makes `core::…` ambiguous with the language's `core` crate —,
  `apps/memories/` (memory page, banner, curation, facts, filter, lightbox, Music and Map views, Contributions, the Map
  tab, `memory_create.rs` — New Memory's dialog, out of `shell.rs` —, `person_memories.rs`), `apps/expenses/` (Pot,
  settings, New pot, entry editor, history), `apps/depot/` (file page, PDF text, drop box); each `mod.rs` holds its
  `register`. Moved with `git mv`; what two sides used left an application's file for core: `saving.rs` (a save's
  banner and toast: a file's, a lightbox's, a track's, a GPX's), `picture.rs` (`within_edge`: a file, a row's cover, the
  lightbox, Contributions), `places.rs` (the bar's places: the Pot, the Memories list, the Map tab), `said::date_text`
  (Home's rows, a person's rows, the facts line); `media_tiers`, `player`, `reader_stream`, `receipt_viewer` (a file's
  PDF, a receipt, the kit) and the uploads, drop and import are core's. `person_page.rs` split: core's frame (avatar,
  name, safety number, the `Person` model) and Memories' section (`PersonSection`). The registry
  (`core_ui/registry.rs`): per kind a `KindPage { body, guest, actions, refreshes, facts_later }` — `body:
  fn(&ObjectRef, &Frame) -> Box<dyn Page>` inside core's `Frame` (window, toasts, the content's navigation, the header
  bar's `start` slot for the page's own creating action — *Add entry*, *Add chapter* —, the header told its facts, the
  title's subtitle), `guest: GuestPage { kind: ReceivedPageKind, body, add_names_first }`, the window actions the
  header's own actions fire (`pot-download`, `file-save`, `file-replace`, `memory-upload`, `memory-music`), Refresh
  standing on it, its header told its facts later —; per application an `ApplicationUi { id, list, creates,
  person_section, queued_open }` (its own list around core's — Memories' places, row dates and Map tab —, New's dialogs
  as `win.create-pot`/`win.create-memory`, its section of a person's page, a held write's *Open* — a Pot entry's
  editor). `kind`/`application` refuse a second registration (a guest variant too); a lookup before `seal()` fails.
  The shell's object page (one `page` slot instead of four), its list, New, Refresh, the header's facts, a queued
  open, the received view's guest pages (Level, the WRITE_ONLY pages and NotShown core's, every other variant the
  kind's `guest`) and the person page ask the registry; R5's crossings: `raster_map.rs` opens a pin through
  `registry::open_node` (the kind's application), `memory_page.rs`' `file_page::within_edge` is `picture`'s, Memories'
  `file_page::saving` `saving`'s, `memories_map.rs`' `pot::places` `places`'. Left in core, named here: the hero still
  placed by each page inside its own scroll (`Page::hero`, the frame hands it; moving it out of the scroll changes what
  is drawn — the structure CR's), `header::action_of`'s and `home`'s kind literals, `home::ask_dates` (`Memories.Dates`,
  a bridge call). Rules (a) and (b) on for Linux (`PLATFORMS["linux"]`): Rust paths resolved per file module
  (`crate`/`veyq`, `self`/`super` chains, `use` groups, a leading `apps`), comments and literals masked (`rust_lexer`).
  Bites, each red then restored green (`/bin/cp -f`): `crate::apps::depot::file_page::FilePage` in
  `apps/memories/memory_page.rs` (b); `use super::super::expenses::pot` there (b); `use crate::apps` in
  `apps/expenses/pot.rs` (b, no own name); `use crate::apps::memories::memory_page::MemoryPage` in `core_ui/shell.rs`
  (a); `use crate::{apps::depot::file_page, bridge}` there (a, a group); `super::super::apps::expenses::register` in
  `core_ui/received.rs` (a); `veyq::apps::depot::register` in `main.rs` (a); `applications.rs` without
  `expenses::register` (the root); `apps/music/x.rs` and `apps/stray.rs` (no application); a copy without
  `apps/depot` (an application without UI files); a comment and a literal naming `crate::apps::…` in core stay green.
  Checks: `python3 src/mobile/linux/run.py build` (clippy `-D warnings`, 105 tests in the SDK + the 5 sandbox skips in
  Fedora, the build) and `run.py test` (1198 + 1 + 1 PASSED) green; every `scripts/validate_*.py` green, guard_gaps
  included. Smoke: `run.py run --no-secret-service --port 8196` started to `session SignIn` with no panic (the
  registry sealed before the shell read it). Not run: a signed-in pass — a Memory, a Pot and a Depot folder, a received
  link — needs a throwaway account, and its creation through the principal's env file was refused in this session;
  the before/after pages are phase 6's.
- [ ] **5 Windows** — the same; one dispatch. · Done when: `python3 windows/run.py build` and its tests green on the VM,
  the guards green. **Bite:** `Veyq.Apps.Expenses` used under `Veyq.Apps.Memories` → red.
- [ ] **6 the passes** — § Passes.
- [ ] **7 harvest** — ADR 0154's `Built:` (decision 3); `src/mobile/CLAUDE.md` gains the layout (one paragraph, pointing
  at the guard); `native-apps.md` § The split; `validate_guard_gaps.py` run once (a new guard). · Done when: every guard
  but guard_gaps green.

## Passes

A throwaway on Android, Linux and Windows (Apple's the owner's or named open): one Memory, one Pot, one Depot file and a
bookmark opened from Home and from a received link; each page's header, hero, notices and tray as before the move
(screenshots of the four pages before phase 2 and after, compared). Behaviour-preserving: no new rule is checked here.

## Owner questions

None — the layout follows the web's and ADR 0154 decision 3.

## Definition of done

Phases 1–7 checked; every bite seen red and green; the passes run and the before/after pages equal; every guard but
guard_gaps green; `git diff --stat` over `src/backend/`, `src/web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
