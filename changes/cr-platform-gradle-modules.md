---
id: cr-platform-gradle-modules
title: Platform phase 2 — the backend splits into Gradle modules (api-generated, core, apps/memories), one image
status: planned
satisfies: []
adrs: ["0079", "0001", "0057", "0064", "0078"]
invariants: [operator-blind E2EE everywhere, self-hostable by default, API-first]
---

> Provenance marks used below: **(measured: cmd)** a command run against this tree at refine; **(spike)** run in a
> throwaway 3-module Quarkus project outside the repository (so not re-runnable from here — step 2 re-measures each
> one in-tree, and the CR says so where it leans on one); **(remembered)** read from a comment or a doc, not run.
> Substrate: branch `worktree-agent-abca538b2278d13f7` at `67f1173d` (= `main` tip, tree clean) — the phase-1 and
> web-boundaries series are merged.

## Open for the owner

Honest questions first; each carries a recommendation. Nothing below is built until you say go on this CR.

0. **Do we need this at all, now?** Phase 1 already enforces "core never names Memories" with a buildless guard and
   no allowlist, and there is no second application yet. What the module split adds: (a) the direction enforced by
   the compiler instead of a lexer (a core file that imports Memories no longer compiles; a Gradle project cycle
   is a build failure — spike); (b) an *application → application* edge becomes statable and checkable (it exists
   in no guard today, ADR 0079 Consequences); (c) Kotlin `internal` becomes a real boundary; (d) generated code
   compiles once instead of with every core edit. What it costs: four build files plus a convention script, ~25
   `scripts/` guards that hard-code `backend/src/main/kotlin`, ~56 citations of `backend/src/(main|test)` paths in 29 files (measured: `grep -rcE 'backend/src/(main|test)' docs wiki CLAUDE.md backend/CLAUDE.md infra charts .github .claude`), test-fixture
   plumbing, and the release `Dockerfile`'s warm-up stage. **Recommendation: yes, but in two committable halves.**
   Steps 0–1 (cut the last core→Memories edge, make the guards root-agnostic) have value on their own and change
   no build; stop there if you would rather wait for the second application. Steps 2–6 are the Gradle part.
1. **Where does the composition root live?** (a) The existing root project (`backend/`) stays the deployable: it
   keeps `src/main/resources` (`application*.properties`, the web `dist` target), `src/test` (the Quarkus slice
   tests) and the `io.quarkus` plugin, and the three modules sit in subdirectories (`backend/api-generated`,
   `backend/core`, `backend/apps/memories`). (b) A new `backend/app` module holds those and the root becomes a
   bare aggregator. **Recommendation: (a).** `quarkusBuild`, `quarkusDev`, `backend/build/quarkus-app`, the
   Dockerfile's `COPY backend/src` and the web-e2e jar path (`scripts/run_web_e2e.py` `JAR`), `ci.yml`'s
   `working-directory: backend`, `.run/Run Backend.run.xml` all stay byte-for-byte. (b) is tidier and moves ~170
   test files plus every one of those paths for no capability (a).
2. **Do packages move with the modules?** (a) No — `com.veyq.app.features.media` stays the package name inside
   `:apps:memories`; the platform's `features.*` and `shared.*` stay inside `:core`. (b) Rename to
   `com.veyq.app.apps.memories.*` / `core.*` now. **Recommendation: (a), rename later.** A module split that also
   renames packages changes every import in 196 main + 169 test files, so a red build could be either. (a) leaves
   imports untouched — the *only* source edits are the ones the split forces (steps 0, 2). The price is naming
   debt: platform code sitting in `features.*` inside `:core`. The new module guard (step 5) forbids a package spanning two modules, so the rename stays mechanical (own inbox row).
3. **Generated API: one module for all specs, or one per owner?** (a) One `:api-generated` generates *all* ten
   specs exactly as today. (b) `:core` and `:apps:memories` each generate their own. **Recommendation: (a).** (b) is
   blocked by contract facts, measured: seven schemas are defined in more than one spec and generate to the same
   FQN (`PresignedUrl` in media+sharing, `StaleSealedMeta` in `_common`+content+media, `NodeKind` in
   content+sharing, …), `content.openapi.yaml` mixes the platform `content` tag with the Memories-owned
   `favorites` / `item-favorites` / `own-item-favorites` tags, and `media.openapi.yaml` carries the three generic
   blob-batch operations next to Memories' own. Two modules generating the same FQN is a duplicate-class defect;
   un-tangling them is a contracts + web-client CR of its own. The price of (a): Gradle cannot stop `:core` from
   naming a Memories-owned generated type (e.g. `MediaApi`) — measured today: core-package main code imports only
   `ErrorResponse`, `PresignedUrl`, `StaleSealedMeta` from `media.openapi.yaml`, all three of them shared shapes.
4. **Where do the migrations live?** **Recommendation: all 67 existing files stay together in `:core`, at the same
   classpath path `db/migration/`, byte-identical; a Memories-only migration written from now on (V68+) goes in
   `:apps:memories/src/main/resources/db/migration/`.** Why not split the existing set by owner: 9 of 67 files
   touch both a Memories table and a platform table (`V11`, `V13`, `V32`, `V33`, `V35`, `V41`, `V49`, `V55`,
   `V66`) and a migration is never edited — not even a comment (`backend/CLAUDE.md`); six more are Memories-only
   but interleave in the version sequence with platform ones. Why the *same* classpath path and not
   `db/migration/memories/`: Flyway stores the resolved path in `flyway_schema_history.script` (spike: the script
   column reads `db/migration/V2__media.sql`), so a new subdirectory renames what is recorded for a file an
   instance already applied. One path, split across jars, changes nothing a running instance knows (spike:
   works in the test JVM *and* in the packaged fast-jar). The cost is that Flyway's version space is global:
   two CRs allocating the same `V68` in different modules fail the boot with `Found more than one migration with
   version 68` (spike) — loud, and step 1 adds a buildless guard so it fails in the trust chain first.
