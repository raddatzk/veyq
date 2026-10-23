# ADR 0108 — A scope rotation re-keys every node below it

- Status: Accepted (built by `changes/cr-scope-rotation-rekeys-nodes.md`; until it lands, what § Context measured holds)
- Date: 2026-10-23

## Context

A rotation moves `K_SC` into a new epoch and touches nothing else: `commitRotation` (`src/web/src/features/spaces/scopes.ts`)
mints the epoch, its chain link and the grants, and `ScopesStore.rotate` writes no node row
(`grep -c "NodeEntity\|veyq_node\|wrappedContentKey" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopesStore.kt`
→ 0, 2026-10-23). Node keys never re-keyed ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
Decision 4). So everything added after a rotation **inside a folder, Memory or chapter that existed before it** is
sealed under a node key the excluded party already holds: a new child's key and name, a new medium's `K_blob` wrap,
its meta and EXIF. Only a new scope-root entry is out of their reach. With a dump, the excluded party reads it.

That breaks the promise of every rotation, not only the one #518 was written from:

- **Replacing one's keys after a compromise** ([ADR 0060](0060-key-rotation-after-compromise.md)) exists for exactly
  the old-key-plus-dump case, and its panel said a secured space opens only with the new keys.
- **Removing a group member** ([ADR 0024](0024-scope-key-rotation.md) Decision 4) claimed *forward-confidential for
  post-rotation content*; it held for scope-root entries only.
- **An explicit re-key of the tenant** ([ADR 0025](0025-forward-secrecy-policy-per-scope.md) §2) the same.

The owner's direction (2026-10-23): rotate every node key under the scope key with it.

What a node's key covers, as built (`src/packages/crypto/src/seal.ts`): **keys** — each child's content key
(`content-key`), each `K_blob` of its media and attachments (`blob-key`: original, thumbnail, preview, cover, banner,
a Depot file's content), its drop private key (`drop-private-key`); **content** — each child's name and slug token
(`node-name`, the slug key `HKDF(K_parent, "veyq/v1/slug-token:" + parentSlot)`), its own meta (`node-meta`), its media's
meta and EXIF (`media-meta`, `media-exif`), its buckets' names (`bucket-name`), its contributors' display names
(`share-identity-name`). Every one of these is a database field. **Nothing in the store is sealed under a node key**:
chunks are sealed under `K_blob` alone (`v1.ts` chunk frame), and each derivative is a blob with its own `K_blob`.

## Decision

1. **Every rotation of `K_SC` re-keys every node of the scope** — the group removal's own rotation, a queued
   rotation (an account's after a compromise, a tenant removal's of a co-shared group), an explicit re-key, and the
   personal scope's self-served one. Live **and trashed** nodes: a restore must not bring back a key the excluded
   party holds. A rotation is **done** only once every node's key was minted at the scope's current epoch; until then
   its status is *re-keying*, a fifth state beside [ADR 0060](0060-key-rotation-after-compromise.md) Decision 6's
   four, and the server derives it from the node rows it already holds.
2. **A node's re-key is one atomic commit.** It mints a fresh random key `K_X'`, writes the node's own entry under its
   parent's current key (under the new epoch at the scope root), and re-seals **every field sealed under `K_X`**
   under `K_X'`: each child's entry (its current key re-wrapped, its name re-sealed, its slug token recomputed), the
   node's meta, its media's meta and EXIF, every `K_blob` wrap of its media and attachments, its buckets' names, its
   contributors' names, its drop key (point 5) and the wrap of every share rooted on it (point 4). The commit lands in
   one transaction under the node's row lock; a large node's fields may be uploaded in parts before it. Top-down,
   so after every commit each row opens from its parent's current key, and an interrupted re-key is readable as it
   stands and resumable where it stopped. Plaintext does not change: names, URLs and uniqueness stay, because the
   set of names under a parent is the same set re-tokenized under its new key.
3. **Blobs are re-wrapped, never re-encrypted.** `K_blob` stays; only its wrap moves to the new node key. No byte is
   read from or written to the store. Re-encrypting would also retract *existing* content from a party holding the
   old keys — the gigabytes-per-rotation trade [ADR 0024](0024-scope-key-rotation.md) § Context rejected, and still
   rejected: what existed at the rotation stays readable to whoever held its keys, as already-read content does.
4. **Shares follow the new key, and stay the excluded party's way in until re-issued.** The re-key opens each share's
   `K_share` through its recovery copy and re-wraps the new root key under it, so a link keeps showing what is added.
   But whoever held an older epoch can rebuild that same `K_share` from the same recovery copy
   ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) Context), so with a dump they read through it
   what is added below its root. **A re-issue** — a fresh `K_share` — is what closes a link, and ADR 0075's
   `reissueRequired` already marks every link whose recovery copy predates the current epoch. The re-key therefore
   leaves recovery copies at their epoch, so that mark stays true. Links are not switched off or re-issued
   automatically (owner 2026-10-23): either would break every guest's link to close a reach the owner can close link
   by link.
5. **A drop key is replaced too.** A node carrying a drop keypair gets a new one: its private half sealed under
   `K_X'`, its public half re-sealed under each `WRITE_ONLY` share's `K_share` (`sealedDropPublicKey`), and every
   existing guest identity's key (`K_up`) re-sealed to the new public key. The old private key is discarded. A guest
   identity that existed before keeps its own `K_up`, which the excluded party could open; its later uploads stay in
   their reach until the link is re-issued, which starts every guest over (ADR 0075 point 2).
