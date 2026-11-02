package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.backup.ErasureLedgerFile
import me.raddatz.veyq.core.backup.ErasureLedgerStart
import me.raddatz.veyq.core.backup.ErasureOutboxEntity
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.items.NodeItemsRevisionEntity
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import me.raddatz.veyq.memories.testing.MediaBlobRows

/**
 * Memories' kind of the erasure ledger (ADR 0056 decision 8): a medium hard-deleted — here a guest's delete of its own
 * upload, which leaves no trash to re-fire — writes its `MEDIA` line through core, and the start erases it again through
 * [MemoriesErasures] once a restore wrote its rows back. The control: a medium nobody deleted stays. As in core's
 * `ErasureLedgerSliceTest`, the outbox and the boot's file are emptied around each test, and the start is called as a
 * restart would (`%test` re-applies nothing at boot).
 */
@QuarkusTest
class MemoriesErasuresSliceTest {

    @Inject lateinit var start: ErasureLedgerStart
    @Inject lateinit var file: ErasureLedgerFile
    @Inject lateinit var store: MediaStore

    @BeforeEach
    @AfterEach
    fun clean() {
        QuarkusTransaction.requiringNew().run { ErasureOutboxEntity.deleteAll() }
        Files.deleteIfExists(file.path)
    }

    private data class Seeded(val scopeId: String, val nodeId: String, val mediaId: String, val blobId: String, val key: String)

    /** A live medium with its blob, under a Memory whose item revision exists — written again by a restore with these ids. */
    private fun medium(scopeId: String, nodeId: String, mediaId: String = UUID.randomUUID().toString(),
                       blobId: String = UUID.randomUUID().toString()): Seeded {
        val key = "ledger-$blobId".take(64)
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = key
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "bGVkZ2Vy"
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; updatedAt = Instant.now()
            }.also { it.persist(); MediaBlobRows.holdFor(it, blobId) }
            if (NodeItemsRevisionEntity.findById(nodeId) == null) NodeItemsRevisionEntity().apply { this.nodeId = nodeId; revision = 1 }.persist()
        }
        return Seeded(scopeId, nodeId, mediaId, blobId, key)
    }

    @Test
    fun aDeletedMediumComesBackLiveAndIsErasedAgain() {
        val scopeId = seedMemberScope().scopeId
        val nodeId = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val erased = medium(scopeId, nodeId)
        val control = medium(scopeId, nodeId)
        QuarkusTransaction.requiringNew().run { store.purgeMediaRows(MediaEntity.findById(erased.mediaId)!!) }
        assertEquals(1L, QuarkusTransaction.requiringNew().call {
            ErasureOutboxEntity.count("kind = ?1 and subjectId = ?2", MemoriesErasures.Kind.MEDIA.name, erased.mediaId)
        }, "the medium's line, in its delete's transaction")
        // The restore: a capture before the delete brings the rows back, and no reclaim row for its blob.
        QuarkusTransaction.requiringNew().run { StoreReclaimEntity.delete("storageKey", erased.key) }
        medium(scopeId, nodeId, erased.mediaId, erased.blobId)

        val done = start.run()
        assertEquals(1, done.erased[MemoriesErasures.Kind.MEDIA.name])
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(erased.mediaId), "erased again, through Memories' own eraser")
            assertNull(BlobEntity.findById(erased.blobId))
            assertEquals(1L, StoreReclaimEntity.count("storageKey", erased.key), "its blob onto the reclaim list, once")
            assertNotNull(MediaEntity.findById(control.mediaId), "control: a medium nobody deleted stays")
        }
        assertEquals(null, start.run().erased[MemoriesErasures.Kind.MEDIA.name], "a second start erases nothing")
    }
}
