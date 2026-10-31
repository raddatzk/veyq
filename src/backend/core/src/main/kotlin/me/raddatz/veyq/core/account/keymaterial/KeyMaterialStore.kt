package me.raddatz.veyq.core.account.keymaterial

import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.identity.WrapMethod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID

/** One Master Key wrap as opaque ciphertext + its KDF params (server never opens it). */
data class WrapMaterial(val id: String, val method: String, val wrappedKey: String, val kdfParams: String?)

/** A user's wrapped key material — everything the client needs to unlock the Master Key on-device. */
data class KeyMaterial(
    val suiteId: String,
    val wraps: List<WrapMaterial>,
    val wrappedIdentityPrivateKey: String,
)

/** Reads a user's wrapped key material. All fields are ciphertext or a public descriptor (suite id). */
@ApplicationScoped
class KeyMaterialStore {

    /**
     * Every wrap of the account, **RECOVERY included, unfiltered**.
     *
     * **A trap:** the RECOVERY wrap is a password-less path to the Master Key, and it is withheld a level up, in
     * [KeyMaterialHandler.forUser] — not here. A second caller of this read that forgets to filter it lets a
     * session plus the Secret Key open the vault without the password (ADR 0054, Context fact 2), and the slice
     * test pins the endpoint, not this store. So a new caller filters, or reads only the methods it is entitled to, as
     * `RecoveryStore.recoveryMaterial` does.
     */
    @Transactional
    fun find(userId: String): KeyMaterial? {
        val user = VeyqUser.findById(userId) ?: return null
        val wraps = MasterKeyWrapEntity.find("userId", userId).list()
            .map { WrapMaterial(it.id, it.method, it.wrappedKey, it.kdfParams) }
        return KeyMaterial(user.suiteId, wraps, user.wrappedIdentityPrivateKey)
    }

    /** How many wraps of a given method the user has (used to cap DEVICE enrolments per account). */
    @Transactional
    fun countWraps(userId: String, method: String): Long =
        MasterKeyWrapEntity.count("userId = ?1 and method = ?2", userId, method)

    /** Append a new wrap for the user (each trusted device adds its own). Returns the new wrap's id. */
    @Transactional
    fun addWrap(userId: String, method: String, wrappedKey: String, kdfParams: String?): String {
        val id = UUID.randomUUID().toString()
        MasterKeyWrapEntity().apply {
            this.id = id
            this.userId = userId
            this.method = method
            this.wrappedKey = wrappedKey
            this.kdfParams = kdfParams
        }.persist()
        return id
    }

    /** Remove a wrap by id — but only the caller's own **device-local convenience** wrap: DEVICE (trust this
     *  device) or DEVICE_KEY (password-less remember-me); the only kinds this endpoint creates. Otherwise a
     *  no-op (idempotent). Whitelisting the device methods — rather than blacklisting only the PASSWORD floor —
     *  keeps the floor un-removable AND protects the RECOVERY/PASSKEY methods from being stripped through this
     *  device-trust endpoint, and doesn't hinge on the exact floor casing. Both device methods are removable so
     *  re-enrol can replace the prior convenience wrap (one per device, ADR 0005 §9). */
    @Transactional
    fun deleteWrap(userId: String, id: String) {
        val wrap = MasterKeyWrapEntity.findById(id) ?: return
        if (wrap.userId != userId || wrap.method !in WrapMethod.DEVICE_LOCAL) return
        wrap.delete()
    }
}
