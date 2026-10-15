package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import me.raddatz.veyq.memories.media.ShareMediaRead
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.ShareWriteProof

/**
 * Slice tests for contributor public shares (uc.sharing.share-contributor, cr-0101, ADR 0030) against the
 * **in-memory store** (metadata only — the byte path is direct-to-store, covered by the store e2e). Covers: a
 * READ_WRITE share's anonymous **identity-create** (name sealed under `K_node`, opaque) → **share-scoped upload**
 * (no bearer, gated by role + token) → the media **attaches to the shared root stamped with the identity** and
 * surfaces in the anonymous resolve (with no identity and no name — those reach the curator's bucket list only,
 * ADR 0030 point 8; the full per-role matrix is `ShareGuestNamesSliceTest`); a **READ** share rejects the identity
 * path (`403`); a **bad token** is rejected (`403`); **revoke** stops contributions (`404`); operator-blindness
 * (the sealed name + wrap round-trip, the server stores only ciphertext + opaque ids).
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `ShareContributorCoreSliceTest` in `:core`.
 */
@QuarkusTest
class ShareContributorSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="

    private val sealedName = "c2VhbGVkLW5hbWU="

    private val sealedContributorName = "c2VhbGVkLWNvbnRyaWJ1dG9yLW5hbWU="

    @Test
    fun contributeFlowAttachesStampsIdentityAndSurfacesInResolve() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "READ_WRITE")

        // name yourself — NO bearer; the sealed name is stored opaque, and we get an identity + a capability token
        val idResp = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201)
            .body("identityId", notNullValue())
            .body("contributorToken", notNullValue())
            .extract()
        val identityId = idResp.path<String>("identityId")
        val contributorToken = idResp.path<String>("contributorToken")

        // start a share-scoped upload (no bearer, token-gated) → an upload session id (in-memory store: no PUTs)
        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path<String>("results[0].uploadId")

        // complete → attaches ONE media to the shared Memory root, stamped with the identity
        val mediaId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).body("results[0].mediaId", notNullValue())
            .extract().path<String>("results[0].mediaId")

        // the anonymous media read now lists the contributed media — but names no identity and serves no guest, not
        // even this link's own: a guest's name is for the Memory's members (ADR 0030 point 8)
        ShareMediaRead.of(shareId)
            .then().statusCode(200)
            .body("media.find { it.mediaId == '$mediaId' }.nodeId", equalTo(memory)) // attached to the shared root
            .body("media.find { it.mediaId == '$mediaId' }.identityId", nullValue())
            .body("identities.size()", equalTo(0))
        // the stamp and the sealed name are stored all the same: the curator's bucket list serves both
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("buckets.find { it.ownerKey == 'share:$identityId' }.sealedName", equalTo(sealedContributorName))
    }

    /**
     * The sealed blob meta (`wrappedBlobMeta`) reaches a guest unchanged through the share read: a member's medium's
     * original and its derivative tiers, and a guest's own upload; a tier that declared none is null. Over the cap the
     * guest start is the contract's 400, at the cap it opens.
     */
    @Test
    fun theShareReadServesEachTiersBlobMetaUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        fun memberStart(blob: TestBlob, ownerId: String, role: String, meta: String?): String =
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"items":[${blob.declare(wrap, ownerId = ownerId, role = role, meta = meta)}]}""")
                .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
                .then().statusCode(200).extract().path("results[0].uploadId")
        val membersMedia = TestIds.uuid()
        val original = memberStart(TestBlob.ofChunks(2), membersMedia, "ORIGINAL", "b3JpZy1tZXRh")
        val thumb = memberStart(TestBlob.SMALL, membersMedia, "THUMBNAIL", "dGh1bWItbWV0YQ==")
        val preview = memberStart(TestBlob.SMALL, membersMedia, "PREVIEW", null)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","mediaId":"$membersMedia","originalUploadId":"$original","derivatives":[""" +
                    """{"kind":"THUMBNAIL","uploadId":"$thumb"},{"kind":"PREVIEW","uploadId":"$preview"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch").then().statusCode(200).body("results[0].error", nullValue())

        val shareId = createShare(userId, memory, role = "READ_WRITE")
        val contributorToken = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")
        fun guestStart(meta: String?) = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw", meta = meta)}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads").then()
        val guestUpload = guestStart("Z3Vlc3QtbWV0YQ==").statusCode(200).extract().path<String>("results[0].uploadId")
        val guestsMedia = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"mediaId":"${TestBlob.ownerOf(guestUpload)}","originalUploadId":"$guestUpload"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).extract().path<String>("results[0].mediaId")

        ShareMediaRead.of(shareId).then().statusCode(200)
            .body("media.find { it.mediaId == '$membersMedia' }.wrappedBlobMeta", equalTo("b3JpZy1tZXRh"))
            .body("media.find { it.mediaId == '$membersMedia' }.derivatives.find { it.kind == 'THUMBNAIL' }.wrappedBlobMeta", equalTo("dGh1bWItbWV0YQ=="))
            .body("media.find { it.mediaId == '$membersMedia' }.derivatives.find { it.kind == 'PREVIEW' }.wrappedBlobMeta", nullValue())
            .body("media.find { it.mediaId == '$guestsMedia' }.wrappedBlobMeta", equalTo("Z3Vlc3QtbWV0YQ=="))

        guestStart("A".repeat(2049)).statusCode(400).body("code", equalTo("invalid_request"))
        guestStart("A".repeat(2048)).statusCode(200).body("results[0].uploadId", notNullValue()) // control
    }

    @Test
    fun viewerShareRejectsContribution() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "READ")

        given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    @Test
    fun badTokenRejectedOnUpload() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "READ_WRITE")
        // a well-formed but never-issued token
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"bm90LWEtcmVhbC10b2tlbg","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    @Test
    fun revokeStopsContribution() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "READ_WRITE")
        val contributorToken = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")

        given().header("Authorization", bearer(userId))
            .`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)

        // a revoked share is inert for the guest paths — identity-create + upload both 404 (non-enumerable)
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities").then().statusCode(404)
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads").then().statusCode(404)
    }

    // The contributor path is anonymous, so its item bound is the one that must not be left open: the 400 is the
    // built-in bean-validation mapper's (see `MediaCoreSliceTest.startBlobUploadsOverItsItemBoundIs400`).
    @Test
    fun shareUploadOverItsItemBoundIs400() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "READ_WRITE")
        val contributorToken = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")
        fun start(n: Int) = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":${(1..n).joinToString(",", "[", "]") { TestBlob.SMALL.declare("Ymxvyi13cmFw") }}}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")

        // control: exactly `maxItems` (256) is accepted — so the negative is the bound, not the share
        start(256).then().statusCode(200).body("results.size()", equalTo(256))
        start(257).then().statusCode(400)
            .body("code", equalTo("invalid_request"))
    }

    // The contract's role is an open token (cr-files-application P3-S2); the closed enum that once kept a guest's COVER
    // out of the wire is now Memories' `admitsGuestRole` behind core's guest start, refused whole as the enum did.
    @Test
    fun guestUploadInARoleMemoriesTakesFromNoGuestIs400() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory, role = "READ_WRITE")
        val contributorToken = given().contentType(ContentType.JSON)
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201).extract().path<String>("contributorToken")
        fun start(vararg roles: String) = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":${roles.joinToString(",", "[", "]") { TestBlob.SMALL.declare("Ymxvyi13cmFw", role = it) }}}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")

        // control: a medium's three roles in one request open three sessions — so each negative is its role, not the link
        start("ORIGINAL", "THUMBNAIL", "PREVIEW").then().statusCode(200)
            .body("results.size()", equalTo(3)).body("results.uploadId", not(hasItem(nullValue())))
        // a node's cover is a member's write: refused whole, even beside an admissible item
        start("COVER").then().statusCode(400).body("code", equalTo("invalid_request"))
        start("ORIGINAL", "COVER").then().statusCode(400).body("code", equalTo("invalid_request"))
        // a well-formed token Memories names no role in
        start("FILE_CONTENT").then().statusCode(400).body("code", equalTo("invalid_request"))
        // a malformed token is the contract's own 400, before the handler
        start("cover").then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
}
