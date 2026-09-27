package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKeys
import com.veyq.app.shared.blobstore.PresignedRef
import com.veyq.app.shared.blobstore.StoreObjectRef
import com.veyq.app.shared.blobstore.StoreReclaimStore
import com.veyq.app.shared.blobstore.presignGets
import com.veyq.app.shared.blobstore.presignPuts
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.http.FailureStatus.BAD_REQUEST
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.FailureStatus.UNPROCESSABLE
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.AccessMode.READ
import com.veyq.app.shared.membership.AccessMode.WRITE
import com.veyq.app.shared.membership.ScopeAccess
import com.veyq.app.shared.membership.refusal
import com.veyq.app.shared.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

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

/** One blob to start: declared shape + the K_blob wrap. */
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
    val bucketId: String? = null, // cr-0113: an explicit target bucket; null = the uploader's primary
)

/** Per-item result of `start`: success carries the session + presigned PUTs (null when the store can't presign). */
data class UploadItemResult(val uploadId: String?, val presignedPuts: List<PresignedRef>?, val error: String?)

/** One window to sign: an open session + the first chunk of the window. */
data class WindowRequest(val uploadId: String, val fromChunk: Int)

/** Per-item result of a window request: the window's presigned PUTs, or an error code. */
data class WindowItemResult(val presignedPuts: List<PresignedRef>?, val error: String?)

/** Per-item result of `complete`. */
data class CompleteItemResult(val blobId: String?, val mediaId: String?, val error: String?)

/** Per-item result of `presign-download`. */
data class DownloadItemResult(val presignedGets: List<PresignedRef>?, val error: String?)

