package com.veyq.app.features.account.login

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Persists the single outstanding login challenge for an email. Runs in its **own** transaction
 * (REQUIRES_NEW) so a concurrent-insert race (PK conflict on the email key) fails **in isolation** —
 * it never poisons the caller's transaction and never changes the challenge response. That keeps
 * challenge issuance non-enumerable: the caller always returns 200 + a nonce regardless of the outcome.
 */
@ApplicationScoped
class LoginChallengeStore {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun replace(email: String, nonce: String, expiresAt: Instant) {
        LoginChallengeEntity.delete("email", email)
        LoginChallengeEntity().apply {
            this.email = email
            this.nonce = nonce
            this.expiresAt = expiresAt
        }.persist()
    }
}
