# Enabler: Favorites (pin a Memory, mark an item)

- **ID:** en.memory.favorites
- **Type:** **Enabler** — a navigation aid, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  It speeds finding a Memory and narrows a gallery; value flows through the [Memory](../_index.md) use-cases it
  accelerates.
- **Provides:** **two** favorite objects with **two different audiences**
  ([ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md)) — a **private** per-(user, scope)
  set of pinned **Memory ids**, and a **shared, member-attributed** per-(member, scope, node) set of marked
  **media ids**. Each is sealed on device and stored as an opaque blob.
- **Serves (use-cases):** [uc.memory.gallery](../use-cases/uc.memory.gallery.md) (the header pin toggle, and the
  per-item mark + the *everyone's / only mine* filter) + [uc.memory.browse](../use-cases/uc.memory.browse.md)
  (the sidebar Favorites section) — a shortcut to frequently-opened Memories, and a way to single out the good
  pictures inside one.
- **Depends on:** [en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md) (the `K_SC` the
  **item** set is sealed under) + the account **master key** (the **pin** set) + [en.memory.tree](en.memory.tree.md)
  (the nodes pinned) + [en.memory.media](en.memory.media.md) (the items marked).
- **Preconditions:** An **authenticated, unlocked** member with the scope open (so `K_SC` and the master key are
  in memory); for item marks, an **open Memory**.
- **Postconditions (success):** The caller's pin set for the scope is sealed under their **master key**; their
  item set for the open Memory is sealed under `K_SC` and readable by co-members. The client holds both
  decrypted in memory and reflects them in the UI. The server never learns **which** Memories are pinned or
  **which** items are marked.

## Two objects, because they have two audiences

They look like one feature and are not. The full rule, its rejected alternatives and its measurements live in
[ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md); what binds here:

| | **Memory favorite** (the pin) | **Item favorite** (the mark) |
| --- | --- | --- |
| what | a node id | a `veyq_media` id |
| audience | **private to the member** | **shared** with the scope, member-attributed |
| seal | master key, purpose `favorites`, bound to the scope | `K_SC@E_0`, purpose `item-favorites`, bound to scope + node |
| stored | one blob per **(user, scope)** | one blob per **(member, scope, node)** |
| read | the caller's blob only | the scope's **per-member** blobs |
| surfaces | sidebar Favorites, heart on card/detail | gallery filter + per-tile aggregate |

One blob has one key and therefore one audience, so a single envelope carrying both sets is not available — that
is *why* there are two, not a convenience.

## Operator-blind: a favorite is ciphertext

A plaintext `favorite` boolean per node or per item would break the
[operator-blind invariant](../../../crosscutting/security-model.md): it sanctions only *IDs, sizes, coarse
timestamps, scope/membership, blinded tokens* as server-visible metadata. A pin is a **preference** — **none of
those** — so a plaintext flag would leak to the operator *what matters to you* among the (opaque) nodes and
items. The invariant therefore forces a favorite to be **ciphertext**:

- Each set is AEAD-sealed on device and stored as **one opaque blob**. The server sees only the ciphertext + its
  size — never the subset that is favorited.
- **Last-write-wins, recorded as such** ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)
  point 6): each set has exactly one writer — its member — so the only loss is one toggle between that member's
  own two devices. Toggling re-seals the *whole* affected set and `PUT`s it. The UI updates optimistically and
  reverts on a failed write. **A toggle never starts from a set it has not loaded** (ADR 0077 point 5): until the
  member's own set has loaded for that scope or Memory, a toggle does nothing — otherwise it would write a
  one-element set over the stored one.
- Access is **member-grant gated** (a non-member gets `404`, like the tree). The ids inside reference nodes and
  media the server already knows opaquely; sealing the **set** is what hides which of them are favorited.

`GET / PUT /api/scopes/{scopeId}/favorites` carries the caller's pin blob and **never** another member's.
`GET /api/scopes/{scopeId}/nodes/{nodeId}/item-favorites` returns `[{ memberId, ciphertext }]` — every member's
blob for that Memory; the `PUT` writes only the caller's.

## Main flow

**Pinning a Memory.**

1. On opening a scope, the client `GET`s the pin blob and `symUnwrap`s it with the **master key** → the pinned-id
   set. There is no branch on scope type: the seal is the same in a personal and in a group scope.
2. Toggling a Memory's pin updates the in-memory set at once, then re-seals it and `PUT`s the new blob. A failure
   reverts the optimistic change.
3. The sidebar resolves the pinned ids against the **local decrypted tree** to render the Favorites section (a
   pinned node that no longer exists simply drops) — each entry with its **name and its chosen cover**
   ([uc.memory.cover](../use-cases/uc.memory.cover.md)), read from the shared cover store. The rail asks that
   store which nodes are on screen and holds **no key material of its own**: the chain that opens a node's
   `K_mem` stays out of an always-mounted component.

**Marking an item.**

4. Opening a Memory, the client `GET`s that node's item-favorite blobs and `symUnwrap`s **each** with `K_SC` →
   one marked-id set per member. Their union feeds the *everyone's* filter, the caller's own feeds *only mine*,
   and the per-item **count** across them feeds the tile aggregate.
