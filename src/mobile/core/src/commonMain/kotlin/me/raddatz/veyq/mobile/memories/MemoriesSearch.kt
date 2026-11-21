package me.raddatz.veyq.mobile.memories

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.api.memories.apis.MediaApi
import me.raddatz.veyq.mobile.api.memories.models.ScopeMediaPage
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.search.MonthSpan
import me.raddatz.veyq.mobile.search.ApplicationSearch
import me.raddatz.veyq.mobile.search.Query
import me.raddatz.veyq.mobile.search.ScopeDocumentsAnswer
import me.raddatz.veyq.mobile.search.ScopeDocumentsContext
import me.raddatz.veyq.mobile.search.ScopeDocument
import me.raddatz.veyq.mobile.search.SearchFacets
import me.raddatz.veyq.mobile.search.YearSpan
import me.raddatz.veyq.mobile.search.localDate
import io.ktor.client.statement.readRawBytes
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.VeyqAccount

/**
 * What Memories adds to core's search (ADR 0125 decisions 6 and 8a; the web's `features/memories/search.ts`, ported for
 * ADR 0146 decision 4) — core never names this; `AppKinds.search` wires it in:
 * - a Memory's and a chapter's facets from its sealed meta ([facets]): its date range as years and months, its place's name,
 *   its participants' user ids — never a name;
 * - a medium's document ([mediumDocument]): its original's name, its capture time and its place; a GPX track and a music
 *   track are no document (`GalleryMedium.shows`), nor is a blocked medium (the caller leaves it out first).
 * Held to `contracts/platform/search/v1-vectors.json`'s `facets` and `medium`.
 */
object MemoriesSearch {
    /** The kind token of a photo's and a video's document (the web's `PHOTO_DOCUMENT`, `VIDEO_DOCUMENT`). */
    const val PHOTO = "memories.photo"
    const val VIDEO = "memories.video"

    /** The icon kinds a photo's and a video's row draws (the web's `image`, `play`). */
    const val PHOTO_ICON = "image"
    const val VIDEO_ICON = "play"

    /** How many media one page of a space's listing carries: the contract's cap (the web's `SCOPE_MEDIA_PAGE`). */
    const val SCOPE_MEDIA_PAGE = 1000

    /** The years a Memory's date spans: `from`'s to `to`'s, an ongoing one's up to [nowMs]'s year; none without a start day. */
    fun yearsOfDate(date: MemoryDate?, nowMs: Long): YearSpan? {
        val from = date?.from?.take(4)?.toIntOrNull() ?: return null
        val to = when {
            date.to != null -> date.to.take(4).toIntOrNull()
            date.mode == MemoryDate.Mode.ONGOING -> maxOf(from, localDate(nowMs).first)
            else -> from
        }
        return YearSpan(from, if (to != null && to >= from) to else from)
    }

    /** The months a Memory's date spans (`YYYY-MM`), an ongoing one's up to [nowMs]'s month; none without a start day. */
    fun monthsOfDate(date: MemoryDate?, nowMs: Long): MonthSpan? {
        val from = date?.from?.take(7)?.takeIf { it.isNotEmpty() } ?: return null
        val (y, m) = localDate(nowMs)
        val thisMonth = Query.monthOf(y, m)
        val to = when {
            date.to != null -> date.to.take(7)
            date.mode == MemoryDate.Mode.ONGOING && thisMonth > from -> thisMonth
            else -> from
        }
        return MonthSpan(from, if (to >= from) to else from)
    }

    /** A Memory's or a chapter's facets from its opened meta (the web's `memorySearchFacets`), at [nowMs]. */
    fun facets(meta: JsonObject, nowMs: Long): SearchFacets {
        val date = MemoryDate.of(meta)
        val place = MemoryLocation.of(meta)?.name?.trim()?.takeIf { it.isNotEmpty() }
        val people = Participants.read(meta)
        return SearchFacets(
            years = yearsOfDate(date, nowMs), months = monthsOfDate(date, nowMs),
            places = place?.let { listOf(it) }, people = people.takeIf { it.isNotEmpty() },
        )
    }

    /** The seals a medium's document is read from: a meta edit or a replaced original is a new document. */
    fun revisionOf(metaRevision: Long, blobId: String): String = "$metaRevision:$blobId"

