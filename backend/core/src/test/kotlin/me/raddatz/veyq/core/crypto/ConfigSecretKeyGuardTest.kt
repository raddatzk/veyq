package me.raddatz.veyq.core.crypto

import me.raddatz.veyq.core.admin.tenant.MailSettingsRow
import me.raddatz.veyq.core.admin.tenant.TenantMailSettingsEntity
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.blobstore.SealedValue
import me.raddatz.veyq.core.blobstore.StoreSettingFixtures
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The config-secret KEK tripwire (ADR 0050), **per sealed value** (ADR 0089 decision 4). A value sealed under a key
 * that is no longer the running one is **detected** ([ConfigSecretKeyState.MISMATCH]) rather than silently producing
 * unseal garbage, and the instance-wide state sees every sealed value — so re-entering ONE secret under the current
 * key cannot hide that another is still sealed under the old one, which a single instance-wide fingerprint advanced
 * on every reseal did.
 *
 * The H2 mem DB is JVM-shared, so the store setting each case stores is cleared in its `finally`.
 */
@QuarkusTest
class ConfigSecretKeyGuardTest {

    @Inject
    lateinit var guard: ConfigSecretKeyGuard

    @Inject
    lateinit var seal: ConfigSecretSeal

    @Inject
    lateinit var store: TenantStore

    @Test
    fun aValueSealedUnderAnotherKeyIsDetectedPerValue() {
        val current = guard.currentFingerprint()
        assertEquals(ConfigSecretKeyState.OK, guard.stateOf(current), "sealed under the running key")
        assertEquals(ConfigSecretKeyState.MISMATCH, guard.stateOf("not-the-current-key"), "a changed key is DETECTED, not silent")
        assertEquals(ConfigSecretKeyState.OK, guard.stateOf(null), "nothing stored, nothing to open")
    }

    @Test
    fun theInstanceWideStateSeesEveryHolderOfASealedValue() {
        assertEquals(ConfigSecretKeyState.OK, committedState(), "precondition: nothing is sealed under another key")
        try {
            StoreSettingFixtures.chooseS3(secretKey = sealedUnder("not-the-current-key"))
            assertEquals(ConfigSecretKeyState.MISMATCH, committedState(), "the store's secret, sealed under another key, is seen")

            StoreSettingFixtures.chooseS3(secretKey = sealedUnder(guard.currentFingerprint()!!))
            assertEquals(ConfigSecretKeyState.OK, committedState(), "re-entered under the running key, it reads OK")
        } finally {
            StoreSettingFixtures.clear()
        }
    }

    @Test
    fun aStoreSecretReEnteredUnderTheNewKeyLeavesTheSmtpSecretUnreadable() {
        // The bite of per-value fingerprints: one instance-wide fingerprint advanced by every reseal would read OK
        // here, hiding an SMTP password still sealed under the lost key behind a store secret that was re-entered.
        val tenantId = seedTenant()
        val smtp = seal.seal("smtp-secret")
        QuarkusTransaction.requiringNew().run {
            store.saveMailSettings(
                tenantId,
                MailSettingsRow(
                    host = "smtp.example.com", port = 587, from = null, username = "u",
                    passwordCiphertext = smtp.ciphertext, passwordNonce = smtp.nonce, passwordFingerprint = "the-lost-key",
                    tls = true, enabled = true, subjectTemplate = null, bodyTemplate = null,
                ),
            )
        }
        try {
            StoreSettingFixtures.chooseS3(secretKey = sealedUnder(guard.currentFingerprint()!!))

            assertEquals(ConfigSecretKeyState.MISMATCH, committedState(), "the SMTP password is still sealed under the lost key")
            assertEquals(ConfigSecretKeyState.MISMATCH, guard.stateOf("the-lost-key"), "and says so of itself")
        } finally {
            StoreSettingFixtures.clear()
            QuarkusTransaction.requiringNew().run { TenantMailSettingsEntity.deleteById(tenantId) }
        }
    }

    /**
     * The instance-wide state as committed, read in a transaction of its own. Read outside one, the test thread's
     * request-scoped session hands back the entity it loaded earlier — so a re-entered secret would still read as the
     * old one. A real request has a session of its own.
     */
    private fun committedState(): ConfigSecretKeyState = QuarkusTransaction.requiringNew().call { guard.readState() }

    private fun sealedUnder(fingerprint: String): SealedValue {
        val sealed = seal.seal("a-secret")
        return SealedValue(sealed.ciphertext, sealed.nonce, fingerprint)
    }
}
