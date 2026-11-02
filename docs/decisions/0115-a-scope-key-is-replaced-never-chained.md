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

2. **The rotation's commit moves everything under the scope key, in one transaction — and it re-wraps keys, it never
   rewrites a value** (owner, 2026-11-02). **Every scope-level value that has an author has a key of its own:** a
   member's tenant name, picture and join record, a verification, a Memories item mark, a group's name and icon, a
   link's recovery copy of `K_share` (`VALUE_KEY_PURPOSES`, `src/packages/crypto/src/seal.ts`) — each sealed under a
   value key `K_v`, 32 random bytes its author mints fresh at **every** write, with the value's own purpose and binding
   unchanged; a field-sealed value derives its `K_field` from `K_v` ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
   § 9). Only `K_v` is sealed under the scope key (`value-key`), bound to the scope, the epoch whose key wraps it and
   the value's **address** — SHA-256 of the canonical encoding of the value's own seal context (`valueAddress`,
   `src/packages/crypto/src/v1.ts`) — so a wrap moved onto another value's slot does not open, and a value under a
   substituted `K_v` does not open either: **a rotator can junk a value, never forge one.** The rotating client pages
   through every wrap under the scope key and every scope-root entry, opens each wrap with the old key, wraps the same
   `K_v` under the new one and stages it before the commit, in a stage of its own (`veyq_scope_rekey_stage`: a
   scope-level row is addressed by no node; sized for wraps and root entries, a root's name the largest part). The
   listing serves no value's ciphertext and no stage schema has a field for one — a field a stage schema does not name
   is `400 invalid_request`, never dropped as if staged; a staged write to a value is refused at the commit, `422
   rekey_writes_value`, nothing stored — for an owner and a member rotator alike, since both hold only the key a wrap
   needs. The commit then, under the scope row's lock,
   takes the new epoch, its grants and its succession statement (point 5), and swaps every staged wrap in — or nothing.
   It fails closed: a wrap under the scope key or a root entry without a staged replacement is `409
   rekey_parts_missing`; a row whose wrap is no longer the one the client re-wrapped is `409 rekey_parts_stale` — each
   staged wrap carries the wrap it replaces as it was listed (`replacedValueKey`; a wrap is under 128 characters, so
   the text itself is compared and nothing is digested), and the commit compares it with the row; every write of a
   value mints a fresh `K_v` and so a fresh wrap, so a write anywhere between the read and the commit is never
   overwritten by a re-wrap of the key it replaced, and no row needs a revision of its own; a scope-root entry is held
   by the node's placement revision instead, which every rename, move and re-key of the entry moves. The client stages
   those again, a bounded number of times. After the commit nothing at scope level is under the old key,
   and every node of the scope is **due** for its own re-key
   ([ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md) decision 4). What moves, and who stages it:

   | Under the scope key | At the commit | Staged by |
   |---|---|---|
   | Each scope-root node's entry, live and trashed: its key wrap, name, slug token | **re-sealed** — the one exemption: any member may write a root entry ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 11), so a rotator rewrites nothing another member could not, and its slug token is a keyed MAC under the scope key that moves with the key. The node's current key re-wrapped, its name re-sealed, its token recomputed; the node's own key is replaced afterwards by its node commit | core |
   | A group's name (`group-name`) and a space's icon (`space-icon`) | their `K_v` re-wrapped; the name and the icon, and the epoch each was written at, stay | core |
   | Each link's recovery copy (`share-key-recovery`) | its `K_v` re-wrapped; the copy stays, with the epoch it was written at — the link's **mint**, which nothing moves after it — beside it for the re-issue rule ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) point 4). `K_share` is itself a key, so it has a value key too: a wrap a rotator substituted opens no `K_share` at all, never one of its choosing | core |
   | A tenant member's name, picture and join record (`member-name`, `member-avatar`, `member-join-record`) | their `K_v` re-wrapped for every member; the fields stay byte-identical, the picture its 16 KiB pad (`ICON_PADDED_LEN`) | core |
   | A tenant's verifications (`member-verification`) | their `K_v` re-wrapped; the row, bound to the epoch it was written at, and the signed statement inside stay | core |
   | A Memory's item marks (`item-favorites`) | their `K_v` re-wrapped; each set and its self-tag stay | Memories, through core's scope re-key seam, handed only the re-wrap |
   | Each member's grant and each open invite's grant (`scope-grant`) | the new epoch wrapped to each (ADR 0111 decision 8) | core |

   **A pending group invitee's name key is the group name's `K_v`**, sealed to the invitee (`group-name-key`) at the
   add and not under the scope key: a rotation leaves it, and the invitee opens the name as the members do. A
   **rename** mints a fresh `K_v` and seals it, in the same request, to every pending invitee
   (`inviteeNameKeys`): to the key the owner's device anchors (ADR 0028 Decision 2), or — for one it holds as an
   anomaly — `null`/`null`, which clears that invitee's name key; an invitee holding a name key left out is `409
   rename_incomplete`, one sealed to a key that is not the invitee's current one `409 recipient_key_not_current`,
   nothing written either way.

   **One rule for every kind: a value that does not open is staged as one nobody opens, and read as unreadable.** A
   wrap the rotator cannot open under the old key is staged as a stand-in: a random `K_v` wrapped at the same slot under
   a random key dropped at once, of the same length, beside the wrap it replaces (`rewrapValueKey`,
   `src/web/src/features/spaces/valueKey.ts`); a scope-root entry's name or key wrap that does not open, as its purpose's
   own seal at the new epoch under such a key, of the replaced value's length (`unopenable`,
   `features/spaces/scopeRekey.ts`) — never a wrap or random bytes under the new key, which would open: as a name of
   garbage, or, for a recovery copy or a key wrap, as a key that is no one's. Every reader shows it as it showed the
   value before the rotation: no name, the letter, the unnamed group, no join record, an ignored verification, an empty
   set of marks nobody owns; the tree refuses a root entry that does not open, as it did before, and no member can
   re-key that node, as before — its re-key leaves it due and re-keys every other node first. **A link whose recovery
   copy does not open has lost its key**: no member can wrap its root's new key for it, so its root's node re-key
   **closes it**, key lost, through the one close every link takes
   ([ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md) decision 1) — the node stage names it with the
   digest of the copy it could not open (`lostCopyDigest`), the commit closes it in its own transaction after every
   check of the node and before it checks the wraps, and the server still requires a wrap for every open link it is not
   told is lost, so leaving a link out never closes it. One unreadable value must not stop every rotation of the scope,
   nor a node's re-key, and nothing readable is lost.

   **The residuals.** **Junking stays possible:** a rotator, owner or member, can stage a stand-in for a wrap it could
   open, which makes that value unreadable — as any member could make their own scope's values unreadable before — and
   a junked recovery copy closes its link, key lost: an insider's denial of service, visible to the curator as a closed
   link and to every reader as a missing value, that reads nothing it could not read already. **Forging through the
   rotation is impossible:** the stage carries wraps only, the commit refuses a value write, and a wrap of a `K_v` of
   the rotator's choosing opens no value sealed under another. What the value key does not change is a member's
   standing ability to seal a value at any address of the scope, which an operator then writes into another member's
   row — the class a seal's binding leaves open ([ADR 0072](0072-a-seal-binds-its-object.md)), against which the join
   record, a verification and an item mark carry signatures or a self-tag
   ([security-model § What holds today](../crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve)).

   An application owns the re-wrap of its own values' keys through core's seam (a **scope re-key participant**,
   `ScopeRekeyParticipant`, the shape of `NodeRekeyParticipant`), never core naming its tables
   ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 3); the seam hands it the
   re-wrap and no scope key, so on the client too an application cannot re-seal a value. **What stays:** a member's
   own favorites (under their Master Key); every `K_blob` (re-wrapped per node, ADR 0109 point 3); the moderation key's
   epochs; a report's shares and a hold's copies (point 8). A job body sealed under a scope key would be a row like any
   other; none exists.

3. **A scope-level write states the epoch it seals under** — for a value, the epoch whose key wraps its fresh `K_v`,
   sent beside the value — and the server refuses another (`409 stale_epoch`) under the scope row's lock: a create at
   the scope root, a recovery copy, a group rename, an icon, a profile name or picture, a join record at accept, a
   verification and an item-mark write. A write that lands while the rotation stages is caught by the commit's
   compare with the wrap it replaced (point 2); one that lands after the commit under the old key is refused.

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
     epoch. The anchor key it is handed opens only a dump from before the rotation that moved past it, since every wrap
     and root entry is moved since; the invite protocol keeps its bytes.
   - **No public key is published at a scope's birth**: a holder derives its own epoch's public key from the key it
     holds, and each statement carries the next one.
   - **What stays open:** a party that held epoch `n` can sign a successor of its own, as it could link one down under a
     chain (ADR 0111 § What stays open); the device that minted or held the newer epoch refuses it.

6. **The device floor is unchanged in kind.** A device remembers the highest scope epoch it held and opens no grant
   below it ([ADR 0024](0024-scope-key-rotation.md) decision 6); a node key bound to a `scopeEpoch` below it is not
   written under (ADR 0109 point 6). The number stays authentic because the grant and the node seals bind it.

7. **The rotator runs the scope commit; anyone finishes the nodes.** Only the rotating client holds the old key and
   the new one, so the scope-level re-wrap is its: reading and staging the scope-level wraps is allowed only to a caller
   who may commit the rotation — an owner, or a member whose account securing left a pending re-key row for the scope —
   and who holds the current grant (`RekeyAccess.checkRotator`). A crash before the commit changes nothing — the
   staged values are discarded after a day, a queued rotation's job runs again. After the commit, any member holding
   the current grant re-keys the nodes ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 11): a node's old key comes from its parent's re-sealed entry, so no one needs the old scope key.

8. **Who reads what while keys move.**
   - **A member the rotation cannot grant at once** — declared pending ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 9), behind until a grant-delivery reaches them — reads **nothing** of the scope until re-granted, where a
     chain let them read the old state. Accepted by the owner, like being offline.
   - **A device online with the old key** fails to open the next re-wrapped key and re-opens the scope, as on any
     epoch change.
   - **An open invite** is re-granted by the rotation; its anchor is point 5's. One a rotation declared instead of
     carrying is redeemable once a later rotation or its owner carries it: a join record's value key is wrapped under
     the current key only, since no later rotation could re-wrap one under a key it does not hold.
   - **A pending group invitee** keeps reading the group's name across a rotation: its name key is the name's own
     `K_v`, which the rotation leaves (point 2). One whose name key a **rename** cleared — the owner's device could not
     anchor its key — sees the proposal unnamed until a rename by a device that anchors it; a residual, visible to the
     invitee, accepted for an invitee its owner's device cannot anchor. A declined or rescinded invitee keeps the `K_v`
     it was handed: that one name, from a dump, until the next rename.
   - **Moderation evidence stays openable.** A hold's copies are snapshots of the stored ciphertext and a report's
     share carries the keys that open those copies — a node's key and each field's own key, never the scope key
     ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 8). A rotation writes new wraps and new
     root entries; the snapshot and its keys are untouched.
   - **Signed records keep their signatures.** A join record, a verification and a grant statement are signed over
     their plaintext, which a rotation never touches. **A join endorsement binds the record, not its envelope:** an
     endorsement over the sealed record's digest
     ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
     decision 21) would tie it to one envelope — and to the value key it is sealed under — that only its author
     writes. It signs SHA-256 of the opened record — the same statement shape, another digest. The reader, which opens the record anyway, checks it; a
     securing's publish pairs each endorsement with a record the account rooted by its member.
   - **An accept that races a rotation re-reads its invite.** The rotation carries every open invite to the new epoch
     (ADR 0111 decision 8); a newcomer that sealed its join record under the epoch it previewed is answered
     `stale_epoch` and seals again under the carried one.

9. **What the server stores and learns.** Per epoch a succession public key and signature (`veyq_scope_succession`),
   which it stores and serves and never verifies; beside every authored scope-level value the wrap of its key, which
   every write and every rotation rewrites; rewritten root entries. It sees a rotation and
   which wraps it rewrites — rows it serves anyway — and that a write minted a fresh wrap, which it saw as a write
   anyway. No key, no new correlation.

**Cost.** A rotation is `O(members)` grants as before, plus one re-wrap per authored scope-level value and one re-seal
per scope-root entry, plus every node's re-key ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)), all
symmetric but the grants. The largest scope-level item is the tenant profile: a tenant of 300 members moves about 900
value-key wraps of 61 bytes each — some 55 KB down and up, where re-sealing the names, join records and padded pictures
moved about 5 MB — staged in pages of 256, on the rotating device; its verifications are at most one wrap per
verifying pair. Rotations are rare by policy ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)): a group removal,
an explicit re-key, an account securing.

**Crypto.** No `scope-epoch` purpose exists; the succession statement and its derived key have vectors of their own
(`scopeSuccession.conformance.test.ts`); the invite's statements keep theirs (point 5). The value key is a seal purpose
of its own, `value-key` — `(scopeId, epoch, valueAddress)`, the address a 32-byte digest — pinned with its addresses
and a rotation's re-wrap (`v1.json#value_key`, `seal.sym['value-key']`, `valueKey.conformance.test.ts`). The group
name's and the icon's HKDF-derived keys (`K_name`, `K_icon`) are gone with their vectors: each is a value key now. Every
other seal keeps its bytes: a value's own seal is unchanged but for the key it is sealed under, and the binding field
renamed `scopeEpoch` in `content-key` and `node-name` changes no AAD, since fields are encoded by position. No instance
was live when values took their own keys, and the dev database is reset: the migration clears every stored value it
cannot give a wrap — a group's name stays, unreadable until renamed (`V202611021800__core_value_keys.sql`).

