# ADR 0029 — Public viewer share: a sealed per-share key + an anonymous access path

- Status: Accepted
- Date: 2026-07-08

## Context

Sharing lets a curator hand **one Memory** to people **outside** the scope — including **anonymous visitors
with no veyq account and no identity keypair**. (Sharing *with* a registered veyq user is already what groups /
scope grants do; the new job of "sharing" is reaching non-members.) The **crypto** for this is already pinned in
[ADR 0012](0012-content-key-hierarchy.md): a share adds an **independent second wrap of that Memory's `K_node`** —
for a public link, `symWrap(K_share, K_node)` with a fresh per-share key `K_share` whose secret rides the **URL
fragment**, never the server. Because `K_node` is random and wrapped independently, a share reveals **neither
`K_SC`** (the rest of the scope) **nor any other Memory's `K_node`** — least-privilege is crypto-enforced.

What is **not yet decided** is the **share resource** and the **anonymous access path**: the server-side record
shape, how an account-less viewer resolves and decrypts a share, how blobs reach them operator-blind, revocation,
and the **client-integrity honesty** that a no-install web viewer forces ([ADR 0026](0026-client-trust-tiers.md)).
This ADR decides those. It is the first sharing slice — **read-only public viewer only**; contributor/uploader
writes, password protection, persisted shares and notices are later slices.

## Decision

Adopt a **public viewer share** as a standalone, operator-blind resource addressed by an unguessable link.

1. **The share resource (opaque server state).** A share record holds: a **256-bit random `shareId`**
   (base64url, unguessable — the id is half the capability), the wrap **`symWrap(K_share, K_node)`**, the fixed
   **`role = READ`** (renamed 2026-10-06 from `VIEWER` —
   [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)'s three access modes), the shared Memory's **name re-sealed under `K_share`**, the **`nodeId`** of the shared
   Memory (the subtree root, used only to gate blob reads), `enabled`, and an optional `expiresAt`. The server
   **never** sees `K_share`, `K_node`, any name, or the viewer — only ciphertext + these opaque fields.

2. **The link.** `/s/{shareId}#k=<base64url(K_share)>` — `K_share` lives in the **fragment**
   ([ADR 0015](0015-operator-blind-readable-urls.md)) and is never transmitted. Access needs **both** the id (from
   the path, seen by the server) **and** the key (from the fragment, not seen) — one without the other is inert.
   For a guest **write** the server checks the second half too: naming yourself on a link presents a token
   derived from `K_share`, of which the share stores only the hash
   ([ADR 0076](0076-a-guest-write-proves-it-holds-the-links-key.md)).

3. **Anonymous resolve.** `GET /api/shares/{shareId}` is **unauthenticated** and returns the record **plus the
   sealed subtree** (nodes + media metadata under `nodeId`, all still sealed under `K_node`). The viewer unwraps
   `K_node` with the fragment key, then decrypts names, layout, media metadata and blob frames **locally**. This is
   a **parallel authorization path keyed on share state** — never a relaxed tenant/scope filter.

4. **Blob access, gated + direct-from-store.** A share-scoped endpoint authorizes a blob GET **iff the blob is
   attached under the shared subtree** (a structural check on opaque ids) and returns a **presigned store URL**
   ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md)); the viewer fetches bytes **directly from the
   object store**, so the operator never learns which media a viewer opens.

5. **The whole subtree.** A share covers the Memory **and everything under it** — its chapters and attached media
   (the subtree rooted at `nodeId`), not just the single node.

