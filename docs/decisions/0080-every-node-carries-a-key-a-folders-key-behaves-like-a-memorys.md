# ADR 0080 — Every node carries a key, and a folder's key behaves like a Memory's

- Status: Accepted
- Date: 2026-10-06

## Context

[ADR 0012](0012-content-key-hierarchy.md) gives every content node a random `K_node` wrapped under its parent's key,
and says "folders carry no key". Names and slug tokens of every node sit under `K_SC` (`src/web/src/core/node/tree.ts`
`sealName`), so a holder of one Memory's key reads none of the names below it: `ShareNode` serves no name
(`contracts/api/sharing.openapi.yaml` ~:1312). A core `FOLDER` therefore cannot be a share root, and Depot
([docs/apps/depot.md](../apps/depot.md)) needs a directory kind of its own only to get a key. A second
application should not rebuild what Memories already proves. A move carries no re-wrapped key today
(`UpdateNodeRequest`), so re-parenting a keyed node under a different keyed parent breaks its chain; the web
has no move caller yet.

The framing this ADR settles (owner, 2026-10-06): **the node tree is a sealed filesystem.** A `FOLDER` is a directory;
an application defines node kinds, its "file types", with the program that opens them; a Memory is a *package*
(core owns the frame, Memories owns its inside, as a macOS bundle). The group page shows one grid per application,
which is a view by kind and not a place in the tree, and the user organizes by topic (a trip folder may hold a
Memory, an expense and a file side by side).

## Decision

1. **Every node has a random content key**, `FOLDER` included, wrapped under its parent's content key, or under
   `K_SC` at the node's epoch for a scope-root node. `wrapped_content_key` becomes `NOT NULL`. One key type,
   recursively, as ADR 0012's *Recursive `K_node`*.
2. **A node's directory entry lives under its parent's key.** The `node-name` seal's field key
   ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9, fresh per
   write) and the slug key (`HKDF(parentKey, "veyq/v1/slug-token:" + parentSlot)`) derive from the parent's content
   key, `K_SC` at the root.
   Whoever holds a folder's key reads every name and opens every key below it. The slug key is stable per parent key
   below the root (between rotations: a rotation's re-key recomputes every token under the new key,
   [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)); at the scope root it is `K_SC` at the entry's epoch (`tree.ts:87,97`), so uniqueness there holds
   within an epoch, exactly as today (stated, not fixed).
3. **Move is O(1).** One request: new parent, the key re-wrapped under the new parent's key, the name re-sealed
   under a field key derived from it, a new slug token. Each node carries a `placement_revision`; move and rename state the revision they were built
   on and the server compares-and-sets it (`409 stale_placement`, the [ADR 0077](0077-a-write-carries-the-version-it-read.md)
   shape), so a rename sealed for the old parent cannot land after a move. Because the `content-key` seal binds the placement
   revision, **a rename re-wraps the key too**: rename and move are the same request shape (a rename is a move to the
   same parent). Seals bind
   (scope, node, epoch, placement revision) ([ADR 0072](0072-a-seal-binds-its-object.md) Decision 1); parents stay
   unbound (Decision 4) — the new parent's key is the binding.
4. **No per-node epochs; node keys re-key with the scope, never on their own.** A keyed folder, like `K_node`, has
   no rotation of its own: revoking a link stays layer 1, server-side, re-issue never revive
   ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md)). Only `K_SC` rotates, and every rotation
   replaces every node key of the scope ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)), so a
   node's `epoch` is the epoch its key was minted at, and every write sealed under a node's key states it (ADR 0109
   point 6). At the **scope root** the entries are sealed and re-sealed at the **current** epoch and the server
   refuses any other (`409 stale_epoch` on create, rename and move whose destination is the root). A create states the
   current epoch at any parent. A placement never changes a node's epoch: it states the node's own (else `409
   stale_epoch`), a node the last rotation's re-key has not reached may not go to the root (`409 node_rekey_pending`),
   and off the root the destination parent's epoch is stated and checked like any write's (ADR 0109 point 6).
