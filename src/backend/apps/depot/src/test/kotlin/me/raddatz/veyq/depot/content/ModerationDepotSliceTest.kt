package me.raddatz.veyq.depot.content

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.call
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.reviewBlobs
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.DepotNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * A content block on a Depot file and the review of a case's files (uc.moderation.take-a-measure step 3,
 * uc.moderation.review-a-case step 5), with a store that presigns ([FakePresignProfile]) — so the served control of every
 * refused byte read is a real presigned answer, not the store's refusal.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class ModerationDepotSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    @Test
    fun aBlockedFileIsNeitherReadNorReplacedByAnyoneWhileTheReviewStillReadsIt() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(m.scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(m.scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val blob = complete(m.userId, m.scopeId, file, start(m.userId, m.scopeId, file, TestBlob.ofChunks(1))).statusCode(200)
            .extract().path<String>("blobId")
        val siblingBlob = complete(m.userId, m.scopeId, sibling, start(m.userId, m.scopeId, sibling, TestBlob.ofChunks(1)))
            .statusCode(200).extract().path<String>("blobId")
        val link = share(m.userId, folder)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", file)))).then().statusCode(201)
        val caseId = openCase("NODE", file)!!.id
        val measure = mod.take(caseId, "CONTENT", targetId = file).then().statusCode(201).extract().path<String>("measures[0].id")

        // A member's download and a guest's byte read: refused; the sibling file served by both.
        download(m.userId, m.scopeId, blob).body("results[0].error", equalTo("blocked_by_operator"))
        download(m.userId, m.scopeId, siblingBlob).body("results[0].error", nullValue()).body("results[0].presignedGets", notNullValue())
        given().`when`().get("/api/shares/$link/blobs/$blob").then().statusCode(451).body("code", equalTo("blocked_by_operator"))
        given().`when`().get("/api/shares/$link/blobs/$siblingBlob").then().statusCode(200)
        // Its content is not replaced; the file is still the member's to delete.
        complete(m.userId, m.scopeId, file, start(m.userId, m.scopeId, file, TestBlob.ofChunks(2)), expected = blob)
            .statusCode(451).body("code", equalTo("blocked_by_operator"))
        // The review reads the held file and presigns its pinned object: a block never hides evidence from the moderator.
        mod.call("listCaseFiles", caseId, "GET", "/api/moderation/cases/$caseId/files").then().statusCode(200)
            .body("items.find { it.nodeId == '$file' }.fileBlob.blobId", equalTo(blob))
        mod.reviewBlobs(caseId, listOf(blob)).then().statusCode(200).body("blobs[0].blobId", equalTo(blob))
            .body("blobs[0].presignedGets.size()", equalTo(1))

        // Lifted: read and replaced again.
        mod.lift(caseId, measure).then().statusCode(200)
        given().`when`().get("/api/shares/$link/blobs/$blob").then().statusCode(200)
        complete(m.userId, m.scopeId, file, start(m.userId, m.scopeId, file, TestBlob.ofChunks(2)), expected = blob).statusCode(200)
        given().header("Authorization", bearer(m.userId)).`when`().delete("/api/scopes/${m.scopeId}/nodes/$file").then().statusCode(204)
    }

    /**
     * Everything from one uploader over Depot's files (uc.moderation.take-a-measure A8): the files whose content the
     * member uploaded to the space are counted and blocked as nodes — the reported one and one elsewhere in the space —
     * while a file they upload after the decision is served (the control on the same predicate).
     */
    @Test
    fun everythingFromOneUploaderBlocksTheFilesTheyUploadedAndNotOneAddedAfter() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val elsewhere = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val file = seedNode(m.scopeId, DepotNodeKinds.FILE, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(m.scopeId, DepotNodeKinds.FILE, parentId = elsewhere, wrappedContentKey = CONTENT_KEY)
        complete(m.userId, m.scopeId, file, start(m.userId, m.scopeId, file, TestBlob.ofChunks(1))).statusCode(200)
        val otherBlob = complete(m.userId, m.scopeId, other, start(m.userId, m.scopeId, other, TestBlob.ofChunks(1))).statusCode(200)
            .extract().path<String>("blobId")
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", file)))).then().statusCode(201)
        val caseId = openCase("NODE", file)!!.id
        mod.call("getModerationCase", caseId, "GET", "/api/moderation/cases/$caseId").then().statusCode(200)
            .body("targets.uploaders.find { it.id == '${m.userId}' }.items", equalTo(2))

        mod.take(caseId, "UPLOADER_CONTENT", targetId = m.userId).then().statusCode(201).body("measures[0].items", equalTo(2))
        download(m.userId, m.scopeId, otherBlob).body("results[0].error", equalTo("blocked_by_operator"))
        // Uploaded after the decision: not what a person decided on.
        val later = seedNode(m.scopeId, DepotNodeKinds.FILE, parentId = elsewhere, wrappedContentKey = CONTENT_KEY)
        val laterBlob = complete(m.userId, m.scopeId, later, start(m.userId, m.scopeId, later, TestBlob.ofChunks(1))).statusCode(200)
            .extract().path<String>("blobId")
        download(m.userId, m.scopeId, laterBlob).body("results[0].error", nullValue()).body("results[0].presignedGets", notNullValue())
    }

    private fun start(userId: String, scopeId: String, node: String, blob: TestBlob): String {
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==", ownerId = node, role = DepotBlobRoles.FILE_CONTENT)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch").then().statusCode(200)
            .extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey })
        return uploadId
    }

    private fun complete(userId: String, scopeId: String, node: String, uploadId: String, expected: String? = null): ValidatableResponse {
        val expectedField = expected?.let { ""","expectedBlobId":"$it"""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"$expectedField}""")
            .`when`().post("/api/scopes/$scopeId/files/$node/content").then()
    }

    private fun download(userId: String, scopeId: String, blobId: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch").then()
}
