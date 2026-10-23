package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.StoredObjectLocks
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeRekeyParticipant
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Value
import me.raddatz.veyq.core.node.rekey.RekeyHandler
import me.raddatz.veyq.core.node.rekey.RekeyOutcome
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/** One medium as its node's re-key reads it: what is sealed under the node's key (ADR 0109 point 2). */
data class RekeyMediumRow(
    val mediaId: String,
    val metaRevision: Long,
    val wrappedMeta: String?,
    val wrappedExif: String?,
    val blobs: List<RekeyMediumBlobRow>,
)

/** One `K_blob` wrap of a medium: its original ([MemoryBlobRoles.ORIGINAL]) or a derivative (its kind). */
data class RekeyMediumBlobRow(val blobId: String, val role: String, val wrappedBlobKey: String)

/**
 * One bucket name a re-key re-seals: under the node's key, or — a READ_WRITE contributor's primary, [shareId] and
 * [identityId] set — a copy of that guest's display name under the share root's key (`share-identity-name`).
 */
data class RekeyBucketRow(val bucketId: String, val sealedName: String, val shareId: String? = null, val identityId: String? = null)

/** What `listMediaRekey` answers: a refusal, or one page of media and every bucket name. */
sealed interface MediaRekeyListOutcome {
    data class Page(val media: ItemsPage<RekeyMediumRow>, val buckets: List<RekeyBucketRow>) : MediaRekeyListOutcome
    data class Refused(val failure: HttpFailure) : MediaRekeyListOutcome
}

/**
 * Which Memories rows of a node are sealed under the node's key — the one answer the listing and the commit share, so
 * the client is shown exactly what the commit then requires (ADR 0109 point 2). Every medium of the node, live and
 * trashed (a trashed node's included: nothing here asks `deletedAt`), its `wrappedMeta`, its `wrappedExif` and the
 * `K_blob` wrap of its original and of each derivative; and every bucket whose name is set. **Not** a row a
 * `WRITE_ONLY` guest sealed under its own `K_up` (ADR 0109 point 5): a medium whose guest identity holds a wrapped key
 * ([MediaStore.uploaderKeysOf]'s rule), and that identity's primary bucket (owner `share:{id}`), whose name is under
 * the same key. The re-key leaves them as they are.
 */
object MediaRekeyRows {
    const val PART = "memories"
    const val META = "media.wrappedMeta"
    const val EXIF = "media.wrappedExif"
    const val BLOB_WRAP = "media.wrappedBlobKey"
    const val BUCKET_NAME = "bucket.sealedName"

    /** The node's media sealed under its key, in id order — immutable, so a page boundary never moves. */
    private const val SEALED_MEDIA =
        "from MediaEntity m where m.scopeId = ?1 and m.nodeId = ?2 and not exists (" +
            "select i.id from ShareIdentityEntity i where i.id = m.shareIdentityId and i.wrappedIdentityKey is not null)"

    /** One page of at most [limit] media past [since] (by id; the cursor's position is unused and 0). */
    fun page(scopeId: String, nodeId: String, since: ItemsCursor?, limit: Int): ItemsPage<RekeyMediumRow> {
        val query = if (since == null) {
            MediaEntity.find("$SEALED_MEDIA order by m.id", scopeId, nodeId)
        } else {
            MediaEntity.find("$SEALED_MEDIA and m.id > ?3 order by m.id", scopeId, nodeId, since.id)
        }
        val page = ItemsPage.of(query.range(0, limit).list(), limit) // range is inclusive: limit + 1 rows
        val blobs = blobsOf(page.items)
        val wraps = blobRows(scopeId, blobs.values.flatten().map { it.first }).associate { it.id to it.wrappedBlobKey }
        val rows = page.items.map { m ->
            val own = blobs[m.id].orEmpty().mapNotNull { (id, role) -> wraps[id]?.let { RekeyMediumBlobRow(id, role, it) } }
            RekeyMediumRow(m.id, m.metaRevision, m.wrappedMeta, m.wrappedExif, own)
        }
        return ItemsPage(rows, page.more, page.items.lastOrNull()?.let { ItemsCursor(0, it.id) })
    }

    /** Every medium of the node sealed under its key, in id order; [lock]: each row locked `FOR UPDATE` (the
     *  participant's [MediaNodeRekeyParticipant.lockRows], before the node's lock). */
    fun media(scopeId: String, nodeId: String, lock: Boolean = false): List<MediaEntity> =
        MediaEntity.find("$SEALED_MEDIA order by m.id", scopeId, nodeId)
            .let { if (lock) it.withLock(LockModeType.PESSIMISTIC_WRITE) else it }.list()

