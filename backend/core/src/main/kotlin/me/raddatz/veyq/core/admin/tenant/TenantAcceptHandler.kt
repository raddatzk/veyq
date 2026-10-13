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
import me.raddatz.veyq.core.membership.Admission
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
 * (ADR 0028 Decision 6), and the admission the owner self-tagged under `E_0` (ADR 0020 move 6). All or nothing —
 * a birth missing any part is no birth, and the accept answers it as a missing grant.
 */
data class OwnerBirth(val grant: SignedGrant, val admission: Admission) {
    companion object {
        fun of(grant: String?, signature: String?, admissionId: java.util.UUID?, admissionEpoch: Int?, admissionTag: String?): OwnerBirth? =
            if (grant == null || signature == null || admissionId == null || admissionEpoch == null || admissionTag == null) null
            else OwnerBirth(SignedGrant(grant, signature), Admission(admissionId.toString(), admissionEpoch, admissionTag))
    }
}

sealed interface PreviewOutcome {
    data class Found(
        val tenantId: String,
        val tenantName: String,
        val domain: String,
        val ownerEmail: String,
        val hasAccount: Boolean,
        val role: String,
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
}

sealed interface RegisterAcceptOutcome {
    data class Activated(val tenantId: String, val userId: String) : RegisterAcceptOutcome
    data object NotFound : RegisterAcceptOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
    data object NoLongerValid : RegisterAcceptOutcome, HttpFailure by httpFailure("invite_no_longer_valid", FailureStatus.GONE)
    data object EmailTaken : RegisterAcceptOutcome, HttpFailure by httpFailure("email_taken", FailureStatus.CONFLICT)
    data object MissingGrant : RegisterAcceptOutcome, HttpFailure by Failures.invalidRequest
    data object UsernameTaken : RegisterAcceptOutcome, HttpFailure by Failures.usernameTaken
    data object IdTaken : RegisterAcceptOutcome, HttpFailure by Failures.idTaken
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
        return PreviewOutcome.Found(
            tenantId = tenant.id,
            tenantName = tenant.name,
            domain = tenant.domain,
            ownerEmail = invite.ownerEmail,
            hasAccount = accounts.findByEmail(invite.ownerEmail) != null,
            role = invite.role,
        )
    }

    /** Existing-user accept: the caller (resolved from the session) must be the invited person. Role-branched
     *  (cr-0077): an OWNER accept cold-activates the tenant (grant required); a MEMBER accept joins the ACTIVE
     *  tenant grant-less (no grant), the key delivered later by a key-holder (cr-0076). */
    fun accept(
        userId: String, bootstrapSecret: String, birth: OwnerBirth?, username: String, sealedName: String?, sealedAvatar: String?,
    ): AcceptOutcome =
        createOrConflict(conflict = { if (handleLost(userId, bootstrapSecret, username)) AcceptOutcome.UsernameTaken else null }) {
            acceptOnce(userId, bootstrapSecret, birth, username, sealedName, sealedAvatar)
        }

    private fun acceptOnce(
        userId: String, bootstrapSecret: String, birth: OwnerBirth?, username: String, sealedName: String?, sealedAvatar: String?,
    ): AcceptOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return AcceptOutcome.NotFound
        if (!isLive(invite)) return AcceptOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return AcceptOutcome.NotFound

        val caller = VeyqUser.findById(userId) ?: return AcceptOutcome.NotInvitedOwner
        if (caller.email != invite.ownerEmail) return AcceptOutcome.NotInvitedOwner

