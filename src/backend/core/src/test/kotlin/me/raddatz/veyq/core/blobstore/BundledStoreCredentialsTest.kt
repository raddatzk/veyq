package me.raddatz.veyq.core.blobstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The bundled store's derived key pair (ADR 0089 decision 3). The backend imports this pair into the Garage it
 * provisions, and `src/tools/instance_backup.py` derives the same pair to capture that store: a drift on either side
 * makes every bundled backup fail its first store call. The vector below is pinned in `instance_backup_selftest.py`
 * too — change both together, or neither.
 */
class BundledStoreCredentialsTest {
    @Test
    fun theDevTokenDerivesThePinnedVector() {
        val pair = BundledStoreCredentials.derive("veyq-dev-admin-token")
        assertEquals("GKd14f469abb39c11f4f86a0e3", pair.accessKey)
        assertEquals("db5a958fff2b57263c0cb0f6963fb047ee2d21fc487250082d035ff35cb79b66", pair.secretKey)
    }

    @Test
    fun anotherTokenDerivesAnotherPair() {
        assertFalse(BundledStoreCredentials.derive("another").accessKey == BundledStoreCredentials.derive("veyq-dev-admin-token").accessKey)
    }
}
