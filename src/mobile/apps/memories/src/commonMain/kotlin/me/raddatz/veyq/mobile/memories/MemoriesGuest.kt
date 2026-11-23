package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import me.raddatz.veyq.mobile.api.memories.apis.ShareMediaApi
import me.raddatz.veyq.mobile.api.memories.models.ShareMedia
import me.raddatz.veyq.mobile.api.memories.models.ShareMediaBatchRequest
import me.raddatz.veyq.mobile.api.memories.models.ShareMediaDerivative
import me.raddatz.veyq.mobile.api.memories.models.ShareMediaRequestItem
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.sharing.AskedUpload
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestDoor
import me.raddatz.veyq.mobile.sharing.GuestPage
import me.raddatz.veyq.mobile.sharing.GuestSession

/**
 * Memories' answers to a link's guest (ADR 0150 decision 5; the web's `MemoryShareBody` and the gallery's guest source,
 * `features/memories/share.ts`): a Memory or a chapter of a READ or READ_WRITE link opened as its gallery with
 * `MemoryRights.Access.Guest(role)` — the member's page, a guest's rights — over the link's media reads, and a READ_WRITE
 * guest's uploads into the node on screen; a WRITE_ONLY link's uploader ([GuestUploader]). Memories' own rules, core's
 * guest calls; nothing of an account.
 */
object MemoriesGuest {
    /** A Memory or a chapter as a link's guest sees it: its gallery with a guest's access. */
    class Gallery(override val node: NodeAt, val model: GalleryModel) : GuestPage()

    /** A Memory's or a chapter's WRITE_ONLY link: the uploader (the guest names itself, adds photos and videos). */
    class Uploader(override val node: NodeAt, val model: GuestUploader) : GuestPage()

    /** A Memory or a chapter of a READ or READ_WRITE link: its gallery. */
    val gallery = GuestDoor { access, node, _ ->
        val opened = access.tree?.node(node.nodeId) ?: return@GuestDoor GuestPage.NotShown(node)
        // its music as the member's (#1034): the player the host handed the link — the account's, or the app's signed out
        MemoriesGuest.Gallery(node, GalleryModel(node, GuestGalleryDoor(access, opened), access = MemoryRights.Access.Guest(access.role), music = access.music))
    }

    /** A Memory's or a chapter's WRITE_ONLY link: the uploader. */
    val uploader = GuestDoor { access, node, _ -> MemoriesGuest.Uploader(node, GuestUploader(access, node)) }

    private fun roleOf(item: MemoryMedia.Item, blob: MemoryMedia.Blob): String = when (blob) {
        item.thumbnail -> MemoryMedia.THUMBNAIL
        item.preview -> MemoryMedia.PREVIEW
        else -> MemoryMedia.ORIGINAL
    }

    /** A gallery over the link (the member's `GalleryModel.Door`, the guest's reads and a READ_WRITE guest's uploads). */
    @PlatformApi
    @HiddenFromObjC
    class GuestGalleryDoor(private val access: GuestAccess, private val node: OpenNode) : GalleryModel.Door {
        private val api get() = ShareMediaApi(access.origin, access.client.http)
        private val suite get() = access.suite
        private val own = HashSet<String>()
        private val _uploaded = MutableSharedFlow<String>(extraBufferCapacity = 16)

        override suspend fun gallery(): MemoryMedia.Gallery? {
            val media = ArrayList<ShareMedia>()
            val identities = HashMap<String, String>()
            var after: String? = null
            while (true) {
                val r = api.listShareMedia(access.shareId, access.pwToken, node.id, after, PAGE)
                if (!r.success) throw IllegalStateException("share media ${r.status}")
                val page = r.body()
                media += page.media
                page.identities.forEach { identities[it.identityId] = it.wrappedIdentityKey }
                if (!page.more) break
                val last = page.media.lastOrNull()?.mediaId
                if (last == null || last == after) throw IllegalStateException("share_media_page_did_not_advance")
                after = last
            }
            val upKeys = HashMap<String, ByteArray?>()
            val items = media.mapNotNull { m ->
                val at = access.tree?.node(m.nodeId) ?: return@mapNotNull null
                val key = keyOf(at, m, identities, upKeys) ?: return@mapNotNull null
                itemOf(m, at, key)
            }
            return MemoryMedia.Gallery(MemoryMedia.order(items), 0, 0)
        }

