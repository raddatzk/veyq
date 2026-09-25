package com.veyq.app.features.sharing

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.media.BlobEntity
import com.veyq.app.features.media.MediaEntity
import com.veyq.app.features.media.TestBlob
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.blobstore.FakePresigningBlobStore
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * The **guest** half of per-request presigning (ADR 0063). `MediaPresignDomainSliceTest` proves the mechanism on
 * the authenticated media paths; this proves the third presign call site — the accountless share blob read — is
 * wired to it too, which no amount of correctness in the shared bean would establish on its own.
 *
 * It matters more here than anywhere else, not less: a public share is opened by someone with no account, on the
 * tenant's own domain, in a browser that will refuse a cross-origin fetch it was never given a CORS policy for.
 * The same-origin blob route (ADR 0013 §4) is the *only* reason there is no such policy to configure, and it
 * holds per domain or not at all.
 */
@QuarkusTest
@TestProfile(SharePresignDomainSliceTest.FakePresignProfile::class)
class SharePresignDomainSliceTest {

    class FakePresignProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FakePresigningBlobStore::class.java)
    }

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun aGuestsShareBlobIsSignedForTheDomainTheGuestIsOn() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId)
        val (_, blobId) = seedMedia(scopeId, memory)
        val shareId = createShare(userId, memory)
        val a = seedTenantDomain()
        val b = seedTenantDomain()

        // One share, one blob, one anonymous request shape — only the domain differs.
        assertEquals(a, URI.create(guestBlobUrl(shareId, blobId, host = a)).host)
        assertNotEquals(a, URI.create(guestBlobUrl(shareId, blobId, host = b)).host)
        assertEquals(b, URI.create(guestBlobUrl(shareId, blobId, host = b)).host)
    }

    @Test
    fun aGuestOnAHostNoTenantClaimsGetsTheDefaultNotTheirOwnHeader() {
        // Same security property as on the media paths, restated where the caller is *unauthenticated* and so
        // has the least standing of anyone to name the origin the server signs for.
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId)
        val (_, blobId) = seedMedia(scopeId, memory)
        val shareId = createShare(userId, memory)
        val spoofed = "attacker-${UUID.randomUUID()}.example.com"

        val url = URI.create(guestBlobUrl(shareId, blobId, host = spoofed))

        assertNotEquals(spoofed, url.host)
        assertEquals("fake-presign.invalid", url.host)
        // Control on the same predicate: a registered domain does come through this exact path.
        val claimed = seedTenantDomain()
        assertEquals(claimed, URI.create(guestBlobUrl(shareId, blobId, host = claimed)).host)
    }

    // --- helpers ---

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    /** Anonymous — no Authorization header at all, which is the whole point of a public share. */
    private fun guestBlobUrl(shareId: String, blobId: String, host: String): String =
        given().header("X-Forwarded-Host", host)
            .`when`().get("/api/shares/$shareId/blobs/$blobId")
            .then().statusCode(200)
            .extract().path("presignedGets[0].url")

    private fun createShare(userId: String, nodeId: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"VIEWER","wrap":"d3JhcA==","sealedName":"bmFtZQ=="}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")

    private fun seedTenantDomain(): String {
        val tenantId = UUID.randomUUID().toString()
        val domain = "sp-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; this.domain = domain
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
        }
        return domain
    }

    private fun seedNode(scopeId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; kind = NodeEntity.MEMORY
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** One media (blob + row) attached to the node. Returns (mediaId, blobId). */
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

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "spt-$tenantId.example.com"
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
                id = userId; email = "sp-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