5. **Folders inherit the `K_node` residual, accepted for parity, up to the next rotation.** A former guest, or a
   member removed without a rotation (the tenant's routine removal, [ADR 0025](0025-forward-secrecy-policy-per-scope.md)),
   who kept a folder's key reads everything later created under it until the scope next rotates, which re-keys the
   folder ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)); a group-member removal rotates, so the
   removed member's reach ends there, except through a link they could rebuild, until it is re-issued. Honest-server
   revocation is the cut in between, as ADR 0075 states. Until `changes/cr-scope-rotation-rekeys-nodes.md` lands, no
   rotation re-keys a node, and the reach lasts (inbox #518).
6. **Tree operations for a guest are core operations behind `ShareGuestGate`; content operations are the
   application's.** Create, move, rename and delete of nodes carry no application semantics, so core serves them
   (a guest variant of the node operations, authorised by the gate instead of a member session) and checks the
   structure only: inside the share's subtree, placement by `NodeKindRules`, wraps under keys the guest holds.
   **Whoever owns the container decides what may be created in it:** under a `FOLDER`, core — and core lets a
   `READ_WRITE` guest create **only a `FOLDER`, or a kind whose own application declares it guest-creatable** (today
   Depot's `FILE`, which is how a contributor's upload lands); **never a Memory, a drop box or any other application's
   node** (owner decision 2026-10-14; it used to be "any kind `NodeKindRules` admits there"). The declaration is the
   kind's application's, fail closed like every seam here, so a new application never hands guests a create by
   accident. Moving and renaming what is already there are unchanged. Inside an application's container (a Memory),
   that application decides. Reading needs no server decision (reading is holding keys, and the keys flow down the tree).
   **`READ_WRITE` is interpreted by each application for its kinds** (what a contributor may do in a Memory, in a
   file tree, in an Expenses Pot), written down as a small role matrix in its `docs/apps/<app>.md`; **a kind with no
   declaration allows nothing** (fail closed, like the other seams), so a new application can never inherit write
   rights by accident. A `READ_WRITE` share on a folder gives a contributor's rights in every Memory or chapter below it (owner, 2026-10-07: the role is
   inherited as the share allows; the share root's kind no longer decides who writes, the target node's kind does):
   a guest's upload names an optional target node below the root, checked inside the live subtree and decided again
   under the scope lock where the medium is written; a `WRITE_ONLY` link's target is its root only.
   `WRITE_ONLY` needs no matrix: such a guest holds no keys of the subtree and can only write
   through the drop mechanism ([ADR 0031](0031-uploader-public-share.md) point 7), never into an existing Memory
   below. **Shareability is separate from guest writes:** a `FOLDER` can be shared with `READ` whether or not any
   application implements guest writes for it — `ShareRoots` answers which roles a kind may be shared with, apart
   from `ShareGuestWrites`. A move or rename is decided by the owners of the containers on **both** sides: between folders core decides
   (a `READ_WRITE` guest may), into or out of the inside of an application's container (a chapter out of a Memory)
   that application decides, and Memories declares nothing, so it is refused. **A guest deletes no node that
   holds anything** (owner decision 2026-10-17): a guest's rights on the tree stay read, read + upload and upload only.
   A guest's own delete would first need a node to know whose its content is, and a node records only who last wrote its
   name and meta ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 8) —
   which editor makes a node a guest's own, whether its delete trashes or erases, and how it meets a member's edit in
   between were judged not worth answering for it. The records *inside* an application's container are that
   application's interpretation of `READ_WRITE` above, not a node delete: Memories lets a guest purge its own media
   ([docs/apps/memories.md](../apps/memories.md)), Expenses lets a guest edit and delete entries as revisions nobody
   loses ([ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)). **What a guest may take back is its own empty upload placeholder** (`abandonShareNode`, owner decision 2026-10-16): the node its upload created before
   the link refused the bytes (a full link: the limit is checked at the upload's start, after the create) or the guest
   gave up, erased at once — never trashed — so nothing of a refused upload is kept. Erased only if the calling guest's
   identity last wrote the node's name (its name editor, ADR 0093 § 8), so a member's empty node, one a member renamed,
   and another guest's are none; and only where nobody could lose anything: inside the guest's reach (strictly inside
   the subtree and outside every identity folder for `READ_WRITE`, strictly inside its own identity folder for
   `WRITE_ONLY`), **no live link rooted on it** (a revoked one counts: the erase tombstones every link on the node,
   `NodeSubtreePurge.erase`, so a take-back could otherwise end a member's other link — owner 2026-10-16; a guest's own
   placeholder never carries one), no child, no upload a member or another identity has open into it, and the owner of
   the node's kind declares it empty (`GuestPlaceholders`: Depot's `FILE` whose slot never
   held a blob; core's own `FOLDER` with no attachment and no sealed metadata — the folders a guest's folder-tree upload
   created before every file of it was refused, owner decision 2026-10-16; a kind nobody declares never is). **A move
   counts as naming:** a placement re-seals the name under a field key derived from the new parent's key and the server cannot tell a re-seal
   from a new text, so a guest that moved a member's empty node becomes its name's last editor and may then take it
   back — accepted (owner 2026-10-16): it is a name with nothing behind it that the guest could already rename away, and
   the link rule keeps it from ending anybody's link. `ShareNodeAbandonCoreSliceTest` pins it, so changing it is a
   decision. A guest's create states the epoch it sealed with, and
   the server checks it as a member's placement off the scope root (a guest never targets the scope root): any epoch
   but the scope's current one is `409 stale_epoch` and the guest re-reads — the `node-name` and `content-key` seals bind
   it, so the reader opens at the stored number (owner, 2026-10-07; it replaced a server-chosen current epoch, which
   left a name unopenable whenever that differed from the one the guest sealed with). It used to store an epoch below
   the current one as sent; a node's epoch is now its key's ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
   point 6), and a node born below it would wait for a re-key that already ran.
   A guest's placement states no epoch of its own — the node keeps its epoch — and states its destination's (ADR 0109
   point 6). Finer rights ("not the cover", "not the date") come later through
   a policy seam that asks the application per node and operation, never a role registry in core.
