package me.raddatz.veyq.depot.content

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeEpochGate
import me.raddatz.veyq.depot.DepotNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/** Depot's blob roles — its values of the contract's open `BlobRole` token (cr-files-application P3). */
object DepotBlobRoles {
    /**
     * A file's bytes: the role its upload is started in AND the attachment slot it lands in (one token space, owner
     * decision of P3). The upload's `ownerId` is the file node itself, so its `K_blob` wrap is sealed bound to
     * (blob, node, `FILE_CONTENT`) under the node's own key (ADR 0072, ADR 0080 Decision 13).
     */
    const val FILE_CONTENT = "FILE_CONTENT"
}

/**
 * The Depot half of an upload's completion (cr-files-application F1a): a verified upload becomes the ONE blob of a
 * `FILE` node — first, or replacing the blob the caller read — through core's [AttachmentStore.attach], which does
 * the whole write in one transaction (scope lock, gate, session spent, liveness by query, compare-and-set on the
 * expected blob, the replaced blob freed). Core's dispatcher (`UploadCompletions`) picked this implementation by the
 * node's kind; the member path (`FilesHandler.complete`) and a guest's (`ShareFilesHandler.complete`, F2) reach it the
 * same way. The attach is the whole write: a file's type, uploaded name and modified time are the blob's own sealed
 * meta (`wrappedBlobMeta`, declared at the upload's start and copied session → blob by core's `persistBlob`, inbox
 * #397), so they change with the bytes in this one transaction and nothing is written beside it — the node's
 * `wrappedMeta` is not Depot's any more (a guest's complete used to write it here, a member's in a second request).
 *
 * Core's item is generic: Depot reads its [CompleteItem.ownerId] as nothing (the session's binding to the node is the
 * store's check), its derivatives as none and the payload [EXPECTED] as the blob a replace expects (a plain id, not a
 * seal). On [AttachOutcome.Conflict] the item's error is [STALE_FILE]; the conflict's current blob is not carried
 * here ([CompleteItemResult] has no field for it) — the member path re-reads the slot after the transaction, which is
 * "what the slot holds now" as much as the conflict's own read was (ADR 0077: the answer is the current state).
 */
@ApplicationScoped
class DepotUploadCompletion(
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val attachments: AttachmentStore,
    private val epochs: NodeEpochGate,
) : UploadCompletion {

    override fun accepts(nodeKind: String): Boolean = nodeKind == DepotNodeKinds.FILE

    override fun complete(
        scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean,
        attached: (objectId: String) -> Unit,
    ): CompleteItemResult {
        // A file holds one blob: an item declaring derivatives is no file upload (only reachable by a future caller of
        // the seam — the Depot contract has no derivatives field).
        if (item.derivatives.isNotEmpty()) return failed("upload_mismatch")
        val uploadId = item.originalUploadId ?: return failed("upload_not_found") // a shared original is media's alone
        val session = blobs.findSession(scopeId, uploadId, opener) ?: return failed("upload_not_found")
        val manifest = when (val v = uploads.verifyBlob(session)) {
            is BlobVerify.Ok -> v.manifest
            BlobVerify.Budget -> {
                uploads.purge(listOf(session))
                return failed("budget_exceeded")
            }
        }
        // ADR 0109 point 6: the content blob's key is wrapped under the file node's key, so the request states the node's
        // epoch, judged under the node's row lock in the attach's transaction — asked as part of the attach's gate, which
        // runs after the scope lock and before the session's spend (the first write): the caller's own gate first (a
        // guest's takes the share row), then the node's lock, the order every guest write takes (scope → share → node).
        val nodeEpoch = checkNotNull(item.payloads[NODE_EPOCH]?.toIntOrNull()) { "Depot's item() always states the node's epoch" }
        var verdict = NodeEpochGate.Verdict.OK
        val gated = { admitted() && epochs.check(scopeId, node.id, nodeEpoch).also { verdict = it } == NodeEpochGate.Verdict.OK }
        val outcome = attachments.attach(
            scopeId, node.id, setOf(DepotNodeKinds.FILE), DepotBlobRoles.FILE_CONTENT, session, manifest, item.payloads[EXPECTED], gated,
            attached,
        )
        // The epoch refused under the lock: nothing attached, the session stays open (the attach rolled back).
        if (outcome == AttachOutcome.NotAdmitted) {
            when (verdict) {
                NodeEpochGate.Verdict.OK -> Unit // the caller's own gate refused
                NodeEpochGate.Verdict.STALE -> return failed(STALE_EPOCH)
                NodeEpochGate.Verdict.PENDING -> return failed(NODE_REKEY_PENDING)
                NodeEpochGate.Verdict.MISSING -> return failed("node_not_found")
            }
        }
        return when (outcome) {
            is AttachOutcome.Attached -> CompleteItemResult(outcome.blobId, node.id, null)
            is AttachOutcome.Conflict -> failed(STALE_FILE)
            // A gate refused under the lock reads as a gone upload, as Memories answers it (the session stays open).
            AttachOutcome.NotAdmitted, AttachOutcome.UploadNotFound -> failed("upload_not_found")
            AttachOutcome.UploadMismatch -> failed("upload_mismatch")
            AttachOutcome.NodeNotFound -> failed("node_not_found")
            AttachOutcome.IllegalTarget -> failed("illegal_target")
            AttachOutcome.SharedSourceInvalid -> error("a file's upload attaches no shared row: $outcome")
        }
    }

    private fun failed(code: String) = CompleteItemResult(null, null, code)

    companion object {
        /** The [CompleteItem.payloads] key of the blob a replace expects in the slot (absent: the first upload). */
        const val EXPECTED = "expectedBlobId"

        /** The item's error when the slot did not hold the expected blob ([AttachOutcome.Conflict]). */
        const val STALE_FILE = "stale_file"

        /** The [CompleteItem.payloads] key of the file node's epoch as the caller read it (ADR 0109 point 6). */
        const val NODE_EPOCH = "nodeEpoch"

        /** The item's error when [NODE_EPOCH] is not the node's stored epoch (`409 stale_epoch`). */
        const val STALE_EPOCH = "stale_epoch"

        /** The item's error when the node is not re-keyed since the scope's last rotation (`409 node_rekey_pending`). */
        const val NODE_REKEY_PENDING = "node_rekey_pending"

        /** Core's generic item for one upload into the file [nodeId]: the node is the `ownerId` the wrap is bound to. */
        fun item(nodeId: String, uploadId: String, expectedBlobId: String?, nodeEpoch: Int): CompleteItem = CompleteItem(
            ownerId = nodeId, nodeId = nodeId, originalUploadId = uploadId, derivatives = emptyList(),
            payloads = listOfNotNull(expectedBlobId?.let { EXPECTED to it }, NODE_EPOCH to nodeEpoch.toString()).toMap(),
        )
    }
}
