# ADR 0072 — A seal binds the object it belongs to, not only its purpose

- Status: Accepted
- Date: 2026-09-23

## Context

Every AEAD seal in veyq authenticates a **purpose** and nothing else. `symWrap`/`asymWrap` take a free context
string and put `type ‖ "v1" ‖ context` into the AAD (`packages/crypto/src/v1.ts`, `wrapAad`); every call site
passes a static literal such as `'node-name'`, `'content-key'`, `'blob-key'` or `'scope-grant'`. So two
ciphertexts sealed for the same purpose under the same key are interchangeable, and the operator-blind server
decides which one a client receives for which object. Measured 2026-09-23 against the whole `web/src` tree,
that yields three things:

- **Swap between objects under one shared key.** Node names (under `K_SC`), `K_blob` wraps, derivative refs and
  media metadata (under `K_mem`), member names (under the tenant key), bucket and contributor names, share-key
  recovery wraps, item favorites: the server can serve any of them for any sibling.
- **A confidentiality break, not only an integrity one.** A Memory's `K_mem` is `symWrap(K_SC, K_mem, 'content-key')`
  for every Memory in the scope. A server that serves shared Memory S's wrap on private Memory X's row makes the
  client unwrap `K_mem(S)` cleanly and seal every new upload and chapter under X to it, where S's public share
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
it belongs to — and **the epoch or version the wire stores it at**, where the wire carries one. The opener
supplies those values from the address it used to fetch the object and never from the ciphertext's own
envelope, so a ciphertext moved to a different slot fails to open.

**2. Where the key is unique to one object, the key is the binding and the context names the purpose only.**
Chunk frames under a per-blob `K_blob` (their AAD stays `index ‖ total`, and the `K_blob` wrap itself is bound
to its blob), a share's fields under its own `K_share`, the Master-Key wraps under a KEK with a fresh salt, the
single-use pairing envelope, and the two WebCrypto seals under a non-extractable per-device key. Those WebCrypto
seals carry no AAD today, and they gain their purpose as `additionalData`.

**3. The client mints the id of every sealed object it creates** (UUIDv4, carried in the create request). The
server refuses an id that is already taken and never substitutes its own. A create that is retried with the
same id becomes idempotent as a side effect.

**4. Parents are not bound.** The tree's shape is server-visible metadata and stays that. Binding a node's
parent would turn every move into a re-seal of everything below it. The harm a parent binding would prevent is
already closed by the two things above: a node's own ciphertexts are bound to its id, and a `K_mem` wrap moved
under a different parent meets a different parent key and does not open.

**5. Binding makes an epoch or version *authentic*; it does not make it *fresh*.** Which epoch's grant and which
version of an object a client receives is still the server's choice. Refusing an older but authentic
ciphertext needs a floor the client holds itself, which inbox #161 owns (the per-scope epoch floor and the pin
store's rollback). The labels are bound now anyway, because they are part of the stored format and a floor
compares against nothing trustworthy unless the label is authenticated.

**6. The typed seal is the only entry point, and the vectors pin every purpose.** `@veyq/crypto` exports a
`seal`/`open` pair (and its asymmetric twin) whose context is a **typed union**: each purpose is a variant
carrying exactly its binding fields, encoded canonically (length-prefixed, fixed field order). The helper draws
its own nonce. The raw `symWrap`/`asymWrap`/`aeadSeal` leave the package's public exports, so an unbound seal
cannot be written from `web/` or `admin-web/` at all — the structure is the guard. `contracts/crypto/vectors/v1.json`
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
