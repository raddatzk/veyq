package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.api.memories.apis.BucketsApi
import me.raddatz.veyq.mobile.api.memories.models.BucketListResponse
import me.raddatz.veyq.mobile.api.memories.models.BucketSummary
import me.raddatz.veyq.mobile.api.memories.models.CreateBucketRequest
import me.raddatz.veyq.mobile.api.memories.models.MoveMediaRequest
import me.raddatz.veyq.mobile.api.memories.models.RenameBucketRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.offline.KeptRead
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece

/**
 * A Memory's contributions (`uc.memory.buckets`, `en.memory.buckets`, ADR 0034, ADR 0149 decision 6; the web's
 * `mediaBuckets.ts` with `MemoryBuckets.vue`'s rows): its uploader buckets — each uploader's primary and the sub-buckets
 * curated below it — listed (a kept read, ADR 0140 decision 5: offline the names stay), created, renamed, deleted and
 * filled. A sub-bucket's name is sealed under the node's key (`bucket-name`, bound to scope + node + bucket); a READ_WRITE
 * contributor's primary carries its guest's own name (`share-identity-name`, under the key of its link's ROOT — this node or
 * an ancestor, ADR 0080 decision 6); a WRITE_ONLY guest's under that guest's `K_up`, opened through the node's drop key. A
 * guest's name is marked as a guest's ("Ben (guest)", inbox #433), so a guest cannot pass for a member.
 */
object Buckets {
    /** The most ids one move request carries (`MoveMediaRequest.mediaIds.maxItems`). */
    const val MOVE_MAX_IDS = 256

    /** The own primary before it exists: the web's virtual `SELF_PRIMARY`, your unbucketed media. */
    const val SELF = "self"

    /** One bucket as listed. */
    class Bucket(
        val bucketId: String, val ownerKey: String, val parentBucketId: String?, val isPrimary: Boolean, val mediaCount: Int,
        val shareId: String?, internal val sealedName: String?, internal val wrappedIdentityKey: String?,
    ) {
        /** A guest's primary (a link's identity, `share:<identityId>`). */
        val guest: Boolean get() = isPrimary && ownerKey.startsWith("share:")
    }

    /** A node's buckets and the drop key a WRITE_ONLY guest's `K_up` opens through. */
    class Listed(val buckets: List<Bucket>, internal val sealedDropPrivateKey: String?)

    private fun of(b: BucketSummary) = Bucket(b.bucketId, b.ownerKey, b.parentBucketId, b.isPrimary, b.mediaCount, b.shareId, b.sealedName, b.wrappedIdentityKey)

    internal fun nameContext(scopeId: String, nodeId: String, bucketId: String) = Context("bucket-name", "scopeId" to scopeId, "nodeId" to nodeId, "bucketId" to bucketId)

    /**
     * A bucket's name, opened (`openBucketName`): a guest's primary under its `K_up` (WRITE_ONLY) or under [nodeKey] and the
     * node's [ancestorKeys] nearest first (READ_WRITE); a sub-bucket's under [nodeKey]. Null where none opens or it has none
     * (a member's primary: the member is named by the directory).
     */
    fun name(suite: V1, scopeId: String, nodeId: String, nodeKey: ByteArray, listed: Listed, b: Bucket, ancestorKeys: List<ByteArray> = emptyList()): String? {
        val sealed = b.sealedName ?: return null
        return runCatching {
            if (b.guest && b.shareId != null) {
                val identity = b.ownerKey.removePrefix("share:")
                val keys = b.wrappedIdentityKey?.let { w -> listOfNotNull(upKey(suite, scopeId, nodeId, nodeKey, listed.sealedDropPrivateKey, identity, w)) }
                    ?: (listOf(nodeKey) + ancestorKeys)
                val ctx = Context("share-identity-name", "scopeId" to scopeId, "shareId" to b.shareId, "identityId" to identity)
                keys.firstNotNullOfOrNull { k -> runCatching { suite.openField(ctx, k, unb64(sealed)).decodeToString() }.getOrNull() }
            } else {
                suite.openField(nameContext(scopeId, nodeId, b.bucketId), nodeKey, unb64(sealed)).decodeToString()
            }
        }.getOrNull()
    }