sealed interface StartOutcome {
    data class Results(val items: List<UploadItemResult>) : StartOutcome
    object Suspended : StartOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : StartOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : StartOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface WindowOutcome {
    data class Results(val items: List<WindowItemResult>) : WindowOutcome
    object Suspended : WindowOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : WindowOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : WindowOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface CompleteOutcome {
    data class Results(val items: List<CompleteItemResult>) : CompleteOutcome
    object Suspended : CompleteOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CompleteOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : CompleteOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface ListMediaOutcome {
    /** A conditional-GET page (cr-0107, ADR 0033): the keyset delta + both revisions + the id-set — the latter present **only when a delete advanced `delete_revision`** past the client's `sinceDelete`. */
    object Suspended : ListMediaOutcome, HttpFailure by Failures.tenantSuspended
    data class Page(
        val rows: List<MediaRow>,
        val mediaRevision: Int,
        val deleteRevision: Int,
        val mediaIds: List<String>?,
    ) : ListMediaOutcome
    /** The client's `If-None-Match` equals the node's `media_revision` — nothing changed (a 304, no media query ran). */
    object NotModified : ListMediaOutcome
    object NoNode : ListMediaOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface DownloadOutcome {
    data class Results(val items: List<DownloadItemResult>) : DownloadOutcome
    object Suspended : DownloadOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : DownloadOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface MediaExifOutcome {
    /** The media's sealed panel bundle (opaque base64), or null when none was sealed. */
    object Suspended : MediaExifOutcome, HttpFailure by Failures.tenantSuspended
    data class Found(val wrappedExif: String?) : MediaExifOutcome
    object NoMedia : MediaExifOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface MetaOutcome {
    /** The media's sealed listed-metadata blob (opaque base64), or null when none/cleared. */
    object Suspended : MetaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : MetaOutcome, HttpFailure by Failures.scopeDeleting
    data class Found(val wrappedMeta: String?) : MetaOutcome
    object NoMedia : MetaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface CoverCommitOutcome {
    /** The committed cover blob ref (any prior cover blob was reclaimed). */
    object Suspended : CoverCommitOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CoverCommitOutcome, HttpFailure by Failures.scopeDeleting
    data class Ok(val ref: BlobRefData) : CoverCommitOutcome
    /** No such scope/node/upload, or the caller holds no grant (a 404, no reveal). */
    object NoTarget : CoverCommitOutcome, HttpFailure by Failures.nodeNotFound
    /** The target node is a FOLDER (owns no `K_mem`) — a 422. */
    object IllegalTarget : CoverCommitOutcome, HttpFailure by httpFailure("illegal_target", UNPROCESSABLE)
    /** The upload was not started as this node's cover — its wrap would not open here (ADR 0072) — a 422. */
    object UploadMismatch : CoverCommitOutcome, HttpFailure by httpFailure("upload_mismatch", UNPROCESSABLE)
    /** The upload failed declare-then-verify (staged objects purged) — a 400. */
    object Budget : CoverCommitOutcome, HttpFailure by httpFailure("budget_exceeded", BAD_REQUEST)
}

sealed interface CoverDeleteOutcome {
    /** The cover blob was detached + reclaimed (or the node had none — idempotent). */
    object Suspended : CoverDeleteOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CoverDeleteOutcome, HttpFailure by Failures.scopeDeleting
    object Done : CoverDeleteOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoTarget : CoverDeleteOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface DeleteMediaOutcome {
    /** The media is trashed (or was already trashed — idempotent). */
    object Suspended : DeleteMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : DeleteMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : DeleteMediaOutcome
    /** No such scope/media, or the caller holds no grant (a 404, no reveal). */
    object NoMedia : DeleteMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface RestoreMediaOutcome {
    /** The media is restored (or was already live — idempotent). */
    object Suspended : RestoreMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RestoreMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : RestoreMediaOutcome
    /** No such scope/media (e.g. already purged), or the caller holds no grant (a 404). */
    object NoMedia : RestoreMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface PurgeMediaOutcome {
    /** The trashed media was permanently deleted. */
    object Suspended : PurgeMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : PurgeMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : PurgeMediaOutcome
    /** No such trashed media (unknown / already purged / still live), or no grant (a 404). */
    object NoMedia : PurgeMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface ListBucketsOutcome {
    data class Ok(val buckets: List<BucketRow>) : ListBucketsOutcome
    object Suspended : ListBucketsOutcome, HttpFailure by Failures.tenantSuspended
    /** No such scope/node, or the caller holds no grant (a 404, no reveal). */
    object NoNode : ListBucketsOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface CreateBucketOutcome {
    data class Ok(val bucket: BucketRow) : CreateBucketOutcome
    object Suspended : CreateBucketOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CreateBucketOutcome, HttpFailure by Failures.scopeDeleting
    /** A given parentBucketId is missing or not a primary (sub-buckets nest one level) — a 403. */
    object Forbidden : CreateBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : CreateBucketOutcome, HttpFailure by Failures.nodeNotFound
    object IdTaken : CreateBucketOutcome, HttpFailure by Failures.idTaken
}

sealed interface RenameBucketOutcome {
    data class Ok(val bucket: BucketRow) : RenameBucketOutcome
    object Suspended : RenameBucketOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RenameBucketOutcome, HttpFailure by Failures.scopeDeleting
    /** No such scope/node/bucket, or the caller holds no grant (a 404). */
    object NoBucket : RenameBucketOutcome, HttpFailure by httpFailure("bucket_not_found", NOT_FOUND)
    /** The bucket is a primary (not renameable) — a 403. */
    object Forbidden : RenameBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

sealed interface DeleteBucketOutcome {
    /** The sub-bucket was deleted + its media reparented (or it did not exist — idempotent). */
    object Suspended : DeleteBucketOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : DeleteBucketOutcome, HttpFailure by Failures.scopeDeleting
    object Done : DeleteBucketOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : DeleteBucketOutcome, HttpFailure by Failures.nodeNotFound
    /** The bucket is a primary (not deletable) — a 403. */
    object Forbidden : DeleteBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

sealed interface MoveMediaOutcome {
    /** The media were moved. */
    object Suspended : MoveMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : MoveMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : MoveMediaOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : MoveMediaOutcome, HttpFailure by Failures.nodeNotFound
    /** A medium or the target bucket is not the caller's own — a 403. */
    object Forbidden : MoveMediaOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

/**
 * Use-case logic for media blobs (en.memory.media, cr-0028). Operator-blind, batch-shaped, per-item results.
 * Scope access is the one gate, [ScopeAccess], asked first (uc.spaces.scope-access): a non-member sees a top-level
 * 404, not a reveal. Direct-to-
 * store: bytes never flow through here — `start` presigns a blob's first window of PUTs and `window` each next one,
 * `complete` stats the stored objects (declare-then-verify), `download` presigns GETs. A session acts only for its
 * [UploadOpener], and every window is authorized again, so a removed member stops at their next window (ADR 0013 §2).
 *
 * Two gates (ADR 0013 §3): the **declared** shape is checked at `start` — a size over the cap is
 * `budget_exceeded`, a `chunkCount` the size does not derive (or a size no frame sequence has) is `shape_invalid` —
 * and the **actual** objects are verified against it at `complete`: every chunk at exactly its derived length, else
 * purge + `budget_exceeded`. The in-memory dev store has no transport, so its `complete` trusts the declaration (manifest
 * null); the byte path is covered by the opt-in store e2e.
 */
@ApplicationScoped
class MediaHandler(
    private val store: MediaStore,
    private val blobStore: BlobStore,
    private val reclaim: StoreReclaimStore,
    private val access: ScopeAccess,
    private val quota: StorageQuota,
    config: VeyqConfig,
) {
    private val maxBytes: Long = config.blob().maxBytes()
    private val window: Int = config.blob().presignWindow()

    fun start(userId: String, scopeId: String, declares: List<UploadDeclare>, domain: String?): StartOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(StartOutcome.NoScope, StartOutcome.Suspended, StartOutcome.Deleting)?.let { return it }
        return StartOutcome.Results(startForScope(scopeId, UploadOpener.Member(userId), declares, domain))
    }

