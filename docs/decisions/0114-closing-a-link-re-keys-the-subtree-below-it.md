# ADR 0114 — Closing a link re-keys the subtree below it

- Status: Proposed
- Built: no
- Date: 2026-11-02
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)

## Context

A `READ` or `READ_WRITE` link carries its own `K_share` in the URL fragment, and the root node's key is wrapped under
it ([ADR 0029](0029-public-viewer-share.md)); from the root's key a holder opens every key below it. A link is a bearer
secret: it spreads beyond anyone's control, further than a member's grant does. Closing it today is layer 1 only — the
honest server stops serving — and nothing is re-keyed ([ADR 0029](0029-public-viewer-share.md) § 7,
[ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 4). So a former holder with
a database dump — a stolen dump, or an operator turned malicious, which is inside the threat model
([security-model](../crosscutting/security-model.md)) — reads everything added under the root after the close. The
owner decided that **revoking a link re-keys its root's subtree, automatically, with no option in the dialog**.

What the code does today:

- **A revoke writes one column.** `SharingStore.revoke` sets `enabled = false` and nothing else (measured:
  `sed -n 314,321p src/backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingStore.kt`). In a dev browser
  pass, revoking a link left `veyq_node.epoch` and every blob wrap of the subtree unchanged (remembered: the
  coordinating session measured it on the dev database).
- **A node's key is tied to its scope's epoch.** The write gate compares the stated node epoch with the stored one and
  the stored one with the scope's (`NodeEpochGate.verdict`, measured: `grep -n "fun verdict" -A 5
  src/backend/core/src/main/kotlin/me/raddatz/veyq/core/node/rekey/NodeEpochGate.kt`). A re-key that leaves the
  scope's epoch where it is would leave a writer holding the old key stating the same epoch, and passing.
- **A rotation re-wraps a switched-off link.** The re-key re-wraps the new key under every link not deleted, a
  revoked one included, "since a revoked one may be re-activated" (measured: `grep -n "deleted = false and (wrap is not
  null" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/node/rekey/RekeyStore.kt`). So today even a rotation
  hands a revoked link's holder, with a dump, what is added after it.
- **A rename re-seals the root's name under a revoked link too** (measured: `grep -n "A revoked share is renamed too"
  src/backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingHandler.kt`).
- **The per-node re-key exists** ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 2): one
  commit per node, top-down, every field under the old key re-sealed, every `K_blob` re-wrapped and never re-encrypted,
  the blob service's wraps paged (`listApplicationBlobRekey`, [ADR 0113](0113-memories-media-are-blobs-of-cores-blob-service.md)),
  any member holding the current grant runs it and resumes it
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 11). It only has no trigger smaller than a rotation.
- **The content-key seal binds the node's placement revision**, and every re-key commit bumps it (measured:
  `grep -n "placementRevision" src/web/src/core/node/tree.ts | head -3` and `grep -n '"placement" to
  c.placementRevision + 1' src/backend/core/src/main/kotlin/me/raddatz/veyq/core/node/rekey/RekeyStore.kt`). A new key
  at the same epoch therefore needs no new seal binding.

## Decision

1. **Every close of a viewable link re-keys its root's subtree.** A link is **open** while it is enabled, not deleted
   and its end date has not passed, and **closed** otherwise. Each transition from open to closed re-keys every node of
   the root's subtree, live and trashed, the root included, when the link is `READ` or `READ_WRITE`:
   - a **revoke** by its owner, and a **bulk switch-off** of the scope's links (`revokeScopeShares`, a removal's
     `revokeLinks`);
   - the **switch-off of a removed member's links** by a group or tenant removal or an account's erasure;
   - a **delete** (the tombstone) of an open link, and a **re-issue** ("Link erneuern"): the old `K_share` is closed
     like any other, the new link follows the new key (point 4);
   - its **end date passing** (point 6).

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
   before the close and after. Replacing the drop keypair would cut nothing it holds.

