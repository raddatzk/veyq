# ADR 0080 — Every node carries a key, and a folder's key behaves like a Memory's

- Status: Accepted
- Built: yes
- Date: 2026-10-06
- Model: [en.platform.node-tree](../features/platform/enablers/en.platform.node-tree.md)
- Replaced in part: Decision 2, Decision 4, Decision 5 by ADR 0109 (built); Decision 11 by ADR 0111 (built)

## Context

[ADR 0012](0012-content-key-hierarchy.md) gave every content node a random `K_node` wrapped under its parent's key, and
said "folders carry no key". Names and slug tokens of every node sat under `K_SC`, so a holder of one Memory's key read
none of the names below it, and a share served no name. A core `FOLDER` therefore could not be a share root, and Depot
([docs/apps/depot.md](../apps/depot.md)) would have needed a directory kind of its own only to get a key. A second
application should not rebuild what Memories already proves. A move carried no re-wrapped key, so re-parenting a keyed
node under a different keyed parent would have broken its chain.

The framing this ADR settles: **the node tree is a sealed filesystem.** A `FOLDER` is a directory;
an application defines node kinds, its "file types", with the program that opens them; a Memory is a *package*
(core owns the frame, Memories owns its inside, as a macOS bundle). The group page shows one grid per application,
which is a view by kind and not a place in the tree, and the user organizes by topic (a trip folder may hold a
Memory, an expense and a file side by side).

## Decision

1. **Every node has a random content key**, `FOLDER` included, wrapped under its parent's content key, or under
   `K_SC` at the node's epoch for a scope-root node. The wrap is required on every node. One key type, recursively,
   as ADR 0012's *Recursive `K_node`*.
2. **A node's directory entry lives under its parent's key.** The `node-name` seal's field key
   ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9, fresh per
   write) and the slug key (`HKDF(parentKey, "veyq/v1/slug-token:" + parentSlot)`) derive from the parent's content
   key, `K_SC` at the root.
   Whoever holds a folder's key reads every name and opens every key below it. The slug key is stable per parent key
   below the root (between rotations: a rotation's re-key recomputes every token under the new key,
   [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)); at the scope root it is `K_SC` at the entry's
   epoch, so uniqueness there holds within an epoch — an accepted residual.
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
   node's `epoch` is the epoch its key was minted at, and every write sealed under a node's key states it. At the
   **scope root** the entries are sealed and re-sealed at the **current** epoch and the server refuses any other. A
   create states the current epoch at any parent. A placement never changes a node's epoch: it states the node's own,
   and it waits for a re-key the node (into the root) or its destination has not had yet — the refusals are ADR 0109
   point 6's.
5. **Folders inherit the `K_node` residual, accepted for parity, up to the next rotation.** A former guest, or a
   member removed without a rotation (the tenant's routine removal, [ADR 0025](0025-forward-secrecy-policy-per-scope.md)),
   who kept a folder's key reads everything later created under it until the scope next rotates, which re-keys the
   folder ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)); a group-member removal rotates, so the
   removed member's reach ends there, except through a link they could rebuild, until it is re-issued. Honest-server
   revocation is the cut in between, as ADR 0075 states.
