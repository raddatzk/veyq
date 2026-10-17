# backend (Quarkus / Kotlin)

Operator-blind API service. Loaded in addition to the root `CLAUDE.md` when working here.

## Prove your work
- `./gradlew check` (compile + slice tests) — runs on **in-memory H2** (`%test`, hermetic + fast). Run from
  `backend/`, it checks every project: `:core` (`backend/core`, the platform — its tests run as `:core:test`),
  `:apps:memories` (`backend/apps/memories`, the Memories application; its tests run as `:apps:memories:test`),
  `:apps:depot` (`backend/apps/depot`, the Depot application — the `FILE` and `DEPOT_DROPBOX` kinds, a file's blob for members and guests, the drop box; its tests boot core plus
  Depot and run as `:apps:depot:test`), the root (`:test`, the composition — no main code — and only the whole-app tests: `OperatorBlindProbeTest`, the posture
  and entity sweeps, `ModuleCompositionTest`). A new
  application is one more `:apps:<name>`; what a module must have (an `include`, a `beans.xml`, the `veyq.module` plugin,
  its Dockerfile `COPY`s) and which edges it may declare (`:core`, and `testFixtures(project(":core"))` over a test
  configuration — never another application) is `scripts/validate_backend_module_graph.py`'s, its docstring the list.
  **What no guard derives, a new application adds by hand**, and forgetting it turns nothing red: its node kinds and
  blob roles to `validate_kernel_imports_no_slices.py`'s lists (and its web kinds to
  `validate_web_core_imports_no_applications.py`'s `KIND`), and its seam anchors to `ModuleCompositionTest` — Depot's tokens
  were missing from the kind lists (all of them on the backend, `DEPOT_DROPBOX` on the web) until its CR closed. Its
  generated API is **not** on that list: `OperatorBlindProbeTest` gets the packages from the root build, which hands
  over `SpecSets`' own derivation, and reads its classpath against them both ways (inbox #368) — so a new
  application's operations are red there, unclassified, the day its spec lands. `:core`'s tests boot core
  with **no** application (`CoreAloneSliceTest`). **A test
  lives where what it exercises runs:** in `:core` if it passes with core alone, in `:apps:memories` if it needs
  Memories at runtime — every share of a `MEMORY` or a `CHAPTER` (core alone shares only a `FOLDER`, `ShareRoots`), every
  `MEMORY`/`CHAPTER` placement, a seam's registered partition — even when the code it exercises is core's; its
  package is then the application's (`me.raddatz.veyq.memories.sharing`, never `…core.sharing`:
  `validate_backend_module_graph.py` rule (d)). **A `--tests`
  filter must name the project that holds the class** — `./gradlew :core:test --tests '*SessionPurgeTest'`,
  `./gradlew :apps:memories:test --tests '*ShareSliceTest'`: a bare `test --tests X` fails "No tests found" in every
  project without a match. **Each project with `@QuarkusTest`s holds
  its own `%test` keys** in `src/test/resources/application-test.properties` beside a comment-only
  `application.properties` anchor (a profile file is read only beside one); the base config is `:core`'s
  `src/main/resources/application.properties`. **A new `@TestProfile` is one more Quarkus boot in its project's one
  test JVM (2 GB heap, `veyq.module`), and `:core`'s is near the ceiling:** two new profiles took `:core:test` to
  `OutOfMemoryError: Java heap space` late in the suite, twice, while the tests themselves were green (2026-10-17,
  cr-client-port-sources); one fitted. Share a profile class across the classes that need the same keys, and reach a
  variant through a test-scope seam rather than a profile where the config mapping is proven elsewhere. Shared test helpers live in **`:core`'s test fixtures**
  (`core/src/testFixtures`, `java-test-fixtures`), consumed as `testImplementation(testFixtures(project(":core")))`:
  Quarkus loads them as a workspace module of the application under test, so a seed that persists entities, a
  helper on `Arc.container()` and the CDI fakes all work from there in `:core`'s, `:apps:memories`' and the root's
  tests (measured 2026-10-05, inbox #333) — as long as no jar is renamed (`archivesName`, `buildSrc`'s `veyq.module`
  says why). The fixtures need their own `META-INF/beans.xml`, carry no config file (`%test` keys go in a module's
  `src/test/resources`), and never depend on an application. **An application's helpers another project's tests
  need** go in that application's own fixtures, never core's: `:apps:memories`' `src/testFixtures` holds
  `MemoriesWorldContent`, its content in core's `ScopeAccessWorld` (a `WorldContent` CDI bean, `@Unremovable`, found
  through `Arc` in whatever application is under test), consumed by the root as
  `testImplementation(testFixtures(project(":apps:memories")))` (inbox #335). So core's world builds with core alone,
  and a second application covers its routes in the gate probes by contributing a `WorldContent` the same way.
- **Run the app (`quarkusDev`) on a persistent MariaDB** (`%dev`): `docker compose -f infra/docker-compose.yml
  up -d` starts it; data survives restarts (Flyway migrates once, then "up to date"). `docker compose -f
  infra/docker-compose.yml down -v` wipes it for a fresh instance. The H2 fallback is only the test default now.
- **A write of node or attachment rows bumps the scope's tree revision** (`NodesStore.bumpTree`, [ADR 0081](../docs/decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)) as its last
  statement, in the same transaction and **under the scope lock it already holds** — that is what keeps the first-bump
  insert race-free and the revision row a leaf (a bump after a node row, with no scope lock, is the lock-order inversion
  `NodesStore.applyUpdate` documents). It is the listing's `ETag`, so a write that forgets is a live view that never
  learns of a change, with no failing request to show it. `backend/src/test/.../TreeRevisionBumpsTest` reads the sources and
  is red on a writer of those rows that is neither listed nor exempt; `TreeRevisionCoreSliceTest` / `DepotTreeRevisionSliceTest`
  pin each path's one bump and a refusal's none. A guest's `resolveShare` tag is NOT this revision (it would leak the rest
  of the space's activity): `ShareETag` digests what the response serves.
- **A list of records inside a node is core's primitive** (inbox #410, [ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md),
  `core/items/`): the table `veyq_node_items_revision(node_id, revision, delete_revision)` (no row reads 0/0; the first change births it), the
  seam `NodeItemsRevisions` and the wire helpers `ItemsETag` / `ItemsCursor`. **Every writer of an application's record rows calls
  `items.bump(scopeId, nodeId, deleted)` in its own transaction**, after the record rows it changed (a removal passes `deleted = true`: both
  counters move); an application never writes the table (`NodeItemsRevisionBumpsTest` is red on a file that names its entity, on a Memories
  media writer that does not bump, and on an unclassified new one). **Lock order: record rows -> node row -> revision row.** Unlike
  `bumpTree` this bump does NOT run under the scope lock (gallery writers lock a media row, not the scope), so the first-bump insert is
  decided under the NODE row's lock with the UPDATE asked again beneath it; the revision row is a leaf nothing waits behind
  (`NodeItemsRevisionsCoreSliceTest`, `MediaLockOrderRaceSliceTest`). A list endpoint is `read(nodeId, ifNoneMatch, sinceDelete, delta, ids)`:
  `NotModified` (a `304`, neither query runs) or `Changed` with the application's own keyset delta and, only when a removal advanced past the
  client's `sinceDelete`, the id set. Erasure is core's: `NodeItemsRevisionNodeSubtreeEraser` (phase 300) and `NodeScopeEraser` delete the row
  before the node (`fk_items_revision_node`, no cascade). The scope's tree revision above is the node LIST's signal; this is the items INSIDE a
  node. How an application adopts it: [docs/apps/README.md](../docs/apps/README.md#how-an-application-publishes-a-revisioned-list).
- **A node's cover is core's write, in ONE transaction** (inbox #411, `core/content/NodeCover*.kt`): `setNodeCover` / `removeNodeCover` attach or detach
  the `COVER` blob through `AttachmentStore` (compare-and-set on the blob replaced; a refused slot COMMITS the loser's freed upload) and then
  write the node's re-sealed `wrappedMeta` through `NodesStore.applyUpdate` (ADR 0077 revision check; a stale meta ROLLS the attach back so the
  upload stays open). Which kinds take a cover is `NodeKindRules.takesCover`, answered by the rule that already handles the kind - core names
  none; `COVER` is a role core declares (`CoreMemberBlobRoles`), so core alone admits it. Two bumps of the tree revision per request is
  harmless. The zero-residue proof (set, replace, remove: no row, no blob, no session, quota back, a reclaim row per storage key, the sweep
  hands every chunk to the store) is `NodeCoverCoreSliceTest`.
- **A change to a lock, a gate or a transaction boundary also runs `python3 scripts/run_backend_races_on_mariadb.py`**
  (Docker; CI's `backend-races` job runs it too): every race class on a throwaway MariaDB of the pinned image. H2 cannot show what the production engine
  does under overlap — on 2026-10-03 three races green on H2 were red there ([ADR 0078](../docs/decisions/0078-production-reads-committed-like-its-tests.md)).
- **Every dependency is checksum-verified** (`gradle/verification-metadata.xml`, ADR 0057 §6), and so is the
  wrapper's distribution (`distributionSha256Sum`) and the wrapper JAR itself (`scripts/gradle_wrapper_jar.sha256`
  — a `./gradlew wrapper` that rewrites the JAR owes it a line, cross-checked as its header says). A dependency, plugin or Quarkus bump — **or a new
  module**: a project that applies `io.quarkus` resolves configurations of its own, and the first one resolved five
  POMs no other project did (`:core`'s `quarkusGenerateAppModel`, 2026-10-04) — therefore regenerates
  the metadata by hand (Dependabot is remembered not to): `GRADLE_USER_HOME=$(mktemp -d) ./gradlew
  --write-verification-metadata sha256 help check quarkusBuild pitest`. **The empty Gradle home is the point** —
  a warm cache hides what a cold one fetches: the first write, from a warm cache, missed
  `okhttp-bom-5.3.2.module`, which every clean build (the Dockerfile's, a fresh CI runner's) resolves. Those four
  tasks cover the Dockerfile's build too, but **not `quarkusDev`**: its `quarkusBootstrapResolverConfiguration` is
  resolved only when the task *executes*, so after a Quarkus bump also start `quarkusDev` once against the
  written metadata and add what it refuses (2026-09-28: two POMs were missing and the dev backend would not
  start). Read the diff before committing it; its checksums are trusted on first write. **A write only adds — so
  a removal or replacement also prunes.** Gradle never removes an entry ("there's no way for Gradle to know upfront
  if a dependency will effectively be used", its dependency-verification docs), and no buildless check can know
  either: the file holds ~1000 components against the ~24 coordinates the build scripts spell
  (`grep -c '<component ' gradle/verification-metadata.xml`); the rest exist only in the resolved graph. So after
  a dependency leaves: empty the `<components>` element (keep `<configuration>` — `verify-metadata` and the trust
  rules), run the cold-home write above, start `quarkusDev` once and add what it refuses, and read the diff
  against the committed file — every component that vanished must trace to the change. The proof is the same
  cold-home `help check quarkusBuild pitest` **without** the write flag: green there means nothing pruned is
  still fetched. Gradle's own `--dry-run` alternative misses configuration-time resolution, so it is not
  enough here. The only trust rules are
  for what an IntelliJ sync fetches and nothing runs: the `-sources`/`-javadoc` jars and the Gradle
  distribution's `-src` zip. `scripts/validate_build_input_pins.py` holds all of this, plus the one generator
  version the three surfaces run. **Every `quarkusBuild` also writes the image's third-party notices**
  (`buildSrc`'s `ThirdPartyLicenses`, finalizing it; `build/third-party-licenses/THIRD-PARTY-LICENSES.txt`, which the
  Dockerfile copies to `/usr/share/licenses/veyq/`): each jar in `quarkus-app/lib`, its license from its POM (or the
  nearest parent's), the license files it carries, and the texts under `licenses/`. It fails on a jar no resolved module
  explains, on a license name its `KNOWN` table lacks, on a license with no text, on an MIT/BSD jar that carries no
  license file and has none under `licenses/notices/` (the template names no copyright holder), and on a text or notice
  no shipped jar needs any more — so a bump that brings a new license is red at the build that would ship it, and one that
  drops a dependency says which files to delete. Admitting a license is a decision beside AGPL-3.0-only code: its `KNOWN`
  entry and its SPDX text (`licenses/README.md`) together. The H2 driver is a `testFixturesApi` of `:core`, never `api`:
  only `%test` uses it, and as `api` it shipped in the fast-jar. The POMs it reads are pinned like every other artifact, so a bump's
  metadata write above covers them. **The build logic is `buildSrc`** (the convention plugins `veyq.module` and
  `veyq.openapi`): it is verified against the same metadata file, and every version it and the build choose — Kotlin,
  Quarkus (plugin and BOM), the OpenAPI generator — is declared once, in `gradle/libs.versions.toml`.

## Conventions (see [developer styleguide](../docs/crosscutting/developer-styleguide.md))
- **API-first.** Edit the spec in `../contracts/api/` — core's specs at its top level, an application's in the
  directory named for it (`memories/`), shared schemas in `_common.openapi.yaml` (core's); the Gradle build generates
  Kotlin models + JAX-RS interfaces per owner, in the module that owns them. **The owner is derived from the
  directory, never listed** (`buildSrc`'s `SpecSets`, inbox #344): the top level is `:core`'s
  (`:core:openApiGenerateCore` → `core/build/generated/openapi/core/`, package `me.raddatz.veyq.core.generated`),
  `contracts/api/<app>/` is `:apps:<app>`'s (`:apps:memories:openApiGenerateMemories` →
  `apps/memories/build/generated/openapi/memories/`, package `me.raddatz.veyq.memories.generated`); `openApiGenerateSpecs`
  from `backend/` runs every owner's. A module opts in with `veyqOpenApi { generateOwnSpecs() }`, which reads its
  owner from its project path — so a new application's specs need only their directory and the module. The build
  refuses, while it configures, a spec nested deeper than one directory, a directory that is no application name
  (lowercase letters and digits) and one with no included `:apps:<dir>`; and a module that is included but never
  opts in, once every project is configured (`validate_backend_module_graph.py` rule (d) holds both before any build).
  A module whose directory is missing configures fine and is refused only when it generates (an empty set), because
  the Dockerfile's warm-up configures the build before `contracts/` is copied in. A schema two owners share lives in
  `_common` and is ONE generated type, core's: an application's set maps every `_common` schema to core's class, and
  an application model named like a core one fails the build; so does `x-veyq-channel` on an application's operation
  (the admin channel is core's). Never hand-write a DTO that
  duplicates a generated model.
  **Nothing generated is tracked** (`backend/build/` is gitignored), and the generator emits no descriptions —
  `useSwaggerAnnotations` / `useMicroProfileOpenAPIAnnotations` are both `false` in `buildSrc`'s `OpenApiGenerate`.
  So a spec edit that changes only `description:` / `summary:` text produces no backend delta beyond the models'
  KDoc (the Kotlin model generator copies a property's description into a comment; nothing else carries it) and
  needs no backend phase: the contracts guards are its whole check. A shape change — a property, a type, a
  `format:`, an enum, a required flag — is the opposite, and always does.
  **One tag is one generated interface with ONE root `@Path`** — the longest prefix its operations share. An
  operation whose path leaves that prefix widens the root (`/api/item-favorites` beside
  `/api/scopes/{scopeId}/nodes/{nodeId}/item-favorites` made it `/api`), and RESTEasy then matches the per-node
  routes to the more specific `/api/scopes` resources first and never falls back: every existing route of the tag
  answers 404/405. Give such an operation a tag of its own (`own-item-favorites`). `revokeScopeShares` under
  `sharing` met it from the other side (ADR 0075): only its `/api/scopes/…` route answered 405 while the tag's
  `/api/shares` routes kept working — so a green suite for the tag's other routes proves nothing; test that URL.
- **Feature-slice + two axes ([ADR 0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)):**
  `me.raddatz.veyq.core.<area>/<usecase>/…` (an application's slices under `me.raddatz.veyq.<application>`, today
  `me.raddatz.veyq.memories`). A `…Resource` implements the generated `…Api` (thin adapter), delegates to
  a `…Handler`; persistence in a `…Store` (Panache). Resources are resource-shaped, not per-use-case.
- **Validation + sanitization belong in the spec** via `x-field-extra-annotation` (bean-validation
  `@NotBlank`/`@Size`/`@Pattern` + a Jackson trim deserializer) → enforced by `@Valid` at the boundary,
  **400 before the handler** — but only one level deep: a nested object or list needs its own
  `@field:jakarta.validation.Valid`, a list of scalars carries its elements' bounds as core's `EachElement`, and
  every request bound needs its annotation ([styleguide](../docs/crosscutting/developer-styleguide.md)). A list's
  `@field:jakarta.validation.Valid` stays in the spec, but the generated field is rewritten to `List<@Valid Item>`
  (`buildSrc`'s `ModelCascade`; Hibernate Validator 9 warns `HV000271` on a container-level `@Valid`). **Trap:** the
  type-argument annotation reaches the bytecode only with `-Xemit-jvm-type-annotations` (`veyq.module`) — drop the flag
  and the cascade silently vanishes (nested-element slice tests + `GeneratedCascadeSweepTest` go red); a generator
  bump that writes a list differently fails the build in `ModelCascade` rather than losing the cascade. Don't
  re-validate field shape in the handler. Every such refusal — and Jackson's own, below — answers the contract's
  `400 invalid_request` with nothing of the request in it, through core's `core/http/RequestRejectionMappers.kt`
  (styleguide §7); an application writes no mapper of its own.
  **Match the annotation to the GENERATED type, not to the wire type.** A property with `format: uuid`
  generates as `java.util.UUID`, and a String validator on it is not a stricter check — it is a
  `UnexpectedTypeException` at request time, i.e. a **500 on a well-formed request** instead of the 400 the
  annotation was reaching for. A uuid field takes `@NotNull` and nothing else; `maxLength` on one is dead text,
  since the length is the type's. Same trap for any `format:` that changes the generated type.
  **A required `type: integer` or `boolean` is no presence check.** It generates a primitive Kotlin
  `Long`/`Int`/`Boolean`, and Jackson fills a missing one with 0/false — measured: `updateMediaMeta` without
  `metaRevision` answered 200, not 400, and so did a mail-settings update without `tls` (STARTTLS off). Unless its
  `minimum` already excludes 0, make the type nullable (`[integer, "null"]`), annotate `@NotNull`, and read it in
  the resource with `.validated()` (`validate_request_bounds.py` rule 5 holds it). A null that would *mean*
  something ("no quota") is not a field value at all but an operation of its own (`clearTenantStorageQuota`): once
  bound, absence and null are the same. An object, enum or
  uuid field is refused by deserialization on its own.
- **Migrations:** Flyway; `schema-management.strategy=none` — Flyway owns the schema. **One global version
  scheme across modules:** `V<yyyymmddHHMM>__<module>_<slug>.sql` (the UTC minute you write it; `<module>` is
  `core` or an application's name — `memories` — as `settings.gradle.kts` includes it, `:apps:<name>`), each in its
  owner's `src/main/resources/db/migration/` (`backend/core/…` for core,
  `backend/apps/memories/…` for Memories; the guard holds the name to the directory) — one classpath path, `db/migration`, so Flyway reads them
  as one set and two files of one version fail startup. The schema starts from two baselines,
  `V202610041200__core_baseline.sql` and `V202610041201__memories_baseline.sql`; a Memories migration references
  core tables or its own, a core migration never names a Memories table (ADR 0079). A new migration sorts above
  every existing one — Flyway runs `outOfOrder=false`, so an instance past a version refuses a file below it —
  and appends its SHA-256 line to `scripts/applied_migrations.sha256` in the same commit;
  `scripts/validate_migration_versions.py` holds the name shape, unique versions, the pins, their order and the
  module direction. A new time column (an entity's `Instant`) names its reader in the root's
  `EveryStoredTimeHasAReaderTest`, or that test is red — the one sweep a new table meets that no contract guard
  predicts (cr-release-notices' list of sweeps missed it). A migration that **seeds rows** (`INSERT … VALUES`) puts
  them into every fresh install, which the restore's emptiness gate then reads as data: run
  `python3 scripts/instance_backup_selftest.py`, which fails until the table is in `instance_backup.py`'s `NOT_DATA`
  with its reason — it runs in CI's `web-e2e` job only, so no backend check sees it (two seeding migrations of
  2026-10-16 refused every restore before it). **An applied migration is never edited — not even a comment in it.** Flyway validates the
  checksum of every applied migration at `migrate-at-start`, so a one-word fix to a `--` comment fails startup on
  every instance that already ran it; `%dev` hides this behind `repair-at-start=true`, so it is green exactly where
  you would test it and red only on somebody else's server. A migration's comment is a historical artifact:
  correct the live copy of that rule (the entity KDoc, the ADR) and leave the file alone. **Until the first
  release** (the owner declares it on the pin file's `release:` line; `none` today) a baseline may still change,
  but only together with its pin line in the same commit, so the change is a visible diff — and every existing
  database, dev ones included, is then recreated (`docker compose -f infra/docker-compose.yml down -v`; repair does
  not rescue a changed baseline). From the release on the rule is absolute: a released migration is only followed
  by a new one. The upgrade proof (`python3 scripts/run_web_e2e.py --upgrade`, `site/src/content/docs/run/upgrade.mdx`) runs
  against the previous commit until then and against the release from then on; **the commit that sets `release:`
  also enables its CI job** (`ci.yml`'s `upgrade-proof`: delete its `if: false` and the `"skipped"` `publish`
  tolerates for it), because only then does a red proof mean a release that must not publish.
  Column types: `TEXT` for opaque base64 (65 535 bytes on MariaDB, so a base64 cap must be ≤ 65 532 —
  guarded by `scripts/validate_maxlength_storage.py`), `MEDIUMTEXT` where a cap needs more (16 MiB), `VARCHAR`
  for short, and **`UUID` (MariaDB's native type) for every id and id reference that is exactly a UUID** (inbox #387): the entities
  keep `String id`, no converter. A non-UUID string is error 1292 on write and a silent miss on read, so an id enters only through a
  `format: uuid` contract field (`scripts/validate_id_contract_format.py` holds it; `IdBoundarySliceTest` the wire) and a native `SELECT` of
  one casts it to text (`CAST(t.id AS CHAR(36))`: a bare one is a `byte[]` on H2, measured). Never a sentinel id (`''`, `'system'`):
  write the query without the clause. A share id, a composite key and `owner_key` are not UUIDs and stay `VARCHAR`.
  A `UUID` column orders by its own segments, not by its text: sort ids in Kotlin where a text order is promised, and break a
  `created_at` tie explicitly. A plain `TIMESTAMP` is **second-precision on MariaDB** (H2 keeps more, so H2 rarely shows the tie): a column that orders rows or picks the newest — `veyq_node`/`veyq_job`/`veyq_membership`/`veyq_tenant_invite.created_at`, the Memories media `updated_at` — is `TIMESTAMP(3)` (millisecond; inbox #387/#402), and the query still ends on `id`. **An entity maps such a column `@Column(columnDefinition = "uuid")`,
  never `length = 36`**: Hibernate's schema validator (a `%dev` boot runs it, `validate-in-dev-mode`; production's `none` does not)
  compares the declared type name with the database's, and `length = 36` says `varchar(36)` — an ERROR per column in every IntelliJ
  start. `EntitySchemaValidationTest` (in `:core` and `:apps:memories`) boots with `strategy=validate`, so a drift is red on H2 and,
  in the race runner's default run, on MariaDB (bitten both ways: one id back to `length = 36`). `python3 scripts/run_backend_races_on_mariadb.py --tests '*'` runs the whole suite on the real engine. The test datasource runs H2 with **`MODE=MariaDB`** (`application-test.properties`) so it parses
  what prod parses — a **parity** measure, not a workaround: a type-change is `MODIFY COLUMN` (MariaDB-only
  syntax; default-mode H2 rejects it, and there is no type-change form in the H2 ∩ MariaDB *default* subset).
  The mode does **not** make H2 enforce MariaDB's byte ceiling — that stays the guard's job; don't conflate them.

## Invariant
The backend performs **no content crypto** and never sees the password (Model B,
[ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)). It stores only ciphertext, public
keys and low-sensitivity operational metadata. Re-read
[security model](../docs/crosscutting/security-model.md) before touching any key/data/auth path.