    /**
     * The member core of [start]: per declared blob, a session bound to [opener] and its first window of PUTs. Each
     * session is its own transaction and nothing is reserved: a member start is not bounded by a total yet (the
     * tenant quota, ADR 0073 §1, routes it through [startForLink]'s locked transaction). Bytes never flow through here.
     */
    private fun startForScope(scopeId: String, opener: UploadOpener, declares: List<UploadDeclare>, domain: String?): List<UploadItemResult> {
        val checked = admissibility(declares)
        val opened = checked.map { (d, error) -> if (error != null) null else openSession(scopeId, opener, d) }
        return presignOpened(checked, opened, domain)
    }

    /**
     * The start of an upload link — CONTRIBUTOR or UPLOADER link [shareId], opened by its identity [identityId] —
     * reserved against the tenant's per-link limit (ADR 0073 §4–§5). The caller does its own authorization (the
     * link's gate), which runs first. One transaction:
     *  1. lock the tenant row, so concurrent starts into the tenant serialize (A6);
     *  2. check each item on its own, as a member start does;
     *  3. ask [StorageQuota] whether the admissible items fit **together**. If not, every admissible item is
     *     `quota_exceeded` and no session is inserted, so a photo never lands without its derivatives — admitting
     *     item by item would reserve an original alone until the stale-session purge;
     *  4. otherwise insert every admissible session, and commit.
     * Presigning runs after the commit: the lock covers the check and the inserts only, never up to 256 × window
     * signatures.
     */
    fun startForLink(scopeId: String, shareId: String, identityId: String, declares: List<UploadDeclare>, domain: String?): List<UploadItemResult> {
        val opener = UploadOpener.Contributor(identityId)
        val (checked, opened) = QuarkusTransaction.requiringNew().call {
            val tenant = ScopeEntity.findById(scopeId)?.tenantId?.let { quota.lockTenant(it) }
            // Checked under the lock, not before it: the sessions are inserted together at the end, so a blob id
            // two racing requests (or two items of this one) both claim would otherwise pass the check and fail
            // the unique index at insert — a 500 instead of `id_taken`.
            val checked = admissibility(declares)
            val admissible = checked.filter { (_, error) -> error == null }.map { (d, _) -> d }
            if (tenant == null || !quota.linkFits(tenant, shareId, admissible)) {
                checked.map { (d, error) -> d to (error ?: "quota_exceeded") } to checked.map { null }
            } else {
                checked to checked.map { (d, error) -> if (error != null) null else openSession(scopeId, opener, d) }
            }
        }
        return presignOpened(checked, opened, domain)
    }

    /**
     * Sign the next window of each open session — authorized again exactly like [start], because a window is where
     * a member removed since then must stop. The window itself is [windowForScope].
     */
    fun window(userId: String, scopeId: String, requests: List<WindowRequest>, domain: String?): WindowOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(WindowOutcome.NoScope, WindowOutcome.Suspended, WindowOutcome.Deleting)?.let { return it }
        return WindowOutcome.Results(windowForScope(scopeId, UploadOpener.Member(userId), requests, domain))
    }

    /**
     * The member-less core of [window]: per item, the session [opener] opened in `scopeId`, re-signed for chunks
     * `[fromChunk, min(fromChunk + window, chunkCount))`, and marked live for the stale-session purge. An unknown
     * session, a consumed one and another caller's are all `upload_not_found`, so a probe learns nothing; a
     * `fromChunk` outside the blob is `range_invalid`. The caller does its own authorization.
     */
    fun windowForScope(scopeId: String, opener: UploadOpener, requests: List<WindowRequest>, domain: String?): List<WindowItemResult> =
        requests.map { windowOne(scopeId, opener, it, domain) }

