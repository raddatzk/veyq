package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.identity.keychain.KeyChainStore
import me.raddatz.veyq.core.backup.ErasureKind
import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.platform.capabilities.ApplicationErasureStore
import me.raddatz.veyq.core.platform.capabilities.SwitchLevel
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.MembershipStore
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.tenant.TenantHostResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A tenant plus the state of its current (latest) invite, for the Server-Owner list view. */
data class TenantView(
    val id: String,
    val name: String,
    val domain: String,
    val ownerEmail: String,
    val status: String,
    val inviteStatus: String?,
    val inviteExpiresAt: Instant?,
    /** What the tenant holds in the store (ADR 0073 §2), from `StorageUsage` — this store never sums. */
    val storageUsedBytes: Long,
    /** The Server Owner's quota for the tenant; null = none (ADR 0073 §1). */
    val storageQuotaBytes: Long?,
    /** An ACTIVE or SUSPENDED tenant no member owns — its last owner was erased at the Server Owner's door (ADR 0101). */
    val ownerless: Boolean = false,
    /** When the purge erases a tenant pending deletion (ADR 0101 decision 10); null otherwise. */
    val deletionDueAt: Instant? = null,
)

/** The state of a tenant's current invite after a mint/resend/reassign. `bootstrapSecret` is the raw token,
 *  present only on a freshly-minted invite (so the owner can copy the invite link) — never on a list read. */
data class InviteView(val ownerEmail: String, val status: String, val expiresAt: Instant, val bootstrapSecret: String? = null)

/** A pending member invite in the owner-facing list (cr-0082) — metadata only, never the secret. */
data class PendingInviteRow(val inviteId: String, val email: String, val status: String, val expiresAt: Instant)

/**
 * A member invite's key and grant as the owner's client built them (ADR 0111 decisions 1-2) — what
 * [TenantStore.mintMemberInvite] stores. The signer is the caller, with their identity key read in the write's own
 * transaction; the grant's granter is the signer too.
 */
data class MemberInviteKey(
    val inviteId: String,
    val invitePublicKey: String,
    val inviteKeySignature: String,
    val signerUserId: String,
    val signerIdentityPublicKey: String,
    val epoch: Int,
    val grant: SignedGrant,
)

/** Why a member invite was not minted (nothing written), or its id. */
sealed interface MintMemberInvite {
    data class Minted(val inviteId: String) : MintMemberInvite
    /** The grant is not of the tenant scope's current epoch, read under the rotation's lock. */
    data object StaleEpoch : MintMemberInvite
    /** The client-minted invite id names an invite that exists. */
    data object IdTaken : MintMemberInvite
}

/** An open member invite as a rotator reads it (ADR 0111 decision 2): enough to verify and wrap to it — no email. */
data class OpenInviteRow(
    val inviteId: String,
    val invitePublicKey: String,
    val inviteKeySignature: String,
    val signerUserId: String,
    val signerIdentityPublicKey: String,
    val expiresAt: Instant,
    /** The head of the signer's key chain now (ADR 0111 decision 7) — a rotator re-grants the invite only if it reaches
     *  the signer key from the head it pinned. */
    val signerKeyChainHead: String,
)

/** One open invite a tenant rotation re-grants: the new epoch's key wrapped to the invite key, signed by the rotator. */
data class InviteRegrant(val inviteId: String, val grant: SignedGrant)

/** A tenant's current terms (cr-0114). `markdownByLocale` is the raw
 *  serialized locale→markdown map (null = none set); the handler decodes it into the per-locale DTO. */
data class TenantTermsRow(val revision: Int, val markdownByLocale: String?)

/** A tenant's current terms with **no** caller — revision + the raw serialized markdown, no acceptance lookup
 *  (the accountless share terms read, uc.sharing.share-accept). Distinct from [TenantTermsRow] which carries a
 *  caller's acceptedRevision; an accountless visitor has no account, so there is nothing to report. */
data class TenantGuestTermsRow(val revision: Int, val markdownByLocale: String?)

/** A tenant's mail configuration (cr-email-smtp-transport). Carries the **sealed** SMTP password (ciphertext +
 *  nonce, ADR 0050) for internal use; the handler redacts it to `passwordSet` before it leaves the server. */