6. **Tree operations for a guest are core operations behind the share's guest gate; content operations are the
   application's.** Create, move, rename and delete of nodes carry no application semantics, so core serves them
   (a guest variant of the node operations, authorised by the gate instead of a member session) and checks the
   structure only: inside the share's subtree, placement by the kinds' placement rules, wraps under keys the guest
   holds. **Whoever owns the container decides what may be created in it:** under a `FOLDER`, core — and core lets a
   `READ_WRITE` guest create **only a `FOLDER`, or a kind whose own application declares it guest-creatable** (today
   Depot's `FILE`, which is how a contributor's upload lands); **never a Memory, a drop box or any other application's
   node**. Rejected: any kind the placement rules admit there, which let a guest create an application's container.
   The declaration is the kind's application's, fail closed like every seam here, so a new application never hands
   guests a create by accident. Moving and renaming what is already there are unchanged. Inside an application's
   container (a Memory), that application decides. Reading needs no server decision (reading is holding keys, and
   the keys flow down the tree).
   **`READ_WRITE` is interpreted by each application for its kinds** (what a contributor may do in a Memory, in a
   file tree, in an Expenses Pot), written down as a small role matrix in its `docs/apps/<application>.md`; **a kind with no
   declaration allows nothing** (fail closed, like the other seams), so a new application can never inherit write
   rights by accident. **A role is inherited down the subtree as the share allows:** a `READ_WRITE` share on a folder
   gives a contributor's rights in every Memory or chapter below it — the target node's kind decides who writes, not
   the share root's: a guest's upload names an optional target node below the root, checked inside the live subtree
   and decided again under the scope lock where the medium is written; a `WRITE_ONLY` link's target is its root only.
   `WRITE_ONLY` needs no matrix: such a guest holds no keys of the subtree and can only write
   through the drop mechanism ([ADR 0031](0031-uploader-public-share.md) point 7), never into an existing Memory
   below. **Shareability is separate from guest writes:** a `FOLDER` can be shared with `READ` whether or not any
   application implements guest writes for it — which roles a kind may be shared with is answered apart from its
   guest writes. A move or rename is decided by the owners of the containers on **both** sides: between folders core decides
   (a `READ_WRITE` guest may), into or out of the inside of an application's container (a chapter out of a Memory)
   that application decides, and Memories declares nothing, so it is refused. **A guest deletes no node that
   holds anything:** a guest's rights on the tree stay read, read + upload and upload only.
   A guest's own delete would first need a node to know whose its content is, and a node records only who last wrote its
   name and meta ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 8) —
   which editor makes a node a guest's own, whether its delete trashes or erases, and how it meets a member's edit in
   between were judged not worth answering for it. The records *inside* an application's container are that
   application's interpretation of `READ_WRITE` above, not a node delete: Memories lets a guest purge its own media
   ([docs/apps/memories.md](../apps/memories.md)), Expenses lets a guest edit and delete entries as revisions nobody
   loses ([ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)). **What a guest may take back
   is its own empty upload placeholder:** the node its upload created before
   the link refused the bytes (a full link: the limit is checked at the upload's start, after the create) or the guest
   gave up, erased at once — never trashed — so nothing of a refused upload is kept. Erased only if the calling guest's
   identity last wrote the node's name (its name editor, ADR 0093 § 8), so a member's empty node, one a member renamed,
   and another guest's are none; and only where nobody could lose anything: inside the guest's reach (strictly inside
   the subtree and outside every identity folder for `READ_WRITE`, strictly inside its own identity folder for
   `WRITE_ONLY`), **no live link rooted on it** (a revoked one counts: the erase tombstones every link on the node,
   so a take-back could otherwise end a member's other link; a guest's own placeholder never carries one), no child,
   no upload a member or another identity has open into it, and the owner of the node's kind declares it empty
   (Depot's `FILE` whose slot never held a blob; core's own `FOLDER` with no attachment and no sealed metadata — the
   folders a guest's folder-tree upload created before every file of it was refused; a kind nobody declares never is).
   **A move counts as naming:** a placement re-seals the name under a field key derived from the new parent's key and
   the server cannot tell a re-seal from a new text, so a guest that moved a member's empty node becomes its name's
   last editor and may then take it back — accepted: it is a name with nothing behind it that the guest could already
   rename away, and the link rule keeps it from ending anybody's link. Changing it is a decision. A guest's create
   states the epoch it sealed with, and the server checks it as a member's placement off the scope root (a guest never
   targets the scope root): any epoch but the scope's current one is `409 stale_epoch` and the guest re-reads — the
   `node-name` and `content-key` seals bind it, so the reader opens at the stored number. Rejected: a server-chosen
   current epoch, which left a name unopenable whenever that differed from the one the guest sealed with; and storing
   an epoch below the current one as sent — a node's epoch is its key's
   ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 6), and a node born below it would wait for
   a re-key that already ran. A guest's placement states no epoch of its own — the node keeps its epoch — and states
   its destination's (ADR 0109 point 6). Finer rights ("not the cover", "not the date") come later through
   a policy seam that asks the application per node and operation, never a role registry in core.
7. **One key, many wraps.** A Memory inside a shared folder may also be shared alone; its share is another wrap
   of the same key.
