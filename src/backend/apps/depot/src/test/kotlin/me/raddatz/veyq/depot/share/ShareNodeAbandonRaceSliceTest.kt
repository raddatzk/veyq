package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.AbandonShareNodeOutcome
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareNodesHandler
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.depot.DepotNodeKinds
import me.raddatz.veyq.depot.content.CompleteFileOutcome
import me.raddatz.veyq.depot.content.DepotBlobRoles
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A guest's take-back of an empty file (`abandonShareNode`) and a completion into that file decide each other under the
 * scope lock (`ShareNodesHandler.abandon`, `AttachmentStore.attach`): whichever commits first is seen by the other, so a
 * file is never erased with a blob landing on it, and no blob lands on an erased file. Each race holds one side's write
 * uncommitted ([whileUncommitted]) and runs the other in that window; its plain reads before the lock still see the old
 * state, so only the decision under the lock keeps it right.
 *
 *  1. The guest's completion commits while an abandon of the file runs: `not_a_placeholder`, the file keeps its blob.
 *  2. The abandon's erase commits while a completion into the file runs: the completion attaches nothing, no blob row.
 *
 * Each round has a control: the same operation with nothing overlapping goes through.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class ShareNodeAbandonRaceSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var files: ShareFilesHandler
    @Inject lateinit var abandons: ShareNodesHandler
    @Inject lateinit var purge: NodeSubtreePurge
    @Inject lateinit var tree: NodesStore

    /** `folder` (the share root) holds `file`, an empty file the guest made (its name's editor) and is uploading into. */
    private data class Tree(val scopeId: String, val file: String, val link: GuestTreeShare.Link)

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, folder)
        val identity = tx { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }
        val file = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY, nameEditor = PrincipalKey.Share(identity))
        return Tree(scopeId, file, link)
    }

    @Test
    fun anAbandonWhileTheGuestsCompletionCommitsKeepsTheFileAndItsBlob() {
        raceRounds { round ->
            val t = tree()
            val uploadId = startStaged(t)
            var completed: CompleteFileOutcome? = null
            // The completion joins the held transaction (`attach` is `@Transactional`, required).
            val answer = whileUncommitted(change = { completed = complete(t, uploadId) }, overlapping = { abandon(t) })
            assertTrue(completed is CompleteFileOutcome.Ok, "round $round: the completion landed")
            assertEquals(AbandonShareNodeOutcome.NotAPlaceholder, answer, "round $round: an abandon while the completion commits")
            assertNotNull(tx { NodeEntity.findById(t.file) }, "round $round: the file is kept")
            assertEquals(1L, tx { NodeAttachmentEntity.count("nodeId", t.file) }, "round $round: with its blob")

            val control = tree() // the same abandon with nothing overlapping erases the file, the guest's own session open
            startStaged(control)
            assertEquals(AbandonShareNodeOutcome.Abandoned, abandon(control), "round $round: control")
            assertNull(tx { NodeEntity.findById(control.file) }, "round $round: control erased")
        }
    }

    @Test
    fun aCompletionWhileTheAbandonCommitsAttachesNothing() {
        raceRounds { round ->
            val t = tree()
            val uploadId = startStaged(t)
            // `abandon` opens its own transaction, so its writes are held as it makes them: the scope lock, then the erase.
            val answer = whileUncommitted(
                change = { tree.lockScope(t.scopeId); purge.erase(t.scopeId, listOf(NodeEntity.findById(t.file)!!)) },
                overlapping = { complete(t, uploadId) },
            )
            assertFalse(answer is CompleteFileOutcome.Ok, "round $round: a completion while the erase commits, answered $answer")
            assertEquals(0L, tx { NodeAttachmentEntity.count("nodeId", t.file) }, "round $round: nothing attached")
            assertEquals(0L, tx { BlobEntity.count("id", declaredBlobs.getValue(uploadId)) }, "round $round: no blob row")

            val control = tree() // the same completion with nothing overlapping lands
            assertTrue(complete(control, startStaged(control)) is CompleteFileOutcome.Ok, "round $round: control")
        }
    }

    private fun abandon(t: Tree): AbandonShareNodeOutcome = abandons.abandon(t.link.shareId, null, t.link.token, t.file)

    /** The client-minted blob id each upload [startStaged] opened declared (a session read after the race may be gone). */
    private val declaredBlobs = mutableMapOf<String, String>()

    /** Start the guest's upload into the file and stage its chunks at their exact lengths, so its verify passes. */
    private fun startStaged(t: Tree): String {
        val blobId = UUID.randomUUID().toString()
        val declare = TestBlob.SMALL.declare("d3JhcA==", ownerId = t.file, role = DepotBlobRoles.FILE_CONTENT, blobId = blobId)
        val uploadId: String = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${t.link.token}","nodeId":"${t.file}","items":[$declare]}""")
            .`when`().post("/api/shares/${t.link.shareId}/blob-uploads")
            .then().statusCode(200).extract().path("results[0].uploadId")
        TestBlob.SMALL.stage(fakeStore, tx { UploadSessionEntity.findById(uploadId)!!.storageKey })
        declaredBlobs[uploadId] = blobId
        return uploadId
    }

    private fun complete(t: Tree, uploadId: String): CompleteFileOutcome {
        val o = files.complete(t.link.shareId, null, t.link.token, t.file, uploadId, null, nodeEpoch = 0, seen = null)
        return (o as ShareFileCompleteOutcome.Completed).outcome
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