6. **No write lands under a key from before the rotation.** A node's `epoch` becomes **the epoch its key was minted
   at** (the `content-key` and `node-name` seals already bind it, [ADR 0072](0072-a-seal-binds-its-object.md)), and
   every write that seals anything under a node's key — member or guest, create, placement, meta, media, attachment,
   bucket, share, identity — states that node's epoch as the writer read it:
   - **below the node's stored epoch** → `409 stale_epoch`: the writer holds the key from before; it re-reads.
   - **the node's stored epoch below the scope's current one** → `409 node_rekey_pending`: the node is not re-keyed
     yet; the writer retries after it is. Only that node waits; every re-keyed node is writable.
   Both are checked under the node's row lock, which the re-key's commit holds too. That is layer 1. Against an
   active operator serving a member the old wrap, the **device floor** of [ADR 0024](0024-scope-key-rotation.md)
   Decision 6 extends to node keys: a client seals nothing under a node key bound to an epoch below the highest it
   has held for the scope. A create off the scope root states the current epoch; one below it is `409 stale_epoch`
   (this narrows ADR 0080 Decision 6's *at or below*, whose reason — a server-chosen epoch that left names
   unopenable — does not arise when the writer is told to re-read).
7. **The client that commits the epoch runs the re-key; any owner holding the current epoch finishes it.** The
   remover's device right after its removal; the scope-rotation worker of the owner completing a queued rotation;
   the rotator's own device for the personal scope. A rotation whose re-key is incomplete is unfinished work the
   scope-rotation worker picks up, for every trigger alike. A queued job is `done` only once no node of the scope
   lags (the completion is refused before). The rotator of an account rotation never runs it in a shared scope: they
   hold no current grant there ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 4).
8. **What the server stores and learns.** Rewritten ciphertext in rows it already holds, and each node's epoch moving
   to the current one, right after an epoch change it already sees. No key, no new correlation. Moderation hold copies
   ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)) are snapshots of
   stored ciphertext: the re-key does not touch them, and a report's sealed keys keep opening what was reported, not
   what is added after the re-key. The export is plaintext and unaffected; a copy already mints fresh keys.

**Cost.** Per node one key, its own entry and its children's entries; per medium its blob wraps (three for a
photo) and two field seals. All symmetric, all in the database, nothing in the store. A Chapter of 1,000 photos is
about 5,000 seals, about 20 requests at the contracts' 256-item caps. It lands on the device that runs the rotation,
for a group removal on the remover's (it was `O(members)`, it becomes `O(members + nodes + media)`).

## Consequences

- **A rotation shuts the excluded party out of everything added from then on, except through a link they could
  rebuild.** What remains, stated on every surface that speaks of rotation: what existed at the rotation (point 3);
  links until re-issued, guest identities from before included (points 4, 5); a removal that does not rotate — the
  tenant's routine one ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)) — and a revoked link's guest, whose kept
  keys read what is added under its subtree until the next rotation; and a queued rotation before an owner runs it
  (*waiting*, *stalled*).
- **ADR 0080 Decision 5's accepted residual narrows** to exactly that list: a removed group member's reach ends at the
  removal's rotation, but for links.
- **Writing waits per node while a rotation re-keys.** A writer meets `node_rekey_pending` on a node not yet reached
  and retries; a crashed re-key leaves the unreached nodes waiting until an owner's worker resumes it.
- **Slug keys change with each rotation.** ADR 0080 Decision 2's "stable per parent key" holds between rotations; the
  re-key recomputes every token under the new key in the same commit.
- **New write paths.** Fields that only an insert wrote so far are rewritten in place: `wrapped_blob_key`,
  `sealed_drop_private_key`, `wrapped_exif`, a share identity's name and key, a share's `wrap` and
  `sealedDropPublicKey`; trashed descendants become listable for the re-key.

## Alternatives rejected

- **A key chain per node** (`symWrap(K_X', K_X)`, old fields left as they are): `O(nodes)`, no re-seal of media. But
  every seal under a node key would carry a generation, every reader of every application would peel, and slug
  uniqueness would split across generations — [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  rejected per-node chains, and re-wrapping fields is cheap enough to keep readers as they are.
- **Re-key lazily, on the first write into a node.** No upfront cost, but every write path of every application, and
  every guest, would re-key ancestors, and a guest cannot (it holds no key above its root).
- **Re-key only on a compromise rotation.** It would leave ADR 0024's removal guarantee false.
- **Switch every link off, or re-issue every link, with the rotation** (owner 2026-10-23): it breaks every guest; the
  `reissueRequired` mark and the result screen leave the choice per link.
- **The whole scope read-only during the re-key** (owner 2026-10-23): simpler on the server, but a large space stops
  taking writes for the whole run, and a crashed run keeps it stopped.

## Bite tests (each must fail if the rule is wrong)

1. After a rotation and its re-key, a medium added to a Memory that existed before does not open with any key of the
   epoch before (the old `K_SC` chain, the old `K_node`); a positive control opens it with the new epoch.
2. Every live and trashed node of the scope carries the current epoch once the rotation is *secured*; a queued job's
   completion is refused while one lags.
3. A write stating a node's old epoch → `409 stale_epoch`; into a node not yet re-keyed → `409 node_rekey_pending`;
   into a re-keyed one → accepted.
4. A re-key interrupted after the parent's commit: the tree opens from the new epoch, and resuming finishes it.
5. A link minted before keeps showing a medium added after the re-key, and is marked `reissueRequired`; its re-issue's
   `K_share` does not open what the old recovery copy's `K_share` reaches.
6. An upload to a drop box after the re-key does not open with the drop private key from before.
7. A trashed node restored after a rotation opens only from the new epoch.
8. No store object is written or read by a re-key.
