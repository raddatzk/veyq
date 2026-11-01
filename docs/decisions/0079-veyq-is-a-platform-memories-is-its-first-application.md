# ADR 0079 — veyq is a platform; Memories is its first application

- Status: Accepted
- Built: yes
- Date: 2026-10-03
- Model: [Applications](../apps/README.md)
- Replaced in part: decision 8 by ADR 0088 (built)

## Context

Everything veyq stores is already a sealed object in a scope, found by an opaque id, shared by a key wrap and
reached through a tenant's host. Nothing about that is specific to photos: the scope kinds, memberships, key
epochs, invitations, public shares, blobs with their quota, the trash and the node tree
(`veyq_node`, with a `kind` column — `FOLDER`, `MEMORY`, `CHAPTER` when this was decided) serve any sealed object. What
is specific to photos is a smaller set: the `MEMORY` and `CHAPTER` kinds, media and its derivatives, the gallery, the
map.

Other things the same people want to keep end-to-end encrypted and self-hosted have exactly that shape: a shared
expense ledger, an inventory of gear, a file tree. Building each as a separate product would copy accounts,
tenants, scopes, invitations, key rotation and sharing — the parts that took the longest and carry the
security argument. The decision is whether those are built once, as the platform every application stands on,
or once per product.

## Decision

**1. veyq is a platform; an application is a set of node kinds with its own domain data and its own client
screens.** **Memories** is the first one. The product language **Memory / Chapter** is unchanged and now belongs
to Memories ([CLAUDE.md](../../CLAUDE.md)); a folder organizes — it holds no content, and like every node it carries
a key and can be the root of a share ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)).
The term is "application" (German *Anwendung*), not "plugin" — it is first-party code, not something installed.

**2. What is the platform ("core").** The server, tenants (resolved by `Host`, unchanged), users, the scope
kinds `TENANT` / `GROUP` / `PERSONAL`, memberships (role + grant), invitations and admit/deliver, key
management (epochs, rotation, revocation), public links (`READ`, `READ_WRITE`, `WRITE_ONLY`, password, reissue),
blobs and the storage quota, the trash, and **the node tree**: a domain-neutral tree of objects in a scope in
which every node has a `kind` and a content key, with folders and move / rename (one placement write) / trash.
Application-neutral means the platform never branches on what a kind means.

**3. What an application owns.** Its node kinds, its payload formats, its tables, its endpoints and its
screens. Memories owns `MEMORY` and `CHAPTER`, media and derivatives, the gallery and its live list, and the map
([docs/apps/memories.md](../apps/memories.md)). What every kind can have is core's, even where Memories had it first:
a node's cover and the counters a live list of records polls are core's, and an application uses them.

**4. Dependency direction.** An application may depend on core; core never depends on an application, and an
application never depends on another. Two applications that must refer to each other do so only through a
generic core object reference (a scope id and a node id), which each side resolves for the kinds it owns.

**5. Build-time only.** Applications are first-party and compiled in. There is no runtime loading of
application code, on the server or in the client. The reason is the security argument, not convenience: client
code can reach every key of the user's unlocked scopes, so code the operator or a third party can add after the
fact is code that can read everything ([ADR 0026](0026-client-trust-tiers.md)). What runs is what was reviewed
and released.

**6. An application keeps its domain data in its own tables, keyed by `node_id`** (`veyq_media` is the first
instance). It does not widen `veyq_node` with columns of its own. The server side of an application is as thin
as it can be: store, authorize on opaque ids, serve ciphertext. Domain logic lives in the client, because the
client is the only party that can read the data.

**7. Payloads carry a schema version; old versions stay readable; migration happens on write.** The server
cannot migrate ciphertext, so a client must open every version it ever wrote, and re-seals into the current one
only when the user writes. This is the convention the sealed `wrappedMeta` blobs follow
([ADR 0077](0077-a-write-carries-the-version-it-read.md) names their concurrency); it is stated here for every
application.

