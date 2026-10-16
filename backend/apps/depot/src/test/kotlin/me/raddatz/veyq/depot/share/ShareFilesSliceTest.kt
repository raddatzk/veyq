package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.quota.StorageQuota
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.DepotNodeKinds
import me.raddatz.veyq.depot.content.DepotBlobRoles
import io.quarkus.narayana.jta.QuarkusTransaction
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A folder share with files (cr-files-application F2), in the application this module's tests boot (core + Depot, no
 * Memories): a `READ` guest lists and downloads the files of the shared subtree, a `READ_WRITE` guest creates a `FILE`
 * with core's `createShareNode`, uploads into it through core's guest transport and Depot's `completeShareFile`,
 * replaces it and renames it; a file in the trash answers 404. The links are seeded as rows ([GuestTreeShare]).
 * [FakePresignProfile] makes declare-then-verify and the presigned GETs run. Each refusal beside its control.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class ShareFilesSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var quota: StorageQuota

    /** A member's scope: `folder` (shared) holds `file` (a member's, uploaded); `outside` is a file beside the folder. */
    private data class Tree(val userId: String, val scopeId: String, val tenantId: String, val folder: String, val file: String, val outside: String)

    private fun tree(): Tree {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        memberUpload(userId, scopeId, file)
        memberUpload(userId, scopeId, outside)
        return Tree(userId, scopeId, tenantId, folder, file, outside)
    }

    @Test
    fun aReadGuestListsAndDownloadsTheFilesOfTheSubtreeOnly() {
        val t = tree()
        val sub = seedNode(t.scopeId, NodeEntity.FOLDER, parentId = t.folder, wrappedContentKey = CONTENT_KEY)
        val deep = seedNode(t.scopeId, DepotNodeKinds.FILE, parentId = sub, wrappedContentKey = CONTENT_KEY) // no upload yet
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ)

        val listed = listShare(link).statusCode(200).body("items.size()", equalTo(2)).extract()
        val byNode = (0..1).associate { listed.path<String>("items[$it].nodeId") to it }
        assertEquals(sortedSetOf(t.file, deep), byNode.keys.toSortedSet(), "the subtree's files, nothing outside it")
        assertEquals(blobOf(t.file), listed.path<String>("items[${byNode[t.file]}].fileBlob.blobId"))
        assertNull(listed.path<Any?>("items[${byNode[deep]}].fileBlob"), "a file without an upload lists no blob")

        // core's guest download serves the file's blob; the file outside the subtree is a 404.
        shareBlob(link, blobOf(t.file)!!).statusCode(200).body("presignedGets.size()", equalTo(1))
        shareBlob(link, blobOf(t.outside)!!).statusCode(404)

        // A READ guest writes nothing: neither the start nor the completion.
        guestStart(link, t.file).statusCode(403).body("code", equalTo("share_not_contributable"))
        completeShare(link, t.file, UUID.randomUUID().toString()).statusCode(403).body("code", equalTo("share_not_contributable"))

        // An unknown link is the non-enumerable 404.
        listShare(GuestTreeShare.Link("x".repeat(43), link.token)).statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun aFileInTheTrashAnswers404ToAGuest() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        val blobId = blobOf(t.file)!!
        val upload = guestStartStaged(link, t.file) // started while the file was live
        shareBlob(link, blobId).statusCode(200) // control

        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/${t.file}").then().statusCode(204)

        listShare(link).statusCode(200).body("items.size()", equalTo(0))
        shareBlob(link, blobId).statusCode(404).body("code", equalTo("share_not_found"))
        completeShare(link, t.file, upload, expected = blobId).statusCode(404).body("code", equalTo("node_not_found"))
        guestStart(link, t.file).statusCode(404).body("code", equalTo("node_not_found"))
        assertEquals(blobId, blobOf(t.file), "the trashed file keeps its blob")
    }

    /**
     * Owner decision 2026-10-14: in a folder a contributor creates folders and files (Depot declares `FILE`
     * guest-creatable, core's `GuestCreatableKinds`) and no drop box — `422 illegal_scope`, nothing stored.
     */
    @Test
    fun aContributorCreatesFoldersAndFilesButNoDropBox() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.DROPBOX).statusCode(422).body("code", equalTo("illegal_scope"))
        assertEquals(listOf(t.file), tx { NodeEntity.find("parentId", t.folder).list().map { it.id } }, "a refused create stores nothing")
        // controls: a folder and a file in the same place
        val sub = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, NodeEntity.FOLDER, nodeId = sub).statusCode(201)
        GuestTreeShare.create(link, sub, DepotNodeKinds.FILE).statusCode(201)
    }

    @Test
    fun aContributorCreatesAFileUploadsReplacesAndRenamesIt() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201)

        // The file's metadata is its blob's (inbox #397): declared at the start, served with the blob, and the node's own
        // payload is never written by a completion.
        val first = TestBlob.ofChunks(2)
        val firstBlob = completeShare(link, file, guestStartStaged(link, file, first, meta = "bWV0YS0x")).statusCode(200)
            .body("chunkCount", equalTo(2)).body("byteSize", equalTo(first.byteSize.toInt()))
            .body("wrappedBlobMeta", equalTo("bWV0YS0x"))
            .extract().path<String>("blobId")
        assertEquals(firstBlob, blobOf(file))
        assertEquals(null to 0L, metaOf(file), "the meta lands with the bytes, as the blob's - the node's payload untouched")
        assertEquals(identityOf(link), attachmentIdentity(file), "the attachment names the guest's identity")

        // The member sees the guest's file in the folder; the link's usage counts it (the attachment term, Depot's 0).
        given().header("Authorization", bearer(t.userId)).queryParam("parentId", t.folder)
            .`when`().get("/api/scopes/${t.scopeId}/files").then().statusCode(200)
            .body("items.find { it.nodeId == '$file' }.fileBlob.blobId", equalTo(firstBlob))
        assertEquals(first.byteSize, linkUsage(link))

        // A replace with the read blob swaps it and, being a new blob, its meta; a stale one is refused and leaves both.
        val second = TestBlob.ofChunks(1)
        val secondBlob = completeShare(link, file, guestStartStaged(link, file, second, meta = "bWV0YS0y"), expected = firstBlob)
            .statusCode(200).body("wrappedBlobMeta", equalTo("bWV0YS0y")).extract().path<String>("blobId")
        assertEquals(second.byteSize, linkUsage(link), "only the new blob counts")
        completeShare(link, file, guestStartStaged(link, file, meta = "bWV0YS0z"), expected = firstBlob).statusCode(409)
            .body("code", equalTo("stale_file")).body("currentBlobId", equalTo(secondBlob))
        assertEquals(secondBlob, blobOf(file))
        listShare(link).statusCode(200)
            .body("items.find { it.nodeId == '$file' }.fileBlob.wrappedBlobMeta", equalTo("bWV0YS0y"))
        // A body that still sends the retired `wrappedMeta` writes nothing with it: the completion has no such door.
        completeShare(link, file, guestStartStaged(link, file), expected = secondBlob, legacyMeta = "bWV0YQ==").statusCode(200)
            .body("wrappedBlobMeta", nullValue())
        assertEquals(null to 0L, metaOf(file), "no completion writes the node's payload")

        // The guest renames its file (core's placement; a FOLDER lets a READ_WRITE guest rename any kind).
        GuestTreeShare.place(link, file, t.folder, placementRevision = 0).statusCode(204)
        // A member's file is the guest's to replace too (the role applies to every node below the root).
        completeShare(link, t.file, guestStartStaged(link, t.file), expected = blobOf(t.file)).statusCode(200)
    }

    /**
     * ADR 0083 Decision 8, inbox #190, #431: a guest's upload records its identity and no member; the member's listing names
     * it as `share:<identity>` with that identity's sealed name and share, a member's replace moves it back to `user:<id>`
     * (and drops the name) — and the guest's own listing carries no uploader and no name at all. Read RAW (a parsed shape
     * would drop a field the contract does not name), with the member's listing of the same file as the positive control.
     */
    @Test
    fun aGuestsUploadIsAttributedToItsIdentityForMembersAndTheGuestListingNamesNoUploader() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        val identity = identityOf(link)
        val sealedName = nameIdentity(identity)

        val guestBlob = completeShare(link, t.file, guestStartStaged(link, t.file), expected = blobOf(t.file)).statusCode(200)
            .extract().path<String>("blobId")
        assertEquals(null to identity, uploaderOf(t.file), "a guest's upload records the identity and no member")
        memberList(t).body("items.find { it.nodeId == '${t.file}' }.uploaderKey", equalTo("share:$identity"))
            .body("items.find { it.nodeId == '${t.file}' }.uploaderName.sealedName", equalTo(sealedName))
            .body("items.find { it.nodeId == '${t.file}' }.uploaderName.shareId", equalTo(link.shareId))

        // The guest's listing: the same file, no uploader field, neither id nor the sealed name anywhere in the body.
        val raw = listShare(link).statusCode(200)
            .body("items.find { it.nodeId == '${t.file}' }.fileBlob.blobId", equalTo(guestBlob)) // control: the file is listed
            .extract().asString()
        assertFalse(raw.contains("uploader"), "the guest's listing carries no uploader field: $raw")
        assertFalse(raw.contains(identity), "nor the identity id")
        assertFalse(raw.contains(sealedName), "nor the guest's sealed name: $raw")
        assertEquals(setOf("nodeId", "fileBlob"), ObjectMapper().readTree(raw)["items"][0].fieldNames().asSequence().toSet())

        // A member's replace moves it back to the member, whose id never reaches the guest either.
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"${memberStart(t.userId, t.scopeId, t.file)}","expectedBlobId":"$guestBlob"}""")
            .`when`().post("/api/scopes/${t.scopeId}/files/${t.file}/content").then().statusCode(200)
        assertEquals(t.userId to null, uploaderOf(t.file), "the member's replace clears the identity")
        memberList(t).body("items.find { it.nodeId == '${t.file}' }.uploaderKey", equalTo("user:${t.userId}"))
            .body("items.find { it.nodeId == '${t.file}' }.uploaderName", nullValue())
        assertFalse(listShare(link).statusCode(200).extract().asString().contains(t.userId), "the member's id never reaches the guest")
    }

    /** Give [identity] a sealed name of its own (a stand-in ciphertext no other field of the test carries); returns it. */
    private fun nameIdentity(identity: String): String {
        val sealed = "c2VhbGVkLWd1ZXN0LW5hbWU="
        tx { ShareIdentityEntity.findById(identity)!!.sealedDisplayName = sealed }
        return sealed
    }

    private fun memberList(t: Tree): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).queryParam("parentId", t.folder)
            .`when`().get("/api/scopes/${t.scopeId}/files").then().statusCode(200)

    /** The attachment's recorded uploader: (member user id, guest identity id). */
    private fun uploaderOf(node: String): Pair<String?, String?> = tx {
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, DepotBlobRoles.FILE_CONTENT).firstResult()!!
            .let { it.uploaderUserId to it.shareIdentityId }
    }

    /**
     * A blob's sealed metadata on the guest path: a contributor's declared `wrappedBlobMeta` rides the guest start and is
     * served back unchanged on the guest completion and the share listing; a member's file's meta is listed to the
     * guest too; absent stays null; over the cap the guest start is 400, at the cap 200.
     */
    @Test
    fun aGuestsAndAMembersBlobMetaAreServedToTheGuestUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val membersFile = seedNode(scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"${memberStart(userId, scopeId, membersFile, meta = MEMBER_META)}"}""")
            .`when`().post("/api/scopes/$scopeId/files/$membersFile/content").then().statusCode(200)
        val link = GuestTreeShare.seed(userId, scopeId, folder, ShareEntity.READ_WRITE)
        val guestsFile = UUID.randomUUID().toString()
        GuestTreeShare.create(link, folder, DepotNodeKinds.FILE, nodeId = guestsFile).statusCode(201)
        val bare = UUID.randomUUID().toString()
        GuestTreeShare.create(link, folder, DepotNodeKinds.FILE, nodeId = bare).statusCode(201)

        completeShare(link, guestsFile, guestStartStaged(link, guestsFile, meta = GUEST_META)).statusCode(200)
            .body("wrappedBlobMeta", equalTo(GUEST_META))
        completeShare(link, bare, guestStartStaged(link, bare)).statusCode(200).body("wrappedBlobMeta", nullValue())

        listShare(link).statusCode(200)
            .body("items.find { it.nodeId == '$membersFile' }.fileBlob.wrappedBlobMeta", equalTo(MEMBER_META))
            .body("items.find { it.nodeId == '$guestsFile' }.fileBlob.wrappedBlobMeta", equalTo(GUEST_META))
            .body("items.find { it.nodeId == '$bare' }.fileBlob.wrappedBlobMeta", nullValue())

        guestStart(link, bare, meta = "A".repeat(2049)).statusCode(400)
        guestStart(link, bare, meta = "A".repeat(2048)).statusCode(200).body("results[0].error", nullValue()) // control
    }

    @Test
    fun aGuestUploadsOnlyIntoAFileOfItsSubtreeInTheFileRole() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)

        // The folder (the root, or named) takes no guest upload: no application writes into a FOLDER.
        guestStart(link, null).statusCode(403).body("code", equalTo("share_not_contributable"))
        guestStart(link, t.folder).statusCode(403).body("code", equalTo("share_not_contributable"))
        completeShare(link, t.folder, guestStartStaged(link, t.file)).statusCode(403).body("code", equalTo("share_not_contributable"))
        // A file outside the subtree is no node of the link.
        guestStart(link, t.outside).statusCode(404).body("code", equalTo("node_not_found"))
        completeShare(link, t.outside, UUID.randomUUID().toString()).statusCode(404).body("code", equalTo("node_not_found"))
        // Only FILE_CONTENT is a guest's role into a file.
        guestStart(link, t.file, role = "ORIGINAL").statusCode(400).body("code", equalTo("invalid_request"))
        // A session started for another file does not open here; an unknown one is not found.
        val sibling = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.FILE, nodeId = sibling).statusCode(201)
        completeShare(link, sibling, guestStartStaged(link, t.file)).statusCode(422).body("code", equalTo("upload_mismatch"))
        completeShare(link, sibling, UUID.randomUUID().toString()).statusCode(404).body("code", equalTo("upload_not_found"))
        // A wrong token writes nothing.
        completeShare(GuestTreeShare.Link(link.shareId, "y".repeat(43)), sibling, UUID.randomUUID().toString()).statusCode(403)
            .body("code", equalTo("share_not_contributable"))
        // A member's session is not the guest's.
        val memberUpload = memberStart(t.userId, t.scopeId, sibling)
        completeShare(link, sibling, memberUpload).statusCode(404).body("code", equalTo("upload_not_found"))
        // control: the guest's own session for the sibling lands
        completeShare(link, sibling, guestStartStaged(link, sibling)).statusCode(200)
    }

    @Test
    fun aGuestsFilesCountAgainstTheLinkLimitToTheByte() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201)
        val held = TestBlob.ofChunks(1)
        completeShare(link, file, guestStartStaged(link, file, held)).statusCode(200)
        val next = TestBlob.SMALL

        // One byte short of held + next: the next start is refused whole, no session opened.
        setLimit(t.tenantId, held.byteSize + next.byteSize - 1)
        guestStart(link, t.file, next).statusCode(200).body("results[0].error", equalTo("quota_exceeded"))
        // Exactly held + next: admitted.
        setLimit(t.tenantId, held.byteSize + next.byteSize)
        guestStart(link, t.file, next).statusCode(200).body("results[0].error", nullValue())
        // The member's own files under the folder count nothing against the link.
        assertEquals(held.byteSize + next.byteSize, linkUsage(link))
    }

    /**
     * The Storage page's promise (site `space-owners/storage`): a full link takes no more uploads, and nothing of the
     * refused one is kept. A guest's create made the file before its start was refused (the limit is checked at the
     * start, against the bytes), so the guest takes the file back (`abandonShareNode`) and it is erased at once.
     */
    @Test
    fun aFileWhoseUploadTheFullLinkRefusedIsTakenBackAndNothingOfItIsKept() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        setLimit(t.tenantId, 1)
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201)
        guestStart(link, file).statusCode(200).body("results[0].error", equalTo("quota_exceeded"))

        GuestTreeShare.abandon(link, file).statusCode(204)

        assertNull(tx { NodeEntity.findById(file) }, "no node is left behind, not even in the trash")
        listShare(link).statusCode(200).body("items.size()", equalTo(1)).body("items[0].nodeId", equalTo(t.file))
        assertEquals(0L, linkUsage(link) ?: 0L, "and nothing counts against the link")
    }

    /**
     * The same promise for a folder-tree upload (web `uploadFolder.ts`): the guest's folders were created before any file
     * of it was sent, so once the full link refused every file, the guest takes back the files and then the folders,
     * deepest first — core's own empty `FOLDER` is a placeholder (`FolderGuestPlaceholders`). A folder that still holds
     * something is not.
     */
    @Test
    fun aFolderTreeWhoseEveryFileTheFullLinkRefusedIsTakenBackDeepestFirst() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        setLimit(t.tenantId, 1)
        val top = UUID.randomUUID().toString()
        val sub = UUID.randomUUID().toString()
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, NodeEntity.FOLDER, nodeId = top).statusCode(201)
        GuestTreeShare.create(link, top, NodeEntity.FOLDER, nodeId = sub).statusCode(201)
        GuestTreeShare.create(link, sub, DepotNodeKinds.FILE, nodeId = file).statusCode(201)
        guestStart(link, file).statusCode(200).body("results[0].error", equalTo("quota_exceeded"))

        // A folder above what is still there holds it: the order is the client's, deepest first.
        GuestTreeShare.abandon(link, sub).statusCode(409).body("code", equalTo("not_a_placeholder"))
        GuestTreeShare.abandon(link, file).statusCode(204)
        GuestTreeShare.abandon(link, top).statusCode(409).body("code", equalTo("not_a_placeholder"))
        GuestTreeShare.abandon(link, sub).statusCode(204)
        GuestTreeShare.abandon(link, top).statusCode(204)

        for (id in listOf(top, sub, file)) assertNull(tx { NodeEntity.findById(id) }, "no node is left behind, not even in the trash")
        assertEquals(0L, linkUsage(link) ?: 0L, "and nothing counts against the link")
    }

    /** Only a `FILE` whose slot never held a blob, and that nobody else is uploading into, is a placeholder (Depot's
     *  `DepotGuestPlaceholders`); nodes store no authorship, so an empty file a member made is one too. */
    @Test
    fun onlyAnEmptyFileNobodyElseIsUploadingIntoIsTakenBack() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        // A file that holds a blob — a member's, or the guest's own once its upload completed — is never one.
        GuestTreeShare.abandon(link, t.file).statusCode(409).body("code", equalTo("not_a_placeholder"))
        val sent = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.FILE, nodeId = sent).statusCode(201)
        completeShare(link, sent, guestStartStaged(link, sent)).statusCode(200)
        GuestTreeShare.abandon(link, sent).statusCode(409).body("code", equalTo("not_a_placeholder"))
        // An empty file a member is uploading into is not cut off.
        val uploading = seedNode(t.scopeId, DepotNodeKinds.FILE, parentId = t.folder, wrappedContentKey = CONTENT_KEY)
        memberStart(t.userId, t.scopeId, uploading)
        GuestTreeShare.abandon(link, uploading).statusCode(409).body("code", equalTo("not_a_placeholder"))
        // A drop box is the owner's link-bearing node, never a placeholder.
        val dropBox = seedNode(t.scopeId, DepotNodeKinds.DROPBOX, parentId = t.folder, wrappedContentKey = CONTENT_KEY)
        GuestTreeShare.abandon(link, dropBox).statusCode(409).body("code", equalTo("not_a_placeholder"))
        for (id in listOf(t.file, sent, uploading, dropBox)) assertNotNull(tx { NodeEntity.findById(id) }, "a refusal writes nothing")
        assertNotNull(blobOf(t.file)); assertNotNull(blobOf(sent))

        // Control: an empty file nobody is uploading into goes.
        val bare = seedNode(t.scopeId, DepotNodeKinds.FILE, parentId = t.folder, wrappedContentKey = CONTENT_KEY)
        GuestTreeShare.abandon(link, bare).statusCode(204)
        assertNull(tx { NodeEntity.findById(bare) })
    }

    @Test
    fun aDeletedLinkKeepsItsGuestsIdentityWhileItsFileNamesIt() {
        val t = tree()
        val link = GuestTreeShare.seed(t.userId, t.scopeId, t.folder, ShareEntity.READ_WRITE)
        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, t.folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201)
        completeShare(link, file, guestStartStaged(link, file)).statusCode(200)
        val identity = identityOf(link)
        val sealedName = nameIdentity(identity)

        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/shares/${link.shareId}").then().statusCode(204)
        assertNotNull(tx { ShareIdentityEntity.findById(identity) }, "core's attachment usage keeps the identity")
        assertEquals(identity, attachmentIdentity(file))
        // The tombstoned link serves nothing.
        listShare(link).statusCode(404)
        // ...and its guest stays named for a member (inbox #431): the same sealed name, bound to the dead link's id.
        memberList(t).body("items.find { it.nodeId == '$file' }.uploaderKey", equalTo("share:$identity"))
            .body("items.find { it.nodeId == '$file' }.uploaderName.sealedName", equalTo(sealedName))
            .body("items.find { it.nodeId == '$file' }.uploaderName.shareId", equalTo(link.shareId))
    }

    // --- the wire ------------------------------------------------------------------------------------------------

    private companion object {
        /** Opaque stand-ins for two clients' `blob-meta` seals. */
        const val MEMBER_META = "bWVtYmVyLWJsb2ItbWV0YQ=="
        const val GUEST_META = "Z3Vlc3QtYmxvYi1tZXRh"
    }

    private fun memberStart(userId: String, scopeId: String, node: String, blob: TestBlob = TestBlob.SMALL, meta: String? = null): String {
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==", ownerId = node, role = DepotBlobRoles.FILE_CONTENT, meta = meta)}]}""")
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

    private fun guestStart(
        link: GuestTreeShare.Link, node: String?, blob: TestBlob = TestBlob.SMALL, role: String = DepotBlobRoles.FILE_CONTENT,
        meta: String? = null,
    ): ValidatableResponse {
        val target = node?.let { ""","nodeId":"$it"""" } ?: ""
        val item = blob.declare("d3JhcA==", ownerId = node ?: UUID.randomUUID().toString(), role = role, meta = meta)
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}"$target,"items":[$item]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads").then()
    }

    private fun guestStartStaged(link: GuestTreeShare.Link, node: String, blob: TestBlob = TestBlob.SMALL, meta: String? = null): String {
        val uploadId = guestStart(link, node, blob, meta = meta).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, sessionKey(uploadId))
        return uploadId
    }

    private fun completeShare(
        link: GuestTreeShare.Link, node: String, uploadId: String, expected: String? = null, legacyMeta: String? = null,
    ): ValidatableResponse {
        // `legacyMeta`: the field the contract dropped (inbox #397), sent anyway to prove nothing reads it.
        val extra = (expected?.let { ""","expectedBlobId":"$it"""" } ?: "") + (legacyMeta?.let { ""","wrappedMeta":"$it"""" } ?: "")
        return given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","uploadId":"$uploadId"$extra}""")
            .`when`().post("/api/shares/${link.shareId}/files/$node/content").then()
    }

    private fun listShare(link: GuestTreeShare.Link): ValidatableResponse =
        given().`when`().get("/api/shares/${link.shareId}/files").then()

    private fun shareBlob(link: GuestTreeShare.Link, blobId: String): ValidatableResponse =
        given().`when`().get("/api/shares/${link.shareId}/blobs/$blobId").then()

    // --- committed state -----------------------------------------------------------------------------------------

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)

    private fun blobOf(node: String): String? = tx {
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, DepotBlobRoles.FILE_CONTENT).firstResult()?.blobId
    }

    private fun attachmentIdentity(node: String): String? = tx {
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, DepotBlobRoles.FILE_CONTENT).firstResult()?.shareIdentityId
    }

    private fun metaOf(node: String): Pair<String?, Long> = tx { NodeEntity.findById(node)!!.let { it.wrappedMeta to it.metaRevision } }

    private fun identityOf(link: GuestTreeShare.Link): String = tx { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }

    private fun sessionKey(uploadId: String): String = tx { UploadSessionEntity.findById(uploadId)!!.storageKey }

    private fun linkUsage(link: GuestTreeShare.Link): Long? = tx { quota.linkUsage(link.shareId, DepotNodeKinds.FILE) }

    private fun setLimit(tenantId: String, bytes: Long) = tx { TenantEntity.findById(tenantId)!!.shareLinkLimitBytes = bytes }
}
