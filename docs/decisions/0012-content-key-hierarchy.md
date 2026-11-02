# ADR 0012 — Content-key hierarchy: per-node keys, random and many-wrapped

- Status: Accepted
- Built: yes
- Date: 2026-06-28
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)
- Replaced in part: Decision 1, Decision 2 by ADR 0080 (built); § Rotation / revocation by ADR 0114 (built)

## Context

[ADR 0002](0002-personal-scope-per-tenant.md) settled
the **scope** layer (opaque `scopeId` + kind; a member holds a scope via a grant = the scope key sealed to
their identity). What lives *inside* a scope — folders, Memories, chapters, media — needed its own key model. The
[Security Model](../crosscutting/security-model.md) **sketched** per-subtree content keys and "a share = one subtree
content key wrapped to a per-share key"; the crypto vectors pinned a content-key-based `slug_token`, `chunk_frame` and
`dedup_token`. The open question was the **exact granularity and wrapping** — and specifically: *how do you share a
single Memory without exposing the rest of the scope?* This ADR nails it. (Considered and rejected during refinement:
**one content key per scope** — sharing one Memory would hand out the whole-scope key, leaving the confidentiality of
the *other* Memories to **server gating, not crypto**. A key on every folder too was first left out here as
re-wrap-on-move complexity the product did not need; [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
added it once a folder had to be shareable, with a move as one O(1) re-wrap.)

## Decision

**Content keys are per node — random, and wrapped to many recipients.** Three layers under the scope:

1. **`K_SC` — scope content key (one per scope, rotatable in epochs — [ADR 0024](0024-scope-key-rotation.md)).**
   Obtained by a member by **opening their grant** — the grant's plaintext *is* the scope's current epoch key;
   there is no key in between. It is the **navigation root**: the **names** and **blinded slug tokens**
   (`slug_token`) of the **scope-root entries** key off the epoch key of the entry's epoch; every node below keeps its
   name and slug token under its **parent's** content key ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
   Decisions 1–2). A member who holds the scope therefore opens the **whole tree's keys and names** down the chain
   from `K_SC`.
2. **`K_node` — per-node content key (one per node of every kind, a fresh random 32-byte key).** Wrapped under its
   parent's content key — `K_SC` for a node at the scope root. Encrypts that node's **content** — for a Memory its
   chapter bodies and sensitive metadata (capture time, location) — and **wraps that node's `K_blob`s**. A folder
   holds no content of its own — it organizes — and its key opens its children's keys, names and slug tokens
   ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 1). Every node is a
   **unit of sharing** (recursively, so is a chapter — see *Recursive `K_node`* below).
3. **`K_blob` — per-blob key (one per blob, random).** Wrapped under its owning node's `K_node`; encrypts the blob's
   `chunk_frame`s. Lets a node's media be keyed independently.

**`K_node` is RANDOM, not derived from `K_SC` — so it is "one key, many wraps"** (the same pattern as the
Master Key, [ADR 0005](0005-unlock-methods-and-device-trust.md)). This is the crux that makes per-node
sharing **cryptographically** enforced:

- **Members:** `K_node` is wrapped under its parent's key (`symWrap`), `K_SC` at the scope root. Every scope member
  opens it down the chain.
- **Sharing one node adds an *independent second wrap* of that same `K_node`** — never `K_SC`:
  - **Public link share:** `symWrap(K_share, K_node)` where `K_share` is a fresh per-share key whose secret rides the
    **URL fragment** (never the server). A guest opens **only** that node's subtree. The share record is
    [ADR 0029](0029-public-viewer-share.md)'s; an *uploader* link carries the node's drop public key instead of this
    wrap ([ADR 0031](0031-uploader-public-share.md) point 7).
  - The same model admits a **member share** — `asymWrap(recipientIdentityPub, K_node)`, the identity-targeted grant
    of [ADR 0020](0020-grant-distribution-invite-wrap.md) scoped to one node — which ADR 0029 § Alternatives considered
    defers.
- Because `K_node` is random, handing a recipient `K_node` reveals **neither `K_SC`** (so not the rest of the
  scope) **nor any other node's `K_node`** (each is wrapped under its own parent's key, none derivable from a
  child's). Least-privilege is **crypto-enforced**, not server-gated.

**Rotation / revocation.** Removing a share record is instant (server-side, no keys), and it is the whole
revocation of a link: a link never re-keys on its own; a re-issue mints a new one
([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md)). A rotation of `K_SC` moves the scope to a new
epoch and re-keys every node below it ([ADR 0024](0024-scope-key-rotation.md) Decisions 1 and 4,
[ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md),
[ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 11).
A node key never re-keys otherwise. Already-read content, and what existed at the rotation, cannot be retracted — a
stated limitation.

**Operator-blind, unchanged.** The server stores only ciphertext content + the opaque wraps (each node key under
its parent's key, share wraps), node ids/parents/kinds/ordering/timestamps, and the blinded slug tokens. It never
holds `K_SC`, `K_node`, `K_blob`, or any name.

## Consequences

- **Per-node sharing works without exposing the scope** — the question this ADR was opened to settle: a share is one
  extra wrap of the node's `K_node`; `K_SC` stays in the owner-circle.
- **Navigation down the chain, scoped content.** `K_SC` opens the scope-root entries, and each node's key opens
  the names and keys of its children; a member walks the whole tree from `K_SC`. A guest of a shared node sees that
  node's subtree — names and content — and nothing beside or above it ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decision 9).
- **Cost:** one wrap per node (under its parent's key) + one per `K_blob`; a share adds one wrap per share.
  Negligible vs. content size.
- **What this record leaves to others:** the byte formats are the vectors' ([ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md)),
  what every seal binds is [ADR 0072](0072-a-seal-binds-its-object.md)'s, and whose key a wrap to another member
  trusts is [ADR 0028](0028-identity-authenticity-tofu-verification.md)'s.
- **No scope-wide dedup.** `K_blob` stays **random**, so identical content in two nodes is two ciphertexts; scope-wide
  dedup is declined and one stored object for an equal file inside one node is the only sharing of storage
  ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md) § 5). Were dedup reopened, its key is
  [ADR 0024](0024-scope-key-rotation.md) Decision 3's.

## Recursive `K_node` — a chapter carries its own key

A **chapter is itself a Memory**, so it carries the **same kind of key, applied recursively** — there is
no separate `K_chapter` type:

- **Every node has its own random key**, of every kind ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decision 1), wrapped under the **content key of its parent**: a scope-root node's under `K_SC`; a chapter's `K_node`
  under its **parent Memory's `K_node`** (the same `content-key` purpose one level deeper, bound like every wrap to the
  node it belongs to and to its placement revision — [ADR 0072](0072-a-seal-binds-its-object.md) Decision 4).
- **`K_blob` is wrapped under the `K_node` of its owning node** — media on a chapter under the chapter's `K_node`,
  media on the Memory under the Memory's `K_node`.
- **Every content node is therefore a sharing unit.** Sharing one chapter = one extra wrap of *that* chapter's
  `K_node`; it reveals neither the parent's `K_node` nor `K_SC`. Sharing the whole Memory transitively unwraps
  its chapters (their keys are wrapped under its `K_node`) — a Memory share includes its chapters; a chapter
  share never leaks upward.
- **A move is a re-wrap, never a data re-encrypt:** a node's move or rename re-wraps its key under the (new) parent's
  key and re-seals its name and slug token there; its subtree is untouched
  ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 3).

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
  [en.platform.node-tree](../features/platform/enablers/en.platform.node-tree.md).
- Security area of the docs site: [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md)
