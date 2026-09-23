package com.veyq.app.shared.blobstore

import com.veyq.app.shared.config.VeyqConfig.SigningMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.net.URI
import java.net.URLDecoder

/**
 * Presigning is **per-request, not per-instance** (ADR 0063, amending ADR 0013 §4): a URL is signed for the
 * origin the browser is actually on, so a second tenant's media does not silently receive a URL for the *first*
 * tenant's origin — a different origin, where ADR 0013's whole no-CORS same-origin trick stops applying.
 *
 * No Quarkus and no network: presigning is local HMAC over an endpoint, which is exactly the part under test
 * here. The complementary half — that the domain reaching this class is the **resolved tenant's own**, never a
 * `Host` header echoed back — is a request-path property and lives in [MediaPresignDomainSliceTest].
 *
 * **Three signing modes, and every case below names the one it is about** (ADR 0067 Decision 5). The
 * [SigningMode.TENANT_DOMAIN] cases construct no mode at all, deliberately: they are the pre-existing ones,
 * untouched, and their staying green *is* the claim that no deployment moved. Which mode the config default
 * actually binds to is a different question and lives in [S3BlobStoreSigningModeConfigTest] — a constructor
 * default cannot prove that one.
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

    @Test
    fun inStoreOriginModeTwoTenantsSignAgainstTheSameStoreHost() {
        // The mirror of [twoTenantsSigningTheSameKeyGetTwoDifferentHosts], and the defect this CR closes: a
        // store on an origin of its own (ADR 0064's Ingress host, a tenant's own bucket) has one host for
        // everybody, so a per-tenant swap signs for an origin that does not serve the bucket. Asserted on the
        // host of each URL, not on "the two are equal" — two URLs signed for the same *wrong* host are equal too.
        val store = storeOriginStore()

        val a = URI.create(store.presignPut(KEY, "a.example.com"))
        val b = URI.create(store.presignPut(KEY, "b.example.com"))

        assertEquals("store.example.net", a.host)
        assertEquals("store.example.net", b.host)
    }

    @Test
    fun inStoreOriginModeNoDomainAtAllCanMoveTheSignedHost() {
        // A3's security property one step stronger than [SigningMode.TENANT_DOMAIN] can state it. There, an
        // unclaimed `Host` is stopped by resolving to null; here *no* value — a registered domain, a null, or
        // something shaped like an attacker's guess that reached this method anyway — moves the origin, because
        // the mode does not read the argument at all. Anchored on the signed host, never on "it did not throw":
        // a signer that silently produced the internal endpoint would also not throw.
        val store = storeOriginStore()
        for (domain in listOf(null, "a.example.com", "attacker.evil.example", "store.example.net")) {
            assertEquals("store.example.net", URI.create(store.presignPut(KEY, domain)).host, "domain=$domain")
            assertEquals("store.example.net", URI.create(store.presignGet(KEY, domain)).host, "domain=$domain")
        }
    }

    @Test
    fun inStoreOriginModeTheSchemeAndPortOfTheStoreOriginAreKept() {
        // `public-endpoint` verbatim means verbatim: a store Ingress on a non-default port is signed for that
        // port. The sibling of [theSchemeAndPortOfTheConfiguredEndpointSurviveTheDomainSwap], which pins the
        // same property for the mode that *does* rewrite the URL.
        val store = S3BlobStore(
            s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
            internalEndpoint = "http://garage.veyq.svc.cluster.local:3900",
            publicEndpoint = "https://store.example.net:8443", signingMode = SigningMode.STORE_ORIGIN,
            credentials = CREDENTIALS, region = "us-east-1",
        )
        val url = URI.create(store.presignPut(KEY, "tenant.example.com"))
        assertEquals("https", url.scheme)
        assertEquals("store.example.net", url.host)
        assertEquals(8443, url.port)
    }

    @Test
    fun theInternalEndpointModeIsSelectableByNameNotOnlyByBlankingThePublicOne() {
        // The third branch, said out loud. Before this CR it existed only as the *absence* of a public
        // endpoint, which is why A5 could describe a case the code could not serve: an operator who wants the
        // backend's own endpoint signed had exactly one way to ask for it, and it was to delete a different
        // setting. Here a public endpoint IS configured and is deliberately not what gets signed — an
        // assertion that is impossible to satisfy by accident from the blank branch.
        val store = S3BlobStore(
            s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
            internalEndpoint = "https://s3.eu-central-1.amazonaws.com",
            publicEndpoint = "https://ignored.example.net", signingMode = SigningMode.INTERNAL_ENDPOINT,
            credentials = CREDENTIALS, region = "eu-central-1",
        )
        assertEquals("s3.eu-central-1.amazonaws.com", URI.create(store.presignPut(KEY, "tenant.example.com")).host)
        assertEquals("s3.eu-central-1.amazonaws.com", URI.create(store.presignGet(KEY, null)).host)
    }

    @Test
    fun aBlankPublicEndpointIsTheInternalModeWhateverTheConfiguredModeSays() {
        // The one combination that cannot mean what it says: [SigningMode.STORE_ORIGIN] over a blank
        // `public-endpoint` has no origin to sign verbatim. It resolves to the internal endpoint rather than to
        // the empty string — which would be a `URI.create("")` deep inside the presigner builder, i.e. a boot
        // that survives and an upload that dies. [withNoPublicEndpointTheDomainIsIgnored] pins the same
        // normalisation from the default mode's side; this one pins that the mode cannot escape it.
        val store = S3BlobStore(
            s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
            internalEndpoint = "https://s3.eu-central-1.amazonaws.com", publicEndpoint = "",
            signingMode = SigningMode.STORE_ORIGIN, credentials = CREDENTIALS, region = "eu-central-1",
        )
        assertEquals("s3.eu-central-1.amazonaws.com", URI.create(store.presignPut(KEY, "tenant.example.com")).host)
    }

    @Test
    fun everyPresignedGetSignsTheInertResponseOverrides() {
        // ADR 0013 §6: nothing the store serves is renderable, and the SIGNER guarantees it — so the rule holds on
        // a store veyq never provisions, and on objects stored before it. Both overrides ride in the signed query
        // (the real store 403s a URL with one removed, which `BlobBatchE2ETest` measures), so asserting them here
        // is asserting what the store will serve. Every mode, because the rule is not a property of any one of them.
        for ((mode, store) in allModes()) {
            val query = queryOf(store.presignGet(KEY, "tenant.example.com"))
            assertEquals("application/octet-stream", query["response-content-type"], "mode=$mode")
            assertEquals("attachment", query["response-content-disposition"], "mode=$mode")
        }
    }

    @Test
    fun everyPresignedPutSignsTheContentTypeHeader() {
        // ADR 0013 §6's other half: the uploader no longer chooses the stored type. A presigned PUT carries no header
        // VALUES, only the names it signed — so this pins that `content-type` is signed at all (unsigned, a store
        // ignores whatever the client sends and stores it as sent). WHICH value is signed is a property only the
        // store can show: `BlobBatchE2ETest` sends `text/html` to such a URL and gets its 403.
        for ((mode, store) in allModes()) {
            val signed = queryOf(store.presignPut(KEY, "tenant.example.com"))["X-Amz-SignedHeaders"]
            assertTrue(signed.orEmpty().split(';').contains("content-type"), "mode=$mode signed=$signed")
        }
    }

    /** One store per [SigningMode], each with a public endpoint so no mode is folded into another. */
    private fun allModes(): Map<SigningMode, S3BlobStore> = SigningMode.entries.associateWith { mode ->
        S3BlobStore(
            s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
            internalEndpoint = "http://garage:3900", publicEndpoint = "https://veyq.example.org",
            signingMode = mode, credentials = CREDENTIALS, region = "us-east-1",
        )
    }

    private fun queryOf(url: String): Map<String, String> =
        URI.create(url).rawQuery.split('&').associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }

    /** A store reached at an origin of its own, which is **not** the endpoint the backend uses — ADR 0064's
     *  Kubernetes shape, where the browser gets an Ingress host and the backend a cluster-internal Service. */
    private fun storeOriginStore() = S3BlobStore(
        s3 = NoS3Client, bucket = BUCKET, presignTtlSeconds = 300,
        internalEndpoint = "http://garage.veyq.svc.cluster.local:3900",
        publicEndpoint = "https://store.example.net", signingMode = SigningMode.STORE_ORIGIN,
        credentials = CREDENTIALS, region = "us-east-1",
    )

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
