package com.veyq.app.features.spaces.scope

import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.identity.TenantProfileStore
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.MembershipStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A scope the caller holds, with their own grant ciphertext — the row behind a `ScopeSummary`. `encryptedName`
 *  is the sealed group name for a GROUP scope (ciphertext), null for TENANT/PERSONAL (ADR 0014). `role` is the
 *  caller's own role in the scope (OWNER/MEMBER, cr-0087). */
data class ScopeRow(
    val id: String,
    val kind: String,
    val tenantId: String,
    val role: String,
    val currentEpoch: Int,
    val grantedEpoch: Int,
    val wrappedGrant: String,
    val encryptedName: String?,
    /** The epoch the group name was sealed under (cr-0096/ADR 0027) — the client derives `K_name` from its key. */
    val nameEpoch: Int,
    /** The scope's icon sealed under `K_icon` (uc.spaces.set-space-icon), or null; the client opens + unpads it. */
    val sealedIcon: String?,
    /** The epoch `sealedIcon` was sealed under — the client peels the chain to it and derives `K_icon`. */
    val iconEpoch: Int,
    /** When a GROUP is in pending-deletion (cr-0090); null = active. The client marks + (for owners) restores it. */
    val deletedAt: Instant?,
)

/** A tenant the caller belongs to — the row behind a `MyTenant` (the space switcher). Carries the caller's own
 *  root-scope `wrappedGrant` + the tenant's `sealedIcon`/`iconEpoch` (uc.spaces.set-space-icon) so the switcher
 *  renders each tenant's avatar without a per-tenant scopes call (en.spaces.tenant-scope). `wrappedGrant` is null
 *  while the caller is grant-less (the transient awaiting-access window). */
data class TenantRow(
    val tenantId: String,
    val name: String,
    val domain: String,
    val role: String,
    val wrappedGrant: String?,
    val sealedIcon: String?,
    val iconEpoch: Int,
)

/** A member of a scope — the row behind a `ScopeMember` (the member directory, cr-0077). `hasGrant=false` is a
 *  pending member awaiting delivery. `identityPublicKey` is a key-holder's `asymWrap` target — never a key.
 *  `username` + `sealedName` come from the member's per-tenant profile (ADR 0023); `email` is owner-only — the
 *  handler nulls it for non-owner callers. */
data class ScopeMemberRow(
    val userId: String,
    val username: String,
    val sealedName: String?,
    val email: String?,
    val role: String,
    val identityPublicKey: String,
    val hasGrant: Boolean,
    /** A soft consent proposal awaiting the target's Accept (cr-0094) — owner-visible only in the directory. */
    val pendingConsent: Boolean = false,
)

/** The result of resolving a per-tenant username to a group-add candidate (cr-0094) — records nothing. */
data class ResolvedCandidateRow(
    val userId: String,
    val username: String,
    val identityPublicKey: String,
    val requiresConsent: Boolean,
)

/** A pending consent proposal from the invitee's point of view (cr-0096): the group + who proposed + the wrapped
 *  name-key `K_name` (`sealedNameKey`) + the scope's canonical `encryptedName` the key opens (ADR 0027). */
data class GroupInvitationRow(val scopeId: String, val addedByUsername: String, val sealedNameKey: String, val encryptedName: String)

/** The caller's membership state on a scope (cr-0094) — drives idempotent accept/decline + 404. */
enum class ConsentState { PENDING, ACTIVE, NONE }

/**
 * Persistence for a member's scopes (cr-0026). Operator-blind: stores only the opaque scope row + the
 * ciphertext grant on the membership; never a scope key. The active tenant is resolved by domain (Host).
 */
@ApplicationScoped
class ScopesStore(private val profiles: TenantProfileStore, private val membership: MembershipStore) {

    /** The tenant id for a Host/domain, or null if no tenant claims it. */
    fun findTenantIdByDomain(domain: String): String? =
        TenantEntity.find("domain", domain).firstResult()?.id

    /** Whether the caller already holds a membership in this tenant (i.e. belongs to it). */
    fun isMember(userId: String, tenantId: String): Boolean = membership.isMember(userId, tenantId)

