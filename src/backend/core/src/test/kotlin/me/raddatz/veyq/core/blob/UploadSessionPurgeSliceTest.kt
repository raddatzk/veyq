package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.UploadSessionPurge
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKey
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.blobstore.ageReclaimRowsPastThePresignBound
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Slice tests for the abandoned-upload-session reclaim (uc.memory.upload E2, en.memory.media E3, ADR 0013).
 * An upload that is cancelled or dies mid-flight leaves an OPEN `veyq_upload_session` row **and** the chunks
 * the client already PUT direct-to-store; [UploadSessionPurge] reclaims both past `veyq.blob.upload.retention`.
 *
 * The load-bearing assertion is that **both** halves happen. A row-only delete is strictly worse than no GC —
 * the row is the only pointer to its `storage_key`, so dropping it alone orphans those objects for the life of
 * the bucket — and the in-memory store's `delete` is a no-op that can prove nothing. The object half is
 * therefore asserted through [RecordingBlobStore], the `@Alternative` that records what the store was handed.
 * Each case seeds its own storage keys and asserts on containment of those, never on the recording's size.
 *
 * **Since ADR 0062 the two halves are two components**, so each case drives `purge.run()` *and then*
 * `sweep.run()`. That is deliberately end-to-end rather than an assertion on `veyq_store_reclaim`: the property
 * these cases exist for is that the bytes reach the store, and a test that stopped at the reclaim row would
 * re-create the very hole [RecordingBlobStore] was built to close — proving the keys were computed, never that
 * they arrived.
 */
@QuarkusTest
class UploadSessionPurgeSliceTest {

    @Inject
    lateinit var store: BlobUploadStore

    @Inject
    lateinit var purge: UploadSessionPurge

    @Inject
    lateinit var sweep: StoreReclaimSweep

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var config: VeyqConfig

    private val recorder: RecordingBlobStore get() = blobStore as RecordingBlobStore

    @Test
    fun purgeReclaimsBothTheRowAndTheStagedChunkObjectsOfAnAbandonedSession() {
        val scopeId = seedScope()
        val storageKey = UUID.randomUUID().toString()
        val blob3 = TestBlob.ofChunks(3)
        val sessionId = store.createSession(scopeId, OPENER, storageKey, blob3.upload("d3JhcA=="))
        // the client PUT its chunks, then went away: backdate past the retention window
        backdate(sessionId, Instant.now().minus(config.blob().upload().retention()).minusSeconds(60))

        clearReclaim()
        recorder.reset()
        purge.run()
        ageReclaimRowsPastThePresignBound(config) // past the not-before, so the sweep may delete
        sweep.run()

        // the row is physically gone
        assertNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // ...and so are the chunk objects it was the only pointer to — every declared index, not just index 0
        val expected = chunkKeys(storageKey, 3)
        assertTrue(recorder.deletedKeys.containsAll(expected)) {
            "the purge must hand every staged chunk key to the store; handed: ${recorder.deletedKeys}"
        }
    }

    @Test
    fun purgeLeavesBothHalvesOfASessionInsideTheRetentionWindow() {
        val scopeId = seedScope()
        val storageKey = UUID.randomUUID().toString()
        // a live upload: started just now, still well inside the window
        val blob2 = TestBlob.ofChunks(2)
        val sessionId = store.createSession(scopeId, OPENER, storageKey, blob2.upload("d3JhcA=="))

        clearReclaim()
        recorder.reset()
        purge.run()
        ageReclaimRowsPastThePresignBound(config) // past the not-before, so the sweep may delete
        sweep.run()

        // the row survives — a slow uploader is not a garbage collector's business
        assertNotNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // ...and NOT ONE of its objects was touched, by the purge or by the sweep that ran right after it. The
        // control the positive case needs: a purge that reclaimed everything unconditionally would satisfy the
        // case above perfectly. It discriminates only because the sweep runs here too — without it the recording
        // would be empty for every input and the assertion would pass on a purge that did nothing at all.
        val untouched = chunkKeys(storageKey, 2)
        assertTrue(recorder.deletedKeys.none { it in untouched }) {
            "a session inside the window must lose no object; handed: ${recorder.deletedKeys}"
        }
    }