3. **A node's key has a revision of its own; writers state it instead of the node's epoch.** `keyRevision` is `0` when
   a key is minted (a create, a copy, an import) and goes up by one with every re-key commit of the node — a
   rotation's or a close's. The node's `epoch` stays what [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
   point 6 made it: the epoch its key was minted at, bound in the `content-key` and `node-name` seals, the key of the
   scope-root entry's `K_SC` and the input of the device floor. Every write that seals under a node's key states the
   node's **key revision** as it read it (`nodeKeyRevision`, `parentKeyRevision`, a guest's `rootKeyRevision`) where it
   states the node's epoch today, and the gate decides under the node's row lock, as now:
   - stated revision ≠ stored → **`409 stale_node_key`**: the writer holds a key from before; it re-reads;
   - the node **lags** → **`409 node_rekey_pending`**: a re-key has not reached it; a member's write pulls its path
     forward, a guest waits ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 11).

   A revision suffices alone: every re-key moves it, so a writer stating the stored one read the row after the last
   re-key, at the node's current epoch. `stale_epoch` stays for statements of a **scope** epoch — a create at the scope
   root, a recovery copy, a rotation. The word is ADR 0086's: a change counter is a revision; an epoch is a `K_SC`
   generation.

4. **One lag, one commit, two triggers.** A node **lags** when its epoch is below the scope's current one (a rotation's
   re-key has not reached it) **or it is marked due** (a close's has not). The **due mark** is set by the close, in the
   close's own transaction, on every node of the root's subtree, read from the parent ids the server holds; it is the
   node's, so it stays with a node moved meanwhile (a move states the node's revision and waits while it lags anyway).
   The commit of [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 2 stays the one commit: its
   precondition is that the node lags and its parent does not; it sets the node's epoch to the scope's, raises its
   revision and clears its mark. A scope is *re-keying* while any node lags (`ScopeRekeying`), so every member's
   worker that resumes a rotation's re-key resumes a close's. A rotation is a re-key of every node; a close a re-key
   of one subtree.

5. **A re-key seals nothing new under a closed link.** It re-wraps the root's new key, and re-seals the drop public
   key, under the **open** links rooted on the node only — on a rotation too. A close clears the link's `wrap` and drop
   public key (a tombstone clears them already); a rename re-seals the root's name under open links only
   ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 14). What the curator
   writes into a closed link — its note, label or password — is their own words to that link's holders and stays
   allowed. The **guest identities** of every link rooted on the node are re-sealed whatever the link's state: a
   contributor's name is the node's field, not the link's.
   Other links on the subtree keep working: one rooted on the root or below gets its root's new key under its own
   `K_share` at that root's commit; one rooted above opens the new key through its chain.

6. **An end date closes the link through a sweep.** No client is there when it passes, so a scheduled server task
   closes every enabled link whose end date has passed and whose close it has not carried out: it marks the subtree
   due, exactly as point 4, and records on the link that it did. Then any member's worker re-keys. The server needs no
   key for it. **Exposed meanwhile:** a write landing between the end date and the sweep's next run (one interval,
   default one minute, configurable) is sealed under the old key; after the sweep nothing is, since due nodes take no
   write. Until a member's device has re-keyed, writers into the subtree wait — a guest of another link there too.

