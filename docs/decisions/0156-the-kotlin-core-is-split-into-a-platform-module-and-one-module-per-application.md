# ADR 0156 — The Kotlin core is split into a platform module and one module per application
- Status: Accepted
- Built: yes
- Date: 2026-11-22
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

[ADR 0155](0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md) decision 7.1
(the owner's order, 2026-11-21): before any application's rules move to the web, the apps' Kotlin core — one Gradle
module, ~104k lines — splits as the backend did ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)):
an application may use the platform, never the reverse and never another application. Two reasons. The direction:
the backend and the web hold it by their builds, the core held it only by a list of 18 platform files allowed to name
an application (`validate_mobile_ui_direction.py`'s `CORE_ROOTS`). And the size: one compiled JavaScript module cannot
carry a second façade without the first one's users paying for it (ADR 0155 § Consequences: the search's entry 28,189 →
59,587 B gz with Expenses' façade beside it).

## Decision

1. **Four kinds of module, the backend's directories.** `:core` (`src/mobile/core`) is the platform; `:apps:<application>`
   (`src/mobile/apps/<application>`) one per `ApplicationId`, its code in its own packages
   `me.raddatz.veyq.mobile.<application>` (unchanged, so no Swift, bridge or Android name moved for it) and its generated
   client `…mobile.api.<application>` from every spec of `contracts/api/<application>/`; `:composition`
   (`src/mobile/composition`, package `…mobile.composition`) the one module that names every application; `:testing`
   the test support the modules' tests share. Edges: `:core` none; an application `:core`; the composition `:core` and
   every application, as `api` and exported into the framework; `:testing` `:core`; the clients (`:bridge`, `:android`)
   the composition; a test set may add `:testing`.
2. **`AppKinds` is the platform's one registry, filled once.** Its members and their call sites stay; each answer for an
   application's kind is that application's part (`ApplicationKinds`: `DepotKinds`, `ExpensesKinds`, `MemoriesKinds`), asked
   with the kind, the one non-null answer taken. The composition installs the parts (`AppComposition.install()`) on every
   client's start path — Android's `VeyqApplication`, Apple's `AppModel`, the bridge's `Bridge` — before any session
   opens. A read before the install fails; the same parts again are no change; other parts fail.
3. **What read applications left the platform by one of three moves.** Lifted whole into the composition: the shell's
   model `ShellModel`, the drop, *Add here*, the share sheet, the row details, the queued tray, the picked paths and
   Swift's adapters over several applications. Narrowed into the platform: what the platform read of them (the trash's
   undo and outcomes into `NodeHeaderModel`, the shell as `ShellDoor` for an application's model). Split: the platform
   keeps the mechanism, each application registers its part — the notices' lines, the share sheet's words, the kept reads
   and what the offline store reaches through them (`ApplicationKept`), the guest pages (`GuestPage` open, each
   application's page nested in its door), and Swift's adapters over one application as extensions of the platform's
   object (an Objective-C category: Swift's call sites unchanged).
4. **What the platform keeps from the clients stays kept.** A member an application or the composition needs of the
   platform's `internal` is `@PlatformApi` — `@RequiresOptIn(ERROR)`, opted into by the core's modules alone — and
   `@HiddenFromObjC`; a constructor is opened only through a hidden `of(…)`. Swift sees what it saw before the split.
5. **One framework, one bridge library, one JavaScript package — each from the composition.** `VeyqCore` keeps its
   name and exports the platform and every application, nothing transitively; the bridge and Android depend on the
   composition; `@veyq/core-js` is the composition's library with Kotlin's per-module output, each module its own file
   (Kotlin's names: the platform's `kotlin_veyq_mobile_core.mjs`, one `.d.mts` for every module's exports), an entry
   mapped to the module file that holds its façade. Every module keeps `js()`; the targets, hierarchy, opt-ins, shared
   dependencies and the generated client are one convention in `src/mobile/build.gradle.kts`.
6. **Tests live where what they test lives.** A test of one application runs in its module, composing it alone
   (`TestApplications.install(<its part>)`, an inert part for every other application); a test across applications, of
   the composition, or of the platform that needs the composed registry runs in `:composition`'s tests.
7. **The build holds the direction.** `scripts/validate_mobile_module_graph.py` reads the settings, the build files
   and the sources: the modules are the settings' and the applications `ApplicationId`'s, the edges run as decision 1
   says, a package lives in one module, the opt-in is the core modules' and every `@PlatformApi` is hidden from
   Objective-C. The compiler refuses every import the graph does not carry.

## Consequences

- **Nothing a client sees changed but what moved.** The Apple framework's header, compared per declaration with the
  one before the split: what moved is renamed (`DerivationDeriver`, `PageMusic`, `DepotGuest.File`, …, Swift renamed in
  seven files), the moved members of `Notices`, `AppleSessions`, `AppleAudio` and `GuestAccess` are categories of the
  same classes, `KeptRead` is a class, and nothing opened by decision 4 shows. The bridge's generated bindings: the same
  types (0 removed, 0 added). Every client built and its tests passed at each step: the JVM tests case for case (1,475
  before, 1,476 after, the one added; a lost contract class caught by that comparison and restored), macOS 1,247, Linux
  1,254, Windows 1,255, the bridge 142, the web's 7,287, `@veyq/core-js`'s 94; three clean JavaScript builds one digest.
- **The search's entry alone, after the split: 27,943 B gz** (27,909 before it; ADR 0155's budget 40,960).
- **Expenses on the split, measured on a throwaway branch** (the parked move of ADR 0155 replayed onto `:apps:expenses`,
  `ecdc90588`, deleted after; the parked method): the search's entry alone **43,091 B gz, over its 40,960**; Expenses'
  net 14,315 B gz, inside its 25,600; `buildLedger` 2.07× and `checkLedger` 1.20× the TypeScript, inside 2.5× and 100 ms.
  The cost moved, not vanished: a bundler drops nothing of a Kotlin module file (its metadata calls run at load), so the
  platform code a façade needs is paid by every entry importing the platform's file — the parked move put its JSON seam
  and money's formats there. **A miss, handed to the owner** (ADR 0155 decision 2's rule); the split stays for its
  direction. Before Expenses resumes, that code leaves the platform's file, each entry gets a declaration file of its
  own, and an application gets a conformance compilation for its own runners.
- **Build time:** a clean `:composition:jsPackage` 30.5 s against 27.0 s before the split (local, fresh processes); the
  CI `frontend` job and the Apple pre-build step were not timed apart.
- **The UI guard's core rules retired into this one**; each platform's UI keeps its own rules. Android's core UI named
  core application packages (inbox #1056, against ADR 0154 decision 3): since 2026-11-22 each application holds what it
  did in a `Holding` it registers, and the UI guard's rule (c) reads those packages (entries 29–37; Apple's is #1066).
- **Left, named:** 82 platform-package tests run in `:composition` rather than over a played kind; the played server
  `PlayedTenant` is not split into the platform's half and the applications'; `validate_mobile_search_names_no_application.py`
  is a second check of what the build now holds; a few kind literals remain in the composition (it may name applications).

## Alternatives considered

- **Friend modules (`-Xfriend-paths`)** for the `internal` crossings: an internal compiler flag without multiplatform
  support, against ADR 0155's rule of no experimental flag.
- **A `when` in the composition instead of a registry:** the platform's own callers (the session, the rotation, the
  offline store) read the tables, and the platform cannot see the composition.
- **A `buildSrc` convention plugin:** a second Kotlin Gradle plugin on the build's classpath beside the catalog's.
- **Keeping the platform's tests over an application's kinds by an application dependency of `:core`'s tests:** the
  platform's tests would compose applications, which is the composition's job.

## Decision log

Kept by the implementer as the decisions were taken (the owner's mandate of 2026-11-21: decided autonomously, written
down so the owner can retrace each one); each entry the question, the options, the choice and why, the evidence
(`measured` with its command, or `remembered`) and what it costs or leaves open. The `scratchpad/cs/tools/` scripts it
names were the implementation session's and are not kept.

1. **2026-11-21 — The substrate and the baseline.** Question: what tree are the "before" copies of `VeyqCore.h` and
   the bridge's bindings taken from? Options: the main checkout (shared, moving), or this worktree before its first
   edit. Chosen: this worktree, branch `claude/core-split` at `d089bffd2`, clean but for nothing (`git status --short`
   empty), copies taken by `./gradlew :core:assembleVeyqCoreDebugXCFramework :bridge:generateBindings` before any
   Kotlin edit. The native libraries (`src/mobile/build/{libsodium,libwebp,utf8proc}`) were copied from another
   checkout's build output, which `build_libsodium.py` and its twins check slice by slice and rebuild where they
   differ. Evidence: measured (the commands above). Cost: none.

2. **2026-11-21 — The registry's shape (decision 2).** Question: how does `AppKinds` reach an application's answer?
   Options: (a) a per-kind descriptor (the web's `KindDescriptor`), each kind one object; (b) one part per application
   (`ApplicationKinds`), every member asked with the kind, the one non-null answer taken; (c) a `when` in the
   composition. Chosen (b): `AppKinds` keeps every member and signature its callers and Swift read, core's own kinds
   (a folder, a kept link) answered inline, the rest `applications.firstNotNullOfOrNull { … }`; (a) would have
   rewritten every call site, (c) the platform cannot see. An application may answer for a platform kind where it acts
   for it (Depot: files shared to a folder, a link guest's upload into a folder). `AppCompositionTest` holds one part
   per `ApplicationId`, no kind owned twice, every queued write sent by one part, every per-kind member answered by at
   most one part. Evidence: measured, `./gradlew :core:jvmTest` 1470 tests, 0 failures (34 skipped live cases).
   Cost: a new per-kind member has to be added to `ApplicationKinds` and `AppKinds` both. Left open: the CR's "every
   `Create` value an application owns is answered" has no registry member to test — a `Create` is dispatched by each
   client to its own dialog, not through `AppKinds`.
3. **2026-11-21 — A second install.** Question: the CR says a second install fails loudly; Android recreates its
   model, the bridge may start twice, and every test class of one JVM installs. Options: fail on any second install;
   no-op on the same parts, fail on different ones. Chosen the second: `AppKinds.install` compares the parts it holds;
   a read before any install fails (`AppKinds read before the applications were installed`). Evidence: measured,
   `AppCompositionTest.aSecondInstallOfOtherPartsFails`. Cost: none known.
4. **2026-11-21 — The platform knew applications beyond the 18 roots.** Question: is the CR's root list the whole
   platform → application knowledge? No: the guard reads package names, not kind literals or shapes. Measured by
   `grep -rnE "\"(MEMORY|CHAPTER|FILE|DEPOT_DROPBOX|EXPENSES_POT)\"" src/mobile/core/src/*Main` outside the
   application packages and by `KeptRead`'s split: `navigation/Library.kt`'s `applicationOf` (a `when` over kind
   literals), `Notices.renameRefused` (`"EXPENSES_POT"`), `ShareWords.of` (a Memory's, a drop box's, a file's and a
   Pot's words) and `AccountOffline`'s pin reach (it walked Memories' media, Depot's files and a Pot's ledger by their
   JSON). Chosen: each becomes a registry member — `objectKinds`/`applicationOf`, `noun`, `shareWords`, `kept`
   (`ApplicationKept` with a `KeptReach` over one scope's kept answers). Cost: `ApplicationKinds` grew five members
   the CR did not list. Left: the literal `"CHAPTER"` in the lifted `QueuedTray` and `"MEMORY"` in the lifted
   `ShareSheet` (composition, which may name applications).
5. **2026-11-21 — Lift, narrow, split (decision 3), re-measured.** Lifted whole into `…mobile.composition`:
   `ShellModel`, `Drop`, `AddHere`, `ShareSheet`, `RowDetails`, `QueuedTray`, native `PickedPaths` and `NodeDownload`
   (an extension of `ShellModel`), Apple's `AppleSharing`, `FileDownloads`, `AppleQueued` (each over several
   applications or the shell). **Deviation:** `LinkForm` is not lifted — only `ShareWords.of` named applications, and it
   splits into the registry (`shareWords`); `LinkForm` stays in `session`. Narrowed: `TRASH_UNDO_MS` into
   `NodeHeaderModel`'s companion (`ShellModel.TRASH_UNDO_MS` aliases it for the clients); `ShellModel.Trashed` and
   `.Renamed` into `NodeHeaderModel` (their only platform reader; no Swift user, `grep -rn` over `apple/`); `PotModel`'s
   `ShellModel` parameter to a platform interface `navigation.ShellDoor` the shell implements (Swift passes the same
   object). Evidence: measured, `/usr/bin/python3 scripts/validate_mobile_ui_direction.py` green with the composition
   package as its only root. Cost: Android's and the bridge's Kotlin imports of the lifted names changed package.
6. **2026-11-21 — Splitting a Swift-facing object without changing Swift: extensions.** Question: how do
   `Notices`' application lines, `AppleSessions`' Pot functions, `AppleAudio`'s track save and WAV wrap and
   `GuestAccess.replace`/`replaceAsking` leave the platform? Options: new objects per application (every Swift, Android
   and bridge call site renamed); extension functions of the platform object in the application's package. Chosen the
   extensions: Kotlin/Native exports them as an Objective-C category of the same class, so Swift's
   `Notices.shared.potRefused(outcome:)` is unchanged (the Apple app compiled, decision 1's evidence below); Kotlin
   callers add one import. `Notices.isMedium` had no caller anywhere (`grep -rn isMedium src/mobile`) and is deleted;
   `uploadProblem`'s Depot codes are `UploadQueue.NAME_TAKEN`/`BUSY` now (server codes, not Depot's). Cost: a header
   diff of categories instead of members (named in entry 9).
7. **2026-11-21 — Platform types that lived in an application.** `MediaUpload.Deriver`/`Derived` (the device's tier
   service, handed to the guest views and the import) move to `media.Derivation`; `GalleryModel.Music` (the player and
   a word) to `audio.PageMusic`. `GuestPage` is no longer sealed (an application's subclass lives in another module):
   `Level` and `NotShown` stay nested, the applications' pages nest in their door objects (`DepotGuest.File`,
   `DepotGuest.DropBox`, `MemoriesGuest.Gallery`, `MemoriesGuest.Uploader`, `PotGuest.Pot`); the bridge's `when` over
   pages gets an `else` (not shown). Cost: Swift renamed in 7 files (`MediaUploadDeriver` → `DerivationDeriver`,
   `GalleryModel.Music` → `PageMusic`, `GuestPage.File` → `DepotGuest.File`, …), Android and the bridge likewise.
8. **2026-11-21 — `@PlatformApi` (decision 4) and Objective-C.** `RequiresOptIn` binds the declaring module too, so
   `:core` opts into its own marker (module-wide `optIn`); the guard's rule (d) therefore allows the platform module as
   well. Every `@PlatformApi` declaration also carries `@HiddenFromObjC`, as do the registry's parts and
   `AppComposition.applications`; `kotlin.experimental.ExperimentalObjCRefinement` is opted into per module (an API
   opt-in, `-opt-in`, not an experimental compiler flag). **Deviation:** phase 1 opens only what the registry needs
   (`QueuedSender`, `AppKinds.kept`); the other `internal` crossings show only once the modules exist and are opened in
   phase 2 from the compiler's list.
9. **2026-11-21 — Phase 1's header and bindings diffs.** Evidence: measured, `scratchpad/cs/tools/hdiff.py` (per
   `@interface`/`@protocol`, members as sets) against the copy of entry 1. Removed: `GalleryModelMusic`,
   `GuestPage{File,Gallery,Pot,DropBox,Uploader}`, `MediaUploadDerived`, `MediaUploadDeriver`,
   `ShellModel{Trashed,Renamed}`, the moved `Notices`/`AppleSessions`/`AppleAudio`/`GuestAccess` members and
   `Notices.isMedium`; `KeptRead` an enum before. Added: `AppComposition`, `ShellDoor` (and `ShellModel`'s conformance
   and its five members), `DerivationDeriver`/`DerivationDerived`, `PageMusic`, the five applications' guest pages,
   `NodeHeaderModel{Trashed,Renamed,Companion}`, the categories `Notices (Extensions)`, `AppleSessions (Extensions)`,
   `AppleAudio (Extensions)`, `GuestAccess (Extensions)`, `AppKinds.applicationOf`/`noun`/`shareWords`,
   `UploadQueue.NAME_TAKEN`/`BUSY`, `KeptRead` a class with its companion. The bridge's `bridge.rs` and `Bridge.g.cs`:
   the same types, only the moved types' package lines and order (`bdiff.py`: 0 removed, 0 added).
10. **2026-11-21 — Each client's start path.** Android: a new `VeyqApplication` (the manifest's `android:name`), since
    WorkManager's workers start a process without an activity; Apple: `AppModel.init` (the app's and the share
    extension's); the bridge: `Bridge`'s first `init`. Tests: each test class that reads the registry installs it
    (`init { AppComposition.install() }`, 62 classes). Evidence: measured, a throwaway `forkEvery = 1` on `:core:jvmTest`
    (one JVM per class, the build file restored after) green, so no class passes only because another installed first.
11. **2026-11-21 — A red that was there before.** `linux/run.py test` and `windows/run.py test` each failed 14
    `ShownVectorsTest` cases ("cannot read src/mobile/words/format-vectors.json"): neither ships that file to the
    container or the VM since the vectors were added. Fixed in both (a file this CR touches). Evidence: measured, before
    14 failed; after, Linux 1248 passed, Windows 1249 passed.
12. **2026-11-21 — The guard's intermediate state.** `validate_mobile_ui_direction.py`'s `CORE_ROOTS` becomes empty and
    the package `…mobile.composition` is the one root: it may name applications, and no other file names it (rule c3).
    Phase 3 retires these rules into the module graph guard.

13. **2026-11-22 — Where the modules' shared targets live (decision 5).** Options: a `buildSrc` convention plugin; a
    convention in `src/mobile/build.gradle.kts`. Chosen the root file: `subprojects { plugins.withId(multiplatform) { … } }`
    for `:core`, `:apps:*`, `:composition`, `:testing` (by path; `:bridge` keeps its own three targets). `buildSrc` would
    put a second Kotlin Gradle plugin on the build's classpath beside the catalog's, and the CR's tie-breaker (what the
    configuration cache accepts) is moot: the build does not enable it (`gradle.properties`, measured by reading). The
    convention declares the eight targets, the hierarchy (`native`/`bridged`/`posix`, `threaded`), the two opt-ins, the
    shared `api` dependencies (coroutines, serialization, Ktor's core, negotiation and JSON — what the platform's API
    hands its applications), the test dependencies, the tests' system properties and `generateApi`. Cost: a reader of a
    module's build file finds its targets in another file (each module's header says where).
14. **2026-11-22 — `generateApi` per module, and the generator's class path.** An application's specs are derived — every
    `contracts/api/<application>/*.openapi.yaml`, into `…mobile.api.<application>` — never listed (the backend's rule);
    the platform keeps its explicit list. The generator writes its `infrastructure`/`auth` packages for every spec; an
    application module deletes its copy and uses the platform's (one class per name on the JVM). **Deviation from the
    obvious:** moving the generator onto the root build script's classpath broke dependency verification — resolved
    beside the Android plugin, `checker-qual` became a 2.5.8 jar no build had verified (measured: `./gradlew
    :core:compileKotlinJvm` → "Dependency verification failed … checker-qual-2.5.8.jar"). The generator now runs from a
    configuration of its own in a class loader of its own (`URLClassLoader` over it, the platform class loader its parent,
    `CodegenConfigurator`/`DefaultGenerator` called reflectively), so neither classpath perturbs the other and every
    artifact was already verified. Cost: twenty lines of reflection in the build script; the CLI artifact
    (`openapi-generator-cli`) would have been a new, unverified dependency.
15. **2026-11-22 — The `internal` crossings (decision 4), from the compiler.** Opened by the compiler's own list
    ("Cannot access …: it is internal in …", "exposes its internal type"), round by round until every module compiled:
    173 declarations of the platform, 39 of the applications (which the composition's code and tests reach), each
    `@PlatformApi` + `@HiddenFromObjC` (`@HiddenFromObjC` dropped where Kotlin/Native's annotation does not exist: JVM and
    JavaScript source sets). An `internal` constructor becomes `@PlatformApi constructor`. Evidence: measured,
    `scratchpad/cs/tools/open_internals.py` over the compile logs; the header diff of entry 21 shows none of them.
16. **2026-11-22 — Where the tests went (decision 6), and a deviation.** The rule applied, by what a test file names: a
    platform test that names an application or the composition, or needs the composed registry, moved to
    `:composition`'s tests (its package kept); an application test that names another application or the composition
    moved there too; an application test that composes only its own application stays and installs its part beside
    inert parts for the others (`:testing`'s `TestApplications.install`) — unless what it asserts depends on another
    application's hook (Depot's `ImportScreenTest` counted 7 nodes of a web download only with every import hook: moved).
    **Deviation:** no platform test was rewritten over a played kind; 82 test files run in `:composition` unchanged,
    rather than being rewritten — behaviour held identical over the move outweighed a cleaner platform test set (left as
    a follow-up). `PlayedTenant` (the played server, which seals Pots and banners) was not split; it and its users are
    composition tests. One assertion moved: the guest doors' check of a Pot's wire view (`PotFixture`, an Expenses test
    helper) is Expenses' `PotViewGuestTest`. Test packages may span modules (tests only; rule (c) of the guard reads main
    sets). Evidence: measured, the JVM test cases of a scratch tree at phase 1's commit against this tree's
    (`testcase` names of every `build/test-results/jvmTest`): 1475 then, 1476 now, the one added `PotViewGuestTest`, none
    lost (a first run had lost the 12 contract cases of `SoftwareDeviceKeysContractTest`, defined in the helper's file;
    they are back in `:core`).
17. **2026-11-22 — `:testing`.** Its `commonMain` holds the old `testDevice` and the helpers every module's tests share
    (moved from `:core`'s test sets: `DeviceKeysContract` and `testPrimitives`, `PlayedImport`, `ImportFixtures`,
    `SizedVolume`, `StoreDirs`, `TestDirs`, `PlayedMapServices`, `QrFixtures`/`RepoFile`, `TestEnv`, `PlayedAudio`, and
    Memories' `AudioFiles`, now `audio.AudioFiles` since it needs nothing of Memories), its `jvmMain` the old `liveTest`;
    their `internal` dropped (support for other modules). Its packages are the platform's, so rule (c) of the guard
    exempts it, and it opts into `PlatformApi` (rule (d) allows it). The bridge's path-based `srcDir` became a
    dependency on it.
18. **2026-11-22 — The JavaScript package from the composition.** `:composition` links the library (per-module
    granularity, Kotlin's default); `:core` keeps only the `conformance` compilation's binary, which `jsPackage` copies
    (`evaluationDependsOn(":core")`). `jsPackage`, `jsPackageDev`, `jsKotlinVersion` and the KT-68281 sort moved whole.
19. **2026-11-22 — Pulled forward from phase 3.** `repo_layout.py`'s constants and helpers (`MOBILE_CORE`, `MOBILE_APPS`,
    `MOBILE_COMPOSITION`, `MOBILE_TESTING`, `MOBILE_SETTINGS`, `mobile_projects`, `mobile_core_modules`,
    `mobile_core_sources`) — `run.py` reads the modules from the settings now — and `validate_mobile_structure.py`'s
    `plan_enums` over every module, because Apple's pre-build `generate_plans.py` reads it and Memories' `TileAction`
    left `core/src`. Linux's and Windows' `run.py test` link and run every core module's test binary but `:testing`'s
    (it has none), the platform's first, the first red one's code the run's.
20. **2026-11-22 — `@HiddenFromObjC` on a subtype.** Kotlin/Native refuses a visible class implementing a hidden
    interface ("Only '@HiddenFromObjC' declaration can be a subtype of …"): `:testing`'s `SizedVolume` (a `DeviceFiles`)
    is hidden too.

21. **2026-11-22 — Phase 2's header and bindings diffs.** Against phase 1's (`hdiff.py`, the same copy of entry 1):
    first two opened constructors (`FileSave`, `OfflineStores`, whose `internal constructor` the compiler listed) showed
    as Objective-C initialisers and four `QueuedNodes` constants as properties — an `@PlatformApi constructor` cannot be
    hidden (`HiddenFromObjC` takes no constructor). Chosen: the constructors stay `internal`, each opened through a
    hidden companion's `of(…)` (Depot's file page and Memories' lightbox, the composition's tests call it); the constants
    carry `@HiddenFromObjC`. After: the semantic diff against the pre-split copy equals phase 1's line for line
    (`diff header-p1.sem header-p2.sem` empty); the bindings: 0 types removed, 0 added. Evidence: measured.
22. **2026-11-22 — The JavaScript package's file names (decision 5), a deviation.** Kotlin's per-module output names a
    dependency's file after its library, not its project: the platform's is `kotlin_veyq_mobile_core.mjs`, each
    application's `kotlin_veyq_mobile_apps_<application>.mjs`, the composition's (an empty shell importing Ktor's client
    for its registration) `veyq-mobile-composition.mjs`, and one `.d.mts` for every module's exports,
    `veyq-mobile-composition.d.mts`. `js { outputModuleName }` on `:core` renames only a binary `:core` links itself, not
    the file the composition's link writes for it (measured: set, rebuilt, unchanged). Chosen: the entry follows
    Kotlin's names — `./search` → `dist/kotlin_veyq_mobile_core.mjs`, its types the one `.d.mts` — rather than a rename
    step after the link (a second rewrite of Kotlin's output beside KT-68281's). The CR's "`./search` stays
    `veyq-mobile-core.mjs`" does not hold; the package's tests name the new files. Cost: an entry's types are the
    whole package's (types only, no bytes).
23. **2026-11-22 — The search alone after the split.** `@veyq/core-js/search` bundled as the web bundles it
    (`src/slice.test.ts`, Node 22.23.3): 27,909 B gz at phase 1's end, 27,943 B gz after the split (+34, per-module
    output), within 40,960; the refine's 28,189 was measured before phase 1. Evidence: measured, `npx vitest run
    src/slice.test.ts` in `src/packages/core-js` after `pnpm run build`.

24. **2026-11-22 — The module graph guard (decision 7).** `scripts/validate_mobile_module_graph.py`, beside the backend's,
    buildless: (a) the settings' includes equal the module directories, and `:apps:*` equals `ApplicationId`'s entries both
    ways; (b) the edges of decision 1, a test edge being one inside a `<set>Test.dependencies { }` block — and, beyond the
    CR, the composition must `api` and `export` the platform and every application (a client would not see one it
    forgot); (c) a package in one module, an application's only in its own, `…composition` only in `:composition`, and a
    file's package its directory's (moved from the UI guard); `:testing` exempt; (d) the opt-in only in the core's
    modules — including the convention's `isCoreModule()`, read for the paths it names; (e), beyond the CR: every
    `@PlatformApi` of a common or native main set carries `@HiddenFromObjC` (decision 4's "hidden from Objective-C", held
    by text where the header diff holds it only per run). Its first run read no edge at all — `kotlin_lexer.mask` blanks
    string literals, and a project's path is one — and a first bite of (d) passed for the same reason: both now match the
    raw text and keep a match only where the masked text still holds its code. Evidence: measured, the bites of entry 25.
25. **2026-11-22 — The bites.** Each planted in the real tree, the guard run, the file restored with `/bin/cp -f` (a planted
    file removed), `git status` clean after (`scratchpad/cs/tools/bite.py`): `implementation(project(":apps:depot"))` in
    `apps/expenses/build.gradle.kts` → (b) "an application uses the platform alone"; `project(":apps:memories")` in
    `core/build.gradle.kts` → (b) "the platform names no other module"; a `package me.raddatz.veyq.mobile.expenses` file
    under `core/src/commonMain` → (c) "is the application `expenses`'s, in :core"; an `ApplicationId` entry `NOTES` → (a)
    "has no module :apps:notes"; `PlatformApi`'s `optIn` in `bridge/build.gradle.kts` → (d) "only the core's modules do";
    `:bridge` added to `isCoreModule()` → (d); `:testing` dropped from the settings → (a) "a build file the settings do
    not include"; `@HiddenFromObjC` removed from `AppKinds.queuedSender` → (e). All eight red (exit 1), each with its
    named message. The eight are pinned as `validate_guard_gaps.py` cases 108–115, so a loosened rule goes red there:
    measured, `/usr/bin/python3 scripts/validate_guard_gaps.py` → "112 pinned case(s) hold".
26. **2026-11-22 — What retired, what stayed.** `validate_mobile_ui_direction.py` loses (c1)–(c3) and `CORE_ROOTS`
    (empty since phase 1) and keeps the platforms' UI rules (a)/(b). `validate_mobile_search_names_no_application.py`
    stays, pointed at `repo_layout.MOBILE_CORE`: it says why before a build does, and docs and an ADR name it; with the
    split it is a second check (follow-up: retire it in a doc sweep). `validate_mobile_structure.py` reads every module's
    main sources but `:testing`, ordered as one tree of source sets (`<set>/kotlin/<path>`), so `generate_plans.py`
    writes the plans in the order it did — measured: `Plans.swift` from phase 1's tree and from this one over the same
    header, `diff` empty (in module-path order Memories' `TileAction` came first).
27. **2026-11-22 — Inbox #1056, decided against ADR 0154 decision 3.** Android's core UI naming a core application's
    package (11 files of `android/core/`, measured) breaks decision 3 — the core UI reaches an application only through
    the platform's registry. Not fixed here (the CR changes no UI; the composition still carries every module to
    Android); routed: the row is `refined`, its next step a CR that moves those holdings behind Android's `apps/`
    registry and widens the UI guard's rule (a) to core application packages, and asks the same of the other platforms.

28. **2026-11-22 — Phase 4: the measurement ADR 0155 waits for.** Substrate: a throwaway branch
    `claude/core-split-p4-measure` from phase 3's `7f4d2f3e3` in a worktree of its own; the parked branch's whole diff
    (`git diff eb1ec9be1 7b8af11cf`, its five commits; none of their files changed on the way to this branch's base) applied
    with its Expenses paths rewritten onto `:apps:expenses` and two moved tests onto `:composition`; conflicts resolved —
    the parked per-file entry writer of `:core:jsPackage` dropped (with modules, an entry is its module's file:
    `./expenses` → `dist/kotlin_veyq_mobile_apps_expenses.mjs`), the slice test kept the parked method (esbuild over
    `export *` of the entries' files, minified ESM, `@noble/*` external, gzip 9, Node 22.23.3); committed there as
    `ecdc90588`, then the branch and its worktree deleted. Measured (`npx vitest run src/slice.test.ts`; the bench
    `scratchpad/p2-gen/bench.mts`, median of 15 after 3 warm-ups, three runs each way):
    - **search alone: 43,091 B gz** (122,812 minified) — **over the 40,960 budget**; it was 27,943 on the split
      without Expenses. The platform's own code in the search's bundle grew from 26,271 to 45,253 B minified: what
      Expenses' façade uses of the platform (the parked move put its JSON seam `json/` and money's formats in `words/Shown`
      into the platform) lives in the platform's file, and a bundler drops nothing of a Kotlin module file (its
      `initMetadataFor…` calls run at load). Per-module output moves the cost from Expenses' file into the platform's.
    - Expenses gross alone 61,619 B gz; search and Expenses together 63,600; **Expenses' net = 63,600 − 43,091 − 6,194 =
      14,315 B gz**, within 25,600.
    - the `.d.mts`: one for every module (`veyq-mobile-composition.d.mts`), all 28 of the façade's `external interface`
      shapes in it — the per-file defect is gone; but with one declaration file the entries share their types, so the
      parked `entries.typetest.ts` (a name of one façade must not resolve through the other's entry) is red.
    - 21 decode cases red: Expenses' decode runner (`jsConformance/…/DecodeRun.kt`) has no conformance compilation in
      `:apps:expenses` (the platform's is the only one); the 384 other package tests, Expenses' vectors through the
      module among them, pass.
    - time: `buildLedger` 79.2–81.6 ms against the TypeScript's 37.5–40.2 (2.07× at the medians), `checkLedger` 4.94–5.06
      against 4.18–4.37 (1.20×), the answers identical (one digest) — within decision 7.3's 2.5× and 100 ms; the read
      into the handle once 40–43 ms.
    - the web's `SearchField.test.ts`: 61 passed — the parked branch's red does not come back.
    The gate misses on the search alone; per the mandate the number goes to the owner and the split stays. What it says
    for Expenses' resumed move: the platform code a façade needs must not sit in the module every other entry imports
    (a module of its own for the JSON seam and the shared formats, or those parts in `:apps:expenses`), the entries need
    a declaration file each, and an application needs a conformance compilation of its own for its runners.

29. **2026-11-22 — Inbox #1056 built (cr-android-core-ui-through-the-registry, harvested here).** Substrate: branch
    `claude/android-coreui` from `961f96c1f`, clean but for nothing of this work; `:android:assembleDebug` green before the
    first edit. A CR or none: the work moved ~1000 lines out of Android's `AppModel` into three applications and added a
    registry member and a guard rule — CR-sized (`docs/ai/README.md` § The pipeline); written, built, harvested into this
    log and ADR 0154's Consequences, deleted. Cost: none beyond the file.
30. **2026-11-22 — The mechanism.** Options: (a) keep `AppModel`'s per-application members, typed through core-UI
    interfaces (the core UI would still build each application's model through the composition and hold it);
    (b) one `AndroidViewModel` per application (its own scope; the tenant's moments would need a bus between view
    models); (c) one `Holding` per application inside the one `AppModel`, registered through the registry
    (`ApplicationUi.holding`, a typed `HoldingKey`), built once in registration order, reached by the application through
    an extension it declares (`model.memories`). Chosen (c): one lifecycle and one scope as before (a rotation keeps the
    same objects), the registry the only edge, the call sites `model.<application>.x`. Evidence: remembered (Apple's
    `AppModelHeld.swift` already keeps Memories' stored state apart, though in core). Cost: `AppModel` opens seven
    members as `internal` (`shellModel` and `tenantSession` read-only, `readRowDetails`, `refreshLists(t)`,
    `appLanguage`, `followLevelView`, `rowDetail`); `held` casts once by its key.
31. **2026-11-22 — The tenant's moments, one hook each.** Question: one "closed" hook or one per moment? The old code
    closed different things at each: a lock closed a Memory's Music view, its dialogs and a profile's section but kept
    the gallery and the uploads; a sign-out closed those and the gallery, the media and Depot's uploads; showing a tenant
    closed the pages first and built the models after the shell. Chosen one hook per moment (`leave`, `shown`, `locked`,
    `signedOut`, `personOpened`, `listsRead`), each closing exactly what its moment closed — measured by reading the
    removed lines against each override (`git show f4e302d59`). Cost: six hooks where one would read simpler.
32. **2026-11-22 — The flows the core asks.** Each preserves its answer: *Add here* and a drop go to the target's
    application (`AddHere.Gallery`/`DropTarget.Gallery` Memories', `AddHere.Level`/`DropTarget.Level`/`Row` Depot's;
    none answering, the composition's drop with no uploads, as before with none built); a drop on the app reaches the open
    object where an application says it takes it (Expenses' `takesDrop`, the old `PotShare.takesDropOn`); the header's
    `closeOpen` is `trashed`; a Pot's copy sheet is Expenses' by its kind, every other kind's the composition's with no
    Pot (the Pot only ever reached `duplicateOpen`, which `AppKinds.copiesByOpenModel` gives a Pot alone — measured by
    reading `MoveSheet.kt:233` and `ExpensesKinds.kt:93`); a row's last line is `rowLines`; a link guest's tiers are the
    registered `deriver`. Level *Add here* says no notice: the old line said Memories' `MediaUploads.notTaken`, but a
    level takes any file (`FolderShare.answer`'s intake is `{ true }`), so its refused count is always 0 — measured by
    reading. "Send a copy" keeps its check at confirm time: the file's header hands the shell a `SendCopy` whose name and
    type are read when the person confirms. Cost: eleven questions on `Holding`, each a default for the applications that
    do not answer.
33. **2026-11-22 — The tray.** Options: the tray's actions on the platform's `UploadQueue` directly (they are
    `queue.cancel`/`retry`/`acknowledge`, `DepotUploads.kt:312–317`); or through Depot. Chosen through Depot's
    `LevelUploads` (`tray`, its actions, New's `upload`/`uploadFolder`): the tray shows a folder being prepared (Depot's
    state) and is hidden while no tenant's uploads exist, as before. Cost: four one-line delegations.
34. **2026-11-22 — The small files.** `AudioEnds` and `MediaDerive` move to `apps/memories/` (their only users Memories'
    music and media; `MediaDerive` follows `MediaUpload`'s spec); a Memory's date text and the world view (`MemoryLocation`'s
    constants) move beside their only callers; the music bar's lengths are `Shown.length` (`MusicQueue.formatLength` is
    exactly that, `MusicQueue.kt:63`); a Depot tab's icon reads the kind literal `"DEPOT_DROPBOX"`, as `kindIcon` beside it
    does (the guard blanks literals: its documented limit); two imports were unused. Nothing in `src/mobile/core/` or
    `src/mobile/apps/` changed: the registry answered every question.
35. **2026-11-22 — One drawing change.** The link bar's per-kind actions are one slot (`KindPage.guestActions`), drawn
    where the file's were, before *Keep*; a Memory's music, after *Keep* before, moves left of it. Options: two slots to
    keep the order; one. Chosen one (the order of two icons on a guest's bar is no plan of the core's). Cost: that icon's
    place.
36. **2026-11-22 — The guard.** `validate_mobile_ui_direction.py` gains rule (c) — no Android core UI file names
    `me.raddatz.veyq.mobile.<application>` or its generated API `…mobile.api.<application>` (read over the masked text, so a
    comment is none) — and its rule (b) reads core application packages too. Bites, each planted in the real tree and
    restored with `/bin/cp -f`: Depot's `DepotUploads` imported into `core/UploadTray.kt` → red (c); a spaced
    `me . raddatz.veyq.mobile.api.expenses` in `MainActivity.kt` → red (c); Expenses' `PotModel` imported into Memories'
    `MemoryScreen.kt` → red (b); the name in a comment → green. On the tree before (`b2656a816`, in a throwaway worktree
    with the new guard) → 104 violations in 11 files, the inbox's 11. `validate_guard_gaps.py` pins cases 116 and 117.
    Limit, documented: a type reached without its name (an inferred `val` of what a composition function returns).
37. **2026-11-22 — The other platforms.** Apple's core UI names the core applications' Kotlin types through the
    framework (`GalleryModel`, `PotModel`, `DepotUploads`, … in 6 files of `Veyq/Core/`, measured by `grep -rlwE` over
    the names); Linux's and Windows' reach applications through the bridge, a composition root, and their core UIs carry
    application-shaped bridge names (10 and 5 files, an unclassified grep). Not this CR's (Android's; Apple's build and
    pass need the Mac): inbox #1066. Cost: Apple's half of decision 3 stays open.
38. **2026-11-22 — Inbox #1066 built (cr-native-core-uis-through-the-registry, harvested here).** Branch `claude/coreui-registry` from `36516924f`, clean but for nothing of
    this work. CR-sized (three platforms, a registry member per platform, a guard rule per platform), so written, built,
    harvested into this log and ADR 0154's Consequences, deleted, as #1056's was. Cost: none beyond the file.
39. **2026-11-22 — The classification.** A read-only sweep (an Explore agent) classified every application-shaped name in
    each core UI: an application's type or wire part the core UI holds or drives (moved), a platform or composition type
    that only sounds applicative (`ShellModel`, `AddHere`, `DropTarget`, `QueuedOpen`, `MusicPlayer`, `MapServices`,
    `UploadQueue` — kept), a kind literal or an action's name (kept: the guard's documented limit, as Android kept
    `"DEPOT_DROPBOX"`). It found what the inbox row did not: `AppleMemories` and `AppleDepot` are application objects
    (`…mobile.memories`, `…mobile.depot`) whose generic helpers (watch a state, a picked file's source) the core UI used
    everywhere, and `AppleSessions.openReceipt`/`AppleAudio.floatWav` are application extensions. Measured with the new
    guard on the tree before (a throwaway worktree of `36516924f` with this CR's guard): **200 violations** — Apple 184 in
    19 files (`AppModel.swift` 118, `AppModelHeld.swift` 20, `GuestLinks.swift` 14, …; 2 of them Depot's UI naming
    Memories' `AppleMemories`, rule (b)), Linux 9 in 4 (`home.rs`, `receipt_viewer.rs`, `said.rs`, `uploads.rs`), Windows 7
    in 3 (`RowDates.cs`, `Uploads.cs`, `ReceivedView.cs`). After: 0.
40. **2026-11-22 — Apple's mechanism.** Options: (a) a base class `Holding` as Android's; (b) a protocol `Holding` with its
    defaults in an extension, each application's an `@Observable final class`, registered as `ApplicationUi.holding: (AppModel)
    -> any Holding`, built once (lazily, at first use) in registration order (`Registry.registered()`), reached by the
    application through an extension it declares (`model.memories`, `model.depot`, `model.expenses`) over `AppModel.held(T.self)`
    — the type as the key, Android's `HoldingKey`. Chosen (b): `@Observable` on a subclass of a plain class is the macro's
    weak spot, and a protocol's requirements dispatch to the conformer as an abstract class's open members do; SwiftUI
    observes each holding's own stored properties, so views redraw as before. Evidence: measured, both Apple builds green.
    Cost: `AppModel` opens `refreshLists`, `readDetails`, `syncInbox`, `galleryMusic`, `Picked`, `levelLabel`, `sharedDir`
    as internal and gains `setRowDetail`, `shareTaken`, `holdingsOpen`, `levelUploads`, `deriver`, `editing`.
41. **2026-11-22 — The tenant's moments on Apple.** Android's hooks, the same names: `leave` (before a tenant is shown:
    Memories closes its gallery and the lightbox windows' galleries, Depot its file page), `shown` (Expenses' `PotModel`,
    Depot's `DepotUploads`, Memories' `MediaUploads` handed over or closed as before), `locked` (Memories' curation dialogs),
    `signedOut` (Depot's uploads and file, Memories' gallery and media; the Pot stays as before), `listsRead` (the file page
    reloads). `show(_:)` now asks `leave` before the shell is built and `shown` after; the maps, the share's handoffs and the
    music, which the old `openMedia` called, are core's and run after every `shown`. The two sign-out blocks are one
    `closeTenant()`. Evidence: measured by reading each removed line against its hook (`git diff` of `AppModel.swift`).
    Cost: an open gallery closes a moment earlier in `show` (before the shell, not after the uploads) — nothing draws between.
42. **2026-11-22 — The flows Apple's core asks.** `addHereTarget` (Memories' gallery on screen, else the level),
    `addHere` (Memories a gallery, Depot a level, each in the app and in the share extension; the holding ends the share
    through `AppModel.shareTaken`), `drop` (Memories a gallery, Depot a level or a row; none answering, the composition's
    drop with no uploads), `pickUp` (a handoff's Memory or folder), `takesDrop` (an open Pot), `moveSheet` (a Pot's copy by
    the open Pot, every other kind's with `pot: nil`, as Android's entry 32 measured), `trashed`, `openQueued` (a held Pot
    entry), `adopt`/`release` (a link's gallery, file and Pot held in the member's place), `editing` (Expenses' editor open
    over a guest's page), `levelUploads` (Depot's tray and New's uploads), `deriver` (Memories' `MediaDeriver`; a Mac's
    Depot import and a link guest's upload ask it). Two answers differ from before by reasoning: a level's *Add here* says
    no `MediaUploads.notTaken` (a level takes any file, `FolderShare.answer`'s intake is `{ true }`, so its refused count is
    0 — Android's entry 32); the handoffs are picked up once the shell is built (`shellModel != nil`) instead of once both
    Memories' and Depot's uploads exist — both are built in `shown`, before any pick-up. Evidence: measured by reading.
43. **2026-11-22 — `ApplePlatform`, the minimum the registry lacked.** The core UI watched states, made a picked file's
    source, read a reader's range, handed derived tiers and walked a dropped folder through `AppleMemories`/`AppleDepot`.
    Options: name them in core through the registry (each a closure — noise for what no application decides); move the
    helpers out of `apps/` (barred: other sessions work there); add them to the composition's `appleMain`. Chosen the
    composition: `composition/…/ApplePlatform.kt` (`watch`, `source`, `urlOf`, `readRange`, `derived`, `found` over its own
    `Entry`/`EntryKind`, so core's folder walk names no Depot enum), held by `ApplePlatformTest` (appleTest). Every Swift
    `CoreState` watch goes through it, an application's too, since `CoreState` takes its `Watch`. Cost: `AppleMemories`'
    `watch`/`source`/`urlOf`/`readRange`/`derived` and `AppleDepot`'s `source`/`found`/`FolderFile` are no longer called
    from Swift — left in `apps/` (not this CR's to touch), a cleanup for whoever next edits those files.
44. **2026-11-22 — What an application presents over the shell.** Depot's uploads' alert (`DepotUploads.said`), "Send a
    Copy…"'s question and share sheet, and a file's Replace picker sat in `ShellView`. Options: core draws them over a
    protocol (it would name Depot's `Said`); the application draws them in a slot. Chosen a slot, `ApplicationUi.shell`,
    drawn in the shell's `.background` (where the uploads' alert already sat on a view of its own); the folder's
    "preparing" overlay and the tray read `LevelUploads.tray` (`TrayShown`, Android's shape). Cost: none seen — an alert
    or a picker presents the same from any view of the window (unclicked, below).
45. **2026-11-22 — The link bar's per-kind actions.** `KindPage.guestActions` (Android's slot): Depot's file download and
    replace (`GuestFileActions`, moved from `GuestLinks.swift`), Memories' music button; drawn where they were, before
    *Keep*, in the same order. Cost: the toolbar group gets them through an `AnyView` (unclicked, below).
46. **2026-11-22 — The small files.** `AppModelHeld.swift` → `Apps/Memories/MemoriesHeld.swift`; `MediaTiers.swift`
    (`MediaUpload`'s spec) and `AssetMeasure` (a Memories protocol) → `Apps/Memories/`, the asset's length staying core's
    `AssetLength` for the debug build's `DevAudio`; `MemoryDateText` and `MapMath.framing` (`MemoryLocation`'s world view) →
    `Apps/Memories/MemoriesFormats.swift`; the Depot tab's icon reads the kind literal `"DEPOT_DROPBOX"` (Android's entry 34).
47. **2026-11-22 — Linux.** The core UI named Memories' dates (`home.rs`, `said.rs`), Depot's Replace (`uploads.rs`) and
    Expenses' receipts (`receipt_viewer.rs`). Moved: a member's and a guest's Replace → `apps/depot/replace.rs` (the guest's
    reached through the page, `Page::act("replace")` on a `GuestFile` wrapper, so `received.rs` names no Depot call); the
    receipt viewer → `apps/expenses/receipt_viewer.rs`, its page rendering staying core's as `core_ui/pdf_render.rs` (a
    file's PDF and the Emergency Kit draw it, with its two tests); a Memory's date text and the ask → `apps/memories/dates.rs`,
    `home.rs` keeping only the rows' lines by node id (`lines_asked`, `lines_arrived`) — Memories' list asked them already
    (`ApplicationList::around`), so no registry member was needed. Left, named: `header::doer`'s action names and kind
    literals (strings, not types — the guard's limit), the generic Refresh's action named `pot-refresh`, the WRITE_ONLY
    upload pages (core kinds, ADR 0154), and `bridge/mod.rs`'s quiet `Answer::PotOpened` (the host's mirror of the whole
    wire, not read). Cost: the dates' stderr line says how many dated, no longer of how many.
48. **2026-11-22 — Windows.** `RowDates` → core's `RowLines` over a new `ApplicationUi.RowLines` (Memories registers
    `MemoryDates.Lines`, which holds the date text); Replace → `Apps/Depot/FileReplace.cs` (`Member`, `Guest`),
    `PageHost.Replace` removed, `GuestHost.Received` and `KindBody.GuestReplace` added so the link's bar asks the page. Left:
    `HeaderPlan.DownloadsByPage`'s kind literals, `ShellView`'s held Pot entry (it holds the composition's `QueuedOpen`
    and calls the page's generic `OpenHeld`), `Words.PotRefresh` as Refresh's label. Cost: the list redraws when a row's
    line differs, no longer when a date's record differs with the same text — nothing visible.
49. **2026-11-22 — The guard.** `validate_mobile_ui_direction.py` rule (c) per platform. Apple: an index of what each
    `src/mobile/apps/<application>` module hands Objective-C (top-level non-private, non-internal classes, interfaces,
    objects, type aliases, a file's `…Kt` facade; top-level extensions as a `.member(` call; `@HiddenFromObjC`/`@PlatformApi`
    none), less the platform's and composition's names; (b) reads it too. Its one exception: `.memories`/`.depot`/`.expenses`
    (an application's holding extension) are `ApplicationId`'s cases as well. Linux and Windows: the wire's serial names,
    owned by prefix (`WIRE_APPLICATIONS`, the rest `WIRE_PLATFORM`; an unclassified dotted prefix is red), read where a file
    names the wire (`wire::`, a variant's `Call::`/`Answer::`/…; on Windows a variant, or a type bare unless the name is a
    prefix alone, which .NET's `File` would match); `ReceiptPages` is shared (`WIRE_SHARED`: Depot's `File.Pages` answers
    with it); Linux's `src/bridge/` is not read. Options for the desktops: the generated bindings' types (no checkout holds
    them: generated at build); the Kotlin bridge's dispatch (names the models, not the hosts' names); the serial names
    (chosen: the wire file is the generator's input). Bites in the real tree, each restored with `/bin/cp -f`: Apple core
    `GalleryModel` → red (c); Apple core `.replaceAsking(` → red (c); Apple Expenses `FileModel` → red (b); Linux core
    `Call::FileChooseReplace` → red (c); Linux core `use wire::{MemoryDateShown, …}` → red (c); Windows core
    `Call.FileChooseReplace` → red (c); `GalleryModel` in a Swift comment → green. Cases 118–125 in
    `validate_guard_gaps.py`. Limits, documented in the docstring: a concatenated nested name (`GalleryModelState`), an
    extension property, a wire type carried from an application's package as a field (`PotView.SettleRow`), a prefix-less
    name starting with a platform prefix (`PersonMemoriesShown`).
50. **2026-11-22 — The checks.** Each measured on the branch: the XCFramework
    (`./gradlew :composition:assembleVeyqCoreDebugXCFramework`, exit 0), `xcodegen` (0), the macOS ad-hoc build
    (`xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO`, BUILD SUCCEEDED) and the iOS simulator SDK compile (the
    same with `-sdk iphonesimulator`, the share extension among it, BUILD SUCCEEDED); `:composition:macosArm64Test --tests
    '*ApplePlatformTest*'` (4/4, its two bites red); Linux `run.py build` (clippy `-D warnings`, `cargo test`: 106, exit 0)
    and `run.py test` (exit 0); Windows `run.py build` (the app, Veyq.Drive and both test projects green — 6, 6 and 76
    tests — then exit 3 at PdfWebTest, which needs a registered scheduled task, as expected) and `run.py test` (exit 0);
    `:composition:jvmTest` (458) and `:bridge:jvmTest` (142), 0 failures; every `scripts/validate_*.py` green
    (`validate_mobile_words.py`'s one exception for the Pot create sheet's `currency(code:)` moved with it to
    `ExpensesHolding.swift`); `validate_guard_gaps.py` (122 cases hold). **Not run:** any pass — the Mac and the VM's UI
    are the owner's; what is owed is inbox #1066's row.
