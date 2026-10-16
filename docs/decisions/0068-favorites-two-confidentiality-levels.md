# ADR 0068 — Favorites are two objects: a private node pin and a shared item mark

- Status: Accepted
- Date: 2026-09-20

## Context

Favorites were **one** object: a per-(user, scope) set of **node ids**, `symWrap`-sealed under the scope
content key `K_SC` at the base epoch `E_0`, stored as one opaque blob and surfaced as the sidebar Favorites
section plus a heart on the Memory card and detail
([en.memory.favorites](../features/memory/enablers/en.memory.favorites.md)). (That object is the **pin**; §10 says
it now belongs to the platform and covers a node of any kind.)

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
`veyq_media.id` is a 36-char UUID string on the wire (a native `UUID` column, Memories baseline) costing **39 bytes** per entry in a JSON array.
`measured` (a script over real `randomUUID()` ids + `JSON.stringify`, against this tree):

| favorited ids in one blob | base64 chars | outcome |
| --- | --- | --- |
| 100 | 5 240 | 5.1 KiB re-uploaded per heart click |
| 500 | 26 040 | 25.4 KiB per heart click |
| 1 259 | 65 508 | the last set that fits |
| 1 260 | 65 560 | `PUT` → 400 |

The wall is real. But the number is not the defect — the curve under it is: at 500 favorites a *single* toggle
re-uploads 25 KiB, long before anything fails. The column is `MEDIUMTEXT`, so storage is not the
constraint; raising the cap only buys a larger per-toggle upload.

## Decision

**1. Two objects, two confidentiality levels, two seals.**

| | **Node pin** (the pin) | **Item favorite** (the mark) |
| --- | --- | --- |
| what | a node id, of any kind (§10) | a `veyq_media` id |
| audience | **private to the member** | **shared, member-attributed** |
| seal | `symWrap(masterKey, …, "favorites")` | `symWrap(K_SC@E_0, …, "item-favorites")` |
| read | the caller's blob only | the scope's **per-member** blobs |
| surfaces | sidebar Favorites, the heart on every node's row and header | gallery filter + per-tile aggregate |
| owner | **core** (§10) | Memories |

The typed single-envelope shape is **rejected**: one blob has one key, so it can serve only one audience.
The pin's plaintext is a **bare id array**; the mark's is `{"tag", "ids"}`, the ids beside the self-tag that says
whose they are (§9). Neither carries a `v` field: the versioning axis is the resource.

**2. "The member's own key" means the master key, not an asymmetric seal to the identity key.**
[ADR 0014](0014-group-scopes-and-governance.md) §7 names the *property* (private to the member, on every device
they unlock), not the primitive. A `sealTo` to the member's own X-Wing identity key would satisfy it at **+1 149 bytes and an
X-Wing key derivation per toggle**, and buys nothing: nobody seals *to* the member here — it is seal-to-self.
`symWrap` under the master key costs +29 bytes, is the property stated exactly, and already has a live
precedent in the identity-pin store.

**3. The pin's seal does not branch on scope type.** In a personal scope `K_SC` is per-user, so the old seal
was *already* private there, and a seal that keeps `K_SC` for personal scopes and the master key for groups
would be **defensible on paper** — which is exactly what makes it dangerous: the branch nobody exercises is
where the fallback survives. One seal, every scope. That is what makes the guarantee testable at all:
*a member's pins must not open with `K_SC` in hand* — in **any** scope, with no case exempted.

**4. Item favorites are partitioned per node — and the partition key is chosen for what it does not leak.**
One blob per **(member, scope, node)**, not per (member, scope). The ceiling becomes 1 257 ids *per Memory* (the
table's 1 259, less the self-tag's fixed 61 bytes, §9), which is beyond a Memory's practical media count, and a toggle re-uploads only that Memory's set. Nothing
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
- `GET /api/scopes/{scopeId}/nodes/{nodeId}/item-favorites` → `[{ self, ciphertext }]`, every member's blob
  including the caller's, which the server flags `self`. No member id: the array separates the members, and
  `self` is the one fact the client cannot derive (§9 says what the client checks it against). `PUT` writes
  **only the caller's**. Authorisation stays member-gated; a non-member gets `404`, like the tree.
- `GET /api/item-favorites` → `[{ scopeId, nodeId, ciphertext }]`, the **caller's own** blobs across every scope
  they are an accepted member of — what a Master-Key rotation re-tags (§9). The server already holds exactly these
  rows under the caller's id, so it learns nothing, and the caller learns only its own.

Operator visibility does not change in kind: the same opaque rows, sizes and timestamps (`updated_at`, to the second).

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

