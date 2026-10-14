package me.raddatz.veyq.files.content

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.TenantRoots
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.quota.StorageUsage
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.GRANT
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.files.FilesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A member's file, end to end through the wire the web will use (cr-files-application F1a), in the application this
 * module's tests boot (core + Files, no Memories): core's `startBlobUploads` (role `FILE_CONTENT`, owner = the node),
 * the chunks staged as an honest client leaves them, Files' `completeFile`, `listFiles`, core's presigned download,
 * core's trash and purge. [FakePresignProfile] makes declare-then-verify run (`canPresign = true`, scripted `stat`), so
 * a completion here passes the same budget check a real store does.
 *
 * Each refusal sits beside its positive control through the same route. Every check of stored state reads what is
 * committed, in a fresh transaction.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class FilesContentSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore
    @Inject lateinit var sessions: BlobUploadStore
    @Inject lateinit var usage: StorageUsage
    @Inject lateinit var subtreePurge: NodeSubtreePurge

    @Test
    fun aFileIsUploadedListedAndItsBlobPresignedForTheMemberOnly() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(scopeId, FilesNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val empty = seedNode(scopeId, FilesNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val blob = TestBlob.ofChunks(2)

        val upload = start(userId, scopeId, file, blob, wrap = "ZmlsZS13cmFw")
        val blobId = complete(userId, scopeId, file, upload).statusCode(200)
            .body("chunkCount", equalTo(2)).body("byteSize", equalTo(blob.byteSize.toInt()))
            .body("wrappedBlobKey", equalTo("ZmlsZS13cmFw")).body("manifest.size()", equalTo(2))
            .extract().path<String>("blobId")
        assertEquals(blobId, attachedBlob(file), "the upload is the file's FILE_CONTENT slot")

        // The listing pairs each live file of the folder with its blob, the empty one with null, in node-id order.
        val listed = list(userId, scopeId, folder).statusCode(200).body("items.size()", equalTo(2)).extract()
        val byNode = (0..1).associate { listed.path<String>("items[$it].nodeId") to it }
        assertEquals(sortedSetOf(file, empty), byNode.keys.toSortedSet())
        assertEquals(listOf(file, empty).sorted(), (0..1).map { listed.path<String>("items[$it].nodeId") })
        assertEquals(blobId, listed.path<String>("items[${byNode[file]}].fileBlob.blobId"))
        assertEquals("ZmlsZS13cmFw", listed.path<String>("items[${byNode[file]}].fileBlob.wrappedBlobKey"))
        assertNull(listed.path<Any?>("items[${byNode[empty]}].fileBlob"), "a file without an upload lists no blob")
        // The root holds none of them; a file at the root is listed without a parent.
        list(userId, scopeId, null).statusCode(200).body("items.size()", equalTo(0))
        val atRoot = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        list(userId, scopeId, null).statusCode(200).body("items.size()", equalTo(1)).body("items[0].nodeId", equalTo(atRoot))

        // The tenant's usage counts the blob through core's path, unchanged.
        assertEquals(blob.byteSize, QuarkusTransaction.requiringNew().call { usage.tenantUsage(tenantId) })

        // The member presigns the chunks through core's download endpoint; a stranger is told nothing.
        download(userId, scopeId, blobId).statusCode(200)
            .body("results[0].error", nullValue()).body("results[0].presignedGets.size()", equalTo(2))
        val stranger = seedUser()
        download(stranger, scopeId, blobId).statusCode(404).body("code", equalTo("scope_not_found"))
        list(stranger, scopeId, folder).statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun aReplaceWithTheReadBlobSwapsItAndFreesTheOldOne() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val first = TestBlob.ofChunks(1)
        val second = TestBlob.ofChunks(3)
        val oldBlob = complete(userId, scopeId, file, start(userId, scopeId, file, first)).statusCode(200).extract().path<String>("blobId")
        val oldKey = storageKeyOf(oldBlob)

        val newBlob = complete(userId, scopeId, file, start(userId, scopeId, file, second), expected = oldBlob).statusCode(200)
            .body("chunkCount", equalTo(3)).extract().path<String>("blobId")

        assertEquals(newBlob, attachedBlob(file))
        QuarkusTransaction.requiringNew().run {
            assertNull(BlobEntity.findById(oldBlob), "the replaced blob's row is gone")
            assertEquals(1L, StoreReclaimEntity.count("storageKey", oldKey), "and its objects are on the reclaim list")
        }
        assertEquals(second.byteSize, QuarkusTransaction.requiringNew().call { usage.tenantUsage(tenantId) }, "only the new blob counts")
        list(userId, scopeId, null).body("items[0].fileBlob.blobId", equalTo(newBlob))
    }

    /**
     * ADR 0083 Addendum: the member's listing names who uploaded each file's CURRENT content — the attachment records the
     * upload's opener, and a replace by another member moves it to the replacer. A file without content names nobody.
     */
    @Test
    fun theListingNamesTheUploaderOfTheCurrentContentAndAReplaceMovesIt() {
        val (owner, scopeId, tenantId) = seedMemberScope(ScopeEntity.GROUP)
        val peer = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = peer; this.tenantId = tenantId; this.scopeId = scopeId
                role = MembershipEntity.MEMBER; wrappedGrant = GRANT; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(peer, tenantId)
        }
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val empty = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)

        val first = complete(owner, scopeId, file, start(owner, scopeId, file, TestBlob.SMALL)).statusCode(200).extract().path<String>("blobId")
        assertEquals(owner to null, uploaderOf(file), "the member's upload records the member, no guest")
        list(peer, scopeId, null).statusCode(200)
            .body("items.find { it.nodeId == '$file' }.uploaderKey", equalTo("user:$owner"))
            .body("items.find { it.nodeId == '$empty' }.uploaderKey", nullValue())

        complete(peer, scopeId, file, start(peer, scopeId, file, TestBlob.SMALL), expected = first).statusCode(200)
        assertEquals(peer to null, uploaderOf(file), "the replacer is the uploader now")
        list(owner, scopeId, null).statusCode(200).body("items.find { it.nodeId == '$file' }.uploaderKey", equalTo("user:$peer"))
    }

    @Test
    fun aStaleExpectedBlobIsRefusedWithTheCurrentOneAndTheUploadIsFreed() {
        val (userId, scopeId) = seedMemberScope()
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val current = complete(userId, scopeId, file, start(userId, scopeId, file, TestBlob.SMALL)).statusCode(200).extract().path<String>("blobId")

        // A replace built on a blob the slot no longer holds (a peer replaced it).
        val lost = start(userId, scopeId, file, TestBlob.SMALL)
        val lostKey = sessionKey(lost)
        complete(userId, scopeId, file, lost, expected = UUID.randomUUID().toString()).statusCode(409)
            .body("code", equalTo("stale_file")).body("currentBlobId", equalTo(current))
        // A first upload into a filled slot is the same conflict.
        complete(userId, scopeId, file, start(userId, scopeId, file, TestBlob.SMALL)).statusCode(409)
            .body("code", equalTo("stale_file")).body("currentBlobId", equalTo(current))

        assertEquals(current, attachedBlob(file), "the slot is unchanged")
        QuarkusTransaction.requiringNew().run {
            assertNull(UploadSessionEntity.findById(lost), "the loser's upload is spent")
            assertEquals(1L, StoreReclaimEntity.count("storageKey", lostKey), "and its objects reclaimed")
        }
        // control: the right expected blob replaces through the same route
        complete(userId, scopeId, file, start(userId, scopeId, file, TestBlob.SMALL), expected = current).statusCode(200)
    }

    @Test
    fun onlyALiveFileOfTheScopeTakesAnUpload() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        for (target in listOf(folder, memory)) {
            // The upload is even started for that node in the file role: the kind alone refuses it.
            complete(userId, scopeId, target, start(userId, scopeId, target, TestBlob.SMALL)).statusCode(422)
                .body("code", equalTo("illegal_target"))
            assertNull(attachedBlob(target))
        }

        val trashed = seedNode(scopeId, FilesNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val upload = start(userId, scopeId, trashed, TestBlob.SMALL)
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$folder").then().statusCode(204)
        complete(userId, scopeId, trashed, upload).statusCode(404).body("code", equalTo("node_not_found"))
        list(userId, scopeId, folder).statusCode(404).body("code", equalTo("node_not_found"))

        // Another scope's file, addressed through this scope.
        val other = seedMemberScope(userId = userId)
        val foreign = seedNode(other.scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        complete(userId, scopeId, foreign, start(userId, scopeId, foreign, TestBlob.SMALL)).statusCode(404)
            .body("code", equalTo("node_not_found"))

        // A session for another node, or in another role, would not open here.
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        complete(userId, scopeId, file, start(userId, scopeId, folder, TestBlob.SMALL)).statusCode(422).body("code", equalTo("upload_mismatch"))
        // Another application's role is not started here at all (inbox #366: Files alone declares only its own) …
        startRequest(userId, scopeId, file, TestBlob.SMALL, role = "ORIGINAL").statusCode(400).body("code", equalTo("invalid_request"))
        // … and a session in it — which the composed application starts, for Memories — does not open as a file's blob.
        complete(userId, scopeId, file, openPastTheStart(userId, scopeId, file, TestBlob.SMALL, role = "ORIGINAL")).statusCode(422)
            .body("code", equalTo("upload_mismatch"))
        complete(userId, scopeId, file, UUID.randomUUID().toString()).statusCode(404).body("code", equalTo("upload_not_found"))
        // control: the right session lands
        complete(userId, scopeId, file, start(userId, scopeId, file, TestBlob.SMALL)).statusCode(200)
    }

    /**
     * Files' own generated resource answers a malformed body as core's does (inbox #339, #355): core's boundary mappers
     * reach an application's `…Api` with no code of Files' own. A missing `uploadId` (the constructor's null check), a
     * non-uuid one (Jackson's type refusal), unparseable JSON — each the contract's `400 invalid_request`, the value
     * not echoed; the control is the same route with a well-formed body, refused by the handler instead.
     */
    @Test
    fun aMalformedCompletionIsTheContractsInvalidRequest() {
        val (userId, scopeId) = seedMemberScope()
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        fun post(body: String) = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(body).`when`().post("/api/scopes/$scopeId/files/$file/content").then()

        for (body in listOf("{}", """{"uploadId":"c2VjcmV0LWNpcGhlcnRleHQ"}""", """{"uploadId":""")) {
            val refused = post(body).statusCode(400).contentType(ContentType.JSON)
                .body("code", equalTo("invalid_request")).body("message", nullValue()).extract().asString()
            assertFalse(refused.contains("c2VjcmV0") || refused.contains("uploadId"), "nothing echoed: $refused")
        }
        // control: a well-formed body reaches the handler, which finds no such upload
        post("""{"uploadId":"${UUID.randomUUID()}"}""").statusCode(404).body("code", equalTo("upload_not_found"))
    }

    @Test
    fun aCallerWithoutWriteAccessCompletesNothing() {
        val (owner, scopeId, tenantId) = seedMemberScope()
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val upload = start(owner, scopeId, file, TestBlob.SMALL)

        // A member of the space who left the tenant reads it but writes nothing (the gate's WRITE rule).
        val reader = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); userId = reader; this.tenantId = tenantId; this.scopeId = scopeId
                role = MembershipEntity.MEMBER; wrappedGrant = GRANT; createdAt = Instant.now()
            }.persist()
        }
        complete(reader, scopeId, file, upload).statusCode(404).body("code", equalTo("scope_not_found"))
        list(reader, scopeId, null).statusCode(200).body("items.size()", equalTo(1)) // control: READ passes
        complete(seedUser(), scopeId, file, upload).statusCode(404).body("code", equalTo("scope_not_found"))
        assertNull(attachedBlob(file))
        // control: the opener completes the same upload
        complete(owner, scopeId, file, upload).statusCode(200)
    }

    @Test
    fun aListingNeverReachesIntoAnotherScope() {
        val (userId, scopeId) = seedMemberScope()
        val other = seedMemberScope()
        val foreignFolder = seedNode(other.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val foreignFile = seedNode(other.scopeId, FilesNodeKinds.FILE, parentId = foreignFolder, wrappedContentKey = CONTENT_KEY)
        complete(other.userId, other.scopeId, foreignFile, start(other.userId, other.scopeId, foreignFile, TestBlob.SMALL)).statusCode(200)

        list(userId, scopeId, foreignFolder).statusCode(404).body("code", equalTo("node_not_found"))
        // control: its own member lists it
        list(other.userId, other.scopeId, foreignFolder).statusCode(200).body("items[0].nodeId", equalTo(foreignFile))
    }

    @Test
    fun purgingATrashedFileFreesItsAttachmentAndBlob() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val blobId = complete(userId, scopeId, file, start(userId, scopeId, file, TestBlob.SMALL)).statusCode(200).extract().path<String>("blobId")
        val key = storageKeyOf(blobId)

        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$file").then().statusCode(204)
        list(userId, scopeId, null).statusCode(200).body("items.size()", equalTo(0))
        // In the trash it still holds its blob (and the quota counts it, ADR 0073 §2) …
        assertEquals(blobId, attachedBlob(file))
        assertEquals(TestBlob.SMALL.byteSize, QuarkusTransaction.requiringNew().call { usage.tenantUsage(tenantId) })

        // … until the purge, which frees the attachment, the blob and records its objects.
        QuarkusTransaction.requiringNew().run { subtreePurge.eraseExpiredIn(scopeId, Instant.now().plusSeconds(1)) }
        QuarkusTransaction.requiringNew().run {
            assertNull(NodeEntity.findById(file), "the node is purged")
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", file), "its attachment went")
            assertNull(BlobEntity.findById(blobId), "its blob went")
            assertEquals(1L, StoreReclaimEntity.count("storageKey", key), "its objects are on the reclaim list")
        }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { usage.tenantUsage(tenantId) })
    }

    /**
     * A blob's sealed metadata (`wrappedBlobMeta`, the `blob-meta` seal under `K_blob`): declared at the start, carried
     * by the session to the blob row unchanged, served back on the completion's answer and the listing — and absent
     * stays null on both, through the same routes.
     */
    @Test
    fun theDeclaredBlobMetaIsServedBackUnchangedAndAbsentStaysNull() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val withMeta = seedNode(scopeId, FilesNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val without = seedNode(scopeId, FilesNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)

        val upload = start(userId, scopeId, withMeta, TestBlob.SMALL, meta = BLOB_META)
        assertEquals(BLOB_META, QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(upload)!!.wrappedBlobMeta })
        val blobId = complete(userId, scopeId, withMeta, upload).statusCode(200)
            .body("wrappedBlobMeta", equalTo(BLOB_META)).extract().path<String>("blobId")
        assertEquals(BLOB_META, QuarkusTransaction.requiringNew().call { BlobEntity.findById(blobId)!!.wrappedBlobMeta }, "copied session to blob")
        complete(userId, scopeId, without, start(userId, scopeId, without, TestBlob.SMALL)).statusCode(200)
            .body("wrappedBlobMeta", nullValue())

        list(userId, scopeId, folder).statusCode(200)
            .body("items.find { it.nodeId == '$withMeta' }.fileBlob.wrappedBlobMeta", equalTo(BLOB_META))
            .body("items.find { it.nodeId == '$without' }.fileBlob.wrappedBlobMeta", nullValue())
    }

    /** The bound is the spec's: 400 before the handler over 2048 characters, empty or not base64, no session opened. */
    @Test
    fun aBlobMetaOverItsCapOrEmptyIsRefused400() {
        val (userId, scopeId) = seedMemberScope()
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        startRequest(userId, scopeId, file, TestBlob.SMALL, meta = "A".repeat(2049)).statusCode(400)
        startRequest(userId, scopeId, file, TestBlob.SMALL, meta = "").statusCode(400)
        startRequest(userId, scopeId, file, TestBlob.SMALL, meta = "not base64!").statusCode(400)
        assertEquals(0L, QuarkusTransaction.requiringNew().call { UploadSessionEntity.count("scopeId", scopeId) }, "no session opened")
        // control: exactly at the cap, through the same route
        startRequest(userId, scopeId, file, TestBlob.SMALL, meta = "A".repeat(2048)).statusCode(200).body("results[0].error", nullValue())
    }

    /**
     * Write-once: no operation names the meta after the start, and the column is `updatable = false` on both rows, so
     * even a write through the entity — the only way code could change it — leaves what the upload declared.
     */
    @Test
    fun theBlobMetaIsWriteOnce() {
        val (userId, scopeId) = seedMemberScope()
        val file = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val upload = start(userId, scopeId, file, TestBlob.SMALL, meta = BLOB_META)
        val touched = Instant.parse("2030-01-01T00:00:00Z")
        QuarkusTransaction.requiringNew().run {
            UploadSessionEntity.findById(upload)!!.apply { wrappedBlobMeta = "b3RoZXI="; lastWindowAt = touched }
        }
        QuarkusTransaction.requiringNew().run {
            val session = UploadSessionEntity.findById(upload)!!
            assertEquals(touched, session.lastWindowAt, "control: the same write reached an updatable column")
            assertEquals(BLOB_META, session.wrappedBlobMeta)
        }
        val blobId = complete(userId, scopeId, file, upload).statusCode(200).extract().path<String>("blobId")
        QuarkusTransaction.requiringNew().run {
            BlobEntity.findById(blobId)!!.apply { wrappedBlobMeta = "b3RoZXI="; manifest = "[1]" }
        }
        QuarkusTransaction.requiringNew().run {
            val blob = BlobEntity.findById(blobId)!!
            assertEquals("[1]", blob.manifest, "control: the same write reached an updatable column")
            assertEquals(BLOB_META, blob.wrappedBlobMeta)
        }
    }

    /** Inbox #391: the trash tells a file that holds content from one whose upload never finished (no attachment row) —
     *  core's `attached`, so the web does not offer an empty file as a restorable one. */
    @Test
    fun theTrashTellsAFileWithContentFromAnEmptyOne() {
        val (userId, scopeId) = seedMemberScope()
        val full = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        complete(userId, scopeId, full, start(userId, scopeId, full, TestBlob.SMALL)).statusCode(200)
        val empty = seedNode(scopeId, FilesNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        for (node in listOf(full, empty)) {
            given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$node").then().statusCode(204)
        }

        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/trash/nodes").then().statusCode(200)
            .body("size()", equalTo(2))
            .body("find { it.id == '$full' }.attached", equalTo(true))
            .body("find { it.id == '$empty' }.attached", equalTo(false))
    }

    // --- the wire ------------------------------------------------------------------------------------------------

    private companion object {
        /** An opaque stand-in for a client's `blob-meta` seal: the server never reads it, only stores and serves it. */
        const val BLOB_META = "c2VhbGVkLWJsb2ItbWV0YQ=="
    }

    /** Start one upload for [node] in [role] (default: the file role) and stage its chunks at their exact lengths. */
    private fun start(
        userId: String, scopeId: String, node: String, blob: TestBlob, wrap: String = "d3JhcA==",
        role: String = FilesBlobRoles.FILE_CONTENT, meta: String? = null,
    ): String {
        val uploadId = startRequest(userId, scopeId, node, blob, wrap, role, meta).statusCode(200).body("results[0].error", nullValue())
            .extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, sessionKey(uploadId))
        return uploadId
    }

    private fun startRequest(
        userId: String, scopeId: String, node: String, blob: TestBlob, wrap: String = "d3JhcA==",
        role: String = FilesBlobRoles.FILE_CONTENT, meta: String? = null,
    ): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare(wrap, ownerId = node, role = role, meta = meta)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then()

    /** A member's session for [node] in [role], opened through the store past the start's role check, chunks staged. */
    private fun openPastTheStart(userId: String, scopeId: String, node: String, blob: TestBlob, role: String): String {
        val key = UUID.randomUUID().toString().replace("-", "")
        val uploadId = sessions.createSession(scopeId, UploadOpener.Member(userId), key, blob.upload("d3JhcA==", ownerId = node, role = role))
        blob.stage(fakeStore, key)
        return uploadId
    }

    private fun complete(userId: String, scopeId: String, node: String, uploadId: String, expected: String? = null): ValidatableResponse {
        val expectedField = expected?.let { ""","expectedBlobId":"$it"""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"$expectedField}""")
            .`when`().post("/api/scopes/$scopeId/files/$node/content").then()
    }

    private fun list(userId: String, scopeId: String, parentId: String?): ValidatableResponse {
        val req = given().header("Authorization", bearer(userId))
        if (parentId != null) req.queryParam("parentId", parentId)
        return req.`when`().get("/api/scopes/$scopeId/files").then()
    }

    private fun download(userId: String, scopeId: String, blobId: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch").then()

    // --- committed state -----------------------------------------------------------------------------------------

    /** The attachment's recorded uploader: (member user id, guest identity id). */
    private fun uploaderOf(node: String): Pair<String?, String?> = QuarkusTransaction.requiringNew().call {
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, FilesBlobRoles.FILE_CONTENT).firstResult()!!
            .let { it.uploaderUserId to it.shareIdentityId }
    }

    private fun attachedBlob(node: String): String? = QuarkusTransaction.requiringNew().call {
        NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", node, FilesBlobRoles.FILE_CONTENT).firstResult()?.blobId
    }

    private fun sessionKey(uploadId: String): String = QuarkusTransaction.requiringNew().call {
        UploadSessionEntity.findById(uploadId)!!.storageKey
    }

    private fun storageKeyOf(blobId: String): String = QuarkusTransaction.requiringNew().call {
        BlobEntity.findById(blobId)!!.storageKey
    }
}
