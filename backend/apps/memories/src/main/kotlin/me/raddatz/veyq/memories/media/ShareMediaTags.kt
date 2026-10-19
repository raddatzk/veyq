package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.items.ItemsRevisions
import me.raddatz.veyq.memories.generated.model.ShareMedia
import me.raddatz.veyq.memories.generated.model.ShareMediaDerivative
import me.raddatz.veyq.memories.generated.model.UploaderKey
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.util.UUID

/** A guest's media rows as `listShareMedia` serves them — the one mapping the body and the tag ([ShareMediaTag]) share,
 *  so the tag is a function of exactly what the body says. */
internal object ShareMediaWire {
    fun media(s: ShareMediaRow, blocked: Boolean) = ShareMedia(
        nodeId = UUID.fromString(s.nodeId),
        mediaId = UUID.fromString(s.row.mediaId),
        blobId = UUID.fromString(s.row.blobId),
        chunkCount = s.row.chunkCount,
        byteSize = s.row.byteSize,
        wrappedBlobKey = s.row.wrappedBlobKey,
        manifest = s.row.manifest,
        derivatives = s.row.derivatives.map { d ->
            ShareMediaDerivative(
                kind = ShareMediaDerivative.Kind.valueOf(d.kind),
                blobId = UUID.fromString(d.blobId),
                chunkCount = d.chunkCount,
                byteSize = d.byteSize,
                wrappedBlobKey = d.wrappedBlobKey,
                manifest = d.manifest,
                wrappedBlobMeta = d.wrappedBlobMeta,
            )
        },
        wrappedMeta = s.row.wrappedMeta,
        wrappedBlobMeta = s.row.wrappedBlobMeta,
        identityId = s.identityId?.let(UUID::fromString),
        // Kept and marked, not left out (uc.moderation.take-a-measure): the guest sees the placeholder a member sees.
        blockedByOperator = blocked,
    )

    fun identities(keys: Map<String, String>): List<UploaderKey> =
        keys.map { (id, wrap) -> UploaderKey(identityId = UUID.fromString(id), wrappedIdentityKey = wrap) }
}

/**
 * The `ETag` of a guest's media listing (inbox #497, ADR 0081 decision 4): a digest of everything the read serves, every
 * page of it — each medium as the generated model names it (its `toString` lists every field, so a field added to the
 * wire later is in the tag without anyone remembering to add it), in `mediaId` order, and the identities beside them.
 * Nothing in it is outside the response, so the tag tells a guest nothing the listing does not: in particular it does
 * NOT follow the node's item revision, which also moves on what a guest is not shown (a medium moved between buckets, an
 * edit undone), nor the scope's tree revision. Truncated SHA-256, 128 bits, quoted — the resolve's tag's form
 * (`ShareETag`); equality with the guest's last tag is the only question it answers.
 */
internal object ShareMediaTag {
    fun of(media: List<ShareMedia>, identities: List<UploaderKey>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        for (m in media) sha.update((m.toString() + "\n").toByteArray(Charsets.UTF_8))
        sha.update("identities\n".toByteArray(Charsets.UTF_8))
        for (i in identities.sortedBy { it.identityId.toString() }) sha.update((i.toString() + "\n").toByteArray(Charsets.UTF_8))
        return "\"" + sha.digest().take(16).joinToString("") { "%02x".format(it) } + "\""
    }
}

/**
 * The guest listing's tags, remembered per what they were computed from, so an idle guest poll costs the revisions read
 * and a compare instead of a read of the whole listing (inbox #497; the cache ADR 0081 left for "the first measurement
 * that wants it"). The tag stays the digest ([ShareMediaTag]); what keys it is everything the served listing is a
 * function of that can change:
 *
 * - the asked nodes and each one's item revisions ([ItemsRevisions]) — every writer of a medium bumps its node's
 *   (`NodeItemsRevisionBumpsTest` is red on one that does not), and a medium's blobs and derivatives are written once,
 *   with its attach;
 * - the content blocks that reach those nodes and the item blocks of the space (a block marks a served row, and a node
 *   block bumps no item revision).
 *
 * A guest's identity filter needs no part of the key: an identity's wrapped key is written with it, and an identity is
 * deleted only once no medium names it (`SharingStore.dropDeadIdentities`). **The key is read before the rows** (ADR 0081
 * decision 3's order): a change that commits in between lands in the digest stored under the older key, which no later
 * read asks for, since the revision has moved — a stale key only costs a recompute, never a stale tag.
 *
 * Per instance, bounded (least recently used out): a miss on another instance, or after an eviction, recomputes the same
 * digest. The served listing does not depend on the link, so every link onto a node shares its entry.
 */
@ApplicationScoped
class ShareMediaTags {
    /** What a tag was computed from; see the class. Node ids are sorted so two reads of one subtree share an entry. */
    data class Key(val nodes: List<Pair<String, ItemsRevisions>>, val blockedNodes: Set<String>, val blockedItems: Set<String>)

    private val tags = object : LinkedHashMap<Key, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean = size > CAPACITY
    }

    fun get(key: Key): String? = synchronized(tags) { tags[key] }

    fun put(key: Key, etag: String) {
        synchronized(tags) { tags[key] = etag }
    }

    companion object {
        /** Open guest galleries remembered at once — an entry is a key and 34 characters. */
        const val CAPACITY = 4096
    }
}
