# UC: Move a node — put a folder, a Memory, a chapter, a file or a drop box somewhere else in the tree

- **ID:** uc.memory.move-node
- **Primary actor:** A **member** with an unlocked vault browsing a space; or a **`READ_WRITE` guest** of a folder link.
- **Goal:** Put a node — with everything inside it — at another place in the tree, and keep everything in it readable,
  without the server learning a name or a key.
- **Preconditions:** The member's scope is open (its epoch keys held) and not pending deletion; a guest holds a
  `READ_WRITE` link and the keys of its subtree (the link's `#k=` and the chain below it). The node tree and its
  placement write exist ([en.platform.node-tree](../../platform/enablers/en.platform.node-tree.md),
  [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 3).
- **Postconditions (success):** the node lists under its new parent, its own content key unchanged — so every key, name
  and blob below it opens as before — its key re-wrapped and its slug token recomputed under the destination's
  key, its name re-sealed under a field key derived from it, at the next placement revision. A drop-box guest's folder moved out by a member is an ordinary folder from then on.

## Main flow

1. On a node it may move — a folder's card, an opened Memory's or chapter's header, a file's row, a drop box's card, a
   guest's folder in a drop box — the user chooses **Move to…**. What is offered is the application's rights function for whoever looks (a member: any node,
   out of a drop box too; a `READ_WRITE` guest: a node a folder of its subtree holds — never the link's root, never in
   or of a guest's folder; a `READ` or `WRITE_ONLY` guest: nothing).
2. Core's move dialog is a **folder browser**, not a list of every destination (owner decision 2026-10-14, replacing
   inbox #358's list). It opens at the node's current place, with a breadcrumb up to the top of what the session can
   see (a member's space root, a guest's link root), and shows **only the places that can take the node's kind** (the
   kinds' `placement`, which mirrors the server's `NodeKindRules`: a folder, a Memory, a file or a drop box at the root
   or in a folder; a chapter only in a Memory; a guest only into a folder), drawn as the level's own rows without their
   actions. Opening a place moves into it. **"Move here"** applies to the place on screen and is disabled with the
   reason where the node cannot go: where it is now, the node itself or anything inside it (which it does not open), a
   guest's folder. Every place and the breadcrumb are real buttons, so the browser is as reachable by keyboard and
   screen reader as the list was. Drag and drop comes later, as a second way (inbox #421).
3. The user moves into the destination and chooses **Move here**, or **Cancel**. Before anything is sent, a node at the destination whose name folds to the
   same slug **in the same route group** (ADR 0080 decision 16: a Memory never meets a Pot or a folder) is named ("There is already a file named … there").
4. Moving a drop-box guest's folder out asks first: it becomes an ordinary folder, its guest can add nothing more, and
   it cannot be moved back into the drop box.
5. The client re-wraps the node's own content key under the destination's key (`K_SC` at the current epoch at the scope
   root, else the destination node's key), re-seals its name (a field key derived from that key) and recomputes its slug token there, binds all of it to the next
   placement revision and sends one placement write stating the revision it was built on — a member's `updateNode`, a
   guest's `updateShareNode` after the page's naming flow.
6. The server checks structure only (the destination live and in the scope or subtree, the kind's placement rule, no
   cycle, nothing into a guest's folder, the guest rules of both containers), compare-and-sets the revision and stores
   the ciphertext; a member's move out of a drop box ends the folder's identity.
7. The tree is re-read; a node on screen is followed to its new path. A toast says where it went, with **Undo**, which
   moves it back the same way — not after a guest's folder left a drop box.

### Several at once (owner decisions 2026-10-14, inbox #421)

A selection of the level (uc.memory.browse step 6) moves through the same browser: it shows the places that take
**every** selected kind and blocks a place that blocks any selected node, with that node's reason. Before anything is
sent, each node is checked as one is (the place, a name that folds to a sibling's there); a node that fails is named
and left out, and the rest is sent. There is **no batch request**: each node is the one placement write above, sent
one after the other, and the tree is re-read **once** at the end. Afterwards one summary says how many moved and
names each one refused with its reason (A1-E4), and one toast with **Undo** moves back exactly the nodes that moved -
a guest's folder that left a drop box excepted, as for one. Not atomic by design: a refusal part-way leaves what was
already moved where it went, and says so; a node that did not move stays selected. A guest names itself once for the
whole selection.

Seen in a real browser on 2026-10-14 (the dev instance): a member moved a folder, a Memory and a file into a sibling
folder - three placement writes, one re-read of the tree, one toast "3 nach „Gedroppt“ verschoben." - and back; a
folder, a file and a drop box whose name a folder at the destination already held: the drop box was named and left
out, the other two moved, and the summary stayed until closed; the toast's Undo moved two nodes back with two writes;
a `READ_WRITE` guest of a folder link moved a folder, a file and a Memory into a folder of its subtree - three
`updateShareNode` writes, one re-resolve of the link.

## Alternate / exception flows

- **A1 — the name is taken there** (`409 slug_taken`): a sibling the client could not see (another member's race, one a
  guest cannot read) holds it. Said by name; the user renames one first or picks another place.
- **A2 — a concurrent move or rename landed first** (`409 stale_placement`): the client re-reads the tree and rebuilds
  once on the node as it now is; if that loses again, the dialog says someone else changed it, re-reads the tree, and a
  second attempt builds on the new state.
- **E1 — not allowed there** (`422 illegal_structure`): the place does not take the kind, would make a cycle, is a
  guest's folder, or lies outside a guest's subtree — the server's rules decide, the picker only mirrors them.
- **E2 — the link does not allow it** (`422 illegal_scope`): a guest's move a container's owner refuses (out of or into
  a Memory: Memories declares no guest tree rules).
- **E3 — the keys were renewed meanwhile** (`409 stale_epoch`): a move into or out of the scope root was sealed at an
  epoch a rotation superseded; reload and try again.
- **E4 — the link stopped allowing writes or is gone** (`share_not_contributable`, `share_not_found`,
  `node_not_found`), **rate-limited**, or **the space is being deleted** (`scope_deleting`): said by name; nothing moved.
- **E5 — a guest declines to name itself**: nothing is sent, nothing said.

## Quality notes

- **A move changes one wrap, not a subtree.** Every node carries its own content key (ADR 0080 decision 1); a move
  re-wraps only the moved node's, so a subtree of any size moves in one request and every key below keeps opening.
- **One picker for one node and for many.** The browser takes the node's kind and place as input, so the selection of
  inbox #421 moves through the same dialog: the places shown are those that take every selected kind.
- **N writes, not a batch.** A move is already one request that re-wraps one key, and node writes are not
  rate-limited, so several nodes are several writes; a batch endpoint would add a contract for no atomicity the
  user needs (owner decision 2026-10-14).
- **One dialog for a member and a guest.** A `READ_WRITE` guest moves through core's one move dialog, the member's
  own, offered only for a node a folder of the subtree holds; and a folder is shared through core's own share dialog,
  never an application's ([ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 6).
- **The picker is a hint, the server the authority.** The client mirrors `NodeKindRules` and the guest rules so it
  offers only what would be taken; a disagreement surfaces as the server's refusal, explained by its code — never a
  silently different result.
- **Operator-blind, unchanged.** The server sees the node id, the new parent id, the epoch number, the revision and
  ciphertext — the same class of metadata every create and rename already discloses.

## User impact

You can reorganize your space by moving folders, Memories, chapters, files and drop boxes to another place, with
everything inside coming along and staying readable.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.platform.node-tree](../../platform/enablers/en.platform.node-tree.md) (the placement write)
- Use-cases: [uc.memory.browse](uc.memory.browse.md) (the tree a move acts on, its rename the same write),
  [uc.memory.delete-node](uc.memory.delete-node.md) (the other way a node leaves its place),
  [uc.depot.drop-box](../../depot/use-cases/uc.depot.drop-box.md) (a guest's folder moved out),
  [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md) (a guest's tree operations)
- Applications: [docs/apps/memories.md](../../../apps/memories.md), [docs/apps/depot.md](../../../apps/depot.md) (the
  rights matrices)
- Decisions: [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  (decisions 1, 3, 4, 6, 10, 11), [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
  (one view per kind, rights)
- End-user view (docs site): [Move things](../../../../site/src/content/docs/spaces/move-things.mdx)
- End-user view (docs site): [Select several things at once](../../../../site/src/content/docs/spaces/select-several.mdx)