    /** A WRITE_ONLY guest's `K_up`, opened through the node's drop key; null where it does not open. */
    private fun upKey(suite: V1, scopeId: String, nodeId: String, nodeKey: ByteArray, sealedDrop: String?, identity: String, wrapped: String): ByteArray? = runCatching {
        val seed = suite.deserializeDropKey(suite.open(Context("drop-private-key", "scopeId" to scopeId, "nodeId" to nodeId), nodeKey, unb64(sealedDrop!!)))
        suite.openAs(Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to nodeId, "identityId" to identity), suite.xwingKey(seed), unb64(wrapped))
    }.getOrNull()

    /** One row of the Contributions picker: a bucket at its depth, its label, its count, whether it is yours to curate. */
    class Row(val bucketId: String, val label: Piece, val depth: Int, val mediaCount: Int, val isPrimary: Boolean, val canManage: Boolean, val virtual: Boolean)

    /**
     * The picker's rows (`MemoryBuckets.vue`'s `bucketRows`): each primary with the sub-buckets under it, depth first, siblings
     * by label; a guest's primary "Name (guest)" (or "Contribution" where its name does not open), a member's by [people]
     * (else "Member"), a sub-bucket by its own name; where no own primary is materialized yet, a virtual "You" first holding
     * the members' unbucketed media ([unbucketed]). [label] sorts by the words a platform would show (the label's first
     * argument, else its key: the platform's collation is not the core's).
     */
    fun rows(listed: Listed, names: Map<String, String?>, people: (String) -> String?, unbucketed: Int): List<Row> {
        fun said(n: Notice) = Piece.Said(n)
        fun label(b: Bucket): Piece {
            val opened = names[b.bucketId]
            return when {
                b.guest -> said(opened?.let { Notice(Msg.MEMORY_UPLOADER_GUEST_NAMED, it) } ?: Notice(Msg.MEMORY_BUCKETS_CONTRIBUTOR))
                !b.isPrimary -> opened?.let { Piece.Plain(it) } ?: said(Notice(Msg.MEMORY_BUCKETS_CONTRIBUTOR))
                b.ownerKey.startsWith("user:") -> people(b.ownerKey.removePrefix("user:"))?.let { Piece.Plain(it) } ?: said(Notice(Msg.MEMORY_BUCKETS_MEMBER))
                else -> said(Notice(Msg.MEMORY_BUCKETS_CONTRIBUTOR))
            }
        }
        class Vm(val id: String, val parent: String?, val label: Piece, val count: Int, val primary: Boolean, val manage: Boolean, val virtual: Boolean)
        val all = listed.buckets.map { Vm(it.bucketId, it.parentBucketId, label(it), it.mediaCount, it.isPrimary, it.ownerKey.startsWith("user:"), false) }
        val withSelf = if (listed.buckets.any { it.isPrimary && it.ownerKey.startsWith("user:") }) all
        else listOf(Vm(SELF, null, said(Notice(Msg.MEMORY_BUCKETS_YOU)), unbucketed, true, true, true)) + all
        fun key(p: Piece) = when (p) {
            is Piece.Plain -> p.text
            is Piece.Said -> p.notice.args.firstOrNull() as? String ?: p.notice.msg.name
            else -> ""
        }.lowercase()
        val kids = withSelf.groupBy { it.parent }.mapValues { (_, v) -> v.sortedBy { key(it.label) } }
        val out = ArrayList<Row>()
        fun visit(v: Vm, depth: Int) {
            out += Row(v.id, v.label, depth, v.count, v.primary, v.manage, v.virtual)
            kids[v.id].orEmpty().forEach { visit(it, depth + 1) }
        }
        kids[null].orEmpty().forEach { visit(it, 0) }
        return out
    }

    /** The media of bucket [bucketId] among [items]: a materialized one by its id, the virtual "You" the members' unbucketed. */
    fun mediaOf(bucketId: String, items: List<MemoryMedia.Item>): List<MemoryMedia.Item> =
        if (bucketId == SELF) items.filter { (it.ownerKey == null || it.ownerKey.startsWith("user:")) && it.bucketId == null }
        else items.filter { it.bucketId == bucketId }

    /** Which row the picker opens on: your own primary (materialized or virtual), else the first. */
    fun preferred(rows: List<Row>, listed: Listed): String? =
        rows.firstOrNull { it.virtual }?.bucketId
            ?: listed.buckets.firstOrNull { it.isPrimary && it.ownerKey.startsWith("user:") }?.bucketId
            ?: rows.firstOrNull()?.bucketId

    // ---- against the server ----

    /** Node [nodeId]'s buckets: a kept read (offline the list as last read); a throw where it does not read. */
    @Throws(Exception::class)
    suspend fun list(tenant: TenantSession, scopeId: String, nodeId: String): Listed {
        val account = tenant.account
        val api = BucketsApi(tenant.base, account.http)
        val read = account.kept(KeptRead.NODE_BUCKETS, tenant.tenant.tenantId, scopeId, nodeId) {
            account.fetched(account.respond { api.listNodeBuckets(scopeId, nodeId) }, null)
        }
        val page = VeyqAccount.KEPT_JSON.decodeFromString(BucketListResponse.serializer(), read.pages.single().decodeToString())
        return Listed(page.buckets.map(::of), page.sealedDropPrivateKey)
    }

    /** The keys of node [nodeId]'s ancestors, nearest first, as the tree holds them (a READ_WRITE guest's link root). */
    suspend fun ancestorKeys(tenant: TenantSession, scopeId: String, nodeId: String): List<ByteArray> {
        val tree = tenant.trees()[scopeId].orEmpty().associateBy { it.id }
        val out = ArrayList<ByteArray>()
        var at = tree[nodeId]?.parentId?.let { tree[it] }
        while (at != null) {
            out += at.key
            at = at.parentId?.let { tree[it] }
        }
        return out
    }

    /**
     * Each guest's name among node [nodeId]'s buckets, by owner key (`useUploaderNames`): what a medium's details name a
     * guest by. Empty where the buckets do not read or the node is not open here.
     */
    suspend fun guestNames(tenant: TenantSession, scopeId: String, nodeId: String): Map<String, String> {
        val node = tenant.node(scopeId, nodeId) ?: return emptyMap()
        val listed = runCatching { list(tenant, scopeId, nodeId) }.getOrNull() ?: return emptyMap()
        val above = ancestorKeys(tenant, scopeId, nodeId)
        return listed.buckets.filter { it.guest }.mapNotNull { b -> name(tenant.account.suite, scopeId, nodeId, node.key, listed, b, above)?.let { b.ownerKey to it } }.toMap()
    }

    /** What a curation came to. */
    enum class Outcome { DONE, REFUSED, FAILED }

    private suspend fun outcome(status: suspend () -> Int): Outcome = when (runCatching { status() }.getOrDefault(-1)) {
        in 200..299 -> Outcome.DONE
        -1 -> Outcome.FAILED
        else -> Outcome.REFUSED
    }

    /** A sub-bucket named [name] created under [parentBucketId] (null: the caller's own primary), its name sealed under the node's key. */
    suspend fun create(tenant: TenantSession, scopeId: String, nodeId: String, name: String, parentBucketId: String?): Outcome {
        val node = tenant.node(scopeId, nodeId) ?: return Outcome.FAILED
        val bucketId = NodeCreate.uuid(tenant.account)
        val sealed = b64(tenant.account.suite.sealField(nameContext(scopeId, nodeId, bucketId), node.key, utf8(name.trim())))
        return outcome {
            tenant.account.respond {
                BucketsApi(tenant.base, tenant.account.http).createBucket(scopeId, nodeId, CreateBucketRequest(node.epoch, bucketId, sealed, parentBucketId?.takeIf { it != SELF }))
            }.status
        }
    }

    /** One of the caller's own sub-buckets renamed: its name sealed anew under the node's key. */
    suspend fun rename(tenant: TenantSession, scopeId: String, nodeId: String, bucketId: String, name: String): Outcome {
        val node = tenant.node(scopeId, nodeId) ?: return Outcome.FAILED
        val sealed = b64(tenant.account.suite.sealField(nameContext(scopeId, nodeId, bucketId), node.key, utf8(name.trim())))
        return outcome { tenant.account.respond { BucketsApi(tenant.base, tenant.account.http).renameBucket(scopeId, nodeId, bucketId, RenameBucketRequest(node.epoch, sealed)) }.status }
    }

    /** One of the caller's own sub-buckets deleted: its media go back to the primary (the server's). */
    suspend fun delete(tenant: TenantSession, scopeId: String, nodeId: String, bucketId: String): Outcome =
        outcome { tenant.account.respond { BucketsApi(tenant.base, tenant.account.http).deleteBucket(scopeId, nodeId, bucketId) }.status }

    /** The caller's own [mediaIds] moved into [targetBucketId] (null or [SELF]: its primary), in slices of [MOVE_MAX_IDS]; not atomic across slices. */
    suspend fun move(tenant: TenantSession, scopeId: String, nodeId: String, mediaIds: List<String>, targetBucketId: String?): Outcome {
        val api = BucketsApi(tenant.base, tenant.account.http)
        for (slice in mediaIds.chunked(MOVE_MAX_IDS)) {
            val o = outcome { tenant.account.respond { api.moveBucketMedia(scopeId, nodeId, MoveMediaRequest(slice, targetBucketId?.takeIf { it != SELF })) }.status }
            if (o != Outcome.DONE) return o
        }
        return Outcome.DONE
    }
}
