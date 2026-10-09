# Developer Styleguide & Patterns

> How we write code in veyq — the code-level companion to the architecture and security docs. It
> captures the conventions every contributor (human or agent) must follow, so the first attempt is
> right. Working rules (workflow, sparring, language, Python, diagrams) live in
> [`docs/ai/README.md`](../ai/README.md); the security boundary in [Security Model](security-model.md).
>
> **Litmus test for a rule here:** *would a competent developer get it wrong without this written down,
> and does everyone follow it?* If it's just how one class happens to work, leave it to the code.
> Concrete file examples are added as code lands — this is greenfield, so today it states principles.

## 0. Invariants that override style

- **Operator-blind is a code rule, not just a goal.** The server stores and serves only ciphertext and
  must never gain unilateral or silent access to content, keys or passwords. Re-read the
  [Security Model](security-model.md) before touching any data, key, media, sharing, search or storage
  path. The paramount invariant — an unshared personal space is confidential even from the operator —
  beats any convenience below.
- **Self-hostable by default.** Managed hosting, push gateways, federation are optional layers, never required.
- **Terminology:** **Memory** (product), **Chapter** for nested memories, **Folder** is organizational
  only. No Album/Explorer models, APIs or tables.

## 1. Code organization (binding)

- **Two axes, not one** (see [ADR 0001](../decisions/0001-backend-http-adapter-vs-application-slicing.md)).
  The **application layer is use-case-sliced** — `<area>/<use-case>/…Handler` under the owner's package,
  `me.raddatz.veyq.core` for the platform or `me.raddatz.veyq.memories` for the Memories application (a use-case may
  have several handlers); this is where cohesion and the trust chain live. The **HTTP adapter layer is
  resource-shaped** — one `…Resource` per generated `…Api` interface (OpenAPI tag), *not* per use-case,
  because one endpoint can serve several use-cases. Use-case slicing is what carries the trust chain; the
  adapter shape is necessarily per-stack.
- **Cross-cutting code is the platform's kernel** — the packages of `me.raddatz.veyq.core` that every feature slice
  builds on (tenant, identity, auth, membership, the blob store, rate limits, …). **The list is declared once**,
  as `KERNEL` in [`validate_kernel_imports_no_slices.py`](../../scripts/validate_kernel_imports_no_slices.py);
  every other package is a slice, so a new package is a slice until it is added there on purpose. A kernel
  package must not depend on a slice, and the guard holds that for its imports. The **platform half**
  ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)) is the build's: core is
  the Gradle module `:core`, an application is `:apps:<name>` and depends on core, never the reverse and never on
  another application (`validate_backend_module_graph.py`). Core reaches application behaviour through an interface
  the application implements (`ScopeEraser`, `NodeSubtreeEraser`, `NodeKindRules`, …), never through an import —
  which would not compile.
- **Dependencies stay directed.** If a slice builds on a kernel package, that package must not reach back into
  the slice; share neutral rules through small interfaces.

## 2. Backend layering

- **Resource = HTTP adapter, resource-shaped.** It implements the generated `…Api` interface (one per
  OpenAPI tag / API resource) and handles JAX-RS `Response`, headers, multipart/temp files and parameter
  normalization — nothing else. Each operation is a thin **delegate to the relevant use-case handler**;
  an operation serving N use-cases dispatches to N handlers (or a small dispatcher).
- **Handler/Service = one responsibility,** describable in one sentence. Constructor dependencies are a
  refactoring signal: 6–9 → review the responsibility; 10+ → actively split out policies/resolvers/mappers/stores.
- **Authentication & coarse authorization are central, declared at the resource boundary** — never read the
  bearer token in a resource or handler ([ADR 0008](../decisions/0008-centralized-http-auth.md)). A custom
  `HttpAuthenticationMechanism` + `IdentityProvider` resolve the token once into a `SecurityIdentity`; each
  resource method declares `@PermitAll` / `@Authenticated` / `@RolesAllowed(SERVER_OWNER)`. **Default-deny**
  (`deny-unannotated-endpoints=true`): an unannotated endpoint fails closed (401), so the annotation is the
  single place the token-or-not decision is taken. The 401/403 body comes from two `ExceptionMapper`s, not a
  per-resource `unauthorized()`. The one exception is the deliberately-lenient `logout`, which reads the token
  manually so it stays a 204 no-op for any token.
