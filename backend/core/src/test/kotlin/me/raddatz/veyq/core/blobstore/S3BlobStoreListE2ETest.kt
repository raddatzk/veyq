package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Opt-in (`VEYQ_S3_E2E=1`, set by no workflow, so it makes no coverage claim): [S3BlobStore.list] against a real
 * S3-compatible store — the one un-mocked run of the mapping the reconcile trusts (changes/README.md § Where a mapping
 * depends on a library's output). It pages a fresh prefix of the bucket by `start-after`, reads `truncated` and each
 * object's size, and holds the store's clock (the answer's `Date`) to its objects' `LastModified`: the reconcile judges
 * an object's age by subtracting one from the other, so the two must come from one clock.
 *
 * The store: `VEYQ_S3_E2E_ENDPOINT` (default the dev stack's `http://127.0.0.1:19000`), `_BUCKET` (`blobs`), `_REGION`
 * (`garage`), `_ACCESS_KEY`, `_SECRET_KEY` — or, without the key pair, the pair the bundled store derives from
 * `VEYQ_S3_E2E_ADMIN_TOKEN` (ADR 0089 decision 3). The test writes its objects under a random prefix and removes them.
 */
@EnabledIfEnvironmentVariable(named = "VEYQ_S3_E2E", matches = "1")
class S3BlobStoreListE2ETest {

    @Test
    fun aRealStoreListsPageByPageOnItsOwnClock() {
        val connection = connection()
        val s3 = BlobStoreProducer.s3Client(connection)
        val store = BlobStoreProducer.connect(connection, 300)
        // A prefix that sorts before every UUID and the probe, so the first page is this test's own.
        val prefix = "0000-reconcile-e2e-${UUID.randomUUID()}"
        val keys = (0 until 5).map { "$prefix/$it" }
        try {
            keys.forEachIndexed { i, key ->
                s3.putObject(PutObjectRequest.builder().bucket(connection.bucket).key(key).build(), RequestBody.fromBytes(ByteArray(i + 1)))
            }
            val first = store.list(null, 2)
            assertEquals(keys.take(2), first.objects.map { it.key })
            assertTrue(first.truncated)
            assertEquals(listOf(1L, 2L), first.objects.map { it.size })

            val second = store.list(first.objects.last().key, 2)
            assertEquals(keys.subList(2, 4), second.objects.map { it.key })

            val skew = first.objects.maxOf { Duration.between(it.lastModified, first.storeTime).abs() }
            assertTrue(skew < Duration.ofMinutes(1), "the store's Date and its LastModified are one clock: $skew apart")
            assertFalse(first.storeTime.isAfter(Instant.now().plus(Duration.ofDays(1))), "a Date, read as a date")
        } finally {
            keys.forEach { s3.deleteObject(DeleteObjectRequest.builder().bucket(connection.bucket).key(it).build()) }
        }
    }

    private fun connection(): StoreConnection {
        val env = System.getenv()
        val token = env["VEYQ_S3_E2E_ADMIN_TOKEN"]
        val derived = token?.let { BundledStoreCredentials.derive(it) }
        return StoreConnection(
            bundled = false, type = StoreType.S3,
            endpoint = env["VEYQ_S3_E2E_ENDPOINT"] ?: "http://127.0.0.1:19000", publicEndpoint = null,
            signingMode = SigningMode.INTERNAL_ENDPOINT,
            bucket = env["VEYQ_S3_E2E_BUCKET"] ?: "blobs", region = env["VEYQ_S3_E2E_REGION"] ?: "garage",
            accessKey = env["VEYQ_S3_E2E_ACCESS_KEY"] ?: derived?.accessKey.orEmpty(),
            secretKey = env["VEYQ_S3_E2E_SECRET_KEY"] ?: derived?.secretKey.orEmpty(),
            garageAdminEndpoint = null, garageAdminToken = null,
        )
    }
}