7. **One key, many wraps.** A Memory inside a shared folder may also be shared alone; its share is another wrap
   of the same key.
8. **Trash.** Delete, restore and purge are subtree-wide on one marker (`NodesStore.deleteSubtree` re-stamps
   descendants; create, move and restore refuse a trashed parent; `NodeSubtreePurge` erases what the marker
   selects). So a child row never outlives the parent whose key wraps its own. A restore's slug token is derived
   under the live parent's key; a descendant whose token an earlier delete freed stays in the trash with its
   subtree and is restored on its own ([ADR 0061](0061-node-deletion-reversible-node-trash.md), inbox #357).
9. **A share covers exactly the subtree under its root, and nothing above it.** A guest of a chapter reads the chapter
   and below: its key opens nothing of the parent (the parent's key is not derivable from the child's), the root's
   own name arrives as `sealedName` under a field key derived from `K_share` (its entry is sealed from the parent's key
   and a guest holds neither),
   and resolve serves no node outside the subtree and no parent id of the root.
10. **Guest moves.** A `READ_WRITE` guest may move a node inside its share's subtree — never the root, never out of
    the subtree, never into a cycle — by re-wrapping the key under the new parent's key it holds. A `WRITE_ONLY` guest
    cannot move. The server checks the structure only; the move is core's like the other tree operations (decision 6).
11. **A drop box is a node of its own, with one guest folder per identity.** Creating a `WRITE_ONLY` share names a
    new node under the chosen parent (its share name; never an existing folder), so the drop mechanism, its drop
    private key and the guests' folders stay apart from members' own content. A guest creates **one folder per guest
    identity** in it on its first upload (unique by `(drop box, identityId)`: the server already knows the identity,
    so no slug is needed), minting that folder's key itself and wrapping it under its identity key, which is sealed
    to the drop public key whose private half is sealed under the drop box's key (as Memories' guest media is today).
    Anyone holding the drop box's key opens everything below, with no approval step. Below its own folder the guest
    holds the key, computes slug tokens and gets the normal uniqueness check, and learns nothing about other guests'
    names. Equal display names of two guests' folders are disambiguated by the client at display time (`Anna`,
    `Anna (2)`, in creation order: the server-observed `createdAt` the members' listing carries, then the node id
    for one millisecond), never stored, and their URL segments by the same order (`anna`, `anna-2`;
    [ADR 0015](0015-operator-blind-readable-urls.md)). A guest who loses its identity (browser data cleared) cannot manage its folder any more and
    starts a new one under a new identity — so does one whose folder a member trashed (that identity's folder takes
    nothing again). Members may only delete, move out or copy from a drop box and place
    nothing of their own there (a server placement rule); a guest — a `READ`/`READ_WRITE` guest of a folder link
    above the drop box included, which holds its key and reads its guests' folders — moves nothing out of it; moving out re-wraps and re-slugs under the target, where
    the normal index applies, so every other folder stays unique; a ZIP export (not built) names the identity
    folders it writes by the URL-segment rule above, so it stays unambiguous inside a drop box too. Revoking the link
    keeps the drop box and its content. Memories keeps its uploader share on an existing Memory (its media are not
    nodes); this rule is for nodes guests create, built with the first application that needs it (Depot).
