package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestBlob
import io.quarkus.narayana.jta.QuarkusTransaction
import java.util.UUID

/** Open upload sessions for an attachment, as an application's client starts them: owner = the node, role = the slot. */
object AttachmentUploads {
    const val WRAP = "YXR0YWNo"

    /** An open session and what it will become: the blob id its wrap is bound to, and the storage key of its chunks. */
    data class Opened(val session: UploadSessionEntity, val slot: String) {
        val blobId: String get() = session.blobId!!
        val storageKey: String get() = session.storageKey
    }

    /** A member's session in [scopeId] for [slot] of [nodeId], read back detached — what `findSession` hands a completion. */
    fun open(blobs: BlobUploadStore, userId: String, scopeId: String, nodeId: String, slot: String): Opened {
        val storageKey = UUID.randomUUID().toString().replace("-", "")
        val id = blobs.createSession(scopeId, UploadOpener.Member(userId), storageKey, TestBlob.SMALL.upload(WRAP, ownerId = nodeId, role = slot))
        return Opened(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(id)!! }, slot)
    }
}

/**
 * A second application's completion for its own [EXPENSE] kind — not a CDI bean (`:core`'s test application holds no
 * implementation, `CoreAloneSliceTest`), so a test builds a dispatcher over it. It is what an application's attachment
 * completion looks like on [AttachmentStore]: the session for the opener, the chunks verified, then [AttachmentStore.attach]
 * with the expected blob its own payloads carry ([EXPECTED]), the outcome mapped onto its item result.
 */
class ExpenseAttachmentCompletion(
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val attachments: AttachmentStore,
) : UploadCompletion {
    override fun accepts(nodeKind: String): Boolean = nodeKind == EXPENSE

    override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean, attached: (String) -> Unit): CompleteItemResult {
        val uploadId = item.originalUploadId ?: return failed("upload_not_found") // a shared original is media's alone
        val session = blobs.findSession(scopeId, uploadId, opener) ?: return failed("upload_not_found")
        val manifest = when (val v = uploads.verifyBlob(session)) {
            is BlobUploadHandler.BlobVerify.Ok -> v.manifest
            BlobUploadHandler.BlobVerify.Budget -> return failed("budget_exceeded")
        }
        return when (val o = attachments.attach(scopeId, node.id, setOf(EXPENSE), SLOT, session, manifest, item.payloads[EXPECTED], admitted, attached)) {
            is AttachOutcome.Attached -> CompleteItemResult(o.blobId, node.id, null)
            is AttachOutcome.Conflict -> failed("attachment_conflict")
            AttachOutcome.NotAdmitted, AttachOutcome.UploadNotFound -> failed("upload_not_found")
            AttachOutcome.UploadMismatch -> failed("upload_mismatch")
            AttachOutcome.NodeNotFound -> failed("node_not_found")
            AttachOutcome.IllegalTarget -> failed("illegal_target")
            AttachOutcome.SharedSourceInvalid -> error("attach never answers a shared row's refusal")
        }
    }

    private fun failed(code: String) = CompleteItemResult(null, null, code)

    companion object {
        /** A kind no application in this repo declares. */
        const val EXPENSE = "EXPENSE"

        /** Its slot — and the role its uploads are started in (one token space, cr-files-application P3). */
        const val SLOT = "RECEIPT"

        /** The payload key under which this application's completion carries the blob a replace expects. */
        const val EXPECTED = "expectedBlobId"

        /** The item for one upload into [nodeId]'s slot: the node is the `ownerId` the wrap is bound to. */
        fun item(nodeId: String, uploadId: String, expectedBlobId: String?): CompleteItem = CompleteItem(
            ownerId = nodeId, nodeId = nodeId, originalUploadId = uploadId, derivatives = emptyList(),
            payloads = listOfNotNull(expectedBlobId?.let { EXPECTED to it }).toMap(),
        )
    }
}
