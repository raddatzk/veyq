package me.raddatz.veyq.core.applicationblob

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.StoredObjectLocks
import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.blob.WindowRequest
import me.raddatz.veyq.core.identity.AccountEraser
import me.raddatz.veyq.core.identity.ColumnFate
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.identity.kept
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.node.rekey.NodeRekeyParticipant
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Value
import me.raddatz.veyq.core.node.rekey.RekeyStore
import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.core.scope.ScopeEraser
import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/**
 * The service's half of an upload's completion (ADR 0112 decision 1): a verified upload becomes one application blob —
 * its blob row and its service row, in [ApplicationBlobs.attach]'s one transaction. Core's dispatcher
 * (`UploadCompletions`) picked this implementation by the node's kind: every kind an [ApplicationBlobOwners] accepts
 * that stores an item of its own (an owner's content is completed in its owner's write, [ApplicationBlobs.attachIn]).
 * The member path ([ApplicationBlobsHandler.complete]) and a guest's ([ApplicationBlobsHandler.completeShared]) reach it
 * the same way.
 *
 * Core's item is generic: the service reads its [CompleteItem.ownerId] as the node — the owner every blob's wrap is
 * bound to — its derivatives as none, and its payloads [ID], [OWNER], [ROLE] and [NODE_EPOCH] (plain ids, a token and a
 * counter, never a seal). Like Memories' media completion it uses no `AttachmentStore` — core's attachments hold one
 * blob per named slot — so it asks the session's binding itself ([me.raddatz.veyq.core.blob.UploadSessionEntity.boundTo]).
 */
@ApplicationScoped
class ApplicationBlobUploadCompletion(
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val service: ApplicationBlobs,
) : UploadCompletion {

    // A kind whose roles are all its owners' content completes them in its own write (ADR 0113 decision 4): its
    // application's completion accepts the kind, and two acceptors would make core's dispatcher refuse it.
    override fun accepts(nodeKind: String): Boolean = service.storesItems(nodeKind)

    override fun complete(
        scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean,
        attached: (objectId: String) -> Unit,
    ): CompleteItemResult {
        // An application blob is one blob, uploaded: an item declaring derivatives or a shared original is none (only
        // reachable by a future caller of the seam — the service's contract has neither field).
        if (item.derivatives.isNotEmpty() || item.originalShared != null) return failed("upload_mismatch")
        val uploadId = item.originalUploadId ?: return failed("upload_not_found")
        val id = checkNotNull(item.payloads[ID]) { "the service's item() always names the blob" }
        val owner = checkNotNull(item.payloads[OWNER]) { "the service's item() always names the owner" }
        val role = checkNotNull(item.payloads[ROLE]) { "the service's item() always names the role" }
        val nodeEpoch = checkNotNull(item.payloads[NODE_EPOCH]?.toIntOrNull()) { "the service's item() always states the node's epoch" }
        // A role the node's application does not store through the service would land a blob nothing reads; an owner's
        // content is completed only in its owner's write (ADR 0113 decision 4).
        if (!service.isOwnItem(node.kind, role)) return failed("upload_mismatch")
        val session = blobs.findSession(scopeId, uploadId, opener) ?: return failed("upload_not_found")
        // The wrap is sealed bound to (blob, owner, role): a session started for another node or in another role would
        // land a blob nobody can open here.
        if (item.ownerId != node.id || !session.boundTo(node.id, role)) return failed("upload_mismatch")
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
        return when (val r = service.attach(scopeId, node.id, nodeEpoch, id, owner, role, session, manifest, writer, admitted, attached)) {
            is BlobAttach.Attached -> CompleteItemResult(r.row.blobId, r.row.id, null)
            // A gate refused under the lock reads as a gone upload, as Memories and Depot answer it (the session stays open).
            BlobAttach.NotAdmitted -> failed("upload_not_found")
            BlobAttach.IdTaken -> failed("id_taken")
            BlobAttach.TooMany -> failed(TOO_MANY)
            BlobAttach.NotFound -> failed("node_not_found")
            is BlobAttach.EpochRefused -> failed(r.code)
        }
    }

    private fun failed(code: String) = CompleteItemResult(null, null, code)

    companion object {
        /** The [CompleteItem.payloads] key of the client-minted id. */
        const val ID = "id"

        /** The [CompleteItem.payloads] key of the application's record that holds the blob. */
        const val OWNER = "ownerId"

        /** The [CompleteItem.payloads] key of the blob's role. */
        const val ROLE = "role"

        /** The [CompleteItem.payloads] key of the node's epoch as the caller read it (ADR 0109 point 6). */
        const val NODE_EPOCH = "nodeEpoch"

        /** The error of a completion past its owner's count. */
        const val TOO_MANY = "too_many_blobs"

        /** Core's generic item for one blob of the node [nodeId]: the node is the `ownerId` the wrap is bound to. */
        fun item(nodeId: String, uploadId: String, id: String, owner: String, role: String, nodeEpoch: Int): CompleteItem = CompleteItem(
            ownerId = nodeId, nodeId = nodeId, originalUploadId = uploadId, derivatives = emptyList(),
            payloads = mapOf(ID to id, OWNER to owner, ROLE to role, NODE_EPOCH to nodeEpoch.toString()),
        )
    }
}

