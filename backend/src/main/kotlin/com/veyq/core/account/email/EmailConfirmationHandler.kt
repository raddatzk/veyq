package com.veyq.core.account.email

import com.veyq.app.generated.model.DeliveryResult
import com.veyq.core.config.VeyqConfig
import com.veyq.core.crypto.Tokens
import com.veyq.core.http.FailureStatus.NOT_FOUND
import com.veyq.core.http.Failures
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpFailure
import com.veyq.core.identity.VeyqUser
import com.veyq.core.membership.MembershipStore
import com.veyq.core.tenant.ResolvedTenant
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/** The caller's address as the server holds it, and whether it is confirmed. */
data class AccountEmailView(val email: String, val confirmed: Boolean)

/** What a confirmation request produced. */
sealed interface ConfirmationRequestOutcome {
    data object Mailed : ConfirmationRequestOutcome

    /** This tenant cannot send, or the send failed and the fresh token was superseded (ADR 0055 §4, §6). */
    data object MailUnavailable : ConfirmationRequestOutcome, HttpFailure by Failures.mailUnavailable

    /** No tenant answers the `Host`, or the caller is not a member of it: its relay may not carry the link. */
    data object TenantNotFound : ConfirmationRequestOutcome, HttpFailure by Failures.tenantNotFound

    /** Over the per-account send budget — mapped to 429 + Retry-After, nothing minted. */
    data class RateLimited(val retryAfterSeconds: Long) : ConfirmationRequestOutcome
}

/** What presenting a token produced. */
sealed interface ConfirmOutcome {
    data object Confirmed : ConfirmOutcome

    /** Unknown, expired, superseded or spent; minted for another account; or mailed to an address the account no
     *  longer holds — deliberately one answer, and it spends nothing. */
    data object Invalid : ConfirmOutcome, HttpFailure by httpFailure("email_confirmation_invalid", NOT_FOUND)
}

/**
 * Address confirmation (uc.account.confirm-email-address, ADR 0074).
 *
 * Records a **fact**, never a permission: nothing anywhere is refused because an account is unconfirmed
 * (Decision 3). The two other events that set the same record live where they happen — a redeemed MEMBER invite in
 * `TenantAcceptHandler`, a completed reset in `RecoveryStore.applyReset` — and all three stamp through
 * [VeyqUser.confirmAddress], which binds the stamp to the address the proof was mailed to.
 */
@ApplicationScoped
class EmailConfirmationHandler(
    private val store: EmailConfirmationStore,
    private val membership: MembershipStore,
    private val mailer: EmailConfirmationMailer,
    private val limiter: EmailConfirmationRateLimiter,
    config: VeyqConfig,
) {
    private val ttl = config.emailConfirmation().token().ttl()

    @Transactional
    fun get(userId: String): AccountEmailView? =
        VeyqUser.findById(userId)?.let { AccountEmailView(it.email, it.emailConfirmedAt != null) }

    /**
     * Mail a confirmation link to the caller's own address. The order is the contract's:
     *
     * 1. **No tenant, no relay.** A `Host` no tenant answers is the tenant's not-found answer.
     * 2. **Transport** — a tenant that cannot send answers `503` before anything is minted: there is no copy-link
     *    to hand a token to (ADR 0074 Decision 6).
     * 3. **Membership** — a relay carries a link only for its own tenant's members (ADR 0054 §4, the predicate
     *    recovery uses).
     * 4. **Budget**, then the mint, committed **before** the send so a hung relay never holds a DB connection.
     *
     * The send runs synchronously: the caller is the account itself, so there is no address to hide behind a
     * uniform answer, unlike recovery. A send that fails supersedes the fresh token (the ADR 0055 §6
     * compensation) and answers `503` — never a silent `202` for a mail that never left.
     */
    fun request(userId: String, tenant: ResolvedTenant?): ConfirmationRequestOutcome {
        if (tenant == null) return ConfirmationRequestOutcome.TenantNotFound
        // The gates and the mint commit in one short transaction; the send runs after it, outside any.
        val minted = when (val gate = QuarkusTransaction.requiringNew().call<Gate> { gateAndMint(userId, tenant) }) {
            is Gate.Refused -> return gate.outcome
            is Gate.Minted -> gate
        }

        val delivery = mailer.sendConfirmationLink(tenant.id, tenant.domain, minted.address, minted.token, minted.expiresAt)
        if (delivery != DeliveryResult.SENT) {
            // FAILED and FALLBACK_COPYLINK alike: the latter is unreachable past canSend, and treating it as a
            // failure means a hole in that gate loses a token loudly instead of leaving one with no channel.
            store.supersede(minted.rowId)
            return ConfirmationRequestOutcome.MailUnavailable
        }
        return ConfirmationRequestOutcome.Mailed
    }

    private fun gateAndMint(userId: String, tenant: ResolvedTenant): Gate {
        if (!mailer.canSend(tenant.id)) return Gate.Refused(ConfirmationRequestOutcome.MailUnavailable)
        if (!membership.belongsToTenant(userId, tenant.id)) return Gate.Refused(ConfirmationRequestOutcome.TenantNotFound)
        limiter.check(userId)?.let { return Gate.Refused(ConfirmationRequestOutcome.RateLimited(it)) }
        val address = VeyqUser.findById(userId)?.email ?: return Gate.Refused(ConfirmationRequestOutcome.TenantNotFound)
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(ttl)
        return Gate.Minted(store.mint(userId, address, Tokens.hash(token), expiresAt), address, token, expiresAt)
    }

    /** The gates' answer: a refusal, or a committed mint waiting for its send. */
    private sealed interface Gate {
        data class Refused(val outcome: ConfirmationRequestOutcome) : Gate
        data class Minted(val rowId: String, val address: String, val token: String, val expiresAt: Instant) : Gate
    }

    /**
     * Confirm with a mailed token. Every check runs **before** the spend, so a refusal consumes nothing and the
     * token stays live for its owner (A5).
     *
     * The session requirement is the point (ADR 0074 Decision 5): the proof this records is that *the account
     * holder* reads the mailbox. A token presented by any other account — whoever reads a mistyped mailbox — is
     * refused exactly like an unknown one.
     */
    @Transactional
    fun confirm(userId: String, token: String): ConfirmOutcome {
        val row = store.findByTokenHash(Tokens.hash(token)) ?: return ConfirmOutcome.Invalid
        if (!store.isLive(row, Instant.now())) return ConfirmOutcome.Invalid
        if (row.userId != userId) return ConfirmOutcome.Invalid
        val user = VeyqUser.findById(userId) ?: return ConfirmOutcome.Invalid
        if (user.email != row.email) return ConfirmOutcome.Invalid
        // The liveness read above already refuses a replay; this compare-and-set decides only the race that read
        // cannot see (two confirmations that both read the row live). A test of it must therefore spend one row
        // twice at the store — through this method, removing the CAS changes no observable answer.
        if (!store.consume(row.id)) return ConfirmOutcome.Invalid
        user.confirmAddress(row.email)
        return ConfirmOutcome.Confirmed
    }
}
