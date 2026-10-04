package com.veyq.core.account.identitypins

import jakarta.enterprise.context.ApplicationScoped

/**
 * The authenticated identity-pin-store use-case (en.account.identity-pinning, cr-0098), server side. Thin over
 * [IdentityPinsStore] (ADR 0001): the resource is handed an already-resolved `accountId` (central `@Authenticated`,
 * cr-0012) and this only delegates. Operator-blind: every value that flows through is opaque ciphertext or the
 * public version counter.
 */
@ApplicationScoped
class IdentityPinsHandler(private val store: IdentityPinsStore) {

    fun forAccount(accountId: String): IdentityPinsView = store.get(accountId)

    fun store(accountId: String, blob: String, expectedVersion: Long): PutResult =
        store.put(accountId, blob, expectedVersion)
}
