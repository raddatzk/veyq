package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import me.raddatz.veyq.core.blobstore.PresignedRef
import me.raddatz.veyq.core.blobstore.StoreObjectRef
import me.raddatz.veyq.core.blobstore.StoreReclaimStore
import me.raddatz.veyq.core.blobstore.presignGets
import me.raddatz.veyq.core.blobstore.presignPuts
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Per-item result of `presign-download`. */
data class DownloadItemResult(val presignedGets: List<PresignedRef>?, val error: String?)

sealed interface StartOutcome {
    data class Results(val items: List<UploadItemResult>) : StartOutcome
    object Suspended : StartOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : StartOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : StartOutcome, HttpFailure by Failures.scopeNotFound
    /** A declared role no application attaches a member's upload in ([MemberBlobRoles]): the whole request, a 400. */
    object InvalidRequest : StartOutcome, HttpFailure by Failures.invalidRequest
}

sealed interface WindowOutcome {
    data class Results(val items: List<WindowItemResult>) : WindowOutcome
    object Suspended : WindowOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : WindowOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : WindowOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface DownloadOutcome {
    data class Results(val items: List<DownloadItemResult>) : DownloadOutcome
    object Suspended : DownloadOutcome, HttpFailure by Failures.tenantSuspended
    object NoScope : DownloadOutcome, HttpFailure by Failures.scopeNotFound
}

/**
 * The generic blob upload (ADR 0013), shared by every application that stores blobs. Operator-blind, batch-shaped,
 * per-item results. Scope access is the one gate, [ScopeAccess], asked first (uc.spaces.scope-access): a non-member
 * sees a top-level 404, not a reveal. Direct-to-store: bytes never flow through here — `start` presigns a blob's
 * first window of PUTs and `window` each next one, [verifyBlob] stats the stored objects (declare-then-verify),
 * `download` presigns GETs. A session acts only for its [UploadOpener], and every window is authorized again, so a
 * removed member stops at their next window (ADR 0013 §2).
 *
 * Two gates (ADR 0013 §3): the **declared** shape is checked at `start` — a size over the cap is
 * `budget_exceeded`, a `chunkCount` the size does not derive (or a size no frame sequence has) is `shape_invalid` —
 * and the **actual** objects are verified against it when an application attaches them ([verifyBlob]): every chunk at
 * exactly its derived length, else [purge] + `budget_exceeded`. What a completed blob becomes is the application's
 * ([UploadCompletion]). The in-memory dev store has no transport, so its verify trusts the declaration (manifest
 * null); the byte path is covered by the opt-in store e2e.
 */
@ApplicationScoped
class BlobUploadHandler(
    private val store: BlobUploadStore,
    private val blobStore: BlobStore,
    private val reclaim: StoreReclaimStore,
    private val access: ScopeAccess,
    private val quota: StorageQuota,
    /** The roles the applications attach a member's upload in (see [MemberBlobRoles]); none → no member start.
     *  `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor). */
    @All private val memberRoles: MutableList<MemberBlobRoles>,
    config: VeyqConfig,
) {
    private val maxBytes: Long = config.blob().maxBytes()
    private val window: Int = config.blob().presignWindow()

    fun start(userId: String, scopeId: String, declares: List<UploadDeclare>, domain: String?): StartOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(StartOutcome.NoScope, StartOutcome.Suspended, StartOutcome.Deleting)?.let { return it }
        // The role is an open token on the wire; one no application attaches a member's upload in would hold its quota
        // reservation until the stale-session purge (inbox #366). Refused whole, as a guest's start refuses a role its
        // target's application does not take — and only past the access check, so a non-member still learns its 404.
        if (declares.any { !MemberBlobRoles.admits(memberRoles, it.role) }) return StartOutcome.InvalidRequest
        return StartOutcome.Results(reserveAndOpen(scopeId, UploadOpener.Member(userId), link = null, declares, domain))
    }