        /** The key [m] opens under: its node's, or a WRITE_ONLY uploader's `K_up` through the node's drop key (ADR 0031 point 7). */
        private fun keyOf(at: OpenNode, m: ShareMedia, identities: Map<String, String>, upKeys: HashMap<String, ByteArray?>): ByteArray? {
            val identity = m.identityId ?: return at.key
            val sealedDrop = at.sealedDropPrivateKey ?: return at.key
            val wrapped = identities[identity] ?: return at.key
            return upKeys.getOrPut(identity) {
                runCatching {
                    val seed = suite.deserializeDropKey(suite.open(Context("drop-private-key", "scopeId" to at.scopeId, "nodeId" to at.id), at.key, unb64(sealedDrop)))
                    suite.openAs(Context("upload-identity-key", "scopeId" to at.scopeId, "nodeId" to at.id, "identityId" to identity), suite.xwingKey(seed), unb64(wrapped))
                }.getOrNull()
            }
        }

        private fun itemOf(m: ShareMedia, at: OpenNode, key: ByteArray): MemoryMedia.Item {
            val original = MemoryMedia.Blob(m.blobId, m.chunkCount, m.byteSize, m.wrappedBlobKey, m.wrappedBlobMeta)
            fun tier(kind: ShareMediaDerivative.Kind) = m.derivatives.orEmpty().firstOrNull { it.kind == kind }?.let {
                MemoryMedia.Blob(it.blobId, it.chunkCount, it.byteSize, it.wrappedBlobKey, it.wrappedBlobMeta)
            }
            val opened = original.wrappedBlobMeta?.let { sealed ->
                MemoryMedia.openBlobKey(suite, at.scopeId, m.mediaId, key, original, MemoryMedia.ORIGINAL)?.let { BlobMeta.open(suite, it, unb64(sealed)) }
            }
            return MemoryMedia.Item(
                at.scopeId, at.id, m.mediaId, original, tier(ShareMediaDerivative.Kind.THUMBNAIL), tier(ShareMediaDerivative.Kind.PREVIEW),
                MemoryMedia.openMeta(suite, at.scopeId, m.mediaId, key, m.wrappedMeta), opened?.type?.takeIf { it.isNotBlank() }, opened?.name,
                m.identityId?.let { "share:$it" }, m.blockedByOperator, key, 0, m.wrappedMeta, opened?.sha256, opened?.modifiedAt, null,
            )
        }

        override suspend fun scopeNodes(): List<NodeRef> = access.tree?.nodes?.map { it.asNode() }.orEmpty()

        private fun blobKey(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? =
            if (item.blocked) null else MemoryMedia.openBlobKey(suite, item.scopeId, item.mediaId, item.key, blob, roleOf(item, blob))

        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? {
            val key = blobKey(item, blob) ?: return null
            return access.blobs.whole(blob.blobId, blob.chunkCount, key)
        }

        override fun reader(item: MemoryMedia.Item): RangeReader? {
            val key = blobKey(item, item.original) ?: return null
            return access.blobs.reader(item.original.blobId, item.original.chunkCount, item.original.size, key)
        }

        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit): Boolean {
            val key = blobKey(item, item.original) ?: return false
            access.blobs.Frames(item.original.blobId, item.original.chunkCount, key).read(0, item.original.chunkCount, sink)
            return true
        }

        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null

