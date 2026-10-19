package me.raddatz.veyq.memories.banner

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.memories.media.MemoryBlobRoles
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/**
 * A Memory's **banner** (uc.memory.banner, ADR 0102): one image or one video above the menu bar, independent of the
 * cover. Its bytes are the node's own core attachments in [SLOT] and, for a video, [POSTER_SLOT]; which medium it is,
 * where it came from, its frame, focal point and description are sealed in the node's `wrappedMeta` - Memories'
 * plaintext, which the server never opens. Each slot token is also its upload's role ([MemoryBlobRoles]), so a banner
 * blob's `K_blob` wrap opens on no other node or slot (ADR 0072).
 */
object MemoryBanner {
    const val SLOT = MemoryBlobRoles.BANNER
    const val POSTER_SLOT = MemoryBlobRoles.BANNER_POSTER

    /** The kinds that carry a banner: a Memory and a Chapter, which render the same view. */
    val KINDS: Set<String> = setOf(MemoriesNodeKinds.MEMORY, MemoriesNodeKinds.CHAPTER)
}

/** What `setMemoryBanner` / `removeMemoryBanner` did. Only [Done] wrote. */
sealed interface MemoryBannerOutcome {
    object Done : MemoryBannerOutcome

    /** No such scope grant (or no write), live node or open upload of the caller - a 404 that reveals nothing. */
    object NotFound : MemoryBannerOutcome, HttpFailure by Failures.nodeNotFound
    object Suspended : MemoryBannerOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeDeleting : MemoryBannerOutcome, HttpFailure by Failures.scopeDeleting

    /** The node is no MEMORY or CHAPTER - a 422. */
    object IllegalTarget : MemoryBannerOutcome, HttpFailure by httpFailure("illegal_target", UNPROCESSABLE)

    /** An upload was not started as this node's banner or poster: its key wrap would not open here (ADR 0072) - a 422. */
    object UploadMismatch : MemoryBannerOutcome, HttpFailure by httpFailure("upload_mismatch", UNPROCESSABLE)

    /** An upload failed declare-then-verify (the request's staged objects are on the reclaim list) - a 400. */
    object Budget : MemoryBannerOutcome, HttpFailure by httpFailure("budget_exceeded", BAD_REQUEST)

    /** A banner slot holds another blob than the request states; NOTHING was written, the uploads are still open - a 409. */
    data class BannerStale(val currentBlobId: String?, val currentPosterBlobId: String?) :
        MemoryBannerOutcome, HttpFailure by httpFailure("banner_stale", CONFLICT)

    /** The node's `wrappedMeta` moved past the stated revision; NOTHING was written, the uploads are still open (ADR 0077). */
    data class StaleMeta(val wrappedMeta: String?, val metaRevision: Long) : MemoryBannerOutcome, HttpFailure by Failures.staleRevision
}

/**
 * Use-case logic for a Memory's banner: the checks that need no lock, then [MemoryBannerStore]'s one transaction - the
 * cover's order (core's `NodeCoverHandler`), with two slots. Scope access is the first gate ([ScopeAccess], WRITE: a
 * member who may write the scope, exactly who may set the cover); no guest reaches it (no share route, and
 * `MediaShareGuestWrites` admits no banner role for a guest's upload).
 */
@ApplicationScoped
class MemoryBannerHandler(
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val store: MemoryBannerStore,
) {
    /**
     * Make [uploadId] the banner of [nodeId] in place of [expectedBlobId], and [posterUploadId] its poster in place of
     * [expectedPosterBlobId] - or, without a poster, empty the poster slot of [expectedPosterBlobId] - and write the
     * node's re-sealed [wrappedMeta] at [metaRevision]: all or nothing ([MemoryBannerStore.set]). Each upload is the
     * caller's own (a session another member opened is not found), verified against its declaration before anything
     * is attached.
     */
    fun set(
        userId: String, scopeId: String, nodeId: String, uploadId: String, posterUploadId: String?,
        expectedBlobId: String?, expectedPosterBlobId: String?, wrappedMeta: String, metaRevision: Long,
    ): MemoryBannerOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(MemoryBannerOutcome.NotFound, MemoryBannerOutcome.Suspended, MemoryBannerOutcome.ScopeDeleting)?.let { return it }
        val node = nodes.findInScope(scopeId, nodeId) ?: return MemoryBannerOutcome.NotFound
        if (node.kind !in MemoryBanner.KINDS) return MemoryBannerOutcome.IllegalTarget
        val opener = UploadOpener.Member(userId)
        val banner = blobs.findSession(scopeId, uploadId, opener) ?: return MemoryBannerOutcome.NotFound
        val poster = posterUploadId?.let { blobs.findSession(scopeId, it, opener) ?: return MemoryBannerOutcome.NotFound }
        if (!banner.boundTo(node.id, MemoryBanner.SLOT)) return MemoryBannerOutcome.UploadMismatch
        if (poster != null && !poster.boundTo(node.id, MemoryBanner.POSTER_SLOT)) return MemoryBannerOutcome.UploadMismatch

        val sessions = listOfNotNull(banner, poster)
        val manifests = HashMap<String, List<Long>?>()
        for (session in sessions) {
            when (val v = uploads.verifyBlob(session)) {
                is BlobVerify.Ok -> manifests[session.id] = v.manifest
                BlobVerify.Budget -> {
                    uploads.purge(sessions)
                    return MemoryBannerOutcome.Budget
                }
            }
        }
        return store.set(
            scopeId, node.id,
            MemoryBannerStore.Upload(banner, manifests[banner.id], expectedBlobId),
            poster?.let { MemoryBannerStore.Upload(it, manifests[it.id], expectedPosterBlobId) },
            expectedPosterBlobId, wrappedMeta, metaRevision, PrincipalKey.User(userId),
        )
    }

    /** Empty both banner slots (only while they hold [blobId] and [posterBlobId], or nothing) and write the meta without it. */
    fun remove(
        userId: String, scopeId: String, nodeId: String, blobId: String, posterBlobId: String?, wrappedMeta: String, metaRevision: Long,
    ): MemoryBannerOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(MemoryBannerOutcome.NotFound, MemoryBannerOutcome.Suspended, MemoryBannerOutcome.ScopeDeleting)?.let { return it }
        val node = nodes.findInScope(scopeId, nodeId) ?: return MemoryBannerOutcome.NotFound
        if (node.kind !in MemoryBanner.KINDS) return MemoryBannerOutcome.IllegalTarget
        return store.remove(scopeId, node.id, blobId, posterBlobId, wrappedMeta, metaRevision, PrincipalKey.User(userId))
    }
}