**9. Which entry is mine is proven by my client, not told by the server.** A mark's seal binds (scope, node) and
not the marking member, because the read names no member (§6, [ADR 0072](0072-a-seal-binds-its-object.md) §3). So
the server's `self` flag alone would let it present another member's set as the caller's own — and the caller's
next toggle would then write that set, plus the clicked item, into the caller's own row, erasing their real marks
and doubling the other member's in every count. So the plaintext carries a **self-tag** and the client checks it:

- `K_self = HKDF-SHA256(masterKey, salt = "", info = "veyq/v1/item-mark-self", 32)`. It must be a secret **only the
  marking member** holds — from anything a co-member also holds (`K_SC`, a user id, the scope and node ids) they
  could compute every member's tag by trying each member in turn, which is the attribution §6 forbids. The Master
  Key is the one such secret every device of the member has, and the server cannot choose it.
- `tag = HMAC-SHA256(K_self, statement)`, the statement being the canonical `item-mark-self` encoding of the scope,
  the node and `marks` — SHA-256 over `lp(utf8(id))…` of the marked ids, de-duplicated and sorted. The tag binds the
  **content**, so two versions of one member's set carry unrelated tags and nothing links across nodes: a co-member
  learns nothing the unnamed set did not already tell them. Conformance: `contracts/crypto/vectors/v1.json#item_mark_self`.
- An entry is **mine** only when the server flags it `self` **and** its tag verifies; if several do, the first is,
  and the rest count as someone else's. Everything not provably mine — a failed tag, no tag, a verifying tag without
  `self` — **counts as another member's set**, and the caller's own set is then empty: a degradation, never a
  refusal, because each of these outcomes is one the operator can already cause by deleting the caller's row. What
  this guarantees is the integrity direction: **another member's marks are never shown as mine, and a toggle never
  writes another member's set into my row.** *"Only mine"* stays a filter, not a promise.
- **A Master-Key rotation re-tags.** It reads the caller's own rows (§5), opens each with that scope's `E_0`, and
  re-seals under the new `K_self` only those whose tag verifies under the **old** one — the listing is the server's,
  a hint and not a proof, so a co-member's blob slipped into it stays not-mine. Best-effort like the other
  Master-Key riders: a Memory whose re-tag does not land reads as degraded above until its next toggle.

Rejected: **the tag alone, without `self`** — every co-member opens the caller's blob and can copy it, tag and all,
into their own row; the honest server's `self` is what tells that copy from the original, so neither half is
removable. **A static per-node tag** — it sits inside a plaintext every co-member reads, so it is a copyable
credential: with the server's help, pasted onto a set of their choosing, it would show as the caller's own.
Content-bound, the most a colluder can do is replay a set the caller really once had, which is freshness
([ADR 0072](0072-a-seal-binds-its-object.md) §5), not forgery. **A MAC over the ciphertext as a wire field** —
equally sound, but a contract field on two schemas, a column, a migration and one more opaque value per row for the
operator; inside the seal it costs a fixed 61 bytes that carry no information. **Refusing** an unverifiable own
entry — it adds a UI state and strands the one honest producer of one, a rotation whose re-tag did not land.