- **Fine-grained, content-level authorization stays a small domain guard** in the handler — a *different layer*
  from the HTTP role gate above (e.g. scope access — the membership boundary first, then the grant / key-holder
  check ([ADR 0042](../decisions/0042-http-failure-projection.md)); a containment guard; tenant-admin over a
  *specific* tenant). These need DB/grant state and are never an HTTP role; keep them small, named and
  fail-closed. The map of both layers is [cc.security.authorization](cc.security.authorization.md).
- **Mappers produce the target model directly** from the richer source — no
  `domain.toSummary().toSomethingElse()` chains. Generated API models stay at the resource/mapper
  boundary; handlers, guards and stores work with domain types.

## 3. API-first contract

- **The OpenAPI spec under `contracts/api/` is the contract.** The build generates JAX-RS interfaces +
  Kotlin/TS models; resources implement the interfaces directly. Never hand-write a DTO that duplicates a
  generated model. The API is internal and may change with its consumers.
- **Every edit operation declares how it meets a second writer** — `x-veyq-concurrency` with `mode: version` (a
  list of `fields`: each version field the request carries, the fields that require it, and the conflict code its
  stale `409` answers) or `mode: last-writer-wins` with its reason
  ([ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md); `scripts/validate_write_concurrency.py`
  holds it). A write that more than one account makes **and** that re-sends fields its writer did not edit is
  `version`; the client re-applies only its own change on the conflict body, and never seals a blob it could not
  open.
- **A dev seeder or script that calls the API is a client like any other**: it goes through the generated client
  and is typechecked with the app (`web/scripts/`, `tsconfig.scripts.json`), and it never reaches past the API into
  the database. When `bootstrap:user` hand-wrote its bodies, login verify gained `nonce` and the seeder failed its
  own login with 400 at its next run instead of at `typecheck`.

## 4. Input validation & sanitization

- **The OpenAPI schema is the source of truth for input shape and limits.** Declare constraints on
  request fields (`maxLength`/`minLength`, `pattern`, `format`, `minimum`/`maximum`, `required`).
  Required-field + structural validation is enforced by generated bean validation → a malformed body is
  rejected **before the handler** (400) — with the contract's `ErrorResponse{code: invalid_request}`, the same
  body a handler's refusal carries (§7).
- **Field-level validation AND sanitization are declared in the spec via `x-field-extra-annotation`.**
  The `kotlin` model generator ignores standard `maxLength`/`pattern`, but emits whatever
  `x-field-extra-annotation` holds onto the field. Use it to attach (a) **bean-validation** constraints
  (`@field:…NotBlank`/`Size`/`Pattern`) — enforced by `@Valid` at the boundary, 400 before the handler —
  and (b) a **trimming Jackson deserializer**
  (`@field:…JsonDeserialize(using = TrimmingStringDeserializer::class)`) to normalize server-visible name
  metadata. Only **server-visible** fields may be sanitized server-side; content is ciphertext and stays
  untouched.
