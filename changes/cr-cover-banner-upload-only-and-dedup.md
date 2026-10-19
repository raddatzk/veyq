---
id: cr-cover-banner-upload-only-and-dedup
title: A cover and a banner are set only by an upload in their dialog, and an upload of a file the Memory already stores shares its stored object
status: in-progress
satisfies: ["uc.platform.node-cover", "uc.memory.cover", "uc.memory.banner", "uc.memory.upload", "en.memory.media", "en.memory.node-meta"]
adrs: ["0102", "0013", "0062", "0072", "0082", "0093", "0056", "0079"]
invariants:
  - operator-blind - the server compares no content; the client finds an equal file by a hash sealed under the blob's own key
  - a stored object is deleted only when no blob row names it - deleting the gallery medium never removes the cover or banner, and the reverse
  - share view = member view - a guest sees the cover and banner as before; no guest creates a shared row
  - no new seal purpose, no new table naming a storage key
---

## Goal

Owner decision 2026-10-19 (three parts, plus an addition the same day):

1. **Cover and banner are set only by an explicit upload in their own dialogs.** The gallery lightbox's *Set as cover* and
   *Use as banner*, Memories' photo source of core's cover dialog (`cover.extraSource` / `memoryPhotoSource`) and the banner
   dialog's gallery seed go. Reason: set casually from the lightbox, members overwrite each other's choice without noticing.
