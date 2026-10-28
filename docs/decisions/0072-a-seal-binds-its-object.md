# ADR 0072 — A seal binds the object it belongs to, not only its purpose

- Status: Accepted
- Built: yes
- Date: 2026-09-23
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)

## Context

Every AEAD seal in veyq authenticates a **purpose** and nothing else. `symWrap`/`asymWrap` take a free context
string and put `type ‖ "v1" ‖ context` into the AAD (`src/packages/crypto/src/v1.ts`, `wrapAad`); every call site
passes a static literal such as `'node-name'`, `'content-key'`, `'blob-key'` or `'scope-grant'`. So two
ciphertexts sealed for the same purpose under the same key are interchangeable, and the operator-blind server
decides which one a client receives for which object. Measured against the whole `web/src` tree,
that yields three things:

- **Swap between objects under one shared key.** Node names (under `K_SC`), `K_blob` wraps, derivative refs and
  media metadata (under `K_node`), member names (under the tenant key), bucket and contributor names, share-key
  recovery wraps, item favorites: the server can serve any of them for any sibling.
- **A confidentiality break, not only an integrity one.** A Memory's `K_node` is `symWrap(K_SC, K_node, 'content-key')`
  for every Memory in the scope. A server that serves shared Memory S's wrap on private Memory X's row makes the
  client unwrap `K_node(S)` cleanly and seal every new upload and chapter under X to it, where S's public share
  link reads them.
- **Cross-scope and cross-epoch grant swaps.** Every self-grant is `asymWrap(ownPub, K, 'scope-grant')`, so one
  scope's grant opens in another scope's row, and the client takes the epoch a grant belongs to from the server.

The object ids these seals would bind are, in most cases, **minted by the server after the client has sealed**:
`createNode`, `startBlobUploads`, `completeMedia`, `createBucket`, `createShare`, `createShareIdentity`,
`createGroup` and `createPersonalScope` all return the id the ciphertext they carry should have been bound to.

This is a stored-format change. Before V1 it is a code change; after V1 it would be a suite `v2` plus a
re-encryption of every existing ciphertext. That is why it is decided now.

## Decision

**1. A seal binds its purpose and the identity of the object it is stored as.** The AAD carries the purpose,
**every id the opener addresses the object by** — the scope, and the node, media, blob, bucket, share or member
it belongs to — and **the epoch or revision the wire stores it at**, where the wire carries one. The opener
supplies those values from the address it used to fetch the object and never from the ciphertext's own
envelope, so a ciphertext moved to a different slot fails to open.

**2. Where the key is unique to one object, the key is the binding and the context names the purpose only.**
Chunk frames under a per-blob `K_blob` (their AAD stays `index ‖ total`, and the `K_blob` wrap itself is bound
to its blob), a blob's sealed metadata under that same `K_blob` (`blob-meta` — its type, upload name and
modification time; its AAD `0x02 ‖ 'v1' ‖ lp('blob-meta')` is 16 bytes against a frame's 8, so the two never open as
each other), a share's three fields under field keys derived from its own `K_share`, the Master-Key wraps under a
KEK with a fresh salt, the single-use pairing envelope, and the two WebCrypto seals under a non-extractable
per-device key. Those WebCrypto seals carry no AAD today, and they gain their purpose as `additionalData`. A field
under its own `K_field` ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 9) has a key unique to that one revision as well, but keeps Decision 1's object binding wherever its purpose
names an object; only the share's three bind their purpose alone.

**3. The client mints the id of every sealed object it creates** (UUIDv4, carried in the create request). The
server refuses an id that is already taken — `id_taken`, the same answer wherever the taken id lives — and never
substitutes its own or overwrites the object that holds it. A share's id is the exception to the form, not to
the rule: it stays the 256-bit base64url token it always was, because it is half of the share's capability and a
UUID would shrink it; the client mints that token instead.

**Where the opener's address carries no id, the seal binds none.** Item favorites are read as every member's
entry for one node, and the read deliberately names no member ([ADR 0068](0068-favorites-two-confidentiality-levels.md)
§6 — the aggregate is anonymous in the data). So an `item-favorites` seal binds (scope, node) and not the marking
member: an opener could not supply one. *Whose* an entry is, is instead a self-tag inside the plaintext that only
the marking member's own client can compute and check ([ADR 0068](0068-favorites-two-confidentiality-levels.md) §9),
so the server cannot present another member's set as the caller's own. What binding does not give it, it does not
give here either: the server still chooses which stored version of a set it serves (Decision 5) and may serve
stored sets again as extra entries in the count (ADR 0068 §9). Likewise a revision-carrying store (`identity-pins`,
`device-register`) binds the revision it is stored at — the write's `revision + 1` — because that is the one the
reader's address names.

