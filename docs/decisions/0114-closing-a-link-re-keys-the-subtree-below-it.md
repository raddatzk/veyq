# ADR 0114 — Closing a link re-keys the subtree below it

- Status: Accepted
- Built: yes
- Date: 2026-11-02
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)

## Context

A `READ` or `READ_WRITE` link carries its own `K_share` in the URL fragment, and the root node's key is wrapped under
it ([ADR 0029](0029-public-viewer-share.md)); from the root's key a holder opens every key below it. A link is a bearer
secret: it spreads beyond anyone's control, further than a member's grant does. Closing it was layer 1 only — the
honest server stops serving — and nothing was re-keyed ([ADR 0029](0029-public-viewer-share.md) § 7,
[ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 4). So a former holder with
a database dump — a stolen dump, or an operator turned malicious, which is inside the threat model
([security-model](../crosscutting/security-model.md)) — read everything added under the root after the close. The
owner decided that **revoking a link re-keys its root's subtree, automatically, with no option in the dialog**.

This record builds on [ADR 0115](0115-a-scope-key-is-replaced-never-chained.md): every key — a scope's and a node's —
is replaced, never chained, and counts its replacements as its epoch. A close is a replacement of the keys of one
subtree, as a rotation is of the scope's and then of every node's.

Before this record, closing a link wrote one column (`enabled = false`) and re-keyed nothing; a node's epoch was the
scope's at its mint, so a re-key that left the scope's epoch where it was would have let a writer holding the old key
state the same epoch and pass (`NodeEpochGate`); a rotation re-wrapped the new key under every link not deleted, a
switched-off one included, "since a revoked one may be re-activated"; and a rename re-sealed the root's name under a
switched-off link too. So even a rotation handed a revoked link's holder, with a dump, what was added after it. The
per-node re-key existed ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 2): one commit per
node, top-down, every field under the old key re-sealed, every `K_blob` re-wrapped and never re-encrypted, the blob
service's wraps paged (`listApplicationBlobRekey`, [ADR 0113](0113-memories-media-are-blobs-of-cores-blob-service.md)),
run and resumed by any member holding the current grant
([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decision 11); it had no trigger smaller than a rotation. The `content-key` seal binds the node's placement revision,
which every re-key commit bumps, so a new key under an unchanged scope key needs no new seal binding.

## Decision

1. **Every close of a viewable link re-keys its root's subtree.** A link is **open** while it is enabled, not deleted
   and its end date has not passed, and **closed** otherwise. Each transition from open to closed re-keys every node of
   the root's subtree, live and trashed, the root included, when the link is `READ` or `READ_WRITE`:
   - a **revoke** by its owner, and a **bulk switch-off** of the scope's links (`revokeScopeShares`, a removal's
     `revokeLinks`);
   - the **switch-off of a removed member's links** by a group or tenant removal or an account's erasure;
   - a **delete** (the tombstone) of an open link, and a **re-issue** ("Link erneuern"): the old `K_share` is closed
     like any other, the new link follows the new key (point 5);
   - its **end date passing** (point 6);
   - its **key lost**: no member opens its recovery copy any more — a copy a rotator could not re-seal is staged as one
     nobody opens ([ADR 0115](0115-a-scope-key-is-replaced-never-chained.md) decision 2) — so no re-key can wrap its
     root's new key for it, and the root's node re-key that names it closes it, switched off, in its own commit.

   Nothing else closes a link. A **pause** — the root trashed, the role's capability or a link requirement switched
   off, a moderation block, a suspended or deleting scope — stops serving at layer 1 and ends by itself; the link's
   holders are meant to read again, so it re-keys nothing and the re-key keeps wrapping the root's key under it. A
   **password change**, a role change between `READ` and `READ_WRITE`, a note or label edit leave the link open; the
   password is an access gate, never a key ([ADR 0037](0037-password-protected-shares.md)), so a re-key would re-wrap
   the new key under the same `K_share` and buy nothing. A link tombstoned by its root's **purge** re-keys nothing:
   nothing remains below it.

