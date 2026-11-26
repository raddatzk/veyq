package me.raddatz.veyq.core.account.kitrecord

import me.raddatz.veyq.core.identity.AccountStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * The authenticated kit-record use-case (ADR 0157, uc.account.renew-emergency-kit), server side. Thin over
 * [KitRecordStore] (ADR 0001): the resource hands it the already-resolved `accountId` (central `@Authenticated`,
 * cr-0012). Operator-blind: the only value that flows through is opaque ciphertext.
 */
@ApplicationScoped
class KitRecordHandler(
    private val store: KitRecordStore,
    private val accounts: AccountStore,
) {

    fun forAccount(accountId: String): String? = store.get(accountId)

    /**
     * Replace the caller's record — under the account lock (`AccountStore.lock`), the serializing parent the rotation and
     * the erasure take too: a write waits for a rotation in flight and for an erasure, and two first writes cannot race
     * to the row's key. False when the account is gone (an erasure committed while the request waited): nothing written.
     */
    @Transactional
    fun store(accountId: String, blob: String): Boolean {
        accounts.lock(accountId) ?: return false
        store.replace(accountId, blob)
        return true
    }
}