/**
 * The service's half of a guest's upload on a share: a `READ_WRITE` guest of a link on a node whose application stores
 * blobs through the service, or on a folder above one, uploads a blob in a role its application lets a guest add
 * ([ApplicationBlobRole.guestMayAdd]) — core's guest transport forwarded to core's generic upload; what the upload becomes
 * is [ApplicationBlobsHandler.completeShared], through core's completion dispatcher to [ApplicationBlobUploadCompletion].
 * A guest's blob names its identity, which [ApplicationBlobGuestIdentityUsage] counts while a blob names it.
 */
@ApplicationScoped
class ApplicationBlobShareGuestWrites(private val uploads: BlobUploadHandler, private val service: ApplicationBlobs) : ShareGuestWrites {

    override fun supports(nodeKind: String): Boolean =
        service.ownersOf(nodeKind)?.roles()?.values?.any { it.guestMayAdd && it.ownItem } == true

    /** Some application storing through the service lets a guest add [role]; the completion asks again for the node's own. */
    override fun admitsGuestRole(role: String): Boolean = service.anyGuestRole(role)

    override fun start(
        scopeId: String, shareId: String, targetKind: String, identityId: String, declares: List<UploadDeclare>, domain: String?,
    ): List<UploadItemResult> =
        uploads.startForLink(scopeId, shareId, targetKind, identityId, declares, domain)

    override fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult> =
        uploads.windowForScope(scopeId, UploadOpener.Contributor(identityId), requests, domain)
}

/**
 * The service's half of a link's usage (ADR 0073 §4): the blob of every application blob the link's identities completed
 * — in any node of its subtree, released and trashed ones included until the purge (trash is still in the store, ADR 0073
 * §2). A blob a member completed is not the link's, and neither is another link's. Core adds the link's open sessions and
 * its node attachments, and every other application's term (`StorageQuota.linkUsage`).
 */
@ApplicationScoped
class ApplicationBlobUploadAdmission(private val em: EntityManager, private val service: ApplicationBlobs) : UploadAdmission {

    override fun supports(nodeKind: String): Boolean = service.stores(nodeKind)

    override fun linkBlobUsage(shareId: String): Long =
        (
            em.createNativeQuery(
                """
                SELECT COALESCE((SELECT SUM(b.byte_size) FROM veyq_blob b
                       JOIN veyq_application_blob a ON a.blob_id = b.id
                       JOIN veyq_share_identity i ON i.id = a.writer_identity_id
                      WHERE i.share_id = :key), 0)
                """.trimIndent(),
            ).setParameter("key", shareId).singleResult as Number
            ).toLong()
}

/**
 * The service's half of what a share's blob GET is authorized against (the fourth `veyq_blob` arm,
 * `validate_blob_reference_arity.py`): every application blob of a live node of the subtree — released ones too, which a
 * guest still reads in an application's history until the purge. The listing a guest is served is
 * [ApplicationBlobsHandler.listShared], a separate read with its own live predicate on the node (see
 * [SharedSubtreeContributor]). A content block's reach is core's (`ModerationBlocks.blobBlocked` asks this arm for a
 * blocked node, and [ApplicationBlobReportableItems] and the application's own `ReportableItems` for a blocked blob or
 * owner).
 */
@ApplicationScoped
class ApplicationBlobSharedSubtreeContributor(private val service: ApplicationBlobs) : SharedSubtreeContributor {

    /** An item of its own, released or not; an owner's content only while it is not released — one released with its
     *  owner's end (a trashed medium) is in no share's reach (ADR 0113 decision 6, [ApplicationBlobs.reachesGuests]). */
    override fun authorizedBlobIds(scopeId: String, liveNodeIds: List<String>): Set<String> {
        if (liveNodeIds.isEmpty()) return emptySet()
        val kinds = service.kindsOf(liveNodeIds)
        val ids = HashSet<String>()
        liveNodeIds.chunked(ApplicationBlobs.CHUNK).forEach { chunk ->
            ApplicationBlobEntity.list("scopeId = ?1 and nodeId in ?2", scopeId, chunk)
                .filter { service.reachesGuests(it, kinds[it.nodeId]) }.forEach { row -> ids.add(row.blobId) }
        }
        return ids
    }

