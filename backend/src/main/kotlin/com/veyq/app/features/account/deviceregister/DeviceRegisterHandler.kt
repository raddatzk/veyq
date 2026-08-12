package com.veyq.app.features.account.deviceregister

import jakarta.enterprise.context.ApplicationScoped

/**
 * The authenticated device-register use-case (en.account.device-register), server side. Thin over
 * [DeviceRegisterStore] (ADR 0001): the resource is handed an already-resolved `accountId` (central
 * `@Authenticated`, cr-0012) and this only delegates. Operator-blind: every value that flows through is opaque
 * ciphertext or the public version counter.
 */
@ApplicationScoped
class DeviceRegisterHandler(private val store: DeviceRegisterStore) {

    fun forAccount(accountId: String): DeviceRegisterView = store.get(accountId)

    fun store(accountId: String, blob: String, expectedVersion: Long): PutResult =
        store.put(accountId, blob, expectedVersion)
}
