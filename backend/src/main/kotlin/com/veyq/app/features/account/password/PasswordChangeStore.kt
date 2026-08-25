package com.veyq.app.features.account.password

import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Persistence for changing a password from a live session (uc.account.change-password).
 *
 * `RecoveryStore.applyReset`'s shape, for the same reason: the floor + auth-key rewrite and the device-wrap
 * delete are one method rather than a handler orchestrating separate store calls, because the atomicity **is**
 * the requirement. The one deliberate difference from `applyReset` — this flow spares the caller's own session
 * — lives in [PasswordChangeHandler], not here: revocation is not a row this store touches.
 */
@ApplicationScoped
class PasswordChangeStore {

    /**
     * Apply the change. **One transaction, both writes or none.**
     *
     * Deleting `DEVICE_KEY` alongside `DEVICE` matters here exactly as it does for recovery: a `DEVICE_KEY`
     * wrap takes no password at all, so leaving it would keep granting password-less entry to a device under
     * the very account whose password just changed.
     *
     * Returns false if there is no `PASSWORD` floor row to update — nothing is written.
     */
    @Transactional
    fun applyChange(userId: String, wrappedKey: String, kdfParams: String, authPublicKey: String): Boolean {
        val user = VeyqUser.findById(userId) ?: return false
        val floor = MasterKeyWrapEntity
            .find("userId = ?1 and method = ?2", userId, PASSWORD_METHOD)
            .firstResult() ?: return false

        floor.wrappedKey = wrappedKey
        floor.kdfParams = kdfParams
        user.authPublicKey = authPublicKey

        MasterKeyWrapEntity.delete("userId = ?1 and method in ?2", userId, DEVICE_METHODS)
        return true
    }

    private companion object {
        private const val PASSWORD_METHOD = "PASSWORD"
        private val DEVICE_METHODS = listOf("DEVICE", "DEVICE_KEY")
    }
}
