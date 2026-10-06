# ADR 0080 — Every node carries a key, and a folder's key behaves like a Memory's

- Status: Proposed
- Date: 2026-10-06

## Context

[ADR 0012](0012-content-key-hierarchy.md) gives every content node a random `K_mem` wrapped under its parent's key,
and says "folders carry no key". Names and slug tokens of every node sit under `K_SC` (`web/src/core/node/tree.ts`
`sealName`), so a holder of one Memory's key reads none of the names below it: `ShareNode` serves no name
(`contracts/api/sharing.openapi.yaml` ~:1312). A core `FOLDER` therefore cannot be a share root, and Files
([docs/apps/files.md](../apps/files.md)) needs a directory kind of its own only to get a key. A second
application should not rebuild what Memories already proves. A move carries no re-wrapped key today
(`UpdateNodeRequest`), so re-parenting a keyed node under a different keyed parent breaks its chain; the web
has no move caller yet.

## Decision

1. **Every node has a random content key**, `FOLDER` included, wrapped under its parent's content key, or under
   `K_SC` at the node's epoch for a scope-root node. `wrapped_content_key` becomes `NOT NULL`. One key type,
   recursively, as ADR 0012's *Recursive `K_mem`*.
2. **A node's directory entry lives under its parent's key.** The `node-name` seal and the slug key
   (`HKDF(parentKey, "veyq/v1/slug-token:" + parentSlot)`) take the parent's content key, `K_SC` at the root.
   Whoever holds a folder's key reads every name and opens every key below it.
3. **Move is O(1).** One request: new parent, the key re-wrapped under the new parent's key, the name re-sealed,
   a new slug token. Each node carries a `placement_version`; move and rename state the version they were built
   on and the server compares-and-sets it (`409 stale_placement`, the [ADR 0077](0077-a-write-carries-the-version-it-read.md)
   shape), so a rename sealed for the old parent cannot land after a move. Seals bind
   (scope, node, epoch, placement version) ([ADR 0072](0072-a-seal-binds-its-object.md) Decision 1); parents stay
   unbound (Decision 4) — the new parent's key is the binding.
4. **No per-node epochs, no root rotation.** A keyed folder, like `K_mem`, never re-keys
   ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) Context). Revocation stays layer 1:
   server-side, re-issue never revive. Only `K_SC` rotates. A scope-root entry is sealed and re-sealed at the
   **current** epoch and the server refuses any other (`NodesHandler` ~:96 extended to update) — today a rename
   re-seals under the node's birth epoch and the server cannot check it.
5. **Folders inherit the `K_mem` residual, accepted for parity.** A former guest or removed member who kept a
   folder's key reads everything later created under it, and a group-member removal re-keys no folder. Today that
   reach is one Memory; it widens to a folder subtree. Honest-server revocation is the cut, as ADR 0075 states.
6. **Guest writes dispatch by the target node's kind.** A READ_WRITE guest wraps a new child under the share
   root's or a descendant folder's key it holds. A WRITE_ONLY guest uses the drop mechanism
   ([ADR 0031](0031-uploader-public-share.md) point 7) and holds no content key. The application registered for
   the target kind (`ShareGuestWrites`) decides what may be written.
7. **One key, many wraps.** A Memory inside a shared folder may also be shared alone; its share is another wrap
   of the same key.
8. **Trash.** Delete, restore and purge are subtree-wide on one marker (`NodesStore.deleteSubtree` re-stamps
   descendants; create, move and restore refuse a trashed parent; `NodeSubtreePurge` erases what the marker
   selects). So a child row never outlives the parent whose key wraps its own. A restore's slug token is derived
   under the live parent's key.
9. **A share covers exactly the subtree under its root, and nothing above it.** A guest of a chapter reads the chapter
   and below: its key opens nothing of the parent (the parent's key is not derivable from the child's), the root's
   own name arrives as `sealedName` under `K_share` (it is sealed under the parent's key and a guest holds neither),
   and resolve serves no node outside the subtree and no parent id of the root.
10. **Guest moves.** A `READ_WRITE` guest may move a node inside its share's subtree — never the root, never out of
    the subtree, never into a cycle — by re-wrapping the key under the new parent's key it holds. A `WRITE_ONLY` guest
    cannot move. The server checks the structure only. Finer rights are an application's, later.
11. **A drop box is a node of its own, with one guest folder per identity.** Creating a `WRITE_ONLY` share names a
    new node under the chosen parent (its share name; never an existing folder), so the drop mechanism, its drop
    private key and the guests' folders stay apart from members' own content. A guest creates **one folder per guest
    identity** in it on its first upload (unique by `(drop box, identityId)`: the server already knows the identity,
    so no slug is needed), minting that folder's key itself and wrapping it under its identity key, which is sealed
    to the drop public key whose private half is sealed under the drop box's key (as Memories' guest media is today).
    Anyone holding the drop box's key opens everything below, with no approval step. Below its own folder the guest
    holds the key, computes slug tokens and gets the normal uniqueness check, and learns nothing about other guests'
    names. Equal display names of two guests' folders are disambiguated by the client at display time (`(1)`,
    `(2)`, by creation time), never stored. Members may only delete, move out or copy from a drop box and place
    nothing of their own there (a server placement rule); moving out re-wraps and re-slugs under the target, where
    the normal index applies, so every other folder stays unique and a ZIP export is unambiguous. Revoking the link
    keeps the drop box and its content. Memories keeps its uploader share on an existing Memory (its media are not
    nodes); this rule is for nodes guests create, built with the first application that needs it (Files).
