# ADR 0115 — A scope key is replaced, never chained

- Status: Proposed
- Built: no
- Date: 2026-11-02
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)

## Context

A scope's key is a chain today ([ADR 0024](0024-scope-key-rotation.md) decision 1): each new epoch key `E_{n+1}` wraps
its predecessor (`scope-epoch`), so whoever holds the newest key peels every older one. The chain existed so that a
rotation re-encrypts nothing: what was sealed at epoch `k` stays sealed there and is read with `E_k`. Node keys are
the opposite: since [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) every rotation **replaces** every
node key and re-seals what was under it, and [ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md) replaces
a subtree's keys when a link closes. Two levels, two models.

The owner decided (2026-11-02): **the scope key and a node key are treated the same — a key is replaced, never
chained.** One model, so an auditor never asks why one level chains and the other does not; a chain is a performance
device, and for security we would rather not have it. ADR 0109 already re-keys every node at a rotation, so most of
the chain's original purpose is gone.

Replacing is also the stronger guarantee (owner, 2026-11-02). With the chain, everything ever sealed under
`E_0 … E_n` stays sealed that way **in the live store**, so a key obtained later — a removed member's, a lost device's
— opens it from any later dump; no earlier archive is needed. With replacement, after a rotation the live store holds
everything under the new key only, and an old key opens nothing in a dump taken after it: the attacker needs a dump
from before the rotation, that is an operator already malicious and archiving then. A later-turned operator, a backup
taken after the rotation and a new operator are kept out by replacement, and not by the chain.

The chain does two jobs, and both need an answer:

- **Reading what an older key sealed.** Measured: the web reads under an older epoch through `keyAt`,
  `openUnderHeldEpochs` and `writeKey` in `core/node/tree.ts`, `core/node/rekey/rekeyScope.ts`, `core/report/reportKeys.ts`,
  `core/sharing/manageShares.ts`, `features/account/profile/{carryProfileForward,memberAvatar,memberName}.ts`,
  `features/account/rotation/rotateKeys.ts`, `features/account/tenantTrust.ts`, `features/memories/{itemFavorites,participantIndex}.ts`,
  `features/memories/components/MemoryDetailHost.vue`, `features/spaces/{memberInvite,members,scopes}.ts` (`grep -rnE
  "keyAt\(|writeKey\(|openUnderHeldEpochs\(|deriveEpochKeys\(|epochLinkContext|copyEpochKeys\(" src/web/src | grep -v
  "\.test\.ts:"`; `app/activeSpace.ts`, `core/people/directory.ts` and `features/spaces/TenantMembersPanel.vue` only copy
  the key set). The seal purposes that bind a scope epoch are `content-key`, `node-name`, `share-key-recovery`,
  `group-name`, `space-icon`, `member-verification`, `scope-grant`, `group-name-key` and the chain's own `scope-epoch`
  (`grep -n "epoch\|Epoch" src/packages/crypto/src/seal.ts | grep "\[\["`); `member-name`, `member-avatar`,
  `member-join-record` and `item-favorites` bind none and are opened by trying every held key, or at `E_0` for good
  (ADR 0068). The backend's columns carrying a scope epoch are listed by `grep -rn '@Column(name =
  "[a-z_]*epoch[a-z_]*"' src/backend/core/src/main src/backend/apps/*/src/main`.
- **Proving a new key is genuine.** A device opens a later epoch only if its key peels down through the links to the
  key it held, and a newcomer requires the carried epoch to peel down to its inviting owner's anchor grant
  ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 19 and 23). The operator never held the old key, so it cannot make a link that ends there.

Measured beside it: the moderation key already has no chain (`grep -n "No chain link between epochs"
docs/decisions/0099-*.md`); no job body is sealed under a scope key (`grep -c "'job'" src/packages/crypto/src/seal.ts`
is `0`); a report hands over a node's key and each field's own key, never a scope key (`core/report/reportKeys.ts`);
a seal's AAD encodes its binding fields by position, never by name (`sed -n 327,337p src/packages/crypto/src/seal.ts`).

## Decision

1. **A scope has one key at a time.** A rotation mints a fresh random key at epoch `n + 1`; nothing seals an older key
   under a newer one, and no record keeps the old one. The scope is, key-wise, the root above its nodes: its key is
   replaced the way a node's is ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 2), and
   everything sealed under it moves to the new key.

