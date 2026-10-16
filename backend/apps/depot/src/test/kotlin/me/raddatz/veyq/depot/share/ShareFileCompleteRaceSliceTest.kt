package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.content.DeleteNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.depot.DepotNodeKinds
import me.raddatz.veyq.depot.content.CompleteFileOutcome
import me.raddatz.veyq.depot.content.DepotBlobRoles
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A guest's `completeShareFile` decides its file again in the attach's own transaction, under the scope lock and then the
 * share row's (`ShareGuestGate.admittedInto`, the order of `ShareNodesHandler`) — cr-files-application F2, the Depot twin
 * of Memories' `GuestCompleteTargetRaceSliceTest`. Each race holds a member's write uncommitted ([whileUncommitted]) and
 * runs the guest's completion in that window: its gate and target check — plain reads before any lock — still see the
 * old state, so only the decision under the locks keeps a blob off a file the link no longer reaches.
 *
 *  1. A member trashes the file: `upload_not_found`, the trashed file keeps no new blob.
 *  2. A member moves the file out of the shared folder: `upload_not_found`, nothing attached outside the subtree.
 *  3. The link's owner revokes it: `upload_not_found`, nothing lands on a revoked link.
 *
 * Each round has a control: the same completion with nothing overlapping lands.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class ShareFileCompleteRaceSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var nodes: NodesHandler
    @Inject lateinit var files: ShareFilesHandler
    @Inject lateinit var store: SharingStore

    /** `folder` (the share root) holds `file`, the guest's target; `away` is a folder beside it. */
    private data class Tree(
        val userId: String, val scopeId: String, val folder: String, val file: String, val away: String, val link: GuestTreeShare.Link,
    )

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val away = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, folder, file, away, GuestTreeShare.seed(userId, scopeId, folder))
    }

    @Test
    fun aCompletionIntoAFileAMemberTrashesMeanwhileIsRefused() = raceAgainst("trash") { t ->
        assertEquals(DeleteNodeOutcome.Deleted, nodes.delete(t.userId, t.scopeId, t.file))
    }

    @Test
    fun aCompletionIntoAFileAMemberMovesOutOfTheSubtreeMeanwhileIsRefused() = raceAgainst("move") { t ->
        assertEquals(UpdateNodeOutcome.Updated, nodes.update(t.userId, t.scopeId, t.file, placementTo(t.away), null, null, null))
    }

    @Test
    fun aCompletionOnALinkRevokedMeanwhileIsRefused() = raceAgainst("revoke") { t ->
        assertEquals(true, store.revoke(t.userId, t.link.shareId))
    }

    private fun raceAgainst(what: String, change: (Tree) -> Unit) {
        repeat(RACE_ROUNDS) { round ->
            val t = tree()
            val uploadId = startStaged(t)
            val answer = whileUncommitted(change = { change(t) }, overlapping = { complete(t, uploadId) })
            assertEquals(CompleteFileOutcome.NoUpload, answer, "round $round: a completion while the $what commits")
            assertEquals(0L, tx { NodeAttachmentEntity.count("nodeId", t.file) }, "round $round: nothing landed after the $what")
            assertEquals(0L, tx { BlobEntity.count("id", blobOf(uploadId)) }, "round $round: no blob (and so no meta) after the $what")

            val kept = tree() // control: the same completion with nothing overlapping lands on the file, its meta with it
            val keptUpload = startStaged(kept)
            assertTrue(complete(kept, keptUpload) is CompleteFileOutcome.Ok, "round $round: control")
            assertEquals(META, tx { BlobEntity.findById(blobOf(keptUpload))!!.wrappedBlobMeta }, "round $round: control meta")
            assertEquals(0L, tx { NodeEntity.findById(kept.file)!!.metaRevision }, "round $round: the node's payload untouched")
        }
    }

    /** The client-minted blob id each upload [startStaged] opened declared (a session read after the race may be gone). */
    private val declaredBlobs = mutableMapOf<String, String>()
    private fun blobOf(uploadId: String): String = declaredBlobs.getValue(uploadId)

    /** Start one upload into the file, its blob meta declared, and stage its chunks at their exact lengths, so its
     *  verify passes. */
    private fun startStaged(t: Tree): String {
        val blobId = UUID.randomUUID().toString()
        val declare = TestBlob.SMALL.declare("d3JhcA==", ownerId = t.file, role = DepotBlobRoles.FILE_CONTENT, blobId = blobId, meta = META)
        val uploadId: String = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${t.link.token}","nodeId":"${t.file}","items":[$declare]}""")
            .`when`().post("/api/shares/${t.link.shareId}/blob-uploads")
            .then().statusCode(200).extract().path("results[0].uploadId")
        TestBlob.SMALL.stage(fakeStore, tx { UploadSessionEntity.findById(uploadId)!!.storageKey })
        declaredBlobs[uploadId] = blobId
        return uploadId
    }

    /** The guest's completion into the file, as the completion's answer. */
    private fun complete(t: Tree, uploadId: String): CompleteFileOutcome {
        val o = files.complete(t.link.shareId, null, t.link.token, t.file, uploadId, null)
        return (o as ShareFileCompleteOutcome.Completed).outcome
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private companion object {
        /** An opaque stand-in for a client's `blob-meta` seal, declared with every start. */
        const val META = "c2VhbGVkLWJsb2ItbWV0YQ=="
    }
}
