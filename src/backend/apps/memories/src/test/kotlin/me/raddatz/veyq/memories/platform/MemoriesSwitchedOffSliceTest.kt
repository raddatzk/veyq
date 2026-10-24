package me.raddatz.veyq.memories.platform

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * Memories switched off is read-only (uc.platform.switch-application-off): a Memory's rename, a medium's completion
 * and a `READ_WRITE` guest's upload answer `422 capability_disabled`, each with its control on the same request once the
 * switch is back on; what exists still lists, downloads, goes to the trash and back, takes a member's heart and is served
 * through its link. Off for the instance and on for the tenant is off (E3). Every operation of the application is
 * `ApplicationSwitchSweepTest`'s; the re-key is `MediaNodeRekeySliceTest`'s.
 */
@QuarkusTest
class MemoriesSwitchedOffSliceTest {

    private data class World(val userId: String, val scopeId: String, val tenantId: String, val memory: String, val shareId: String, val token: String)

    @Test
    fun offAMemoryIsNotRenamedNoMediumCompletesAndNoGuestUploads_onEachLands() {
        val w = world()
        val upload = memberStart(w) // started before the switch (E4): its completion is refused all the same
        val guestUpload = guestStart(w).statusCode(200).extract().path<String>("results[0].uploadId")

        TenantCapabilitySwitchFixtures.with(w.tenantId, CapabilityIds.MEMORIES) {
            rename(w).statusCode(422).body("code", equalTo("capability_disabled"))
            complete(w, upload).statusCode(422).body("code", equalTo("capability_disabled"))
            memberStartResponse(w).statusCode(422).body("code", equalTo("capability_disabled"))
            guestStart(w).statusCode(422).body("code", equalTo("capability_disabled"))
            guestComplete(w, guestUpload).statusCode(422).body("code", equalTo("capability_disabled"))
        }
        // control: the same requests land once Memories is on again — nothing above was refused for another reason
        rename(w).statusCode(204)
        complete(w, upload).statusCode(200).body("results[0].error", nullValue())
        guestComplete(w, guestUpload).statusCode(200).body("results[0].mediaId", notNullValue())
        guestStart(w).statusCode(200)
    }

    @Test
    fun offWhatExistsListsDownloadsGoesToTheTrashAndBackTakesAHeartAndIsServedThroughItsLink() {
        val w = world()
        val blobId = UUID.randomUUID().toString()
        val mediaId = UUID.randomUUID().toString()
        complete(w, memberStart(w, blobId = blobId, mediaId = mediaId)).statusCode(200).body("results[0].error", nullValue())

        var downloadOff: Any? = null
        TenantCapabilitySwitchFixtures.with(w.tenantId, CapabilityIds.MEMORIES) {
            // the switch is in effect for this tenant: a write is refused (the predicate the reads below are held against)
            rename(w).statusCode(422).body("code", equalTo("capability_disabled"))

            member(w).`when`().get("/api/scopes/${w.scopeId}/nodes/${w.memory}/media")
                .then().statusCode(200).body("media.find { it.mediaId == '$mediaId' }.mediaId", equalTo(mediaId))
            downloadOff = download(w, blobId)
            member(w).contentType(ContentType.JSON).body("""{"ciphertext":"aGVhcnQ="}""")
                .`when`().put("/api/scopes/${w.scopeId}/nodes/${w.memory}/item-favorites").then().statusCode(204)
            given().`when`().get("/api/shares/${w.shareId}").then().statusCode(200)

            member(w).`when`().delete("/api/scopes/${w.scopeId}/nodes/${w.memory}").then().statusCode(204)
            member(w).contentType(ContentType.JSON).body("""{"slugToken":"${slug()}","parentEpoch":0}""")
                .`when`().post("/api/scopes/${w.scopeId}/nodes/${w.memory}/restore").then().statusCode(204)
            // restored, it is read-only like the rest (owner 2026-10-23)
            rename(w).statusCode(422).body("code", equalTo("capability_disabled"))
        }
        // the download is answered exactly as with Memories on: this profile's store signs nothing (`store_unavailable`
        // both ways), so the answers are compared; a URL signed while an application is off is `DepotSwitchedOffSliceTest`'s
        assertEquals(download(w, blobId), downloadOff)
    }

