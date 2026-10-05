# ADR 0079 — veyq is a platform; Memories is its first application

- Status: Accepted
- Date: 2026-10-03

## Context

Everything veyq stores is already a sealed object in a scope, found by an opaque id, shared by a key wrap and
reached through a tenant's host. Nothing about that is specific to photos: the scope kinds, memberships, key
epochs, invitations, public shares, blobs with their quota, the trash and the node tree
(`veyq_node`, with a `kind` column — `FOLDER`, `MEMORY`, `CHAPTER` today) serve any sealed object. What is
specific to photos is a smaller set: the `MEMORY` and `CHAPTER` kinds, media and its derivatives, covers, the
gallery, the map and the live updates.

Other things the same people want to keep end-to-end encrypted and self-hosted have exactly that shape: a shared
expense ledger, an inventory of gear, a file tree. Building each as a separate product would copy accounts,
tenants, scopes, invitations, key rotation and sharing — the parts that took the longest and carry the
security argument. The decision is whether those are built once, as the platform every application stands on,
or once per product.

Measured 2026-10-03 (substrate: branch `main` of this repo, `grep`/`ls` over the tree): `veyq_media`,
`veyq_share` and `veyq_bucket` already key on a `node_id`; the backend had one feature package per area
(`features/{account,admin,content,media,sharing,spaces,tasks,platform}` under the then `com.veyq.app` package)
and `shared/`; the node `kind` is closed to `[FOLDER, MEMORY, CHAPTER]` in `contracts/api/content.openapi.yaml`.
That was the state when this was decided; what has moved since is under Consequences.

## Decision

**1. veyq is a platform; an application is a set of node kinds with its own domain data and its own client
screens.** **Memories** is the first one. The product language **Memory / Chapter** is unchanged and now belongs
to Memories ([CLAUDE.md](../../CLAUDE.md)); folders organize only. The term is "application" (German
*Anwendung*), not "plugin" — it is first-party code, not something installed.

**2. What is the platform ("core").** The server, tenants (resolved by `Host`, unchanged), users, the scope
kinds `TENANT` / `GROUP` / `PERSONAL`, memberships (role + grant), invitations and admit/deliver, key
management (epochs, rotation, revocation), public shares (viewer / contributor / uploader, password, reissue),
blobs and the storage quota, the trash, and **the node tree**: a domain-neutral tree of objects in a scope in
which every node has a `kind`, with folders and move / rename / trash. Application-neutral means the platform
never branches on what a kind means.

**3. What an application owns.** Its node kinds, its payload formats, its tables, its endpoints and its
screens. Memories owns `MEMORY` and `CHAPTER`, media and derivatives, covers, the gallery, the map and live
updates ([docs/apps/memories.md](../apps/memories.md)).

**4. Dependency direction.** An application may depend on core; core never depends on an application, and an
application never depends on another. Two applications that must refer to each other do so only through a
generic core object reference (a scope id and a node id), which each side resolves for the kinds it owns.

**5. Build-time only.** Applications are first-party and compiled in. There is no runtime loading of
application code, on the server or in the client. The reason is the security argument, not convenience: client
code can reach every key of the user's unlocked scopes, so code the operator or a third party can add after the
fact is code that can read everything ([ADR 0026](0026-client-trust-tiers.md)). What runs is what was reviewed
and released.

**6. An application keeps its domain data in its own tables, keyed by `node_id`** (`veyq_media` is the existing
instance). It does not widen `veyq_node` with columns of its own. The server side of an application is as thin
as it can be: store, authorize on opaque ids, serve ciphertext. Domain logic lives in the client, because the
client is the only party that can read the data.

**7. Payloads carry a schema version; old versions stay readable; migration happens on write.** The server
cannot migrate ciphertext, so a client must open every version it ever wrote, and re-seals into the current one
only when the user writes. This is the convention the sealed `wrappedMeta` blobs follow
([ADR 0077](0077-a-write-carries-the-version-it-read.md) names their concurrency); it is stated here for every
application.

**8. No per-tenant activation.** Every application is available everywhere and created on demand inside a
scope: many Memories, many expense groups, one inventory, one file tree per scope. A tenant owner does not
"turn an application on". The operator can still gate a node kind as a rollout flag through the capability
mechanism ([ADR 0032](0032-capability-discovery-version-gating.md)); that is an instance-wide switch with a
`422 capability_disabled`, not a per-tenant product setting.

