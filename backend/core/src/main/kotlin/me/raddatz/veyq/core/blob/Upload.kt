package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blobstore.PresignedRef

// The upload protocol's request/result shapes, shared by every application that stores blobs (Memories' member and
// guest paths today): an upload session is a core.blob row ([UploadSessionEntity]) and who may act on it
// ([UploadOpener]) is a statement about that row, so these live beside it, not in an application. The completion's
// shapes live beside the seam that takes them, in UploadCompletion.kt.

/**
 * Who opened an upload session (ADR 0013 §2) — the one caller who may extend it by a window or complete it. A
 * member upload is opened by an account, a contributor upload by a share identity; never both, never neither.
 */
sealed interface UploadOpener {
    /** Whether this caller opened [session]. A session from before openers were recorded answers false to both. */
    fun opened(session: UploadSessionEntity): Boolean

    data class Member(val userId: String) : UploadOpener {
        override fun opened(session: UploadSessionEntity) = session.openerUserId == userId
    }

    data class Contributor(val shareIdentityId: String) : UploadOpener {
        override fun opened(session: UploadSessionEntity) = session.openerShareIdentityId == shareIdentityId
    }
}

/**
 * One blob to start: its shape, its `K_blob` wrap, the ids that wrap is sealed bound to (ADR 0072), and — optional,
 * opaque — its metadata sealed under that `K_blob`, which the session carries to the blob row unchanged.
 */
data class UploadDeclare(
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val blobId: String,
    val ownerId: String,
    val role: String,
    val wrappedBlobMeta: String?,
)

/** Per-item result of `start`: success carries the session + presigned PUTs (null when the store can't presign). */
data class UploadItemResult(val uploadId: String?, val presignedPuts: List<PresignedRef>?, val error: String?)

/** One window to sign: an open session + the first chunk of the window. */
data class WindowRequest(val uploadId: String, val fromChunk: Int)

/** Per-item result of a window request: the window's presigned PUTs, or an error code. */
data class WindowItemResult(val presignedPuts: List<PresignedRef>?, val error: String?)