2. **Dedup, client-side.** An upload into the cover or banner dialog of a file the Memory's gallery already stores does
   not store it again: the slot's blob shares the stored object of the gallery medium's tier the slot shows. **And the
   other way round** (owner addition): a gallery upload of a file a banner already stores shares the banner's object.
   Shared ownership: the object goes only when nothing names it. The operator may infer which medium is the cover or
   banner from the shared object (owner accepted, inbox #505).
3. **The banner's description (alt text) goes**, and a test pins the worst-case sealed Memory meta under the 8192 cap,
   bounding any field that still threatens it.

## Measured substrate (worktree `agent-a3657bc065276ba5d` at `85041e58` = main, clean)

| Fact | Locator | At refine |
|---|---|---|
| No plaintext content hash exists on any medium | `grep -rniE "sha256\|contentHash\|digest" web/src/features/memory web/src/core/blob packages/crypto/src/blobMeta.ts` | only the export manifest and `v1.ts` helpers |
| `dedupToken` is pinned and unused; ADR 0013 § 5 declines dedup | `grep -n 'NOT USED' packages/crypto/src/v1.ts`; `sed -n 211,227p docs/decisions/0013-*.md` | declined |
| The listed media meta is capped at 512 base64 (the place name clamped to fit); a blob's `blob-meta` at 1024 plaintext bytes, worst case 821 | `grep -n 'maxLength: 512' contracts/api/memories/media.openapi.yaml`; `grep -n 'maxBytes\|821' packages/crypto/src/blobMeta.ts` | 512 / 1024 |
| `COVER_SPEC` = `BANNER_IMAGE_SPEC` = `TIER_SPECS.PREVIEW` (2048 px, 0.85); a video's poster is drawn at the PREVIEW spec and stored as THUMBNAIL and PREVIEW | `grep -n 'COVER_SPEC\|BANNER_IMAGE_SPEC' web/src/core/node/cover/coverWrite.ts web/src/features/memory/banner/bannerWrite.ts`; `grep -n 'TIER_SPECS' web/src/shared/image/mediaDeriver.ts` | equal |
| A chunk frame binds only its index and total under `K_blob`; the `K_blob` wrap binds scope, owner, blob id, role | `grep -n 'export function sealFrame' packages/crypto/src/v1.ts`; `grep -n "'blob-key'" packages/crypto/src/seal.ts` | frames carry no blob id |
| A cover's and a banner's `wrappedBlobKey` live in the sealed node meta, not on a server row the client reads | `grep -n 'wrappedBlobKey' web/src/core/node/cover/coverModel.ts web/src/features/memory/banner/bannerModel.ts` | meta |
| A blob row has no owner or role; three arms name blob rows; `ux_attachment_blob` keeps one attachment per row | `sed -n 314,325p backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`; `python3 scripts/validate_blob_reference_arity.py` | three arms |
| The per-row choke point records a reclaim unconditionally; the sweep skips only held keys | `grep -n 'fun deleteBlobRow' -A4 backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/BlobUploadStore.kt`; `grep -n 'fun due' -A6 backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreReclaim.kt` | unconditional |
| Callers of the choke point (each removes its reference first) and the one set-based eraser | `grep -rn 'deleteBlobRow\|BlobEntity.delete' --include='*.kt' backend \| grep -v /test/` | attach ×2, detach, attachment subtree eraser, `purgeMediaRows`; `ScopePurge` by scope |
| Tenant quota sums `veyq_blob.byte_size` per row | `grep -n 'SUM(b.byte_size)' backend/core/src/main/kotlin/me/raddatz/veyq/core/quota/StorageUsage.kt` | per row |
| A content block decides by blob id | `grep -n 'fun blobBlocked' -A6 backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/block/ModerationBlocks.kt` | by id |
| The reconcile's claims, the backup's `blob_index` and the ledger's `STORAGE_KEY` read storage keys, not blob ids | `grep -n 'CLAIM_TABLES' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreReconcileStore.kt`; `grep -n 'def blob_index' -A18 scripts/instance_backup.py`; `grep -n 'STORAGE_KEY' backend/core/src/main/kotlin/me/raddatz/veyq/core/backup/ErasureLedger.kt` | by key, `UNION` dedups |
| The Memory's manual place name is unbounded in the meta (the export bounds it at 1024) | `grep -n 'maxlength' web/src/features/memory/components/MemoryLocationEditor.vue`; `grep -n 'place:' web/src/features/memory/exportImport.ts` | no bound |
| Sites of the removed sources | `grep -rn 'extraSource\|memoryPhotoSource\|coverFromPhoto\|setFromPhoto\|useAsBanner\|canSetBanner\|canSetCover\|bannerMediaId\|coverMediaId\|loadMemoryPhotoCover' web/src e2e` | lightbox, detail, host, register, export, e2e seeds |
| Sites of the banner's description | `grep -rnE "\balt\b\|boundAlt\|MAX_BANNER_ALT\|bannerAlt\|descriptionHint" web/src/features/memory contracts/export e2e/tests/features/memory-banner.spec.ts` | model, dialog, frame, export, schema, L1 |

## Decisions this CR takes

1. **A share is a second blob row on the same stored object — never a second reference to one row.** The new row has a
   fresh client-minted id, its own `K_blob` wrap bound to its own owner and role (ADR 0072, unchanged — the same `K_blob`
   wrapped again, no new seal purpose) and its own `blob-meta`; its `storage_key`, `chunk_count`, `byte_size` and
   `manifest` are copied by the server from the source row. Chunk frames bind only index and total, so the same
   ciphertext opens under either row. Every arm keeps exactly one reference per row (`ux_attachment_blob`, the arity
   guard, the link quota's terms and ADR 0082's "one reference per blob" stand); what is shared is the **object**, keyed by
   `storage_key`, which every store-level reader already keys on. Rejected: a second reference to one row (needs a wrap
   per reference on every arm, drops `ux_attachment_blob`, double-counts the link quota).
2. **Who may share what.** A member who may write the scope, through the cover's, the banner's and the gallery's own
   writes (no new operation). The source row must be in the scope and be named by a **live** reference **on the same
   node**: a live medium's original or tier of that node, or an attachment of that node; and it must not be blocked.
   Otherwise `422 shared_source_invalid`, nothing written. The check is a core seam each arm answers
   (`BlobReferenceContributor`: core's for attachments, Memories' for media), so core names no application table. No link
   role gets the shared form; a guest's upload is never deduplicated.
3. **The object is reclaimed when the last row naming it goes — two layers, each bitten with the other held off.**
   (a) The per-row choke point (`BlobUploadStore.deleteBlobRow`) deletes the row and records the reclaim **only when no other
   `veyq_blob` row names the key**. (b) The reclaim sweep's selection (`StoreReclaimStore.due`) skips a key a `veyq_blob` row
   still names, as it skips a held key — so an eraser written later that records a shared key directly cannot delete a live
   object. `ScopePurge` records each key once. Quota counts a stored object once (`SUM` over distinct keys). A content block
   on a row blocks every row naming the same object (`blobBlocked` resolves the key's rows). The reconcile's claim tables,
   the backup's `blob_index` (`UNION`), its e2e decoy and the ledger's `STORAGE_KEY` lines need no change: they key on
   `storage_key`, and a line is written only when the sweep really deleted the object. `MeasureTargets` names no blob table.
4. **Where the content hash lives: the original's `blob-meta`** — `{v, type, name?, modifiedAt?, sha256?}`, `sha256` the
   43-character base64url SHA-256 of the plaintext, sealed under the blob's own `K_blob`. The listed media meta has no room
   (512 base64, already clamping the place name); `blob-meta` has (worst case 821 → 876 of 1024 bytes), is the blob's own
   fact, and is served with every listed original. Written for a gallery original and for a video banner's `BANNER` blob
   (the two kinds of slot that hold the source bytes); copied into a shared row's `blob-meta`. **New uploads only:** a medium
   uploaded before this CR has no hash and is never matched. Cost: the client reads each picked original once more to hash
   it before it is planned (the `blob-meta` is written at declare), streamed, never held whole. The `packages/crypto` codec
   changes (no new purpose, no new vector: the pinned plaintext stays valid), so its mutation gate runs.
5. **Per slot, which directions share (measured above):**

   | Slot holds | upload into the slot matches a gallery original | gallery upload matches the slot |
   |---|---|---|
   | `COVER` — the picked image derived to 2048/0.85 | shares the medium's **PREVIEW** (the same spec of the same original: what the slot shows) | **no** — the slot holds no original, and a medium's tiers are derived from its original in one pass |
   | `BANNER` image — derived 2048/0.85 | shares the medium's **PREVIEW** | **no**, as the cover |
   | `BANNER` video — the original as it is | shares the medium's **ORIGINAL** | **yes** — the medium's ORIGINAL shares `BANNER`'s object; its posters are derived as always |
   | `BANNER_POSTER` — the first frame at 2048/0.85 | shares the video medium's **PREVIEW** (its poster); none → derived and uploaded | **no** (derived) |

   A match is: same sealed size (`byteSize` of the plaintext's frames, compared before any hash) and equal `sha256`. A
   matched medium without the needed tier is no match (the upload runs as before). Cover dedup exists only for kinds with a
   gallery: core's dialog asks the kind (`KindCover.findStored`, Memories for MEMORY and CHAPTER, against that node's own
   media); a folder or a drop box has none — a Depot file stores an original and a cover a derived JPEG, never the same object.
6. **The removed sources go entirely**, readers included: `extraSource`, `memoryPhotoSource`, Memories' `cover.load`
   (the `media` cover source), the lightbox buttons and their state, the banner dialog's `seed`, the banner's `origin`. The
   delete confirmation of ADR 0102 decision 7 stays, keyed on the shared object instead of `origin`: deleting a medium whose
   blob shares its object with the banner's says the banner stays and offers to remove it too. **Not migrated (dev data):**
   a stored `media` cover shows the kind's icon (core's E4 for a source it cannot open); a stored banner's `origin` and
   `alt` are ignored and preserved by other writers. The export no longer writes a cover's or banner's `mediaIndex` nor the
   banner's `alt`; the schema drops them (an older archive's cover by `mediaIndex` is not restored).
