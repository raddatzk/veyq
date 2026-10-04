---
id: cr-platform-gradle-modules
title: Platform phase 2 — the backend becomes Gradle modules (core, apps/memories), packages renamed to their owner, the API and contracts split by owner, the Flyway history reset to a baseline
status: planned
satisfies: []
adrs: ["0079", "0001", "0057", "0064", "0078"]
invariants: [operator-blind E2EE everywhere, self-hostable by default, API-first]
---

> Provenance marks used below: **(measured: cmd)** a command run against this tree at refine; **(spike)** run in a
> throwaway project outside the repository (so not re-runnable from here — the step that leans on one re-measures it
> in-tree and says so); **(remembered)** read from a comment or a doc, not run; **(unmeasured)** named so nobody
> mistakes it for a finding. Substrate of every measurement: branch `worktree-agent-a107be7bac47c2568` at `6bb906cf`
> (= `main` tip, tree clean; the only difference to the first refine's substrate `67f1173d` is this CR and inbox
> row #324 — `git diff --stat 67f1173d 6bb906cf`). Re-refined 2026-10-04 after the owner's decisions (§ Decided).

## Open for the owner

What is still open after your seven decisions; each carries a recommendation. None blocks the first step; the ones
marked **before step N** block that step.

1. **The shape of the baseline scripts — before step 1.** The history (67 files) folds to the end state: 35 tables,
   30 core + 5 Memories (measured, § State). Options: (a) **two hand-folded files, one per module**, schema only,
   same constraint/index names as today; (b) one file per table; (c) a `SHOW CREATE TABLE` dump of a migrated
   MariaDB, normalised. **Recommendation: (a).** (b) is 35 files for no ordering benefit; (c) needs Docker to
   *author* and yields MariaDB-only syntax that must then be made to parse on H2 `MODE=MariaDB`. The safety net is
   not the authoring method but the **schema-equality proof** (step 1): old history and new baseline replayed on
   H2, the `INFORMATION_SCHEMA` fingerprints diffed — feasible today without Docker (measured: the 67 files
   replay in one `RunScript` and yield 35 tables / 281 column rows / 34 foreign keys) — plus the same on MariaDB
   **[LIVE]**. Names: `V<yyyymmddHHMM>__<module>_<slug>.sql`, e.g. `V202610041200__core_baseline.sql` and
   `V202610041201__memories_baseline.sql` — 12 digits (Flyway compares version parts as numbers; unmeasured on
   MariaDB's `flyway_schema_history.version` VARCHAR, measured in step 1), the module in the name so a human and a
   guard see the owner without opening the path.
2. **Pin from the baseline, but when is the baseline "released"?** You decided the freeze guard pins from the new
   baseline onward. A baseline written today will want a typo fixed tomorrow, before any image is released.
   **Recommendation:** pin at once (the guard compares to `scripts/applied_migrations.sha256`); until the first
   released image a baseline edit is allowed *only together with* a visible edit of its pin line (the guard cannot
   know what a release is); from the first release the `backend/CLAUDE.md` rule "an applied migration is never
   edited" is absolute again. The release moment is the owner's, not derivable.
3. **Group id and artifact names — before step 4.** Without a group, `quarkus-app/lib/main` carries `core-unspecified.jar`,
   a name that can collide with a third-party `core` (unmeasured; the spike's jars were per module but unnamed).
   **Recommendation:** `group = "com.veyq"`, project paths `:core` and `:apps:memories` (what the commands type),
   `base.archivesName` `veyq-core` / `veyq-memories`; the root stays `veyq-backend`. Version: whatever the root
   already stamps; no per-module versions.
4. **Where do blobs, uploads and quota live? — before step 4.** ADR 0079 calls blobs and the storage quota platform
   code. Measured, they are not: `MediaHandler` (709 lines) serves the three generic blob endpoints
   (`startBlobUploads`, `presignBlobUploadWindows`, `presignBlobDownloads`) next to the media ones; `ChunkFrames`,
   `StorageQuota` and `UploadSessionPurge` sit in `features.media`; `TestBlob`, the helper every upload test uses,
   too. Consequence: **no core-alone test can upload a blob**, so 21 tests of core features (all the share upload
   and guest-write tests, tenant storage) can only run with Memories present. **Recommendation: accept for this CR**
   (they live in `:apps:memories`, where the runtime is a superset of core's), keep the three blob operations in the
   Memories media spec, and make "blob upload/download/quota become `com.veyq.core.blob`" the **next** CR — it moves
   ~300 lines of `MediaHandler` plus a spec split and is not mechanical. If you would rather have it first, it
   slots in before step 3 and the 21 tests stay in core; say so.
5. **`MEMORY`/`CHAPTER` inside core code.** You named "the memory/chapter parts of content" as Memories. Measured, they
   are two things: the node-placement rule in `NodesHandler` (a CHAPTER lives only under a MEMORY, a few lines) and
   the string constants in `NodeEntity`; the contract's `NodeKind` enum is closed and core's. Moving the rule is a new
   core seam (a `NodeKindRules` interface, one Memories implementation, collected like the erasers) and ties to the
   open "node `kind` open to values core does not interpret" row. **Recommendation: not in this CR**; they stay in
   core, the module guard does not see them, and ADR 0079 Consequences keeps naming them as the remaining core →
   Memories knowledge.
6. **The `shared/` → `features/` layering dies in the flat rename.** `shared.X` and `features.X` both become
   `com.veyq.core.X`, and the directory name was the guard's only handle on "kernel never imports a feature slice".
   **Recommendation: keep the rule, re-express it as a declared list** — the 15 former `shared/` packages are the
   *kernel*; `validate_shared_imports_no_features.py` (renamed) reads that list from one constant, a new package
   defaults to "slice". Alternative: keep `com.veyq.core.shared.<x>` as a sub-tree (no list, but a name that says
   nothing the module does not). The list is a layer declaration, not a table that rots; step 2 bites it.
7. **Contract layout — before step 3.** **Recommendation:** Memories-owned specs go to `contracts/api/memories/`
   (`media.openapi.yaml` moved, `favorites.openapi.yaml` new); core specs and `_common.openapi.yaml` stay where they
   are. A full `core/` + `memories/` tree would move ten files, rewrite two `package.json` scripts and ~13 guards
   for no extra ownership signal. The cost of the asymmetric layout is guards that glob `contracts/api/*.openapi.yaml`
   *non-recursively* and silently stop seeing the Memories specs (R3) — step 3 adds one helper and one bite.
8. **Web clients: "must not change" has a measured limit.** `web/package.json` generates **one directory per spec file**
   (`generate:api`, nine `openapi-generator-cli` calls). Splitting `content.openapi.yaml` therefore changes
   `src/api/generated/content/` by construction (it loses the favorites files, which appear byte-identical in a new
   `favorites/` directory) and three web files change their import (`api/client.ts` and two tests, measured). And
   consolidating the three real duplicate schemas changes JSDoc text in some directories, because their copies'
   descriptions differ (measured: distinct-with-description 2→1, 2→1, 3→2; § State). **Recommendation:** accept that
   and prove the precise statement — every generated file is byte-identical modulo `/** … */` and the two barrel
   index files, and the favorites files move whole (step 3). The literal-zero alternative is to leave the favorites
   operations in `content.openapi.yaml`, which contradicts decision 3.
9. **Docs that narrate the migration history (`V11__media.sql`, "V9 and V21 rebuild…").** Measured: ~45 files cite
   a migration by version (locator below), several as the *reason* for a rule. **Recommendation: delete the
   version-specific lore, keep the trap** (rule 5 of `docs/ai/README.md`): cite the table and the baseline file
   (`core baseline, veyq_node`), keep rules the history taught (`MODIFY COLUMN` parity, the `TEXT` ceiling), drop
   diary lines ("V9/V21 rebuild through a fresh table"). Your call whether any of it is worth keeping as prose.
10. **Where the durable record goes.** **Recommendation:** edit ADR 0079 in place (Consequences list the module split
    as not done; Decision 10 says "applications as packages of it" → modules) plus `backend/CLAUDE.md` and the
    developer styleguide (migration versioning, module layout); a new ADR only for the migration-version rule if you
    want it citable on its own.
11. **Test homes (decision 5, one proposal).** Mixed tests that need both sides go to `:apps:memories` — its runtime is
    core plus Memories, so the test sees the real thing; only genuinely whole-app tests stay in the root (one:
    `OperatorBlindProbeTest`, plus one new composition test). The exact-set pins of the `@All` collections
    (`ScopeEraserOrderSliceTest`, `NodeSubtreeEraserOrderSliceTest`) go to `:apps:memories` too, and core gets its own
    core-alone pin. When a second application arrives, the root's composition test is the one that changes
    deliberately. Table in step 6.

**Unknowns I could not measure** (each is a step's first task or a **[LIVE]** run, not a decision): the wall time of
`./gradlew check` after the split (R11 — one measured fact below makes this the largest open number); `quarkusDev`
hot reload across workspace modules and the IntelliJ `Run Backend` configuration (R8); whether Dependabot's
`gradle` entry with `directory: "/backend"` follows `include`d subprojects (R13, remembered: it does, unproven —
the repository has no remote); whether test-fixture beans in a `java-test-fixtures` jar are discovered by a
dependent module's `@QuarkusTest` (R14); that the jaxrs-spec generator honours `schemaMappings` the way the Kotlin
model generator measurably does; that the TypeScript generator resolves a `../_common.openapi.yaml` parent-relative
`$ref` (step 3 runs it); the `javap`-normalised bytecode diff proving the rename (step 2 runs it; feasibility
reasoned, not run); every **[LIVE]** proof.

## Decided by the owner (2026-10-04)

| # | Decision | What it replaced in the first refine |
|---|---|---|
| 0 | Do the whole split now, not only the guard prelude | "two committable halves, stop after step 1 if you want to wait" |
| 1 | Stay in `backend/`; Gradle subprojects below it (`backend/core`, `backend/apps/memories`); the root `backend/` stays the composition/Quarkus app, so Dockerfile, CI and run configs keep their paths | recommendation (a), now decided |
| 2 | Rename packages **with** the move: platform → `com.veyq.core.<feature>` (today `com.veyq.app.core.*`, `shared.*` and the platform `features.*`), Memories → `com.veyq.memories.<feature>`, a future `com.veyq.inventory…`; composition stays `com.veyq.app` | "no rename, later" |
| 3 | The generated API and `contracts/` split by owner; the multiply-defined schemas move to core and Memories maps them; `content.openapi.yaml` splits node (core) vs the Memories parts; no `:api-generated` module — each module generates its own types; `TrimmingStringDeserializer` stays in core (no cycle, § Target shape); wire format and paths unchanged | "one `:api-generated` module for all ten specs" |
| 4 | Flyway reset to fresh baseline scripts (no production environment exists), split per module (core first, Memories referencing core, never the reverse), **one global monotone version scheme** across modules (timestamp-style), the same version in two modules fails loudly, the freeze guard pins from the baseline onward | "all 67 files stay in `:core`, byte-identical, upgrade rehearsal" |
| 5 | Tests separated cleanly: each module has its own `@QuarkusTest` integration tests, running with core on the classpath; only genuinely whole-app tests stay in the root | "every `@QuarkusTest` stays in the root" |
| 6 | Follows from 3 | — |

Slicing order (yours, kept): MembershipEraser edge → Flyway baseline reset → package rename → contracts split →
`:core` extraction → `:apps:memories` extraction → tests/guards/docs. Every step leaves `./gradlew check`, the web
`npm run check` where contracts or clients are touched, `for f in scripts/validate_*.py` (python3 is 3.9) and
`validate_guard_gaps` green.

## Goal

Phase 2 of ADR 0079: **the backend's boundary becomes a build boundary and a naming boundary, one image, no
behaviour change.** After it:

- `:core` compiles with no Memories on its classpath (Gradle refuses a cycle); `:apps:memories` depends on `:core`;
  no application depends on another (a guard, because Gradle does not). The root `backend/` composes them into the one
  `quarkus-app`.
- Every class of the platform lives in `com.veyq.core.<feature>`, every Memories class in
  `com.veyq.memories.<feature>`, and the generated API follows (`com.veyq.core.generated.*`,
  `com.veyq.memories.generated.*`). A package spanning two modules is a guard failure.
- The contracts name their owner (core specs, `contracts/api/memories/`), each module generates its own types, every
  schema has one definition, and **paths, schemas, status codes and the wire are unchanged** (a mechanical
  canonical-JSON diff proves it, step 3).
- The database starts from one baseline per module under one global version scheme; the migration guard pins it.
- Each module's `@QuarkusTest`s run in that module, with core included; the core-alone boot test proves core needs no
  application.
- The container image, `infra/` and `charts/` are unchanged (`git diff main --stat -- infra charts` empty).

Not in this CR: the platform blob/upload/quota code that sits in Memories packages (open question 4), the
`NodeKindRules` seam (5), the node `kind` beyond `FOLDER`/`MEMORY`/`CHAPTER`, the Memories-flavoured columns on
`veyq_node` (`media_revision`, `delete_revision`, `cover_blob_id`, `cover_byte_size` — measured; they stay in the core
baseline because moving a column is a schema change, not a rename), the `veyq.media.*` config keys, `rootKind` on
`PublicShare` (#329), node URLs (#328), the `wiki/` update, a second application.

## State (what exists, measured)

| Fact | How measured |
|---|---|
| One Gradle project, no `include`; Gradle 9.5.1, Kotlin 2.3.21, Quarkus 3.36.0; PIT applied at the root | `cd backend && ./gradlew --offline projects`; `backend/build.gradle.kts`, `backend/settings.gradle.kts` |
| 196 main + 169 test Kotlin files (46 without `@QuarkusTest`, 123 with); 67 migrations `V1`–`V67`; `application{,-dev,-e2e,-prod,-test}.properties` in `src/main/resources` | `find backend/src/main -name '*.kt' \| wc -l` (and `src/test`); `grep -rL '@QuarkusTest' backend/src/test/kotlin \| wc -l`; `ls backend/src/main/resources/db/migration \| wc -l` |
| **Every one of the 365 Kotlin files** carries a `com.veyq.app` import or package line: 1727 import lines, none a wildcard; 212 of them `com.veyq.app.generated.{api,model}` (25 api, 187 model) | `grep -rhE '^import com\.veyq\.app\.' backend/src/main/kotlin backend/src/test/kotlin \| wc -l`; `grep -rlE 'com\.veyq\.app' backend/src \| wc -l` |
| Package map (files): `core` 12 (`blob node quota scope sharing`), `shared` 60 (15 packages: `adminchannel appshell auth blobstore config crypto http identity instance membership persistence ratelimit scope tenant validation`), `features/{account 48, admin 22, content 16, media 15, platform 2, sharing 7, spaces 7, tasks 7}`; of `content`, 12 are favorites/item-favorites. Memories = `features.media` (15), `content.favorites` (5), `content.itemfavorites` (7) | a `find … -name '*.kt'` tally keyed on the first two path segments under `com/veyq/app` |
| **The rename map has no file collision**; exactly three packages merge — `core.scope` ← `core/scope` + `shared/scope`, `core.sharing` ← `core/sharing` + `features/sharing` (main and test) | `/…/scratchpad/map.py`-style script: map each file to its new package, collect `(tree, new package, file name)`; 0 duplicates. Class-level clashes are left to the compiler |
| **Platform → Memories edge in main: exactly one import** — `features/spaces/scope/ScopesStore.kt` → `ItemFavoritesEntity`, two sites. Memories → platform: wide, as designed. **No non-Memories file, main or test, imports a Memories-owned generated type** (the five Memories APIs and their models) | `grep -rnE 'import com\.veyq\.app\.features\.(media\|content\.(item)?favorites)' backend/src/main/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'`; a script over the generated type names of `media.openapi.yaml` + the favorites schemas, 0 hits |
| **Nothing keyed on a package name outside Kotlin imports:** no `quarkus.log.category`/`hibernate-orm.packages`/`index-dependency` in any `application*.properties`; all 24 entities carry an explicit `@Table(name=…)`, so table names do not follow the package; entity names (JPQL) are simple names; one `@ConfigMapping(prefix = "veyq")` and one `@ConfigProperty` (`quarkus.http.host`); no `@NamedQuery`, no `Class.forName`, no `::class.java.name` persisted anywhere; no `META-INF/services`. **One observable change:** 18 classes log through `Logger.getLogger(X::class.java)`, so the logger *category* is the FQN and changes with the rename — nothing in the repo (properties, `infra/`, `charts/`, docs) names one | `grep -nE 'log\|category\|packages\|index-dependency\|flyway' backend/src/main/resources/application*.properties`; `grep -rL '@Table' $(grep -rlE '^@Entity' backend/src/main/kotlin)` (empty); `grep -rnE '@ConfigProperty\|@ConfigMapping' backend/src/main/kotlin`; `grep -rnE 'com\.veyq' infra charts .github/workflows/ci.yml docs` |
| FQN strings that the rename must also reach: 24 `x-field-extra-annotation` entries in `contracts/api` naming `com.veyq.app.shared.validation.TrimmingStringDeserializer`; `pitest.scope.json` (10 globs); `.github/workflows/mutation-backend.yml` (28 path citations); 11 scripts (`validate_guard_gaps` 22, `validate_shared_imports_no_features` 5, …); `com.veyq.app…` mentions outside import/package lines (11 in main, ~20 in tests; mostly KDoc links, a few inline code); string-form `com.veyq.app.generated.api` in `OperatorBlindProbeTest` | `grep -rhoE 'com\.veyq\.app\.[A-Za-z0-9_.]+' contracts/api \| sort \| uniq -c`; `grep -cE 'com[./]veyq' scripts/*.py \| grep -v ':0'`; `grep -rnE 'com\.veyq\.app' backend/src \| grep -vE '^\S+:[0-9]+:(import\|package) '` |
| ArC `@All` / `Instance<>` consumers collecting Memories implementations: `ScopePurge`, `NodeSubtreePurge`, `SharingStore`, `SharingHandler` | `grep -rnE '@All\|Instance<' backend/src/main/kotlin` |
| **Only three `internal` members**, all platform code with tests in the same package (`SessionPurge`'s companion, `TraefikFileIngressSink.render`, `TenantIngressProjector.isRoutableHostname`) | `grep -rnE '^\s*internal ' backend/src/main/kotlin` |
| **Generation:** one `openApiGenerateSpecs` loop over all ten specs (`_common` excluded) emits models and JAX-RS interfaces into **one** package pair `com.veyq.app.generated.{api,model}`; a schema defined in several specs is generated once per spec into the same file, **last spec in sort order wins** (`tenants` after `account`) | `backend/gradle/openapi.gradle.kts`; `backend/build/generated/openapi-models/…/MasterKeyWrap.kt` carries the annotated variant |
| **The seven multiply-defined schemas, classified.** Four are already aliases of `_common` and identical (`ErrorResponse` ×11, `StaleSealedMeta` ×3, `LocalizedMarkdown` ×3, `TenantStatus` ×3). Three are real copies: `PresignedUrl` (`media` + `sharing`) and `NodeKind` (`content` + `sharing`) differ only in `description:`; **`MasterKeyWrap` (`account`, `setup`, `tenants`) differs in validation** — the `account` copy has no `x-field-extra-annotation` (`@NotBlank`/`@Size`/`@Pattern` on `wrappedKey`, `@Size` on `id`, `kdfParams`), the other two have it, and generation order alone decides which one the backend gets (today: the annotated one) | a PyYAML walk over `components.schemas` resolving the `_common` alias, then a diff with `description`/`example` stripped (`/…/scratchpad/dups.py`, `mkw.py`) |
| Which tags each spec carries: `content` = `content` (6 ops, core) + `item-favorites`, `own-item-favorites`, `favorites` (5 ops, Memories); `media` = `media` + `buckets` (17 ops) — all Memories-hosted, **including the three generic blob operations**; `sharing` carries two `/media` operations served by core's `SharingHandler` through `ShareGuestWrites`; `x-veyq-channel: admin` appears only in `setup` (2), `instance` (3), `tenants` (11) — all core | a PyYAML walk of tags per operation; `grep -c x-veyq-channel contracts/api/*.yaml` |
| Web generates **one directory per spec file** into the gitignored `web/src/api/generated/` (nine calls in `web/package.json` `generate:api`; `admin-web` four); `api/generated/content` is imported 41 times in `web/src`, the favorites symbols only by `web/src/api/client.ts` and two tests | `grep -n generate:api web/package.json admin-web/package.json`; `grep -rlE '\b(FavoritesApi\|ItemFavoritesApi\|OwnItemFavoritesApi\|PutFavoritesRequest\|…)\b' web/src admin-web/src` |
| **Migrations: the end state is 35 tables, 5 of them Memories** (`veyq_media`, `veyq_media_derivative`, `veyq_bucket`, `veyq_scope_favorites`, `veyq_scope_item_favorites`). No core table references a Memories table (0 FKs); Memories tables reference only `veyq_blob` (2) and `veyq_scope` (3) — **the direction is already right**. 15 of the 67 files touch a Memories table: 6 only (`V12 V14 V15 V16 V18 V34`), 9 also a core one (`V11 V13 V32 V33 V35 V41 V49 V55 V66`). Six files carry data statements (`INSERT`/`UPDATE`: `V9 V21 V33 V51 V63 V67`) — rebuilds and backfills for rows that exist only in a pre-existing instance; a baseline holds no rows. Memories-flavoured columns on the core table `veyq_node`: `cover_blob_id`, `cover_byte_size` (V19), `media_revision`, `delete_revision` (V33). `veyq_media.share_identity_id` (V32) is a plain column, no FK | `/…/scratchpad/refs.py` (statement-level `REFERENCES` scan, comments stripped); `grep -liE '^\s*(INSERT\|UPDATE\|DELETE)\b' backend/src/main/resources/db/migration/*.sql`; `fp.py` below |
| **The history replays in one H2 run**: concatenating `V1`…`V67` in numeric order and `RunScript` on `jdbc:h2:file:…;MODE=MariaDB` succeeds (rc 0) and `INFORMATION_SCHEMA` answers 35 tables, 281 column rows, 71 constraint rows, 34 foreign keys. (The `INDEX_COLUMNS` view has no `NON_UNIQUE` in H2 2.4 — the proof script must read `INDEXES`; not yet written.) This is the no-Docker half of the baseline equality proof | `/…/scratchpad/fp.py backend/src/main/resources/db/migration <dir>` |
| Where migration versions are read as data: `validate_blob_reference_arity` (hard-codes `V11`, `V13`, `V19`, `V53` file:column pairs), `validate_scope_erasure_arity` (replays `REFERENCES` in numeric order; V9/V21 rebuild logic), `validate_maxlength_storage` (`V*.sql`), `validate_guard_gaps` (`V99` forgery), `run_web_e2e.py` (a comment: "highest `V<n>`") and `instance_backup.py` (`schema_version()` returns the string only — nothing parses it as an integer) | `grep -nE 'MIGRATIONS\|db/migration\|\bV[0-9]+' scripts/*.py` |
| **No upgrade rehearsal exists in CI or in a script** (no job boots an old jar against a persistent database); the "Flyway upgrade" proof lives only in the first refine of this CR, which is removed here | `grep -rniE 'upgrade\|rehears' .github/workflows scripts/*.py` (only the backup-restore rehearsal and `helm upgrade`) |
| Docs citing a migration by version: ~45 files (ADRs 0004 0034 0047 0054 0056 0064 0067 0068 0072 0073 0074, `docs/apps/{memories,files}.md`, enablers, use-cases, 6 coverage yamls, `backend/CLAUDE.md`, the styleguide, KDoc in `media/Entities.kt`, `MediaStore`, `NodesStore`, `core/blob/Entities.kt`, `ScopesSliceTest`) — noisy: the pattern also hits unrelated `V1`s | `grep -rnoE '\bV[0-9]{1,2}(__[a-z_0-9]+(\.sql)?)?\b' docs wiki CLAUDE.md backend/CLAUDE.md infra charts scripts backend/src .github` |
| Test side: 123 of 169 test files are `@QuarkusTest`; **30 carry a `@TestProfile`** (25 distinct profile classes → about 26 Quarkus boots in today's single JVM: one default plus one per profile); `test-port=0`, so test JVMs of different modules can run in parallel; `org.gradle.parallel` is not set | `grep -rlE '@TestProfile' backend/src/test/kotlin \| wc -l`; `grep -n test-port backend/src/main/resources/application-test.properties`; `backend/gradle.properties` |
| **24 platform-package tests import Memories** (+2 helper fakes that only mention it in KDoc); of those, 7 use only the `TestBlob` helper, 17 also touch `MediaEntity`/`BucketEntity`/`MediaStore`/`ItemFavorites*`/another Memories test class. `TestBlob` and `ChunkFrames` are the Memories-hosted upload plumbing (open question 4) | `grep -rlE 'features\.(media\|content\.(item)?favorites)' backend/src/test/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'`; per-file imported-symbol list |
| Three CDI beans live in test sources (`RecordingBlobStore`, `RefusingStoreReclaimStore`, `FakePresigningBlobStore`, in `shared/blobstore`) — moved into a `java-test-fixtures` jar they are only discovered if that jar is indexed (R14) | `grep -rlE '@ApplicationScoped\|@Alternative\|@Singleton\|@Produces' backend/src/test/kotlin \| xargs grep -LE '^@QuarkusTest'` |
| **Cost of one `:test` invocation, measured**: `./gradlew --offline test --tests '*ClientMintedIdSliceTest'` (10 tests, `@QuarkusTest`) — 1m47–1m50 with every compile task UP-TO-DATE, of which the class itself is 12.0 s (the report's `time`); `--tests '*TokenBucketTest'` (a pure unit test, no Quarkus boot) — 1m33. So **~90 s per `test` task invocation is neither the Quarkus boot nor compilation**; whether it is fixed per task (then three modules pay it three times) or scales with the 169-class discovery is **not decomposed** (R11). A cold first run in a fresh worktree cost 2m58 | `time ./gradlew --offline test --tests '…'`; `build/test-results/test/*.xml`; `--profile` report |
| No native build, no reflection config, no `beans.xml`/`jandex`; the Dockerfile's `backend-build-base` stage copies named files only (`gradlew`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `pitest.scope.json`, `gradle/`) and warms with `./gradlew --no-daemon help`; `COPY backend/src ./src`; `.run/*.xml` and CI use `working-directory: backend` and root tasks only | `Dockerfile` lines 46–65; `grep -nE 'gradlew\|working-directory' .github/workflows/*.yml` |
| Dependency verification is one file at the build root; Dependabot's `gradle` entry is `directory: "/backend"` | `backend/CLAUDE.md`; `.github/dependabot.yml` |

### What the spikes measured

| Question | Result |
|---|---|
| (spike, first refine) Bean in a workspace module with **no** `META-INF/beans.xml` | not discovered: `UnsatisfiedResolutionException` at build; an `@All` list would instead silently *shrink* |
| Same, **with** an empty `beans.xml` | `@Entity`, `@ApplicationScoped`, `@All` implementations and `@ConfigMapping` found across modules; two `ScopeEraser`-shaped beans from two jars both collected |
| A `@Path` interface in a module without `beans.xml`, resource in an indexed module | the resource **answers 404**, no error anywhere; with `beans.xml` on the interface's module: 200 — so *each module that generates interfaces needs it* |
| `V1` in module A and `V2` in module B at the same `db/migration/` path | both applied in order, in the test JVM and from the packaged fast-jar |
| The same version in two modules | boot failure `Found more than one migration with version 2`, naming both jars |
| `@QuarkusTest` in a library module | **fails without the `io.quarkus` plugin**; with it, boots that module alone with only its own beans; `check --dry-run` contains no `quarkusBuild` |
| A project cycle `:core` ↔ `:memories` | `Circular dependency between the following tasks` |
| `./gradlew test --tests '*X'` across projects | fails `No tests found for given includes` in every project without a match |
| Packaged fast-jar of three modules | `quarkus-app/lib/main` holds one jar per module; boots, migrates, serves |
| **(measured here, 2026-10-04) the generator can map a shared schema to another module's class.** Kotlin model generator, openapi-generator 7.23.0, a Memories-shaped spec whose `ErrorResponse`/`NodeKind` are `$ref` aliases of another file: `addSchemaMapping("ErrorResponse", "com.veyq.core.generated.model.ErrorResponse")` generates **no** `ErrorResponse.kt`/`NodeKind.kt` in the Memories output and `Thing.kt` types its fields with the core FQN. `addImportMapping` alone is the wrong lever: it still generates the models, as junk files *named* `com.veyq.core.generated.model.ErrorResponse.kt`. Same-spec control (no mapping) generates both. The `jaxrs-spec` API run (which imports models by name) was **not** run with the mapping | a scratch Gradle project outside the repo using the repo's wrapper and the repo's generator version (`-p …/genspike g0 g1 g2`) |
| (measured here) the web generator is per spec file and ignores `x-field-extra-annotation` | `web/package.json` `generate:api`; the extension is a backend-annotation hint |

## Target shape

| Module (dir) | Contains | Depends on |
|---|---|---|
| `:core` (`backend/core`) | `com.veyq.core.*` (former `app.core.*`, `shared.*`, the platform `features.*`, the node files of `features.content`); generation of the core specs into `com.veyq.core.generated.{api,model}` plus `AdminChannelOperations`; `TrimmingStringDeserializer` (as `com.veyq.core.validation…`); the core baseline migration; `beans.xml`; `java-test-fixtures` (the helpers both sides use); core's tests; PIT plugin + `pitest.scope.json` | the Quarkus BOM and extensions |
| `:apps:memories` (`backend/apps/memories`) | `com.veyq.memories.{media,favorites,itemfavorites}`; generation of `contracts/api/memories/*` into `com.veyq.memories.generated.{api,model}` with the shared schemas mapped to core's; the Memories baseline migration; `beans.xml`; Memories tests and the mixed tests (step 6) | `:core` (+ its test fixtures for tests) |
| root `backend/` (`com.veyq.app`) | `io.quarkus` composition, `src/main/resources` (`application*.properties`, the web `dist` target), `quarkusBuild`/`quarkusDev`, the wrapper, `gradle/verification-metadata.xml`, the whole-app tests (`src/test`: `OperatorBlindProbeTest`, one composition test) | `:core`, `:apps:memories` |

No `:api-generated` module (decision 3): each module compiles what it generates, so the cycle the first refine feared
(`:api-generated` → core's `TrimmingStringDeserializer` → `:api-generated`) does not exist — core's generated models name
a class in core itself, Memories' name it across a legal edge.

**How each rule is enforced.** *Core never imports Memories*: `:core` has no `project(":apps:…")` dependency, so the
import does not compile; adding it is a project cycle (spike). *No application imports another*: Gradle allows
`:apps:a` → `:apps:b`; a buildless guard parses the build files (step 6; its bite needs a forged second application).
*No package spans two modules*: Kotlin allows it and Quarkus indexes both; a guard derives it from `package` lines.
*Every module is indexed*: a guard on `beans.xml`. *No two migrations share a version, a core migration names no Memories
table, a Memories migration references only core tables or earlier Memories ones*: `validate_migration_versions.py`
(step 1, finished in step 6). *Inside core*, the kernel never imports a slice: the surviving half of the old guard, over
a declared kernel list (open question 6). *A Memories spec never carries `x-veyq-channel`* and *no Memories type has the
name of a core one*: the generator build and a guard (step 3).

**Quarkus specifics, decided.** Indexing: an empty `META-INF/beans.xml` per module (no extra plugin — the Jandex Gradle
plugin would be one more third party to keep alive; `quarkus.index-dependency.*` in the root's properties is the
alternative, rejected because the list lives away from the module and a forgotten entry is silent). Config:
`application*.properties` stay in the root only (a library `application.properties` is a second same-ordinal source,
remembered); a module's `@QuarkusTest`s get the `%test` keys from **one** file — `application-test.properties` moves to
`:core/src/test/resources` and is shared to dependants through the test-fixtures jar's resources (decided in step 6,
R6). Library modules apply the `io.quarkus` plugin only where a `@QuarkusTest` runs (both, now); the root applies it for
the build. Config mapping: `VeyqConfig` stays whole in `:core`, including its `media()` section
(`veyq.media.upload.retention` is read by core code); keys are untouched by any rename (the mapping is bound by prefix
`veyq`, not by package — measured above).

## Inventory — the locators (run them; the tally is not part of the CR)

| What | Command |
|---|---|
| Module members, by package | `ls backend/src/main/kotlin/com/veyq/app/{core,shared,features/*}` |
| Platform → Memories imports (main), angle 1 | `grep -rnE 'import com\.veyq\.app\.features\.(media\|content\.(item)?favorites)' backend/src/main/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'` |
| … angle 2 (names, comments excluded) | `grep -rnE 'MediaEntity\|MediaDerivativeEntity\|BucketEntity\|FavoritesEntity\|veyq_media\|veyq_bucket\|veyq_scope_favorites\|veyq_scope_item_favorites\|MediaStore\|MediaHandler' <platform packages>`; drop `^\s*(\*\|//\|/\*)` lines |
| Memories → platform imports | `grep -rhoE 'import com\.veyq\.app\.(shared\|core)\.[A-Za-z.]+' backend/src/main/kotlin/com/veyq/app/features/{media,content/favorites,content/itemfavorites} \| sort \| uniq -c` |
| Non-Memories users of Memories-owned generated types | the `genuse.py`-style script: type names from `media.openapi.yaml` + the favorites schemas, grep imports outside the three packages |
| Every FQN a rename must reach | `grep -rnE 'com\.veyq\.app' backend contracts scripts .github docs wiki CLAUDE.md changes/README.md web/CLAUDE.md admin-web .claude \| grep -vE '^\S+:[0-9]+:(import\|package) '` |
| Scripts that name the backend tree or a package | `grep -lE 'backend/(src\|build)\|com[./]veyq\|backend/pitest' scripts/*.py`; second angle, the Python constants: `grep -nE 'BACKEND\|KOTLIN_MAIN\|MIGRATIONS\|TEST_ROOT\|SOURCE_ROOTS\|APP *=\|SHARED *=' scripts/*.py`; third, directory-name keyed: `grep -cE 'features[/"]\|shared[/"]\|"core"\|core/' scripts/*.py` |
| Scripts that read the specs (the non-recursive glob risk) | `grep -nE 'contracts.*api\|openapi\.yaml' scripts/*.py` (13 files) |
| Docs and config citing a moved path | `grep -rnE 'backend/src/(main\|test)' docs wiki CLAUDE.md backend/CLAUDE.md infra charts .github .claude`; second angle, bare package paths: `grep -rnE 'features/(account\|admin\|content\|media\|platform\|sharing\|spaces\|tasks)\|shared/(adminchannel\|appshell\|auth\|blobstore\|config\|crypto\|http\|identity\|instance\|membership\|persistence\|ratelimit\|scope\|tenant\|validation)' docs wiki CLAUDE.md backend/CLAUDE.md .claude` |
| `internal` members and their tests | `grep -rnE '^\s*internal ' backend/src/main/kotlin`; then `grep -rl` each symbol under `backend/src/test` |
| Tests that need both sides | `grep -rlE 'features\.(media\|content\.(item)?favorites)' backend/src/test/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'` |
| Test-source CDI beans | `grep -rlE '@ApplicationScoped\|@Alternative\|@Singleton\|@Produces' backend/src/test/kotlin` minus the `@QuarkusTest` files |
| Non-Quarkus tests (move with their code) | `grep -rLE '@QuarkusTest' backend/src/test/kotlin` |
| `@TestProfile` classes (boot count) | `grep -rhoE '@TestProfile\([A-Za-z_.]+::class\)' backend/src/test/kotlin \| sort \| uniq -c` |
| Duplicated schemas | a PyYAML walk of `components.schemas` per spec (see § State) |
| Web users of the generated content client | `grep -rnE "api/generated/content" web/src admin-web/src` |
| Migrations, by owner / tables | `grep -lE 'veyq_media\|veyq_bucket\|veyq_scope_(item_)?favorites' backend/src/main/resources/db/migration/*.sql`; the statement-level `refs.py` scan |
| Where docs/code cite a migration version | the `\bV[0-9]{1,2}…` grep of § State |
| CI/paths that name the backend | `grep -nE 'backend' .github/workflows/*.yml` (`mutation-backend.yml`: 14 source/test files + `build.gradle.kts` twice; `codeql.yml` filters `backend/**`) |
| What consumes the build output | `grep -rnE 'quarkus-app\|backend/build' Dockerfile scripts .github infra charts .run` |
| Flow surfaces naming the split | `grep -nE 'Gradle module\|phase 2\|#324' docs/inbox.md changes/*.md` |

## Steps

One commit per step (a step with a mechanical script commits the script's output separately from any hand edit), each
ending with the checks it names; a step that fails one is not done. The permanent checks of **every** step:
`cd backend && ./gradlew check`, `for f in scripts/validate_*.py; do python3 "$f"; done` (python3 is 3.9),
`python3 scripts/validate_guard_gaps.py`, and — from step 3 on, wherever a contract or generated client is touched —
`cd web && npm run check` and `cd admin-web && npm run check`. **A neighbouring CR editing backend files collides with
steps 2–5** (the rename touches every Kotlin file): check `ls changes/` and `git status` first; land from the
worktree by `--no-ff` merge, never by editing the main checkout; stage by explicit path. **[LIVE]** marks proofs that
need the live stack (Docker, MariaDB); the owner or CI runs those, the others run anywhere.

**Baselines recorded before the first edit** (a baseline is the command that produces it):
`ls scripts/validate_*.py | wc -l`; `find backend/src -name '*.kt' | wc -l`; `cd backend && time ./gradlew check` (the
number the module cost is judged against, R11); `python3 -c "import sys; sys.path.insert(0,'scripts'); import
run_backend_races_on_mariadb as r; print(r.race_classes())"`; the set of test names that ran
(`build/test-results/test/*.xml` → sorted `classname#name`, saved in the scratchpad: step 2 compares it after mapping);
the three web `generate:api` trees (`web`, `admin-web`: `find src/api/generated -type f | xargs shasum -a 256`).

### 0 — Cut the last platform → Memories edge, and make the guard see all of the platform

*Why first:* the only edge between today's tree and a core that compiles alone; behaviour-preserving, no build change.

- **Change.** A core interface `MembershipEraser` (`core.scope`, the ADR 0079 name) — "one user loses access to one
  scope; nothing is erased from the scope" — called from the two `ScopesStore` sites inside their existing
  transactions; `ItemFavorites…MembershipEraser` implements it in the item-favorites package. Collected like the other
  seams (`@All MutableList`, not `List` — the ArC trap in `backend/CLAUDE.md`). No phase table: one implementation; the
  doc says implementations must not take locks beyond the caller's scope-row lock.
- **Guard first (red → fix → green).** Extend `validate_shared_imports_no_features.py`: the Memories ban covers *every
  main Kotlin file whose package is not a Memories package* (the inverse derivation — no list of platform packages to
  rot, and exactly the cut Gradle will make). Run it on the untouched tree first: red on `ScopesStore` only.
- **Acceptance.** Angle 1 of the inventory returns nothing; member removal and removal-rotation still delete that
  member's item favorites for that scope and no other user's or scope's.
- **Proof.** `./gradlew check`; the validators; **[LIVE]** `python3 scripts/run_backend_races_on_mariadb.py` — this moves
  a delete that runs under the scope row lock inside `rotate` (ADR 0078); `RemovalRaceSliceTest` asserts
  `ItemFavoritesEntity.count(...) == 0` on it.
- **Bite-tests.** (1) *live defect:* the extended guard on the untouched tree → red on the one line. (2) *control:* a
  forged `features/media` file importing `core.*` stays green (one-way). (3) *injection:* a forged `features/spaces`
  file importing `features.media` → red (a new `validate_guard_gaps` case; cases 34 and 38 stay until step 6). (4)
  *behaviour:* delete the implementation's `@ApplicationScoped` in a scratch copy → `RemovalRaceSliceTest` and the
  non-race removal test go red, with a positive control in the same test (another scope's favorites survive).
- **Done when:** the above, and ADR 0079 Consequences' "`ScopesStore` still imports…" sentence is deleted.

### 1 — Flyway: reset to a baseline per module under one version scheme

*Why before the rename and the split:* it changes no Kotlin and no package, only what the database starts from; and
the per-module baselines are the first artefact that has an owner before the modules exist.

- **Change.** Delete `V1`…`V67`. Add two schema-only files in the one `db/migration/` directory:
  `V202610041200__core_baseline.sql` (the 30 core tables in dependency order, including `veyq_node`'s four
  Memories-flavoured columns) and `V202610041201__memories_baseline.sql` (the 5 Memories tables; foreign keys to
  `veyq_blob` and `veyq_scope` only). End-state DDL, **every constraint and index name unchanged** (guards and tests
  cite `fk_media_blob`, `ix_media_share_identity`, …), column order as the history leaves it, no `INSERT`/`UPDATE`
  (the six data-bearing files transformed rows a fresh instance does not have). Same single directory as today: no
  Flyway `locations` setting to forget when a third application arrives (spike: files from several jars resolve).
  `quarkus.flyway.*` untouched; `%dev` keeps `repair-at-start`.
- **The version scheme (global, monotone).** `V<yyyymmddHHMM>__<module>_<slug>.sql`; the allocation rule is "the time you
  write it, UTC, minute resolution"; a collision is a loud boot failure *and* a guard failure (spike). A module's
  migration may depend on any lower version; the guard checks table-level direction (below).
- **New guard `validate_migration_versions.py` + `scripts/applied_migrations.sha256`** (replaces the first refine's
  `validate_migrations_frozen`; reads every `db/migration` under `backend/`, found by glob via `scripts/backend_tree.py`
  once step 2 adds it — until then the one directory). Asserts: names match `V\d{12}__(core|memories)_\w+\.sql`; versions
  unique across roots; every pinned file exists with its pinned SHA-256; no new version below the highest pinned one
  (`outOfOrder=false` makes that a start failure on an existing instance); the name's module equals the owning module
  (from step 5 on, the root the file sits in); **a `core` migration names no table created by a `memories`
  migration, a `memories` migration's `REFERENCES` point at core tables or at Memories tables created at a lower
  version** (statement scan, comments stripped — `validate_scope_erasure_arity` already parses `REFERENCES` this way;
  its limit is the same: declarations, not a database). Docstring: asserts / the defect that bought it / limit.
- **Re-pointing the four readers.** `validate_blob_reference_arity` (its `V11`/`V13`/`V19`/`V53` file:column constants
  become baseline-file constants); `validate_scope_erasure_arity` (the V9/V21 rebuild handling is no longer needed —
  delete that branch rather than keep it; numeric ordering stays, the versions are now 12 digits);
  `validate_maxlength_storage` (glob unchanged); `validate_guard_gaps` (the `V99` banner forgery becomes a
  timestamp-style forged file); comments naming the old files in `run_web_e2e.py` and `instance_backup.py`.
- **Consequences, stated, not hidden.** Every existing database is incompatible: Flyway on an old schema refuses the
  new jar (`Detected applied migration not resolved locally`). That is **every dev database, the owner's included** —
  recreate it (`docker compose -f infra/docker-compose.yml down -v`, which also drops the Garage volume, so the dev
  store's objects go with the rows they belonged to) and **accounts, tenants and everything in them are lost**;
  `Bootstrap User`/`Bootstrap DB + Garage` recreate the seed. An instance backup taken before the reset cannot be
  restored onto a jar after it (the dump carries `flyway_schema_history`, `uc.admin.back-up-and-restore-instance`
  point 7). No production environment exists, so no data is at stake; the *procedure* an operator follows on a real
  upgrade is untouched. The H2 tests (`%test`, in-memory, `MODE=MariaDB`) and the MariaDB race suite both migrate
  from the baseline on every boot; neither keeps state. Docs and tests that mention the old history are listed in
  § State and rewritten here: `backend/CLAUDE.md` § Migrations and the styleguide §§ on migrations (version scheme,
  per-module, pin rule), KDoc and prose naming `Vnn` (open question 9), the removed upgrade rehearsal.
- **Proof that nothing but history changed (the schema-equality proof).** A throwaway script (scratchpad, recorded in the
  commit message, not committed — scaffolding): replay the old 67 files and the new two on H2 `MODE=MariaDB`
  (`org.h2.tools.RunScript`, as measured), dump `INFORMATION_SCHEMA` tables / columns (type, length, nullability,
  default) / constraints / referential constraints / **indexes (via `INDEXES`/`INDEX_COLUMNS`, columns by name)**, sort,
  diff → empty. **[LIVE]** the same against MariaDB (`information_schema.COLUMNS`, `STATISTICS`,
  `KEY_COLUMN_USAGE`, `REFERENTIAL_CONSTRAINTS` on the pinned image): H2 parity is a measure, not the engine (ADR 0078).
  `schema-management.strategy=none` means no Hibernate validation will catch a baseline typo — a wrong column surfaces
  only as a runtime SQL error, which is why the diff is the proof and the suite alone is not.
- **Proof, rest.** `./gradlew check` (every boot migrates the baseline); validators; **[LIVE]** the race suite on
  MariaDB and `python3 scripts/run_web_e2e.py` (a fresh stack: first boot on the baseline); a boot of the fast-jar
  against a fresh MariaDB reads `Successfully applied 2 migrations, now at version v202610041201`.
  An *upgrade* proof returns when the first release exists (open question 2).
- **Bite-tests.** (1) *the proof script bites:* drop one index from the new baseline in a scratch copy → the fingerprint
  diff is non-empty (lever: that index). (2) a one-character edit of the core baseline → `validate_migration_versions`
  red (lever: the pin). (3) `V202610041200__x.sql` in a second directory → red (duplicate). (4) a `core_`-named
  migration with `REFERENCES veyq_media` → red; the same in a `memories_`-named file referencing `veyq_blob` → green
  (the point). (5) a `memories_` migration whose version is *below* the core baseline → red. (6) a `V43__x.sql` → red
  (name shape).
- **Done when:** the above; `backend/CLAUDE.md` migration conventions rewritten (the pin rule and open question 2's
  release caveat included).

### 2 — Rename the packages (a script, a manifest, a proof)

*One purely mechanical commit, nothing else in it.* The module split follows in 4–5; here every file keeps its
production role and moves to its owner's package.

- **The map** (longest prefix wins; `com.veyq.app.` omitted on the left): `core.X` → `com.veyq.core.X`;
  `shared.X` → `com.veyq.core.X`; `features.{account,admin,platform,sharing,spaces,tasks}.X` → `com.veyq.core.<same>.X`;
  `features.content` (the four node files) → `com.veyq.core.content`; `features.media` → `com.veyq.memories.media`;
  `features.content.favorites` → `com.veyq.memories.favorites`; `features.content.itemfavorites` →
  `com.veyq.memories.itemfavorites`; `testing` → `com.veyq.core.testing`. **Left alone in this step:**
  `com.veyq.app.generated.*` (the generated packages cannot be assigned an owner before the contracts split; step 3).
  Merges (measured, no file clash): `core.scope` ← `core/scope` + `shared/scope`, `core.sharing` ← `core/sharing` +
  `features/sharing`. Tests follow their production package. The map is data in the script, with the three merges
  declared, not inferred.
- **The script** `scripts/rename_packages.py` (committed with the step, deleted at close — it is scaffolding): (1) `git mv`
  per file, creating directories as needed; (2) rewrites `package`, `import`, inline FQNs, KDoc `[…]` links and string
  forms by longest-prefix match on the manifest's old→new package pairs, in `backend/src`, `contracts/api`
  (`x-field-extra-annotation`: 24 occurrences of the `TrimmingStringDeserializer` FQN), `backend/pitest.scope.json`,
  `.github/workflows/mutation-backend.yml`, `scripts/*.py` constants and docs citations; (3) a `--check` mode that
  re-derives the new content of every file from `git show <pre-step hash>:<path>` and compares byte-for-byte, and
  fails on any residual `com.veyq.app.(core|shared|features|testing)` outside the declared exceptions. Directory moves
  and text rewrites are one invocation; hand edits (guards' directory logic, docs prose) are a **second** commit.
- **Helper first (in the same step, second commit).** `scripts/backend_tree.py` — `main_roots()`, `test_roots()`,
  `migration_roots()`, `package_dirs(pkg)` resolved by glob over `backend/**/src/{main,test}/kotlin`, never listed — and
  every guard that hard-codes `backend/src/main/kotlin/com/veyq/app` is rewired to it (the 11 FQN-naming scripts, the
  directory-name-keyed ones, `validate_coverage`'s `BACKEND_TESTS`, `chart_meta_check`'s two `…KT` paths,
  `mutation_meta_check*`). Each rewired guard keeps its "a run that finds none is red" line. `validate_shared_imports_no_features`
  becomes the kernel guard of open question 6: the declared kernel list is generated **once** from the former `shared/`
  directory names by the script and committed as a constant.
- **The proof that only names changed** (all recorded in the commit message):
  - **P1, text.** `rename_packages.py --check` green: every file equals the mapping of its pre-image; the diff of each
    `git diff -M` rename touches only `package`/`import`/FQN lines.
  - **P2, bytecode.** Compile before and after; `javap -p -c -constants` over every class; normalise package prefixes via
    the manifest; the two listings are equal. (Feasibility reasoned, **unmeasured** — the first thing the step runs; if
    Kotlin-generated names (`$WhenMappings`, lambdas) differ only by prefix the normaliser covers it, else the
    comparison falls back to class-count and member-signature equality and says so.)
  - **P3, tests.** The sorted set of `classname#testname` from `build/test-results` equals the pre-step set after
    mapping — no test lost, none added; `./gradlew check` green.
  - **P4, nothing else moved.** `git diff -M --stat` lists only renames and modified files; no file under `contracts/`
    changes except the 24 annotation lines (checked by a canonical-JSON compare of the specs with
    `x-field-extra-annotation` values mapped); `git diff --stat -- infra charts web admin-web` is empty;
    `npm run generate:api` yields byte-identical trees in `web` and `admin-web` (the extension is ignored by the TS
    generator — measured premise, proved by the diff).
  - **P5, runtime names.** The set of table names from the fast-jar's Hibernate metamodel (a one-off test or the
    baseline fingerprint of step 1 run through a boot) and the set of `veyq.*` config keys the mapping binds are
    unchanged; the one deliberate change — logger categories — is stated in the commit.
- **Acceptance.** `grep -rnE 'com\.veyq\.app\.(core|shared|features|testing)' .` finds nothing outside this CR, the
  rename script and the retired manifest; the angle-2 inventory of "scripts that name the backend tree" reads the helper.
- **Bite-tests.** (1) hand-edit one non-import line of a moved file in a scratch copy → `--check` red (lever: the
  byte comparison). (2) leave one `com.veyq.app.shared.` KDoc link → the residual scan red. (3) re-point a guard
  constant at the old directory → its "found none" line is red, not green. (4) a kernel-list package importing a
  slice package → the kernel guard red; the same import from a slice package → green. (5) delete a merge declaration
  → the compile fails on the clashing package (so the declaration is load-bearing).
- **Done when:** the above, and `docs` citing backend paths/packages are rewritten (second commit; locator: "Docs and
  config citing a moved path", both angles) with `validate_doc_citations` and `validate_mutation_trigger_paths` green.

### 3 — Split the contracts and the generated API by owner (one Gradle module still)

- **Change.**
  - `content.openapi.yaml` keeps the six node operations and the node schemas; the favorites, item-favorites and
    own-item-favorites tags (five operations, five schemas) move to the new `contracts/api/memories/favorites.openapi.yaml`;
    `media.openapi.yaml` moves to `contracts/api/memories/media.openapi.yaml` unchanged in content. The three generic blob
    operations stay with the media spec (open question 4).
  - `_common.openapi.yaml` gains the three schemas it does not yet hold — `PresignedUrl`, `NodeKind`, `MasterKeyWrap` —
    and every spec that defined them aliases the common one like `ErrorResponse` already does. **`MasterKeyWrap` takes the
    annotated variant** (the one the backend gets today); the un-annotated `account` copy was a latent defect (open
    question 8 states the description drift). Memories specs reference `../_common.openapi.yaml#…`.
  - Generation, still in the one module: `openapi.gradle.kts` reads two spec sets — core (`contracts/api/*.openapi.yaml`,
    not `_common`) into `com.veyq.core.generated.{api,model}` and Memories (`contracts/api/memories/*`) into
    `com.veyq.memories.generated.{api,model}`, the latter with `addSchemaMapping` for every schema `_common` defines
    (measured lever; the `jaxrs-spec` run is verified here). `AdminChannelOperations` is emitted from the core specs
    only into `com.veyq.core.generated`; **a Memories spec carrying `x-veyq-channel` fails the build** (a silent hole
    otherwise: the admin gate reads core's set).
  - A mechanical import rewrite keyed on the type's owner (script, same `--check` discipline as step 2): core types →
    `com.veyq.core.generated.*`, Memories types → `com.veyq.memories.generated.*`; the `OperatorBlindProbeTest`
    `API_PACKAGE` constant becomes both packages (the probe still derives the set from the generated interfaces).
  - Contract-reading guards (13 scripts) take the spec list from one helper `scripts/contract_specs.py` that globs
    **recursively** under `contracts/api`; each keeps its "found none" line (R3).
  - Web: `generate:api` (`web/package.json`) gains one call (`favorites.openapi.yaml` → `src/api/generated/favorites`) and
    drops nothing; `api/client.ts` and the two favorites tests import the favorites symbols from
    `./generated/favorites`. `admin-web` is unchanged (it generates four core specs). `validate_build_input_pins`
    and `verify_openapi_generator_jar` keep holding the generator version.
- **Proof — backend.** (i) *Generated sources:* the old `build/generated/openapi-{api,models}` tree, mapped through the
  owner table, equals the new two trees file by file (generated code carries no descriptions — measured —
  so this is exact, including `MasterKeyWrap`'s annotations). (ii) *Wire:* a script resolves every spec of the old and
  the new contract (following `$ref`s) and compares, per `(method, path)`: `operationId`, tags, parameters, request
  body, every response, with `description`/`example`/`summary` stripped → identical sets; the set of schemas reachable
  from operations is identical; this is the "paths and wire unchanged" statement as a command. (iii) `./gradlew check`
  (`OperatorBlindProbeTest` and `AdminChannelPostureSweepTest` derive their sets from the generated interfaces, so an
  unregistered resource shows as 404s).
- **Proof — web.** `npm run generate:api` before and after, then: every pre-existing generated file is byte-identical
  in the new tree **modulo `/** … */` blocks**, except the two barrel `index.ts` export lists; the favorites files
  moved whole from `content/` to `favorites/`; the only JSDoc differences are in `NodeKind`, `PresignedUrl`,
  `MasterKeyWrap` files (the consolidated descriptions). `npm run check` in `web` and `admin-web`.
- **Bite-tests.** (1) change a `required` list in the scratch Memories spec → the wire diff red (lever). (2) drop the
  `addSchemaMapping` in a scratch copy → a guard comparing the model names of the two generated trees red (a Memories
  type named like a core one is a duplicated definition). (3) put `x-veyq-channel: admin` on a Memories operation →
  the build fails. (4) move a spec into `contracts/api/memories/` without the recursive helper → the guard that reads
  it reports "found none" or the changed set, not green. (5) edit the favorites spec in scratch → the web diff
  non-empty.
- **Done when:** the above; `backend/CLAUDE.md` § Conventions names the two generation task paths; `contracts/` README or
  `_common` header states the layout and that a Memories spec may reference core's `_common`, never the reverse.

### 4 — Gradle skeleton and `:core`

*The big move; one commit for the directory moves, a second for build files.*

- **Change.** `settings.gradle.kts` includes `:core`; `backend/gradle/module.gradle.kts` carries what every module repeats
  (toolchain 21, Kotlin + all-open for `@Entity`/`@MappedSuperclass`/`@ApplicationScoped`, BOM, `javaParameters`,
  `useJUnitPlatform`, `maxHeapSize = "2g"` and the logging manager property from the root's test config). Plugin
  versions stay declared once at the root. `git mv` all `com.veyq.core.*` main code, the core specs' generation
  (`openapi.gradle.kts` parametrised: spec set, packages, schema mappings), `TrimmingStringDeserializer`, the core
  baseline migration, the Quarkus-free unit tests of that code (**including the three tests of `internal` members**,
  which stop compiling across a module boundary, and the PIT scope's policy tests) into `backend/core`. `:core` gets
  `beans.xml`, the `io.quarkus` plugin (required for a library `@QuarkusTest`), `java-test-fixtures`, the PIT plugin and
  `pitest.scope.json` (still readable by `scripts/`). The Memories packages, their generation and **every
  `@QuarkusTest` stay in the root** for now; the root depends on `:core`; the direction is already right. The
  Dockerfile's `backend-build-base` stage copies `backend/core/build.gradle.kts` (the warm-up configures every
  included project; a missing directory is an error — remembered, measured when hit) and the second `COPY` adds
  `backend/core/src`. The Memories baseline migration stays in the root (one directory, one classpath path) until step 5.
- **Acceptance.** `git diff -M --stat` shows renames only for `.kt`/`.sql`/specs; no Kotlin *content* change beyond what
  the build forces (the module block, nothing in sources). `race_classes()` unchanged. Every path in
  `mutation-backend.yml`, `pitest.scope.json`, the mutation scripts, `validate_guard_gaps` forgeries and doc citations
  resolves. `scripts/run_backend_races_on_mariadb.py` addresses the root project explicitly (a bare `test --tests X`
  fails in every project without a match — spike).
- **Proof.** `./gradlew check`; `./gradlew pitest` (the score is the same; the report path moves to
  `backend/core/build/reports/pitest`, read by `mutation_meta_check_backend.py` and its selftest); the cold-home
  verification run `GRADLE_USER_HOME=$(mktemp -d) ./gradlew help check quarkusBuild pitest` **without** the write flag
  is green (the dependency set did not grow; any entry it asks for is a finding — read the diff, `backend/CLAUDE.md`);
  `quarkusBuild` and the fast-jar lists the core jar; the Dockerfile warm-up without Docker: copy exactly the files its
  `COPY` lines name into a temp dir and run `./gradlew --offline help`. **[LIVE]** `docker build .`; the race suite
  (the transactional code now sits in a jar — an interceptor binding that silently stops applying because the module
  is unindexed is exactly what H2 cannot show, ADR 0078); a fresh-database boot of the fast-jar
  (`Successfully applied 2 migrations`). Record `time ./gradlew check` (R11).
- **Bite-tests.** (1) delete `core/…/beans.xml` → the root's slice tests red at build (unsatisfied dependency), and the
  quieter case — an `@All` list that shrinks — is caught by the pinned-set tests. (2) a one-character edit of the core
  baseline → `validate_migration_versions` red. (3) a `@Transactional` method in an unindexed module — the one case
  that needs the live race run; the commit says so rather than claim H2 shows it. (4) re-point a script constant at
  the old path → "found none" red. (5) an `internal` test left in the root → compile error naming it.
- **Done when:** the above; `backend/CLAUDE.md` § Prove your work lists the `:core:test` / `:test` split and the
  `--tests` filter rule.

### 5 — `:apps:memories`

- **Change.** `git mv` `com.veyq.memories.*`, the Memories specs' generation, the Memories baseline migration, the
  Quarkus-free tests of that code (`ChunkFramesTest`, …) into `backend/apps/memories`; `beans.xml` (the module generates
  JAX-RS interfaces — without it the resources 404 silently, spike); depends on `:core`. The root now holds no main
  Kotlin. The Dockerfile copies the new module's files (rule (e) of step 6 will hold it).
- **Acceptance.** `:core` has no `project(":apps:…")` line and `./gradlew :core:compileKotlin` is green with
  `:apps:memories` absent from the build — the proof that the one edge really was one edge. The `@All`/`Instance<>`
  consumers collect the same set (`ScopeEraserOrderSliceTest`, `NodeSubtreeEraserOrderSliceTest`,
  `GuestIdentityUsageTest` pin it by name and phase). The migration guard now attributes `memories_` files to the
  `apps/memories` root.
- **Proof.** `./gradlew check`; the cold-home verification run; **[LIVE]** the race suite; **[LIVE]**
  `python3 scripts/run_web_e2e.py` against the fast-jar (the packaged multi-module jar serving the real stack; first run
  where Flyway reads one `db/migration/` directory from two jars on MariaDB); `docker build .`; record `time ./gradlew
  check`.
- **Bite-tests.** (1) delete `apps/memories/…/beans.xml` → `ScopeEraserOrderSliceTest` red (the pinned set loses its
  Memories erasers) and `OperatorBlindProbeTest` red (media routes 404). (2) add `implementation(project(":apps:memories"))`
  to `:core` → `Circular dependency`. (3) add an import of `com.veyq.memories.media` to a `:core` file →
  `:core:compileKotlin` fails. (4) a `memories_` migration in the core root → the migration guard red; a Memories
  migration with a later timestamp referencing `veyq_media` → green and applied after the core baseline on a fresh H2.
- **Done when:** the above.

### 6 — Test separation, the enforcement Gradle cannot do, the old guards' hand-over

- **Test homes (decision 5).** Each module owns the `@QuarkusTest`s of its code; every module's tests run with core on
  the classpath; helpers both sides use go to `java-test-fixtures` on `:core`. The proposal below is by *measured
  import*, to be confirmed file by file when the step compiles:

  | Home | Tests | Why |
  |---|---|---|
  | `:core` | the 129-odd tests of core features that import no Memories class: account (all), admin (instance, setup, tenant except `TenantStorageSliceTest`), platform, tasks, `spaces` except the three below, content except the four below, sharing except the thirteen below, all of `shared/*` except the four below; the 46 Quarkus-free unit tests of core code | they boot core alone |
  | `:apps:memories` — its own | the 17 `features/media` tests, favorites, item-favorites (19 files) | Memories code |
  | `:apps:memories` — core behaviour that can only be exercised with Memories present (open question 4) | `ShareContributorSliceTest`, `ShareDeletedNodeSliceTest`, `ShareGuestNamesSliceTest`, `ShareIdentityBoundTest`, `ShareLinkStorageLimitSliceTest`, `SharePasswordWriteGateTest`, `SharePresignDomainSliceTest`, `ShareSliceTest`, `ShareTrashedMediaSliceTest`, `ShareUploadWindowSliceTest`, `ShareUploaderSliceTest`, `ShareWriteProofSliceTest`, `GuestWriteRaceSliceTest`, `TenantStorageSliceTest`, `NodeTrashSliceTest`, `NodeTreeRaceSliceTest`, `RemovalRaceSliceTest`, `ClientMintedIdSliceTest`, the two eraser-order pins | they upload blobs through the Memories-hosted endpoints, seed `MediaEntity`/`BucketEntity`/`ItemFavorites…`, or pin the Memories erasers |
  | split | `ScopesSliceTest` (56 tests; a handful seed media/upload sessions through `MediaStore`/`TestBlob`) and `NodesSliceTest` (one `MediaStore` use): the media-touching methods move to a `:apps:memories` class, the rest stays; `ScopeAccessWorld` (a world-builder helper that uses `TestBlob`): the core part stays, the media seeding moves. **Account per assertion, not per count** (`changes/README.md`) | mixed within a file |
  | root | `OperatorBlindProbeTest` (probes every contract operation of both modules) and one new composition test (every module's beans present; the `@All` collections hold the union) | genuinely whole-app |

  The three CDI fake beans (`RecordingBlobStore`, `RefusingStoreReclaimStore`, `FakePresigningBlobStore`) move to
  `:core`'s test fixtures **with a `META-INF/beans.xml`** in the fixtures resources (R14, spiked at the start of the
  step); the `%test` keys: `application-test.properties` lives once, in `:core`'s test fixtures resources, read by the
  dependants' tests (R6); KDoc links that name a moved test class are rewritten (they name classes in both modules).
- **Cost, stated.** Today: one default Quarkus boot plus about 25 profile boots in one JVM. After: three default boots
  (core, Memories, root) — the profile boots are unchanged in number, they move with their test — i.e. **+2 default
  boots, ≈ +20 s** at the measured 12 s for a 10-test class including its boot, **plus the per-`test`-task overhead
  measured at ~90 s that I could not decompose** (R11): if it is fixed per task, `check` grows by up to ~3 minutes; if it
  scales with discovery it shrinks. Decide with the number from the step, not with this paragraph. Two things cut it:
  `org.gradle.parallel=true` (the module test tasks do not share ports — `test-port=0` — nor an H2 database, each
  in-memory per JVM; unmeasured under load) and Gradle's incremental behaviour (a Memories-only edit no longer reruns
  core's tests, measured only by reasoning).
- **New guard `validate_backend_module_graph.py`** (buildless; derives from `settings.gradle.kts` and the build files):
  (a) the included projects equal the directories holding a `build.gradle.kts`; (b) project-dependency edges are a
  subset of {`apps:*` → `core`} — in particular **no `apps:*` → `apps:*`** and no `core` → anything; (c) every module has
  `src/main/resources/META-INF/beans.xml` (and a test-fixtures one where fixtures hold beans); (d) no package is
  declared under two modules (from the files' `package` lines — nothing to rot), generated packages included (they are
  declared by the Gradle config: the guard reads the packages out of the generation tasks); (e) the Dockerfile's
  `backend-build-base` stage copies every included module's `build.gradle.kts`. Docstring fields as the guards before it.
- **Core-alone boot test** in `:core` (`@QuarkusTest`, core config only, no application): asserts the `ScopeEraser` /
  `NodeSubtreeEraser` / `SharedSubtreeContributor` / `MembershipEraser` lists hold exactly core's own implementations
  (with the control that core's are present), that `ShareGuestWrites` has no implementation and the core share routes
  that need none answer, and that an unauthenticated core route answers. *Why it is worth a test:* the compiler
  forbids the import; this catches a **runtime** coupling — a core bean injecting an interface only Memories
  implements, or a config key only an application defines.
- **Hand-over of the old guards.** Delete the Memories half of `validate_shared_imports_no_features.py` (the module
  boundary does it); keep the kernel half; `validate_guard_gaps` cases 34 and 38 (core importing media) are rewritten
  against what now rejects them (the module guard) or removed with the reason stated in the case table — never left
  pinning a guard that no longer checks it. Finish `validate_migration_versions.py`: the owning module comes from the
  path.
- **Acceptance.** The guards are green on the final tree and their docstrings say what each rule bites. The per-module test
  inventories are disjoint and their union equals the pre-step set of `classname#testname` (modulo the split classes,
  accounted per assertion).
- **Bite-tests.** (1) a forged `backend/apps/other/build.gradle.kts` with `implementation(project(":apps:memories"))`
  and an `include` → rule (b) red (lever: a forged second application — the only way to exercise app→app before a real
  one exists). (2) delete a `beans.xml` → (c) red. (3) a `:core` file declaring a `com.veyq.memories.…` package → (d)
  red. (4) drop a module's `COPY` from the Dockerfile → (e) red. (5) core-alone: add `@Inject lateinit var x:
  ShareGuestWrites` (non-`Instance`) to a core bean in a scratch copy → the core-alone boot fails; remove → green.
  (6) move a mixed test back into `:core` → `:core:compileTestKotlin` fails (the module cannot see Memories).
- **Done when:** the above, and the trust-chain glob is green.

### 7 — Close the series

- **Docs (edit in place, no addenda).** ADR 0079: Decision 10 (modules), Consequences — delete "Not done: the Gradle module
  split" and the `ScopesStore` remainder, state the module topology, the package naming, the migration versioning and the
  remaining core → Memories knowledge (`NodeKindRules`, blob/quota hosting, the `veyq_node` columns). `CLAUDE.md` §
  Repository shape ("inside the one Gradle module", `com.veyq.app.core` guard sentence); `backend/CLAUDE.md` (module
  layout, which tests run where, the `--tests` rule, migration versioning, the pruned-verification recipe still names
  `help check quarkusBuild pitest`); `docs/apps/README.md` and `docs/apps/memories.md` ("Separated only in part");
  the developer styleguide §§ on `shared/` and migrations; `infra/README.md:63` (`:backend:test`); `docs/architecture`
  package citations; `changes/README.md` if its examples name moved paths. Inbox #324: replace the "Gradle module split
  (phase 2) … not written yet" outcome sentence; delete the row only if nothing is left (the node `kind`, `wiki/`,
  `NodeKindRules` and the blob move remain, so it stays).
- **Delete** `scripts/rename_packages.py` and the one-off scratch scripts (scaffolding), after what they taught moved to
  where it binds.
- **Retrospective** written; the CR deleted after what it taught moved to where it binds (`changes/README.md`).

## Risks

| # | Risk | Where it bites | Held by |
|---|---|---|---|
| R1 | A module not indexed: beans, entities, `@ConfigMapping`s, `@Path` interfaces, interceptor bindings vanish. Injected beans fail loudly; **`@All` lists shrink and unindexed `@Path` interfaces 404 silently** (spike) | steps 4–6 | `beans.xml` per module + guard rule (c); the pinned-set slice tests; `OperatorBlindProbeTest`; the live race run for the interceptor case |
| R2 | The baseline differs from the history's end state in a column, type, default or index → runtime SQL errors, invisible to Hibernate (`strategy=none`) and to H2-only checks | step 1 | the schema-equality proof (H2 + **[LIVE]** MariaDB); bite (1) |
| R3 | Guards that read a shrunken tree after a move and stay green — now also **specs** (`contracts/api/memories/` is invisible to a non-recursive glob) and **migrations** (a second root) | steps 2–5 | the two helpers, per-guard "found none" lines, the second-root `validate_guard_gaps` cases |
| R4 | `./gradlew test --tests X` fails per project with no match; `run_backend_races_on_mariadb.py` is one such caller | step 4 | the script addresses the project explicitly and prints its class list |
| R5 | Kotlin `internal` stops at a module: 3 members, tests must move with them | step 4 | compile errors name them |
| R6 | Two `application-test.properties` on one test classpath, same config ordinal (remembered) | step 6 | one home (`:core` fixtures resources), decided in the step |
| R7 | The Dockerfile warm-up copies named files only; a new module without its `COPY` breaks the *release* build and nothing in `./gradlew check` shows it | steps 4–6 | the temp-dir warm-up each step; rule (e); **[LIVE]** `docker build .` |
| R8 | `quarkusDev` with workspace modules: hot reload of `:core`/`:apps:memories` classes, and the IntelliJ `Run Backend` configuration — **not measured** (running a dev server was out of scope; and a parallel `quarkusDev` in this tree would corrupt the owner's) | step 5 / close | **[LIVE]** the owner starts `Run Backend` once; broken reload across modules is a finding, not a blocker for the image |
| R9 | PIT across modules: `info.solidsoft.pitest` in `:core`, report path and trigger paths move | step 4 | `./gradlew pitest`, `mutation_meta_check_backend.py`, `validate_mutation_trigger_paths.py` |
| R10 | Dependency verification: new projects add configurations that resolve coordinates the single project never did (test fixtures, library `io.quarkus`) | steps 4–6 | the cold-home recipe without the write flag |
| R11 | Build time. Measured: one `:test` invocation costs ~90 s beyond its test (neither boot nor compile); unknown whether per task or per class discovered; with three test tasks that is the largest unmeasured number in the CR | steps 4–6 | the baseline recorded before step 0; `time ./gradlew check` after steps 4, 5, 6; if cold `check` regresses by more than a minute, say so before the next step; `org.gradle.parallel` as the lever |
| R12 | A parallel session edits a file this series moves (the rename touches all 365); the `--no-ff` merge is blocked by a neighbour's uncommitted edit (`memory`) | steps 2–5 | check `git status`/`ls changes/` first; wait, never clear it; the script's `--check` re-derives from a **hash**, not `HEAD~` |
| R13 | Dependabot's `gradle` entry points at `/backend`; whether it follows `include`d subprojects is **not measured** (no remote, remembered: it does) | close | note in the retro; a `directories:` list if it does not |
| R14 | Test-fixture beans (three CDI fakes) in a `java-test-fixtures` jar are not discovered by a dependant's `@QuarkusTest` without an index | step 6 | spike first; `beans.xml` in the fixtures resources; the bite: delete it → the Memories tests that need the fakes go red |
| R15 | The rename changes logger categories (`Logger.getLogger(X::class.java)` in 18 classes): an operator filtering by `com.veyq.app.…` stops matching. Nothing in the repo does | step 2 | stated in the commit and in `infra/README.md` if it names log filtering (`grep -rn 'log' infra/README.md` at the step) |
| R16 | The flat `core` merge hides the kernel/slice layering inside one package name | step 2 | the declared kernel list and its bite (open question 6) |
| R17 | A wrong decision on open question 4 surfaces late: if blob upload is extracted to core *after* this CR, 21 tests move modules a second time | step 6 | the table in step 6 names them; the follow-up CR owns the move |

## Definition of done

- **The enforcement exists and is bitten.** `validate_backend_module_graph.py` and `validate_migration_versions.py`
  are green on the final tree, and every bite listed above was made red once, with its lever named in the commit
  message. The kernel guard and the wire-diff / schema-equality proofs ran and are recorded. `validate_shared_imports_no_features.py` keeps only the half Gradle cannot see.
- **Behaviour unchanged.** `./gradlew check` green with no test deleted or weakened (the split classes accounted per
  assertion); the pre/post `classname#testname` sets agree (modulo the split classes); `git diff main -- contracts/`
  shows only the owner split (the wire-diff script is the statement); no crypto-vector diff. `race_classes()`
  identical, and **[LIVE]** `python3 scripts/run_backend_races_on_mariadb.py` green on the final tree.
- **Database.** The schema-equality proof green on H2 and **[LIVE]** MariaDB; a fresh boot applies exactly the two
  baselines; the owner's dev database was recreated (consequence stated in step 1).
- **One image.** **[LIVE]** `docker build .` green; **[LIVE]** `python3 scripts/run_web_e2e.py` green against the final
  fast-jar; `infra/` and `charts/` untouched (`git diff main --stat -- infra charts`); web/admin-web `npm run check` green
  and the generated-client diff as step 3 states it.
- **Independence is demonstrated, not asserted.** `:core:compileKotlin` and the core-alone boot test are green with
  `:apps:memories` absent from the build (`./gradlew :core:check`).
- **Baseline commands, run before the first edit:** § Steps ("Baselines recorded before the first edit").
- **Cost recorded.** `time ./gradlew check` before and after steps 4, 5, 6 in the commit messages; the decomposition of
  the ~90 s per-task overhead, or the statement that it was not decomposed.
- **Trust chain.** The whole glob green on the merged tree. Named: `validate_guard_gaps`, `validate_shared_imports_no_features`,
  `validate_scope_erasure_arity`, `validate_node_subtree_erasure`, `validate_blob_reference_arity`,
  `validate_store_reclaim_arity`, `validate_coverage`, `validate_doc_citations`, `validate_mutation_trigger_paths`,
  `validate_build_input_pins`, `validate_contracts_common`, `validate_changes`, plus the two new guards.
- **Coverage.** No acceptance criterion changes (`satisfies: []`); every coverage ref to a moved test still resolves
  by class stem (test class names do not change); `validate_coverage` green and its backend scan covers every module's
  test root.
- **Docs.** Step 7's list, each edited in place; the inbox row updated; no doc cites a `Vnn` migration that no longer
  exists (open question 9).
- **Retrospective** below filled in; the CR deleted after what it taught moved to where it binds.

## Retrospective

_(filled in at close)_
