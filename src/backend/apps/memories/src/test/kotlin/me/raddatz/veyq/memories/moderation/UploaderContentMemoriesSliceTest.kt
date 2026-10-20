package me.raddatz.veyq.memories.moderation

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.recipients
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.MediaEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Everything from one uploader (ADR 0093 §§ 1, 4, 6; uc.moderation.take-a-measure A8, cr-report-a-name-and-an-uploader):
 * the count a case shows per uploader, the `UPLOADER_CONTENT` step that blocks exactly what that uploader had uploaded to
 * the space, the hold widened to it, and its end with its last item. Each negative has its control on the same predicate:
 * another uploader's medium in the same Memory, and the same uploader's medium added after the decision, both still serve.
 */
@QuarkusTest
class UploaderContentMemoriesSliceTest {

    @Test
    fun theStepBlocksExactlyThatUploadersMediaAndTheHoldNowHoldsThem() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val guest = guest(w)
        val other = guest(w)
        val g1 = medium(w, guest)
        val g2 = medium(w, guest)
        val h1 = medium(w, other)
        val mine = medium(w, null)
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", g1)))).then().statusCode(201)
        val caseId = openCase("ITEM", g1)!!.id

        // The case names the reported medium's uploader with what they uploaded to the space: two, not the others'.
        mod.case(caseId).then().statusCode(200)
            .body("targets.uploaders.size()", equalTo(1))
            .body("targets.uploaders[0].id", equalTo(guest))
            .body("targets.uploaders[0].items", equalTo(2))
            .body("targets.uploaders[0].contentBlocked", equalTo(false))
        assertEquals(listOf(g1), heldMedia(caseId), "the report held the one medium")
        // The statement goes to the space's owners (a guest is told nothing in veyq).
        mod.recipients(caseId, "UPLOADER_CONTENT", targetId = guest).then().statusCode(200).body("accounts.accountId", equalTo(listOf(w.owner)))

        val measure = mod.take(caseId, "UPLOADER_CONTENT", targetId = guest).then().statusCode(201)
            .body("measures[0].kind", equalTo("UPLOADER_CONTENT"))
            .body("measures[0].items", equalTo(2))
            .body("targets.uploaders[0].contentBlocked", equalTo(true))
            .extract().path<String>("measures[0].id")
        mediaList(w).then().statusCode(200)
            .body("media.find { it.mediaId == '$g1' }.blockedByOperator", equalTo(true))
            .body("media.find { it.mediaId == '$g2' }.blockedByOperator", equalTo(true))
            .body("media.find { it.mediaId == '$h1' }.blockedByOperator", equalTo(false))
            .body("media.find { it.mediaId == '$mine' }.blockedByOperator", equalTo(false))
        // The hold widened to the uploader's other medium — "later-held", by the hold's rules: one hold, no row twice,
        // every pin at its end.
        assertEquals(listOf(g1, g2).sorted(), heldMedia(caseId).sorted())
        val (holds, pinEnds) = QuarkusTransaction.requiringNew().call {
            val all = HoldEntity.list("caseId", caseId)
            all.size to HoldPinEntity.list("holdId", all.single().id).map { it.expiresAt }.toSet()
        }
        assertEquals(1, holds)
        assertEquals(1, pinEnds.size, "every pin ends with the hold: $pinEnds")

        // The space's owner reads the statement, and finds it on each item it covers — and only those.
        val covers = given().header("Authorization", bearer(w.owner)).`when`().get("/api/statements").then().statusCode(200)
            .body("statements[0].measureKind", equalTo("UPLOADER_CONTENT"))
            .extract().path<List<String>>("statements[0].covers")
        assertEquals(listOf(g1, g2).sorted(), covers.sorted())

        // What the uploader adds after the decision is not what a person decided on.
        val later = medium(w, guest)
        mediaList(w).then().body("media.find { it.mediaId == '$later' }.blockedByOperator", equalTo(false))

