package com.veyq.app.shared.blobstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.net.URI

/**
 * Presigning is **per-request, not per-instance** (ADR 0063, amending ADR 0013 §4): a URL is signed for the
 * origin the browser is actually on, so a second tenant's media does not silently receive a URL for the *first*
 * tenant's origin — a different origin, where ADR 0013's whole no-CORS same-origin trick stops applying.
 *
 * No Quarkus and no network: presigning is local HMAC over an endpoint, which is exactly the part under test
 * here. The complementary half — that the domain reaching this class is the **resolved tenant's own**, never a
 * `Host` header echoed back — is a request-path property and lives in [MediaPresignDomainSliceTest].
 */
class S3BlobStorePresigningTest {

    @Test
    fun twoTenantsSigningTheSameKeyGetTwoDifferentHosts() {
        // The defect this CR closes, as a test. One key, one store, two domains — before this, the second tenant
        // received the first's origin. Asserted on the host component specifically: the paths and the query are
        // *supposed* to differ too (a different host means a different signature), and asserting "the URLs
        // differ" would pass on that alone while the origin stayed wrong.
        val store = sameOriginStore()

        val a = URI.create(store.presignPut(KEY, "a.example.com"))
        val b = URI.create(store.presignPut(KEY, "b.example.com"))

        assertEquals("a.example.com", a.host)
        assertEquals("b.example.com", b.host)
        assertNotEquals(a.host, b.host)
    }

    @Test
    fun anUnresolvedRequestSignsAgainstTheBootstrapDefault() {
        // `null` is "this request resolved to no tenant" — the bootstrap domain, or a Host that claims none. It
        // must fall back to the configured public endpoint and never to something derived from the request.
        val store = sameOriginStore()
        assertEquals("veyq.example.org", URI.create(store.presignPut(KEY, null)).host)
        assertEquals("veyq.example.org", URI.create(store.presignGet(KEY, null)).host)
    }

    @Test
    fun theSchemeAndPortOfTheConfiguredEndpointSurviveTheDomainSwap() {
        // Only the host is per-tenant. Scheme and port are properties of how this deployment exposes the store,
        // not of who is asking — swapping the whole endpoint would sign for a port the proxy does not listen on.
        val store = S3BlobStore(
            s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
            internalEndpoint = "http://garage:3900", publicEndpoint = "https://veyq.example.org:8443",
            credentials = CREDENTIALS, region = "us-east-1",
        )
        val url = URI.create(store.presignPut(KEY, "tenant.example.com"))
        assertEquals("https", url.scheme)
        assertEquals("tenant.example.com", url.host)
        assertEquals(8443, url.port)
    }

    @Test
    fun withNoPublicEndpointTheDomainIsIgnored() {
        // ADR 0013 §4's other branch: an external S3 (or any store already publicly reachable at its own
        // hostname) is signed against the internal endpoint, and a tenant domain has nothing to do with it —
        // substituting one would sign for a bucket host that does not exist. The negative control is the test
        // above: the same call *does* swap the host once a public endpoint is configured.
        val store = S3BlobStore(
            s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
            internalEndpoint = "https://s3.eu-central-1.amazonaws.com", publicEndpoint = "",
            credentials = CREDENTIALS, region = "eu-central-1",
        )
        assertEquals("s3.eu-central-1.amazonaws.com", URI.create(store.presignPut(KEY, "tenant.example.com")).host)
    }

    @Test
    fun repeatedSigningForOneDomainStaysStableAcrossCallsAndKeys() {
        // The presigner is cached per resolved endpoint; the cache must not leak one domain's presigner into
        // another's answer. Interleaved on purpose — a cache keyed on "the last domain seen" passes a sequential
        // A,A,B,B and fails this.
        val store = sameOriginStore()
        assertEquals("a.example.com", URI.create(store.presignPut(KEY, "a.example.com")).host)
        assertEquals("b.example.com", URI.create(store.presignGet(KEY, "b.example.com")).host)
        assertEquals("a.example.com", URI.create(store.presignGet("$KEY/1", "a.example.com")).host)
        assertEquals("b.example.com", URI.create(store.presignPut("$KEY/1", "b.example.com")).host)
    }

    private fun sameOriginStore() = S3BlobStore(
        s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
        internalEndpoint = "http://garage:3900", publicEndpoint = "https://veyq.example.org",
        credentials = CREDENTIALS, region = "us-east-1",
    )

    private companion object {
        const val KEY = "storage-key/0"
        const val BUCKET = "veyq"
        val CREDENTIALS: StaticCredentialsProvider =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret"))

        /**
         * `stat`/`delete` are the only methods that would touch it, and neither is on this path — presigning is
         * local HMAC and never connects (ADR 0013 §4). A real `S3Client.builder()` would work too; this makes
         * "no network happens here" a property of the fixture rather than a hope.
         */
        val NoS3Client = object : software.amazon.awssdk.services.s3.S3Client {
            override fun serviceName() = "s3"
            override fun close() = Unit
        }
    }
}