    /** The set's predicate for one blob: a row of that node names it, and reaches a guest. */
    override fun holds(scopeId: String, liveNodeId: String, blobId: String): Boolean {
        val kind = NodeEntity.findById(liveNodeId)?.kind
        return ApplicationBlobEntity.list("scopeId = ?1 and nodeId = ?2 and blobId = ?3", scopeId, liveNodeId, blobId)
            .any { service.reachesGuests(it, kind) }
    }
}

/** A link identity is still in use while an application blob names it as its writer — the link its bytes count against
 *  (ADR 0073 §4), and `fk_application_blob_identity` would refuse its delete anyway. */
@ApplicationScoped
class ApplicationBlobGuestIdentityUsage : GuestIdentityUsage {
    override fun stillReferenced(identityId: String): Boolean = ApplicationBlobEntity.count("writerIdentityId", identityId) > 0
}

/**
 * What of the service goes with an erased account (ADR 0101 decisions 4-5): nothing. A blob a member completed belongs
 * to its node like a medium to its Memory, and its writer stays under the opaque id, shown as a former member's. The
 * account's own scopes, and their blobs, are erased before this runs (`ApplicationBlobScopeEraser`).
 */
@ApplicationScoped
class ApplicationBlobAccountEraser : AccountEraser {
    override val columns: Map<String, ColumnFate> = mapOf(
        "veyq_application_blob.writer_user_id" to kept("content attribution: an application blob's writer, shown as a former member's"),
    )

    override fun eraseAccount(accountId: String) {}
}

/**
 * The application blobs of an erased node subtree (the trash purge, ADR 0061 decision 6) — they live inside their node
 * and go with it. Phase 160: after the applications' own record rows (Expenses' 150, Memories' media 100), whose writers
 * lock them before the service's rows, and before core's attachments (200) and counters (300). The rows are locked first,
 * as every other writer of them locks them (before the node — and before the blob rows, which core's purge locks for
 * every eraser at once from [blobIdsOf]). Their blob rows go through core's `BlobUploadStore.deleteBlobRows`, which puts
 * their store objects on the reclaim list in this transaction (ADR 0062). Returns the link identities that wrote them,
 * for the caller to drop once nothing else names them; the guest-write records naming the rows go with them, and a
 * content block on one ends with it (ADR 0093 § 6).
 */
@ApplicationScoped
class ApplicationBlobNodeSubtreeEraser(
    private val guestWriteRecords: GuestWriteRecords,
    private val measures: MeasureTargets,
    private val blobs: BlobUploadStore,
) : NodeSubtreeEraser {
    override val phase = 160

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        val rows = locked(nodeIds)
        if (rows.isEmpty()) return emptySet()
        val guests = rows.mapNotNullTo(HashSet()) { it.writerIdentityId }
        val ids = rows.map { it.id }
        guestWriteRecords.forget(ids)
        ids.chunked(ApplicationBlobs.CHUNK).forEach { ApplicationBlobEntity.delete("id in ?1", it) }
        blobs.deleteBlobRows(scopeId, rows.map { it.blobId }) // the service rows first: fk_application_blob_blob
        measures.erased(listOf(BlockKind.CONTENT_ITEM), ids)
        return guests
    }

    /** The rows locked first, as [erase] locks them (item rows before blob rows), and every row's blob. */
    override fun blobIdsOf(scopeId: String, nodeIds: List<String>): Collection<String> = locked(nodeIds).map { it.blobId }

    private fun locked(nodeIds: List<String>): List<ApplicationBlobEntity> =
        nodeIds.chunked(ApplicationBlobs.CHUNK).flatMap {
            ApplicationBlobEntity.find("nodeId in ?1 order by id", it).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
}

/**
 * The application blob rows of an erased group. Phase 160: below [ScopeEraser.BLOB_ROWS_PHASE], because
 * `fk_application_blob_blob` references `veyq_blob`, whose rows core deletes there (`ScopePurge` records every blob's
 * store objects for reclaim before any eraser runs, so this erases rows only), and below core's share erasure (800),
 * because `fk_application_blob_identity` references `veyq_share_identity`, whose rows that one deletes. The guest-write
 * records naming the rows go with the space's identities (`ShareScopeEraser`, cascading); a content block on a blob ends
 * with it (ADR 0093 § 6).
 */
