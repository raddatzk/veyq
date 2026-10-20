package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blobstore.BundledStoreCredentials
import me.raddatz.veyq.core.blobstore.StoreSettingFixtures
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in byte-transport e2e (ADR 0013) — exercises the **one path the hermetic [MediaSliceTest] cannot**: the
 * presigned direct-to-store transfer against a **real S3-compatible store** (the bundled Garage). Gated on
 * `VEYQ_S3_E2E=1` (default `gradle test` skips it cleanly); the [S3Profile] points the backend at the store on
 * `:19000`. Flow: start → PUT each sealed chunk to its presigned URL → complete (the backend stats the objects,
 * declare-then-verify) → list → presign GET → fetch each chunk back → assert byte-identical. The chunks are at the
 * lengths the frame size derives, and it reproduces ADR 0013 §3's table: a wrong-length PUT is refused, a
 * same-length re-PUT is not. Bytes here are
 * opaque (the crypto round-trip is the client suites' job); this proves transport + stat + manifest.
 *
 * It also reproduces the ADR 0013 §6 matrix — **store responses are inert, enforced by the signer** — against
 * the real store, which is the only place the signed header's VALUE and the override's un-strippability can be
 * seen ([me.raddatz.veyq.core.blobstore.S3BlobStorePresigningTest] pins the URL shape): a `text/html` PUT → 403,
 * a GET → `application/octet-stream` + `attachment`, and that GET with its override removed → 403.
 *
 * And it moves a blob of more than one presign window through the real store (ADR 0013 §2): `start` signs the
 * first window, `blob-upload-windows` each next one, and every URL is a working PUT for its chunk's exact length.
 *
 * Bring the store up with `docker compose -f infra/docker/docker-compose.yml --profile s3 up -d` (see infra README).
 */
@QuarkusTest
@TestProfile(BlobBatchE2ETest.S3Profile::class)
@EnabledIfEnvironmentVariable(named = "VEYQ_S3_E2E", matches = "1")
class BlobBatchE2ETest {

    class S3Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // A window of two chunks, so a blob of a few frames crosses windows without megabytes of test data.
            "veyq.blob.presign-window" to "$WINDOW",
        )
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun uploadThenDownloadRoundTripsBytes() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)

        // a two-frame blob at its derived sizes — one full frame, then a short last one (ADR 0013 §3). The bytes are
        // opaque ciphertext as far as the store is concerned; only their lengths are the contract's.
        val blob = TestBlob.ofChunks(2)
        val chunks = (0 until blob.chunkCount).map { i -> ByteArray(blob.chunkLength(i).toInt()) { b -> (b * 31 + i).toByte() } }

        // start → presigned PUT per chunk, each signed for its chunk's exact length
        val start = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==")}]}""")
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
        // §3: the PUT signs `content-length`, so a body one byte short is refused. The control is the same URL at
        // the exact length just below, which must succeed.
        assertEquals(403, put(putUrls[0], chunks[0].copyOf(chunks[0].size - 1), PINNED), "a wrong-length PUT must be refused")
        chunks.forEachIndexed { i, bytes -> assertEquals(200, put(putUrls[i], bytes, PINNED), "PUT chunk $i") }
        // §3's last row: the same URL re-sent at the same length rewrites the uploader's own object at its size.
        assertEquals(200, put(putUrls[0], chunks[0], PINNED), "a same-length re-PUT is the same object again")

        // An object stored BEFORE the rule, with a renderable type — written over chunk 1 through the signer as it
        // was. Without it every object here is stored as `application/octet-stream` and the served type below
        // would be right by accident rather than by the GET override.
        plantLegacyHtml(URI.create(putUrls[1]), chunks[1])

        // complete → backend stats every object at its exact derived length (declare-then-verify), attaches the media
        val blobId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", notNullValue())
            .body("results[0].error", org.hamcrest.CoreMatchers.nullValue())
            .extract().path<String>("results[0].blobId")

        // list → the manifest records the real per-chunk sizes
        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media[0].blobId", equalTo(blobId))
            .body("media[0].manifest.size()", equalTo(chunks.size))

        // presign GET → fetch each chunk back → byte-identical
        val dl = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
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

    @Test
    fun aBlobOfSeveralWindowsMovesThroughStartAndWindows() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blob = TestBlob.ofChunks(2 * WINDOW + 1) // three windows: two full, one of a single chunk
        val chunks = (0 until blob.chunkCount).map { i -> ByteArray(blob.chunkLength(i).toInt()) { b -> (b * 17 + i).toByte() } }

        val start = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${blob.declare("d3JhcA==")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].presignedPuts.size()", equalTo(WINDOW)) // the first window only
            .extract()
        val uploadId: String = start.path("results[0].uploadId")
        val urls = (0 until WINDOW).associateWith { start.path<String>("results[0].presignedPuts[$it].url") }.toMutableMap()
        var from = WINDOW
        while (from < blob.chunkCount) {
            val window = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"items":[{"uploadId":"$uploadId","fromChunk":$from}]}""")
                .`when`().post("/api/scopes/$scopeId/blob-upload-windows")
                .then().statusCode(200).body("results[0].error", org.hamcrest.CoreMatchers.nullValue())
                .extract()
            val n: Int = window.path("results[0].presignedPuts.size()")
            (0 until n).forEach { k -> urls[window.path("results[0].presignedPuts[$k].index")] = window.path("results[0].presignedPuts[$k].url") }
            from += n
        }
        assertEquals(blob.chunkCount, urls.size, "every chunk was signed by start or a window")
        chunks.forEachIndexed { i, bytes -> assertEquals(200, put(urls.getValue(i), bytes, PINNED), "PUT chunk $i") }

        val blobId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$memory"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", org.hamcrest.CoreMatchers.nullValue())
            .extract().path<String>("results[0].blobId")
        val dl = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"blobId":"$blobId"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200).body("results[0].presignedGets.size()", equalTo(chunks.size))
            .extract()
        chunks.forEachIndexed { i, expected -> assertArrayEquals(expected, get(dl.path("results[0].presignedGets[$i].url")).body(), "chunk $i bytes") }
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

    companion object {
        // The dev credentials the bundled Garage imports (infra/docker/docker-compose.yml --profile s3): derived from its
        // admin token (ADR 0089 decision 3), which is the documented dev throwaway unless the run says otherwise.
        private val DEV = BundledStoreCredentials.derive(System.getenv("VEYQ_S3_ADMIN_TOKEN") ?: "veyq-dev-admin-token")
        val DEV_KEY_ID: String get() = DEV.accessKey
        val DEV_SECRET: String get() = DEV.secretKey

        /** The presign window this suite runs with — small, so crossing windows costs a few frames. */
        const val WINDOW = 2

        /** The one type a presigned PUT signs and a presigned GET is served as (ADR 0013 §6). */
        const val PINNED = "application/octet-stream"

        private fun bucket() = System.getenv("VEYQ_S3_BUCKET") ?: "blobs"

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
         * Best-effort bucket create before the suite (on the bundled Garage the backend's store provisioner has
         * already created the configured bucket, `blobs` by default, on its first start; this is a safety net for
         * a bare store where the key may create buckets). Swallows already-exists / no-permission.
         */
        @JvmStatic
        @BeforeAll
        fun ensureBucket() {
            runCatching { devS3().use { it.createBucket { b -> b.bucket(bucket()) } } }
            // The store is a setting now (ADR 0089): an S3-compatible one, the secret sealed as the console seals it.
            StoreSettingFixtures.chooseS3Sealing(
                secretKey = System.getenv("VEYQ_S3_SECRET_KEY") ?: DEV_SECRET,
                endpoint = System.getenv("VEYQ_S3_ENDPOINT") ?: "http://localhost:19000",
                accessKey = System.getenv("VEYQ_S3_ACCESS_KEY") ?: DEV_KEY_ID,
                bucket = bucket(),
                region = System.getenv("VEYQ_S3_REGION") ?: "us-east-1",
            )
        }

        @JvmStatic
        @AfterAll
        fun forgetTheStore() = StoreSettingFixtures.clear()
    }
}
