package me.raddatz.veyq.core.account.kitrecord

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Reads and replaces the account's kit record (ADR 0157 decision 3). The stored `sealedBlob` is one opaque ciphertext
 * the server never opens; there is no revision to keep — last writer wins. Keyed by the account id its caller resolved
 * (the authenticated principal, or the account a birth just created) — never an id in a request.
 *
 * **Its writers** (ADR 0157 decision 4), each in the transaction that makes its kit's key material: the two births
 * (`SetupStore.persistOwner`, `TenantAcceptHandler.registerAndAccept`, an account created in that very transaction), the
 * rotation (`RotationHandler`, under the account lock, replacing the row the old Master Key sealed) and the `PUT` of a
 * flow that has a session ([KitRecordHandler.store], under the account lock). The account lock is what makes the
 * find-then-insert of [replace] safe against a second writer: two first writes never race to the primary key.
 */
@ApplicationScoped
class KitRecordStore {

    /** The account's sealed record, or null when no flow has written one. */
    @Transactional
    fun get(accountId: String): String? = KitRecordEntity.findById(accountId)?.sealedBlob

    /**
     * Replace the account's record with [blob], inserting the row on the first write. Joins the caller's transaction,
     * which must hold the account (its lock, or the account's birth) so no other writer inserts in between.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun replace(accountId: String, blob: String) {
        val row = KitRecordEntity.findById(accountId)
        if (row != null) {
            row.sealedBlob = blob
            return
        }
        KitRecordEntity().apply {
            this.accountId = accountId
            this.sealedBlob = blob
        }.persist()
    }
}
