package me.raddatz.veyq.files.content

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
import me.raddatz.veyq.files.FilesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/** Files' blob roles — its values of the contract's open `BlobRole` token (cr-files-application P3). */
object FilesBlobRoles {
    /**
     * A file's bytes: the role its upload is started in AND the attachment slot it lands in (one token space, owner
     * decision of P3). The upload's `ownerId` is the file node itself, so its `K_blob` wrap is sealed bound to
     * (blob, node, `FILE_CONTENT`) under the node's own key (ADR 0072, ADR 0080 Decision 13).
     */
    const val FILE_CONTENT = "FILE_CONTENT"
}

/**
 * The Files half of an upload's completion (cr-files-application F1a): a verified upload becomes the ONE blob of a
 * `FILE` node — first, or replacing the blob the caller read — through core's [AttachmentStore.attach], which does
 * the whole write in one transaction (scope lock, gate, session spent, liveness by query, compare-and-set on the
 * expected blob, the replaced blob freed). Core's dispatcher (`UploadCompletions`) picked this implementation by the
 * node's kind; the member path (`FilesHandler.complete`) reaches it, and a guest path (F2) will through the same seam.
 *
 * Core's item is generic: Files reads its [CompleteItem.ownerId] as nothing (the session's binding to the node is the
 * store's check), its derivatives as none, and the one payload [EXPECTED] as the blob a replace expects (a plain id,
 * not a seal). On [AttachOutcome.Conflict] the item's error is [STALE_FILE]; the conflict's current blob is not carried
 * here ([CompleteItemResult] has no field for it) — the member path re-reads the slot after the transaction, which is
 * "what the slot holds now" as much as the conflict's own read was (ADR 0077: the answer is the current state).
 */
@ApplicationScoped
class FilesUploadCompletion(
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val attachments: AttachmentStore,
) : UploadCompletion {

    override fun accepts(nodeKind: String): Boolean = nodeKind == FilesNodeKinds.FILE

    override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean): CompleteItemResult {
        // A file holds one blob: an item declaring derivatives is no file upload (only reachable by a future caller of
        // the seam — the Files contract has no derivatives field).
        if (item.derivatives.isNotEmpty()) return failed("upload_mismatch")
        val session = blobs.findSession(scopeId, item.originalUploadId, opener) ?: return failed("upload_not_found")
        val manifest = when (val v = uploads.verifyBlob(session)) {
            is BlobVerify.Ok -> v.manifest
            BlobVerify.Budget -> {
                uploads.purge(listOf(session))
                return failed("budget_exceeded")
            }
        }
        val outcome = attachments.attach(
            scopeId, node.id, setOf(FilesNodeKinds.FILE), FilesBlobRoles.FILE_CONTENT, session, manifest,
            item.payloads[EXPECTED], admitted,
        )
        return when (outcome) {
            is AttachOutcome.Attached -> CompleteItemResult(outcome.blobId, node.id, null)
            is AttachOutcome.Conflict -> failed(STALE_FILE)
            // A gate refused under the lock reads as a gone upload, as Memories answers it (the session stays open).
            AttachOutcome.NotAdmitted, AttachOutcome.UploadNotFound -> failed("upload_not_found")
            AttachOutcome.UploadMismatch -> failed("upload_mismatch")
            AttachOutcome.NodeNotFound -> failed("node_not_found")
            AttachOutcome.IllegalTarget -> failed("illegal_target")
        }
    }

    private fun failed(code: String) = CompleteItemResult(null, null, code)

    companion object {
        /** The [CompleteItem.payloads] key of the blob a replace expects in the slot (absent: the first upload). */
        const val EXPECTED = "expectedBlobId"

        /** The item's error when the slot did not hold the expected blob ([AttachOutcome.Conflict]). */
        const val STALE_FILE = "stale_file"

        /** Core's generic item for one upload into the file [nodeId]: the node is the `ownerId` the wrap is bound to. */
        fun item(nodeId: String, uploadId: String, expectedBlobId: String?): CompleteItem = CompleteItem(
            ownerId = nodeId, nodeId = nodeId, originalUploadId = uploadId, derivatives = emptyList(),
            payloads = listOfNotNull(expectedBlobId?.let { EXPECTED to it }).toMap(),
        )
    }
}