    /** Whether [type] is one well-formed video essence (the web's `isVideoMediaType`: `video/` and a token). */
    fun isVideoType(type: String?): Boolean {
        if (type == null || !type.startsWith("video/") || type.length == 6) return false
        return type.substring(6).all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it in "!#$%&'*+-.^_`|~" }
    }

    /**
     * Medium [mediaId]'s document of node [nodeId] (the web's `mediumDocument`), from its listed tier [meta] (null: it did not
     * open) and its original's sealed [type] and [name]; null for a medium no photo view shows (a GPX track, a music track).
     * [unnamed] names one whose original carries no name (its words: "Photo", "Video").
     */
    fun mediumDocument(
        mediaId: String, nodeId: String, metaRevision: Long, blobId: String,
        meta: MemoryMedia.Meta?, type: String?, name: String?, unnamed: (video: Boolean) -> String,
    ): ScopeDocument? {
        if (!GalleryMedium.shows(type, name)) return null
        val video = isVideoType(type?.trim())
        val place = meta?.placeName?.trim()?.takeIf { it.isNotEmpty() }
        return ScopeDocument(
            kind = if (video) VIDEO else PHOTO, id = mediaId, nodeId = nodeId, revision = revisionOf(metaRevision, blobId),
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: unnamed(video),
            facets = SearchFacets(capturedAt = meta?.effectiveCapturedAt, places = place?.let { listOf(it) }),
            icon = if (video) VIDEO_ICON else PHOTO_ICON,
        )
    }

    /** Memories' part in the search of every space (`AppKinds.search`): [scopeMediaDocuments]. */
    val search = ApplicationSearch { scopeMediaDocuments(it) }

    /**
     * Every photo and video of one space (the web's `scopeMediaDocuments`; ADR 0125 decision 8a): `listScopeMedia` read by the
     * last answer's tag (a `304`: null, nothing opened), else page by page, [SCOPE_MEDIA_PAGE] at a time, each page opened by
     * [pageDocuments] under the shell's node keys. A kept read where the account keeps a store (ADR 0153 decision 7,
     * [kept]): offline the space's kept listing is searched.
     */
    @Throws(Exception::class)
    suspend fun scopeMediaDocuments(ctx: ScopeDocumentsContext): ScopeDocumentsAnswer? {
        val tenant = ctx.tenant
        if (tenant.account.offline != null) return kept(ctx)
        val api = MediaApi(tenant.base, tenant.account.http)
        val first = tenant.account.respond { api.listScopeMedia(ctx.scopeId, limit = SCOPE_MEDIA_PAGE, ifNoneMatch = ctx.tag) }
        if (first.status == 304 && ctx.tag != null) return null
        if (!first.success) throw IllegalStateException("scope media ${first.status}")
        val tag = first.response.headers[HttpHeaders.ETag]
        var page = first.body()
        val out = ArrayList<ScopeDocument>()
        while (true) {
            out += pageDocuments(tenant.account.suite, ctx.scopeId, page, ctx.nodes, ctx.previous, ctx.unnamed)
            val last = page.media.lastOrNull()?.mediaId
            if (!page.more || last == null) break
            page = tenant.account.call { api.listScopeMedia(ctx.scopeId, after = last, limit = SCOPE_MEDIA_PAGE) }
        }
        return ScopeDocumentsAnswer(tag, out)
    }

    /**
     * [scopeMediaDocuments] through the offline store (`MemoriesKept.SCOPE_MEDIA`, automatic tier): the kept `ETag` sent as
     * `If-None-Match`, a newer listing replacing the kept one — never merged, so a medium trashed elsewhere is gone with the
     * next answer —, the kept listing while the server is unreachable. The answer is null where its tag is still [ctx]'s.
     */
    private suspend fun kept(ctx: ScopeDocumentsContext): ScopeDocumentsAnswer? {
        val tenant = ctx.tenant
        val api = MediaApi(tenant.base, tenant.account.http)
        val read = try {
            tenant.account.kept(MemoriesKept.SCOPE_MEDIA, tenant.tenant.tenantId, ctx.scopeId, ctx.scopeId, conditional = true) { tag -> pages(tenant, api, ctx.scopeId, tag) }
        } catch (e: VeyqAccount.Refused) {
            throw IllegalStateException("scope media ${e.status}")
        }
        if (ctx.tag != null && read.revision == ctx.tag) return null
        val out = ArrayList<ScopeDocument>()
        for (raw in read.pages) {
            val page = VeyqAccount.KEPT_JSON.decodeFromString(ScopeMediaPage.serializer(), raw.decodeToString())
            out += pageDocuments(tenant.account.suite, ctx.scopeId, page, ctx.nodes, ctx.previous, ctx.unnamed)
        }
        return ScopeDocumentsAnswer(read.revision, out)
    }

    /** Every page of [scopeId]'s media as the server sent them, the first conditional on [tag] (the kept `ETag`). */
    private suspend fun pages(tenant: me.raddatz.veyq.mobile.session.TenantSession, api: MediaApi, scopeId: String, tag: String?): VeyqAccount.Fetched {
        val out = ArrayList<ByteArray>()
        var after: String? = null
        var revision: String? = null
        while (true) {
            val first = after == null
            val r = tenant.account.respond { api.listScopeMedia(scopeId, after = after, limit = SCOPE_MEDIA_PAGE, ifNoneMatch = if (first) tag else null) }
            if (first && r.status == 304) return VeyqAccount.Fetched.NotModified
            if (!r.success) return VeyqAccount.Fetched.Refused(r.status, tenant.account.errorCode(r))
            // a later page carries no tag: the first page's stands for the read
            if (first) revision = r.response.headers[HttpHeaders.ETag]
            val raw = r.response.readRawBytes()
            out += raw
            val page = VeyqAccount.KEPT_JSON.decodeFromString(ScopeMediaPage.serializer(), raw.decodeToString())
            val last = page.media.lastOrNull()?.mediaId
            if (!page.more || last == null || last == after) break
            after = last
        }
        return VeyqAccount.Fetched.Pages(out, revision)
    }

    /**
     * One page of a space's listing as documents, one medium at a time (cancellable between two): a blocked medium left
     * before anything of it is opened; one at an unchanged revision of the same node kept as [previous] holds it; every
     * other opened under its node's key — the shell's ([nodes], read in place, never copied or zeroed here) or, for a
     * drop box's guest, the guest's `K_up` — with `MemoryMedia`'s own openers. **What this walk unwraps it zeroes before
     * it returns, a thrown walk included** (ADR 0146 decision 5): each original's `K_blob`, each drop box's private seed and
     * each guest's `K_up`.
     */
    internal suspend fun pageDocuments(
        suite: V1, scopeId: String, page: ScopeMediaPage, nodes: Map<String, OpenNode>, previous: Map<String, ScopeDocument>,
        unnamed: (Boolean) -> String,
    ): List<ScopeDocument> {
        val out = ArrayList<ScopeDocument>()
        val upKeys = HashMap<String, ByteArray?>()
        val pageNodes = page.nodes.associateBy { it.nodeId }
        fun keyOf(ownerKey: String?, node: OpenNode): ByteArray? {
            val drop = pageNodes[node.id]
            if (ownerKey == null || !ownerKey.startsWith("share:") || drop == null) return node.key
            val identity = ownerKey.removePrefix("share:")
            val wrapped = drop.uploaderKeys.firstOrNull { it.identityId == identity }?.wrappedIdentityKey ?: return node.key
            return upKeys.getOrPut("${node.id}:$identity") {
                runCatching {
                    val serialized = suite.open(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to node.id), node.key, unb64(drop.sealedDropPrivateKey))
                    val seed = suite.deserializeDropKey(serialized)
                    val dropKey = suite.xwingKey(seed)
                    try {
                        suite.openAs(
                            Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to node.id, "identityId" to identity),
                            dropKey, unb64(wrapped),
                        )
                    } finally {
                        serialized.fill(0)
                        seed.fill(0)
                        dropKey.mlKemDk.fill(0)
                        dropKey.x25519Sk.fill(0)
                    }
                }.getOrNull()
            }
        }
        try {
            for (row in page.media) {
                currentCoroutineContext().ensureActive()
                if (row.blockedByOperator) continue
                val before = previous[row.mediaId]
                if (before != null && before.revision == revisionOf(row.metaRevision, row.blobId) && before.nodeId == row.nodeId) {
                    out += before
                    continue
                }
                val node = nodes[row.nodeId] ?: continue
                val key = keyOf(row.ownerKey, node) ?: continue // a chain that does not open: its media are not searched
                val meta = MemoryMedia.openMeta(suite, scopeId, row.mediaId, key, row.wrappedMeta)
                var type: String? = null
                var name: String? = null
                row.wrappedBlobMeta?.let { sealed ->
                    val blob = MemoryMedia.Blob(row.blobId, row.chunkCount, row.byteSize, row.wrappedBlobKey, sealed)
                    val kBlob = MemoryMedia.openBlobKey(suite, scopeId, row.mediaId, key, blob, MemoryMedia.ORIGINAL)
                    if (kBlob != null) {
                        try {
                            BlobMeta.open(suite, kBlob, unb64(sealed))?.let {
                                type = it.type.takeIf { t -> t.isNotBlank() }
                                name = it.name
                            }
                        } finally {
                            kBlob.fill(0)
                        }
                    }
                }
                mediumDocument(row.mediaId, row.nodeId, row.metaRevision, row.blobId, meta, type, name, unnamed)?.let { out += it }
            }
        } finally {
            for (k in upKeys.values) k?.fill(0)
        }
        return out
    }
}
