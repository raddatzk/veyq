package com.veyq.memories.media

import com.veyq.core.testing.TestBlob
import com.veyq.core.blob.UploadSessionEntity
import com.veyq.core.blobstore.FakePresigningBlobStore
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
 *
 * **Split from `MediaPresignDomainSliceTest`** (cr-platform-gradle-modules step 8a): these methods need Memories in the application at runtime, so they live in
 * `:apps:memories`; the rest of the class is `MediaPresignDomainSliceTest` in `:core`. The bodies moved verbatim.
 */
@QuarkusTest
@TestProfile(MediaPresignDomainMemoriesSliceTest.FakePresignProfile::class)
class MediaPresignDomainMemoriesSliceTest {

    class FakePresignProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FakePresigningBlobStore::class.java)
    }

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

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

    /** A bare ACTIVE tenant row whose only job is to claim a domain; returns that domain. */
    private fun seedTenantDomain(): String {
        val domain = "pd-${UUID.randomUUID()}.example.com"
        seedTenant(domain = domain, tenantScope = false)
        return domain
    }
}
