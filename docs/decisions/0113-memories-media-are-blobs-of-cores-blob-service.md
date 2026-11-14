# ADR 0113 — Memories' media are blobs of core's blob service

- Status: Accepted
- Built: yes
- Date: 2026-11-02
- Model: [Applications](../apps/README.md)
- Replaced in part: § Context's "no path adds a derivative later", for derived roles, by ADR 0132 (not built)

## Context

[ADR 0112](0112-an-applications-stored-blobs-are-managed-by-a-core-service.md) gave core one service for the blobs an
application stores. It carries the platform's duties over them: erasure, a report's hold, quota, reclaim, the re-key of
the wraps, and the export of the bytes. That ADR left Memories' media on their own two `veyq_blob` arms
(§ Consequences), "since a derivative's rows are not a receipt's". The owner has since decided that Memories' media move
onto the service too ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 3: the data
management of an application's blobs is core's). This record decides how.

**What stands today** (measured in the tree at `b85c806fa`):

- **Four arms.** `veyq_blob` has four arms (`scripts/validate_blob_reference_arity.py`'s docstring):
  `veyq_media.blob_id` and `veyq_media_derivative.blob_id` are Memories', `veyq_node_attachment.blob_id` holds core's
  covers and banners, and `veyq_application_blob.blob_id` is the service.
- **The Memories arms are in the baseline.** Both were declared in the Memories baseline of the day (`veyq_media`,
  `veyq_media_derivative`). A derivative
  row is (id, media, kind, blob), with no unique key on (media, kind).
- **Memories copies every duty the service has.** It does so in its own implementations of the same seams:
  `MediaUploadCompletion`, `MediaShareGuestWrites`, `MediaUploadAdmission`, `MediaSharedSubtreeContributor`,
  `MediaReportableItems`, `MediaHoldSnapshot`, `MediaNodeRekeyParticipant`, the erasers and `MediaTrashPurge`. These
  live among the 3146 lines in 23 files of `memories/media/` (`wc -l src/backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/*.kt`).

Where a medium differs from a receipt, the code says so:

- **The wrap binds the medium, not the node.** Every `K_blob` wrap is sealed with `blob-key`, which binds
  (scopeId, ownerId, blobId, role) (`src/packages/crypto/src/seal.ts:66`).
  - Memories binds `ownerId` = the media id (`src/web/src/features/memories/media.ts:716-722`, `rekey.ts:53`).
  - The service binds `ownerId` = the node id: its completion refuses anything else
    (`core/applicationblob/ApplicationBlobSeams.kt:78`), and so do the web transport (`core/blob/applicationBlobs.ts:167,203`)
    and core's re-key driver (`core/node/rekey/rekeyScope.ts:288`).
  - The media binding is the one that holds a medium together. Its meta and EXIF are bound to the media id
    (`media-meta`, `media-exif`), and no sealed record names a medium's blob ids. Bound to the node only, the server
    could pair one medium's picture with another's caption, date and place, and the client would not notice.
- **A medium is born whole.** One completion attaches the original, up to two derivatives, the sealed meta and EXIF, and
  the uploader's bucket, all in one transaction and in batches of up to 256 items (`MediaStore.attach`;
  `contracts/api/memories/media.openapi.yaml` `completeMedia`).
  - The derivatives are made in the client at upload (`src/web/src/shared/image/mediaDeriver.ts:317-344`). An image
    gets PREVIEW and THUMBNAIL. A video gets one poster frame stored as both. No path adds a derivative later.
  - The service completes one blob per call (`ApplicationBlobCompleteRequest`).
  - Core's dispatcher refuses a kind that two completions accept (`core/blob/UploadCompletion.kt:102`).
- **A `WRITE_ONLY` guest uploads media.** It seals them under its own `K_up` (`src/web/src/core/sharing/dropKey.ts`), so
  those wraps are not under the node's key and the re-key leaves them alone (`MediaNodeRekeyParticipant.kt:64`). The
  service's guest route takes `READ_WRITE` only (`ApplicationBlobsHandler.kt:142`), and its re-key requires every row's
  wrap (`ApplicationBlobSeams.kt`, `ApplicationBlobRekeyParticipant.commit`).