12. **The share's recovery copy is mandatory.** `sealedShareKey`/`sealedShareKeyEpoch` ([ADR 0036](0036-editable-share-note-recoverable-share-key.md))
    become required in `CreateShareRequest`, so every member can always re-show and edit a link. The columns and
    `ShareSummary` stay nullable: a tombstoned share clears them (`SharingStore.tombstone`), so the baseline does not
    change.
13. **Attachments ([#327](../inbox.md)).** A node attachment's `K_blob` is wrapped under its owning node's key,
   whatever the kind. A chapter's media already work this way: a chapter has its own `K_node` and `K_blob` hangs under
   it (`memoryNodes.ts:29-33`, `media.ts:368-371`), so a chapter share is cryptographically ready. Memories decided
   it: a `CHAPTER` is a share root with all three roles, a smaller Memory link (`MemoriesNodeKinds.SHARE_ROOTS`, which
   `MemoryShareRoots`, `MediaShareGuestWrites` and `MediaUploadAdmission` all answer from; a chapter link's guest
   uploads into the chapter, a Memory link's contributor also into a chapter below it (decision 6), and an uploader
   seals to the chapter's own drop key). The web offers the chapter link through the same share dialogs as a Memory (`shareable: true`,
   `register.ts`). What each role may do is the role matrix in [`docs/apps/memories.md`](../apps/memories.md).
14. **The share root's name.** It arrives as `sealedName` under a field key derived from `K_share`. A guest cannot rename the root (it holds
   no parent key). When a member renames a share root, that member's client re-seals the name of every share on it
   through the recovery copy (decision 12 makes it available) and stores it with its own operation
   (`PUT /api/shares/{shareId}/sealed-name`), which any member with WRITE access may call, not only the share's owner:
   `updateShare` stays owner-gated ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 3) and replaces
   its fields wholesale, so it cannot carry this write. The name is last-writer-wins: it is always the root's current
   name, so the shown name never goes stale.

**What the server stores and learns.** One more opaque wrap per folder and an integer `placement_revision`.
It already sees the tree, kinds, moves and timestamps; it holds no new key.

**Migration.** Edit the core baseline `V202610041200__core_baseline.sql` (`wrapped_content_key NOT NULL`,
`placement_revision BIGINT NOT NULL DEFAULT 0`) with its pin line in `scripts/applied_migrations.sha256` in the
same commit (`release: none`). `V202610041201__memories_baseline.sql` holds no node-key column. Development
instances are reset; no data migration.

**Contracts.** `content.openapi.yaml` (create: wrap required; update: `wrappedContentKey`, `placementRevision`,
`epoch`; `NodeSummary.placementRevision`), `sharing.openapi.yaml` (`ShareNode` gains `encryptedName` and
`placementRevision`; the guest tree operations `createShareNode` and `updateShareNode`; `setShareSealedName`; the
recovery copy required on create), a guest upload's optional target `nodeId` (core's share blob-upload requests,
Memories' `ShareMediaBatchRequest`), and the `node-name` and `content-key` seals binding the placement revision, with
their vectors in `contracts/crypto/vectors/v1.json`.

**Superseded or edited.** ADR 0012's "folders carry no key" and its `K_SC` naming rule; ADR 0079's "folders
organize only" wording; `docs/apps/depot.md` (a `FOLDER` can be a share root; Depot may keep its own kind for
its own reasons).

## Consequences

- **A Memory guest newly reads chapter titles.** Today a guest sees the root's title and a flat media list.
  Serving descendant names is a product change; the owner decides it, and the share view must render or drop
  them.
- **A core `FOLDER` can be shared** with the `READ` and `READ_WRITE` roles (`ShareRoots`); the drop-box role belongs to Depot's own drop-box node (decision 11), not to a plain folder.
- **Rename and move cost a revision compare**, and a client may retry on `409 stale_placement`; both re-wrap the key.
- **Reading is limited by position in the tree, never by kind.** Whoever holds a folder's key opens everything
  below it. A filter by kind exists only for writes (a drop-style share, or an application's policy), so a
  Memory that must stay hidden from a folder's guests is not placed under that folder. A per-kind read filter would
  need the share-time fan-out wrap rejected below.
- **Each application documents a role matrix** for `READ_WRITE` in its `docs/apps/<app>.md`; Memories first.
- **One guard changes its rule:** `validate_write_concurrency.py` accepts one counter-shaped field per operation
  (`:153-171`); `updateNode` now has two (`metaRevision`, `placementRevision`), so `x-veyq-concurrency` becomes a list
  of declarations with a conflict code each.

## Alternatives rejected

- **Names under `K_SC`, keys per folder.** A folder guest would see keys but no names.
- **Per-node epoch chains with folder rotation.** Forward secrecy per folder, at the price of an epoch chain on
  every node and an `O(subtree)` rotation; Memories has never needed it, and parity is the goal. A scope rotation's
  re-key replaces each node key and re-seals what is under it instead of chaining it
  ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) § Alternatives rejected).
