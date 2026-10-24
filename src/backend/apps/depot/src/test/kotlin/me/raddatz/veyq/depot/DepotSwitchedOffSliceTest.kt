package me.raddatz.veyq.depot

import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.FakePresigningBlobStore
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
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
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Depot switched off is read-only (uc.platform.switch-application-off): a file's completion, a new upload of its content
 * and a drop-box guest's folder answer `422 capability_disabled`, each with its control once Depot is on again, while a
 * file that exists still presigns its download — on a store that signs (`FakePresignProfile`), so the URL is real.
 * Every operation of the application is `ApplicationSwitchSweepTest`'s.
 */
@QuarkusTest
@TestProfile(FakePresignProfile::class)
class DepotSwitchedOffSliceTest {

    @Inject lateinit var fakeStore: FakePresigningBlobStore

    @Test
    fun offAFileIsNotCompletedAndItsDownloadPresigns_onTheCompletionLands() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val file = seedNode(scopeId, DepotNodeKinds.FILE, wrappedContentKey = CONTENT_KEY)
        val blobId = UUID.randomUUID().toString()
        complete(userId, scopeId, file, start(userId, scopeId, file, blobId).statusCode(200).uploadId()).statusCode(200)
        val replacement = start(userId, scopeId, file).statusCode(200).uploadId() // started before the switch (E4)

        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.DEPOT) {
            complete(userId, scopeId, file, replacement, expected = blobId).statusCode(422).body("code", equalTo("capability_disabled"))
            start(userId, scopeId, file).statusCode(422).body("code", equalTo("capability_disabled"))
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"items":[{"blobId":"$blobId"}]}""")
                .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
                .then().statusCode(200).body("results[0].error", nullValue()).body("results[0].presignedGets[0].url", notNullValue())
        }
        complete(userId, scopeId, file, replacement, expected = blobId).statusCode(200) // control
    }

    @Test
    fun offADropBoxGuestGetsNoFolder_onItDoes() {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val dropBox = seedNode(scopeId, DepotNodeKinds.DROPBOX, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, dropBox, role = ShareEntity.WRITE_ONLY)
        TenantCapabilitySwitchFixtures.with(tenantId, CapabilityIds.DEPOT) {
            ensureFolder(link).statusCode(422).body("code", equalTo("capability_disabled"))
        }
        ensureFolder(link).statusCode(200) // control
    }

    private fun start(userId: String, scopeId: String, file: String, blobId: String = UUID.randomUUID().toString()): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", ownerId = file, role = DepotBlobRoles.FILE_CONTENT, blobId = blobId)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch").then()

    private fun ValidatableResponse.uploadId(): String =
        body("results[0].error", nullValue()).extract().path<String>("results[0].uploadId")
            .also { TestBlob.SMALL.stage(fakeStore, storageKey(it)) }

    private fun complete(userId: String, scopeId: String, file: String, uploadId: String, expected: String? = null): ValidatableResponse {
        val expectedField = expected?.let { ""","expectedBlobId":"$it"""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"uploadId":"$uploadId"$expectedField}""")
            .`when`().post("/api/scopes/$scopeId/files/$file/content").then()
    }

    private fun ensureFolder(link: GuestTreeShare.Link): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body(
                """{"contributorToken":"${link.token}","nodeId":"${UUID.randomUUID()}","encryptedName":"bmFtZQ==",""" +
                    """"slugToken":"${GuestTreeShare.slug()}","epoch":0,"wrappedContentKey":"d3JhcHBlZC11bmRlci1rdXA="}""",
            )
            .`when`().post("/api/shares/${link.shareId}/files/drop-folder").then()

    private fun storageKey(uploadId: String): String =
        QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }
}
