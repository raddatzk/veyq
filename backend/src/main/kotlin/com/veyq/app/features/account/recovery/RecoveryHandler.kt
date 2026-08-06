package com.veyq.app.features.account.recovery

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.crypto.Tokens
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Instant

/** What a recovery request produced. The caller answers identically for all of them — this is for logging and
 *  tests, never for the wire (ADR 0054 § Decision 6). */
enum class RequestOutcome { MAILED, NO_ACCOUNT, NO_MAIL_TRANSPORT, SEND_FAILED, RATE_LIMITED }

/** The outcome of a completing reset. */
sealed interface ResetOutcome {
    data object Applied : ResetOutcome

    /** The token is unknown, expired or already spent — deliberately one case, not three. */
    data object TokenNotLive : ResetOutcome

    /** The commitment did not reproduce: the caller never opened the wrap, so they do not hold the Secret Key. */
    data object NotProven : ResetOutcome
}

/**
 * Password recovery (cr-account-recovery, ADR 0054).
 *
 * Two factors, and this handler is the seam where they meet: address control (a token only reachable through the
 * mailbox) authorizes the *release*, and reproducing the birth-time commitment authenticates the *actor*. It
 * never sees a password, a Secret Key or a Master Key — all three stay on the device.
 */
@ApplicationScoped
class RecoveryHandler(
    private val store: RecoveryStore,
    private val mailer: RecoveryMailer,
    private val limiter: RecoveryRateLimiter,
    private val sessions: SessionService,
    config: VeyqConfig,
) {
    private val ttl = config.recovery().token().ttl()

    /**
     * Mint and mail a recovery link, if there is an account under [email].
     *
     * The **order is load-bearing, in two steps**.
     *
     * *Transport first.* Whether this tenant can send mail is a property of the deployment, so it is answered
     * plainly as `503` (ADR 0055 §4) — and it must be answered **before** the limiter, or the pair becomes an
     * address-level oracle: on a tenant that cannot send, a limited request would answer 202 and a fresh one
     * 503, which is exactly "was this address asked about recently" (ADR 0055 §5). Asking first also stops a
     * tenant that cannot send from burning limiter budget.
     *
     * *Then limiter, then resolve.* Charging the limiter before the account lookup keeps a known and an unknown
     * address indistinguishable in both response *and* timing, and stops a scraper from walking a list for free.
     * That is the rule ADR 0054 § Decision 6 states, and it is untouched by the hoist above: the transport check
     * reads nothing about the address.
     *
     * The mint commits before the send (the cr-email-invite-send-hardening discipline), so a hung SMTP server
     * never holds a DB connection and a send failure leaves a usable token rather than rolling back a mint the
     * person is already waiting on.
     *
     * Every outcome except [RequestOutcome.NO_MAIL_TRANSPORT] is reported to the caller as the same 202.
     */
    fun request(email: String, domain: String?): RequestOutcome {
        if (domain == null || !mailer.canSend(domain)) return RequestOutcome.NO_MAIL_TRANSPORT
        if (limiter.check(email) != null) return RequestOutcome.RATE_LIMITED

        val normalized = email.trim().lowercase()
        val user = store.findUserByEmail(normalized) ?: return RequestOutcome.NO_ACCOUNT

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(ttl)
        QuarkusTransaction.requiringNew().run { store.mint(user.id, normalized, Tokens.hash(token), expiresAt) }

        return when (mailer.sendRecoveryLink(domain, normalized, token, expiresAt)) {
            com.veyq.app.generated.model.DeliveryResult.SENT -> RequestOutcome.MAILED
            else -> RequestOutcome.SEND_FAILED
        }
    }

    /**
     * Release the RECOVERY wrap against a live token.
     *
     * The token is **not** spent here — it is spent by the completing reset. A wrong Secret Key therefore costs
     * the person nothing but a retry, which matters because the Secret Key is 44 characters they are typing from
     * a printout under stress.
     */
    fun release(token: String): RecoveryMaterialRow? {
        val row = store.findByTokenHash(Tokens.hash(token)) ?: return null
        if (!store.isLive(row, Instant.now())) return null
        return store.recoveryMaterial(row.userId)
    }

    /**
     * Apply the reset, if the commitment proves the caller opened the released wrap.
     *
     * The comparison is constant-time out of habit rather than necessity — the tag is a non-invertible hash of 32
     * random bytes, so a timing leak reveals nothing useful — but a proof check that leaks its progress is the
     * kind of thing that becomes true later when someone changes what is compared.
     */
    fun complete(
        token: String,
        recoveryAuthTag: String,
        wrappedKey: String,
        kdfParams: String,
        authPublicKey: String,
    ): ResetOutcome {
        val row = store.findByTokenHash(Tokens.hash(token)) ?: return ResetOutcome.TokenNotLive
        if (!store.isLive(row, Instant.now())) return ResetOutcome.TokenNotLive

        val expected = store.recoveryTag(row.userId) ?: return ResetOutcome.NotProven
        if (!MessageDigest.isEqual(expected.toByteArray(), recoveryAuthTag.toByteArray())) return ResetOutcome.NotProven

        // One transaction: wrap, auth key, device wraps, sessions, token. A partial apply is a lockout.
        return QuarkusTransaction.requiringNew().call<ResetOutcome> {
            if (!store.applyReset(row.userId, wrappedKey, kdfParams, authPublicKey, row.id)) {
                return@call ResetOutcome.TokenNotLive
            }
            sessions.revokeAllForUser(row.userId)
            ResetOutcome.Applied
        }
    }
}