## Consequences

- **After a rotation no key older than the current one opens anything stored**, once the nodes are re-keyed — at
  scope level from the commit on. A removed member's old key opens only a dump from before; a member who joins later
  never holds an older key.
- **The residual, stated honestly: a re-key re-wraps keys, it does not re-encrypt content**
  ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) point 3). A `K_blob` a former key-holder extracted
  still opens that blob's bytes in a later dump — only content they could read anyway; what is added after the rotation
  gets blob keys of its own. The same holds for any value whose own key a re-key keeps — an Expenses revision's
  `K_rev`, and every scope-level value's `K_v` (point 2): one a former key-holder extracted while holding the scope key
  opens that value in a later dump until its author writes it again, which mints a fresh `K_v` — content they could
  read anyway.
- **A rotator can junk a value, never forge one** (point 2). The stage carries wraps only and the server refuses a
  value write (`422 rekey_writes_value`), for owner and member rotators alike; a wrap a rotator substitutes opens a key
  no value is sealed under. Junking stays: a stand-in wrap makes a value unreadable, and a junked recovery copy closes
  its link, key lost — an insider's denial of service, visible as a missing value or a closed link; it reads nothing
  more. Before this, any rotator re-sealed every other member's values, and so could rewrite them.
- **Pending and behind members read nothing until re-granted** (point 8).
- **An unreadable value never blocks a rotation or a re-key** (point 2): it stays unreadable, and a link whose recovery
  copy is one closes, key lost. A member re-keyer can thereby close links whose copy it junks or claims not to open — an
  insider's denial of service, visible as a closed link; it reads nothing more.