5. **Test layout.** **Recommendation:** every `@QuarkusTest` (the large majority — see the inventory) stays in the root
   composition's `src/test`, because it boots the whole product and 24 of the platform-package tests need
   Memories classes (a platform module cannot depend on Memories); the Quarkus-free unit tests move with their
   code (three of them *must*: Kotlin `internal` members of core code are tested from the same package, and
   `internal` stops at a module boundary); shared helpers go to `java-test-fixtures` on `:core`; plus **one**
   new `@QuarkusTest` in `:core` that boots core alone with no application on the classpath — the executable proof
   of "core does not need Memories". The alternative (every module's tests live in that module, each with its own
   test config) duplicates `application-test.properties` per module and makes the 24 mixed tests homeless.
6. **`TrimmingStringDeserializer` lives in the API module.** 24 generated-model annotations in `contracts/api`
   name `com.veyq.app.shared.validation.TrimmingStringDeserializer` (measured: `grep -rhoE 'com\.veyq\.app\.[A-Za-z0-9_.]+'
   contracts/api/*.yaml | sort | uniq -c`). Generated code that names a core class makes `:api-generated` →
   `:core` while `:core` → `:api-generated`: a cycle. **Recommendation:** move that one hand-written class into
   `:api-generated` **keeping its FQN**, so no spec and no import changes. Alternative: a fourth module for it
   (rejected: a module for one 20-line class), or rewrite the 24 annotations (a contracts change that buys
   nothing).
7. **Retire the Memories half of `validate_shared_imports_no_features.py`?** Once Gradle forbids the edge, a
   lexer guard saying the same thing is the duplicate-enforcement rule 2 of `docs/ai/README.md` warns about, and
   a human notices a non-compiling import at once (rule 3). **Recommendation: yes, in step 5** — and keep its
   `shared/` → `features/` half, which stays an *inside-core* rule Gradle cannot see.
8. **Where does the durable record go?** The module topology and the migration rule are durable decisions.
   **Recommendation:** edit ADR 0079 in place (Consequences already lists "the Gradle module split" as not done;
   Decision 10 already says "applications as packages of it") plus `backend/CLAUDE.md`; a new ADR only if you
   want the migration-location rule citable on its own.

## Goal

Phase 2 of ADR 0079: **the backend's boundary becomes a build boundary, with no behaviour change and one
image.** After it:

- `:core` compiles with no Memories on its classpath; `:apps:memories` depends on `:core`; neither depends on the
  other way round (Gradle refuses a cycle), and no application depends on another (a guard, because Gradle does
  not).
- The generated API compiles once, in `:api-generated`.
- `contracts/`, every applied migration (bytes, order, path), every published route, every config key and the
  container image are unchanged — `./gradlew quarkusBuild` still yields one `quarkus-app`, run by the same
  entrypoint.