- **A directory kind per application.** Every application would rebuild keyed directories.
- **A `ROOT` node per scope, or one root node per application** (`MemoriesFolder`, a Depot drive) under the group.
  It would give each application a clean subtree but forbids mixing kinds by topic and needs a migration of every
  root entry; `FOLDER` as the universal container with each kind admitted by `NodeKindRules` does both without one.

## Decided with the owner (2026-10-06 and 2026-10-07)

Guests of a Memory read its chapter titles (the consequence of decision 2). The slug key is stable per parent key.
A share covers its subtree and nothing above. Contributors may move inside the subtree. Guest nodes in a drop box
live in one folder per identity. The recovery copy is mandatory. Tree operations for guests are core's;
`READ_WRITE` is interpreted per application, fail closed. **A guest deletes no node that holds anything**
(owner 2026-10-17: a node records who last wrote its name, not whose its content is, and a guest's delete was not worth
deciding that, decision 6); it takes back only an empty upload placeholder whose name it wrote last and
on which no link is rooted, erased at once (`abandonShareNode`). **A guest's create states the epoch it sealed with**, checked as a member's placement off the root —
this replaced a server-chosen current epoch, which left a name unopenable whenever it differed. **A role is
inherited down the subtree as the share allows:** a `READ_WRITE` folder link contributes in every Memory or chapter
below it, a `WRITE_ONLY` link writes into its root only. **A `CHAPTER` is a share root with every role.** **A Depot `FILE` is a share root with `READ` and `READ_WRITE`** (inbox #378): the file's own key goes under `K_share` (decision 1), its guest reaches that file alone and a contributor replaces its content; `WRITE_ONLY` on a file is `illegal_scope` — no key to hand out, no folder to send into ([docs/apps/depot.md](../apps/depot.md) § A single file as a share root). **The
share root's name is re-sealed by its own operation** (`setShareSealedName`), callable by any member with write
access, not by the owner-gated `updateShare`. A folder is shared through core's own dialog, not the Memories one,
and a guest's move is core's one move dialog, the member's too (inbox #358: offered only for a node a folder of the
subtree holds, through `placeShareNodeAsGuest`).