8. **Trash.** Delete, restore and purge are subtree-wide on one marker (the delete re-stamps descendants; create,
   move and restore refuse a trashed parent; the purge erases what the marker selects). So a child row never outlives
   the parent whose key wraps its own. A restore's slug token is derived under the live parent's key; a descendant
   whose token an earlier delete freed stays in the trash with its subtree and is restored on its own
   ([ADR 0061](0061-node-deletion-reversible-node-trash.md)).
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
    names; inside it the guest creates only the kinds the drop box's application declares (Depot: `FILE`). Equal
    display names of two guests' folders are disambiguated by the client at display time (`Anna`, `Anna (2)`, in
    creation order: the server-observed creation instant the members' listing carries, then the node id for one
    millisecond), never stored, and their URL segments by the same order (`anna`, `anna-2`;
    [ADR 0015](0015-operator-blind-readable-urls.md)). A guest who loses its identity (browser data cleared) cannot
    manage its folder any more and starts a new one under a new identity. **A trashed identity folder ends that
    identity's folder for good:** the guest starts over under a new identity, and a member's restore brings the folder
    back. Members may only delete, move out or copy from a drop box and place nothing of their own there (a server
    placement rule). **Only members move out:** a guest's placement of an identity folder or of anything inside one
    is refused (`illegal_structure`), whatever an application declares for the container — a guest of a
    `READ`/`READ_WRITE` folder link above the drop box included. Such a guest holds the drop box's key and **sees it
    read-only**: reading is limited by position, never by kind (§ Consequences), so it opens the identity folders and
    their files and downloads them, and writes nothing there. Moving out re-wraps and re-slugs under the target, where
    the normal index applies, so every other folder stays unique; a ZIP download names the identity folders it writes
    by [ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)
    decision 3's rule, which numbers two names of one directory that meet, so it stays unambiguous inside a drop box
    too. Revoking the link keeps the drop box and its content. Memories keeps its uploader share on an existing Memory
    (its media are not nodes); this rule is for nodes guests create, built with the first application that needs it
    (Depot, [docs/apps/depot.md](../apps/depot.md) § The drop box). A re-key re-seals what lies under a node's key, so
    it is a write onto the node: a drop box, its guests' identity folders and what lies below one are re-keyed by an
    owner only ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 7); a Memory or chapter that
    carries a drop key for its uploader share is no drop box, and any member re-keys it, drop key included
    ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
    decision 11 says why that opens no write a member lacks).
12. **The share's recovery copy is mandatory.** The recovery copy of `K_share`
    ([ADR 0036](0036-editable-share-note-recoverable-share-key.md)) is required on every share's create, so every
    member can always re-show and edit a link. A stored share may still lack it: a tombstoned share clears it.
13. **Attachments.** A node attachment's `K_blob` is wrapped under its owning node's key, whatever the kind. A
   chapter's media work this way: a chapter has its own `K_node` and `K_blob` hangs under it, so a chapter share needs
   no new key. Memories decided it: a `CHAPTER` is a share root with all three roles, a smaller Memory link (a chapter
   link's guest uploads into the chapter, a Memory link's contributor also into a chapter below it (decision 6), and an
   uploader seals to the chapter's own drop key). The web offers the chapter link through the same share dialogs as a
   Memory. What each role may do is the role matrix in [`docs/apps/memories.md`](../apps/memories.md).
14. **The share root's name.** It arrives as `sealedName` under a field key derived from `K_share`. A guest cannot rename the root (it holds
   no parent key). When a member renames a share root, that member's client re-seals the name of every share on it
   through the recovery copy (decision 12 makes it available) and stores it with its own operation, which any member
   with WRITE access may call, not only the share's owner: the share's update stays owner-gated
   ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 3) and replaces its fields wholesale, so it
   cannot carry this write. The name is last-writer-wins: it is always the root's current name, so the shown name
   never goes stale.
15. **A Depot `FILE` is a share root with `READ` and `READ_WRITE`.** The file's own key goes under `K_share`
   (decision 1); its guest reaches that file alone and a contributor replaces its content. `WRITE_ONLY` on a file is
   refused (`illegal_scope`): there is no key to hand out and no folder to send into
   ([docs/apps/depot.md](../apps/depot.md) § A single file as a share root).