    /** Each medium's blobs as (blob id, role): the original first, then its derivatives. */
    fun blobsOf(media: List<MediaEntity>): Map<String, List<Pair<String, String>>> {
        if (media.isEmpty()) return emptyMap()
        val derivatives = media.map { it.id }.chunked(CHUNK)
            .flatMap { MediaDerivativeEntity.list("mediaId in ?1 order by id", it) }.groupBy { it.mediaId }
        return media.associate { m ->
            m.id to listOf(m.blobId to MemoryBlobRoles.ORIGINAL) + derivatives[m.id].orEmpty().map { it.blobId to it.kind }
        }
    }

    private fun blobRows(scopeId: String, ids: List<String>): List<BlobEntity> =
        ids.distinct().chunked(CHUNK).flatMap { BlobEntity.list("id in ?1 and scopeId = ?2", it, scopeId) }

    /** The node's buckets whose name is sealed under its key ([lock]: each named bucket's row locked `FOR UPDATE`, a
     *  `WRITE_ONLY` primary's too — it is filtered out only after the read). */
    fun buckets(scopeId: String, nodeId: String, lock: Boolean = false): List<BucketEntity> {
        val query = BucketEntity.find("scopeId = ?1 and nodeId = ?2 and sealedName is not null order by id", scopeId, nodeId)
        val all = if (lock) query.withLock(LockModeType.PESSIMISTIC_WRITE).list() else query.list()
        val guests = all.filter { it.isPrimary }
            .mapNotNullTo(HashSet()) { (PrincipalKey.parse(it.ownerKey) as? PrincipalKey.Share)?.identityId }
        val writeOnly = if (guests.isEmpty()) emptySet()
        else ShareIdentityEntity.find("id in ?1 and wrappedIdentityKey is not null", guests).list().mapTo(HashSet()) { it.id }
        return all.filterNot { b ->
            b.isPrimary && (PrincipalKey.parse(b.ownerKey) as? PrincipalKey.Share)?.identityId in writeOnly
        }
    }

    private const val CHUNK = 500
}

/**
 * The Memories half of a node's re-key (ADR 0109 point 2, CR decision 2): the listing a re-keyer reads and the staging
 * it writes through, under core's re-key rights ([RekeyHandler]) — an owner with the current grant, not excluded.
 */
@ApplicationScoped
class MediaRekeyHandler(private val rekey: RekeyHandler) {

    /** One page of the node's sealed media and every bucket name; the node may be trashed. */
    fun list(userId: String, scopeId: String, nodeId: String, since: ItemsCursor?, limit: Int): MediaRekeyListOutcome {
        rekey.refusal(userId, scopeId, READ)?.let { return MediaRekeyListOutcome.Refused(it as HttpFailure) }
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }
            ?: return MediaRekeyListOutcome.Refused(RekeyOutcome.NotFound)
        val buckets = MediaRekeyRows.buckets(scopeId, nodeId)
        // A contributor's primary names its identity (owner `share:{id}`); a WRITE_ONLY one is not among [buckets].
        val contributors = buckets.filter { it.isPrimary }
            .mapNotNullTo(HashSet()) { (PrincipalKey.parse(it.ownerKey) as? PrincipalKey.Share)?.identityId }
        val shareOf = if (contributors.isEmpty()) emptyMap()
        else ShareIdentityEntity.find("id in ?1", contributors).list().associate { it.id to it.shareId }
        return MediaRekeyListOutcome.Page(
            MediaRekeyRows.page(scopeId, nodeId, since, limit),
            buckets.map { b ->
                val identity = (PrincipalKey.parse(b.ownerKey) as? PrincipalKey.Share)?.identityId?.takeIf { b.isPrimary && it in shareOf }
                RekeyBucketRow(b.id, b.sealedName!!, identity?.let(shareOf::getValue), identity)
            },
        )
    }

    /** Stage [values] under [rekeyId] for core's commit; core checks the rights and that the node still lags at [fromEpoch]. */
    fun stage(userId: String, scopeId: String, nodeId: String, rekeyId: String, fromEpoch: Int, values: Map<Key, Value>): RekeyOutcome =
        rekey.stagePart(userId, scopeId, nodeId, rekeyId, fromEpoch, MediaRekeyRows.PART, values)
}

