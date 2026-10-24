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
to Memories ([CLAUDE.md](../../CLAUDE.md)); a folder organizes — it holds no content, and like every node it carries
a key and can be the root of a share ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)).
The term is "application" (German
*Anwendung*), not "plugin" — it is first-party code, not something installed.

**2. What is the platform ("core").** The server, tenants (resolved by `Host`, unchanged), users, the scope
kinds `TENANT` / `GROUP` / `PERSONAL`, memberships (role + grant), invitations and admit/deliver, key
management (epochs, rotation, revocation), public shares (viewer / contributor / uploader, password, reissue),
blobs and the storage quota, the trash, and **the node tree**: a domain-neutral tree of objects in a scope in
which every node has a `kind` and a content key, with folders and move / rename (one placement write) / trash. Application-neutral means the platform
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

**8. No activation step, but an application can be switched off.** Every application is available everywhere
and created on demand inside a scope: many Memories, many Expenses Pots, one inventory, one file tree per scope.
Nobody "turns an application on", so there is no "activated but empty" state. What the operator (for the
instance) and a tenant owner (for their tenant) can do is switch an application, or one of its functions,
**off** through the capability register ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)):
each application is an entry of its own (`memories`, `depot`, `expenses`), switchable by both. **Off, an application
is read-only** (owner 2026-10-23): the server refuses with `422 capability_disabled` every write of it — a node of its
kinds created, renamed, moved, copied or imported, its records and blobs written, a link rooted on one of its nodes
created or given a write role, a guest's write through any link — while what exists stays readable and downloadable,
through the links that exist too, so nobody's data is trapped. Taking something out is never refused: a delete to the
trash and its restore (a restored node is read-only like the rest), an erasure and a moderation measure go on, and so
do a member's own marks on its items ([ADR 0068](0068-favorites-two-confidentiality-levels.md)), which a Master-Key
rotation re-tags through the same write, and so does a rotation's re-key, which is the security of what
exists. While it is off, every member of the tenant sees a banner at the top of veyq that says so, so there is time to
save what is in it, and the links that exist keep serving it, read-only (owner 2026-10-24). Switched on again,
everything is as before; nothing was deleted. Off is a window, not an end: erasing an application's data after it is
open (inbox #539). Built: core's `ApplicationSwitch`, asked by kind and by name, and its sweep over every application's
operations ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) Consequences).

**9. Crypto does not change.** No existing format, seal purpose or suite id moves; nothing is re-encrypted.
A new seal purpose would be additive ([ADR 0072](0072-a-seal-binds-its-object.md): a new
variant plus its vector is backward-compatible). Core has added one such purpose, `blob-meta`: a blob's type, upload
name and modification time, sealed under the blob's own `K_blob`, declared with the upload and stored write-once on
the blob row. It is the platform's, carried by every application's blobs alike (Depot's name of what was uploaded,
Memories' original file name and the media type its gallery badges a video by), so it is no exception to the rule below that an application needs no purpose of its
own. For a **new** application, its keys are derived from the scope
key by HKDF with the application id as context (`veyq/v1/<application id>:…`, the form the existing derivations
use in `src/packages/crypto/src/v1.ts`), so two applications never share a key by accident; the seals' associated
data binds the application id or kind, the object id and the revision, by the ADR 0072 mechanism. **Measured with
Depot (cr-files-application P3-S1/P5), a new kind meets both rules with no new seal purpose and no HKDF context:**
its node key is random and wrapped under its parent's key ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)),
so it shares no key with another application's node by construction, which is what the HKDF context was for; and
its blob's `blob-key` seal binds `ownerId` (the owning node's id) and `role` — an open token the application
defines, Depot's `FILE_CONTENT` — which names the application's object and kind. **Memories'
existing keys are the documented exemption**: they derive as they always have, and are not re-derived under a
Memories context — that would be a suite `v2` and a re-encryption for no gain, since there is no earlier
application to be separated from.

**10. One backend image.** A modular monolith: one container, one database, one deployable — the root build's one
fast-jar. Core and each application are build modules of it, compiled in and never built, published or deployed
on their own.

## Consequences

