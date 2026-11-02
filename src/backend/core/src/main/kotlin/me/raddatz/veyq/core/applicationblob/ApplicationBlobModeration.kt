package me.raddatz.veyq.core.applicationblob

import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.UploadedItems
import me.raddatz.veyq.core.moderation.block.Uploader
import me.raddatz.veyq.core.moderation.block.UploaderContent
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.hold.HeldUploaders
import me.raddatz.veyq.core.moderation.hold.HoldSnapshot
import me.raddatz.veyq.core.moderation.hold.HoldSnapshotContributor
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.report.ReportableItem
import me.raddatz.veyq.core.moderation.report.ReportableItems
import me.raddatz.veyq.core.node.NodeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/**
 * An application blob as a reportable item on its own (uc.moderation.report, ADR 0112 decision 1: a blob is an `ITEM`;
 * Expenses' receipt, owner 2026-10-23): its row, by its id; a block refuses its one blob. Its owner — the application's
 * record — is the application's item, never this one's: the application's `ReportableItems` locates it and asks the
 * service for its blobs ([ApplicationBlobs.blobIdsOfOwner]), so a report of a record reaches its blobs and a block of it
 * refuses them. An application blob's id is no record's id: the service refuses one an application names
 * ([ApplicationBlobOwners.admit]), and an application a record id the service holds ([ApplicationBlobs.anyTaken]).
 *
 * A block or a lift moves the node's list once ([blockChanged]), so an open view re-reads the blobs with their new
 * `blockedByOperator`; a block on a blob ends with its purge.
 */
@ApplicationScoped
class ApplicationBlobReportableItems(private val items: NodeItemsRevisions, private val service: ApplicationBlobs) : ReportableItems {
    /** A blob that is an item of its own; an owner's content is reported with its owner, never alone (ADR 0113). */
    override fun locate(itemId: String): ReportableItem? {
        val row = ApplicationBlobEntity.findById(itemId) ?: return null
        val kind = NodeEntity.findById(row.nodeId)?.kind ?: return null
        return if (service.isOwnItem(kind, row.role)) ReportableItem(row.scopeId, row.nodeId) else null
    }

    /** The blob's own — released or not (a content block refuses it either way). */
    override fun blobIdsOf(itemId: String): Set<String> = ApplicationBlobEntity.findById(itemId)?.let { setOf(it.blobId) } ?: emptySet()

    @Transactional
    override fun blockChanged(itemId: String) {
        val row = ApplicationBlobEntity.findById(itemId) ?: return
        items.bump(row.scopeId, row.nodeId)
    }

    /** The row, locked as its erasers lock it (`FOR UPDATE`). */
    override fun lockExisting(itemId: String): Boolean =
        ApplicationBlobEntity.find("id", itemId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult() != null

    /** The held blob's stored objects, through its held blob row. */
    override fun heldObjectsOf(itemId: String, held: List<HeldRow>): Set<String> = ApplicationBlobs.heldObjects(held) { it.sourceId == itemId }
}

/**
 * The service's rows of a hold (en.moderation.hold): every application blob of every node of a held subtree (a trashed
 * node's too — still the space's until purged); for a reported item, the blob itself or every blob the reported record
 * holds as their owner (Expenses: a reported entry's receipts); for an uploader's content, every blob it completed and
 * every blob of the records it wrote (a moderator's `UPLOADER_CONTENT` step). A reported link or name holds none. Every
 * row's blob is named, so core copies its row and pins its storage key — the hold copies, it never blocks (ADR 0093). Every
 * guest identity a copied row names as its writer is copied by core. Phase 160: after the applications' own (Memories'
 * media 100, Expenses' 150), before core's (200); it reads and copies only.
 */
@ApplicationScoped
class ApplicationBlobHoldSnapshot : HoldSnapshotContributor {
    override val phase = 160

    override fun contribute(target: HoldTarget, snapshot: HoldSnapshot) {
        val rows = when (target) {
            is HoldTarget.Subtree -> snapshot.copy(ApplicationBlobs.TABLE, "node_id", target.nodeIds)
            is HoldTarget.Item -> snapshot.copy(ApplicationBlobs.TABLE, "id", idsOf(listOf(target.itemId)))
            is HoldTarget.Uploads -> snapshot.copy(ApplicationBlobs.TABLE, "id", idsOf(target.itemIds))
            is HoldTarget.Link, is HoldTarget.Name -> return
        }
        rows.forEach {
            snapshot.blob(it["blob_id"])
            snapshot.identity(it["writer_identity_id"])
        }
    }

    /** The rows [itemIds] name: a blob itself, or every blob a record of them holds. */
    private fun idsOf(itemIds: Collection<String>): List<String> =
        itemIds.distinct().chunked(ApplicationBlobs.CHUNK)
            .flatMap { ApplicationBlobEntity.list("id in ?1 or ownerId in ?1", it) }.map { it.id }.distinct()
}

/**
 * The service's half of [UploaderContent]: every application blob of the space the uploader completed — a member by its
 * account, a guest by its link identity — kept, released and never kept alike: the content a writer put there, each an
 * item a block or a hold reaches. The records an uploader wrote are its application's half.
 */
@ApplicationScoped
class ApplicationBlobUploaderContent(private val service: ApplicationBlobs) : UploaderContent {
    /** Its items of their own only: an owner's content is reached through its owner, which its application returns. */
    override fun of(scopeId: String, uploader: Uploader): UploadedItems {
        val column = if (uploader.guest) "writerIdentityId" else "writerUserId"
        val rows = ApplicationBlobEntity.list("scopeId = ?1 and $column = ?2", scopeId, uploader.id)
        val kinds = service.kindsOf(rows.map { it.nodeId })
        val ids = rows.filter { r -> kinds[r.nodeId]?.let { service.isOwnItem(it, r.role) } == true }.mapTo(LinkedHashSet()) { it.id }
        return UploadedItems(emptySet(), ids)
    }
}

/** The service's half of [HeldUploaders]: the members among the writers of the application blobs a hold copied. */
@ApplicationScoped
class ApplicationBlobHeldUploaders : HeldUploaders {
    override fun membersAmong(rows: List<HeldRow>): Set<String> =
        rows.filter { it.table == ApplicationBlobs.TABLE }.mapNotNullTo(LinkedHashSet()) { it["writer_user_id"] }
}
