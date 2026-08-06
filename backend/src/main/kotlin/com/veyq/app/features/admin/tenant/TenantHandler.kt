package com.veyq.app.features.admin.tenant

import com.veyq.app.features.sharing.SharingStore
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.features.tasks.TasksStore
import com.veyq.app.generated.model.DeliveryResult
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.http.FailureStatus
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant

/** Result of a create-tenant attempt. `delivery` reports how the invite reached the owner (cr-email-invite-send-hardening). */
sealed interface CreateTenantOutcome {
    data class Created(val tenantId: String, val expiresAt: Instant, val bootstrapSecret: String, val delivery: DeliveryResult) : CreateTenantOutcome
    data object DomainTaken : CreateTenantOutcome, HttpFailure by httpFailure("domain_taken", FailureStatus.CONFLICT)
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

/** Result of a tenant owner inviting a member by email (cr-0077). */
sealed interface MemberInviteOutcome {
    data class Done(val invite: InviteView, val delivery: DeliveryResult) : MemberInviteOutcome
    /** Over the invite-mail rate limit (cr-email-invite-send-hardening) — mapped to 429 + Retry-After, no mint. */
    data class RateLimited(val retryAfterSeconds: Long) : MemberInviteOutcome
    data object NotFound : MemberInviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : MemberInviteOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object NotActive : MemberInviteOutcome, HttpFailure by httpFailure("tenant_not_active", FailureStatus.CONFLICT)
    data object AlreadyMember : MemberInviteOutcome, HttpFailure by Failures.alreadyMember

    /** The tenant cannot send mail, and a member invite has no other channel (ADR 0055 §2) — nothing is minted. */
    data object MailUnavailable : MemberInviteOutcome, HttpFailure by Failures.mailUnavailable

    /** SMTP was configured but the send errored, so the minted invite was revoked again (ADR 0055 §6). Retryable.
     *  Slice-local code (only this slice mints invites), so it is written inline rather than in [Failures]. */
    data object SendFailed : MemberInviteOutcome, HttpFailure by httpFailure("mail_send_failed", FailureStatus.SERVICE_UNAVAILABLE)
}

/** Result of listing a tenant's pending member invites (cr-0082). */
sealed interface MemberInvitesOutcome {
    data class Listed(val invites: List<PendingInviteRow>) : MemberInvitesOutcome
    data object NotFound : MemberInvitesOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : MemberInvitesOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/** Result of re-issuing a pending member invite (cr-0082). */
sealed interface ReissueOutcome {
    data class Done(val invite: InviteView, val delivery: DeliveryResult) : ReissueOutcome
    /** Over the invite-mail rate limit (cr-email-invite-send-hardening) — mapped to 429 + Retry-After, no mint. */
    data class RateLimited(val retryAfterSeconds: Long) : ReissueOutcome
    data object NotFound : ReissueOutcome, HttpFailure by Failures.tenantNotFound        // no such tenant
    data object NotOwner : ReissueOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object InviteNotFound : ReissueOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)  // no such pending invite for this tenant

    /** No sendable mailer ⇒ nothing minted, and the outstanding invite still stands (ADR 0055 §2). */
    data object MailUnavailable : ReissueOutcome, HttpFailure by Failures.mailUnavailable

    /** The send errored ⇒ the fresh invite is revoked; the superseded one is **not** restored (ADR 0055 §6). */
    data object SendFailed : ReissueOutcome, HttpFailure by httpFailure("mail_send_failed", FailureStatus.SERVICE_UNAVAILABLE)
}

/** Result of revoking a pending member invite (cr-0082). */
sealed interface RevokeMemberInviteOutcome {
    data object Done : RevokeMemberInviteOutcome
    data object NotFound : RevokeMemberInviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : RevokeMemberInviteOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object InviteNotFound : RevokeMemberInviteOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
}

/** Result of a tenant owner removing a member — the ACL-revoke cascade + orphan rule (cr-0091). */
sealed interface RemoveTenantMemberOutcome {
    data object Ok : RemoveTenantMemberOutcome
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
    config: VeyqConfig,
) {
    private val inviteTtl: Duration = config.tenant().invite().ttl()
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
            is MintResult.ToSend -> r.build(mailer.sendInvite(r.domain, r.recipient, r.token, r.expiresAt))
        }