**8. No activation step, but an application can be switched off.** Every application is created on demand inside a
scope, so nobody turns one on; switching one off, and what that does to what exists, is
[ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 5's.

**9. Crypto does not change.** No existing format, seal purpose or suite id moves; nothing is re-encrypted.
A new seal purpose would be additive ([ADR 0072](0072-a-seal-binds-its-object.md): a new variant plus its vector is
backward-compatible). Core has added one such purpose, `blob-meta` (a blob's sealed metadata,
[ADR 0072](0072-a-seal-binds-its-object.md)). It is the platform's, carried by every application's blobs alike, so it is
no exception to the rule below that an application needs no purpose of its own. For a **new** application whose keys
derive from the scope key, they derive by HKDF with the application id as context (`veyq/v1/<application id>:…`, the
form the existing derivations use), so two applications never share a key by accident; the seals' associated data binds
the application id or kind, the object id and the revision, by the ADR 0072 mechanism. A kind whose node key is random
and wrapped under its parent's key ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md))
meets both rules with no new seal purpose and no HKDF context: it shares no key with another application's node by
construction, which is what the context was for, and its blob's `blob-key` seal binds `ownerId` (the owning node's id)
and `role` — an open token the application defines — which names the application's object and kind. Depot and Expenses
are such kinds. **Memories' existing keys are the documented exemption**: they derive as they always have, and are not
re-derived under a Memories context — that would be a suite `v2` and a re-encryption for no gain, since there is no
earlier application to be separated from.

**10. One backend image.** A modular monolith: one container, one database, one deployable — the root build's one
fast-jar. Core and each application are build modules of it, compiled in and never built, published or deployed
on their own.

## Consequences

How each of these is built — the modules, the seams core asks, the web registry, the guards — is
[Applications](../apps/README.md); this section keeps what was decided and why.

- **The node tree is the platform's, not Memories'.** Rejected: the tree owned by Memories. Every other
  application would then need a tree of its own and, with it, its own sharing, trash and move rules — the
  duplication this decision exists to avoid. The price is that the tree's `kind` holds values core does not
  interpret, so **node kinds are open**: an open, bounded token on the wire, core naming `FOLDER` only, each application
  bringing its kinds and their rules, and a kind no application claims refused by fail-closed seams, never by decoding.
  A link serves its root's kind, so the share page renders the body the kind's application claimed.
- **A node's URL carries its application's slug** (the grammar is [ADR 0015](0015-operator-blind-readable-urls.md)'s).
  The path stays in the `#fragment`; each application claims its slug once or declares that it has none. Rejected:
  history-mode `/<application>/:nodeId` URLs — they put a stable per-node id into the operator's access logs on every hard load
  and need a scope lookup the fragment never did.
- **What the server learns grows by one fact.** The node `kind` is already a server-visible column; once
  further kinds exist it also tells the operator which applications a scope uses, and the rows, timestamps and
  sizes each kind produces. [security-model](../crosscutting/security-model.md) lists it.
- **Attachments are core's**: a second application needed sealed files on a node, and every further one would have;
  what a verified upload becomes stays the application's. What the platform holds in all is
  [Applications](../apps/README.md).
- **Contracts by owner.** A core spec describes what core does; where a value's meaning belongs to an application, its
  description says whose it is. An operation whose behaviour is an application's lives in that application's spec
  directory, even when its path sits under a core route. Rejected: one spec per route, whoever's behaviour it is — the
  module that generates it would then be core, and core would carry an application's operation.
- **The node's payload slot and content key are core's.** Core gives every node one sealed, versioned payload
  (`wrappedMeta`, `metaRevision`) that it stores, compare-and-sets and lists with the tree, and never opens; its
  plaintext format is the kind's application's (decision 7). These are platform columns, not an application widening
  `veyq_node` (decision 6). Rejected: moving the payload into a Memories table with endpoints — it loses the atomic
  create and the payload in the tree listing that sorting relies on, and the next application would want the same
  slot back.
- **The drop key is the `WRITE_ONLY` link's mechanism**, core's, not the first application's that used it. Its seal
  purposes `drop-public-key` / `drop-private-key` are a stored format and keep their names.
- **Three access modes.** A link grants one of `READ`, `READ_WRITE`, `WRITE_ONLY`; core enforces the mode,
  and which roles a node may be shared with is its kind's owner's. A `READ_WRITE` guest's tree operations are core's,
  each container's owner deciding them inside it; what "write" means on an application's node is the application's.
  The modes are bound in no seal. Application-specific roles, if ever needed, come through a policy seam where core
  asks the application whether an action is allowed for a mode — never through a registry of roles.
- **Application guest operations.** Core owns the public link and its guest gate and exposes the gate as a service an
  application calls before its own guest read or write; core's share contract keeps only kind-neutral guest
  operations, and an application serves its rows and writes on its own route under the share, carrying their privacy
  rules with it. A link may later become a credential core's gate accepts for application operations that declare
  it; that is a separate decision.
- **An append-only application cannot make the server keep its order.** The server may drop or reorder entries
  it holds unless a sequence number or hash chain is bound into each entry's associated data (ADR 0072 binds an
  object, not its neighbours). Each such application states that gap; it is not a platform property.
