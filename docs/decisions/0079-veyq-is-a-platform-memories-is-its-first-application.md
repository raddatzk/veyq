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

**10. One backend image.** A modular monolith: one container, one database, applications as packages of it.

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
- **The code matches this only in part.** Phase 1 of the change-request series (inbox #324) drew the backend
  boundary inside the one Gradle module: `com.veyq.core.{node,blob,sharing,quota,scope}` hold the node, blob,
  upload-session and share entities, the generic blob upload (start, windows, verify, download, session purge) and
  the storage quota, and nine interfaces through which core reaches Memories code without importing it —
  `ScopeEraser`, `NodeSubtreeEraser`, `MembershipEraser`, `GuestIdentityUsage`, `SharedSubtreeContributor`,
  `ShareGuestWrites`, `UploadCompletion` (what a verified upload becomes on a node of the kind, today Memories'
  media), `UploadAdmission` (what a share link's guests contributed, for its per-link limit) and `NodeKindRules`
  (where a node of the kind may live in the tree; core's own answers for `FOLDER`, Memories' for `MEMORY` and
  `CHAPTER`) — and no platform file imports Memories code. The last three fail closed like `ShareGuestWrites`: a
  kind no application (or two) claims takes no uploads (`illegal_target`) and is placed nowhere
  (`illegal_structure`), and a link whose usage no single application measures is refused
  (`share_not_contributable`). Hand-written core code names the node kind `FOLDER` only — the same guard holds it. Core still speaks media in one place: the completion item (`CompleteItem`'s
  `mediaId`, `derivatives`, `wrappedExif`), because core's share contract serves a guest's completion. (`MembershipEraser` is a different seam from the erasers: nothing of the scope is
  erased, one user loses access to one scope, and item favorites drop that user's rows.) Every class is named for its owner: the platform is `com.veyq.core.<feature>`, Memories
  `com.veyq.memories.<feature>`, and so is the generated API (`com.veyq.core.generated`, `com.veyq.memories.generated`):
  the contracts name their owner too — core's specs in `contracts/api/` (among them `blob.openapi.yaml`, the three
  generic blob operations, served by core's `BlobResource`), the Memories specs in `contracts/api/memories/`, every
  schema two owners share defined once in core's `_common.openapi.yaml`. Inside core the
  kernel/slice layering is a declared list, not a directory: the packages every slice builds on are `KERNEL` in
  `validate_kernel_imports_no_slices.py`, and a kernel package names no slice. Not done: the Gradle module split; the node `kind` is still the contract's
  closed `FOLDER`/`MEMORY`/`CHAPTER` enum, which core's generated API decodes.
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
- **Guards move with the code.** `validate_kernel_imports_no_slices.py` encodes slice → kernel; the
  application → core, never core → application and never application → application rules need the same form of
  guard (an import check over the moved packages, bite-tested with a core file importing an application) before
  the first package moves, or the direction is prose. The core → application half now exists in the same guard
  (no main file outside the Memories packages may import them — the platform derived as the inverse of the
  application, not as a list of directories) and on the web as
  `validate_web_core_imports_no_applications.py` (core may not import `features/memory`, discover code by glob, or
  name the Memories node kinds; no allowlist); application → application exists on neither side yet. The
  test trees stay out of scope of the import check for the reasons that guard's docstring gives.
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
