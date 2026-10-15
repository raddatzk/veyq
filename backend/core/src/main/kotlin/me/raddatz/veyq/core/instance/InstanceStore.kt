package me.raddatz.veyq.core.instance

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