7. **Reopening carries the key; it is the curator's act.** A **re-activation**, and an edit that moves a passed end
   date into the future or clears it, carry what the re-key left out: the root key at its current revision wrapped
   under the link's `K_share` (or, for `WRITE_ONLY`, the drop public key sealed under it) and the root's name sealed
   under it, stating `nodeKeyRevision`. The member opens `K_share` from its recovery copy
   ([ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4); a link with no recovery copy is reopened
   only by a re-issue. A reopen without them is refused, a stale or lagging root answers point 3's refusals, and
   [ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4's refusals stay. A reopen hands the old
   `K_share` everything added while the link was closed — the very link turned back on; the close's re-key was its
   price.

8. **Close first, then re-key; the close is atomic, the re-key is not.** The close and every due mark land in one
   transaction (the scope row, then the share rows, then the node rows — every writer's lock order). A dump taken
   between it and the re-key shows the subtree as it stood at the close and nothing added since, because every node is
   due. The re-key then runs node by node as a rotation's does, readable as it stands after every commit. A key a
   re-keyer **staged** before the close must not land: its staged wrap under the now-closed link would sit in the stage
   table until the commit, and a dump would hand the closed link the new key. The commit refuses staged values for a
   link that is no longer open (`409 rekey_parts_stale`), and the re-keyer mints afresh.

9. **The closing device runs the re-key; any member finishes it.** Right after the server answers a close, the device
   that closed starts the subtree's re-key in the background (the path re-key with its subtree,
   `rekeyPath(…, { subtree: true })`); the dialog does not wait. A crash, a device whose grant is behind, a member who
   may not re-key an owner-only node (a drop box, a guest's identity folder,
   [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 11), a scope being
   deleted, and an end date leave the marks for any member's worker (owner-only nodes for an owner's). A group
   removal's rotation already lags every node; its marks add nothing. The space shows the *re-keying* state a rotation
   shows; there is no new screen.

10. **What the server stores and learns.** Per node a revision and a due flag, per link the sweep's note; rewritten
    ciphertext in rows it holds. It sees that a link closed and which nodes lie below it — the close and the tree it
    serves anyway. No key, no new correlation. Moderation hold copies stay snapshots
    ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 8).

**Cost.** [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)'s per node and per medium, bounded by the
subtree: only wraps and field seals, all in the database, nothing in the store. A Memory of 10,000 photos is about
30,000 blob wraps and 20,000 field seals, staged in pages of 256 (measured: `grep -n "STAGE_BATCH = "
src/web/src/core/node/rekey/rekeyScope.ts`), so about 200 stage requests beside the listing pages. A link on a folder
pays that for every node and medium below it; a bulk switch-off of a scope's links pays up to a rotation's re-key of
the scope, without its re-grant. Writes into a node wait only for that node's commit.

**Wire.** Contracts first, generated code only:
- every stated node epoch becomes the node's key revision — `nodeEpoch` → `nodeKeyRevision`, `parentEpoch` →
  `parentKeyRevision`, `rootEpoch` → `rootKeyRevision`, the stage's `fromEpoch` → `fromKeyRevision`; every node row a
  member, a guest or the re-key lists carries `keyRevision`; the re-key listing's row carries `due`; the gate's conflict
  is `stale_node_key`; the `x-veyq-concurrency` declarations name the revisions. A field carrying the epoch a key is
  minted at (a create's `epoch`, the commit's `epoch`) stays. The sites are found by `grep -rn
  "nodeEpoch\|parentEpoch\|rootEpoch\|fromEpoch\|stale_epoch" contracts src tests --include="*.yaml" --include="*.kt"
  --include="*.ts" --include="*.vue" | grep -v "/generated/\|/build/\|node_modules"`;
- `reactivateShare` gets a body and `updateShare` the same fields for a reopen (point 7);
- the revoke, delete, re-issue and bulk operations keep their shapes: the server marks;
- **no seal changes and no conformance vector**: a new key at the same epoch is told apart by the placement revision
  the `content-key` seal already binds.

## Consequences

- **A closed link reads nothing added after its close, with a dump**, on an honest server: what existed at the close
  stays readable to it ([ADR 0012](0012-content-key-hierarchy.md)), as for a rotation. A switched-off link is also no
  longer re-wrapped by a rotation, which closes a reach today's code leaves open.
- **What stays in reach**, stated where the residuals are listed:
  - the sweep interval after an end date (point 6);
  - existing `WRITE_ONLY` guest identities on a re-keyed node with a drop key: a former holder of the viewable link
    opened their `K_up` through the old drop private key, so it reads their later uploads until the uploader link is
    re-issued — [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 5's residual, now after a close
    too;
  - a removed tenant member's links: the close cuts whoever else holds them; the member keeps `K_SC` until an explicit
    re-key ([ADR 0025](0025-forward-secrecy-policy-per-scope.md) § 2), which this decision leaves as it is;
  - **an active operator that rolls a member's view of the subtree back** to before the re-key: that member's device
    seals under the old key again. The device floor watches scope epochs, not key revisions. A per-device mark of the
    highest key revision opened per node would be authentic (the placement revision is bound in the seal) but protects
    only devices that saw the re-key, and a fresh device trusts what it is served; it is not built, and
    [security-model § What holds today](../crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve)
    gets the row.
- **A revoke is no longer free.** It costs the subtree's re-key on the revoking device, and writes into the subtree
  wait per node until it is done — for a guest of another link until a member's device has run it. A revoke followed by
  a re-activation pays a re-key for nothing it keeps.
- **Every write path changes the field it states**, a rename across the contracts and every client and handler; the
  rename alone changes nothing a rotation does, so it lands first and is proved by ADR 0109's criteria.

## Alternatives rejected

- **Rotate the scope on every close.** One mechanism without a new counter, but `O(members)` re-grants
  ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)'s cost at a tenant's size) and a re-key of every node of the
  scope, for a link on one Memory.