2. **A `WRITE_ONLY` link's close re-keys nothing, and replaces no drop key.** Its `K_share` opens only the root's drop
   *public* key and the guest's own `K_up` ([ADR 0031](0031-uploader-public-share.md) point 7); the drop private key is
   under the node's key, which the guest never held. With a dump it reads what it uploaded itself and nothing else,
   before the close and after. Replacing the drop keypair would cut nothing it holds. Like every close it clears the
   link's sealed drop public key (point 5), which a reopen brings back.

3. **A node's epoch is its own key's.** It is `0` when a key is minted (a create, a copy, an import) and goes up by one
   with every replacement of that key — after a rotation or after a close
   ([ADR 0115](0115-a-scope-key-is-replaced-never-chained.md) decision 4). Every write that seals under a node's key
   states it (`nodeEpoch`, `parentEpoch`, a guest's `rootEpoch`), and the gate (`NodeEpochGate`) decides under the
   node's row lock:
   - stated epoch ≠ stored → **`409 stale_epoch`**: the writer holds a key that was replaced; it re-reads;
   - the node **lags** → **`409 node_rekey_pending`**: a re-key has not reached it; a member's write pulls its path
     forward, a guest waits ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 11).

   The scope's epoch when the key was minted is the node's `scopeEpoch`: what the `content-key` and `node-name` seals
   bind and the device floor reads.

4. **One lag, one mark, one commit.** A node **lags** exactly when it is marked **due**. Two things mark: a rotation's
   commit marks every node of the scope ([ADR 0115](0115-a-scope-key-is-replaced-never-chained.md) decision 2), a
   close marks every node of its root's subtree, each in its own transaction, from the parent ids the server holds. The
   mark is the node's, so it stays with a node moved meanwhile (a move states the node's epoch and waits while it lags
   anyway). The commit of [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 2 is the one commit:
   its precondition is that the node is due and its parent is not; it raises the node's epoch, sets its `scopeEpoch`
   to the scope's, and clears the mark. A scope is *re-keying* while any node is due (`ScopeRekeying`), so every
   member's worker that resumes a rotation's re-key resumes a close's.

5. **A re-key seals nothing new under a closed link.** It re-wraps the root's new key, and re-seals the drop public
   key, under the **open** links rooted on the node only — after a rotation too. A close clears the link's `wrap` and
   drop public key (a tombstone clears them already); a rename re-seals the root's name under open links only
   ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 14), and the server
   refuses a re-seal of a closed one's (`422 link_closed`, decided under the link's row lock). What the curator
   writes into a closed link — its note, label or password — is their own words to that link's holders and stays
   allowed. The **guest identities** of every link rooted on the node are re-sealed whatever the link's state: a
   contributor's name is the node's field, not the link's.
   Other links on the subtree keep working: one rooted on the root or below gets its root's new key under its own
   `K_share` at that root's commit; one rooted above opens the new key through its chain of node keys.

6. **An end date closes the link through a sweep.** No client is there when it passes, so a scheduled server task
   (`ShareExpiryCloseSweep`, every `veyq.share.expiry-close.every`, default `1m`, never twice at once) closes every
   enabled link whose end date has passed and whose close it has not carried out, one transaction per scope: it marks
   the subtree due, exactly as point 4, and records on the link that it did (`expiry_closed`). Then any member's worker
   re-keys. The server needs no key for it. An edit that sets an end date already passed closes the link at once,
   through the same body. **Exposed meanwhile:** a write landing between the end date and the sweep's next run (one
   interval) is sealed under the old key; after the sweep nothing is, since due nodes take no
   write. Until a member's device has re-keyed, writers into the subtree wait — a guest of another link there too.

