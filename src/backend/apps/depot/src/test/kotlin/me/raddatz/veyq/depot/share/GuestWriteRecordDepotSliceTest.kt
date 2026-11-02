package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.testing.FakePresignProfile
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.depot.DepotNodeKinds
import me.raddatz.veyq.depot.content.DepotBlobRoles
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The guest-write record for a drop box's guests (ADR 0093 § 10, en.moderation.guest-write-record; uc.depot.drop-box
 * A4b): the call that creates the guest's folder records once and the call that finds it records nothing; a file the
 * guest creates and the content it uploads into it each record, the content as a `NODE` with no field group; a replace
 * that loses its compare-and-set attached nothing and records nothing. On [FakePresignProfile], so the uploads verify.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class GuestWriteRecordDepotSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    @Test
    fun theFolderTheFileAndItsContentEachRecordOnce() {
        val (userId, scopeId, _) = seedMemberScope()
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, dropBox, ShareEntity.WRITE_ONLY)
        QuarkusTransaction.requiringNew().run {
            ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.wrappedIdentityKey = IDENTITY_KEY
        }

        val folder = ensure(link, "203.0.113.40:41000").body("created", equalTo(true)).extract().path<String>("nodeId")
        ensure(link, "203.0.113.41").body("created", equalTo(false)).body("nodeId", equalTo(folder))
        assertEquals(listOf(Triple("NODE", "name", 41000)), rows(folder).map { Triple(it.objectKind, it.fieldGroup, it.clientPort) }, "only the creating call records")

        val file = UUID.randomUUID().toString()
        GuestTreeShare.create(link, folder, DepotNodeKinds.FILE, nodeId = file).statusCode(201)
        val first = upload(link, file, "203.0.113.42:42000", expected = null).statusCode(200).body("error", nullValue())
            .extract().path<String>("blobId")
        val fileRows = rows(file)
        assertEquals(1, fileRows.count { it.fieldGroup == "name" }, "the create names the file")
        assertEquals(listOf("NODE" to 42000), fileRows.filter { it.fieldGroup == null }.map { it.objectKind to it.clientPort },
            "the content names no field group, at the port the edge saw")

        // A replace naming a blob the slot no longer holds attaches nothing, and records nothing.
        upload(link, file, "203.0.113.43", expected = UUID.randomUUID().toString()).statusCode(409)
        assertEquals(0, rows(file).count { it.clientIp == "203.0.113.43" }, "the conflict attached nothing")
        upload(link, file, "203.0.113.44", expected = first).statusCode(200)
        assertEquals(1, rows(file).count { it.clientIp == "203.0.113.44" }, "control: a replace that stands records")
    }

    private fun ensure(link: GuestTreeShare.Link, xff: String) =
        given().contentType(ContentType.JSON).header("X-Forwarded-For", xff)
            .body(
                """{"contributorToken":"${link.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                    """"slugToken":"${GuestTreeShare.slug()}","scopeEpoch":0,"wrappedContentKey":"d3JhcA=="}""",
            )
            .`when`().post("/api/shares/${link.shareId}/files/drop-folder").then().statusCode(200)

    private fun upload(link: GuestTreeShare.Link, file: String, xff: String, expected: String?) = run {
        val blob = TestBlob.SMALL
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${link.token}","nodeId":"$file","items":[${blob.declare("d3JhcA==", ownerId = file, role = DepotBlobRoles.FILE_CONTENT)}]}""")
            .`when`().post("/api/shares/${link.shareId}/blob-uploads").then().statusCode(200).extract().path<String>("results[0].uploadId")
        blob.stage(fakeStore, QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey })
        val expectedField = expected?.let { ""","expectedBlobId":"$it"""" } ?: ""
        given().contentType(ContentType.JSON).header("X-Forwarded-For", xff)
            .body("""{"contributorToken":"${link.token}","nodeEpoch":0,"uploadId":"$uploadId"$expectedField}""")
            .`when`().post("/api/shares/${link.shareId}/files/$file/content").then()
    }

    private fun rows(objectId: String): List<GuestWriteRecordEntity> =
        QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.list("objectId", objectId) }

    private companion object {
        val IDENTITY_KEY: String = Base64.getEncoder().encodeToString("k-up-wrapped-to-the-drop-key".toByteArray())
    }
}
