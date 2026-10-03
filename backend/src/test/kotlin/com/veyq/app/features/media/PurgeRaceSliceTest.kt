package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.RestoreGroupOutcome
import com.veyq.app.features.spaces.scope.ScopePurge
import com.veyq.app.features.spaces.scope.ScopesHandler
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A purge erases only what is still due when it erases it, and a restore answers as a replay would (developer-
 * styleguide §12: a purge's selection is its lock, a restore is a guarded update). A purge read its worklist as a
 * plain read and deleted afterwards; a restore — or an upload's next window — that committed in between was erased
 * anyway, an outcome neither order of the two produces: a restored photo, a restored group, a live upload's session
 * gone. The reverse overlap, a restore of a row a purge is deleting, was a 500 at the restore's flush.
 *
 * Each race holds one side's commit back ([whileUncommitted]) and runs the other inside that window. Each has a
 * control: with nothing overlapping, the purge still erases what is due.
 */
@QuarkusTest
class PurgeRaceSliceTest {

    @Inject lateinit var store: MediaStore
    @Inject lateinit var scopes: ScopesStore
    @Inject lateinit var scopeHandler: ScopesHandler
    @Inject lateinit var scopePurge: ScopePurge

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

    @Test
    fun anUploadWindowedWhileTheIdlePurgeRunsKeepsItsSession() {
        repeat(RACE_ROUNDS) { round ->
            val sessionId = idleSession()
            whileUncommitted(
                change = { store.touchWindow(sessionId) },
                overlapping = { store.purgeSessionsIdleSince(Instant.now().minus(Duration.ofDays(1))) },
            )
            assertNotNull(tx { UploadSessionEntity.findById(sessionId) }, "round $round: the purge dropped a live upload")
        }
        val idle = idleSession()
        store.purgeSessionsIdleSince(Instant.now().minus(Duration.ofDays(1)))
        assertNull(tx { UploadSessionEntity.findById(idle) }, "control: an idle session is purged")
    }

    @Test
    fun aGroupRestoredWhileThePurgeRunsSurvives() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = expiredGroup()
            whileUncommitted(
                change = { scopes.restore(scopeId) },
                overlapping = { scopePurge.run() },
            )
            assertEquals(true, tx { ScopeEntity.findById(scopeId)?.let { it.deletedAt == null } }, "round $round: the purge erased a restored group")
        }
        val (_, due) = expiredGroup()
        scopePurge.run()
        assertNull(tx { ScopeEntity.findById(due) }, "control: a group past its grace is erased")
    }

    @Test
    fun aRestoreOfAGroupThePurgeIsErasingAnswersNotFound() {
        repeat(RACE_ROUNDS) { round ->
            val (owner, scopeId) = expiredGroup()
            val outcome = whileUncommitted(
                change = { scopePurge.purge(scopeId) },
                overlapping = { runCatching { scopeHandler.restoreGroup(owner, scopeId) } },
            )
            assertEquals(Result.success(RestoreGroupOutcome.ScopeNotFound), outcome, "round $round")
        }
    }

    // --- helpers ---

    /** A trashed photo in a fresh personal scope. Returns (scopeId, mediaId). */
    private fun trashedMedia(): Pair<String, String> {
        val (_, scopeId) = seedMemberScope()
        val nodeId = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        tx {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
                createdAt = Instant.now()
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                createdAt = Instant.now(); deletedAt = Instant.now().minus(Duration.ofDays(1))
            }.persist()
        }
        return scopeId to mediaId
    }

    /** An upload session last windowed two days ago — idle past a one-day cutoff. */
    private fun idleSession(): String {
        val (userId, scopeId) = seedMemberScope()
        val id = UUID.randomUUID().toString()
        tx {
            UploadSessionEntity().apply {
                this.id = id; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
                openerUserId = userId; createdAt = Instant.now().minus(Duration.ofDays(2))
                lastWindowAt = Instant.now().minus(Duration.ofDays(2))
            }.persist()
        }
        return id
    }

    /** A group its owner deleted long past the grace. Returns (owner, scopeId). */
    private fun expiredGroup(): Pair<String, String> {
        val (owner, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        tx { ScopeEntity.findById(scopeId)!!.deletedAt = Instant.now().minus(Duration.ofDays(1000)) }
        return owner to scopeId
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
