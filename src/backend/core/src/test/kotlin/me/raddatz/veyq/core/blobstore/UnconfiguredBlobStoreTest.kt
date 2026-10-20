package me.raddatz.veyq.core.blobstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The store outside `%test` while none is usable (ADR 0089 decision 5): it refuses every upload with a reason
 * (`canPresign` false is the handlers' `store_unavailable`) and never reports an object deleted, so a reclaim row
 * waits for a store instead of being dropped — which is exactly what the in-memory stand-in would do.
 */
class UnconfiguredBlobStoreTest {

    @Test
    fun anUnconfiguredStoreSignsNothingAndHasNoOrigin() {
        assertFalse(UnconfiguredBlobStore.canPresign, "the handlers read this as `store_unavailable`")
        assertNull(UnconfiguredBlobStore.storeOrigin, "no store, no CSP origin")
        assertNull(UnconfiguredBlobStore.presignPut("k", 1L, null))
        assertNull(UnconfiguredBlobStore.presignGet("k", null))
        assertNull(UnconfiguredBlobStore.stat("k"))
    }

    @Test
    fun anUnconfiguredStoreListsNoBucket() {
        assertNull(UnconfiguredBlobStore.list(null, 1000), "the reconcile reads this as NO_STORE")
        assertNull(UnconfiguredBlobStore.bucketDigest, "no bucket to confirm as exclusive")
    }

    @Test
    fun anUnconfiguredStoreReportsNothingDeleted() {
        assertEquals(listOf("a", "b"), UnconfiguredBlobStore.delete(listOf("a", "b")), "every key stays on the reclaim list")
    }
}
