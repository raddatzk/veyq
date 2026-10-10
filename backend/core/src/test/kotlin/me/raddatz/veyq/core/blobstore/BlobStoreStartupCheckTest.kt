package me.raddatz.veyq.core.blobstore

import io.quarkus.runtime.LaunchMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit test for the fail-loud store requirement (cr-0105, ADR 0013 §1): a byte-path store is mandatory outside
 * the hermetic `%test` profile. Tests the pure predicate directly — booting a `%dev`/`%prod` app to assert the
 * abort is impractical under `@QuarkusTest` (which always runs in TEST mode); that mode's happy path (the
 * in-memory stub boots) is exercised by every other slice test in the suite.
 */
class BlobStoreStartupCheckTest {

    @Test
    fun testProfileAllowsABlankEndpoint() {
        assertTrue(BlobStoreStartupCheck.hasStore("", LaunchMode.TEST), "%test may run the in-memory stub")
    }

    @Test
    fun devAndProdRequireAConfiguredEndpoint() {
        assertFalse(BlobStoreStartupCheck.hasStore("", LaunchMode.DEVELOPMENT), "%dev must have a store")
        assertFalse(BlobStoreStartupCheck.hasStore("   ", LaunchMode.NORMAL), "%prod must have a store (blank/whitespace fails)")
    }

    @Test
    fun aConfiguredEndpointSatisfiesEveryMode() {
        for (mode in LaunchMode.values()) {
            assertTrue(BlobStoreStartupCheck.hasStore("http://localhost:19000", mode), "a configured store is always OK ($mode)")
        }
    }

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
        assertTrue(refusal.contains("veyq.blob-store.bucket=Blobs"), refusal)
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
}