2. **The rotation's commit re-seals everything sealed under the scope key, in one transaction.** The rotating client
   opens each value with the old key, re-seals it with the new one, and stages it in pages before the commit, as a
   node's re-key does. The commit then, under the scope row's lock, takes the new epoch, its grants and its
   succession statement (point 5), and swaps every staged value in — or nothing. It fails closed: a row sealed under
   the scope key without a staged value is `409 rekey_parts_missing`; a row whose ciphertext changed since it was
   staged is `409 rekey_parts_stale` (the stage keeps the digest of the ciphertext it replaces, so no row needs a
   revision of its own); the client stages those again. After the commit nothing at scope level is under the old key,
   and every node of the scope is **due** for its own re-key ([ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md)
   decision 4). What moves, and who stages it:

   | Sealed under the scope key | Today | At the commit | Staged by |
   |---|---|---|---|
   | Each scope-root node's entry: its key wrap, name, slug token | at the epoch it was written | the node's current key re-wrapped, its name re-sealed, its token recomputed; the node's own key is replaced afterwards by its node commit | core |
   | A group's name (`group-name`) and a space's icon (`space-icon`) | at `nameEpoch` / `iconEpoch` | re-sealed at the new epoch | core |
   | A pending group invitee's name key (`group-name-key`) | sealed to the invitee at `nameEpoch` | the new name key sealed to each, after the anchor check every wrap to a member makes | core |
   | Each link's recovery copy (`share-key-recovery`) | at the epoch it was written, left there as the re-issue mark | re-sealed at the new epoch; the epoch the link was **minted** at is kept beside it for the re-issue rule ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4) | core |
   | A tenant member's name, picture and join record (`member-name`, `member-avatar`, `member-join-record`) | under the epoch of their write; each member carries their own forward | re-sealed for every member; the picture is 16 KiB padded (`ICON_PADDED_LEN`) | core |
   | A tenant's verifications (`member-verification`) | at the epoch of the write | re-sealed; the signed statement inside is unchanged | core |
   | A Memory's item marks (`item-favorites`) | at `E_0` for good (ADR 0068) | re-sealed under the new key; the self-tag inside is unchanged | Memories, through core's scope re-key seam |
   | Each member's grant and each open invite's grant (`scope-grant`) | the new epoch wrapped to each (ADR 0111 decision 8) | unchanged | core |

   An application owns the re-seal of its own rows through core's seam (a **scope re-key participant**, the shape of
   `NodeRekeyParticipant`), never core naming its tables
   ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 3). **What stays:** a member's
   own favorites (under their Master Key); every `K_blob` (re-wrapped per node, ADR 0109 point 3); the moderation key's
   epochs, which are already replaced rather than chained ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
   decision 2); a report's shares and a hold's copies (point 8). A job body sealed under a scope key would be a row
   like any other; none exists.

3. **A scope-level write states the epoch it seals under**, and the server refuses another (`409 stale_epoch`) under
   the scope row's lock — as a create at the scope root, a recovery copy, a group rename and an icon do already. A
   profile name or picture, a join record at accept, a verification and an item-mark write state it too. A write that
   lands while the rotation stages is caught by the digest (point 2); one that lands after the commit under the old
   key is refused.

4. **One counter, one word: a key's epoch.** Every replaceable key — a scope's and a node's — counts its replacements
   as its **epoch**; nothing about the word implies a chain any more. A node's `epoch` is its own key's: `0` at its
   mint, `+1` at every replacement — a rotation's or a close's ([ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md),
   which writes it, and every writer states it as `nodeEpoch`, `parentEpoch`, `rootEpoch`). Beside it a node keeps
   `scopeEpoch`: the scope's epoch when its key was minted, bound in its `content-key` and `node-name` seals in the
   position `epoch` holds today (no byte of a seal changes) and read by the device floor. `revision` stays the word for
   a change counter of content ([ADR 0086](0086-one-word-per-meaning.md)).

5. **A new key is proven by a succession statement, not by a link.** Each epoch's key derives a signing key,
   `HKDF(K, "veyq/v1/scope-succession")` as an Ed25519 seed, whose public key is published with the epoch. The rotation
   signs `(scopeId, n + 1, pk_{n+1})` with `sk_n`. A holder of epoch `n` verifies the statements from `pk_n` up to
   the granted epoch and requires the last public key to be the one derived from the key the grant carries. Nothing in
   a statement opens anything: the old key is not readable from the new one, nor the new from the old. So:
   - **A device's commitment** ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 23) is the succession public key of the newest epoch it held; a later grant counts only through statements
     from it. A member pending across several epochs checks every statement in between.
   - **An invite's anchor** (ADR 0111 decision 19) stays the owner's grant beside the carried one; the newcomer
     derives the anchor epoch's succession public key from it and verifies the statements from there to the carried
     epoch, where it peeled the links before. The anchor key it is handed opens only a dump from before the rotation
     that moved past it, since every row is re-sealed since.
   - **No public key is published at a scope's birth**: a holder derives its own epoch's public key from the key it
     holds, and each statement carries the next one.
   - **What stays open is unchanged:** a party that held epoch `n` can sign a successor of its own, as it could link
     one down today (ADR 0111 § What stays open); the device that minted or held the newer epoch refuses it.

6. **The device floor is unchanged in kind.** A device remembers the highest scope epoch it held and opens no grant
   below it ([ADR 0024](0024-scope-key-rotation.md) decision 6); a node key bound to a `scopeEpoch` below it is not
   written under (ADR 0109 point 6). The number stays authentic because the grant and the node seals bind it.

7. **The rotator runs the scope commit; anyone finishes the nodes.** Only the rotating client holds the old key and
   the new one, so the scope-level re-seal is its, before the commit. A crash before the commit changes nothing — the
   staged values are discarded, a queued rotation's job runs again. After the commit, any member holding the current
   grant re-keys the nodes ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 11): a node's old key comes from its parent's re-sealed entry, so no one needs the old scope key.

