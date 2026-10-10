package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A purge erases only what is still due when it erases it, and a restore answers as a replay would (developer-
 * styleguide §12: a purge's selection is its lock, a restore is a guarded update). A purge read its worklist as a
 * plain read and deleted afterwards; a restore — or an upload's next window — that committed in between was erased
 * anyway, an outcome neither order of the two produces: a restored photo, a restored group, a live upload's session
 * gone. The reverse overlap, a restore of a row a purge is deleting, was a 500 at the restore's flush.
 *
 * Each race holds one side's commit back ([whileUncommitted]) and runs the other inside that window. Each has a
 * control: with nothing overlapping, the purge still erases what is due.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `PurgeRaceCoreSliceTest` in `:core`.
 */
@QuarkusTest
class PurgeRaceSliceTest {

    @Inject lateinit var store: MediaStore

    @Test
    fun aMediaRestoredWhileTheRetentionPurgeRunsSurvives() {
        repeat(RACE_ROUNDS) { round ->
            val (scopeId, mediaId) = trashedMedia()
            whileUncommitted(
                change = { assertNotNull(store.restoreMedia(scopeId, mediaId), "round $round") },
                overlapping = { store.purgeTrashedBefore(Instant.now().plusSeconds(60)) },
            )
            assertEquals(true, tx { MediaEntity.findById(mediaId)?.let { it.deletedAt == null } }, "round $round: the purge erased a restored photo")
        }
        val (_, due) = trashedMedia()
        store.purgeTrashedBefore(Instant.now().plusSeconds(60))
        assertNull(tx { MediaEntity.findById(due) }, "control: a trashed photo past its retention is erased")
    }

    @Test
    fun aRestoreOfAMediaTheRetentionPurgeIsErasingAnswersNotFound() {
        repeat(RACE_ROUNDS) { round ->
            val (scopeId, mediaId) = trashedMedia()
            val restored = whileUncommitted(
                change = { store.purgeTrashedBefore(Instant.now().plusSeconds(60)) },
                overlapping = { runCatching { store.restoreMedia(scopeId, mediaId) } },
            )
            assertEquals(Result.success(null), restored, "round $round: the restore of an erased photo")
            assertNull(tx { MediaEntity.findById(mediaId) }, "round $round")
        }
    }

    @Test
    fun aMediaRestoredWhileAMemberPurgesItSurvives() {
        repeat(RACE_ROUNDS) { round ->
            val (scopeId, mediaId) = trashedMedia()
            val purged = whileUncommitted(
                change = { assertNotNull(store.restoreMedia(scopeId, mediaId), "round $round") },
                overlapping = { store.purgeMedia(scopeId, mediaId) },
            )
            assertFalse(purged, "round $round: a live photo was purged")
            assertEquals(true, tx { MediaEntity.findById(mediaId)?.let { it.deletedAt == null } }, "round $round")
        }
        val (scopeId, due) = trashedMedia()
        assertTrue(store.purgeMedia(scopeId, due), "control: a trashed photo is purged on request")
    }

    // --- helpers ---

    /** A trashed photo in a fresh personal scope. Returns (scopeId, mediaId). */
    private fun trashedMedia(): Pair<String, String> {
        val (_, scopeId) = seedMemberScope()
        val nodeId = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        tx {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                updatedAt = Instant.now(); deletedAt = Instant.now().minus(Duration.ofDays(1))
            }.persist()
        }
        return scopeId to mediaId
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