- **A trashed medium is out of every guest's reach.** The share's blob predicate takes live media only
  (`MediaSharedSubtreeContributor.kt:17,27`). The service's predicate serves released blobs too, because Expenses'
  history reads them (`ApplicationBlobSeams.kt:177`).
- **Media are erased at once, too.** A member can empty a medium from the trash at once (`purgeMedia`), a guest can delete
  its own upload (`MediaShareGuestWrites.deleteOwn`), and the erasure ledger erases a medium again after a restore
  (`MemoriesErasures`). The service erases only through its retention purge.
- **Two trash windows exist.** Media wait out `veyq.media.trash.retention` (`MemoriesMediaConfig.kt`, default P30D). The
  service and node trash wait out `veyq.node.trash.retention` (`core/src/main/resources/application.properties:154`,
  P30D). The privacy notice states only the node's (`core/terms/PrivacyNoticeRenderer.kt:44`).
- **The re-key read is unpaged.** Core's `getNodeRekeyParts` lists every service blob of a node in one response
  (`core/node/rekey/RekeyStore.kt:88-92`). Memories pages its own re-key list instead (`listMediaRekey`, 256 per page).
- **No instance is live.** The owner's dev database holds one medium with two derivatives (the owner's count).

## Decision

**1. A medium stays Memories' record; its original and derivatives become blobs of the service.**

- **What `veyq_media` keeps:** what is behind the node — the sealed meta and EXIF and their revision and editors, the
  bucket, the position, the uploader attribution, and the soft delete.
- **What goes:** `veyq_media` loses `blob_id`, and `veyq_media_derivative` is dropped.
- **Each blob becomes one service row.** Its owner is the media id. Its role is `ORIGINAL`, `THUMBNAIL` or `PREVIEW`;
  a video's poster is its THUMBNAIL and PREVIEW, with no role of its own. Each role allows **one blob per owner**, and a
  link guest may add all three.
- **The row's id is the blob's id.** That id is already client-minted and unique, so no new id goes on the wire.
- **The arms go from four to two:** core's node attachments (around the node) and the service (behind it).
- A Memory's banner and every cover stay attachments (decision 10).

**2. A role is either an item of its own or its owner's content.** This is one declaration on `ApplicationBlobRole`
(`ownItem`). The service reads all five consequences below from it, so an application cannot set them apart.