    private fun windowOne(scopeId: String, opener: UploadOpener, r: WindowRequest, domain: String?): WindowItemResult {
        val session = store.findSession(scopeId, r.uploadId, opener)
            ?: return WindowItemResult(presignedPuts = null, error = "upload_not_found")
        if (r.fromChunk !in 0 until session.chunkCount) return WindowItemResult(presignedPuts = null, error = "range_invalid")
        if (!blobStore.canPresign) return WindowItemResult(presignedPuts = null, error = "store_unavailable")
        store.touchWindow(session.id)
        return WindowItemResult(presignedPuts = presignWindow(session.storageKey, session.byteSize, session.chunkCount, r.fromChunk, domain), error = null)
    }

    /** The PUTs of the window starting at [fromChunk], each signed for its chunk's exact length (ADR 0013 §3). */
    private fun presignWindow(storageKey: String, byteSize: Long, chunkCount: Int, fromChunk: Int, domain: String?): List<PresignedRef>? =
        blobStore.presignPuts(storageKey, fromChunk until minOf(fromChunk + window, chunkCount), { ChunkFrames.chunkLength(byteSize, it) }, domain)

    /**
     * Each declared item paired with the code that refuses it on its own, or null when it is admissible (ADR 0013 §3,
     * ADR 0072): over the per-blob cap, a shape the size does not derive, or a blob id already taken — by a blob, an
     * open session, or an earlier item of the same request.
     */
    private fun admissibility(declares: List<UploadDeclare>): List<Pair<UploadDeclare, String?>> {
        val claimed = HashSet<String>()
        return declares.map { d ->
            val error = when {
                d.byteSize > maxBytes -> "budget_exceeded"
                // The frame size fixes the shape (ADR 0013 §3): a count the size does not derive is a client bug, not a
                // big file, so it gets its own code. The size is checked first — chunkCount() is defined only where it holds.
                !ChunkFrames.isPossible(d.byteSize) || d.chunkCount != ChunkFrames.chunkCount(d.byteSize) -> "shape_invalid"
                // The client-minted blob id (ADR 0072): taken by a blob or by another open session, anywhere → refused,
                // and the same answer wherever it lives.
                !claimed.add(d.blobId) || store.blobIdTaken(d.blobId) -> "id_taken"
                else -> null
            }
            d to error
        }
    }

    /** A session inserted for one admissible item, with the object-key prefix its chunks are stored under. */
    private data class Opened(val uploadId: String, val storageKey: String)

    /** Insert the session for one admissible item; joins the caller's transaction when there is one. */
    private fun openSession(scopeId: String, opener: UploadOpener, d: UploadDeclare): Opened {
        val storageKey = UUID.randomUUID().toString()
        return Opened(store.createSession(scopeId, opener, storageKey, d), storageKey)
    }

    /**
     * The per-item results of a start whose sessions are committed: each opened session with its first window of
     * PUTs, each refused item with its code. The session is metadata; presigned PUTs require a transport-capable
     * store — only the %test in-memory stub cannot presign (puts = null), %dev/%prod always have a real store
     * (BlobStoreStartupCheck, ADR 0013). Only the first window: the rest is signed window by window, so the TTL
     * never has to cover a whole file.
     */
    private fun presignOpened(checked: List<Pair<UploadDeclare, String?>>, opened: List<Opened?>, domain: String?): List<UploadItemResult> =
        checked.mapIndexed { i, (d, error) ->
            val o = opened[i] ?: return@mapIndexed UploadItemResult(uploadId = null, presignedPuts = null, error = error)
            UploadItemResult(uploadId = o.uploadId, presignedPuts = presignWindow(o.storageKey, d.byteSize, d.chunkCount, 0, domain), error = null)
        }

    fun complete(userId: String, scopeId: String, items: List<CompleteItem>): CompleteOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CompleteOutcome.NoScope, CompleteOutcome.Suspended, CompleteOutcome.Deleting)?.let { return it }
        return CompleteOutcome.Results(completeForNode(scopeId, items, UploadOpener.Member(userId)))
    }

    /**
     * The member-less core of [complete]: verify + attach each item, scoped only by `scopeId`, resolving each upload
     * only for its [opener] — who is also whom the media is attributed to: a member's `owner_key` (cr-0113), or a
     * **contributor `shareIdentityId`** (the CONTRIBUTOR share upload path, cr-0101). The caller does its own
     * authorization. Each item's `nodeId` is honoured (the share path sets it to the shared root node).
     */
    fun completeForNode(scopeId: String, items: List<CompleteItem>, opener: UploadOpener): List<CompleteItemResult> =
        items.map { completeOne(scopeId, it, opener) }

