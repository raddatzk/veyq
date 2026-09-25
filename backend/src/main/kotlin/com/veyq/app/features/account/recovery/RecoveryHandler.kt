package com.veyq.app.features.account.recovery

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.AccountStore
import com.veyq.app.shared.identity.normalizeEmail
import com.veyq.app.shared.membership.MembershipStore
import com.veyq.app.shared.tenant.ResolvedTenant
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Instant

/** What a recovery request produced. The caller answers identically for all but [NO_MAIL_TRANSPORT] — this is for
 *  logging and tests, never for the wire (ADR 0054 § Decision 6). [RecoveryHandler.request] answers
 *  [NO_MAIL_TRANSPORT], [RATE_LIMITED] or [ACCEPTED]; the part it hands off, [RecoveryHandler.deliver], answers
 *  [MAILED], [NO_ACCOUNT] (unknown *in this tenant*) or [SEND_FAILED]. */
enum class RequestOutcome { ACCEPTED, MAILED, NO_ACCOUNT, NO_MAIL_TRANSPORT, SEND_FAILED, RATE_LIMITED }

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
    private val accounts: AccountStore,
    private val membership: MembershipStore,
    private val mailer: RecoveryMailer,
    private val limiter: RecoveryRateLimiter,
    private val sessions: SessionService,
    config: VeyqConfig,
) {
    private val ttl = config.recovery().token().ttl()
    private val log = Logger.getLogger(RecoveryHandler::class.java)

    /**
     * Accept a recovery request, and hand everything that depends on the address to the background.
     *
     * The **order is load-bearing, in three steps**.
     *
     * *Transport first.* Whether this tenant can send mail is a property of the deployment, so it is answered
     * plainly as `503` (ADR 0055 §4) — and it must be answered **before** the limiter, or the pair becomes an
     * address-level oracle: on a tenant that cannot send, a limited request would answer 202 and a fresh one
     * 503, which is exactly "was this address asked about recently" (ADR 0055 §5). Asking first also stops a
     * tenant that cannot send from burning limiter budget. A request whose Host names no tenant has no relay at
     * all and answers the same way.
     *
     * *Then the limiter.* Charged before anything reads the address, so a scraper cannot walk a list for free,
     * and it stays on this side of the hand-off because it is what bounds the queued work (ADR 0054 §6).
     *
     * *Then answer, and do the rest after.* The lookup, the membership check, the mint and the send run in
     * [deliver] on the worker pool, and this returns [RequestOutcome.ACCEPTED] without waiting for it. Doing them
     * inline made the answer's *timing* the oracle §6 forbids: a known address waited for a mint and an SMTP round
     * trip (up to `TenantMailSender.SEND_TIMEOUT_SECONDS` against a slow relay), an unknown one for neither.
     *
     * Every outcome except [RequestOutcome.NO_MAIL_TRANSPORT] is reported to the caller as the same 202.
     */
    fun request(email: String, tenant: ResolvedTenant?): RequestOutcome {
        if (tenant == null || !mailer.canSend(tenant.id)) return RequestOutcome.NO_MAIL_TRANSPORT
        if (limiter.check(email) != null) return RequestOutcome.RATE_LIMITED

        val normalized = normalizeEmail(email)
        Infrastructure.getDefaultWorkerPool().execute {
            // A worker thread carries no request, and the mailer's settings read wants the context a request
            // would have had — so give it one, for exactly this task.
            val context = Arc.container().requestContext()
            context.activate()
            try {
                deliver(normalized, tenant)
            } catch (e: Exception) {
                // deliver() logs its own send failures secret-free; this is what it cannot catch (a DB error).
                // The tenant and the cause only — never the address, its outcome, or a token.
                log.warnf("recovery delivery for tenant %s failed: %s", tenant.id, e.message)
            } finally {
                context.terminate()
            }
        }
        return RequestOutcome.ACCEPTED
    }

    /**
     * The part of a request that depends on the address: mint and mail a link if [email] belongs to an account that
     * is a **member of [tenant]** (ADR 0054 §4). Runs after the caller was answered; returns its outcome
     * synchronously so a test can call it directly and assert the negative cases deterministically.
     *
     * "Member" is any membership row in that tenant ([MembershipStore.isMember]). That is safe to trust here because
     * a tenant owner cannot make an outsider belong: a group add refuses a non-member, so a membership comes only
     * from the person accepting an invite. An account elsewhere — or the tenantless Server Owner — is
     * [RequestOutcome.NO_ACCOUNT]: unknown *here*, which is all this tenant's relay may learn.
     *
     * It must not inherit a transaction, and it runs on a worker thread with no request, so it opens its own: one
     * short read, then the mint, committed before the send (the cr-email-invite-send-hardening discipline) so a
     * hung SMTP server never holds a DB connection and a failed send leaves a usable token.
     */
    fun deliver(email: String, tenant: ResolvedTenant): RequestOutcome {
        val normalized = normalizeEmail(email)
        val user = QuarkusTransaction.requiringNew().call {
            accounts.findByEmail(normalized)?.takeIf { membership.belongsToTenant(it.id, tenant.id) }
        } ?: return RequestOutcome.NO_ACCOUNT

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(ttl)
        QuarkusTransaction.requiringNew().run { store.mint(user.id, normalized, Tokens.hash(token), expiresAt) }

        return when (mailer.sendRecoveryLink(tenant.id, tenant.domain, normalized, token, expiresAt)) {
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
