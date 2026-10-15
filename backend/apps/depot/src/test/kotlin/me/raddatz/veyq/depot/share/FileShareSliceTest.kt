package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.DepotNodeKinds
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
import org.junit.jupiter.api.Test

/**
 * A single file as a share root (inbox #378, ADR 0080 decisions 1 and 6): Depot's [me.raddatz.veyq.depot.DepotShareRoots]
 * lets a `FILE` be shared `READ` or `READ_WRITE` — never `WRITE_ONLY`, which would mean nothing on a node with no identity
 * folder and no key to hand out. A guest of such a link sees exactly that file (its blob, its download) and nothing beside
 * or above it; a `READ_WRITE` guest replaces its content through the same guest transport and `completeShareFile` a
 * folder's contributor uses, and nothing else (no create under a file, no placement of the root). The links are created
 * through the member API where the creation is the question, and seeded as rows ([GuestTreeShare]) for the guest's side.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class FileShareSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    /** `file` is the shared one, `sibling` a file beside it, both under `folder`; `file` and `sibling` hold a blob. */
    private data class Tree(val userId: String, val scopeId: String, val folder: String, val file: String, val sibling: String)

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        memberUpload(userId, scopeId, file)
        memberUpload(userId, scopeId, sibling)
        return Tree(userId, scopeId, folder, file, sibling)
    }

    // --- creating the link: ShareRoots --------------------------------------------------------------------------

    @Test
    fun aFileIsSharedReadOrReadWriteNeverWriteOnly() {
        val t = tree()
        create(t.userId, t.file, "READ").statusCode(201)
        create(t.userId, t.file, "READ_WRITE").statusCode(201)
        // WRITE_ONLY means nothing on a file: refused whole, stored nowhere (control: the drop box takes it, the
        // DropBoxSliceTest's; here the two roles above went through the same path).
        create(t.userId, t.file, "WRITE_ONLY").statusCode(422).body("code", equalTo("illegal_scope"))
        assertEquals(listOf("READ", "READ_WRITE"), tx { ShareEntity.find("nodeId", t.file).list().map { it.role }.sorted() })
    }

    @Test
    fun aDepotLinkChangesBetweenReadAndReadWriteAndNeverToWriteOnly() {
        val t = tree()
        val share: String = create(t.userId, t.file, "READ").statusCode(201).extract().path("shareId")
        changeRole(t.userId, share, "READ_WRITE").statusCode(204)
        assertEquals("READ_WRITE", tx { ShareEntity.findById(share)!!.role })
        changeRole(t.userId, share, "WRITE_ONLY").statusCode(422).body("code", equalTo("invalid_role_change"))
        assertEquals("READ_WRITE", tx { ShareEntity.findById(share)!!.role }, "refused: the role is unchanged")
    }

    @Test
    fun aTrashedFileIsSharedByNobody() {
        val t = tree()
        create(t.userId, t.file, "READ").statusCode(201) // control: live
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/${t.sibling}").then().statusCode(204)
        create(t.userId, t.sibling, "READ").statusCode(404).body("code", equalTo("node_not_found"))
        create(t.userId, t.sibling, "READ_WRITE").statusCode(404).body("code", equalTo("node_not_found"))
        assertEquals(0L, tx { ShareEntity.count("nodeId", t.sibling) })
    }

    // --- the guest of a READ link: the file, nothing else ------------------------------------------------------------

    @Test
    fun aReadGuestOfAFileSeesAndDownloadsThatFileOnly() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.file, ShareEntity.READ)

        // The resolve serves the file alone: neither its folder nor its sibling, by id or by content.
        val body = given().`when`().get("/api/shares/${link.shareId}").then().statusCode(200)
            .body("rootKind", equalTo(DepotNodeKinds.FILE))
            .body("nodes.size()", equalTo(1))
            .body("nodes[0].id", equalTo(t.file))
            .body("nodes[0].parentId", nullValue())
            .extract().asString()
        for (absent in listOf(t.folder, t.sibling)) assertEquals(false, body.contains(absent), "nothing beside or above the file: $absent")

        // Depot's listing: the one file with its blob; the sibling's blob is not downloadable through this link.
        listShare(link).statusCode(200).body("items.size()", equalTo(1)).body("items[0].nodeId", equalTo(t.file))
            .body("items[0].fileBlob.blobId", equalTo(blobOf(t.file)))
        shareBlob(link, blobOf(t.file)!!).statusCode(200).body("presignedGets.size()", equalTo(1))
        shareBlob(link, blobOf(t.sibling)!!).statusCode(404).body("code", equalTo("share_not_found"))

        // A READ guest writes nothing.
        guestStart(link, t.file).statusCode(403).body("code", equalTo("share_not_contributable"))
        completeShare(link, t.file, UUID.randomUUID().toString()).statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    // --- the guest of a READ_WRITE link: replaces the file, does nothing else ------------------------------------------

    @Test
    fun aReadWriteGuestReplacesTheFileAndNothingElse() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.file, ShareEntity.READ_WRITE)
        val first = blobOf(t.file)!!

        // The replace names the file (the root), reads the blob it saw (a compare-and-set), lands as the guest's.
        val fresh = TestBlob.ofChunks(2)
        val second = completeShare(link, t.file, guestStartStaged(link, t.file, fresh, meta = "bWV0YS0x"), expected = first)
            .statusCode(200).body("wrappedBlobMeta", equalTo("bWV0YS0x")).extract().path<String>("blobId")
        assertEquals(second, blobOf(t.file))
        listShare(link).statusCode(200).body("items[0].fileBlob.blobId", equalTo(second))
        // A stale replace is refused and leaves the slot (a peer replaced it meanwhile).
        completeShare(link, t.file, guestStartStaged(link, t.file), expected = first).statusCode(409)
            .body("code", equalTo("stale_file")).body("currentBlobId", equalTo(second))
        assertEquals(second, blobOf(t.file))

        // Nothing beside the file is the guest's: not its sibling, not its folder, nothing created under it.
        guestStart(link, t.sibling).statusCode(404).body("code", equalTo("node_not_found"))
        completeShare(link, t.sibling, UUID.randomUUID().toString()).statusCode(404).body("code", equalTo("node_not_found"))
        guestStart(link, t.folder).statusCode(404).body("code", equalTo("node_not_found"))
        GuestTreeShare.create(link, t.file, DepotNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_scope"))
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        // The root is never placed by a guest (no rename, no move).
        GuestTreeShare.place(link, t.file, t.folder).statusCode(422).body("code", equalTo("illegal_structure"))
        assertEquals(0, tx { NodeEntity.count("parentId", t.file) }.toInt(), "no node under the file")
        assertEquals(first.let { blobOf(t.sibling) }, blobOf(t.sibling), "the sibling's blob is untouched")
    }

    @Test
    fun aGuestOfAFileLinkLosesTheFileWhenItIsTrashed() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.file, ShareEntity.READ_WRITE)
        val blobId = blobOf(t.file)!!
        val upload = guestStartStaged(link, t.file) // started while the file was live
        shareBlob(link, blobId).statusCode(200) // control

        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/${t.file}").then().statusCode(204)

        listShare(link).statusCode(200).body("items.size()", equalTo(0))
        shareBlob(link, blobId).statusCode(404).body("code", equalTo("share_not_found"))
        completeShare(link, t.file, upload, expected = blobId).statusCode(403).body("code", equalTo("share_not_contributable"))
        guestStart(link, t.file).statusCode(403).body("code", equalTo("share_not_contributable"))
        assertEquals(blobId, blobOf(t.file), "the trashed file keeps its blob")
    }

    // --- the wire ------------------------------------------------------------------------------------------------

    private fun create(userId: String, nodeId: String, role: String): ValidatableResponse {
        val keys = if (role == "WRITE_ONLY") """"sealedDropPublicKey":"ZHJvcA==","sealedDropPrivateKey":"cHJpdg==""""
        else """"wrap":"d3JhcA==""""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",$keys,"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then()
    }

    private fun changeRole(userId: String, shareId: String, role: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"role":"$role"}""")
            .`when`().patch("/api/shares/$shareId").then()

    private fun memberStart(userId: String, scopeId: String, node: String, blob: TestBlob = TestBlob.SMALL): String {
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==", ownerId = node, role = DepotBlobRoles.FILE_CONTENT)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, sessionKey(uploadId))
        return uploadId
    }

    private fun memberUpload(userId: String, scopeId: String, node: String) {
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"${memberStart(userId, scopeId, node)}"}""")
            .`when`().post("/api/scopes/$scopeId/files/$node/content").then().statusCode(200)
    }

    private fun guestStart(link: GuestTreeShare.Link, node: String?, blob: TestBlob = TestBlob.SMALL, meta: String? = null): ValidatableResponse {
        val target = node?.let { ""","nodeId":"$it"""" } ?: ""
        val item = blob.declare("d3JhcA==", ownerId = node ?: UUID.randomUUID().toString(), role = DepotBlobRoles.FILE_CONTENT, meta = meta)
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}"$target,"items":[$item]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads").then()
    }

    private fun guestStartStaged(link: GuestTreeShare.Link, node: String, blob: TestBlob = TestBlob.SMALL, meta: String? = null): String {
        val uploadId = guestStart(link, node, blob, meta).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, sessionKey(uploadId))
        return uploadId
    }

    private fun completeShare(link: GuestTreeShare.Link, node: String, uploadId: String, expected: String? = null): ValidatableResponse {
        val extra = expected?.let { ""","expectedBlobId":"$it"""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","uploadId":"$uploadId"$extra}""")
            .`when`().post("/api/shares/${link.shareId}/files/$node/content").then()
    }

    private fun listShare(link: GuestTreeShare.Link): ValidatableResponse = given().`when`().get("/api/shares/${link.shareId}/files").then()

    private fun shareBlob(link: GuestTreeShare.Link, blobId: String): ValidatableResponse =
        given().`when`().get("/api/shares/${link.shareId}/blobs/$blobId").then()

    // --- committed state -----------------------------------------------------------------------------------------

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private fun blobOf(node: String): String? = tx {
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, DepotBlobRoles.FILE_CONTENT).firstResult()?.blobId
    }

    private fun sessionKey(uploadId: String): String = tx { UploadSessionEntity.findById(uploadId)!!.storageKey }
}
