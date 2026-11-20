# ADR 0154 — Every app draws the core's gates, plans, notices and formats through a core UI its applications plug into, as the web's do
- Status: Proposed (refined 2026-11-20; the CRs' owner questions open)
- Built: no — the CRs `cr-apps-gates-in-the-core`, `cr-apps-words-guard-reads-every-ui`, `cr-apps-core-ui-layer`,
  `cr-apps-structure-from-core-plans` and `cr-apps-notices-and-formats-from-the-core` build it
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
