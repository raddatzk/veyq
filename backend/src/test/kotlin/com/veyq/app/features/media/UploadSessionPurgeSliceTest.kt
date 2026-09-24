package com.veyq.app.features.media

import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.blobstore.RecordingBlobStore
import com.veyq.app.shared.blobstore.StoreReclaimEntity
import com.veyq.app.shared.blobstore.StoreReclaimSweep
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the abandoned-upload-session reclaim (uc.memory.upload E2, en.memory.media E3, ADR 0013).
 * An upload that is cancelled or dies mid-flight leaves an OPEN `veyq_upload_session` row **and** the chunks
 * the client already PUT direct-to-store; [UploadSessionPurge] reclaims both past `veyq.media.upload.retention`.
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
    lateinit var store: MediaStore

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
        val sessionId = store.createSession(scopeId, storageKey, chunkCount = blob3.chunkCount, byteSize = blob3.byteSize, wrappedBlobKey = "d3JhcA==")
        // the client PUT its chunks, then went away: backdate past the retention window
        backdate(sessionId, Instant.now().minus(config.media().upload().retention()).minusSeconds(60))

        clearReclaim()
        recorder.reset()
        purge.run()
        sweep.run()

        // the row is physically gone
        assertNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // ...and so are the chunk objects it was the only pointer to — every declared index, not just index 0
        val expected = (0 until 3).map { chunkKey(storageKey, it) }
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
        val sessionId = store.createSession(scopeId, storageKey, chunkCount = blob2.chunkCount, byteSize = blob2.byteSize, wrappedBlobKey = "d3JhcA==")

        clearReclaim()
        recorder.reset()
        purge.run()
        sweep.run()

        // the row survives — a slow uploader is not a garbage collector's business
        assertNotNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // ...and NOT ONE of its objects was touched, by the purge or by the sweep that ran right after it. The
        // control the positive case needs: a purge that reclaimed everything unconditionally would satisfy the
        // case above perfectly. It discriminates only because the sweep runs here too — without it the recording
        // would be empty for every input and the assertion would pass on a purge that did nothing at all.
        val untouched = (0 until 2).map { chunkKey(storageKey, it) }
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
        val sessionId = store.createSession(scopeId, storageKey, chunkCount = blob1.chunkCount, byteSize = blob1.byteSize, wrappedBlobKey = "d3JhcA==")
        backdate(sessionId, Instant.now().minus(config.media().upload().retention()).minusSeconds(60))

        clearReclaim()
        recorder.reset()
        purge.run()
        sweep.run()

        assertNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // the re-delete of an already-purged key must be tolerated, not skipped: an abandoned session has an
        // unknown number of its chunks actually present, and `deleteObject` is idempotent for the absent ones.
        assertTrue(chunkKey(storageKey, 0) in recorder.deletedKeys)
    }

    @Test
    fun retentionDefaultsToTheMeasuredPresignBound() {
        // The window is bounded by construction, not taste: all PUTs are signed once at `start` under this TTL
        // and nothing re-signs them, so a session past it can never receive another byte. PT1H is the buffer
        // over that floor, and this pins the two against each other rather than restating one of them.
        assertEquals(600, config.blobStore().presignTtlSeconds())
        assertTrue(config.media().upload().retention().seconds > config.blobStore().presignTtlSeconds()) {
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

    private fun backdate(sessionId: String, at: Instant) {
        QuarkusTransaction.requiringNew().run {
            UploadSessionEntity.findById(sessionId)!!.apply { createdAt = at }.persist()
        }
    }

    private fun seedScope(): String {
        val userId = UUID.randomUUID().toString()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return scopeId
    }
}