**What the per-tile count is not: operator-proof.** The aggregate is exactly as honest as the operator. Over one
node's marks it can, on its own, (R1) withhold any entry, down to a count of zero; (R2) serve an earlier version of
a member's set in place of the current one; (R3) keep serving a removed member's set by skipping the delete; and
(R5) serve stored sets again as **extra entries**, inflating the count without bound on any item some member ever
marked there. With any member the scope ever had — they all hold `E_0` — it can also (R4) forge any number of
entries with any content. The *everyone's* filter is a union, so extra entries do not move it beyond R2. None of
this reaches confidentiality or, after the self-tag, the caller's own row. Two closings of R5 were weighed and
rejected: **(B) de-duplicating equal tags** bounds a replay of one version only, since a member's distinct
historical sets still each count, and it carries a trap — an own entry merged with a same-tag one would let a
co-member who copies the caller's tag launder their ids into the caller's row; **(C) a per-(member, node)
pseudonym inside the plaintext**, counting distinct pseudonyms, caps server-alone inflation at one count per member
per Master-Key generation, but moves §6 from anonymous to pseudonymous per node (co-members could link every
version of one member's set) and still leaves R1, R2 and R4 open. **Re-open (C)** once marks stop sealing under
`E_0` (so a former member can no longer forge) **and** a freshness floor covers them: replay is then the last power
over the count. The plaintext is JSON, so a later optional field is additive.

**10. The pin is core's, and covers every node (2026-10-12, inbox #409).** The pin is a private preference about a
*node*, and a node is core's: a folder, a file, a Memory and a drop box are all nodes, and the data already fitted any
of them — a bare id array, sealed under the Master Key, knows no kind. Keeping it in Memories meant the one application
that happened to draw a heart owned a function every kind needs, and a Depot user could not favorite a file. So the pin
object moved **out of the Memories module into the platform's**:

- **Contract and storage.** `contracts/api/favorites.openapi.yaml` (core's; Memories keeps
  `memories/item-favorites.openapi.yaml`, the item marks) with the same two operations, the same
  `GET`/`PUT /api/scopes/{scopeId}/favorites`, the same body `{ciphertext}` and the same 65 536-char ceiling;
  `veyq_scope_favorites` moved from the Memories baseline to the core baseline (both baselines are editable before the
  first release, pinned together; a database created before the move is recreated). Core's `favorites` package holds the
  entity, store, handler, resource and the scope eraser (phase 600, as before). The seal purpose `favorites`, the
  plaintext (a bare id array) and the last-write-wins rule are **unchanged**, so every crypto vector stays valid and a pin
  set stored by the Memories-era client opens as it did.
- **Surfaces.** The heart is a **core action** — `favoriteRights(access, node)` (member only, fail closed: no
  guest in any role, no scope root, no kind this client cannot draw) contributed through `NodeHeader`'s `coreActions` and
  the level list's `coreRowActions`, so every kind gets the same heart with no change to its application; the sidebar
  Favorites section is core's too and lists every kind's pinned nodes, each with its kind's registry icon (the Memory
  cover it used to show is a Memories concern; inbox #411 brings a cover to any node). A share guest has no heart and the
  server has no guest route: share view = member view with the function removed.
- **A pinned id that no longer resolves is inert, not pruned.** The server cannot read the set, so a purged node's id
  stays in the blob; every view resolves ids against the loaded tree and skips what is not there, so a dangling id never
  renders a broken row. A *trashed* node's pin survives on purpose — restoring it hands the heart back. A client-side
  prune was rejected: it cannot tell "purged" from "not loaded yet", and a prune on the wrong reading is a lost pin. The
  cost is the ceiling (about 1 600 ids), the same as before.
- **A pending deletion keeps the heart**, as Memories always kept it (a pin is a preference, not a write to the scope's
  tree). The server's put takes the READ gate (owner decision 2026-10-12; it took WRITE before, so the toggle was refused with
  `scope_deleting` and the heart bounced back): a pin is the member's own sealed preference, so a space waiting for its
  deletion still stores it. The put no longer has a `409 scope_deleting`.
- **The mark stays Memories'** (`item-favorites`): it names a `veyq_media` id and lives only in a Memory's gallery.

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
  could find it: nothing is drifting, the rule simply would not have been written. The mark's self-tag (§9) is the
  **fourth** rider: its key derives from the Master Key, so without a re-tag a rotation would empty *only mine* on
  every Memory and the first toggle on each would replace the old set.
- **Cross-feature lifecycle is now owed on a second table.** Scope purge must reclaim the item-favorite rows,
  node deletion must drop the rows under it, and a member whose grant is revoked leaves a blob that would keep
  counting into the aggregate. These are named here because the doc↔code delta cannot find them: nothing is
  drifting — the rule simply would not have been written.
- **Contracts move first, and only here.** This is the one axis of the change with a new wire shape; backend
  and web follow it.

## Related

- Supersedes nothing; it **discharges** [ADR 0014](0014-group-scopes-and-governance.md) §7, whose open question
  ("folded in when favorites reach group scopes") is answered by §1–§3 above.
- Owner map: [docs/apps/memories.md](../apps/memories.md) (the marks) and [web/CLAUDE.md](../../web/CLAUDE.md) (the pin in core), [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) (core and applications)
- Enabler: [en.memory.favorites](../features/memory/enablers/en.memory.favorites.md) (both objects)
- Use-case: [uc.memory.gallery](../features/memory/use-cases/uc.memory.gallery.md) (the filter + the aggregate)
- Concepts: [Security Model](../crosscutting/security-model.md) § *What is encrypted vs. server-visible*
  (the operator-blind metadata boundary)
- Decisions: [ADR 0012](0012-content-key-hierarchy.md) (`K_SC` sealing),
  [ADR 0024](0024-scope-key-rotation.md) (the epoch chain behind `E_0`),
  [ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) (the wrap primitives)
- Security area of the docs site: [Known limitations](../../site/src/content/docs/security/known-limitations.md)
