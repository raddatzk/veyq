package com.veyq.app.features.media

import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.blobstore.RecordingBlobStore
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
 */
@QuarkusTest
class UploadSessionPurgeSliceTest {

    @Inject
    lateinit var store: MediaStore

    @Inject
    lateinit var purge: UploadSessionPurge

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var config: VeyqConfig

    private val recorder: RecordingBlobStore get() = blobStore as RecordingBlobStore

    @Test
    fun purgeReclaimsBothTheRowAndTheStagedChunkObjectsOfAnAbandonedSession() {
        val scopeId = seedScope()
        val storageKey = UUID.randomUUID().toString()
        val sessionId = store.createSession(scopeId, storageKey, chunkCount = 3, byteSize = 900, wrappedBlobKey = "d3JhcA==")
        // the client PUT its chunks, then went away: backdate past the retention window
        backdate(sessionId, Instant.now().minus(config.media().upload().retention()).minusSeconds(60))

        recorder.reset()
        purge.run()

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
        val sessionId = store.createSession(scopeId, storageKey, chunkCount = 2, byteSize = 600, wrappedBlobKey = "d3JhcA==")

        recorder.reset()
        purge.run()

        // the row survives — a slow uploader is not a garbage collector's business
        assertNotNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // ...and NOT ONE of its objects was touched. The control the positive case needs: a purge that deleted
        // everything unconditionally would satisfy the case above perfectly.
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
        val sessionId = store.createSession(scopeId, storageKey, chunkCount = 1, byteSize = 300, wrappedBlobKey = "d3JhcA==")
        backdate(sessionId, Instant.now().minus(config.media().upload().retention()).minusSeconds(60))

        recorder.reset()
        purge.run()

        assertNull(QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(sessionId) })
        // the re-delete of an already-purged key must be tolerated, not skipped: an abandoned session has an
        // unknown number of its chunks actually present, so the store delete is defined as best-effort.
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
