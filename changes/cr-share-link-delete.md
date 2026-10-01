---
id: cr-share-link-delete
title: A share link can be deleted — its ciphertext goes, its id and its guests' uploads stay
status: planned
satisfies: [uc.sharing.share-shared-scope, uc.memory.delete-node]
adrs: ["0029", "0031", "0036", "0061", "0073"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Third refine wave, 2026-10-01 — open, not covered by the answer below.** Inbox #229 (*drop share-identity rows
whose media were all erased*) is folded in here: it is the "later hygiene step" question 4 left out, and it needs
this CR's tombstone to be safe. Questions 8–9 and § *Folded: inbox #229* below carry it; the go on questions 1–7
stands for the rest of the CR whatever the answer.

8. **When may an identity row go?** Its `wrappedIdentityKey` is the only way anyone opens an uploader guest's media,
   its sealed name is the only name their bucket shows, and its token is a live guest's session. **Recommendation:
   only when all three are dead at once** — its share is tombstoned (no guest can use the token again: every guest
   write starts at `activeShare`), **and** no media row (live or trashed — a trashed one can be restored)
   references it, **and** no bucket does. An open upload session does not hold it: such a session can never
   complete once the share is gone, and the stale-session sweep reclaims its objects by storage key alone.
   Measured, this means: a guest who never uploaded goes with the link's delete; a guest who did goes with the
   purge of the last Memory that held their uploads (primary buckets are erased only by a node or scope purge).
9. **Fold #229 into this CR?** **Recommendation: yes.** The rule rests on the tombstone (question 1b) and runs in
   the same two places this CR already edits — the tombstone body and `NodeSubtreePurge`. As its own CR it would
   land after this one and re-open both. Its value is erasure, not tidiness: a guest's sealed name and wrapped key
   are the last trace of them once the Memory they gave to is erased, and security-model § Deletion & erasure should
   be able to say so.

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

Nothing below is decided. Each question carries a recommendation; the CR is written for the recommendations.
**Order:** this CR lands **after** `cr-reissue-share-link` (planned, owner-approved) and changes what its re-issue
does to the old row (question 3). Every claim about that CR is *planned in `cr-reissue-share-link`*; the implementer
re-reads each one against the merged tree first ([changes/README.md](README.md) § *A sibling CR still in flight is
cited as PLANNED*).

**What the refine measured changes the row's premise** (inbox #211: *"disabled and re-issued share rows pile up"*).
Listing noise is the smallest cost. Three findings weigh more:

- **A share on a purged Memory keeps answering `200`, with the Memory's title, forever.** The trash purge erases a
  Memory's nodes, media and blobs and never touches its shares (Inventory row 7). The guest resolve does not check
  that the node exists (row 8), so the link serves the Memory's `K_share`-sealed title and an empty subtree after the
  erasure. Its note, label, password copy and recovery copy stay on the row too. Nobody can see or revoke it any
  more: the manage panel lists shares by node, and the node is gone (row 9). This is an **erasure gap**, not tidiness.
- **A re-issue leaves the old link one click from revival.** `cr-reissue-share-link` turns the old row off. Its
  re-activation refusal fires only when someone left or re-keyed (its Decision 2). A curator who re-issues because a
  link leaked to the wrong person — no removal involved — leaves the old row re-activatable by **any** member (row 5).
- **A deleted row's id must never be minted again.** A share id is client-chosen and refused only while a row holds
  it (row 3). Anyone holding a dead link's id and `K_share` — a former guest with an account on the instance — could
  mint a new share under that id, and every other holder of the dead link would open the attacker's Memory. The scope
  purge already frees ids this way today (row 10).

And a constraint that rules out the obvious delete: **a share row cannot simply be removed while its guests' uploads
exist.** Its identities carry a foreign key to it (row 11), and each UPLOADER identity's `wrappedIdentityKey` is the
only way members and viewers open that guest's uploads; the bucket list also reads the identity (row 12). The media
belongs to the Memory, so deleting a link must not touch it.

1. **What is a delete?** Options: (a) hard-delete the row and its identities; (b) **tombstone**: keep the row with its
   id, scope, node, role and dates; clear every ciphertext column (wrap, sealed name, note, label, recovery copy,
   password fields, drop public key, expiry); set `deleted_at`; keep the identities. **Recommendation: (b).** (a)
   breaks the foreign key, orphans every guest upload and frees the id.
