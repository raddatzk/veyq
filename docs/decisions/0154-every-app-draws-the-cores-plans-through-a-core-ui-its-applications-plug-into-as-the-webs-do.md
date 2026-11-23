# ADR 0154 — Every app draws the core's gates, plans, notices and formats through a core UI its applications plug into, as the web's do
- Status: Accepted (owner 2026-11-20: the CRs' owner questions answered as recommended)
- Built: decisions 1 and 3 on every app and decision 6's words and direction guard (2026-11-20), decision 2 on every
  app and decision 6's structure guard (2026-11-21), decisions 4 and 5 on every app and the rest of decision 6, the
  duration and formatter rule `platform-format` (2026-11-21; each § Consequences). Decision 6's shared plan fixture
  each platform's tests draw was not built: the compilers' exhaustiveness and the structure guard hold it (§ Consequences).
  Decision 3 on Android down to the core applications' packages (inbox #1056, 2026-11-22) and on Apple, Linux and
  Windows (inbox #1066, 2026-11-22; § Consequences)
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
  drawn title (a guest's *Add photos* and a WRITE_ONLY page's upload joined the naming 2026-11-22, below). Tests: `GatesInTheCoreTest`, `SessionModelTest`,
  `GuestDoorsTest`, the bridge's `DispatchTest` and `ReceivedBridgeTest`; the bridge's `ShellShown.dropsHere`/`dropRows`
  are held by no bridge test (the bridge's played tenants have no shell; the core's gates are).
- **As built, decision 1's guest writes** (2026-11-22, #1042 and #1044). Every write a link's guest is offered asks its
  name at the write through `GuestAccess.naming`, and nothing else does: a gallery's *Add photos* (`GalleryModel.uploadAsking`
  over `Door.named()` — a member's door answers at once, a guest's asks), a WRITE_ONLY page's upload
  (`GuestDropBox.uploadAsking`, `GuestUploader.uploadAsking`), beside Replace, *Add entry* and a level's New. The naming
  covers every writing link: `request()` asks on READ_WRITE and WRITE_ONLY, and `name()` makes a READ_WRITE guest
  (`nameGuest`) or a WRITE_ONLY uploader with its own `K_up` (`nameUploader`) — the one place that branches on the role.
  So the bridge's `Received.Name` is gone (a host names only when a write asks, `Received.NamingSave`), Linux's
  `received.rs` `write()` and its `GuestPage.add_names_first`, Windows' `ReceivedView.Write` and `KindBody.GuestCreate`'s
  writer, and the phones' `canAdd`: a guest's gallery offers *Add photos* by its rights alone, named or not. Decided on the
  way: `GuestNaming.offered` stays READ_WRITE-only — it means "the screen shows the guest line", and a WRITE_ONLY page says
  who sent in its own body, so widening it would draw *Not you?* twice; the phones' WRITE_ONLY pages keep the web's drop
  form (name, then pick), its save now `naming.name`, so the identity is still the core's — whether they should ask at the
  write as the desktops' do is the owner's (#1090); `GalleryModel.uploadAsking` closes the picked sources on a decline,
  since no queue holds them and its callers never closed on a zero; a declined naming on the desktops still toasts
  "failed" (`Received.Upload` answers `NOT_TAKEN`, as a level's upload did before) — #1091, rather than the bridge reading
  the session to tell the two apart. Tests: `GuestDoorsTest` (an unnamed guest's gallery offers Add and asks at the upload,
  declined nothing sent and the pick closed; a drop box's guest asked at its upload and named as an uploader, no identity on
  a decline; a Memory's uploader named through the naming), `ReceivedBridgeTest` (the drop box's upload asked, declined and
  named over the bridge; an unnamed guest gallery's `Memory.Upload` asked and declined). **Owed passes:** a guest's
  *Add photos* and a drop box's and a Memory uploader's upload asked at the write on Linux and Windows, the phones' guest
  gallery's *Add photos* unnamed on Android and Apple — none clicked in this change.
- **The passes (decision 1).** Each a throwaway, erased after. New Folder with a taken name kept the dialog open with the
  word under the field on Android, Linux and Windows. Android: a READ_WRITE link opened unnamed offered *Replace…* on a
  file and asked the name at the write, then uploaded. **Not run:** every drop on every app — Android needs a window to
  drag from another app (#1045), Broadway carries no file drop from the browser, the Windows VM's WinUI takes none from
  Explorer with UAC off; a guest's Replace on the desktops; *Add entry* asking at the save; the release builds' empty
  server field (compiled only). **Apple:** both builds green, nothing clicked; the Mac's drops (a ZIP and a folder onto a
  level, onto a folder row and a guest's identity folder, onto a gallery; #1039's folder from Finder), its dialogs and a
  guest's link are the coordinator's pass, the iPhone's the owner's.
- **As built, decision 1 — a Contributions view's bucket** (inbox #1065, 2026-11-22). A drop on a Memory's Contributions
  view goes into the bucket it shows, as the web's Beiträge drop does: `DropTarget.Bucket(contributions)`, judged by the
  view alone (`Contributions.intake`: the caller's own root takes it, another uploader's refuses it with
  `memory_buckets_drop_not_own`, a sub-bucket offline with `offline_read_only`) and sorted as a gallery's drop
  (`Drops.sorted`); a sub-bucket's upload is filed by a move once each medium is in (`MediaUploads.upload`'s filer,
  ADR 0034 point 7). The desktops send `DropOn.Buckets` with the open view's id; Android, Apple, Linux and Windows pick
  the bucket target while their Contributions view shows. Tests: `ContributionsTest`, `MediaUploadsTest`,
  `BucketDropTest`. **Not run:** a drop on any app's Contributions view (the passes above apply; owed in #1065).
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
  kind literals for glyphs and actions' names (strings, the guard's limit) and `TREE_PAGES`; Windows'
  WRITE_ONLY upload page and the bookmark page (core kinds). What was named here as left — Apple's link bar's file and
  music actions, Apple's `AppModel` methods per application and Memories' holders, the rows' dates, Linux's
  `home::ask_dates` — left the core UIs with inbox #1056 (Android) and #1066 (Apple, Linux, Windows), below.
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
- **As built, decision 3 on Android's core applications** (inbox #1056, 2026-11-22). Android's core UI named the core
  applications' packages (`me.raddatz.veyq.mobile.<application>`) in 11 files — `AppModel` held each application's screen
  models, the guest screens their pages, the tray Depot's uploads. Each application now holds them itself in a
  `Holding` (`core/Holding.kt`) it registers as `ApplicationUi.holding` (a typed `HoldingKey`); `AppModel` builds every
  registered holding once, in registration order, and the application reaches its own through an extension it declares
  (`model.memories`, `model.depot`, `model.expenses`). The core UI asks every holding the tenant's moments — `leave`
  (another tenant is shown), `shown` (its models built over the new shell), `locked`, `signedOut`, `personOpened`,
  `listsRead` — and the flows an application answers for its kinds: `rowLines` (a Memory's date), `trashed` (an open Pot
  closes), `moveSheet` (a Pot's copy by the open Pot), `addHere` and `drop` (a gallery's Memories, a level's or a folder
  row's Depot), `takesDrop` (an open Pot's new expense), `levelUploads` (the tray and New's *Upload files*/*folder*,
  Depot's) and `deriver` (Memories' tiers, a link guest's upload's too). A `KindPage` adds `guestActions` (the link bar's
  file download and replace, a Memory's music) and `guestNamed` (a Pot reads its write right anew); *Add here* on a
  page is an `AddHerePage` the page sets, "Send a copy" a `SendCopy` the file's header hands the shell. What only
  Memories used moved into it (`AudioEnds`, `MediaDerive`, the date text, the world view). The guard's rule (c) holds
  it: no core UI file names a core application's package or generated API (`…mobile.api.<application>`), and (b) no
  application another core application's. Behaviour-preserving but for one place: the link bar draws a Memory's music
  before *Keep* now (it was after). The decision log is ADR 0156's, entries 29–37.
- **As built, decision 3 on Apple's, Linux's and Windows' core applications** (inbox #1066, 2026-11-22). The new rule (c)
  of the UI guard measured 200 violations on the tree before (Apple 184 in 19 files, Linux 9 in 4, Windows 7 in 3), 0
  after. **Apple** takes Android's shape: each application registers a `Holding` (`Core/Holding.swift`, a protocol whose
  defaults are its extension) as `ApplicationUi.holding`; `AppModel` builds them once in registration order and an
  application reaches its own through `model.memories`/`model.depot`/`model.expenses` (`AppModel.held(T.self)`). The core
  UI asks the tenant's moments (`leave`, `shown`, `locked`, `signedOut`, `listsRead`) and the flows (`rowLines`,
  `trashed`, `moveSheet`, `addHereTarget`/`addHere`, `drop`, `pickUp` of a share's handoff, `takesDrop`, `openQueued` of a
  held write, `adopt`/`release` of a link's page, `editing`, `levelUploads` with the tray's `TrayShown`, `deriver`).
  `ApplicationUi.shell` is what an application presents over the shell (Depot's uploads' alert, "Send a Copy…", Replace's
  picker), `KindPage.guestActions` the link bar's per-kind actions. What the core UI asked of `AppleMemories`/`AppleDepot`
  for itself (a state watched, a picked file's source, a folder's walk) is the composition's `ApplePlatform`. Memories'
  holders, tiers, music measure, date text and world view moved into `Apps/Memories/`. **Linux**: Depot's Replace (a
  member's and a guest's, the guest's through `Page::act`) into `apps/depot/replace.rs`; the receipt viewer into
  `apps/expenses/`, its page rendering core's `pdf_render`; a Memory's date and its ask into `apps/memories/dates.rs`,
  `home.rs` holding only the rows' lines. **Windows**: `RowLines` over `ApplicationUi.RowLines` (Memories' `MemoryDates`);
  Depot's `FileReplace`, the guest's asked of the page (`KindBody.GuestReplace`, `GuestHost.Received`). The guard's (c):
  on Apple an index of each application module's Kotlin exports, on the desktops the wire's serial names owned by prefix
  (`WIRE_APPLICATIONS`; an unclassified prefix is red). Behaviour-preserving by reading (no pass yet). The decision log is
  ADR 0156's, entries 38–50.
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