5. Toggling an item's mark updates the caller's set for that Memory, re-seals it and `PUT`s it — only that
   Memory's set moves, never the scope's.
6. A marked item that has been trashed or purged simply drops client-side when the grid resolves the ids against
   the Memory's live media, exactly as a dead pin drops from the sidebar.

## Quality notes

- **Operator-blind, and — for the pin — per-user for real.** The pin blob seals under the member's **master
  key**, so it is private to them in **every** scope. This is the fix for a real defect: it previously sealed
  under `K_SC`, which is *shared* in a group, so every co-member could already decrypt another member's pins —
  the data leaked, the UI merely never showed it
  ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §7, discharged by
  [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md)).
- **The aggregate is a presentation choice, not a confidentiality guarantee — and copy must not say otherwise.**
  Item marks are sealed under `K_SC`, which every member of a group holds; and because the server is
  operator-blind it **cannot aggregate on our behalf**, so the client necessarily receives the **per-member**
  blobs. **It cannot put a name to them** — `ItemFavoritesEntry` carries `self` and nothing else, so the wire
  holds no member id at all, and that was a deliberate narrowing during the build. What survives is that the
  entries stay **separable**: one entry is one member's whole coherent set, readable with a key every member
  holds. So a member learns another member's entire marking pattern as an unnamed set — **and in a small scope
  the name follows by elimination**, because with two members *"not mine"* is the other person. The narrowing
  raises the cost of attribution with scope size; it does not remove it, and at the size most spaces actually
  have it removes very little. So **no string in the UI or in `wiki/` may imply that nobody can see what you
  marked** — *"only mine"* is a **filter**, never a promise. This is the same trap as the pin defect above, one
  level down, which is why that one was fixed rather than deferred.
- **The write shape is the size limit, and it is why item blobs are per-node.** The whole set is re-sealed on
  every single toggle, so a set's size is also its *per-click upload*. `measured` (real UUIDs +
  `JSON.stringify`, against this tree): a 65 536-char `ciphertext` cap holds **1 259** bare ids — **1 257** beside
  an item mark's self-tag (below) — and at 500 ids a
  single toggle already re-uploads 25 KiB. Partitioning item marks **per node** keeps the ceiling beyond a
  Memory's practical media count and the upload at that Memory's set — and it costs **no** extra metadata,
  because the client has just fetched that node's media list, so the server already observed the node id. A
  larger cap, per-item rows and keyed-hash buckets were all rejected; the reasoning is in
  [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md) §4.
- **Two seals, two purposes.** The typed seal as everywhere else
  ([ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)), with **distinct** purposes `favorites`
  and `item-favorites` — so a blob authenticated for one can never be opened as the other — each bound to the
  slot it is stored at (the scope, and for an item mark also the node), so the server cannot serve one scope's
  pins or one node's marks as another's. An item mark is **not** bound to the member who made it: the read names
  no member (ADR 0068 §6), so a reader has none to bind. *Whose* it is rides inside the plaintext,
  `{"tag","ids"}`: an HMAC under a key derived from the marking member's master key, over the scope, the node and
  the marked set, which only that member's client can compute. An entry is the caller's own only when the server
  flags it `self` **and** the tag verifies; anything else counts as someone else's
  ([ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md) §9). The per-tile count is the
  operator's report of how many stored sets hold the id: it can leave entries out, serve an older version of a set,
  or serve stored sets again as **extra entries**, and with any present or past member forge more (ADR 0068 §9).
- **The pin rides the master-key rotation re-seal.** The master key rotates
  ([ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md)), so the pin blob must be re-sealed from
  the old key to the new one alongside the identity-pin store and the device register — best-effort, like they
  are: a re-seal that never lands costs the pins, not the account. Item marks stay sealed under `K_SC`, but their
  self-tag's key derives from the master key, so the rotation also **re-tags** the caller's own marks — those whose
  tag verifies under the old key — read through `GET /api/item-favorites`, best-effort too: a set whose re-tag does
  not land counts as someone else's, and the next toggle on that Memory starts the caller's set from empty.
- **The base epoch survives only on the shared side.** Item marks seal under `K_SC` at the **base epoch `E_0`**,
  so a scope-key rotation cannot strand a set. Pins no longer need that: the master key does not rotate with
  scope epochs.
- **Idempotent + recoverable.** A missing/unreadable blob decodes to the empty set; the feature never blocks
  browse or the gallery.

> **No end-user section.** As an enabler it carries no standalone wiki story — favoriting shows up in the
> Memory use-cases' bilingual sections.

## Related

- Feature: [Memory](../_index.md)
- Siblings: [en.memory.tree](en.memory.tree.md) (the nodes pinned),
  [en.memory.media](en.memory.media.md) (the items marked)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (operator-blind metadata boundary)
- Decisions: [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md) (the two objects —
  the governing decision), [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_SC` sealing),
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (the epoch chain behind `E_0`),
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) §7 (the defect this fixes)