        return if (invite.role == TenantInviteEntity.MEMBER) {
            if (!access.isTenantLive(tenant.id)) return AcceptOutcome.NoLongerValid
            // The rotation's lock first, so the admission copied below is one no in-flight rotation is replacing.
            store.lockTenantScope(tenant.id)
            if (!store.consumeLiveInvite(invite.id)) return AcceptOutcome.NoLongerValid
            val admission = store.currentAdmission(invite)
            // A member joins an ACTIVE tenant grant-less; if already a member, spending the invite is all there is
            // (their tenant profile already exists — the username is only chosen on a fresh join).
            if (store.belongsToTenant(userId, tenant.id)) {
                // nothing to join
            } else if (profiles.find(userId, tenant.id) != null) {
                // A member removed from the tenant and invited back: the profile outlived the removal, so they
                // rejoin under the username they had rather than being handed a second one.
                store.joinAsMember(tenant.id, userId, admission)
            } else {
                if (profiles.usernameTaken(tenant.id, username)) return unspend(AcceptOutcome.UsernameTaken)
                store.joinAsMember(tenant.id, userId, admission)
                profiles.create(userId, tenant.id, username, sealedName)
            }
            // A member invite only ever travels by mail (ADR 0055 §2), so redeeming it proves the mailbox — keyed on
            // the invite's ROLE, never on a delivery value (ADR 0074 Decision 2). Every MEMBER sub-branch above.
            caller.confirmAddress(invite.ownerEmail)
            AcceptOutcome.Activated(tenant.id)
        } else {
            val b = birth ?: return AcceptOutcome.MissingGrant
            // An OWNER accept has no scope to lock yet: the spend alone serializes it.
            if (!store.consumeLiveInvite(invite.id)) return AcceptOutcome.NoLongerValid
            if (tenant.status != TenantEntity.PENDING) return unspend(AcceptOutcome.AlreadyActive)
            if (profiles.usernameTaken(tenant.id, username)) return unspend(AcceptOutcome.UsernameTaken)
            store.activate(tenant.id, userId, b.grant, b.admission)
            // Owner cold-birth: the caller holds K_tenant here, so an optional sealed name may ride the accept (cr-0106),
            // and so may the picture (ADR 0084 decision 7) - which a MEMBER branch above never stores: it holds no key yet.
            profiles.create(userId, tenant.id, username, sealedName, sealedAvatar)
            AcceptOutcome.Activated(tenant.id)
        }
    }

    /** Register-and-accept: the invited email has no account yet — born cold here (email from the invite).
     *  Role-branched like [accept]: OWNER activates (grant required), MEMBER joins grant-less. */
    fun registerAndAccept(
        bootstrapSecret: String, userId: String, account: NewAccount, birth: OwnerBirth?, username: String, sealedName: String?,
        sealedAvatar: String?,
    ): RegisterAcceptOutcome =
        createOrConflict(conflict = { if (handleLost(userId, bootstrapSecret, username)) RegisterAcceptOutcome.UsernameTaken else null }) {
            registerAndAcceptOnce(bootstrapSecret, userId, account, birth, username, sealedName, sealedAvatar)
        }

    private fun registerAndAcceptOnce(
        bootstrapSecret: String, userId: String, account: NewAccount, birth: OwnerBirth?, username: String, sealedName: String?,
        sealedAvatar: String?,
    ): RegisterAcceptOutcome {
        val invite = store.findInviteByTokenHash(Tokens.hash(bootstrapSecret)) ?: return RegisterAcceptOutcome.NotFound
        if (!isLive(invite)) return RegisterAcceptOutcome.NoLongerValid
        val tenant = store.findTenant(invite.tenantId) ?: return RegisterAcceptOutcome.NotFound
        if (!access.isTenantLive(tenant.id)) return RegisterAcceptOutcome.NoLongerValid

        val member = invite.role == TenantInviteEntity.MEMBER
        if (!member && birth == null) return RegisterAcceptOutcome.MissingGrant
        // A MEMBER takes the rotation's lock first, as in [accept]; then the spend, before anything is born.
        if (member) store.lockTenantScope(tenant.id)
        if (!store.consumeLiveInvite(invite.id)) return RegisterAcceptOutcome.NoLongerValid
        // The account is born for the INVITED email (a MEMBER invite's email differs from the tenant owner's).
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
            store.joinAsMember(tenant.id, userId, store.currentAdmission(invite))
            // Mail-only by construction (ADR 0055 §2): the redeemed token is the mailbox proof (ADR 0074 Decision 2).
            // The OWNER branch is the copy-link and stamps nothing.
            VeyqUser.findById(userId)!!.confirmAddress(invite.ownerEmail)
        } else {
            store.activate(tenant.id, userId, birth!!.grant, birth.admission)
        }
        // The per-tenant identity: the plaintext handle, plus the optional sealed real name when the invitee
        // already holds K_tenant at accept (owner cold-birth / grant-sealed accept — ADR 0023); a grant-less
        // member passes null and seals later via the profile op. The server stores the blob verbatim (blind).
        // The picture rides only the OWNER branch (ADR 0084 decision 7): a MEMBER invitee holds no K_tenant yet.
        profiles.create(userId, tenant.id, username, sealedName, sealedAvatar.takeIf { !member })
        return RegisterAcceptOutcome.Activated(tenant.id, userId)
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