8. **Who reads what while keys move.**
   - **A member the rotation cannot grant at once** — declared pending ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 9), behind until a grant-delivery reaches them — reads **nothing** of the scope until re-granted; with the
     chain it read the old state. Accepted by the owner, like being offline.
   - **A device online with the old key** fails to open the next re-sealed row and re-opens the scope, as on an epoch
     change today.
   - **An open invite** is re-granted by the rotation as today; its anchor is point 5's.
   - **Moderation evidence stays openable.** A hold's copies are snapshots of the stored ciphertext and a report's
     share carries the keys that open those copies — a node's key and each field's own key, never the scope key
     ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 8). A re-seal writes new ciphertext; the
     snapshot and its keys are untouched.
   - **Signed records keep their signatures.** A join record, a verification and a grant statement are signed over
     their plaintext; a re-seal changes the envelope, not what was signed.

9. **What the server stores and learns.** Per epoch a succession public key and signature instead of a chain link;
   rewritten ciphertext in rows it holds; a minted-at epoch per link. It sees a rotation and which rows it rewrites —
   rows it serves anyway. No key, no new correlation.

**Cost.** A rotation becomes `O(members)` grants as before, plus every scope-level seal of the table, plus every
node's re-key ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)), all symmetric but the grants and the
pending name keys. The largest scope-level item is the tenant profile: a tenant of 300 members moves about 300 names,
join records and pictures, roughly 5 MB of ciphertext down and up, staged in pages, on the rotating device; its
verifications are at most one row per verifying pair. Rotations are rare by policy
([ADR 0025](0025-forward-secrecy-policy-per-scope.md)): a group removal, an explicit re-key, an account securing.

**Migration.** No instance is live. A forward migration drops `veyq_scope_epoch` (the links), adds the succession
statements, a node's `scope_epoch` and a link's minted-at epoch; a node's `epoch` changes meaning. Dev databases are
reset — the owner's step.

**Crypto.** The `scope-epoch` purpose and its vector go; the succession statement and its derived key get vectors of
their own; the invite's statements keep theirs (point 5). Every other seal keeps its bytes: renaming
a binding field (`epoch` → `scopeEpoch` in `content-key` and `node-name`) changes no AAD, since fields are encoded by
position.

## Consequences

- **After a rotation no key older than the current one opens anything stored**, once the nodes are re-keyed — at
  scope level from the commit on. A removed member's old key opens only a dump from before; a member who joins later
  never holds an older key.
- **The residual, stated honestly: a re-key re-wraps keys, it does not re-encrypt content**
  ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 3). A `K_blob` a former key-holder extracted
  still opens that blob's bytes in a later dump — only content they could read anyway; what is added after the rotation
  gets blob keys of its own. The same holds for any value whose own key a re-seal keeps (an Expenses revision's
  `K_rev`).
- **Pending and behind members read nothing until re-granted** (point 8), where the chain let them read the old state.
- **A rotation is no longer cheap.** ADR 0024's "one random key + one chain-wrap + one asymWrap per member" becomes
  the table above, landing on the rotating device; a tenant re-key moves its whole profile directory.
- **`carryProfileForward` and every "open under every held epoch" go**: there is one key to open with.
- **Every scope-level write gains a stated epoch** (point 3), and its writers a `stale_epoch` re-read.

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

## Bite tests and guards

Acceptance criteria of [uc.spaces.rekey-after-rotation](../features/spaces/use-cases/uc.spaces.rekey-after-rotation.md),
each negative with a positive control: after the commit every row of the table opens under the new key and none under
the old; a rotation missing one row's staged value is refused and writes nothing; a row changed since staged is
`rekey_parts_stale`; a scope-level write stating the old epoch is `stale_epoch`; a grant whose succession statements do
not lead from the held key is refused, the same grant through genuine statements opens; a pending member reads nothing
and reads everything once re-granted; no chain link is stored.

- **A new guard, the scope re-key's arity:** every seal purpose in `seal.ts` is classified as sealed under the scope
  key — with the commit's or a participant's assignment that rewrites its column — or not, with its reason; a new
  purpose is red until classified, an under-scope-key column nobody rewrites is red. Bite: a purpose added unclassified,
  and a rewriter's assignment removed. Its case goes into `validate_guard_gaps.py`.
- `validate_node_rekey_arity.py`: the recovery copy's exclusion ("left at its epoch") moves to the new guard.
- The crypto conformance tests lose `scope-epoch` and gain the succession statement.

## Phases

The combined plan with [ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md) is in that record's § Phases.
When this record is accepted the earlier ones get their `Replaced in part` lines: [ADR 0024](0024-scope-key-rotation.md)
decisions 1, 2 and 6 and its rejected single-key rotation; [ADR 0068](0068-favorites-two-confidentiality-levels.md)'s
seal at `E_0`; [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 2's
scope-root entries at their epoch; [ADR 0084](0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md)'s
carry-forward; [ADR 0036](0036-editable-share-note-recoverable-share-key.md) point 4's copy at its epoch;
[ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) points 4 and 6;
[ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decisions
19 and 23.