    @Test
    fun purgeAlsoReclaimsTheRowLeftBehindByABudgetRejection() {
        // MediaHandler's complete-time budget gate purges the staged objects and RETURNS, leaving the session
        // row standing; nothing else deletes it. Same age predicate, so this sweep is what reclaims it.
        val scopeId = seedScope()
        val storageKey = UUID.randomUUID().toString()
        val blob1 = TestBlob.ofChunks(1)
        val sessionId = store.createSession(scopeId, OPENER, storageKey, blob1.upload("d3JhcA=="))
        backdate(sessionId, Instant.now().minus(config.blob().upload().retention()).minusSeconds(60))

        clearReclaim()
        recorder.reset()
        purge.run()
        ageReclaimRowsPastThePresignBound(config) // past the not-before, so the sweep may delete
        sweep.run()

        assertNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // the re-delete of an already-purged key must be tolerated, not skipped: an abandoned session has an
        // unknown number of its chunks actually present, and `deleteObject` is idempotent for the absent ones.
        assertTrue(chunkKey(storageKey, 0) in recorder.deletedKeys)
    }

    @Test
    fun purgeKeysOnTheLastIssuedWindowNotOnTheStart() {
        // ADR 0013 §3: a large upload is signed window by window, so its START says nothing about whether it is
        // alive. Two sessions started equally long ago: the one windowed five minutes ago is a slow upload in
        // progress and survives; the one whose last window is as old as its start is abandoned and goes. Each is
        // the other's control — a purge on the start takes both, a purge that takes nothing keeps both.
        val scopeId = seedScope()
        val blob = TestBlob.ofChunks(2)
        val longAgo = Instant.now().minus(config.blob().upload().retention()).minus(Duration.ofHours(1))
        val liveKey = UUID.randomUUID().toString()
        val live = store.createSession(scopeId, OPENER, liveKey, blob.upload("d3JhcA=="))
        backdate(live, startedAt = longAgo, lastWindowAt = Instant.now().minus(Duration.ofMinutes(5)))
        val idleKey = UUID.randomUUID().toString()
        val idle = store.createSession(scopeId, OPENER, idleKey, blob.upload("d3JhcA=="))
        backdate(idle, startedAt = longAgo)

        clearReclaim()
        recorder.reset()
        purge.run()
        ageReclaimRowsPastThePresignBound(config) // past the not-before, so the sweep may delete
        sweep.run()

        assertNotNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(live) }) {
            "a session windowed inside the retention is alive, however long ago it started"
        }
        assertNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(idle) }) {
            "a session with no window inside the retention is abandoned"
        }
        assertTrue(recorder.deletedKeys.none { it.startsWith("$liveKey/") }) { "the live session must lose no object" }
    }

    @Test
    fun retentionDefaultsToTheMeasuredPresignBound() {
        // The window is bounded by construction, not taste: the purge keys on a session's last issued window, and
        // every PUT of a window is signed under this TTL, so a session with no window past it can never receive
        // another byte. PT1H is the buffer over that floor, and this pins the two against each other rather than
        // restating one of them.
        assertEquals(600, config.blobStore().presignTtlSeconds())
        assertTrue(config.blob().upload().retention().seconds > config.blobStore().presignTtlSeconds()) {
            "the retention window must outlast the presign TTL it buffers"
        }
    }

    /**
     * Drop the JVM-shared reclaim backlog before the action. `StoreReclaimSweep` takes a bounded batch of the
     * OLDEST due rows, and every purge in every earlier test class of this JVM leaves rows behind — so without
     * this the row this case just wrote is the newest in the table and can be crowded out of the batch. A flake
     * that would read as "the sweep did not hand the keys over".
     */
    private fun clearReclaim() = QuarkusTransaction.requiringNew().run { StoreReclaimEntity.deleteAll() }

    /**
     * Move a session's last issued window to [lastWindowAt] (default: [startedAt]). The start itself is no column — a
     * session keeps no creation time (inbox #318), so a purge on the start cannot even be written; [startedAt] stays
     * as the scenario's name for "a window as old as the start".
     */
    private fun backdate(sessionId: String, startedAt: Instant, lastWindowAt: Instant = startedAt) {
        QuarkusTransaction.requiringNew().run {
            UploadSessionEntity.findById(sessionId)!!.apply { this.lastWindowAt = lastWindowAt }.persist()
        }
    }

    private fun seedScope(): String = seedMemberScope().scopeId

    private companion object {
        /** Who opened the sessions this class seeds — the purge never reads it. */
        val OPENER = UploadOpener.Member("4896eaa3-ac1d-4aa7-956d-bdd5d13a6f08")
    }
}
