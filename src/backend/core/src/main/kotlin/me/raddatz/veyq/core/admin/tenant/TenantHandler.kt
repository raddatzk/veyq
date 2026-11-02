package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.quota.StorageUsage
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.spaces.scope.MembershipPolicy
import me.raddatz.veyq.core.spaces.scope.ScopesStore
import me.raddatz.veyq.core.tasks.TasksStore
import me.raddatz.veyq.core.generated.model.DeliveryResult
import me.raddatz.veyq.core.blobstore.StoreSettings
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.normalizeEmail
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.tenant.TenantIngressProjector
import me.raddatz.veyq.core.moderation.cases.SignInBlocks
import me.raddatz.veyq.core.moderation.statement.StatementMailing
import me.raddatz.veyq.core.moderation.statement.StepStatementInput
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant

/** How far a member invite's signed expiry may lie from the server's own lifetime (clock skew, the client's work). */
private val EXPIRY_TOLERANCE: Duration = Duration.ofMinutes(1)

/** Result of a create-tenant attempt. `delivery` reports how the invite reached the owner (cr-email-invite-send-hardening). */
sealed interface CreateTenantOutcome {
    data class Created(val tenantId: String, val expiresAt: Instant, val bootstrapSecret: String, val delivery: DeliveryResult) : CreateTenantOutcome
    data object DomainTaken : CreateTenantOutcome, HttpFailure by httpFailure("domain_taken", FailureStatus.CONFLICT)

    /** No object store has passed its check yet (ADR 0089 decision 6): a tenant is the first thing that stores blobs. */
    data object StoreNotReady : CreateTenantOutcome, HttpFailure by httpFailure("store_not_ready", FailureStatus.CONFLICT)
}

/** Result of a resend/reassign on a pending tenant's invite. */
sealed interface InviteOutcome {
    data class Done(val invite: InviteView, val delivery: DeliveryResult) : InviteOutcome
    data object NotFound : InviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotPending : InviteOutcome, HttpFailure by httpFailure("tenant_not_pending", FailureStatus.CONFLICT)
}

/** Result of revoking a pending tenant. */
sealed interface RevokeOutcome {
    data object Done : RevokeOutcome
    data object NotFound : RevokeOutcome, HttpFailure by Failures.tenantNotFound
    data object NotPending : RevokeOutcome, HttpFailure by httpFailure("tenant_not_pending", FailureStatus.CONFLICT)
}

/**
 * Result of moving a tenant to a different domain (uc.admin.create-tenant).
 *
 * [NotPendingOrActive] is a SUSPENDED tenant, and it deliberately does **not** reuse `Failures.tenantSuspended`:
 * that one is the request-boundary refusal (403, pre-authentication, on the tenant's *own* domain) and a client
 * that saw the same code here would read "this space is offline, sign out" for what is really "reactivate it
 * first". One wire code, one status.
 */
sealed interface ChangeDomainOutcome {
    data object Done : ChangeDomainOutcome
    data object NotFound : ChangeDomainOutcome, HttpFailure by Failures.tenantNotFound
    data object DomainTaken : ChangeDomainOutcome, HttpFailure by httpFailure("domain_taken", FailureStatus.CONFLICT)
    data object NotPendingOrActive : ChangeDomainOutcome, HttpFailure by httpFailure("tenant_not_pending_or_active", FailureStatus.CONFLICT)
}

/** Result of suspending an ACTIVE tenant (ADR 0066). Availability only — no key, grant or content row moves. */
sealed interface SuspendOutcome {
    /** Taken offline; over content, whom its statement — mailed after the commit — reached (null without one). */
    data class Done(val statement: StatementMailing? = null) : SuspendOutcome
    data object NotFound : SuspendOutcome, HttpFailure by Failures.tenantNotFound
    data object NotActive : SuspendOutcome, HttpFailure by httpFailure("tenant_not_active", FailureStatus.CONFLICT)

    /** The statement a suspension over content carries was refused (ADR 0093 § 7) — nothing was written. */
    data class Refused(val failure: HttpFailure) : SuspendOutcome, HttpFailure by failure
}

/** Result of reactivating a SUSPENDED tenant (ADR 0066) — the exact inverse of [SuspendOutcome]. */
sealed interface ReactivateOutcome {
    data object Done : ReactivateOutcome
    data object NotFound : ReactivateOutcome, HttpFailure by Failures.tenantNotFound
    data object NotSuspended : ReactivateOutcome, HttpFailure by httpFailure("tenant_not_suspended", FailureStatus.CONFLICT)
}

/** Result of the Server Owner deleting an ACTIVE or SUSPENDED tenant (ADR 0101 decision 10). */
sealed interface DeleteTenantOutcome {
    /** Deleted; over content, whom its statement — mailed after the commit — reached (null without one). */
    data class Deleted(val dueAt: Instant, val statement: StatementMailing? = null) : DeleteTenantOutcome
    data object NotFound : DeleteTenantOutcome, HttpFailure by Failures.tenantNotFound
    data object DomainMismatch : DeleteTenantOutcome, HttpFailure by httpFailure("tenant_domain_mismatch", FailureStatus.CONFLICT)
    data object NotDeletable : DeleteTenantOutcome, HttpFailure by httpFailure("tenant_not_deletable", FailureStatus.CONFLICT)
    /** Over content without the statement it must carry (ADR 0093 § 7), or a statement the operating mode refuses. */
    data class Refused(val failure: HttpFailure) : DeleteTenantOutcome, HttpFailure by failure
}

/** Result of stopping a pending tenant deletion — the tenant returns SUSPENDED. */
sealed interface StopTenantDeletionOutcome {
    data object Done : StopTenantDeletionOutcome
    data object NotFound : StopTenantDeletionOutcome, HttpFailure by Failures.tenantNotFound
    data object NotPendingDeletion : StopTenantDeletionOutcome, HttpFailure by httpFailure("tenant_not_pending_deletion", FailureStatus.CONFLICT)
}

/** Result of a tenant owner setting a member's TENANT-scope role ("make owner", ADR 0101 decision 9). */
sealed interface SetTenantRoleOutcome {
    data object Done : SetTenantRoleOutcome
    data object Suspended : SetTenantRoleOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : SetTenantRoleOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : SetTenantRoleOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object MemberNotFound : SetTenantRoleOutcome, HttpFailure by Failures.memberNotFound
    data object LastOwner : SetTenantRoleOutcome, HttpFailure by Failures.lastOwner
}

