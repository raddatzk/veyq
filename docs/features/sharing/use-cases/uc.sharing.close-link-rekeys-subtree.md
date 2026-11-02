# UC: A closed link's subtree gets new keys

- **ID:** uc.sharing.close-link-rekeys-subtree
- **Primary actor:** **The member whose act closes a viewable link** — its owner switching it off, deleting it or
  setting its end date into the past; any member re-issuing it; an owner switching off every link of the scope; the
  remover whose removal switches off the removed member's links — and, for an end date that passes, the **server's
  sweep**. Then, in the background, **any member holding the current grant**, through their worker
  ([ADR 0111](../../../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 11); a drop box and a guest's identity folder are an **owner's**.
- **Secondary actor:** Every other **writer** below the link's root — a member's write re-keys its path first, a guest's
  (of another link there) waits — and the **holder of the closed link**, who must read nothing added after the close.
- **Goal:** Once a `READ` or `READ_WRITE` link closes, nothing added below its root seals under a key the link's
  holder has, even for a holder with a database dump
  ([ADR 0114](../../../decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md)).
- **Preconditions:** The link is open — enabled, not deleted, its end date not passed — and its role is `READ` or
  `READ_WRITE`. A `WRITE_ONLY` link's close re-keys nothing (E1).
- **Postconditions (success):** Every live and trashed node of the root's subtree, the root included, has a fresh key
  and its own epoch one higher; the closed link carries no wrap of any of them, and no other link lost its key. Open
  links on the subtree open the new keys. The server saw a close, due marks on nodes it serves anyway, and rewritten
  ciphertext in rows it holds.

## Main flow

1. **Close.** The close — a switch-off, a delete of an open link, a re-issue's old link, a bulk or a removal's
   switch-off, or an end date carried out — runs through the one store body (`SharingStore.close`): under the scope's
   row lock, then the link's, it clears the link's `wrap` and sealed drop public key and marks every node of the root's
   subtree **due**, in one transaction.
2. **Writes wait.** From that commit on, a write into a due node is `409 node_rekey_pending`: a member's write re-keys
   its path and retries, a guest's waits.
3. **Re-key.** Right after the server answers, the closing device starts the subtree's re-key in the background
   (`rekeyPath(…, { subtree: true })`); the dialog does not wait, and the link's row reads *re-keying* meanwhile. Each
   node is one commit, top-down, as after a rotation
   ([uc.spaces.rekey-after-rotation](../../spaces/use-cases/uc.spaces.rekey-after-rotation.md)): a fresh key, every
   field under the old one re-sealed, every `K_blob` re-wrapped, the drop key replaced; each **open** link rooted on
   the node gets the new key under its own `K_share`, the closed one nothing; every link's guest identities are
   re-sealed whatever its state.
4. **Finish.** Once no node is due the scope reads as before. A writer stating a node's epoch from before the close is
   `409 stale_epoch` and re-reads.

## Alternate / exception flows

- **E1 — a `WRITE_ONLY` link.** Its close clears its sealed drop public key and marks nothing: its `K_share` opens only
  the drop public key and its guest's own `K_up`.
- **E2 — an end date.** No client is there when it passes. The sweep (`ShareExpiryCloseSweep`, every
  `veyq.share.expiry-close.every`, default `1m`) closes every enabled link whose end date passed and whose close it has
  not carried out, marks the subtree as step 1 and sets `expiry_closed`, once per link; any member's worker re-keys. An
  edit that sets an end date already passed closes the link at once. A write landing between the end date and the
  sweep is under the old key.
- **E3 — a pause.** The root trashed, the role's capability or a link requirement switched off, a moderation block, a
  suspended or deleting scope: the link stops serving and re-keys nothing; it keeps its key, since its holders are meant
  to read again. A password, role (`READ` ↔ `READ_WRITE`), note or label edit leaves the link open.
- **E4 — a key staged before the close.** A re-keyer that staged the root's new key under the link before the close
  meets `409 rekey_parts_stale` at its commit and mints afresh; the key never lands under the closed link.
- **E5 — the closing device stops, or may not re-key.** The marks stay; any member's worker resumes them, an owner's
  for owner-only nodes. A crash leaves the subtree readable as it stands after every commit.
- **E6 — reopen.** A re-activation, or an edit that moves a passed end date into the future or clears it, carries the
  root's current key (for `WRITE_ONLY` the drop public key) and name under the link's `K_share`, opened from its
  recovery copy and stating the root's epoch. Without them it is `422 link_key_required`; a stale or due root answers
  `stale_epoch` / `node_rekey_pending`, and a member's write pulls the root forward first. The reopened link reads
  everything added while it was closed.
- **E7 — a rotation after the close.** It re-wraps no closed link either; a closed link comes back only by E6 or a
  re-issue.

## Quality notes

- **What stays in the link holder's reach:** what existed at the close; the sweep interval (E2); `WRITE_ONLY` guests'
  `K_up` on a re-keyed node with a drop key, until the uploader link is re-issued; a removed tenant member's tenant key
  until an explicit re-key; an active operator rolling a member's view of the subtree back. Stated where the residuals
  are, [security-model § Revocation](../../../crosscutting/security-model.md#revocation--two-layers).
- **Operator-blind.** The server marks nodes from the parent ids it holds and needs no key; every key is minted and
  every seal made on a member's device.
- **Cost.** The subtree's re-key on the closing device: for a Memory of 10,000 photos about 30,000 wraps and 20,000
  field seals in pages of 256, nothing in the store.

## User impact

Switching a link off, renewing it, deleting it or letting it run out gives what is below it a new lock: the old link
opens nothing that is added later, even for someone who kept a copy of the server's data.

## Related

- Model: [en.sharing.public-link § Lifecycle](../enablers/en.sharing.public-link.md#lifecycle) (closes and pauses),
  [keys, grants and trust § Re-keying the nodes](../../../crosscutting/keys-grants-and-trust.md#re-keying-the-nodes)
- Feature: [Sharing](../_index.md)
- Triggers: [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md),
  [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-shared-scope](uc.sharing.share-shared-scope.md) (removals, bulk switch-off, re-issue)
- Decisions: [ADR 0114](../../../decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md),
  [ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md),
  [ADR 0075](../../../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)
- End-user view (docs site): [Change, switch off, renew and delete links](../../../../site/src/content/docs/sharing/manage-links.mdx)