- **A pending invitee's name key outlives a rotation and dies with a rename** (point 2): it is the name's `K_v`, so a
  rotation needs no anchor for an invitee and clears nothing; a rename seals the new key to every invitee, and refuses
  one that leaves an invitee out (`409 rename_incomplete`).
- **The upgrade costs each device one first use per scope** (point 5): the held commitments of the earlier format are
  not read, so the next grant of each scope is trusted on first use; the epoch floor stays.
- **A rotation is not cheap, but its scope stage is small.** ADR 0024's "one random key + one chain-wrap + one
  asymWrap per member" became the table above, landing on the rotating device; a tenant re-key moves one wrap per
  profile field, not the profile directory, and every node's re-key on top.
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
  the whole staging; the commit's compare re-stages only what changed.
- **Replace an invite's anchor grant by the anchor epoch's public key, signed by the owner.** The newcomer would never
  hold an older key, but the invite protocol and its vectors change for a key that opens only a dump from before.
- **The rotation re-seals every value under the new key** (what this decision first built). One envelope per value and
  no wrap beside it, but the rotator opens and re-writes every other member's value: any rotator — an owner, or a member
  securing their account — could rewrite another member's name, picture or join record, a group's name, an item-mark
  set or a link's recovery copy (handing every re-keyer a `K_share` of its choosing) in a commit the server cannot tell
  from an honest one, and a tenant re-key moved some 5 MB. A key of its own per value leaves the rotator nothing to
  write but a wrap (owner, 2026-11-02).
