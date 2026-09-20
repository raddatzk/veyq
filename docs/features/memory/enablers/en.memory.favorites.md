# Enabler: Favorites (pin a Memory)

- **ID:** en.memory.favorites
- **Type:** **Enabler** — a navigation aid, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  It speeds finding a Memory; value flows through the [Memory](../_index.md) use-cases it accelerates.
- **Provides:** a **per-(user, scope) set of pinned Memory ids**, sealed on device and stored as **one opaque
  blob** — surfaced as the sidebar **Favorites** section + a heart toggle on the Memory card and detail.
- **Serves (use-cases):** [uc.memory.gallery](../use-cases/uc.memory.gallery.md) (the header favorite toggle) +
  [uc.memory.browse](../use-cases/uc.memory.browse.md) (the sidebar Favorites section) — a shortcut to
  frequently-opened Memories.
- **Depends on:** [en.spaces.personal-scope](../../spaces/enablers/en.spaces.personal-scope.md) (the `K_SC`
  the set is sealed under) + [en.memory.tree](en.memory.tree.md) (the nodes pinned).
- **Preconditions:** An **authenticated, unlocked** member with the scope open (so `K_SC` is in memory).
- **Postconditions (success):** The caller's pinned-id set for the scope is sealed under `K_SC` and stored as
  a single ciphertext blob; the client holds the decrypted set in memory and reflects it in the UI. The server
  never learns **which** Memories are pinned.

## Operator-blind: a favorite is ciphertext

A plaintext `favorite` boolean per node would break the
[operator-blind invariant](../../../crosscutting/security-model.md): it sanctions only *IDs, sizes, coarse
timestamps, scope/membership, blinded tokens* as server-visible metadata. A pin is a **preference** — **none of
those** — so a plaintext flag would leak to the operator *what matters to you* among the (opaque) nodes. The
invariant therefore forces a favorite to be **ciphertext**:

- The caller's pinned-id **set** is AEAD-sealed under `K_SC` (context `favorites`) and stored as **one opaque
  blob** per `(user, scope)`. The server sees only the ciphertext + its size — never the subset of pinned ids.
- **Last-write-wins.** Toggling re-seals the *whole* set and `PUT`s it (the set is tiny). The UI updates
  optimistically and reverts on a failed write.

`GET / PUT /api/scopes/{scopeId}/favorites` carry only the opaque blob; access is **member-grant gated** (a
non-member gets `404`, like the tree). The pinned ids inside reference nodes the server already knows opaquely;
sealing the **set** is what hides which of them are favorited.

## Main flow

1. On opening a scope, the client `GET`s the favorites blob and `symUnwrap`s it with `K_SC` → the pinned-id set.
2. Toggling a Memory's pin updates the in-memory set at once, then re-seals it (`symWrap` under `K_SC`) and
   `PUT`s the new blob. A failure reverts the optimistic change.
3. The sidebar resolves the pinned ids against the **local decrypted tree** to render the Favorites section (a
   pinned node that no longer exists simply drops) — each entry with its **name and its chosen cover**
   ([uc.memory.cover](../use-cases/uc.memory.cover.md)), read from the shared cover store. The rail asks that
   store which nodes are on screen and holds **no key material of its own**: the chain that opens a node's
   `K_mem` stays out of an always-mounted component.

## Quality notes

- **Operator-blind, per-user.** Ciphertext set under `K_SC`; for a **personal** scope `K_SC` is per-user, so the
  blob is inherently private. **In a group `K_SC` is shared** — so group favorites must seal under the member's
  **own identity key**, not `K_SC` ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md)); folded
  in when favorites reach group scopes.
- **No new crypto.** The same `symWrap` / `symUnwrap` name-sealing the tree uses
  ([ADR 0012](../../../decisions/0012-content-key-hierarchy.md)), a new context string `favorites`.
- **Idempotent + recoverable.** A missing/unreadable blob decodes to the empty set; the feature never blocks
  browse.

> **No end-user section.** As an enabler it carries no standalone wiki story — favoriting shows up in the
> Memory use-cases' bilingual sections.

## Related

- Feature: [Memory](../_index.md)
- Sibling: [en.memory.tree](en.memory.tree.md) (the nodes pinned)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (operator-blind metadata boundary)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_SC` sealing),
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (group favorites under the identity key)
