package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.identity.PrincipalKey
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
import me.raddatz.veyq.depot.DepotNodeKinds
import me.raddatz.veyq.depot.content.DepotBlobRoles
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The drop box (cr-files-application F3, ADR 0080 decision 11), in the application this module's tests boot (core +
 * Depot): a `DEPOT_DROPBOX` is shared `WRITE_ONLY`; each guest identity gets ONE identity folder in it
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
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, folder, dropBox, other)
    }

    @Test
    fun twoGuestsGetTwoFoldersAndASecondUploadOfOneIdentityLandsInTheSameFolder() {
        val t = tree()
        val anna = dropLink(t)
        val ben = secondGuest(anna)

        val annaFolder = ensure(anna).statusCode(200).body("created", equalTo(true)).body("placementRevision", equalTo(0))
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
        GuestTreeShare.create(ben, annaFolder, DepotNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        GuestTreeShare.create(ben, t.dropBox, DepotNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
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
        // and its files through Depot's own listing
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
        memberCreate(t, folder, DepotNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        memberPlace(t, folder, to = t.dropBox, version = 0).statusCode(422).body("code", equalTo("illegal_structure"))
        memberPlace(t, file, to = folder, version = 0).statusCode(422).body("code", equalTo("illegal_structure"))
        val outsider = seedNode(t.scopeId, DepotNodeKinds.FILE, parentId = t.other, wrappedContentKey = CONTENT_KEY)
        memberPlace(t, outsider, to = folder, version = 0).statusCode(422).body("code", equalTo("illegal_structure"))
        // control: the same create in a plain folder
        memberCreate(t, t.other, DepotNodeKinds.FILE).statusCode(201)

        // A guest of a link ABOVE the drop box (it holds its key) creates nothing in a guest's folder either.
        val above = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        GuestTreeShare.create(above, folder, DepotNodeKinds.FILE).statusCode(422).body("code", equalTo("illegal_structure"))
        GuestTreeShare.create(above, t.folder, DepotNodeKinds.FILE).statusCode(201) // control

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
        GuestTreeShare.create(guest, again, DepotNodeKinds.FILE).statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    /**
     * Inbox #377 (a), owner decision 2026-10-08: only a member moves anything out of a drop box. A `READ_WRITE` guest of a
     * link ABOVE the drop box holds its key and sees its guests' folders, yet places neither an identity folder nor
     * anything inside one — `422 illegal_structure` (core's identity-folder rule, `ShareNodesHandler`), nothing written.
     */
    @Test
    fun aGuestOfALinkAboveMovesNothingOutOfADropBox() {
        val t = tree()
        val guest = dropLink(t)
        val folder = ensure(guest).statusCode(200).extract().path<String>("nodeId")
        val file = guestFile(guest, folder)
        val above = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)

        GuestTreeShare.place(above, folder, parentId = t.folder).statusCode(422).body("code", equalTo("illegal_structure"))
        GuestTreeShare.place(above, file, parentId = t.folder).statusCode(422).body("code", equalTo("illegal_structure"))
        assertEquals(t.dropBox to folder, parentOf(folder) to parentOf(file), "nothing moved")
        assertEquals(identityOf(guest), tx { NodeEntity.findById(folder)!!.shareIdentityId }, "the folder keeps its identity")
        assertEquals(0L to 0L, tx { NodeEntity.findById(folder)!!.placementRevision to NodeEntity.findById(file)!!.placementRevision })

        // control: the same guest moves a plain file of the subtree, and a member moves the guest's folder out
        val plain = UUID.randomUUID().toString()
        GuestTreeShare.create(above, t.folder, DepotNodeKinds.FILE, nodeId = plain).statusCode(201)
        val sub = UUID.randomUUID().toString()
        GuestTreeShare.create(above, t.folder, NodeEntity.FOLDER, nodeId = sub).statusCode(201)
        GuestTreeShare.place(above, plain, parentId = sub).statusCode(204)
        memberPlace(t, folder, to = t.other, version = 0).statusCode(204)
    }

    /** A drop box's guest takes back an empty file of its own folder (a refused or given-up upload,
     *  `abandonShareNode`) — never one in another guest's folder, never its folder, never a file it completed; and a
     *  guest of a link above takes back nothing inside a drop box. */
    @Test
    fun aDropBoxGuestTakesBackAnEmptyFileOfItsOwnFolderOnly() {
        val t = tree()
        val anna = dropLink(t)
        val ben = secondGuest(anna)
        val annaFolder = ensure(anna).statusCode(200).extract().path<String>("nodeId")
        val benFolder = ensure(ben).statusCode(200).extract().path<String>("nodeId")
        val annas = UUID.randomUUID().toString()
        GuestTreeShare.create(anna, annaFolder, DepotNodeKinds.FILE, nodeId = annas).statusCode(201)
        val bens = UUID.randomUUID().toString()
        GuestTreeShare.create(ben, benFolder, DepotNodeKinds.FILE, nodeId = bens).statusCode(201)
        val sent = guestFile(anna, annaFolder)
        val above = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)

        GuestTreeShare.abandon(anna, bens).statusCode(404).body("code", equalTo("node_not_found"))
        GuestTreeShare.abandon(anna, annaFolder).statusCode(422).body("code", equalTo("illegal_structure"))
        GuestTreeShare.abandon(anna, sent).statusCode(409).body("code", equalTo("not_a_placeholder"))
        GuestTreeShare.abandon(above, bens).statusCode(422).body("code", equalTo("illegal_structure"))
        assertEquals(setOf(annas, sent), childrenOf(annaFolder).toSet(), "a refusal writes nothing")
        assertEquals(listOf(bens), childrenOf(benFolder))

        // Control: its own empty file goes, the folder and the sent file stay.
        GuestTreeShare.abandon(anna, annas).statusCode(204)
        assertEquals(listOf(sent), childrenOf(annaFolder))
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

    /**
     * Inbox #377 (c), owner decision 2026-10-08: inside its own identity folder a drop-box guest creates `FILE`s only
     * (Depot's `WriteOnlyGuestKinds`) — a sub-folder, a drop box or a kind no application here places (`MEMORY`: this
     * module boots without Memories) is `422 illegal_scope`, decided before the placement rule, and stores nothing.
     */
    @Test
    fun aDropBoxGuestCreatesOnlyFilesInItsFolder() {
        val t = tree()
        val guest = dropLink(t)
        val folder = ensure(guest).statusCode(200).extract().path<String>("nodeId")

        for (kind in listOf(NodeEntity.FOLDER, DepotNodeKinds.DROPBOX, "MEMORY")) {
            GuestTreeShare.create(guest, folder, kind).statusCode(422).body("code", equalTo("illegal_scope"))
        }
        assertEquals(emptyList<String>(), childrenOf(folder), "a refused create stores nothing")
        // control: a FILE in the same folder, and its upload
        val file = guestFile(guest, folder)
        assertEquals(listOf(file), childrenOf(folder))
        assertNotNull(tx { NodeAttachmentEntity.find("nodeId", file).firstResult() })
    }

    @Test
    fun onlyAWriteOnlyLinkOnADropBoxGetsAFolder() {
        val t = tree()
        // a WRITE_ONLY link seeded on a plain folder (the API refuses one, `ShareRoots`): Depot makes no folder there
        val onFolder = GuestTreeShare.seed(t.userId, t.scopeId, t.other, ShareEntity.WRITE_ONLY)
        ensure(onFolder).statusCode(403).body("code", equalTo("share_not_contributable"))
        val readWrite = GuestTreeShare.seed(t.userId, t.scopeId, t.dropBox, ShareEntity.READ_WRITE)
        ensure(readWrite).statusCode(403).body("code", equalTo("share_not_contributable"))
        // a WRITE_ONLY guest with no folder yet creates nothing
        val fresh = dropLink(t)
        GuestTreeShare.create(fresh, t.dropBox, DepotNodeKinds.FILE).statusCode(403).body("code", equalTo("share_not_contributable"))
        ensure(fresh, epoch = 7).statusCode(409).body("code", equalTo("stale_epoch"))
        ensure(GuestTreeShare.Link(fresh.shareId, Tokens.random())).statusCode(403)
        // control
        ensure(fresh).statusCode(200)

        // The API shares a drop box WRITE_ONLY only.
        share(t.userId, t.dropBox, "READ").statusCode(422).body("code", equalTo("illegal_scope"))
        share(t.userId, t.dropBox, "WRITE_ONLY").statusCode(201)
    }

    /** An identity folder is born by core's other create path (`NodesStore.createIdentityFolder`); the members' listing
     *  serves its server-observed `createdAt` like any node's — the order two guests of one name are told apart by. */
    @Test
    fun anIdentityFolderListsTheInstantTheServerMadeIt() {
        val t = tree()
        val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val folder = ensure(dropLink(t)).statusCode(200).body("created", equalTo(true)).extract().path<String>("nodeId")
        val after = Instant.now()
        val listed = OffsetDateTime.parse(
            given().header("Authorization", bearer(t.userId)).`when`().get("/api/scopes/${t.scopeId}/nodes")
                .then().statusCode(200).extract().path<String>("find { it.id == '$folder' }.createdAt"),
        ).toInstant()
        assertEquals(tx { NodeEntity.findById(folder)!!.createdAt }, listed)
        assertTrue(!listed.isBefore(before) && !listed.isAfter(after), "$listed is not within [$before, $after]")
    }

    /** The identity folder's name is sealed by its guest, so its identity is the name's last editor (ADR 0093 § 8,
     *  en.platform.field-editor) — and so of the files it creates inside. Control, the other kind: a member's move out
     *  re-seals the name and takes it over. */
    @Test
    fun anIdentityFolderAndItsFilesNameTheirGuestAsTheNamesEditorUntilAMemberMovesItOut() {
        val t = tree()
        val guest = dropLink(t)
        val folder = ensure(guest).statusCode(200).body("created", equalTo(true)).extract().path<String>("nodeId")
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(guest, folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201)
        val identity = PrincipalKey.Share(identityOf(guest))
        assertEquals(identity, tx { NodeEntity.findById(folder)!!.nameEditor })
        assertEquals(identity, tx { NodeEntity.findById(file)!!.nameEditor })

        memberPlace(t, folder, to = t.other, version = 0).statusCode(204)
        assertEquals(PrincipalKey.User(t.userId), tx { NodeEntity.findById(folder)!!.nameEditor })
        assertEquals(identity, tx { NodeEntity.findById(file)!!.nameEditor }, "the file inside was not renamed")
    }

    // --- the wire ------------------------------------------------------------------------------------------------

    private fun ensure(link: GuestTreeShare.Link, wrap: String = WRAP, epoch: Int = 0): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body(
                """{"contributorToken":"${link.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                    """"slugToken":"${GuestTreeShare.slug()}","scopeEpoch":$epoch,"wrappedContentKey":"$wrap"}""",
            )
            .`when`().post("/api/shares/${link.shareId}/files/drop-folder").then()

    /** A guest creates a FILE under [parent] and uploads its blob; returns the file's id. */
    private fun guestFile(link: GuestTreeShare.Link, parent: String): String {
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, parent, DepotNodeKinds.FILE, nodeId = file).statusCode(201)
        val blob = TestBlob.SMALL
        val uploadId = guestStart(link, file, blob).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, tx { UploadSessionEntity.findById(uploadId)!!.storageKey })
        given().contentType(ContentType.JSON).body("""{"contributorToken":"${link.token}","nodeEpoch":0,"uploadId":"$uploadId"}""")
            .`when`().post("/api/shares/${link.shareId}/files/$file/content").then().statusCode(200)
        return file
    }

    private fun guestStart(link: GuestTreeShare.Link, node: String, blob: TestBlob = TestBlob.SMALL): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","nodeId":"$node","items":[${blob.declare("d3JhcA==", ownerId = node, role = DepotBlobRoles.FILE_CONTENT)}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads").then()

    private fun memberCreate(t: Tree, parent: String, kind: String): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{"parentId":"$parent","parentEpoch":0,"nodeId":"${UUID.randomUUID()}","kind":"$kind","encryptedName":"bmFtZQ==","slugToken":"${GuestTreeShare.slug()}","orderIndex":0,"scopeEpoch":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes").then()

    private fun memberPlace(t: Tree, node: String, to: String, version: Long): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{"parentId":"$to","parentEpoch":0,"encryptedName":"cGxhY2Vk","slugToken":"${GuestTreeShare.slug()}","wrappedContentKey":"cmUtd3JhcA==","epoch":0,"placementRevision":$version}""")
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
                wrappedIdentityKey = IDENTITY_KEY; tokenHash = Tokens.hash(token)
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