- Every guard that read `backend/src/main/kotlin` reads all of the backend, and a guard that silently reads *less*
  after a move is impossible (step 1's helper + bite).

Not in this CR: opening the node `kind` beyond `FOLDER`/`MEMORY`/`CHAPTER`, renaming packages, splitting the
generated API by owner, the `veyq.media.*` config keys (below), the web side (done), `rootKind` on `PublicShare`
(#329), node URLs (#328), the `wiki/` update. A second application is the *next* CR, not this one.

## State (what exists, measured)

| Fact | How measured |
|---|---|
| One Gradle project, no `include` (`Root project 'veyq-backend' / No sub-projects`); Gradle 9.5.1; Kotlin 2.3.21; Quarkus 3.36.0; the PIT plugin applied at the root | `cd backend && ./gradlew --offline projects`; `backend/build.gradle.kts`, `backend/settings.gradle.kts` |
| 196 main + 169 test Kotlin files; 67 migrations `V1`–`V67` in `db/migration`; `application{,-dev,-e2e,-prod,-test}.properties` in `src/main/resources` (the test profile ships in the image) | `find backend/src/main -name '*.kt' \| wc -l` (and `src/test`); `ls backend/src/main/resources/db/migration \| wc -l`; `ls backend/src/main/resources` |
| The Memories packages are exactly three: `features.media` (15 files), `features.content.favorites` (5), `features.content.itemfavorites` (7). Everything else — `core.*` (12), `shared.*`, `features.{account,admin,platform,sharing,spaces,tasks}` and the four node files of `features.content` — is platform | `ls backend/src/main/kotlin/com/veyq/app/features/*` |
| **Exactly one import** from platform main code into a Memories package: `features/spaces/scope/ScopesStore.kt` → `ItemFavoritesEntity`, used at two sites (member removal, removal-rotation). The phase-1 guard misses it because it scans `core/` and `shared/` only. Three angles agree: the import grep, the entity/table-name grep, and a package-keyed import graph | `grep -rnE 'import com\.veyq\.app\.features\.(media\|content\.(item)?favorites)' backend/src/main/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'`; `grep -rnE 'MediaEntity\|ItemFavorites\|veyq_media\|veyq_bucket' <platform packages>`; a throwaway ~20-line script that classifies each file by its `package` line and collects imports across the cut (all three: 1 edge) |
| The reverse (Memories → platform) is wide: ~100 imports of `shared.*` and `core.*`, as designed | same graph |
| Test side: 123 of 169 test files are `@QuarkusTest`; **24 platform-package tests import Memories** (all the `features/sharing` upload/trash/quota tests, the `content` trash/eraser tests, `ScopeEraserOrderSliceTest`, `OperatorBlindProbeTest`, …) | `grep -rlE 'features\.(media\|content\.(item)?favorites)' backend/src/test/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'` (26 lines; two are KDoc-only mentions in the blob-store fakes) |
| Only three `internal` members in main, all platform code with tests in the same package: `SessionPurge`'s companion, `TraefikFileIngressSink.render`, `TenantIngressProjector.isRoutableHostname` | `grep -rnE '^\s*internal ' backend/src/main/kotlin`; `grep -rlE 'isRoutableHostname\|\.render\(\|SessionPurge' backend/src/test/kotlin` |
| ArC `@All` / `Instance<>` injection points that collect Memories implementations: `ScopePurge` (`ScopeEraser`), `NodeSubtreePurge` (`NodeSubtreeEraser`), `SharingStore` (`SharedSubtreeContributor`, `GuestIdentityUsage`), `SharingHandler` (`ShareGuestWrites`) | `grep -rnE '@All\|Instance<' backend/src/main/kotlin` |
| Generated code is not tracked; `compileKotlin`/`compileJava` depend on `openApiGenerateSpecs`; models and JAX-RS interfaces for **all ten** specs land in one package each (`com.veyq.app.generated.{model,api}`); the openapi-generator runs in-process from a `buildscript` classpath; `AdminChannelOperations.kt` is generated from `x-veyq-channel` | `backend/gradle/openapi.gradle.kts` |
| No spec `$ref`s another spec except `_common.openapi.yaml`; so the files split cleanly. But 7 schemas are defined in 2–11 specs (above), and 24 annotations name a `shared.validation` class | `grep -ho "[a-z_]*\.openapi\.yaml#" contracts/api/*.yaml \| sort \| uniq -c`; a 10-line yaml walk over `components.schemas` |
| 67 migrations: 52 mention no Memories table, 6 are Memories-only (`V12 V14 V15 V16 V18 V34`), 9 are mixed | a 12-line script: per file, tables named (comments stripped) ∩ `{veyq_media, veyq_media_derivative, veyq_bucket, veyq_scope_favorites, veyq_scope_item_favorites}` |
| Cold `compileKotlin compileTestKotlin` (incl. generation), warm Gradle daemon: **57 s** on this machine. `./gradlew check` is ~2m45s — **remembered** from a comment in `backend/build.gradle.kts`, not re-run (it is the baseline step 1 records) | `time ./gradlew --offline compileKotlin compileTestKotlin` |
| No native build, no reflection config, no `beans.xml`/`jandex` anywhere in the tree today; the Dockerfile's native stage was removed (#200, #243) | `find backend/src -name 'beans.xml' -o -name 'reflection-config*'`; `Dockerfile` |
| `infra/` and `charts/` consume only the image (`quarkus-app` appears only in comments): the Gradle layout is invisible to both deployment targets | `grep -rnE 'quarkus-app\|backend/(src\|build)\|gradlew' infra charts` |
| The Gradle dependency verification is one file at the build root (`backend/gradle/verification-metadata.xml`) — it covers every project of the build; its pruning recipe is in `backend/CLAUDE.md` | `backend/CLAUDE.md` § Prove your work |

### What the spike measured (throwaway project: `:core` + `:memories` + `:app`, Quarkus 3.36.0, Kotlin 2.3.21)

| Question | Result |
|---|---|
| Bean in a workspace module with **no** `META-INF/beans.xml` | not discovered: `UnsatisfiedResolutionException` at build. An `@All` list would instead silently *shrink* |
| Same, **with** an empty `META-INF/beans.xml` | `@Entity`, `@ApplicationScoped`, `@All` implementations and `@ConfigMapping` are found across modules; two `ScopeEraser`-shaped beans from two jars were both collected |
| A `@Path` interface in a module without `beans.xml`, resource in an indexed module | the resource **answers 404** — no error anywhere. With `beans.xml` on the interface's module: 200 |
| `V1` in module A and `V2` in module B at the same `db/migration/` path | both applied in order, both in the test JVM and from the packaged fast-jar; `flyway_schema_history.script` = `db/migration/V2__media.sql` |
| The same version in two modules | boot failure `Found more than one migration with version 2`, naming both jars |
| `@QuarkusTest` in a library module | **fails without the `io.quarkus` plugin** (`Task 'integrationTestClasses' not found`); with the plugin applied it boots that module alone with only its own beans. `check --dry-run` contains no `quarkusBuild` |
| A project cycle `:core` ↔ `:memories` | `Circular dependency between the following tasks` — build failure |
| `./gradlew test --tests '*X'` across projects | fails with `No tests found for given includes` in every project that has no match |
| The packaged fast-jar of the 3 modules | `quarkus-app/lib/main` holds one jar per module; boots, migrates, serves |

## Target shape

| Module (dir) | Contains | Depends on |
|---|---|---|
| `:api-generated` (`backend/api-generated`) | `openapi.gradle.kts` moves here (generation of all ten specs, `AdminChannelOperations`); the one hand-written class `shared.validation.TrimmingStringDeserializer` (same FQN); `META-INF/beans.xml` | the Quarkus BOM, jackson, `jakarta.ws.rs`, `jakarta.validation` — no project |
| `:core` (`backend/core`) | `core.*`, `shared.*`, `features.{account,admin,platform,sharing,spaces,tasks}`, the node files of `features.content` (`ContentResource`, `NodesHandler`, `NodesStore`, `NodeSubtreePurge`), **all 67 migrations**, `beans.xml`, a `testFixtures` source set, the Quarkus-free unit tests of its code, the PIT plugin + `pitest.scope.json`, one core-alone `@QuarkusTest` | `:api-generated` |
| `:apps:memories` (`backend/apps/memories`) | `features.media`, `features.content.favorites`, `features.content.itemfavorites`, `beans.xml`, its Quarkus-free unit tests; future Memories-only migrations | `:core`, `:api-generated` |
| root / composition (`backend/`) | `io.quarkus`, `src/main/resources` (`application*.properties`, web `dist` target), `src/test` (all `@QuarkusTest` slice tests and their Quarkus-bound helpers), `quarkusBuild`/`quarkusDev`, `gradle/verification-metadata.xml`, the wrapper | `:api-generated`, `:core`, `:apps:memories` |

**How the direction is enforced, per rule.** *Core never imports Memories* — `:core` has no `project(":apps:…")`
dependency, so the import does not compile, and adding the dependency is a project cycle (spike). *An application
never imports another* — nothing in Gradle prevents `:apps:a` → `:apps:b` (no cycle), so a buildless guard parses
the build files (step 5; its bite needs a forged second application module). *No package spans two modules* — a guard (Kotlin does not forbid split packages; Quarkus would index both).
*Every module is indexed* — a guard on `beans.xml` (the silent failures above). *Inside core*, `shared/` must still
not import `features/` — the surviving half of the existing guard.

**Quarkus specifics, decided.** Indexing: an empty `META-INF/beans.xml` per module (no extra dependency — the
Jandex Gradle plugin would be one more third party to keep alive, `CLAUDE.md` no-unmaintained-deps). Config:
`application*.properties` stay in the root only; a library `application.properties` would be a second same-ordinal
source on the classpath (remembered, not measured). `:core`'s own boot test gets a minimal config file of its
own under `:core/src/test/resources` — **step 3 decides one home for the `%test` keys** (risk R6). Library modules apply the `io.quarkus` plugin only where a
`@QuarkusTest` runs (`:core`); the other two apply Kotlin + all-open + the shared convention. Native / GraalVM:
nothing to configure — there is no native build to carry (remembered: reflection registration is generated from
the index, so `beans.xml` would be the only thing a future native build needs from the modules; not measured).
Config mapping: `VeyqConfig` stays whole in `:core`, including its `media()` section — `veyq.media.upload.retention`
is read by core (`BlobStoreStartupCheck`, `StoreReclaim`), `veyq.media.trash.retention` only by Memories; renaming
operator-facing keys is out of scope (own inbox row).

## Inventory — the locators (run them; the tally is not part of the CR)

| What | Command |
|---|---|
| Module members, by package | `ls backend/src/main/kotlin/com/veyq/app/{core,shared,features/*}` |
| Platform → Memories imports (main), angle 1 (import) | `grep -rnE 'import com\.veyq\.app\.features\.(media\|content\.(item)?favorites)' backend/src/main/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'` |
| … angle 2 (names, comments excluded) | `grep -rnE 'MediaEntity\|MediaDerivativeEntity\|BucketEntity\|FavoritesEntity\|veyq_media\|veyq_bucket\|veyq_scope_favorites\|veyq_scope_item_favorites\|MediaStore\|MediaHandler' backend/src/main/kotlin/com/veyq/app/{core,shared,features/{account,admin,platform,sharing,spaces,tasks}}` plus the four `features/content/*.kt` node files; drop `^\s*(\*\|//\|/\*)` lines |
| Memories → platform imports (the wide direction, as designed) | the same throwaway package-graph script, keyed the other way; or `grep -rhoE 'import com\.veyq\.app\.(shared\|core)\.[A-Za-z.]+' backend/src/main/kotlin/com/veyq/app/features/{media,content/favorites,content/itemfavorites} \| sort \| uniq -c` |
| Tests that need both sides | `grep -rlE 'features\.(media\|content\.(item)?favorites)' backend/src/test/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'` |
| `internal` members and their tests | `grep -rnE '^\s*internal ' backend/src/main/kotlin`; then `grep -rl` each symbol under `backend/src/test` |
| Non-Quarkus test files (move with their code) | `grep -rLE '@QuarkusTest' backend/src/test/kotlin --include='*Test.kt'` and the helper files beside them (`TestIds`, `TestVeyqConfig`, `TestProofKey`, `TestBlob`, the blob-store fakes) |
| `@All` / `Instance<>` consumers | `grep -rnE '@All\|Instance<' backend/src/main/kotlin` |
| Scripts that name the backend tree | `grep -lE 'backend/(src\|build)\|"backend" */ *"src"\|backend/pitest\|com/veyq/app' scripts/*.py` — second angle, keyed on the Python constants: `grep -nE 'BACKEND\|KOTLIN_MAIN\|MIGRATIONS\|TEST_ROOT\|SOURCE_ROOTS' scripts/*.py` |
| Docs and config that cite a moved path | `grep -rnE 'backend/src/(main\|test)' docs wiki CLAUDE.md backend/CLAUDE.md infra charts .github .claude` |
| CI jobs that name backend paths | `grep -nE 'backend' .github/workflows/*.yml` (`mutation-backend.yml` lists 14 source/test files + `build.gradle.kts` twice; `codeql.yml` filters `backend/**`) |
| Migrations, by owner | the 12-line script of § State; plus `grep -lE 'veyq_media\|veyq_bucket\|veyq_scope_(item_)?favorites' backend/src/main/resources/db/migration/*.sql` |
| What consumes the build output | `grep -rnE 'quarkus-app\|backend/build' Dockerfile scripts .github infra charts .run` |
| Flow surfaces (inbox + CRs) naming the split | `grep -nE 'Gradle module\|phase 2\|#324' docs/inbox.md changes/*.md` |

## Steps

One commit per step, each ending with the checks it names; a step that fails one is not done. The permanent
checks of every step: `cd backend && ./gradlew check`, and `for f in scripts/validate_*.py; do python3 "$f"; done`
(python3 is 3.9). **A neighbouring CR editing backend files during steps 3–4 collides with the moves** — check
`ls changes/` and `git status` first; land from the worktree by `--no-ff` merge, never by editing the main
checkout. Proofs that need the live stack are marked **[LIVE]**; the owner (or CI) runs those, the others run
anywhere.

### 0 — Cut the last platform → Memories edge, and make the guard see all of the platform

*Why first:* it is the only edge standing between today's tree and `:core` compiling alone, and it needs no build
change — a behaviour-preserving refactor of the phase-1 kind, provable by the existing suite.

- **Change.** A core interface `MembershipEraser` (`core.scope`, the ADR 0079 name) — "one user loses access to one
  scope; nothing is erased from the scope" — called from the two `ScopesStore` sites inside their existing
  transactions; `ItemFavorites…MembershipEraser` implements it in `features.content.itemfavorites`. Collected like
  the other seams (`@All MutableList`, not `List` — the ArC trap in `backend/CLAUDE.md`). No phase table: one
  implementation, and the doc says implementations must not take locks beyond the caller's scope-row lock.
- **Guard first (red → fix → green).** Extend `validate_shared_imports_no_features.py`: the Memories ban now covers
  *every main Kotlin file whose package is not a Memories package* (the inverse derivation — no list of platform
  packages to rot, and exactly the cut Gradle will make). Run the predicate on the untouched tree first: it must
  be red on `ScopesStore` only.
- **Acceptance.** `grep` angle 1 of the inventory returns nothing; `ScopesStore` names no favorites entity; member
  removal and removal-rotation still delete that member's item favorites for that scope and no other user's or scope's.
- **Proof.** `./gradlew check`; the validators; **[LIVE]** `python3 scripts/run_backend_races_on_mariadb.py` — this
  moves a delete that runs under the scope row lock inside `rotate` (ADR 0078), and `RemovalRaceSliceTest` asserts
  `ItemFavoritesEntity.count(...) == 0` on it.
- **Bite-tests (lever named per bite).** (1) *live defect:* the extended guard on the untouched tree → red on the
  one line. (2) *control:* a forged `features/media` file importing `core.*` stays green (the ban is one-way).
  (3) *injection:* a forged `features/spaces` file importing `features.media` → red (new `validate_guard_gaps`
  case, closed-hole kind, replacing none: cases 34 and 38 stay until step 5). (4) *behaviour:* delete the
  implementation's `@ApplicationScoped` in a scratch copy → `RemovalRaceSliceTest` and the non-race removal test go
  red, with a positive control in the same test (another scope's favorites survive; otherwise a purge-everything
  bug would also read green).
- **Done when:** the above, and `ADR 0079` Consequences' "`ScopesStore` still imports…" sentence is deleted
  (state, not diary).

### 1 — Make the tools root-agnostic and freeze the migration set (no build change)

*Why before any move:* the 25-odd scripts that hard-code `backend/src/main/kotlin` are the real blast radius of a
move, and a guard that quietly scans less after a move is red on nothing.

- **Change.** One helper `scripts/backend_tree.py`: `main_roots()`, `test_roots()`, `migration_roots()` — every
  `…/src/main/kotlin`, `…/src/test/kotlin`, `…/src/main/resources/db/migration` under `backend/` that git lists
  (derived by glob, never listed) — and `find(rel)` to resolve a `com/veyq/app/…` path across roots. Rewire each
  script the second-angle locator lists: whole-tree scanners iterate all roots (`validate_http_failure_projection`,
  `validate_lookup_single_home`, `validate_ratelimit_core`, `validate_wrap_method_vocabulary`,
  `validate_store_reclaim_arity`, `validate_blob_reference_arity`, `validate_grant_writer_arity`,
  `validate_scope_erasure_arity`, `validate_node_subtree_erasure`, `validate_test_fixture_single_home`,
  `validate_maxlength_storage`, `validate_request_bounds`, `validate_coverage`, `validate_doc_citations`,
  `validate_tenant_ingress_triggers`, `validate_config_secret_persistence`, `chart_meta_check`,
  `validate_scope_model_vocabulary`, `validate_shared_imports_no_features`, `run_backend_races_on_mariadb`);
  single-file anchors go through `find`; `validate_guard_gaps`' forgery paths and the mutation scripts follow in
  the steps that move their targets (the helper makes that a one-line edit each). Each rewired guard keeps its
  own "a run that finds none is red" line.
- **New guard `validate_migrations_frozen.py` + `scripts/applied_migrations.sha256`.** Asserts over the union of
  `migration_roots()`: file names match `V<n>__*.sql`; versions are unique across roots; every version in the
  pin file exists with exactly the pinned SHA-256; no new version sits below the highest pinned one (Flyway's
  `outOfOrder=false` makes that a start failure on every existing instance — remembered). The pin is generated
  once from the unmoved tree (`shasum -a 256 backend/src/main/resources/db/migration/V*.sql`), its header names
  the commit and command, and it ends at V67 (later migrations are pinned when released, by hand, as a line in
  the release CR — an open edge, stated in the docstring).
- **Acceptance.** Every rewired guard prints the same verdict and the same file counts as before the step (record
  both in the commit message — the count is the comparison, `changes/README.md`: a "same before and after"
  condition needs a comparable set). `race_classes()` returns the identical list (run it without Docker).
- **Proof.** Validators + `./gradlew check` (nothing in `backend/` changed). Baseline recorded in the commit:
  wall time of `./gradlew check` on the owner's machine, and `ls scripts/validate_*.py | wc -l` (the DoD's
  baseline is a command, not a number).
- **Bite-tests.** (1) *Second root seen* — new `validate_guard_gaps` case: the forgery creates
  `backend/apps/memories/src/main/kotlin/…` in the throwaway copy with a file each of the whole-tree guards must
  reject; the lever is the helper — revert one guard to its old constant and the case goes red. (2) *Migrations:*
  a one-character edit of a comment in `V11` → red; `git mv` of `V11` byte-identical into a second root → green
  (the point of the guard); a second root holding any `V12__x.sql` → red (duplicate); a new `V30__x.sql` → red
  (below the pin); deleting `V67` → red.
- **Done when:** the above plus `python3 scripts/validate_guard_gaps.py` green with the new cases.

### 2 — Gradle skeleton and `:api-generated` (the first real module)

- **Change.** `settings.gradle.kts` includes `:api-generated`; `backend/gradle/module.gradle.kts` (applied like
  `openapi.gradle.kts` is today) carries what every module repeats: toolchain 21, Kotlin + all-open for
  `@Entity`/`@MappedSuperclass`/`@ApplicationScoped`, the BOM, `kotlin { javaParameters = true }`, `useJUnitPlatform`.
  Plugin versions stay declared once, at the root. `openapi.gradle.kts` moves into the module (its `fileTree`
  path becomes `../../contracts/api`; the generated dirs follow the module); the root's compile tasks drop their
  direct `dependsOn` and the root depends on the module. `TrimmingStringDeserializer` moves in (open question 6).
  `META-INF/beans.xml`. The Dockerfile's `backend-build-base` stage copies the new module's `build.gradle.kts` —
  the warm-up (`./gradlew help`) configures every included project, and a missing directory is an error in
  Gradle 9 (remembered; measure it when you hit it).
- **Acceptance.** `contracts/` diff empty. The generated sources are byte-identical to before
  (`diff -r` of the old `build/generated/openapi-{api,models}` against the new — measured once, here, not a
  guard). No route changed.
- **Proof.** `./gradlew check` — `OperatorBlindProbeTest.everyContractOperationIsProbedOrExcluded…` derives its set
  from the generated interfaces and probes each, so an unindexed `:api-generated` shows as 404s, not as a quiet
  pass (spike: the 404 was silent *without* it). `./gradlew quarkusBuild` and the fast-jar lists
  `…api-generated….jar`. `GRADLE_USER_HOME=$(mktemp -d) ./gradlew help check quarkusBuild pitest` **without** the
  write flag is green (the dependency set did not grow; if it asks for entries that is a finding — read the
  diff, `backend/CLAUDE.md`). Dockerfile warm-up without Docker: copy exactly the files its `COPY` lines name
  into a temp dir and run `./gradlew --offline help` there. **[LIVE]** `docker build .` once (the real image).
- **Bite-tests.** (1) delete `api-generated/…/beans.xml` → `OperatorBlindProbeTest` red (lever: the 404 the spike
  measured). (2) point `TrimmingStringDeserializer` back into `:core` (scratch) → `:api-generated` fails to
  compile — the cycle is loud, no guard needed. (3) drop the new `COPY` line from the Dockerfile in a scratch
  copy → the temp-dir warm-up fails.
- **Done when:** the above; `backend/CLAUDE.md` § Conventions names the new generation task path.

### 3 — `:core`

*The big move and the one with the most to lose; one commit, mechanical.*

- **Change.** `git mv` (history-preserving) the platform packages, **all 67 migrations**, and the Quarkus-free
  unit tests of that code (including the three that test `internal` members, and the PIT scope's seven policy tests)
  into `backend/core`. `:core` gets `beans.xml`, the `io.quarkus` plugin (spike: required for a `@QuarkusTest`
  in a library) with `quarkusBuild`/`quarkusDev` irrelevant there, `java-test-fixtures` for the helpers both sides
  use (`testing/TestFixtures`, `Race`, `TestIds`, `TestVeyqConfig`, `TestProofKey`, the blob-store fakes, `TestBlob`
  only if it ends up Memories-free — otherwise it stays in the root), the PIT plugin and `pitest.scope.json`
  (root-level manifests stay readable by `scripts/`). The Memories packages and every `@QuarkusTest` stay in the
  root for now — the root is `:core`'s only consumer, and the direction is already right
  (root → `:core`).
- **The single home for `%test` keys (R6).** `application-test.properties` is read by the root's tests only
  today. Decide and write down where it lives so the core-alone boot test (step 5) and the root's tests do not
  read two copies of the same keys at the same ordinal: recommended — it stays in the root; the core-alone test
  carries a minimal `application.properties` of its own under `:core/src/test/resources`, and a comment names the
  one other place the keys live.
- **Acceptance.** No source file's *content* changes except import-free moves (packages untouched — open question
  2); `git diff -M --stat main..` shows renames only for `.kt`/`.sql`. **The migration set is bit-identical:**
  `validate_migrations_frozen.py` green and `diff <(shasum -a 256 <old>/V*.sql) <(shasum -a 256 <new>/V*.sql)`
  empty. Every path in `mutation-backend.yml`, `pitest.scope.json`, the mutation scripts, `validate_guard_gaps`
  forgeries and the doc citations resolves (`validate_mutation_trigger_paths`, `validate_doc_citations`,
  `validate_coverage` green). `race_classes()` unchanged; `scripts/run_backend_races_on_mariadb.py` addresses the
  root project explicitly (`:test`) — a bare `test --tests X` now fails in every project without a match (spike).
- **Proof.** `./gradlew check` — nothing deleted or weakened; account per assertion for the moved tests, not by
  count (`changes/README.md`). `./gradlew pitest` (the mutation score is the same; its report path moved to
  `backend/core/build/reports/pitest`, which `mutation_meta_check_backend.py` and its selftest read).
  **[LIVE]** the race suite on MariaDB — the transactional code now lives in a jar, and an interceptor binding
  that silently stops applying (module not indexed) is exactly what H2 cannot show (ADR 0078). **[LIVE, upgrade]**
  an existing database, not a fresh one: boot the *pre-step* fast-jar against a persistent database (H2 file in
  `MODE=MariaDB` is enough and needs no Docker; MariaDB is the stronger proof), stop it, boot the *post-step*
  fast-jar against the same file and read `Successfully validated 67 migrations` / `Schema … is up to date` —
  no repair, no checksum error.
- **Bite-tests.** (1) delete `core/…/beans.xml` → the root's slice tests go red at build (unsatisfied
  dependency, spike) — and `ScopeEraserOrderSliceTest`-style tests catch the quieter case where only an `@All`
  list shrinks. (2) a one-character edit of a comment in a moved migration → `validate_migrations_frozen` red
  (lever: the pin). (3) a `@Transactional` method whose module is un-indexed (scratch: remove `beans.xml`, keep
  the class reachable through a direct reference) is the one case that needs the live race run — say so in the
  commit rather than claim H2 shows it. (4) re-point a script constant at the old path → its guard is red
  ("found none"), not green.
- **Done when:** the above; `backend/CLAUDE.md` § Prove your work lists the new `:core:test` / `:test` split and
  the `--tests` filter rule.

### 4 — `:apps:memories`

- **Change.** `git mv` `features.media`, `features.content.favorites`, `features.content.itemfavorites` and their
  Quarkus-free unit tests (`ChunkFramesTest`, …) into `backend/apps/memories`; `beans.xml`; depends on `:core`
  and `:api-generated`. The root now holds no main Kotlin. Packages untouched.
- **Acceptance.** `:core` has no `project(":apps:…")` line and compiles alone (`./gradlew :core:compileKotlin`
  green is the proof the one edge really was one edge). The `@All`/`Instance<>` consumers still collect the same
  set — `ScopeEraserOrderSliceTest`, `NodeSubtreeEraserOrderSliceTest`, `GuestIdentityUsageTest` pin it by name and
  phase.
- **Proof.** `./gradlew check`; the cold-home verification run of step 2; **[LIVE]** the race suite; **[LIVE]**
  `python3 scripts/run_web_e2e.py` against the fast-jar (the packaged multi-module jar serving the real stack —
  this is also the first run where Flyway reads one `db/migration/` directory from two jars on MariaDB); `docker
  build .`.
- **Bite-tests.** (1) *the silent one:* delete `apps/memories/…/beans.xml` → `ScopeEraserOrderSliceTest` red (the
  pinned set loses its Memories erasers), `OperatorBlindProbeTest` red (media routes 404). (2) add
  `implementation(project(":apps:memories"))` to `:core` → `Circular dependency` (spike). (3) add an import of
  `features.media` to a `:core` file → `:core:compileKotlin` fails. (4) a Memories migration `V12__x.sql` in
  `apps/memories` → `validate_migrations_frozen` red; `V68__x.sql` there with a `veyq_media` column → green and
  applied after `V67` on a fresh H2 (the rule open question 4 states).
- **Done when:** the above.

### 5 — Enforcement that Gradle cannot do, the core-alone proof, and the guard hand-over

- **New guard `validate_backend_module_graph.py`** (buildless; derives everything from `settings.gradle.kts` and the
  build files): (a) the set of included projects equals the directories holding a `build.gradle.kts`; (b)
  project-dependency edges are a subset of {`apps:*` → `core` → `api-generated`} — in particular **no
  `apps:*` → `apps:*`** (the one edge Gradle allows); (c) every module has `src/main/resources/META-INF/beans.xml`;
  (d) no package is declared under two modules (derived from the files' `package` lines — no table to rot); (e) the Dockerfile's
  `backend-build-base` stage copies every included module's `build.gradle.kts`. Docstring fields: asserts / the
  defect that bought it (the spike's silent 404 and silent `@All` shrink) / its limit (it reads files, not the
  resolved graph).
- **Core-alone boot test** in `:core` (`@QuarkusTest`, minimal config): boots with no application on the classpath;
  asserts the `ScopeEraser` / `NodeSubtreeEraser` / `SharedSubtreeContributor` lists hold exactly core's own
  implementations (with the control that core's are *present*), and that an unauthenticated core route answers.
  *Why it is worth a test:* the compiler already forbids core importing Memories; this catches a *runtime*
  coupling the compiler cannot — a core bean that injects an interface only Memories implements (an
  `UnsatisfiedResolutionException` at core-alone boot) or a core config key only an application defines.
- **Hand-over of the old guard.** Delete the Memories half of `validate_shared_imports_no_features.py` (open
  question 7); keep the `shared/` → `features/` half; `validate_guard_gaps` cases 34 and 38 (core importing media)
  are rewritten against what now rejects it — the module guard or are removed with the reason stated in the
  case table, never left pinning a guard that no longer checks it.
- **Acceptance.** The guard is green on the final tree and its docstring says what each rule bites.
- **Bite-tests.** (1) a forged `backend/apps/other/build.gradle.kts` with `implementation(project(":apps:memories"))`
  and a matching `include` → rule (b) red (lever: forged second application — the only way to exercise
  app→app before a real one exists). (2) delete a `beans.xml` → (c) red. (3) a `:core` file declaring
  `package com.veyq.app.features.media` (the package `:apps:memories` declares) → (d) red. (4) drop a module's `COPY` from the Dockerfile → (e) red.
  (5) core-alone test: add `@Inject lateinit var x: ShareGuestWrites` (non-`Instance`) to a core bean in a scratch
  copy → the core-alone boot fails; remove it → green.
- **Done when:** the above, and the trust-chain glob is green.

### 6 — Close the series

- **Docs (edit in place, no addenda).** ADR 0079 Consequences: delete "Not done: the Gradle module split" and the
  `ScopesStore` remainder, state the module topology, the migration rule and the package-naming debt;
  `CLAUDE.md` § Repository shape ("inside the one Gradle module"); `backend/CLAUDE.md` (module layout, which tests
  run where, the `--tests` rule, the pruned verification recipe still names `help check quarkusBuild pitest`);
  `docs/apps/README.md` and `docs/apps/memories.md` ("Separated only in part"); the developer styleguide § on
  `shared/` (which guard holds what now); `infra/README.md:63` (`:backend:test`). Inbox #324: replace the "Gradle
  module split (phase 2) … not written yet" sentence with its outcome and delete the row only if nothing is left
  (the node `kind`, `wiki/`, package renames remain).
- **Retrospective** written; the CR deleted after what it taught moved to where it binds (`changes/README.md`).

## Risks

| # | Risk | Where it bites | Held by |
|---|---|---|---|
| R1 | A module not indexed: beans, entities, `@ConfigMapping`s, `@Path` interfaces, interceptor bindings vanish. Injected beans fail loudly; **`@All` lists shrink and unindexed `@Path` interfaces 404 silently** (spike) | steps 2–4 | `beans.xml` per module + guard rule (c); the pinned-set slice tests; `OperatorBlindProbeTest`; the live race run for the interceptor case |
| R2 | A migration changes bytes, path or order → startup failure on every existing instance, green on the dev machine (`%dev` repairs) | step 3 | `validate_migrations_frozen` + the upgrade rehearsal on a database that already has history |
| R3 | Guards that read a shrunken tree after the move and stay green | steps 1, 3, 4 | the helper; per-guard "found none" lines; the second-root `validate_guard_gaps` case |
| R4 | `./gradlew test --tests X` fails per project with no match; `run_backend_races_on_mariadb.py` is one such caller | step 3 | the script addresses `:test` and prints its class list |
| R5 | Kotlin `internal` stops at a module: 3 members, tests must move with them | step 3 | compile errors name them; listed in the inventory |
| R6 | Two `application-test.properties` on one test classpath, same config ordinal (remembered) | step 3 | one home, decided in the step |
| R7 | The Dockerfile warm-up stage copies named files only; a new module without its `COPY` breaks the *release* build and nothing in `./gradlew check` shows it | steps 2–5 | the temp-dir warm-up each step; rule (e); **[LIVE]** `docker build .` |
| R8 | `quarkusDev` with workspace modules: hot reload of `:core`/`:apps:memories` classes, and the IntelliJ run config — **not measured** (a dev server was out of scope for this refine) | step 4 / close | **[LIVE]** the owner starts `Run Backend` once; if reload across modules is broken, that is a finding, not a blocker for the image |
| R9 | PIT across modules: `info.solidsoft.pitest` in `:core`, report path and trigger paths move | step 3 | `./gradlew pitest`, `mutation_meta_check_backend.py`, `validate_mutation_trigger_paths.py` |
| R10 | Dependency verification: a new project can add configurations that resolve coordinates the single project never did (test fixtures, library `io.quarkus`) | steps 2–3 | the cold-home recipe without the write flag |
| R11 | Build time: the spike's three tiny modules say nothing about wall time. The monolith's cold compile is 57 s (measured); the split is *expected* to speed incremental builds and slow a cold one slightly — **unmeasured** | step 3 | step 1 records the `check` baseline; step 4 records the after, both in commit messages; if cold `check` regresses by more than a minute, say so before step 5 |
| R12 | A parallel session edits a file this series moves; the `--no-ff` merge is blocked by a neighbour's uncommitted edit (`memory`) | steps 3–4 | check `git status`/`ls changes/` first; wait, never clear it |
| R13 | Dependabot's `gradle` entry points at `/backend`; whether it covers subprojects is **not measured** | close | note in the retro; a one-line `directories:` addition if it does not |

## Definition of done

- **The enforcement exists and is bitten.** `validate_backend_module_graph.py` and `validate_migrations_frozen.py`
  are green on the final tree and every bite listed above was made red once and recorded in its commit message
  (lever named). `validate_shared_imports_no_features.py` keeps only the half Gradle cannot see.
- **Behaviour unchanged.** `./gradlew check` green with no test deleted or weakened (accounted per assertion where
  a test moved). `git diff main -- contracts/` empty; `git diff main -M --stat -- 'backend/**/db/migration/*'`
  shows renames only, and `diff` of the two `shasum` listings is empty. No crypto-vector diff.
  `race_classes()` identical before and after, and **[LIVE]** `python3 scripts/run_backend_races_on_mariadb.py`
  green on the final tree.
- **One image, upgrade-safe.** **[LIVE]** `docker build .` green; **[LIVE]** `python3 scripts/run_web_e2e.py`
  green against the final fast-jar; the upgrade rehearsal (old jar migrates a persistent database, new jar boots it,
  `Successfully validated 67 migrations`) recorded. `infra/` and `charts/` are untouched (`git diff main --stat --
  infra charts`).
- **Independence is demonstrated, not asserted.** `:core:compileKotlin` and the core-alone boot test are green with
  `:apps:memories` absent from the build (`./gradlew :core:check`).
- **Baseline commands, run before the first edit:** `ls scripts/validate_*.py | wc -l`; `cd backend && time
  ./gradlew check`; `python3 -c "import sys; sys.path.insert(0,'scripts'); import run_backend_races_on_mariadb as r;
  print(r.race_classes())"`; `find backend/src -name '*.kt' | wc -l`. (A baseline is the command that produces it.)
- **Trust chain.** The whole glob green on the merged tree. Named: `validate_guard_gaps`,
  `validate_shared_imports_no_features`, `validate_scope_erasure_arity`, `validate_node_subtree_erasure`,
  `validate_blob_reference_arity`, `validate_store_reclaim_arity`, `validate_coverage`, `validate_doc_citations`,
  `validate_mutation_trigger_paths`, `validate_build_input_pins`, `validate_changes`, plus the two new guards.
- **Coverage.** No acceptance criterion changes (`satisfies: []`); every coverage ref to a moved test still
  resolves by class stem; `validate_coverage` green and its backend scan root covers every module.
- **Docs.** Step 6's list, each edited in place; the inbox row updated.
- **Retrospective** below filled in; the CR deleted after what it taught moved to where it binds.

## Retrospective

_(filled in at close)_
