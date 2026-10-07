package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.blob.AttachmentScopeEraser
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.node.NodeScopeEraser
import me.raddatz.veyq.core.scope.ScopeEraser
import me.raddatz.veyq.core.sharing.ShareScopeEraser
import me.raddatz.veyq.memories.favorites.FavoritesScopeEraser
import me.raddatz.veyq.memories.itemfavorites.ItemFavoritesScopeEraser
import me.raddatz.veyq.memories.media.MediaDerivativeEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.MediaScopeEraser
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.scope.ScopeEntity
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scope erasure's order is an explicit phase table, not bean order (ADR 0079 phase 1, step 5). The FK
 * order derivative -> media -> blob is a **runtime** failure when broken, so the second test erases a real scope
 * that has a media with an original and a derivative: a media eraser given a phase after core's blob delete makes
 * `veyq_blob` refuse (`fk_media_blob`, `fk_derivative_blob`) and the purge throws.
 */
@QuarkusTest
class ScopeEraserOrderSliceTest {
    @Inject
    @All
    lateinit var erasers: MutableList<ScopeEraser>

    @Inject
    lateinit var purge: ScopePurge

    @Test
    fun theErasersRunInTheDocumentedPhaseOrder() {
        // ArC may hand out client proxies or subclasses; compare by the implemented class name.
        val byName = erasers.associate { realName(it) to it.phase }
        val table = byName.entries.sortedBy { it.value }
        assertEquals(
            mapOf(
                MediaScopeEraser::class.java.simpleName to 100,
                AttachmentScopeEraser::class.java.simpleName to 200,
                FavoritesScopeEraser::class.java.simpleName to 600,
                ItemFavoritesScopeEraser::class.java.simpleName to 610,
                NodeScopeEraser::class.java.simpleName to 700,
                ShareScopeEraser::class.java.simpleName to 800,
            ),
            byName,
            "the eraser set and its phases are the documented table: $table",
        )
        assertEquals(erasers.size, erasers.map { it.phase }.toSet().size, "phases are distinct, so order never rests on a tie")
        assertTrue(erasers.none { it.phase == ScopeEraser.BLOB_ROWS_PHASE }, "no eraser sits on core's own blob delete")
        assertTrue(byName.getValue("MediaScopeEraser") < ScopeEraser.BLOB_ROWS_PHASE, "media (derivative -> media) precedes the blob delete")
        assertTrue(byName.getValue("AttachmentScopeEraser") < ScopeEraser.BLOB_ROWS_PHASE, "attachments (fk_attachment_blob) precede the blob delete")
        listOf("FavoritesScopeEraser", "ItemFavoritesScopeEraser", "NodeScopeEraser", "ShareScopeEraser").forEach {
            assertTrue(byName.getValue(it) > ScopeEraser.BLOB_ROWS_PHASE, "$it runs after the blob delete, as the purge always ran it")
        }
    }

    @Test
    fun purgeErasesAScopeWhoseMediaAndDerivativeNameBlobs() {
        val seeded = seedMemberScope()
        val scopeId = seeded.scopeId
        val nodeId = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mediaId = UUID.randomUUID().toString()
        val blobIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        QuarkusTransaction.requiringNew().run {
            blobIds.forEach { blobId ->
                BlobEntity().apply {
                    id = blobId; this.scopeId = scopeId; storageKey = "order-$blobId".take(64)
                    chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize
                    wrappedBlobKey = "b3JkZXI="; createdAt = Instant.now()
                }.persist()
            }
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; blobId = blobIds[0]; createdAt = Instant.now()
            }.persist()
            MediaDerivativeEntity().apply {
                id = UUID.randomUUID().toString(); this.mediaId = mediaId; kind = "thumb"; blobId = blobIds[1]; createdAt = Instant.now()
            }.persist()
        }

        purge.purge(scopeId) // throws on an FK refusal if the media step ran after the blob delete

        QuarkusTransaction.requiringNew().run {
            assertNull(ScopeEntity.findById(scopeId), "the scope is gone")
            assertEquals(0, MediaEntity.count("scopeId", scopeId))
            assertEquals(0, MediaDerivativeEntity.count("mediaId", mediaId))
            assertEquals(0, BlobEntity.count("scopeId", scopeId))
        }
    }

    // Quarkus may wrap a bean in a generated subclass; the declared eraser is the nearest non-generated class.
    private fun realName(eraser: ScopeEraser): String {
        var c: Class<*> = eraser::class.java
        while (c.simpleName.contains("_") && c.superclass != null) c = c.superclass
        return c.simpleName
    }
}
