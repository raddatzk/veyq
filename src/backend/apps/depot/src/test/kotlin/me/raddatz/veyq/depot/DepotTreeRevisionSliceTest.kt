package me.raddatz.veyq.depot

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.content.DepotBlobRoles
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The scope's tree revision (ADR 0081) over Depot's write paths — the ones `TreeRevisionCoreSliceTest` cannot reach with
 * core alone. A level view of Depot lists the tree and, beside it, each file's blob: a file is "uploaded" when its slot
 * holds one, so the completion that fills the slot, the one that replaces it and the delete that takes the file off the
 * level are all changes the live view must hear of. Each is made over the wire a client uses, as a member and as a
 * guest, and moves the revision by one; a refused completion, the control, moves it by none.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class DepotTreeRevisionSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var nodes: NodesStore

    @Test
    fun aMembersFirstUploadAReplaceAndAFileDeleteEachMoveIt() {
        val (userId, scopeId, _) = seedMemberScope()
        val file = seedNode(scopeId, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)

        var blobId = ""
        assertEquals(1L, moved(scopeId) { blobId = memberComplete(userId, scopeId, file).statusCode(200).extract().path("blobId") })
        assertEquals(1L, moved(scopeId) { memberComplete(userId, scopeId, file, expected = blobId).statusCode(200) })

        assertEquals(1L, moved(scopeId) {
            given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$file").then().statusCode(204)
        }, "deleting a file is trashing its node: it leaves every level view")
    }

    @Test
    fun aRefusedCompletionMovesNothing() {
        val (userId, scopeId, _) = seedMemberScope()
        val file = seedNode(scopeId, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        memberComplete(userId, scopeId, file).statusCode(200)
        assertEquals(0L, moved(scopeId) {
            memberComplete(userId, scopeId, file).statusCode(409) // a first upload into a filled slot
            memberComplete(userId, scopeId, file, expected = UUID.randomUUID().toString()).statusCode(409) // a stale replace
        })
    }

    @Test
    fun aGuestsFileCreateUploadAndReplaceMoveIt() {
        val (userId, scopeId, _) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, folder, ShareEntity.READ_WRITE)
        val file = UUID.randomUUID().toString()

        assertEquals(1L, moved(scopeId) { GuestTreeShare.create(link, folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201) })
        var blobId = ""
        assertEquals(1L, moved(scopeId) { blobId = guestComplete(link, file).statusCode(200).extract().path("blobId") })
        assertEquals(1L, moved(scopeId) { guestComplete(link, file, expected = blobId).statusCode(200) })
    }

    @Test
    fun aGuestsIdentityFolderAndItsUploadMoveIt() {
        val (userId, scopeId, _) = seedMemberScope()
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, dropBox, ShareEntity.WRITE_ONLY)
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.wrappedIdentityKey = "aWRlbnRpdHkta2V5"
        }

        var folder = ""
        assertEquals(1L, moved(scopeId) { folder = ensureFolder(link).statusCode(200).body("created", equalTo(true)).extract().path("nodeId") })
        assertEquals(0L, moved(scopeId) { ensureFolder(link).statusCode(200).body("created", equalTo(false)) }, "the identity's second ask makes nothing")

        val file = UUID.randomUUID().toString()
        assertEquals(1L, moved(scopeId) { GuestTreeShare.create(link, folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201) })
        assertEquals(1L, moved(scopeId) { guestComplete(link, file).statusCode(200) })
    }

    /** The root's row carries the sealed drop private key (`NodeSummary.sealedDropPrivateKey`) once its first
     *  WRITE_ONLY link is made — a change of the listing, and only the first link makes it. */
    @Test
    fun theFirstWriteOnlyLinkSetsTheDropKeyOnTheRootAndMovesIt() {
        val (userId, scopeId, _) = seedMemberScope()
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        assertEquals(1L, moved(scopeId) { createWriteOnlyShare(userId, dropBox, withPrivateKey = true).statusCode(201) })
        assertEquals(0L, moved(scopeId) { createWriteOnlyShare(userId, dropBox, withPrivateKey = false).statusCode(201) }, "a second link reuses the key")
    }

    /** A guest's tag (ADR 0081) is a digest of what it reads: a file's upload completing, and a replace, change no node
     *  row and a guest sees both — the tag covers the attachments beside the nodes, or Depot's guest page would never
     *  learn that a peer's file finished uploading. An idle resolve before it is the control. */
    @Test
    fun anUploadMovesAGuestsResolveTagThoughNoNodeRowChanged() {
        val (userId, scopeId, _) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, folder, ShareEntity.READ)

        val empty = resolveTag(link)
        given().header("If-None-Match", empty).`when`().get("/api/shares/${link.shareId}").then().statusCode(304)

        val blobId: String = memberComplete(userId, scopeId, file).statusCode(200).extract().path("blobId")
        val uploaded = resolveTag(link)
        assertNotEquals(empty, uploaded, "the first blob moved the tag")
        given().header("If-None-Match", uploaded).`when`().get("/api/shares/${link.shareId}").then().statusCode(304)

        memberComplete(userId, scopeId, file, expected = blobId).statusCode(200)
        assertNotEquals(uploaded, resolveTag(link), "a replace moved it again")
    }

    // --- the wire ---

    private fun resolveTag(link: GuestTreeShare.Link): String =
        given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200).extract().header("ETag")

    private fun memberComplete(userId: String, scopeId: String, file: String, expected: String? = null): ValidatableResponse {
        val blob = TestBlob.SMALL
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==", ownerId = file, role = DepotBlobRoles.FILE_CONTENT)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue()).extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, storageKey(uploadId))
        val expectedField = expected?.let { ""","expectedBlobId":"$it"""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"uploadId":"$uploadId"$expectedField}""")
            .`when`().post("/api/scopes/$scopeId/files/$file/content").then()
    }

    private fun guestComplete(link: GuestTreeShare.Link, file: String, expected: String? = null): ValidatableResponse {
        val blob = TestBlob.SMALL
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","nodeId":"$file","items":[${blob.declare("d3JhcA==", ownerId = file, role = DepotBlobRoles.FILE_CONTENT)}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads")
            .then().statusCode(200).body("results[0].error", nullValue()).extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, storageKey(uploadId))
        val expectedField = expected?.let { ""","expectedBlobId":"$it"""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","nodeEpoch":0,"uploadId":"$uploadId"$expectedField}""")
            .`when`().post("/api/shares/${link.shareId}/files/$file/content").then()
    }

    private fun ensureFolder(link: GuestTreeShare.Link): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body(
                """{"contributorToken":"${link.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                    """"slugToken":"${GuestTreeShare.slug()}","epoch":0,"wrappedContentKey":"d3JhcHBlZC11bmRlci1rdXA="}""",
            )
            .`when`().post("/api/shares/${link.shareId}/files/drop-folder").then()

    private fun createWriteOnlyShare(userId: String, nodeId: String, withPrivateKey: Boolean): ValidatableResponse {
        val privateKey = if (withPrivateKey) ""","sealedDropPrivateKey":"cHJpdg==""""  else ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"WRITE_ONLY","sealedDropPublicKey":"ZHJvcA=="$privateKey,"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then()
    }

    private fun storageKey(uploadId: String): String = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }

    private fun moved(scopeId: String, body: () -> Unit): Long {
        val before = QuarkusTransaction.requiringNew().call { nodes.treeRevision(scopeId) }
        body()
        return QuarkusTransaction.requiringNew().call { nodes.treeRevision(scopeId) } - before
    }
}
