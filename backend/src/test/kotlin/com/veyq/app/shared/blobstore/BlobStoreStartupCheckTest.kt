package com.veyq.app.shared.blobstore

import io.quarkus.runtime.LaunchMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