data class MailSettingsRow(
    val host: String?,
    val port: Int?,
    val from: String?,
    val username: String?,
    val passwordCiphertext: String?,
    val passwordNonce: String?,
    /** The fingerprint of the key that sealed the password (ADR 0089 decision 4); non-null iff it is stored. */
    val passwordFingerprint: String?,
    val tls: Boolean,
    val enabled: Boolean,
    val subjectTemplate: String?,
    val bodyTemplate: String?,
) {
    val passwordSet: Boolean get() = passwordCiphertext != null
}

/**
 * Persistence for tenant creation + invite lifecycle (cr-0009). The invite is its own row history; only the
 * SHA-256 token hash is stored (never the token), and transitions are soft (ACTIVE → SUPERSEDED/CONSUMED/
 * EXPIRED) — nothing is hard-deleted inline; a scheduled purge removes terminal rows later.
 */
@ApplicationScoped
class TenantStore(
    private val keyChain: KeyChainStore,
    private val membership: MembershipStore,
    private val hosts: TenantHostResolver,
    private val ledger: ErasureLedger,
    private val erasures: ApplicationErasureStore,
) {

    /** Whether some tenant already claims [domain] — through the one Host→tenant lookup, so "taken" and "resolves"
     *  can never disagree about what a domain names. */
    fun domainExists(domain: String): Boolean = hosts.tenantFor(domain) != null

    fun findTenant(id: String): TenantEntity? = TenantEntity.findById(id)

    /** Find an invite by its token hash (the accept lookup), or null if no invite ever had that token. */
    fun findInviteByTokenHash(tokenHash: String): TenantInviteEntity? =
        TenantInviteEntity.find("tokenHash", tokenHash).firstResult()

    /**
     * Spend a live invite — a **compare-and-set**, and the first write of the accept that calls it: only a row still
     * `ACTIVE` and unexpired flips to `CONSUMED`, and the caller goes on only if exactly one did. Two accepts that
     * both read the invite live cannot both win, and the loser has written nothing a constraint would have to undo,
     * so it answers exactly as a replay of the spent secret does (developer-styleguide §12).
     *
     * The `and status = ACTIVE` in the WHERE is the transition's guard, not a re-read: the database decides it, so a
     * revoke that landed between the caller's read and this write is not overwritten either. The caller's earlier
     * read only tells a 404 from a 410 and supplies the tenant; it never authorizes the spend.
     */
    @Transactional
    fun consumeLiveInvite(inviteId: String): Boolean =
        TenantInviteEntity.update(
            "status = ?1, ${TenantInviteEntity.INVITE_KEY_DROPPED} where id = ?2 and status = ?3 and expiresAt > ?4",
            TenantInviteEntity.CONSUMED, inviteId, TenantInviteEntity.ACTIVE, Instant.now(),
        ) == 1

    /**
     * Take the tenant scope's row lock — the lock a rotation and [mintMemberInvite] take — and return the scope's
     * current epoch (null: no tenant scope). A MEMBER accept takes it before the spend, so a rotation cannot re-grant
     * the invite between the accept's read of it and the membership it writes, and the self-grant's epoch is judged
     * against the epoch as committed now.
     */
    @Transactional
    fun lockTenantScope(tenantId: String): Int? = ScopeEntity.lockRow(tenantId)?.currentEpoch

    /** The invite as committed **now** — after [lockTenantScope], so a rotation that re-granted it is seen. A refresh,
     *  not a find: a find would hand back the copy this transaction read before the lock. */
    fun refreshInvite(invite: TenantInviteEntity) {
        TenantInviteEntity.getEntityManager().refresh(invite)
    }

    /**
     * Activate a tenant on acceptance — **atomic**: record the owner's `OWNER` membership + grant and flip the
     * tenant `ACTIVE`. The invite was already spent by [consumeLiveInvite], first in the same transaction. The grant
     * is opaque ciphertext stored verbatim, with the owner's self-signature over it (ADR 0028 Decision 6). The first
     * owner is the tenant's trust root (ADR 0111 decision 3): nobody vouches for them, so they have no join record.
     */
    @Transactional
    fun activate(tenantId: String, userId: String, grant: SignedGrant) {
        // The grant's recipient key was checked by the accept, against the key this reads (ADR 0111 decision 10).
        // The TENANT root scope is born with the owner membership; keyed id == tenantId (cr-0026).
        ScopeEntity().apply {
            id = tenantId
            this.tenantId = tenantId
            kind = ScopeEntity.TENANT
        }.persist()
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            scopeId = tenantId
            role = MembershipEntity.OWNER
            wrappedGrant = grant.wrappedGrant
            granterUserId = userId
            granterIdentityPublicKey = VeyqUser.findById(userId)?.identityPublicKey
            grantSignature = grant.signature
            createdAt = Instant.now()
        }.persist()
        TenantEntity.update("status = ?1 where id = ?2", TenantEntity.ACTIVE, tenantId)
    }

    /**
     * Join an **already-active** tenant as a MEMBER **with the grant it gave itself** (ADR 0111 decision 3): the
     * newcomer opened its invite's grant and sealed that epoch's key to its own identity. Its granter is the newcomer,
     * with its identity key read here; at [epoch] — the tenant's current one, or older for an invite a rotation
     * declared pending, which leaves the membership behind for the grant delivery to bring current. The invite was
     * already spent by [consumeLiveInvite]. One writer for both accepts (sign-in and register). Idempotent-friendly:
     * the caller checks membership first.
     */
    @Transactional
    fun joinAsMember(tenantId: String, userId: String, grant: SignedGrant, epoch: Int) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            scopeId = tenantId // the TENANT root scope is keyed id == tenantId (cr-0026)
            role = MembershipEntity.MEMBER
            wrappedGrant = grant.wrappedGrant
            grantedEpoch = epoch
            granterUserId = userId
            granterIdentityPublicKey = VeyqUser.findById(userId)?.identityPublicKey
            grantSignature = grant.signature
            createdAt = Instant.now()
        }.persist()
    }

    /** Whether [userId] belongs to the tenant — an accepted root membership. A question about a *target* (an
     *  invitee, a returning member); whether a *caller* may act on the tenant is the scope-access gate's. */
    fun belongsToTenant(userId: String, tenantId: String): Boolean = membership.belongsToTenant(userId, tenantId)

    /** A tenant owner holds an OWNER membership on the tenant **root scope** (cr-0163), not merely any OWNER row
     *  carrying this `tenantId`. Delegates to the single home in `core/membership/`. */
    fun isOwner(userId: String, tenantId: String): Boolean = membership.isTenantOwner(userId, tenantId)

    /**
     * Mint a single-use MEMBER invite on an active tenant — a placeholder member holding [key] (ADR 0111 decisions 1-2)
     * — superseding any prior ACTIVE invite to that email (whose key and grant go with it). Refused, nothing written,
     * when the grant is not of the tenant scope's current epoch, read under the lock a rotation takes: a rotation that
     * committed in between could not have carried it ([MintMemberInvite.StaleEpoch]); and when the client-minted id is
     * taken. The invite's granter is its signer, the caller.
     */
    @Transactional
    fun mintMemberInvite(tenantId: String, email: String, tokenHash: String, expiresAt: Instant, key: MemberInviteKey): MintMemberInvite {
        if (ScopeEntity.lockRow(tenantId)?.currentEpoch != key.epoch) return MintMemberInvite.StaleEpoch
        if (TenantInviteEntity.findById(key.inviteId) != null) return MintMemberInvite.IdTaken
        TenantInviteEntity.update(
            "status = ?1, ${TenantInviteEntity.INVITE_KEY_DROPPED} where tenantId = ?2 and ownerEmail = ?3 and status = ?4",
            TenantInviteEntity.SUPERSEDED, tenantId, email, TenantInviteEntity.ACTIVE,
        )
        TenantInviteEntity().apply {
            id = key.inviteId
            this.tenantId = tenantId
            ownerEmail = email
            this.tokenHash = tokenHash
            status = TenantInviteEntity.ACTIVE
            role = TenantInviteEntity.MEMBER
            this.expiresAt = expiresAt
            createdAt = Instant.now()
            invitePublicKey = key.invitePublicKey
            inviteKeySignature = key.inviteKeySignature
            signerUserId = key.signerUserId
            signerIdentityPublicKey = key.signerIdentityPublicKey
            wrappedGrant = key.grant.wrappedGrant
            grantedEpoch = key.epoch
            grantSignature = key.grant.signature
            granterUserId = key.signerUserId
            granterIdentityPublicKey = key.signerIdentityPublicKey
        }.persist()
        return MintMemberInvite.Minted(key.inviteId)
    }

    /** The tenant's pending (ACTIVE + unexpired) MEMBER invites — metadata only, for the owner's tracking list. */
    fun listPendingMemberInvites(tenantId: String): List<PendingInviteRow> =
        openMemberInvites(tenantId).map { PendingInviteRow(it.id, it.ownerEmail, it.status, it.expiresAt) }

    /** The tenant's open MEMBER invites with their keys — what every member reads to carry them through a rotation
     *  (`listOpenInvites`). An open invite always holds its key; one that does not is skipped. */
    fun openInviteKeys(tenantId: String): List<OpenInviteRow> {
        val invites = openMemberInvites(tenantId)
        val heads = keyChain.heads(invites.mapNotNull { it.signerUserId })
        return invites.mapNotNull {
            val signer = it.signerUserId ?: return@mapNotNull null
            OpenInviteRow(
                it.id,
                it.invitePublicKey ?: return@mapNotNull null,
                it.inviteKeySignature ?: return@mapNotNull null,
                signer,
                it.signerIdentityPublicKey ?: return@mapNotNull null,
                it.expiresAt,
                heads[signer] ?: return@mapNotNull null,
            )
        }
    }

    /** The key-chain heads of [userIds] that exist (ADR 0111 decision 7) — what the invitee's preview serves beside the
     *  signer and the granter of its invite. */
    fun keyChainHeads(userIds: Collection<String>): Map<String, String> = keyChain.heads(userIds)

    /** The tenant's open MEMBER invites (ACTIVE + unexpired) — the one predicate both lists and a tenant rotation's
     *  completeness check use, so a rotator is required to carry exactly the invites it was shown (ADR 0111 decision
     *  2). An invite that expires unswept is left behind; the sweep drops its key. */
    fun openMemberInvites(tenantId: String): List<TenantInviteEntity> =
        TenantInviteEntity.find(
            "tenantId = ?1 and role = ?2 and status = ?3 and expiresAt > ?4",
            tenantId, TenantInviteEntity.MEMBER, TenantInviteEntity.ACTIVE, Instant.now(),
        ).list()

    /** A specific pending (ACTIVE) MEMBER invite of this tenant, or null (unknown / consumed / terminal). */
    fun findPendingMemberInvite(tenantId: String, inviteId: String): TenantInviteEntity? =
        TenantInviteEntity.find(
            "id = ?1 and tenantId = ?2 and role = ?3 and status = ?4",
            inviteId, tenantId, TenantInviteEntity.MEMBER, TenantInviteEntity.ACTIVE,
        ).firstResult()

    /**
     * Soft-revoke a live invite (→ SUPERSEDED terminal) so its secret replays as no-longer-valid, dropping its key and
     * grant in the same statement (ADR 0111 decision 2). A compare-and-set like [consumeLiveInvite], so a revoke that
     * lost to an accept changes nothing, and an accept that lost to it cannot join. Returns whether this call revoked
     * it. The tenant does not rotate (ADR 0025).
     */
    @Transactional
    fun revokeInvite(inviteId: String): Boolean =
        TenantInviteEntity.update(
            "status = ?1, ${TenantInviteEntity.INVITE_KEY_DROPPED} where id = ?2 and status = ?3",
            TenantInviteEntity.SUPERSEDED, inviteId, TenantInviteEntity.ACTIVE,
        ) == 1

    /**
     * Re-grant the open invites a tenant rotation carries into its new epoch (ADR 0111 decision 2): each listed one
     * gets the new epoch's key wrapped to its invite key, signed by [rotatorId] — the rotation's granter, with their
     * identity key read here. A **bulk update of the grant columns only, guarded by `status = ACTIVE`**, never a save
     * of the entities read: the rotation read the invites under the scope lock, which neither a revoke nor a member
     * accept's spend takes, and a managed save would write their stale `status` back — a revoked or spent secret live
     * again. An invite spent or revoked meanwhile is left as it is: it has no key left to wrap to, and the completeness
     * check already ran on the list the rotator was shown. A declared-pending invite keeps the grant it holds.
     */
    @Transactional
    fun carryInvites(regrants: List<InviteRegrant>, newEpoch: Int, rotatorId: String) {
        val rotatorKey = VeyqUser.findById(rotatorId)?.identityPublicKey ?: return
        regrants.forEach { r ->
            TenantInviteEntity.update(
                "wrappedGrant = ?1, grantedEpoch = ?2, grantSignature = ?3, granterUserId = ?4, " +
                    "granterIdentityPublicKey = ?5 where id = ?6 and status = ?7",
                r.grant.wrappedGrant, newEpoch, r.grant.signature, rotatorId, rotatorKey, r.inviteId, TenantInviteEntity.ACTIVE,
            )
        }
    }

    /** Create a PENDING tenant with its first ACTIVE invite. Returns the new tenant id + invite expiry. */
    @Transactional
    fun createPendingTenant(
        name: String,
        domain: String,
        ownerEmail: String,
        tokenHash: String,
        expiresAt: Instant,
    ): Pair<String, Instant> {
        // The instance row's lock before the tenant exists (ApplicationErasureStore's lock order): an instance switch of
        // an application either committed before this reads it, and the new tenant gets its erasure row below, or it
        // waits and finds the tenant (uc.platform.erase-switched-off-application A4).
        erasures.lockInstance()
        val now = Instant.now()
        val tenantId = UUID.randomUUID().toString()
        TenantEntity().apply {
            id = tenantId
            this.name = name
            this.domain = domain
            this.ownerEmail = ownerEmail
            status = TenantEntity.PENDING
        }.persist()
        mintInvite(tenantId, ownerEmail, tokenHash, expiresAt, now)
        TenantEntity.flush()
        // Its own window from its creation, and no notice: nobody is a member yet. The reminder reaches who joins.
        ApplicationErasureStore.APPLICATIONS.filter { erasures.isOff(tenantId, it) }.forEach {
            erasures.reconcile(tenantId, it, SwitchLevel.INSTANCE, now, noticeOwed = false)
        }
        return tenantId to expiresAt
    }

    /** Soft-supersede the tenant's current ACTIVE invite(s) and mint a fresh ACTIVE one (resend/reassign). */
    @Transactional
    fun supersedeAndMintInvite(
        tenantId: String,
        ownerEmail: String,
        tokenHash: String,
        expiresAt: Instant,
    ): InviteView {
        TenantInviteEntity.update(
            "status = ?1, ${TenantInviteEntity.INVITE_KEY_DROPPED} where tenantId = ?2 and status = ?3",
            TenantInviteEntity.SUPERSEDED, tenantId, TenantInviteEntity.ACTIVE,
        )
        mintInvite(tenantId, ownerEmail, tokenHash, expiresAt, Instant.now())
        return InviteView(ownerEmail, TenantInviteEntity.ACTIVE, expiresAt)
    }

    /** Re-bind the tenant's intended owner email (reassign). */
    @Transactional
    fun updateOwnerEmail(tenantId: String, ownerEmail: String) {
        TenantEntity.update("ownerEmail = ?1 where id = ?2", ownerEmail, tenantId)
    }

    /**
     * Take an ACTIVE tenant offline (ADR 0066) — `status` is the only column touched, so no key, grant, content
     * row or session is in this path. Returns whether the transition happened.
     *
     * The `and status = ACTIVE` in the WHERE is the transition's guard, not a re-read: two concurrent suspends
     * cannot double-apply and a suspend cannot silently overwrite a reactivate that landed between a read and a
     * write, because there is no such window — the database decides the transition. The caller's separate
     * existence probe only tells a 404 from a 409; it never authorizes the write.
     */
    @Transactional
    fun suspend(tenantId: String): Boolean =
        TenantEntity.update(
            "status = ?1 where id = ?2 and status = ?3",
            TenantEntity.SUSPENDED, tenantId, TenantEntity.ACTIVE,
        ) > 0

    /** Set the tenant owner's per-link storage limit (ADR 0073 §4) — the only writer of the column. */
    @Transactional
    fun setShareLinkLimit(tenantId: String, shareLinkLimitBytes: Long) {
        TenantEntity.update("shareLinkLimitBytes = ?1 where id = ?2", shareLinkLimitBytes, tenantId)
    }

    /**
     * Set or clear (null) the tenant's storage quota (ADR 0073 §1) — the only writer of the column, reached only by
     * the Server Owner. The write is an UPDATE of the tenant row at flush, so it waits for, and is waited on by, the row
     * lock an upload start holds while it reserves. (Through the entity, not a JPQL `update`: Panache's positional
     * parameters are non-null, and null is the value that clears.) Returns whether the tenant exists.
     */
    @Transactional
    fun setStorageQuota(tenantId: String, quotaBytes: Long?): Boolean {
        val tenant = TenantEntity.findById(tenantId) ?: return false
        tenant.storageQuotaBytes = quotaBytes
        return true
    }

    /** Bring a SUSPENDED tenant back to ACTIVE — the exact inverse of [suspend], same guarded-UPDATE reasoning. */
    @Transactional
    fun reactivate(tenantId: String): Boolean =
        TenantEntity.update(
            "status = ?1 where id = ?2 and status = ?3",
            TenantEntity.ACTIVE, tenantId, TenantEntity.SUSPENDED,
        ) > 0

    /**
     * Move a tenant to a different domain (uc.admin.create-tenant), allowed from PENDING **or** ACTIVE — the
     * same guarded-`UPDATE` shape as [suspend]/[reactivate], and for the same reason: the `and status in (…)`
     * is the transition's guard, so a concurrent suspend cannot be overwritten by a domain change that read the
     * old status a moment earlier. Returns whether the move happened.
     *
     * Uniqueness is the caller's check plus the column's UNIQUE; this only writes.
     */
    @Transactional
    fun changeDomain(tenantId: String, domain: String): Boolean =
        TenantEntity.update(
            "domain = ?1 where id = ?2 and status in (?3, ?4)",
            domain, tenantId, TenantEntity.PENDING, TenantEntity.ACTIVE,
        ) > 0

    /**
     * Delete an ACTIVE or SUSPENDED tenant (ADR 0101 decision 10): it goes offline as a suspension takes it, under
     * `PENDING_DELETION`, until [dueAt]. The same guarded-`UPDATE` shape as [suspend]: the `status in (…)` decides the
     * transition, so a concurrent reactivate or a second delete cannot be overwritten. Returns whether it happened.
     */
    @Transactional
    fun markForDeletion(tenantId: String, dueAt: Instant): Boolean =
        TenantEntity.update(
            "status = ?1, deletionDueAt = ?2 where id = ?3 and status in (?4, ?5)",
            TenantEntity.PENDING_DELETION, dueAt, tenantId, TenantEntity.ACTIVE, TenantEntity.SUSPENDED,
        ) > 0

    /** Stop a pending deletion: the tenant returns SUSPENDED, as it was taken offline (ADR 0101 decision 10). */
    @Transactional
    fun stopDeletion(tenantId: String): Boolean =
        TenantEntity.update(
            "status = ?1, deletionDueAt = null where id = ?2 and status = ?3",
            TenantEntity.SUSPENDED, tenantId, TenantEntity.PENDING_DELETION,
        ) > 0

    /** The tenants whose deletion is due at [now] — `TenantPurge`'s work list. */
    fun dueForDeletion(now: Instant): List<String> =
        TenantEntity.list("status = ?1 and deletionDueAt <= ?2", TenantEntity.PENDING_DELETION, now).map { it.id }

    /** Whether [userId] is an accepted member of the tenant's TENANT scope (its root, `scopeId == tenantId`). */
    fun isTenantMember(userId: String, tenantId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and pendingConsent = false", userId, tenantId) > 0

    /** The accepted members of the tenant's TENANT scope, by account id. */
    fun tenantMemberIds(tenantId: String): List<String> =
        MembershipEntity.list("scopeId = ?1 and pendingConsent = false", tenantId).map { it.userId }

    /** How many OWNERs the tenant's TENANT scope has. */
    fun ownerCount(tenantId: String): Long =
        MembershipEntity.count("scopeId = ?1 and role = ?2 and pendingConsent = false", tenantId, MembershipEntity.OWNER)

    /** Set [userId]'s TENANT-scope role — decides nothing; the caller holds the tenant scope's lock and the gates. */
    @Transactional
    fun setTenantRole(tenantId: String, userId: String, role: String) {
        MembershipEntity.update("role = ?1 where userId = ?2 and scopeId = ?3", role, userId, tenantId)
    }

    /**
     * Mint an OWNER invite into an ACTIVE tenant for one of its members (ADR 0101 decision 9). Supersedes the tenant's
     * live OWNER invites only — never its member invites, which [supersedeAndMintInvite] would end too.
     */
    @Transactional
    fun mintOwnerInvite(tenantId: String, email: String, tokenHash: String, expiresAt: Instant): InviteView {
        TenantInviteEntity.update(
            "status = ?1, ${TenantInviteEntity.INVITE_KEY_DROPPED} where tenantId = ?2 and status = ?3 and role = ?4",
            TenantInviteEntity.SUPERSEDED, tenantId, TenantInviteEntity.ACTIVE, TenantInviteEntity.OWNER,
        )
        mintInvite(tenantId, email, tokenHash, expiresAt, Instant.now())
        return InviteView(email, TenantInviteEntity.ACTIVE, expiresAt)
    }

    /**
     * An erased account's address leaves the tenants whose owner invite went to it (ADR 0101 decision 4): the tenant
     * stays, the record of whom its first owner invite named does not. Not a routing column: no reprojection.
     */
    @Transactional
    fun forgetOwnerAddress(address: String) {
        TenantEntity.update("ownerEmail = ?1 where ownerEmail = ?2", "", address)
    }

    /**
     * An account's address change carries the tenants whose owner invite went to the old address along (ADR 0101
     * decision 4, inbox #591): the column names the account's address, never one it gave up, so [forgetOwnerAddress] at
     * its erasure finds every tenant that names it. In the swap's transaction, after the account row's lock — the order
     * the account's purge takes too (account, then tenant rows). Not a routing column: no reprojection.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun followOwnerAddress(oldAddress: String, newAddress: String) {
        TenantEntity.update("ownerEmail = ?1 where ownerEmail = ?2", newAddress, oldAddress)
    }

    /** Revoke a pending tenant: delete its invite history — the address its owner invite went to — then the tenant
     *  itself, with its erasure ledger line (ADR 0056 decision 8): a restore of an earlier capture would bring both back. */
    @Transactional
    fun deleteTenant(tenantId: String) {
        TenantInviteEntity.delete("tenantId", tenantId)
        ledger.erased(ErasureKind.TENANT, tenantId)
        TenantEntity.deleteById(tenantId)
    }

    /** Every tenant, with its usage read from [usageByTenant] (tenant id → bytes; a tenant it lacks holds nothing). */
    fun listAll(usageByTenant: Map<String, Long>): List<TenantView> =
        TenantEntity.listAll().map { t ->
            val invite = currentInvite(t.id)
            TenantView(
                id = t.id,
                name = t.name,
                domain = t.domain,
                ownerEmail = t.ownerEmail,
                status = t.status,
                inviteStatus = invite?.status,
                inviteExpiresAt = invite?.expiresAt,
                storageUsedBytes = usageByTenant[t.id] ?: 0,
                storageQuotaBytes = t.storageQuotaBytes,
                ownerless = (t.status == TenantEntity.ACTIVE || t.status == TenantEntity.SUSPENDED) && ownerCount(t.id) == 0L,
                deletionDueAt = t.deletionDueAt,
            )
        }

    private fun currentInvite(tenantId: String): TenantInviteEntity? =
        TenantInviteEntity.find("tenantId = ?1 order by createdAt desc, id desc", tenantId).firstResult()

    // --- terms of service (cr-0114, ADR 0035) ---

    /** The tenant's current terms. Null if no such tenant. */
    fun termsFor(tenantId: String): TenantTermsRow? {
        val tenant = TenantEntity.findById(tenantId) ?: return null
        return TenantTermsRow(tenant.termsRevision, tenant.termsMarkdownByLocale)
    }

    /** Whether [userId]'s history for [tenantId] holds a row for the pair — the member gate's answer (ADR 0035
     *  decision 6): the space's [revision] and the operator section's [operatorRevision]. */
    fun hasAccepted(userId: String, tenantId: String, revision: Int, operatorRevision: Int): Boolean =
        TenantTermsAcceptanceEntity.count(
            "userId = ?1 and tenantId = ?2 and acceptedRevision = ?3 and operatorRevision = ?4",
            userId, tenantId, revision, operatorRevision,
        ) > 0

    /** The tenant's current terms **without** a caller — revision + serialized markdown, and **no** acceptance
     *  lookup (uc.sharing.share-accept: the accountless visitor's acceptance is client-only, so the server keeps
     *  no record and needs no userId). Null if no such tenant. */
    fun guestTermsFor(tenantId: String): TenantGuestTermsRow? {
        val tenant = TenantEntity.findById(tenantId) ?: return null
        return TenantGuestTermsRow(tenant.termsRevision, tenant.termsMarkdownByLocale)
    }

    /** Store the (already normalized) serialized terms and bump the revision **only if the markdown changed** — an
     *  idempotent re-save returns the same revision (the pre-E2EE logic). Returns the resulting current revision. */
    @Transactional
    fun updateTerms(tenantId: String, normalizedMarkdown: String): Int {
        val tenant = TenantEntity.findById(tenantId) ?: return 0
        if (tenant.termsMarkdownByLocale == normalizedMarkdown) return tenant.termsRevision
        tenant.termsMarkdownByLocale = normalizedMarkdown
        tenant.termsRevision += 1
        tenant.persist()
        return tenant.termsRevision
    }

    /** Record [userId]'s acceptance of the pair for [tenantId], stamped with the server's own time — one row per
     *  pair (keyed by the (user, tenant, revision, operator revision) UNIQUE), so an older pair's row and time stay as
     *  evidence (ADR 0035 §4). Written once per pair: re-accepting one already recorded changes nothing — the first
     *  time stands. */
    @Transactional
    fun recordAcceptance(userId: String, tenantId: String, revision: Int, operatorRevision: Int) {
        if (hasAccepted(userId, tenantId, revision, operatorRevision)) return
        TenantTermsAcceptanceEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            acceptedRevision = revision
            this.operatorRevision = operatorRevision
            acceptedAt = Instant.now()
        }.persist()
    }

    // --- per-tenant mail settings (cr-email-smtp-transport, ADR 0048/0049/0050) ---

    /** The tenant's mail settings (sealed password included), or null if it never configured any. */
    fun mailSettingsFor(tenantId: String): MailSettingsRow? =
        TenantMailSettingsEntity.findById(tenantId)?.toRow()

    /** Upsert a tenant's mail settings verbatim. The caller has already sealed (or preserved) the password and
     *  validated the templates — this only persists the given row. */
    @Transactional
    fun saveMailSettings(tenantId: String, row: MailSettingsRow) {
        val e = TenantMailSettingsEntity.findById(tenantId) ?: TenantMailSettingsEntity().apply { this.tenantId = tenantId }
        e.host = row.host
        e.port = row.port
        e.fromAddress = row.from
        e.username = row.username
        e.passwordCiphertext = row.passwordCiphertext
        e.passwordNonce = row.passwordNonce
        e.passwordFingerprint = row.passwordFingerprint
        e.tls = row.tls
        e.enabled = row.enabled
        e.subjectTemplate = row.subjectTemplate
        e.bodyTemplate = row.bodyTemplate
        e.persist()
    }

    private fun TenantMailSettingsEntity.toRow() = MailSettingsRow(
        host = host, port = port, from = fromAddress, username = username,
        passwordCiphertext = passwordCiphertext, passwordNonce = passwordNonce, passwordFingerprint = passwordFingerprint,
        tls = tls, enabled = enabled, subjectTemplate = subjectTemplate, bodyTemplate = bodyTemplate,
    )

    // --- scheduled cleanup (see TenantInvitePurge) ---

    /** Flip ACTIVE invites whose TTL has passed to EXPIRED, dropping their keys and grants in the same statement
     *  (ADR 0111 decision 2). Returns how many were expired. */
    @Transactional
    fun expireOverdueInvites(now: Instant): Long =
        TenantInviteEntity.update(
            "status = ?1, ${TenantInviteEntity.INVITE_KEY_DROPPED} where status = ?2 and expiresAt < ?3",
            TenantInviteEntity.EXPIRED, TenantInviteEntity.ACTIVE, now,
        ).toLong()

    /** Physically delete terminal-state invites whose TTL passed before [cutoff]. Returns how many. */
    @Transactional
    fun purgeTerminalInvites(cutoff: Instant): Long =
        TenantInviteEntity.delete(
            "status in ?1 and expiresAt < ?2",
            TenantInviteEntity.TERMINAL, cutoff,
        )

    /** An OWNER invite — the bootstrap one or a handover (ADR 0101 decision 9): no key, there is no grant to hand over. */
    private fun mintInvite(tenantId: String, ownerEmail: String, tokenHash: String, expiresAt: Instant, now: Instant): String {
        val inviteId = UUID.randomUUID().toString()
        TenantInviteEntity().apply {
            id = inviteId
            this.tenantId = tenantId
            this.ownerEmail = ownerEmail
            this.tokenHash = tokenHash
            status = TenantInviteEntity.ACTIVE
            role = TenantInviteEntity.OWNER
            this.expiresAt = expiresAt
            createdAt = now
        }.persist()
        return inviteId
    }
}
