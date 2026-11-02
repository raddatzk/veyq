# ADR 0115 — A scope key is replaced, never chained

- Status: Accepted
- Built: yes
- Date: 2026-11-02
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)

## Context

[ADR 0024](0024-scope-key-rotation.md) decision 1 made a scope's key a chain: each new epoch key `E_{n+1}` wrapped its
predecessor (`scope-epoch`), so whoever held the newest key peeled every older one. The chain existed so that a
rotation re-encrypts nothing: what was sealed at epoch `k` stayed sealed there and was read with `E_k`. Node keys were
the opposite: since [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) every rotation **replaces** every
node key and re-seals what was under it, and [ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md) replaces
a subtree's keys when a link closes. Two levels, two models.

The owner decided (2026-11-02): **the scope key and a node key are treated the same — a key is replaced, never
chained.** One model, so an auditor never asks why one level chains and the other does not; a chain is a performance
device, and for security we would rather not have it. ADR 0109 already re-keys every node at a rotation, so most of
the chain's original purpose is gone.

Replacing is also the stronger guarantee (owner, 2026-11-02). With a chain, everything ever sealed under
`E_0 … E_n` stays sealed that way **in the live store**, so a key obtained later — a removed member's, a lost device's
— opens it from any later dump; no earlier archive is needed. With replacement, after a rotation the live store holds
everything under the new key only, and an old key opens nothing in a dump taken after it: the attacker needs a dump
from before the rotation, that is an operator already malicious and archiving then. A later-turned operator, a backup
taken after the rotation and a new operator are kept out by replacement, and not by a chain.

The chain did two jobs, and both need an answer:

- **Reading what an older key sealed.** Every row sealed under a scope key — the scope-root entries, a group's name
  and icon, an invitee's name key, the links' recovery copies, a tenant's profiles, join records and verifications,
  a Memory's item marks (sealed at `E_0` for good, [ADR 0068](0068-favorites-two-confidentiality-levels.md)) — was read
  under the epoch it was written at.
- **Proving a new key is genuine.** A device opened a later epoch only if its key peeled down through the links to the
  key it held, and a newcomer required the carried epoch to peel down to its inviting owner's anchor grant
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 19 and 23). The operator never held the old key, so it cannot make a link that ends there.

The moderation key has no chain ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
decision 2); no job body is sealed under a scope key; a report hands over a node's key and each field's own key, never
a scope key (`core/report/reportKeys.ts`); a seal's AAD encodes its binding fields by position, never by name
(`src/packages/crypto/src/seal.ts`).

## Decision

1. **A scope has one key at a time.** A rotation mints a fresh random key at epoch `n + 1`; nothing seals an older key
   under a newer one, and no record keeps the old one. The scope is, key-wise, the root above its nodes: its key is
   replaced the way a node's is ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 2), and
   everything sealed under it moves to the new key.