        // Lifted, both serve again.
        mod.lift(caseId, measure).then().statusCode(200)
        mediaList(w).then().body("media.find { it.mediaId == '$g2' }.blockedByOperator", equalTo(false))
    }

    @Test
    fun aMembersUploadsAreCountedByTheirAccountAndAnUploaderWithNothingInTheSpaceIsNoTarget() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val mine = medium(w, null)
        medium(w, null)
        medium(w, guest(w))
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", mine)))).then().statusCode(201)
        val caseId = openCase("ITEM", mine)!!.id
        mod.case(caseId).then().statusCode(200)
            .body("targets.uploaders.find { it.id == '${w.owner}' }.kind", equalTo("ACCOUNT"))
            .body("targets.uploaders.find { it.id == '${w.owner}' }.items", equalTo(2))
        // An id the case does not name is refused; the step on the named member is taken (the control).
        mod.take(caseId, "UPLOADER_CONTENT", targetId = UUID.randomUUID().toString()).then().statusCode(404)
            .body("code", equalTo("moderation_target_unknown"))
        mod.take(caseId, "UPLOADER_CONTENT", targetId = w.owner).then().statusCode(201).body("measures[0].items", equalTo(2))
    }

    @Test
    fun theBlockEndsWhenItsLastItemIsPurgedAndNotBefore() {
        val mod = CaseTestKit.moderator()
        val w = world()
        val guest = guest(w)
        val g1 = medium(w, guest)
        val g2 = medium(w, guest)
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", g1)))).then().statusCode(201)
        val caseId = openCase("ITEM", g1)!!.id
        val measure = mod.take(caseId, "UPLOADER_CONTENT", targetId = guest).then().statusCode(201).extract().path<String>("measures[0].id")

        purge(w, g1)
        assertNotNull(row(measure).activeKey, "control: one item left, the block stands on it")
        mod.case(caseId).then().body("measures[0].items", equalTo(1))
        purge(w, g2)
        val ended = row(measure)
        assertNull(ended.activeKey, "the block ended with its last item")
        assertNull(ended.liftedBy, "lifted by no one")
    }

    // --- the world ------------------------------------------------------------------------------------------------

    private class World(val owner: String, val scopeId: String, val memory: String, val shareId: String)

    private fun world(): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(m.scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        return World(m.userId, m.scopeId, memory, share(m.userId, memory, role = "READ_WRITE"))
    }

    /** A guest identity of the world's link, minted as a guest's client does; its id. */
    private fun guest(w: World): String =
        given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(w.shareId))
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"Z3Vlc3Q="}""")
            .`when`().post("/api/shares/${w.shareId}/identities").then().statusCode(201).extract().path("identityId")

    /** A medium of the world's Memory, uploaded by the guest [identity] or, null, by the owner. Seeded, not uploaded. */
    private fun medium(w: World, identity: String?): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                this.id = blobId; scopeId = w.scopeId; storageKey = UUID.randomUUID().toString().replace("-", "")
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            MediaEntity().apply {
                this.id = id; scopeId = w.scopeId; nodeId = w.memory; this.blobId = blobId; shareIdentityId = identity
                createdBy = identity?.let { "share:$it" } ?: "user:${w.owner}"; updatedAt = Instant.now(); wrappedMeta = "bWV0YQ=="
            }.persist()
        }
        return id
    }

    private fun mediaList(w: World) =
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/nodes/${w.memory}/media")

    private fun purge(w: World, mediaId: String) {
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/scopes/${w.scopeId}/media/$mediaId").then().statusCode(204)
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/scopes/${w.scopeId}/media/$mediaId/purge").then().statusCode(204)
    }

    private fun heldMedia(caseId: String): List<String> = QuarkusTransaction.requiringNew().call {
        val hold = HoldEntity.find("caseId", caseId).firstResult()!!
        HoldRowEntity.list("holdId = ?1 and sourceTable = ?2", hold.id, "veyq_media").map { it.sourceId }
    }

    private fun row(id: String): ModerationBlockEntity = QuarkusTransaction.requiringNew().call { ModerationBlockEntity.findById(id)!! }
}
