package com.veyq.core.sharing

import com.veyq.core.blob.BlobEntity
import com.veyq.core.node.NodeEntity
import com.veyq.core.admin.tenant.TenantTermsAcceptanceEntity
import com.veyq.memories.media.MediaEntity
import com.veyq.memories.media.TestBlob
import com.veyq.core.scope.ScopeEntity
import com.veyq.core.tenant.TenantEntity
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import com.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test

/**
 * Slice tests for public viewer shares (uc.sharing.share-public-viewer, cr-0100, ADR 0029) against the
 * **in-memory store** (metadata only — the byte path is direct-from-store, covered by the store e2e). Covers
 * the six acceptance criteria: create → **anonymous** resolve returns the sealed subtree; the shareId alone is
 * inert (the wrap is opaque); the gated blob GET allows an in-subtree blob (store_unavailable proves authz on
 * the dev store) and 404s an out-of-subtree blob; revoke + expiry 404 the guest paths; a non-MEMORY (folder)
 * node create is rejected 422 (group/tenant shared-scope sharing is cr-0104); a non-member/unknown node is 404;
 * resolve leaks nothing outside the subtree.
 *
 * With the in-memory store a presign answers `503 store_unavailable` when the blob **is** authorized (proving
 * the subtree gate passed) vs `404 share_not_found` when it is not — so the two codes distinguish "authorized
 * but no transport" from "not under the share", which is what these hermetic tests assert.
 */