    /**
     * Complete one logical media (original + derivative uploads). The item is **atomic**: the node + every
     * upload session are resolved first, then **every** blob is declare-then-verified, and only if all pass are
     * the blobs + media + derivative rows attached. On a budget failure the item's staged objects are purged.
     */
    private fun completeOne(scopeId: String, item: CompleteItem, opener: UploadOpener): CompleteItemResult {
        val node = store.findNode(scopeId, item.nodeId)
            ?: return CompleteItemResult(null, null, "node_not_found")
        if (node.kind == NodeEntity.FOLDER) return CompleteItemResult(null, null, "illegal_target")

        val original = store.findSession(scopeId, item.originalUploadId, opener)
            ?: return CompleteItemResult(null, null, "upload_not_found")
        val derivSessions = item.derivatives.map { d ->
            val s = store.findSession(scopeId, d.uploadId, opener)
                ?: return CompleteItemResult(null, null, "upload_not_found")
            d.kind to s
        }
        val allSessions = listOf(original) + derivSessions.map { it.second }
        // Each blob's K_blob wrap is sealed bound to (media, role) (ADR 0072): a session started for another media or
        // in another role would land a blob nobody can open where it is attached, so the item is refused whole.
        if (!original.boundTo(item.mediaId, "ORIGINAL") || derivSessions.any { (kind, s) -> !s.boundTo(item.mediaId, kind) }) {
            return CompleteItemResult(null, null, "upload_mismatch")
        }
        if (store.mediaIdTaken(item.mediaId)) return CompleteItemResult(null, null, "id_taken")

        // declare-then-verify every blob up front; a single budget failure fails (and purges) the whole item
        val manifests = HashMap<String, List<Long>?>()
        for (session in allSessions) {
            when (val v = verifyBlob(session)) {
                is BlobVerify.Ok -> manifests[session.id] = v.manifest
                BlobVerify.Budget -> {
                    purge(allSessions)
                    return CompleteItemResult(null, null, "budget_exceeded")
                }
            }
        }

        val (blobId, mediaId) = store.attach(
            item.mediaId, original, item.nodeId, manifests[original.id],
            derivSessions.map { (kind, s) -> DerivativeAttach(kind, s, manifests[s.id]) },
            item.wrappedMeta,
            item.wrappedExif,
            (opener as? UploadOpener.Contributor)?.shareIdentityId,
            (opener as? UploadOpener.Member)?.userId, // cr-0113: the member uploader → owner_key "user:{id}" (null on the contributor path)
            item.bucketId,  // cr-0113: an explicit target bucket, else the uploader's primary
        )
        return CompleteItemResult(blobId, mediaId, null)
    }

    private sealed interface BlobVerify {
        data class Ok(val manifest: List<Long>?) : BlobVerify
        object Budget : BlobVerify
    }

    /**
     * Stat a session's stored objects against its declaration: every chunk present at **exactly** its derived length
     * (ADR 0013 §3). "The total does not exceed `byteSize`" would pass a short chunk — a frame no client sealed — and
     * this is the check that still holds on a store that ignores the signed `Content-Length`. A session whose shape
     * the size does not derive (one opened before `start` refused such shapes) cannot match and fails the same way.
     * Ok carries the manifest (null on the dev store).
     */
    private fun verifyBlob(session: UploadSessionEntity): BlobVerify {
        if (!blobStore.canPresign) return BlobVerify.Ok(null) // dev store: no transport, trust the declaration
        if (!ChunkFrames.isPossible(session.byteSize) || session.chunkCount != ChunkFrames.chunkCount(session.byteSize)) {
            return BlobVerify.Budget
        }
        val sizes = chunkKeys(session.storageKey, session.chunkCount).map { blobStore.stat(it) }
        val exact = sizes.withIndex().all { (i, size) -> size == ChunkFrames.chunkLength(session.byteSize, i) }
        return if (exact) BlobVerify.Ok(sizes.filterNotNull()) else BlobVerify.Budget
    }