- **The node tree is the platform's, not Memories'.** Rejected: the tree owned by Memories. Every other
  application would then need a tree of its own and, with it, its own sharing, trash and move rules — the
  duplication this decision exists to avoid. The price is that the tree's `kind` holds values core does not
  interpret, and how is decided (2026-10-06, cr-platform-contract-split): **node kinds are open.** The node
  `kind` is an open, bounded token on the wire (`^[A-Z][A-Z0-9_]{0,15}$`). Core names `FOLDER` only; each
  application brings its kinds and their rules (`NodeKindRules`, the web registry). A kind no application claims
  is refused by the fail-closed seams, never by decoding. `PublicShare` carries the root's kind (inbox #329).
  The token is the code: the backend carries the kind as a string and a request's shape is checked at the
  boundary (400), the web types it as a string with core's one `FOLDER` constant. `PublicShare.rootKind` is the
  root row's kind as stored, served for every unlocked role (a `WRITE_ONLY` guest, served no node, learns this one
  structural fact) and omitted on a locked response and while the root is in the trash; the public share page renders the body the kind's application
  claimed, whatever the role — for a `FOLDER`, core's own body, which embeds the body claimed for each child's kind.
- **A node's URL carries its application's slug** (decided 2026-10-11, inbox #328; the grammar is
  [ADR 0015](0015-operator-blind-readable-urls.md)'s amendment). The path stays in the `#fragment`: a folder ends
  in a slash, a node of an application is `…/<route slug>/<name>` (a Memory: `#foo/memories/memory`), and Depot —
  the one application that **declares** it has no segment — is its plain name (`#foo/memory`). Each application
  claims its slug once in the registry (`claimRouteSlug`, a second claim throws, platform words are refused) or
  declares `claimNoRouteSegment`; a claimed slug is reserved among folder and file names, which are numbered
  around it (`memories-2`). Rejected: history-mode `/<app>/:nodeId` URLs — they put a stable per-node id into the
  operator's access logs on every hard load and need a scope lookup the fragment never did.
- **What the server learns grows by one fact.** The node `kind` is already a server-visible column; once
  further kinds exist it also tells the operator which applications a scope uses, and the rows, timestamps and
  sizes each kind produces. [security-model](../crosscutting/security-model.md) lists it.
- **What the platform is** (decided 2026-10-06, cr-platform-contract-split). veyq is a platform for sealed,
  shareable objects. The core provides:
  - **who**: tenants, central users, groups and personal scopes, memberships, invitations, keys, devices;
  - **what**: nodes as a tree inside a scope, with parents, order, trash and a content key per node; and
    *attachments*, sealed files on a node that can be uploaded and downloaded, with size and storage quota;
  - **with whom**: links with `READ`, `READ_WRITE` or `WRITE_ONLY` access, with password, expiry and consent;
  - **in the background**: tasks, rate limits, the admin channel and operator-blindness.

  An application supplies the **meaning**: which node kinds exist, what the plaintext means, which rules apply to
  structure, attachments and guests, and the user interface. `READ`/`READ_WRITE`/`WRITE_ONLY` are the wire values
  (`ShareRole`, renamed from `VIEWER`/`CONTRIBUTOR`/`UPLOADER` on 2026-10-06).
  Attachments are core's since cr-files-application P3 (`veyq_node_attachment`, written only by
  `AttachmentStore`), and Depot is the first application on them (a `FILE` holds one blob in its slot), the node
  cover the second (its `COVER` slot, inbox #341 - core's own feature since inbox #411: any node of a kind that opts in
  carries one, see below); what a verified upload becomes is still each application's
  (`UploadCompletion`), and Memories' media and gallery counters keep their own tables. Depot, the second application, needed them and
  nothing else of a blob's; whether every application does, the next one shows. Depot is an application on core's
  attachments, never a dependency of another application (Decision 4: an application depends on core only).
- **Contracts by owner** (decided 2026-10-06, cr-platform-contract-split). A core spec describes what core does.
  Where a value's meaning belongs to an application — a node's sealed payload, a blob role, a kind — its
  description says whose it is and names no application's plaintext, operation or key. An operation whose
  behaviour is an application's lives in that application's directory, even when its path sits under a core
  route (`/api/shares/{shareId}/media` is Memories': `contracts/api/memories/share-media.openapi.yaml`, its own
  tag and root — the two guest writes moved there with the wire unchanged, and the media a share lists left
  `PublicShare` for its own read there, a wire change). Core's spec texts and its main comments describe what core
  does.
