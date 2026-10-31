package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.content.DeleteNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.ShareMediaCompleteOutcome
import me.raddatz.veyq.memories.media.ShareMediaHandler
import me.raddatz.veyq.memories.media.ShareMediaItem
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareGuestGate
import me.raddatz.veyq.core.sharing.ShareNodesHandler
import me.raddatz.veyq.core.sharing.SharingStore

/**
 * A guest's completion into a node below its share's root decides that target again in the attach's own transaction,
 * under the scope lock and then the share row's (`ShareGuestGate.admittedInto`;
 * uc.sharing.share-contributor:rights-inherit-below-a-folder, the order of `ShareNodesHandler`). Each race holds a
 * member's or a curator's write uncommitted ([whileUncommitted]) and runs the guest's completion in that window: its
 * gate and its target check — plain reads before any lock — still see the old state, so only the decision under the
 * locks stands between it and a medium where the link no longer reaches.
 *
 *  1. A member trashes the target Memory: the item is `upload_not_found`, no medium in the trash.
 *  2. A member moves the target Memory out of the shared folder: the item is `upload_not_found`, no medium outside the
 *     subtree.
 *  3. The link's owner revokes it: the item is `upload_not_found`, nothing lands on a revoked link.
 *
 * Each round has a control: the same completion with nothing overlapping lands on the target.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class GuestCompleteTargetRaceSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var nodes: NodesHandler
    @Inject lateinit var media: ShareMediaHandler
    @Inject lateinit var store: SharingStore

    /** `folder` (the share root) holds `memory`, the guest's target; `away` is a folder beside it. */
    private data class Tree(
        val userId: String, val scopeId: String, val folder: String, val memory: String, val away: String,
        val link: GuestTreeShare.Link,
    )

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val away = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, folder, memory, away, GuestTreeShare.seed(userId, scopeId, folder))
    }

    @Test
    fun aCompletionIntoAMemoryAMemberTrashesMeanwhileIsRefused() = raceAgainst("trash") { t ->
        assertEquals(DeleteNodeOutcome.Deleted, nodes.delete(t.userId, t.scopeId, t.memory))
    }

    @Test
    fun aCompletionIntoAMemoryAMemberMovesOutOfTheSubtreeMeanwhileIsRefused() = raceAgainst("move") { t ->
        assertEquals(UpdateNodeOutcome.Updated, nodes.update(t.userId, t.scopeId, t.memory, placementTo(t.away), null, null, null, null))
    }

    @Test
    fun aCompletionOnALinkRevokedMeanwhileIsRefused() = raceAgainst("revoke") { t ->
        assertEquals(true, store.revoke(t.userId, t.link.shareId))
    }

    private fun raceAgainst(what: String, change: (Tree) -> Unit) {
        raceRounds { round ->
            val t = tree()
            val uploadId = startStaged(t)
            val answer = whileUncommitted(change = { change(t) }, overlapping = { complete(t, uploadId) })
            assertEquals("upload_not_found", answer, "round $round: a completion while the $what commits")
            assertEquals(0L, tx { MediaEntity.count("scopeId", t.scopeId) }, "round $round: nothing landed after the $what")

            val kept = tree() // control: the same completion with nothing overlapping lands on the target
            val keptUpload = startStaged(kept)
            assertEquals(null, complete(kept, keptUpload), "round $round: control")
            assertEquals(kept.memory, tx { MediaEntity.find("scopeId", kept.scopeId).firstResult()!!.nodeId }, "round $round: control")
        }
    }

    /** Start one upload into the Memory and stage its chunks at their exact lengths, so its verify passes. */
    private fun startStaged(t: Tree): String {
        val uploadId: String = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${t.link.token}","nodeId":"${t.memory}","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/${t.link.shareId}/blob-uploads")
            .then().statusCode(200).extract().path("results[0].uploadId")
        TestBlob.SMALL.stage(fakeStore, tx { UploadSessionEntity.findById(uploadId)!!.storageKey })
        return uploadId
    }

    /** The guest's completion into the Memory, as the item's error (null = landed). */
    private fun complete(t: Tree, uploadId: String): String? {
        val item = ShareMediaItem(TestBlob.ownerOf(uploadId), uploadId, emptyList(), null, null)
        val o = media.complete(t.link.shareId, null, t.link.token, listOf(item), t.memory, seen = null, nodeEpoch = 0)
        return (o as ShareMediaCompleteOutcome.Results).items.single().error
    }

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
