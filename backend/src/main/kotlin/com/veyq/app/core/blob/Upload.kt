package com.veyq.app.core.blob

import com.veyq.app.shared.blobstore.PresignedRef

// The upload protocol's request/result shapes, shared by every application that stores blobs (Memories' member and
// guest paths today): an upload session is a core.blob row ([UploadSessionEntity]) and who may act on it
// ([UploadOpener]) is a statement about that row, so these live beside it, not in an application.

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

/** One blob to start: its shape, its `K_blob` wrap, and the ids that wrap is sealed bound to (ADR 0072). */
data class UploadDeclare(
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val blobId: String,
    val ownerId: String,
    val role: String,
)

/** One derivative upload of a media to complete: its role + the upload session id. */
data class DerivativeDeclare(val kind: String, val uploadId: String)

/** One logical media to complete: the original upload + role-tagged derivative uploads, attached to a node. */
data class CompleteItem(
    /** The client-minted media id (ADR 0072) — the `ownerId` every upload of the item was started with. */
    val mediaId: String,
    val nodeId: String,
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    val wrappedMeta: String?,
    val wrappedExif: String?,
)

/** Per-item result of `start`: success carries the session + presigned PUTs (null when the store can't presign). */
data class UploadItemResult(val uploadId: String?, val presignedPuts: List<PresignedRef>?, val error: String?)

/** One window to sign: an open session + the first chunk of the window. */
data class WindowRequest(val uploadId: String, val fromChunk: Int)

/** Per-item result of a window request: the window's presigned PUTs, or an error code. */
data class WindowItemResult(val presignedPuts: List<PresignedRef>?, val error: String?)

/** Per-item result of `complete`. */
data class CompleteItemResult(val blobId: String?, val mediaId: String?, val error: String?)