/**
 * Memories' participant in core's commit of a node's re-key (core's `RekeyStore.commit`, [NodeRekeyParticipant]): it
 * runs in that transaction. Its existing rows are locked in [lockRows] — the media, then the buckets — after core's
 * scope and share locks and BEFORE the node's, the order every Memories writer takes (`MediaStore`'s lock-order note);
 * [commit] runs after the node's lock, locks the blob rows of their objects ([StoredObjectLocks]: item rows before blob
 * rows) and **decides before it writes**: every medium's set `wrappedMeta` and `wrappedExif`, every blob wrap of it and every bucket name
 * ([MediaRekeyRows]) must be staged ([NodeRekeyParticipant.Verdict.MISSING] if not), and a medium staged against a meta
 * revision it no longer has is [NodeRekeyParticipant.Verdict.STALE]. Only then it writes: the meta (its revision + 1, as
 * the contract says), the EXIF, each `K_blob` wrap, each bucket name. The editor columns stay — a re-seal is no edit
 * (ADR 0093 § 8). A trashed medium is re-sealed like a live one: a restore must open under the new key.
 *
 * Once any medium was rewritten, the node's item revision moves once and every medium takes its position, so an open
 * gallery's next poll re-fetches them all with the new wraps (the keyset delta carries a row only past the cursor,
 * ADR 0033 decision 3); `updatedAt`, the served time of a change, stays.
 */
@ApplicationScoped
class MediaNodeRekeyParticipant(private val items: NodeItemsRevisions) : NodeRekeyParticipant {

    override val part: String = MediaRekeyRows.PART

    /** The media rows, then the bucket rows [commit] rewrites — each `FOR UPDATE`, before the node's lock. A medium or a
     *  bucket inserted after this needs the node's lock first (the epoch gate), which the commit holds by then. */
    override fun lockRows(scopeId: String, nodeId: String) {
        MediaRekeyRows.media(scopeId, nodeId, lock = true)
        MediaRekeyRows.buckets(scopeId, nodeId, lock = true)
    }

    override fun commit(scopeId: String, nodeId: String, staged: Map<Key, Value>): NodeRekeyParticipant.Verdict {
        // Held since [lockRows]: read, not locked again — a lock here, after the node's, would invert the order.
        val media = MediaRekeyRows.media(scopeId, nodeId)
        val buckets = MediaRekeyRows.buckets(scopeId, nodeId)
        val blobIds = MediaRekeyRows.blobsOf(media)
        val wanted = blobIds.values.flatten().mapTo(HashSet()) { it.first }
        val blobs = StoredObjectLocks.lockOf(scopeId, wanted).filter { it.id in wanted }

        // Decide: every sealed field has its staged value …
        for (m in media) {
            if (m.wrappedMeta != null && Key(m.id, MediaRekeyRows.META) !in staged) return NodeRekeyParticipant.Verdict.MISSING
            if (m.wrappedExif != null && Key(m.id, MediaRekeyRows.EXIF) !in staged) return NodeRekeyParticipant.Verdict.MISSING
        }
        if (blobs.any { Key(it.id, MediaRekeyRows.BLOB_WRAP) !in staged }) return NodeRekeyParticipant.Verdict.MISSING
        if (buckets.any { Key(it.id, MediaRekeyRows.BUCKET_NAME) !in staged }) return NodeRekeyParticipant.Verdict.MISSING
        // … staged against the meta revision the medium still has.
        val stale = media.any { m ->
            listOfNotNull(staged[Key(m.id, MediaRekeyRows.META)], staged[Key(m.id, MediaRekeyRows.EXIF)])
                .any { it.revision != m.metaRevision }
        }
        if (stale) return NodeRekeyParticipant.Verdict.STALE

        // Write — nothing below can refuse.
        for (m in media) {
            if (m.wrappedMeta != null) {
                m.wrappedMeta = staged.getValue(Key(m.id, MediaRekeyRows.META)).value
                m.metaRevision += 1
            }
            if (m.wrappedExif != null) m.wrappedExif = staged.getValue(Key(m.id, MediaRekeyRows.EXIF)).value
        }
        for (b in blobs) b.wrappedBlobKey = staged.getValue(Key(b.id, MediaRekeyRows.BLOB_WRAP)).value
        for (b in buckets) b.sealedName = staged.getValue(Key(b.id, MediaRekeyRows.BUCKET_NAME)).value
        if (media.isNotEmpty()) {
            val position = items.bump(scopeId, nodeId) // after the media rows: record rows -> node row -> revision row
            media.forEach { it.position = position }
        }
        return NodeRekeyParticipant.Verdict.APPLIED
    }
}