7. **The description goes; the place name is bounded.** The worst-case meta (100 participants, an uploaded cover, a range
   date, a video banner with poster, type and `sha256`, and a place) is a unit test against 8192; the manual place name is
   the field that still threatens it, so it is bounded at **200 code points** (the editor's `maxlength`, `normalizeLocation`
   and the import clamp), and the test seals the place at that bound in 4-byte characters.
8. **A copy and an import do not keep the sharing** — they read each blob and upload it as its own (as before); stated in
   uc.platform.copy-node. Not this CR.

## Inventory

| Surface | What | Locator |
|---|---|---|
| contracts | `SharedBlob` schema; `setNodeCover`/`setMemoryBanner` take `shared`/`posterShared` beside the upload ids; `completeMedia` item takes `originalShared`; `shared_source_invalid`; export schema drops `alt`, both `mediaIndex` | `grep -n 'uploadId\|originalUploadId' contracts/api/content.openapi.yaml contracts/api/memories/*.yaml` |
| crypto | `blob-meta` `sha256?` in the codec, its bound and the vector note's layout sentence | `packages/crypto/src/blobMeta.ts`, `contracts/crypto/vectors/v1.json` note |
| backend | shared row creation; `deleteBlobRow` + `due` + `ScopePurge` + quota + `blobBlocked`; cover, banner and media completions; the contributor seam | the substrate table's commands |
| web | removals (decision 6), hashing, `findStored`, the shared writes, the gallery's reverse match, `alt` removal, place bound, budget test, L1 spec edits (not run) | the substrate table's last two rows |
| docs | ADR 0102, 0013 § 5, 0082/0062 one-reference wording, uc.platform.node-cover, uc.memory.cover, uc.memory.banner, uc.memory.upload, en.memory.media, security-model, site Use pages EN+DE (add-a-banner, set-a-cover), site security pages naming dedup, inbox #505 + a new row, coverage | `grep -rln -i 'dedup\|Set as cover\|Use as banner\|copied, never referenced' docs site/src/content` |

## Phases

1. **Contracts + crypto.** Done when: `npm run generate:api` (web), `packages/crypto` `npm run check` and
   `npm run mutation -- --force` (exit code in the commit), the contracts guards green. Predicted reds: the backend handlers
   and web callers that build the changed requests compile against optional upload ids (fixed in their phases).
2. **Backend.** Decisions 1–3 and the three writes. Bites (slice tests): a shared row names the source's object, nothing
   uploaded; a shared object survives purging the medium and survives removing the cover/banner, and goes with the last row
   (one reclaim record); the sweep skips a recorded key a row names (with the choke point's check held off) and the choke
   point records nothing (with the sweep's skip held off); quota counts once; a block on the medium blocks the cover's row;
   a source on another node, trashed or blocked is refused; a guest cannot share. Done when: `./gradlew check`.
3. **Web.** Decisions 4–7 on the client. Bites (unit): a match writes the shared form and uploads nothing; no match uploads;
   a size-equal file with another hash uploads; the reverse match completes the medium with `originalShared`; the budget.
   Done when: `npm run check` (web), L1 specs edited, not run.
4. **Docs + coverage.** Done when: every `scripts/validate_*.py` green (`validate_guard_gaps.py` only if a validator changed).

Not run (owner's test rule 2026-10-19): L1, web-e2e, api-fuzz, MariaDB races, any docker stack. So the browser pass of the
dialogs is owed (named in uc.memory.banner's status).

## Definition of done

The four phases' checks green and recorded; this CR deleted after its lessons moved (decision text into ADR 0102 / 0013 and
the use-cases, the two-layer rule into the KDoc of `deleteBlobRow` and `due`).

## Retrospective

(filled at the end)
