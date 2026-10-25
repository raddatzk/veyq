package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.MemberBlobRoles
import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.blob.WindowRequest
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager

/**
 * The role a member uploads an Expenses blob in, for core's member start (inbox #366): a receipt's bytes, and its cap —
 * 10 MiB of plaintext, held on every start of the role, a member's and a guest's ([ExpensesBlobRoles]).
 */
@ApplicationScoped
class ExpensesMemberBlobRoles : MemberBlobRoles {
    override fun roles(): Set<String> = setOf(ExpensesBlobRoles.RECEIPT)

    override fun application(): String = CapabilityIds.EXPENSES

    override fun maxBytes(role: String): Long? = if (role == ExpensesBlobRoles.RECEIPT) ExpensesBlobRoles.RECEIPT_MAX_SEALED_BYTES else null
}

/**
 * The Expenses half of an upload's completion (ADR 0108 decision 7): a verified upload becomes one receipt of an entry of
 * a Pot — its blob row and its receipt row, in [PotReceiptStore.attach]'s one transaction. Core's dispatcher
 * (`UploadCompletions`) picked this implementation by the node's kind; the member path ([ReceiptsHandler.complete]) and
 * a guest's ([ReceiptsHandler.completeShared]) reach it the same way.
 *
 * Core's item is generic: Expenses reads its [CompleteItem.ownerId] as the Pot — the owner every receipt's wrap is bound
 * to — its derivatives as none, and its payloads [RECEIPT_ID], [ENTRY_ID] and [NODE_EPOCH] (plain ids and a counter,
 * never a seal). Like Memories' media completion it uses no `AttachmentStore` — core's attachments hold one blob per
 * named slot — so it asks the session's binding itself ([me.raddatz.veyq.core.blob.UploadSessionEntity.boundTo]).
 */
@ApplicationScoped
class ReceiptUploadCompletion(
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val store: PotReceiptStore,
) : UploadCompletion {

    override fun accepts(nodeKind: String): Boolean = nodeKind == ExpensesNodeKinds.POT

    override fun complete(
        scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean,
        attached: (objectId: String) -> Unit,
    ): CompleteItemResult {
        // A receipt is one blob, uploaded: an item declaring derivatives or a shared original is none (only reachable by
        // a future caller of the seam — the Expenses contract has neither field).
        if (item.derivatives.isNotEmpty() || item.originalShared != null) return failed("upload_mismatch")
        val uploadId = item.originalUploadId ?: return failed("upload_not_found")
        val receiptId = checkNotNull(item.payloads[RECEIPT_ID]) { "Expenses' item() always names the receipt" }
        val entryId = checkNotNull(item.payloads[ENTRY_ID]) { "Expenses' item() always names the entry" }
        val nodeEpoch = checkNotNull(item.payloads[NODE_EPOCH]?.toIntOrNull()) { "Expenses' item() always states the Pot's epoch" }
        val session = blobs.findSession(scopeId, uploadId, opener) ?: return failed("upload_not_found")
        // The wrap is sealed bound to (blob, owner, role): a session started for another node or in another role would
        // land a receipt nobody can open here.
        if (item.ownerId != node.id || !session.boundTo(node.id, ExpensesBlobRoles.RECEIPT)) return failed("upload_mismatch")
        val manifest = when (val v = uploads.verifyBlob(session)) {
            is BlobVerify.Ok -> v.manifest
            BlobVerify.Budget -> {
                uploads.purge(listOf(session))
                return failed("budget_exceeded")
            }
        }
        val writer = when (opener) {
            is UploadOpener.Member -> PrincipalKey.User(opener.userId)
            is UploadOpener.Contributor -> PrincipalKey.Share(opener.shareIdentityId)
        }
        return when (val r = store.attach(scopeId, node.id, nodeEpoch, receiptId, entryId, session, manifest, writer, admitted, attached)) {
            is ReceiptAttach.Attached -> CompleteItemResult(r.receipt.blobId, r.receipt.id, null)
            // A gate refused under the lock reads as a gone upload, as Memories and Depot answer it (the session stays open).
            ReceiptAttach.NotAdmitted -> failed("upload_not_found")
            ReceiptAttach.IdTaken -> failed("id_taken")
            ReceiptAttach.NotFound -> failed("node_not_found")
            is ReceiptAttach.EpochRefused -> failed(r.code)
        }
    }

    private fun failed(code: String) = CompleteItemResult(null, null, code)

    companion object {
        /** The [CompleteItem.payloads] key of the client-minted receipt id. */
        const val RECEIPT_ID = "receiptId"

        /** The [CompleteItem.payloads] key of the record the receipt is kept with. */
        const val ENTRY_ID = "entryId"

        /** The [CompleteItem.payloads] key of the Pot's epoch as the caller read it (ADR 0109 point 6). */
        const val NODE_EPOCH = "nodeEpoch"

        /** Core's generic item for one receipt of the Pot [potId]: the Pot is the `ownerId` the wrap is bound to. */
        fun item(potId: String, uploadId: String, receiptId: String, entryId: String, nodeEpoch: Int): CompleteItem = CompleteItem(
            ownerId = potId, nodeId = potId, originalUploadId = uploadId, derivatives = emptyList(),
            payloads = mapOf(RECEIPT_ID to receiptId, ENTRY_ID to entryId, NODE_EPOCH to nodeEpoch.toString()),
        )
    }
}

