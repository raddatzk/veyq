---
id: cr-platform-gradle-modules
title: Platform phase 2 — the backend becomes Gradle modules (core, apps/memories), packages renamed to their owner, blob upload and node-kind rules moved into core, the API and contracts split by owner, the Flyway history reset to a baseline
status: planned
satisfies: []
adrs: ["0079", "0001", "0013", "0057", "0064", "0072", "0073", "0078"]
invariants: [operator-blind E2EE everywhere, self-hostable by default, API-first]
---

> Provenance marks used below: **(measured: cmd)** a command run against this tree at refine; **(spike)** run in a
> throwaway project outside the repository (so not re-runnable from here — the step that leans on one re-measures it
> in-tree and says so); **(scan)** a throwaway script in the scratchpad (not committed): its numbers are a heuristic
> over source text, good for sizing and for choosing a home, and the step that moves a file re-derives the home from
> what the file imports; **(remembered)** read from a comment or a doc, not run; **(unmeasured)** named so nobody
> mistakes it for a finding. Substrate of every measurement: branch `worktree-agent-a42adb50c330ac43e` at `26eda01c`
> (= `main` tip, tree clean; the only difference to the second refine's substrate `6bb906cf` is this CR and inbox
> row #324 — `git diff --stat 6bb906cf 26eda01c`). Re-cut a third time on 2026-10-04 after the owner's further
> decisions (§ Decided, rows 7–11): one deployable jar, `NodeKindRules` in, the generic blob upload and quota half
> moves to core as a step of its own.

## Open for the owner

What is still open after your decisions of 2026-10-04 (first round: rows 0–6 of § Decided; second round: rows 7–11);
each carries a recommendation. None blocks step 0; the ones marked **before step N** block that step. Decided since
the last version of this CR: the artifact shape (one fast-jar), the blob location (now step 3), `NodeKindRules` (now
step 4). Question 3 had no answer from you and **keeps my recommendation, the declared kernel list**, until you say
otherwise.

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
2. **Pin and release — your call; this is what the words mean here.** The *pin* is one file,
   `scripts/applied_migrations.sha256`, holding the SHA-256 of every baseline migration, and a guard
   (`validate_migration_versions.py`) that fails when a migration file differs from its line or has vanished. The
   *release* is the first version that anyone but you has installed; from then on a baseline is never edited, only
   extended by a new migration (the `backend/CLAUDE.md` rule "an applied migration is never edited" becomes absolute
   again). Before that moment a baseline may still change, but only together with a visible edit of its pin line in
   the same commit, so the change cannot go unnoticed. The guard cannot know when the release happens; you declare
   it (a line in the pin file). **Recommendation:** pin at once, release when you say so.
3. **The `shared/` → `features/` layering dies in the flat rename — before step 2.** `shared.X` and `features.X` both become
   `com.veyq.core.X`, and the directory name was the guard's only handle on "kernel never imports a feature slice".
   **Recommendation: keep the rule, re-express it as a declared list** — the 15 former `shared/` packages are the
   *kernel*; `validate_shared_imports_no_features.py` (renamed) reads that list from one constant, a new package
   defaults to "slice". Alternative: keep `com.veyq.core.shared.<x>` as a sub-tree (no list, but a name that says
   nothing the module does not). The list is a layer declaration, not a table that rots; step 2 bites it. **No answer from you yet: this recommendation stands.**
4. **Contract layout — before step 5.** **Recommendation:** Memories-owned specs go to `contracts/api/memories/`
   (`media.openapi.yaml` moved, `favorites.openapi.yaml` new); core specs and `_common.openapi.yaml` stay where they
   are. A full `core/` + `memories/` tree would move ten files, rewrite two `package.json` scripts and ~13 guards
   for no extra ownership signal. The cost of the asymmetric layout is guards that glob `contracts/api/*.openapi.yaml`
   *non-recursively* and silently stop seeing the Memories specs (R3) — step 5 adds one helper and one bite. The new core `blob.openapi.yaml` (decision 9) sits at the top level beside the other core specs, so it needs no layout decision of its own.
5. **Web clients: "must not change" has a measured limit.** `web/package.json` generates **one directory per spec file**
   (`generate:api`, nine `openapi-generator-cli` calls). Splitting `content.openapi.yaml` therefore changes
   `src/api/generated/content/` by construction (it loses the favorites files, which appear byte-identical in a new
   `favorites/` directory) and three web files change their import (`api/client.ts` and two tests, measured). **The
   core `blob` tag (step 5) goes further:** `generated/media/` loses three operations and their schemas to a new
   `generated/blob/`, and the *source* changes — a `blobApi()` in `api/client.ts`, the three call sites in
   `features/memory/media.ts` and the fakes in five test files (§ State). And
   consolidating the three real duplicate schemas changes JSDoc text in some directories, because their copies'
   descriptions differ (measured: distinct-with-description 2→1, 2→1, 3→2; § State). **Recommendation:** accept that
   and prove the precise statement — every generated file is byte-identical modulo `/** … */` and the two barrel
   index files, and the favorites and blob files move whole (step 5). The literal-zero alternative is to leave the favorites
   operations in `content.openapi.yaml`, which contradicts decision 3.
6. **Docs that narrate the migration history (`V11__media.sql`, "V9 and V21 rebuild…").** Measured: ~45 files cite
   a migration by version (locator below), several as the *reason* for a rule. **Recommendation: delete the
   version-specific lore, keep the trap** (rule 5 of `docs/ai/README.md`): cite the table and the baseline file
   (`core baseline, veyq_node`), keep rules the history taught (`MODIFY COLUMN` parity, the `TEXT` ceiling), drop
   diary lines ("V9/V21 rebuild through a fresh table"). Your call whether any of it is worth keeping as prose.
7. **Where the durable record goes.** **Recommendation:** edit ADR 0079 in place (Consequences list the module split
    as not done; Decision 10 says "applications as packages of it" → modules) plus `backend/CLAUDE.md` and the
    developer styleguide (migration versioning, module layout), with the open prerequisites (Goal) written into ADR 0079 Consequences where the next application finds them; a new ADR only for the migration-version rule if you
    want it citable on its own.
8. **Test homes — one measured finding to decide.** Decision 5 (each module owns its `@QuarkusTest`s) meets a fact:
   **core refuses to create a share for a node kind that no `ShareGuestWrites` supports** (`SharingHandler.kt:275`),
   and the only implementation is Memories'. In a runtime of core alone every share creation is `illegal_scope`, so
   the **149 test methods in 27 files** (20 of them in `features/sharing`) that POST `/api/shares` cannot run there —
   including pure share-policy tests (password, note, key recovery, reissue, shared scope) that never touch a medium
   (scan, § State). Likewise the 22 methods that create a `MEMORY`/`CHAPTER` over HTTP need `NodeKindRules`' Memories
   implementation (step 4). **Recommendation: they move to `:apps:memories` unchanged** — zero edits, nothing
   weakened, the Memories runtime being a superset of core's; the price is that about 170 methods lose the label
   "boots core alone". The alternative is a fake `ShareGuestWrites` for a neutral test kind in `:core`'s own test
   sources (not in the fixtures jar, or Memories' runtime would see it), which needs those tests' fixtures re-based on
   that kind — `seedNode` defaults to `MEMORY` today. That is worth doing when the second application brings a second
   kind; say so if you want it now. The table is step 8's.
9. **Where do `CompleteItem` and `CompleteItemResult` land?** You decided they leave `core/blob/Upload.kt` (they
   speak Memories: `mediaId`, `derivatives`, `wrappedExif`). Measured: core's own `ShareGuestWrites.complete`,
   `SharingHandler.completeUploads`/`ShareCompleteItem` (`SharingHandler.kt:44`, `:647`) and `SharingResource.kt:373`
   build and consume them, because the share contract's `/api/shares/{shareId}/media` is a core-served operation of
   the sharing spec. Moved into Memories, core's sharing code would import Memories — the one edge the cut forbids.
   **Recommendation: out of `Upload.kt` into the new `UploadCompletion.kt` beside the seam that needs them, still in
   core, field names unchanged** (renaming `mediaId` is a wire change; moving the two share `/media` operations out of
   core's sharing spec needs a core guest-write gate service first and is a seam of its own, a later CR). Cost: core
   still spells "media" in the share completion path; ADR 0079 Consequences says so at step 8. If you want them in
   Memories now, the CR grows by that gate-service step.

**Unknowns I could not measure** (each is a step's first task or a **[LIVE]** run, not a decision): the wall time of
`./gradlew check` after the split (R11 — one measured fact below makes this the largest open number); `quarkusDev`
hot reload across workspace modules and the IntelliJ `Run Backend` configuration (R8); whether Dependabot's
`gradle` entry with `directory: "/backend"` follows `include`d subprojects (R13, remembered: it does, unproven —
the repository has no remote); whether test-fixture beans in a `java-test-fixtures` jar are discovered by a
dependent module's `@QuarkusTest` (R14); that the jaxrs-spec generator honours `schemaMappings` the way the Kotlin
model generator measurably does; that the TypeScript generator resolves a `../_common.openapi.yaml` parent-relative
`$ref` (step 5 runs it); the `javap`-normalised bytecode diff proving the rename (step 2 runs it; feasibility
reasoned, not run); the exact file names Quarkus gives intermediate jars in `quarkus-app/lib/main` once a group and
`archivesName` are set (step 6 reads them); the per-method test classification below is a scan, and it does not
follow a helper across files (`ScopeAccessWorld`'s share creation is counted once, in its own file); every
**[LIVE]** proof.

## Decided by the owner (2026-10-04)

First round:

| # | Decision | What it replaced in the first refine |
|---|---|---|
| 0 | Do the whole split now, not only the guard prelude | "two committable halves, stop after step 1 if you want to wait" |
| 1 | Stay in `backend/`; Gradle subprojects below it (`backend/core`, `backend/apps/memories`); the root `backend/` stays the composition/Quarkus app, so Dockerfile, CI and run configs keep their paths | recommendation (a), now decided |
| 2 | Rename packages **with** the move: platform → `com.veyq.core.<feature>` (today `com.veyq.app.core.*`, `shared.*` and the platform `features.*`), Memories → `com.veyq.memories.<feature>`, a future `com.veyq.inventory…`; composition stays `com.veyq.app` | "no rename, later" |
| 3 | The generated API and `contracts/` split by owner; the multiply-defined schemas move to core and Memories maps them; `content.openapi.yaml` splits node (core) vs the Memories parts; no `:api-generated` module — each module generates its own types; `TrimmingStringDeserializer` stays in core (no cycle, § Target shape); wire format and paths unchanged | "one `:api-generated` module for all ten specs" |
| 4 | Flyway reset to fresh baseline scripts (no production environment exists), split per module (core first, Memories referencing core, never the reverse), **one global monotone version scheme** across modules (timestamp-style), the same version in two modules fails loudly, the freeze guard pins from the baseline onward | "all 67 files stay in `:core`, byte-identical, upgrade rehearsal" |
| 5 | Tests separated cleanly: each module has its own `@QuarkusTest` integration tests, running with core on the classpath; only genuinely whole-app tests stay in the root | "every `@QuarkusTest` stays in the root" |
| 6 | Follows from 3 | — |

Second round (later the same day):

| # | Decision | What it replaced |
|---|---|---|
| 7 | **No separately deployed jars.** The deliverable stays **one Quarkus fast-jar** built by the root `backend/`; the modules are project dependencies whose *intermediate* jars land in `quarkus-app/lib/main` (spike). They are named, not shipped: a group and `base.archivesName` so no `core-unspecified.jar` can collide with a third-party `core`. Dockerfile, compose and chart unchanged | the first refine's "Jars `veyq-core` / `veyq-memories`" and open question 3 |
| 8 | **`NodeKindRules` is in this CR** (step 4): the `MEMORY`/`CHAPTER` parent and mutation rules belong to Memories; core gets an interface, collected with `@All` like `ScopeEraser`, that Memories implements; core knows `FOLDER` only; an unknown kind stays fail-closed | open question 5, "not in this CR" |
| 9 | **The generic blob upload/quota half moves to core, as its own step before the module cut** (step 3), with two core seams, `UploadAdmission` and `UploadCompletion` | open question 4, "accept for this CR, next CR" |
| 10 | **Not in this CR, recorded as open prerequisites** (§ Goal, inbox #325–#327): a generic `veyq_node_attachment(node_id, slot, blob_id)` table with real foreign keys — built with the first application that really needs it (Expenses or Files), cut on that real case; the generalisation of the cover columns and revision counters; a new seal purpose and key rule for non-Memories blobs | — |
| 11 | Pin/release stays the owner's call (open question 2); open question 3 (kernel list) had no answer and keeps its recommendation | — |

Slicing order (the owner's, re-cut): MembershipEraser edge (0) → Flyway baseline reset (1) → package rename (2) →
blob upload/quota to core (3) → `NodeKindRules` (4) → contracts split (5) → Gradle skeleton and `:core` (6) →
`:apps:memories` (7) → test separation, module-graph guard, core-alone boot test, docs, retrospective (8). Every step
leaves `./gradlew check`, the web and admin-web `npm run check` where contracts or clients are touched,
`for f in scripts/validate_*.py` (python3 is 3.9) and `validate_guard_gaps` green. Steps 3 and 4 are separate because
the measurement says they are: different files (`MediaHandler`/`MediaStore`/`StorageQuota` against
`NodesHandler`/`ContentResource`), different tests, different bites, and one commit holding both would be the hardest
thing in the series to review; they meet in one place only — the `MEMORY`/`CHAPTER` allow-list that step 3 leaves in
the Memories `UploadCompletion` and step 4 re-points at Memories' own constants.

## Goal

Phase 2 of ADR 0079: **the backend's boundary becomes a build boundary and a naming boundary, one image, no
behaviour change.** After it:

- `:core` compiles with no Memories on its classpath (Gradle refuses a cycle); `:apps:memories` depends on `:core`;
  no application depends on another (a guard, because Gradle does not). The root `backend/` composes them into the one
  `quarkus-app`.
- **One deliverable.** The result is a single Quarkus fast-jar built by the root; the modules' intermediate jars are
  inside it (`quarkus-app/lib/main`), named by group and `archivesName`, never deployed on their own. The container
  image, `infra/` and `charts/` are unchanged (`git diff main --stat -- infra charts` empty).
- Every class of the platform lives in `com.veyq.core.<feature>`, every Memories class in
  `com.veyq.memories.<feature>`, and the generated API follows (`com.veyq.core.generated.*`,
  `com.veyq.memories.generated.*`). A package spanning two modules is a guard failure.
- **Core owns the generic blob upload and the storage quota** (start, windows, verify, download, session purge,
  chunk framing, tenant quota); Memories plugs its media attach and its per-link usage into them through two seams
  (`UploadCompletion`, `UploadAdmission`). **Core knows the node kind `FOLDER` only**: the `MEMORY`/`CHAPTER`
  placement rules are Memories', behind a third seam (`NodeKindRules`); an unknown kind is refused.
- The contracts name their owner (core specs, `contracts/api/memories/`), each module generates its own types, every
  schema has one definition, the three generic blob operations form a core `blob` tag, and **paths, schemas, status
  codes and the wire are unchanged** (a mechanical canonical-JSON diff proves it, step 5).
- The database starts from one baseline per module under one global version scheme; the migration guard pins it.
- Each module's `@QuarkusTest`s run in that module, with core included; the core-alone boot test proves core needs no
  application.

**Not in this CR — open prerequisites** (each is also a note on the inbox rows named, so the next application finds
it; none is a decision this CR takes):

- **A generic `veyq_node_attachment(node_id, slot, blob_id)` table with real foreign keys** (#327, #325/#326 where
  they need a blob). Owner decision: it is built with the first application that really needs it (Expenses or Files)
  and cut on that real case, not designed here. Today three columns point at `veyq_blob` — `veyq_media.blob_id`,
  `veyq_media_derivative.blob_id`, `veyq_node.cover_blob_id` — and `validate_blob_reference_arity` pins exactly those
  three arms; a new table is a fourth arm that guard (and the reclaim predicate behind it) must learn on the day it
  appears.
- **The generalisation of the Memories-flavoured columns and counters on `veyq_node`** (`cover_blob_id`,
  `cover_byte_size`, `media_revision`, `delete_revision`; measured, they stay in the core baseline because moving a
  column is a schema change, not a rename). Step 3 moves the *code* that uses `cover_blob_id` no further than it
  already is: `commitCover`, `clearCover` and `bumpMediaRevision` stay Memories' (they write core's `veyq_node` row
  through the entity, as today).
- **A seal purpose and a key rule for blobs that are not Memories' (#327).** The server side is neutral already: a
  blob's `K_blob` wrap, `owner_id` and `blob_role` are opaque strings the generic upload code stores and compares and
  never opens. The *client* side and the stored format are not: the `blob-key` seal is derived under a Memory's
  `K_mem` (`web/src/features/memory/media.ts:45`, `packages/crypto/src/seal.ts:48`), and `BlobRole`
  (`ORIGINAL`/`THUMBNAIL`/`PREVIEW`/`COVER`) is a closed enum in three places — the contract schema
  (`contracts/api/media.openapi.yaml:1122`, which moves into core's `blob.openapi.yaml` at step 5, still closed), the
  crypto package (`seal.ts:35` type, `:136` list) and its re-export `web/src/features/memory/media.ts:62`. A role or a
  key for another application is an ADR 0072 stored-format decision (a new variant plus its vector), not a rename.

Also not in this CR: the node `kind` beyond `FOLDER`/`MEMORY`/`CHAPTER` (the contract's `NodeKind` enum stays closed
and core's: hand-written core code stops naming `MEMORY`/`CHAPTER`, the *generated* enum, which core's resources decode,
still does), the `veyq.media.*` config keys (the upload retention and purge keys keep their names although the code
that reads them is core's now), `rootKind` on `PublicShare` (#329), node URLs (#328), the `wiki/` update, a second
application, moving the share contract's two `/media` operations out of core (question 9).

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
| **24 platform-package tests import Memories** (+2 helper fakes that only mention it in KDoc); of those, 7 use only the `TestBlob` helper (generic after step 3), 17 also touch `MediaEntity`/`BucketEntity`/`MediaStore`/`ItemFavorites*`/another Memories test class. `TestBlob` and `ChunkFrames` are the Memories-hosted upload plumbing (open question 4) | `grep -rlE 'features\.(media\|content\.(item)?favorites)' backend/src/test/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'`; per-file imported-symbol list |
| Three CDI beans live in test sources (`RecordingBlobStore`, `RefusingStoreReclaimStore`, `FakePresigningBlobStore`, in `shared/blobstore`) — moved into a `java-test-fixtures` jar they are only discovered if that jar is indexed (R14) | `grep -rlE '@ApplicationScoped\|@Alternative\|@Singleton\|@Produces' backend/src/test/kotlin \| xargs grep -LE '^@QuarkusTest'` |
| **Cost of one `:test` invocation, measured**: `./gradlew --offline test --tests '*ClientMintedIdSliceTest'` (10 tests, `@QuarkusTest`) — 1m47–1m50 with every compile task UP-TO-DATE, of which the class itself is 12.0 s (the report's `time`); `--tests '*TokenBucketTest'` (a pure unit test, no Quarkus boot) — 1m33. So **~90 s per `test` task invocation is neither the Quarkus boot nor compilation**; whether it is fixed per task (then three modules pay it three times) or scales with the 169-class discovery is **not decomposed** (R11). A cold first run in a fresh worktree cost 2m58 | `time ./gradlew --offline test --tests '…'`; `build/test-results/test/*.xml`; `--profile` report |
| No native build, no reflection config, no `beans.xml`/`jandex`; the Dockerfile's `backend-build-base` stage copies named files only (`gradlew`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `pitest.scope.json`, `gradle/`) and warms with `./gradlew --no-daemon help`; `COPY backend/src ./src`; `.run/*.xml` and CI use `working-directory: backend` and root tasks only | `Dockerfile` lines 46–65; `grep -nE 'gradlew\|working-directory' .github/workflows/*.yml` |
| Dependency verification is one file at the build root; Dependabot's `gradle` entry is `directory: "/backend"` | `backend/CLAUDE.md`; `.github/dependabot.yml` |
| **The generic blob half of `features.media`, measured at the third refine.** `MediaHandler.kt` is 709 lines: the generic halves are the constructor fields and `start`/`startForLink`/`reserveAndOpen`/`window`/`windowForScope`/`windowOne`/`presignWindow`/`admissibility`/`openSession`/`presignOpened` (lines 232–369, 138), `BlobVerify`/`verifyBlob`/`purge` (444–489, 46), `download`/`downloadOne` (619–631, 13) and the outcome types `StartOutcome`/`WindowOutcome`/`DownloadOutcome`/`DownloadItemResult` (39–60, 79–84, 28) — **≈ 225 lines**, matching the owner's ≈ 230. The Memories rest: `complete`/`completeForNode`/`completeOne`/`attachesMedia` (371–447), media listing/meta/trash/cover/buckets (498–617, 638–709). `MediaStore.kt` is 698 lines, of which ≈ 100 are generic: `blobIdTaken`, `createSession`, `findSession`, `sessionSpent`, `touchWindow`, `persistBlob`, `purgeSessionsIdleSince`, `findBlob`, `deleteBlobRow`, the manifest codec (lines 98–146, 229–246, 366–388, 444–445, 505–518). Whole generic files: `ChunkFrames.kt` (51), `UploadSessionPurge.kt` (48; it reads `config.media().upload().retention()` and calls `purgeSessionsIdleSince`), `TestBlob.kt` (69; imports only `ChunkFrames`, `UploadSessionEntity`, `FakePresigningBlobStore`). `StorageQuota.kt` (70) is half and half: `lockTenant`/`tenantFits` only delegate to core's `StorageUsage`; `linkFits`/`linkUsage` hold one SQL whose **session term** (`veyq_upload_session ⨝ veyq_share_identity`, both core tables) is generic and whose **blob term** (`veyq_media`, `veyq_media_derivative`) is Memories'. **Nothing outside `features.media` calls `MediaHandler`, `MediaStore` or `StorageQuota` in main code** — the move touches one package | `wc -l backend/src/main/kotlin/com/veyq/app/features/media/*.kt`; `grep -nE '^\s*(private )?fun \|^class\|^private sealed' …/MediaHandler.kt …/MediaStore.kt`; `grep -rnE 'MediaHandler\|MediaStore\|StorageQuota' backend/src/main/kotlin \| grep -v '/features/media/'` (comments only) |
| **`completeOne` interleaves the two halves**, which fixes the seam's grain: node lookup → `attachesMedia(node.kind)` (`MEMORY` or `CHAPTER`, `MediaHandler.kt:395`) → session lookup → `boundTo(mediaId, "ORIGINAL")` (the `K_blob` wrap's binding, ADR 0072) → `mediaIdTaken` (Memories) → **declare-then-verify every blob** (generic) → `MediaStore.attach` (Memories: spends the sessions, `persistBlob`, media row, derivative rows, primary bucket, `bumpMediaRevision`). A verification sits between two Memories checks, so the seam is the *whole item*, and the generic steps are core primitives the Memories implementation calls. `commitCover` and `attach` both call `persistBlob`, and `deleteBlobRow` is called by the cover path and by `purgeMediaRows`: both stay Memories callers of core store methods. The member `complete` (access check, then `completeForNode`) is the Memories operation `completeMedia`; the guest path reaches the same loop through `ShareGuestWrites.complete`, whose `supports` is `MEMORY` only (a share's root), narrower than the member path's `MEMORY` or `CHAPTER` | `sed -n 371,447p …/MediaHandler.kt`; `sed -n 20,35p …/MediaShareGuestWrites.kt` |
| **Where `MEMORY`/`CHAPTER` are named in hand-written main code: four sites.** The constants (`core/node/Entities.kt:99–100`), `NodesHandler.parentAllows` (lines 74–79; called from `create` and from the move branch of `update`; `FOLDER` and `MEMORY` at the root or under a `FOLDER`, `CHAPTER` under a `MEMORY`, `else -> false`), `MediaHandler.attachesMedia` (395), `MediaShareGuestWrites.supports` (28). The generated `NodeKind` enum (closed in `content.openapi.yaml`, decoded in `ContentResource.kt:106,117` and `SharingResource.kt:272`) names them too but is generated. **Tests: 174 uses of `NodeEntity.MEMORY`/`CHAPTER` in 30 test files** (`seedNode` defaults to `MEMORY`); the rule-bearing ones are `NodesSliceTest` (`createTreeThenList`, `chapterAtRootIs422`, `renameThenDeleteSubtree`; 8 of its 12 methods create a `MEMORY`/`CHAPTER` over HTTP), `NodeCreateRaceSliceTest`, `NodeTrashSliceTest`, `NodeTreeRaceSliceTest`, `ClientMintedIdSliceTest`; `ParentSlot` (`Root`/`Node(kind)`/`Missing`, `NodesStore.kt:39`) is already kind-agnostic — a missing or trashed parent is answered before `parentAllows` runs — so `NodeTreeRaceSliceTest`'s "created under a trashed folder" races test a generic rule through a `MEMORY` create | `grep -rnE 'NodeEntity\.(MEMORY\|CHAPTER)\|"(MEMORY\|CHAPTER)"' backend/src/main/kotlin`; `grep -rnc 'NodeEntity\.\(MEMORY\|CHAPTER\)' backend/src/test/kotlin \| grep -v ':0'` |
| **A runtime of core alone refuses every share**: `SharingHandler.create` answers `illegal_scope` unless some `ShareGuestWrites` `supports(node.kind)` (line 275), and the only implementation is Memories'. 27 test files POST `/api/shares` (20 in `features/sharing`), **149 methods** (117 that need only the share, 32 that also touch a medium) (scan) | `grep -rlE 'post\("/api/shares"\)\|createShare\(' backend/src/test/kotlin`; the scan below |
| **Test classification (scan, 1071 `@Test` methods in 169 files; `scripts` of the scratchpad `homes2.py`).** Flags: A = a Memories entity, a media/cover/bucket/favorites endpoint or a media completion; B = creates a share over HTTP; C = creates a `MEMORY`/`CHAPTER` node over HTTP. **786 methods carry no flag** and run core-alone once the blob half is in core; 117 are B only, 114 A only, 32 AB, 19 C only, 3 AC. Per file: 105 files all unflagged, 27 files with no unflagged method, 20 split. **`features/media`: 17 files, 133 methods, 43 generic** — whole generic: `ChunkFramesTest` (5), `StoreOriginPresignSliceTest` (3); generic methods inside mixed classes: `TenantStorageQuotaSliceTest` 11 of 21 (+2 B), `UploadWindowSliceTest` 5 of 8, `UploadSessionPurgeSliceTest` 4 of 5, `MediaPresignDomainSliceTest` 3 of 4, `MediaSliceTest` 6 of 24, `PurgeRaceSliceTest` 3 of 6, `MediaCreateRaceSliceTest` 2 of 7, `ObjectKeyCarriesNoStructureSliceTest` 1 of 2; Memories-only whole classes: `BlobBatchE2ETest`, `MediaBucketsSliceTest`, `MediaBudgetBranchSliceTest`, `MediaRowLockRaceSliceTest`, `MediaTrashSliceTest`, `NodeCoverSliceTest`. `UploadWindowSliceTest` and `ShareUploadWindowSliceTest` borrow `MediaBudgetBranchSliceTest.FakePresignProfile` as their `@TestProfile` — a helper that has to leave its Memories test class. **The previous count of "21 tests of core features that need Memories" was 20** (13 `features/sharing` + `TenantStorageSliceTest`, `NodeTrashSliceTest`, `NodeTreeRaceSliceTest`, `RemovalRaceSliceTest`, `ClientMintedIdSliceTest` + the two eraser-order pins); after the blob move **only `TenantStorageSliceTest` (5 methods) loses its last Memories dependency**; `RemovalRaceSliceTest` has no blob logic at all (3 unflagged, 2 B, 1 A: item favorites and share creation); `ScopesSliceTest` (56) needs `MediaStore.createSession` and `TestBlob` — both generic after step 3 — so it stays whole in core except its one share-creating method, which makes the earlier "split `ScopesSliceTest` and `NodesSliceTest`" unnecessary on the media side | `python3 …/scratchpad/homes2.py list`, `…/methods2.py <files>` (throwaway; the markers are the regexes at its top). Limits: a text scan, helper calls are followed inside a file only |
| **Blob operations and their schemas (contract).** The three generic operations are `startBlobUploads` (`POST /api/scopes/{scopeId}/blob-uploads-batch`), `presignBlobUploadWindows` (`…/blob-upload-windows`), `presignBlobDownloads` (`…/blob-downloads-batch`), all tagged `media` in `media.openapi.yaml`. The schema closure of the three is **13 schemas that no other media operation reaches** (`BlobUploadsBatchRequest`/`…Response`, `BlobUploadRequestItem`/`…ResultItem`, `BlobUploadWindowsRequest`/`…Response`, `BlobUploadWindowRequestItem`/`…ResultItem`, `BlobDownloadsBatchRequest`/`…Response`, `BlobDownloadRequestItem`/`…ResultItem`) **plus `BlobRole` and `PresignedUrl`** — so they move whole; the closure shares only `ErrorResponse`, `ScopeDeleting`, `TenantSuspended` and the `ScopeId` parameter with the rest (`ScopeId` is defined once in `content` and once in `media`, a parameter the schema-only duplicate walk of the first refine did not cover). **Generated, measured:** a throwaway tag `blobx` on the three operations yields `@Path("/api/scopes/{scopeId}")` for the new interface and leaves `MediaApi` at `/api/scopes/{scopeId}` — the same root `ContentApi` already has, so the backend/CLAUDE.md "one tag, one root" rule holds (the three paths differ at the segment after `{scopeId}`; it is not a character-wise prefix). `sharing.openapi.yaml` carries its own mirrors under other names (`ShareBlobUpload…`), not copies | `python3 …/scratchpad/blobschemas.py`; edit the three `tags:` lines of `contracts/api/media.openapi.yaml`, `cd backend && ./gradlew --offline openApiGenerateSpecs`, `grep -n '^@Path' build/generated/openapi-api/src/gen/java/com/veyq/app/generated/api/{Blobx,Media}Api.java` (reverted afterwards) |
| **Web side of the blob operations.** `web/src/features/memory/media.ts` calls them through `mediaApi()` (lines 764, 778, 1206); `web/src/api/client.ts:34,145` builds the one `MediaApi`; three test files fake `MediaApi['startBlobUploads']`/`['presignBlobDownloads']` (`galleryFakeStore.ts`, `media.test.ts`, `shareUploader.test.ts`) and two more reach them through `store.api` (`MediaLightbox.test.ts`, `MemoryDetail.test.ts`). A core `blob` tag therefore changes the **web client by construction**, beyond the favorites move | `grep -rnE 'startBlobUploads\|presignBlobUploadWindows\|presignBlobDownloads' web/src admin-web/src \| grep -v generated` |

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
| `:core` (`backend/core`) | `com.veyq.core.*` (former `app.core.*`, `shared.*`, the platform `features.*`, the node files of `features.content`); **the generic blob upload and quota** (`com.veyq.core.blob`: `ChunkFrames`, `BlobUploadHandler`, `BlobUploadStore`, `UploadSessionPurge`, `BlobResource`; `com.veyq.core.quota.StorageQuota`) and the three seams `UploadAdmission`, `UploadCompletion`, `NodeKindRules` with the `FOLDER` rule; generation of the core specs (including `blob.openapi.yaml`) into `com.veyq.core.generated.{api,model}` plus `AdminChannelOperations`; `TrimmingStringDeserializer` (as `com.veyq.core.validation…`); the core baseline migration; `beans.xml`; `java-test-fixtures` (the helpers both sides use, `TestBlob` and the fake-presign profile among them); core's tests; PIT plugin + `pitest.scope.json` | the Quarkus BOM and extensions |
| `:apps:memories` (`backend/apps/memories`) | `com.veyq.memories.{media,favorites,itemfavorites}`: the `MediaStore`/`MediaHandler` rest (media, derivatives, buckets, covers, revision counters, trash, the media attach), the three seam implementations (`UploadAdmission`: per-link blob usage; `UploadCompletion`: media/derivative/bucket attach for `MEMORY`/`CHAPTER`; `NodeKindRules`: `MEMORY`/`CHAPTER` placement) and `MediaShareGuestWrites`; generation of `contracts/api/memories/*` into `com.veyq.memories.generated.{api,model}` with the shared schemas mapped to core's; the Memories baseline migration; `beans.xml`; Memories tests and the mixed tests (step 8) | `:core` (+ its test fixtures for tests) |
| root `backend/` (`com.veyq.app`) | `io.quarkus` composition, `src/main/resources` (`application*.properties`, the web `dist` target), `quarkusBuild`/`quarkusDev`, the wrapper, `gradle/verification-metadata.xml`, the whole-app tests (`src/test`: `OperatorBlindProbeTest`, one composition test) | `:core`, `:apps:memories` |

**One deliverable, intermediate jars named.** `quarkusBuild` in the root produces the one `quarkus-app/` the Dockerfile
copies today; `:core` and `:apps:memories` contribute `lib/main/<jar>` entries (spike: one jar per module, boots,
migrates, serves) and are never built, published or deployed on their own. Without a group the jars would be
`core-unspecified.jar` and `memories-unspecified.jar`, names a third-party `core` can collide with. So: `group =
"com.veyq"` and `base.archivesName` `veyq-core` / `veyq-memories` set once in `backend/gradle/module.gradle.kts`
(step 6), the root stays `veyq-backend`, no per-module version (whatever the root stamps). Project paths stay `:core`
and `:apps:memories` (what the commands type). The exact file names Quarkus writes into `lib/main` are read in step 6
and recorded; a guard rule (step 8) fails a module without an `archivesName`.

No `:api-generated` module (decision 3): each module compiles what it generates, so the cycle the first refine feared
(`:api-generated` → core's `TrimmingStringDeserializer` → `:api-generated`) does not exist — core's generated models name
a class in core itself, Memories' name it across a legal edge.

**The three seams** (interfaces in core, collected with `@All MutableList<…>` — the ArC trap in `backend/CLAUDE.md` —
like `ScopeEraser` and `ShareGuestWrites`; one implementation each, in Memories):

| Seam | Question core asks | Memories answers with | Absent implementation |
|---|---|---|---|
| `UploadAdmission` | "what does link *L* already hold in blobs that are not open sessions?" — core adds its own open-session sum and compares with the tenant's `shareLinkLimitBytes` | the `veyq_media`/`veyq_media_derivative` blob term of today's `linkUsage` SQL | 0 (no application stores blobs for a link, so there is nothing to count; a share cannot exist without an application anyway) |
| `UploadCompletion` | `accepts(nodeKind)` and `complete(scopeId, node, item, opener, admitted)` — dispatched by the node's kind exactly like `ShareGuestWrites.supports`, **exactly one** acceptor or the item is `illegal_target` | `MEMORY`/`CHAPTER`: the media/derivative/bucket attach (today's `completeOne` body), built on core primitives (`findSession`, `verify`, `purge`, `persistBlob`, spend-the-sessions) | refused: **default-deny for a new kind stays in core** — a kind gets uploads when an application opts it in |
| `NodeKindRules` | `handles(kind)` and `parentAllows(kind, parent: ParentSlot)` — exactly one handler or the kind is refused (`illegal_structure`, today's `else -> false`) | `MEMORY`: root or under a `FOLDER`; `CHAPTER`: under a `MEMORY`. Core's own implementation handles `FOLDER`: root or under a `FOLDER` | refused: an unknown kind stays fail-closed |

**How each rule is enforced.** *Core never imports Memories*: `:core` has no `project(":apps:…")` dependency, so the
import does not compile; adding it is a project cycle (spike). *No application imports another*: Gradle allows
`:apps:a` → `:apps:b`; a buildless guard parses the build files (step 8; its bite needs a forged second application).
*No package spans two modules*: Kotlin allows it and Quarkus indexes both; a guard derives it from `package` lines.
*Every module is indexed*: a guard on `beans.xml`. *No two migrations share a version, a core migration names no Memories
table, a Memories migration references only core tables or earlier Memories ones*: `validate_migration_versions.py`
(step 1, finished in step 8). *Inside core*, the kernel never imports a slice: the surviving half of the old guard, over
a declared kernel list (open question 3). *Hand-written core code names no `MEMORY`/`CHAPTER`*: a rule of the same guard
over main Kotlin outside the generated tree (step 4; the generated `NodeKind` enum is the contract's and is exempt by
construction). *A Memories spec never carries `x-veyq-channel`* and *no Memories type has the name of a core one*: the
generator build and a guard (step 5).

**Quarkus specifics, decided.** Indexing: an empty `META-INF/beans.xml` per module (no extra plugin — the Jandex Gradle
plugin would be one more third party to keep alive; `quarkus.index-dependency.*` in the root's properties is the
alternative, rejected because the list lives away from the module and a forgotten entry is silent). Config:
`application*.properties` stay in the root only (a library `application.properties` is a second same-ordinal source,
remembered); a module's `@QuarkusTest`s get the `%test` keys from **one** file — `application-test.properties` moves to
`:core/src/test/resources` and is shared to dependants through the test-fixtures jar's resources (decided in step 8,
R6). Library modules apply the `io.quarkus` plugin only where a `@QuarkusTest` runs (both, now); the root applies it for
the build. Config mapping: `VeyqConfig` stays whole in `:core`, including its `media()` section
(`veyq.media.upload.retention` is read by core code — by `UploadSessionPurge`, which is core's from step 3 on); keys are
untouched by any rename (the mapping is bound by prefix `veyq`, not by package — measured above).

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
| Tests that need both sides (step 8 classifies them by method) | `grep -rlE 'features\.(media\|content\.(item)?favorites)' backend/src/test/kotlin \| grep -vE '/features/(media\|content/favorites\|content/itemfavorites)/'` |
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
| The generic blob half (step 3) | `wc -l backend/src/main/kotlin/com/veyq/app/features/media/*.kt`; `grep -nE '^\s*(private )?fun ' …/MediaHandler.kt …/MediaStore.kt`; callers outside the package: `grep -rnE 'MediaHandler\|MediaStore\|StorageQuota\|UploadSessionPurge\|ChunkFrames' backend/src/main/kotlin \| grep -v '/features/media/'` |
| Scripts and docs that name the moved classes | `grep -lE 'MediaStore\|MediaHandler\|StorageQuota\|UploadSessionPurge\|ChunkFrames\|TestBlob\|features[/.]media' scripts/*.py .github/workflows/*.yml backend/pitest.scope.json` (4 scripts, 0 workflows); `grep -rlE 'features[/.]media\|MediaHandler\|MediaStore\|StorageQuota\b\|UploadSessionPurge' docs` (11 files, among them ADR 0013/0062/0073 and four coverage yamls) |
| Where the node kinds are named (step 4) | `grep -rnE 'NodeEntity\.(MEMORY\|CHAPTER)\|"(MEMORY\|CHAPTER)"' backend/src/main/kotlin`; tests: `grep -rc 'NodeEntity\.\(MEMORY\|CHAPTER\)' backend/src/test/kotlin \| grep -v ':0'`; the generated enum: `grep -rn 'NodeKind' backend/src/main/kotlin` |
| Tests that need an application at runtime | `grep -rlE 'post\("/api/shares"\)\|createShare\(' backend/src/test/kotlin` (share creation, 27 files); the kind/entity/endpoint scans of § State; per-method: the scan scripts (scratchpad, not committed) |
| Blob operations, their schemas and their web callers (steps 3, 5) | the PyYAML closure walk of § State; `grep -rnE 'startBlobUploads\|presignBlobUploadWindows\|presignBlobDownloads' web/src admin-web/src \| grep -v generated`; `grep -n 'blob-uploads-batch\|blob-upload-windows\|blob-downloads-batch' contracts/api/*.yaml` |

## Steps

One commit per step (a step with a mechanical script commits the script's output separately from any hand edit), each
ending with the checks it names; a step that fails one is not done. The permanent checks of **every** step:
`cd backend && ./gradlew check`, `for f in scripts/validate_*.py; do python3 "$f"; done` (python3 is 3.9),
`python3 scripts/validate_guard_gaps.py`, and — from step 5 on, wherever a contract or generated client is touched —
`cd web && npm run check` and `cd admin-web && npm run check`. **A neighbouring CR editing backend files collides with
steps 2–7** (the rename touches every Kotlin file; steps 3 and 4 touch the hot upload path and the node tree): check `ls changes/` and `git status` first; land from the
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
  file importing `features.media` → red (a new `validate_guard_gaps` case; cases 34 and 38 stay until step 8). (4)
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
  (from step 7 on, the root the file sits in); **a `core` migration names no table created by a `memories`
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
  per-module, pin rule), KDoc and prose naming `Vnn` (open question 6), the removed upgrade rehearsal.
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

*One purely mechanical commit, nothing else in it.* The blob and node-kind seams follow in 3–4 and the module split in 6–7; here every file keeps its
production role and moves to its owner's package.

- **The map** (longest prefix wins; `com.veyq.app.` omitted on the left): `core.X` → `com.veyq.core.X`;
  `shared.X` → `com.veyq.core.X`; `features.{account,admin,platform,sharing,spaces,tasks}.X` → `com.veyq.core.<same>.X`;
  `features.content` (the four node files) → `com.veyq.core.content`; `features.media` → `com.veyq.memories.media`;
  `features.content.favorites` → `com.veyq.memories.favorites`; `features.content.itemfavorites` →
  `com.veyq.memories.itemfavorites`; `testing` → `com.veyq.core.testing`. **Left alone in this step:**
  `com.veyq.app.generated.*` (the generated packages cannot be assigned an owner before the contracts split; step 5).
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
  becomes the kernel guard of open question 3: the declared kernel list is generated **once** from the former `shared/`
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

### 3 — The generic blob upload and quota half moves from `memories.media` to `core.blob` (one Gradle module still)

*Why here.* After the rename — a class moves once, between its two final packages (`com.veyq.memories.media` →
`com.veyq.core.blob`), not between today's names and then again — and before the contracts split (which has to know
which operations are core's) and the module cut (which would otherwise leave core's own sharing and tenant features
running on code in a module core cannot see, and the tests of that code uncompilable in core: § State, test
classification). Behaviour-preserving. It is the **hot upload path**, and its races are the ones H2 shows green and
MariaDB red (ADR 0078), so the step carries a **[LIVE]** race run.

- **Split `MediaHandler` and `MediaStore` along the measured lines** (§ State). A new `BlobUploadHandler` in `core.blob`
  takes ≈ 225 lines — `start`, `startForLink`, `reserveAndOpen`, `window`, `windowForScope`, the signing and
  admissibility helpers, `verifyBlob`, `purge`, `download` and the outcome types they return; a new `BlobUploadStore`
  takes ≈ 100 — `blobIdTaken`, `createSession`, `findSession`, `sessionSpent`, `touchWindow`, `persistBlob`,
  `purgeSessionsIdleSince`, `findBlob`, `deleteBlobRow`, the manifest codec. Bodies move **verbatim**; what the split
  forces is visibility (`persistBlob`, `deleteBlobRow`, the codec, `verify` and `purge` become public API of core,
  called by Memories' `attach`, `commitCover`, `purgeMediaRows` and `toRow`), constructor wiring and imports.
  `MediaHandler` and `MediaStore` keep the Memories rest and take the new beans by constructor.
- **Move whole:** `ChunkFrames`, `UploadSessionPurge` (the `@Scheduled(every = "{veyq.media.upload.purge.every}")` key
  and `VeyqConfig.media()` stay as they are — keys are not renamed), `TestBlob` (test tree: to the testing package;
  it joins the fixtures jar at step 8).
- **`StorageQuota`.** Its tenant half (`lockTenant`, `tenantFits`, today delegating to `StorageUsage`) becomes
  `com.veyq.core.quota.StorageQuota`. Its per-link half is re-expressed through the seam **`UploadAdmission`**: core
  sums the *open-session* term of today's SQL (`veyq_upload_session ⨝ veyq_share_identity`, core tables), Memories'
  implementation returns the *blob* term (the `veyq_media`/`veyq_media_derivative` subquery, verbatim), and core
  compares the sum plus the declared sizes with `tenant.shareLinkLimitBytes`. The two terms of the old SQL are
  independent subqueries added together, so the split is exact; **proof:** one new case seeds a link with a medium, a
  derivative and an open session and asserts the sum equals what the pre-step `linkUsage` returned (recorded from the
  untouched tree), beside the existing `ShareLinkStorageLimitSliceTest` and `TenantStorageQuotaSliceTest` link cases.
- **`UploadCompletion`.** The per-item loop of `completeForNode` moves to core as a *dispatcher*: live node
  (`node_not_found`) → the one `UploadCompletion` that `accepts(node.kind)` (none **or several** → `illegal_target`,
  the partition read exactly like `SharingHandler.writesFor`) → its `complete(...)`. Memories' `MediaUploadCompletion`
  holds today's `completeOne` body from the session lookup on — the `boundTo` check, `mediaIdTaken`, the verification,
  `attach` — **and the `MEMORY`/`CHAPTER` allow-list** (`attachesMedia`; its KDoc, which explains why an allow-list and
  not a `FOLDER` deny-list, moves with it), built on core primitives (`findSession`, `verify`, `purge`, `persistBlob`,
  spending the session rows). The default-deny the KDoc describes is now enforced by the *absence* of an acceptor, in
  core. The member `complete` (access check, then the dispatcher) stays in Memories' `MediaHandler`, the operation
  `completeMedia`; `MediaShareGuestWrites.complete` calls the dispatcher with the contributor opener;
  `MediaShareGuestWrites.supports` stays `MEMORY` only. `CompleteItem`, `CompleteItemResult` and `DerivativeDeclare`
  leave `core/blob/Upload.kt` for the new `UploadCompletion.kt` in core (open question 9); `Upload.kt` keeps
  `UploadOpener`, `UploadDeclare` and the start/window item types.
- **HTTP unchanged in this step.** The three blob operations are still on the `media` tag, so `MediaResource` still
  implements them — now by calling `BlobUploadHandler`. Their move to a core `BlobResource` is step 5, where the
  contract and its generated interface move together (a contracts phase cannot end green with only one side moved,
  `changes/README.md`).
- **Test helpers.** The `@TestProfile` that selects `FakePresigningBlobStore` (`MediaBudgetBranchSliceTest.FakePresignProfile`,
  borrowed by `UploadWindowSliceTest` and `ShareUploadWindowSliceTest`) becomes a neutral class in the testing
  package. No test moves modules in this step; the generic ones are named for their step 8 home in the table there.
- **What names the moved classes** (locator: "Scripts and docs that name the moved classes"): the four scripts name
  `MediaStore.purgeMediaRows`, `MediaTrashPurge` and the Memories erasers, all of which stay; the step re-reads each and
  says so. The 11 docs are re-pointed (ADR 0073's `StorageQuota` sentences, the four coverage yamls — a ref to a method
  that changes class is rewritten, none dropped, `validate_coverage` resolving by class stem).
- **Acceptance.** `ChunkFrames`, `UploadSessionPurge` and `StorageQuota` are gone from `memories.media`; `core.blob` and
  `core.quota` import nothing Memories (the step-0 guard sees it); every moved member's text occurs unchanged in the
  pre-step file apart from the lines the split forced (a scratch script, its output in the commit); the set of
  `classname#testname` is unchanged; `race_classes()` unchanged.
- **Proof.** `./gradlew check`; the validators; **[LIVE]** `python3 scripts/run_backend_races_on_mariadb.py` —
  `reserveAndOpen` (the tenant row lock plus `createOrConflict`), `purgeSessionsIdleSince` (lock, then re-check) and
  `attach`/`commitCover` ("spent first") must keep their transaction boundaries: `MediaCreateRaceSliceTest`,
  `PurgeRaceSliceTest`, `TenantStorageQuotaSliceTest.parallelMemberStartsCannotOvershoot`, `GuestWriteRaceSliceTest`.
- **Bite-tests.** (1) *seam absent:* delete `@ApplicationScoped` on `MediaUploadCompletion` in a scratch copy → every
  completion answers `illegal_target` and `MediaSliceTest` goes red. (2) *partition:* a second `UploadCompletion` that
  accepts `MEMORY` → the same refusal, pinned by a new `UploadCompletionPartitionSliceTest` (red without the dispatcher's
  `singleOrNull`; the model is `ShareGuestWritesPartitionSliceTest`). (3) *admission:* make Memories' `UploadAdmission`
  return 0 → `ShareLinkStorageLimitSliceTest` red (a link over its limit is admitted). (4) *the lock:* remove
  `lockTenant` from `reserveAndOpen` in a scratch copy → `parallelMemberStartsCannotOvershoot` red **[LIVE]** (H2 may
  stay green; the commit says which). (5) *moved, not rewritten:* edit one moved line in a scratch copy → the verbatim
  check red. (6) *the direction:* a forged `core/blob` file importing a Memories class → `validate_shared_imports_no_features`
  red.
- **Done when:** the above; the moved-class citations in docs and coverage re-pointed (`validate_doc_citations`,
  `validate_coverage` green).

### 4 — `NodeKindRules`: the `MEMORY`/`CHAPTER` rules leave core

*Why here.* The second seam of the family, independent of step 3 in files, tests and bites; after it hand-written core
code names `FOLDER` only, so the module cut never has to give `:core` a `MEMORY` constant. Behaviour-preserving.

- **Change.** A core interface `NodeKindRules` (`com.veyq.core.node`): `handles(kind): Boolean` and
  `parentAllows(kind, parent: ParentSlot): Boolean`. `NodesHandler.parentAllows` becomes "the one rule that handles the
  kind decides; **none or several → false**" — today's `else -> false`, extended to ambiguity. Core's own
  `FolderNodeRules` handles `FOLDER` (root, or under a `FOLDER`). Memories' `MemoryNodeKindRules` handles `MEMORY` (root
  or under a `FOLDER`) and `CHAPTER` (under a `MEMORY`). Collected with `@All MutableList` (the ArC trap). `ParentSlot`
  is already kind-agnostic (§ State) and travels with the node files. The constants `MEMORY` and `CHAPTER` leave
  `NodeEntity`'s companion for a Memories object; `MediaShareGuestWrites.supports` and the `UploadCompletion` allow-list
  re-point at it.
- **Test ripple — the visible cost.** 174 uses of the constants in 30 test files, a compile-error list and a mechanical
  edit. Core fixtures take an explicit kind: `seedNode` stops defaulting to `MEMORY` (in core it defaults to `FOLDER`;
  Memories' tests use a Memories-side `seedMemory`). **The rule: a test is edited by its kind literal and nothing
  else; a test whose meaning is a Memories rule is not rewritten onto `FOLDER` — it is a `:apps:memories` test**
  (step 8, the 22 C-flagged methods).
- **New core tests** (the fail-closed pins that can run core-alone): a `FOLDER` under a `FOLDER` lands and a `FOLDER`
  under a missing parent is `illegal_structure` (HTTP), and a handler-level create with a forged kind literal
  (`"NOT_A_KIND"`) is refused — `NodesHandler.create` takes `kind: String`, so the contract's closed enum does not
  shield it. The existing `MEMORY`/`CHAPTER` tests (`NodesSliceTest`'s tree, chapter-at-root and move cases) are
  unchanged.
- **Guard.** A rule in `validate_shared_imports_no_features.py` (renamed at step 2): a hand-written main file of core
  (outside the generated tree) contains neither `MEMORY` nor `CHAPTER` outside comments. Run it on the untouched tree
  first: red on exactly the four sites of § State. The generated `NodeKind` enum still lists both — that is the
  contract's, closed until the open "node `kind`" row; ADR 0079 says so at step 8.
- **Acceptance.** The four sites are gone from core; `grep -rnE 'NodeEntity\.(MEMORY|CHAPTER)' backend/src/main` finds
  Memories' files only; `git diff --stat -- contracts` is empty; `NodesHandler` answers identically for the three kinds
  (the pre/post test-name sets agree, plus the new core tests, accounted).
- **Proof.** `./gradlew check`; the validators; **[LIVE]** the race suite (`create` and `update` are the tree's lock
  path, ADR 0078, though no boundary moves).
- **Bite-tests.** (1) *live defect:* the new guard rule on the untouched tree → red on the sites. (2) delete
  `@ApplicationScoped` on `MemoryNodeKindRules` in a scratch copy → creating a `MEMORY` is `illegal_structure`,
  `NodesSliceTest` red. (3) a second handler of `MEMORY` → refused (a partition pin, red without the `none or several`
  clause). (4) a `FolderNodeRules.handles` that answers true for every kind → the forged-kind test red (lever: the
  fail-closed default). (5) a forged core file naming `NodeEntity.MEMORY` → the guard red; the same line in a Memories
  file → green.
- **Done when:** the above.

### 5 — Split the contracts and the generated API by owner (one Gradle module still)

- **Change.**
  - `content.openapi.yaml` keeps the six node operations and the node schemas; the favorites, item-favorites and
    own-item-favorites tags (five operations, five schemas) move to the new `contracts/api/memories/favorites.openapi.yaml`;
    `media.openapi.yaml` moves to `contracts/api/memories/media.openapi.yaml` **without the three generic blob
    operations**, which become core's new `contracts/api/blob.openapi.yaml` (tag `blob`): `startBlobUploads`,
    `presignBlobUploadWindows`, `presignBlobDownloads` and the 13 schemas only they reach, plus `BlobRole` (moved whole,
    **still the closed four-value enum** — an open prerequisite, Goal) and `PresignedUrl` (to `_common`, below). One tag is
    one generated interface with one root `@Path`: the three paths differ at the segment after `{scopeId}`, so the root is
    `/api/scopes/{scopeId}` like `ContentApi`'s and `MediaApi`'s (measured, § State) — the step re-measures it on the
    real spec. Descriptions in the moved schemas that name "the Memory's `K_mem`" are reworded to "the owner object's
    key (today Memories' `K_mem`, ADR 0012)" — description-only, stripped by the wire diff, and the one place a core spec
    would otherwise teach the wrong layering. `contracts/api/blob.openapi.yaml` is read by every spec-reading guard
    through the helper below like any other core spec.
  - **The resource moves with the interface, in the same step** (a contracts phase that reshapes a generated interface
    cannot end green with only the spec moved, `changes/README.md`): a core `BlobResource` implements the generated
    `BlobApi`, its three methods moved verbatim from `MediaResource` (which stops implementing them), calling
    `BlobUploadHandler`; it carries `@Authenticated` and the same `@RateLimitExempt` reason as `MediaResource` (the
    amplification argument is about presign minting, which is exactly what moved) and resolves the signing domain through
    `RequestTenant` as before. A resource not registered answers 404 for three routes with no error anywhere
    (`OperatorBlindProbeTest` and `AdminChannelPostureSweepTest` derive their sets from the generated interfaces, which
    is why they are the proof).
  - `_common.openapi.yaml` gains the three schemas it does not yet hold — `PresignedUrl`, `NodeKind`, `MasterKeyWrap` —
    and every spec that defined them aliases the common one like `ErrorResponse` already does. **`MasterKeyWrap` takes the
    annotated variant** (the one the backend gets today); the un-annotated `account` copy was a latent defect (open
    question 5 states the description drift). Memories specs reference `../_common.openapi.yaml#…`.
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
  - Web: `generate:api` (`web/package.json`) gains two calls (`favorites.openapi.yaml` → `src/api/generated/favorites`,
    `blob.openapi.yaml` → `src/api/generated/blob`) and drops nothing; `api/client.ts` and the two favorites tests import
    the favorites symbols from `./generated/favorites`; `api/client.ts` builds a `BlobApi` (`blobApi()`), the three call
    sites in `features/memory/media.ts` (lines 764, 778, 1206) use it, and the five test files that fake or reach the three
    operations (`galleryFakeStore.ts`, `media.test.ts`, `shareUploader.test.ts`, `MediaLightbox.test.ts`,
    `MemoryDetail.test.ts`) fake `BlobApi` for them. `admin-web` is unchanged (it generates four core specs). `validate_build_input_pins`
    and `verify_openapi_generator_jar` keep holding the generator version.
- **Proof — backend.** (i) *Generated sources:* the old `build/generated/openapi-{api,models}` tree, mapped through the
  owner table, equals the new two trees file by file (generated code carries no descriptions — measured —
  so this is exact, including `MasterKeyWrap`'s annotations), **except `MediaApi`, whose three blob methods reappear
  unchanged in `BlobApi` and are compared per method**. (ii) *Wire:* a script resolves every spec of the old and
  the new contract (following `$ref`s) and compares, per `(method, path)`: `operationId`, tags (**identical except the three blob operations, which move `media` → `blob`, declared in the script**), parameters, request
  body, every response, with `description`/`example`/`summary` stripped → identical sets; the set of schemas reachable
  from operations is identical; this is the "paths and wire unchanged" statement as a command. (iii) `./gradlew check`
  (`OperatorBlindProbeTest` and `AdminChannelPostureSweepTest` derive their sets from the generated interfaces, so an
  unregistered resource shows as 404s).
- **Proof — web.** `npm run generate:api` before and after, then: every pre-existing generated file is byte-identical
  in the new tree **modulo `/** … */` blocks**, except the two barrel `index.ts` export lists; the favorites files
  moved whole from `content/` to `favorites/` and the blob files whole from `media/` to `blob/` (`MediaApi.ts` is the one
  file whose body changes by construction); the only JSDoc differences are in `NodeKind`, `PresignedUrl`,
  `MasterKeyWrap` files and the reworded blob descriptions. `npm run check` in `web` and `admin-web`.
- **Bite-tests.** (1) change a `required` list in the scratch Memories spec → the wire diff red (lever). (2) drop the
  `addSchemaMapping` in a scratch copy → a guard comparing the model names of the two generated trees red (a Memories
  type named like a core one is a duplicated definition). (3) put `x-veyq-channel: admin` on a Memories operation →
  the build fails. (4) move a spec into `contracts/api/memories/` without the recursive helper → the guard that reads
  it reports "found none" or the changed set, not green. (5) edit the favorites spec in scratch → the web diff
  non-empty. (6) leave one blob operation on the `media` tag in a scratch copy → the generated `MediaApi` keeps the method
  and `MediaResource` (its blob methods gone) fails to compile: the contract and the resource are one unit. (7) drop
  `BlobResource`'s `@Authenticated` in a scratch copy → `OperatorBlindProbeTest` red.
- **Done when:** the above; `backend/CLAUDE.md` § Conventions names the two generation task paths; `contracts/` README or
  `_common` header states the layout (including that `blob.openapi.yaml` is core's) and that a Memories spec may reference core's `_common`, never the reverse.

### 6 — Gradle skeleton and `:core`

*The big move; one commit for the directory moves, a second for build files.*

- **Change.** `settings.gradle.kts` includes `:core`; `backend/gradle/module.gradle.kts` carries what every module repeats
  (`group = "com.veyq"`, `base.archivesName` `veyq-<module>` — decision 7: the intermediate jars in `quarkus-app/lib/main` are named, not deployed — toolchain 21, Kotlin + all-open for `@Entity`/`@MappedSuperclass`/`@ApplicationScoped`, BOM, `javaParameters`,
  `useJUnitPlatform`, `maxHeapSize = "2g"` and the logging manager property from the root's test config). Plugin
  versions stay declared once at the root. `git mv` all `com.veyq.core.*` main code, the core specs' generation
  (`openapi.gradle.kts` parametrised: spec set, packages, schema mappings), `TrimmingStringDeserializer`, the core
  baseline migration, the Quarkus-free unit tests of that code (**including the three tests of `internal` members**,
  which stop compiling across a module boundary, and the PIT scope's policy tests) into `backend/core` — the generic blob upload and quota (steps 3, 5), the three seams and their core halves, `ChunkFramesTest` and the generic tests of § State's table are core's from here (the `@QuarkusTest`s among them move at step 8). `:core` gets
  `beans.xml`, the `io.quarkus` plugin (required for a library `@QuarkusTest`), `java-test-fixtures`, the PIT plugin and
  `pitest.scope.json` (still readable by `scripts/`). The Memories packages, their generation and **every
  `@QuarkusTest` stay in the root** for now; the root depends on `:core`; the direction is already right. The
  Dockerfile's `backend-build-base` stage copies `backend/core/build.gradle.kts` (the warm-up configures every
  included project; a missing directory is an error — remembered, measured when hit) and the second `COPY` adds
  `backend/core/src`. The Memories baseline migration stays in the root (one directory, one classpath path) until step 7.
- **Acceptance.** `git diff -M --stat` shows renames only for `.kt`/`.sql`/specs; no Kotlin *content* change beyond what
  the build forces (the module block, nothing in sources). `race_classes()` unchanged. Every path in
  `mutation-backend.yml`, `pitest.scope.json`, the mutation scripts, `validate_guard_gaps` forgeries and doc citations
  resolves. `scripts/run_backend_races_on_mariadb.py` addresses the root project explicitly (a bare `test --tests X`
  fails in every project without a match — spike).
- **Proof.** `./gradlew check`; `./gradlew pitest` (the score is the same; the report path moves to
  `backend/core/build/reports/pitest`, read by `mutation_meta_check_backend.py` and its selftest); the cold-home
  verification run `GRADLE_USER_HOME=$(mktemp -d) ./gradlew help check quarkusBuild pitest` **without** the write flag
  is green (the dependency set did not grow; any entry it asks for is a finding — read the diff, `backend/CLAUDE.md`);
  `quarkusBuild` and `ls build/quarkus-app/lib/main` lists the core jar under its set name (read and recorded here; **one** `quarkus-app`, no second artifact — `ls build/quarkus-app` and the Dockerfile's `COPY` lines are unchanged); the Dockerfile warm-up without Docker: copy exactly the files its
  `COPY` lines name into a temp dir and run `./gradlew --offline help`. **[LIVE]** `docker build .`; the race suite
  (the transactional code now sits in a jar — an interceptor binding that silently stops applying because the module
  is unindexed is exactly what H2 cannot show, ADR 0078); a fresh-database boot of the fast-jar
  (`Successfully applied 2 migrations`). Record `time ./gradlew check` (R11).
- **Bite-tests.** (1) delete `core/…/beans.xml` → the root's slice tests red at build (unsatisfied dependency), and the
  quieter case — an `@All` list that shrinks — is caught by the pinned-set tests. (2) a one-character edit of the core
  baseline → `validate_migration_versions` red. (3) a `@Transactional` method in an unindexed module — the one case
  that needs the live race run; the commit says so rather than claim H2 shows it. (4) re-point a script constant at
  the old path → "found none" red. (5) an `internal` test left in the root → compile error naming it. (6) remove `archivesName` in a scratch copy → `ls quarkus-app/lib/main` shows an `-unspecified` name (the step-8 guard rule is the permanent form).
- **Done when:** the above; `backend/CLAUDE.md` § Prove your work lists the `:core:test` / `:test` split and the
  `--tests` filter rule.

### 7 — `:apps:memories`

- **Change.** `git mv` `com.veyq.memories.*`, the Memories specs' generation, the Memories baseline migration, the
  Quarkus-free tests of that code into `backend/apps/memories` (`ChunkFramesTest` is core's since step 3); `beans.xml` (the module generates
  JAX-RS interfaces — without it the resources 404 silently, spike); depends on `:core`. The root now holds no main
  Kotlin. The Dockerfile copies the new module's files (rule (e) of step 8 will hold it).
- **Acceptance.** `:core` has no `project(":apps:…")` line and `./gradlew :core:compileKotlin` is green with
  `:apps:memories` absent from the build — the proof that the one edge really was one edge. The `@All`/`Instance<>`
  consumers collect the same set (`ScopeEraserOrderSliceTest`, `NodeSubtreeEraserOrderSliceTest`,
  `GuestIdentityUsageTest` pin it by name and phase). The Memories implementations of the three seams (`MediaUploadCompletion`, `MemoryNodeKindRules`, the `UploadAdmission` and `MediaShareGuestWrites` beans) are in the module and collected (`@All`) from it. The migration guard now attributes `memories_` files to the
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

### 8 — Test separation, the enforcement Gradle cannot do, the old guards' hand-over, close

**Tests and enforcement**

- **Test homes (decision 5).** Each module owns the `@QuarkusTest`s of its code; every module's tests run with core on
  the classpath; helpers both sides use go to `java-test-fixtures` on `:core`. The proposal is by *measured marker*
  (scan, § State: 1071 methods), confirmed class by class when the step compiles — a class's home is decided by what it
  imports and calls, not by the table:

  | Home | Tests | Why |
  |---|---|---|
  | `:core` | the **786 unflagged methods** — 105 files whole and the unflagged part of 20 split files — among them `ScopesSliceTest` (55 of 56; one method creates a share), `TenantStorageSliceTest` (5, the one former "core feature needing Memories" that now runs core-alone), `ChunkFramesTest` (5), `StoreOriginPresignSliceTest` (3) and the **43 generic methods of `features/media`** (§ State: `TenantStorageQuotaSliceTest` 11, `UploadWindowSliceTest` 5, `UploadSessionPurgeSliceTest` 4, `MediaPresignDomainSliceTest` 3, `MediaSliceTest` 6, `PurgeRaceSliceTest` 3, `MediaCreateRaceSliceTest` 2, `ObjectKeyCarriesNoStructureSliceTest` 1); the 46 Quarkus-free unit tests of core code; the new fail-closed pins of steps 3 and 4 | they boot core alone: blob start/window/download/quota/purge are core's since step 3, and a node can be seeded under any kind string |
  | `:apps:memories` — its own | the Memories-only classes of `features/media` (`BlobBatchE2ETest`, `MediaBucketsSliceTest`, `MediaBudgetBranchSliceTest`, `MediaRowLockRaceSliceTest`, `MediaTrashSliceTest`, `NodeCoverSliceTest`) and the Memories halves of the mixed ones (90 methods in all), favorites (5), item favorites (11) | Memories code |
  | `:apps:memories` — core behaviour that needs an application at runtime | the **149 methods that create a share** (27 files; question 8), the **22 that create a `MEMORY`/`CHAPTER` over HTTP** (`NodesSliceTest` 8, `NodeCreateRaceSliceTest` 3, `NodeTrashSliceTest` 9, …), the methods that seed `MediaEntity`/`BucketEntity`/`ItemFavorites…` (`NodeSubtreeEraserOrderSliceTest`, `ScopeEraserOrderSliceTest`, `RemovalRaceSliceTest`'s item-favorites case, …) — **285 flagged methods in all, 106 of them in Memories' own packages** | the Memories runtime is core plus Memories; nothing is rewritten |
  | split | **20 files** hold methods of both homes (§ State). A class that splits becomes two classes: the half that keeps the old file name is the one with most methods; the other gets a new class name. Coverage refs resolve by class stem and method (`validate_coverage`), so each moved method's ref in `docs/coverage` is rewritten — a dead ref fails the guard, none can be dropped silently. **Account per assertion, not per count** (`changes/README.md`); the pre/post `classname#testname` sets are compared through the rename map plus the declared splits | mixed within a file |
  | root | `OperatorBlindProbeTest` (probes every contract operation of both modules; it also creates a share and touches media endpoints) and one new composition test (every module's beans present; the `@All` collections hold the union) | genuinely whole-app |

  The three CDI fake beans (`RecordingBlobStore`, `RefusingStoreReclaimStore`, `FakePresigningBlobStore`), `TestBlob`
  and the neutral fake-presign `@TestProfile` move to `:core`'s test fixtures **with a `META-INF/beans.xml`** in the
  fixtures resources (R14, spiked at the start of the step); the `%test` keys: `application-test.properties` lives once,
  in `:core`'s test fixtures resources, read by the dependants' tests (R6); KDoc links that name a moved test class are
  rewritten (they name classes in both modules).
- **Cost, stated.** Today: one default Quarkus boot plus about 25 profile boots in one JVM. After: three default boots
  (core, Memories, root) — the profile boots are unchanged in number, they move with their test — i.e. **+2 default
  boots, ≈ +20 s** at the measured 12 s for a 10-test class including its boot, **plus the per-`test`-task overhead
  measured at ~90 s that I could not decompose** (R11): if it is fixed per task, `check` grows by up to ~3 minutes; if it
  scales with discovery it shrinks. Decide with the number from the step, not with this paragraph. Two things cut it:
  `org.gradle.parallel=true` (the module test tasks do not share ports — `test-port=0` — nor an H2 database, each
  in-memory per JVM; unmeasured under load) and Gradle's incremental behaviour (a Memories-only edit no longer reruns
  core's tests, measured only by reasoning). With three quarters of the methods in `:core`, the incremental half is
  the larger saving.
- **New guard `validate_backend_module_graph.py`** (buildless; derives from `settings.gradle.kts` and the build files):
  (a) the included projects equal the directories holding a `build.gradle.kts`; (b) project-dependency edges are a
  subset of {`apps:*` → `core`} — in particular **no `apps:*` → `apps:*`** and no `core` → anything; (c) every module has
  `src/main/resources/META-INF/beans.xml` (and a test-fixtures one where fixtures hold beans); (d) no package is
  declared under two modules (from the files' `package` lines — nothing to rot), generated packages included (they are
  declared by the Gradle config: the guard reads the packages out of the generation tasks); (e) the Dockerfile's
  `backend-build-base` stage copies every included module's `build.gradle.kts`; (f) every module's jar is named: a group
  and a `base.archivesName` are set once in `module.gradle.kts`, and no module overrides them (the `core-unspecified.jar`
  case). Docstring fields as the guards before it.
- **Core-alone boot test** in `:core` (`@QuarkusTest`, core config only, no application): asserts the `ScopeEraser` /
  `NodeSubtreeEraser` / `SharedSubtreeContributor` / `MembershipEraser` lists hold exactly core's own implementations
  (with the control that core's are present); `ShareGuestWrites`, `UploadCompletion` and `UploadAdmission` have **no**
  implementation and `NodeKindRules` holds exactly core's `FOLDER` rule; a `FOLDER` can be created and a `MEMORY` is
  `illegal_structure`; a share is `illegal_scope`; a blob upload starts, windows and downloads (a seeded blob) through
  core's handler, and a completion is `illegal_target` for every kind; an unauthenticated core route answers. *Why it is
  worth a test:* the compiler forbids the import; this catches a **runtime** coupling — a core bean injecting an
  interface only Memories implements, or a config key only an application defines.
- **Hand-over of the old guards.** Delete the Memories half of `validate_shared_imports_no_features.py` (the module
  boundary does it); keep the kernel half and the step-4 `MEMORY`/`CHAPTER` rule; `validate_guard_gaps` cases 34 and 38
  (core importing media) are rewritten against what now rejects them (the module guard) or removed with the reason
  stated in the case table — never left pinning a guard that no longer checks it. Finish
  `validate_migration_versions.py`: the owning module comes from the path.
- **Acceptance.** The guards are green on the final tree and their docstrings say what each rule bites. The per-module test
  inventories are disjoint and their union equals the pre-step set of `classname#testname` (modulo the split classes,
  accounted per assertion).
- **Bite-tests.** (1) a forged `backend/apps/other/build.gradle.kts` with `implementation(project(":apps:memories"))`
  and an `include` → rule (b) red (lever: a forged second application — the only way to exercise app→app before a real
  one exists). (2) delete a `beans.xml` → (c) red. (3) a `:core` file declaring a `com.veyq.memories.…` package → (d)
  red. (4) drop a module's `COPY` from the Dockerfile → (e) red. (5) core-alone: add `@Inject lateinit var x:
  ShareGuestWrites` (non-`Instance`) to a core bean in a scratch copy → the core-alone boot fails; remove → green.
  (6) move a Memories-needing test (a share-creating one) back into `:core` → it fails at runtime with `illegal_scope`
  (the module cannot see Memories); move one that imports a Memories class → `:core:compileTestKotlin` fails. (7)
  remove `archivesName` from `module.gradle.kts` in a scratch copy → (f) red.

**Closing the series**

- **Docs (edit in place, no addenda).** ADR 0079: Decision 10 (modules, one deployable), Consequences — delete "Not done:
  the Gradle module split" and the `ScopesStore` remainder, state the module topology, the package naming, the migration
  versioning, the three seams, and **what core still knows**: the closed generated `NodeKind` enum, the Memories-flavoured
  `veyq_node` columns, `ShareGuestWrites.supports` as the gate on share creation, the media-shaped `CompleteItem` and the
  share contract's two `/media` operations, the closed `BlobRole` enum — and the open prerequisites (the generic
  attachment table, the seal purpose). `CLAUDE.md` § Repository shape ("inside the one Gradle module",
  `com.veyq.app.core` guard sentence); `backend/CLAUDE.md` (module layout, which tests run where, the `--tests` rule,
  migration versioning, the pruned-verification recipe still names `help check quarkusBuild pitest`); `docs/apps/README.md`
  and `docs/apps/memories.md` ("Separated only in part"); `docs/apps/files.md` (the blob-role question now reads against
  core's neutral upload code); the developer styleguide §§ on `shared/` and migrations; `infra/README.md:63`
  (`:backend:test`); `docs/architecture` package citations; `changes/README.md` if its examples name moved paths. Inbox
  #324: replace the "Gradle module split (phase 2) … not written yet" outcome sentence; delete the row only if nothing is
  left (the node `kind`, `wiki/`, the open prerequisites remain, so it stays); the notes on #325–#327 stay until their
  applications are refined.
- **Delete** `scripts/rename_packages.py` and the one-off scratch scripts (scaffolding), after what they taught moved to
  where it binds.
- **Retrospective** written; the CR deleted after what it taught moved to where it binds (`changes/README.md`).

## Risks

| # | Risk | Where it bites | Held by |
|---|---|---|---|
| R1 | A module not indexed: beans, entities, `@ConfigMapping`s, `@Path` interfaces, interceptor bindings vanish. Injected beans fail loudly; **`@All` lists shrink and unindexed `@Path` interfaces 404 silently** (spike) | steps 6–8 | `beans.xml` per module + guard rule (c); the pinned-set slice tests; `OperatorBlindProbeTest`; the live race run for the interceptor case |
| R2 | The baseline differs from the history's end state in a column, type, default or index → runtime SQL errors, invisible to Hibernate (`strategy=none`) and to H2-only checks | step 1 | the schema-equality proof (H2 + **[LIVE]** MariaDB); bite (1) |
| R3 | Guards that read a shrunken tree after a move and stay green — now also **specs** (`contracts/api/memories/` is invisible to a non-recursive glob) and **migrations** (a second root) | steps 2–7 | the two helpers, per-guard "found none" lines, the second-root `validate_guard_gaps` cases |
| R4 | `./gradlew test --tests X` fails per project with no match; `run_backend_races_on_mariadb.py` is one such caller | step 6 | the script addresses the project explicitly and prints its class list |
| R5 | Kotlin `internal` stops at a module: 3 members, tests must move with them | step 6 | compile errors name them |
| R6 | Two `application-test.properties` on one test classpath, same config ordinal (remembered) | step 8 | one home (`:core` fixtures resources), decided in the step |
| R7 | The Dockerfile warm-up copies named files only; a new module without its `COPY` breaks the *release* build and nothing in `./gradlew check` shows it | steps 6–8 | the temp-dir warm-up each step; rule (e); **[LIVE]** `docker build .` |
| R8 | `quarkusDev` with workspace modules: hot reload of `:core`/`:apps:memories` classes, and the IntelliJ `Run Backend` configuration — **not measured** (running a dev server was out of scope; and a parallel `quarkusDev` in this tree would corrupt the owner's) | step 7 / close | **[LIVE]** the owner starts `Run Backend` once; broken reload across modules is a finding, not a blocker for the image |
| R9 | PIT across modules: `info.solidsoft.pitest` in `:core`, report path and trigger paths move | step 6 | `./gradlew pitest`, `mutation_meta_check_backend.py`, `validate_mutation_trigger_paths.py` |
| R10 | Dependency verification: new projects add configurations that resolve coordinates the single project never did (test fixtures, library `io.quarkus`) | steps 6–8 | the cold-home recipe without the write flag |
| R11 | Build time. Measured: one `:test` invocation costs ~90 s beyond its test (neither boot nor compile); unknown whether per task or per class discovered; with three test tasks that is the largest unmeasured number in the CR | steps 6–8 | the baseline recorded before step 0; `time ./gradlew check` after steps 6, 7, 8; if cold `check` regresses by more than a minute, say so before the next step; `org.gradle.parallel` as the lever |
| R12 | A parallel session edits a file this series moves (the rename touches all 365); the `--no-ff` merge is blocked by a neighbour's uncommitted edit (`memory`) | steps 2–7 | check `git status`/`ls changes/` first; wait, never clear it; the script's `--check` re-derives from a **hash**, not `HEAD~` |
| R13 | Dependabot's `gradle` entry points at `/backend`; whether it follows `include`d subprojects is **not measured** (no remote, remembered: it does) | close | note in the retro; a `directories:` list if it does not |
| R14 | Test-fixture beans (three CDI fakes) in a `java-test-fixtures` jar are not discovered by a dependant's `@QuarkusTest` without an index | step 8 | spike first; `beans.xml` in the fixtures resources; the bite: delete it → the Memories tests that need the fakes go red |
| R15 | The rename changes logger categories (`Logger.getLogger(X::class.java)` in 18 classes): an operator filtering by `com.veyq.app.…` stops matching. Nothing in the repo does | step 2 | stated in the commit and in `infra/README.md` if it names log filtering (`grep -rn 'log' infra/README.md` at the step) |
| R16 | The flat `core` merge hides the kernel/slice layering inside one package name | step 2 | the declared kernel list and its bite (open question 3) |
| R17 | The blob split touches the hot upload path: `reserveAndOpen`'s tenant lock with `createOrConflict`, `purgeSessionsIdleSince`'s lock-then-recheck, the "spent first" session deletes in `attach`/`commitCover` — all races H2 shows green and MariaDB red (ADR 0078); a moved `@Transactional` that silently stops applying is invisible to the H2 suite | step 3 (and 6–7, where the code lands in a jar) | **[LIVE]** race suite at steps 3, 6 and 7; the "moved, not rewritten" verbatim check; bite (4) |
| R18 | A seam whose collection silently shrinks: `@All` lists of `UploadCompletion`, `NodeKindRules`, `UploadAdmission` lose their Memories implementation if the module is unindexed — uploads refused (`illegal_target`), every `MEMORY` create `illegal_structure`, link usage 0 (a limit that admits everything). The first two fail closed and loudly; the third **fails open** | steps 3, 4, 7 | the partition pins; the core-alone test (core's own list is exactly `FolderNodeRules`); `ShareLinkStorageLimitSliceTest` with bite (3) of step 3 |
| R19 | Core keeps media-shaped words: `CompleteItem` (`mediaId`, `derivatives`, `wrappedExif`) in `UploadCompletion.kt`, the share contract's two `/media` operations, `ShareGuestWrites.complete`, the closed `BlobRole` enum in core's `blob.openapi.yaml` | steps 3, 5 | open question 9 (recommendation: keep, record); ADR 0079 Consequences "what core still knows" at step 8; the seal-purpose prerequisite (Goal) |
| R20 | A class that splits across modules breaks `docs/coverage` refs (resolved by class stem + method) and KDoc links; 20 files split, 43 generic methods of `features/media` alone | step 8 | `validate_coverage` fails on a dead ref; the per-assertion accounting; split classes get new names, never a silent copy |
| R21 | The `MEMORY` default of `seedNode` and 174 uses of the constants: re-basing a Memories-rule test onto `FOLDER` to keep it in core would **weaken** it | step 4, 8 | the stated rule (a test is edited by its kind literal only; Memories-rule tests go to `:apps:memories`); the reviewer diff of the 30 files |
| R22 | A runtime of core alone refuses every share (`ShareGuestWrites.supports`): share-policy tests lose the "core-alone" label (149 methods). If the owner later wants share policy proven without Memories, that needs a fake kind and re-based fixtures | step 8 | open question 8; the table in step 8 names them |

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
- **One image, one jar.** **[LIVE]** `docker build .` green; `ls build/quarkus-app` shows the one fast-jar, the modules' jars inside `lib/main` under their `veyq-…` names and no other artifact; **[LIVE]** `python3 scripts/run_web_e2e.py` green against the final
  fast-jar; `infra/` and `charts/` untouched (`git diff main --stat -- infra charts`); web/admin-web `npm run check` green
  and the generated-client diff as step 5 states it.
- **The seams exist and are bitten.** `UploadAdmission`, `UploadCompletion`, `NodeKindRules` each have a core pin (the partition, the fail-closed default) and a Memories implementation; hand-written core code names `FOLDER` only (the guard rule of step 4 is green); the generic blob upload runs in `:core` alone (the core-alone test uploads, windows and downloads a blob).
- **Independence is demonstrated, not asserted.** `:core:compileKotlin` and the core-alone boot test are green with
  `:apps:memories` absent from the build (`./gradlew :core:check`).
- **Baseline commands, run before the first edit:** § Steps ("Baselines recorded before the first edit").
- **Cost recorded.** `time ./gradlew check` before and after steps 6, 7, 8 in the commit messages; the decomposition of
  the ~90 s per-task overhead, or the statement that it was not decomposed.
- **Trust chain.** The whole glob green on the merged tree. Named: `validate_guard_gaps`, `validate_shared_imports_no_features`,
  `validate_scope_erasure_arity`, `validate_node_subtree_erasure`, `validate_blob_reference_arity`,
  `validate_store_reclaim_arity`, `validate_coverage`, `validate_doc_citations`, `validate_mutation_trigger_paths`,
  `validate_build_input_pins`, `validate_contracts_common`, `validate_changes`, plus the two new guards (and the `MEMORY`/`CHAPTER` rule of the kernel guard).
- **Coverage.** No acceptance criterion changes (`satisfies: []`); every coverage ref to a moved test still resolves
  by class stem (test class names do not change); `validate_coverage` green and its backend scan covers every module's
  test root.
- **Docs.** Step 8's closing list, each edited in place; the inbox row updated; no doc cites a `Vnn` migration that no longer
  exists (open question 6).
- **The open prerequisites are written where the next application finds them:** the generic attachment table, the cover/revision generalisation and the seal-purpose decision are in ADR 0079 Consequences and on inbox #325–#327 (and #324 until it is deleted).
- **Retrospective** below filled in; the CR deleted after what it taught moved to where it binds.

## Retrospective

_(filled in at close)_
