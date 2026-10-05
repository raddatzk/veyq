package com.veyq.core.account.login

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Persists an outstanding login challenge. It only ever **inserts**: a new challenge never replaces an older
 * one for the same email (uc.account.login E4). Runs in its **own** transaction (REQUIRES_NEW) so a failed
 * insert fails **in isolation** — it never poisons the caller's transaction and never changes the challenge
 * response. That keeps challenge issuance non-enumerable: the caller always returns 200 + a nonce, and an
 * existing account whose insert failed answers exactly like one that does not exist.
 */
@ApplicationScoped
class LoginChallengeStore {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun insert(email: String, nonce: String, expiresAt: Instant) {
        LoginChallengeEntity().apply {
            this.nonce = nonce
            this.email = email
            this.expiresAt = expiresAt
        }.persist()
    }
}
