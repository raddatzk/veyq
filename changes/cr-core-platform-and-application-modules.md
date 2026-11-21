---
id: cr-core-platform-and-application-modules
title: The Kotlin core splits into a platform module and one module per application — `:core`, `:apps:<application>`, `:composition`, the direction held from the build files
status: planned
satisfies: []
adrs: ["0155", "0079", "0154", "0123", "0136", "0046"]
invariants:
  - "no behaviour changes on any client: the move is structure only; every existing core, bridge, Android, Apple, Linux, Windows and core-js check stays green, and the Apple framework's header and the bridge's bindings diff empty except where a phase names the change"
  - "an application module depends on the platform module only — never the reverse, never another application — held by a bite-tested guard over the build files, not by review"
  - "a member the platform kept `internal` from the clients stays invisible to them: one an application needs is opened only behind an opt-in only application modules take, and hidden from Objective-C"
  - "the web's search is untouched in behaviour and size: `@veyq/core-js/search` bundled alone stays within ADR 0155's 40 KB gz, its vectors pass through the shipped module"
  - "operator-blind: nothing about what is sealed, under which purpose, or what the server sees changes; the crypto suite stays in the platform module and answers contracts/crypto/vectors/v1.json on every target"
---

## Goal

[ADR 0155](../docs/decisions/0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)
decision 7.1, the owner's order of 2026-11-21: before any application's rules move to the web, the Kotlin core
(`src/mobile/core`, one Gradle module today) splits into **a platform module and one module per application**, as the
backend's `:core` and `:apps:<application>` ([ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).
An application may use the platform, never the reverse and never another application, and a guard reads that from
the build files. Two reasons, both binding: the direction the backend and the web already hold, which the core holds
today only by an allow-list of 18 files that name applications from the platform; and the measured fact that one
compiled JavaScript module cannot carry a second façade without the first one's users paying for it (ADR 0155
§ Consequences: the search's entry 28,189 → 59,587 B gz with Expenses' façade beside it). This CR ends with that
second reason measured on the real modules: per-module granularity, each module its own JavaScript file, against the
search's 40 KB alone and Expenses' 25 KB net. Expenses' parked move
([cr-web-expenses-on-the-core](cr-web-expenses-on-the-core.md), branch `claude/p2-impl`) resumes on top of this.

## Context & constraints

- **The substrate** (named before every number below): branch `claude/core-split-refine` at `b6e517ca2`, 2026-11-21.
  Nothing was built for this refinement; the inventory is read from the sources (an Explore pass plus the locators
  below, comments and strings blanked through `scripts/kotlin_lexer.mask` where it says so).
