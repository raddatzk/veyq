package com.veyq.core.blob

import com.veyq.core.testing.TestBlob
import com.veyq.core.testing.TestFixtures.bearer
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedTenant
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

/**
 * [com.veyq.core.config.VeyqConfig.SigningMode.STORE_ORIGIN] over a **real request** (ADR 0067 Decision 5):
 * a store reached at an origin of its own — ADR 0064's Kubernetes Ingress host, a tenant on their own bucket —
 * is signed for that one origin, for every tenant and for every `Host`.
 *
 * **Why this cannot be a unit test, given [com.veyq.core.blobstore.S3BlobStorePresigningTest] already
 * covers the mode.** That class calls the signer with a domain it chose itself. The property A3 states is about
 * a *request*: what the `Host` header can and cannot make the server do. Reaching that needs the whole chain
 * live — `TenantHostResolver` reading the `tenant` table, `BlobUploadHandler` handing what it found to the store, and
 * a **real** [com.veyq.core.blobstore.S3BlobStore] built by [com.veyq.core.blobstore.BlobStoreProducer]
 * from this profile's config. `MediaPresignDomainSliceTest` deliberately runs against
 * `FakePresigningBlobStore`, which echoes whatever domain it is handed and therefore cannot see a signing mode
 * at all; a store-origin assertion there would pass against an unimplemented mode.
 *
 * No network: the S3 client is built against an internal endpoint that resolves nowhere, and nothing on the
 * upload-*start* path connects — presigning is local HMAC (ADR 0013 §4) and `stat` is a `complete`-path call.
 * The `.invalid` TLD is chosen so that a regression which *did* connect fails fast and loudly instead of
 * hanging on a DNS lookup that might succeed on somebody's network.
 */
@QuarkusTest
@TestProfile(StoreOriginPresignSliceTest.StoreOriginProfile::class)
class StoreOriginPresignSliceTest {

    /**
     * Pure config overrides, so the `BlobStore` the suite injects is the real one: `RecordingBlobStore` wins the
     * injection point everywhere and delegates to `BlobStoreProducer.blobStore()`, which builds an
     * `S3BlobStore` as soon as an endpoint is configured (the same seam `BlobBatchE2ETest` uses).
     */
    class StoreOriginProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "veyq.blob-store.endpoint" to INTERNAL_ENDPOINT,
            "veyq.blob-store.public-endpoint" to "https://$STORE_HOST",
            "veyq.blob-store.signing-mode" to "store-origin",
            "veyq.blob-store.access-key" to "access",
            "veyq.blob-store.secret-key" to "secret",
            "veyq.blob-store.bucket" to BUCKET,
        )
    }

    @Test
    fun aHostNoTenantClaimsCannotChooseTheOriginInStoreOriginMode() {
        // A3, in the mode that is new here and therefore the one that could weaken it. The lever is the header,
        // and the assertion is the **signed host** — not that the call succeeded, and not that the two URLs
        // match each other: a signer that had started echoing headers would match itself perfectly.
        val (userId, scopeId) = seedMemberScope()
        val claimed = seedTenantDomain()
        val spoofed = "attacker-${UUID.randomUUID()}.example.com" // deliberately NOT a tenant row

        val fromSpoofed = URI.create(startUpload(userId, scopeId, host = spoofed))
        val fromClaimed = URI.create(startUpload(userId, scopeId, host = claimed))

        assertNotEquals(spoofed, fromSpoofed.host, "a Host no tenant claims must never reach the signed URL")
        assertEquals(STORE_HOST, fromSpoofed.host, "…and the store's own origin is what is signed instead")
        // The registered domain gets the same answer, which is the mode: a store on its own address serves every
        // tenant from one host, so even a *legitimate* domain cannot move the origin here.
        assertNotEquals(claimed, fromClaimed.host)
        assertEquals(STORE_HOST, fromClaimed.host)
    }

    @Test
    fun theSignedUrlIsARealPresignedUrlForTheConfiguredBucket() {
        // The control that keeps the case above from passing vacuously. Every assertion there is a *negative*
        // about the host, and a store that had quietly become the in-memory stub, or a handler that returned a
        // placeholder, could satisfy them without signing anything. A SigV4 query and the bucket-and-key path
        // are only produced by the real S3BlobStore this profile is here to build.
        val (userId, scopeId) = seedMemberScope()
        val url = URI.create(startUpload(userId, scopeId, host = seedTenantDomain()))

        assertTrue(url.path.startsWith("/$BUCKET/"), "path-style bucket expected, got ${url.path}")
        assertTrue(url.path.endsWith("/0"), "the first chunk's object key expected, got ${url.path}")
        assertTrue(url.query.orEmpty().contains("X-Amz-Signature="), "expected a SigV4-signed URL, got ${url.query}")
    }

    @Test
    fun twoTenantDomainsSignTheSameScopesUploadsForOneOrigin() {
        // The request-altitude mirror of `MediaPresignDomainSliceTest`'s
        // `twoTenantDomainsSignTheSameScopesUploadsForTwoDifferentOrigins`, and the reason the mode exists: on a
        // store with its own address the per-tenant host is the *defect*, since only one of those hosts serves
        // the bucket. Asserted on each host by name — "the two are equal" is also true of two identically wrong
        // ones.
        val (userId, scopeId) = seedMemberScope()
        val a = seedTenantDomain()
        val b = seedTenantDomain()

        assertEquals(STORE_HOST, URI.create(startUpload(userId, scopeId, host = a)).host)
        assertEquals(STORE_HOST, URI.create(startUpload(userId, scopeId, host = b)).host)
    }

    // --- helpers (the seeding shape of MediaPresignDomainSliceTest; this class needs its own profile) ---

    /** Start a one-chunk upload over [host] and return its first presigned PUT URL. */
    private fun startUpload(userId: String, scopeId: String, host: String): String =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", host)
            .contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .extract().path("results[0].presignedPuts[0].url")

    /** A bare ACTIVE tenant row whose only job is to claim a domain; returns that domain. */
    private fun seedTenantDomain(): String {
        val domain = "so-${UUID.randomUUID()}.example.com"
        seedTenant(domain = domain, tenantScope = false)
        return domain
    }

    private companion object {
        const val STORE_HOST = "store.example.net"
        const val BUCKET = "veyq"

        /** Resolves nowhere on purpose — see the class KDoc: nothing on this path may connect. */
        const val INTERNAL_ENDPOINT = "http://garage.internal.invalid:3900"
    }
}
