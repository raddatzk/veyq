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
- **Order:** after `cr-apps-gates-in-the-core` (its platform phases edit the drop and dialog files this one moves) and
  after `cr-app-offline-writes` phases 7–10 (a move under a running implementation is a merge conflict in every file).
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

- [ ] **1 core** — L4: `FileSave` to `blob/`, R6's files resolved; the guard with rule (c). · Done when: `:core:jvmTest
  :core:macosArm64Test`, `:bridge:jvmTest` green; the guard green. **Bite:** an `import …mobile.depot.*` planted in a
  `memories/` file → red.
- [ ] **2 Android** — L1–L3, rules (a) and (b) on for Android. · Done when: `:android:assembleDebug`, its unit tests,
  the words, guidelines and direction guards green. **Bites:** a Memories file using a Pot composable → red; the shell
  naming `MemoryScreen` → red.
- [ ] **3 Apple** — the same. · Done when: both Apple builds green, the guards green. **Bite:** a Memories view using
  `PotScreen` → red.
- [ ] **4 Linux** — the same; `raster_map.rs`' and `memory_page.rs`' crossings resolved through the registry or core
  UI. · Done when: `python3 linux/run.py test` and `build` green, the guards green. **Bite:** `crate::apps::depot::…`
  from `apps/memories` → red.
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
