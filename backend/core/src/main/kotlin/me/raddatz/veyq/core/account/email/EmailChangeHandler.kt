package me.raddatz.veyq.core.account.email

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.crypto.AuthAttestation
import me.raddatz.veyq.core.crypto.SecretKeyProof
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.normalizeEmail
import me.raddatz.veyq.core.membership.MembershipStore
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.tenant.ResolvedTenant
import io.quarkus.logging.Log
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/** What asking for a change produced. */
sealed interface EmailChangeRequestOutcome {
    /** Stored and mailed — or, for an address another account holds, stored and mailed just the same. */
    data object Mailed : EmailChangeRequestOutcome

    data object MailUnavailable : EmailChangeRequestOutcome, HttpFailure by Failures.mailUnavailable

    data object TenantNotFound : EmailChangeRequestOutcome, HttpFailure by Failures.tenantNotFound

    /** The attestation did not verify against the current auth key (the password). Nothing was stored. */
    data object AttestationFailed : EmailChangeRequestOutcome, HttpFailure by httpFailure("attestation_failed", FORBIDDEN)

    /** The Secret-Key proof did not verify (the Secret Key). Nothing was stored. */
    data object SecretKeyProofFailed :
        EmailChangeRequestOutcome, HttpFailure by httpFailure("secret_key_proof_failed", FORBIDDEN)

    /** The caller's session ended while this request waited for the account's lock. */
    data object SessionEnded : EmailChangeRequestOutcome, HttpFailure by Failures.authenticationFailed

    data class RateLimited(val retryAfterSeconds: Long) : EmailChangeRequestOutcome
}

/** What confirming a change produced. */
sealed interface EmailChangeConfirmOutcome {
    /** Swapped. [oldEmail] is where the notice goes; it never reaches the wire. */
    data class Changed(val oldEmail: String) : EmailChangeConfirmOutcome

    /** Unknown, expired, replaced or spent; minted for another account; or an address another account holds by now —
     *  deliberately one answer, and nothing changed. */
    data object Invalid : EmailChangeConfirmOutcome, HttpFailure by httpFailure("email_change_invalid", NOT_FOUND)

    data object MailUnavailable : EmailChangeConfirmOutcome, HttpFailure by Failures.mailUnavailable

    data object TenantNotFound : EmailChangeConfirmOutcome, HttpFailure by Failures.tenantNotFound

    data object SessionEnded : EmailChangeConfirmOutcome, HttpFailure by Failures.authenticationFailed
}

/**
 * Changing the address an account signs in with (uc.account.change-email, ADR 0074 Decision 8).
 *
 * The address is the auth salt's input (ADR 0003), so the auth key changes with it: the client derives the new one
 * and the server installs **both together**, and only once the new address is proven. Asking for it mints a cold
 * credential's identifier — the address a password reset is sent to — so it is gated like a password change: the
 * attestation by the current auth key (the password) and the Secret-Key proof (the Secret Key). A session alone moves
 * nothing.
 *
 * **Non-enumerable.** A request for an address another account holds is stored and mailed like any other; the
 * address's uniqueness is enforced at the swap, under the account's lock and the address's unique index, and a taken
 * address fails the confirmation with the same answer as a dead link.
 */
