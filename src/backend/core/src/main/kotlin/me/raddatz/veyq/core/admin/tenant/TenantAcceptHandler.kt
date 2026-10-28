package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.crypto.Tokens
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.identity.WrapData
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import org.jboss.logging.Logger

/** Cold-birth account material for the register-and-accept path (the email comes from the invite). No display
 *  name — identity is the per-tenant `username` (threaded separately) + the sealed name set later (ADR 0023). */
data class NewAccount(
    val suiteId: String,
    val identityPublicKey: String,
    val wrappedIdentityPrivateKey: String,
    val authPublicKey: String,
    val secretKeyProofPublicKey: String,
    val wraps: List<WrapData>,
)

/**
 * What an OWNER accept brings for the tenant's cold birth: the first grant with the owner's self-signature over it
 * (ADR 0028 Decision 6), and the digest of the identity it is wrapped to (ADR 0111 decision 10: refused unless it is the
 * owner's current one). All or nothing — a birth missing a part is no birth, and the accept answers it as a missing
 * grant.
 */
data class OwnerBirth(val grant: SignedGrant, val recipientIdentityDigest: String) {
    companion object {
        fun of(grant: String?, signature: String?, recipientIdentityDigest: String?): OwnerBirth? =
            if (grant == null || signature == null || recipientIdentityDigest == null) null
            else OwnerBirth(SignedGrant(grant, signature), recipientIdentityDigest)
    }
}

/**
 * What a MEMBER accept brings (ADR 0111 decision 3): the grant the newcomer gave itself from its invite's grant — at
 * [epoch], naming the key it is wrapped to — the invite key's proof that its holder redeems it for this account and
 * key, and the join record sealed under the tenant key. All or nothing, like [OwnerBirth].
 */
data class MemberJoin(
    val selfGrant: SignedGrant,
    val epoch: Int,
    val recipientIdentityDigest: String,
    val inviteProof: String,
    val sealedJoinRecord: String,
) {
    companion object {
        fun of(
            wrappedGrant: String?, signature: String?, epoch: Int?, digest: String?, inviteProof: String?, sealedJoinRecord: String?,
        ): MemberJoin? =
            if (wrappedGrant == null || signature == null || epoch == null || digest == null || inviteProof == null || sealedJoinRecord == null) null
            else MemberJoin(SignedGrant(wrappedGrant, signature), epoch, digest, inviteProof, sealedJoinRecord)
    }
}

/** Why a MEMBER join was refused before anything was written, or null when it may go ahead. */
private enum class JoinRefusal { MISSING, PROOF_INVALID, RECIPIENT_KEY_NOT_CURRENT, STALE_EPOCH }

sealed interface PreviewOutcome {
    data class Found(
        val tenantId: String,
        val tenantName: String,
        val domain: String,
        val ownerEmail: String,
        val hasAccount: Boolean,
        val role: String,
        /** An OWNER invite into an ACTIVE tenant (ADR 0101 decision 9): accepted by [TenantAcceptHandler.acceptOwnership]. */
        val handover: Boolean,
        /** A MEMBER invite's key, signature and grant (ADR 0111 decision 1) — the invitee checks them against its link. */
        val invite: TenantInviteEntity?,
        /** The key-chain heads of the invite's signer and granter (ADR 0111 decision 7), by user id. */
        val heads: Map<String, String> = emptyMap(),
    ) : PreviewOutcome
    data object NotFound : PreviewOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
    data object NoLongerValid : PreviewOutcome, HttpFailure by httpFailure("invite_no_longer_valid", FailureStatus.GONE)
}

sealed interface AcceptOutcome {
    data class Activated(val tenantId: String) : AcceptOutcome
    data object NotFound : AcceptOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
    data object NoLongerValid : AcceptOutcome, HttpFailure by httpFailure("invite_no_longer_valid", FailureStatus.GONE)
    data object NotInvitedOwner : AcceptOutcome, HttpFailure by httpFailure("not_invited_owner", FailureStatus.FORBIDDEN)
    data object AlreadyActive : AcceptOutcome, HttpFailure by httpFailure("tenant_not_pending", FailureStatus.CONFLICT)
    data object MissingGrant : AcceptOutcome, HttpFailure by Failures.invalidRequest
    data object UsernameTaken : AcceptOutcome, HttpFailure by Failures.usernameTaken
    data object InviteProofInvalid : AcceptOutcome, HttpFailure by httpFailure("invite_proof_invalid", FailureStatus.FORBIDDEN)
    data object RecipientKeyNotCurrent : AcceptOutcome, HttpFailure by Failures.recipientKeyNotCurrent
    data object StaleEpoch : AcceptOutcome, HttpFailure by Failures.staleEpoch
}

