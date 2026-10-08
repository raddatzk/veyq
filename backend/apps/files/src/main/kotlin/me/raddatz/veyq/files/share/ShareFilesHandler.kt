package me.raddatz.veyq.files.share

import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.ShareViewGate
import me.raddatz.veyq.core.sharing.ShareWriteGate
import me.raddatz.veyq.core.sharing.ShareWriteTarget
import me.raddatz.veyq.files.content.CompleteFileOutcome
import me.raddatz.veyq.files.content.FileRow
import me.raddatz.veyq.files.content.FilesStore
import me.raddatz.veyq.files.content.FilesUploadCompletion
import me.raddatz.veyq.files.content.completedOutcome
import jakarta.enterprise.context.ApplicationScoped

sealed interface ShareFilesListOutcome {
    data class Listed(val files: List<FileRow>) : ShareFilesListOutcome
    /** Unknown, disabled, expired or deleted share, a space that serves no guests, or password-protected with no token
     *  — the non-enumerable 404 (the password challenge is the resolve's locked shell; this read serves none). */
    object NotFound : ShareFilesListOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    object PasswordIncorrect : ShareFilesListOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    object RateLimited : ShareFilesListOutcome, HttpFailure by Failures.rateLimited
}

sealed interface ShareFileCompleteOutcome {
    /** Past the gate: the completion's answer, exactly as a member's `completeFile` words it. */
    data class Completed(val outcome: CompleteFileOutcome) : ShareFileCompleteOutcome
    object NotFound : ShareFileCompleteOutcome, HttpFailure by httpFailure("share_not_found", NOT_FOUND)
    /** The mode takes no guest writes, the token is invalid, the root is trashed, a `WRITE_ONLY` link named a node below
     *  its root, or the node is no `FILE` — a 403, as the guest transport answers each. */
    object NotContributable : ShareFileCompleteOutcome, HttpFailure by httpFailure("share_not_contributable", FORBIDDEN)
    /** The node is no live node inside the share's subtree (trashed, outside, none) — a 404. */
    object NodeNotFound : ShareFileCompleteOutcome, HttpFailure by Failures.nodeNotFound
    object PasswordIncorrect : ShareFileCompleteOutcome, HttpFailure by httpFailure("share_password_incorrect", FORBIDDEN)
    /** Too many wrong password attempts, or the per-share upload budget spent (ADR 0051) — a 429. */
    object RateLimited : ShareFileCompleteOutcome, HttpFailure by Failures.rateLimited
}

/**
 * Files' guest operations on a share link (cr-files-application F2; tag `share-files` of `files/files.openapi.yaml`):
 * list the files of the shared subtree with their blobs, and complete a `READ_WRITE` guest's upload into a file below
 * the root. Each passes core's guest gate first ([ShareGuestGate.viewGate] / [ShareGuestGate.writeGate], ADR 0079 § Application guest
 * operations), as Memories' `ShareMediaHandler` does.
 */
@ApplicationScoped
class ShareFilesHandler(
    private val gate: ShareGuestGate,
    private val completions: UploadCompletions,
    private val guestWrites: FilesShareGuestWrites,
    private val store: FilesStore,
) {

    /** The live files of the share's live subtree, each with its blob or null. A `WRITE_ONLY` link sees nothing. */
    fun list(shareId: String, pwToken: String?): ShareFilesListOutcome {
        val viewable = when (val g = gate.viewGate(shareId, pwToken)) {
            is ShareViewGate.Viewable -> g
            ShareViewGate.WriteOnly -> return ShareFilesListOutcome.Listed(emptyList())
            ShareViewGate.NotFound, ShareViewGate.Locked -> return ShareFilesListOutcome.NotFound
            ShareViewGate.PasswordIncorrect -> return ShareFilesListOutcome.PasswordIncorrect
            ShareViewGate.RateLimited -> return ShareFilesListOutcome.RateLimited
        }
        return ShareFilesListOutcome.Listed(store.listFilesAmong(viewable.scopeId, viewable.liveNodeIds))
    }

    /**
     * Complete a guest's upload [uploadId] as the blob of the file [nodeId] below the share's root, replacing
     * [expectedBlobId] (null: the first upload), with the file's sealed [wrappedMeta] written in the same transaction
     * when sent. The target is the gate's ([ShareGuestGate.target]: a live node inside the subtree; a `WRITE_ONLY` link
     * none but its root, which is never a `FILE`), its kind must be Files' — the check that keeps this route from
     * handing an upload to another application's completion, as the member route's is — and the write re-decides link
     * and target under the scope lock ([ShareGuestGate.admittedInto], handed on unevaluated: asked here, a revoke or a
     * trash committing in between would be missed).
     */
    fun complete(
        shareId: String, pwToken: String?, contributorToken: String, nodeId: String, uploadId: String,
        expectedBlobId: String?, wrappedMeta: String?,
    ): ShareFileCompleteOutcome {
        // Attaching creates the attachment row, so it spends the per-share upload budget the start spends (ADR 0051).
        val gated = when (val g = gate.writeGate(shareId, pwToken, contributorToken, spendUploadBudget = true)) {
            is ShareWriteGate.Admitted -> g
            ShareWriteGate.NotFound -> return ShareFileCompleteOutcome.NotFound
            ShareWriteGate.PasswordIncorrect -> return ShareFileCompleteOutcome.PasswordIncorrect
            ShareWriteGate.RateLimited -> return ShareFileCompleteOutcome.RateLimited
            ShareWriteGate.NotContributable -> return ShareFileCompleteOutcome.NotContributable
        }
        val target = when (val t = gate.target(gated, nodeId)) {
            is ShareWriteTarget.Node -> t
            ShareWriteTarget.NotFound -> return ShareFileCompleteOutcome.NodeNotFound
            ShareWriteTarget.NotContributable -> return ShareFileCompleteOutcome.NotContributable
        }
        if (!guestWrites.supports(target.kind)) return ShareFileCompleteOutcome.NotContributable
        val item = FilesUploadCompletion.item(target.nodeId, uploadId, expectedBlobId, wrappedMeta)
        val result = completions.complete(
            gated.scopeId, listOf(item), UploadOpener.Contributor(gated.identityId), gate.admittedInto(gated, target),
        ).single()
        return ShareFileCompleteOutcome.Completed(completedOutcome(store, gated.scopeId, target.nodeId, result))
    }
}