/** Result of the Server Owner inviting a member of an ACTIVE tenant to own it (ADR 0101 decision 9). */
sealed interface OwnerInviteOutcome {
    data class Done(val invite: InviteView, val delivery: DeliveryResult) : OwnerInviteOutcome
    data object NotFound : OwnerInviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotActive : OwnerInviteOutcome, HttpFailure by httpFailure("tenant_not_active", FailureStatus.CONFLICT)
    data object NotMember : OwnerInviteOutcome, HttpFailure by httpFailure("not_tenant_member", FailureStatus.CONFLICT)
}

/** Result of a tenant owner inviting a member by email (cr-0077). */
sealed interface MemberInviteOutcome {
    data class Done(val invite: InviteView, val delivery: DeliveryResult) : MemberInviteOutcome
    data object Suspended : MemberInviteOutcome, HttpFailure by Failures.tenantSuspended
    /** Over the invite-mail rate limit (cr-email-invite-send-hardening) — mapped to 429 + Retry-After, no mint. */
    data class RateLimited(val retryAfterSeconds: Long) : MemberInviteOutcome
    data object NotFound : MemberInviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : MemberInviteOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object NotActive : MemberInviteOutcome, HttpFailure by httpFailure("tenant_not_active", FailureStatus.CONFLICT)
    data object AlreadyMember : MemberInviteOutcome, HttpFailure by Failures.alreadyMember
    /** The invite's grant is of an epoch that is no longer the tenant's current one (ADR 0111 decision 2). */
    data object StaleEpoch : MemberInviteOutcome, HttpFailure by Failures.staleEpoch
    /** The client-minted invite id is taken. */
    data object IdTaken : MemberInviteOutcome, HttpFailure by Failures.idTaken
    /** The grant's `recipientIdentityDigest` is not the invite key's. */
    data object RecipientKeyNotCurrent : MemberInviteOutcome, HttpFailure by Failures.recipientKeyNotCurrent
    /** `sealedLink` is not base64 — the shape's 400, which the contract's own bounds refuse first. */
    data object Invalid : MemberInviteOutcome, HttpFailure by Failures.invalidRequest
    /** `expiresAt` is not the server's invite lifetime from now, within a minute ([InviteFailures.expiryInvalid]). */
    data object ExpiryInvalid : MemberInviteOutcome, HttpFailure by InviteFailures.expiryInvalid
    /** The owner's signature over the invite key does not verify under the caller's identity key ([InviteFailures.signatureInvalid]). */
    data object SignatureInvalid : MemberInviteOutcome, HttpFailure by InviteFailures.signatureInvalid

    /** The tenant cannot send mail, and a member invite has no other channel (ADR 0055 §2) — nothing is minted. */
    data object MailUnavailable : MemberInviteOutcome, HttpFailure by Failures.mailUnavailable

    /** SMTP was configured but the send errored, so the minted invite was revoked again (ADR 0055 §6). Retryable.
     *  Slice-local code (only this slice mints invites), so it is written inline rather than in [Failures]. */
    data object SendFailed : MemberInviteOutcome, HttpFailure by httpFailure("mail_send_failed", FailureStatus.SERVICE_UNAVAILABLE)
}

/**
 * A member invite as the owner's client built it (ADR 0111 decisions 1-2): the email, the invite key, the owner's
 * signature over it, a grant of the tenant's current epoch to it (naming the key it is wrapped to), the expiry the owner
 * signed, and the link's sealed part (standard base64), which is mailed and forgotten.
 */
data class MemberInviteInput(
    val email: String,
    val inviteId: String,
    val invitePublicKey: String,
    val inviteKeySignature: String,
    val expiresAt: Instant,
    val sealedLink: String,
    val epoch: Int,
    val grant: SignedGrant,
    val recipientIdentityDigest: String,
)

/** Result of listing a tenant's pending member invites (cr-0082). */
sealed interface MemberInvitesOutcome {
    data class Listed(val invites: List<PendingInviteRow>) : MemberInvitesOutcome
    data object Suspended : MemberInvitesOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : MemberInvitesOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : MemberInvitesOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/**
 * What a member invite's material is refused for against what the server holds or computes (ADR 0111 decisions 1-2):
 * rules no schema states, so each is a `422` of its own, never the shape's `400` (`tests/fuzz/schemathesis.toml`'s
 * positive acceptance: a 400 is a request the contract itself refuses).
 */
object InviteFailures {
    /** `expiresAt` is not the server's invite lifetime from now, within a minute: the owner signs the expiry, so the server
     *  takes the client's, but only near its own. */
    val expiryInvalid = httpFailure("invite_expiry_invalid", FailureStatus.UNPROCESSABLE)
    /** `inviteKeySignature` does not verify under the caller's identity key over the invite's statement, whose expiry is
     *  whole Unix seconds — so an `expiresAt` with a fraction of a second is one too. */
    val signatureInvalid = httpFailure("invite_signature_invalid", FailureStatus.UNPROCESSABLE)
    /** A reissue's `email` is not the reissued invite's: a reissue re-sends to the same person. */
    val emailMismatch = httpFailure("invite_email_mismatch", FailureStatus.UNPROCESSABLE)
}

/** Result of re-issuing a pending member invite (cr-0082). */
sealed interface ReissueOutcome {
    data class Done(val invite: InviteView, val delivery: DeliveryResult) : ReissueOutcome
    data object Suspended : ReissueOutcome, HttpFailure by Failures.tenantSuspended
    /** Over the invite-mail rate limit (cr-email-invite-send-hardening) — mapped to 429 + Retry-After, no mint. */
    data class RateLimited(val retryAfterSeconds: Long) : ReissueOutcome
    data object NotFound : ReissueOutcome, HttpFailure by Failures.tenantNotFound        // no such tenant
    data object NotOwner : ReissueOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object InviteNotFound : ReissueOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)  // no such pending invite for this tenant
    /** `sealedLink` is not base64 — the shape's 400, which the contract's own bounds refuse first. */
    data object InvalidRequest : ReissueOutcome, HttpFailure by Failures.invalidRequest
    data object EmailMismatch : ReissueOutcome, HttpFailure by InviteFailures.emailMismatch
    data object ExpiryInvalid : ReissueOutcome, HttpFailure by InviteFailures.expiryInvalid
    data object SignatureInvalid : ReissueOutcome, HttpFailure by InviteFailures.signatureInvalid
    data object StaleEpoch : ReissueOutcome, HttpFailure by Failures.staleEpoch
    data object IdTaken : ReissueOutcome, HttpFailure by Failures.idTaken
    data object RecipientKeyNotCurrent : ReissueOutcome, HttpFailure by Failures.recipientKeyNotCurrent

    /** No sendable mailer ⇒ nothing minted, and the outstanding invite still stands (ADR 0055 §2). */
    data object MailUnavailable : ReissueOutcome, HttpFailure by Failures.mailUnavailable