/** Result of a member accepting an owner invite into an ACTIVE tenant (`acceptTenantOwnership`, ADR 0101 decision 9). */
sealed interface AcceptOwnershipOutcome {
    data object Done : AcceptOwnershipOutcome
    data object NotFound : AcceptOwnershipOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
    data object NoLongerValid : AcceptOwnershipOutcome, HttpFailure by httpFailure("invite_no_longer_valid", FailureStatus.GONE)
    data object NotInvitedOwner : AcceptOwnershipOutcome, HttpFailure by httpFailure("not_invited_owner", FailureStatus.FORBIDDEN)
    data object NotMember : AcceptOwnershipOutcome, HttpFailure by httpFailure("not_tenant_member", FailureStatus.CONFLICT)
}

sealed interface RegisterAcceptOutcome {
    data class Activated(val tenantId: String, val userId: String) : RegisterAcceptOutcome
    data object NotFound : RegisterAcceptOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
    data object NoLongerValid : RegisterAcceptOutcome, HttpFailure by httpFailure("invite_no_longer_valid", FailureStatus.GONE)
    data object EmailTaken : RegisterAcceptOutcome, HttpFailure by httpFailure("email_taken", FailureStatus.CONFLICT)
    data object MissingGrant : RegisterAcceptOutcome, HttpFailure by Failures.invalidRequest
    data object UsernameTaken : RegisterAcceptOutcome, HttpFailure by Failures.usernameTaken
    data object IdTaken : RegisterAcceptOutcome, HttpFailure by Failures.idTaken
    data object InviteProofInvalid : RegisterAcceptOutcome, HttpFailure by httpFailure("invite_proof_invalid", FailureStatus.FORBIDDEN)
    data object RecipientKeyNotCurrent : RegisterAcceptOutcome, HttpFailure by Failures.recipientKeyNotCurrent
    data object StaleEpoch : RegisterAcceptOutcome, HttpFailure by Failures.staleEpoch
}

/**
 * Tenant activation — invite acceptance (uc.admin.create-tenant Slice B, cr-0010). Verifies the bootstrap
 * secret by hash, applies reuse-detection (a presented non-ACTIVE / expired invite is a replay → rejected +
 * logged, mirroring the session model) — an invite to a SUSPENDED tenant answers exactly as a dead one: its holder
 * is no member and learns nothing of the suspension (uc.spaces.scope-access A4) — and on success cold-activates the
 * tenant atomically: the owner's
 * `OWNER` membership + grant, the tenant flipped `ACTIVE`, the invite `CONSUMED`. Operator-blind — the grant
 * is opaque ciphertext stored verbatim; the server never sees the scope key.
 */