2. **The rotation's commit re-seals everything sealed under the scope key, in one transaction.** The rotating client
   pages through every scope-level row, opens each value with the old key, re-seals it with the new one and stages it
   before the commit, in a stage of its own (`veyq_scope_rekey_stage`: a scope-level row is addressed by no node, and
   a value runs to an item-mark row's 256 KiB). The commit then, under the scope row's lock, takes the new epoch, its
   grants and its succession statement (point 5), and swaps every staged value in — or nothing. It fails closed: a row
   sealed under the scope key without a staged value is `409 rekey_parts_missing`; a row whose ciphertext is no longer
   the one the client re-sealed is `409 rekey_parts_stale` — each staged value names `replacedDigest`, the SHA-256 of
   the ciphertext it was opened from (per item, and per field of a member's profile), checked at the stage and again
   at the commit, so a write between the read and the stage is not overwritten by a re-seal of what it replaced, and
   no row needs a revision of its own; a scope-root entry's name and slug are held by the node's placement revision
   instead. The client stages those again, a bounded number of times. After the commit nothing at scope level is under
   the old key, and every node of the scope is **due** for its own re-key
   ([ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md) decision 4). What moves, and who stages it:

   | Sealed under the scope key | At the commit | Staged by |
   |---|---|---|
   | Each scope-root node's entry, live and trashed: its key wrap, name, slug token | the node's current key re-wrapped, its name re-sealed, its token recomputed; the node's own key is replaced afterwards by its node commit | core |
   | A group's name (`group-name`) and a space's icon (`space-icon`) | re-sealed at the new epoch | core |
   | A pending group invitee's name key (`group-name-key`) | the new name key sealed to each invitee the rotation anchors; one it holds as an anomaly, or anchors no key for, gets its name key **cleared** — the proposal stands, shown to the invitee without the group's name | core |
   | Each link's recovery copy (`share-key-recovery`) | re-sealed at the new epoch; the epoch the link was **minted** at is kept beside it for the re-issue rule ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4) | core |
   | A tenant member's name, picture and join record (`member-name`, `member-avatar`, `member-join-record`) | re-sealed for every member; the picture keeps its 16 KiB pad (`ICON_PADDED_LEN`) | core |
   | A tenant's verifications (`member-verification`) | re-sealed at the new epoch; the signed statement inside is unchanged | core |
   | A Memory's item marks (`item-favorites`) | re-sealed under the new key; the self-tag inside is unchanged | Memories, through core's scope re-key seam |
   | Each member's grant and each open invite's grant (`scope-grant`) | the new epoch wrapped to each (ADR 0111 decision 8) | core |

   **One rule for every kind: a value that does not open is staged as one nobody opens, and read as unreadable.** A
   value the rotator cannot open under the old key — a scope-root entry's name or key wrap, a group's name, an icon, a
   link's recovery copy, a member's field, a verification — is staged as its purpose's own seal at the new epoch under
   a random key dropped at once, of the replaced value's length, with the `replacedDigest` of what it replaces
   (`unopenable` in `features/spaces/scopeRekey.ts`) — never random bytes under the new key, which would open: as a
   name of garbage, or, for a recovery copy or a key wrap, as a key that is no one's. Every reader shows it as it showed
   the value before the rotation: no name, the letter, the unnamed group, no join record, an ignored verification; the
   tree refuses a root entry that does not open, as it did before, and no member can re-key that node, as before — its
   re-key leaves it due and re-keys every other node first. An item-mark set that does not open is staged as an empty
   set nobody owns. **A link whose recovery copy does not open has lost its key**: no member can wrap its root's new
   key for it, so its root's node re-key **closes it**, key lost, through the one close every link takes
   ([ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md) decision 1) — the node stage names it with the
   digest of the copy it could not open (`lostCopyDigest`), the commit closes it in its own transaction after every
   check of the node and before it checks the wraps, and the server still requires a wrap for every open link it is not
   told is lost, so leaving a link out never closes it. One unreadable value must not stop every rotation of the scope,
   nor a node's re-key, and nothing readable is lost. **The residual:** a member re-keyer can so close the links whose
   recovery copy it junks, or claims it cannot open — an insider's denial of service, visible to the curator as a
   closed link, that reads nothing it could not read already.

   An application owns the re-seal of its own rows through core's seam (a **scope re-key participant**,
   `ScopeRekeyParticipant`, the shape of `NodeRekeyParticipant`), never core naming its tables
   ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 3). **What stays:** a member's
   own favorites (under their Master Key); every `K_blob` (re-wrapped per node, ADR 0109 point 3); the moderation key's
   epochs; a report's shares and a hold's copies (point 8). A job body sealed under a scope key would be a row like any
   other; none exists.

3. **A scope-level write states the epoch it seals under**, and the server refuses another (`409 stale_epoch`) under
   the scope row's lock: a create at the scope root, a recovery copy, a group rename, an icon, a profile name or
   picture, a join record at accept, a verification and an item-mark write. A write that lands while the rotation
   stages is caught by the digest (point 2); one that lands after the commit under the old key is refused.

4. **One counter, one word: a key's epoch.** Every replaceable key — a scope's and a node's — counts its replacements
   as its **epoch**; nothing about the word implies a chain. A node's `epoch` is its own key's: `0` at its mint, `+1`
   at every replacement — a rotation's or a close's ([ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md))
   — and every writer states it as `nodeEpoch`, `parentEpoch`, `rootEpoch`. Beside it a node keeps `scopeEpoch`: the
   scope's epoch when its key was minted, bound in its `content-key` and `node-name` seals in the position the epoch
   field always held (no byte of a seal changes) and read by the device floor. `revision` stays the word for a change
   counter of content ([ADR 0086](0086-one-word-per-meaning.md)).

5. **A new key is proven by a succession statement, not by a link.** Each epoch's key derives a signing key,
   `HKDF(K, "veyq/v1/scope-succession")` as an Ed25519 seed, whose public key is published with the epoch. The rotation
   signs `(scopeId, n + 1, pk_{n+1})` with `sk_n`. A holder of epoch `n` verifies the statements from `pk_n` up to
   the granted epoch and requires the last public key to be the one derived from the key the grant carries
   (`verifySuccessionPath`; a refusal is `epoch_unlinked`). Nothing in a statement opens anything: the old key is not
   readable from the new one, nor the new from the old. So:
   - **A device's commitment** ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 23) is the succession public key of the newest epoch it held; a later grant counts only through statements
     from it. A member pending across several epochs checks every statement in between. **The upgrade drops every
     device's held commitment:** the earlier one was a 16-byte HKDF digest of the key, and a stored record of that
     format reads as no held key (`features/account/trustMarks.ts`), so each device's next grant of each scope it held
     opens as a first use, once — trusted on first use, as a linked device's first grant is — and is held as the new
     commitment from then on. The epoch floor beside it is kept: no grant below the epoch the device held opens.
   - **An invite's anchor** (ADR 0111 decision 19) stays the owner's grant beside the carried one; the newcomer
     derives the anchor epoch's succession public key from it and verifies the statements from there to the carried
     epoch. The anchor key it is handed opens only a dump from before the rotation that moved past it, since every row
     is re-sealed since; the invite protocol keeps its bytes.
   - **No public key is published at a scope's birth**: a holder derives its own epoch's public key from the key it
     holds, and each statement carries the next one.
   - **What stays open:** a party that held epoch `n` can sign a successor of its own, as it could link one down under a
     chain (ADR 0111 § What stays open); the device that minted or held the newer epoch refuses it.