    /** Whether the caller already has a PERSONAL scope in this tenant (one per member, per tenant). */
    fun personalScopeExists(userId: String, tenantId: String): Boolean {
        val scopeIds = MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId)
            .list().map { it.scopeId }
        return scopeIds.isNotEmpty() &&
            ScopeEntity.count("id in ?1 and kind = ?2", scopeIds, ScopeEntity.PERSONAL) > 0
    }

    /** Birth a PERSONAL scope for the caller in this tenant + the self-membership (grant = opaque ciphertext). */
    @Transactional
    fun createPersonal(userId: String, tenantId: String, wrappedGrant: String): String {
        val now = Instant.now()
        val scopeId = UUID.randomUUID().toString()
        ScopeEntity().apply {
            id = scopeId
            this.tenantId = tenantId
            kind = ScopeEntity.PERSONAL
            createdAt = now
        }.persist()
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.OWNER
            this.wrappedGrant = wrappedGrant
            createdAt = now
        }.persist()
        return scopeId
    }

    /** Birth a GROUP scope for the caller in this tenant + the OWNER self-membership (en.spaces.group-scope,
     *  cr-0032). Mirrors [createPersonal] but stores the opaque `encryptedName` (the group name sealed under its
     *  derived name-key `K_name`, cr-0096) on the scope row, at name-epoch 0. Group names are not unique — no existence check. */
    @Transactional
    fun createGroup(userId: String, tenantId: String, encryptedName: String, wrappedGrant: String): String {
        val now = Instant.now()
        val scopeId = UUID.randomUUID().toString()
        ScopeEntity().apply {
            id = scopeId
            this.tenantId = tenantId
            kind = ScopeEntity.GROUP
            this.encryptedName = encryptedName
            createdAt = now
        }.persist()
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.OWNER
            this.wrappedGrant = wrappedGrant
            createdAt = now
        }.persist()
        return scopeId
    }

    /** Rename a GROUP (uc.spaces.rename-group, cr-0097): overwrite the scope's `encrypted_name` (re-sealed under
     *  `K_name` of the current epoch) + `name_epoch`. Name-only — no rotation, no re-grant. The caller (handler)
     *  has already gated owner + active + `nameEpoch == currentEpoch`; this just persists the two opaque values. */
    @Transactional
    fun renameGroup(scopeId: String, encryptedName: String, nameEpoch: Int) {
        ScopeEntity.findById(scopeId)?.let {
            it.encryptedName = encryptedName
            it.nameEpoch = nameEpoch
            it.persist()
        }
    }

    /** Set/replace a scope's icon (uc.spaces.set-space-icon): overwrite `sealed_icon` (re-sealed under `K_icon` of
     *  the current epoch) + `icon_epoch`. The caller (handler) has already gated owner + active + `iconEpoch ==
     *  currentEpoch`; this just persists the two opaque values (blind overwrite, the [renameGroup] shape). */
    @Transactional
    fun setIcon(scopeId: String, sealedIcon: String, iconEpoch: Int) {
        ScopeEntity.findById(scopeId)?.let {
            it.sealedIcon = sealedIcon
            it.iconEpoch = iconEpoch
            it.persist()
        }
    }

    /** Clear a scope's icon (uc.spaces.set-space-icon A5): reset `sealed_icon` + `icon_epoch` so every member falls
     *  back to the initial. **No epoch** — a removal carries no ciphertext that could be stale; idempotent (clearing
     *  an already-iconless scope is a no-op). Owner-gated by the caller (handler). */
    @Transactional
    fun clearIcon(scopeId: String) {
        ScopeEntity.findById(scopeId)?.let {
            it.sealedIcon = null
            it.iconEpoch = 0
            it.persist()
        }
    }

    /** The caller's scopes in this tenant, each with their own grant ciphertext (to open client-side). A
     *  grant-less ("pending") membership is skipped — the member can't open that scope yet, so it never
     *  surfaces as an openable scope (they see the Personal fallback until delivery lands, cr-0076). */
    fun listMine(userId: String, tenantId: String): List<ScopeRow> =
        MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().mapNotNull { m ->
            m.wrappedGrant?.let { grant ->
                ScopeEntity.findById(m.scopeId)?.let {
                    ScopeRow(it.id, it.kind, it.tenantId, m.role, it.currentEpoch, m.grantedEpoch, grant, it.encryptedName, it.nameEpoch, it.sealedIcon, it.iconEpoch, it.deletedAt)
                }
            }
        }

    /** Whether the caller holds a grant in this scope (is a key-holder there) — the member-directory gate. */
    fun holdsGrant(userId: String, scopeId: String): Boolean = membership.holdsGrant(userId, scopeId)

    /** The tenant a scope lives in (for the directory's per-tenant profile join + owner-email gating). */
    fun scopeTenantId(scopeId: String): String? = ScopeEntity.findById(scopeId)?.tenantId

    /** The kind of a scope (TENANT/GROUP/PERSONAL), or null if there's no such scope. */
    fun scopeKind(scopeId: String): String? = ScopeEntity.findById(scopeId)?.kind

    /** Whether the caller holds an OWNER membership on this scope (the group-admission gate, ADR 0014 §4). */
    fun isScopeOwner(userId: String, scopeId: String): Boolean = membership.isScopeOwner(userId, scopeId)

    /** Whether the user already holds any membership on this scope (grant or not) — the idempotency check. */
    fun isScopeMember(userId: String, scopeId: String): Boolean = membership.isScopeMember(userId, scopeId)

    /** Resolve a per-tenant username to a userId within a tenant (cr-0086) — the tenant-scoped resolve. */
    fun resolveUsernameInTenant(tenantId: String, username: String): String? =
        profiles.findUserIdByUsername(tenantId, username)

    // --- member management + rotation (cr-0088) ---

    /** The number of OWNER memberships on a scope — the last-owner guard (ADR 0007). */
    fun ownerCount(scopeId: String): Long = membership.ownerCount(scopeId)

    /** A member's role in a scope, or null if they hold no membership there. */
    fun memberRole(userId: String, scopeId: String): String? = membership.memberRole(userId, scopeId)

    /** Set a member's role (promote/demote). No-op if not a member. */
    @Transactional
    fun setRole(userId: String, scopeId: String, role: String) {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.let {
            it.role = role
            it.persist()
        }
    }

    /** Delete a member's membership (layer-1 removal — the instant access cut). */
    @Transactional
    fun removeMembership(userId: String, scopeId: String) {
        MembershipEntity.delete("userId = ?1 and scopeId = ?2", userId, scopeId)
    }

    /** The scope's current content-key epoch, or null if there's no such scope. */
    fun currentEpoch(scopeId: String): Int? = ScopeEntity.findById(scopeId)?.currentEpoch

    /** The epoch-chain links of a scope (ascending), each `epoch → wrappedPrev` (ADR 0024). */
    fun epochLinks(scopeId: String): List<Pair<Int, String>> =
        ScopeEpochEntity.find("scopeId", scopeId).list().sortedBy { it.epoch }.map { it.epoch to it.wrappedPrev }

    /** Commit a rotation (ADR 0024): bump the current epoch, store the chain-link, apply the re-grants (each to the
     *  new epoch). Client-minted; the server stores only ciphertext. Returns the new epoch.
     *
     *  Dumb by design (ADR 0045 §2): the decision — the next epoch, and that a `pendingConsent` proposal is never
     *  (re-)granted so `K_SC` stays gated before the wrap (cr-0094/cr-0095) — lives in the pure [RotationPolicy], where
     *  it is unit-tested and mutation-verified. This method only loads the directory, asks the policy, and writes what
     *  it returns; the `pendingConsent` skip is no longer an SQL predicate here. */
    @Transactional
    fun rotate(scopeId: String, wrappedPrev: String, grants: List<Pair<String, String>>): Int {
        val scope = ScopeEntity.findById(scopeId)!!
        val members = MembershipEntity.find("scopeId", scopeId).list().map { RotationMember(it.userId, it.pendingConsent) }
        val plan = RotationPolicy.plan(scope.currentEpoch, members, grants)
        scope.currentEpoch = plan.newEpoch
        scope.persist()
        ScopeEpochEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            epoch = plan.newEpoch
            this.wrappedPrev = wrappedPrev
            createdAt = Instant.now()
        }.persist()
        plan.grants.forEach { (uid, g) ->
            MembershipEntity.find("userId = ?1 and scopeId = ?2", uid, scopeId).firstResult()?.let {
                it.wrappedGrant = g
                it.grantedEpoch = plan.newEpoch
                it.persist()
            }
        }
        return plan.newEpoch
    }

    // --- group lifecycle: soft-delete + grace + purge (cr-0090) ---

    /** When a scope was soft-deleted (`deletedAt`), or null if active / no such scope. */
    fun deletedAt(scopeId: String): Instant? = ScopeEntity.findById(scopeId)?.deletedAt

    /** Soft-delete a group: set `deletedAt = now` (enters the grace period). */
    @Transactional
    fun softDelete(scopeId: String, now: Instant) {
        ScopeEntity.findById(scopeId)?.let { it.deletedAt = now; it.persist() }
    }

    /** Restore a soft-deleted group: clear `deletedAt` (active again). */
    @Transactional
    fun restore(scopeId: String) {
        ScopeEntity.findById(scopeId)?.let { it.deletedAt = null; it.persist() }
    }

    /** The ids of GROUP scopes whose grace period has elapsed (`deletedAt < cutoff`) — the purge worklist. */
    fun listPurgeable(cutoff: Instant): List<String> =
        ScopeEntity.find("deletedAt is not null and deletedAt < ?1", cutoff).list().map { it.id }

    // --- tenant-member removal cascade (cr-0091) ---

    /** One of a user's memberships within a tenant, tagged with its scope kind + the total member and owner counts
     *  on that scope — a row of the `removeTenantMember` worklist (cr-0091). The `ownerCount` is pre-resolved here so
     *  the removal decision's sole-owner / last-owner `<= 1` boundaries live in the pure [RemovalPolicy], not an SQL
     *  predicate — unit-tested and mutation-verified without a boot (ADR 0045 §2). */
    data class TenantMembershipRow(
        val scopeId: String,
        val kind: String,
        val role: String,
        val memberCount: Long,
        val ownerCount: Long,
    )

    /** Every scope the user is a member of in this tenant, each tagged with the scope kind + its member and owner
     *  counts — the input to the per-kind removal cascade (delete shared, orphan-transfer, soft-delete own, cr-0091). */
    fun membershipsInTenant(userId: String, tenantId: String): List<TenantMembershipRow> =
        MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().mapNotNull { m ->
            ScopeEntity.findById(m.scopeId)?.let { s ->
                TenantMembershipRow(
                    m.scopeId, s.kind, m.role,
                    MembershipEntity.count("scopeId", m.scopeId),
                    membership.ownerCount(m.scopeId),
                )
            }
        }

    /** The earliest-joined **other** member of a scope (min `createdAt`), or null if the user is alone — the
     *  orphan rule's deterministic successor owner (cr-0091, ADR 0014 §6). */
    fun oldestOtherMember(scopeId: String, exceptUserId: String): String? =
        MembershipEntity.find("scopeId = ?1 and userId <> ?2 order by createdAt asc", scopeId, exceptUserId)
            .firstResult()?.userId

    /** Admit a member to a scope **grant-less** (role=MEMBER, no grant) — grant-delivery wraps `K_SC` to them
     *  later (cr-0076). Operator-blind: **no key is written here**. */
    @Transactional
    fun addGrantlessMember(userId: String, tenantId: String, scopeId: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.MEMBER
            wrappedGrant = null
            createdAt = Instant.now()
        }.persist()
    }

    /** Whether a user's account-global "ask before adding me to groups" preference is on (cr-0094). */
    fun requiresGroupAddConsent(userId: String): Boolean =
        VeyqUser.findById(userId)?.requireGroupAddConsent ?: false

    /** A user's identity public key bundle (opaque base64), or null — the owner's device wraps the group name-key to it. */
    fun identityPublicKeyOf(userId: String): String? = VeyqUser.findById(userId)?.identityPublicKey

    /** Admit a member as a **soft consent proposal** (cr-0096): a grant-less, `pendingConsent` MEMBER membership
     *  carrying the wrapped group name-key `K_name` (`sealedNameKey`) + who proposed. grant-delivery SKIPS it — no
     *  `K_SC` wrap — until the invitee accepts. Operator-blind: only ciphertext + operational metadata is written. */
    @Transactional
    fun addSoftPendingMember(userId: String, tenantId: String, scopeId: String, sealedNameKey: String, addedBy: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.MEMBER
            wrappedGrant = null
            pendingConsent = true
            this.sealedNameKey = sealedNameKey
            this.addedBy = addedBy
            createdAt = Instant.now()
        }.persist()
    }

    /** The caller's membership state on a scope (cr-0094): a live pending proposal, an ordinary member, or none. */
    fun consentState(userId: String, scopeId: String): ConsentState =
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()
            ?.let { if (it.pendingConsent) ConsentState.PENDING else ConsentState.ACTIVE } ?: ConsentState.NONE

    /** Accept a proposal (cr-0096): clear `pendingConsent` (and the now-moot wrapped name-key / addedBy) on the
     *  caller's own soft membership, so ordinary grant-delivery picks it up. Returns rows updated (0 if none was pending). */
    @Transactional
    fun clearPendingConsent(userId: String, scopeId: String): Int =
        MembershipEntity.update(
            "pendingConsent = false, sealedNameKey = null, addedBy = null " +
                "where userId = ?1 and scopeId = ?2 and pendingConsent = true",
            userId, scopeId,
        )

    /** Decline / rescind a proposal (cr-0094): delete the caller's soft membership. Guarded to `pendingConsent`
     *  rows only — never deletes a real (accepted) membership. Nothing was ever wrapped, so no rotation. */
    @Transactional
    fun deletePendingMembership(userId: String, scopeId: String): Int =
        MembershipEntity.delete("userId = ?1 and scopeId = ?2 and pendingConsent = true", userId, scopeId).toInt()

    /** The caller's pending group invitations in a tenant (cr-0096): each with the wrapped name-key `K_name`, the
     *  scope's canonical `encryptedName` the key opens, and the proposer's per-tenant username. Skips rows missing a
     *  wrapped name-key or a scope name (never happens for a proposal, defensive). */
    fun listInvitations(userId: String, tenantId: String): List<GroupInvitationRow> {
        val profileByUser = profiles.byUserInTenant(tenantId)
        return MembershipEntity.find("userId = ?1 and tenantId = ?2 and pendingConsent = true", userId, tenantId).list()
            .mapNotNull { m ->
                val sealedNameKey = m.sealedNameKey ?: return@mapNotNull null
                val encryptedName = ScopeEntity.findById(m.scopeId)?.encryptedName ?: return@mapNotNull null
                GroupInvitationRow(m.scopeId, m.addedBy?.let { profileByUser[it]?.username } ?: "", sealedNameKey, encryptedName)
            }
    }

    /** The directory row for one member of a scope (the add response). `email` is included; the handler nulls it. */
    fun scopeMemberRow(scopeId: String, userId: String): ScopeMemberRow? {
        val tenantId = ScopeEntity.findById(scopeId)?.tenantId ?: return null
        val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult() ?: return null
        val u = VeyqUser.findById(userId) ?: return null
        val p = profiles.byUserInTenant(tenantId)[userId]
        return ScopeMemberRow(userId, p?.username ?: "", p?.sealedName, u.email, m.role, u.identityPublicKey, m.wrappedGrant != null, m.pendingConsent)
    }

    /** Whether the user is the tenant's OWNER (gates the owner-only email in the directory). */
    /** A tenant owner holds an OWNER membership on the tenant **root scope** (cr-0163), not merely any OWNER row
     *  carrying this `tenantId` — gates the owner-only member email (ADR 0023). Delegates to the single home. */
    fun isTenantOwner(userId: String, tenantId: String): Boolean = membership.isTenantOwner(userId, tenantId)

    /** Every member of a scope: identity (per-tenant username + sealed name), pubkey, grant-present, email. The
     *  email is included on every row here; the handler nulls it for non-owner callers (ADR 0023). */
    fun listMembers(scopeId: String): List<ScopeMemberRow> {
        val tenantId = ScopeEntity.findById(scopeId)?.tenantId ?: return emptyList()
        val profileByUser = profiles.byUserInTenant(tenantId)
        return MembershipEntity.find("scopeId", scopeId).list().mapNotNull { m ->
            VeyqUser.findById(m.userId)?.let { u ->
                val p = profileByUser[m.userId]
                ScopeMemberRow(
                    m.userId, p?.username ?: "", p?.sealedName, u.email, m.role,
                    u.identityPublicKey, m.wrappedGrant != null, m.pendingConsent,
                )
            }
        }
    }

    /** Every tenant the caller belongs to (one row per tenant, name+domain joined) — the space switcher. */
    fun myTenants(userId: String): List<TenantRow> =
        MembershipEntity.find("userId", userId).list()
            .groupBy { it.tenantId }
            .mapNotNull { (tenantId, memberships) ->
                // The tenant role keys on the ROOT scope (scopeId == tenantId), mirroring isTenantOwner
                // (MembershipStore, cr-0163/ADR 0007) — NOT `.first().role`, which over an unordered find could
                // return the OWNER row every scope birth mints its creator (the member's own personal scope
                // carries this tenantId), reporting OWNER for a plain member. Belonging is by tenantId across all
                // the user's scopes, so fall back to MEMBER rather than drop a tenant with no root row. #93.
                // The same root membership carries the caller's own `wrappedGrant`, and the root scope (id ==
                // tenantId, cr-0010) carries the tenant's `sealedIcon`/`iconEpoch` — the switcher opens the icon
                // against that grant without a per-tenant scopes call (uc.spaces.set-space-icon, en.spaces.tenant-scope).
                val root = memberships.firstOrNull { it.scopeId == tenantId }
                val role = root?.role ?: MembershipEntity.MEMBER
                val rootScope = ScopeEntity.findById(tenantId)
                TenantEntity.findById(tenantId)?.let { t ->
                    TenantRow(t.id, t.name, t.domain, role, root?.wrappedGrant, rootScope?.sealedIcon, rootScope?.iconEpoch ?: 0)
                }
            }
            .sortedBy { it.name }
}
