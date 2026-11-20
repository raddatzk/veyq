---
id: cr-core-js-target
title: The Kotlin core compiles for JavaScript over the web's noble primitives, as the workspace package @veyq/core-js whose own check runs the suite's conformance — no Kotlin npm tooling, nothing shipped to the web yet
status: planned
satisfies: []
adrs: ["0155", "0123", "0136", "0116", "0065"]
invariants:
  - "the core's whole common code compiles for every target, JavaScript included; the apps' behaviour does not change (ADR 0155 decision 3)"
  - "one implementation of each primitive ships in the web: the lockfile's @noble/* (ADR 0155 decision 3)"
  - "pnpm is the one JavaScript package manager: no Gradle task downloads Node or Yarn or writes kotlin-js-store/ (ADR 0155 decision 6, ADR 0116)"
  - "nothing under src/web/ imports @veyq/core-js in this CR"
---

## Goal

ADR 0155 decisions 3 and 6, the pilot's P0. After this CR `:core` has a `js()` target whose primitives are `@noble/*`,
its compiled module lands in the new workspace package `src/packages/core-js/` (`@veyq/core-js`), and that package's
`pnpm run check` runs the suite's conformance over `contracts/crypto/vectors/v1.json` under Node, in CI's `frontend`
job. The web consumes nothing yet: that is `cr-web-expenses-on-the-core`.

## Context & constraints