- **The node's payload slot and content key are core's** (decided 2026-10-06, cr-platform-contract-split). Core
  gives every node one sealed, versioned payload (`wrappedMeta`, `metaRevision`): core stores it, compare-and-sets
  it ([ADR 0077](0077-a-write-carries-the-version-it-read.md)) and lists it with the tree, and never opens it. Its
  plaintext format is the node kind's application's, versioned per Decision 7. `wrappedContentKey` is the content
  key of every node, `FOLDER` included ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)),
  chained so a share's guest derives every descendant's key. These are platform columns,
  not an application widening `veyq_node` (Decision 6). That only one application fills them today makes this an
  intermediate state, re-examined when a second application does. Rejected: moving the payload into a Memories
  table with endpoints — it loses the atomic create and the payload in the tree listing that sorting relies on,
  and the next application would want the same slot back.
- **The drop key is the `WRITE_ONLY` link's mechanism** (decided 2026-10-06, cr-platform-contract-split).
  A `WRITE_ONLY` link's drop key pair belongs to core's link mechanism:
  - the public half is sealed under the link's `K_share` on the share row;
  - the private half is the shared root node's, sealed under the root's content key, set once by core inside the
    share creation (409 `drop_key_exists`/`drop_key_missing`);
  - it stays a field of its own and never joins the node payload.

  The seal purposes `drop-public-key`/`drop-private-key` are a stored format and keep their names.
- **Three access modes** (decided 2026-10-06, cr-platform-contract-split). A link grants one of three access
  modes: `READ`, `READ_WRITE`, `WRITE_ONLY` — `ShareRole`'s wire values. Core enforces the mode (who
  may read the shared subtree, who may add through the guest transport). Which roles a node may be shared with is
  its kind's owner's (`ShareRoots`). A `READ_WRITE` guest's tree operations — create, move, rename — are core's,
  behind the gate, and each container's owner decides them inside it (`GuestNodeRules`: core allows them in a
  `FOLDER`, Memories nothing in a Memory or chapter); what "write" means on an application's node is the
  application's, through `ShareGuestWrites`, asked for the kind of the node written into — the root or a node below it
  the guest names ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 6). The
  modes are bound in no seal. Application-specific roles, if ever
  needed, come through a policy seam where core asks the application whether an action is allowed for a mode —
  never through a registry of roles.
- **Application guest operations** (decided 2026-10-06, cr-platform-contract-split). Core owns the public link and
  its guest gate: active link, password, the mode's capability, the guest identity's token, and the per-link
  upload budget where the operation spends one. Core exposes the gate as a service an application calls before its
  own guest read or write. Core's share contract keeps only kind-neutral guest operations: resolve (the link plus
  the shared nodes, their names and keys, no application data), terms, blob transport and download, guest identity,
  and the tree operations (`createShareNode`, `updateShareNode`). An
  application serves its rows and its writes on its own route under the share, checks that the kind of the node it
  writes into is its own, and carries the privacy rules of its rows with them (for Memories:
  [ADR 0030](0030-contributor-public-share.md) point 8). Partly the code: the gate is core's service
  (`ShareGuestGate`), and Memories' two guest writes (complete an upload, delete one's own) are its own operations
  that call it (`ShareMediaHandler`), handing the gate's re-check of the link on unevaluated to the transaction that
  attaches. Its guest read is its own operation too (`listShareMedia`): the media under the gate's live nodes with
  Memories' own live predicate, and the wrapped keys of the `WRITE_ONLY` identities whose media those are — the
  ADR 0030 point 8 rule nulls every other identity id in the same handler, so the gate carries no identity set and
  core's resolve serves no application data. A link may later become a credential core's gate accepts for
  application operations that declare it; that is a separate decision.
- **An append-only application cannot make the server keep its order.** The server may drop or reorder entries
  it holds unless a sequence number or hash chain is bound into each entry's associated data (ADR 0072 binds an
  object, not its neighbours). Each such application states that gap; it is not a platform property.