**9. Crypto does not change.** No existing format, seal purpose or suite id moves; nothing is re-encrypted.
A new node kind adds seal purposes, which is additive ([ADR 0072](0072-a-seal-binds-its-object.md): a new
variant plus its vector is backward-compatible). For a **new** application, its keys are derived from the scope
key by HKDF with the application id as context (`veyq/v1/<application id>:…`, the form the existing derivations
use in `packages/crypto/src/v1.ts`), so two applications never share a key by accident; the seals' associated
data binds the application id or kind, the object id and the version, by the ADR 0072 mechanism. **Memories'
existing keys are the documented exemption**: they derive as they always have, and are not re-derived under a
Memories context — that would be a suite `v2` and a re-encryption for no gain, since there is no earlier
application to be separated from.

**10. One backend image.** A modular monolith: one container, one database, one deployable — the root build's one
fast-jar. Core and each application are build modules of it, compiled in and never built, published or deployed
on their own.

## Consequences

- **The node tree is the platform's, not Memories'.** Rejected: the tree owned by Memories. Every other
  application would then need a tree of its own and, with it, its own sharing, trash and move rules — the
  duplication this decision exists to avoid. The price is that the tree's `kind` is a column the platform must
  keep open to values it does not interpret.
- **What the server learns grows by one fact.** The node `kind` is already a server-visible column; once
  further kinds exist it also tells the operator which applications a scope uses, and the rows, timestamps and
  sizes each kind produces. [security-model](../crosscutting/security-model.md) lists it.
- **An append-only application cannot make the server keep its order.** The server may drop or reorder entries
  it holds unless a sequence number or hash chain is bound into each entry's associated data (ADR 0072 binds an
  object, not its neighbours). Each such application states that gap; it is not a platform property.