**A name's slug is folded by its node's kind** (inbox #350, decided and built, cr-files-application P1). The slug is
what the token HMACs, so the folding is frozen into every stored token. **Containers (folders, Memories, chapters) keep
`slugify`** (`src/web/src/shared/naming.ts`: NFC, lowercase, `ä`→`ae`, accents dropped, non-alphanumerics → `-`, a 32-bit hash
when nothing is left): it is the URL segment and the uniqueness at once (`Müller` and `Mueller` collide, accepted: rare). **Files** are not in the URL and use **`fileNameSlug`**: NFC, then
`toLowerCase()` (no locale rules), punctuation and spaces kept, no trim (the UI trims before folding): `Foto.JPG` =
`foto.jpg`, as on OneDrive, Dropbox, macOS and Windows, while `Day1.md` ≠ `Day1 md`. The stored and displayed name
keeps its case. Starting case-insensitive is the safe direction: loosening later would meet existing pairs, and a ZIP
export needs no case rule, only a sanitiser for the target system. The registry carries the choice: a
`KindDescriptor` may register `slug`, and `registry.slugFor(kind, name)` answers the kind's function or `slugify`
(also for a kind nobody registered); `slugTokenFor` takes the node's kind on every write (create, rename and move,
restore, a guest's create and placement), so core names no application kind. Uniqueness stays per token among
siblings, whatever their kinds: a folder `a.b` (`a-b`) and a file `a.b` (`a.b`) coexist, a folder `a.b` and a file
`a-b` collide. A guest's pre-check folds each readable sibling by its own kind and names the colliding one; the
server's `slug_taken` is the race backstop. A member's create and rename dialogs (core's `FolderNameDialog`, Memories'
and the drop box's create) do the same against the loaded tree (`core/node/siblingCollision.ts`, inbox #389) and name
the sibling by its kind's `nounWithArticle`; a member's file upload onto a non-file sibling still ends on `slug_taken`
(inbox #399). Non-Latin container names keep the hash fallback until someone needs a
frozen, versioned transliteration table. **No slug library**: a dependency's table change would silently break
uniqueness. Both foldings are pinned by the `name_folding` vectors in `contracts/crypto/vectors/v1.json` (generated by
an independent Python implementation, consumed by `src/web/src/shared/naming.conformance.test.ts`); changing either is a
stored-format change (ADR 0072).

## Open questions

None open from this decision set. **The drop box node** (decision 11) is built with Depot (`DEPOT_DROPBOX`,
[docs/apps/depot.md](../apps/depot.md) § The drop box): the identity folder is a core `FOLDER` naming its identity
(`veyq_node.share_identity_id`, unique per parent), created only by core's `IdentityFolders` behind the application's
own operation; its entry is under the guest's `K_up`, which the reader opens through the parent's drop key. A
`WRITE_ONLY` link on a plain `FOLDER` stays refused (`ShareRoots`). Where the build differed from decision 11 the owner
ruled on 2026-10-08 ([inbox #377](../inbox.md)): **only members move out** — a guest's placement of an identity folder
or of anything inside one is `422 illegal_structure` (core's `ShareNodesHandler`, known by `share_identity_id`, whatever
an application declares for the container); a **trashed identity folder ends that identity's folder for good**
(owner-accepted: the unique index spans trashed rows, the guest starts over under a new identity, a member's restore
brings the folder back); the **folder call spends a token of the link's upload rate limit on every call**, also when it
only answers the existing folder (owner-accepted: a limit with no gap); equal names are numbered by creation time as
the decision says — the members' listing carries the server-observed `createdAt` (to the millisecond), the node id
breaks a same-millisecond tie (ADR 0015); inside its own folder a `WRITE_ONLY` guest creates `FILE`s only (the root
kind's application declares it, core's `WriteOnlyGuestKinds`; anything else `illegal_scope`). And a `READ`/`READ_WRITE`
guest of a folder link above a drop box **sees it, read-only** (inbox #380): reading is limited by position, never by
kind (§ Consequences), so the guest opens the identity folders and their files and downloads them; it writes nothing
there — the placement rule above refuses it, and the web offers no control.

## Bite tests (each must fail if the rule is wrong)

1. Create a `FOLDER` without `wrappedContentKey` → `400`.
2. A folder share's guest opens a grandchild's name and key; a sibling folder's do not open.
3. Rename built on `placement_revision` *n* after a move to *n+1* → `409`, name unchanged.
4. A wrap served on another node's row, or at another placement revision, fails to open.
5. A scope-root rename at a stale epoch → `409 stale_epoch`.
6. Move into a trashed parent → `illegal_structure`.
7. Purge a trashed folder whose child was trashed earlier: no child row survives.
8. Restore a child whose parent is trashed → `404`.
9. A WRITE_ONLY guest's resolve carries no content key and no name.
10. A Memory shared alone and through its folder opens the same key from both wraps.
11. A cycle-forming move → `illegal_structure`, `placement_revision` unchanged.
12. A chapter guest opens neither the Memory's key, nor its name, nor a sibling chapter; resolve serves nothing above the root.
13. A guest moves a node out of its subtree, or its root → refused; a WRITE_ONLY guest moves anything → refused.
14. A share created without `sealedShareKey` → `400`.
15. A rename without a re-wrapped key → `400`; a rename built on a stale `placement_revision` → `409 stale_placement`.
16. A placement never changes the node's epoch: one stating another epoch → `409 stale_epoch` at any destination; a
    node not re-keyed since the last rotation → `409 node_rekey_pending` into the root, and a destination parent not
    re-keyed → `409 node_rekey_pending` (ADR 0109 point 6).
17. A `READ_WRITE` guest creates a kind `NodeKindRules` does not admit under the folder → refused.
18. A kind with no role declaration, in a `READ_WRITE` share: a write → refused, a read → served (fail closed).
19. A guest renames the share root → refused; a member renames it → the share's `sealedName` opens to the new name.
20. A `READ` share on a `FOLDER` is created although no application implements guest writes for it.
21. A chapter share (once enabled): the guest opens the chapter's media and nothing of the parent Memory.

Explained for security researchers on the docs site: [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md).