6. **Scope of shareable content: any scope the caller is a member of** (personal, **group**, or **tenant**). A
   member may public-share **any Memory in a scope they belong to** — not only their own personal Memories. The
   two shared-scope questions the earlier v1 cut deferred are **decided**, not sidestepped:
   - **Who may publish — any member.** Content in a shared scope belongs to all members, and any member could
     already reach an outsider by other means; a public link is a member capability, not an owner-only one. (This
     is a *capability*, still `AND`-ed with the instance's `core.public-shares` entries (the READ, contribute and
     drop-box leaves), [ADR 0032](0032-capability-discovery-version-gating.md), and the role-`AND`-scope gate.)
   - **Member removal — deactivate the removed member's shares.** Removing a member **soft-revokes every share
     that member created** in that scope (`enabled = false`, §7). The share is **not** deleted, but it is **not
     revived either**: a link its removed creator holds is re-issued — a new id and a new `K_share`, the old one
     off — never re-activated ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md), which also
     lets the removal, or any owner of the scope on its own, turn off every link of the scope). Re-activation, which transfers ownership to the
     re-activating member, stays for a share no departed member could hold, and is gated like creation: the
     instance capability for the share's role still applies. This deactivation is
     the **honest-server forward-secrecy layer for a public link**: because `K_node` is stable and never re-keyed
     (Consequences — *scope rotation does not revoke a share*), a link **cannot be rotated away**, so the
     server-side soft-revoke *is* the revocation. It composes with, and is independent of, the scope-key rotation
     that member-removal also triggers for a **group** ([ADR 0024](0024-scope-key-rotation.md)); the **tenant**,
     which revokes by ACL rather than rotation ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)), relies on
     this deactivation as its **only** forward layer for a public link — and because its epoch never moves on a
     removal, a co-member's link the removed member could copy is marked by ADR 0075's **removal counter**
     instead, never re-activated, and can be turned off in the removal itself.

   A **personal** scope has no member removal, so only the first slice's crypto/access-path (§1–§5, §7–§8) applies
   there; the governance above is inert for it.

7. **Revocation is soft (L1).** `enabled = false` / deleting the link → the next guest request `404`s; an
   `expiresAt` in the past `404`s. The same soft-revoke fires **automatically on member removal** for the removed
   member's shares (§6), and a re-activation flips `enabled` back on (transferring ownership).

   **Deleting a link is a tombstone, not a row delete.** The row keeps its id, scope, node, role and creation time; every
   ciphertext column — wrap, drop public key, sealed name, note, label, recovery copy, password fields, write
   verifier — and its
   expiry are cleared and its `deleted` flag is set — a flag, not a time: every reader asks only *whether* a link
   is deleted, so *when* is not stored (inbox #398). A tombstone `404`s like a revoked link, cannot be re-activated,
   edited or re-issued, and is in no listing. The row stays because the link's guest identities hang off it by
   foreign key, and a WRITE_ONLY identity's wrapped key is the only way members open that guest's uploads
   ([ADR 0031](0031-uploader-public-share.md) point 7): a delete never touches the Memory's media, buckets or drop
   key, and reclaims no bytes. Its owner deletes it at any time (an enabled one is turned off in the same
   transaction); another member only once its owner is no longer a member of the scope — deleting a co-member's
   parked link would destroy their note and password copy, which re-activation never did. A re-issue tombstones the
   link it replaces ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md)), and the purge that erases
   a Memory tombstones every link on it; trashing a Memory does not (its links come back on restore).
   A guest identity of a deleted link goes once nothing can use or open it — its link is a tombstone and no media
   row (trashed included) and no bucket references it: a guest who never uploaded goes with the delete, one who did
   goes with the purge of the last Memory that held their uploads.

   **A share id is never minted twice.** A tombstone keeps holding its id, and the scope purge — which must remove
   share rows, the scope's foreign key requires it — records each erased id in `veyq_share_retired_id`, which
   create consults like a live row (`409 id_taken`). Otherwise anyone holding a dead link's id and `K_share` could
   mint a share under that id, and every other holder of the dead link would open the minter's Memory.

   There is **no
   content-key rotation** for a viewer share: already-downloaded ciphertext (and a saved fragment link) cannot be
   retracted — a **stated limitation**, consistent with [ADR 0012](0012-content-key-hierarchy.md) (already-read
   content is never retractable). Heavier defense (re-seal under a fresh `K_node`) stays available but is not part
   of viewer-share revoke.

