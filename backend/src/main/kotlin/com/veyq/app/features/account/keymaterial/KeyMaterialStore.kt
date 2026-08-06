package com.veyq.app.features.account.keymaterial

import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
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
        if (wrap.userId != userId || wrap.method !in DEVICE_METHODS) return
        wrap.delete()
    }

    private companion object {
        private val DEVICE_METHODS = setOf("DEVICE", "DEVICE_KEY")
    }
}