/**
 * The Expenses half of a guest's upload on a share (ADR 0108 decision 6): a `READ_WRITE` guest of a link on a Pot, or on
 * a folder above one, uploads a receipt into the Pot — core's guest transport forwarded to core's generic upload, as
 * Memories' and Depot's do; what the upload becomes is [ReceiptsHandler.completeShared], through core's completion
 * dispatcher to [ReceiptUploadCompletion]. A guest's receipt names its identity, which [ExpensesGuestIdentityUsage]
 * (`me.raddatz.veyq.expenses.pot`) counts while a receipt names it.
 */
@ApplicationScoped
class ExpensesShareGuestWrites(private val uploads: BlobUploadHandler) : ShareGuestWrites {

    override fun supports(nodeKind: String): Boolean = nodeKind == ExpensesNodeKinds.POT

    // A Pot takes receipts; nothing else is a guest's to upload into it.
    override fun admitsGuestRole(role: String): Boolean = role == ExpensesBlobRoles.RECEIPT

    override fun start(
        scopeId: String, shareId: String, targetKind: String, identityId: String, declares: List<UploadDeclare>, domain: String?,
    ): List<UploadItemResult> =
        uploads.startForLink(scopeId, shareId, targetKind, identityId, declares, domain)

    override fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult> =
        uploads.windowForScope(scopeId, UploadOpener.Contributor(identityId), requests, domain)
}

/**
 * The Expenses half of a link's usage (ADR 0073 §4): the blob of every receipt the link's identities completed — in any
 * Pot of its subtree, dropped and deleted ones included until the purge (trash is still in the store, ADR 0073 §2). A
 * receipt a member completed is not the link's, and neither is another link's. Core adds the link's open sessions and
 * its node attachments, and every other application's term (`StorageQuota.linkUsage`).
 */
@ApplicationScoped
class ExpensesUploadAdmission(private val em: EntityManager) : UploadAdmission {

    override fun supports(nodeKind: String): Boolean = nodeKind == ExpensesNodeKinds.POT

    override fun linkBlobUsage(shareId: String): Long =
        (
            em.createNativeQuery(
                """
                SELECT COALESCE((SELECT SUM(b.byte_size) FROM veyq_blob b
                       JOIN veyq_expenses_receipt r ON r.blob_id = b.id
                       JOIN veyq_share_identity i ON i.id = r.writer_identity_id
                      WHERE i.share_id = :key), 0)
                """.trimIndent(),
            ).setParameter("key", shareId).singleResult as Number
            ).toLong()
}

/**
 * The receipts' half of what a share's blob GET is authorized against (the fourth `veyq_blob` arm,
 * `validate_blob_reference_arity.py`): every receipt of a live Pot of the subtree — dropped ones and a deleted entry's
 * too, which a guest still reads in the Pot's history and its deleted entries until the purge. The listing a guest is
 * served is [ReceiptsHandler.listShared], a separate read with its own live predicate on the Pot (see
 * [SharedSubtreeContributor]). A content block's reach is core's (`ModerationBlocks.blobBlocked` asks this arm for a
 * blocked node, and [me.raddatz.veyq.expenses.moderation.PotReportableItems] for a blocked entry or receipt).
 */
@ApplicationScoped
class ReceiptSharedSubtreeContributor : SharedSubtreeContributor {

    override fun authorizedBlobIds(scopeId: String, liveNodeIds: List<String>): Set<String> {
        if (liveNodeIds.isEmpty()) return emptySet()
        val ids = HashSet<String>()
        liveNodeIds.chunked(500).forEach { chunk ->
            PotReceiptEntity.list("scopeId = ?1 and nodeId in ?2", scopeId, chunk).forEach { receipt -> ids.add(receipt.blobId) }
        }
        return ids
    }

    /** The set's predicate for one blob: a receipt of that Pot names it. */
    override fun holds(scopeId: String, liveNodeId: String, blobId: String): Boolean =
        PotReceiptEntity.count("scopeId = ?1 and nodeId = ?2 and blobId = ?3", scopeId, liveNodeId, blobId) > 0
}
