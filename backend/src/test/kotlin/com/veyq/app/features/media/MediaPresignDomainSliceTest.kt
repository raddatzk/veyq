package com.veyq.app.features.media

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Where a presigned URL's origin comes from (ADR 0063, amending ADR 0013 §4): **the tenant the request resolved
 * to**, looked up in the `tenant` table — not the instance-wide default, and not the `Host` header echoed back.
 *
 * [S3BlobStorePresigningTest] owns the signing half (a different endpoint really produces a different signed
 * host). This class owns the half that only exists over a real request: which domain is chosen, and what happens
 * when the header names one that no tenant claims. That negative — [aHostNoTenantClaimsFallsBackToTheDefault] —
 * is the reason the resolution goes through the database at all, and it is the assertion that separates this
 * implementation from the one that merely *looks* equivalent (`headers.getHeaderString("Host")`): both are
 * identical on the happy path, and only one of them refuses to sign for a name the caller invented.
 *
 * [FakePresigningBlobStore] echoes the domain it was handed into the URL's host, so the assertions read the
 * value the store actually received rather than a proxy for it.
 */
@QuarkusTest
@TestProfile(MediaPresignDomainSliceTest.FakePresignProfile::class)
class MediaPresignDomainSliceTest {

    class FakePresignProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FakePresigningBlobStore::class.java)
    }

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun twoTenantDomainsSignTheSameScopesUploadsForTwoDifferentOrigins() {
        // One scope, one member, one request body: the *only* thing that varies is the domain the request
        // arrived on — which is what makes this about the presigning origin and nothing else. Before this CR
        // both answers carried the single instance-wide endpoint.
        val (userId, scopeId) = seedMemberScope()
        val a = seedTenantDomain()
        val b = seedTenantDomain()

        val urlA = URI.create(startUpload(userId, scopeId, host = a))
        val urlB = URI.create(startUpload(userId, scopeId, host = b))

        assertEquals(a, urlA.host)
        assertEquals(b, urlB.host)
        assertNotEquals(urlA.host, urlB.host)
    }

    @Test
    fun aHostNoTenantClaimsFallsBackToTheDefault() {
        // The security property, and the reason `RequestTenant` hands over the tenant row's domain, never a header.
        // `X-Forwarded-Host` is caller-controlled on any deployment whose proxy does not strip it; a server that
        // signed for whatever it was handed would mint URLs for an attacker-named origin. Resolving through the
        // `tenant` table means an unclaimed name resolves to nothing and signs against the bootstrap default.
        val (userId, scopeId) = seedMemberScope()
        val claimed = seedTenantDomain()
        val spoofed = "attacker-${UUID.randomUUID()}.example.com" // deliberately NOT a tenant row

        val url = URI.create(startUpload(userId, scopeId, host = spoofed))

        assertNotEquals(spoofed, url.host, "a Host no tenant claims must never reach the signer")
        assertEquals("fake-presign.invalid", url.host, "…it signs against the default instead")
        // The positive control on the same predicate: a *registered* domain does come through, so the assertion
        // above is about the row not existing and not about the header being ignored wholesale.
        assertEquals(claimed, URI.create(startUpload(userId, scopeId, host = claimed)).host)
    }

    @Test
    fun downloadsAreSignedForTheSameResolvedDomainAsUploads() {
        // Both directions or neither: a GET signed for a different origin than the PUT is the same CORS failure
        // one step later, at the point where the user is trying to *see* what they uploaded.
        val (userId, scopeId) = seedMemberScope()
        val domain = seedTenantDomain()
        val uploadId = URI.create(startUpload(userId, scopeId, host = domain)) // upload signed for `domain`

        val blobId = completeAndReadBlobId(userId, scopeId, uploadId)
        val get = given().header("Authorization", token(userId)).header("X-Forwarded-Host", domain)
            .contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200)
            .extract().path<String>("results[0].presignedGets[0].url")

        assertEquals(domain, URI.create(get).host)
    }

    @Test
    fun aSuspendedTenantsHostNeverReachesThePresignerAtAll() {
        // A **negative control**, not a presigning rule. The presigning path deliberately knows nothing about
        // SUSPENDED — it does not have to, because `TenantSuspensionFilter` refuses the request before matching,
        // so `MediaResource` is never entered and there is no domain to sign for. Asserted on the specific
        // observable (403 + `tenant_suspended`) precisely so it reads as the *filter* refusing: if this were
        // ever silently handled inside the presigning path instead, the code would answer something else here
        // and `TenantSuspendSliceTest` — not this class — is where the real regression would surface.
        val (userId, scopeId) = seedMemberScope()
        val domain = seedTenantDomain()
        assertEquals(domain, URI.create(startUpload(userId, scopeId, host = domain)).host) // control: it routes

        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1 where domain = ?2", TenantEntity.SUSPENDED, domain)
        }

        given().header("Authorization", token(userId)).header("X-Forwarded-Host", domain)
            .contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":1,"byteSize":16,"wrappedBlobKey":"d3JhcA=="}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(403).body("code", org.hamcrest.CoreMatchers.equalTo("tenant_suspended"))
    }

    // --- helpers ---

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    /** Start a one-chunk upload over [host] and return its first presigned PUT URL. */
    private fun startUpload(userId: String, scopeId: String, host: String): String =
        given().header("Authorization", token(userId)).header("X-Forwarded-Host", host)
            .contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":1,"byteSize":16,"wrappedBlobKey":"d3JhcA=="}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .extract().path("results[0].presignedPuts[0].url")

    /**
     * The download path needs a committed blob, and `complete` verifies the declared size against the store —
     * so the fake's `stat` is scripted to the declared 16 bytes first. Returns the new blob's id.
     */
    private fun completeAndReadBlobId(userId: String, scopeId: String, uploadUrl: URI): String {
        // The fake echoes the key into the URL path: /put/<storageKey>/<index>.
        val key = uploadUrl.path.removePrefix("/put/")
        fakeStore.statOf(key, 16)
        val uploadId = QuarkusTransaction.requiringNew().call {
            UploadSessionEntity.find("storageKey", key.substringBeforeLast('/')).firstResult()!!.id
        }
        val memory = seedMemoryNode(scopeId)
        return given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$memory","originalUploadId":"$uploadId","derivatives":[]}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .extract().path("results[0].blobId")
    }

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

    private fun seedMemoryNode(scopeId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            com.veyq.app.features.content.NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId
                kind = com.veyq.app.features.content.NodeEntity.MEMORY
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    /** A bare ACTIVE tenant row whose only job is to claim a domain; returns that domain. */
    private fun seedTenantDomain(): String {
        val tenantId = UUID.randomUUID().toString()
        val domain = "pd-$tenantId.example.com"
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; this.domain = domain
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
        }
        return domain
    }

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "pdt-$tenantId.example.com"
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
                id = userId; email = "pd-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
