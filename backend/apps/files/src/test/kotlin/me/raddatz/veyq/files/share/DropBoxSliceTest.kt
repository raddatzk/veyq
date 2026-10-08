package me.raddatz.veyq.files.share

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.crypto.Tokens
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
import me.raddatz.veyq.files.FilesNodeKinds
import me.raddatz.veyq.files.content.FilesBlobRoles
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The drop box (cr-files-application F3, ADR 0080 decision 11), in the application this module's tests boot (core +
 * Files): a `FILES_DROPBOX` is shared `WRITE_ONLY`; each guest identity gets ONE identity folder in it
 * (`ensureShareDropFolder`), creates its files there with core's `createShareNode` and uploads into them; two guests get
 * two folders, a second call of one identity answers the same folder; a member places nothing into a drop box and only
 * deletes or moves out; revoking the link keeps the folders, the files and the identities that open them. The link is
 * seeded as rows ([GuestTreeShare]); [FakePresignProfile] makes the uploads verify. Each refusal beside its control.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class DropBoxSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var purge: NodeSubtreePurge

    /** A member's scope with `folder` holding the drop box `dropBox`, and a plain `other` folder beside it. */
    private data class Tree(val userId: String, val scopeId: String, val folder: String, val dropBox: String, val other: String)

    private fun tree(): Tree {
        val (userId, scopeId, _) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val dropBox = seedNode(scopeId, FilesNodeKinds.DROPBOX, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, folder, dropBox, other)
    }

    @Test
    fun twoGuestsGetTwoFoldersAndASecondUploadOfOneIdentityLandsInTheSameFolder() {
        val t = tree()
        val anna = dropLink(t)
        val ben = secondGuest(anna)

        val annaFolder = ensure(anna).statusCode(200).body("created", equalTo(true)).body("placementVersion", equalTo(0))
            .extract().path<String>("nodeId")
        // the same identity again — another minted id, another wrap: the stored folder answers, nothing new is made
        ensure(anna, wrap = "b3RoZXItd3JhcA==").statusCode(200).body("created", equalTo(false))
            .body("nodeId", equalTo(annaFolder)).body("wrappedContentKey", equalTo(WRAP))
        val benFolder = ensure(ben).statusCode(200).body("created", equalTo(true)).extract().path<String>("nodeId")
        assertNotEquals(annaFolder, benFolder)
        assertEquals(listOf(annaFolder, benFolder).sorted(), childrenOf(t.dropBox).sorted(), "one folder per identity, nothing else")
        assertEquals(NodeEntity.FOLDER to identityOf(anna), tx { NodeEntity.findById(annaFolder)!!.let { it.kind to it.shareIdentityId } })

        // Anna uploads twice: both files land in her one folder.
        val first = guestFile(anna, annaFolder)
        val second = guestFile(anna, annaFolder)
        assertEquals(annaFolder to annaFolder, parentOf(first) to parentOf(second))
        assertEquals(identityOf(anna), attachmentIdentity(second))

        // Ben cannot write into Anna's folder or her file, nor beside the folders; a write-only link serves no listing.
        GuestTreeShare.create(ben, annaFolder, FilesNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        GuestTreeShare.create(ben, t.dropBox, FilesNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        guestStart(ben, first).statusCode(403).body("code", equalTo("share_not_contributable"))
        // control: Ben's own folder takes his file
        guestFile(ben, benFolder)
        given().`when`().get("/api/shares/${anna.shareId}/files").then().statusCode(200).body("items.size()", equalTo(0))

        // The member lists the folders with what opens them: the identity and its wrapped K_up beside the drop box's key.
        val nodes = given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes")
            .then().statusCode(200).extract()
        assertEquals(identityOf(anna), nodes.path<String>("find { it.id == '$annaFolder' }.shareIdentityId"))
        assertEquals(IDENTITY_KEY, nodes.path<String>("find { it.id == '$annaFolder' }.wrappedIdentityKey"))
        assertNull(nodes.path<String?>("find { it.id == '$first' }.shareIdentityId"), "a file in the folder names no identity")
        // and its files through Files' own listing
        given().header("Authorization", bearer(t.userId)).queryParam("parentId", annaFolder)
            .`when`().get("/api/scopes/${t.scopeId}/files").then().statusCode(200).body("items.size()", equalTo(2))
    }

    @Test
    fun aMemberPlacesNothingInADropBoxAndOnlyDeletesOrMovesOut() {
        val t = tree()
        val guest = dropLink(t)
        val folder = ensure(guest).statusCode(200).extract().path<String>("nodeId")
        val file = guestFile(guest, folder)

        // A member creates nothing in the drop box or in a guest's folder, renames nothing there, moves nothing in.
        memberCreate(t, t.dropBox, NodeEntity.FOLDER).statusCode(422).body("code", equalTo("illegal_structure"))
        memberCreate(t, folder, FilesNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        memberPlace(t, folder, to = t.dropBox, version = 0).statusCode(422).body("code", equalTo("illegal_structure"))
        memberPlace(t, file, to = folder, version = 0).statusCode(422).body("code", equalTo("illegal_structure"))
        val outsider = seedNode(t.scopeId, FilesNodeKinds.FILE, parentId = t.other, wrappedContentKey = CONTENT_KEY)
        memberPlace(t, outsider, to = folder, version = 0).statusCode(422).body("code", equalTo("illegal_structure"))
        // control: the same create in a plain folder
        memberCreate(t, t.other, FilesNodeKinds.FILE).statusCode(201)

        // A guest of a link ABOVE the drop box (it holds its key) creates nothing in a guest's folder either.
        val above = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        GuestTreeShare.create(above, folder, FilesNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        GuestTreeShare.create(above, t.folder, FilesNodeKinds.FILE).statusCode(201) // control

        // A member moves the guest's folder out: re-wrapped under its new parent, it is no identity's folder any more.
        memberPlace(t, folder, to = t.other, version = 0).statusCode(204)
        assertEquals(t.other to null, tx { NodeEntity.findById(folder)!!.let { it.parentId to it.shareIdentityId } })
        // ...and the guest's next call makes it a new one; its old file went with the moved folder.
        val again = ensure(guest).statusCode(200).body("created", equalTo(true)).extract().path<String>("nodeId")
        assertNotEquals(folder, again)
        guestStart(guest, file).statusCode(403).body("code", equalTo("share_not_contributable"))

        // A member deletes a guest's file (and a folder) — into the trash.
        val newer = guestFile(guest, again)
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/$newer").then().statusCode(204)
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/$again").then().statusCode(204)
        // A trashed identity folder takes nothing; the identity keeps it (a restore needs the identity's key).
        ensure(guest).statusCode(403).body("code", equalTo("share_not_contributable"))
        GuestTreeShare.create(guest, again, FilesNodeKinds.FILE).statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    @Test
    fun revokingTheLinkKeepsTheFoldersTheFilesAndTheIdentitiesThatOpenThem() {
        val t = tree()
        val guest = dropLink(t)
        val folder = ensure(guest).statusCode(200).extract().path<String>("nodeId")
        val file = guestFile(guest, folder)
        val identity = identityOf(guest)
        val blob = tx { NodeAttachmentEntity.find("nodeId", file).firstResult()!!.blobId }
        // a second guest that made its folder and uploaded nothing: only the folder names its identity
        val quiet = secondGuest(guest)
        val quietFolder = ensure(quiet).statusCode(200).extract().path<String>("nodeId")

        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/shares/${guest.shareId}").then().statusCode(204)

        assertNotNull(tx { ShareIdentityEntity.findById(identityOf(quiet)) }, "an identity only its folder names is kept (NodeGuestIdentityUsage)")
        assertEquals(identityOf(quiet), tx { NodeEntity.findById(quietFolder)!!.shareIdentityId })

        ensure(guest).statusCode(404).body("code", equalTo("share_not_found"))
        assertNotNull(tx { ShareIdentityEntity.findById(identity) }, "the identity a folder names is kept")
        assertEquals(identity, tx { NodeEntity.findById(folder)!!.shareIdentityId })
        assertEquals(blob, tx { NodeAttachmentEntity.find("nodeId", file).firstResult()?.blobId }, "the file keeps its blob")
        given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes").then().statusCode(200)
            .body("find { it.id == '$folder' }.wrappedIdentityKey", equalTo(IDENTITY_KEY))
    }

    @Test
    fun thePurgeOfATrashedDropBoxErasesItsFoldersAndThenTheirIdentities() {
        val t = tree()
        val guest = dropLink(t)
        val folder = ensure(guest).statusCode(200).extract().path<String>("nodeId")
        val file = guestFile(guest, folder)
        val identity = identityOf(guest)
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/${t.dropBox}").then().statusCode(204)
        assertNotNull(tx { ShareIdentityEntity.findById(identity) }, "control: the trash keeps the identity (a restore needs it)")

        tx { purge.eraseExpiredIn(t.scopeId, Instant.now().plusSeconds(60)) }

        assertEquals(0L, tx { NodeEntity.count("id in ?1", listOf(t.dropBox, folder, file)) }, "the drop box, the folder and the file are erased")
        assertNull(tx { ShareIdentityEntity.findById(identity) }, "the identity nothing names any more goes with them")
    }

    @Test
    fun onlyAWriteOnlyLinkOnADropBoxGetsAFolder() {
        val t = tree()
        // a WRITE_ONLY link seeded on a plain folder (the API refuses one, `ShareRoots`): Files makes no folder there
        val onFolder = GuestTreeShare.seed(t.userId, t.scopeId, t.other, ShareEntity.WRITE_ONLY)
        ensure(onFolder).statusCode(403).body("code", equalTo("share_not_contributable"))
        val readWrite = GuestTreeShare.seed(t.userId, t.scopeId, t.dropBox, ShareEntity.READ_WRITE)
        ensure(readWrite).statusCode(403).body("code", equalTo("share_not_contributable"))
        // a WRITE_ONLY guest with no folder yet creates nothing
        val fresh = dropLink(t)
        GuestTreeShare.create(fresh, t.dropBox, FilesNodeKinds.FILE).statusCode(403).body("code", equalTo("share_not_contributable"))
        ensure(fresh, epoch = 7).statusCode(409).body("code", equalTo("stale_epoch"))
        ensure(GuestTreeShare.Link(fresh.shareId, Tokens.random())).statusCode(403)
        // control
        ensure(fresh).statusCode(200)

        // The API shares a drop box WRITE_ONLY only.
        share(t.userId, t.dropBox, "READ").statusCode(422).body("code", equalTo("illegal_scope"))
        share(t.userId, t.dropBox, "WRITE_ONLY").statusCode(201)
    }

    // --- the wire ------------------------------------------------------------------------------------------------

    private fun ensure(link: GuestTreeShare.Link, wrap: String = WRAP, epoch: Int = 0): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body(
                """{"contributorToken":"${link.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                    """"slugToken":"${GuestTreeShare.slug()}","epoch":$epoch,"wrappedContentKey":"$wrap"}""",
            )
            .`when`().post("/api/shares/${link.shareId}/files/drop-folder").then()

    /** A guest creates a FILE under [parent] and uploads its blob; returns the file's id. */
    private fun guestFile(link: GuestTreeShare.Link, parent: String): String {
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, parent, FilesNodeKinds.FILE, nodeId = file).statusCode(201)
        val blob = TestBlob.SMALL
        val uploadId = guestStart(link, file, blob).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, tx { UploadSessionEntity.findById(uploadId)!!.storageKey })
        given().contentType(ContentType.JSON).body("""{"contributorToken":"${link.token}","uploadId":"$uploadId","wrappedMeta":"bWV0YQ=="}""")
            .`when`().post("/api/shares/${link.shareId}/files/$file/content").then().statusCode(200)
        return file
    }

    private fun guestStart(link: GuestTreeShare.Link, node: String, blob: TestBlob = TestBlob.SMALL): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","nodeId":"$node","items":[${blob.declare("d3JhcA==", ownerId = node, role = FilesBlobRoles.FILE_CONTENT)}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads").then()

    private fun memberCreate(t: Tree, parent: String, kind: String): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{"parentId":"$parent","nodeId":"${UUID.randomUUID()}","kind":"$kind","encryptedName":"bmFtZQ==","slugToken":"${GuestTreeShare.slug()}","orderIndex":0,"epoch":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes").then()

    private fun memberPlace(t: Tree, node: String, to: String, version: Long): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{"parentId":"$to","encryptedName":"cGxhY2Vk","slugToken":"${GuestTreeShare.slug()}","wrappedContentKey":"cmUtd3JhcA==","epoch":0,"placementVersion":$version}""")
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/$node").then()

    private fun share(userId: String, nodeId: String, role: String): ValidatableResponse {
        val keys = if (role == "WRITE_ONLY") """"sealedDropPublicKey":"ZHJvcA==","sealedDropPrivateKey":"cHJpdg==""""
        else """"wrap":"d3JhcA==""""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",$keys,"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then()
    }

    // --- committed state -----------------------------------------------------------------------------------------

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    /** A second named guest on [link]'s share, as `createShareIdentity` stores a WRITE_ONLY one. */
    private fun secondGuest(link: GuestTreeShare.Link): GuestTreeShare.Link {
        val token = Tokens.random()
        tx {
            ShareIdentityEntity().apply {
                id = UUID.randomUUID().toString(); shareId = link.shareId; sealedDisplayName = "YmVu"
                wrappedIdentityKey = IDENTITY_KEY; tokenHash = Tokens.hash(token); createdAt = Instant.now()
            }.persist()
        }
        return GuestTreeShare.Link(link.shareId, token)
    }

    /** A WRITE_ONLY link on the drop box, its seeded guest given a wrapped `K_up` as `createShareIdentity` stores one. */
    private fun dropLink(t: Tree): GuestTreeShare.Link {
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.dropBox, ShareEntity.WRITE_ONLY)
        tx { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.wrappedIdentityKey = IDENTITY_KEY }
        return link
    }

    private fun identityOf(link: GuestTreeShare.Link): String = tx {
        ShareIdentityEntity.find("shareId = ?1 and tokenHash = ?2", link.shareId, Tokens.hash(link.token)).firstResult()!!.id
    }

    private fun childrenOf(parent: String): List<String> = tx { NodeEntity.find("parentId", parent).list().map { it.id } }
    private fun parentOf(node: String): String = tx { NodeEntity.findById(node)!!.parentId }
    private fun attachmentIdentity(node: String): String? = tx { NodeAttachmentEntity.find("nodeId", node).firstResult()?.shareIdentityId }

    private companion object {
        const val WRAP = "d3JhcHBlZC11bmRlci1rdXA="
        val IDENTITY_KEY: String = Base64.getEncoder().encodeToString("k-up-wrapped-to-the-drop-key".toByteArray())
    }
}