6. **The device floor is unchanged in kind.** A device remembers the highest scope epoch it held and opens no grant
   below it ([ADR 0024](0024-scope-key-rotation.md) decision 6); a node key bound to a `scopeEpoch` below it is not
   written under (ADR 0109 point 6). The number stays authentic because the grant and the node seals bind it.

7. **The rotator runs the scope commit; anyone finishes the nodes.** Only the rotating client holds the old key and
   the new one, so the scope-level re-seal is its: reading and staging the scope-level rows is allowed only to a caller
   who may commit the rotation — an owner, or a member whose account securing left a pending re-key row for the scope —
   and who holds the current grant (`RekeyAccess.checkRotator`). A crash before the commit changes nothing — the
   staged values are discarded after a day, a queued rotation's job runs again. After the commit, any member holding
   the current grant re-keys the nodes ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 11): a node's old key comes from its parent's re-sealed entry, so no one needs the old scope key.

8. **Who reads what while keys move.**
   - **A member the rotation cannot grant at once** — declared pending ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 9), behind until a grant-delivery reaches them — reads **nothing** of the scope until re-granted, where a
     chain let them read the old state. Accepted by the owner, like being offline.
   - **A device online with the old key** fails to open the next re-sealed row and re-opens the scope, as on any epoch
     change.
   - **An open invite** is re-granted by the rotation; its anchor is point 5's. One a rotation declared instead of
     carrying is redeemable once a later rotation or its owner carries it: a join record is sealed under the current
     key only, since no later rotation could re-seal one under a key it does not hold.
   - **A pending group invitee whose name key a rotation cleared** (point 2) sees the proposal unnamed for good: nothing
     re-wraps a cleared name key later. A residual, visible to the invitee, accepted for an invitee the rotator could
     not anchor.
   - **Moderation evidence stays openable.** A hold's copies are snapshots of the stored ciphertext and a report's
     share carries the keys that open those copies — a node's key and each field's own key, never the scope key
     ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 8). A re-seal writes new ciphertext; the
     snapshot and its keys are untouched.
   - **Signed records keep their signatures.** A join record, a verification and a grant statement are signed over
     their plaintext; a re-seal changes the envelope, not what was signed. **A join endorsement binds the record, not
     its envelope:** an endorsement over the sealed record's digest
     ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 21) would be voided by every re-seal, and only the owner could sign again. It signs SHA-256 of the
     opened record — the same statement shape, another digest. The reader, which opens the record anyway, checks it; a
     securing's publish pairs each endorsement with a record the account rooted by its member.
   - **An accept that races a rotation re-reads its invite.** The rotation carries every open invite to the new epoch
     (ADR 0111 decision 8); a newcomer that sealed its join record under the epoch it previewed is answered
     `stale_epoch` and seals again under the carried one.

9. **What the server stores and learns.** Per epoch a succession public key and signature (`veyq_scope_succession`),
   which it stores and serves and never verifies; rewritten ciphertext in rows it holds; a minted-at epoch per link. It
   sees a rotation and which rows it rewrites — rows it serves anyway. No key, no new correlation.

**Cost.** A rotation is `O(members)` grants as before, plus every scope-level seal of the table, plus every node's
re-key ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)), all symmetric but the grants and the pending
name keys. The largest scope-level item is the tenant profile: a tenant of 300 members moves about 300 names, join
records and pictures, roughly 5 MB of ciphertext down and up, staged in pages (256 per list, 128 profiles), on the
rotating device; its verifications are at most one row per verifying pair. Rotations are rare by policy
([ADR 0025](0025-forward-secrecy-policy-per-scope.md)): a group removal, an explicit re-key, an account securing.

**Crypto.** No `scope-epoch` purpose exists; the succession statement and its derived key have vectors of their own
(`scopeSuccession.conformance.test.ts`); the invite's statements keep theirs (point 5). Every other seal keeps its
bytes: the binding field renamed `scopeEpoch` in `content-key` and `node-name` changes no AAD, since fields are
encoded by position.

## Consequences