- **What is an application on the core.** Exactly the `ApplicationId` entries,
  `src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/navigation/Applications.kt`: `memories`, `depot`,
  `expenses` — the backend's three (`src/backend/settings.gradle.kts`). Music (`audio/`) and the map (`map/`) are
  platform packages (the web's `core/` holds the player and the map too); Memories' own parts of them are
  `memories.music`, `memories.place`, `memories.tracks`.
- **Sizes** (measured, `wc -l` per package directory over all source sets, generated sources not counted): ~104k Kotlin
  lines; platform ~43k main + ~27k test, Memories ~11k + ~9k, Expenses ~6k + ~3.6k, Depot ~2.4k + ~1.7k, the two
  root-package files (`AppKinds.kt`, `RowDetails.kt`) ~0.5k.
- **Application → application today: none in main source sets** (rule c1 of
  `scripts/validate_mobile_ui_direction.py`, green). One test crosses: `jvmTest/.../expenses/FolderLiveTest.kt`
  reads `depot.DepotFiles`.
- **Platform → application today: the 18 `CORE_ROOTS`** of `validate_mobile_ui_direction.py` (lines 114–149, each with
  its reason). How each one is read by others decides how it leaves the platform (decision 3); measured at refine,
  non-root main files that name it, comments blanked:

  | Root | Read by (non-root main files) | So |
  |---|---|---|
  | `AppKinds.kt` | ~25 platform and application files (`AppKinds.share`, `.tokenInput`, `.Facts`, `.Create`, `.pins`, `.rekeys`, …) | invert: the platform's registry |
  | `navigation/ShellModel.kt` | `expenses/PotModel` (a `ShellModel` parameter), `memories/GalleryModel` (`TRASH_UNDO_MS`), `export/{FileDownloads,NodeDownload}`, `navigation/{MoveSheet,NodeHeaderModel}`, `offline/AppleQueued` | lift; what those read narrows into the platform |
  | `words/Notices.kt` | 12 (platform sheets and application models) | split: the platform's table, each application's lines its own |
  | `offline/KeptRead.kt` | 10 (`blob`, `offline`, `session`, every application) | split: the platform's kept reads, each application's registered |
  | `sharing/GuestAccess.kt`, `sharing/GuestView.kt` | 8 and 6 (platform `sharing/*`, each application's guest door) | split: the guest page's variants through the registry |
  | `offline/QueuedTray.kt` | `ShellModel`, `offline/AppleQueued` | lift with the shell |
  | `navigation/{Drop,AddHere,ShareSheet}.kt`, `session/LinkForm.kt`, `RowDetails.kt`, `nativeMain/blob/PickedPaths.kt` | only another root, or nothing | lift |
  | `appleMain/{sharing/AppleSharing,audio/AppleAudio,export/FileDownloads,session/Apple}.kt` | Swift | lift (Swift's adapters over applications) or move to the application they adapt |

- **`internal` crossings** (read, not exhaustive — a compile lists the rest): `session/Account.kt`'s `http` (15
  application files), `kept` (6), `settle`; `sharing/GuestClient.kt`'s `http`; `blob/ApplicationBlobs.kt`'s
  `openKey` (7), `frames`, and `SharedBlob`'s key fields. `internal` is per module, so each stops compiling at the
  split.
- **Tests that cross**: ~30 platform test files use an application (the offline queue and pins, guest doors, the
  subtree import, the search vectors, notices); ~50 test files read `AppKinds`; shared test support sits in source
  directories added to core's test sets — `src/testDevice/kotlin` (`commonTest`), `src/liveTest/kotlin` (`jvmTest`), and
  `offline/PlayedTenant.kt` names Expenses and `memories.banner`. The bridge compiles core's two directories into its own
  `jvmTest` by path (`src/mobile/bridge/build.gradle.kts:66-67`). Kotlin Multiplatform has no `testFixtures`.
- **The consumers**: `:bridge` and `:android` each `implementation(project(":core"))`
  (`bridge/build.gradle.kts:48`, `android/build.gradle.kts:99`); the bridge's dispatch (`Wire.kt`, `Bridge.kt`) is
  one sealed `Call` over every application. Apple links one static framework `VeyqCore`, declared in
  `core/build.gradle.kts` (`XCFramework("VeyqCore")`, three Apple targets) and built by Xcode's pre-build step
  `./gradlew :core:embedAndSignAppleFrameworkForXcode` (`apple/project.yml:62,132`), searched under
  `core/build/xcode-frameworks` (`project.yml:92` and its twin), its header read by `apple/generate_plans.py`
  (`PREFIX = "VeyqCore"`); 64 of 71 Swift files `import VeyqCore`. Linux and Windows reach the core through the
  bridge library and run core's own native test binary (`linux/run.py:147-148`, `windows/run.py:145,293,1124`).
- **The JavaScript package**: `:core:jsPackage` (`core/build.gradle.kts:463`) syncs the production library into
  `src/packages/core-js/dist/` — `veyq-mobile-core.mjs` and the standard library's files, per-module granularity
  already (Kotlin's default; no `outputGranularity` set) — with the KT-68281 sort step; `scripts/build.py` runs it,
  `package.json` maps `./search` to `dist/veyq-mobile-core.mjs`; `src/slice.test.ts` holds the 40 KB.
- **Generated sources** added to core's `commonMain`: `generateApi` (core's specs plus `applicationSpecs` —
  `expenses/expenses`, `depot/files`, `memories/{media,memory-banner,item-favorites,share-media}` — into
  `…mobile.api.<application>`), `generateMajor`, `generateTheme`, `generateWords` (`Msg`). Native interop (`sodium`,
  `webp`, `utf8proc`, `wincred`, `secret`) is used by platform code only.
- **The guards naming the core's tree**: `validate_mobile_ui_direction.py` (`CORE_SRC`, `APPLICATIONS`, `CORE_ROOTS`,
  `*Main` sets of one tree), `validate_mobile_search_names_no_application.py` (`CORE_SRC`, the search package),
  `validate_mobile_structure.py` (`CORE_SRC`, `core_main_sources`, `plan_enums` — Memories' `@Plan` enum
  `TileAction` lives in an application package; `apple/generate_plans.py` imports it). `scripts/repo_layout.py` has
  `MOBILE`, `MOBILE_ANDROID`, `MOBILE_APPLE`, … and no core constant. The backend's
  `validate_backend_module_graph.py` reads the build files, not imports (settings ↔ directories, the edges, a package
  in one module), and is the model.
- **Out of scope, observed**: Android's core UI (`android/core/AppModel.kt` and ~10 files) reaches application packages
  of the core directly; `validate_mobile_ui_direction.py` checks Android's own `apps/<x>` only. Whether that breaks ADR
  0154 decision 3 is that ADR's question; this CR keeps Android compiling through `:composition` and changes no UI.

## Decisions (technical, settled here)

1. **Four kinds of module, directories as the backend's.** `:core` stays at `src/mobile/core` and becomes the platform
   (its name and directory kept: the JavaScript file `veyq-mobile-core.mjs`, `konan_toolchain.py`, the native interop
   and the backend's word for it stay); `:apps:<application>` at `src/mobile/apps/<application>`, one per
   `ApplicationId`; `:composition` at `src/mobile/composition`, the one place that names every application — it fills
   the platform's registry, holds what was lifted (decision 3), packs the Apple framework and produces the JavaScript
   package; `:testing` at `src/mobile/testing`, the shared test support (decision 6). Edges: `:core` → none;
   `:apps:<a>` → `:core`; `:composition` → `:core` and every `:apps:*` (as `api`, so its consumers see them);
   `:bridge` and `:android` → `:composition`; test configurations may add `:testing`. **Packages do not move**: an
   application's files keep `me.raddatz.veyq.mobile.<application>`, so no Swift, bridge, Android or ObjC name
   changes; what is lifted takes `me.raddatz.veyq.mobile.composition`.

2. **`AppKinds` becomes the platform's registry, filled once by `:composition`** (ADR 0154 decision 3's "the
   platform's one registry, which follows the core's `AppKinds`"; the web's `register.ts` per application). Its
   members and their call sites stay; its value types (`Facts`, `Create`, the token input, …) stay in the platform;
   each `when` arm that names an application's type becomes a lookup into one contribution per application
   (`<Application>Kinds` in that application's module, implementing a platform interface). `:composition` installs
   the three before any session opens, from each client's start path; a read before the install or a second install
   fails loudly — never a silent default, since an unregistered kind would write a node the app cannot name. A
   `:composition` test holds that every `ApplicationId` has exactly one contribution and every `Create`/`Queued`
   value an application owns is answered by it. Why not a static `when` in `:composition`: the platform's own
   callers (`SessionModel`, `Account`, the rotation) read these tables, and the platform cannot see `:composition`.

3. **Each of the other roots leaves the platform by one of three moves, chosen by who reads it** (the table above,
   re-measured in phase 1): **lift** — moved whole into `:composition` when only roots, Swift or nothing read it;
   **narrow** — what platform or application code reads of a lifted file comes down into the platform as the least it
   needs (`ShellModel.TRASH_UNDO_MS` a platform constant, `PotModel`'s `ShellModel` parameter a platform interface
   the shell implements); **split** — the platform keeps the mechanism and each application registers its part
   (`Notices`' application lines, `KeptRead`'s application reads with their generated models, the guest page's
   variants), through decision 2's registry. ShellModel is lifted, not redesigned: a hook per screen model is the
   design `CORE_ROOTS` already names as one of its own, and lifting keeps every client's reading of the shell
   unchanged (the header diff proves it). The Apple adapters over one application move into that application's
   `appleMain`; those over several are lifted.

4. **`internal` the applications need becomes public behind an opt-in, hidden from Objective-C.** A platform
   annotation `@PlatformApi` (`@RequiresOptIn(level = ERROR)`) plus `@HiddenFromObjC` on each such member; only
   `:apps:*` and `:composition` opt in (`optIn` in their build files), and the guard (decision 7) refuses the opt-in
   anywhere else. Swift and the bridge's clients see exactly what they see today. Why not friend modules: Kotlin's
   `-Xfriend-paths` is an internal compiler flag with no multiplatform support, and the pilot's rule is no
   experimental compiler flag (ADR 0155 decision 3).

5. **One Apple framework, one bridge library, one JavaScript package — each from `:composition`.** The XCFramework
   keeps its name `VeyqCore` and moves to `:composition`, which `export`s `:core` and every `:apps:*` (no transitive
   export); `apple/project.yml`'s pre-build step and search paths follow it. The bridge depends on `:composition`, its
   dispatch unchanged. `jsPackage` (with `jsPackageDev`, the KT-68281 step and `jsKotlinVersion`) moves to
   `:composition`'s production library, per-module granularity, so `dist/` holds one file per module; the package's
   entries map to the module files they need — `./search` stays `veyq-mobile-core.mjs` — and `:composition`'s own
   file is never an entry. Every module keeps `js()` (ADR 0155 decision 3: the whole common code compiles for
   JavaScript). Each module declares the same targets and hierarchy as today's core (one convention in
   `src/mobile/build.gradle.kts` or a `buildSrc` plugin, chosen in phase 2 by what Gradle's configuration cache
   accepts), so `commonMain`/`threadedMain`/`bridgedMain`/`appleMain` mean the same in each.

6. **Tests go where what they test lives.** A test of one application's behaviour moves to that application's module
   (the `Pot*`, `Queued*Expenses` cases); a test across several applications or of the composition
   (`OfflineScreensTest`, `GuestDoorsTest`, `SubtreeImportTest`, `ShareInboxTest`, `FolderLiveTest`, …) moves to
   `:composition`'s tests; a platform test that used an application only as a convenient fixture is rewritten over a
   played kind — never kept by giving the platform's tests an application dependency. `:testing` is a multiplatform
   module whose `commonMain` is today's `testDevice` and `PlayedTenant`'s platform half, whose `jvmMain` is today's
   `liveTest`; the bridge's path-based `srcDir` becomes a dependency on it. `generateApi` runs per module: core's
   specs in `:core`, each `applicationSpecs` entry in its application's module.

7. **The direction guard reads the build files, as the backend's does**: `scripts/validate_mobile_module_graph.py`,
   new, beside `validate_backend_module_graph.py` (one guard per build). Its rules:
   (a) `src/mobile/settings.gradle.kts`'s includes equal the module directories, and the `:apps:*` set equals
   `ApplicationId`'s entries both ways; (b) the edges of decision 1, every configuration read (`implementation`,
   `api`, `compileOnly`, `runtimeOnly`, the test ones), no `:apps:a` → `:apps:b`, no `:apps:*` → `:composition`, no
   `:core` → anything; (c) a package lives in one module, an application's packages
   (`…mobile.<application>`, `…mobile.api.<application>`) only in its module, `…mobile.composition` only in
   `:composition`; (d) `@PlatformApi`'s opt-in only in `:apps:*` and `:composition`. The compiler then refuses any
   import the graph does not carry, so the guard needs no import rule. `validate_mobile_ui_direction.py`'s c1, c2 and
   `CORE_ROOTS` retire into it (the allow-list is empty at the end; an entry that names no application was already
   red); its platform-UI rules stay. **Bite-tested** (the memory note's rule): each rule gets the edit that must make
   it red, run and reverted before the guard is called sound — `implementation(project(":apps:depot"))` in
   `apps/expenses/build.gradle.kts`; `project(":apps:memories")` in `core/build.gradle.kts`; a
   `package me.raddatz.veyq.mobile.expenses` file under `core/src/commonMain`; an `ApplicationId` entry with no module;
   an `optIn` of `PlatformApi` in `bridge/build.gradle.kts`; a module directory missing from the settings.
   `validate_guard_gaps.py` runs, since guards change.

8. **Paths come from `scripts/repo_layout.py`**: `MOBILE_CORE`, `MOBILE_APPS`, `MOBILE_COMPOSITION`, `MOBILE_TESTING`
   join its constants; every guard that spelled `MOBILE / "core" / "src"` reads the source roots of every module
   (a helper beside the constants), so `plan_enums` sees Memories' `TileAction` where it now lives. `linux/run.py` and
   `windows/run.py` run every multiplatform module's native test binary, the list read from the settings, never
   spelled.

## Inventory (locators — run at refine, read as lists; re-run at implement)

| What | Locator (from the repo root) | At refine |
|---|---|---|
| The roots and what names them | `/usr/bin/python3 scripts/validate_mobile_ui_direction.py`; `CORE_ROOTS` in that file | green, 18 roots |
| Who reads a root (decision 3's verb) | for each root's stem, `kotlin_lexer.mask` over every non-test `src/mobile/core/src/*Main` file and `\bStem\b` — the script is three lines over `scripts/kotlin_lexer.py`'s `mask` | the table in § Context |
| `AppKinds`' members in use | `grep -rhoE "AppKinds\.[A-Za-z]+" src/mobile/core/src/*Main \| sort \| uniq -c \| sort -rn` | 28 distinct members, `share` 12, `tokenInput` 11, `Facts` 10, `Create` 10 |
| `internal` an application calls | after phase 2's `git mv`, `./gradlew :apps:<a>:compileKotlinJvm` lists them; before it, `grep -nE "internal (val\|fun\|suspend fun)" src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/{session,sharing,blob,offline}/*.kt` | `Account.http`/`kept`/`settle`, `GuestClient.http`, `ApplicationBlobs.openKey`/`frames`, `SharedBlob`'s fields |
| Tests that cross | `grep -rlE "import me\.raddatz\.veyq\.mobile\.(memories\|depot\|expenses)\b" src/mobile/core/src/*Test src/mobile/core/src/{testDevice,liveTest}` minus the application directories | ~30 platform test files, one application test (`FolderLiveTest`) |
| Everything naming `:core` or the core's tree | `git grep -nE ":core:\|mobile/core\|MOBILE ?/ ?\"core\"\|core/build/" -- src/mobile scripts launcher .github src/packages/core-js src/web/vite.config.ts infra CLAUDE.md docs site` | `apple/project.yml`, `bridge/build.gradle.kts`, `linux/run.py`, `windows/run.py`, `windows/Veyq.Bridge/Veyq.Bridge.csproj`, the three guards, `core-js/scripts/build.py`, `launcher/{linux,windows,web}`, `src/mobile/CLAUDE.md`'s commands, `checks.yml` |
| Generated-source tasks per owner | `grep -n "applicationSpecs\|generateApi\|generateWords\|generateTheme\|generateMajor" src/mobile/core/build.gradle.kts` | one `generateApi` over six application specs |

## Phases

**Phase 1 — the seams, inside the one module.** No Gradle change: in today's `:core`, `AppKinds` becomes the registry
with three contributions in the application packages and the install in a `composition` package (decision 2); each
root lifted (into the `composition` package), narrowed or split (decision 3); the `internal` members applications use
become `@PlatformApi` (decision 4); tests re-homed by package (decision 6). The guard holds the intermediate state:
`CORE_ROOTS` shrinks to the `composition` package's files only, every other entry gone.
Done when: `./gradlew jvmTest macosArm64Test compileKotlinJs compileTestKotlinJs :bridge:jvmTest :android:assembleDebug`
green from `src/mobile`; `VeyqCore.h` (from `:core:assembleVeyqCoreDebugXCFramework`, sorted by declaration) and the
bridge's `generateBindings` output each diffed against copies taken before the first edit — empty, or each line named
here with why; the Apple app compiles ad hoc (`xcodebuild … CODE_SIGNING_ALLOWED=NO`, macOS and iOS simulator);
`python3 linux/run.py test` and `python3 windows/run.py test` green (the Windows VM is disposable);
`cd src/packages/core-js && pnpm run build && pnpm run check` green; every `scripts/validate_*.py` green.

**Phase 2 — the modules.** `git mv` each application's directories into `src/mobile/apps/<application>`, the
`composition` package into `src/mobile/composition`, the test support into `src/mobile/testing`; the settings, the
build files and the shared target convention (decision 5); `generateApi` per module; the XCFramework, `jsPackage`
and its steps onto `:composition`; `apple/project.yml`, the bridge's and Android's dependencies, `run.py`'s binaries,
`core-js/scripts/build.py`'s task path, `launcher/` and `checks.yml` where they name a task; the package's
entries (decision 5). Commit phase 1 first — this phase moves every file it touched.
Done when: phase 1's whole list again, with `:composition:assembleVeyqCoreDebugXCFramework` and per-module test
tasks; the header and bindings diffs against phase 1's end empty; `src/web`'s `pnpm run check` and `pnpm run build`
green; `@veyq/core-js`'s three clean builds one digest (`src/mobile/CLAUDE.md` § Prove your work's command, the task
path updated); the search's entry alone within its 40 KB (`src/slice.test.ts`), its size before and after recorded.

**Phase 3 — the guard.** `validate_mobile_module_graph.py` (decision 7) with its six bites; c1, c2 and `CORE_ROOTS`
out of `validate_mobile_ui_direction.py`; `repo_layout.py`'s constants and the source-root helper; the three mobile
guards and `apple/generate_plans.py` over every module (decision 8). One commit with the guard and its first green run.
Done when: every `scripts/validate_*.py` green including `validate_guard_gaps.py`; each bite red with its named
message, then reverted; `python3 apple/generate_plans.py` writes the same Swift as before.

**Phase 4 — the measurement ADR 0155 waits for.** On a throwaway branch from phase 3's end — never merged — the
parked Expenses commits (`c76d3212f`, `c5793db14`, `cbf54e21e`, `243e89185`, `7b8af11cf` from `claude/p2-impl`) are
replayed onto `:apps:expenses` (its façade in `apps/expenses/src/jsMain`, its entry mapped to its module's file), only
as far as the package builds and its check runs. Measured with the parked branch's method (`src/slice.test.ts` there:
esbuild bundling as the web does, minified ESM, `@noble/*` external, gzip level 9, Node 22): the search's entry
bundled alone, and the search and Expenses bundled together, from which **Expenses' net** = together − search alone −
the 6,194 B gz of TypeScript its move deletes. The gate: **search alone ≤ 40,960 B gz and Expenses' net ≤ 25,600 B gz**;
both gross numbers printed beside, and the `.d.mts` checked for the façade's `external interface` shapes (the per-file
defect). The same branch re-runs the parked bench (`buildLedger`, `checkLedger` on the 10,010-revision Pot, median of
15 after 3 warm-ups) against ADR 0155 decision 7.3's 2.5× and 100 ms, since a module boundary is new on the hot path.
Done when: the numbers are in ADR 0155 § Consequences with the command and commit they came from, the throwaway
branch deleted. **A miss goes to the owner with its number** (ADR 0155 decision 2's rule); the split stays either way
— its direction reason holds without the size one — and the miss decides how Expenses resumes, not whether the
modules stay.

**Phase 5 — docs.** ADR 0155 (`Built`: decision 7.1; § Consequences phase 4's numbers); ADR 0079's Consequences (the
core's split beside the backend's and the web's); `docs/crosscutting/native-apps.md` and `docs/apps/README.md` (where an
application's core code lives); the root `CLAUDE.md`'s `src/mobile/` line (`core` → the platform and the application
modules); `src/mobile/CLAUDE.md` (every `:core:` command that moved, § Prove your work per module, the framework's
task); the [cr-web-expenses-on-the-core](cr-web-expenses-on-the-core.md) status note (the split through, what the
measurement says for its decision 4); inbox #1046.
Done when: every `scripts/validate_*.py` green; `pnpm run check` in `site/` green if a page changed, or this phase says
none speaks for the module layout.

## Risks

- **Per-module production output may keep more than one module's dead code** (whole-program dead-code elimination
  versus per-module files): the search's entry could grow from today's 28,189 B gz without any rule moving. Phase 2
  measures it alone before Expenses exists; a growth there is a finding before phase 4, not inside it.
- **Objective-C names across exported modules**: two modules declaring the same simple name get a suffix in the
  header. Phase 1's and 2's header diffs catch it; the fix is a rename in Kotlin, named in the phase.
- **Native interop seen from a dependent module**: application modules use no interop themselves, but compile their
  `bridgedMain`/`appleMain` against `:core`'s commonized interop klibs (`kotlin.mpp.enableCInteropCommonization`).
  If a shared native source set cannot see them, that application's native code moves to a narrower set or into
  `:composition`, named in phase 2.
- **Build time**: four modules compile and link more than one. The CI `frontend` job's 15-minute bound (ADR 0155
  decision 2) and the Apple pre-build step are timed in phase 2 and recorded; a growth past the pilot's +3 minutes is
  reported, not silently accepted.
- **The `SearchField.test.ts` red of the parked branch** is caused by a module hop between the web and the search's
  façade; this CR changes that hop too. Phase 2 runs the web's check (green on `main`); phase 4 notes whether the red
  reappears with Expenses' entry on per-module output, for the resumed CR.
- **A registry read before its install** (decision 2) fails at runtime, not at compile: each client's start path is
  proved by its own build's run — the Android and Apple apps opened once to the shell (ad hoc, emulator/macOS), the
  Linux bridge's `jvmTest`, `run.py test` on Windows.

## Questions for the owner

None. Every choice above is technical (module names and layout, the registry, the opt-in, the guard's form, where
tests go). The one way this reaches the owner is a missed number in phase 4 (or a build time past its bound), handed
over with the measurement.

## Definition of done

The core is `:core`, `:apps:memories`, `:apps:depot`, `:apps:expenses`, `:composition` and `:testing`; the guard holds
the direction with its bites recorded; every client builds and its checks pass as in phase 1's list; the Apple header
and the bridge's bindings are unchanged except where named; the search's entry is within 40 KB; phase 4's numbers are
in ADR 0155; every `scripts/validate_*.py` is green, `validate_guard_gaps.py` with it.

## Retrospective

(Filled by the implementer.)
