package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.AttachmentNodeSubtreeEraser
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.memories.itemfavorites.ItemFavoritesNodeSubtreeEraser
import me.raddatz.veyq.memories.media.BucketEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.MediaNodeSubtreeEraser
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The node subtree erasure's order is an explicit phase table, not bean order, and `dropDeadIdentities` runs only
 * after the media eraser's guest set is returned and its rows are gone (ADR 0079 phase 1, step 6).
 * `dropDeadIdentities` re-counts the guest's media and buckets, so run before they are deleted it keeps the
 * identity, and run without the set the media eraser returns it never sees it: either way the guest outlives the
 * last thing they uploaded — a runtime result, so the second test erases a real subtree whose link was deleted
 * earlier (a link deleted in this very purge is covered by `tombstone`'s own drop, which would hide the order).
 */
@QuarkusTest
class NodeSubtreeEraserOrderSliceTest {
    @Inject
    @All
    lateinit var erasers: MutableList<NodeSubtreeEraser>

    @Inject
    lateinit var purge: NodeSubtreePurge

    @Test
    fun theErasersRunInTheDocumentedPhaseOrder() {
        val byName = erasers.associate { realName(it) to it.phase }
        assertEquals(
            mapOf(
                MediaNodeSubtreeEraser::class.java.simpleName to 100,
                AttachmentNodeSubtreeEraser::class.java.simpleName to 200,
                ItemFavoritesNodeSubtreeEraser::class.java.simpleName to 610,
            ),
            byName,
            "the eraser set and its phases are the documented table",
        )
        assertEquals(erasers.size, erasers.map { it.phase }.toSet().size, "phases are distinct, so order never rests on a tie")
        assertTrue(erasers.none { it.phase == NodeSubtreeEraser.COVER_BLOBS_PHASE }, "no eraser sits on core's own cover erase")
        assertTrue(byName.getValue("MediaNodeSubtreeEraser") < NodeSubtreeEraser.COVER_BLOBS_PHASE, "media precedes the cover blobs, as the purge always ran it")
        assertTrue(byName.getValue("AttachmentNodeSubtreeEraser") < NodeSubtreeEraser.COVER_BLOBS_PHASE, "attachments go with the other node-hung blobs, before the covers")
    }

    @Test
    fun aDeadLinksGuestIsDroppedWithTheLastMediaAndBucketOfTheSubtree() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = TestIds.shareId()
        val guestId = TestIds.uuid()
        val mediaId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = shareId; ownerAccount = userId; this.scopeId = scopeId; nodeId = memory; role = ShareEntity.WRITE_ONLY
                enabled = false; createdAt = Instant.now(); deletedAt = Instant.now().minusSeconds(7200)
            }.persist()
            ShareIdentityEntity().apply {
                id = guestId; this.shareId = shareId; sealedDisplayName = "Z3Vlc3Q="; tokenHash = "hash-$guestId"
            }.persist()
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "b3JkZXI="
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; nodeId = memory; this.blobId = blobId
                shareIdentityId = guestId; createdAt = Instant.now()
            }.persist()
            BucketEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; nodeId = memory; ownerKey = "share:$guestId"
                isPrimary = true; createdAt = Instant.now()
            }.persist()
            NodeEntity.findById(memory)!!.deletedAt = Instant.now().minusSeconds(3600)
        }

        // The link was deleted EARLIER, so `tombstoneAllOn` skips it and its own drop never sees this guest: only the
        // purge's final `dropDeadIdentities` can remove the identity, with the set the media eraser returned (read
        // before its rows went) and after those rows were gone.
        QuarkusTransaction.requiringNew().run { purge.eraseExpiredIn(scopeId, Instant.now()) }

        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(mediaId), "the media eraser ran")
            assertEquals(0L, BucketEntity.count("ownerKey", "share:$guestId"), "the guest's bucket went")
            assertNotNull(ShareEntity.findById(shareId)!!.deletedAt, "the link was and stays a tombstone")
            assertNull(ShareIdentityEntity.findById(guestId), "nothing of the guest's remains and the link is gone: dropped")
            assertNull(NodeEntity.findById(memory), "the node itself went")
        }
    }

    // Quarkus may wrap a bean in a generated subclass; the declared eraser is the nearest non-generated class.
    private fun realName(eraser: NodeSubtreeEraser): String {
        var c: Class<*> = eraser::class.java
        while (c.simpleName.contains("_") && c.superclass != null) c = c.superclass
        return c.simpleName
    }
}
