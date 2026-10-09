package me.raddatz.veyq.core.admin.setup

import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.WrapData
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Persistence for first-run setup. Reads the setup lock and stores the owner — the account itself is born
 *  the shared operator-blind way via [AccountStore]; setup adds only the single-owner lock. */
@ApplicationScoped
class SetupStore(private val accounts: AccountStore) {

    fun isSetupComplete(): Boolean = ServerSetup.count() > 0

    @Transactional
    fun persistOwner(registration: OwnerRegistration): String {
        val userId = accounts.createAccount(
            email = registration.email,
            serverRole = ServerRoles.SERVER_OWNER,
            suiteId = registration.suiteId,
            identityPublicKey = registration.identityPublicKey,
            wrappedIdentityPrivateKey = registration.wrappedIdentityPrivateKey,
            authPublicKey = registration.authPublicKey,
            secretKeyProofPublicKey = registration.secretKeyProofPublicKey,
            wraps = registration.wraps.map { WrapData(it.method, it.wrappedKey, it.kdfParams) },
        )

        ServerSetup().apply {
            id = ServerSetup.SINGLETON_ID
        }.persist()

        return userId
    }
}