- **The backend is split into build modules; the web by import direction.** On the backend the direction is the
  build's, so a violation does not compile, and core reaches application code only through seams that fail closed —
  a kind or role no application claims gets nothing. On the web there is no module boundary, so the direction is a
  text guard, and the shell asks a build-time registry what to render for a kind. The kind is a string token on both
  sides, so the registry's fail-closed answer and its tests carry what a closed enum's exhaustiveness did. A cover and
  a record list's counters are core's (decision 3), so core's schema and config name no application concept.
- **One view per kind, for a member and a share guest alike.** A share view looks exactly like the member view; a
  link's role only removes functions. So the registry's contract does not know which of the two is looking: a view
  receives who looks and the tree that session reads, and what it offers is its application's **rights function**,
  pure and fail-closed, the role matrix of [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 6 in code. What every node may do whatever its kind — be shared, renamed, moved, deleted — is core's one
  answer (`nodeRights`), which each rights function, core's own for `FOLDER` too, may only narrow, never widen: a rule
  written once cannot drift between applications. A right holds only where the matrix allows it **and** the session's data source implements the
  operation, so a function a guest's source does not have cannot appear. The one guest-only page is a `WRITE_ONLY`
  link's drop, whose guest holds no key. Rejected: a rights object that defaults to allowed, with the operations as
  optional props that fall back to the member's calls — the legacy plaintext client's design; a forgotten prop there
  calls the member API from a share page.
- **Guards move with the code, and the build is one of them.** Core → application is the compiler's; every other
  direction, and the node-kind rule no import check can see, is a guard's ([Applications](../apps/README.md#guards)).
- **Rejected: a generic record store** (core offers "put a sealed record under a key", applications are just
  clients of it). It would make the server a database of blobs with no shape, give up per-kind authorization,
  quota and trash rules, and push every integrity rule into clients that cannot see each other.
- **Rejected: runtime plugins.** Decision 5.
- **Rejected: a container per application.** It multiplies the deployment (compose and Helm, which must answer
  together — [ADR 0064](0064-kubernetes-helm-deployment-target.md)) for no isolation the shared database does not
  already defeat, and works against *self-hostable by default*.
- **Rejected: per-tenant activation** (an application is off until a tenant turns it on). It adds a state
  machine for "activated but empty" and a per-tenant gate on every endpoint, to solve a problem — clutter — that
  "created on demand" already solves. Switching **off** ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 5) is a different thing: it serves policy, not clutter, the default is on, and off leaves what exists
  readable, so there is still no "activated but empty" state.
- **Rejected: re-deriving Memories' keys under an application context** — Decision 9.

Explained for security researchers on the docs site: [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).
