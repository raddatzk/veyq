# ADR 0012 — Content-key hierarchy: per-Memory keys, random and many-wrapped

- Status: Accepted
- Date: 2026-06-28

## Context

[ADR 0002](0002-personal-scope-per-tenant.md) settled
the **scope** layer (opaque `scopeId` + kind; a member holds a scope via a grant = the scope key sealed to
their identity). What lives *inside* a scope — folders, Memories, chapters, media, journal — needs its own
key model. The [Security Model](../crosscutting/security-model.md) **sketched** per-subtree content keys and
"a share = one subtree content key wrapped to a per-share key" (its hierarchy diagram now names this ADR's
per-Memory `K_node`); the crypto vectors pin a `contentKey`-based `slug_token` / `chunk_frame` /
`dedup_token` / `journal_open`. The open question was the **exact granularity and wrapping** — and specifically: *how do you share a single Memory without exposing the rest
of the scope?* This ADR nails it. (Considered and rejected during refinement: **one content key per scope** —
sharing one Memory would hand out the whole-scope key, leaving the confidentiality of the *other* Memories to
**server gating, not crypto**. A key on every folder too was first left out here as re-wrap-on-move complexity
the product did not need; [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) added
it once a folder had to be shareable, with a move as one O(1) re-wrap.)

## Decision

**Content keys are per Memory — random, and wrapped to many recipients.** Three layers under the scope:

1. **`K_SC` — scope content key (one per scope, rotatable in epochs — [ADR 0024](0024-scope-key-rotation.md)).**
   Obtained by a member by **opening their grant** — the grant's plaintext *is* the scope's current epoch key;
   there is no key in between. It is the **navigation root**: the **names** and **blinded slug tokens**
   (`slug_token`) of the **scope-root entries** key off the scope's **current content-epoch key**; every node below
   keeps its name (under a field key derived from it) and slug token under its **parent's** content key ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
   Decision 2). A member who holds the scope therefore opens the **whole tree's keys and names** down the chain from
   `K_SC`. (The per-scope **dedup token** is the one exception: it
   keys off a **stable, non-rotating** scope sub-key so identical blobs still deduplicate across epochs, [ADR 0024](0024-scope-key-rotation.md).)
2. **`K_node` — per-Memory content key (one per Memory, a fresh random 32-byte key).** Wrapped under its
   parent's content key — `K_SC` for a Memory at the scope root, a folder's key for one inside a folder. Encrypts that Memory's **content** — chapter bodies, journal, sensitive metadata
   (capture time, location) — and **wraps that Memory's `K_blob`s**. A Memory is the **unit of sharing**
   (recursively, so is a chapter — see *Recursive `K_node`* below).
3. **`K_blob` — per-blob key (one per media blob, random).** Wrapped under its Memory's `K_node`; encrypts the
   media blob's `chunk_frame`s. Lets a Memory's media be keyed independently (and dedup tokens stay per-scope).

**A folder carries a key too** ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
Decision 1): a random content key wrapped under its parent's key like a `K_node`, under which its children's keys,
names and slug tokens sit. It holds no content of its own — it organizes — and it can be the root of a share.

**`K_node` is RANDOM, not derived from `K_SC` — so it is "one key, many wraps"** (the same pattern as the
Master Key, [ADR 0005](0005-unlock-methods-and-device-trust.md)). This is the crux that makes per-Memory
sharing **cryptographically** enforced:

- **Members:** `K_node` is wrapped under its parent's key (`symWrap`), `K_SC` at the scope root. Every scope member
  opens it down the chain.
- **Sharing one Memory adds an *independent second wrap* of that same `K_node`** — never `K_SC`:
  - **Public link share:** `symWrap(K_share, K_node)` where `K_share` is a fresh per-share key whose secret
    rides the **URL fragment** (never the server); the shared Memory's name travels in the share record too,
    re-sealed to `K_share`. A guest opens **only** that Memory — and an *uploader* link not even that: it
    carries the Memory's drop public key instead of this wrap ([ADR 0031](0031-uploader-public-share.md) point 7).
  - **Member share** (a *registered* recipient gets ongoing access to one Memory):
    `asymWrap(recipientIdentityPub, K_node)` — the **same identity-targeted grant as a scope grant (ADR 0020),
    but scoped to a single Memory's `K_node`** instead of the whole scope's `K_SC`. Opened with the recipient's
    identity private key — no link, no fragment key (unlike the public link above, which shares one Memory
    *anonymously*). A capability of the independent-second-wrap model; **not a built feature** (no use-case yet).
- Because `K_node` is random, handing a recipient `K_node` reveals **neither `K_SC`** (so not the rest of the
  scope) **nor any other Memory's `K_node`** (each is wrapped under its own parent's key, none derivable from a
  child's). Least-privilege is **crypto-enforced**, not server-gated.