    /**
     * Record every chunk object staged for the item's sessions for reclaim (after a budget failure). Objects
     * only — the sessions' **rows** are deliberately left standing here rather than deleted inline, and
     * [UploadSessionPurge] reclaims them on the same age predicate as a genuinely abandoned upload. That makes
     * this the one site whose keys were never at risk under the old best-effort delete, and it keeps that
     * property: the row still names the `storage_key` afterwards.
     *
     * **Its own transaction, because this path has none** (ADR 0062 Decision 2): the handler is not
     * `@Transactional` and `StoreReclaimStore.record` is `MANDATORY`, so the requirement is enforced rather than
     * remembered. The old `canPresign` gate is gone with the delete it guarded — recording is cheap, correct on
     * every store, and gating it would put a hole in the hermetic suite's only view of this path.
     */
    private fun purge(sessions: List<UploadSessionEntity>) {
        QuarkusTransaction.requiringNew().run {
            reclaim.record(sessions.map { StoreObjectRef(it.storageKey, it.chunkCount) })
        }
    }

    /**
     * One `listNodeMedia` poll (cr-0107, ADR 0033). Reads the node's `media_revision` + `delete_revision` as a
     * **consistent pair** (one row). When the client's [ifNoneMatch] still equals `media_revision` ⇒ **NotModified**
     * (a 304 — no media query, the idle fast path). Otherwise a **keyset delta** past the [since] cursor, plus both
     * revisions and — **only when [sinceDelete] lags `delete_revision`** (a real delete since) — the current
     * `mediaId` set for delete-reconcile. Non-member / unknown node ⇒ NoNode (a 404, no reveal).
     */
    fun listMedia(
        userId: String,
        scopeId: String,
        nodeId: String,
        ifNoneMatch: Int?,
        since: MediaCursor?,
        sinceDelete: Int,
        trashed: Boolean,
    ): ListMediaOutcome {
        access.check(userId, scopeId, READ)
            .refusal(ListMediaOutcome.NoNode, ListMediaOutcome.Suspended)?.let { return it }
        val node = store.findNode(scopeId, nodeId) ?: return ListMediaOutcome.NoNode
        // The Trash tab (cr-0110): a plain full list of the node's trashed media (carrying deletedAt/deletedBy), no
        // conditional-GET/delta — the trash isn't live-polled, so the ETag/since/id-set machinery doesn't apply.
        if (trashed) return ListMediaOutcome.Page(store.listNodeTrashed(scopeId, nodeId), node.mediaRevision, node.deleteRevision, null)
        val mediaRevision = node.mediaRevision
        val deleteRevision = node.deleteRevision
        if (ifNoneMatch != null && ifNoneMatch == mediaRevision) return ListMediaOutcome.NotModified
        val rows = store.listNodeMediaSince(scopeId, nodeId, since)
        val mediaIds = if (sinceDelete < deleteRevision) store.listNodeMediaIds(scopeId, nodeId) else null
        return ListMediaOutcome.Page(rows, mediaRevision, deleteRevision, mediaIds)
    }

    /** The media's lazy, panel-only tier for the detail panel. Non-member or unknown media → NoMedia (a 404, no reveal). */
    fun mediaExif(userId: String, scopeId: String, mediaId: String): MediaExifOutcome {
        access.check(userId, scopeId, READ)
            .refusal(MediaExifOutcome.NoMedia, MediaExifOutcome.Suspended)?.let { return it }
        val media = store.findMedia(scopeId, mediaId) ?: return MediaExifOutcome.NoMedia
        return MediaExifOutcome.Found(media.wrappedExif)
    }

    /** Overwrite the media's sealed listed-metadata blob (opaque re-seal, e.g. a corrected date). */
    fun updateMeta(userId: String, scopeId: String, mediaId: String, wrappedMeta: String?): MetaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(MetaOutcome.NoMedia, MetaOutcome.Suspended, MetaOutcome.Deleting)?.let { return it }
        val media = store.setMeta(scopeId, mediaId, wrappedMeta) ?: return MetaOutcome.NoMedia
        return MetaOutcome.Found(media.wrappedMeta)
    }

    /**
     * Trash a media (uc.memory.delete-media, cr-0110): **any member** of the scope may soft-delete it into the
     * per-Memory trash (reversible; the irreversible purge is MediaTrashPurge's). Non-member or unknown media →
     * NoMedia (a 404, no reveal). Idempotent.
     */
    fun deleteMedia(userId: String, scopeId: String, mediaId: String): DeleteMediaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(DeleteMediaOutcome.NoMedia, DeleteMediaOutcome.Suspended, DeleteMediaOutcome.Deleting)?.let { return it }
        return if (store.trashMedia(scopeId, mediaId, userId) != null) DeleteMediaOutcome.Done else DeleteMediaOutcome.NoMedia
    }

