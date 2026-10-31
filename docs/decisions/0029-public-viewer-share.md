# ADR 0029 — Public viewer share: a sealed per-share key + an anonymous access path

- Status: Accepted
- Built: yes
- Date: 2026-07-08
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)
- Replaced in part: § 1, § 5 by ADR 0080 (built); § 6 by ADR 0075 (built); § 7 by ADR 0109 (built)

## Context

Sharing lets a curator hand **one node** to people **outside** the scope — including **anonymous visitors with no
veyq account and no identity keypair**. Sharing *with* a registered veyq user is what groups and scope grants do; the
job of "sharing" is reaching non-members. The crypto is [ADR 0012](0012-content-key-hierarchy.md)'s: a share adds an
**independent second wrap of the node's key** under a fresh per-share key `K_share` whose secret rides the URL
fragment, so a share reveals neither `K_SC` nor any other node's key. This ADR decides the share resource, the
anonymous access path, and the client-integrity honesty a no-install web viewer forces
([ADR 0026](0026-client-trust-tiers.md)).

## Decision

Adopt a **public viewer share** as a standalone, operator-blind resource addressed by an unguessable link. The
record, the key path and the lifecycle as built are the model,
[en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md).

1. **The share resource (opaque server state).** A share record holds a **256-bit random id** (the id is half the
   capability), the root's key wrapped under `K_share`, a plaintext **role** (`READ` here) and the root's name sealed
   under `K_share`, beside the plain ids and switches the server gates on. The root is any node whose kind its
   application declares a share root, and that application says which roles it takes
   ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decisions 6, 9 and 13). The
   server never sees `K_share`, a node key, a name or the viewer.

2. **The link.** `/s/{shareId}#k=<base64url(K_share)>` — `K_share` lives in the **fragment**
   ([ADR 0015](0015-operator-blind-readable-urls.md)) and is never transmitted. Access needs **both** the id and the
   key; one without the other is inert. For a guest **write** the server checks the second half too
   ([ADR 0076](0076-a-guest-write-proves-it-holds-the-links-key.md)).

3. **Anonymous resolve.** The resolve is **unauthenticated** and returns the record plus the sealed
   subtree; the viewer opens the root's key with the fragment key and decrypts everything locally. It is a
   **parallel authorization path keyed on share state** — never a relaxed tenant or scope filter.

4. **Blob access, gated + direct-from-store.** A blob GET is authorized **iff the blob is attached under the shared
   subtree** (a structural check on opaque ids) and answers a **presigned store URL**
   ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md)), so the operator never learns which media a viewer
   opens.

5. **The whole subtree.** A share covers its root **and everything under it**, never anything beside or above it
   ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)).

6. **Any scope the caller is a member of** — personal, **group** or **tenant**.
   - **Who may publish — any member.** Content in a shared scope belongs to all members, and any member could
     already reach an outsider by other means; a public link is a member capability, not an owner-only one. It stays
     `AND`-ed with the role's capability ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)).
   - **Member removal switches off the removed member's links** in that scope. Such a link, and any link a departed
     member could have copied, comes back only by **re-issue** — a new id and a new `K_share` — never by re-activation
     ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md)).
   - **Why the switch-off is the revocation.** A scope rotation re-keys the node and re-wraps its new key under the
     link's own `K_share` ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 4): a link cannot be
     rotated away, so the honest server's switch-off is what ends it. For the **tenant**, which revokes by ACL
     rather than rotation ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)), it is the only forward layer for a
     link.

7. **Revocation is soft (layer 1).** A switched-off, deleted or expired link `404`s on its next guest request.
   Neither re-keys anything: already-downloaded ciphertext and a saved link cannot be retracted
   ([ADR 0012](0012-content-key-hierarchy.md)); what a later rotation does to a link is ADR 0109 point 4.

   **Deleting a link is a tombstone, not a row delete.** The row keeps its plain ids and loses every ciphertext and
   the expiry ([the model](../features/sharing/enablers/en.sharing.public-link.md) § Lifecycle). It stays because the
   link's guest identities hang off it, and a `WRITE_ONLY` identity's wrapped key is the only way members open that guest's uploads
   ([ADR 0031](0031-uploader-public-share.md) point 7); so a delete never touches the node's content. The deletion is
   a flag, not a time: every reader asks only *whether* a link is deleted. Its owner deletes it at any time; another
   member only once the owner is no longer a member of the scope — deleting a co-member's parked link would destroy
   their note and password copy, which re-activation never did. Purging a node tombstones its links; trashing it does
   not (its links come back on restore).

   **A share id is never minted twice.** A tombstone keeps its id, and a scope purge records each erased id as
   retired. Otherwise anyone holding a dead link's id and `K_share` could mint a share under that id, and every other
   holder of the dead link would open the minter's content.

8. **Client-integrity honesty, surfaced by reference.** A no-install viewer is operator-served web code, so against
   an **actively malicious, foreign** operator confidentiality depends on client integrity
   ([ADR 0026](0026-client-trust-tiers.md)). The viewer page carries a **footer link to veyq's security
   documentation**, the single source of the trust model, not an in-viewer caveat. In the default self-hosted
   deployment the operator *is* the curator, so the gap is moot; a viewer proof against a foreign operator waits for
   the trusted-web client.

## Consequences

- **Least-privilege is crypto-enforced**, not server-gated: a leaked link exposes exactly one subtree, never the scope
  or a sibling.
- **Server-visible surface, all non-sensitive:** opaque ids, ciphertext and a plaintext role — the whole record is
  [the model](../features/sharing/enablers/en.sharing.public-link.md) § The share record. A deleted link leaves its
  ids and role behind, and a flag, not when it was deleted.
- **A rotation does not revoke a link.** It re-keys the node and the link follows (§6), including content added
  after a removal. Removing a member therefore switches off that member's links, and a link anyone who left could
  hold is re-issued — two surfaces, *scope rotation ≠ link revocation*, which breaks the naive expectation that
  "removing a member cuts their access to everything".
- **What a report opens is decided at the share boundary** — exactly what was reported, to the moderators
  ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 1). Private,
  unshared content stays unscannable.

## Alternatives considered

- **Member share first** (`asymWrap(recipientIdentityPub, K_node)`, [ADR 0012](0012-content-key-hierarchy.md)) — an
  identity-targeted grant to a *registered* user. Deferred: it overlaps what groups already do and is not the
  "reach someone outside" capability.
- **An explicit in-viewer trust-tier warning** — rejected for the footer link: the honest model belongs in one place,
  and a per-page caveat is noise most viewers cannot act on.
- **Binding public sharing to the trusted-web client** — rejected: the self-hosted default makes a public viewer sound
  today with honest labelling; withholding it would deny the self-hoster their primary sharing use for no gain in
  their threat model.
