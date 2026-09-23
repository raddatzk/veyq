package com.veyq.app.features.media

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * Opt-in byte-transport e2e (ADR 0013) — exercises the **one path the hermetic [MediaSliceTest] cannot**: the
 * presigned direct-to-store transfer against a **real S3-compatible store** (the bundled Garage). Gated on
 * `VEYQ_S3_E2E=1` (default `gradle test` skips it cleanly); the [S3Profile] points the backend at the store on
 * `:19000`. Flow: start → PUT each sealed chunk to its presigned URL → complete (the backend stats the objects,
 * declare-then-verify) → list → presign GET → fetch each chunk back → assert byte-identical. Bytes here are
 * opaque (the crypto round-trip is the client suites' job); this proves transport + stat + manifest.
 *
 * It also reproduces the ADR 0013 §6 matrix — **store responses are inert, enforced by the signer** — against
 * the real store, which is the only place the signed header's VALUE and the override's un-strippability can be
 * seen ([com.veyq.app.shared.blobstore.S3BlobStorePresigningTest] pins the URL shape): a `text/html` PUT → 403,
 * a GET → `application/octet-stream` + `attachment`, and that GET with its override removed → 403.
 *
 * Bring the store up with `docker compose -f infra/docker-compose.yml --profile s3 up -d` (see infra README).
 */
@QuarkusTest
@TestProfile(BlobBatchE2ETest.S3Profile::class)
@EnabledIfEnvironmentVariable(named = "VEYQ_S3_E2E", matches = "1")
class BlobBatchE2ETest {

    class S3Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "veyq.blob-store.endpoint" to (System.getenv("VEYQ_S3_ENDPOINT") ?: "http://localhost:19000"),
            "veyq.blob-store.access-key" to (System.getenv("VEYQ_S3_ACCESS_KEY") ?: DEV_KEY_ID),
            "veyq.blob-store.secret-key" to (System.getenv("VEYQ_S3_SECRET_KEY") ?: DEV_SECRET),
            "veyq.blob-store.bucket" to (System.getenv("VEYQ_S3_BUCKET") ?: "veyq"),
            "veyq.blob-store.region" to (System.getenv("VEYQ_S3_REGION") ?: "us-east-1"),
        )
    }

    @Inject
    lateinit var sessions: SessionService

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun uploadThenDownloadRoundTripsBytes() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)

        // two arbitrary "sealed chunk" payloads (opaque ciphertext bytes as far as the store is concerned)
        val chunks = listOf("chunk-zero-bytes".toByteArray(), "chunk-one-bytes!!".toByteArray())
        val byteSize = chunks.sumOf { it.size }.toLong()

        // start → presigned PUT per chunk
        val start = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":${chunks.size},"byteSize":$byteSize,"wrappedBlobKey":"d3JhcA=="}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[0].presignedPuts.size()", equalTo(chunks.size))
            .extract()
        val uploadId: String = start.path("results[0].uploadId")
        val putUrls = chunks.indices.map { i -> start.path<String>("results[0].presignedPuts[$i].url") }

        // §6: the PUT signs `content-type: application/octet-stream`, so a planted `text/html` is refused. The
        // control is the SAME URL with the pinned type, which must succeed — a 403 from an expired or mis-signed
        // URL would satisfy the negative alone.
        assertEquals(403, put(putUrls[1], chunks[1], "text/html"), "a text/html PUT must be refused")
        chunks.forEachIndexed { i, bytes -> assertEquals(200, put(putUrls[i], bytes, PINNED), "PUT chunk $i") }

        // An object stored BEFORE the rule, with a renderable type — written over chunk 1 through the signer as it
        // was. Without it every object here is stored as `application/octet-stream` and the served type below
        // would be right by accident rather than by the GET override.
        plantLegacyHtml(URI.create(putUrls[1]), chunks[1])

        // complete → backend stats the objects (declare-then-verify), attaches the media
        val blobId = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$uploadId","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", notNullValue())
            .body("results[0].error", org.hamcrest.CoreMatchers.nullValue())
            .extract().path<String>("results[0].blobId")

        // list → the manifest records the real per-chunk sizes
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media[0].blobId", equalTo(blobId))
            .body("media[0].manifest.size()", equalTo(chunks.size))

        // presign GET → fetch each chunk back → byte-identical
        val dl = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200)
            .body("results[0].presignedGets.size()", equalTo(chunks.size))
            .extract()
        chunks.forEachIndexed { i, expected ->
            val url: String = dl.path("results[0].presignedGets[$i].url")
            val res = get(url)
            assertEquals(200, res.statusCode(), "GET chunk $i")
            assertArrayEquals(expected, res.body(), "chunk $i bytes")
            // §6: served inert whatever it was stored as — chunk 1 is the planted `text/html`
            assertEquals(PINNED, res.headers().firstValue("Content-Type").orElse(null), "chunk $i type")
            assertEquals("attachment", res.headers().firstValue("Content-Disposition").orElse(null), "chunk $i disposition")
        }

        // §6: the override is part of the signature, so a URL holder cannot strip it to get the stored type back.
        // The control is the untouched URL above, which served 200.
        val legacyUrl: String = dl.path("results[0].presignedGets[1].url")
        val params = URI.create(legacyUrl).rawQuery.split('&')
        val kept = params.filterNot { it.startsWith("response-content-type=") }
        assertEquals(params.size - 1, kept.size, "the lever must remove exactly the override")
        val stripped = legacyUrl.substringBefore('?') + "?" + kept.joinToString("&")
        assertEquals(403, get(stripped).statusCode(), "a GET with its override removed must be refused")
    }

    private fun put(url: String, bytes: ByteArray, contentType: String): Int = http.send(
        HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    private fun get(url: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    /**
     * Overwrite the object a presigned URL names (path-style: `/<bucket>/<key>`) with a `text/html` copy, through
     * the signer as it was before ADR 0013 §6 — a PUT signing only `host`, a GET with no override. That is the
     * matrix's first two rows, and each is asserted: they are what makes the plant land, and what makes the
     * backend's GET above the thing that neutralises it. A presigner and not `S3Client.putObject`: the SDK's
     * default payload checksum is refused by Garage v2.0.0 (`400 Invalid payload signature`).
     */
    private fun plantLegacyHtml(presigned: URI, bytes: ByteArray) {
        val key = presigned.path.removePrefix("/").substringAfter('/')
        val ttl = java.time.Duration.ofMinutes(5)
        legacyPresigner().use { signer ->
            val putUrl = signer.presignPutObject { it.signatureDuration(ttl).putObjectRequest { r -> r.bucket(bucket()).key(key) } }
            assertEquals(200, put(putUrl.url().toString(), bytes, "text/html"), "§6 row 1: a host-only PUT stores any type")
            val getUrl = signer.presignGetObject { it.signatureDuration(ttl).getObjectRequest { r -> r.bucket(bucket()).key(key) } }
            val served = get(getUrl.url().toString()).headers().firstValue("Content-Type").orElse(null)
            assertEquals("text/html", served, "§6 row 2: with no override the store serves the stored type")
        }
    }

    private fun seedNode(scopeId: String, kind: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; this.kind = kind
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun seedMemberScope(): Pair<String, String> {
        val userId = UUID.randomUUID().toString()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
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

    companion object {
        // Deterministic dev credentials the bundled Garage imports (infra/docker-compose.yml --profile s3).
        const val DEV_KEY_ID = "GK0123456789abcdef01234567"
        const val DEV_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** The one type a presigned PUT signs and a presigned GET is served as (ADR 0013 §6). */
        const val PINNED = "application/octet-stream"

        private fun bucket() = System.getenv("VEYQ_S3_BUCKET") ?: "veyq"

        private fun endpoint() = java.net.URI.create(System.getenv("VEYQ_S3_ENDPOINT") ?: "http://localhost:19000")
        private fun region() = software.amazon.awssdk.regions.Region.of(System.getenv("VEYQ_S3_REGION") ?: "us-east-1")
        private fun pathStyle() = software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()
        private fun creds() = software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                System.getenv("VEYQ_S3_ACCESS_KEY") ?: DEV_KEY_ID,
                System.getenv("VEYQ_S3_SECRET_KEY") ?: DEV_SECRET,
            ),
        )

        private fun devS3(): software.amazon.awssdk.services.s3.S3Client = software.amazon.awssdk.services.s3.S3Client.builder()
            .endpointOverride(endpoint()).region(region()).credentialsProvider(creds()).serviceConfiguration(pathStyle()).build()

        private fun legacyPresigner(): software.amazon.awssdk.services.s3.presigner.S3Presigner =
            software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .endpointOverride(endpoint()).region(region()).credentialsProvider(creds()).serviceConfiguration(pathStyle()).build()

        /**
         * Best-effort bucket create before the suite (the bundled Garage init already creates `veyq`; this is a
         * safety net for a bare store where the key may create buckets). Swallows already-exists / no-permission.
         */
        @JvmStatic
        @BeforeAll
        fun ensureBucket() {
            runCatching { devS3().use { it.createBucket { b -> b.bucket(bucket()) } } }
        }
    }
}