**4. Parents are not bound.** The tree's shape is server-visible metadata and stays that. Binding a node's
parent would turn every move into a re-seal of everything below it. The harm a parent binding would prevent is
already closed by the two things above: a node's own ciphertexts are bound to its id, and a node's key wrap moved
under a different parent meets a different parent key and does not open. What a move does bind is the node's own
`placement_revision` (`node-name`, `content-key`, [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
Decision 3): a move re-seals the moved node's own entry and key once, never its subtree.

**5. Binding makes an epoch or revision *authentic*; it does not make it *fresh*.** Which epoch's grant and which
version of an object a client receives is still the server's choice. Refusing an older but authentic
ciphertext needs a floor the client holds itself: the per-scope epoch floor
([ADR 0024](0024-scope-key-rotation.md) Decision 6) and the pin store's rollback mark
([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 7). The labels are bound now anyway, because they are part of the stored format and a floor
compares against nothing trustworthy unless the label is authenticated.

**6. The typed seal is the only entry point, and the vectors pin every purpose.** `@veyq/crypto` exports a
`seal`/`open` pair (and its asymmetric twin, and the field seal of ADR 0093 § 9, the only entry point for a field
purpose) whose context is a **typed union**: each purpose is a variant
carrying exactly its binding fields, encoded canonically (length-prefixed, fixed field order). The helper draws
its own nonce. The raw `symWrap`/`asymWrap`/`aeadSeal` leave the package's public exports, so an unbound seal
cannot be written from `src/web/` or `src/admin-web/` at all — the structure is the guard. `contracts/crypto/vectors/v1.json`
carries one vector per purpose, and a conformance test compares the union's purposes with the vector set **in
both directions**. That settles inbox #32's question of what earns a vector: a live seal purpose earns one, and
a vector with no live purpose is deleted. `journalSeal` (empty AAD, no caller) is deleted with it.

## Consequences

- **The server can no longer choose which object a ciphertext opens as.** The swap and the upload chain above
  fail at the AEAD tag. It keeps every capability it had over *which* objects exist, where they sit in the tree,
  and how fresh they are (Decisions 4 and 5).
- **Every existing ciphertext stops opening.** No migration is written: before V1 there is no user data to
  carry, and every development instance is reset. The AAD keeps its `v1` marker, because no `v1` artifact was
  ever released.
- **Adding a purpose after V1 is additive; changing a purpose's fields is a suite `v2`.** A new variant plus
  its vector is backward-compatible, and a new field on an existing variant changes bytes that are already
  stored.
- **Eight create operations take a client-minted id.** That is a contract change on `content`, `media`,
  `sharing` and `scopes`, and the backend stops generating those ids.
- **A public share's guest learns the shared node's scope id.** Every node-key seal the viewer opens is bound to
  the scope, so the id rides with the subtree; two links into one space become linkable to anyone holding both
  ([security model](../crosscutting/security-model.md) § What is encrypted vs. server-visible).
- **A grant states the epoch it seals.** A grant-delivery completion names its epoch, and the server records
  exactly that and refuses one that is not current — before, it recorded the scope's current epoch, which a
  rotation between claim and completion made a lie the binding would now turn into an unopenable grant.
- **The group name gets its own purpose.** It used to reuse `node-name` under the derived name key, "domain
  separation lives in the key derivation" ([ADR 0027](0027-group-invite-name-shared-key.md)). A typed union has
  no reason to share a variant between two kinds of object.

**Alternatives rejected.**

- **(a) Keep the server-minted ids and seal in two steps** (create, learn the id, seal, patch). This is not
  atomic, doubles the round trips, and leaves a half-created object visible between the steps.
- **(b) Bind a random nonce stored next to the ciphertext.** The server moves the nonce together with the
  ciphertext, so the binding holds nothing. A binding only works on a value the opener knows independently of
  what it is opening.
- **(c) A signature per ciphertext.** That is sender authenticity, a different property at a different cost.
  Only grants carry a signature ([ADR 0028](0028-identity-authenticity-tofu-verification.md)), because only a
  grant has a recipient who must know who sent it.
- **(d) Keep the static contexts and add a guard.** A guard can require that a context is present. It cannot
  make a static string name the object, and the upload chain lives entirely inside correct-looking static
  contexts.
- **(e) Bind parents** — Decision 4.

Explained for security researchers on the docs site: [Cryptography](../../site/src/content/docs/security/cryptography.md), [Threat model](../../site/src/content/docs/security/threat-model.md), [Known limitations](../../site/src/content/docs/security/known-limitations.md).