**Rotation / revocation.** Removing a share record is instant (server-side, no keys), and it is the whole
revocation of a link: a `K_node`, like every node key, **never re-keys**
([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) Context, [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
Decision 4). The one key that rotates is `K_SC`, **forward into a new epoch** — re-wrap the new epoch to the
remaining members, new scope-root entries seal under it, nothing existing is re-encrypted (the epoch chain,
[ADR 0024](0024-scope-key-rotation.md)). Already-read content cannot be retracted — a stated limitation.

**Operator-blind, unchanged.** The server stores only ciphertext content + the opaque wraps (each node key under
its parent's key, share wraps), node ids/parents/kinds/ordering/timestamps, and the blinded slug/dedup tokens. It never
holds `K_SC`, `K_node`, `K_blob`, or any name.

## Consequences

- **Per-Memory sharing works without exposing the scope** — the question this ADR was opened to settle: a share is one extra wrap of the
  Memory's `K_node`; `K_SC` stays in the owner-circle.
- **Navigation down the chain, scoped content.** `K_SC` opens the scope-root entries, and each node's key opens
  the names and keys of its children; a member walks the whole tree from `K_SC`. A guest of a shared node sees that
  node's subtree — names and content — and nothing beside or above it ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decision 9).
- **`cross-space-memory-move`** ([memory](../../changes/) note): moving a Memory across scopes is a
  copy + re-encrypt (new scope's `K_SC`/`K_node`), never a tree move — consistent with [scopes & tenancy](../crosscutting/scopes-and-tenancy.md).
- **Cost:** one wrap per node (under its parent's key) + one per `K_blob`; a share adds one wrap per share.
  Negligible vs. content size.
- **Deferred to the implementation CR / vectors:** the exact wire formats (every seal purpose has one vector in
  `contracts/crypto/vectors/v1.json`, and every seal binds the object it belongs to —
  [ADR 0072](0072-a-seal-binds-its-object.md)) and the share-record shape. (A chapter carries its **own
  recursive `K_node`**, not the parent's — see *Recursive `K_node`* below.) Group/recipient key-directory trust
  is the active-operator gap already in the Security Model.
- **Media dedup — decided in [ADR 0013](0013-blob-storage-presigned-direct-to-store.md): declined.** `K_blob`
  stays **random** (so this ADR's wording holds) and the `dedup_token` vector stays unused: any storage-saving
  dedup would leak the scope-internal blob-equality structure to the blind operator. Scope-wide convergent
  dedup remains a possible *future* slice, not a default.

## Recursive `K_node` — a chapter carries its own key

A **chapter is itself a Memory**, so it carries the **same kind of key, applied recursively** — there is
no separate `K_chapter` type:

- **Every node (FOLDER, MEMORY *and* CHAPTER) has its own random key,** wrapped under the **content key of its
  parent**: a scope-root node's under `K_SC`; a chapter's `K_node` under its **parent Memory's `K_node`** (the same
  `content-key` purpose one level deeper, bound like every wrap to the node it belongs to and to its placement
  revision — [ADR 0072](0072-a-seal-binds-its-object.md), [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decisions 1 and 3).
- **`K_blob` is wrapped under the `K_node` of the media's *direct* parent node** — media on a chapter under the
  chapter's `K_node`, media on the Memory under the Memory's `K_node`.
- **Every content node is therefore a sharing unit.** Sharing one chapter = one extra wrap of *that* chapter's
  `K_node`; it reveals neither the parent's `K_node` nor `K_SC`. Sharing the whole Memory transitively unwraps
  its chapters (their keys are wrapped under its `K_node`) — a Memory share includes its chapters; a chapter
  share never leaks upward.
- **Moves are a single re-wrap, never a data re-encrypt:** moving media chapter↔Memory re-wraps only its
  `K_blob`. A node's move or rename is one placement write: its key re-wrapped under the (new) parent's key, its
  name re-sealed and its slug token recomputed there, stated against its `placement_revision` (`409 stale_placement`,
  [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 3); its subtree is
  untouched.

**Rejected — a chapter shares its Memory's `K_node`:** separate **per-chapter sharing** is a product goal, and
for E2EE data bolting it on later would force a client-side **re-encryption migration** of every chapter's
media (only the client can re-key). Paying one extra wrap per chapter avoids that. The operator stays blind: it
holds one more opaque wrap and nothing else.

## Related

- [Security Model](../crosscutting/security-model.md) (the key-hierarchy diagram + sharing/T&S),
  [Scopes & Tenancy](../crosscutting/scopes-and-tenancy.md) (scope keys, grants, navigation contract),
  [Account & Key Model](../features/account/enablers/en.account.key-model.md).
- [ADR 0002](0002-personal-scope-per-tenant.md), [ADR 0005](0005-unlock-methods-and-device-trust.md)
  (one-key-many-wraps), [ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) (vectors are the byte
  contract).
- Feature: [Memory](../features/memory/_index.md); the foundation enabler
  [en.memory.tree](../features/memory/enablers/en.memory.tree.md).
- Security area of the docs site: [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md)
