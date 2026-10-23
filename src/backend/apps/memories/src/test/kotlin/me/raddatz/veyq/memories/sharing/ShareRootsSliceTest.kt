package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.core.sharing.ShareRoots
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharingHandler

/**
 * Share roots by kind, with Memories present (ADR 0080 decisions 6, 11, 13): which roles a node of a kind may be shared
 * with is its owner's answer through [ShareRoots] — core's `FOLDER` READ and READ_WRITE, Memories' `MEMORY` and
 * `CHAPTER` all three (a chapter link since cr-node-key-hierarchy 5a.4; it was refused before), a kind nobody declares
 * none — and it holds on create and on a role change alike. Whether a guest may then upload under
 * the root is a separate seam ([ShareGuestWrites]): a `READ_WRITE` folder share exists, and its guest's upload falls
 * closed. Each refusal sits beside a control through the same path on the kind that admits it.
 * [FakePresignProfile]: the control upload is answered with presigned URLs, which the in-memory store cannot make.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class ShareRootsSliceTest {

    /** A chapter is a share root with every role, as the Memory above it (ADR 0080 decision 13; until 5a.4 every
     *  role here was `illegal_scope`). A kind nobody declares stays refused with the same body. */
    @Test
    fun aChapterIsAShareRootForEveryRole() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        for (role in listOf("READ", "READ_WRITE", "WRITE_ONLY")) {
            create(userId, chapter, role).statusCode(201)
        }
        // control: a node of a kind no owner declares is refused every role
        val stray = seedNode(scopeId, "NOT_A_KIND", wrappedContentKey = CONTENT_KEY)
        for (role in listOf("READ", "READ_WRITE", "WRITE_ONLY")) {
            create(userId, stray, role).statusCode(422).body("code", equalTo("illegal_scope"))
        }
    }

    @Test
    fun aFolderIsNoDropBox() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        create(userId, folder, "WRITE_ONLY").statusCode(422).body("code", equalTo("illegal_scope"))
        // controls: the folder takes the other two roles; a Memory beside it takes WRITE_ONLY with the same body shape
        create(userId, folder, "READ").statusCode(201)
        create(userId, folder, "READ_WRITE").statusCode(201)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        create(userId, memory, "WRITE_ONLY").statusCode(201)
    }

    /** A role change asks [ShareRoots] for the new role as a create does. A share on a kind nobody declares cannot be
     *  created, so the row is seeded (as one minted before the rule); changing its role is refused, and the same change
     *  on a Memory's share passes. */
    @Test
    fun aRoleChangeToARoleTheKindDoesNotAllowIsRefused() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val stray = seedNode(scopeId, "NOT_A_KIND", wrappedContentKey = CONTENT_KEY)
        val strayShare = TestIds.shareId()
        QuarkusTransaction.requiringNew().run {
            ShareEntity().apply {
                id = strayShare; ownerAccount = userId; this.scopeId = scopeId; nodeId = stray; role = "READ"
                wrap = "d3JhcA=="; sealedName = "c2VhbGVk"; createdAt = Instant.now()
                writeVerifier = ShareWriteProof.verifier(strayShare)
            }.persist()
        }
        changeRole(userId, strayShare, "READ_WRITE").statusCode(422).body("code", equalTo("invalid_role_change"))
        QuarkusTransaction.requiringNew().run { assertRole(strayShare, "READ") }

        // control: the same change on a Memory's share
        val memoryShare: String = create(userId, memory, "READ").statusCode(201).extract().path("shareId")
        changeRole(userId, memoryShare, "READ_WRITE").statusCode(204)
        QuarkusTransaction.requiringNew().run { assertRole(memoryShare, "READ_WRITE") }
    }

    /**
     * A chapter link changes role as a Memory's does: READ and READ_WRITE flip in place both ways; a change to
     * WRITE_ONLY, or of a WRITE_ONLY link, is `invalid_role_change` (cr-0119), the role unchanged.
     */
    @Test
    fun aChapterLinkChangesRoleAsAMemorysDoes() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val viewer: String = create(userId, chapter, "READ").statusCode(201).extract().path("shareId")
        changeRole(userId, viewer, "READ_WRITE").statusCode(204)
        QuarkusTransaction.requiringNew().run { assertRole(viewer, "READ_WRITE") }
        changeRole(userId, viewer, "READ").statusCode(204)
        QuarkusTransaction.requiringNew().run { assertRole(viewer, "READ") }
        changeRole(userId, viewer, "WRITE_ONLY").statusCode(422).body("code", equalTo("invalid_role_change"))
        QuarkusTransaction.requiringNew().run { assertRole(viewer, "READ") }

        val uploader: String = create(userId, chapter, "WRITE_ONLY").statusCode(201).extract().path("shareId")
        changeRole(userId, uploader, "READ_WRITE").statusCode(422).body("code", equalTo("invalid_role_change"))
        QuarkusTransaction.requiringNew().run { assertRole(uploader, "WRITE_ONLY") }
    }

    /**
     * A `READ_WRITE` folder share is created, and its guest names itself — but no application writes into a folder, so
     * a start and a window naming no node (the target is the folder root itself) both fail closed
     * (`SharingHandler.writesFor`: no [ShareGuestWrites] for the kind; the start is also refused by the missing
     * admission). Its uploads into a Memory or chapter below the folder are `SubtreeUploadSliceTest`'s (5a.5). Control:
     * the same calls on a Memory's contributor link.
     */
    @Test
    fun aGuestOfAReadWriteFolderShareUploadsNothing() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val folderShare: String = create(userId, folder, "READ_WRITE").statusCode(201).extract().path("shareId")
        val folderGuest = identity(folderShare)
        start(folderShare, folderGuest).statusCode(403).body("code", equalTo("share_not_contributable"))
        window(folderShare, folderGuest, TestIds.uuid()).statusCode(403).body("code", equalTo("share_not_contributable"))

        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val memoryShare: String = create(userId, memory, "READ_WRITE").statusCode(201).extract().path("shareId")
        val memoryGuest = identity(memoryShare)
        val uploadId: String = start(memoryShare, memoryGuest).statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].uploadId")
        window(memoryShare, memoryGuest, uploadId).statusCode(200)
    }

    // --- helpers ---

    private fun create(userId: String, nodeId: String, role: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId)},"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then()

    private fun changeRole(userId: String, shareId: String, role: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"role":"$role"}""")
            .`when`().patch("/api/shares/$shareId")
            .then()

    private fun assertRole(shareId: String, role: String) =
        org.junit.jupiter.api.Assertions.assertEquals(role, ShareEntity.findById(shareId)!!.role)

    private fun identity(shareId: String): String =
        given().contentType(ContentType.JSON).body("""{"nodeEpoch":0,"identityId":"${TestIds.uuid()}","sealedDisplayName":"bmFtZQ=="}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path("contributorToken")

    private fun start(shareId: String, contributorToken: String) =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("d3JhcA==")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then()

    private fun window(shareId: String, contributorToken: String, uploadId: String) =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"uploadId":"$uploadId","fromChunk":0}]}""")
            .`when`().post("/api/shares/$shareId/blob-upload-windows")
            .then()
}