2. **Who may delete?** **Recommendation: the share's owner, at any time** — an enabled share is turned off in the same
   transaction. **Another member, only when the owner is no longer a member of the scope** (the orphans a removal
   leaves). Deleting a co-member's parked link would destroy their note and password copy, which re-activation never
   did.
3. **What does a re-issue do to the old row?** **Recommendation: tombstone it** (question 1b), in the re-issue's
   transaction, instead of `cr-reissue-share-link`'s `enabled = false`. A replaced link has nothing left to come back
   for, and a tombstone cannot be re-activated. No separate "replaced" state: it would be a tombstone with a
   different name. Folding this into `cr-reissue-share-link` instead is possible; the recommendation keeps that CR as
   approved. The interim between the two landings is not user-facing: nothing has shipped (row 14).
4. **The trash purge.** **Recommendation: the purge tombstones every share on an erased node** (identities stay; the
   purge already erased their media, so they are dead weight, and dropping them is a later hygiene step, not this
   CR's). Its link then `404`s.
5. **The scope purge** hard-deletes share rows (the scope row's foreign key requires it). **Recommendation: it
   records each erased id in `veyq_share_retired_id` (the id only),** and create refuses an id found there
   (`409 id_taken`). The id is 256 random bits and names nothing once its row is gone.
6. **Retention of turned-off (not deleted) rows.** **Recommendation: kept until deleted — no automatic expiry.**
   Turning a link off is reversible by design; an expired link can still be extended. The panel folds turned-off
   rows under a count instead.
7. **The panel's icons.** Today *revoke* wears a trash can (row 13), so users will read it as the delete this CR
   adds. **Recommendation:** *turn off* gets a "ban" icon; the trash can moves to *delete*, shown on turned-off rows
   and behind a confirm.

## Goal

A curator can delete a share link. Its ciphertext leaves the database, its id can never serve anything again, and
the photos its guests added stay in the Memory, readable as before. A re-issue deletes the link it replaces, and
erasing a Memory deletes its links.

## Context & constraints

- **Soft revoke and its limits:** [ADR 0029](../docs/decisions/0029-public-viewer-share.md) §7 — *"`enabled = false` /
  deleting the record → the next guest request `404`s"*: the ADR already names deletion; nothing implements it.
- **Uploads outlive their link:** [ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7 (the drop key
  and each identity's wrapped key); `cr-reissue-share-link` question 4 relies on it for re-issue.
- **The per-link budget:** [ADR 0073](../docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)
  §4 counts a link's usage through its identities; a deleted link starts no upload, so its usage stops mattering.
  Tenant usage counts blobs by scope and does not change: **a delete reclaims no bytes** (row 12).
- **The trash:** [ADR 0061](../docs/decisions/0061-node-deletion-reversible-node-trash.md);
  [uc.memory.delete-node](../docs/features/memory/use-cases/uc.memory.delete-node.md) E4 — a share on a *trashed*
  node resolves to nothing and comes back on restore. That stays; only the *purge* changes.

### Inventory — measured on `main` @ `64c1f65`, 2026-09-28

Every row was read or run at refine; the command re-derives it.

| # | Fact | Locator |
|---|---|---|
| 1 | No share row is ever deleted outside the scope purge; there is no delete operation. | `grep -rn "ShareEntity.delete" backend/src/main/kotlin`; `grep -n "operationId" contracts/api/sharing.openapi.yaml` |
| 2 | A turned-off share and an unknown id answer the guest identically (`404 share_not_found`). | `grep -n "private fun activeShare" -A8 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt` |
| 3 | Create refuses an id only while a row holds it. | `grep -n "ShareEntity.findById(id) != null" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 4 | Every member-facing share entry point starts from `store.find(shareId)` — the set a tombstone must answer `404` on. | `grep -n "store.find(shareId)\|activeShare(shareId)" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt` |
| 5 | Re-activation flips any row back on for any member with write access. | `grep -n "fun reactivate" -A8 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 6 | The manage panel lists every share of the node, turned-off ones included, each with a re-activate button. | `grep -n "fun listByNode" -A2 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n "v-if=\"!share.enabled\"" web/src/features/memory/components/ShareDialog.vue` |
| 7 | The trash purge erases media, buckets, covers, item favorites and nodes — no share rows. | `grep -n "ShareEntity\|ShareIdentity" backend/src/main/kotlin/com/veyq/app/features/content/NodeSubtreePurge.kt` (no hits) |
| 8 | The resolve does not check that the shared node exists: a missing root yields an empty subtree, served with the row's sealed name; the trashed case is pinned at `200`. | `grep -n "the shared root itself is trashed" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n "fun deletedSubtreeLeavesTheAnonymousResolve" -A22 backend/src/test/kotlin/com/veyq/app/features/sharing/ShareDeletedNodeSliceTest.kt` |
| 9 | The node listing returns `[]` for an unknown node, so a share on an erased Memory is listed nowhere the web reads (the web never calls the node-less listing). | `grep -n "store.findNode(nodeId) ?: return" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; `grep -rn "sharesApi().listShares" web/src` |
| 10 | The scope purge deletes identities then shares, freeing their ids; its comment still calls shares personal-only. | `grep -n "Dormant today" -A6 backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopePurge.kt` |
| 11 | `veyq_share_identity.share_id` is a foreign key to `veyq_share.id`; `veyq_share.sealed_name` is `NOT NULL`. | `grep -n "fk_share_identity_share\|sealed_name" backend/src/main/resources/db/migration/V31__share.sql backend/src/main/resources/db/migration/V32__contributor_share.sql` |
| 12 | Uploads are opened through the identity row: members' media page and viewers' resolve read `wrappedIdentityKey` by identity id; the bucket list reads the identity's share id; tenant usage counts blobs by scope, link usage through identities. | `grep -n "wrappedIdentityKey is not null" backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n "identity?.shareId" backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt`; `grep -n "fun linkUsage\|fun tenantUsage" backend/src/main/kotlin/com/veyq/app/features/media/StorageQuota.kt` |
| 13 | The panel's *revoke* button wears the trash icon. | `grep -n "pi pi-trash" -A2 web/src/features/memory/components/ShareDialog.vue` |
| 14 | Pre-V1 migrations assume an instance reset (nothing has shipped). | `grep -n "before V1" backend/src/main/resources/db/migration/V57__uploader_drop_key.sql` |
| 15 | The Memory's drop private key lives on the node, set once; no share delete may clear it. | `grep -n "sealedDropPrivateKey = ?1 where id = ?2" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 16 | No test drives the node purge **and** touches a share, so nothing pins the resolve after an erasure (row 8 pins only the trashed case). | `grep -rln "NodeSubtreePurge" backend/src/test/kotlin \| xargs grep -ln "/api/shares/"` (no hits) |

The `200`-after-purge in row 8 is read from the code, not run; B1 is written red-first, so it settles it either way.

## Decisions (for the recommendations)

1. **Schema** (next free migration; re-run `ls backend/src/main/resources/db/migration | sort -V | tail -1`):
   `veyq_share.deleted_at TIMESTAMP NULL`; `sealed_name` becomes nullable; `CREATE TABLE veyq_share_retired_id (id
   VARCHAR(64) NOT NULL PRIMARY KEY)`.
2. **`SharingStore.tombstone(share)`** — the one body every path calls: `enabled = false`, `deleted_at = now`, every
   ciphertext column and `expires_at` set to null. Identities, media, buckets and the node's drop key untouched.
3. **`DELETE /api/shares/{shareId}`** → `204`. Gate: scope write access; the caller owns the share, or its owner has no
   membership in the scope (question 2). Otherwise, and for an unknown or already-deleted id, the non-enumerable
   `404 share_not_found`.
4. **A tombstone is not found** on every row-4 entry point except the guest path (already `404` via `enabled`):
   revoke, update, re-activate, re-issue and create-identity answer `404`; both listings filter `deleted_at is null`.
5. **Re-issue tombstones the old row** in its transaction (question 3), replacing the planned `enabled = false`.
6. **`NodeSubtreePurge`** tombstones every share whose `node_id` is in the erased set, inside its existing
   transaction.
7. **`ScopePurge`** writes each erased share id into `veyq_share_retired_id` before deleting the rows; the stale
   "personal-scope only" comment goes (the same stale wording `cr-reissue-share-link` row 9 names).
8. **`SharingStore.create`** refuses an id found in either table (`ID_TAKEN`).
9. **Web** — `share.ts` gains `deleteShare`; `ShareDialog` gets the icon swap and the delete action with its confirm
   (question 7), folds turned-off rows under a count, and drops a deleted row from the list. i18n EN + DE.

## Phases

1. **Phase 0 — the decision on record.** ADR 0029 §7 gets the delete's shape (tombstone, id never reused, uploads
   kept) or a pointer to the ADR `cr-reissue-share-link` wrote, if that one is the natural home. · Done when: all
   `scripts/validate_*.py` green.
2. **Phase 1 — contracts.** `deleteShare` on the existing `sharing` tag (backend red until Phase 2: the generated
   interface gains a method); `reissueShare`'s description says the old link is deleted; `createShare`'s `409
   id_taken` says an id is never reused. · Done when: `npm run generate:api` and the contract guards green.
3. **Phase 2 — backend.** Decisions 1–8. · Done when: `./gradlew check` green, including B1–B5.
4. **Phase 3 — web.** Decision 9. · Done when: `npm run check` green in `web/`, including W1.
5. **Phase 4 — docs.** `uc.sharing.share-shared-scope`: a *delete a link* alternative flow and its coverage YAML;
   `uc.memory.delete-node` E4: *"erasing the Memory deletes its links"*; security-model § Deletion & erasure: share
   rows join the erasure list; the wiki pair if either page describes turning a link off. Inbox #211 → done.
   · Done when: `python3 scripts/validate_coverage.py` and all `scripts/validate_*.py` green.

## Definition of done

- **Baseline before the first edit** — run it, don't trust this line:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` printed nothing on
  `64c1f65` at refine.
- **B1 — erasing a Memory ends its link (red-first, live today).** In `ShareDeletedNodeSliceTest`:
  - **Setup:** share S on Memory X; delete X; move its `deletedAt` past the retention; `purge.run()` (as
    `NodeTrashSliceTest` drives it).
  - **Red today:** `GET /api/shares/S` → `200`. **The assertion expected to fail first** is the `404`.
  - **After:** `404 share_not_found`, and the row's `sealedName`, `sealedShareKey` and `sealedNote` are null.
  - **Control:** a share on a sibling Memory Y that was not deleted still resolves `200` after the same purge.
  - **Bite:** drop Decision 6's call → B1 red, the control green.
- **B2 — delete, and a tombstone is gone everywhere.** Owner deletes enabled S → `204`; resolve `404`; the node
  listing omits S; re-activate, update and revoke on S → `404`; create with id S → `409 id_taken`.
  - **Negative with its control:** a co-member deletes another member's enabled share → `404`, and it still
    resolves `200`; after that owner is removed from the scope, the same call → `204`.
  - **Bite:** filter the listing but skip Decision 4 in `reactivate` → the re-activate assertion goes red.
- **B3 — the guests' uploads survive their link.** UPLOADER share U; a guest identity uploads media M; delete U.
  Then the members' media page still returns M's `uploaderKeys` entry, the bucket list still names the guest, the
  node's `sealedDropPrivateKey` is unchanged and tenant usage is unchanged.
  **Bite:** in a snapshot copy, null the identity's `wrappedIdentityKey` inside `tombstone` → the `uploaderKeys`
  assertion goes red. Restore.
- **B4 — a re-issued link cannot come back (red-first against the tree with `cr-reissue-share-link` merged).**
  Voluntary re-issue of S (no removal, current epoch) → re-activate S. **Red before this CR:** `204` and S resolves
  `200` again. **After:** `404`. **Bite:** revert Decision 5 to `enabled = false` → red.
- **B5 — a purged scope's ids stay dead (red-first, live today).** In `ScopesSliceTest` (or wherever the scope
  purge is driven): group G with share S is purged; a member elsewhere creates a share with id S. **Red today:**
  `201`. **After:** `409 id_taken`. **Bite:** skip Decision 7's insert → red.
- **W1** — `ShareDialog.test.ts`: a turned-off row offers delete, an enabled row does not; *turn off* no longer uses
  the trash icon; a delete removes the row. **Control:** a co-member's turned-off row whose owner is still a member
  offers no delete.
- Existing assertions carry over per test; `ShareDeletedNodeSliceTest`'s trashed-then-restored cases keep passing
  unchanged (a trashed node's share is **not** tombstoned — only the purge does that).

**Not proven:** that a malicious operator erases anything (a dump or backup keeps the old row, as for every erasure
in security-model § Deletion & erasure); that deleting identities of an erased Memory is safe (left out, question 4;
taken up by § *Folded: inbox #229* if the owner answers question 9 yes).

## Folded: inbox #229 — share identities go once nothing can use or open them (third refine wave, 2026-10-01)

Written for questions 8–9's recommendations. Until the owner answers them this section has **no go**; questions
1–7 and everything above keep theirs.

### Inventory — measured on `main` @ `7a34c57`, 2026-10-01

| # | Fact | Locator |
|---|---|---|
| I1 | Three things reference an identity id: a media row (`share_identity_id`, any `deleted_at`), a primary bucket (`owner_key = share:{id}`) and an upload session (`opener_share_identity_id`, no foreign key). | `grep -rn "shareIdentityId\|openerShareIdentityId" backend/src/main/kotlin/com/veyq/app/features/media/Entities.kt`; `grep -n "PREFIX_SHARE = " backend/src/main/kotlin/com/veyq/app/features/media/OwnerKey.kt`; `grep -n "opener_share_identity_id" backend/src/main/resources/db/migration/V51__upload_session_opener_and_window.sql` |
| I2 | What reads an identity: the token lookup (guest writes), the uploader-key map (any share's identity, by media), and the bucket name (primary buckets only). | `grep -n "fun findIdentity\|fun uploaderKeys" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n "private fun contributorIdentityOf" -A4 backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |
| I3 | Every guest write path starts at `activeShare`, before the token lookup — a tombstoned share's token is unusable. | `grep -n "val share = activeShare(shareId) ?: return" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt` |
| I4 | Only the scope purge deletes identities today. | `grep -rn "ShareIdentityEntity.delete" backend/src/main/kotlin` |
| I5 | A primary bucket is deleted only by a node or scope purge; a sub-bucket delete reparents to the primary; media moves stay within one node. | `grep -rn "BucketEntity.delete" backend/src/main/kotlin`; `grep -n "fun deleteBucketReparent\|fun moveMedia" backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |
| I6 | One body erases a media row for every purge (retention, member-expedited, contributor delete-own, node purge); the node purge then deletes the subtree's buckets. | `grep -n "fun purgeMediaRows" backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt`; `grep -n "purgeMediaRows\|BucketEntity.delete" backend/src/main/kotlin/com/veyq/app/features/content/NodeSubtreePurge.kt` |
| I7 | The stale-session sweep deletes sessions and records their objects by storage key, without reading the opener. | `grep -n "fun purgeSessionsIdleSince" -A6 backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |

### Decisions

10. **`SharingStore.dropDeadIdentities(candidateIds)`** — the one body: deletes each candidate whose share has
    `deleted_at` set and that no media row (any state) and no bucket references. Candidates are passed in; it never
    scans the table.
11. **Callers, both inside transactions this CR already edits:** the tombstone body (Decision 2) passes the share's
    identities; `NodeSubtreePurge` collects the identity ids of the media and buckets it erases **before** erasing
    them, and calls it **after** the bucket delete and Decision 6's tombstones — so a link deleted earlier loses its
    identities when the last Memory holding their uploads is purged.
12. **Sessions do not hold an identity** (question 8): no change to the stale-session sweep.

### Phases (merged into the CR's phases above)

- **Phase 2** adds: Decisions 10–12.
- **Phase 4** adds: security-model § Deletion & erasure — a guest's identity row (sealed name, wrapped key) is erased
  once its link is deleted and nothing of theirs remains; `uc.memory.delete-node` E4's wording covers it; inbox
  #229 → done.

### Definition of done (added)

- **B6 — a dead identity goes, a live one stays (red-first: today no path but the scope purge deletes one).** In
  `ShareDeletedNodeSliceTest`:
  - **Setup:** UPLOADER share U on Memory X. Guest G1 names themself and never uploads; guest G2 uploads media M;
    guest G3 uploads media M3 and deletes it again (delete-own), so G3 keeps an empty primary bucket.
  - **Action 1:** delete U. **After:** G1's row is gone; G2's row stays with its `wrappedIdentityKey`, and the
    members' media page still returns M's `uploaderKeys` entry (B3's assertion, unchanged); G3's row stays (its
    bucket still shows its name). **Red today:** the delete does not exist, and nothing removes G1 — the assertion
    expected to fail first is "G1's row is gone".
  - **Action 2:** trash and purge X. **After:** G2's and G3's rows are gone.
  - **Control for the share condition:** share V on Memory Y stays enabled; its guest G4 uploads only into chapter
    C under Y; trash and purge C (not Y) → G4's media and buckets are erased, and G4's row **stays** — V is live and
    G4's token is still a session.
  - **Bite, per lever** (in a snapshot copy, restored after each): drop the `deleted_at` condition → the G4 control
    goes red; drop the bucket condition → G3's Action 1 assertion goes red; drop the media condition → G2's Action
    1 assertion goes red.

**Not proven (added):** that a backup forgets the rows (the same limit as every erasure above).