- **In a request body, the schema bound and its annotation are one rule written twice — value for value, both
  ways.** A `maxLength`/`minLength`/`maxItems`/`minItems`/`minimum`/`maximum`/`pattern` has the annotation that
  enforces it, a `contentEncoding: base64` has the base64 `@Pattern`, and no annotation enforces a rule the
  schema does not declare (a client generated from the contract could not know it). **A property holding an
  object, or a list of objects, whose schema carries any constraint also carries
  `@field:jakarta.validation.Valid`** — validation does not descend without it, so every annotation below it is
  dead. That is how `BlobUploadsBatchRequest.items` enforced neither its `maxItems: 256` nor any item's
  `wrappedBlobKey` checks. **A property that `$ref`s a scalar component schema** (`NodeKind`, `CapabilityTag`) gets
  the scalar inlined as a `String` with none of its bounds, so an annotation carries the target's bounds: beside the
  `$ref` for one request field, or on the target, which the generator copies onto every field `$ref`ing it that has
  none of its own (a sibling annotation replaces the target's, it does not add to it). **A list of scalars bounds
  its elements on the list:** the generator types it `List<String>` and cannot annotate the type argument, so what
  its `items` declare (`maxLength`/`minLength`/`pattern`, through a `$ref`'d scalar too) is written as core's
  `@field:me.raddatz.veyq.core.http.EachElement(maxLength = …, pattern = "…")` beside the list's own `@Size` —
  `ClaimRequest.capabilityTags` took any string as a tag until it did (inbox #354). A list of `uuid`s needs none:
  the element type refuses the rest. Parameters are exempt: the `jaxrs-spec` API generator derives their annotations
  itself (`useBeanValidation`). Enforced by `scripts/validate_request_bounds.py`. On a list, Hibernate
  Validator logs `HV000271` (a container-level `@Valid` is deprecated in favour of the type argument) — which
  the `kotlin` generator cannot place, so the field form stays; `MediaCoreSliceTest.startBlobUploadsValidatesInsideEachItem`
  is what reddens if a later Hibernate Validator stops honouring it.
- **Stateful / cross-field / domain checks stay imperative** in the handler or a guard, close to the
  invariant, and small/named/fail-closed (e.g. crypto-suite allow-list, scope-grant checks, upload
  quota/path-traversal guards). Anything needing DB state does not belong in a field annotation.
- **Operator-blind boundary:** the server may validate only **structure and server-visible fields** (IDs,
  sizes, `suiteId`, lengths, counts). It **cannot** validate or sanitize **content** — content is
  ciphertext. Never introduce a server path that must read content to validate it. Which fields those are is
  not a matter of memory: every field in `contracts/api/` declares its visibility class
  ([ADR 0044](../decisions/0044-field-visibility-declared-at-the-field.md)), and an unclassified one fails the
  build.
- **Sanitization is a client duty, safe-by-construction.** Decrypted content rendered as HTML — above all
  journal Markdown — must never reach `v-html`/`innerHTML` unsanitized. Use an escape-first renderer
  (HTML-escape, then a fixed safe subset; links forced to `https?://` + `rel="noopener noreferrer"`) or
  DOMPurify. This is a security rule, not formatting.

## 5. Naming

- Classes by role suffix: `…Resource`, `…Handler`, `…Guard`, `…Store`, `…Mapper`, `…Service`, `…Filter`.
- Use-case folders are kebab-case (`create-tree`, `journal-live`); DB tables are `veyq_<snake>`; project
  config keys are `veyq.<kebab>`; crypto domain labels are `veyq/v1/<purpose>`.
- All repository docs and code identifiers are **English** (table names, annotations, config keys unchanged).

## 6. Comments — explain *why*, not *what*

A comment earns its place by explaining an **invariant, a threat, or a non-obvious decision** — not by
restating the code. Avoid `// loop over the items` and comments that drift from the code.

## 7. Error handling

- **Backend:** return a stable **error code** (the client localizes). Never put content, keys or secrets
  in an error body — the operator-blind boundary applies to errors too.
- **A request the boundary refuses for its shape answers `400 ErrorResponse{code: invalid_request}`** — whichever
  layer refused it: Jackson while binding (an unknown enum value, a wrong type, a missing or null required field,
  unparseable JSON) or bean validation after it (an empty field, a bound, a pattern, an element). Core's
  `core/http/RequestRejectionMappers.kt` holds one mapper per exception type plus one response filter for the empty
  400 Quarkus' body reader answers, so every generated resource — each application's too — answers alike with no
  code of its own; a new refusal path gets its mapper there, never a per-resource catch. **Never an
  `ExceptionMapper` for `WebApplicationException`** (or any supertype of `NotFoundException`): Quarkus REST then
  answers an unmatched route itself instead of passing it on, and the app shell's SPA fallback and the admin
  channel's unknown-route refusal both stop working. The body carries the code alone: never the refused value (often ciphertext or an opaque id),
  the field's name or path, the rule, an enum's accepted values or a class name — the contract already says what
  is accepted. A violation that names no method parameter (an entity, a return value) is the server's own defect:
  `500 internal_error`, no report. `MalformedRequestSliceTest` holds the shape, `FilesContentSliceTest` an
  application's resource (inbox #339, #355).
- **Frontend:** build errors through a shared helper with a stable `"<label> failed: <status>"` message;
  append the response body **only in dev builds**. Don't couple UI text to whatever the server echoes.

## 8. Configuration

- Env-driven with safe defaults (`...=${ENV:default}`), with `%dev`/`%test` profile overrides; the
  in-memory/H2 path stays the dev/test default so slices run offline.
- **Project-owned `veyq.*` config** is modeled with a complete `@ConfigMapping` class, not scattered
  per-value properties (internal framework config may use direct injection).

## 9. Crypto & data (binding)

- **One suite, byte-identical everywhere.** Any change to crypto suite `v1` (or a new suite) requires a
  **conformance vector in `contracts/crypto/vectors/`** and a **passing conformance test** (web, and the
  backend where it verifies). Use domain-separated labels (`veyq/v1/<purpose>`) and length-prefixed encoding,
  and seal only through the typed `seal`/`open` of `@veyq/crypto`, whose context names the purpose **and** the
  object the ciphertext belongs to ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md)). Never roll your
  own primitive; never skip the vector. (The contents of suite `v1` are decided in the first crypto
  change-request — see [Security Model → Cryptographic stance](security-model.md).)
- **Migrations are portable + Flyway-owned.** Use only column types valid on **both H2 and MariaDB** (the
  common subset); Flyway is the single schema authority. Versions are one global timestamp scheme across modules,
  each file names its owning module, and every file is pinned — the scheme, the two baselines, the pin and the
  pre-release caveat live in [`backend/CLAUDE.md`](../../backend/CLAUDE.md) § Migrations.
- **An applied migration is immutable — including its comments.** Fix a mistake with a *new* migration; never
  edit a file that has run anywhere. Flyway checksums the file's whole content, so a one-word comment edit is
  indistinguishable from a schema change: the next `migrate` fails validation and the instance does not start.
  This bites hardest where it looks safest — a docs sweep, a rename, a citation cleanup — because those are
  exactly the passes that treat a comment as free. It is written here rather than at any one migration because
  the temptation arrives from *outside* `db/migration/`, from a tool or a person editing every file that
  matches a pattern.
  What makes it true here rests on two framework **defaults** nothing in this tree restates, so they are named
  once: `application.properties` sets `quarkus.flyway.migrate-at-start=true`, and validation is left at its
  default on both layers — `quarkus.flyway.validate-on-migrate` (Quarkus 3.36.0: `true`) and Flyway's own
  `validateOnMigrate` (12.0.0 `FlywayModel.defaults()`: `true`). Only `%dev` escapes, via
  `quarkus.flyway.repair-at-start=true` — which is exactly why a broken checksum passes a developer's machine
  unnoticed and stops a production start. `scripts/validate_migration_versions.py` turns such an edit red unless
  its pin line changes in the same diff, which before the first release is the one sanctioned way to change a
  baseline.

## 10. Testing

- **Slice tests** boot a fresh in-memory DB per suite. Cover **positive and negative** paths where
  behavior has real success/failure branches; prefer risk-based coverage over percentages.
- **Conformance tests** pin cross-stack crypto (§9).
- **Test data comes from one fixture per surface, not a local copy.** In the backend a single test-fixtures
  object seeds users, tenants, member scopes and nodes and mints the `Bearer` header; a test keeps a local helper
  only for data no other test needs. Measured cost of the copies it replaces: 50 `seedUser`s, so a new
  `NOT NULL` column on `veyq_user` touched 60 files. In `web/` a test mounts with the **real** i18n catalog
  (`realI18n()` from `src/test/i18n.ts`), where a key the catalog lacks fails the test, and fakes an HTTP failure
  with the one shared `httpError` (`src/test/httpError.ts`; admin-web has its own copy), and stands in for the
  `sessionStorage` node lacks with `memoryStorage()` (`src/test/memoryStorage.ts`); a lint rule keeps stub
  catalogs out. Hand-written stub catalogs kept 13 keys the app no longer has passing green, and ten local
  `httpError`s came in four shapes, two of which cannot carry an error code.
- **A mocked API response is typed by the generated model at the seam.** A generated `…Api` is mocked only through
  `stubApi()`/`fakeApi()` (`src/test/apiMock.ts`, one copy per package), which type each payload by its model and
  refuse an untyped `vi.fn()`; a `features/` module mock exports bare `vi.fn()`s and sets behaviour through
  `vi.mocked(fn)`, which is typed by the real function (a `use*` composable keeps its hand-written stub); and a test
  never casts `as never` — a payload comes from a builder annotated with its model (`src/test/models.ts` for those
  several files serve), and a cast that must stay names its target, `as unknown as <T>`. The lint block
  `veyq/api-mocks-typed-by-the-contract` (admin-web: `veyq-admin/…`) keeps the three forms out. In web the
  feature-mock half is its own rule, `veyq-web/feature-mocks-bare-fn` (`web/eslint/featureMocks.js`), because it
  resolves a RELATIVE mock path against the test's own location: as a selector it saw only a path that spells
  `features/`, so 77 mocks in 30 tests inside `features/` went unchecked, three of them serving a payload that was
  not the model. admin-web's selector still has that limit. What bought it:
  179 `as unknown as ReturnType<typeof xApi>` casts let `ShareIdentity.sealedDisplayName` be removed from the contract
  while the fixture serving it stayed green; typed, the feature mocks named fields that did not exist (admin-web's
  `{ state: 'ACTIVE' }` on `TenantInviteState`), and of 114 `as never` most hid nothing — the ones that did hid
  fixtures that were not the model.
- The **coverage validators** (Python; added with the first feature slice, per start-light) check the
  trust chain: every behavior maps to its use-case coverage.

## 11. Git & PR

- **Domain-scoped Conventional Commits** — `type(scope): summary`, scopes
  `backend|web|contracts|infra|ci|docs|repo` (migrations are `backend`; multi-domain
  changes list each). Commit/push only when the user asks.
- Keep build-generated files out of commits.

## 12. Architectural patterns

Architectural patterns that are **binding across the codebase** are recorded here (and as an ADR when
they are stable decisions) — using the same litmus test as above. Local, self-evident pattern usage is
not documented; the code is the source of truth for that.

- **Scheduled cleanup → a `@Scheduled` purge bean.** Rows that are status/expiry-gated at read but never
  needed again (invites, session tokens, …) are physically removed by a small `@Scheduled` bean that
  delegates to store delete-methods, on a config-driven interval (`*.purge.every`). Purge strictly past
  expiry/terminal-state so any reuse-detection window (e.g. a superseded refresh token, ADR 0004) is
  preserved until it lapses; delete child rows before parents (FK order). First two: `TenantInvitePurge`
  (cr-0009), `SessionPurge` (cr-0011).
- **HTTP failure projection → `core/http/`.** A backend operation's sealed `…Outcome` failure variants project
  to the wire `ErrorResponse{code}` through the **single** `core/http/` envelope writer + status-bucket table;
  no `…Resource` constructs `ErrorResponse` or keeps a per-resource `error`/`forbidden`/`notFound` helper. The
  variant self-describes its `code` + status bucket (recurring failures via a shared `Failures` catalog), so the
  projection is single-sourced and status drift is structurally impossible. **The membership boundary is the first
  guard** on every scope-addressed operation — the one call `ScopeAccess.check` *before* any existence/kind/state
  probe, so a non-member and a bogus id get the same 404 and existence is not enumerable
  ([uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md)). Capability checks
  (grant-holder, owner) run **after** it and may answer 403: that caller is a member and already knows the resource
  exists. `OperatorBlindProbeTest` enforces this for every operation it derives from the contract — a new one is
  red until it is probed or named on the test's exclusion list with a reason.
  ([ADR 0042](../decisions/0042-http-failure-projection.md); cr-0161 the projection, cr-0162 the ordering.)
- **A single-use transition is a checked compare-and-set, first in its transaction.** A secret the docs call
  single-use (an invite, a recovery token, a pairing channel's delegation, a mailed confirmation) is spent by a
  guarded `UPDATE … where id = ? and status = <live>` whose row count the caller reads and branches on, and that
  update is the transaction's first write. Two redeems that both read the row live then cannot both win, and the
  loser has written nothing a unique index would have to undo, so it answers exactly as a replay of the spent
  secret does — not with the `500` of a collision, and not with a second success. The earlier unlocked read only
  tells an unknown secret from a dead one; it never authorizes. Checks that read what a concurrent redeem writes
  (the account it births, a profile, a parent's status) run after the spend, and a refusal there rolls the spend
  back. Examples: `PairingStore.markCollected`, `TenantStore.consumeLiveInvite`. Two corollaries, both measured
  as real defects before they were rules (cr-one-time-secrets-spend-by-compare-and-set):
  - **When the decision reads more than one row, the serializing write is a row lock on their parent, taken
    first.** `SessionService.rotate` locks the session family before it reads the presented and the `CURRENT`
    refresh token: their own row locks ordered only presentations of the same token, so an orphan and the current
    token both rotated and the chain forked ([ADR 0004](../decisions/0004-session-tokens.md)).
  - **The insert half: a create's loser re-asks the replay question after its failed commit.** A row that must
    exist at most once — a client-minted id, a slug under its parent, a username in a tenant, the setup singleton —
    has no live row to compare-and-set; its unique index is the compare-and-set. Two creates that both read "free"
    both insert, the loser's insert waits for the winner's row and fails once it commits, and unhandled that failure
    was a `500` at ten sites (inbox #223). So the create runs through `core/persistence/createOrConflict`, which
    owns the transaction: on a persistence failure it re-runs the site's own "taken?" checks, in the handler's order,
    in a fresh read, and answers the conflict a replay would get; when those checks say "free", the failure is
    re-thrown and stays a `500` — a violation of some other index is a bug, and must look like one. The site's
    pre-check stays as the fast path; it no longer has to be right under concurrency. Classifying the exception
    instead was rejected: it needs dialect-aware matching (H2 `23505`, MariaDB `1062`) and answers "taken" for any
    unique index. A create that also spends a single-use row spends it first, as above (`MediaStore.attach` deletes
    its upload sessions before it inserts the blobs).
- **Every entity carries `@DynamicUpdate`, so a managed write flushes only the columns it changed.**
  `DynamicUpdateEverywhereTest` reads Hibernate's mapping and names any entity without it. Without it a flush wrote
  every column, so a write wrote back every value it had only read and undid what another transaction changed in
  between. Each was measured: a terms edit lifted a tenant suspension, a Memory rename wiped a WRITE_ONLY link's drop
  key, a consent toggle rolled back a password change's new auth key, and a tenant rotation's re-tag revived a
  revoked invite (cr-managed-writes-flush-only-what-they-change). **Two writers of the same column still need the
  compare-and-set rule above, and a check that decides a write is read where the write happens.** A rename's epoch
  gate is its update's own `and currentEpoch = ?` (`ScopesStore.renameGroup`): checked in an earlier read, a rotation
  committed between the check and the write, and a name sealed under the old epoch landed. A re-activation reads
  membership under the scope row's lock, which every removal holds (`SharingHandler.reactivate`): read before it, a
  member being removed adopted a live link in a group it had left. The last-owner guard reads the owner count under
  that lock too (`ScopesStore.changeRole`, `TenantHandler.removeMember`). Checked before it, two co-owners demoting
  each other both counted two owners and the group was left with none.
  **A check under a lock reads what is committed when it runs, because every connection runs READ COMMITTED**, the
  isolation the test H2 runs ([ADR 0078](../decisions/0078-production-reads-committed-like-its-tests.md)). At
  MariaDB's default REPEATABLE READ the first plain read fixed the snapshot, so a check after a later lock was stale
  on production and fresh in every slice test. `scripts/run_backend_races_on_mariadb.py` runs the race classes on
  the pinned engine. One trap holds at any isolation: a lock on an entity the transaction already loaded hands back
  the loaded copy. So a scope row is locked through `ScopeEntity.lockRow`, which refreshes it.
  **A gate read outside its write's transaction is sound only where some serial order of the two writes explains
  the outcome.** It is not where a concurrent cleanup enumerates the rows it must clean (a member's removal, a purge)
  before the gated write's row exists: the write then re-checks under a lock the cleanup also holds, or the cleanup's
  selection is itself the lock. A share create, a re-issue and an item-favorite write take the scope row a removal
  holds; a group create and a group add take the tenant root a tenant removal holds; a purge selects with
  `FOR UPDATE` and a restore is a guarded update (cr-a-gate-holds-until-its-write-commits). A sign-in, a device wrap
  and a pairing delegation take the account row (`AccountStore.lock`) that a password reset, a password change, a
  key rotation and a sign-out everywhere else take first, and re-read under it what those change — the auth key, the
  caller's session (inbox #297, #316).
  The scope row is also the node tree's serializer: a node create, update, trash and restore and the trash purge
  take it first (`NodesStore.lockScope`); a guest's new identity and its upload re-check the link under the row lock
  its delete holds (`SharingStore.admitsGuests`).
  **A rule across different ids needs its own key.** "One personal scope per member and tenant" had no row to
  compare-and-set, and two creates under two ids collided on nothing. `ux_scope_personal_owner` is that row's
  compare-and-set, and `personalScopeExists` reads that key, so the loser's replay question is the index's own
  predicate.
- *(to decide)* Cross-context communication — direct call vs. domain events — settled when bounded
  contexts are cut.

This section fills as decisions are made; it intentionally starts nearly empty rather than with
speculative rules.
