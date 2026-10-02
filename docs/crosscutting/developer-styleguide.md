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
  The **application layer is use-case-sliced** — `features/<area>/<use-case>/…Handler` (a use-case may
  have several handlers); this is where cohesion and the trust chain live. The **HTTP adapter layer is
  resource-shaped** — one `…Resource` per generated `…Api` interface (OpenAPI tag), *not* per use-case,
  because one endpoint can serve several use-cases. Use-case slicing is what carries the trust chain; the
  adapter shape is necessarily per-stack.
- **Cross-cutting code lives in `shared/`** — `backend/src/main/kotlin/com/veyq/app/shared/`. **The directory is
  the list**; it cannot be out of date about itself, whereas the roll-call that used to stand here named six
  slices of which two existed and omitted nine. `shared/` must not depend on a feature;
  [`validate_shared_imports_no_features.py`](../../scripts/validate_shared_imports_no_features.py) holds that for
  its imports.
- **Dependencies stay directed.** If a feature builds on a `shared/` slice, that slice must not reach back into
  the feature; share neutral rules through small interfaces.

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

## 4. Input validation & sanitization

- **The OpenAPI schema is the source of truth for input shape and limits.** Declare constraints on
  request fields (`maxLength`/`minLength`, `pattern`, `format`, `minimum`/`maximum`, `required`).
  Required-field + structural validation is enforced by generated bean validation → a malformed body is
  rejected **before the handler** (400).
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
  `wrappedBlobKey` checks. Parameters are exempt: the `jaxrs-spec` API generator derives their annotations
  itself (`useBeanValidation`). Enforced by `scripts/validate_request_bounds.py`. On a list, Hibernate
  Validator logs `HV000271` (a container-level `@Valid` is deprecated in favour of the type argument) — which
  the `kotlin` generator cannot place, so the field form stays; `MediaSliceTest.startBlobUploadsValidatesInsideEachItem`
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
  common subset); Flyway is the single schema authority.
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
  unnoticed and stops a production start.

## 10. Testing

- **Slice tests** boot a fresh in-memory DB per suite. Cover **positive and negative** paths where
  behavior has real success/failure branches; prefer risk-based coverage over percentages.
- **Conformance tests** pin cross-stack crypto (§9).
- **Test data comes from one fixture per surface, not a local copy.** In the backend a single test-fixtures
  object seeds users, tenants, member scopes and nodes and mints the `Bearer` header; a test keeps a local helper
  only for data no other test needs. Measured cost of the copies it replaces: 50 `seedUser`s, so a new
  `NOT NULL` column on `veyq_user` touched 60 files. In `web/` a test mounts with the **real** i18n catalog
  (`realI18n()` from `src/test/i18n.ts`), where a key the catalog lacks fails the test, and fakes an HTTP failure
  with the one shared `httpError` (`src/test/httpError.ts`); a lint rule keeps stub catalogs out. Hand-written
  stub catalogs kept 13 keys the app no longer has passing green, and ten local `httpError`s came in four shapes,
  two of which cannot carry an error code.
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
- **HTTP failure projection → `shared/http/`.** A backend operation's sealed `…Outcome` failure variants project
  to the wire `ErrorResponse{code}` through the **single** `shared/http/` envelope writer + status-bucket table;
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
  - **A row that other writers transition by status is never saved as a managed entity.** Without
    `@DynamicUpdate` the flush writes every column, so a writer that changed only another column writes back the
    status it read: a tenant rotation's re-tag brought a revoked invite back to `ACTIVE`. Such a writer updates its
    own columns in a bulk update guarded on the live status (`TenantStore.carryInvites`).
  - **When the decision reads more than one row, the serializing write is a row lock on their parent, taken
    first.** `SessionService.rotate` locks the session family before it reads the presented and the `CURRENT`
    refresh token: their own row locks ordered only presentations of the same token, so an orphan and the current
    token both rotated and the chain forked ([ADR 0004](../decisions/0004-session-tokens.md)).
- *(to decide)* Cross-context communication — direct call vs. domain events — settled when bounded
  contexts are cut.

This section fills as decisions are made; it intentionally starts nearly empty rather than with
speculative rules.
