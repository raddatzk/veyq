package me.raddatz.veyq.depot.content

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.GRANT
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.depot.DepotNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A drop box's cover through core's `setNodeCover` / `removeNodeCover` (inbox #411), in the application this module's
 * tests boot (core + Depot, no Memories): the drop box is opted in by `DepotNodeKindRules.takesCover`, a file is not,
 * a space member who may only read writes none, and the cover is the drop box's own blob in core's `COVER` slot - not
 * a file in the box. [FakePresignProfile] makes declare-then-verify run, so the chunks are staged as an honest client
 * leaves them.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class NodeCoverDepotSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    private fun startStaged(userId: String, scopeId: String, node: String): CoverKit.Started {
        val started = CoverKit.start(userId, scopeId, node)
        TestBlob.SMALL.stage(fakeStore, started.storageKey)
        return started
    }

    @Test
    fun aDropBoxTakesACoverThatIsNoFileInIt() {
        val (userId, scopeId) = seedMemberScope()
        val box = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val started = startStaged(userId, scopeId, box)

        CoverKit.set(userId, scopeId, box, started.uploadId, null, "bTE=", 0).statusCode(204)

        assertEquals(started.blobId, CoverKit.slot(box))
        assertEquals("bTE=" to 1L, CoverKit.meta(box))
        QuarkusTransaction.requiringNew().run {
            val row = NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", box, "COVER").singleResult()
            assertEquals(started.blobId, row.blobId)
            assertNull(row.shareIdentityId)
            assertEquals(0L, NodeEntity.count("parentId", box), "the cover is the box's own blob: no node was created in it")
            assertEquals(1L, BlobEntity.count("scopeId", scopeId))
        }

        // replace and remove leave nothing behind (the zero-residue test is core's)
        val next = startStaged(userId, scopeId, box)
        CoverKit.set(userId, scopeId, box, next.uploadId, started.blobId, "bTI=", 1).statusCode(204)
        CoverKit.remove(userId, scopeId, box, next.blobId, "bTM=", 2).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertEquals(0L, NodeAttachmentEntity.count("scopeId", scopeId))
            assertEquals(0L, BlobEntity.count("scopeId", scopeId))
            assertEquals(0L, UploadSessionEntity.count("scopeId", scopeId))
        }
    }

    @Test
    fun aFileTakesNoCoverAndAnUploadThatDoesNotFitTheDeclarationIsRefused() {
        val (userId, scopeId) = seedMemberScope()
        val file = seedNode(scopeId, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val started = startStaged(userId, scopeId, file)
        CoverKit.set(userId, scopeId, file, started.uploadId, null).statusCode(422).body("code", equalTo("illegal_target"))
        assertNull(CoverKit.slot(file))

        // declare-then-verify: a session whose stored chunks fall short of the declaration is budget_exceeded
        val box = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val unstaged = CoverKit.start(userId, scopeId, box)
        CoverKit.set(userId, scopeId, box, unstaged.uploadId, null).statusCode(400).body("code", equalTo("budget_exceeded"))
        assertNull(CoverKit.slot(box))
        assertEquals(null to 0L, CoverKit.meta(box), "nothing was written")
    }

    @Test
    fun aMemberWhoMayOnlyReadSetsNoCover() {
        val (owner, scopeId, tenantId) = seedMemberScope()
        val box = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val started = startStaged(owner, scopeId, box)

        val reader = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = reader; this.tenantId = tenantId; this.scopeId = scopeId
                role = MembershipEntity.MEMBER; wrappedGrant = GRANT; createdAt = Instant.now()
            }.persist()
        }
        // the reader holds the upload (handed over directly): only the write gate, not the session lookup, can refuse
        QuarkusTransaction.requiringNew().run { UploadSessionEntity.update("openerUserId = ?1 where id = ?2", reader, started.uploadId) }
        CoverKit.set(reader, scopeId, box, started.uploadId, null).statusCode(404).body("code", equalTo("node_not_found"))
        CoverKit.remove(reader, scopeId, box, started.blobId).statusCode(404)
        assertNull(CoverKit.slot(box))
        // control: the same upload in its opener's hands lands
        QuarkusTransaction.requiringNew().run { UploadSessionEntity.update("openerUserId = ?1 where id = ?2", owner, started.uploadId) }
        CoverKit.set(owner, scopeId, box, started.uploadId, null).statusCode(204)
    }
}
