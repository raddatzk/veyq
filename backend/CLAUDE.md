# backend (Quarkus / Kotlin)

Operator-blind API service. Loaded in addition to the root `CLAUDE.md` when working here.

## Prove your work
- `./gradlew check` (compile + slice tests) — runs on **in-memory H2** (`%test`, hermetic + fast). Run from
  `backend/`, it checks every project: `:core` (`backend/core`, the platform — its tests run as `:core:test`),
  `:apps:memories` (`backend/apps/memories`, the Memories application; its tests run as `:apps:memories:test`), the
  root (`:test`, the composition — no main code — and only the whole-app tests: `OperatorBlindProbeTest`, the posture
  and entity sweeps, `ModuleCompositionTest`). A new
  application is one more `:apps:<name>`; what a module must have (an `include`, a `beans.xml`, the `veyq.module` plugin,
  its Dockerfile `COPY`s) and which edges it may declare (`:core`, and `testFixtures(project(":core"))` over a test
  configuration — never another application) is `scripts/validate_backend_module_graph.py`'s, its docstring the list. `:core`'s tests boot core
  with **no** application (`CoreAloneSliceTest`). **A test
  lives where what it exercises runs:** in `:core` if it passes with core alone, in `:apps:memories` if it needs
  Memories at runtime — every share creation (core refuses a share on a kind no application supports), every
  `MEMORY`/`CHAPTER` placement, a seam's registered partition — even when its package is core's. **A `--tests`
  filter must name the project that holds the class** — `./gradlew :core:test --tests '*SessionPurgeTest'`,
  `./gradlew :apps:memories:test --tests '*ShareSliceTest'`: a bare `test --tests X` fails "No tests found" in every
  project without a match. **Each project with `@QuarkusTest`s holds
  its own `%test` keys** in `src/test/resources/application-test.properties` beside a comment-only
  `application.properties` anchor (a profile file is read only beside one); the base config is `:core`'s
  `src/main/resources/application.properties`. Shared test helpers live in **`:core`'s test fixtures**
  (`core/src/testFixtures`, `java-test-fixtures`), consumed as `testImplementation(testFixtures(project(":core")))`:
  Quarkus loads them as a workspace module of the application under test, so a seed that persists entities, a
  helper on `Arc.container()` and the CDI fakes all work from there in `:core`'s, `:apps:memories`' and the root's
  tests (measured 2026-10-05, inbox #333) — as long as no jar is renamed (`archivesName`, `buildSrc`'s `veyq.module`
  says why). The fixtures need their own `META-INF/beans.xml`, carry no config file (`%test` keys go in a module's
  `src/test/resources`), and never depend on an application.
- **Run the app (`quarkusDev`) on a persistent MariaDB** (`%dev`): `docker compose -f infra/docker-compose.yml
  up -d` starts it; data survives restarts (Flyway migrates once, then "up to date"). `docker compose -f
  infra/docker-compose.yml down -v` wipes it for a fresh instance. The H2 fallback is only the test default now.
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
  version the three surfaces run. **The build logic is `buildSrc`** (the convention plugins `veyq.module` and
  `veyq.openapi`): it is verified against the same metadata file, and every version it and the build choose — Kotlin,
  Quarkus (plugin and BOM), the OpenAPI generator — is declared once, in `gradle/libs.versions.toml`.

## Conventions (see [developer styleguide](../docs/crosscutting/developer-styleguide.md))
- **API-first.** Edit the spec in `../contracts/api/` — core's specs at its top level, the Memories application's in
  `memories/`, shared schemas in `_common.openapi.yaml` (core's); the Gradle build generates Kotlin models + JAX-RS
  interfaces per owner, in the module that owns them (`:core:openApiGenerateCore` → `core/build/generated/openapi/core/`,
  package `me.raddatz.veyq.core.generated`; `:apps:memories:openApiGenerateMemories` →
  `apps/memories/build/generated/openapi/memories/`, package `me.raddatz.veyq.memories.generated`; `openApiGenerateSpecs`
  from `backend/` runs both). A schema two owners share lives in `_common` and is ONE generated type, core's:
  the Memories set maps every `_common` schema to core's class, and a Memories model named like a core one fails the
  build; so does `x-veyq-channel` on a Memories operation (the admin channel is core's). Never hand-write a DTO that
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
  `@field:jakarta.validation.Valid`, and every request bound needs its annotation
  ([styleguide](../docs/crosscutting/developer-styleguide.md)). Don't re-validate field shape in the handler.
  **Match the annotation to the GENERATED type, not to the wire type.** A property with `format: uuid`
  generates as `java.util.UUID`, and a String validator on it is not a stricter check — it is a
  `UnexpectedTypeException` at request time, i.e. a **500 on a well-formed request** instead of the 400 the
  annotation was reaching for. A uuid field takes `@NotNull` and nothing else; `maxLength` on one is dead text,
  since the length is the type's. Same trap for any `format:` that changes the generated type.
  **A required `type: integer` or `boolean` is no presence check.** It generates a primitive Kotlin
  `Long`/`Int`/`Boolean`, and Jackson fills a missing one with 0/false — measured: `updateMediaMeta` without
  `metaVersion` answered 200, not 400, and so did a mail-settings update without `tls` (STARTTLS off). Unless its
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
  module direction. **An applied migration is never edited — not even a comment in it.** Flyway validates the
  checksum of every applied migration at `migrate-at-start`, so a one-word fix to a `--` comment fails startup on
  every instance that already ran it; `%dev` hides this behind `repair-at-start=true`, so it is green exactly where
  you would test it and red only on somebody else's server. A migration's comment is a historical artifact:
  correct the live copy of that rule (the entity KDoc, the ADR) and leave the file alone. **Until the first
  release** (the owner declares it on the pin file's `release:` line; `none` today) a baseline may still change,
  but only together with its pin line in the same commit, so the change is a visible diff — and every existing
  database, dev ones included, is then recreated (`docker compose -f infra/docker-compose.yml down -v`; repair does
  not rescue a changed baseline). From the release on the rule is absolute: a released migration is only followed
  by a new one, and an upgrade proof against a database of the release becomes possible.
  Column types: `TEXT` for opaque base64 (65 535 bytes on MariaDB, so a base64 cap must be ≤ 65 532 —
  guarded by `scripts/validate_maxlength_storage.py`), `MEDIUMTEXT` where a cap needs more (16 MiB), `VARCHAR`
  for short. The test datasource runs H2 with **`MODE=MariaDB`** (`application-test.properties`) so it parses
  what prod parses — a **parity** measure, not a workaround: a type-change is `MODIFY COLUMN` (MariaDB-only
  syntax; default-mode H2 rejects it, and there is no type-change form in the H2 ∩ MariaDB *default* subset).
  The mode does **not** make H2 enforce MariaDB's byte ceiling — that stays the guard's job; don't conflate them.

## Invariant
The backend performs **no content crypto** and never sees the password (Model B,
[ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)). It stores only ciphertext, public
keys and low-sensitivity operational metadata. Re-read
[security model](../docs/crosscutting/security-model.md) before touching any key/data/auth path.
