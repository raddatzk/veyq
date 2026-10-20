package me.raddatz.veyq.core.crypto

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

/**
 * ADR 0050's **loud startup log on MISMATCH**: a config-secret key that is present but is not the one that sealed the
 * stored secrets is named at boot, with the recovery, instead of first reaching the operator as a refused invite or
 * unreachable media. ABSENT needs nothing here — [ConfigSecretSeal] already logs it as an error when it fails to read
 * or generate the key.
 *
 * A log, never a gate: the whole read is inside the catch, so a database that is not there yet cannot fail the boot —
 * the ADR 0063 lesson [me.raddatz.veyq.core.blobstore.StoreProvisioningRunner] carries in full. The state itself stays
 * the admin channel's read (`veyq-admin config-secret`); this only says it out loud once.
 */
@ApplicationScoped
class ConfigSecretStartupLog(
    private val guard: ConfigSecretKeyGuard,
) {
    private val log = Logger.getLogger(ConfigSecretStartupLog::class.java)

    fun onStart(@Observes ev: StartupEvent) {
        try {
            message(QuarkusTransaction.requiringNew().call { guard.readState() })?.let { log.error(it) }
        } catch (e: Exception) {
            log.warnf("Could not read the config-secret state at startup (%s); read it on the admin channel instead.", e.toString())
        }
    }

    companion object {
        /** What the boot says for [state]: the recovery for MISMATCH, nothing otherwise. */
        fun message(state: ConfigSecretKeyState): String? = when (state) {
            ConfigSecretKeyState.MISMATCH ->
                "The config-secret key is NOT the one that sealed this instance's stored secrets (the key file was lost, " +
                    "replaced or not restored). Those secrets cannot be opened: tenants whose mail needs a password refuse " +
                    "invites and password resets, and a store of your own keeps its media unreachable. Recover by putting the " +
                    "original key file back and restarting, or by entering each secret again under the current key — tenant " +
                    "owners their SMTP password, the Server Owner the object store's credentials (admin console: Server → " +
                    "Object store). Read the state with `veyq-admin config-secret` (Server → Config-secret key)."
            ConfigSecretKeyState.OK, ConfigSecretKeyState.ABSENT -> null
        }
    }
}