- **The backend is split as decided; core still knows Memories in named places.** Core and each application are
  Gradle modules below `src/backend/`: `:core` (`src/backend/core`) and `:apps:<name>` (`:apps:memories` in
  `src/backend/apps/memories`); the test helpers they share are `:core`'s `java-test-fixtures` source set, on no
  runtime classpath. The root `src/backend/`
  holds no main code; it composes the modules into the one fast-jar, where each module's jar is
  `lib/main/me.raddatz.veyq.<module>.jar`. The direction is the build's: `:core` has no edge to an application (Gradle
  refuses it as a cycle, so a core file importing Memories does not compile), and `validate_backend_module_graph.py`
  holds what Gradle allows — no application depends on another, no package is declared in two modules, every module
  is included, indexed (an unindexed module boots green with its routes answering 404 and its seam implementations
  missing — measured on the deliverable), copied by the Dockerfile and named. Every class is named for its owner —
  the platform `me.raddatz.veyq.core.<feature>`, an application `me.raddatz.veyq.<application>.<feature>`, the generated API
  `me.raddatz.veyq.core.generated` / `me.raddatz.veyq.<application>.generated` — and so are the contracts: core's specs in
  `contracts/api/` (`_common.openapi.yaml`, which every shared schema is defined in once, is core's), an
  application's in `contracts/api/<application>/`. The build derives the owner from that place, never from a list
  (inbox #344, built before the second application): a directory below `contracts/api/` holding a spec is the
  application `:apps:<directory>`, generating into `me.raddatz.veyq.<directory>.generated`; a spec in a directory with
  no such module, or nested deeper, fails the build while it configures. The package root `me.raddatz.veyq` — also the Gradle `group`, so
  the module jars' prefix — is the owner's domain `raddatz.me` reversed; it was `com.veyq` until
  2026-10-05, and the rename moved no class between packages. Each module owns its migrations under one global version scheme
  (`V<yyyymmddHHMM>__<module>_<slug>.sql`, the module named by the settings; [src/backend/CLAUDE.md](../../src/backend/CLAUDE.md)
  § Migrations): a core migration names no application table, an application's references only core's and its own.
  Core reaches application code through fourteen interfaces — `ScopeEraser`, `NodeSubtreeEraser`, `MembershipEraser`,
  `GuestIdentityUsage`, `SharedSubtreeContributor`, `ShareGuestWrites`, `ShareRoots` (which roles a node of the kind may
  be the root of a share with; core answers for `FOLDER`, Memories for `MEMORY` and `CHAPTER` — [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)),
  `GuestNodeRules` (which tree operations a `READ_WRITE` guest may do inside a container of the kind; core answers for
  `FOLDER`, Memories declares none, so a guest creates, moves and renames nothing inside a Memory or a chapter — ADR 0080),
  `WriteOnlyGuestKinds` (which kinds a `WRITE_ONLY` guest may create inside its own identity folder, asked of the link's
  root kind; Depot answers `FILE` for its drop box, core and Memories declare none), `GuestCreatableKinds` (which of an
  application's kinds a `READ_WRITE` guest may create inside a `FOLDER`, besides the folder core always allows; Depot
  declares `FILE`, Memories none — a union whose kinds must be disjoint: a kind two applications declare is created by
  nobody and fails startup, ADR 0080 decision 6), `UploadCompletion` (what a verified upload
  becomes on a node of the kind: Memories' media, Depot's one attachment of a `FILE`; the seam stays core's, and its item is generic — an
  `ownerId`, role-tagged derivatives and the application's sealed payloads as opaque values, which core carries to
  the one implementation that accepts the kind and never reads (2026-10-06, cr-platform-contract-split;
  `UploadCompletionGenericItemCoreSliceTest`); Memories maps its request items onto it and
  `ownerId` back to its `mediaId`, Depot carries the blob a replace expects as a payload and calls core's
  `AttachmentStore.attach`), `UploadAdmission` (what a share link's guests contributed to the application's own rows, for its
  per-link limit; their open sessions and node attachments core sums itself, and a kind whose guest uploads are all
  attachments still needs one, reporting 0), `MemberBlobRoles` (which blob roles a member's upload start may carry: Memories
  its four, Depot `FILE_CONTENT`; a union, not a partition, since a member's start names no node to dispatch by —
  inbox #366) and `NodeKindRules` (where a node of the kind may live; core answers for `FOLDER`, Memories
  for `MEMORY` and `CHAPTER`) — and `ShareRoots`, `GuestNodeRules`, `WriteOnlyGuestKinds`, `GuestCreatableKinds`,
  `UploadCompletion`, `UploadAdmission`, `MemberBlobRoles` and `NodeKindRules` fail closed: a role no application declares opens no member upload (`400 invalid_request`, before
  any quota is reserved), and a kind no application (or two) claims takes no
  uploads (`illegal_target`), is placed nowhere (`illegal_structure`), is shared by no one (`illegal_scope`), takes no
  guest tree operation inside it (`illegal_scope`), lets a write-only guest of a link rooted at it create nothing
  (`illegal_scope`), is created by no folder guest (`illegal_scope`), and a link whose usage no single application measures is refused
  (`share_not_contributable`). (`MembershipEraser` is a
  different seam from the erasers: nothing of the scope is erased, one user loses access to one scope.) Hand-written
  core code names the node kind `FOLDER` only (`validate_kernel_imports_no_slices.py`). Each module runs its own
  tests; `:core`'s test application is core with no application at all, and `CoreAloneSliceTest` pins what that
  means — every seam holds exactly core's own implementations, every refusal above holds (so a member's upload
  start is refused in every role: nothing could attach it), and an open upload session windows and a blob downloads
  through core alone — while the root's `ModuleCompositionTest` pins the composed sets. `:core`'s test sources hold
  one stand-in, `CoreTestMemberBlobRoles` (the roles core's own upload tests start in), which `CoreAloneSliceTest`
  excludes.
  **Core's schema and config name no Memories concept** (inbox #341, #342, 2026-10-10): a Memory's uploaded cover is
  the node's `COVER` attachment (below), its gallery counters (`revision`, `delete_revision`, ADR 0033) were
  Memories' own `veyq_media_revision` row per node until inbox #410 lifted the mechanism into core: the table is now
  core's `veyq_node_items_revision` (a foreign key onto `veyq_node`, not cascading, erased by core's node-subtree
  eraser at phase 300 and the scope's node eraser; the node's first item change births it under the node row's lock),
  written through core's `NodeItemsRevisions.bump`, read through its `read` (the `304` / delta / delete-gated id set),
  with the shared wire pieces in `_common.openapi.yaml` and the client half in `src/web/src/core/items/` - the platform's way
  for **any** application to publish a list of records inside a node, Memories' media its first user (Expenses' entries
  and Inventory's items the next); and the never-read `cover_byte_size` is gone (a blob's size is its row's). Core's upload-session keys are
  `veyq.blob.upload.*`; Memories' trash keys keep `veyq.media.trash.*` under its own `@ConfigMapping`
  (`MemoriesMediaConfig`), whose `@WithDefault`s are their only defaults — SmallRye refuses a `veyq.*` key no
  mapping claims, so a line in core's base config would fail core's boot without Memories. The drop key
  (`veyq_node.sealed_drop_private_key`) stays core's: Depot's drop box and Memories' uploader links both seal to it.
  Core's attachments, built with Depot (cr-files-application P3): `veyq_node_attachment` (one blob per node slot, the slot
  being the application's role token; foreign keys to scope, node, blob and guest identity, none cascading, so a
  row goes only through core's erasers and `deleteBlobRow`), written only by `AttachmentStore` (`attach`, and
  `detach` for an owner's own remove — core's `NodeCoverStore` since #411, Memories' `deleteCoverBlob` before it) and counted as
  core's own term of a link's usage — `validate_blob_reference_arity` pins the `veyq_blob` arms, all three foreign-key
  backed since the cover column left; and `BlobRole`,
  an open token (`^[A-Z][A-Z0-9_]{0,15}$`, like `NodeKind`) in the contract's `_common` and in the crypto package,
  core stores and compares it and names none (`validate_kernel_imports_no_slices.py` reads Memories' four roles
  as it reads its kinds), and Memories holds its own (`MemoryBlobRoles`, `MemoryBlobRole`) and refuses a guest's
  `COVER` through `ShareGuestWrites.admitsGuestRole`.
  **A cover is a node feature, core's** (inbox #411, 2026-10-12): any node whose kind opts in (`NodeKindRules.takesCover`:
  core's `FOLDER`, Depot's drop box, Memories' two kinds; not a `FILE`) carries one image its owner uploads as the node's own
  blob in the `COVER` slot, written by core's `setNodeCover` / `removeNodeCover` - the blob step (`AttachmentStore` attach
  with a compare on the blob replaced, or detach) and the node's re-sealed `wrappedMeta` (the `cover` field, ADR 0077
  revision-checked) in ONE transaction, so the slot and the meta that names it never part. `COVER` is a role core declares
  (`CoreMemberBlobRoles`) and the opt-in is a seam on each kind's own placement rules, so core names no application kind. The web
  mechanism (`core/node/cover/`: the model, the upload, the one writer, the store, the action, the one dialog and the display) serves every kind;
  an application adds only what is its own - Memories says where an equal picture is already stored (`cover.findStored`: the
  PREVIEW of a gallery medium whose original is the picked file), so the cover is a second blob row on that stored object
  instead of a second upload ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md) § 5). A cover is set only by an
  upload in that dialog (owner 2026-10-19: set casually from a photo, members overwrote each other's choice unnoticed), so
  the photo source Memories once added is gone; a stored photo reference reads as no cover. **Residual, accepted** (inbox #505):
  a shared cover row names the medium's PREVIEW blob, so the operator can tell which medium is the cover. A guest sees the
  cover through the share's gated blob route and gets no action in this step.
  **The web side is separated by import direction, not by kind:** `src/web/src/features/memories` is the
  application, and no core file imports it except the composition root `src/web/src/applications.ts`; the shell asks a
  build-time registry (`src/web/src/core/applications/registry.ts`) what to render for a node kind, which slot an
  application fills, which body renders a public share, and what runs when a scope is entered or reset and when the
  Master Key rotates (that hook is required: a rotation refuses to start without it). An application's directory is
  named for its owner, `src/web/src/features/<owner>` (Memories' moved from `features/memory`, inbox #324), and the web
  guard derives it from the owner name instead of listing it, so a directory named otherwise is a missing one and red.
  The folder level view is core's (`core/node/LevelView.vue`) and each
  application contributes ROW DATA per kind through the registry (inbox #407: the sections are retired, core draws ONE
  list of the folders and every kind's rows — a factory per application answers a row per node: icon or cover, title,
  meta line, primary click and rights-gated actions) — naming the presentations it switches between beyond core's grid
  and list (`views`, Memories' map), whose switch is the level's toolbar, core's (inbox #370); the level shows the stored
  mode only where something on screen offers it, else the grid, and hands that mode to every row factory
  (`LevelRowsContext.viewMode`, inbox #404: no factory reads the stored preference) — and so its create and share dialogs (a folder's
  create and every node's rename are core's own dialog, FOLDER's share dialog is core's), while the top-bar tray and
  the sidebar pins are ordered list slots each application adds its own item to (the upload status in the top bar is
  core's own, listing every application's uploads, so no application adds it). **Strings and styles follow the
  directories (inbox #513, #517):** each application's i18n namespaces (`memory`/`media`, `depot`) are its own catalog,
  `features/<owner>/i18n.ts`, which the composition root's catalog half (`src/web/src/applicationMessages.ts`, data only, so
  every test file gets it) adds at build time to core's one vue-i18n instance — write-once, a namespace already held
  throws; core's catalog (`i18n/index.ts`) holds core's alone. Each application's global classes are its own
  stylesheet, imported by the application with their dark twins: Memories' `features/memories/memories.css` (the old
  `styles/memory.css`'s Memories part and the media lightbox of the old `styles/media.css`), Depot's
  `features/depot/depot.css` (the PDF text layer of the old `styles/pdf.css`); the core part of the old
  `styles/memory.css` went to core (the rendered Markdown to `styles/markdown.css`, the image cropper's classes renamed
  `veyq-cropper*` in `shared/ui/ImageCropper.vue`), and `styles/` holds no application's sheet any more. An
  application's sheet loads with the application, before core's
  `style.css`, so a rule of it that styles a core class's element compounds with that class and wins by specificity,
  never by source order (the sheet's header). The kind is a string token on both
  sides, so that core never names an application's kind (`MEMORY`, `CHAPTER`, `FILE`, `DEPOT_DROPBOX`) is guarded
  (both kind rules read literals, from lists a new application must extend by hand: a kind missing there is guarded by
  nothing, and nothing turns red), not typed, and no compiler lists the kinds: the registry's fail-closed answer and its tests carry what a closed enum's exhaustiveness
  did.
  Docs describing the target say which of these they rely on.
- **One view per kind, for a member and a share guest alike** (owner principle 2026-10-09, inbox #401). A share
  view looks exactly like the member view; a link's role only removes functions. So the registry's contract does not
  know which of the two is looking:
  - A kind's `detail` and its row factory receive `access` — who looks: a member of the unlocked scope (its epoch keys,
    whether it is pending deletion, the instance's sharing capabilities) or a guest of a link (its role, the resolved
    share, the password token, core's one naming flow) — and `tree`, the tree as that session reads it: the member's
    decrypted scope tree or the guest's opened subtree, every navigation through its `linkTo`, so a guest's link keeps
    its `#k=` fragment. Core mounts them in **both** modes: the share page runs its gates, builds the guest's access and
    tree, and renders core's level view or the kind's `detail` exactly as the member host does.
  - What a view offers is its application's **rights function**: pure and fail-closed — every right false unless
    granted, an unknown mode, role or kind grants nothing — the role matrix of
    [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6 in code, written down
    in its `docs/apps/<application>.md`; core has its own for `FOLDER`. A right holds only where the matrix allows it
    **and** the session's data source implements the operation: a guest's source simply lacks the member's operations,
    so a function it does not have cannot appear. Member-only state — pins under the Master Key, item marks under `K_SC`
    at `E_0`, a node's sealed meta, the scope's member list — is granted to no guest role.
  - `shareSection` is retired (a kind's rows serve the guest's folder page too), and `claimShareBody` narrows to the
    one page with no member equivalent: a `WRITE_ONLY` link's drop, whose guest holds no key and so has nothing to
    render the member's way. The guest-only shell stays core's: the password and terms gates, one naming flow with
    "Not you?", the curator's note, the role badge.

  Rejected: a rights object that defaults to allowed, with the operations as optional props that fall back to the
  member's calls — the legacy plaintext client's design; a forgotten prop there calls the member API from a share page.
  **Built** (inbox #401, five steps, 2026-10-09 to 2026-10-11). Core: `src/web/src/core/access.ts` (`Access`, `TreeSource`,
  the member's factories, `targetOf`, `folderRights`) and the guest's half, `core/sharing/guestAccess.ts`
  (`guestAccess`, `guestTreeSource` over the opened subtree — a served `ShareNode` as a `TreeNode`, `createdAt` 0 so equal
  names number by node id, `metaRevision` 0, `wrappedMeta` as served, every `linkTo` keeping `#k=`). The shell
  (`views/ShareView.vue`) runs the gates, builds the access and owns the page's one naming flow (`requestGuest`, core's
  `GuestNaming` with "Not you?"); then a `READ` or `READ_WRITE` link of any root kind gets core's page
  (`core/sharing/ShareTreePage.vue`): core's `LevelView` at a folder, the kind's `detail` at any other node — the link's
  root included — with what only a guest's page adds (the breadcrumb up to the root, the curator's note, the role
  badge); a `WRITE_ONLY` link gets the drop page its root kind's application claimed (`claimShareBody`, which no longer
  takes an option, and refuses `FOLDER`). Each application reads and writes through its data source, the member's or the
  guest's, fixed at mount: Depot's `features/depot/source.ts` (its row factory and `DropBoxDetail` serve guests;
  `ShareFilesSection` and `DropBoxSharedView` are deleted) and Memories' `features/memories/source.ts` (`MemoryDetail`,
  its composables, the lightbox and the organizer touch no API but through it; `MemoryDetailHost` picks the source by
  `access.mode` and reads nothing of the member's world for a guest). A view shows a right where the matrix grants it
  AND the source carries the door (`sourceRights`); the lightbox takes its loaders as required props and each
  write per right (`canDelete` per item; a node's cover is core's, `coverRights`, the source's `mediaMeta` door). Memories' share body is the
  drop page alone: the grouped-chapters guest gallery of #356, its lightbox wiring and its own-delete are deleted, and a
  contributor uploads into the chapter it navigated to (the chapter named as the target, sealed under its own key; the
  server creates the guest's own bucket there). A guest's Memory view shows the date and place the members sealed but
  never reconciles them (that writes the node's meta). `shareSection`, `embedBelowFolder`, `embeddedShareBody` and a share
  body's `subtreeRoot` are gone. A move (inbox #358) follows the same rule: one right per matrix
  (`folderRights.move`, `depotRights.moveFile`/`moveNode`, `memoryRights.move` — a guest's only
  `READ_WRITE`, only of a node a `FOLDER` of its subtree holds, never in an identity folder), one core dialog
  (`core/node/MoveNodeDialog.vue`) mounted by both hosts, and one door per session (`core/node/move.ts` `nodeMover`:
  the member's `moveNode`, a `READ_WRITE` guest's `placeShareNodeAsGuest`, none for any other role); its destinations
  mirror the server's `NodeKindRules` through each kind's `placement` (the registry's `placementAllows`), and the server
  stays the authority — each of its refusals is explained by its code. What proves it: guest-mode mounts through the real shell, registry and router
  (`ShareView.memory.test.ts`, `MemoryDetail.guest.test.ts`, `DropBoxDetail.guest.test.ts`, `FolderShareGuest.test.ts`)
  record every authenticated client and every read of the member's stores and assert both empty;
  `validate_web_share_bodies_are_drop_pages.py` keeps the name `ShareBody` meaning a claimed drop page.
- **Guards move with the code, and the build is one of them.** On the backend, core → application is the
  compiler's (no edge, so no import) and every other direction is `validate_backend_module_graph.py`'s — including
  application → application, held between Memories and Depot and bitten with a forged third application; inside core,
  `validate_kernel_imports_no_slices.py` keeps the kernel → slice rule (a declared kernel list) and the node-kind
  rule, which no import check can see. On the web there is no module boundary, so the direction is a text guard:
  `validate_web_core_imports_no_applications.py` (core may not import an application's directory —
  `features/memories`, `features/depot` — or a client generated from its specs — derived from `generate:api`, so each
  application's factories live in its own `api.ts` — discover code by glob, name an application's node kinds, or read
  an application's i18n namespace (a kind's own wording reaches core through its registry descriptor); no
  allowlist; and no application imports another or its clients, or reads another's i18n namespace; and the catalogs
  sit where they belong — core's names no application's namespace, an application's exactly its own, and the catalog
  half of the composition root imports an application's catalog and nothing else of it). The test trees stay out of the import checks, for the reasons the kernel guard's docstring gives.
- **Rejected: a generic record store** (core offers "put a sealed record under a key", applications are just
  clients of it). It would make the server a database of blobs with no shape, give up per-kind authorization,
  quota and trash rules, and push every integrity rule into clients that cannot see each other.
- **Rejected: runtime plugins.** Decision 5.
- **Rejected: a container per application.** It multiplies the deployment (compose and Helm, which must answer
  together — [ADR 0064](0064-kubernetes-helm-deployment-target.md)) for no isolation the shared database does not
  already defeat, and works against *self-hostable by default*.
- **Rejected: per-tenant activation** (an application is off until a tenant turns it on). It adds a state
  machine for "activated but empty" and a per-tenant gate on every endpoint, to solve a problem — clutter — that
  "created on demand" already solves. Switching **off** (decision 8, ADR 0088) is a different thing: it serves
  policy, not clutter, the default is on, and the one gate sits at creation.
- **Rejected: re-deriving Memories' keys under an application context** — Decision 9.

Explained for security researchers on the docs site: [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).