    /** The send errored ⇒ the fresh invite is revoked; the superseded one is **not** restored (ADR 0055 §6). */
    data object SendFailed : ReissueOutcome, HttpFailure by httpFailure("mail_send_failed", FailureStatus.SERVICE_UNAVAILABLE)
}

/** Result of revoking a pending member invite (cr-0082). */
sealed interface RevokeMemberInviteOutcome {
    data object Done : RevokeMemberInviteOutcome
    data object Suspended : RevokeMemberInviteOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : RevokeMemberInviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : RevokeMemberInviteOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object InviteNotFound : RevokeMemberInviteOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
}

/** Result of a tenant owner removing a member — the ACL-revoke cascade + orphan rule (cr-0091). */
sealed interface RemoveTenantMemberOutcome {
    data object Ok : RemoveTenantMemberOutcome
    data object Suspended : RemoveTenantMemberOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : RemoveTenantMemberOutcome, HttpFailure by Failures.tenantNotFound  // no such tenant, or the caller is not a member (indistinguishable)
    data object NotOwner : RemoveTenantMemberOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)       // caller is not the tenant OWNER
    data object MemberNotFound : RemoveTenantMemberOutcome, HttpFailure by Failures.memberNotFound  // target holds no tenant-scope membership
    data object LastOwner : RemoveTenantMemberOutcome, HttpFailure by Failures.lastOwner       // target is the last tenant OWNER
}

/**
 * Server-Owner tenant administration, structural half (uc.admin.create-tenant Slice A, cr-0009). Purely
 * fachlich: authentication + the Server-Owner gate are central (`@RolesAllowed` on [TenantsResource] →
 * `SecurityIdentity`, cr-0012), so the handler only sees already-authorized calls. It mints the hashed bootstrap
 * invite (only the hash is stored; the raw token goes to the [TenantInviteMailer] for the link) and keeps the
 * Resource a thin HTTP adapter (ADR 0001). No content crypto, no scope key — activation is Slice B. The caller's
 * identity is not recorded (Slice A tracks no `created_by`), so no userId is threaded in.
 */
