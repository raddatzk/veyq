package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
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
 * does (`purgeMedia`, `MediaShareGuestWrites.deleteOwn`). A trash or a move that read a guest's upload before the
 * guest's delete of it committed flushed onto a row that was gone and answered 500; under the lock it waits for the
 * delete and then finds nothing — the answer its replay gets.
 *
 * A guest's delete also decides by the tree (the medium inside the link's subtree), so it reads that under the scope
 * lock, before the media row's (inbox #359).
 *
 * Each race but the last holds the guest's delete uncommitted ([whileUncommitted]) while the member's write runs. Each has a control:
 * the same write on a photo nobody deletes lands.
 */
@QuarkusTest
class MediaRowLockRaceSliceTest {

    @Inject lateinit var store: MediaStore
    @Inject lateinit var sharing: MediaShareGuestWrites
    @Inject lateinit var tree: NodesStore

    @Test
    fun aTrashOfAGuestUploadBeingDeletedAnswersNotFound() {
        repeat(RACE_ROUNDS) { round ->
            val g = guestUpload()
            val trashed = whileUncommitted(
                change = { assertTrue(sharing.deleteOwn(g.scopeId, g.nodeId, g.mediaId, g.identityId), "round $round") },
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
                change = { assertTrue(sharing.deleteOwn(g.scopeId, g.nodeId, g.mediaId, g.identityId), "round $round") },
                overlapping = { runCatching { store.moveMedia(g.scopeId, g.nodeId, listOf(g.mediaId), null) } },
            )
            assertEquals(Result.success(false), moved, "round $round: the move of a deleted photo")
            assertNull(tx { MediaEntity.findById(g.mediaId) }, "round $round")
            val kept = guestUpload()
            assertTrue(store.moveMedia(kept.scopeId, kept.nodeId, listOf(kept.mediaId), null), "round $round: control")
            assertNotNull(tx { MediaEntity.findById(kept.mediaId) }, "round $round: control")
        }
    }

    /**
     * A guest's delete of its own upload while a member moves the upload's Memory out of the share's subtree (inbox
     * #359): the delete reads the subtree under the scope lock the move holds, so it sees the move and answers 404 —
     * the medium is no longer the link's to touch. Read under the media row's lock only, it read the Memory still
     * inside and deleted it.
     */
    @Test
    fun aGuestDeleteOfAnUploadMovedOutOfTheSubtreeMeanwhileAnswersNotFound() {
        repeat(RACE_ROUNDS) { round ->
            val g = guestUploadBelowAFolder()
            val deleted = whileUncommitted(
                change = {
                    assertEquals(
                        NodesStore.ApplyResult.Applied,
                        tree.applyUpdate(g.upload.scopeId, g.upload.nodeId, NodesStore.Placement(g.outside, "bmV3", "c2x1Zy0$round", CONTENT_KEY, null, 0), null, null, null),
                        "round $round: the move",
                    )
                },
                overlapping = { runCatching { sharing.deleteOwn(g.upload.scopeId, g.root, g.upload.mediaId, g.upload.identityId) } },
            )
            assertEquals(Result.success(false), deleted, "round $round: a guest deleted a medium its link no longer reaches")
            assertNotNull(tx { MediaEntity.findById(g.upload.mediaId) }, "round $round")
        }
        val g = guestUploadBelowAFolder()
        assertTrue(sharing.deleteOwn(g.upload.scopeId, g.root, g.upload.mediaId, g.upload.identityId), "control: inside the subtree it deletes")
        assertNull(tx { MediaEntity.findById(g.upload.mediaId) }, "control")
    }

    // --- helpers ---

    private data class BelowAFolder(val upload: GuestUpload, val root: String, val outside: String)

    /** [guestUpload] in a Memory below a shared folder (the share root), and a second folder outside it. */
    private fun guestUploadBelowAFolder(): BelowAFolder {
        val (userId, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = root, wrappedContentKey = CONTENT_KEY)
        return BelowAFolder(guestUploadIn(userId, scopeId, memory), root, outside)
    }

    private data class GuestUpload(val userId: String, val scopeId: String, val nodeId: String, val identityId: String, val mediaId: String)

    /** A guest's photo in a member's Memory, as `MediaStore.attach` lands one: in the guest's primary bucket. */
    private fun guestUpload(): GuestUpload {
        val (userId, scopeId) = seedMemberScope()
        return guestUploadIn(userId, scopeId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))
    }

    private fun guestUploadIn(userId: String, scopeId: String, nodeId: String): GuestUpload {
        val identityId = UUID.randomUUID().toString()
        val mediaId = UUID.randomUUID().toString()
        tx {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
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