16. **A name's slug is folded by its node's kind.** The slug is what the token HMACs, so the folding is frozen into
   every stored token. **Containers (folders, Memories, chapters) keep `slugify`** (NFC, lowercase, `ä`→`ae`, accents
   dropped, non-alphanumerics → `-`, a 32-bit hash when nothing is left): it is the URL segment and the uniqueness at
   once (`Müller` and `Mueller` collide, accepted: rare). **Files** are not in the URL and fold by their own function:
   NFC, then lowercase with no locale rules, punctuation and spaces kept, no trim (the UI trims before folding):
   `Foto.JPG` = `foto.jpg`, as on OneDrive, Dropbox, macOS and Windows, while `Day1.md` ≠ `Day1 md`. The stored and
   displayed name keeps its case. Starting case-insensitive is the safe direction: loosening later would meet existing
   pairs, and a ZIP download needs no case rule, only a sanitiser for the target system. The kind's descriptor carries
   the choice, and every write that computes a token (create, rename and move, restore, a guest's create and
   placement) folds by the node's kind, so core names no application kind. **A name is unique within its route group**:
   the server's uniqueness stays per token among siblings, and the token's input carries
   the group — for a kind whose application claimed a route slug ([ADR 0015](0015-operator-blind-readable-urls.md)),
   `<routeSlug>/<slug>` (`memories/rom`, `expenses/rom`); for a folder, Depot's kinds and a kind nobody claimed, the bare
   slug. So a Memory and an Expenses Pot `Rom` are siblings, and a Memory and a folder `Rom`; among the plain names a
   folder `a.b` (`a-b`) and a file `a.b` (`a.b`) coexist, a folder `a.b` and a file `a-b` collide. Why: a node's URL carries
   its application's slug, so `#foo/memories/rom` and `#foo/expenses/rom` are two addresses, and refusing the second
   name would protect nothing (owner, 2026-11-01). Rejected: uniqueness per token among all siblings whatever their
   kinds, which refused an Expenses Pot named like a Memory beside it. A route slug is `[a-z0-9-]` and the
   `/` ends it, so two applications' inputs never meet and a container's `slugify` (never a `/`) never meets a routed
   one; a file's folding keeps a `/`, so a file literally named `memories/rom` still meets a Memory `Rom` — accepted,
   like the folder `a.b` and the file `a-b`. The group is only in the input: no crypto function, vector or server rule
   depends on it. A client names
   the colliding sibling of its group before it sends, and the server's `slug_taken` is the race backstop.
   Non-Latin container names keep the hash fallback until someone needs a frozen, versioned transliteration table.
   **Rejected: a slug library** — a dependency's table change would silently break uniqueness. Both foldings are
   pinned by the `name_folding` vectors in `contracts/crypto/vectors/v1.json`; changing either is a stored-format
   change ([ADR 0072](0072-a-seal-binds-its-object.md)). Where each piece lives in the client:
   [en.platform.node-tree § Names and slugs](../features/platform/enablers/en.platform.node-tree.md#names-and-slugs).

**What the server stores and learns.** One more opaque wrap per folder and an integer placement revision.
It already sees the tree, kinds, moves and timestamps; it holds no new key.

## Consequences

- **A Memory guest reads chapter titles** (decision 2): the share view renders the root's title and the names of its
  chapters, where a guest used to see the root's title and a flat media list.
- **A core `FOLDER` can be shared** with the `READ` and `READ_WRITE` roles; the drop-box role belongs to Depot's own drop-box node (decision 11), not to a plain folder.
- **Rename and move cost a revision compare**, and a client may retry on `409 stale_placement`; both re-wrap the key.
- **Reading is limited by position in the tree, never by kind.** Whoever holds a folder's key opens everything
  below it. A filter by kind exists only for writes (a drop-style share, or an application's policy), so a
  Memory that must stay hidden from a folder's guests is not placed under that folder. A per-kind read filter would
  need the share-time fan-out wrap rejected below.
- **Each application documents a role matrix** for `READ_WRITE` in its `docs/apps/<application>.md`; Memories first.

## Alternatives rejected

- **Names under `K_SC`, keys per folder.** A folder guest would see keys but no names.
- **Per-node epoch chains with folder rotation.** Forward secrecy per folder, at the price of an epoch chain on
  every node and an `O(subtree)` rotation; Memories has never needed it, and parity is the goal. A scope rotation's
  re-key replaces each node key and re-seals what is under it instead of chaining it
  ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) § Alternatives rejected).
- **A directory kind per application.** Every application would rebuild keyed directories.
- **A `ROOT` node per scope, or one root node per application** (`MemoriesFolder`, a Depot drive) under the group.
  It would give each application a clean subtree but forbids mixing kinds by topic and needs a migration of every
  root entry; `FOLDER` as the universal container with each kind admitted by the placement rules does both without one.

## Bite tests

The cases that must fail if a rule here is wrong are acceptance criteria, each held by the test that implements it:
[uc.memory.browse](../features/memory/use-cases/uc.memory.browse.md) `every-node-is-born-with-a-key`;
[uc.memory.move-node](../features/memory/use-cases/uc.memory.move-node.md) `a-placement-is-compare-and-set`,
`a-wrap-opens-only-at-its-placement`, `a-placement-keeps-the-nodes-epoch`, `who-may-move-what`;
[uc.memory.delete-node](../features/memory/use-cases/uc.memory.delete-node.md) `a-trashed-parent-takes-no-child`,
`a-purge-leaves-no-row-below-it`, `only-a-trash-root-restores`;
[uc.sharing.share-public-viewer](../features/sharing/use-cases/uc.sharing.share-public-viewer.md) `folder-share-root`,
`chapter-share-root`, `root-rename-reseals-share-names`;
[uc.sharing.share-uploader](../features/sharing/use-cases/uc.sharing.share-uploader.md) `guest-never-holds-the-memory-key`;
[uc.sharing.share-note](../features/sharing/use-cases/uc.sharing.share-note.md) `share-key-recoverable-by-members-only`;
[uc.sharing.share-contributor](../features/sharing/use-cases/uc.sharing.share-contributor.md)
`folder-guest-tree-operations`, `guest-tree-refusals` (coverage under [`docs/coverage/`](../coverage/README.md)).
Decision 7's one key opened from both wraps has no test yet.

Explained for security researchers on the docs site: [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md).