@ApplicationScoped
class EmailChangeHandler(
    private val store: EmailChangeStore,
    private val accounts: AccountStore,
    private val sessions: SessionService,
    private val membership: MembershipStore,
    private val mailer: EmailChangeMailer,
    // The confirmation's per-account budget, shared: both mail kinds are one account aiming its tenant's relay, and a
    // change mails an address of the caller's choosing, so it must not get a budget of its own on top.
    private val limiter: EmailConfirmationRateLimiter,
    config: VeyqConfig,
) {
    private val ttl = config.emailConfirmation().token().ttl()

    /**
     * Ask for the change. The gates and the store commit in one short transaction under the account's lock; the
     * send runs after it, outside any, and a send that fails withdraws the change (ADR 0055 §6) and answers `503`.
     */
    fun request(
        userId: String,
        presentedAccessToken: String?,
        tenant: ResolvedTenant?,
        newEmail: String,
        authPublicKey: String,
        attestationSignature: String,
        secretKeyProofSignature: String,
    ): EmailChangeRequestOutcome {
        if (tenant == null) return EmailChangeRequestOutcome.TenantNotFound
        val gate = QuarkusTransaction.requiringNew().call<Gate> {
            gateAndStore(userId, presentedAccessToken, tenant, newEmail, authPublicKey, attestationSignature, secretKeyProofSignature)
        }
        val stored = when (gate) {
            is Gate.Refused -> return gate.outcome
            is Gate.Stored -> gate
        }
        val delivery = mailer.sendChangeLink(tenant.id, tenant.domain, newEmail, stored.token, stored.expiresAt)
        if (delivery != DeliveryResult.SENT) {
            store.withdraw(stored.rowId)
            return EmailChangeRequestOutcome.MailUnavailable
        }
        return EmailChangeRequestOutcome.Mailed
    }

    private fun gateAndStore(
        userId: String,
        presentedAccessToken: String?,
        tenant: ResolvedTenant,
        newEmail: String,
        authPublicKey: String,
        attestationSignature: String,
        secretKeyProofSignature: String,
    ): Gate {
        // The account row first (AccountStore.lock, inbox #297): the proofs are checked against the auth key as it is
        // after any reset, password change or other address change this waited for.
        val user = accounts.lock(userId) ?: return Gate.Refused(EmailChangeRequestOutcome.SessionEnded)
        if (!mailer.canSend(tenant.id)) return Gate.Refused(EmailChangeRequestOutcome.MailUnavailable)
        if (!membership.belongsToTenant(userId, tenant.id)) return Gate.Refused(EmailChangeRequestOutcome.TenantNotFound)
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return Gate.Refused(EmailChangeRequestOutcome.SessionEnded)

        val attestation = AuthAttestation.emailChangeStatement(userId, newEmail, authPublicKey)
        if (!AuthAttestation.verify(user.authPublicKey, attestation, attestationSignature)) {
            return Gate.Refused(EmailChangeRequestOutcome.AttestationFailed)
        }
        val proof = SecretKeyProof.emailChangeStatement(user.authPublicKey, authPublicKey, newEmail)
        if (!SecretKeyProof.verify(user.secretKeyProofPublicKey, proof, secretKeyProofSignature)) {
            return Gate.Refused(EmailChangeRequestOutcome.SecretKeyProofFailed)
        }
        limiter.check(userId)?.let { return Gate.Refused(EmailChangeRequestOutcome.RateLimited(it)) }

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(ttl)
        val rowId = store.replace(userId, normalizeEmail(newEmail), authPublicKey, Tokens.hash(token), expiresAt)
        return Gate.Stored(rowId, token, expiresAt)
    }

    private sealed interface Gate {
        data class Refused(val outcome: EmailChangeRequestOutcome) : Gate
        data class Stored(val rowId: String, val token: String, val expiresAt: Instant) : Gate
    }

    /**
     * Confirm with a mailed token, in a session of the account that asked. **One transaction swaps the email and the
     * auth key, records the address confirmed and revokes every other session** — or nothing happens. The old address
     * is mailed the fixed notice after the commit; the relay was checked first, so a tenant that cannot send changes
     * nothing (ADR 0055: mail is a precondition).
     *
     * The address's uniqueness is decided here. The fast path reads it under the account's lock; two accounts
     * confirming the same address at once both read it free, and the unique index lets one commit through. The loser
     * re-asks the replay question in a fresh read ([createOrConflict], developer-styleguide §12) and answers as a
     * taken address does — the one neutral refusal — with its pending change rolled back, untouched.
     */
    fun confirm(userId: String, presentedAccessToken: String?, tenant: ResolvedTenant?, token: String): EmailChangeConfirmOutcome {
        if (tenant == null) return EmailChangeConfirmOutcome.TenantNotFound
        val tokenHash = Tokens.hash(token)
        val outcome = createOrConflict(conflict = { takenBySomeoneElse(userId, tokenHash) }) {
            swap(userId, presentedAccessToken, tenant, tokenHash)
        }
        if (outcome is EmailChangeConfirmOutcome.Changed) {
            // After the commit: the change is proven and done whatever the relay does now. A failure is logged, not
            // answered — there is nothing left to undo, and the caller's own screen already says what happened.
            val delivery = mailer.sendChangedNotice(tenant.id, tenant.domain, outcome.oldEmail)
            if (delivery != DeliveryResult.SENT) Log.warnf("address-changed notice not delivered (%s)", delivery)
        }
        return outcome
    }

    private fun swap(
        userId: String,
        presentedAccessToken: String?,
        tenant: ResolvedTenant,
        tokenHash: String,
    ): EmailChangeConfirmOutcome {
        val user = accounts.lock(userId) ?: return EmailChangeConfirmOutcome.SessionEnded
        if (!sessions.isLiveSessionOf(userId, presentedAccessToken)) return EmailChangeConfirmOutcome.SessionEnded
        if (!mailer.canSend(tenant.id)) return EmailChangeConfirmOutcome.MailUnavailable
        if (!membership.belongsToTenant(userId, tenant.id)) return EmailChangeConfirmOutcome.TenantNotFound

        val row = store.findByTokenHash(tokenHash) ?: return EmailChangeConfirmOutcome.Invalid
        // A link opened in another account's session — whoever reads a mailbox somebody mistyped — changes nothing.
        if (row.userId != userId) return EmailChangeConfirmOutcome.Invalid
        if (!store.spend(row.id, Instant.now())) return EmailChangeConfirmOutcome.Invalid

        val holder = accounts.findByEmail(row.newEmail)
        if (holder != null && holder.id != userId) {
            // Taken: undo the spend too, so a refusal changes nothing at all.
            QuarkusTransaction.setRollbackOnly()
            return EmailChangeConfirmOutcome.Invalid
        }
        val oldEmail = user.email
        user.email = normalizeEmail(row.newEmail)
        user.authPublicKey = row.authPublicKey
        // The link proved the new address (ADR 0074 Decision 2). The one writer of the stamp binds it to the address.
        user.confirmAddress(user.email)
        presentedAccessToken?.let { sessions.revokeOtherFamilies(userId, it) }
        return EmailChangeConfirmOutcome.Changed(oldEmail)
    }

    /** The loser's replay question: does another account hold the address this token asked for, now? */
    private fun takenBySomeoneElse(userId: String, tokenHash: String): EmailChangeConfirmOutcome? {
        val row = store.findByTokenHash(tokenHash) ?: return null
        val holder = accounts.findByEmail(row.newEmail) ?: return null
        return if (holder.id != userId) EmailChangeConfirmOutcome.Invalid else null
    }
}
