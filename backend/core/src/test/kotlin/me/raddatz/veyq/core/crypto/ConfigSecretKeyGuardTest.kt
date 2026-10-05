package me.raddatz.veyq.core.crypto

import me.raddatz.veyq.core.instance.InstanceStore
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The config-secret KEK tripwire (ADR 0050). Proves the load-bearing behaviour: a key that no longer
 * matches the one that sealed the stored secrets is **detected** ([ConfigSecretKeyState.MISMATCH]) rather than
 * silently producing unseal garbage, and a reseal under the current key recovers to OK.
 *
 * The H2 mem DB + the config-secret key file are shared across the test JVM, so this mutates the stored fingerprint
 * and **always restores** it (finally → recordReseal) to keep the suite isolated.
 */
@QuarkusTest
class ConfigSecretKeyGuardTest {

    @Inject
    lateinit var guard: ConfigSecretKeyGuard

    @Inject
    lateinit var instance: InstanceStore

    @Test
    fun detectsAChangedKeyThenRecoversOnReseal() {
        // Steady state: the running key matches the adopted fingerprint (adopting it now if this runs first).
        assertEquals(ConfigSecretKeyState.OK, guard.state(), "the running key matches the adopted fingerprint")
        try {
            // Simulate the key file changing under a running server: the stored fingerprint no longer matches.
            QuarkusTransaction.requiringNew().run { instance.setConfigSecretFingerprint("not-the-current-key") }
            assertEquals(ConfigSecretKeyState.MISMATCH, guard.state(), "a changed key is DETECTED, not silent")
        } finally {
            // Recovery (and suite isolation): a reseal re-adopts the current key.
            guard.recordReseal()
        }
        assertEquals(ConfigSecretKeyState.OK, guard.state(), "recordReseal clears MISMATCH back to OK")
    }
}
