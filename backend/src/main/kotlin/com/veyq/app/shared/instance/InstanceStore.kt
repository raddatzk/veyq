package com.veyq.app.shared.instance

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.util.Base64

/**
 * The instance singleton. The public instance salt is generated **once** at first run and persisted, so
 * it is stable for the life of the instance — changing it would invalidate every account's auth keypair
 * (ADR 0003). Seeded at startup so it is always present before the first request.
 */
@ApplicationScoped
class InstanceStore {

    /** The instance salt (base64). Always seeded by [seedOnStartup] before any request runs. */
    fun instanceSalt(): String =
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID)?.instanceSalt
            ?: error("instance salt not seeded")

    /** The stored config-secret KEK fingerprint, or null if not adopted yet (ADR 0050 amendment). */
    fun configSecretFingerprint(): String? =
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID)?.configSecretFingerprint

    /** Set/advance the stored config-secret KEK fingerprint (adopt on first read, or reseal recovery). The instance
     *  row is always present (seeded at startup, before any request). Must run in a transaction (caller-provided). */
    fun setConfigSecretFingerprint(fingerprint: String) {
        val row = VeyqInstance.findById(VeyqInstance.SINGLETON_ID) ?: error("instance not seeded")
        row.configSecretFingerprint = fingerprint
        row.persist()
    }

    @Transactional
    fun seedOnStartup(@Observes ev: StartupEvent) {
        if (VeyqInstance.findById(VeyqInstance.SINGLETON_ID) == null) {
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            VeyqInstance().apply {
                id = VeyqInstance.SINGLETON_ID
                instanceSalt = Base64.getEncoder().encodeToString(salt)
            }.persist()
        }
    }
}
