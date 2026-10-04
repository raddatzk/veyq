package com.veyq.memories.media

import com.veyq.core.testing.TestBlob
import com.veyq.core.blob.UploadSessionEntity
import com.veyq.core.blobstore.FakePresigningBlobStore
import com.veyq.core.tenant.TenantEntity
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import com.veyq.core.testing.TestFixtures.seedTenant
import com.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.net.URI
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

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
        val get = given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain)
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
        // SUSPENDED — it does not have to: on the suspended tenant's own domain `TenantSuspensionFilter` refuses the
        // request before matching, so `MediaResource` is never entered and there is no domain to sign for (from any
        // other domain the scope-access gate answers first, before any presign — `OperatorBlindProbeTest`). Asserted on the specific
        // observable (403 + `tenant_suspended`) precisely so it reads as the *filter* refusing: if this were
        // ever silently handled inside the presigning path instead, the code would answer something else here
        // and `TenantSuspendSliceTest` — not this class — is where the real regression would surface.
        val (userId, scopeId) = seedMemberScope()
        val domain = seedTenantDomain()
        assertEquals(domain, URI.create(startUpload(userId, scopeId, host = domain)).host) // control: it routes

        QuarkusTransaction.requiringNew().run {
            TenantEntity.update("status = ?1 where domain = ?2", TenantEntity.SUSPENDED, domain)
        }

        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain)
            .contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(403).body("code", org.hamcrest.CoreMatchers.equalTo("tenant_suspended"))
    }

    // --- helpers ---

    /** Start a one-chunk upload over [host] and return its first presigned PUT URL. */
    private fun startUpload(userId: String, scopeId: String, host: String): String =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", host)
            .contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .extract().path("results[0].presignedPuts[0].url")

    /**
     * The download path needs a committed blob, and `complete` verifies every chunk's exact length against the
     * store — so the fake's `stat` is staged at the declared shape first. Returns the new blob's id.
     */
    private fun completeAndReadBlobId(userId: String, scopeId: String, uploadUrl: URI): String {
        // The fake echoes the key into the URL path: /put/<storageKey>/<index>.
        val key = uploadUrl.path.removePrefix("/put/")
        TestBlob.SMALL.stage(fakeStore, key.substringBeforeLast('/'))
        val uploadId = QuarkusTransaction.requiringNew().call {
            UploadSessionEntity.find("storageKey", key.substringBeforeLast('/')).firstResult()!!.id
        }
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","derivatives":[]}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .extract().path("results[0].blobId")
    }

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

    /** A bare ACTIVE tenant row whose only job is to claim a domain; returns that domain. */
    private fun seedTenantDomain(): String {
        val domain = "pd-${UUID.randomUUID()}.example.com"
        seedTenant(domain = domain, tenantScope = false)
        return domain
    }
}