12. **The share's recovery copy is mandatory.** `sealedShareKey`/`sealedShareKeyEpoch` ([ADR 0036](0036-editable-share-note-recoverable-share-key.md))
    become `NOT NULL` on the wire and in the baseline, so every member can always re-show and edit a link.
13. **Attachments ([#327](../inbox.md)).** A node attachment's `K_blob` is wrapped under its owning node's key,
   whatever the kind.

**What the server stores and learns.** One more opaque wrap per folder and an integer `placement_version`.
It already sees the tree, kinds, moves and timestamps; it holds no new key.

**Migration.** Edit the core baseline `V202610041200__core_baseline.sql` (`wrapped_content_key NOT NULL`,
`placement_version BIGINT NOT NULL DEFAULT 0`) with its pin line in `scripts/applied_migrations.sha256` in the
same commit (`release: none`). `V202610041201__memories_baseline.sql` holds no node-key column (to verify while
implementing). Development instances are reset; no data migration.

**Contracts.** `content.openapi.yaml` (create: wrap required; update: `wrappedContentKey`, `placementVersion`,
`epoch`; `NodeSummary.placementVersion`), `sharing.openapi.yaml` (`ShareNode` gains `encryptedName`), the
`node-name` and `content-key` vectors in `contracts/crypto/vectors/v1.json`.

**Superseded or edited.** ADR 0012's "folders carry no key" and its `K_SC` naming rule; ADR 0079's "folders
organize only" wording; `docs/apps/files.md` (a `FOLDER` can be a share root; Files may keep its own kind for
its own reasons).

## Consequences

- **A Memory guest newly reads chapter titles.** Today a guest sees the root's title and a flat media list.
  Serving descendant names is a product change; the owner decides it, and the share view must render or drop
  them.
- **A core `FOLDER` can be shared** with viewer, contributor and drop-box roles.
- **Rename and move cost a version compare**, and a client may retry on `409 stale_placement`.

## Alternatives rejected

- **Names under `K_SC`, keys per folder.** A folder guest would see keys but no names.
- **Per-node epoch chains with folder rotation.** Forward secrecy per folder, at the price of an epoch chain on
  every node and an `O(subtree)` rotation; Memories has never needed it, and parity is the goal.
- **A directory kind per application.** Every application would rebuild keyed directories.

## Decided with the owner (2026-10-06)

Guests of a Memory read its chapter titles (the consequence of decision 2). The slug key is stable per parent key.

## Open questions

- Who defines a name's slug. Today one client function folds every name (`web/src/shared/naming.ts:21`: lowercase,
  `ä`→`ae`, accents dropped, non-alphanumerics → `-`, a 32-bit hash when nothing is left), the slug is also the URL
  path segment, and the token freezes it. For Files that is too coarse for **files** (`Day1.md` = `Day1 md`).
  Proposed: a hook per node kind in the registry. **Containers (folders, Memories) keep today's slug**: it is the URL
  segment and the uniqueness at once (`Müller` and `Mueller` collide, accepted: rare, and the message names the
  colliding sibling). **Leaves (files)** are not in the URL and use a stricter uniqueness: NFC plus Unicode case
  folding with punctuation kept (`Day1.md` ≠ `Day1 md`, `README` = `readme` as on macOS and Windows). Display names
  are always the original. The client checks the decrypted siblings before sending and explains the conflict by
  naming the colliding sibling; the server's `slug_taken` is only the race backstop. Non-Latin names keep the hash
  fallback until someone needs a frozen, versioned transliteration table. **No slug library**: the slug is frozen
  into the tokens, so a dependency's table change would silently break uniqueness; the function stays ours, and its
  output gets conformance vectors in `contracts/crypto/vectors/v1.json` (it has one for the token only). Decide
  before Files; changing it later invalidates tokens.
- Whether a chapter's media blob keys hang under the chapter's key or its Memory's decides if a chapter guest can read the
  chapter's media: to verify while implementing (bite test 12).

## Bite tests (each must fail if the rule is wrong)

1. Create a `FOLDER` without `wrappedContentKey` → `400`.
2. A folder share's guest opens a grandchild's name and key; a sibling folder's do not open.
3. Rename built on `placement_version` *n* after a move to *n+1* → `409`, name unchanged.
4. A wrap served on another node's row, or at another placement version, fails to open.
5. A scope-root rename at a stale epoch → `409 stale_epoch`.
6. Move into a trashed parent → `illegal_structure`.
7. Purge a trashed folder whose child was trashed earlier: no child row survives.
8. Restore a child whose parent is trashed → `404`.
9. A WRITE_ONLY guest's resolve carries no content key and no name.
10. A Memory shared alone and through its folder opens the same key from both wraps.
11. A cycle-forming move → `illegal_structure`, `placement_version` unchanged.
12. A chapter guest opens neither the Memory's key, nor its name, nor a sibling chapter; resolve serves nothing above the root.
13. A guest moves a node out of its subtree, or its root → refused; a WRITE_ONLY guest moves anything → refused.
14. A share created without `sealedShareKey` → `400`.