@ApplicationScoped
class TenantHandler(
    private val store: TenantStore,
    private val scopes: ScopesStore,
    private val tasks: TasksStore,
    private val mailer: TenantInviteMailer,
    private val inviteRateLimiter: InviteMailRateLimiter,
    private val shares: SharingStore,
    private val ingress: TenantIngressProjector,
    private val accounts: AccountStore,
    private val profiles: TenantProfileStore,
    private val access: ScopeAccess,
    private val quota: StorageUsage,
    private val storeSettings: StoreSettings,
    private val moderationSteps: SignInBlocks,
    private val deletionMail: TenantDeletionMail,
    config: VeyqConfig,
) {
    private val inviteTtl: Duration = config.tenant().invite().ttl()
    private val deletionRetention: Duration = config.scope().delete().retention()
    private val maxAttempts: Int = config.tasks().maxAttempts()

    /**
     * A minted invite is committed **then** sent: the send is a blocking network call that must not run under an
     * open DB transaction (a hung SMTP server would hold a pooled connection, cr-email-invite-send-hardening). So
     * the mint runs in an explicit [QuarkusTransaction] block and the mailer is called **after** it commits —
     * never as a self-invoked `@Transactional` method, which would silently share this call's transaction
     * (CDI self-invocation bypasses the proxy). The tx block returns either a terminal [MintResult.Rejected]
     * (guards / rate-limit tripped — nothing to send) or a [MintResult.ToSend] payload whose `build` folds the
     * [DeliveryResult] into the final outcome.
     */
    private sealed interface MintResult<out O> {
        data class Rejected<O>(val outcome: O) : MintResult<O>
        data class ToSend<O>(
            val tenantId: String,
            val domain: String,
            val recipient: String,
            val token: String,
            val expiresAt: Instant,
            val build: (DeliveryResult) -> O,
        ) : MintResult<O>
    }

    private fun <O> commitThenSend(mint: () -> MintResult<O>): O =
        when (val r = QuarkusTransaction.requiringNew().call(mint)) {
            is MintResult.Rejected -> r.outcome
            is MintResult.ToSend -> r.build(mailer.sendInvite(r.tenantId, r.domain, r.recipient, r.token, r.expiresAt))
        }

    fun create(name: String, domain: String, ownerEmail: String): CreateTenantOutcome {
        val normalizedDomain = domain.lowercase()
        val normalizedEmail = normalizeEmail(ownerEmail)
        val outcome = commitThenSend {
            if (!storeSettings.readyForTenants()) return@commitThenSend MintResult.Rejected(CreateTenantOutcome.StoreNotReady)
            if (store.domainExists(normalizedDomain)) return@commitThenSend MintResult.Rejected(CreateTenantOutcome.DomainTaken)
            val token = Tokens.random()
            val expiresAt = Instant.now().plus(inviteTtl)
            val (tenantId, expiry) = store.createPendingTenant(name, normalizedDomain, normalizedEmail, Tokens.hash(token), expiresAt)
            // Return the raw token so the Server Owner can copy the invite link (works without a mailer, cr-0080).
            MintResult.ToSend(tenantId, normalizedDomain, normalizedEmail, token, expiry) { delivery ->
                CreateTenantOutcome.Created(tenantId, expiry, token, delivery)
            }
        }
        if (outcome is CreateTenantOutcome.Created) reproject()
        return outcome
    }

    /** Every tenant with its storage usage — one grouped read from [StorageUsage], never one query per tenant. */
    @Transactional
    fun list(): List<TenantView> = store.listAll(quota.usageByTenant())

    /** Resend: re-mint to the SAME owner email. */
    fun resendInvite(tenantId: String): InviteOutcome = commitThenSend {
        val tenant = store.findTenant(tenantId) ?: return@commitThenSend MintResult.Rejected(InviteOutcome.NotFound)
        if (tenant.status != TenantEntity.PENDING) return@commitThenSend MintResult.Rejected(InviteOutcome.NotPending)
        mintToSend(tenant.id, tenant.ownerEmail, tenant.domain)
    }

    /** Reassign: re-bind to a NEW owner email, then re-mint. */
    fun reassignInvite(tenantId: String, newOwnerEmail: String): InviteOutcome = commitThenSend {
        val tenant = store.findTenant(tenantId) ?: return@commitThenSend MintResult.Rejected(InviteOutcome.NotFound)
        if (tenant.status != TenantEntity.PENDING) return@commitThenSend MintResult.Rejected(InviteOutcome.NotPending)
        val email = normalizeEmail(newOwnerEmail)
        store.updateOwnerEmail(tenant.id, email)
        mintToSend(tenant.id, email, tenant.domain)
    }

    fun revoke(tenantId: String): RevokeOutcome {
        val outcome = QuarkusTransaction.requiringNew().call {
            val tenant = store.findTenant(tenantId) ?: return@call RevokeOutcome.NotFound
            if (tenant.status != TenantEntity.PENDING) return@call RevokeOutcome.NotPending
            store.deleteTenant(tenantId)
            RevokeOutcome.Done
        }
        if (outcome is RevokeOutcome.Done) reproject()
        return outcome
    }

    /**
     * Move a tenant to a different domain (uc.admin.create-tenant). Unlike resend/reassign/revoke this is **not**
     * PENDING-only: a domain is how a tenant is *reached*, not part of its invite lifecycle, so a live tenant may
     * need to move exactly as often as a pending one. SUSPENDED is refused — reactivate first; changing the domain
     * of a tenant whose whole point is "leave it alone" only adds a state to reason about.
     *
     * The free-domain check and the write share one transaction so two concurrent moves onto the same domain
     * cannot both pass their check; the column's UNIQUE is the backstop behind that, not the primary guard.
     * Domain-taken is decided **before** the state check on purpose: it is the answer that does not depend on
     * which tenant you are looking at.
     */
    fun changeDomain(tenantId: String, domain: String): ChangeDomainOutcome {
        val normalized = domain.lowercase()
        val outcome = QuarkusTransaction.requiringNew().call {
            val tenant = store.findTenant(tenantId) ?: return@call ChangeDomainOutcome.NotFound
            if (tenant.domain == normalized) return@call ChangeDomainOutcome.Done // idempotent: nothing to move
            if (store.domainExists(normalized)) return@call ChangeDomainOutcome.DomainTaken
            if (store.changeDomain(tenantId, normalized)) ChangeDomainOutcome.Done else ChangeDomainOutcome.NotPendingOrActive
        }
        if (outcome is ChangeDomainOutcome.Done) reproject()
        return outcome
    }

    /**
     * Take an ACTIVE tenant offline (uc.admin.suspend-tenant, ADR 0066). Deliberately **not** `revoke`'s shape:
     * that hard-deletes a tenant nothing was ever behind, this one preserves everything and changes only
     * reachability — the lockout itself is the scope-access gate every operation on the tenant asks (from any
     * domain), plus `TenantSuspensionFilter` on the tenant's own domain; both read the very row this writes.
     *
     * Nothing else happens here on purpose. In particular **no session is revoked**: sessions are account-global,
     * not tenant-scoped (ADR 0004 — `SessionFamilyEntity` carries no tenantId), so revoking a member's sessions
     * to lock them out of *this* tenant would sign them out of every other tenant they belong to as well. The
     * gate keys on the tenant an operation acts on, so it already refuses every such request the instant the status
     * flips; a still-valid token against a suspended tenant is not a defect to patch here.
     *
     * The existence probe only separates 404 from 409 — the transition itself is the store's guarded UPDATE.
     *
     * The one thing that *does* follow is [reproject]: a suspended tenant drops out of the routing projection
     * (ADR 0063). That is a **secondary** effect and not the lockout — the gate is, and it is what still refuses a
     * request that reaches this instance by any other route while the file catches up.
     */
    fun suspend(
        tenantId: String, serverOwnerId: String, overContent: Boolean = false, statement: StepStatementInput? = null,
        now: Instant = Instant.now(),
    ): SuspendOutcome {
        val outcome = QuarkusTransaction.requiringNew().call {
            store.findTenant(tenantId) ?: return@call SuspendOutcome.NotFound
            // Over content, the suspension carries its statement of reasons (ADR 0093 § 7): refused before anything flips.
            moderationSteps.offlineRefusal(tenantId, overContent, statement)?.let { return@call SuspendOutcome.Refused(it) }
            if (!store.suspend(tenantId)) return@call SuspendOutcome.NotActive
            // A moderator's proposal to take this tenant offline is taken by the suspension itself (uc.admin.suspend-tenant A4);
            // one over content is recorded as a measure with its statement.
            moderationSteps.takenOffline(tenantId, serverOwnerId, statement, now)
            SuspendOutcome.Done()
        }
        if (outcome is SuspendOutcome.Done) {
            reproject()
            // The suspension stands whatever the mail reached; the answer reports it (ADR 0093 § 7).
            return SuspendOutcome.Done(statement?.let { moderationSteps.mailStatement(it) })
        }
        return outcome
    }

    /** Bring a SUSPENDED tenant back (ADR 0066): no key, grant or content row was torn down, so none is rebuilt —
     *  the gate simply stops firing. The routing projection is the exception, and only because suspension dropped
     *  the tenant from it: [reproject] puts its routers back. Same 404-vs-409 shape as [suspend]. */
    fun reactivate(tenantId: String, serverOwnerId: String, now: Instant = Instant.now()): ReactivateOutcome {
        val outcome = QuarkusTransaction.requiringNew().call {
            store.findTenant(tenantId) ?: return@call ReactivateOutcome.NotFound
            if (!store.reactivate(tenantId)) return@call ReactivateOutcome.NotSuspended
            // A suspension over content was recorded as a measure (ADR 0093 § 7): reactivating lifts it.
            moderationSteps.backOnline(tenantId, serverOwnerId, now)
            ReactivateOutcome.Done
        }
        if (outcome is ReactivateOutcome.Done) reproject()
        return outcome
    }

    /**
     * Delete an ACTIVE or SUSPENDED tenant (uc.admin.delete-tenant, ADR 0101 decision 10). It goes offline exactly as a
     * suspension takes it — the scope-access gate and the domain filter refuse `PENDING_DELETION` as they refuse
     * `SUSPENDED` — and [reproject] withdraws its routers; `TenantPurge` erases it after the scope trash's retention.
     * The domain the confirmation typed must be this tenant's, so a wrong id deletes nothing. A moderator's proposal to
     * take the tenant offline is taken by the deletion, as by a suspension. Every member is mailed after the commit;
     * every account stays (ADR 0101 decision 10).
     */
    fun delete(
        tenantId: String, domain: String, serverOwnerId: String, overContent: Boolean = false, statement: StepStatementInput? = null,
        now: Instant = Instant.now(),
    ): DeleteTenantOutcome {
        val dueAt = now.plus(deletionRetention)
        val outcome = QuarkusTransaction.requiringNew().call {
            val tenant = store.findTenant(tenantId) ?: return@call DeleteTenantOutcome.NotFound
            if (tenant.domain != domain.trim().lowercase()) return@call DeleteTenantOutcome.DomainMismatch
            if (tenant.status != TenantEntity.ACTIVE && tenant.status != TenantEntity.SUSPENDED) return@call DeleteTenantOutcome.NotDeletable
            // Over content, the deletion carries its statement of reasons to the tenant's owners (ADR 0093 § 7, DSA Art.
            // 17(1)(c)): refused before anything flips, as a suspension's is.
            moderationSteps.offlineRefusal(tenantId, overContent, statement)?.let { return@call DeleteTenantOutcome.Refused(it) }
            if (!store.markForDeletion(tenantId, dueAt)) return@call DeleteTenantOutcome.NotDeletable
            // A moderator's proposal to take the tenant offline is taken by the deletion; over content it is recorded as a
            // TENANT_DELETED measure with its statement, which a stop lifts.
            moderationSteps.deleted(tenantId, serverOwnerId, statement, now)
            DeleteTenantOutcome.Deleted(dueAt)
        }
        if (outcome !is DeleteTenantOutcome.Deleted) return outcome
        reproject()
        deletionMail.tenantDeleted(tenantId, dueAt)
        // The deletion stands whatever the mail reached; the answer reports it (ADR 0093 § 7).
        return outcome.copy(statement = statement?.let { moderationSteps.mailStatement(it) })
    }

    /** Stop a pending deletion (ADR 0101 decision 10): the tenant returns SUSPENDED and is reactivated as one is. */
    fun stopDeletion(tenantId: String, serverOwnerId: String, now: Instant = Instant.now()): StopTenantDeletionOutcome {
        val outcome = QuarkusTransaction.requiringNew().call {
            store.findTenant(tenantId) ?: return@call StopTenantDeletionOutcome.NotFound
            if (!store.stopDeletion(tenantId)) return@call StopTenantDeletionOutcome.NotPendingDeletion
            // A deletion over content is lifted with the stop; a suspension's measure, if one stands, stays until reactivated.
            moderationSteps.deletionStopped(tenantId, serverOwnerId, now)
            StopTenantDeletionOutcome.Done
        }
        if (outcome is StopTenantDeletionOutcome.Done) reproject()
        return outcome
    }

    /**
     * A tenant **owner** sets one member's TENANT-scope role — "make owner" (ADR 0101 decision 9). Membership-gated
     * first, then owner-gated; demoting the last OWNER is refused. A pure role change: every member holds the tenant's
     * key already. Decided under the tenant scope's lock, which a removal and every other role change take, so two
     * owners demoting each other cannot both pass the last-owner count.
     */
    fun setMemberRole(callerId: String, tenantId: String, targetId: String, role: String): SetTenantRoleOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(SetTenantRoleOutcome.NotFound, SetTenantRoleOutcome.Suspended)?.let { return it }
        return QuarkusTransaction.requiringNew().call {
            scopes.lockScope(tenantId)
            if (!store.isOwner(callerId, tenantId)) return@call SetTenantRoleOutcome.NotOwner
            val current = MembershipEntity.find("userId = ?1 and scopeId = ?2 and pendingConsent = false", targetId, tenantId)
                .firstResult()?.role ?: return@call SetTenantRoleOutcome.MemberNotFound
            if (MembershipPolicy.isLastOwnerDemote(current, role, store.ownerCount(tenantId))) return@call SetTenantRoleOutcome.LastOwner
            store.setTenantRole(tenantId, targetId, role)
            SetTenantRoleOutcome.Done
        }
    }

    /**
     * The Server Owner invites a **member** of an ACTIVE tenant to own it (ADR 0101 decision 9) — the way out of an
     * ownerless tenant. Only a member: the operator can admit nobody into the tenant's key (ADR 0020 move 3), so an
     * owner from outside would wait for a key no member could vouch for. Committed then sent, as setup's invite is.
     */
    fun inviteOwner(tenantId: String, email: String): OwnerInviteOutcome = commitThenSend {
        val tenant = store.findTenant(tenantId) ?: return@commitThenSend MintResult.Rejected(OwnerInviteOutcome.NotFound)
        if (tenant.status != TenantEntity.ACTIVE) return@commitThenSend MintResult.Rejected(OwnerInviteOutcome.NotActive)
        val member = accounts.findByEmail(normalizeEmail(email))?.takeIf { store.isTenantMember(it.id, tenantId) }
            ?: return@commitThenSend MintResult.Rejected(OwnerInviteOutcome.NotMember)
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val invite = store.mintOwnerInvite(tenantId, member.email, Tokens.hash(token), expiresAt)
        MintResult.ToSend(tenant.id, tenant.domain, member.email, token, expiresAt) { delivery ->
            OwnerInviteOutcome.Done(invite.copy(bootstrapSecret = token), delivery)
        }
    }

    /**
     * A tenant **owner** invites a person (by email) into the ACTIVE tenant as a MEMBER (cr-0077, ADR 0111 decisions
     * 1-2). Owner-gated (the caller must hold the tenant's OWNER membership); the invitee onboards via the same accept
     * flow with the invite's MEMBER role. The invite is a **placeholder member**: it stores the invite key the owner's
     * client minted and signed, and the grant of the tenant's current epoch wrapped to it — so the newcomer grants
     * itself at accept and nobody waits for a key-holder. The link's sealed part is appended to the mailed link and
     * kept nowhere; the code that opens it never reaches the server.
     */
    fun inviteMember(callerId: String, tenantId: String, input: MemberInviteInput): MemberInviteOutcome = commitThenSend {
        access.check(callerId, tenantId, WRITE)
            .refusal(MemberInviteOutcome.NotFound, MemberInviteOutcome.Suspended)?.let { return@commitThenSend MintResult.Rejected(it) }
        val tenant = store.findTenant(tenantId) ?: return@commitThenSend MintResult.Rejected(MemberInviteOutcome.NotFound)
        if (!store.isOwner(callerId, tenantId)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.NotOwner)
        if (tenant.status != TenantEntity.ACTIVE) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.NotActive)
        val normalizedEmail = normalizeEmail(input.email)
        val existing = accounts.findByEmail(normalizedEmail)
        if (existing != null && store.belongsToTenant(existing.id, tenantId)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.AlreadyMember)
        if (!expiryIsTheLifetime(input.expiresAt)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.ExpiryInvalid)
        if (!signedByCaller(callerId, tenantId, input)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.SignatureInvalid)
        if (!InviteAcceptProof.digestMatches(input.invitePublicKey, input.recipientIdentityDigest)) {
            return@commitThenSend MintResult.Rejected(MemberInviteOutcome.RecipientKeyNotCurrent)
        }
        val fragmentTail = linkTail(input.sealedLink) ?: return@commitThenSend MintResult.Rejected(MemberInviteOutcome.Invalid)
        // Mail is the only channel for a member invite (ADR 0055 §2), so ask *before* minting: an invitation that
        // cannot be delivered is a live secret with no recipient, and there is no copy-link to hand it to.
        if (!mailer.canSend(tenant.id)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.MailUnavailable)
        // Authorized owner + real recipient ⇒ enforce the anti-bombing limit before minting (no orphan on a 429).
        inviteRateLimiter.check(tenantId, normalizedEmail)?.let { return@commitThenSend MintResult.Rejected(MemberInviteOutcome.RateLimited(it)) }

        val token = Tokens.random()
        val expiresAt = input.expiresAt
        val inviteId = when (val minted = store.mintMemberInvite(tenantId, normalizedEmail, Tokens.hash(token), expiresAt, inviteKey(callerId, input))) {
            is MintMemberInvite.Minted -> minted.inviteId
            MintMemberInvite.StaleEpoch -> return@commitThenSend MintResult.Rejected(MemberInviteOutcome.StaleEpoch)
            MintMemberInvite.IdTaken -> return@commitThenSend MintResult.Rejected(MemberInviteOutcome.IdTaken)
        }
        MintResult.ToSend(tenant.id, tenant.domain, normalizedEmail, "$token.$fragmentTail", expiresAt) { delivery ->
            if (delivery != DeliveryResult.SENT) {
                revokeUndelivered(inviteId)
                MemberInviteOutcome.SendFailed
            } else {
                MemberInviteOutcome.Done(InviteView(normalizedEmail, TenantInviteEntity.ACTIVE, expiresAt, token), delivery)
            }
        }
    }

    /**
     * Undo a member invite whose mail never left: soft-revoke it (→ SUPERSEDED) so its secret is dead at once.
     *
     * Three properties are deliberate (ADR 0055 §6). It is a **compensation, not a rollback** — the mint commits
     * before the send so a hung SMTP server cannot hold a pooled connection, so this necessarily runs in its own
     * short transaction afterwards. It **soft**-revokes rather than deleting, following the invite model's
     * standing rule (soft-invalidate now, hard-delete later — `TenantInvitePurge`), which also means a process
     * that dies between the failed send and this call leaves a token that expires on its own rather than one
     * that lives forever. And on a re-issue it does **not** restore the invite that was superseded a moment
     * earlier: reviving a token the owner was in the middle of replacing is worse than an error they can retry.
     *
     * `delivery != SENT` covers FAILED and FALLBACK_COPYLINK alike. The latter must be unreachable here — the
     * `canSend` gate above is what makes it so — but treating it as a failure means a hole in that gate loses an
     * invite loudly instead of minting a secret with no channel.
     *
     * Whether the revoke changed anything is not asked: this invite was never handed out, so nobody can have
     * spent it in between.
     */
    private fun revokeUndelivered(inviteId: String) {
        store.revokeInvite(inviteId)
    }

    /** Whether the invite key carries the caller's own `invite-key` signature, under the identity key the server holds for
     *  them — so a session without the owner's unlocked vault mints no invite (an honest server's check, ADR 0111). An
     *  expiry with a fraction of a second is no signed one. */
    private fun signedByCaller(callerId: String, tenantId: String, input: MemberInviteInput): Boolean =
        input.expiresAt.nano == 0 && InviteAcceptProof.inviteKeyVerifies(
            VeyqUser.findById(callerId)?.identityPublicKey, tenantId, input.inviteId, input.invitePublicKey,
            input.expiresAt.epochSecond, input.inviteKeySignature,
        )

    /** The owner signed the expiry, so the server takes the client's — but only within a minute of its own lifetime. */
    private fun expiryIsTheLifetime(expiresAt: Instant): Boolean =
        Duration.between(Instant.now().plus(inviteTtl), expiresAt).abs() <= EXPIRY_TOLERANCE

    /** The stored key of a member invite: the caller is its signer and its first granter, with the identity key their
     *  account holds now — the key the client signed with. */
    private fun inviteKey(callerId: String, input: MemberInviteInput): MemberInviteKey = MemberInviteKey(
        inviteId = input.inviteId,
        invitePublicKey = input.invitePublicKey,
        inviteKeySignature = input.inviteKeySignature,
        signerUserId = callerId,
        signerIdentityPublicKey = VeyqUser.findById(callerId)?.identityPublicKey ?: "",
        epoch = input.epoch,
        grant = input.grant,
    )

    /** The sealed part as the link's fragment carries it — base64url, unpadded, after the token and a `.` — or null for
     *  a value that is no base64. Opaque here: the server never learns what it seals. */
    private fun linkTail(sealedLinkB64: String): String? = try {
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Base64.getDecoder().decode(sealedLinkB64))
    } catch (e: IllegalArgumentException) {
        null
    }

    /** List a tenant's pending member invites — owner-gated (cr-0082). */
    @Transactional
    fun listMemberInvites(callerId: String, tenantId: String): MemberInvitesOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(MemberInvitesOutcome.NotFound, MemberInvitesOutcome.Suspended)?.let { return it }
        store.findTenant(tenantId) ?: return MemberInvitesOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MemberInvitesOutcome.NotOwner
        return MemberInvitesOutcome.Listed(store.listPendingMemberInvites(tenantId))
    }

    /** Re-issue a pending member invite: supersede it (its key and grant go) and mint the new invite the owner's
     *  client built for the same email — a new key, grant, link and code (cr-0082, ADR 0111). */
    fun reissueMemberInvite(callerId: String, tenantId: String, inviteId: String, input: MemberInviteInput): ReissueOutcome = commitThenSend {
        access.check(callerId, tenantId, WRITE)
            .refusal(ReissueOutcome.NotFound, ReissueOutcome.Suspended)?.let { return@commitThenSend MintResult.Rejected(it) }
        val tenant = store.findTenant(tenantId) ?: return@commitThenSend MintResult.Rejected(ReissueOutcome.NotFound)
        if (!store.isOwner(callerId, tenantId)) return@commitThenSend MintResult.Rejected(ReissueOutcome.NotOwner)
        // The tenant rotation's lock, before the invite is read: a rotation carries only the invites it reads under
        // this lock, and the fresh invite's grant must be of the epoch that lock holds.
        store.lockTenantScope(tenantId)
        val invite = store.findPendingMemberInvite(tenantId, inviteId) ?: return@commitThenSend MintResult.Rejected(ReissueOutcome.InviteNotFound)
        if (normalizeEmail(input.email) != invite.ownerEmail) return@commitThenSend MintResult.Rejected(ReissueOutcome.EmailMismatch)
        if (!expiryIsTheLifetime(input.expiresAt)) return@commitThenSend MintResult.Rejected(ReissueOutcome.ExpiryInvalid)
        if (!signedByCaller(callerId, tenantId, input)) return@commitThenSend MintResult.Rejected(ReissueOutcome.SignatureInvalid)
        if (!InviteAcceptProof.digestMatches(input.invitePublicKey, input.recipientIdentityDigest)) {
            return@commitThenSend MintResult.Rejected(ReissueOutcome.RecipientKeyNotCurrent)
        }
        val fragmentTail = linkTail(input.sealedLink) ?: return@commitThenSend MintResult.Rejected(ReissueOutcome.InvalidRequest)
        // Before the supersede, not after: mintMemberInvite kills the outstanding invite as it mints, so refusing
        // later would leave the invitee with nothing on a tenant that never could have sent (ADR 0055 §2).
        if (!mailer.canSend(tenant.id)) return@commitThenSend MintResult.Rejected(ReissueOutcome.MailUnavailable)
        // The reissue loop against one address is the sharpest bombing vector — limit before minting anew.
        inviteRateLimiter.check(tenantId, invite.ownerEmail)?.let { return@commitThenSend MintResult.Rejected(ReissueOutcome.RateLimited(it)) }
        val token = Tokens.random()
        val expiresAt = input.expiresAt
        // mintMemberInvite supersedes the current ACTIVE invite for this email (i.e. the one we found) + mints anew.
        val freshId = when (val minted = store.mintMemberInvite(tenantId, invite.ownerEmail, Tokens.hash(token), expiresAt, inviteKey(callerId, input))) {
            is MintMemberInvite.Minted -> minted.inviteId
            MintMemberInvite.StaleEpoch -> return@commitThenSend MintResult.Rejected(ReissueOutcome.StaleEpoch)
            MintMemberInvite.IdTaken -> return@commitThenSend MintResult.Rejected(ReissueOutcome.IdTaken)
        }
        MintResult.ToSend(tenant.id, tenant.domain, invite.ownerEmail, "$token.$fragmentTail", expiresAt) { delivery ->
            if (delivery != DeliveryResult.SENT) {
                revokeUndelivered(freshId)
                ReissueOutcome.SendFailed
            } else {
                ReissueOutcome.Done(InviteView(invite.ownerEmail, TenantInviteEntity.ACTIVE, expiresAt, token), delivery)
            }
        }
    }

    /** Revoke a pending member invite (soft-terminal; its key and grant are dropped, ADR 0111) — owner-gated (cr-0082). */
    @Transactional
    fun revokeMemberInvite(callerId: String, tenantId: String, inviteId: String): RevokeMemberInviteOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(RevokeMemberInviteOutcome.NotFound, RevokeMemberInviteOutcome.Suspended)?.let { return it }
        store.findTenant(tenantId) ?: return RevokeMemberInviteOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return RevokeMemberInviteOutcome.NotOwner
        val invite = store.findPendingMemberInvite(tenantId, inviteId) ?: return RevokeMemberInviteOutcome.InviteNotFound
        // The read above only tells an unknown invite from a live one. An accept that spent it since wins, and the
        // owner hears what a spent invite always answers — never "revoked" for a person who has already joined.
        if (!store.revokeInvite(invite.id)) return RevokeMemberInviteOutcome.InviteNotFound
        return RevokeMemberInviteOutcome.Done
    }

    /**
     * A tenant **owner** removes a member (uc.spaces.remove-tenant-member, ADR 0014 §6 / ADR 0025).
     * Membership-gated first (a non-member gets the same 404 as a bogus tenant id, ADR 0042), then owner-gated;
     * the tenant **last-owner guard** rejects removing the last tenant OWNER (ADR 0007). Then the cascade, per
     * scope the target belongs to in this tenant:
     *  - **TENANT** + a **shared GROUP** → **delete** the membership (instant layer-1 cut on shared content; the
     *    tenant key is **not** rotated, ADR 0025 — ACL-revoke). A co-shared GROUP additionally **queues a
     *    `SCOPE_ROTATION` job** (cr-0092, Fork 1b) so the next online remaining **owner** of the group rotates it
     *    forward for layer-2 forward secrecy — the removing owner need not hold that group's key. It joins the
     *    group's unclaimed rotation job if there is one, and never one already claimed.
     *  - a shared **GROUP the target solely owned** (OWNER, no other owner) → promote the **oldest** remaining
     *    member to OWNER (the orphan rule — a server rule, the tenant owner never gains access), then delete.
     *  - the target's **own** scopes — their **PERSONAL** scope + any **solo GROUP** (they are the only member) →
     *    **soft-delete** (cr-0090 grace + read-only + purge) but **keep** their membership, so they retain read-only
     *    access to read/export their own content during grace before `ScopePurge` erases them.
     * Every scope cut counts the removal ([ScopesStore.recordRemoval], ADR 0075 point 4); with [revokeLinks] every
     * link of the TENANT scope is also turned off and marked (point 5). A refused removal writes neither.
     * Operator-blind: only membership deletions + a lifecycle timestamp — never a key.
     *
     * The plan reads owner counts, so it is made under the locks of every scope the target is in. A group owner's
     * role change and a removal-rotation hold the same lock. Planned before the locks, a removal that overlapped a
     * co-owner's demote cut the other owner as "one of two", and the group was left with none. The target's scope
     * ids are read first, outside the decision's transaction. That transaction then takes their locks, in ascending
     * id order — the order every multi-scope lock takes — as its first statements. The gates run again under the
     * locks, so a refusal is what a replay would get.
     */
    fun removeMember(callerId: String, tenantId: String, targetId: String, revokeLinks: Boolean = false): RemoveTenantMemberOutcome {
        gateRemoval(callerId, tenantId)?.let { return it }
        val scopeIds = scopes.scopeIdsInTenant(targetId, tenantId).sorted()
        return QuarkusTransaction.requiringNew().call {
            scopeIds.forEach { scopes.lockScope(it) }
            cutUnderLocks(callerId, tenantId, targetId, revokeLinks)
        }
    }

    private fun gateRemoval(callerId: String, tenantId: String): RemoveTenantMemberOutcome? {
        access.check(callerId, tenantId, WRITE)
            .refusal(RemoveTenantMemberOutcome.NotFound, RemoveTenantMemberOutcome.Suspended)?.let { return it }
        return if (store.isOwner(callerId, tenantId)) null else RemoveTenantMemberOutcome.NotOwner
    }

    private fun cutUnderLocks(callerId: String, tenantId: String, targetId: String, revokeLinks: Boolean): RemoveTenantMemberOutcome {
        gateRemoval(callerId, tenantId)?.let { return it }
        return cascade(tenantId, targetId, revokeLinks, allowLastOwner = false)
    }

    /**
     * The account erasure's member removal (ADR 0101 decision 2): the same cascade a tenant owner's removal runs — own
     * scopes into the scope trash with their membership kept, shared ones cut and their links off, a sole-owned group
     * handed on, the picture cleared — without the caller's gates, inside the erasure's transaction, which took the
     * locks of every scope the account is in first. [allowLastOwner] is the Server Owner's door, which never refuses.
     * Returns false only for a last owner the caller did not allow, so the erasure rolls back whole.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun removeForErasure(tenantId: String, targetId: String, allowLastOwner: Boolean): Boolean =
        cascade(tenantId, targetId, revokeLinks = false, allowLastOwner = allowLastOwner) != RemoveTenantMemberOutcome.LastOwner

    private fun cascade(tenantId: String, targetId: String, revokeLinks: Boolean, allowLastOwner: Boolean): RemoveTenantMemberOutcome {
        // Impure shell: load the directory (ownerCount pre-resolved per row), ask the pure policy, execute its steps.
        // Every branch and boundary of the decision lives in RemovalPolicy, where it is mutation-verified (ADR 0045 §2).
        val rows = scopes.membershipsInTenant(targetId, tenantId)
            .map { RemovalScope(it.scopeId, it.kind, it.role, it.acceptedMembers, it.ownerCount, it.pending) }
        return when (val plan = RemovalPolicy.plan(rows, tenantId, allowLastOwner)) {
            RemovalPlan.MemberNotFound -> RemoveTenantMemberOutcome.MemberNotFound
            RemovalPlan.LastOwner -> RemoveTenantMemberOutcome.LastOwner
            is RemovalPlan.Cascade -> {
                val now = Instant.now()
                // Every scope this removal cuts is locked before anything is deleted, in ascending id order: the
                // rotation each cut may originate serialises on the scope row, and the lock order is scope →
                // membership → job (ScopesStore.rotate's). A cut that held a member's row while a rotation held the
                // scope row would deadlock; one order across scopes keeps two such requests from crossing. The
                // locks taken above already cover every scope but one the target joined after its ids were read;
                // that one is locked here, and only its counts can come from the pre-lock snapshot. A tombstone that
                // expires a group's proposals deletes membership rows too, so it is locked like a cut (inbox #598).
                plan.steps.filter { it.action != CascadeAction.SOFT_DELETE || it.expireProposals }.map { it.scopeId }.sorted()
                    .forEach { scopes.lockScope(it) }
                plan.steps.forEach { execute(it, targetId, now) }
                // ADR 0084 decision 8: the picture goes with the access, the profile row (and its handle) outlives it
                profiles.clearAvatar(targetId, tenantId)
                // ADR 0075 point 5: opted into, every link of the TENANT scope goes off in this same transaction —
                // never a co-shared group's, which the tenant owner need not see (the removal count marks those)
                if (revokeLinks) shares.turnOffAllInScope(tenantId)
                RemoveTenantMemberOutcome.Ok
            }
        }
    }

    /** Run one policy-decided cascade step against the stores. Decides nothing — the `when` only dispatches. */
    private fun execute(step: CascadeStep, targetId: String, now: Instant) {
        // ADR 0014 §6: the group's only accepted owner leaves, so its open proposals expire — before the hand-over,
        // whose successor query skips proposals besides. No rotation is queued for them here: a tombstoned group has nobody
        // left to hold a key, and a handed-over group is rotated by the target's own cut below.
        if (step.expireProposals) scopes.expireProposals(step.scopeId)
        when (step.action) {
            // The target's own scope: grace-tombstone, keep the membership (read-only grace, then purge).
            CascadeAction.SOFT_DELETE -> scopes.softDelete(step.scopeId, now)
            // Orphan rule: hand the solely-owned group to the oldest remaining accepted member before cutting the target.
            CascadeAction.TRANSFER_THEN_REMOVE -> {
                scopes.oldestOtherMember(step.scopeId, targetId)
                    ?.let { scopes.setRole(it, step.scopeId, MembershipEntity.OWNER) }
                cutSharedAccess(step, targetId)
            }
            CascadeAction.REMOVE -> cutSharedAccess(step, targetId)
            // Only proposed here: the decline's path (ScopesHandler.declineInvitation) — the row goes and, since it
            // held the key, the group's rotation is queued; no removal is counted and the group keeps its owner.
            CascadeAction.DROP_PROPOSAL ->
                if (scopes.deletePendingMembership(targetId, step.scopeId) > 0 && step.rotateAfter) {
                    tasks.originateScopeRotation(step.scopeId, maxAttempts)
                }
        }
    }

    /** The shared-access cut (layer-1): drop the membership, soft-revoke the shares the target created here
     *  (cr-0104), and queue the async forward rotation for a co-shared GROUP (cr-0092, Fork 1b) — the removing
     *  owner need not be a key-holder. The TENANT scope carries `rotateAfter = false` (ACL-revoke only, ADR 0025).
     *  The scope row is already locked ([removeMember]), so the cut and its origination are one event to a claim. */
    private fun cutSharedAccess(step: CascadeStep, targetId: String) {
        scopes.removeMembership(targetId, step.scopeId)
        // ADR 0075: counted on EVERY scope cut — the tenant never rotates, and a co-shared group's rotation is only
        // queued, so neither epoch marks the links the removed member could rebuild until this does
        scopes.recordRemoval(step.scopeId)
        shares.deactivateSharesByOwnerInScope(targetId, step.scopeId)
        if (step.rotateAfter) tasks.originateScopeRotation(step.scopeId, maxAttempts)
    }

    /**
     * Re-derive the reverse proxy's routing from the `tenant` table (ADR 0063) — the one call every routability-
     * changing operation here ends with.
     *
     * **Two things are load-bearing and neither is visible at the call site.** It must run *after* the caller's
     * transaction has committed: the projector reads in its own transaction, so called from inside one it would
     * regenerate from the pre-change table and write a file that is stale the moment it lands. That is why the
     * four transitions above are structured as `QuarkusTransaction.requiringNew().call { … }` **then** this,
     * rather than as `@Transactional` methods ending in a call.
     *
     * And it must be called from **every** such operation — create, revoke, change-domain, suspend, reactivate.
     * Missing one is a silent staleness bug: the row is right, the routing is wrong, and no compiler or type
     * catches it. `scripts/validate_tenant_ingress_triggers.py` is what does, by deriving the routability-
     * changing `TenantStore` methods from the store itself and requiring each caller here to reach this.
     */
    private fun reproject() = ingress.regenerate()

    /** Supersede + mint a bootstrap invite (resend/reassign), returning the payload to send **after** commit. */
    private fun mintToSend(tenantId: String, ownerEmail: String, domain: String): MintResult<InviteOutcome> {
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val invite = store.supersedeAndMintInvite(tenantId, ownerEmail, Tokens.hash(token), expiresAt)
        return MintResult.ToSend(tenantId, domain, ownerEmail, token, expiresAt) { delivery ->
            InviteOutcome.Done(invite.copy(bootstrapSecret = token), delivery)
        }
    }
}
