package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.files.FilesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

sealed interface CompleteFileOutcome {
    /** The file's blob is now the upload's. */
    data class Ok(val blob: FileBlobData) : CompleteFileOutcome
    object Suspended : CompleteFileOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CompleteFileOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : CompleteFileOutcome, HttpFailure by Failures.scopeNotFound
    /** No live node of this id in the scope (never was, another scope's, trashed). */
    object NoNode : CompleteFileOutcome, HttpFailure by Failures.nodeNotFound
    /** The node is live but no `FILE`. */
    object IllegalTarget : CompleteFileOutcome, HttpFailure by httpFailure("illegal_target", UNPROCESSABLE)
    /** No open session of this caller under the upload id (never started, spent, expired). */
    object NoUpload : CompleteFileOutcome, HttpFailure by httpFailure("upload_not_found", NOT_FOUND)
    /** The session was not started for this node in the role `FILE_CONTENT` (ADR 0072). */
    object UploadMismatch : CompleteFileOutcome, HttpFailure by httpFailure("upload_mismatch", UNPROCESSABLE)
    /** The stored chunks failed declare-then-verify; the objects are on the reclaim list. */
    object Budget : CompleteFileOutcome, HttpFailure by httpFailure("budget_exceeded", BAD_REQUEST)

    /**
     * The slot did not hold the expected blob (ADR 0077): nothing attached, the upload spent and reclaimed;
     * [currentBlobId] is what the slot holds now (null: empty).
     */
    data class Stale(val currentBlobId: String?) :
        CompleteFileOutcome, HttpFailure by httpFailure(FilesUploadCompletion.STALE_FILE, CONFLICT)
}

sealed interface ListFilesOutcome {
    data class Ok(val files: List<FileRow>) : ListFilesOutcome
    object Suspended : ListFilesOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : ListFilesOutcome, HttpFailure by Failures.scopeNotFound
    /** The named parent is no live node of this scope. */
    object NoParent : ListFilesOutcome, HttpFailure by Failures.nodeNotFound
}

/**
 * A member's files (cr-files-application F1a): the scope-access gate first ([ScopeAccess], uc.spaces.scope-access —
 * a non-member gets the 404 before anything else is read), then the operation. The upload's transfer is core's
 * (`BlobResource`); what a verified upload becomes is [FilesUploadCompletion], reached through core's
 * [UploadCompletions] dispatcher like every completion.
 */
@ApplicationScoped
class FilesHandler(
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val completions: UploadCompletions,
    private val store: FilesStore,
) {
    /**
     * `completeFile`: the upload [uploadId] becomes the blob of the file [nodeId], replacing [expectedBlobId] (null:
     * the first upload). The node must be a live `FILE` before core's dispatcher is asked, so this route never hands an
     * upload to another application's completion (measured: without this line a `MEMORY` here attached a medium through
     * Memories' completion, `ModuleCompositionTest.filesCompletionRouteNeverReachesMemoriesCompletion`). Core's attach re-checks the node
     * live and of kind `FILE` under the scope lock; this read only decides which answer a plain mistake gets.
     */
    fun complete(userId: String, scopeId: String, nodeId: String, uploadId: String, expectedBlobId: String?): CompleteFileOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CompleteFileOutcome.NoScope, CompleteFileOutcome.Suspended, CompleteFileOutcome.Deleting)?.let { return it }
        val node = nodes.findInScope(scopeId, nodeId) ?: return CompleteFileOutcome.NoNode
        if (node.kind != FilesNodeKinds.FILE) return CompleteFileOutcome.IllegalTarget

        val item = FilesUploadCompletion.item(nodeId, uploadId, expectedBlobId)
        val result = completions.complete(scopeId, listOf(item), UploadOpener.Member(userId)).single()
        return completedOutcome(store, scopeId, nodeId, result)
    }

    /** `listFiles`: the live files directly under [parentId] (null: the scope root), each with its blob or null. */
    fun list(userId: String, scopeId: String, parentId: String?): ListFilesOutcome {
        access.check(userId, scopeId, READ)
            .refusal(ListFilesOutcome.NoScope, ListFilesOutcome.Suspended)?.let { return it }
        val parentSlot = if (parentId == null) scopeId else nodes.findInScope(scopeId, parentId)?.id ?: return ListFilesOutcome.NoParent
        return ListFilesOutcome.Ok(store.listFiles(scopeId, parentSlot))
    }
}

/**
 * A completed item as the Files contract answers it — the member's `completeFile` and the guest's `completeShareFile`
 * alike. A success re-reads the attached blob; a conflict re-reads the slot ("what is there now", ADR 0077).
 */
internal fun completedOutcome(store: FilesStore, scopeId: String, nodeId: String, result: CompleteItemResult): CompleteFileOutcome =
    when (result.error) {
        // A peer's replace committed between the attach and this read freed the blob: answer as the conflict would.
        null -> store.blob(scopeId, checkNotNull(result.blobId))?.let(CompleteFileOutcome::Ok)
            ?: CompleteFileOutcome.Stale(store.currentBlobId(nodeId))
        FilesUploadCompletion.STALE_FILE -> CompleteFileOutcome.Stale(store.currentBlobId(nodeId))
        "node_not_found" -> CompleteFileOutcome.NoNode
        "illegal_target" -> CompleteFileOutcome.IllegalTarget
        "upload_not_found" -> CompleteFileOutcome.NoUpload
        "upload_mismatch" -> CompleteFileOutcome.UploadMismatch
        "budget_exceeded" -> CompleteFileOutcome.Budget
        else -> error("FilesUploadCompletion answered a code the Files contract does not name: ${result.error}")
    }