        /** A guest reads no member's name (ADR 0108 decision 2; a READ guest's download carries none, ADR 0149 decision 9). */
        override suspend fun uploaders(items: List<MemoryMedia.Item>): Map<String, MediaDetails.Uploader> = emptyMap()

        /** A READ_WRITE guest not named yet is asked its name here, at the write ([GuestAccess.naming], #1044). */
        override suspend fun named(): Boolean = access.role == GuestAccess.READ_WRITE && access.naming.request() != null

        override fun upload(sources: List<UploadSource>): Int {
            val queue = access.queue ?: return 0
            val deriver = access.deriver ?: return 0
            val session = access.session.value ?: return 0
            if (access.role != GuestAccess.READ_WRITE || sources.isEmpty()) return 0
            val target = node.id.takeIf { it != access.tree?.root?.id }
            val nodeEpoch = if (target == null) access.rootEpoch else node.epoch
            queue.enqueue(
                sources.map { source ->
                    MediaUpload.GuestJob(source, deriver, access.now) { original, prepared, progress ->
                        MemoriesGuest.send(access, session, node.scopeId, node.key, nodeEpoch, target, original, prepared, progress).also { r ->
                            r.mediaId?.let { own += it }
                            _uploaded.tryEmit(node.id)
                        }
                    }
                },
                node.name, null, null, null,
            )
            return sources.size
        }

        override val revisions: Flow<Any?> = flowOf(Unit)
        override val uploaded: Flow<String> = _uploaded
        override fun isOwn(mediaId: String): Boolean = mediaId in own

        override suspend fun purge(mediaId: String): Boolean {
            val token = access.session.value?.contributorToken ?: return false
            return runCatching { api.deleteShareMedia(access.shareId, mediaId, token, access.pwToken).success }.getOrDefault(false).also { if (it) own -= mediaId }
        }

        override val origin: String get() = access.origin

        /** A medium the guest sees reported as an `ITEM` to the link's own operator (#1033; the web's guest `mediumReportKeys`, no EXIF). */
        override suspend fun report(item: MemoryMedia.Item, category: String, reason: String, contact: String) =
            me.raddatz.veyq.mobile.sharing.GuestReport.reportItem(access, item.mediaId, MediumReport.keys(suite, item), category, reason, contact)

        /** Whether the link's instance takes reports (a viewing guest's report, #1033). */
        override suspend fun reportOffered(): Boolean = access.role != GuestAccess.WRITE_ONLY && me.raddatz.veyq.mobile.sharing.GuestReport.offered(access)
    }

    /** One guest medium sealed under [sealKey], sent through the link and completed (`completeShareMedia`, [nodeEpoch] stated). */
    @PlatformApi
    @HiddenFromObjC
    suspend fun send(
        access: GuestAccess, session: GuestSession, scopeId: String, sealKey: ByteArray, nodeEpoch: Int, target: String?,
        original: MediaUpload.Original, prepared: MediaUpload.Prepared, progress: (Double) -> Unit,
    ): MediaUpload.Result = MediaUpload.attachAsGuest(
        access.suite, access.uuid(), scopeId, sealKey, original, prepared,
        reserve = { mediaId, blobs, sent -> access.blobs.reserveAndPumpAll(scopeId, mediaId, sealKey, session.contributorToken, blobs, target, sent) },
        complete = { item: ShareMediaRequestItem ->
            val r = ShareMediaApi(access.origin, access.client.http).completeShareMedia(
                access.shareId, ShareMediaBatchRequest(nodeEpoch, session.contributorToken, listOf(item), target), access.pwToken,
            )
            if (!r.success) throw ApplicationBlobs.UploadRefused(me.raddatz.veyq.mobile.sharing.GuestBlobs.codeOf(runCatching { r.response.bodyAsText() }.getOrDefault("")) ?: "complete ${r.status}")
            val res = r.body().results.singleOrNull() ?: throw ApplicationBlobs.UploadRefused("complete_mismatch")
            res.error?.let { throw ApplicationBlobs.UploadRefused(it) }
            if (res.mediaId != item.mediaId) throw ApplicationBlobs.UploadRefused("id_substituted")
        },
        progress = progress,
    )

