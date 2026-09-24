# ADR 0068 — Favorites are two objects: a private Memory pin and a shared item mark

- Status: Accepted
- Date: 2026-09-20

## Context

Favorites today are **one** object: a per-(user, scope) set of **node ids**, `symWrap`-sealed under the scope
content key `K_SC` at the base epoch `E_0`, stored as one opaque blob and surfaced as the sidebar Favorites
section plus a heart on the Memory card and detail
([en.memory.favorites](../features/memory/enablers/en.memory.favorites.md)).

The product asks for a second axis: mark an **individual media item**, then filter a Memory's gallery
([uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md)) to *everyone's favorites* or *only
mine*. That request does not extend the existing object — it **collides** with it, in three places.

- **It inverts a decision already taken.** [ADR 0014](0014-group-scopes-and-governance.md) §7 holds that a
  group's per-user favorites must seal under the member's **own** key, because `K_SC` is *shared* in a group and
  today's blob is therefore **already readable by every member** — the enabler calls this a defect, and it is a
  silent one: the data leaks, the UI merely does not display it. *"Everyone's favorites"* needs the exact
  opposite: member-attributed sets every member **can** read. The two designs do not converge.
- **Two confidentiality levels cannot share a blob.** A single sealed envelope carrying both
  (`{v, nodes[], items[]}`) has exactly one key and therefore exactly one audience. Proposed and rejected here.
- **The write shape does not survive the second axis.** The whole set is re-sealed and `PUT` on every toggle
  (last-write-wins). Node counts never stressed that; item counts do.

**The ceiling, measured.** The contract caps `ciphertext` at **65 536** base64 chars → 49 152 sealed bytes; the
`symWrap` frame (type byte + 12-byte nonce + 16-byte GCM tag) costs 29 (`packages/crypto/src/v1.ts:103`); a
`veyq_media.id` is a 36-char UUID (`V11__media.sql:42`) costing **39 bytes** per entry in a JSON array.
`measured` (a script over real `randomUUID()` ids + `JSON.stringify`, against this tree):

| favorited ids in one blob | base64 chars | outcome |
| --- | --- | --- |
| 100 | 5 240 | 5.1 KiB re-uploaded per heart click |
| 500 | 26 040 | 25.4 KiB per heart click |
| 1 259 | 65 508 | the last set that fits |
| 1 260 | 65 560 | `PUT` → 400 |

The wall is real. But the number is not the defect — the curve under it is: at 500 favorites a *single* toggle
re-uploads 25 KiB, long before anything fails. The column has been `MEDIUMTEXT` since V41, so storage is not the
constraint; raising the cap only buys a larger per-toggle upload.

## Decision

**1. Two objects, two confidentiality levels, two seals.**

| | **Memory favorite** (the pin) | **Item favorite** (the mark) |
| --- | --- | --- |
| what | a node id | a `veyq_media` id |
| audience | **private to the member** | **shared, member-attributed** |
| seal | `symWrap(masterKey, …, "favorites")` | `symWrap(K_SC@E_0, …, "item-favorites")` |
| read | the caller's blob only | the scope's **per-member** blobs |
| surfaces | sidebar Favorites, heart on card/detail | gallery filter + per-tile aggregate |

The typed single-envelope shape is **rejected**: one blob has one key, so it can serve only one audience.
Both plaintexts stay **bare id arrays**; the versioning axis is the resource, not a `v` field inside the seal.

**2. "The member's own key" means the master key, not an asymmetric seal to the identity key.**
[ADR 0014](0014-group-scopes-and-governance.md) §7 names the *property* (private to the member, on every device
they unlock), not the primitive. `asymWrap(hybridPublic(priv), …)` would satisfy it at **+1 149 bytes and an
ML-KEM keygen per toggle**, and buys nothing: nobody seals *to* the member here — it is seal-to-self.
`symWrap` under the master key costs +29 bytes, is the property stated exactly, and already has a live
precedent in the identity-pin store.

**3. The Memory seal does not branch on scope type.** In a personal scope `K_SC` is per-user, so the old seal
was *already* private there, and a seal that keeps `K_SC` for personal scopes and the master key for groups
would be **defensible on paper** — which is exactly what makes it dangerous: the branch nobody exercises is
where the fallback survives. One seal, every scope. That is what makes the guarantee testable at all:
*a member's Memory favorites must not open with `K_SC` in hand* — in **any** scope, with no case exempted.

**4. Item favorites are partitioned per node — and the partition key is chosen for what it does not leak.**
One blob per **(member, scope, node)**, not per (member, scope). The ceiling becomes 1 259 ids *per Memory*,
which is beyond a Memory's practical media count, and a toggle re-uploads only that Memory's set. Nothing
consumes item favorites outside a single gallery (§7), so nothing needs the scope-wide set.

Three alternatives were rejected, and for the same reason each time — they pay for the fix in **metadata**:

- **A larger cap.** Fixes nothing: the per-toggle upload is the defect, and a larger cap enlarges it.
- **One row per favorited item, keyed by a blinded token.** Removes the ceiling and makes a toggle O(1), but
  hands the server a per-item write it can correlate, by timing, with the presigned GET for that same media
  moments earlier. That breaks the enabler's own postcondition — *the server never learns which Memories are
  pinned*. Blinded tokens are sanctioned metadata; this particular use of one is still a deanonymisation
  channel.