    fun create(name: String, domain: String, ownerEmail: String): CreateTenantOutcome {
        val normalizedDomain = domain.lowercase()
        val normalizedEmail = ownerEmail.lowercase()
        return commitThenSend {
            if (store.domainExists(normalizedDomain)) return@commitThenSend MintResult.Rejected(CreateTenantOutcome.DomainTaken)
            val token = Tokens.random()
            val expiresAt = Instant.now().plus(inviteTtl)
            val (tenantId, expiry) = store.createPendingTenant(name, normalizedDomain, normalizedEmail, Tokens.hash(token), expiresAt)
            // Return the raw token so the Server Owner can copy the invite link (works without a mailer, cr-0080).
            MintResult.ToSend(normalizedDomain, normalizedEmail, token, expiry) { delivery ->
                CreateTenantOutcome.Created(tenantId, expiry, token, delivery)
            }
        }
    }

    @Transactional
    fun list(): List<TenantView> = store.listAll()

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
        val email = newOwnerEmail.lowercase()
        store.updateOwnerEmail(tenant.id, email)
        mintToSend(tenant.id, email, tenant.domain)
    }

    @Transactional
    fun revoke(tenantId: String): RevokeOutcome {
        val tenant = store.findTenant(tenantId) ?: return RevokeOutcome.NotFound
        if (tenant.status != TenantEntity.PENDING) return RevokeOutcome.NotPending
        store.deleteTenant(tenantId)
        return RevokeOutcome.Done
    }

    /**
     * A tenant **owner** invites a person (by email) into the ACTIVE tenant as a MEMBER (cr-0077 / ADR 0020).
     * Admission is owner-gated (the caller must hold the tenant's OWNER membership); the invitee onboards via
     * the same cold-birth accept flow with the invite's MEMBER role. The scope key is delivered afterward by a
     * key-holder (cr-0076) — this only mints the admission token.
     */
    fun inviteMember(callerId: String, tenantId: String, email: String): MemberInviteOutcome = commitThenSend {
        if (!store.hasMembership(callerId, tenantId)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.NotFound)
        val tenant = store.findTenant(tenantId) ?: return@commitThenSend MintResult.Rejected(MemberInviteOutcome.NotFound)
        if (!store.isOwner(callerId, tenantId)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.NotOwner)
        if (tenant.status != TenantEntity.ACTIVE) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.NotActive)
        val normalizedEmail = email.lowercase()
        if (store.isEmailMember(tenantId, normalizedEmail)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.AlreadyMember)
        // Mail is the only channel for a member invite (ADR 0055 §2), so ask *before* minting: an invitation that
        // cannot be delivered is a live secret with no recipient, and there is no copy-link to hand it to.
        if (!mailer.canSend(tenant.domain)) return@commitThenSend MintResult.Rejected(MemberInviteOutcome.MailUnavailable)
        // Authorized owner + real recipient ⇒ enforce the anti-bombing limit before minting (no orphan on a 429).
        inviteRateLimiter.check(tenantId, normalizedEmail)?.let { return@commitThenSend MintResult.Rejected(MemberInviteOutcome.RateLimited(it)) }

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val inviteId = store.mintMemberInvite(tenantId, normalizedEmail, Tokens.hash(token), expiresAt)
        MintResult.ToSend(tenant.domain, normalizedEmail, token, expiresAt) { delivery ->
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
     */
    private fun revokeUndelivered(inviteId: String) = store.revokeInvite(inviteId)

    /** List a tenant's pending member invites — owner-gated (cr-0082). */
    @Transactional
    fun listMemberInvites(callerId: String, tenantId: String): MemberInvitesOutcome {
        if (!store.hasMembership(callerId, tenantId)) return MemberInvitesOutcome.NotFound
        store.findTenant(tenantId) ?: return MemberInvitesOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MemberInvitesOutcome.NotOwner
        return MemberInvitesOutcome.Listed(store.listPendingMemberInvites(tenantId))
    }

    /** Re-issue a pending member invite: supersede it + mint a fresh secret for the same email (cr-0082). */
    fun reissueMemberInvite(callerId: String, tenantId: String, inviteId: String): ReissueOutcome = commitThenSend {
        if (!store.hasMembership(callerId, tenantId)) return@commitThenSend MintResult.Rejected(ReissueOutcome.NotFound)
        val tenant = store.findTenant(tenantId) ?: return@commitThenSend MintResult.Rejected(ReissueOutcome.NotFound)
        if (!store.isOwner(callerId, tenantId)) return@commitThenSend MintResult.Rejected(ReissueOutcome.NotOwner)
        val invite = store.findPendingMemberInvite(tenantId, inviteId) ?: return@commitThenSend MintResult.Rejected(ReissueOutcome.InviteNotFound)
        // Before the supersede, not after: mintMemberInvite kills the outstanding invite as it mints, so refusing
        // later would leave the invitee with nothing on a tenant that never could have sent (ADR 0055 §2).
        if (!mailer.canSend(tenant.domain)) return@commitThenSend MintResult.Rejected(ReissueOutcome.MailUnavailable)
        // The reissue loop against one address is the sharpest bombing vector — limit before minting anew.
        inviteRateLimiter.check(tenantId, invite.ownerEmail)?.let { return@commitThenSend MintResult.Rejected(ReissueOutcome.RateLimited(it)) }
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        // mintMemberInvite supersedes the current ACTIVE invite for this email (i.e. the one we found) + mints anew.
        val freshId = store.mintMemberInvite(tenantId, invite.ownerEmail, Tokens.hash(token), expiresAt)
        MintResult.ToSend(tenant.domain, invite.ownerEmail, token, expiresAt) { delivery ->
            if (delivery != DeliveryResult.SENT) {
                revokeUndelivered(freshId)
                ReissueOutcome.SendFailed
            } else {
                ReissueOutcome.Done(InviteView(invite.ownerEmail, TenantInviteEntity.ACTIVE, expiresAt, token), delivery)
            }
        }
    }

    /** Revoke a pending member invite (soft-terminal, additive-safe) — owner-gated (cr-0082). */
    @Transactional
    fun revokeMemberInvite(callerId: String, tenantId: String, inviteId: String): RevokeMemberInviteOutcome {
        if (!store.hasMembership(callerId, tenantId)) return RevokeMemberInviteOutcome.NotFound
        store.findTenant(tenantId) ?: return RevokeMemberInviteOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return RevokeMemberInviteOutcome.NotOwner
        val invite = store.findPendingMemberInvite(tenantId, inviteId) ?: return RevokeMemberInviteOutcome.InviteNotFound
        store.revokeInvite(invite.id)
        return RevokeMemberInviteOutcome.Done
    }

    /**
     * A tenant **owner** removes a member (uc.spaces.remove-tenant-member, ADR 0014 §6 / ADR 0025).
     * Membership-gated first (a non-member gets the same 404 as a bogus tenant id, ADR 0042), then owner-gated;
     * the tenant **last-owner guard** rejects removing the last tenant OWNER (ADR 0007). Then the cascade, per
     * scope the target belongs to in this tenant:
     *  - **TENANT** + a **shared GROUP** → **delete** the membership (instant layer-1 cut on shared content; the
     *    tenant key is **not** rotated, ADR 0025 — ACL-revoke). A co-shared GROUP additionally **queues a
     *    `SCOPE_ROTATION` job** (cr-0092, Fork 1b) so the next online remaining key-holder rotates it forward for
     *    layer-2 forward secrecy — the removing owner need not hold that group's key.
     *  - a shared **GROUP the target solely owned** (OWNER, no other owner) → promote the **oldest** remaining
     *    member to OWNER (the orphan rule — a server rule, the tenant owner never gains access), then delete.
     *  - the target's **own** scopes — their **PERSONAL** scope + any **solo GROUP** (they are the only member) →
     *    **soft-delete** (cr-0090 grace + read-only + purge) but **keep** their membership, so they retain read-only
     *    access to read/export their own memories during grace before `ScopePurge` erases them.
     * Operator-blind: only membership deletions + a lifecycle timestamp — never a key.
     */
    @Transactional
    fun removeMember(callerId: String, tenantId: String, targetId: String): RemoveTenantMemberOutcome {
        if (!store.hasMembership(callerId, tenantId)) return RemoveTenantMemberOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return RemoveTenantMemberOutcome.NotOwner
        // Impure shell: load the directory (ownerCount pre-resolved per row), ask the pure policy, execute its steps.
        // Every branch and boundary of the decision lives in RemovalPolicy, where it is mutation-verified (ADR 0045 §2).
        val rows = scopes.membershipsInTenant(targetId, tenantId)
            .map { RemovalScope(it.scopeId, it.kind, it.role, it.memberCount, it.ownerCount) }
        return when (val plan = RemovalPolicy.plan(rows, tenantId)) {
            RemovalPlan.MemberNotFound -> RemoveTenantMemberOutcome.MemberNotFound
            RemovalPlan.LastOwner -> RemoveTenantMemberOutcome.LastOwner
            is RemovalPlan.Cascade -> {
                val now = Instant.now()
                plan.steps.forEach { execute(it, targetId, now) }
                RemoveTenantMemberOutcome.Ok
            }
        }
    }

    /** Run one policy-decided cascade step against the stores. Decides nothing — the `when` only dispatches. */
    private fun execute(step: CascadeStep, targetId: String, now: Instant) {
        when (step.action) {
            // The target's own scope: grace-tombstone, keep the membership (read-only grace, then purge).
            CascadeAction.SOFT_DELETE -> scopes.softDelete(step.scopeId, now)
            // Orphan rule: hand the solely-owned group to the oldest remaining member before cutting the target.
            CascadeAction.TRANSFER_THEN_REMOVE -> {
                scopes.oldestOtherMember(step.scopeId, targetId)
                    ?.let { scopes.setRole(it, step.scopeId, MembershipEntity.OWNER) }
                cutSharedAccess(step, targetId)
            }
            CascadeAction.REMOVE -> cutSharedAccess(step, targetId)
        }
    }

    /** The shared-access cut (layer-1): drop the membership, soft-revoke the shares the target created here
     *  (cr-0104), and queue the async forward rotation for a co-shared GROUP (cr-0092, Fork 1b) — the removing
     *  owner need not be a key-holder. The TENANT scope carries `rotateAfter = false` (ACL-revoke only, ADR 0025). */
    private fun cutSharedAccess(step: CascadeStep, targetId: String) {
        scopes.removeMembership(targetId, step.scopeId)
        shares.deactivateSharesByOwnerInScope(targetId, step.scopeId)
        if (step.rotateAfter) tasks.originateScopeRotation(step.scopeId, maxAttempts)
    }

    /** Supersede + mint a bootstrap invite (resend/reassign), returning the payload to send **after** commit. */
    private fun mintToSend(tenantId: String, ownerEmail: String, domain: String): MintResult<InviteOutcome> {
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val invite = store.supersedeAndMintInvite(tenantId, ownerEmail, Tokens.hash(token), expiresAt)
        return MintResult.ToSend(domain, ownerEmail, token, expiresAt) { delivery ->
            InviteOutcome.Done(invite.copy(bootstrapSecret = token), delivery)
        }
    }
}
