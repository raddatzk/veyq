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
job. The web consumes nothing yet: that is `cr-web-search-on-the-core`.

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
- **Out of scope:** any façade, any web import, the JSON seam (Expenses', after the pilot: ADR 0155 decision 7).

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

- [ ] **1 the measurement** — first, and a stop: on a scratch branch, `js()` added as J1 and every compile run
  (`:core:compileKotlinJs`, `:core:compileTestKotlinJs`). Recorded in this CR: every error that is not one of R1's
  `expect`s (a JVM- or native-only API in common code is handed back with its list, not worked around); the task
  graph of the package's Gradle task (`--dry-run`) with no `kotlinNodeJsSetup`, `kotlinYarnSetup`, `kotlinNpmInstall`
  or `kotlinStoreYarnLock`, and the switch that keeps them off; whether `src/mobile/` configures with no Android SDK
  present (`ANDROID_HOME` unset, in a JDK container) and on JDK 25 as on 21; two clean builds of the module compared
  byte for byte (`shasum -a 256` over `dist/`). · Done when: the four answers are in § Measured at implement; any
  "no" is handed back before phase 2.
- [ ] **2 the target** — J1, J2, J6. · Done when: `:core:jvmTest :core:macosArm64Test`, `:core:compileKotlinJs
  :core:compileTestKotlinJs` and `:bridge:jvmTest` green; `--warning-mode all` clean; `git diff --stat` shows only
  `jsMain`, `jsTest`, the build file and the metadata under `src/mobile/`. **Bite:** a common-code call to a JVM-only
  API (`System.currentTimeMillis()`) → `compileKotlinJs` red.
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

_(phase 1 fills this)_

## Owner questions

None — ADR 0155 settles the target and the package; a phase 1 "no" comes back as a question.

## Definition of done

Phases 1–4 checked; every bite seen red and green; the `frontend` job's time before and after recorded; `git diff
--stat` over `src/web/`, `src/admin-web/`, `src/backend/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