- **After a rotation no key older than the current one opens anything stored**, once the nodes are re-keyed — at
  scope level from the commit on. A removed member's old key opens only a dump from before; a member who joins later
  never holds an older key.
- **The residual, stated honestly: a re-key re-wraps keys, it does not re-encrypt content**
  ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 3). A `K_blob` a former key-holder extracted
  still opens that blob's bytes in a later dump — only content they could read anyway; what is added after the rotation
  gets blob keys of its own. The same holds for any value whose own key a re-seal keeps (an Expenses revision's
  `K_rev`).
- **Pending and behind members read nothing until re-granted** (point 8).
- **An unreadable value never blocks a rotation or a re-key** (point 2): it stays unreadable, and a link whose recovery
  copy is one closes, key lost. A member re-keyer can thereby close links whose copy it junks or claims not to open — an
  insider's denial of service, visible as a closed link; it reads nothing more.
- **The upgrade costs each device one first use per scope** (point 5): the held commitments of the earlier format are
  not read, so the next grant of each scope is trusted on first use; the epoch floor stays.
- **A rotation is not cheap.** ADR 0024's "one random key + one chain-wrap + one asymWrap per member" became the table
  above, landing on the rotating device; a tenant re-key moves its whole profile directory.
- **There is one key to open with**: no reader tries every held epoch, and no member carries their profile forward.
- **Every scope-level write states an epoch** (point 3), and its writers re-read on `stale_epoch`.

## Alternatives rejected

- **Keep the chain for reading, replace only node keys.** The owner's decision; two models for one question.
- **Keep the chain for authenticity only.** A link that proves `E_{n+1}` knew `E_n` is `E_n` sealed under `E_{n+1}` —
  the chain itself. Succession statements prove the same without making the old key readable.
- **A MAC under the old key over the new key's digest.** Verifies one step only: a device two epochs behind does not
  hold the key in between and cannot check the second step. A signature by a derived key verifies with public keys.
- **Re-seal scope-level rows lazily, on their next write.** Reading them in between needs the old key — a chain by
  another name — and a row nobody writes would stay under it for good.
- **Hold every scope-level write while the rotation stages.** No stale re-stage, but a tenant's profile edits stop for
  the whole staging; the digest compare re-stages only what changed.
- **Replace an invite's anchor grant by the anchor epoch's public key, signed by the owner.** The newcomer would never
  hold an older key, but the invite protocol and its vectors change for a key that opens only a dump from before.
- **Give each picture a key of its own and re-wrap only that.** Saves the picture bytes at a tenant re-key, at the cost
  of a new envelope for one field; not taken while tenant rotations stay rare.
- **Refuse the rotation while an invitee's or a member's field, or any other value, cannot be re-sealed.** Every
  rotation of the scope would stay refused for one member's anomaly or one unreadable value; clearing the name key and
  the seal nobody opens keep the rotation going and lose nothing readable.
- **Random bytes under the new key as the stand-in.** Reads the same for a name only where its reader checks a format;
  a recovery copy or a key wrap of random bytes opens as a key nobody's link or node holds — a link that looks open and
  serves nothing — so the stand-in is under no key at all.
- **Leave a link whose copy does not open out of the node's re-key.** No wrap needed, but the server could not tell a
  lost link from one a re-keyer skips, and an open link would be closed by omission; the stage names it instead, and
  the close goes through the one body that marks the subtree.

## Bite tests and guards

The acceptance criteria are [uc.spaces.rekey-after-rotation](../features/spaces/use-cases/uc.spaces.rekey-after-rotation.md)'s
`scope-level-rows-replaced` to `pending-reads-nothing`, each negative with a positive control; the phase-1 probes
(`ScopeKeyReplacedProbeSliceTest`, `epochs.replaced.probe.test.ts`) are among them.

- `validate_scope_rekey_arity.py`: every seal purpose in `seal.ts` is classified as sealed under the scope key — with
  the commit's or a participant's assignment that rewrites its column — or not, with its reason; a new purpose is red
  until classified, an under-scope-key column nobody rewrites is red. Its bites are `validate_guard_gaps.py` cases 98
  (a purpose added unclassified) and 99 (a rewriter's assignment removed).
- `validate_node_rekey_arity.py` names the recovery copy as the scope key's, not a node's; the new guard holds it.
- Point 2's rule for a value that does not open: `scopeRekey.test.ts` (a root entry's name and wrap, a group name, an
  icon, a recovery copy and a member's field, each staged under no key with its digest beside a re-sealed control),
  `rekeyScope.test.ts` (a link whose copy does not open named key-lost, a node whose key does not open holding up no
  other) and `LinkCloseRekeyCoreSliceTest` (the key-lost close, its digest and node checks, the rollback of a refused
  commit, and that leaving an open link out never closes it).
- The crypto conformance tests pin the succession statement and that the `scope-epoch` seal is gone.