@QuarkusTest
class ShareSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun createThenAnonymousResolveReturnsSealedSubtree() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, NodeEntity.CHAPTER, parentId = memory, wrappedContentKey = "Y2hhcC1rbWVt")
        val (mediaId, blobId) = seedMedia(scopeId, memory)

        val shareId = createShare(userId, memory)

        // resolve with NO bearer — the record + the sealed subtree, operator-blind (wrap/sealedName round-trip)
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("role", equalTo("VIEWER"))
            .body("wrap", equalTo(wrap))
            .body("sealedName", equalTo(sealedName))
            // the root Memory carries a null parentId; the chapter is present (whole-subtree share)
            .body("nodes.find { it.id == '$memory' }.parentId", equalTo(null))
            .body("nodes.id", hasItem(chapter))
            // the media is listed with its owning nodeId + the original blob's wrap
            .body("media.find { it.mediaId == '$mediaId' }.nodeId", equalTo(memory))
            .body("media.find { it.mediaId == '$mediaId' }.blobId", equalTo(blobId))
    }

    @Test
    fun gatedBlobAllowsInSubtreeAnd404sOutOfSubtree() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (_, inBlob) = seedMedia(scopeId, memory)

        // a sibling Memory (NOT shared) with its own media — its blob must be unreachable through the share
        val other = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (_, outBlob) = seedMedia(scopeId, other)

        val shareId = createShare(userId, memory)

        // in-subtree blob → authorized (503 store_unavailable on the dev store: gate passed, no transport)
        given().`when`().get("/api/shares/$shareId/blobs/$inBlob")
            .then().statusCode(503).body("code", equalTo("store_unavailable"))

        // out-of-subtree blob → 404 (structural gate: not attached under the shared node)
        given().`when`().get("/api/shares/$shareId/blobs/$outBlob")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        // an unknown blob id → 404 too
        given().`when`().get("/api/shares/$shareId/blobs/${UUID.randomUUID()}")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun resolveLeaksNothingOutsideTheSubtree() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        seedMedia(scopeId, sibling)

        val shareId = createShare(userId, memory)

        // the sibling Memory + its media are NOT in the resolved payload (only the shared subtree)
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("nodes.id", not(hasItem(sibling)))
            .body("media.nodeId", not(hasItem(sibling)))
    }

    @Test
    fun revokeMakesGuestPaths404() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val (_, blobId) = seedMedia(scopeId, memory)
        val shareId = createShare(userId, memory)

        given().`when`().get("/api/shares/$shareId").then().statusCode(200) // live

        given().header("Authorization", bearer(userId))
            .`when`().post("/api/shares/$shareId/revoke")
            .then().statusCode(204)

        given().`when`().get("/api/shares/$shareId").then().statusCode(404).body("code", equalTo("share_not_found"))
        given().`when`().get("/api/shares/$shareId/blobs/$blobId").then().statusCode(404)

        // revoke is idempotent (still owned) → 204 again
        given().header("Authorization", bearer(userId))
            .`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
    }

    @Test
    fun expiredShareResolves404() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val past = Instant.now().minusSeconds(3600).toString()

        val shareId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","expiresAt":"$past"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path<String>("shareId")

        given().`when`().get("/api/shares/$shareId").then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun listReturnsCallersSharesForTheMemory() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory)

        given().header("Authorization", bearer(userId))
            .`when`().get("/api/shares")
            .then().statusCode(200)
            .body("find { it.shareId == '$shareId' }.nodeId", equalTo(memory))
            .body("find { it.shareId == '$shareId' }.enabled", equalTo(true))
    }

    @Test
    fun folderNodeIsRejected() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val folder = seedNode(scopeId, NodeEntity.FOLDER, parentId = scopeId, wrappedContentKey = null)

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$folder","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(422).body("code", equalTo("illegal_scope"))
    }

    @Test
    fun nonMemberCreateIs404() {
        val (_, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val outsider = seedUser()

        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun unknownNodeCreateIs404() {
        val userId = seedUser()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${UUID.randomUUID()}","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    @Test
    fun unknownShareResolves404() {
        // a well-formed but unknown 43-char base64url id — non-enumerable 404
        given().`when`().get("/api/shares/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    @Test
    fun createRequiresAuth() {
        given().contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"${UUID.randomUUID()}","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- accountless terms gate (uc.sharing.share-accept, ADR 0035 §3) ---

    @Test
    fun shareTermsServesOwningTenantTermsAndRecordsNoAcceptance() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val tenantId = seedTerms(scopeId, de = "## Nutzungsbedingungen", en = "## Terms of use")
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory)

        // (a) an accountless GET (no bearer) gets the owning tenant's terms — the opaque tenantId is only RETURNED
        // (the client's localStorage key), never passed in, so there is no tenantId existence oracle.
        given().`when`().get("/api/shares/$shareId/terms")
            .then().statusCode(200)
            .body("tenantId", equalTo(tenantId))
            .body("termsVersion", equalTo(1))
            .body("terms.de", equalTo("## Nutzungsbedingungen"))
            .body("terms.en", equalTo("## Terms of use"))

        // (c) the server records NOTHING about the accountless visitor — no acceptance row exists for the tenant.
        val acceptances = QuarkusTransaction.requiringNew().call {
            TenantTermsAcceptanceEntity.count("tenantId", tenantId)
        }
        assert(acceptances == 0L) { "expected no acceptance record for an accountless terms read, found $acceptances" }
    }

    @Test
    fun shareTermsVersionZeroWhenTenantPublishedNoTerms() {
        // A tenant with no published terms → version 0 / null terms; the client then never gates.
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory)

        given().`when`().get("/api/shares/$shareId/terms")
            .then().statusCode(200)
            .body("termsVersion", equalTo(0))
            .body("terms", nullValue())
    }

    @Test
    fun shareTermsIsTheSameNonEnumerable404AsResolve() {
        // (b) unknown / disabled (revoked) / expired are ALL the same non-enumerable 404 — no existence oracle.
        // unknown — a well-formed but unknown 43-char base64url id
        given().`when`().get("/api/shares/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/terms")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        // disabled (revoked)
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        seedTerms(scopeId, de = "## Regeln", en = "## Rules")
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId = createShare(userId, memory)
        given().`when`().get("/api/shares/$shareId/terms").then().statusCode(200) // live first
        given().header("Authorization", bearer(userId)).`when`().post("/api/shares/$shareId/revoke").then().statusCode(204)
        given().`when`().get("/api/shares/$shareId/terms")
            .then().statusCode(404).body("code", equalTo("share_not_found"))

        // expired
        val past = Instant.now().minusSeconds(3600).toString()
        val expiredId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$memory","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName","expiresAt":"$past"}""")
            .`when`().post("/api/shares").then().statusCode(201).extract().path<String>("shareId")
        given().`when`().get("/api/shares/$expiredId/terms")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    // --- helpers ---

    /** Publish terms (version 1) on the tenant that owns [scopeId], the direct-entity seed. Returns the tenantId
     *  (the value the /terms endpoint must return). `termsMarkdownByLocale` is the serialized locale→markdown map. */
    private fun seedTerms(scopeId: String, de: String, en: String): String =
        QuarkusTransaction.requiringNew().call {
            val tenant = TenantEntity.findById(ScopeEntity.findById(scopeId)!!.tenantId)!!
            tenant.termsMarkdownByLocale = """{"de":"$de","en":"$en"}"""
            tenant.termsVersion = 1
            tenant.persist()
            tenant.id
        }

    private fun createShare(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")

    /** Seed one media (blob + row) attached to a node. Returns (mediaId, blobId). */
    private fun seedMedia(scopeId: String, nodeId: String): Pair<String, String> {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; createdAt = Instant.now()
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                createdAt = Instant.now()
            }.persist()
        }
        return mediaId to blobId
    }
}
