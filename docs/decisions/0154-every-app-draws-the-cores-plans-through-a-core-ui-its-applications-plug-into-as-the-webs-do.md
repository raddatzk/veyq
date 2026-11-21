# ADR 0154 — Every app draws the core's gates, plans, notices and formats through a core UI its applications plug into, as the web's do
- Status: Accepted (owner 2026-11-20: the CRs' owner questions answered as recommended)
- Built: decisions 1 and 3 on every app and decision 6's words and direction guard (2026-11-20, § Consequences);
  decisions 2, 4, 5 and the rest of 6 not yet — the CRs `cr-apps-structure-from-core-plans` and
  `cr-apps-notices-and-formats-from-the-core` build them
- Date: 2026-11-20
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

[ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md) put every rule in the Kotlin
core and left each platform its drawing. The owner's goal (2026-11-20): *the core provides all functionality, the apps
only lay a native UI over it; every app has the same words and fundamentally the same UI structure* — the same places,
order, sections, actions and flows; a control's look follows each platform's guidelines (a Mac sheet where iOS shows an
alert is the same flow). And: *in every app a core of UI that the applications hang onto, so they all work alike and
look alike — exactly as in the web.*

An audit of the four apps on 2026-11-20 (measured at `a75188eb8`; the commands are the CRs' locators) found ADR 0136
held for the screens' models but not around them:

- **Gates re-decided in a platform.** The desktops judge a drop target with their own gate instead of `AddHere.shown`
  and accept a drop on any folder row; the bridge hands a client's scope and parent to an upload unchecked; the drop's
  sort is two diverging copies (Windows one import dialog per drop, Linux one per call), the gallery drop assembled on
  all four. A link guest's Replace and *Add entry* follow four rules (Windows ignores `FileRights`' identity-folder
  rule). After `Reach.Ended` Android and Apple compare identities themselves, the desktops not at all. Android and
  Apple prefill the dev host `veyq.localhost:8080` in a release build. New Folder's outcome is one `Notice?` for a name
  problem and a refusal alike, and four ask-again loops treat a failure four ways (Apple's `try?` reports it as made).
- **Structure decided per platform.** Only a few structures come from core lists (`NodeHeader.plan`,
  `DrawerSection.shown`, `AppKinds.createsWhere`, `Shell.barTabs`, `SearchShown`, `ShareSheet.rows`, …). Header
  actions are strings, not an enum, so every platform has a fallback arm, and Linux and Windows filter the plan through
  a whitelist of 13 ids that silently drops `banner` and `open-own-page` today and every later action. Windows puts the
  banner and the report entries after Delete. New, Trash, the Settings order (nobody draws `SettingsSection.offered()`),
  the sign-in buttons and the bookmark's actions sit differently on each app.
- **Notices and formats per platform.** `Notice` carries no lifetime: Android short or long, Apple the trash window
  reused, Linux toast default or until closed, Windows until closed or 5 s; "copied" is 6 s on all four, the web's
  2 s. Sizes are formatted by four formatters, Windows' 1024-based; dates by four styles (Windows numeric); money by
  three; " · " joins written in some 65 places; three music bars truncate where the core's `MusicQueue.formatLength`
  rounds.
- **No layer for applications.** The web is a core frame with application slots: `core/applications/registry.ts`'s
  `KindDescriptor` gives each kind its page body, header order, rows, create and share dialogs; `NodePage.vue` draws the
  header and mounts the body; `validate_web_core_imports_no_applications.py` holds the direction. The apps are flat
  directories; Android and Apple have a `KindPage(header, body)` dispatch, Linux and Windows dispatch inline in the
  shell (and Windows a second time in `ReceivedView`); a Memories page calls Depot's `file_page` on Linux; no guard
  reads an app's import direction.
- **Guards that cannot see the drift.** `validate_mobile_words.py` reads Android's `res/values*` but no Kotlin, misses
  accessibility hints and values, `Text(verbatim:)`, `SetHelpText`, GTK's `add_response`, and the manifests'
  user-facing text (`apple/project.yml`'s usage descriptions, `linux/flatpak/metainfo.xml`, both English only).

## Decision

1. **A gate is the core's, and the bridge holds it too.** Where the core decides whether something is offered or
   accepted — a drop's or a share's target (`AddHere`), what a picked or dropped set sorts into (`ShareAnswer.sort`), a
   link guest's rights (`FileRights`, `PotGuest`, `MemoryRights`), which identity the shell follows after a reach ended,
   a dialog's outcome — a platform asks the core and draws the answer; it never computes a second answer beside it. A
   call that acts on a target the client names (an upload's scope and parent) is judged again by the core on the call,
   so a host that skipped the gate is refused, not obeyed. An outcome a UI must tell apart is a typed answer (a name
   problem that keeps the dialog open versus a refusal that closes it), never one nullable notice. A release build
   carries no development default.

2. **A screen's structure is a core plan, drawn whole and in order.** What a screen offers and in which order — its
   sections, its actions and their groups, its entries and where they live (New, Trash, Settings, sign-in, a tile's
   menu, a header's buttons, menu and last group) — is a list of typed entries the core hands the UI. A platform draws
   every entry of a plan, in the plan's order, in its own control: how many fit inline, a menu or a sheet, an alert or a
   dialog is the platform's (ADR 0110's room rule included); presence, order and grouping are not. An action or section
   is an enum value, not a string, so each platform's drawing is exhaustive; a whitelist, a fallback arm over a plan's
   entries, or an entry a platform adds of its own is a defect. A platform affordance that is no product structure (a
   Mac's *Open in New Window*, a system share sheet's own rows) is the platform's, named as such in the plan's docs.

3. **Each app has a core UI, and its applications plug into it, as the web's.** Each platform's sources split into a
   core UI — the shell and drawer, the page frame and node header, the level with its rows and tiles, the sheets and
   dialogs every kind shares (share, move, trash, report, name), the notice host, New, the upload, drop and import flow,
   the tray — and one directory per application (`apps/<application>`), mirroring `src/web/src/core` and
   `features/<owner>`. An application registers per kind what the web's `KindDescriptor` gives: its page body and its
   own sheets; the frame, the header and every shared sheet are the core UI's, so an application page draws no frame
   of its own. Dependencies run application → core UI, never back and never application → application; the core UI
   reaches applications only through the platform's one registry, which follows the core's `AppKinds`.

4. **A notice carries its lifetime.** The core's `Notice` names its kind, and the kind its lifetime, the web's: said
   briefly (a success, the web's 4 s), held until closed (a refusal or failure), or held for a window the core ends (an
   Undo, `TRASH_UNDO_MS`). An inline note's time ("copied", "nothing plays") is a core constant. A platform maps a
   lifetime onto its control (a snackbar, a toast, an InfoBar) and holds no duration literal.

5. **What a UI shows as text is formatted by the core.** Sizes, dates and date ranges, lengths, money, and every
   joined or ordered sentence (the " · " lines, a count with its noun) are core functions over the catalog's words,
   the shown string handed to the UI. Each is held to vectors generated from the web's own formatters
   (`shared/formatBytes.ts`, `features/expenses/amounts.ts`, the date styles, `features/memories/memoryDateRange.ts`)
   for English and German, so the apps say what the web says. A platform formats only what it draws for itself (a
   player's running clock is the core's function too).

6. **Guards hold each of these.** The words guard reads every source a platform shows text from — Android's Kotlin,
   accessibility labels, hints and values, dialog buttons, help texts, and the manifests, which are generated from the
   catalog in both languages. A structure guard requires exhaustive drawing of every core plan enum (no fallback arm,
   no whitelist) and a shared plan fixture each platform's tests draw in full. A direction guard per platform holds
   decision 3. A duration literal or a size, date or money formatter in a platform's UI is refused outside a named
   exception.

## Consequences

- ADR 0136 decision 1's line is drawn sharper: "which notice an outcome raises" now includes its lifetime, and "what it
  shows" includes its structure and its text's form. Its Status stands; this ADR extends it.
- Each platform moves its files once (decision 3); the move changes no behaviour and lands before the structure work,
  so each plan is drawn once in the core UI rather than per page.
- Some of the apps' drawing changes where they disagreed with each other and the web (Windows' banner after Delete,
  the desktops' Trash in the switcher, Windows' numeric dates and 1024-based sizes, the 6 s "copied"); each such change
  is listed in its CR and follows the web unless the owner decides otherwise.
- The words guard grows a Kotlin and manifest reader; the structure and direction guards are new, each bitten before
  trusted.

- **As built, decision 1** (2026-11-20). One drop in the core: `ShellModel.drop(target, items)` (`navigation/Drop.kt`)
  judges the target — a level by `AddHere`, a folder row by `ShellModel.dropTarget`, never by its kind, a gallery by its
  rights —, sorts once (one import offer per drop, a folder without a local path refused by name) and answers `Uploaded`,
  `Offered` or `Refused`; Windows' `DropSort.cs`, Linux's sort and the four gallery sorts are gone. Every upload, folder
  upload and import screen through the bridge is judged again by `ShellModel.takesUpload` before a path is opened
  (`Refused(DROP_NOT_HERE)`; it takes files offline where a Depot file queues, ADR 0153). New Folder, a rename and a
  guest's folder answer `NameAnswer` (a `problem` keeps the dialog open, a `notice` closes it, `queued`); a guest's Replace
  is offered by `FileRights.replace`, *Add entry* by `PotView.offersEntries`, and a guest not named yet is asked at the
  write by the core's one naming (`GuestAccess.naming`). The core follows a reach's end (`SessionModel.followed`), the
  bridge for the desktops; a release build of Android and Apple prefills no server. Where a platform still decides: a
  phone puts a level's refused drop into its pick mode (a refused drop hands nothing back); a level's batch label is its
  drawn title; a Memory's *Add photos* and a WRITE_ONLY page still name a guest first through `Received.Name` (#1042), and
  the phones' guest gallery offers *Add* only once named (#1044). Tests: `GatesInTheCoreTest`, `SessionModelTest`,
  `GuestDoorsTest`, the bridge's `DispatchTest` and `ReceivedBridgeTest`; the bridge's `ShellShown.dropsHere`/`dropRows`
  are held by no bridge test (the bridge's played tenants have no shell; the core's gates are).
- **The passes (decision 1).** Each a throwaway, erased after. New Folder with a taken name kept the dialog open with the
  word under the field on Android, Linux and Windows. Android: a READ_WRITE link opened unnamed offered *Replace…* on a
  file and asked the name at the write, then uploaded. **Not run:** every drop on every app — Android needs a window to
  drag from another app (#1045), Broadway carries no file drop from the browser, the Windows VM's WinUI takes none from
  Explorer with UAC off; a guest's Replace on the desktops; *Add entry* asking at the save; the release builds' empty
  server field (compiled only). **Apple:** both builds green, nothing clicked; the Mac's drops (a ZIP and a folder onto a
  level, onto a folder row and a guest's identity folder, onto a gallery; #1039's folder from Finder), its dialogs and a
  guest's link are the coordinator's pass, the iPhone's the owner's.
- **As built, decision 6's words** (2026-11-20). `scripts/validate_mobile_words.py` reads Android's Kotlin
  (`android-literal`), Swift's accessibility hints and values and `Text(verbatim:`, C#'s and XAML's help texts and a
  dialog's three button texts, GTK's alert responses and an entry row's text, and the two manifests that show text
  (`manifest-literal`); its docstring is the rule list and its limits (a literal reaching a shown property through a
  variable stays unseen). The new rules found no literal in any platform's source; the manifests' texts moved into
  words.json (`usage_camera`, `usage_photos`, `metainfo_summary`, `metainfo_about`, `metainfo_preview`, the German
  drafted, the owner's to change), and `generate_words.py` writes them — `--apple-infoplist` the usage descriptions'
  `InfoPlist.xcstrings` and the `InfoPlist.xcconfig` their English stands in, `--metainfo` the Flatpak's summary and
  description in English and German (`generate_words_selftest.py`). Each new rule was bitten on a planted literal.
  **Passes:** the Flatpak's metainfo shown by `appstreamcli get app.veyq.Veyq --details` (AppStream 1.1.3, in
  `veyq-flatpak-builder`, the generated metainfo installed into the container's `/usr/share/metainfo`) in German under
  `LANG=de_DE.UTF-8` and in English under `en_US`; both Apple builds carry the German in `de.lproj/InfoPlist.strings`
  (`plutil -p`). **Open:** a German device's camera and photo prompts — the Mac's and the iPhone's (Apple's usage
  descriptions as shown), Android's and the desktops' (their prompts are the system's, no manifest word) — never opened;
  the Mac's needs full-screen control, which a background agent has not.

- **As built, decision 3** (2026-11-20). Each platform is a core UI, one directory per application and one
  composition root, the web's `src/web/src/core`, `features/<owner>` and `applications.ts`; files were moved with `git
  mv`, and what two applications or the core use left an application's file for the core UI (the music bar, the
  formats, the PDF view, a save's banner, a picture's edge, the bar's places, the rows' date). The layout:

  | Platform | Core UI | An application | Composition root | Left at the root (the system names it by class) |
  |---|---|---|---|---|
  | Android | package `…android.core` (`core/`) | `…android.apps.<id>` (`apps/<id>/`) | `Applications.kt` | `MainActivity`, `MusicService`, the WorkManager workers (a queued job names its class), the debug/release `Dev*` doors |
  | Apple | group `Veyq/Core/` | `Veyq/Apps/<Id>/` | `Applications.swift` (the app's `init` and the share extension's `viewDidLoad`) | what `project.yml` names by path per target (`VeyqApp`, `ContinuedWork`, `BackgroundSync`, `DevLogin`, `DevAudio`) |
  | Linux | module `core_ui` (`src/core_ui/`; `bridge/` is core too) | `apps::<id>` (`src/apps/<id>/`, its `mod.rs` holds `register`) | `src/applications.rs` (first in `main`) | `lib.rs`, `main.rs` |
  | Windows | `Veyq.Core` (`Core/`) | `Veyq.Apps.<Id>` (`Apps/<Id>/`) | `Applications.cs` (first in `App.OnLaunched`) | `App`, `MainWindow`, `Program`, `Words` |

  The registry (`Registry.kt`/`.swift`/`registry.rs`/`Registry.cs` in the core UI) is the web's `registry.ts`: per kind
  a `KindPage` — the body drawn inside the core UI's frame, and `guest`, the page a received link shows of that kind —
  and per application an `ApplicationUi(id, list, creates, personSection)` — its drawer list around the core's (Memories'
  places and Map tab), New's dialogs keyed by `AppKinds.Create`, its section of a person's profile under the core's
  frame. Each application has one `register(registry)`; the root registers the core's own kinds (folder, bookmark),
  then every application, then `seal()`s; a second registration and a lookup before the seal fail. The shell's object
  page, its lists, New, a received link's pages (Level and NotShown stay the core's; Windows' second dispatch in
  `ReceivedView` is gone) and the person screen ask the registry; no `when`/`switch` over a kind of an application is
  left in a dispatch. The desktops' registries carry what their frames ask of a page beside the body (Linux's window
  actions and `facts_later`, Windows' `KindBody`: the hero slot, tabs, the header's commands, `FactsWhenRead`).
  **Left in the core UI on purpose, named:** the header's hero is still placed by each desktop page inside its own
  scroll (a slot the frame fills: lifting it out of the scroll changes what is drawn, the structure CR's); the link
  bar's file and music actions on the phones (header actions over core-Kotlin models); kind literals for glyphs, the
  rows' dates and `TREE_PAGES`; Android's and Apple's `AppModel` methods per application and Apple's holders it stores
  for Memories (`Core/AppModelHeld.swift`: a stored property cannot live in an application's extension); Windows'
  WRITE_ONLY upload page and the bookmark page (core kinds); Linux's `home::ask_dates` (a bridge call).
  **The guard**, `scripts/validate_mobile_ui_direction.py` (its docstring the rules and limits): (a) no core UI file
  names an application but the composition root; (b) no application names another's; (c) in the Kotlin core no
  application package names another's package or generated client (`blob/FileSave` moved out of `depot/` for it), and no
  file outside the application packages names one but `AppKinds.kt` and the `CORE_ROOTS` it lists with their reasons;
  each file's package or namespace is its directory's; an application without a file, a directory that is no
  application, and a root that misses an application are red. The applications are derived from `ApplicationId`. Read
  per language: Kotlin and C# by import, qualified name and namespace (C#'s `global using` and a csproj `<Using>` too);
  Rust by paths resolved against the file's module; Swift — one module, no imports — by an index of each `Apps/<Id>/`'s
  non-private top-level names and its extensions' members. **Blind spots** (the docstring's): a type alias or an `Any`
  handed over and cast, a kind spelled as a literal (no kind rule yet, as the web's guard has), a star import of a parent
  package, reflection, a Rust `#[path]`, `pub use` or macro, Swift's protocol requirements; the bridge module and the
  tests are not read (a host and consumers of both sides). **What remains:** the 17 `CORE_ROOTS` beside `AppKinds.kt` —
  the shell's model holding each application's screen models, the drop and *Add here*, the top level's *Add files
  only*, the rows' details, the notices, the link form, the guest pages' per-kind models, the tray, the kept reads, the
  folder walk and the four Swift adapters in `appleMain` — each a hook behind `AppKinds` that needs a design of its own
  (inbox #1048); the list only shrinks (a root that names no application any more is red).
- **The passes (decision 3).** Behaviour-preserving, so each platform's pass checked that every kind still reaches its
  page through the registry with its header and body. Throwaways, erased after. Android (an API 36 emulator): New pot
  and New Memory (registered creates), a Memory's, a Pot's and a folder's page, the drawer's Expenses and Memories lists
  and the Map tab; a photo uploaded into a Memory; a Depot file's page from Home (header Favorite, Download, More with
  share, replace, rename, send a copy, offline, move, copy, report, delete; the picture and its facts); a person's
  profile (the core's frame with the safety number, Memories' section with List | Tiles | Map); a link to the Memory,
  the Pot and the file each opened signed out as its guest (the gallery with the photo; Entries and Overview; the
  picture with Download and Report). Linux (Broadway; the signed-in pass its phase could not run): a Memory with a
  photo and its lightbox, a Pot with an entry, a folder and a file's page, each header's plan in the log; a link to
  each opened signed out (`received: Ready`, `page Gallery`/`Pot`/`Level`, the file from the folder's level). Windows
  (the VM): New → Folder, Memory, Pot through the registered creates, a Memory with a photo and a cover (the hero in its
  slot), a Pot, a Depot file's page (the text, its facts and uploader); a link to each opened signed out (`page
  Gallery`/`Pot`/`File`). Nowhere a kind fell to *not shown* or a dispatch panicked. A link opened while signed in was
  not tried: *Open a link* stands only on the sign-in and unlock screens (ADR 0150 decision 4), and the desktops' signed-in
  way, `veyq://link`, needs an installed package. **Not run:** the before/after screenshots the CR planned (the pages
  before the move were never shot; the comparison stands on each pass reading the same structure the phases' smokes
  read before the move); a drop box's page on every app. **Apple:** both builds green (unchanged since), nothing
  clicked — the coordinator's Mac pass: a Memory's, a Pot's, a Depot file's and a drop box's page; the drawer's
  Expenses and Memories lists and the Map tab; Home's New pot and New Memory; a received link's guest pages (Memory,
  Pot, file); a person's profile with Memories' section; the lightbox window and its menu command; the share
  extension (the iPhone's, the owner's).

## Alternatives considered

- **Hold the structure by passes and a checklist.** Rejected: the four apps drifted under exactly that (the audit's
  findings were all passed screens), and a pass sees one platform at a time.
- **The core hands a view tree (server-driven UI).** One structure by construction, but every control, gesture and
  guideline (ADR 0123 decision 9) lost to a generic renderer — a fifth UI toolkit in effect.
- **A shared UI toolkit (Compose Multiplatform).** Rejected by the owner in ADR 0136's alternatives; nothing new.
- **Vectors only, each platform formatting with its own formatter.** Four implementations held to one table; the
  locale data of four ICU versions differs (Windows' sizes already do), so a vector would hold one platform's output
  and fail another's for reasons no one chose. One core implementation held to the web's vectors is one source.
- **Whitelists with a warning for unknown actions.** Rejected: a warning in a log is how `banner` went missing unseen.
- **A core UI without per-application directories (a registry in a flat tree).** Cheaper to move, but nothing a guard
  can read separates an application from the core UI, and the Linux Memories → Depot call shows the flat tree invites
  the crossing.