- **Keyed-hash buckets** (re-seal one bucket of N). The same leak, coarser: the server learns which bucket
  moved, i.e. a hash prefix of the media id, again correlatable by timing.

The node partition costs **zero** additional metadata, because the client has just fetched that node's media
list and its derivative blobs — the server already observed the node id. That is the reason to choose it; the
smaller upload is the bonus, not the argument.

**5. Resource shape.**

- `GET` / `PUT /api/scopes/{scopeId}/favorites` — unchanged shape, one blob per (user, scope). It returns the
  **caller's** blob and never another member's. Only the seal changes (§1).
- `GET /api/scopes/{scopeId}/nodes/{nodeId}/item-favorites` → `[{ memberId, ciphertext }]`, every member's blob
  including the caller's. `PUT` writes **only the caller's**. Authorisation stays member-gated; a non-member
  gets `404`, like the tree.

Operator visibility does not change in kind: the same opaque rows, sizes and coarse timestamps.

**6. Presentation is aggregate only.** The gallery filter is *everyone's favorites* / *only mine*; the grid
shows a **count** on the tile's heart. No faces, no names, no per-member attribution anywhere in the UI.

**7. Where item favorites do not surface.** The sidebar Favorites section stays **node-only** — items are not
navigable nodes, and a cross-scope "all my favorite photos" view is **playlists** (out of V1) wearing a
different hat. A favorited item that is trashed or purged simply drops client-side, as a dead pin already does.
In a **personal** scope *"everyone's"* is degenerate: the filter is **hidden**, not rendered disabled.

**8. No migration, and no read path for the old blob.** There is no production data anywhere, so the old
`K_SC`-sealed node blob is not read, not converted, and not supported back-compatibly. A one-shot re-seal
fallback was considered and rejected: it would keep the `symWrap`-under-`K_SC` *read* path for Memory favorites
alive — the precise path §1 closes, and the one a regression would fall back into.

## Consequences

- **Aggregate-only is a presentation choice, not a confidentiality guarantee — and this must be said where
  copy is written.** In a group scope `K_SC` is shared, and because the server is operator-blind it cannot
  aggregate on our behalf; the client necessarily receives the **per-member blobs** and could name who marked
  what. Hiding that in the UI does not make it unknowable. So **no string in the UI or in `wiki/` may imply
  that nobody can see what you marked** — "only mine" is a *filter*, never a promise. This is the same trap as
  the Memory-favorites defect one level down, and it is why §1 is being built now rather than deferred.
- **The same heart now carries two audiences, and two things carry the difference.** The per-tile **count**
  shows it continuously — but it fails in exactly the first-mark case, where "1" is the user's own mark and
  proves nothing about visibility. That case is carried by the **filter**: *everyone's / only mine* is present
  in the gallery header precisely when favorites are shared, and hidden when they are not (§7). Two carriers,
  because neither covers the other's case. No new icon: a Memory and an item are different elements in
  different places, so the icon is not what is ambiguous.
- **The base-epoch special case survives only on the shared side.** Item favorites keep `K_SC@E_0`, so a
  rotation cannot strand a set. Memory favorites no longer need it at all — the master key does not rotate
  with scope epochs, so that whole class of concern leaves with the seal. **The price of `E_0`:** every member
  the scope ever had holds it, so a set written after a removal is readable by the removed member if they get
  its ciphertext — from a dump, or from a colluding operator. What leaks is each member's marked media ids per
  Memory. Item favorites are therefore outside [ADR 0024](0024-scope-key-rotation.md)'s post-rotation
  guarantee, by this choice, and that ADR names them as such.
- **The pin blob acquires a rotation obligation it did not have before.** The master key **rotates**
  ([ADR 0060](0060-key-rotation-after-compromise.md)), and `rotateKeys` already carries a best-effort re-seal
  for the two objects sealed under it (the identity-pin store and the device register). A master-key-sealed pin
  set is the **third** rider on that path, and omitting it means a rotation silently empties every user's
  Favorites — a degradation, not a lockout, since an unreadable blob decodes to the empty set, but a silent one.
  This is the cost of choosing the master key over `K_SC@E_0`, and it is stated here because no doc↔code delta
  could find it: nothing is drifting, the rule simply would not have been written.
- **Cross-feature lifecycle is now owed on a second table.** Scope purge must reclaim the item-favorite rows,
  node deletion must drop the rows under it, and a member whose grant is revoked leaves a blob that would keep
  counting into the aggregate. These are named here because the doc↔code delta cannot find them: nothing is
  drifting — the rule simply would not have been written.
- **Contracts move first, and only here.** This is the one axis of the change with a new wire shape; backend
  and web follow it.

## Related

- Supersedes nothing; it **discharges** [ADR 0014](0014-group-scopes-and-governance.md) §7, whose open question
  ("folded in when favorites reach group scopes") is answered by §1–§3 above.
- Enabler: [en.memory.favorites](../features/memory/enablers/en.memory.favorites.md) (both objects)
- Use-case: [uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md) (the filter + the aggregate)
- Concepts: [Security Model](../crosscutting/security-model.md) § *What is encrypted vs. server-visible*
  (the operator-blind metadata boundary)
- Decisions: [ADR 0012](0012-content-key-hierarchy.md) (`K_SC` sealing),
  [ADR 0024](0024-scope-key-rotation.md) (the epoch chain behind `E_0`),
  [ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) (the wrap primitives)
