# UC: Delete media — remove a photo into a recoverable per-Memory trash

- **ID:** uc.memory.delete-media
- **Primary actor:** A member with an **unlocked vault** viewing a Memory who wants to **remove** a photo/video.
- **Goal:** Take a media item out of a Memory — it **leaves the gallery immediately**, sits in that Memory's
  **recoverable trash** for a retention window, and is **hard-purged** (rows + blobs + store objects) by a
  scheduled system job afterwards. **Operator-blind** — the server flips a trash flag and, at purge, removes opaque
  blob objects; it never sees content.
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_node`** is held); the caller **belongs to
  the scope**. The media data layer + blob store exist ([en.memory.media](../enablers/en.memory.media.md)); the
  live-updates reconcile is in place ([uc.memory.live-updates](uc.memory.live-updates.md), the `delete_revision`
  substrate).
- **Postconditions (success):** The media is gone from the gallery on **every open client**; it sits in the
  Memory's **Trash tab**, restorable, until the retention window elapses, then a scheduled job **irreversibly
  purges** it. The trashed record carries **who trashed it and when**.

## What deletion is here

Deletion is a **soft-delete → trash → retention window → scheduled hard-purge** flow (the
[security model](../../../crosscutting/security-model.md)'s own erasure mechanism). The two steps carry
**different authority**:

- **Trashing is reversible** — the media moves to the Memory's trash and is restorable for the window — so **any
  member of the scope** may do it (recovery, not destruction). It is **audited**: the trashed record carries
  `deleted_by` + `deleted_at`, so the action is accountable, not anonymous.
- **Purging is irreversible** — the blob-level hard-delete. It runs two ways: the scheduled retention job, and — for
  an item **already in the trash** — a member may **expedite** it by hand (cr-0111, from the Trash tab). A member
  cannot purge a **live** item directly; destruction always goes **trash → purge**.

For a **personal** scope "any member" is simply the owner; in a **shared** scope (group/tenant) any member may
trash **and** restore, and recoverability + the audit bound the blast radius. (Formal moderation — *who* may
delete *whose* — is deferred to a later groups/moderation refine.)

## Main flow

1. In the gallery **lightbox** the user removes the current photo — a **trash button** in
   the lightbox top bar, or the **Delete / Backspace** key (the other delete surface is the contributions organizer's
   selection, E7). There is **no confirmation dialog** (it is recoverable):
   the media moves to the trash, the lightbox **advances to the next** photo (a fast culling flow), and an **undo
   toast** offers immediate restore.
2. The client calls **delete** → the server marks the media **trashed** (`deleted_at = now`,
   `deleted_by = the member`) and **bumps the node's `delete_revision`** ([uc.memory.live-updates](uc.memory.live-updates.md)),
   so every open gallery drops the tile on its next poll. Locally the tile is removed **optimistically** and the
   poll reconciles it.
3. Trashed media are **excluded from the normal media list** (`listNodeMedia` and its id-set), so they no longer
   appear in the gallery, **feed the `auto` date/place**, or serve as the **cover** (a trashed cover's `mediaId`
   dangles → the placeholder, the existing E2 fallback).
4. The Memory's **"Trash" tab** (beside *Gallery* / *Chapters*) lists trashed media in the **same justified grid**
   as the gallery (dimmed tiles). Each tile has a **checkbox** (batch-select) and a **3-dot menu** — **Restore**
   (clears `deleted_at`, bumps the revision, and the tile returns to the gallery in its date position) or
   **Delete forever** (the member-expedited purge, cr-0111; a confirm guards the irreversible hard-delete). A batch
   toolbar restores or purges the whole selection at once.
5. After the **retention window** (default **30 days**, operator-configurable), a **scheduled purge job**
   hard-deletes each expired trashed media — its derivative + media + blob rows and the store objects (reusing the
   blob-reclaim path). This step is **irreversible** and is **not** a member action.

## Alternate / exception flows

- **E1 — undo / restore:** the toast's *Restore*, or the Trash tab, clears the trash flag before purge; the media
  returns intact (its blobs were never removed — only the flag flipped).
- **E2 — the trashed media was the cover:** the `cover.mediaId` dangles → best-effort placeholder (the existing
  [uc.memory.cover](uc.memory.cover.md) E2 path); restoring brings the cover back. (Proactively clearing a cover
  that points at trashed media is an optional nicety, not required — the placeholder already prevents a broken
  tile.)
- **E3 — auto date/place:** `auto` ranges + the medoid **self-heal** from the shrunken media list on the next
  reload/poll ([uc.memory.date](uc.memory.date.md) / [uc.memory.place](uc.memory.place.md)) — no delete-specific
  reconcile code; a manual/`fixed` value is never touched.
- **E4 — not a member / vault locked:** the delete op is **scope-membership-gated**; a non-member gets a
  non-enumerable **404** (operator-blind), a locked vault cannot open the Memory to reach the affordance.
- **E5 — concurrent / idempotent:** trashing an already-trashed media is a no-op; restoring an already-**purged**
  media is a 404 (the retention job won the race).
- **E6 — the trashed media is inside a public share:** it leaves the guest's view on **both** guest paths at once —
  the anonymous resolve stops listing it, and its blobs stop being authorized for a presign
  ([uc.sharing.share-public-viewer](../../sharing/use-cases/uc.sharing.share-public-viewer.md)); a restore brings it
  back on both. **Both, or neither**: filtering only the listing leaves a medium that is invisible in the share and
  still fully downloadable by anyone who kept its `blobId` — for the entire retention window. This is the rule that
  had to be *written*, not just fixed. It is a cross-feature obligation with no single owner — the trash side and
  the sharing side share nothing but the `veyq_media` rows, so neither one's tests would have missed it, and the
  defect shipped exactly there.
- **E7 — a selection (the contributions organizer, [uc.memory.buckets](uc.memory.buckets.md)):** the organizer
  trashes a selection of media with one delete per medium, sent together; a refusal does not stop the rest. One undo
  toast counts only what went to the trash and its *Restore* restores exactly those; every refused medium is named
  (the file name sealed with its original, else its place in the grid) with its reason — gone or no longer reachable
  (`404`), the space is being deleted (`409 scope_deleting`), or anything else (try again) — one error per reason.
  When nothing went there is no undo toast. A selection where every delete succeeds shows only the count with its
  undo (inbox #427).
  **The Undo's restore names its refusals the same way** (inbox #429) — the organizer's *Restore* (one restore per
  medium; a refusal does not stop the rest) and the lightbox's (its one photo) alike: every medium whose restore is
  refused is named by the name it had when it went (the sealed file name, else its place in the grid the delete
  came from) with its reason — purged meanwhile or no longer reachable (`404 media_not_found`), the space is being
  deleted (`409 scope_deleting`), anything else (try again) — one error per reason. What was restored simply
  reappears in the grid, as before; an Undo whose restores all succeed says nothing.
  **So does the Trash tab's restore** (inbox #432) — a tile's *Restore* (its one medium) and the toolbar's restore of
  the selection (one restore per medium; a refusal does not stop the rest): every medium whose restore is refused is
  named by the name it had in the tab (the sealed file name, else its place in the trash grid) with the same reasons,
  one error per reason, and the tab re-lists, so a medium still in the trash shows there again (as a refused purge
  re-lists it). What was restored leaves the tab and reappears in the gallery, as before; a restore where every
  medium went says nothing.

## Quality notes

- **Operator-blind, unchanged.** Delete flips `deleted_at`/`deleted_by`; purge removes **opaque blob objects**.
  The **trash state** (a trashed flag + who + when, to the millisecond) is **operator-visible operational
  metadata**, exactly as
  listed in the [security model](../../../crosscutting/security-model.md) *(what is encrypted vs. server-visible)* —
  never content, never a key.
- **Reversible vs. irreversible (the security model's posture).** Any-member reversible trash + `deleted_by` audit;
  the irreversible purge is the scheduled **system job** — or, for an item **already in the trash**, a member
  expediting it by hand (cr-0111). A member never purges a **live** item in one step (destruction is trash → purge).
- **Reuses the live-updates substrate — no new propagation.** `bump delete_revision` + the id-set delete-reconcile
  ([ADR 0033](../../../decisions/0033-live-media-updates-revision-polling.md), cr-0107) drop the tile on every open
  gallery; the auto date/place self-heal from the shrunken list.
- **Reuses the blob-reclaim path.** The purge deletes derivative → media → blob rows in FK order and reclaims the
  store objects — the same logic the anonymous share *delete-own* (cr-0102) already runs, minus its identity
  predicate — so no new blob-lifecycle code, only its schedule.
- **Start light.** Soft-delete + retention **is** the security model's mechanism; a single **per-Memory trash tab**,
  no per-item "delete forever" UI, no cross-scope trash. The store isn't freed until the purge — an honest
  trade-off of recoverability, communicated by the retention window.

## What this means for you / Was das für dich bedeutet

**EN —** Remove a photo from a memory and it goes into that memory's trash — out of sight right away, but you can
put it back for 30 days if you change your mind. Only after that is it erased for good, automatically. In a shared
memory anyone can tidy up, and the trash shows who removed what — nothing vanishes without a trace. Everything
stays encrypted: the server only ever flips an "in the trash" marker and, later, deletes scrambled files it can't
read.

**DE —** Nimm ein Foto aus einer Erinnerung, und es wandert in deren Papierkorb — sofort aus dem Blick, aber
30 Tage lang zurückholbar, falls du es dir anders überlegst. Erst danach wird es automatisch endgültig gelöscht.
In einer geteilten Erinnerung kann jedes Mitglied aufräumen, und der Papierkorb zeigt, wer was entfernt hat —
nichts verschwindet spurlos. Alles bleibt verschlüsselt: der Server setzt nur eine „im Papierkorb"-Markierung und
löscht später verschlüsselte Dateien, die er nicht lesen kann.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.media](../enablers/en.memory.media.md) (the blob data layer purged here),
  [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the cover a trashed media may dangle)
- Use-cases: [uc.memory.gallery](uc.memory.gallery.md) (the lightbox the delete lives in),
  [uc.memory.live-updates](uc.memory.live-updates.md) (the `delete_revision` reconcile this drives),
  [uc.memory.cover](uc.memory.cover.md) (the E2 dangling-cover fallback),
  [uc.memory.date](uc.memory.date.md) / [uc.memory.place](uc.memory.place.md) (the `auto` self-heal)
- Cross-cutting: [security model](../../../crosscutting/security-model.md) — *Deletion & erasure* (reversible
  any-member audited trash vs. irreversible system purge) + the server-visible trash-state metadata
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (blob store + purge),
  [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_node` seals content),
  [ADR 0033](../../../decisions/0033-live-media-updates-revision-polling.md) (the revision reconcile)
- Reference impl: the anonymous share *delete-own* (cr-0102) — the blob-reclaim + `NodeItemsRevisions.bump(deleted = true)` (core's item-list revision)
  path the purge parallels. Built: **cr-0110** (contracts → backend → web + the scheduled purge job) + **cr-0111**
  (the member-expedited `purgeMedia` + the Trash-tab redesign — the same `MediaGrid`, batch-select + a 3-dot
  restore/purge menu — and the upload per-file reconcile + the trashed-in-grid tombstone fixes).