| | Own item (Expenses' receipt) | Owner's content (a medium's original, thumbnail, preview) |
|---|---|---|
| The wrap binds `ownerId` = | the node — its owner's sealed record names the blob | the owner — nothing else names the blob |
| Completed through | the service's own routes, one blob per call | the owner's write in its application, in that write's transaction (decision 4) |
| Reported, blocked, held as | an `ITEM` of its own, and with its owner | its owner, never alone |
| A guest reaches it after its release | yes (the application's history) | no — released with its owner's end, it is in no share's reach |
| Listed by | the service's node, link and case reads | its application's record reads, which embed the service's rows |

Expenses' roles are own items, and their behaviour does not change. Memories' three roles are owner's content.

**3. Nothing in the crypto changes.**

- **The binding is the one each role already has.** The service stores, re-keys and erases the wrap in the `veyq_blob`
  row, as now. The wrap stays `blob-key` over (scopeId, ownerId, blobId, role). For an own item `ownerId` is the node;
  for owner's content it is the owner. Both are exactly what Expenses and Memories seal today.
- **The wrap's key is the node's key**, or a `WRITE_ONLY` guest's `K_up` for a blob that guest wrote.
- **Byte-identical.** Every existing wrap and every stored byte stays as it is. No purpose, field or suite moves
  (ADR 0079 decision 9), and `contracts/crypto/vectors/v1.json` does not change. Its `blob-key` vector (`seal.sym`, role
  `THUMBNAIL`) pins the encoding, not what `ownerId` names. The `blob-key` row's comment in `seal.ts` already says what
  `ownerId` is for each.
- **Who chooses the binding.** It is the application's (ADR 0079 decision 3: what an application seals is its own).
  Making it a property of the role keeps core naming none of it.
- **Where core opens a wrap, it takes the binding from the server.** Core opens an application's wraps only to re-wrap
  them in a re-key (decision 7), and there it binds with the `ownerId` the server reports per row. A server that lies
  about that id gets a wrap that does not open, so the re-key fails closed. That is a denial, which the server could
  always cause. It is not a substitution.
- **The application's own reads** keep taking `ownerId` from what they address: the medium they list
  ([ADR 0072](0072-a-seal-binds-its-object.md) decision 1).

**4. A medium's upload stays Memories' operation; every blob row in it is written by the service.**

- **The routes stay Memories'.** `completeMedia` (members) and `completeShareMedia` (`READ_WRITE` and `WRITE_ONLY`
  guests) keep their batch and their item: a medium id, the uploads of the original and its derivatives, the sealed meta
  and EXIF, and a member's `originalShared`.
- **The service writes each blob inside Memories' transaction.** Inside `MediaStore.attach`, Memories calls a new
  `ApplicationBlobs.attachIn` for each of its blobs. It joins the caller's transaction and checks, under the node's lock
  the caller took:
  - that the session is bound to (owner, role) by the role's binding;
  - the verified manifest, and spends the session;
  - that the row's id is free and the owner is under its count — a medium declaring one kind twice is refused as
    `upload_mismatch`, which today's code lets through;
  - `guestMayAdd` for a guest writer.

  It then writes the blob row and the service row. A shared original comes in through `SharedBlobs.persist` in the
  same call.
- **The owner is born keeping its blobs.** The owner's state is the caller's to state, and Memories states `KEEPING`:
  the medium is born in the same write as its blobs, so the "nothing written yet" state of ADR 0112 decision 2 never
  arises for a medium.
- **One bump per write.** `attachIn` leaves the node's item revision to its caller, so a medium moves the gallery once,
  as now.
- **Lock order.** It is the service's: Memories' rows (the uploader's primary bucket), the node, the blob rows, then the
  medium, its service rows and the revision.
- **Memories refuses a media id that is a service row's id** (`ApplicationBlobs.anyTaken`), as Expenses does for an entry.
- **The service's own completion and guest route accept only a kind that has an own-item role.** So
  `ApplicationBlobUploadCompletion` and `ApplicationBlobShareGuestWrites` stay out of `MEMORY` and `CHAPTER`, and
  `MediaUploadCompletion` and `MediaShareGuestWrites` remain the one completion and guest writer of those kinds.
  - **Why not one blob per call:** a medium would need three completions and a fourth write for its record. A batch of
    1000 photos would become 4000 requests, and its blobs would sit unkept while the record is missing. The service
    would also have to learn the item's whole shape.
- **The service's guest route does not widen to `WRITE_ONLY`.** A write-only guest still uploads only through Memories'
  route, which admits both write roles through core's guest gate, as it does today. It still never reads:
  - a `WRITE_ONLY` link's shared subtree is empty for the share's blob predicate (`core/sharing/SharingHandler.kt:76`);
  - the service's link reads give it nothing;
  - nothing new is served to it.

**5. A medium's lifecycle is the owner's; the blobs' erasure is the service's.**

- **Trash releases, restore keeps.** A trash releases the medium's blobs with its owner's end (`releaseOwners`). A
  restore keeps them again (`keep`). Both happen in the trash or restore transaction, after the medium's row and the
  service's rows are locked. A medium's blobs are born only in its own completion, so no later completion adds one these
  bulk updates miss.
- **One trash window.** The media trash waits out the node trash retention (`veyq.node.trash.retention`). The keys
  `veyq.media.trash.retention` and `veyq.media.trash.purge.every` go, and `MemoriesMediaConfig` with them.
  - The service's purge and Memories' purge then judge the same instant: the medium's `deleted_at` is its blobs'
    `released_at`.
  - A restore of a medium trashed longer than the retention is refused as if it were already purged, so no medium comes
    back without its bytes.
  - One window is also what the privacy notice already states.
- **Erasing at once.** The service gains `ApplicationBlobErasure.eraseOwners(scopeId, ownerIds)`: an application's immediate
  erase, inside its own write. It removes the rows, puts their blob rows' objects on the reclaim list (ADR 0062), and
  deletes the guest-write records that name them. Memories calls it:
  - when a member empties a medium from the trash at once;
  - when a guest deletes its own upload;
  - when the erasure ledger erases a medium again;
  - when `MediaTrashPurge` erases a medium's row, so that row and blobs go together whichever job reaches them first.
- **What stays Memories'.** The medium's measures (the item is the medium) and its ledger line (`MEDIA`). The objects a
  measure hangs on when a cover or banner still shares one are read before the erase, as now.

**6. Reach, report, hold and quota follow decision 2.**

- **The share's blob predicate.** It is the service's arm alone. A blob of owner's content that was released is not the
  share's, so a trashed medium's bytes stay out of a guest's reach, as `MediaSharedSubtreeContributor` keeps them today.
  `SharedBlobs.persist` asks the same predicate. So a cover or banner can share only a live medium's blob, as now.
- **Report and block.**
  - `MediaReportableItems` asks the service for its medium's blobs (`blobIdsOfOwner`) and held objects
    (`heldObjectsOfOwner`).
  - The service's `ReportableItems` locates only own items, and its `UploaderContent` returns only own items. A
    medium's uploader content is the media ids Memories returns.
  - The service's case read (`listCaseApplicationBlobs`) lists only rows in a role no application declares as its
    owner's content — asked by the role's token, since the held node may be gone; a medium's are Memories' case read's.
  - A block on a medium reaches its blobs through the owner rule `ApplicationBlobs.served` already has.
- **The hold.** The service's contributor already copies by node, by item and by owner. `MediaHoldSnapshot` copies only
  `veyq_media` and the buckets.
- **Quota.** A link's usage is the service's (`ApplicationBlobUploadAdmission`, summing over the writer identity's
  link), and `MediaUploadAdmission` goes. A scope's usage reads `veyq_blob`, which does not change.

**7. Reads keep their wire; the re-key wraps move to core's part.**

- **Listings.** `listNodeMedia`, `listShareMedia` and `listCaseMedia` serve the same rows. Each medium's blob fields and
  derivatives are filled from the service (`ApplicationBlobs.served` for the page's owners). That keeps a constant
  number of queries per page (`MediaListQueryCountSliceTest`).
- **Unchanged:** the web's listing, its worker (ADR 0105), the live delta (ADR 0033), playback (core's presign by blob
  id, ADR 0047), export and import (client-side, through the same listing and completion, ADR 0082), the buckets and
  the derivative pipeline.
- **The re-key.** A medium's wraps are core blob wraps of its node, re-wrapped by core's driver.
  - A new paged read in the service, `listApplicationBlobRekey` (256 per page, by id), serves each blob with its role,
    its binding `ownerId` and its wrap. `getNodeRekeyParts` keeps the attachments only. Unpaged, a Memory of 10,000
    photos would answer 30,000 rows at once.
  - Read and commit both leave out a row whose writer identity holds a wrapped identity key. That row is a `WRITE_ONLY`
    guest's, sealed under `K_up`. This is `MediaRekeyRows`' rule moved into core, which owns the drop key.
  - `listMediaRekey` and `stageMediaRekey` keep the meta and EXIF and lose `blobs`.
  - Memories' participant gives every medium of the node a new position once the node holds a medium. Core's
    participant rewrites their wraps in the same all-or-nothing commit, and an open gallery must fetch them again.

**8. The wire changes, contracts first. No DTO is written by hand.**

- **`media.openapi.yaml`:** `RekeyMedium.blobs` and `MediaRekeyStage.media[].blobs` go. The completion text names the
  per-role count.
- **`blob.openapi.yaml`:**
  - `listApplicationBlobRekey` is added.
  - The service's completion, link and case reads say they serve own-item roles only. The guest route stays
    `READ_WRITE` only.
- **`content.openapi.yaml`:** the `getNodeRekeyParts` `blobs` are the attachments.
- **Unchanged:** `share-media.openapi.yaml`, the export schema and the crypto vectors.

**9. Memories' existing media are discarded, not migrated.** This is the shape of ADR 0112 decision 5.

- **Memories' migration deletes no row.** It refuses to run while `veyq_media` or `veyq_media_derivative` holds a row,
  or while `veyq_hold_row` holds a copy from either table. It does so with CHECK constraints no such row can meet, which
  are added and dropped again — on H2 and MariaDB alike. On an empty database it:
  1. drops `veyq_media_derivative`;
  2. drops `veyq_media`'s `fk_media_blob`;
  3. drops `veyq_media.blob_id`.
- **Why the held copies refuse too:** a held copy keeps the shape it was copied in, and after the move no code reads a
  medium's blob from that shape.
- **The reset happens before the new code starts.** It was run on the commit before the move, one of two ways:
  - **Every medium gone.** In each Memory, delete each medium and empty it from the Memory's trash (`deleteMedia`, then
    `purgeMedia`), or delete and purge the Memory itself (`deleteNode`, `purgeNode`). Then
    `SELECT COUNT(*) FROM veyq_media` and `SELECT COUNT(*) FROM veyq_media_derivative` both answer `0`.
  - **The whole dev instance**, by `./gradlew infra:wipe infra:up`, then seeding again. This is the only way when
    `SELECT COUNT(*) FROM veyq_hold_row WHERE source_table IN ('veyq_media', 'veyq_media_derivative')` is not `0`.
  - A start that refused clears its failed run on the next dev start (`quarkus.flyway.repair-at-start=true`).
  - Never delete the rows in SQL: that would leave their blob rows with no arm and their objects in the store.
  - *Since folded:* before the first release the whole migration history became one baseline per module, so neither
    the migration nor its refusal exists any more, and every dev database is recreated instead
    ([infra/README.md § The migration history starts at three baselines](../../infra/README.md#the-migration-history-starts-at-three-baselines-once-per-dev-database)).
- **Why discard, though the binding would allow a byte-identical move:**
  - No instance is live. The owner's dev database holds one medium.
  - A migration would have to parse each writer out of `created_by` (`user:…` or `share:…`) and answer the old-shape
    holds anyway.
  - The owner has already judged this trade once, for Expenses.

**10. Core's node attachments stay; they do not follow onto the service.**

- **A cover, a banner and a file's content are around a node, not behind it.** Core's `FOLDER` has a cover, and no
  application holds a folder.
- **They have no owner but the node.** No record keeps or releases them.
- **Their one-blob-per-slot rule is what the compare-and-set replace of a cover rests on** (`AttachmentStore`; ADR 0112
  rejected widening it).

Two arms, one on each side of ADR 0079 decision 3's line, is the count this decision aims for.

**11. The operator sees nothing new.**

- **Per blob:** size, role, owner, writer, times and kept or released. It already sees a medium's blob ids, their role
  and its trash time.
- **The service's rows only regroup what the media rows show today.**
- **The `ownItem` flag is code, not data.**

## Consequences

- **Memories shrinks** by `MediaSharedSubtreeContributor`, `MediaUploadAdmission`, `MediaDerivativeEntity`,
  `MemoriesMediaConfig` and the blob halves of `MediaNodeRekeyParticipant`, the erasers, `MediaHoldSnapshot` and
  `MediaReportableItems`. It gains one `ApplicationBlobOwners` (its three roles; it accepts `MEMORY` and `CHAPTER`).
- **The service gains** `ApplicationBlobRole.ownItem`, `attachIn`, `eraseOwners`, the paged re-key read, the `K_up` rule
  and the binding per role. Expenses is unchanged: its roles are own items, the default.
- **Rules a medium did not have:** a medium declaring one derivative kind twice is refused, and a restore after the
  retention is refused.
- **Operator-visible:** `veyq.media.trash.retention` leaves the configuration (`site/src/content/docs/run/configuration.md`).
- **Guards.** Each change gets a bite before it is trusted (a case that must turn it red).
  - **`validate_blob_reference_arity`.**
    - It drops from four arms to two, with the media and derivative entries gone from all four keys.
    - Its schema and backstop keys learn to replay `ALTER TABLE … DROP COLUMN` and `DROP FOREIGN KEY`.
    - Its live twin no longer names a Memories contributor.
    - Bites: a third arm forged in a scratch copy of the tree turns every key red; a migration dropping
      `veyq_media.blob_id` while the entity still declares it turns the entity key red; removing the service
      contributor's `add(row.blobId)` turns the live twin red.
  - **`validate_guard_gaps`.** Its cases for this guard follow; it is run because a validator changed.
  - **`validate_scope_erasure_arity` and `validate_account_erasure_arity`** replay the dropped table and need no change.
    They are run green.
  - **`validate_node_rekey_arity`** stays green, because core's participant assigns `wrappedBlobKey`. It cannot prove
    which rows are reached, so a slice test proves the re-key (`MediaNodeRekeySliceTest`). A commit that misses a
    member medium's THUMBNAIL wrap gets `409 rekey_parts_missing`; one without the `WRITE_ONLY` guest medium's wrap
    applies. Bite: drop the `K_up` rule and the second case goes red.
  - **The share predicate.** A slice test checks that a guest's GET of a trashed medium's blob is a 404
    (`ShareTrashedMediaSliceTest`). Bite: drop the rule for released owner's content.
  - **`ModuleCompositionTest`.**
    - Its partition stores `MEMORY` and `CHAPTER` with Memories, its admission becomes the service's, and its completion
      and guest writer stay Memories'.
    - Its seams lose Memories' contributor and admission and gain Memories' `ApplicationBlobOwners`.
    - Bite: let the service's completion accept `MEMORY`.
  - **`validate_measure_target_erasure`, `validate_node_subtree_erasure` and `validate_store_reclaim_arity`** read
    erasers and calls, so they follow unchanged.
- **Docs.**
  - [Applications](../apps/README.md) § Core's blob service holds two arms and the two forms.
    [Memories](../apps/memories.md) and [en.memory.media](../features/memory/enablers/en.memory.media.md) describe a
    medium's blobs as the service's.
  - [cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md) counts two arms.
  - ADR 0112 carries `- Replaced in part:` for decision 1 (the arm count; a wrap's key and binding are per role) and
    decision 3 (an owner's content is served by its application's routes).
- **Costs accepted:**
  - every medium on the dev instances is gone at the move;
  - one more flag every new role must answer;
  - a re-key read that is now paged for every application.

## Rejected alternatives

- **Bind every service blob to its node.** This would re-seal Memories' wraps under `ownerId` = node. Memories' format
  would change against ADR 0079 decision 9, and the medium would lose the binding that holds its picture to its meta,
  since no sealed record names a medium's blobs (§ Context).
- **Bind every service blob to its owner.** This is one rule, but it re-seals every receipt and resets Expenses again.
  It also takes from Expenses a choice ADR 0079 decision 3 gives it: what it seals is its own.
- **Complete a medium through the service's routes,** one blob per call plus a record write. It costs four requests per
  medium, leaves a window of unkept blobs, and needs the service to learn a group of blobs with sealed payloads, a
  shared original and a bucket.
- **Widen the service's guest route to `WRITE_ONLY`.** Nothing needs it while a medium is completed in Memories' write,
  and every route a write-only guest reaches is one more to prove it never reads.
- **Keep two trash windows.** A restore between the two would bring a medium back without its bytes, or the privacy
  notice would name only one of them.
- **Migrate the media in SQL** (`INSERT … SELECT` into the service's table, byte-identical). This is possible, since the
  binding stays (decision 3). It was not chosen, for decision 9's reasons.
- **Move core's node attachments onto the service too.** Rejected in decision 10.
