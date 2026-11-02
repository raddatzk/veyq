package me.raddatz.veyq.memories.banner

import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.SharedBlobSpec
import me.raddatz.veyq.core.blob.UploadSessionEntity
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

    /** Neither or both of `uploadId` and `shared`, or both poster forms - a 400. */
    object InvalidRequest : MemoryBannerOutcome, HttpFailure by Failures.invalidRequest

    /** A shared row's source is no live blob of this node, is blocked, or its new id is taken (ADR 0013 § 5) - a 422. */
    object SharedSourceInvalid : MemoryBannerOutcome, HttpFailure by httpFailure("shared_source_invalid", UNPROCESSABLE)

    /** A banner slot holds another blob than the request states; NOTHING was written, the uploads are still open - a 409. */
    data class BannerStale(val currentBlobId: String?, val currentPosterBlobId: String?) :
        MemoryBannerOutcome, HttpFailure by httpFailure("banner_stale", CONFLICT)

    /** The node's `wrappedMeta` moved past the stated revision; NOTHING was written, the uploads are still open (ADR 0077). */
    data class StaleMeta(val wrappedMeta: String?, val metaRevision: Long) : MemoryBannerOutcome, HttpFailure by Failures.staleRevision

    /** `nodeEpoch` is not the node's stored epoch (ADR 0109 point 6); NOTHING was written, the uploads are still open. */
    object StaleEpoch : MemoryBannerOutcome, HttpFailure by Failures.staleEpoch

    /** The node is due for its re-key (ADR 0114 decision 3); NOTHING was written. */
    object RekeyPending : MemoryBannerOutcome, HttpFailure by Failures.nodeRekeyPending
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
    private val applications: ApplicationSwitch,
) {
    /**
     * Make [uploadId] - or [shared], a new row on an object the node already stores (ADR 0013 § 5) - the banner of
     * [nodeId] in place of [expectedBlobId], and [posterUploadId] (or [posterShared]) its poster in place of
     * [expectedPosterBlobId] - or, without a poster, empty the poster slot of [expectedPosterBlobId] - and write the
     * node's re-sealed [wrappedMeta] at [metaRevision]: all or nothing ([MemoryBannerStore.set]). Each upload is the
     * caller's own (a session another member opened is not found), verified against its declaration before anything
     * is attached; a shared row's source is checked under the scope lock (`SharedBlobs`).
     */
    fun set(
        userId: String, scopeId: String, nodeId: String, uploadId: String?, shared: SharedBlobSpec?,
        posterUploadId: String?, posterShared: SharedBlobSpec?,
        expectedBlobId: String?, expectedPosterBlobId: String?, wrappedMeta: String, metaRevision: Long, nodeEpoch: Int,
    ): MemoryBannerOutcome {
        if ((uploadId == null) == (shared == null) || (posterUploadId != null && posterShared != null)) {
            return MemoryBannerOutcome.InvalidRequest
        }
        access.check(userId, scopeId, WRITE)
            .refusal(MemoryBannerOutcome.NotFound, MemoryBannerOutcome.Suspended, MemoryBannerOutcome.ScopeDeleting)?.let { return it }
        applications.requireWritable(CapabilityIds.MEMORIES, scopeId) // switched off, read-only (uc.platform.switch-application-off)
        val node = nodes.findInScope(scopeId, nodeId) ?: return MemoryBannerOutcome.NotFound
        if (node.kind !in MemoryBanner.KINDS) return MemoryBannerOutcome.IllegalTarget
        val opener = UploadOpener.Member(userId)
        val banner = uploadId?.let { blobs.findSession(scopeId, it, opener) ?: return MemoryBannerOutcome.NotFound }
        val poster = posterUploadId?.let { blobs.findSession(scopeId, it, opener) ?: return MemoryBannerOutcome.NotFound }
        if (banner != null && !banner.boundTo(node.id, MemoryBanner.SLOT)) return MemoryBannerOutcome.UploadMismatch
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
        fun fill(session: UploadSessionEntity?, spec: SharedBlobSpec?, expected: String?): MemoryBannerStore.Fill? = when {
            session != null -> MemoryBannerStore.Upload(session, manifests[session.id], expected)
            spec != null -> MemoryBannerStore.Shared(spec, userId, expected)
            else -> null
        }
        return store.set(
            scopeId, node.id,
            checkNotNull(fill(banner, shared, expectedBlobId)),
            fill(poster, posterShared, expectedPosterBlobId),
            expectedPosterBlobId, wrappedMeta, metaRevision, nodeEpoch, PrincipalKey.User(userId),
        )
    }

    /** Empty both banner slots (only while they hold [blobId] and [posterBlobId], or nothing) and write the meta without it. */
    fun remove(
        userId: String, scopeId: String, nodeId: String, blobId: String, posterBlobId: String?, wrappedMeta: String, metaRevision: Long,
        nodeEpoch: Int,
    ): MemoryBannerOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(MemoryBannerOutcome.NotFound, MemoryBannerOutcome.Suspended, MemoryBannerOutcome.ScopeDeleting)?.let { return it }
        applications.requireWritable(CapabilityIds.MEMORIES, scopeId) // switched off, read-only (uc.platform.switch-application-off)
        val node = nodes.findInScope(scopeId, nodeId) ?: return MemoryBannerOutcome.NotFound
        if (node.kind !in MemoryBanner.KINDS) return MemoryBannerOutcome.IllegalTarget
        return store.remove(scopeId, node.id, blobId, posterBlobId, wrappedMeta, metaRevision, nodeEpoch, PrincipalKey.User(userId))
    }
}