    /**
     * The start of an upload link — READ_WRITE or WRITE_ONLY link [shareId], opened by its identity [identityId]. The
     * caller does its own authorization (the link's gate), which runs first; the reservation is [reserveAndOpen]'s,
     * with the per-link limit checked beside the tenant quota, the link's usage measured by the application that takes
     * uploads into a node of [targetKind] (the upload's target, `ShareGuestGate.target`).
     */
    fun startForLink(
        scopeId: String, shareId: String, targetKind: String, identityId: String, declares: List<UploadDeclare>, domain: String?,
    ): List<UploadItemResult> =
        reserveAndOpen(scopeId, UploadOpener.Contributor(identityId), LinkStart(shareId, targetKind), declares, domain)

    /** A link start's two keys to the per-link limit: the link, and the kind of the node its uploads are for. */
    private data class LinkStart(val shareId: String, val targetKind: String)

    /**
     * Every start into the store — a member's upload, a cover, a link upload — reserved against what the tenant may
     * hold (ADR 0073 §5). Bytes never flow through here. One transaction:
     *  1. lock the tenant row, so concurrent starts into the tenant serialize (uc.admin.tenant-storage-quota A5;
     *     `TenantStorageQuotaSliceTest.aStartWhileAnotherReservationIsUncommittedCountsIt` reddens without it on
     *     every run and both engines, inbox #330);
     *  2. check each item on its own ([admissibility]);
     *  3. ask [StorageQuota] whether the admissible items fit **together**: the tenant quota always, and the per-link
     *     limit too when [link] names a link. The two are independent, and either one refuses the whole
     *     request — every admissible item `quota_exceeded`, no session inserted — so an original never lands without its
     *     derivatives; admitting item by item would reserve an original alone until the stale-session purge;
     *  4. otherwise insert every admissible session, and commit.
     * Presigning runs after the commit: the lock covers the check and the inserts only, never up to 256 × window
     * signatures.
     */
    private fun reserveAndOpen(
        scopeId: String,
        opener: UploadOpener,
        link: LinkStart?,
        declares: List<UploadDeclare>,
        domain: String?,
    ): List<UploadItemResult> {
        val reserve = {
            val tenant = ScopeEntity.findById(scopeId)?.tenantId?.let { quota.lockTenant(it) }
            // Checked under the lock, not before it: the sessions are inserted together at the end, so a blob id
            // two racing requests (or two items of this one) both claim would otherwise pass the check and fail
            // the unique index at insert — a 500 instead of `id_taken`.
            val checked = admissibility(declares)
            val admissible = checked.filter { (_, error) -> error == null }.map { (d, _) -> d }
            val fits = tenant != null &&
                quota.tenantFits(tenant, admissible) &&
                (link == null || quota.linkFits(tenant, link.shareId, link.targetKind, admissible))
            if (!fits) {
                checked.map { (d, error) -> d to (error ?: "quota_exceeded") } to checked.map { null }
            } else {
                checked to checked.map { (d, error) -> if (error != null) null else openSession(scopeId, opener, d) }
            }
        }
        // The lock orders starts into ONE tenant; a blob id is unique across all of them, so two starts into
        // different tenants can still both pass the check, and the loser dies on `ux_upload_session_blob_id`. Its
        // replay answers per item — the taken item `id_taken`, the others opened — so when a declared id is now
        // taken, the conflict answer is the reservation run again, in the fresh transaction that sees the winner.
        val (checked, opened) = createOrConflict(
            conflict = { if (declares.any { store.blobIdTaken(it.blobId) }) reserve() else null },
            create = reserve,
        )
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

    sealed interface BlobVerify {
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
    fun verifyBlob(session: UploadSessionEntity): BlobVerify {
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
    fun purge(sessions: List<UploadSessionEntity>) {
        QuarkusTransaction.requiringNew().run {
            reclaim.record(sessions.map { StoreObjectRef(it.storageKey, it.chunkCount) })
        }
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
}
