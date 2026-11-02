# UC: Re-key a space after a rotation

- **ID:** uc.spaces.rekey-after-rotation
- **Primary actor:** The **device that commits a scope's new epoch** — the remover's, right after a group removal; an
  owner's, right after their explicit re-key of the tenant or a group
  ([uc.spaces.remove-tenant-member](uc.spaces.remove-tenant-member.md) A8–A9,
  [uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) A6); an owner's scope-rotation worker, for a
  queued rotation (a tenant removal that cut a co-shared group); a secured account's own device, for every scope its rotation re-granted — and, in the background, **any member
  holding the current epoch**, through their worker ([ADR 0111](../../../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 11: a re-key is not governance). A drop box and what its guests hold in it are re-keyed by an **owner**
  only.
- **Secondary actor:** Every other **writer** of the scope. A member's write into a node not yet re-keyed re-keys
  that node's path first and goes through; a guest's waits until a member's or an owner's device has reached its node.
- **Goal:** After a rotation, nothing stored in the space opens under a key the excluded party holds, and nothing
  added seals under one — at scope level, inside a folder, Memory or chapter that existed before it, and at the scope
  root ([ADR 0115](../../../decisions/0115-a-scope-key-is-replaced-never-chained.md),
  [ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)).
- **Preconditions:** The scope has just committed epoch `n+1` ([en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md)):
  a fresh key, proven by a succession statement, that replaced the old one; the commit re-wrapped every scope-level
  value's own key under it and re-sealed every scope-root entry, rewriting no value
  ([keys, grants and trust § The scope-level re-seal](../../../crosscutting/keys-grants-and-trust.md#the-scope-level-re-seal))
  and marked every node of the scope **due**. The device holds the key of epoch `n+1`.
- **Postconditions (success):** No row of the scope is under the old key. Every live and trashed node of the scope is
  re-keyed: a fresh random key, its own epoch one higher, its `scopeEpoch` `n+1`, no due mark, its entry under its
  parent's new key, and every field that was sealed under its old key re-sealed under the new
  one. No store object was written, and no scope-level value: a member's name, picture and join record, a
  verification, an item mark, a group's name and icon and a link's recovery copy are byte-identical, each opening
  through its key's new wrap. Each share on a re-keyed node opens the new key; each drop key is new. The rotation reads
  *secured*. The server saw rewritten wraps and ciphertext in rows it already held, and nodes' epochs and due marks
  moving.

## Main flow

1. **List the scope.** The device lists every node of the scope, trashed descendants included, with each node's
   epoch and due mark, and the media, attachments, buckets, shares and guest identities hanging off each node.
2. **Re-key top-down.** For each due node, parents before children, it mints a fresh key and
   commits the node in one transaction: its own entry under its parent's current key; each child's entry (the
   child's current key re-wrapped, its name re-sealed, its slug token recomputed); the node's meta; its media's meta
   and EXIF; every `K_blob` wrap; its buckets' and its contributors' names; its drop key, replaced; every share
   rooted on it and open, re-wrapped under that share's `K_share` from its recovery copy. A large node's fields may go up in
   parts before the commit.
3. **A member's write pulls its path; a guest waits per node.** Until a node is committed, every write sealed under its
   key is `409 node_rekey_pending`. A member's client then re-keys that node and its lagging ancestors (step 2 on that
   path only) and retries at once: its write is delayed by its path's re-key, not refused. A guest's client — it holds
   no key above its root — waits and retries. A write stating the node's key from before is `409 stale_epoch`, and the
   writer re-reads once. A node already committed takes writes at once.
4. **Finish.** When no node of the scope is due, the rotation is *secured*; a queued job may now complete.

## Alternate / exception flows

- **E1 — the device stops halfway.** Every committed node opens from its parent's current key, so the tree reads as
  it stands. The rotation reads *re-keying* on every member's scope row. Any member's worker that holds `E_{n+1}`
  resumes at the first node still due (every 45 s while unlocked, and when the tab becomes visible); for the
  personal scope, the owner's own device on its next unlock. Two devices re-keying the same node leave one commit: the
  second is `409 stale_epoch` and re-lists.
- **E2 — a writer races the commit.** Both take the node's row lock. The writer that loses sees `node_rekey_pending`
  or `stale_epoch` and retries against the new key; nothing lands under the old one, nothing is orphaned.
- **E3 — a guest's open page.** Its keys are from before; its next write is `stale_epoch`, and the page re-resolves
  the link, whose wrap now opens the new key.
- **E4 — a contributor or uploader identity from before.** Its own `K_up` stays, re-sealed to the new drop key. The
  excluded party could open it before the rotation, so its later uploads stay in their reach until the link is
  re-issued, which starts every guest over ([ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)
  point 2).
- **E5 — a link from before.** An open one keeps working and shows what is added, and stays marked `reissueRequired`:
  the excluded party can rebuild its `K_share`. Its re-issue or switch-off closes it, and a closed link gets no new key
  ([uc.sharing.close-link-rekeys-subtree](../../sharing/use-cases/uc.sharing.close-link-rekeys-subtree.md)).
- **E6 — a node a moderation hold copied.** The hold is a snapshot of the stored ciphertext and stays as it is; the
  report's keys keep opening what was reported.
- **E7 — a drop box.** A drop box (Depot's: its guests get identity folders), a guest's identity folder and what lies
  below one are an **owner's** to re-key ([ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decision 11): the listing marks them, a member's worker skips them (the scope then stays *re-keying* until an
  owner's device runs), a member's operation on one is `403 not_scope_owner`, and a member's write into one waits
  like a guest's. A Memory or chapter with an uploader link carries a drop key too, but members write into it: any
  member re-keys it, its drop key included (ADR 0111 decision 11).
- **E8 — a member's write that carries ciphertext under the old key.** A cover and a banner upload a blob wrapped under
  the node's key, and a medium's meta edit starts from a tier sealed under it. Each pulls its path like any member's write
  and is then rebuilt, not resent: a cover or a banner runs again from scratch under the re-read key, the upload included
  (a kept one's wraps read from the meta the re-key re-sealed); a meta edit re-applies its change to the tier's plaintext
  as listed and re-bases on the re-sealed tier through `409 stale_revision`. An import or a copy pulls the target it
  creates under. Into a drop box each waits for an owner (E7).
- **E9 — a rotation while an import or a copy is under way.** What the run already created is at the epoch the run
  started with, so its next write meets the gate: a create below one of its nodes, a Memory's next medium, its cover
  or banner, a Pot's next batch or receipt, a Depot file's content. Each is **one write** that pulls that node forward
  on this device and is sealed again under the re-read key; the run goes on under the key and epoch it left, and never
  repeats a kind's whole import, which would create the node twice. A download's medium seals its corrected capture time
  and place name in the upload that adds it, so no write after the last medium opens a tier the re-key re-sealed.
  Another member's device may re-key the node first: the write then meets `409 stale_epoch`, and the pull runs before
  the re-read anyway, since the device holds the scope
  at the old epoch and opens the new key only once it re-opened it. A create at the scope root, which states no parent
  epoch, re-opens the scope the same way. Below a drop box the write waits for an owner (E7); the refused pull still
  re-opens the scope, so the re-read after the wait opens the owner's re-key. Only when the waits run out is the run
  rolled back.
- **E10 — the scope-level commit is refused.** A wrap or a root entry without a staged replacement is `409
  rekey_parts_missing`, one written since the rotator read it `409 rekey_parts_stale` (each staged wrap names the digest
  of the wrap it replaces, and every write of a value mints a fresh one): nothing commits, and the rotator stages
  again, a bounded number of times. A stage that writes a value's ciphertext rather than its key's wrap is `422
  rekey_writes_value`, for an owner and a member rotator alike: a rotation never rewrites a value. A scope-level write
  stating the old epoch after the commit is `409 stale_epoch`. Only a caller who may commit the rotation and holds the
  current grant reads and stages the scope-level wraps.
- **E11 — a wrap the rotator cannot open.** It is staged as a stand-in — a key nobody holds, wrapped at the same slot,
  of the same length — so the value reads as unreadable, exactly as before; a recovery copy so lost closes its link,
  key lost, at its root's re-key. A pending group invitee's name key is the name's own key, sealed to the invitee: the
  rotation leaves it. The rotation commits.
- **E12 — a member behind.** A member the rotation declared pending reads nothing of the scope — it reads as *awaiting
  the current key* — until a grant reaches them; a grant whose succession statements do not lead from the key their
  device held is refused (`epoch_unlinked`).

## Quality notes

- **Nothing in the store moves.** Each `K_blob` keeps encrypting its chunks; only its wrap changes. A Chapter of 1,000
  photos is about 5,000 symmetric seals, about 20 requests.
- **What stays in the excluded party's reach:** a dump from before the rotation, the bytes of a `K_blob` they
  extracted and the value of a scope-level `K_v` they extracted until its author writes it again (a re-key re-wraps,
  it re-encrypts nothing), and open links until closed or re-issued (E4, E5).
- **A rotator can junk a value, never forge one.** It stages wraps only; one it substitutes opens a key the value is
  not sealed under, so the value reads as unreadable — an insider's denial of service, visible — never as another.
- **Operator-blind.** No key reaches the server; it learns nothing it did not see from the epoch change.
- **Honest-operator for the server's refusals, layer 2 for the floor.** The `409`s are layer 1; a client also seals
  nothing under a node key bound to an epoch below the highest it has held for the scope
  ([ADR 0024](../../../decisions/0024-scope-key-rotation.md) Decision 6).

## User impact

After a space gets new keys, whoever was shut out cannot read anything added to it, wherever in the space it goes.
A member keeps adding to the space right away: their own device fits the new lock on what they add to, and every
member's device finishes the rest in the background.

## Related

- Model: [keys, grants and trust](../../../crosscutting/keys-grants-and-trust.md) (rotation and the node re-key)
- Feature: [Spaces (scopes)](../_index.md)
- Enabler: [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) (the epoch it follows)
- Triggers: [uc.spaces.complete-queued-rotation](uc.spaces.complete-queued-rotation.md),
  [uc.account.rotate-keys](../../account/use-cases/uc.account.rotate-keys.md),
  [uc.spaces.manage-group-members](uc.spaces.manage-group-members.md) (a removal)
- Decisions: [ADR 0115](../../../decisions/0115-a-scope-key-is-replaced-never-chained.md),
  [ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md),
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md), [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md),
  [ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)
- End-user view (docs site): [Secure your account](../../../../site/src/content/docs/account/replace-your-keys.mdx),
  [Invite and manage members](../../../../site/src/content/docs/space-owners/who-gets-into-your-space.mdx)