    private const val PAGE = 500
}

/**
 * A Memory's or a chapter's page for the guest of its WRITE_ONLY link (ADR 0150 decision 5; the web's `MemoryShareBody`):
 * the guest is asked its name at its first upload ([uploadAsking], the core's one naming flow; never by a form of the
 * page's, #1090) — a fresh `K_up` wrapped to the root's drop public key, its session in the credential store — and each
 * photo or video it adds is sealed under `K_up` into the link's root; it sees nothing of the Memory, only what it
 * sent in this visit, and removes its own.
 */
class GuestUploader internal constructor(private val access: GuestAccess, val node: NodeAt) {
    val session: StateFlow<GuestSession?> get() = access.session

    private val _sent = MutableStateFlow<List<Sent>>(emptyList())

    /** What this device sent in this visit, newest last. */
    val sent: StateFlow<List<Sent>> = _sent.asStateFlow()

    data class Sent(val mediaId: String, val name: String)

    /** Whether the platform's tiers and the upload queue are at hand (else nothing is offered). */
    val offered: Boolean get() = access.queue != null && access.deriver != null

    /** *Not you?*: this device's session forgotten; the next upload asks a name again. */
    fun forget() = access.forgetGuest()

    /** [sources] queued into the link's root; how many were (none before the guest named itself). */
    fun upload(sources: List<UploadSource>): Int {
        val queue = access.queue ?: return 0
        val deriver = access.deriver ?: return 0
        if (session.value == null || sources.isEmpty()) return 0
        queue.enqueue(sources.map { source -> MediaUpload.GuestJob(source, deriver, access.now) { o, p, progress -> send(o, p, progress, source.name) } }, node.name, null, null, null)
        return sources.size
    }

    /**
     * [sources] queued as [upload] does, a guest not named yet asked its name first through the core's one naming flow
     * ([GuestAccess.naming], ADR 0154 decision 1: an uploader's own `K_up`); declined or refused ([AskedUpload], #1091),
     * none is queued (and each is the caller's to close).
     */
    suspend fun uploadAsking(sources: List<UploadSource>): AskedUpload {
        if (!offered || sources.isEmpty()) return AskedUpload.REFUSED
        access.naming.request() ?: return AskedUpload.unnamed(access)
        return AskedUpload(upload(sources))
    }

    /** One medium sent now under the guest's `K_up` (the queue's job, a test's). */
    @PlatformApi
    @HiddenFromObjC
    suspend fun send(original: MediaUpload.Original, prepared: MediaUpload.Prepared, progress: (Double) -> Unit, name: String): MediaUpload.Result {
        val session = session.value ?: return MediaUpload.Result(MediaUpload.Outcome.FAILED, code = "unnamed")
        val kUp = access.uploaderKey(session) ?: return MediaUpload.Result(MediaUpload.Outcome.FAILED, code = "no_uploader_key")
        try {
            val r = MemoriesGuest.send(access, session, access.scopeId, kUp, access.rootEpoch, null, original, prepared, progress)
            r.mediaId?.let { _sent.value = _sent.value + Sent(it, name) }
            return r
        } finally {
            kUp.fill(0)
        }
    }

    /** The guest's own medium [mediaId] removed (session-only delete-own); whether it went. */
    suspend fun remove(mediaId: String): Boolean {
        val token = session.value?.contributorToken ?: return false
        val ok = runCatching {
            ShareMediaApi(access.origin, access.client.http).deleteShareMedia(access.shareId, mediaId, token, access.pwToken).success
        }.getOrDefault(false)
        if (ok) _sent.value = _sent.value.filter { it.mediaId != mediaId }
        return ok
    }
}