8. **Client-integrity honesty (the trust tier), surfaced by reference.** The crypto keeps the operator blind — the
   server never sees `K_share`. But a **no-install viewer is served as operator-controlled web code**, so
   confidentiality against an **actively malicious, foreign** operator depends on client integrity: such an
   operator could serve tampered JS that captures the fragment key ([ADR 0026](0026-client-trust-tiers.md)). This
   is **not** surfaced as an explicit in-viewer caveat (noise for the reader, and the honest model must not be
   duplicated per-page); instead the viewer page carries a **footer link to veyq's security documentation**, the
   single source of the trust model. For the **default self-hosted deployment the operator *is* the curator**, so
   the gap is **moot**. A fully operator-proof public viewer against a *foreign* operator is gated on the future
   **trusted-web client** (inbox #53) / SaaS (#41) and is **out of scope** here.

## Consequences

- **Least-privilege is crypto-enforced**, not server-gated: a leaked share exposes exactly one Memory subtree,
  never the scope or a sibling Memory.
- **New server-visible operational surface** (all non-sensitive): the opaque `shareId`, the wrapped key, the
  plaintext `role`, and the `K_share`-sealed name. No content, no identity, no viewer. A deleted link leaves its
  id, scope, node, role and its `deleted_at` (to the second) behind — the operator served its deletion anyway.
- **Scope rotation does not revoke a share.** A share wraps `symWrap(K_share, K_node)`, independent of `K_SC`.
  Removing a scope member rotates `K_SC` forward (a new epoch, [ADR 0024](0024-scope-key-rotation.md)) but leaves
  `K_node` — and therefore the share — **untouched**: the publicly-shared Memory keeps working, **including content
  added after the removal** (new blobs seal under the same stable `K_node`). So a removed member who kept the link
  keeps reading until the **share is revoked separately** (soft revoke → the resolve + gated blob GET `404`, after
  which they can only replay already-downloaded ciphertext). *Scope rotation ≠ share revocation* — two distinct
  surfaces. This is consistent (the Memory was **deliberately** made public), but it breaks the naive expectation
  that "removing a member cuts their access to everything" — which is exactly **why member removal auto-deactivates
  the removed member's shares** (§6): since `K_node` cannot be rotated away, that server-side soft-revoke is the
  only revocation available, and it is the honest-server forward layer that makes group/tenant sharing sound.
- **T&S "decryptable-on-report" corollary** ([security model](../crosscutting/security-model.md)): an abuse report
  necessarily includes the full share URL — hence the fragment key — so an operator **can** decrypt a **reported
  public** share. Private, unshared content stays unscannable; this is the intended, honest asymmetry.
- **New contract** `contracts/api/sharing.openapi.yaml` (create/list/revoke for the curator; the unauthenticated
  resolve + gated blob GET for the guest). Blob bytes reuse the ADR 0013 presigned direct-to-store path.
- **Built since:** contributor/uploader writes ([ADR 0030](0030-contributor-public-share.md) /
  [ADR 0031](0031-uploader-public-share.md)); public-sharing of **group/tenant** Memories (point 6, this revision).
- **Deferred, each with its own dependency:** password protection (fold `Argon2id(passphrase)` into the wrap key);
  persisted shares / person-resolution; notices / tenant terms; and the foreign-operator-proof no-install viewer
  (trusted-web / SaaS).

## Alternatives considered

- **Member share first** (`asymWrap(recipientIdentityPub, K_node)`, [ADR 0012](0012-content-key-hierarchy.md)) —
  an identity-targeted grant to a *registered* user. Deferred: it overlaps what groups already do (share with
  veyq users) and is not the product-defining "reach someone outside" capability. It stays a crypto earmark, not
  a built feature.
- **An explicit in-viewer trust-tier warning** — rejected in favor of the footer link: the honest model belongs
  in one place (the security docs), and a per-page caveat is noise that most viewers can't action.
- **Binding public sharing entirely to the trusted-web client / SaaS** — rejected: the self-hosted default
  (operator = curator) makes a public viewer sound **today** with honest labelling; withholding it until SaaS
  would deny the core self-hoster their primary sharing use for no gain in their threat model.