@ApplicationScoped
class TenantAcceptHandler(
    private val store: TenantStore,
    private val accounts: AccountStore,
    private val profiles: TenantProfileStore,
    private val access: ScopeAccess,
) {
    private val log = Logger.getLogger(TenantAcceptHandler::class.java)

    @Transactional
    fun preview(bootstrapSecret: String): PreviewOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return PreviewOutcome.NotFound
        if (!isLive(invite)) return PreviewOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return PreviewOutcome.NotFound
        if (!access.isTenantLive(tenant.id)) return PreviewOutcome.NoLongerValid
        // The invited email is the invite's own — for a MEMBER invite it differs from the tenant owner's.
        val served = invite.takeIf { it.role == TenantInviteEntity.MEMBER && it.invitePublicKey != null }
        return PreviewOutcome.Found(
            tenantId = tenant.id,
            tenantName = tenant.name,
            domain = tenant.domain,
            ownerEmail = invite.ownerEmail,
            hasAccount = accounts.findByEmail(invite.ownerEmail) != null,
            role = invite.role,
            handover = invite.role == TenantInviteEntity.OWNER && tenant.status == TenantEntity.ACTIVE,
            invite = served,
            heads = served?.let { store.keyChainHeads(listOfNotNull(it.signerUserId, it.granterUserId)) }.orEmpty(),
        )
    }

    /**
     * A member accepts an owner invite into an ACTIVE tenant (`inviteTenantOwner`, ADR 0101 decision 9): they become an
     * OWNER of its TENANT scope. Nothing is born and no key moves — a member already holds the tenant's key — so the
     * invite is only spent and the role set, under the tenant scope's lock every role change takes. The caller must be
     * the invited address and still a member: an invite to someone who left since makes nobody an owner.
     */
    @Transactional
    fun acceptOwnership(userId: String, bootstrapSecret: String): AcceptOwnershipOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret))
            ?.takeIf { it.role == TenantInviteEntity.OWNER } ?: return AcceptOwnershipOutcome.NotFound
        if (!isLive(invite)) return AcceptOwnershipOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return AcceptOwnershipOutcome.NotFound
        if (tenant.status != TenantEntity.ACTIVE) return AcceptOwnershipOutcome.NoLongerValid
        val caller = VeyqUser.findById(userId) ?: return AcceptOwnershipOutcome.NotInvitedOwner
        if (caller.email != invite.ownerEmail) return AcceptOwnershipOutcome.NotInvitedOwner
        store.lockTenantScope(tenant.id)
        if (!store.isTenantMember(userId, tenant.id)) return AcceptOwnershipOutcome.NotMember
        if (!store.consumeLiveInvite(invite.id)) return AcceptOwnershipOutcome.NoLongerValid
        store.setTenantRole(tenant.id, userId, me.raddatz.veyq.core.membership.MembershipEntity.OWNER)
        return AcceptOwnershipOutcome.Done
    }

    /** Existing-user accept: the caller (resolved from the session) must be the invited person. Role-branched
     *  (cr-0077): an OWNER accept cold-activates the tenant (grant required); a MEMBER accept joins the ACTIVE
     *  tenant with the grant it gave itself, after its invite key's proof verified (ADR 0111 decision 3). */
    fun accept(
        userId: String, bootstrapSecret: String, birth: OwnerBirth?, join: MemberJoin?, username: String, sealedName: String?,
        sealedAvatar: String?,
    ): AcceptOutcome =
        createOrConflict(conflict = { if (handleLost(userId, bootstrapSecret, username)) AcceptOutcome.UsernameTaken else null }) {
            acceptOnce(userId, bootstrapSecret, birth, join, username, sealedName, sealedAvatar)
        }

    private fun acceptOnce(
        userId: String, bootstrapSecret: String, birth: OwnerBirth?, join: MemberJoin?, username: String, sealedName: String?,
        sealedAvatar: String?,
    ): AcceptOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return AcceptOutcome.NotFound
        if (!isLive(invite)) return AcceptOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return AcceptOutcome.NotFound

        // The caller's account row, locked first (the order the publish of a rotation takes, account → scopes): the
        // self-grant below is checked against the identity it holds, and no rotation of this account can publish another
        // between that check and this commit (ADR 0111 decision 10).
        val caller = accounts.lock(userId) ?: return AcceptOutcome.NotInvitedOwner
        if (caller.email != invite.ownerEmail) return AcceptOutcome.NotInvitedOwner

        return if (invite.role == TenantInviteEntity.MEMBER) {
            if (!access.isTenantLive(tenant.id)) return AcceptOutcome.NoLongerValid
            // The rotation's lock first, so the invite read below is one no in-flight rotation is re-granting, and the
            // self-grant's epoch is judged against the epoch as committed now.
            val current = store.lockTenantScope(tenant.id) ?: return AcceptOutcome.NotFound
            store.refreshInvite(invite)
            if (!isLive(invite)) return AcceptOutcome.NoLongerValid
            when (refusal(join, invite, userId, caller.identityPublicKey, current)) {
                JoinRefusal.MISSING -> return AcceptOutcome.MissingGrant
                JoinRefusal.PROOF_INVALID -> return AcceptOutcome.InviteProofInvalid
                JoinRefusal.RECIPIENT_KEY_NOT_CURRENT -> return AcceptOutcome.RecipientKeyNotCurrent
                JoinRefusal.STALE_EPOCH -> return AcceptOutcome.StaleEpoch
                null -> Unit
            }
            val j = join!!
            if (!store.consumeLiveInvite(invite.id)) return AcceptOutcome.NoLongerValid
            // If already a member, spending the invite is all there is (their tenant profile already exists — the
            // username is only chosen on a fresh join).
            if (store.belongsToTenant(userId, tenant.id)) {
                // nothing to join
            } else if (profiles.find(userId, tenant.id) != null) {
                // A member removed from the tenant and invited back: the profile outlived the removal, so they
                // rejoin under the username they had rather than being handed a second one — with a new join record.
                store.joinAsMember(tenant.id, userId, j.selfGrant, j.epoch)
                profiles.setJoinRecord(userId, tenant.id, j.sealedJoinRecord)
            } else {
                if (profiles.usernameTaken(tenant.id, username)) return unspend(AcceptOutcome.UsernameTaken)
                store.joinAsMember(tenant.id, userId, j.selfGrant, j.epoch)
                profiles.create(userId, tenant.id, username, sealedName, sealedAvatar, j.sealedJoinRecord)
            }
            // A member invite only ever travels by mail (ADR 0055 §2), so redeeming it proves the mailbox — keyed on
            // the invite's ROLE, never on a delivery value (ADR 0074 Decision 2). Every MEMBER sub-branch above.
            caller.confirmAddress(invite.ownerEmail)
            AcceptOutcome.Activated(tenant.id)
        } else {
            val b = birth ?: return AcceptOutcome.MissingGrant
            if (!InviteAcceptProof.digestMatches(caller.identityPublicKey, b.recipientIdentityDigest)) return AcceptOutcome.RecipientKeyNotCurrent
            // An OWNER accept has no scope to lock yet: the spend alone serializes it.
            if (!store.consumeLiveInvite(invite.id)) return AcceptOutcome.NoLongerValid
            if (tenant.status != TenantEntity.PENDING) return unspend(AcceptOutcome.AlreadyActive)
            if (profiles.usernameTaken(tenant.id, username)) return unspend(AcceptOutcome.UsernameTaken)
            store.activate(tenant.id, userId, b.grant)
            // Owner cold-birth: the caller holds K_tenant here, so an optional sealed name may ride the accept (cr-0106),
            // and so may the picture (ADR 0084 decision 7).
            profiles.create(userId, tenant.id, username, sealedName, sealedAvatar)
            AcceptOutcome.Activated(tenant.id)
        }
    }

    /** Register-and-accept: the invited email has no account yet — born cold here (email from the invite).
     *  Role-branched like [accept]: OWNER activates (grant required), MEMBER joins with its self-grant. */
    fun registerAndAccept(
        bootstrapSecret: String, userId: String, account: NewAccount, birth: OwnerBirth?, join: MemberJoin?, username: String,
        sealedName: String?, sealedAvatar: String?,
    ): RegisterAcceptOutcome =
        createOrConflict(conflict = { if (handleLost(userId, bootstrapSecret, username)) RegisterAcceptOutcome.UsernameTaken else null }) {
            registerAndAcceptOnce(bootstrapSecret, userId, account, birth, join, username, sealedName, sealedAvatar)
        }

    private fun registerAndAcceptOnce(
        bootstrapSecret: String, userId: String, account: NewAccount, birth: OwnerBirth?, join: MemberJoin?, username: String,
        sealedName: String?, sealedAvatar: String?,
    ): RegisterAcceptOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return RegisterAcceptOutcome.NotFound
        if (!isLive(invite)) return RegisterAcceptOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return RegisterAcceptOutcome.NotFound
        if (!access.isTenantLive(tenant.id)) return RegisterAcceptOutcome.NoLongerValid

        val member = invite.role == TenantInviteEntity.MEMBER
        if (!member && birth == null) return RegisterAcceptOutcome.MissingGrant
        // The newborn's first grant is wrapped to the identity it registers (ADR 0111 decision 10).
        if (!member && !InviteAcceptProof.digestMatches(account.identityPublicKey, birth!!.recipientIdentityDigest)) {
            return RegisterAcceptOutcome.RecipientKeyNotCurrent
        }
        // A MEMBER takes the rotation's lock first and checks its join under it, as in [accept]; then the spend, before
        // anything is born.
        if (member) {
            val current = store.lockTenantScope(tenant.id) ?: return RegisterAcceptOutcome.NotFound
            store.refreshInvite(invite)
            if (!isLive(invite)) return RegisterAcceptOutcome.NoLongerValid
            when (refusal(join, invite, userId, account.identityPublicKey, current)) {
                JoinRefusal.MISSING -> return RegisterAcceptOutcome.MissingGrant
                JoinRefusal.PROOF_INVALID -> return RegisterAcceptOutcome.InviteProofInvalid
                JoinRefusal.RECIPIENT_KEY_NOT_CURRENT -> return RegisterAcceptOutcome.RecipientKeyNotCurrent
                JoinRefusal.STALE_EPOCH -> return RegisterAcceptOutcome.StaleEpoch
                null -> Unit
            }
        }
        if (!store.consumeLiveInvite(invite.id)) return RegisterAcceptOutcome.NoLongerValid
        // The account is born for the INVITED email (a MEMBER invite's email differs from the tenant owner's).
        // An owner invite into an ACTIVE tenant goes to a member (ADR 0101 decision 9), never to a new account: a tenant
        // is born only from PENDING (a second birth would collide on the tenant scope's id).
        if (!member && tenant.status != TenantEntity.PENDING) return unspend(RegisterAcceptOutcome.NoLongerValid)
        if (accounts.findByEmail(invite.ownerEmail) != null) return unspend(RegisterAcceptOutcome.EmailTaken)
        if (profiles.usernameTaken(tenant.id, username)) return unspend(RegisterAcceptOutcome.UsernameTaken)
        // The client-minted account id (ADR 0072): the optional sealed real name is bound to (tenant scope, it).
        if (VeyqUser.findById(userId) != null) return unspend(RegisterAcceptOutcome.IdTaken)

        accounts.createAccount(
            userId = userId,
            email = invite.ownerEmail,
            serverRole = ServerRoles.MEMBER,
            suiteId = account.suiteId,
            identityPublicKey = account.identityPublicKey,
            wrappedIdentityPrivateKey = account.wrappedIdentityPrivateKey,
            authPublicKey = account.authPublicKey,
            secretKeyProofPublicKey = account.secretKeyProofPublicKey,
            wraps = account.wraps,
        )
        if (member) {
            store.joinAsMember(tenant.id, userId, join!!.selfGrant, join.epoch)
            // Mail-only by construction (ADR 0055 §2): the redeemed token is the mailbox proof (ADR 0074 Decision 2).
            // The OWNER branch is the copy-link and stamps nothing.
            VeyqUser.findById(userId)!!.confirmAddress(invite.ownerEmail)
        } else {
            store.activate(tenant.id, userId, birth!!.grant)
        }
        // The per-tenant identity: the plaintext handle, plus what the joiner sealed under K_tenant, which it holds at
        // accept either way (ADR 0023, ADR 0111 decision 3) — the optional name and picture (ADR 0084 decision 7) and a
        // member's join record. The server stores the blobs verbatim (blind).
        profiles.create(userId, tenant.id, username, sealedName, sealedAvatar, if (member) join?.sealedJoinRecord else null)
        return RegisterAcceptOutcome.Activated(tenant.id, userId)
    }

    /**
     * What a MEMBER join must hold before anything is written (ADR 0111 decision 3), read under the tenant scope's lock:
     * all its parts; the invite key's proof that this account and identity redeem it, verified under the invite key the
     * server stores (the operator could mint its own invite, but an honest server refuses a redeem by token alone); a
     * self-grant addressed to [identityPublicKey], the account's key; and an epoch no later than the tenant's [current]
     * one — an earlier one is a pending invite's, and leaves the membership behind for the grant delivery.
     */
    private fun refusal(join: MemberJoin?, invite: TenantInviteEntity, userId: String, identityPublicKey: String?, current: Int): JoinRefusal? {
        val j = join ?: return JoinRefusal.MISSING
        val key = identityPublicKey ?: return JoinRefusal.RECIPIENT_KEY_NOT_CURRENT
        if (!InviteAcceptProof.verify(invite.invitePublicKey, invite.id, userId, key, j.inviteProof)) return JoinRefusal.PROOF_INVALID
        if (!InviteAcceptProof.digestMatches(key, j.recipientIdentityDigest)) return JoinRefusal.RECIPIENT_KEY_NOT_CURRENT
        if (j.epoch > current) return JoinRefusal.STALE_EPOCH
        return null
    }

    /**
     * The replay question of a join whose commit failed: does someone else now hold [username] in the invite's tenant,
     * while [userId] has no profile there — the one branch that claims a handle? A profile edit takes no tenant lock,
     * so it can claim the handle between this join's check and its commit; `ux_tp_tenant_username` then fails the
     * join, which rolls back whole (the invite stays live), and its replay answers `username_taken`.
     */
    private fun handleLost(userId: String, bootstrapSecret: String, username: String): Boolean {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return false
        return profiles.find(userId, invite.tenantId) == null && profiles.usernameTaken(invite.tenantId, username)
    }

    /**
     * A refusal that comes **after** the spend. The spend runs before every check that reads what a concurrent
     * accept of the same invite writes (the account, the profile, the tenant's status), so the loser of a race
     * answers as a replay and never as that check (developer-styleguide §12). A refusal that follows the spend is
     * the holder's to correct — a taken username, say — and must not cost them the invite, so the transaction is
     * rolled back, spend included. A racer blocked on the spend then re-reads the invite as still live.
     */
    private fun <T> unspend(refusal: T): T {
        QuarkusTransaction.setRollbackOnly()
        return refusal
    }

    /** A live invite is ACTIVE and unexpired. A presented non-ACTIVE/expired invite is a replay → logged. */
    private fun isLive(invite: TenantInviteEntity): Boolean {
        if (invite.status != TenantInviteEntity.ACTIVE) {
            log.warnf("Tenant invite replay rejected (status=%s, tenant=%s)", invite.status, invite.tenantId)
            return false
        }
        return !invite.expiresAt.isBefore(Instant.now())
    }
}
