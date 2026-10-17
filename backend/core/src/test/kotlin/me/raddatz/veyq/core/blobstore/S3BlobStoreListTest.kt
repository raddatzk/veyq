package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Instant
import java.util.Collections
import kotlin.concurrent.thread

/**
 * [S3BlobStore.list] through the real AWS SDK against a raw HTTP stub — the mapping the reconcile trusts: the query it
 * sends (`list-type=2`, `start-after`, `max-keys`), the objects it reads, the store's clock from the answer's `Date`, and
 * the two refusals it must not paper over (no `Date`; a `403`). A raw socket, not the JDK's `HttpServer`: that one adds a
 * `Date` to every answer, so the missing-clock case could not be built with it. Garage's own answer is the opt-in
 * [S3BlobStoreListE2ETest]'s.
 */
class S3BlobStoreListTest {

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var server: ServerSocket

    @AfterEach
    fun stop() = server.close()

    @Test
    fun aPageCarriesItsObjectsTheStoresClockAndWhetherMoreFollow() {
        serve(status = 200, date = "Sat, 17 Oct 2026 17:34:32 GMT", body = page(truncated = true))

        val listing = store().list("11111111-1111-1111-1111-111111111111/0", 2)

        val query = requests.single()
        assertTrue(query.contains("list-type=2"), query)
        assertTrue(query.contains("max-keys=2"), query)
        assertEquals("11111111-1111-1111-1111-111111111111/0", param(query, "start-after"))
        assertEquals(Instant.parse("2026-10-17T17:34:32Z"), listing.storeTime)
        assertTrue(listing.truncated)
        assertEquals(
            listOf(
                StoredObject("22222222-2222-2222-2222-222222222222/0", 5, Instant.parse("2026-10-17T17:34:24.035Z")),
                StoredObject("veyq-check/probe", 17, Instant.parse("2026-10-17T17:30:00Z")),
            ),
            listing.objects,
        )
    }

    @Test
    fun theFirstPageAsksFromTheStartAndTheLastSaysSo() {
        serve(status = 200, date = "Sat, 17 Oct 2026 17:34:32 GMT", body = page(truncated = false))

        val listing = store().list(null, 1000)

        assertFalse(requests.single().contains("start-after"), requests.single())
        assertFalse(listing.truncated)
    }

    @Test
    fun anAnswerWithoutTheStoresClockIsRefused() {
        serve(status = 200, date = null, body = page(truncated = false))

        assertThrows(MissingStoreClock::class.java) { store().list(null, 1000) }
    }

    @Test
    fun aRefusedListingLeavesAsTheStoresOwn403() {
        serve(
            status = 403, date = "Sat, 17 Oct 2026 17:34:32 GMT",
            body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code><Message>Forbidden</Message></Error>",
        )

        val e = assertThrows(S3Exception::class.java) { store().list(null, 1000) }
        assertEquals(403, e.statusCode())
    }

    @Test
    fun theDigestNamesTheBucketAndNotTheSecret() {
        serve(status = 200, date = null, body = "")
        val one = store()
        assertEquals(one.bucketDigest, store().bucketDigest, "the same store setting, the same digest")
        assertNotEquals(one.bucketDigest, store(bucket = "other").bucketDigest)
        assertEquals(one.bucketDigest, store(secret = "another-secret").bucketDigest, "a rotated secret is the same bucket")
        assertFalse(one.bucketDigest.contains("secret"))
    }

    // --- helpers ---

    private fun store(bucket: String = "blobs", secret: String = "secret-key"): S3BlobStore {
        val connection = StoreConnection(
            bundled = false, type = StoreType.S3, endpoint = "http://127.0.0.1:${server.localPort}", publicEndpoint = null,
            signingMode = SigningMode.INTERNAL_ENDPOINT, bucket = bucket, region = "garage", accessKey = "GKtest",
            secretKey = secret, garageAdminEndpoint = null, garageAdminToken = null,
        )
        return BlobStoreProducer.connect(connection, 300)
    }

    private fun param(requestLine: String, name: String): String? =
        requestLine.substringAfter("?").substringBefore(" ").split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == name }
            ?.let { URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8) }

    private fun page(truncated: Boolean) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
          <Name>blobs</Name><Prefix></Prefix><KeyCount>2</KeyCount><MaxKeys>2</MaxKeys><IsTruncated>$truncated</IsTruncated>
          <Contents><Key>22222222-2222-2222-2222-222222222222/0</Key><LastModified>2026-10-17T17:34:24.035Z</LastModified><ETag>"a"</ETag><Size>5</Size><StorageClass>STANDARD</StorageClass></Contents>
          <Contents><Key>veyq-check/probe</Key><LastModified>2026-10-17T17:30:00.000Z</LastModified><ETag>"b"</ETag><Size>17</Size><StorageClass>STANDARD</StorageClass></Contents>
          ${if (truncated) "<NextContinuationToken>opaque</NextContinuationToken>" else ""}
        </ListBucketResult>
    """.trimIndent()

    /** Answers every connection once with [status], an optional `Date`, and [body]; records each request line. */
    private fun serve(status: Int, date: String?, body: String) {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    requests += requestLine
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\n")
                        if (date != null) append("Date: $date\r\n")
                        append("Content-Type: application/xml\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    s.getOutputStream().apply {
                        write(head.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }
}