- **Sign each value by its author instead.** Closes forging by the operator too, but a reader would need every author's
  current key to check a name, a re-signed value at each securing, and an unsigned one would have to read as unreadable
  — a new trust path for every field, where the value key leaves a rotation nothing to forge with and the records that
  need authorship (the join record, a verification) are signed already.
- **One key per member for all their fields, not per value.** Fewer wraps, but one write's key would open the member's
  other fields, and a report handing one field over would hand them all.
- **Refuse the rotation while a wrap, or any other value, cannot be opened.** Every rotation of the scope would stay
  refused for one unreadable value; the stand-in keeps the rotation going and loses nothing readable.
- **Keep re-wrapping a pending invitee's name key at each rotation.** It is the name's `K_v`, which a rotation leaves,
  so a re-wrap would hand the invitee the same key again; the rename is the one write that mints a new one.
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

- `validate_scope_rekey_arity.py`: every seal purpose in `seal.ts` is classified as under the scope key — with the
  commit's or a participant's assignment that rewrites its column — or not, with its reason; a new purpose is red until
  classified, an under-scope-key column nobody rewrites is red, and "a rotation writes only wraps" is structural: the
  value purposes are exactly `VALUE_KEY_PURPOSES`, each re-wrapped, and only the root-entry re-seal may name a value
  column. Its bites are `validate_guard_gaps.py` cases 98 (a purpose added unclassified), 99 (a re-wrap assignment
  removed), 102 (the commit writing a member's name beside its key), 103 (Memories' participant writing a set again),
  104 (a value purpose dropped from `VALUE_KEY_PURPOSES`) and 105 (the root-entry re-seal writing a group's name).
- `validate_node_rekey_arity.py` names the recovery copy as the scope key's, not a node's; the new guard holds it.
- Point 2's value keys: `valueKey.conformance.test.ts` (a wrap moved onto another value's slot, or of another epoch,
  does not open; the value under a substituted `K_v` does not open; a re-wrap leaves the value's bytes), the web's
  `valueKey.test.ts` (a writer's value opens through its `K_v` and never under the scope key; a substituted wrap reads
  as unreadable, not as another value; a re-wrap leaves the value byte-identical; an unopenable wrap becomes the
  stand-in), `scopeRekey.test.ts` (a rotation stages wraps only — no value's ciphertext in any stage — and each moved
  wrap opens the unchanged value under the new key; a root entry's name and wrap, a group name's, an icon's, a recovery
  copy's and a member's field's wrap that does not open, each staged under no key beside the wrap it replaces and a
  re-wrapped control), `itemFavorites.test.ts` (Memories' participant re-wraps and never re-seals a set), `scopes.test.ts` (a
  rename after a rotation mints a fresh `K_v` and seals it to each invitee, clearing an anomaly's),
  `rekeyScope.test.ts` (a link whose copy's wrap does not open, or was substituted, named key-lost; a node whose key
  does not open holding up no other) and `ScopeRekeySliceTest` (the commit refuses a value write `422
  rekey_writes_value`, a stale wrap or root entry `409 rekey_parts_stale`, a field no stage schema names `400`) and
  `LinkCloseRekeyCoreSliceTest` (the key-lost close, its digest and node checks, the rollback
  of a refused commit, and that leaving an open link out never closes it).
- The crypto conformance tests pin the succession statement, the value key, and that the `scope-epoch` seal is gone.