- **State a key generation beside the epoch.** Two stated fields where one decides: a revision that every re-key moves
  already implies the epoch.
- **Bind the key revision into the seals.** A new binding and new vectors for what the placement revision already
  tells a client; it would only matter to the per-node device mark, which is not built.
- **Derive "due" at write time from the closed links above a node.** No column, but an ancestor walk and a link lookup
  on every write and in every scope's re-keying status.
- **Re-key an expired link's subtree when the next member comes, without marks.** No sweep, but writes keep landing
  under the old key for as long as no member comes.
- **Re-key first, then close; or both in one request.** The link would still be open while its subtree re-keys and get
  the new key; one request has no bound on a large subtree.
- **A closed link only comes back by re-issue.** Simpler — no reopen payload — but it takes away re-activation, which
  is the owner's product choice, not this decision's.
- **A key chain per node, or a lazy re-key.** Rejected in [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
  for the same reasons; a close keeps that commit.

## Bite tests and guards

The cases that must fail if a rule here is wrong become acceptance criteria of a use case of their own — the property
has several triggers, so not under one operation's use case — each with a positive control on the same predicate (an
open sibling link whose wrap opens the new key): the subtree re-keys after each close (point 1); a `WRITE_ONLY` close
re-keys nothing (point 2); a same-epoch stale writer is `stale_node_key` and a write into a due node
`node_rekey_pending` (points 3, 4); a rotation and a close re-wrap no closed link, and re-seal every identity (point
5); the sweep marks an expired link once (point 6); a reopen without the key is refused (point 7); a key staged before
the close does not land (point 8).

- `validate_write_concurrency.py` reads key-revision declarations where it reads epoch declarations, with the conflict
  codes `stale_node_key` and `node_rekey_pending`; bite: an edit declaring `nodeEpoch` is red.
- A new arity guard: every write that closes a link — `enabled = false`, `deleted = true`, row or bulk — sits in the
  one store body that marks the subtree; bite: a stray assignment elsewhere is red; with its case in
  `validate_guard_gaps.py`.
- `validate_node_rekey_arity.py` stays: it proves the assignments exist, not which rows they reach — point 5's
  selection is the slice tests'.

## Phases

1. **Probe the untouched tree:** a revoked link's root takes a member's write under its unchanged key; a rotation
   re-wraps a switched-off link; a contributor of a deleted link keeps a readable name after a rotation (suspected
   from `RekeyStore.parts`, which reads identities only of links not deleted — unmeasured). Each a failing test, quoted.
2. **Contracts:** the renames, `keyRevision`, `due`, the reopen fields, `stale_node_key`; the concurrency guard and
   its bite. Proof: the trust-chain guards.
3. **Backend**, in two steps: the key revision replaces the stated epoch with a rotation's behaviour unchanged
   (`./gradlew check`, ADR 0109's criteria green); then the close — due marks, the one close body, open-only re-wraps,
   every identity, the stage refusal, the reopen, the sweep, the arity guard with its bite (`./gradlew check`, the new
   criteria).
4. **Web:** the generated client, writers stating revisions, the lag predicate in the re-key and its resume, each close
   starting its subtree's re-key, the reopen sealing the key and name, renames skipping closed links (`npm run check`);
   then a browser pass on the dev stack: a revoked link's subtree moves its revisions, the link's wrap is gone, a
   sibling link opens the new key and its guest uploads after.
5. **Docs:** the model (lifecycle and the events table), [keys-grants-and-trust § Re-keying the nodes](../crosscutting/keys-grants-and-trust.md#re-keying-the-nodes),
   the security model's rows, the use case with coverage, the site's Security pages and the Use pages on revoking a
   link (EN+DE). This record becomes Accepted, and only then do the earlier records get their `Replaced in part`
   lines: [ADR 0029](0029-public-viewer-share.md) § 7; [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
   Decisions 4, 5 and 14; [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) points 1, 4 and 6;
   [ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4.
