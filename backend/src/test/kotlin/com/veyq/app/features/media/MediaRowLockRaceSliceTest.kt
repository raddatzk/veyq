package com.veyq.app.features.media

import com.veyq.app.core.blob.BlobEntity
import com.veyq.app.core.node.NodeEntity
import com.veyq.app.features.sharing.SharingStore
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.whileUncommitted
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
 * A member's write to a media row reads that row under its lock (developer-styleguide §12), as every delete of it
 * does (`purgeMedia`, `SharingStore.deleteContributedMedia`). A trash or a move that read a guest's upload before the
 * guest's delete of it committed flushed onto a row that was gone and answered 500; under the lock it waits for the
 * delete and then finds nothing — the answer its replay gets.
 *
 * Each race holds the guest's delete uncommitted ([whileUncommitted]) while the member's write runs. Each has a control:
 * the same write on a photo nobody deletes lands.
 */
@QuarkusTest
class MediaRowLockRaceSliceTest {

    @Inject lateinit var store: MediaStore
    @Inject lateinit var sharing: SharingStore

    @Test
    fun aTrashOfAGuestUploadBeingDeletedAnswersNotFound() {
        repeat(RACE_ROUNDS) { round ->
            val g = guestUpload()
            val trashed = whileUncommitted(
                change = { assertTrue(sharing.deleteContributedMedia(g.scopeId, g.mediaId, g.identityId), "round $round") },
                overlapping = { runCatching { store.trashMedia(g.scopeId, g.mediaId, g.userId) } },
            )
            assertEquals(Result.success(null), trashed, "round $round: the trash of a deleted photo")
            assertNull(tx { MediaEntity.findById(g.mediaId) }, "round $round")
            val kept = guestUpload()
            assertEquals(kept.nodeId, store.trashMedia(kept.scopeId, kept.mediaId, kept.userId), "round $round: control")
        }
    }

    @Test
    fun aMoveOfAGuestUploadBeingDeletedAnswersAsAReplay() {
        repeat(RACE_ROUNDS) { round ->
            val g = guestUpload()
            val moved = whileUncommitted(
                change = { assertTrue(sharing.deleteContributedMedia(g.scopeId, g.mediaId, g.identityId), "round $round") },
                overlapping = { runCatching { store.moveMedia(g.scopeId, g.nodeId, listOf(g.mediaId), null) } },
            )
            assertEquals(Result.success(false), moved, "round $round: the move of a deleted photo")
            assertNull(tx { MediaEntity.findById(g.mediaId) }, "round $round")
            val kept = guestUpload()
            assertTrue(store.moveMedia(kept.scopeId, kept.nodeId, listOf(kept.mediaId), null), "round $round: control")
            assertNotNull(tx { MediaEntity.findById(kept.mediaId) }, "round $round: control")
        }
    }

    // --- helpers ---

    private data class GuestUpload(val userId: String, val scopeId: String, val nodeId: String, val identityId: String, val mediaId: String)

    /** A guest's photo in a member's Memory, as `MediaStore.attach` lands one: in the guest's primary bucket. */
    private fun guestUpload(): GuestUpload {
        val (userId, scopeId) = seedMemberScope()
        val nodeId = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val identityId = UUID.randomUUID().toString()
        val mediaId = UUID.randomUUID().toString()
        tx {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
                createdAt = Instant.now()
            }.persist()
            val bucket = BucketEntity().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
                ownerKey = OwnerKey.Share(identityId).format(); isPrimary = true; createdAt = Instant.now()
            }.also { it.persist() }
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                shareIdentityId = identityId; createdBy = bucket.ownerKey; bucketId = bucket.id; createdAt = Instant.now()
            }.persist()
        }
        return GuestUpload(userId, scopeId, nodeId, identityId, mediaId)
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
