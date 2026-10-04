package com.veyq.core.sharing

import com.veyq.core.sharing.ShareEntity
import com.veyq.core.sharing.ShareIdentityEntity
import com.veyq.core.testing.TestBlob
import com.veyq.core.TestIds
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.identityKeyJson
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import com.veyq.core.testing.TestFixtures.shareKeysJson
import com.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A guest naming themself on a link proves they hold its key, not only its id (ADR 0076, uc.sharing.share-contributor
 * / uc.sharing.share-uploader). The share id is stored raw, so a database copy (or an access log) holds every id;
 * what it does not hold is `writeToken`, of which the share keeps only the hash. Every negative here owes its
 * positive control on the same share: a link that 404s wholesale would satisfy each negative perfectly.
 */
@QuarkusTest
class ShareWriteProofSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val sealedGuestName = "c2VhbGVkLWd1ZXN0LW5hbWU="

    /** B1 — a database copy names no guest: the id it holds, and the verifier it holds, are not the token. */
    @Test
    fun anIdWithoutTheKeyNamesNoGuest() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, "CONTRIBUTOR")
        // the row as a database copy reads it: the server stored the hash it was handed, never a token
        val stored = QuarkusTransaction.requiringNew().call { ShareEntity.findById(shareId)!!.writeVerifier }
        assertEquals(ShareWriteProof.verifier(shareId), stored)

        nameGuest(shareId, writeToken = null).then().statusCode(404).body("code", equalTo("share_not_found"))
        nameGuest(shareId, writeToken = stored).then().statusCode(404).body("code", equalTo("share_not_found"))
        // a token derived for another link — the binding (ADR 0072): the same holder, the wrong share
        nameGuest(shareId, writeToken = ShareWriteProof.token(TestIds.shareId())).then().statusCode(404)
            .body("code", equalTo("share_not_found"))
        nameGuest(shareId, writeToken = "A").then().statusCode(404).body("code", equalTo("share_not_found")) // not base64
        // outside the base64 alphabet: the boundary's 400, before any lookup — so the same for an id that exists
        nameGuest(shareId, writeToken = "!!").then().statusCode(400)
        nameGuest(TestIds.shareId(), writeToken = "!!").then().statusCode(400)
        assertEquals(0L, identitiesOf(shareId), "no refused proof wrote an identity")

        // positive control: the whole link names a guest, and that guest uploads exactly as before
        val token = nameGuest(shareId, ShareWriteProof.token(shareId)).then().statusCode(201)
            .body("contributorToken", notNullValue()).extract().path<String>("contributorToken")
        assertEquals(1L, identitiesOf(shareId))
        uploadAs(shareId, token)
    }

    /** B2 — the gate survives a role flip: a VIEWER link stored its verifier too, since its key never changes. */
    @Test
    fun aViewerLinkFlippedToContributorIsGatedByTheSameProof() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, "VIEWER")
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"role":"CONTRIBUTOR"}""")
            .`when`().patch("/api/shares/$shareId").then().statusCode(204)

        nameGuest(shareId, writeToken = null).then().statusCode(404).body("code", equalTo("share_not_found"))
        nameGuest(shareId, ShareWriteProof.token(shareId)).then().statusCode(201)
    }

    /** B2 — an UPLOADER link behaves as a CONTRIBUTOR one. */
    @Test
    fun anUploaderLinkNeedsTheProofToo() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, "UPLOADER")

        nameGuest(shareId, writeToken = null, role = "UPLOADER").then().statusCode(404).body("code", equalTo("share_not_found"))
        nameGuest(shareId, ShareWriteProof.token(TestIds.shareId()), role = "UPLOADER").then().statusCode(404)
        assertEquals(0L, identitiesOf(shareId))
        val token = nameGuest(shareId, ShareWriteProof.token(shareId), role = "UPLOADER").then().statusCode(201)
            .extract().path<String>("contributorToken")
        uploadAs(shareId, token)
    }

    /**
     * B3 — a share holding no verifier (minted before the column) fails closed: it takes no new guest whatever the
     * token, while a guest named before keeps uploading — only naming oneself is gated.
     */
    @Test
    fun aShareWithoutAVerifierTakesNoNewGuestButKeepsItsOld() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, "CONTRIBUTOR")
        val earlier = nameGuest(shareId, ShareWriteProof.token(shareId)).then().statusCode(201)
            .extract().path<String>("contributorToken") // also the control: this share took a guest with a verifier
        QuarkusTransaction.requiringNew().run { ShareEntity.update("writeVerifier = null where id = ?1", shareId) }

        nameGuest(shareId, ShareWriteProof.token(shareId)).then().statusCode(404).body("code", equalTo("share_not_found"))
        nameGuest(shareId, writeToken = null).then().statusCode(404).body("code", equalTo("share_not_found"))
        assertEquals(1L, identitiesOf(shareId))
        uploadAs(shareId, earlier)
    }

    /**
     * B5 (#270) — the manage list marks each guest-taking link that holds no verifier, so its curator knows which
     * links to renew: a CONTRIBUTOR and an UPLOADER link without one are marked; a VIEWER link without one is not (it
     * takes no guest), and neither is a link that has its verifier — the control that the mark is not on every row.
     */
    @Test
    fun theManageListMarksAGuestLinkThatHoldsNoVerifier() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val contributor = createShare(userId, memory, "CONTRIBUTOR")
        val uploader = createShare(userId, memory, "UPLOADER")
        val viewer = createShare(userId, memory, "VIEWER")
        val intact = createShare(userId, memory, "CONTRIBUTOR")
        QuarkusTransaction.requiringNew().run {
            ShareEntity.update("writeVerifier = null where id in ?1", listOf(contributor, uploader, viewer))
        }

        val rows = given().header("Authorization", bearer(userId)).queryParam("nodeId", memory)
            .`when`().get("/api/shares").then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("")
            .associate { it["shareId"] as String to it["newGuestsRefused"] }

        assertEquals(mapOf(contributor to true, uploader to true, viewer to false, intact to false), rows)
    }

    /**
     * #303 — the role edit needs the verifier fact whatever the role: a VIEWER link without one turns into a
     * CONTRIBUTOR link that refuses every guest, so the row says so before the switch. A link with its verifier is
     * the control, in both roles.
     */
    @Test
    fun theManageListSaysWhichLinksHoldNoVerifierWhateverTheirRole() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val viewer = createShare(userId, memory, "VIEWER")
        val contributor = createShare(userId, memory, "CONTRIBUTOR")
        val intactViewer = createShare(userId, memory, "VIEWER")
        val intactContributor = createShare(userId, memory, "CONTRIBUTOR")
        QuarkusTransaction.requiringNew().run {
            ShareEntity.update("writeVerifier = null where id in ?1", listOf(viewer, contributor))
        }

        val rows = given().header("Authorization", bearer(userId)).queryParam("nodeId", memory)
            .`when`().get("/api/shares").then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("")
            .associate { it["shareId"] as String to it["writeVerifierMissing"] }

        assertEquals(mapOf(viewer to true, contributor to true, intactViewer to false, intactContributor to false), rows)
    }

    /** B4 — a share is never created without a verifier: the field is required for every role. */
    @Test
    fun createWithoutAVerifierIs400AndStoresNothing() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId","nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares").then().statusCode(400)
        assertNull(QuarkusTransaction.requiringNew().call { ShareEntity.findById(shareId) })
        // control: the same body with the field is created
        assertNotNull(createShare(userId, memory, "VIEWER"))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String, role: String): String {
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId","nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName",${ShareWriteProof.field(shareId)}}""")
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }

    private fun nameGuest(shareId: String, writeToken: String?, role: String = "CONTRIBUTOR") =
        given().contentType(ContentType.JSON)
            .apply { if (writeToken != null) header(ShareWriteProof.HEADER, writeToken) }
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedGuestName"${identityKeyJson(role)}}""")
            .`when`().post("/api/shares/$shareId/identities")

    private fun identitiesOf(shareId: String): Long =
        QuarkusTransaction.requiringNew().call { ShareIdentityEntity.count("shareId", shareId) }

    /** Start and complete one upload through [contributorToken] — the guest write the proof leaves untouched. */
    private fun uploadAs(shareId: String, contributorToken: String) {
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path<String>("results[0].uploadId")
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).body("results[0].mediaId", notNullValue())
    }
}