@ApplicationScoped
class ApplicationBlobScopeEraser(private val measures: MeasureTargets) : ScopeEraser {
    override val phase = 160

    override fun eraseScope(scopeId: String) {
        val ids = ApplicationBlobEntity.getEntityManager()
            .createQuery("select a.id from ApplicationBlobEntity a where a.scopeId = ?1", String::class.java)
            .setParameter(1, scopeId).resultList
        ApplicationBlobEntity.delete("scopeId", scopeId)
        measures.erased(listOf(BlockKind.CONTENT_ITEM), ids)
    }
}

/**
 * The service's part of a node's re-key (ADR 0109 decision 3, ADR 0112 decision 1): every application blob's `K_blob`
 * wrap — its blob row's — is re-wrapped, never re-encrypted. Its wraps are core's blob wraps: listed page by page by the
 * service's `listApplicationBlobRekey` ([ApplicationBlobs.rekeyPage], each with the id it is bound to) and staged by
 * `stageNodeRekey` as [RekeyStore.BLOB_WRAP] under core's
 * part, which is why [part] is [NodeRekeyStage.CORE] — it reads what core's stage holds. Core's commit runs it after
 * every application's participant ([RekeyStore.commit]: core's own last), so its [lockRows] takes the service's rows
 * after an application's own rows and before the node's — the order every writer takes ([ApplicationBlobs]). [commit]
 * **decides before it writes**:
 *
 * - every blob of the node whose wrap is under its key has it staged ([NodeRekeyParticipant.Verdict.MISSING] if not) —
 *   one completed since the read included, which the re-keyer then stages and commits again; a `WRITE_ONLY` guest's,
 *   under its own `K_up`, is left as it is (ADR 0109 point 5, ADR 0113 decision 7);
 * - every staged blob wrap is a blob of the node, an attachment's or the service's ([NodeRekeyParticipant.Verdict.STALE]
 *   if not): a blob purged since.
 *
 * Only then it writes the wraps into their blob rows (after the node's lock: item rows before blob rows) and moves the
 * node's item revision once, so an open list re-reads them (their digest changed with the wraps).
 */
@ApplicationScoped
class ApplicationBlobRekeyParticipant(private val items: NodeItemsRevisions, private val service: ApplicationBlobs) : NodeRekeyParticipant {

    override val part: String = NodeRekeyStage.CORE

    override fun lockRows(scopeId: String, nodeId: String) {
        rows(scopeId, nodeId, lock = true)
    }

    override fun commit(scopeId: String, nodeId: String, staged: Map<Key, Value>): NodeRekeyParticipant.Verdict {
        // Held since [lockRows]: read, not locked again — a lock here, after the node's, would invert the order.
        val held = rows(scopeId, nodeId)
        val wraps = staged.filterKeys { it.field == RekeyStore.BLOB_WRAP }.mapKeys { it.key.itemId }
        val ownBlobs = held.mapTo(HashSet()) { it.blobId }
        val attachmentBlobs = NodeAttachmentEntity.find("scopeId = ?1 and nodeId = ?2", scopeId, nodeId).list().mapTo(HashSet()) { it.blobId }
        if (wraps.keys.any { it !in ownBlobs && it !in attachmentBlobs }) return NodeRekeyParticipant.Verdict.STALE
        if (ownBlobs.any { it !in wraps }) return NodeRekeyParticipant.Verdict.MISSING
        if (held.isEmpty()) return NodeRekeyParticipant.Verdict.APPLIED
        // The blob rows the wraps live in, after the node's lock (item rows before blob rows).
        val blobRows = StoredObjectLocks.lockOf(scopeId, held.map { it.blobId }).associateBy { it.id }

        // Write — nothing below can refuse.
        for (r in held) blobRows[r.blobId]?.wrappedBlobKey = wraps.getValue(r.blobId).value
        items.bump(scopeId, nodeId) // after the record rows: record rows -> node row -> revision row
        return NodeRekeyParticipant.Verdict.APPLIED
    }

    /** The rows whose wrap is under the node's key — not a `WRITE_ONLY` guest's, under its `K_up`, which the re-key keeps
     *  ([ApplicationBlobs.underNodeKey], the rule `listApplicationBlobRekey` lists by). */
    private fun rows(scopeId: String, nodeId: String, lock: Boolean = false): List<ApplicationBlobEntity> =
        service.underNodeKey(scopeId, nodeId, lock)
}