7. **Reopening carries the key; it is the curator's act, and it stays** (owner, 2026-11-02). A **re-activation**, and
   an edit that moves a passed end date into the future or clears it, carry what the re-key left out: the root's
   current key wrapped under the link's `K_share` (or, for `WRITE_ONLY`, the drop public key sealed under it) and the
   root's name sealed under it, stating `nodeEpoch`. The member opens `K_share` from its recovery copy
   ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4); a link with no recovery copy is reopened
   only by a re-issue. A reopen without them is refused, a stale or lagging root answers point 3's refusals, and
   [ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4's refusals stay. A reopen hands the old
   `K_share` everything added while the link was closed — the very link turned back on; the close's re-key was its
   price.

8. **Close first, then re-key; the close is atomic, the re-key is not.** The close and every due mark land in one
   transaction (the scope row, then the share rows, then the node rows — every writer's lock order). A dump taken
   between it and the re-key shows the subtree as it stood at the close and nothing added since, because every node is
   due. The re-key then runs node by node as a rotation's does, readable as it stands after every commit. A key a
   re-keyer **staged** before the close must not land: its staged wrap under the closed link would sit in the stage
   table until the commit, and a dump would hand the closed link the new key. The commit refuses staged values for a
   link that is no longer open (`409 rekey_parts_stale`), and the re-keyer mints afresh.

9. **The closing device runs the re-key; any member finishes it.** Right after the server answers a close, the device
   that closed starts the subtree's re-key in the background (the path re-key with its subtree,
   `rekeyPath(…, { subtree: true })`); the dialog does not wait. A crash, a device whose grant is behind, a member who
   may not re-key an owner-only node (a drop box, a guest's identity folder,
   [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 11), a scope being
   deleted, and an end date leave the marks for any member's worker (owner-only nodes for an owner's). A group
   removal's rotation marks every node anyway; its close's marks add nothing. **What the screen says** is the
   re-keying state a rotation shows, worded for the subtree, not the space (`share.closedRekeying`, the owner's words):
   *de* „Der Link ist abgeschaltet. Was darunter liegt, bekommt ein neues Schloss, damit der alte Link auch künftig
   nichts mehr öffnet.“ *en* "The link is switched off. What is below it gets a new lock, so the old link opens
   nothing that comes later either."

10. **What the server stores and learns.** Per node a due flag (`rekey_due`) and its own epoch, per link the sweep's note (`expiry_closed`);
    rewritten ciphertext in rows it holds. It sees that a link closed and which nodes lie below it — the close and the
    tree it serves anyway. No key, no new correlation. Moderation hold copies stay snapshots
    ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 8).

**Cost.** [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)'s per node and per medium, bounded by the
subtree: only wraps and field seals, all in the database, nothing in the store. A Memory of 10,000 photos is about
30,000 blob wraps and 20,000 field seals, staged in pages of 256 (measured: `grep -n "STAGE_BATCH = "
src/web/src/core/node/rekey/rekeyScope.ts`), so about 200 stage requests beside the listing pages. A link on a folder
pays that for every node and medium below it; a bulk switch-off of a scope's links pays up to the node half of a
rotation, without its scope-level re-seal and its re-grant. Writes into a node wait only for that node's commit.

**Wire.** Contracts first, generated code only:
- the stated node epochs keep their names and carry the node's own epoch; every node row a member, a guest or the
  re-key lists carries `scopeEpoch` beside it; the re-key listing's row carries `due`; a field carrying the scope epoch
  a key is minted under (a create's `epoch`, the commit's `epoch`) is renamed `scopeEpoch`;
- `reactivateShare` has a body and `updateShare` the same fields for a reopen (point 7);
- the revoke, delete, re-issue and bulk operations keep their shapes: the server marks;
- **no seal changes and no conformance vector of its own**: the renamed binding field keeps its position, and a new
  key under an unchanged scope key is told apart by the placement revision the `content-key` seal already binds.

## Consequences

- **A closed link reads nothing added after its close, with a dump**, on an honest server: what existed at the close
  stays readable to it ([ADR 0012](0012-content-key-hierarchy.md)), as after a rotation. A rotation re-wraps no closed link
  either.
- **What stays in reach**, stated where the residuals are listed:
  - the sweep interval after an end date (point 6);
  - existing `WRITE_ONLY` guest identities on a re-keyed node with a drop key: a former holder of the viewable link
    opened their `K_up` through the old drop private key, so it reads their later uploads until the uploader link is
    re-issued — [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 5's residual, after a close
    too;
  - a removed tenant member's links: the close cuts whoever else holds them; the member keeps the tenant key until an
    explicit re-key ([ADR 0025](0025-forward-secrecy-policy-per-scope.md) § 2), which this decision leaves as it is;
  - **an active operator that rolls a member's view of the subtree back** to before the re-key: that member's device
    seals under the old key again. The device floor watches the scope's epoch (`scopeEpoch`), which a close does not
    move. A per-device mark of the highest epoch opened per node would be authentic (the placement revision is bound
    in the seal) but protects only devices that saw the re-key, and a fresh device trusts what it is served; it is not
    built, and [security-model § What holds today](../crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve)
    has the row.
- **A revoke is not free.** It costs the subtree's re-key on the revoking device, and writes into the subtree
  wait per node until it is done — for a guest of another link until a member's device has run it. A revoke followed by
  a re-activation pays a re-key for nothing it keeps.
- **The links closed before this record are re-keyed after it, on the safe side.** The upgrade cleared the keys of the
  links switched off before it and, in a migration of its own (`V202611021647__core_closed_links_mark_due.sql`), marks
  due every node of every scope holding such a READ or READ_WRITE link, or a deleted one whose root still exists — more
  than their subtrees, a one-time cost; a link whose end date had passed is closed and marked by the sweep's first run.
- **A node's `epoch` is its own key's** on every reader and writer, the scope's at its mint is `scopeEpoch`; the wire
  names a writer states are the same.

## Alternatives rejected

- **Rotate the scope on every close.** One trigger fewer, but `O(members)` re-grants
  ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)'s cost at a tenant's size), the scope-level re-seal of
  [ADR 0115](0115-a-scope-key-is-replaced-never-chained.md) and a re-key of every node of the scope, for a link on one
  Memory.
