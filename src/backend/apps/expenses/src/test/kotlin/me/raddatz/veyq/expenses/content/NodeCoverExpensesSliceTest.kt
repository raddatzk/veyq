package me.raddatz.veyq.expenses.content

import me.raddatz.veyq.core.applicationblob.ApplicationBlobEntity
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A Pot's cover through core's `setNodeCover` / `removeNodeCover` (owner 2026-11-04), in the application this module's
 * tests boot (core + Expenses, no Memories, no Depot): the Pot is opted in by `ExpensesNodeKindRules.takesCover`, and the
 * cover is the Pot's own blob in core's `COVER` slot - no receipt (an entry's blob of Expenses' own role) and no
 * revision of its ledger. A file's refusal is Depot's slice test's (`NodeCoverDepotSliceTest`) and the composition's
 * (`ModuleCompositionTest`); here the control is a kind no rule of this boot opts in. [FakePresignProfile] makes
 * declare-then-verify run, so the chunks are staged as an honest client leaves them.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class NodeCoverExpensesSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    private fun startStaged(userId: String, scopeId: String, node: String): CoverKit.Started {
        val started = CoverKit.start(userId, scopeId, node)
        TestBlob.SMALL.stage(fakeStore, started.storageKey)
        return started
    }

    @Test
    fun aPotTakesACoverThatIsNoReceiptAndNoRevision() {
        val (userId, scopeId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val started = startStaged(userId, scopeId, pot)

        CoverKit.set(userId, scopeId, pot, started.uploadId, null, "bTE=", 0).statusCode(204)

        assertEquals(started.blobId, CoverKit.slot(pot))
        assertEquals("bTE=" to 1L, CoverKit.meta(pot))
        QuarkusTransaction.requiringNew().run {
            val row = NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", pot, "COVER").singleResult()
            assertEquals(started.blobId, row.blobId)
            assertEquals(0L, ApplicationBlobEntity.count("nodeId", pot), "the cover is no receipt")
            assertEquals(0L, PotRevisionEntity.count("nodeId", pot), "the cover writes no revision of the ledger")
        }

        // replace and remove leave nothing behind (the zero-residue test is core's)
        val next = startStaged(userId, scopeId, pot)
        CoverKit.set(userId, scopeId, pot, next.uploadId, started.blobId, "bTI=", 1).statusCode(204)
        assertEquals(next.blobId, CoverKit.slot(pot))
        CoverKit.remove(userId, scopeId, pot, next.blobId, "bTM=", 2).statusCode(204)
        assertNull(CoverKit.slot(pot))
        QuarkusTransaction.requiringNew().run {
            assertEquals(0L, NodeAttachmentEntity.count("scopeId", scopeId))
            assertEquals(0L, BlobEntity.count("scopeId", scopeId))
            assertEquals(0L, UploadSessionEntity.count("scopeId", scopeId))
        }
    }

    @Test
    fun aKindNoRuleOptsInTakesNoCover() {
        val (userId, scopeId) = seedMemberScope()
        // Depot is not booted here: a FILE is a kind no rule of this boot claims, so it takes none (fail closed).
        val file = seedNode(scopeId, "FILE", wrappedContentKey = CONTENT_KEY)
        val started = startStaged(userId, scopeId, file)
        CoverKit.set(userId, scopeId, file, started.uploadId, null).statusCode(422).body("code", equalTo("illegal_target"))
        assertNull(CoverKit.slot(file))
    }

    @Test
    fun aPotGetsNoCoverWhileExpensesIsOff() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val pot = seedNode(scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val started = startStaged(userId, scopeId, pot)
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.EXPENSES) {
            CoverKit.set(userId, scopeId, pot, started.uploadId, null).statusCode(422).body("code", equalTo("capability_disabled"))
        }
        assertNull(CoverKit.slot(pot))
        CoverKit.set(userId, scopeId, pot, started.uploadId, null).statusCode(204) // control: on again, the same upload lands
    }
}
