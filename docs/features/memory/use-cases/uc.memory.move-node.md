# UC: Move a node — put a folder, a Memory, a chapter, a file or a drop box somewhere else in the tree

- **ID:** uc.memory.move-node
- **Primary actor:** A **member** with an unlocked vault browsing a space; or a **`READ_WRITE` guest** of a folder link.
- **Goal:** Put a node — with everything inside it — at another place in the tree, and keep everything in it readable,
  without the server learning a name or a key.
- **Preconditions:** The member's scope is open (its epoch keys held) and not pending deletion; a guest holds a
  `READ_WRITE` link and the keys of its subtree (the link's `#k=` and the chain below it). The node tree and its
  placement write exist ([en.memory.tree](../enablers/en.memory.tree.md),
  [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 3).
- **Postconditions (success):** the node lists under its new parent, its own content key unchanged — so every key, name
  and blob below it opens as before — its key re-wrapped and its name and slug token re-sealed under the destination's
  key at the next placement version. A drop-box guest's folder moved out by a member is an ordinary folder from then on.

## Main flow

1. On a node it may move — a folder's card, an opened Memory's or chapter's header, a file's row, a drop box's card, a
   guest's folder in a drop box — the user chooses **Move to…** (no drag and drop: a list reachable by keyboard and
   screen reader). What is offered is the application's capability function for whoever looks (a member: any node,
   out of a drop box too; a `READ_WRITE` guest: a node a folder of its subtree holds — never the link's root, never in
   or of a guest's folder; a `READ` or `WRITE_ONLY` guest: nothing).
2. Core's move dialog lists the destinations the session can see: a member's whole space with its root, a guest's
   subtree only. A place that cannot be picked says why — the node itself, inside it, where it is now, a guest's
   folder, a place that does not take its kind (the kinds' `placement`, which mirrors the server's `NodeKindRules`: a
   folder, a Memory, a file or a drop box at the root or in a folder; a chapter only in a Memory; a guest only into a
   folder).
3. The user picks a destination and confirms. Before anything is sent, a node at the destination whose name folds to the
   same slug is named ("There is already a file named … there").
4. Moving a drop-box guest's folder out asks first: it becomes an ordinary folder, its guest can add nothing more, and
   it cannot be moved back into the drop box.
5. The client re-wraps the node's own content key under the destination's key (`K_SC` at the current epoch at the scope
   root, else the destination node's key), re-seals its name and slug token there, binds all of it to the next
   placement version and sends one placement write stating the version it was built on — a member's `updateNode`, a
   guest's `updateShareNode` after the page's naming flow.
6. The server checks structure only (the destination live and in the scope or subtree, the kind's placement rule, no
   cycle, nothing into a guest's folder, the guest rules of both containers), compare-and-sets the version and stores
   the ciphertext; a member's move out of a drop box ends the folder's identity.
7. The tree is re-read; a node on screen is followed to its new path. A toast says where it went, with **Undo**, which
   moves it back the same way — not after a guest's folder left a drop box.

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
- **The picker is a hint, the server the authority.** The client mirrors `NodeKindRules` and the guest rules so it
  offers only what would be taken; a disagreement surfaces as the server's refusal, explained by its code — never a
  silently different result.
- **Operator-blind, unchanged.** The server sees the node id, the new parent id, the epoch number, the version and
  ciphertext — the same class of metadata every create and rename already discloses.

## What this means for you / Was das für dich bedeutet

**EN —** You can move a folder, a memory, a chapter, a file or a drop box to another place in your space with "Move
to…": pick where it should go from a list, and everything inside it comes along and stays readable. If someone shared
a folder with you and lets you add things, you can move things around inside that folder too — but nothing out of it.
If a guest sent files into your drop box, you can move their folder out to keep it as an ordinary folder; the guest
then can no longer add to it. A move can be undone right away. The server only learns that something moved, never what
it is called.

**DE —** Mit „Verschieben nach …“ kannst du einen Ordner, eine Erinnerung, ein Kapitel, eine Datei oder einen
Briefkasten an einen anderen Ort in deinem Bereich verschieben: Wähle das Ziel aus einer Liste, und alles darin kommt
mit und bleibt lesbar. Hat dir jemand einen Ordner freigegeben, in den du etwas hinzufügen darfst, kannst du darin auch
verschieben – aber nichts heraus. Hat eine Person Dateien in deinen Briefkasten eingeworfen, kannst du ihren Ordner
herausverschieben und als gewöhnlichen Ordner behalten; die Person kann dann nichts mehr hinzufügen. Ein Verschieben
lässt sich sofort rückgängig machen. Der Server erfährt nur, dass etwas verschoben wurde – nie, wie es heißt.

## Related

- End-user view (wiki): [Files — and finding your way around](../../../../wiki/en/files.md)
- Feature: [Memory](../_index.md)
- Enablers: [en.memory.tree](../enablers/en.memory.tree.md) (the placement write)
- Use-cases: [uc.memory.browse](uc.memory.browse.md) (the tree a move acts on, its rename the same write),
  [uc.memory.delete-node](uc.memory.delete-node.md) (the other way a node leaves its place),
  [uc.files.drop-box](../../files/use-cases/uc.files.drop-box.md) (a guest's folder moved out),
  [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md) (a guest's tree operations)
- Applications: [docs/apps/memories.md](../../../apps/memories.md), [docs/apps/files.md](../../../apps/files.md) (the
  capability matrices)
- Decisions: [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  (decisions 1, 3, 4, 6, 10, 11), [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
  (one view per kind, capabilities)
