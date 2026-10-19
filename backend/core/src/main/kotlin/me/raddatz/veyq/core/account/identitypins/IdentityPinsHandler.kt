package me.raddatz.veyq.core.account.identitypins

import jakarta.enterprise.context.ApplicationScoped

/**
 * The authenticated identity-pin-store use-case (en.account.identity-pinning, ADR 0104), server side. Thin over
 * [IdentityPinsStore] (ADR 0001): the resource is handed an already-resolved `accountId` (central `@Authenticated`,
 * cr-0012). Operator-blind: every value that flows through is opaque ciphertext, an opaque id or the revision counter.
 */
@ApplicationScoped
class IdentityPinsHandler(private val store: IdentityPinsStore) {

    fun forAccount(accountId: String): IdentityPinsView = store.get(accountId)

    fun entries(accountId: String, after: String?, limit: Int): PinEntryPage = store.list(accountId, after, limit)

    /** One write in its own transaction; a store left over its bound is rolled back there and answered `Full`. */
    fun write(accountId: String, write: PinWrite): PutResult = try {
        store.write(accountId, write)
    } catch (_: IdentityPinsStore.StoreFull) {
        PutResult.Full
    }
}
