package me.raddatz.veyq.core.blobstore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit test for what the boot still refuses about the store (ADR 0089): the store is no startup requirement any more
 * — an instance with none set starts and refuses uploads with a reason — so what is left is the bundled hint's bucket
 * name and the upload retention against the presign TTL. Tests the pure predicates directly.
 */
class BlobStoreStartupCheckTest {

    @Test
    fun aBucketNameWithinS3sRulesIsAccepted() {
        // `veyq` stays valid: an install that must keep its old name pins it (ADR 0067 § Consequences).
        for (name in listOf("blobs", "veyq", "a.b-c")) {
            assertTrue(BlobStoreStartupCheck.isValidBucketName(name), "accepted: $name")
        }
    }

    @Test
    fun aBucketNameOutsideS3sRulesIsRefused() {
        // The backtick first: the name is interpolated into a Traefik `PathPrefix(` matcher, where it closes the rule.
        for (name in listOf("blo`bs", "Blobs", "my_blobs", "ab", "-blobs", "a..b", "192.168.0.1")) {
            assertFalse(BlobStoreStartupCheck.isValidBucketName(name), "refused: $name")
        }
    }

    @Test
    fun theRefusalNamesTheSetting() {
        // The message is what an operator reads when the boot aborts, so it has to name the key and the value.
        val refusal = BlobStoreStartupCheck.bucketNameRefusal("Blobs")
        assertTrue(refusal.contains("veyq.bundled-store.bucket=Blobs"), refusal)
    }

    @Test
    fun anUploadRetentionThatOutlastsThePresignTtlIsAccepted() {
        assertNull(BlobStoreStartupCheck.retentionRefusal(Duration.ofHours(1), 600), "the defaults")
        assertNull(BlobStoreStartupCheck.retentionRefusal(Duration.ofSeconds(601), 600), "one second past the TTL")
    }

    @Test
    fun anUploadRetentionNotPastThePresignTtlIsRefusedNamingBothSettings() {
        // Equal is refused too: a PUT signed at the TTL's last second may still be streaming when the window ends.
        for (retention in listOf(Duration.ofSeconds(600), Duration.ofMinutes(5))) {
            val refusal = BlobStoreStartupCheck.retentionRefusal(retention, 600)
            assertNotNull(refusal, "refused: $retention")
            assertTrue(refusal!!.contains("veyq.blob.upload.retention=$retention"), refusal)
            assertTrue(refusal.contains("veyq.blob-store.presign-ttl-seconds=600"), refusal)
        }
    }

    @Test
    fun aReconcileGapBoundThatCannotOutlastAHealthyQuietIsRefused() {
        val period = Duration.ofHours(6)
        val every = Duration.ofMinutes(1)
        assertNull(BlobStoreStartupCheck.reconcileGapRefusal(Duration.ofDays(1), period, every), "the defaults")
        assertNull(BlobStoreStartupCheck.reconcileGapRefusal(period.plus(every).plusSeconds(1), period, every))
        val refusal = BlobStoreStartupCheck.reconcileGapRefusal(period.plus(every), period, every)
        assertTrue(refusal!!.contains("veyq.store-reconcile.max-gap"), refusal)
    }
}