    @Test
    fun offForTheInstanceAndOnForTheTenantIsOff() {
        val w = world()
        CapabilitySwitchFixtures.with(CapabilityIds.MEMORIES) {
            rename(w).statusCode(422).body("code", equalTo("capability_disabled"))
        }
        rename(w).statusCode(204) // control
    }

    @Test
    fun offNoLinkIsMadeOnAMemoryButAFolderTakesOne() {
        val w = world()
        val folder = seedNode(w.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        TenantCapabilitySwitchFixtures.with(w.tenantId, CapabilityIds.MEMORIES) {
            createShare(w.userId, w.memory, "READ").statusCode(422).body("code", equalTo("capability_disabled"))
            createShare(w.userId, folder, "READ").statusCode(201) // core's own kind is never switched by an application
        }
        createShare(w.userId, w.memory, "READ").statusCode(201) // control
    }

    // --- the world ---

    private fun world(): World {
        val (userId, scopeId, tenantId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, "READ_WRITE").statusCode(201).extract().path<String>("shareId")
        val token = given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"identityId":"${TestIds.uuid()}","sealedDisplayName":"$NAME"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")
        return World(userId, scopeId, tenantId, memory, shareId, token)
    }

    private fun member(w: World) = given().header("Authorization", bearer(w.userId))

    /** `(status, body)` of a member's download presign of [blobId]. */
    private fun download(w: World, blobId: String): Pair<Int, String> =
        member(w).contentType(ContentType.JSON).body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/${w.scopeId}/blob-downloads-batch").then().extract().let { it.statusCode() to it.body().asString() }

    private fun rename(w: World): ValidatableResponse =
        member(w).contentType(ContentType.JSON)
            .body("""{"encryptedName":"$NAME","slugToken":"${slug()}","wrappedContentKey":"$CONTENT_KEY","epoch":0,"placementRevision":${placementRevision(w)}}""")
            .`when`().patch("/api/scopes/${w.scopeId}/nodes/${w.memory}").then()

    private fun placementRevision(w: World): Long =
        member(w).`when`().get("/api/scopes/${w.scopeId}/nodes").then().statusCode(200)
            .extract().path<Number>("find { it.id == '${w.memory}' }.placementRevision").toLong()

    private fun memberStartResponse(w: World, blobId: String = UUID.randomUUID().toString(), mediaId: String = UUID.randomUUID().toString()) =
        member(w).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare(WRAP, ownerId = mediaId, blobId = blobId)}]}""")
            .`when`().post("/api/scopes/${w.scopeId}/blob-uploads-batch").then()

    private fun memberStart(w: World, blobId: String = UUID.randomUUID().toString(), mediaId: String = UUID.randomUUID().toString()): String =
        memberStartResponse(w, blobId, mediaId).statusCode(200).extract().path("results[0].uploadId")

    private fun complete(w: World, uploadId: String): ValidatableResponse =
        member(w).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"${w.memory}","mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","derivatives":[],"nodeEpoch":0}]}""")
            .`when`().post("/api/scopes/${w.scopeId}/media-batch").then()

    private fun guestStart(w: World): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${w.token}","items":[${TestBlob.SMALL.declare(WRAP)}]}""")
            .`when`().post("/api/shares/${w.shareId}/blob-uploads").then()

    private fun guestComplete(w: World, uploadId: String): ValidatableResponse =
        given().contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"contributorToken":"${w.token}","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/${w.shareId}/media").then()

    private fun createShare(userId: String, nodeId: String, role: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role","wrap":"$WRAP","sealedName":"$NAME",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then()

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private companion object {
        const val WRAP = "d3JhcA=="
        const val NAME = "bmFtZQ=="
    }
}