    /** Restore a trashed media (cr-0110): any member may bring it back before the purge. 404 on unknown/purged. */
    fun restoreMedia(userId: String, scopeId: String, mediaId: String): RestoreMediaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(RestoreMediaOutcome.NoMedia, RestoreMediaOutcome.Suspended, RestoreMediaOutcome.Deleting)?.let { return it }
        return if (store.restoreMedia(scopeId, mediaId) != null) RestoreMediaOutcome.Done else RestoreMediaOutcome.NoMedia
    }

    /**
     * Permanently delete a trashed media NOW (uc.memory.delete-media, cr-0111): any member may expedite the purge
     * of an already-trashed item (the security-model reversible/irreversible split). Non-member, unknown, or a
     * still-LIVE media → NoMedia (a 404). The blob objects go on the reclaim list with the rows.
     */
    fun purgeMedia(userId: String, scopeId: String, mediaId: String): PurgeMediaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(PurgeMediaOutcome.NoMedia, PurgeMediaOutcome.Suspended, PurgeMediaOutcome.Deleting)?.let { return it }
        if (!store.purgeMedia(scopeId, mediaId)) return PurgeMediaOutcome.NoMedia
        return PurgeMediaOutcome.Done
    }

    /**
     * Commit an uploaded blob as the node's **standalone cover** (cr-0073): resolve the node + session, run the
     * same declare-then-verify as `complete` (stat the stored objects against the byte budget, purge + fail on
     * excess), then persist one `veyq_blob` + point the node's `cover_blob_id` at it, atomically reclaiming any
     * prior cover blob (its row goes and its store objects are recorded for reclaim, in the same transaction). No `veyq_media` row — the cover never lists in the
     * gallery. Guards mirror `complete`: member gate (non-member → NoTarget/404), FOLDER → IllegalTarget/422.
     */
    fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String): CoverCommitOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CoverCommitOutcome.NoTarget, CoverCommitOutcome.Suspended, CoverCommitOutcome.Deleting)?.let { return it }
        val node = store.findNode(scopeId, nodeId) ?: return CoverCommitOutcome.NoTarget
        if (node.kind == NodeEntity.FOLDER) return CoverCommitOutcome.IllegalTarget
        val session = store.findSession(scopeId, uploadId, UploadOpener.Member(userId)) ?: return CoverCommitOutcome.NoTarget
        // The cover's K_blob wrap is sealed bound to (this node, COVER) (ADR 0072).
        if (!session.boundTo(node.id, "COVER")) return CoverCommitOutcome.UploadMismatch
        val manifest = when (val v = verifyBlob(session)) {
            is BlobVerify.Ok -> v.manifest
            BlobVerify.Budget -> {
                purge(listOf(session))
                return CoverCommitOutcome.Budget
            }
        }
        return CoverCommitOutcome.Ok(store.commitCover(node.id, session, manifest))
    }

    /** Detach + reclaim the node's standalone cover blob (cr-0073). Idempotent — a node with no cover → Done. */
    fun deleteCover(userId: String, scopeId: String, nodeId: String): CoverDeleteOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CoverDeleteOutcome.NoTarget, CoverDeleteOutcome.Suspended, CoverDeleteOutcome.Deleting)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return CoverDeleteOutcome.NoTarget
        store.clearCover(scopeId, nodeId)
        return CoverDeleteOutcome.Done
    }

    fun download(userId: String, scopeId: String, blobIds: List<String>, domain: String?): DownloadOutcome {
        access.check(userId, scopeId, READ)
            .refusal(DownloadOutcome.NoScope, DownloadOutcome.Suspended)?.let { return it }
        return DownloadOutcome.Results(blobIds.map { downloadOne(scopeId, it, domain) })
    }

    private fun downloadOne(scopeId: String, blobId: String, domain: String?): DownloadItemResult {
        val blob = store.findBlob(scopeId, blobId)
            ?: return DownloadItemResult(presignedGets = null, error = "upload_not_found")
        val gets = blobStore.presignGets(blob.storageKey, blob.chunkCount, domain)
            ?: return DownloadItemResult(presignedGets = null, error = "store_unavailable")
        return DownloadItemResult(presignedGets = gets, error = null)
    }

    // --- buckets (uc.memory.buckets, ADR 0034, cr-0113 · curation relaxed cr-0123) — member-gated: a member may
    // curate any owner-root's SUB-buckets (create/rename/delete) + move media WITHIN one root, but never a primary
    // and never across owners (attribution — the owner_key — is preserved on every path) ---