- **As built, decision 2** (2026-11-21). A plan's enum carries `@Plan` (`navigation/Plan.kt`): `HeaderAction` (core's
  ten and the pages' own — `UPLOAD`, `MUSIC`, `BANNER`, `REPLACE`, `SEND`, `OPEN_OWN_PAGE`, `REPORT_SHARED` —, serial
  names the web's ids), `LevelTool` (`TRASH`, `VIEW`; `SELECT` first since a level selects, below), `DrawerSection` (`NEW` first), `SettingsSection`, `SignInEntry`, `UnlockEntry`,
  `LinkAction`, `TileAction` and `AppKinds.Create` (and `SelectionAction`, below). The plans: `NodeHeader.Plan(buttons, menu, last)` with Delete always
  in `last` and each kind's order `AppKinds.headerOrder` (the banner, a kept link's *Open on its own page* and *Report
  shared content* among the entries, no longer a page's body buttons or Windows' entries after Delete);
  `ShellModel.levelPlan` → `LevelPlan(creates, tools)` and `drawerCreates`/`drawerSections`; `AccountSettings.State.sections`;
  `SignInPlan.entries`, `UnlockPlan.entries` (inbox #1052; its *Stop trusting this device* always asked first, in
  core's one question `QueuedShown.forgetAsk`, owner 2026-11-22); `LevelRow.plan` (`ShellModel.rowPlan`), `GalleryModel.tileActions`, `ShareSheet.rowActions`;
  a guest's level New `GuestLevel.creates`. The bridge carries each as typed entries (`ShellShown.level`,
  `SettingsShown.sections`, `Call.RowPlan`, `Call.SignInPlan`, `Call.UnlockPlan`, `GalleryRow.tileActions`, `ShownLink.actions`) and
  generates every plan enum into `bridge.rs` and `Bridge.g.cs`. `PlansAreTheWebsTest` reads the web for the orders:
  each kind's `header.order` from its `register.ts`, core's merge order from `coreNodeActions.ts`, the Settings order
  from `sections.ts`. The owner's answers (2026-11-20), now on all four: **New** in the level's head (`LevelPlan.creates`)
  *and* the drawer's below the switcher (`DrawerSection.NEW`, ADR 0122 decision 2), whose New Folder lands at the
  space's top (`ShellModel.createFolderAtTop`, `Call.ShellCreateFolder.top`); **Trash** among the level's tools, then
  the view switch — out of the desktops' switcher and the phones' bar; **Settings** in the web's order, the app-only
  sections beside their kin (this device and saved keys after sessions, offline storage after maps, the account and
  its address where the web's password is), Sign out its own last section; **the sign-in** Submit first, Cancel while
  adding, then the link and kit entries. **Owner-visible beyond the answers:** an application's list draws no New of
  its own any more (Expenses' *New pot*, Memories' *New Memory* on the phones, Apple's application lists): the plan
  offers New there only through the drawer's. Each platform draws the plans in its core UI, its look its own:
  Android's header runner and icons exhaustive `when`s over `HeaderAction`, the level's New the FAB and its tools the
  toolbar, a row's, a tile's and a link row's menu by a long press; Apple's over generated Swift enums (below), a
  row's, a medium tile's and a link row's menu a context menu (the link row's swipe actions gone — a subset of the plan),
  the Mac's *Open in New Window* beside a tile's plan; Linux's `header::doer` one arm per entry (a page's own entries its
  window actions), New at the content header bar's start and the tools at its end, the drawer's New the sidebar
  header's "+", a row's and a tile's menu by a right click or a touch long press; Windows' `NodeHeader.cs` a `switch`
  expression per entry without a discard, a page's own entries its `PageHooks`, the tools in the level's `CommandBar`,
  a row's menu `RowMenu.cs`. **Apple's exhaustive form** (#1050): a Swift `switch` over a Kotlin enum is no exhaustive
  one (an Objective-C class) and needs a `default:`, so `apple/generate_plans.py`, a pre-build step of both targets,
  writes each plan enum's Swift mirror (`HeaderAction.Entry`, read as `x.entry`) from the core's sources through the
  structure guard's own reader, its Swift names from the framework's `VeyqCore.h` (`theCopy`, `delete_`, `theNew`), the
  two checked equal or the build stops. **Windows' choice:** `CS8509` (an entry not named) is an error, `CS8524` (an
  unnamed value, `(E)17`) off — on, it fires on a switch naming every entry and so demands the very discard the guard
  refuses; no unnamed value crosses the wire (`JsonStringEnumConverter` refuses an unknown name); the reason stands in
  `Directory.Build.props`. **The guard**, `scripts/validate_mobile_structure.py` (its docstring the rules and limits),
  reads the plan enums from the annotation, never a list, and each one's name per language: (a) no fallback arm in a
  `when`/`switch`/`match` whose arms name a plan's entry — the core, the bridge, all four platforms; (b) no collection
  literal of a plan's entries or of two or more of its serial names (a whitelist); (c) no filter or sort in the
  statement that reads a plan's list — the bridge and the platforms. The compilers carry the rest, so an entry the core
  adds fails every platform's build until drawn; that is why decision 6's shared plan fixture was not built. **Blind
  spots** (the docstring's): it reads names, not types — an arm naming no entry (`in set`, `.name` compared with a
  string), a plan's list renamed into a local first or built by a loop with `if`, a list whose property has a library
  name (`entries`, `keys`), a whitelist of one string. **Left:** a
  level's tabs stay `Shell.barTabs` (the bar's, ADR 0122); a guest's link pages on the phones draw only the gallery's
  music from `headerActions` (the page's own menu, no core header there). Every tile and row of a level or an
  application's list opens its row menu (`rowPlan`) on every app since 2026-11-22 (Apple's tiles #1053, Android's
  application lists #1064: `ArrangedListScreen` wraps each as the level does).
- **The web draws the header's and the row's plans from the core** (2026-11-22, [ADR 0155](0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)
  § The rights and plans' slice): `NodeHeader.plan` and `LevelRow.plan` through `@veyq/core-js/navigation`, held with each
  kind's header order by `contracts/platform/plans/v1-vectors.json` (`PlanVectorsTest`), which replaced
  `PlansAreTheWebsTest`'s reading of `register.ts` and `coreNodeActions.ts` (its Settings reading stays). A Memory's
  `BANNER` moved to `Slot.LAST`, the web's menu-only banner: on the four apps *Add banner* now sits behind the menu's
  separator. `HeaderAction` is serialized at its use site (no longer `@Serializable` itself), its serial names unchanged.
- **The passes (decision 2)**, 2026-11-21, each a throwaway erased after; compared against the web's orders as
  `PlansAreTheWebsTest` reads them (each `register.ts`'s `header.order`, `coreNodeActions.ts`, `sections.ts`), not
  beside a web screen. **Linux** (Broadway, its own home volume): the sign-in Submit, then the QR, the kit and *Open a
  link* (`sign-in entries: Submit,LinkShow,KitOpen,OpenLink`); Home's New at the header bar's start (folder, Pot, files,
  folder upload, Memory, bookmark) and its tools Trash then the view switch at its end, no Trash in the switcher; the
  drawer's New (folder, Pot, Memory, bookmark) — its New Folder made from inside a folder landed at the space's top; a
  folder's, a Memory's, a Pot's, a file's and a bookmark's header each with Delete last (the Memory's *Add banner* in
  its menu, the bookmark's *Open on its own page* a button and *Report shared content* before Delete; each plan in the
  log); a folder tile's right-click menu (rename, move, favorite, share, copy, cover, download | report, delete); a
  medium tile's (the mark only); a link row's (Copy a button; Edit, Renew, Revoke); Settings (maps, storage, sessions,
  this device, saved keys, account, email, password, add a device, security, profile, erase, Sign out last). **Android**
  (a throwaway API 36 emulator, deleted after; its phase's unclicked items): a medium tile's long press (the mark only), a
  file's header (Favorite, Download | share, replace, rename, send a copy, offline | move, copy, report, delete), a Pot's
  (Favorite, Share | download, rename, cover, offline | move, copy, report, delete) and a bookmark's (*Open on its own
  page*, Favorite | rename | move, report name, report shared content, delete). **Windows** (the VM's
  `tw-win.localhost` throwaway, its own directory and tasks, read by UI Automation; the tenant name was freed by revoking
  the earlier owner-less pending tenants): the sign-in Submit, then the QR, the kit and *Open a link*; Home's `CommandBar`
  New first (folder, Pot, files, folder upload, Memory, bookmark), then *Add files only*, Trash and the view switch, no
  Trash in the switcher; the drawer's New (folder, Pot, Memory, bookmark) — its New Folder made from inside a folder
  landed at the space's top; a folder's (New, Favorite, Share, Trash, View | download, rename, cover, offline | move,
  copy, report, delete), a Memory's (*Add banner* in its overflow), a Pot's, a file's (Favorite, Download | share,
  replace, rename, offline | move, copy, report, delete) and a bookmark's header (*Open on its own page*, Favorite |
  rename | move, report name, report shared content, delete) each with Delete last, each plan in the log; a folder
  tile's flyout, opened by Shift+F10 (rename, move, favorite, share, copy, cover, download | report, delete; a file
  tile's the same without the cover); a medium tile's (the mark only); a link row's (Copy and Share buttons; Edit, Renew,
  Revoke under More); Settings (maps, storage, offline, sessions, this device, saved keys, account, email, password, add
  a device, security, profile, erase, Sign out last). **Apple:** both builds green, nothing clicked — the coordinator's Mac pass: the sign-in's order; Home's New and its tools (Trash, then the view
  switch, none in the bar's Home actions); the drawer's New below the switcher (its folder at the space's top) and the
  application lists without a New of their own; a folder's, a Memory's (Upload's submenu), a Pot's, a file's and a
  bookmark's header with Delete last; a row's, a medium tile's (with *Open in New Window* beside the plan) and a link
  row's context menu; Settings with Sign out last.
- **As built, decision 4** (2026-11-21). `Notice.kind` (`words/Notices.kt`): `NoticeKind.SAID` (a success,
  `Notice.SAID_MS` 4 s), `HELD` (a refusal or failure, until closed), `UNDO` (until the state drops the offer, the
  window `ShellModel.TRASH_UNDO_MS` 6 s); the inline notes `Notice.COPIED_MS` (2 s) and `NOTHING_PLAYS_MS` (5 s). The
  default is `NoticeKind.of(msg)` — `SAID` for the success words (a copy, a move, a download, an import, an offline send,
  and the platforms' own successes made core notices: a kept link, a saved profile, receipts saved), `UNDO` for a trash,
  every other word `HELD` —, overridden where the same word lives otherwise (`living(kind)`: a move's offered undo is
  `UNDO`, a trash queued offline `SAID`); `NoticeKindTest` holds the table. Each app has one notice host mapping the
  kind onto its control: Android `core/NoticeHost.kt` (every snackbar `Indefinite`, closed by the core's `lifeMs` once
  it appears, a held one with a close), Apple `AppModel.show` (`HELD` the shell's alert, `SAID` a bottom toast, `UNDO`
  its Undo toast), Linux `said.rs` (the one `adw::Toast`), Windows `Core/NoticeBar.cs` with `Core/Lives.cs` (the InfoBar
  under the bar: a Success, an Error with its close, an Undo until `EndOffer`). The desktops read every duration, the
  kind of their own words and the separator once from the bridge, `Call.NoticeLifetimes` → `Answer.Lifetimes`, answered
  before the core starts; a wire `Notice` carries its `kind`.
- **As built, decision 5** (2026-11-21). `words/Shown.kt`: `size`, `day`, `dayOf`, `moment`, `memoryDay`, `dayRange`,
  `dayLabel` (the gallery's chip), `money`, `number`, `list`, `length` (a track and both music clocks), `distance` (a
  track's length), `clock` (a picked time), `joined`/`SEPARATOR`, and `args(notice)`/`piece(p)`, each in the app's
  language, free of kotlinx.serialization. What each app draws through it: **Android** `Formats.kt`/`NoticeText.kt`
  (`Context.language`); **Apple** `Formats.swift` (`shownLanguage`; the date pickers' ISO day by a Gregorian `IsoDay`,
  a GPX track's distance `Shown.distance` since phase 10); **Linux** and **Windows** the wire's text — a notice's sizes,
  moments and amounts as `text`, a line's `Piece` as `said` or `plain`, and every value a view held raw beside its new
  `…Text` field (`ShownText`, written by `Shown` in the bridge's `WireWords.module` language, a plain string on the
  wire): the storage's `automaticText`/`pinnedText`/`freeText`, `OfflineItem.bytesText`, a session's
  `createdText`/`lastUsedText`, `rotatedText`, a rate's `dayText`, a Pot's `amountText`s and `totalText`, an entry's
  `untilText`/`atText`/`dateText`, `DateShown` `fromText`/`toText`, `MemoryDateShown.text`, `DetailRow`
  `sizeText`/`momentText`, a link's `createdText`/`expiresText`, `ShownEnd.dayText`, the trash's
  `deletedText`/`purgeText`, `ReportShown.receivedText`, `NowPlayingShown` `positionText`/`durationText`,
  `TrackFactShown.distanceText`, `BannerEditorShown.tooLargeBound`, and `ChipShown.label`. Gone with it: Windows'
  `Said.cs` (`CurrencyFormatter`, `StrFormatByteSizeEx` at base 1024, the `"d"`/`"g"` dates) and its truncating
  `MusicTime`, Linux's `format_size`/`%x`/`localeconv` money and its `libicuuc` link, Apple's `ByteCountFormatter`,
  `musicTime` and `Measurement`, Android's `formatShortFileSize` and `DateUtils.formatDateRange`. The vectors:
  `src/mobile/words/format-vectors.json`, written by `src/web/scripts/format-vectors.mts` over the web's own formatters
  (`src/web/src/test/formatVectors.ts`), re-rendered by the web's `formatVectors.test.ts`, held by the core's
  `ShownVectorsTest` (and `ShownDayLabelTest`) on the JVM and `macosArm64`, which also holds `expenses/Currencies` —
  the digits a new Pot or entry is sealed with — to the web's `minorUnitsOf`. **The file depends on the ICU of the Node
  that writes it**: written first under Node 23.11 (ICU 77.1), it failed on Node 22.23 (ICU 78.3), which CI runs —
  COP, HUF, IDR and PKR lost their minor digits and RSD gained two (25 money cases; measured 2026-11-21 by running the
  web test under `npx node@22`). Every Node line `package.json`'s `engines` names ships ICU 78.3 (22.23.3, 24.22.3,
  26.13.1), so the file was written anew under Node 22 and `Currencies` followed; the web test names an ICU major
  mismatch before the cases it moves.
- **The web stays the source of these formats.** ADR 0155's Consequences make the core's output the one source for what
  *moved*; these formats did not move, and moving them now misses that ADR's decision-7 budget by a measured factor: a
  throwaway `@JsExport` façade of `Shown` (size, day, moment, Memory day and range, money, list, length) bundled beside
  the search's entry grew it from 78,824 B min / 27,691 B gz to 91,955 / 32,658 — 4,967 B gz of own code against the
  703 B gz (1,221 min) of TypeScript it would delete (`formatBytes`, `formatMoney`+`decimalOf`, `memoryDateRange`'s
  two; esbuild `--minify`, `gzip -9`): 7.1×, where R = 3.0 allows about 2.1 KB. It would also give up `Intl`'s
  browser locale data and put the money table in the eager set. So the vectors run web → core, and the turn comes with
  the move that owns a format (Expenses brings `formatMoney`): then `Shown` is the source and the file is written by a
  core test, as `SearchVectorsTest` writes the search's.
- **As built, decision 6's `platform-format`** (2026-11-21). `validate_mobile_words.py`'s tenth rule, on for all four
  platforms (`PLATFORM_FORMAT_ON`): no duration literal at a notice or an inline note (Linux also: no `adw::Toast` made
  outside `said.rs`, libadwaita's unseen 5 s), no size, date, money or measurement formatter by name, no `" · "` literal.
  `PLATFORM_FORMAT_EXCEPTIONS` names each kept call with why (the docstring lists the classes: the one notice host's
  control without a lifetime of its own, a player's media time, a timer that is no notice's, a picked day encoded as ISO,
  the decimal separator a person types, a Pot create sheet's currency picker, debug-only sources). Bitten per platform
  (a `formatShortFileSize`, a `ByteCountFormatter`, a `.formatted(.measurement`, `format_size`, an `adw::Toast::new`,
  `TimeSpan.FromSeconds(6)`, a `.ToString("N0")`, a `" · "` — each red alone, green restored).
- **What changed for a person**: Windows' sizes are base 1000 and its dates the web's (`Nov 21, 2026` / `21.11.2026`),
  "copied" lives 2 s everywhere (was 6 s), the music clocks round as the web's, a success closes after 4 s and a refusal
  stays on every app, a Memory's range is the web's numeric one on Android, a track's distance the web's on Apple, and
  apps sealing a new Pot in COP, HUF, IDR or PKR use 0 digits, RSD 2 (ICU 78's, as current browsers; a stored amount
  never moves).
- **The passes (decisions 4 and 5)**, 2026-11-21, each a throwaway erased after, each value compared with what
  `format-vectors.json` holds for the web. **Android** (a throwaway API 36 emulator, deleted after): a copy's notice gone
  after about 4 s, a trash's Undo after about 6 s, an upload's *Replace* offer (held) still up after 14 s with its
  Dismiss, a link's "Copied" about 2 s; a 1.5 MB file "1.5 MB" / "1,5 MB", a moment "Nov 21, 2026, 2:29 PM" /
  "21.11.2026, 14:29", a link's days; a Pot in USD with a EUR entry: "$1,234.56", "€50.50", "paid $1,290.11 · consumed
  $645.06" / "1.234,56 $", "50,50 €", "bezahlt 1.290,11 $ · verbraucht 645,06 $"; a Memory's range "03/12/2026 –
  04/02/2026" / "12.03.2026 – 02.04.2026"; a 95.6 s track listed "1:36" (rounded, the web's; the system's file picker
  showed "01:35") with the running clock "0:02 / 1:36". **Windows** (the VM, a `tw-win` throwaway, a directory and
  tasks of its own): the app logs `lives read: said 4000 ms, 13 words not held`; a trash's Undo ended after 6.0 s; a
  copy's notice gone between 1.5 s and 5.5 s; a download's "downloaded" there at 2.5 s and gone by 14.5 s; a download
  that failed (its file held open by another process the moment the save picker made it) an Error InfoBar with its
  close, still up after 18 s; Settings' "10 kB", "0 B", "31.3 GB"; a trash row's days; a Pot in EUR with a USD entry
  "€1,234.56", "$50.50" → "€45.45", "paid €1,280.01 · consumed €640.01" / "1.234,56 €", "50,50 $", "bezahlt
  1.280,01 € · verbraucht 640,01 €"; a lightbox's "Size: 2.4 MB" for 2,440,775 bytes (base 1000; Windows' old base 1024
  wrote 2.3) and "Taken: Mar 20, 2026, 12:00 PM" / "Größe: 2,4 MB", "Aufgenommen: 20.03.2026, 12:00"; a Memory's range
  "03/12/2026 – 04/02/2026" / "12.03.2026 – 02.04.2026". A move onto a taken name is said inside the move sheet, not as
  a notice. **Linux** (phase 7, Broadway): built and its rules bitten; the notices' lifetimes and a Pot's amounts were
  not seen there. **Not passed:** Windows' music clocks (the core's text, `ShownWireTest` and the vectors);
  Linux's lifetimes and amounts; **the Mac and the iPhone: both builds green, nothing clicked** — the coordinator's Mac
  pass: a copy's notice at the bottom gone after 4 s; a refusal (a download whose target fails) the alert until closed;
  a trash's and a move's Undo gone after 6 s; a link's "Copied" about 2 s; *nothing plays* about 5 s; in English and
  German a 1.5 MB file's line ("1.5 MB · Modified …" / "1,5 MB · Geändert …"), a Pot's amounts in EUR and USD and its
  overview, a Memory's range and an ongoing one's "Since …", a 95.6 s track's "1:36" and the running clock, a GPX
  track's distance ("850 m", "12.3 km"; was the system's `Measurement`), the lightbox's size and moment, Settings'
  storage sizes and session dates, the trash's and a link's days, the gallery's day chip ("March 12" / "12. März"); the
  iPhone's the same, the owner's.

- **As built: the plans aligned** (2026-11-22, branch `claude/plans-align`; owner mandate 2026-11-21, answers 2026-11-22:
  a row's *Share* on every client, a READ_WRITE link guest's *Upload folder* in New on every client). ADR 0155 § The
  rights and plans' slice's D3 and D14 deviations, each settled in the core so all four apps follow, by this ADR's rule
  (the web's unless an owner answer says otherwise); `contracts/platform/plans/v1-vectors.json` gained a `level` section
  both sides read. The decision log, kept by its implementer (question, options, choice and why, evidence, cost):
  - **P1 — Pin the level's plan in the vectors first.** *Q:* where do New's order, a guest's New and the level's tools get
    held on both sides? *Options:* each client's own test; a new vectors file; a `level` section of
    `contracts/platform/plans/v1-vectors.json`. *Choice:* the `level` section (`creates`, `guest`, `tools`), answered by the
    core's `PlanVectorsTest` (and its write mode) and on the web by `plans.vectors.test.ts`' New-order case over the real
    registry (`LevelView.vue`'s folder first, then `registry.levelMenu()`); the row case "an app's row: the share among the
    standard ones" renamed for owner answer 1, as it is now every client's. The web has no core-drawn level plan, so `guest`
    and `tools` are written from the web's rules by hand (`LevelView.vue` lines 175, 259–281; `canUploadFolder`) and read
    against them. *Evidence:* measured, `:composition:jvmTest --tests '*PlanVectorsTest*'` green; bitten (below). *Cost:*
    the web's `tools` and `guest` rules are pinned in prose and cases, not by a web test (it draws no core plan there).
  - **P2 — New's order is the web's** (D14; ADR 0154's rule). `AppKinds.createsWhere` builds Folder, Memory, (on a level
    of Home) Upload files, Upload folder, Pot, then the kept link; `homeCreates`/`drawerCreates` gone (their only readers
    were the function and one test). The drawer's New follows: Folder, Memory, Pot, Bookmark. *Evidence:* the vectors'
    `level.creates`; the web's case reads its registry. *Cost:* every app's New and the drawer's reorder (owner-visible,
    settled by the rule).
  - **P3 — The view switch's gate is the web's `showViews`** (D14). *Q:* is "unless the map tab" the same rule? *Options:*
    keep `views: Boolean`; port the web's rule. *Choice:* `LevelPlan.of(…, views: List<ViewMode>)` offers the switch only
    where more than one view is offered, and `LevelPlan.views(mapTab, identityFolder, rows)` is the web's: none on the map
    tab, none on an empty guest's identity folder (the web's `hideEmptyTabs` lists no folders there, so one view at most),
    rows and tiles everywhere else. `ShellModel.levelPlan` reads the folder's identity and its rows. *Evidence:* measured
    in `LevelView.vue`; the vectors' `level.tools`. *Cost:* a member's empty guest folder loses its (useless) switch on the
    apps.
  - **P4 — A READ_WRITE guest's *Upload folder* (owner answer 2).** *Q:* where does a guest's folder upload live, given a
    platform's walk yields Depot's `FolderUpload.Found` and the core UI may not reach an application? *Options:* move the
    walk vocabulary to `:core`; a core hook (`AppKinds`) beside `guestLevelUploads`; Depot's own entry
    (`DepotGuest.uploadFolder`) reached through each platform's existing application seam. *Choice:* the third — the
    web's is Depot's too (`features/depot/levelRows.ts` `uploadFolder` over `guestUpload.beginTree`). `FolderUpload.made`
    is now the one plan-and-order for both (a `Maker` over a member's folder ids or a guest's opened nodes, the top numbered
    past its siblings); `GuestLevel.writer()` (`@PlatformApi`) asks the name through core's one naming flow and hands Depot
    the access, the folder and its siblings; each file is a `LevelUpload` with its planned name. Android and Apple reach it
    through their `LevelUploads` seam (Depot's holding), Linux and Windows through the bridge's new `Received.UploadFolder`;
    `ReceivedPage.creates` carries `GuestLevel.creates` so the desktops draw the plan instead of a fixed two-entry menu.
    `Answer.Queued.more` carries every line a folder upload says. *Evidence:* compiled (`:composition:compileTestKotlinJvm`,
    `:bridge:compileKotlinJvm`); the plan rule bitten through the vectors. Once the batch settled, the folders that hold nothing
    are taken back deepest first (`GuestLevel.Writer.takeBackEmpty`, the web's guest-only `takeBackEmptyFolders`: a guest
    cannot trash), none below which a refusal that is not final could still land a file on a retry. *Cost:* no storage check
    for a guest (the web's skips it too: a guest reads no tenant's room).
  - **P5 — A row's Share on the web (owner answer 1).** `rowRightsOf` hands the row the links its rights answer grants
    (`LevelRowStandard.share`, a drop box's worded `depot.dropBox.share` as its page's), `standardRowActions` offers it
    where the page has the share door, and the plan (`LevelRow.plan`) places it as the apps' rows already did. *Evidence:*
    `LevelView.test.ts` (a folder row: favorite, rename, move, share, delete; its click opens the share door with the
    row's offer). *Cost:* every member's row grows one entry.
  - **P6 — A kept link's row's *Report shared content*** (D3/D14). `ShellModel.rowPlan` hands `REPORT_SHARED` as the row's
    own entry where the kept link's meta reads (the web's `coreNodeActions.ts` condition); `NodeHeader.label` words it (a
    row has no page); `BookmarkPage.reportSharedFromRow` reads the meta and opens the link's screen with its report. Android
    and Apple run it from the row menu's own entry, Linux's header and Windows' `RowMenu` through the bridge's new
    `Row.ReportShared`. *Cost:* opening a kept link's row menu decrypts its meta once.
  - **P7 — Settings: the two `storage` ids** (D14). The web's `storage` is the tenant owner's storage section (its search
    vectors' `storage` with "S3"), the apps' is "Storage on this device": the apps' section is now `device-storage`
    (Android's anchor reads the core's id; Linux's and Windows' anchors renamed). *Cost:* a stored deep link to the apps'
    `storage` section (none is stored: the anchors are in-memory) would miss.
  - **P8 — Settings: the profile's and appearance's gates** (D14). Profile: the web gates it on a tenant
    (`activeSpace.tenantId`); every app session is a tenant's (`TenantSession`), so the gate holds always — the same answer,
    no change. Maps: the web lists it always ("a consent must be withdrawable wherever it was given"); the apps list it once
    the tenant's map services are built, which every platform does when the shell opens (Apple's `AppModel`, Android's
    `AppModel`, the bridge for the desktops) — the same answer while Settings can show, no change. Appearance: the web offers
    it everywhere, the apps on the phones only (Mac, Linux and Windows follow the system's appearance and language). The rule
    says the web's; the owner's 2026-11-09 rule says the platform's guidelines as far as possible, and adding it means a
    theme and language choice on three desktops. **The owner, 2026-11-22: the desktops follow the system** — a platform's
    trait, not a gap; the phones keep their Appearance section.
  - **P9 — The sign-in's entries** (D14). The web's sign-in has a locked entry (§E3: sign in without unlocking, to sign out
    everywhere), *Forgot password* (the recovery flow) and, with a live session, *Sign out*; the apps' have Cancel while
    adding and *Open a link* (owner 2026-11-20, ADR 0154 § Consequences). The rule settles that the apps gain the first two,
    but each is an account flow the apps do not have (recovery, the locked sign-out), not a plan entry, and the account and
    Secret Key area is another session's work in parallel. *Choice:* not moved here; a follow-up CR — built 2026-11-23
    (As built: the sign-in's entries, below). The third is the apps' `UnlockPlan` (a live session's own screen: *Other
    account*, *Forget*), no difference to align.
  - **P10 — Delete on a guest's own photo tile** (D14). `TileAction.DELETE` where the rights delete a guest's own media
    only and the medium is its own (`GalleryModel.deletes`), the web's `isOwnMedia` overlay; it runs `GalleryModel.trash`,
    which asks first and purges (`purging`, each platform's question). Drawn on all four. *Cost:* the apps know a guest's
    own uploads for the session only (`MemoriesGuest`' in-memory set), the web per device and identity (`ownShareMedia.ts`,
    localStorage) — the tile offers the delete in the session that uploaded it; follow-up.
  - **P11 — The apps read through `CoreRights`** (D8/D14). `NodeHeader.rights` (both the header's and the row's) answers the
    heart by `CoreRights.favorite` (a member's, on a node, of a kind this client draws, and the pin set known), the cover by
    `CoreRights.cover` and a kept link's rename, move and delete by `CoreRights.bookmark`; an app's header is a member's, on
    a node, under open keys, and a switched-off application's object offers no cover through its doors
    (`AppKinds.Facts.canWrite`). *Evidence:* `NodeHeaderTest`, `PlanVectorsTest` green. *Cost:* the apps read no pending
    deletion yet (`readonly` false): the web's narrowing of a space pending deletion has no app twin — follow-up.
  - **P12 — #1049, a level's Select.** *Q:* fits this CR? Measured by the Explore map: no selection model in the core,
    `MoveSheet` and `NodeTrash` single-node, each platform's level a pick mode and an action bar of its own — roughly
    1.5–2.5k lines over the core, the bridge and four platforms. *Choice:* its own CR, refined; built 2026-11-22 (As built: a
    level's Select, below).
  - **P13 — #1062, numbered sibling names.** *Q:* fits cleanly? A row's and a header's name are `NodeRef.name` and
    `FolderRef.name`, which the rename dialog prefills and the move and collision checks read; numbering them in place would
    prefill "Anna (2)". A display name beside the name touches every row, tile, title and breadcrumb drawing on four apps.
    *Choice:* not here; inbox #1062 keeps its row with this measurement as its next step.
  - **P14 — The bites and the pass.** Each restored with `/bin/cp -f`: `createsWhere` with the Pot before the Memory turns
    `PlanVectorsTest`'s `level.creates` red; `GuestLevel.createsFor` without *Upload folder* its `level.guest`;
    `LevelPlan.views` without the identity-folder case its `level.tools` (the `size > 1` gate alone bit nothing: `views`
    offers none or two, so the rule lives in `views`); a guest folder upload's files sent into the level instead of their
    folder turns `GuestDoorsTest` red; the web's row share switched off turns `LevelView.test.ts` red, Memories' New
    `order` 45 turns `plans.vectors.test.ts`' New-order case red. `VEYQ_WRITE_VECTORS=1` rewrites the vectors byte for
    byte. The take-back of a guest's empty folders is held by no test (the played share answers no `abandonShareNode`).
    **The browser pass** (one throwaway, headless system Chrome, a Vite of this tree against one of `335f70170`): New,
    the folder's header buttons and menu, the level inside and Settings identical; a folder row's menu gains *Share*
    after *Move to…*, which opens the folder's share dialog; no console error. This was also ADR 0155's rights and plans'
    slice's owed pass: the core-drawn header (cover, rename, favorite, share, download | move, copy, report, delete) and
    row menus rendered and acted. **Not clicked on the apps:** a guest's *Upload folder*, a kept link row's *Report shared
    content* and a guest tile's delete on every platform (Android, Linux and Windows built and their tests green, Apple
    both builds green); the passes are the coordinator's and the owner's.

- **As built: a level's Select** (2026-11-22, branch `claude/level-selection`; inbox #1049, owner mandate 2026-11-21). The web's
  Select (`LevelView.vue`, inbox #421) on every app, through the core: `LevelTool.SELECT` first among a level's tools, a
  level's selection in the shell's state (`navigation/LevelSelection.kt`, `ShellModel.State.selection`), its bar's actions a
  plan enum (`SelectionAction`: move, copy, report, delete) each platform draws whole, and every action the core's own sheet
  over the selected rows it reaches (`MoveSheet` over a list, `SelectionTrash`, `ReportSheet` over several nodes). The
  decision log, kept by its implementer (question, options, choice and why, evidence, cost):
  - **L1 — The bar's rule.** *Q:* the refinement's invariant said an action is offered "where every selected row grants it"; is
    that the web's? *Options:* every selected row; the web's. *Choice:* the web's (this ADR's rule): an action is shown where some row of
    the level allows it, enabled where some SELECTED row does, its hint says how many of the selected it cannot reach (none for
    the report), and it reaches only the selected rows that allow it (`LevelSelection.reached`) — so no row's rights are widened.
    *Evidence:* measured in `LevelView.vue` (`anyMove`, `movable`, `cannotMoveText`); the vectors' `selection` cases. *Cost:* the
    invariant's wording was the refinement's guess.
  - **L2 — One table for both sides.** *Q:* the web's rule lives in `LevelView.vue`'s computeds; how do the vectors read it?
    *Options:* hand-written expectations the web test re-derives; the web's rule extracted. *Choice:* `selectionBar` in the web's
    `selection.ts` (numbers only: offered, count, hidden, entries), which `LevelView.vue` now draws from, and the core's
    `LevelSelection.bar`; both read `contracts/platform/plans/v1-vectors.json`'s `selection` (ten cases), `level.tools` gained
    `select`. *Evidence:* `:composition:jvmTest --tests '*PlanVectorsTest*'`, the web's `plans.vectors.test.ts`; written by the
    core's write mode, every case read against its rule. *Cost:* the web touched (`LevelView.vue`, `selection.ts`).
  - **L3 — A row's rights.** *Q:* where does a row's part in a selection come from? *Options:* a selection right per kind (the
    web's `LevelRow.selection`); the row's own menu. *Choice:* the row's menu (`LevelSelection.Rights.of(LevelRow.plan(…))`):
    the apps' row plan is already held to the web's rights by the vectors, so a selection can do nothing a row's menu cannot.
    *Cost:* while the level picks, every state change re-plans each row (synchronous; the report's answer cached once).
  - **L4 — Where the selection lives.** *Options:* a model per platform; a core model beside the shell; the shell's state.
    *Choice:* `ShellModel.State.selection` (`LevelSelection.Picking`): every platform already follows the shell's one flow, and the
    shell's every change keeps it on what the level holds or clears it where the place changed (`reselected`), as the web's
    watchers do. *Cost:* one more field on the shell's state and the bridge's `ShellShown.selection`.
  - **L5 — Which levels select.** *Choice:* Home's levels only (`selectionPlace`): the web offers Select where core draws the
    rows, never in an application's own presentation; an application's list on the apps is the drawer's list, no level.
    *Cost:* none against the web.
  - **L6 — A selection's move and copy.** *Q:* the web's `MoveNodesDialog` stays open on a summary; how do four platforms say it?
    *Options:* a summary view in each sheet; the summary as the sheet's one notice. *Choice:* `MoveSheet` over a list
    (`nodes`), each node sent in turn and a refusal stopping nothing (a node already there left out), what went said with
    what did not after it (`select_summary`: the moved count, then "Not moved:" and each node's reason — the web's words,
    joined as the web's copy toast joins two), held until closed; one Undo moves every node back; nothing moved keeps the
    sheet open on why. A copy goes node by node by its kind's way and stops at its first failure (the web's one run stops
    too). *Evidence:* `MoveSheetTest` (four selection cases). *Cost:* the refusals are a notice, not a dialog; each node's move
    re-reads the lists (N reads; the web re-reads once) — follow-up; a copy's room is checked node by node, so two that fit
    apart but not together are refused by the server's quota.
  - **L7 — The delete.** *Choice:* core's `SelectionTrash`: one question (one node: its kind's own, as the web's), each node
    through the row's own delete (`ShellModel.trash`), one notice, one Undo restoring exactly what went; the bridge holds it
    (`Select.Delete*`) and its Undo's window (`Watched.SelectionDelete`). *Evidence:* `SelectionTrashTest`. *Cost:* the kinds in
    the question are joined with ", " (core's `Joined`), not the web's "a, b and c"; Memories' nouns are registered now
    (`MemoriesKinds.noun`, which also words a name collision with a Memory as the web's), Depot's file and drop box still say
    "an item" — follow-up.
  - **L8 — The report.** *Choice:* one report of every selected row (`NodeReport.reportMany`: one `K_report`, each node whole,
    a kept link its name), at most `NodeReport.MAX_ITEMS` (the web's 100); above it the sheet sends nothing and offers the
    folder on screen (`ReportSheet.useEnclosing`). A node gone or not opening fails the whole report, as the web's one
    submission does. *Evidence:* `ReportSheetTest`. *Cost:* none.
  - **L9 — Words.** The web's English and German for every key (`select_*`, `move_many_*`, `copy_many_*`, `delete_many_*`,
    `report_items`, `report_too_many`, `report_enclosing`, `node_a_folder`); *Cancel* is `entry_cancel`, the actions' words the
    row's (`node_move`, `copy_action`, `report_action`, `entry_delete`). Where the German is a plural and the English is not, the
    English is written as a plural of one text (the generator wants one shape). *Cost:* "Not moved: {1}" and
    `select_summary` compose the web's heading and lines into one sentence.
  - **L10 — The bridge.** The dispatch's one method hit the JVM's size limit with the new arms ("Method too large"), so the
    selection's calls are dispatched by `selecting()`; `validate_mobile_ui_direction.py`'s `WIRE_PLATFORM` gained `Select` and
    `Selection` (the selection is the platform's). *Cost:* a validator changed: `validate_guard_gaps.py` ran.
  - **L11 — The structure guard's carriers.** *Found:* the guard's (c) reads a plan's list by its element class's simple name;
    nested carriers named `Entry` and `Action` made three unrelated `List<Entry>` filters red. *Choice:* renamed
    (`BarEntry`, `BarAction`); the name collision is a blind spot of the guard — follow-up. The bridge's Rust
    names a wire type by its simple name too: a nested `LevelSelection.Shown` took the name `Shown` from the safety
    code's (Linux's `safety.rs` imports `wire::Shown`), so the selection is `LevelSelection.Picking`.
  - **L12 — A move sheet's "here".** `MoveSheet.here(state)` is a `Notice` (a selection's names the node that blocks the place);
    `hereWord` is gone and the bridge's `MoveShown.here` is a notice.
  - **L13 — Each platform's look.** Android: the level's tool an icon toggle, a row or tile one toggle with its checkbox, the
    bar a surface above the list (Material's contextual actions); Apple: Files' Select — the toolbar toggle, check circles,
    the bar at the bottom; Linux: Files' selection mode — the header toggle (`selection-mode-symbolic`), check rows, an action
    bar under the level; Windows: Explorer's check boxes — an `AppBarToggleButton`, a `CommandBar` under the level.
  - **L14 — The bites and the proofs.** Each restored with `/bin/cp -f`: the report's hint counted in `LevelSelection.bar`
    turns `PlanVectorsTest`'s `selection` red, and in the web's `selectionBar` its `plans.vectors.test.ts`; a refused node's
    restore offered with what went turns `SelectionTrashTest` red; a refusal that stops the rest `MoveSheetTest`; a report
    above the cap that may still be sent `ReportSheetTest`; a selection kept across another place `ShellModelTest`; a `_ =>`
    arm in Linux's drawing of `SelectionAction` `validate_mobile_structure.py`; `Select` missing from `WIRE_PLATFORM`
    `validate_mobile_ui_direction.py` (22 violations). Green: `:core:jvmTest`, `:composition:jvmTest`, `:bridge:jvmTest`,
    `compileKotlinJs compileTestKotlinJs`, `macosArm64Test`, `:android:assembleDebug`, the XCFramework, `xcodegen` and the
    macOS ad-hoc build, Linux `run.py build` (clippy, tests) and `run.py test`, Windows `run.py build` (the app, the bridge's
    and the app's tests; PdfWebTest needs a scheduled task this pass registered none of) and `run.py test`, the web's
    `pnpm run check` under Node 22 (its timeout-only reds under load green on one re-run), every validator and
    `validate_guard_gaps.py`. **Not clicked:** no app's Select was clicked (no throwaway emulator, Mac, Linux or Windows
    pass ran under the machine's load); the passes are the coordinator's and the owner's: a level's Select on, rows and
    tiles turned, All, None, the count with a tab hiding some, each action's sheet over the selection (a move with a
    refusal, its one Undo; a copy; a report and above 100 its folder; the delete's question and its one Undo), Cancel.

- **As built: the sign-in's entries** (2026-11-23, branch `claude/signin-entries`; P9's follow-up, inbox #1105). The web's
  two entries on every app's sign-in, in the core: `SignInEntry.LOCKED` (*I don't have my Secret Key with me*, the web's
  `sessions.lockedEntry`, right after Submit and Cancel) and `SignInEntry.FORGOT` (*Forgot your password?*, after the
  kit, before *Open a link*), each opening a gate of its own — `Gate.Locked` (uc.account.sessions E3) and `Gate.Recover`
  (uc.account.recover-access steps 1–2) — whose entries are plans too (`LockedPlan`: sign out everywhere, back;
  `RecoverPlan`: send until sent, back). No endpoint and no field is added: the apps send what the web sends
  (`requestLoginChallenge`, `verifyLogin`, `revokeOtherSessionFamilies`, `logout`, `requestPasswordRecovery`). The
  decision log, kept by its implementer:
  - **S1 — The recovery's link half stays the web's.** *Q:* the brief asked for the recovery with the kit in the core;
    where does the mailed token reach an app? *Options:* a custom scheme or an app link carrying it; the mailed link pasted
    into the app; the web's page finishing it. *Choice:* the app asks for the link (`SessionModel.requestRecovery`, the web's
    `requestRecovery`, a 503 said as the web's `mail_unavailable`, every other failure as its `requestFailed`) and says that
    the link opens in the browser, where the kit and a new password finish the reset (`recovery_on_web`); the web's page
    then offers *Open in the veyq app* (ADR 0150 § O2). A scheme carrying a secret is ADR 0150 § O's rejection; pasting a
    mailed link the owner rejected for the address change (ADR 0148 decision 6, 2026-11-18). So release, open and
    complete are not ported — without a transport no ADR allows they would be dead code; whether the app finishes a reset
    itself is the owner's (inbox #1106). *Cost:* a reset begun in the app ends in a browser.
  - **S2 — The locked sign-in is the login alone.** `VeyqAccount.signInLocked`: `connect` without the device (not even
    the instance salt is kept), the login, no `key-material`; a `SessionModel.LockedSession` holds the tokens in memory
    only and renews an expired access token once over its refresh token (the locked screen can stand longer than an access
    token lives). *Evidence:* `SignInEntriesTest.theLockedLoginReadsNoKeyAndKeepsNothingOnTheDevice` (the requests made,
    no device write), `.anExpiredAccessTokenIsRenewedOnceAndAWrongPasswordIsRefused`.
  - **S3 — The order is the web's.** `SessionModel.signOutEverywhere`: every other family revoked first, then this one
    signed out; a revoke that fails stops there (`account_settings_revoke_failed`, the web's `sessions.revokeFailed`), the
    session kept for a retry; afterwards the sign-in says `locked_done`. *Evidence:* `.theLockedSignOutRevokesTheOthersFirstThenThisOne`,
    `.aRevokeThatFailsCutsNothingOffAndKeepsTheSessionForTheRetry`.
  - **S4 — A sign-out of this device that fails.** The web's `lockedDoneStillHere` points at its card's own *Sign out*,
    which the app has not; the app keeps the session and says so (`locked_done_still_here`, apart in `web.json`), the
    action runs again (the revoke is idempotent). *Evidence:* `.aSignOutOfThisDeviceThatFailsSaysSoAndRunsAgain`.
  - **S5 — The way back signs in anew.** The web unlocks over the session the locked entry holds; the app's sign-in is one
    login and unlock, so `lockedBack` signs the locked session out (best effort, its tokens forgotten either way) and
    the sign-in after it (`Gate.SignIn.keepsNothing`, the form saying `locked_back_hint`) enrols no trust and makes no
    offer. The traces it still leaves are the web's named two in their app form: the register's device entry and family
    join (`recordThisDevice`, the web's `noteThisDevice`) and the instance salt (`keepInstanceSalt`, public). *Evidence:*
    `.theWayBackSignsInKeepingNothing`, `.anOrdinarySignInStillTrustsAndOffers`. *Cost:* one more login than the web's.
  - **S6 — Words.** Eighteen twins of the web's `sessions.locked*` and `recovery.*` (`web.json`); `locked_scope_note` (the
    web's names a browser's cookie and its toggle) and `locked_done_still_here` apart; `recovery_on_web` the app's own.
  - **S7 — Each platform's look.** Android: text buttons under the form, the locked screen's action an error-coloured
    button asked first in an `AlertDialog`, the system Back as each screen's Back; Apple: a grouped form per screen, the
    action `.destructive` asked in an `.alert`; Linux: button rows in the boxed list, each screen a way-in page, the
    action a destructive pill asked in an `AdwAlertDialog` (Cancel the default); Windows: buttons in the column, each
    screen a page, the action critical-filled asked by `Dialogs.Confirm` (Cancel the default). The desktops reach the
    model through the bridge's `Session.SignInLocked`, `Session.SignOutEverywhere`, `Session.LockedBack`,
    `Session.ForgotPassword`, `Session.RequestRecovery`, `Session.BackToSignIn`, `SignIn.LockedPlan`, `SignIn.RecoverPlan`
    (`SecretFreeSchemaTest` lists the locked sign-in's password as typed in).
  - **S8 — The bites and the proofs.** Each restored with `/bin/cp -f` (measured 2026-11-23, seven of twelve red at
    once): the sign-out's two calls swapped; `door.trust` on the way back's sign-in; `key-material` read after the locked
    login; the 503 worded as a failed request. Green: `:composition:jvmTest`, `:bridge:jvmTest` (the dispatch's one method
    hit the JVM's size limit again, so these calls go through `signInEntries`, as L10's `selecting`; the plans' wire names
    sit under `SignIn.` so `validate_mobile_ui_direction.py` names them the platform's), `:core:jvmTest` but
    `UnlockForgetTest`'s setup, refused by the offline store's 5 % free-space floor on this Mac's 98 %-full disk (not this
    change: its outbox `put`), `:android:assembleDebug`, the XCFramework, `xcodegen` and the macOS build (which also needed
    #1065's `MediaUploads.upload` given its `filed` on Apple's share pick-up — Swift sees no Kotlin default), Linux
    `run.py build` (clippy with `-D warnings`, 101 tests, the build; its last step, the sandbox skips in the Fedora
    container, failed on Docker's full disk; its re-run after the plans' wire names moved under `SignIn.` — two Rust variant
    names, checked against the generated `bridge.rs` — stopped on that disk before building, owed), Windows `run.py build` (the app, the bridge's and the app's tests; its last
    step needs the scheduled task `veyq-app-signin`, never registered), every validator. **Not clicked** on any app (no
    device pass ran): the passes are inbox #1105's.

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
