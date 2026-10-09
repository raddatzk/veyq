package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.spaces.scope.RestoreGroupOutcome
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.spaces.scope.ScopesHandler
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
 * **Split from `PurgeRaceSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `PurgeRaceSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class PurgeRaceCoreSliceTest {

    @Test
    fun anUploadWindowedWhileTheIdlePurgeRunsKeepsItsSession() {
        repeat(RACE_ROUNDS) { round ->
            val sessionId = idleSession()
            whileUncommitted(
                change = { blobs.touchWindow(sessionId) },
                overlapping = { blobs.purgeSessionsIdleSince(Instant.now().minus(Duration.ofDays(1))) },
            )
            assertNotNull(tx { UploadSessionEntity.findById(sessionId) }, "round $round: the purge dropped a live upload")
        }
        val idle = idleSession()
        blobs.purgeSessionsIdleSince(Instant.now().minus(Duration.ofDays(1)))
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

    @Inject lateinit var blobs: BlobUploadStore

    @Inject lateinit var scopes: ScopesStore

    @Inject lateinit var scopeHandler: ScopesHandler

    @Inject lateinit var scopePurge: ScopePurge

    // --- helpers ---

    /** An upload session last windowed two days ago — idle past a one-day cutoff. */
    private fun idleSession(): String {
        val (userId, scopeId) = seedMemberScope()
        val id = UUID.randomUUID().toString()
        tx {
            UploadSessionEntity().apply {
                this.id = id; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
                openerUserId = userId
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
