# UC: Re-key a space's nodes after a rotation

- **ID:** uc.spaces.rekey-after-rotation
- **Primary actor:** The **device that commits a scope's new epoch** — the remover's, right after a group removal; an
  owner's scope-rotation worker, for a queued rotation (an account rotation, a tenant removal that cut a co-shared
  group) or an explicit re-key; the rotator's own, for the personal scope — and, if that device stops, **any owner
  holding the current epoch**, through their worker.
- **Secondary actor:** Every other **writer** of the scope — members and guests — whose writes into a node wait while
  that node is re-keyed.
- **Goal:** After a rotation, nothing added to the space seals under a key the excluded party holds — inside a
  folder, Memory or chapter that existed before it as much as at the scope root
  ([ADR 0108](../../../decisions/0108-a-scope-rotation-re-keys-every-node-below-it.md)).
- **Preconditions:** The scope has just committed epoch `n+1` ([en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md)).
  The device holds `E_{n+1}`.
- **Postconditions (success):** Every live and trashed node of the scope carries epoch `n+1`: a fresh random key,
  its entry under its parent's new key, and every field that was sealed under its old key re-sealed under the new
  one. No store object was written. Each share on a re-keyed node opens the new key; each drop key is new. The
  rotation reads *secured*. The server saw rewritten ciphertext in rows it already held, and nodes' epochs moving.

## Main flow

1. **List the scope.** The device lists every node of the scope, trashed descendants included, with each node's
   epoch, and the media, attachments, buckets, shares and guest identities hanging off each node.
2. **Re-key top-down.** For each node whose epoch is below `n+1`, parents before children, it mints a fresh key and
   commits the node in one transaction: its own entry under its parent's current key; each child's entry (the
   child's current key re-wrapped, its name re-sealed, its slug token recomputed); the node's meta; its media's meta
   and EXIF; every `K_blob` wrap; its buckets' and its contributors' names; its drop key, replaced; every share
   rooted on it, re-wrapped under that share's `K_share` from its recovery copy. A large node's fields may go up in
   parts before the commit.
3. **Writers wait per node.** Until a node is committed, every write sealed under its key is `409 node_rekey_pending`,
   and the writer retries; a write stating the node's key from before is `409 stale_epoch`, and the writer re-reads.
   A node already committed takes writes at once.
4. **Finish.** When no node of the scope is below `n+1`, the rotation is *secured*; a queued job may now complete.

## Alternate / exception flows

- **E1 — the device stops halfway.** Every committed node opens from its parent's current key, so the tree reads as
  it stands. The rotation reads *re-keying*; the unreached nodes keep waiting for writes. Any owner's worker that
  holds `E_{n+1}` resumes at the first node still below `n+1`; for the personal scope, the owner's own device on its
  next unlock.
- **E2 — a writer races the commit.** Both take the node's row lock. The writer that loses sees `node_rekey_pending`
  or `stale_epoch` and retries against the new key; nothing lands under the old one, nothing is orphaned.
- **E3 — a guest's open page.** Its keys are from before; its next write is `stale_epoch`, and the page re-resolves
  the link, whose wrap now opens the new key.
- **E4 — a contributor or uploader identity from before.** Its own `K_up` stays, re-sealed to the new drop key. The
  excluded party could open it before the rotation, so its later uploads stay in their reach until the link is
  re-issued, which starts every guest over ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)
  point 2).
- **E5 — a link from before.** It keeps working and shows what is added, and stays marked `reissueRequired`: the
  excluded party can rebuild its `K_share`. Its re-issue closes it.
- **E6 — a node a moderation hold copied.** The hold is a snapshot of the stored ciphertext and stays as it is; the
  report's keys keep opening what was reported.

## Quality notes

- **Nothing in the store moves.** Each `K_blob` keeps encrypting its chunks; only its wrap changes. A Chapter of 1,000
  photos is about 5,000 symmetric seals, about 20 requests.
- **What stays in the excluded party's reach:** what existed at the rotation, and links until re-issued (E4, E5).
- **Operator-blind.** No key reaches the server; it learns nothing it did not see from the epoch change.
- **Honest-operator for the server's refusals, layer 2 for the floor.** The `409`s are layer 1; a client also seals
  nothing under a node key bound to an epoch below the highest it has held for the scope
  ([ADR 0024](../../../decisions/0024-scope-key-rotation.md) Decision 6).

## User impact

After a space gets new keys, whoever was shut out cannot read anything added to it, wherever in the space it goes.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enabler: [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) (the epoch it follows)
- Triggers: [uc.spaces.complete-queued-rotation](uc.spaces.complete-queued-rotation.md),
  [uc.account.rotate-keys](../../account/use-cases/uc.account.rotate-keys.md),
  [uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) (a removal)
- Decisions: [ADR 0108](../../../decisions/0108-a-scope-rotation-re-keys-every-node-below-it.md),
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md), [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md),
  [ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)
