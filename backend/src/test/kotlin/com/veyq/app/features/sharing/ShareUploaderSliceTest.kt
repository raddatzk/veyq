package com.veyq.app.features.sharing

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.blobstore.StoreReclaimEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the **UPLOADER** write-only drop-box share (uc.sharing.share-uploader, cr-0102, ADR 0031)
 * against the in-memory store. Covers the drop-box's two structural guarantees + the delete-own:
 *  - **write-only:** the anonymous resolve of an UPLOADER share serves `media: []` **even with existing media**
 *    (and no identities) — "cannot see others" is enforced by the `canView` gate, never gallery filtering;
 *  - **contribute:** an uploader names itself + uploads, and the media **attaches to the shared root stamped
 *    with its identity** (same path as CONTRIBUTOR);
 *  - **delete-own:** the token-gated `DELETE /media/{id}` removes + reclaims the caller's own media (a single-row
 *    ownership check) — a **foreign identity's** media is a `404`, a **bad token** is a `403`, and a **VIEWER**
 *    share rejects the delete (`403`).
 */
@QuarkusTest
class ShareUploaderSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val sealedUploaderName = "c2VhbGVkLXVwbG9hZGVyLW5hbWU="

    @Test
    fun uploaderResolveServesNoMediaEvenWithExisting() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        seedMedia(scopeId, memory, identityId = null) // a curator's existing photo on the Memory
        val shareId = createShare(userId, memory, role = "UPLOADER")

        // the anonymous resolve of an UPLOADER share is write-only: NO media, NO identities, NO nodes — only the
        // wrap (to seal uploads under K_mem) + the sealed title. The minimal capability an uploader needs.
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("role", equalTo("UPLOADER"))
            .body("wrap", equalTo(wrap))
            .body("sealedName", equalTo(sealedName))
            .body("media.size()", equalTo(0)) // the existing photo is NOT served
            .body("identities.size()", equalTo(0))
            .body("nodes.size()", equalTo(0)) // not even the root node — no K_mem-decryptable node metadata leaks
    }

    @Test
    fun uploaderShareHasNoBlobDownloadCapability() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val mediaId = seedMedia(scopeId, memory, identityId = null) // a curator photo — its blob IS in the subtree
        val blobId = QuarkusTransaction.requiringNew().call { MediaEntity.findById(mediaId)!!.blobId }

        // an UPLOADER share is role-gated at the blob endpoint too — even an in-subtree blob id is a 404 (write-only
        // is a real capability, not just id-secrecy). Contrast: a VIEWER share of the same memory passes the role
        // gate and only fails downstream at the (in-memory) store — a 503, proving the gate is what blocks UPLOADER.
        val uploaderShare = createShare(userId, memory, role = "UPLOADER")
        given().`when`().get("/api/shares/$uploaderShare/blobs/$blobId").then().statusCode(404)

        val viewerShare = createShare(userId, memory, role = "VIEWER")
        given().`when`().get("/api/shares/$viewerShare/blobs/$blobId").then().statusCode(503)
    }

    @Test
    fun uploaderNamesUploadsAttachesStampedAndDeletesOwn() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val shareId = createShare(userId, memory, role = "UPLOADER")

        // name yourself — NO bearer (uploader path is open like CONTRIBUTOR); identity + capability token out
        val idResp = given().contentType(ContentType.JSON)
            .body("""{"sealedDisplayName":"$sealedUploaderName"}""")
            .`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(201)
            .body("identityId", notNullValue()).body("contributorToken", notNullValue())
            .extract()
        val identityId = idResp.path<String>("identityId")
        val contributorToken = idResp.path<String>("contributorToken")

        val uploadId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")

        val mediaId = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"originalUploadId":"$uploadId"}]}""")
            .`when`().post("/api/shares/$shareId/media")
            .then().statusCode(200).extract().path<String>("results[0].mediaId")

        // the media attached to the shared root, stamped with the uploader's identity (verified in the DB — the
        // resolve deliberately serves the uploader nothing)
        QuarkusTransaction.requiringNew().run {
            val m = MediaEntity.findById(mediaId)!!
            assertEquals(memory, m.nodeId)
            assertEquals(identityId, m.shareIdentityId)
        }
        // and it is STILL invisible to the uploader's own resolve
        given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("media.size()", equalTo(0))

        // the key the delete-own must not lose — read while the rows that hold it still exist
        val storageKey = QuarkusTransaction.requiringNew().call {
            BlobEntity.findById(MediaEntity.findById(mediaId)!!.blobId)!!.storageKey
        }

        // delete-own — the token rides the X-Contributor-Token header; the media + its blob are reclaimed
        given().header("X-Contributor-Token", contributorToken)
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            assertNull(MediaEntity.findById(mediaId), "media row reclaimed")
            assertEquals(0, BlobEntity.count("scopeId", scopeId), "blob rows reclaimed")
        }
        assert(QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) } == 1L) {
            "the contributor's own delete must leave a durable reclaim row for the blob it deleted"
        }
        // a second delete of the now-gone media is a non-enumerable 404
        given().header("X-Contributor-Token", contributorToken)
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(404).body("code", equalTo("share_media_not_found"))
    }

    @Test
    fun foreignIdentityDeleteIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val shareId = createShare(userId, memory, role = "UPLOADER")

        // identity A owns a media (seeded), identity B holds a valid token but did not upload it
        val identityA = createIdentity(shareId)
        val (_, tokenB) = createIdentityWithToken(shareId)
        val mediaOfA = seedMedia(scopeId, memory, identityId = identityA)

        given().header("X-Contributor-Token", tokenB)
            .`when`().delete("/api/shares/$shareId/media/$mediaOfA")
            .then().statusCode(404).body("code", equalTo("share_media_not_found"))
        // A's media is untouched
        QuarkusTransaction.requiringNew().run { assertEquals(mediaOfA, MediaEntity.findById(mediaOfA)?.id) }
    }

    @Test
    fun badTokenDeleteIs403() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val shareId = createShare(userId, memory, role = "UPLOADER")
        val mediaId = seedMedia(scopeId, memory, identityId = createIdentity(shareId))

        // a well-formed but never-issued token — rejected before any ownership check
        given().header("X-Contributor-Token", "notARealToken000000000000000000000000000000")
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    @Test
    fun viewerShareRejectsUploadAndDelete() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, parent = scopeId)
        val shareId = createShare(userId, memory, role = "VIEWER")
        val mediaId = seedMedia(scopeId, memory, identityId = null)

        // a VIEWER share accepts neither an identity (upload) nor a delete
        given().contentType(ContentType.JSON).body("""{"sealedDisplayName":"$sealedUploaderName"}""")
            .`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
        given().header("X-Contributor-Token", "notARealToken000000000000000000000000000000")
            .`when`().delete("/api/shares/$shareId/media/$mediaId")
            .then().statusCode(403).body("code", equalTo("share_not_contributable"))
    }

    // --- helpers ---

    private fun createShare(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"$role","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun createIdentity(shareId: String): String = createIdentityWithToken(shareId).first

    private fun createIdentityWithToken(shareId: String): Pair<String, String> {
        val r = given().contentType(ContentType.JSON).body("""{"sealedDisplayName":"$sealedUploaderName"}""")
            .`when`().post("/api/shares/$shareId/identities").then().statusCode(201).extract()
        return r.path<String>("identityId") to r.path<String>("contributorToken")
    }

    private fun seedMedia(scopeId: String, nodeId: String, identityId: String?): String {
        val mediaId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"; createdAt = Instant.now()
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                shareIdentityId = identityId; createdAt = Instant.now()
            }.persist()
        }
        return mediaId
    }

    private fun seedNode(scopeId: String, kind: String, parent: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = parent; this.kind = kind
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = if (kind != NodeEntity.FOLDER) "a21lbQ==" else null; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return userId to scopeId
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