- **A counter of its own beside the node's epoch** (a "key revision"). Two numbers for one key; with every key
  replaced, the node's epoch is that counter ([ADR 0115](0115-a-scope-key-is-replaced-never-chained.md) decision 4).
- **Lag as "epoch below the scope's or due".** Two predicates; the rotation's commit marks every node instead.
- **Derive "due" at write time from the closed links above a node.** No column, but an ancestor walk and a link lookup
  on every write and in every scope's re-keying status.
- **Re-key an expired link's subtree when the next member comes, without marks.** No sweep, but writes keep landing
  under the old key for as long as no member comes.
- **Re-key first, then close; or both in one request.** The link would still be open while its subtree re-keys and get
  the new key; one request has no bound on a large subtree.
- **A closed link only comes back by re-issue.** Simpler — no reopen payload — but it takes away re-activation, which
  the owner keeps.
- **A key chain per node, or a lazy re-key.** Rejected in [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
  for the same reasons, and against [ADR 0115](0115-a-scope-key-is-replaced-never-chained.md)'s one model.

## Bite tests and guards

The cases that must fail if a rule here is wrong are the acceptance criteria of a use case of their own,
[uc.sharing.close-link-rekeys-subtree](../features/sharing/use-cases/uc.sharing.close-link-rekeys-subtree.md) — the
property has several triggers, so not under one operation's use case — each with a positive control on the same
predicate (an open sibling link whose wrap opens the new key). The phase-1 probes (`LinkCloseRekeyProbeCoreSliceTest`)
are among them.

- `validate_link_close_arity.py`: every write that closes a link — `enabled = false`, `deleted = true`, row or bulk —
  sits in the one store body that marks the subtree (`SharingStore.close`); its bites are `validate_guard_gaps.py`
  cases 100 (a revoke written as its own assignment beside the body) and 101 (a removal's switch-off spelled as a bulk
  update).
- `ShareSealedNameCoreSliceTest` refuses a rename's re-seal of a switched-off link and of one past its end date, each
  beside an open control that takes it (point 5); `ClosedLinksMarkDueMigrationSliceTest` proves the upgrade's marks.
- `validate_write_concurrency.py` keeps its node-epoch declarations, the epoch read as the node key's own.
- `validate_node_rekey_arity.py` proves the assignments exist, not which rows they reach — point 5's selection is the
  slice tests'.