    /** List the node's uploader buckets for the "Beiträge" tab. Non-member / unknown node → NoNode (404, no reveal). */
    fun listBuckets(userId: String, scopeId: String, nodeId: String): ListBucketsOutcome {
        access.check(userId, scopeId, READ)
            .refusal(ListBucketsOutcome.NoNode, ListBucketsOutcome.Suspended)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return ListBucketsOutcome.NoNode
        return ListBucketsOutcome.Ok(store.listBuckets(scopeId, nodeId))
    }

    /**
     * Create a sub-bucket under a primary root. `parentBucketId == null` ⇒ the caller's own primary; else it must
     * be a **primary** (any owner's — so a member can add sub-buckets under a contributor's root), and the new sub
     * inherits that root's owner_key. A missing/non-primary parent → Forbidden (403). Non-member / unknown node → NoNode (404).
     */
    fun createBucket(userId: String, scopeId: String, nodeId: String, bucketId: String, sealedName: String, parentBucketId: String?): CreateBucketOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CreateBucketOutcome.NoNode, CreateBucketOutcome.Suspended, CreateBucketOutcome.Deleting)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return CreateBucketOutcome.NoNode
        val ownerKey = if (parentBucketId != null) {
            val parent = store.findBucket(scopeId, nodeId, parentBucketId) ?: return CreateBucketOutcome.Forbidden
            if (!parent.isPrimary) return CreateBucketOutcome.Forbidden // nest one level, under a primary only
            parent.ownerKey
        } else {
            OwnerKey.User(userId).format()
        }
        // The client-minted id (ADR 0072): the sealed name is bound to it.
        return store.createSubBucket(bucketId, scopeId, nodeId, ownerKey, sealedName)
            ?.let { CreateBucketOutcome.Ok(it) } ?: CreateBucketOutcome.IdTaken
    }

    /** Rename a sub-bucket (any member may — incl. a contributor's, cr-0123). A primary is not renameable (Forbidden → 403). */
    fun renameBucket(userId: String, scopeId: String, nodeId: String, bucketId: String, sealedName: String): RenameBucketOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(RenameBucketOutcome.NoBucket, RenameBucketOutcome.Suspended, RenameBucketOutcome.Deleting)?.let { return it }
        // ADR 0061: the bucket lookup is keyed on (scope, node, bucket) and never touches the node row, so the
        // node gate every sibling op passes through has to be spelled out here — else a trashed subtree's
        // buckets stay writable while everything else about it is hidden.
        if (store.findNode(scopeId, nodeId) == null) return RenameBucketOutcome.NoBucket
        val b = store.findBucket(scopeId, nodeId, bucketId) ?: return RenameBucketOutcome.NoBucket
        if (b.isPrimary) return RenameBucketOutcome.Forbidden
        val row = store.renameBucket(bucketId, sealedName) ?: return RenameBucketOutcome.NoBucket
        return RenameBucketOutcome.Ok(row)
    }

    /**
     * Delete a sub-bucket — its media reparent to that root's owner primary (never deleted). Any member may
     * (incl. a contributor's sub, cr-0123); a primary is not deletable (Forbidden → 403). Unknown bucket is
     * idempotent (Done → 204).
     */
    fun deleteBucket(userId: String, scopeId: String, nodeId: String, bucketId: String): DeleteBucketOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(DeleteBucketOutcome.NoNode, DeleteBucketOutcome.Suspended, DeleteBucketOutcome.Deleting)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return DeleteBucketOutcome.NoNode // ADR 0061, as in renameBucket
        val b = store.findBucket(scopeId, nodeId, bucketId) ?: return DeleteBucketOutcome.Done // idempotent
        if (b.isPrimary) return DeleteBucketOutcome.Forbidden
        store.deleteBucketReparent(bucketId)
        return DeleteBucketOutcome.Done
    }

    /**
     * Move media into a target bucket WITHIN one owner-root (null = that owner's primary). The store derives the
     * shared owner from the source media and requires the target to match — so a member may re-bucket a
     * contributor's media, but a cross-owner move (or a target of a different owner) → Forbidden (403).
     */
    fun moveMedia(userId: String, scopeId: String, nodeId: String, mediaIds: List<String>, targetBucketId: String?): MoveMediaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(MoveMediaOutcome.NoNode, MoveMediaOutcome.Suspended, MoveMediaOutcome.Deleting)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return MoveMediaOutcome.NoNode
        val ok = store.moveMedia(scopeId, nodeId, mediaIds, targetBucketId)
        return if (ok) MoveMediaOutcome.Done else MoveMediaOutcome.Forbidden
    }
}