- **Read first:** ADR 0155; ADR 0123 decisions 1–3; `src/mobile/core/build.gradle.kts` (targets, the hierarchy, the
  test tasks' vector inputs); `crypto/Primitives.kt`, `crypto/Conformance.kt` and its JVM and native tests
  (`ConformanceTest`, `LibsodiumConformanceTest`, the bite); `src/packages/crypto/package.json` and `src/primitives.ts`
  (which noble entry each primitive is); `pnpm-workspace.yaml` (members listed, never globbed; `allowBuilds`);
  `.github/workflows/checks.yml` § `frontend`; `scripts/repo_layout.py`; `src/mobile/CLAUDE.md` § Traps (the
  verification-metadata write from an empty Gradle home).
- **Order:** none required; it may run beside the running app CRs (`cr-apps-core-ui-layer`,
  `cr-apps-structure-from-core-plans`, `cr-apps-notices-and-formats-from-the-core`) — its files are disjoint from
  theirs but `core/build.gradle.kts` and `gradle/verification-metadata.xml`. From its merge on, their common code must
  also compile for JavaScript (`:core:compileKotlinJs`), which phase 4 writes into the core's check.
- **Out of scope:** any façade, any web import, the JSON seam (`cr-web-expenses-on-the-core`).

## Measured at refine (2026-11-20)

Branch `claude/kjs-refine` at `ff410c6c9`. `C=src/mobile/core/src`.

| # | What | Where / command |
|---|---|---|
| R1 | Eight `expect`s in common code (`nfc`, `nfkd`, `nfd`, `saveKitFile`, `deviceFiles`, `inflate`, `coreWebpEncoder`, `localWallClock`); five in common tests (`testEnv`, `usedHeapAfterGc`, `readRepoFile`, `freshTestDirectory`, `testPrimitives`). | `grep -rnE "^(internal \|public )?expect " $C/commonMain $C/commonTest $C/testDevice` |
| R2 | Common code's libraries: coroutines, kotlinx.serialization-json, Ktor (core, content negotiation, serialization), KotlinCrypto SHA-3; Ktor's engine is per target. | `sed -n '/commonMain.dependencies/,/^        }/p' src/mobile/core/build.gradle.kts` |
| R3 | The web's primitives are noble, synchronous: `@noble/ciphers` (AES-GCM), `@noble/hashes` (HKDF, HMAC, SHA-256, Argon2id), `@noble/curves` (Ed25519, X25519), `@noble/post-quantum` (X-Wing / ML-KEM-768). | `grep -n "@noble" src/packages/crypto/src/primitives.ts src/packages/crypto/src/v1.ts` |
| R4 | The settings refuse a project repository (`FAIL_ON_PROJECT_REPOS`), which a Kotlin/JS Node download would add. | `grep -n repositoriesMode src/mobile/settings.gradle.kts` |
| R5 | The `frontend` job installs once, then checks crypto, web, admin-web and the e2e suite, inside `timeout-minutes: 15`, with no Java. | `sed -n '/^  frontend:/,/^  webp-encoder:/p' .github/workflows/checks.yml` |
| R6 | The site's stage map names what `frontend` checks. | `grep -n "frontend" site/src/content/docs/security/how-veyq-is-tested.md` |
| R7 | The root `CLAUDE.md` and `pnpm-workspace.yaml` say the workspace exists for one package. | `grep -n "for that one\|one @veyq/crypto" CLAUDE.md pnpm-workspace.yaml package.json` |

## Decisions

- **J1 — the target.** `js { binaries.library(); useEsModules(); generateTypeScriptDefinitions() }`, es2015, IR; no
  `-Xes-long-as-bigint` or other experimental flag. No Kotlin/JS test task runs (`jsTest`, `jsNodeTest`,
  `jsBrowserTest`): common tests compile for JavaScript (`compileTestKotlinJs`) as a check, and run under Node only
  through the package (J4). If the plugin cannot be kept from its Node and Yarn setup tasks, that is handed back.
- **J2 — the actuals.** `jsMain` brings: `Primitives` over `@noble/*` through `@JsModule` externals, one external per
  noble entry R3 names (ML-KEM's deterministic `KeyGen_internal(d, z)` and encapsulation as the vectors need them);
  `nfc`/`nfkd`/`nfd` over `String.prototype.normalize`; `localWallClock` over `Date`; `coreWebpEncoder = null`;
  `saveKitFile`, `deviceFiles` and `inflate` as a named refusal in one file, `jsMain/…/NotOnTheWeb.kt` (ADR 0155
  decision 3), each throwing an error that names it. `testPrimitives` and the other test `expect`s get `jsTest` actuals
  only so the tests compile.
- **J3 — the package.** `src/packages/core-js/` joins `pnpm-workspace.yaml`: a `package.json` (`@veyq/core-js`,
  private, AGPL-3.0-only, dependencies `@noble/*` at `@veyq/crypto`'s ranges so the lockfile holds one version each,
  `exports` empty in this CR but for the conformance runner's private path), a `.gitignore` for `dist/`, and a `build`
  script — a Python wrapper (`scripts/` convention) that runs `src/mobile/gradlew` for the one task that writes the
  module, its `.d.ts` and source maps into `dist/`. The Gradle task is the core's (`:core:jsPackage`), so the
  package names a task, never a build path.
- **J4 — the conformance.** A second JavaScript binary from a compilation of its own (`conformance`, depending on
  `main`) exports one function that runs `Conformance` over the vectors' text and the JS primitives; it is built into
  `dist/conformance/` and never into the shipped module. The package's `pnpm run check` runs it under Vitest over
  `contracts/crypto/vectors/v1.json`, with the bite the JVM's `ConformanceTest` has (a primitive off by one byte →
  red).
- **J5 — CI.** `frontend` sets up Java 21 (the toolchain `:core` declares) with Gradle's cache, validates the wrapper
  (as `app-inputs` does), and runs the package's `build` and `check` before the web's. The timeout stays 15 minutes;
  the phase records the job's time before and after.
- **J6 — the build's inputs.** The new artifacts enter `src/mobile/gradle/verification-metadata.xml` by a write from
  an empty Gradle home; a Node distribution is never among them.

## Phases

- [x] **1 the measurement** — first, and a stop: on a scratch branch, `js()` added as J1 and every compile run
  (`:core:compileKotlinJs`, `:core:compileTestKotlinJs`). Recorded in this CR: every error that is not one of R1's
  `expect`s (a JVM- or native-only API in common code is handed back with its list, not worked around); the task
  graph of the package's Gradle task (`--dry-run`) with no `kotlinNodeJsSetup`, `kotlinYarnSetup`, `kotlinNpmInstall`
  or `kotlinStoreYarnLock`, and the switch that keeps them off; whether `src/mobile/` configures with no Android SDK
  present (`ANDROID_HOME` unset, in a JDK container) and on JDK 25 as on 21; two clean builds of the module compared
  byte for byte (`shasum -a 256` over `dist/`). · Done when: the four answers are in § Measured at implement; any
  "no" is handed back before phase 2. **Built** 2026-11-20: M1–M4, four yeses with their switches; the bites seen red
  were the task-graph check (`:kotlinNpmInstall --dry-run`) and the reproducibility itself (4 variants in 4 builds).
- [x] **2 the target** — J1, J2, J6. · Done when: `:core:jvmTest :core:macosArm64Test`, `:core:compileKotlinJs
  :core:compileTestKotlinJs` and `:bridge:jvmTest` green; `--warning-mode all` clean; `git diff --stat` shows only
  `jsMain`, `jsTest`, the build file and the metadata under `src/mobile/`. **Bite:** a common-code call to a JVM-only
  API (`System.currentTimeMillis()`) → `compileKotlinJs` red.
  **Built** 2026-11-20: `jsMain` holds `crypto/JsPrimitives.kt` over one `@JsModule` file per noble entry
  (`crypto/noble/`: `sha2`, `hmac`, `hkdf`, `argon2`, `utils`' `randomBytes`, `aes`' `gcm`, `ed25519`' `ed25519`/`x25519`,
  `ml-kem`' `ml_kem768`; bytes shared as views, never copied), `nfc`/`nfkd`/`nfd`, `localWallClock` over `Date`,
  `coreWebpEncoder = null`, and the refusals — in two `NotOnTheWeb.kt`, not one: an `actual` lives in its `expect`'s
  package, and `saveKitFile` is `account`'s where `deviceFiles` and `inflate` are `blob`'s (one `NotOnTheWeb` error, in
  `blob/`). The three thread-blocking tests moved to `threadedTest` (M1). The Gradle half of J3/J4 landed here too: the
  `conformance` compilation (`jsConformance/…/crypto/ConformanceRun.kt`: `runConformance`, `runConformanceWithBrokenHkdf`),
  `:core:jsPackage`, the plugin's `.d.ts` validation disabled (it runs `tsc` under Kotlin's Node). J6: 37 components (the
  JavaScript variants of libraries the core already uses, `kotlin-stdlib-js`, `kotlin-dom-api-compat`, `kotlinx-browser`
  among them; 290 lines, none removed) written from an empty Gradle home (`--write-verification-metadata sha256
  :core:jsPackage :core:compileTestKotlinJs :core:compileTestKotlinJvm :bridge:compileTestKotlinJvm`, 10 m 52 s), and no
  `nodejs/` or `yarn/` in that home afterwards. Checks: `:core:jvmTest` 1417 tests, `:core:macosArm64Test` 1188,
  `:bridge:jvmTest` 136, `compileKotlinJs`, `compileTestKotlinJs`, `jsPackage` green with `--warning-mode all` and no
  deprecation (`QueuedOverlayTest`'s gallery case red once under a load of ~9 — a `Dispatchers.Default` wait, outside
  this CR — and green on the re-run). Bite: `System.currentTimeMillis()` in `media/WallClock.kt` →
  `compileKotlinJs` red ("Unresolved reference 'System'"), restored green; M1's 44 errors are the sharper one (a
  `runBlocking` that compiled for every target before turned red with the JavaScript one). `git diff --stat` under
  `src/mobile/` names more than the plan said: `build.gradle.kts` (root), `gradle.properties` and the three moved tests
  (M1, M2, M4).

- [ ] **3 the package** — J3, J4, J5. · Done when: from the repo root, `python3 scripts/pnpm_install_no_deprecated.py`
  green; in `src/packages/core-js`, `pnpm run build` and `pnpm run check` green; `src/packages/crypto`, `src/web`,
  `src/admin-web` and `tests/e2e` checks green unchanged; `ls src/mobile/core/kotlin-js-store src/mobile/kotlin-js-store`
  finds nothing; `python3 scripts/run_pipeline.py` runs the new steps. **Bites:** `JsPrimitives`' AES-GCM tag off by
  one byte → the package's check red; `Conformance` exported from the shipped binary → the package's test that the
  shipped `.d.ts` names no conformance symbol red.
- [ ] **4 harvest** — the root `CLAUDE.md` § Repository shape (the workspace holds `@veyq/crypto` and `@veyq/core-js`,
  and why each), `pnpm-workspace.yaml`'s and the root `package.json`'s comments, `scripts/repo_layout.py` (`CORE_JS`),
  `src/mobile/CLAUDE.md` § Prove your work (`:core:compileKotlinJs :core:compileTestKotlinJs` with the JVM tests; the
  package's check for a change under `crypto/` or `jsMain`), the site's stage map (R6: `frontend` checks crypto,
  core-js, web, admin-web), `native-apps.md` § Platforms (the JavaScript target, what it ships: nothing yet), ADR
  0155's `Built:` (decision 3; decision 6 but the web's half). · Done when: every guard but guard_gaps green
  (`/usr/bin/python3`), the site's `pnpm run check` green.

## Measured at implement

Phase 1, 2026-11-20, on `claude/kjs-p0` (from `claude/windows-cr-f6c73c` at `3a05be7fe`), Kotlin 2.4.21, Gradle 9.8.2,
macOS arm64. Every row `measured` by the command named; `C=src/mobile/core/src`.

| # | Question | Answer |
|---|---|---|
| M1 | Does the common code compile for JavaScript? | **`commonMain`: yes** — `:core:compileKotlinJs` failed on R1's eight `expect`s and nothing else; with J2's actuals it is green. **`commonTest`: 44 errors in 3 files, none an `expect`** — `search/SearchModelTest.kt` (26), `search/SearchVolumeMeasure.kt` (9), `session/DeviceLinkTest.kt` (9): `runBlocking` (17 references) and `newFixedThreadPoolContext` (2), the rest their follow-on suspend/inference errors. Both are kotlinx.coroutines' JVM-and-native API (no JavaScript form: no thread to block). Answer: the three files move, unchanged, into a source set between `commonTest` and the JVM and native targets, `threadedTest` (the hierarchy's `threaded` group: `withJvm()` + `native`); the JVM and native test compilations compile exactly what they did. (`./gradlew :core:compileTestKotlinJs`, the errors in the scratchpad's `p0/testcompile.txt`.) |
| M2 | Does the package's task reach Kotlin's Node, Yarn or npm? | **Not without an environment; always with one.** With `js { nodejs() }` even the link (`compileProductionLibraryKotlinJs --dry-run`) depends on `:kotlinNodeJsSetup`, `:kotlinYarnSetup`, `:kotlinNpmInstall`, `:rootPackageJson` and each compilation's `*PackageJson`, and configuring it fails on the settings' `FAIL_ON_PROJECT_REPOS` ("repository 'Distributions at https://nodejs.org/dist' was added"). With no environment, `:core:jsPackage --dry-run` lists only the core's generate tasks, `compileKotlinJs`, `compileConformanceKotlinJs`, the two production links and their `.d.ts` validations (whose `tsc` runs under Kotlin's Node; ignored by default, disabled here). **The switch:** no environment (the plugin's "JS Environment Not Selected" warning suppressed by its id, `kotlin.suppressGradlePluginWarnings=JsEnvironmentNotChosenExplicitly`: "will be an error in the future releases" — a Kotlin upgrade that makes it one is handed back); the root plugin an environment would have applied (`NodeJsRootPlugin`, whose extension the link's sync task reads — without it `tasks --all` fails) applied in `src/mobile/build.gradle.kts` with `download = false` for Node and Yarn; and a task-graph check there that fails a build reaching any of Kotlin's Node/Yarn/npm tasks (`./gradlew :kotlinNpmInstall --dry-run` → red, naming seven). No `kotlin-js-store/` was written. |
| M3 | Does `src/mobile/` configure with no Android SDK, on JDK 21 as on 25? | **Yes.** `env -i HOME=… PATH=/usr/bin:/bin:/usr/sbin:/sbin JAVA_HOME=<temurin-21.0.13 \| 25.0.5> ./gradlew --no-daemon :core:jsPackage` green on both; in the same environment `:android:assembleDebug --dry-run` fails "SDK location not found" — no SDK was found, and `:core` needed none. Not in a container: no JDK image is on this host, and a pull is the Docker credential-helper stall this machine has (remembered); the emptied environment stands in. |
| M4 | Are two clean builds byte-identical? | **Only without the JavaScript incremental caches.** Default (`kotlin.incremental.js.ir` on), clean `core/build` and `dist/`, fresh in-process compiler each time: the conformance's `kotlin-kotlin-stdlib.mjs` came out in **4 variants in 4 builds** (the interfaces' order in `initMetadataForClass`, e.g. `[Collection, KtList]` against `[KtList, Collection]`); whole-program granularity 3 in 4. With `kotlin.incremental.js.ir=false`: **4 in 4 identical** (`find dist -type f \| sort \| xargs shasum -a 256`, digest `55c98c7b…`). A warm rebuild after a one-line edit took the same with and without the caches (16–17 s against 17–18 s, `:core:jsPackage`, under a load of ~9). So `src/mobile/gradle.properties` turns them off. The shipped module itself (`veyq-mobile-core.mjs`, a few hundred bytes while it exports nothing) was identical in every build. Not measured: a build at another checkout path — the source maps of the third-party klibs name their sources relative to `dist/` (`../../…/Users/admin/Projects/KotlinCrypto/…`), so their bytes depend on the checkout's depth; that is the attested tier's question, not this CR's. |

Each answer is a yes with its switch, so phase 2 went on (the coordinator's stop conditions: `commonMain`, Kotlin's Node/Yarn,
reproducibility, the Android SDK). M1's move of three common tests is a deviation from phase 2's "only `jsMain`,
`jsTest`, the build file and the metadata"; M2 and M4 add `src/mobile/build.gradle.kts` and `gradle.properties` to it.

## Owner questions

None — ADR 0155 settles the target and the package; a phase 1 "no" comes back as a question.

## Definition of done

Phases 1–4 checked; every bite seen red and green; the `frontend` job's time before and after recorded; `git diff
--stat` over `src/web/`, `src/admin-web/`, `src/backend/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