- **The backend is split as decided; core still knows Memories in named places.** Core and each application are
  Gradle modules below `backend/`: `:core` (`backend/core`) and `:apps:<name>` (`:apps:memories` in
  `backend/apps/memories`); the test helpers they share are `:core`'s `java-test-fixtures` source set, on no
  runtime classpath. The root `backend/`
  holds no main code; it composes the modules into the one fast-jar, where each module's jar is
  `lib/main/me.raddatz.veyq.<module>.jar`. The direction is the build's: `:core` has no edge to an application (Gradle
  refuses it as a cycle, so a core file importing Memories does not compile), and `validate_backend_module_graph.py`
  holds what Gradle allows — no application depends on another, no package is declared in two modules, every module
  is included, indexed (an unindexed module boots green with its routes answering 404 and its seam implementations
  missing — measured on the deliverable), copied by the Dockerfile and named. Every class is named for its owner —
  the platform `me.raddatz.veyq.core.<feature>`, an application `me.raddatz.veyq.<application>.<feature>`, the generated API
  `me.raddatz.veyq.core.generated` / `me.raddatz.veyq.<application>.generated` — and so are the contracts: core's specs in
  `contracts/api/` (`_common.openapi.yaml`, which every shared schema is defined in once, is core's), an
  application's in `contracts/api/<application>/`. The package root `me.raddatz.veyq` — also the Gradle `group`, so
  the module jars' prefix — is the owner's domain `raddatz.me` reversed; it was `com.veyq` until
  2026-10-05, and the rename moved no class between packages. Each module owns its migrations under one global version scheme
  (`V<yyyymmddHHMM>__<module>_<slug>.sql`, the module named by the settings; [backend/CLAUDE.md](../../backend/CLAUDE.md)
  § Migrations): a core migration names no application table, an application's references only core's and its own.
  Core reaches application code through nine interfaces — `ScopeEraser`, `NodeSubtreeEraser`, `MembershipEraser`,
  `GuestIdentityUsage`, `SharedSubtreeContributor`, `ShareGuestWrites`, `UploadCompletion` (what a verified upload
  becomes on a node of the kind, today Memories' media), `UploadAdmission` (what a share link's guests contributed,
  for its per-link limit) and `NodeKindRules` (where a node of the kind may live; core answers for `FOLDER`, Memories
  for `MEMORY` and `CHAPTER`) — and the last four fail closed: a kind no application (or two) claims takes no
  uploads (`illegal_target`), is placed nowhere (`illegal_structure`), is shared by no one (`illegal_scope`), and a
  link whose usage no single application measures is refused (`share_not_contributable`). (`MembershipEraser` is a
  different seam from the erasers: nothing of the scope is erased, one user loses access to one scope.) Hand-written
  core code names the node kind `FOLDER` only (`validate_kernel_imports_no_slices.py`). Each module runs its own
  tests; `:core`'s test application is core with no application at all, and `CoreAloneSliceTest` pins what that
  means — every seam holds exactly core's own implementations, every refusal above holds, and the generic blob
  upload starts, windows and downloads — while the root's `ModuleCompositionTest` pins the composed sets.
  **What core still knows of Memories**, each a decision a later change takes, not an oversight: the node `kind` is
  the contract's closed `FOLDER`/`MEMORY`/`CHAPTER` enum, which core's generated API decodes (so the convention is
  guarded, not typed); `veyq_node` carries Memories-flavoured columns in core's baseline (`cover_blob_id`,
  `cover_byte_size`, `media_revision`, `delete_revision` — moving a column is a schema change, not a rename);
  `ShareGuestWrites.supports` gates share *creation*, so core alone refuses every share and share policy is tested
  with an application present; the guest completion item is media-shaped (`CompleteItem`'s `mediaId`,
  `derivatives`, `wrappedExif`) because core's share contract serves the two `/api/shares/{shareId}/media`
  operations (moving them out needs a core guest-write gate service first); `BlobRole`
  (`ORIGINAL`/`THUMBNAIL`/`PREVIEW`/`COVER`) is a closed enum in core's `blob.openapi.yaml`; and the upload retention
  and purge keys keep their `veyq.media.*` names though core reads them.
  **Open prerequisites for the next application** (on inbox #325–#327, where it will be refined): a generic
  `veyq_node_attachment(node_id, slot, blob_id)` table with real foreign keys, built with the first application that
  needs a blob (Expenses or Files) and cut on that case — `validate_blob_reference_arity` pins today's three
  `veyq_blob` arms, so a fourth is red until the reclaim predicates learn it; the generalisation of the cover columns
  and revision counters above; and a seal purpose plus key rule for blobs that are not Memories' — the server side
  is neutral (a blob's `K_blob` wrap, `owner_id` and `blob_role` are opaque strings core stores and compares), but the
  `blob-key` seal derives under a Memory's `K_mem` and `BlobRole` is closed in three places (the contract, the crypto
  package, its web re-export), so a new role or key is an [ADR 0072](0072-a-seal-binds-its-object.md) stored-format
  decision, not a rename. The node `kind` open to values core does not interpret is not decided yet.
  **The web side is separated by import direction, not by name or by kind:** `web/src/features/memory` is the
  application, and no core file imports it except the composition root `web/src/applications.ts`; the shell asks a
  build-time registry (`web/src/core/applications/registry.ts`) what to render for a node kind, which slot an
  application fills, which body renders a public share, and what runs when a scope is entered or reset and when the
  Master Key rotates (that hook is required: a rotation refuses to start without it). Not done on the web: the
  directory is still named `features/memory`; the level view, the create/rename dialog, the share dialog, the
  top-bar tray and the sidebar pins are single-claim slots shaped for Memories (the second application re-cuts them
  per kind); the root kind of an UPLOADER share is unknown to the client (the unlocked response serves no nodes), so
  it renders in the one application that claimed the kind-less drop until `PublicShare` carries a `rootKind`
  (inbox #329); the i18n catalog is one file and `styles/memory.css` is global; `NodeKind` is the generated closed
  enum, so the convention that core never names `MEMORY`/`CHAPTER` is guarded, not typed.
  Docs describing the target say which of these they rely on.
- **Guards move with the code, and the build is one of them.** On the backend, core → application is the
  compiler's (no edge, so no import) and every other direction is `validate_backend_module_graph.py`'s — including
  application → application, bitten with a forged second application because no real one exists; inside core,
  `validate_kernel_imports_no_slices.py` keeps the kernel → slice rule (a declared kernel list) and the node-kind
  rule, which no import check can see. On the web there is no module boundary, so the direction is a text guard:
  `validate_web_core_imports_no_applications.py` (core may not import `features/memory`, discover code by glob, or
  name the Memories node kinds; no allowlist). Application → application has no web guard yet — there is one
  application. The test trees stay out of the import checks, for the reasons the kernel guard's docstring gives.
- **Rejected: a generic record store** (core offers "put a sealed record under a key", applications are just
  clients of it). It would make the server a database of blobs with no shape, give up per-kind authorization,
  quota and trash rules, and push every integrity rule into clients that cannot see each other.
- **Rejected: runtime plugins.** Decision 5.
- **Rejected: a container per application.** It multiplies the deployment (compose and Helm, which must answer
  together — [ADR 0064](0064-kubernetes-helm-deployment-target.md)) for no isolation the shared database does not
  already defeat, and works against *self-hostable by default*.
- **Rejected: per-tenant activation.** It adds a settings surface, a state machine for "activated but empty", and
  a per-tenant gate on every endpoint, to solve a problem — clutter — that "created on demand" already solves.
- **Rejected: re-deriving Memories' keys under an application context** — Decision 9.
