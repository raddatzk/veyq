package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.tasks.CompromiseRotationPolicy
import me.raddatz.veyq.core.tasks.CompromiseRotationState
import me.raddatz.veyq.core.tasks.TasksStore
import me.raddatz.veyq.core.scope.MembershipEraser
import me.raddatz.veyq.core.membership.Admission
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.membership.admission
import me.raddatz.veyq.core.membership.admit
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.MembershipStore
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.arc.All
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
    /** Who posted the caller's grant, their signature over it and their current identity public key (ADR 0028
     *  Decision 6) — the recipient verifies before it opens. Null for a grant written before grants were signed. */
    val granter: GranterRow?,
    /** Owner-only: whether a compromise rotation queued on this scope needs an owner (`STALLED`/`FAILED`), or null. */
    val compromiseRotation: CompromiseRotationState? = null,
)

/** The granter of one grant as a read serves it: its id, its signature over the grant, and its current identity
 *  public key (so the recipient needs no directory call). Opaque to the server, which verifies none of it. */
data class GranterRow(val userId: String, val signature: String, val identityPublicKey: String)

/** A tenant the caller belongs to — the row behind a `MyTenant` (the space switcher). Carries the caller's own
 *  root-scope `wrappedGrant` + the tenant's `sealedIcon`/`iconEpoch` (uc.spaces.set-space-icon) so the switcher
 *  renders each tenant's avatar without a per-tenant scopes call (en.spaces.tenant-scope). `wrappedGrant` is null
 *  while the caller is grant-less (the transient awaiting-access window). */
data class TenantRow(
    val tenantId: String,
    val name: String,
    val domain: String,
    val role: String,
    val status: String,
    val wrappedGrant: String?,
    val sealedIcon: String?,
    val iconEpoch: Int,
    /** The epoch `wrappedGrant` holds (its seal is bound to it, ADR 0072); null whenever the grant is. */
    val grantedEpoch: Int?,
    /** The grant's granter (ADR 0028 Decision 6); null whenever the grant is, or when it was never signed. */
    val granter: GranterRow?,
    /** Owner-only: whether a compromise rotation queued on the tenant root needs an owner, or null. */
    val compromiseRotation: CompromiseRotationState? = null,
)

/** A member of a scope — the row behind a `ScopeMember` (the member directory, cr-0077). `hasGrant=false` is a
 *  pending member awaiting delivery. `identityPublicKey` is a key-holder's `asymWrap` target — never a key.
 *  `username` + `sealedName` come from the member's per-tenant profile (ADR 0023); `email` is owner-only — the
 *  handler nulls it for non-owner callers. */
data class ScopeMemberRow(
    val userId: String,
    val username: String,
    val sealedName: String?,
    /** The picture's revision (`0` = none); the bytes never ride the directory (ADR 0084). */
    val avatarRevision: Int,
    val email: String?,
    val role: String,
    val identityPublicKey: String,
    val hasGrant: Boolean,
    /** A soft consent proposal awaiting the target's Accept (cr-0094) — owner-visible only in the directory. */
    val pendingConsent: Boolean = false,
    /** The membership's admission (ADR 0020 move 6) — served so a rotation can verify it before carrying it. */
    val admission: Admission? = null,
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
data class GroupInvitationRow(
    val scopeId: String, val addedByUsername: String, val sealedNameKey: String, val encryptedName: String,
    /** The name epoch both seals are bound to (ADR 0072) — the invitee opens them at exactly this epoch. */
    val nameEpoch: Int,
)

/** The caller's membership state on a scope (cr-0094) — drives idempotent accept/decline + 404. */
enum class ConsentState { PENDING, ACTIVE, NONE }

/**
 * Persistence for a member's scopes (cr-0026). Operator-blind: stores only the opaque scope row + the
 * ciphertext grant on the membership; never a scope key. The active tenant arrives as an id (`RequestTenant`).
 */
@ApplicationScoped
class ScopesStore(
    private val profiles: TenantProfileStore,
    private val membership: MembershipStore,
    private val tenants: TenantStore,
    private val shares: SharingStore,
    private val tasks: TasksStore,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge). What goes with one member's access
    // to one scope, owned by the package whose rows they are (today item favorites, ADR 0068).
    @All private val membershipErasers: MutableList<MembershipEraser>,
) {

    /** Whether [userId] belongs to the tenant — an accepted root membership (a group-add candidate's check). */
    fun belongsToTenant(userId: String, tenantId: String): Boolean = membership.belongsToTenant(userId, tenantId)

    /** Whether the caller already has a PERSONAL scope in this tenant (one per member, per tenant). It reads the
     *  unique key itself (`ux_scope_personal_owner`), so it is the replay question for a create that lost on that
     *  key: every collision there is answered `personal_scope_exists`. */
    fun personalScopeExists(userId: String, tenantId: String): Boolean =
        ScopeEntity.count("tenantId = ?1 and personalOwner = ?2", tenantId, userId) > 0

    /** Does any scope, of any kind and tenant, already hold the client-minted [scopeId]? */
    fun scopeIdTaken(scopeId: String): Boolean = ScopeEntity.findById(scopeId) != null

    /** Birth a PERSONAL scope for the caller in this tenant + the self-membership (grant = opaque ciphertext). */
    @Transactional
    fun createPersonal(scopeId: String, userId: String, tenantId: String, grant: SignedGrant): Boolean {
        if (scopeIdTaken(scopeId)) return false // the client-minted id is taken → nothing written
        val now = Instant.now()
        ScopeEntity().apply {
            id = scopeId
            this.tenantId = tenantId
            kind = ScopeEntity.PERSONAL
            personalOwner = userId // the unique key a second personal create of this member collides on
        }.persist()
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.OWNER
            wrappedGrant = grant.wrappedGrant
            granterUserId = userId
            granterIdentityPublicKey = identityKeyOf(userId)
            grantSignature = grant.signature
            createdAt = now
        }.persist()
        return true
    }

    /** Birth a GROUP scope for the caller in this tenant + the OWNER self-membership (en.spaces.group-scope,
     *  cr-0032). Mirrors [createPersonal] but stores the opaque `encryptedName` (the group name sealed under its
     *  derived name-key `K_name`, cr-0096) on the scope row, at name-epoch 0. Group names are not unique — no existence check.
     *  The creator's own membership carries the admission it self-tagged under `E_0` (ADR 0020 move 6), so the first
     *  rotation has a tag to carry forward. */
    @Transactional
    fun createGroup(scopeId: String, userId: String, tenantId: String, encryptedName: String, grant: SignedGrant, admission: Admission): Boolean {
        if (scopeIdTaken(scopeId)) return false // the client-minted id is taken → nothing written
        val now = Instant.now()
        ScopeEntity().apply {
            id = scopeId
            this.tenantId = tenantId
            kind = ScopeEntity.GROUP
            this.encryptedName = encryptedName
        }.persist()
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.OWNER
            wrappedGrant = grant.wrappedGrant
            granterUserId = userId
            granterIdentityPublicKey = identityKeyOf(userId)
            grantSignature = grant.signature
            admit(admission)
            createdAt = now
        }.persist()
        return true
    }

    /** Rename a GROUP (uc.spaces.rename-group, cr-0097): overwrite the scope's `encrypted_name` (re-sealed under
     *  `K_name` of the current epoch) + `name_epoch`. Name-only — no rotation, no re-grant. The caller (handler)
     *  has already gated owner + active. Returns false, nothing written, when [nameEpoch] is not the scope's current
     *  epoch.
     *
     *  The epoch gate is this update's own WHERE, not an earlier read: a rotation holds the scope row's lock while it
     *  advances the epoch ([rotate]), so this update waits for it and then matches the epoch it committed. Checked in
     *  a read before the write, a rotation committed in between and a name sealed under the pre-rotation epoch landed
     *  — readable by the member that rotation removed (ADR 0024). */
    @Transactional
    fun renameGroup(scopeId: String, encryptedName: String, nameEpoch: Int): Boolean =
        ScopeEntity.update(
            "encryptedName = ?1, nameEpoch = ?2 where id = ?3 and currentEpoch = ?2", encryptedName, nameEpoch, scopeId,
        ) == 1

    /** Set/replace a scope's icon (uc.spaces.set-space-icon): overwrite `sealed_icon` (re-sealed under `K_icon` of
     *  the current epoch) + `icon_epoch`. The caller (handler) has already gated owner + active. Returns false,
     *  nothing written, when [iconEpoch] is not the scope's current epoch — the [renameGroup] gate, for the same
     *  reason. */
    @Transactional
    fun setIcon(scopeId: String, sealedIcon: String, iconEpoch: Int): Boolean =
        ScopeEntity.update(
            "sealedIcon = ?1, iconEpoch = ?2 where id = ?3 and currentEpoch = ?2", sealedIcon, iconEpoch, scopeId,
        ) == 1

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
    fun listMine(userId: String, tenantId: String): List<ScopeRow> {
        val memberships = MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list()
        val stuck = stuckCompromiseRotations(memberships)
        return memberships.mapNotNull { m ->
            m.wrappedGrant?.let { grant ->
                ScopeEntity.findById(m.scopeId)?.let {
                    ScopeRow(
                        it.id, it.kind, it.tenantId, m.role, it.currentEpoch, m.grantedEpoch, grant, it.encryptedName,
                        it.nameEpoch, it.sealedIcon, it.iconEpoch, it.deletedAt, granterOf(m), stuck[it.id],
                    )
                }
            }
        }
    }

    /**
     * For the scopes among [memberships] the caller **owns**, what a compromise rotation queued there needs from an
     * owner (uc.spaces.complete-queued-rotation E2/E8): `FAILED` if any rotator's failed, else `STALLED`, else nothing.
     * A plain member's row never carries it — only owners can promote a co-owner or re-queue. One query over the
     * caller's owned scopes finds the exclusions; the state is derived only for those.
     */
    private fun stuckCompromiseRotations(memberships: List<MembershipEntity>): Map<String, CompromiseRotationState> {
        val owned = memberships.filter { it.role == MembershipEntity.OWNER && it.wrappedGrant != null }.map { it.scopeId }
        return tasks.compromiseRotations(owned).groupBy({ it.scopeId }, { it.state })
            .mapNotNull { (scopeId, states) -> CompromiseRotationPolicy.ownerView(states)?.let { scopeId to it } }.toMap()
    }

    /** The granter of a membership's grant — id, signature, and the identity public key the grant row recorded when
     *  it was written (the key that signed it, [MembershipEntity.granterIdentityPublicKey]) — or null when the grant
     *  carries no signature (written before grants were signed) or no recorded key. Never the granter's CURRENT key:
     *  after an account rotation that is a key the signature was not made with, and the recipient, which verifies
     *  before it reads its pin, would refuse every grant the rotator signed (ADR 0028 § What is still open). */
    private fun granterOf(m: MembershipEntity): GranterRow? {
        val granterId = m.granterUserId ?: return null
        val signature = m.grantSignature ?: return null
        val key = m.granterIdentityPublicKey ?: return null
        return GranterRow(granterId, signature, key)
    }

    /** The granter's identity public bundle as it is now — what a grant written in this transaction records. */
    private fun identityKeyOf(granterId: String): String? = VeyqUser.findById(granterId)?.identityPublicKey

    /** Whether the caller holds a grant in this scope (is a key-holder there) — the member-directory gate. */
    fun holdsGrant(userId: String, scopeId: String): Boolean = membership.holdsGrant(userId, scopeId)

    /** The tenant a scope lives in (for the directory's per-tenant profile join + owner-email gating). */
    fun scopeTenantId(scopeId: String): String? = ScopeEntity.findById(scopeId)?.tenantId

    /** The kind of a scope (TENANT/GROUP/PERSONAL), or null if there's no such scope. */
    fun scopeKind(scopeId: String): String? = ScopeEntity.findById(scopeId)?.kind

    /** Whether the caller holds an OWNER membership on this scope (the group-admission gate, ADR 0014 §4). */
    fun isScopeOwner(userId: String, scopeId: String): Boolean = membership.isScopeOwner(userId, scopeId)

    /** Whether the user already holds any row on this scope, an open proposal included — the add's idempotency. */
    fun holdsAnyRow(userId: String, scopeId: String): Boolean = membership.holdsAnyRow(userId, scopeId)

    /** Resolve a per-tenant username to a userId within a tenant (cr-0086) — the tenant-scoped resolve. */
    fun resolveUsernameInTenant(tenantId: String, username: String): String? =
        profiles.findUserIdByUsername(tenantId, username)

    // --- member management + rotation (cr-0088) ---

    /** What [changeRole] did: the change landed, or which of its checks refused it. */
    enum class RoleChange { CHANGED, NOT_OWNER, MEMBER_NOT_FOUND, LAST_OWNER }

    /**
     * An owner sets [targetId]'s role on a GROUP (uc.spaces.manage-group-members). The checks run under the scope
     * row's lock, which is this transaction's first statement: the caller is an OWNER, the target holds a row, and
     * the change leaves an OWNER ([MembershipPolicy]). Each removal-rotation holds the same lock. Checked before
     * the lock, two co-owners demoting each other both counted two owners, and the group was left with none
     * (developer-styleguide §12). The checks read committed data under the lock on both engines (ADR 0078).
     */
    @Transactional
    fun changeRole(callerId: String, targetId: String, scopeId: String, role: String): RoleChange {
        ScopeEntity.lockRow(scopeId)
        if (!membership.isScopeOwner(callerId, scopeId)) return RoleChange.NOT_OWNER
        val current = membership.memberRole(targetId, scopeId) ?: return RoleChange.MEMBER_NOT_FOUND
        if (MembershipPolicy.isLastOwnerDemote(current, role, membership.ownerCount(scopeId))) return RoleChange.LAST_OWNER
        setRole(targetId, scopeId, role)
        return RoleChange.CHANGED
    }

    /** Set a member's role (promote/demote). No-op if not a member. Decides nothing: an owner's change goes through
     *  [changeRole]; the tenant removal's orphan promotion calls this under the scope locks it already holds. */
    @Transactional
    fun setRole(userId: String, scopeId: String, role: String) {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.let {
            it.role = role
            it.persist()
        }
    }

    /**
     * Delete a member's membership (layer-1 removal — the instant access cut). The tenant-removal cascade's path,
     * which queues the rotation it cannot perform; a group owner's removal goes through [rotate] instead.
     *
     * Their per-member rows of this scope go with it, through every [MembershipEraser] (today their item favorites,
     * ADR 0068) — as they do in a removal-rotation.
     */
    @Transactional
    fun removeMembership(userId: String, scopeId: String) {
        MembershipEntity.delete("userId = ?1 and scopeId = ?2", userId, scopeId)
        eraseMembership(userId, scopeId)
    }

    private fun eraseMembership(userId: String, scopeId: String) =
        membershipErasers.forEach { it.eraseMembership(userId, scopeId) }

    /**
     * Count one removal of an accepted member (ADR 0075 point 4) — called in the transaction of **every** path that
     * removes one: the group's removal-rotation ([rotate]) and the tenant cascade, for each scope it cuts. A share
     * minted before it is then one the removed member could have rebuilt from its recovery copy, so it comes back only
     * by re-issue. A pending proposal's delete is no removal: it was never keyed and holds no grant to open a copy.
     * One atomic `+ 1` in the database, never a read-modify-write of a loaded row: the caller may already hold that row
     * in its persistence context (the gate read it; [rotate] locked it), and a stale in-memory count plus one would
     * lose a removal racing this one. The loaded row is not refreshed — `ScopeEntity` is `@DynamicUpdate`, so its
     * later flush writes only the columns its transaction changed and never this count back.
     */
    @Transactional
    fun recordRemoval(scopeId: String) {
        ScopeEntity.update("memberRemovals = memberRemovals + 1 where id = ?1", scopeId)
    }

    /** The scope row, or null — read-only: its epoch and removal count for the sharing feature's re-issue rule. */
    fun find(scopeId: String): ScopeEntity? = ScopeEntity.findById(scopeId)

    /** Turn off and mark every share of the scope (ADR 0075 point 5) — a scope owner's standalone switch-off. */
    fun turnOffAllShares(scopeId: String): Int = shares.turnOffAllInScope(scopeId)

    /** The scope's current content-key epoch, or null if there's no such scope. */
    fun currentEpoch(scopeId: String): Int? = ScopeEntity.findById(scopeId)?.currentEpoch

    /** The epoch-chain links of a scope (ascending), each `epoch → wrappedPrev` (ADR 0024). */
    fun epochLinks(scopeId: String): List<Pair<Int, String>> =
        ScopeEpochEntity.find("scopeId", scopeId).list().sortedBy { it.epoch }.map { it.epoch to it.wrappedPrev }

    /** Commit a rotation (ADR 0024 §4): if [epoch] is the scope's next one and the rotation re-tags or drops every
     *  open admission of the scope, advance to it, store the chain-link, apply the re-grants (each to the new epoch,
     *  signed by [granterId]) and the re-tags; otherwise store nothing. Client-minted; the server stores only
     *  ciphertext and tags it cannot check.
     *
     *  Dumb by design (ADR 0045 §2): the decision — only the next epoch commits, a `pendingConsent` proposal is never
     *  (re-)granted so `K_SC` stays gated before the wrap (cr-0094/cr-0095), and no open admission may be left behind
     *  (ADR 0020 move 6) — lives in the pure [RotationPolicy], where it is unit-tested and mutation-verified. This
     *  method only locks the row, loads the directory and the open admissions, asks the policy, and writes what it
     *  returns.
     *
     *  The scope row is read under `PESSIMISTIC_WRITE`, so two in-flight commits stating the same epoch serialize:
     *  the loser reads the winner's epoch and is a clean stale. Without the lock both read the old epoch, both pass
     *  the policy, and the loser dies on `ux_scope_epoch` — the chain still holds, but as an error the client does
     *  not retry. An admission write takes the same lock ([addGrantlessMember], the tenant's member invite), so no
     *  admission can be tagged at an epoch a concurrent rotation is leaving.
     *
     *  The same lock is what makes the two facts about the caller exact (ADR 0060 Decision 4): every scope-rotation
     *  origination takes it too, so an account rotation and the rotator's own commit on one scope serialise, and
     *  whichever commits second sees the other. A commit that lands marks every rotation job of the scope leased to
     *  the committer ([TasksStore.markRotated]) — the only thing that lets such a job complete. */
    @Transactional
    fun rotate(
        scopeId: String, epoch: Int, wrappedPrev: String, granterId: String,
        grants: List<RotationGrant>, retags: List<Pair<String, String>>, dropped: Set<String>, removes: String? = null,
        revokeLinks: Boolean = false,
    ): RotationDecision {
        val scope = ScopeEntity.lockRow(scopeId)!!
        val all = MembershipEntity.find("scopeId", scopeId).list()
        val invites = if (scope.kind == ScopeEntity.TENANT) tenants.openMemberInvites(scope.tenantId) else emptyList()
        val open = all.mapNotNull { it.admissionId }.toSet() + invites.mapNotNull { it.admissionId }
        val caller = all.firstOrNull { it.userId == granterId }
        val decision = RotationPolicy.plan(
            scope.currentEpoch, epoch, all.map { RotationMember(it.userId, it.pendingConsent, it.role, it.admissionId) },
            open, grants, retags, dropped, removes,
            callerExcluded = tasks.isRotationExcluded(scopeId, granterId),
            callerGrantedEpoch = caller?.wrappedGrant?.let { caller.grantedEpoch },
        )
        val plan = (decision as? RotationDecision.Commit)?.plan ?: return decision
        // A removal-rotation deletes the membership in this same transaction (ADR 0024 Decision 4): if anything below
        // fails, the member is still in place — a removal never ends without its rotation. An entity delete, not a
        // bulk one, so the loops below never re-persist the row it took. The transaction starts HERE, not in the
        // handler: a caller transaction that had already read the scope row would make the lock above hand back that
        // stale copy, and two concurrent commits would both pass the policy (twoConcurrentRotations… bites that).
        val rows = plan.removes?.let { removed ->
            all.filter { it.userId == removed }.forEach { it.delete() }
            eraseMembership(removed, scopeId)
            shares.deactivateSharesByOwnerInScope(removed, scopeId) // cr-0104: their public links 404 from now on
            recordRemoval(scopeId) // ADR 0075: every link minted before now is one the removed member could rebuild
            // ADR 0075: and, opted into, every other link of the group — any of them the removed member could copy
            if (revokeLinks) shares.turnOffAllInScope(scopeId)
            all.filter { it.userId != removed }
        } ?: all
        scope.currentEpoch = plan.newEpoch
        scope.persist()
        val granterKey = identityKeyOf(granterId)
        ScopeEpochEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            this.epoch = plan.newEpoch
            this.wrappedPrev = wrappedPrev
        }.persist()
        plan.grants.forEach { g ->
            rows.firstOrNull { it.userId == g.userId }?.let {
                it.wrappedGrant = g.grant.wrappedGrant
                it.grantedEpoch = plan.newEpoch
                it.granterUserId = granterId
                it.granterIdentityPublicKey = granterKey
                it.grantSignature = g.grant.signature
                it.persist()
            }
        }
        rows.forEach { m ->
            val id = m.admissionId ?: return@forEach
            if (id in plan.dropped) m.admit(null) else plan.retags[id]?.let { m.admit(Admission(id, plan.newEpoch, it)) }
            m.persist()
        }
        // A re-tagged admission is a new one to judge: the `admission_unverified` declines its old tag earned go with it.
        tasks.liftAdmissionDeclines(rows.filter { it.admissionId != null && it.admissionId in plan.retags }.map { it.id })
        tenants.carryInvites(invites, plan.newEpoch, plan.retags, plan.dropped)
        tasks.markRotated(scopeId, granterId, plan.newEpoch)
        return decision
    }

    /** Take the scope row's lock for the caller's transaction — what a tenant removal's cut holds before it deletes,
     *  so its rotation origination serialises with every other one of the scope (lock order scope → membership →
     *  job). */
    @Transactional
    fun lockScope(scopeId: String) {
        ScopeEntity.lockRow(scopeId)
    }

    // --- group lifecycle: soft-delete + grace + purge (cr-0090) ---


    /** Soft-delete a group: set `deletedAt = now` (enters the grace period). */
    @Transactional
    fun softDelete(scopeId: String, now: Instant) {
        ScopeEntity.findById(scopeId)?.let { it.deletedAt = now; it.persist() }
    }

    enum class Restore { RESTORED, NOT_DELETED, GONE }

    /** Restore a soft-deleted group: clear `deletedAt` (active again), decided under the scope row's lock, which the
     *  purge's erase takes first (`ScopePurge`): a restore waits out an erase in flight and then finds the group
     *  [Restore.GONE], and an erase that comes after finds it restored. */
    @Transactional
    fun restore(scopeId: String): Restore {
        val scope = ScopeEntity.lockRow(scopeId) ?: return Restore.GONE
        if (scope.deletedAt == null) return Restore.NOT_DELETED
        scope.deletedAt = null
        scope.persist()
        return Restore.RESTORED
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

    /** The ids of every scope the user holds a row on in this tenant — the locks a tenant removal takes before it
     *  plans ([membershipsInTenant] is the plan's read, made under them). */
    fun scopeIdsInTenant(userId: String, tenantId: String): List<String> =
        MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().map { it.scopeId }

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
        MembershipEntity.find("scopeId = ?1 and userId <> ?2 order by createdAt asc, id", scopeId, exceptUserId)
            .firstResult()?.userId

    /** Admit a member to a scope **grant-less** (role=MEMBER, no grant) — grant-delivery wraps `K_SC` to them
     *  later (cr-0076), once their [admission] verifies (ADR 0020 move 6). Operator-blind: **no key is written here**.
     *  False — nothing written — when the admission was tagged at an epoch that is no longer the scope's current one:
     *  read under the lock a rotation takes, so an admission can never land behind a rotation that did not carry it. */
    @Transactional
    fun addGrantlessMember(userId: String, tenantId: String, scopeId: String, admission: Admission): Boolean {
        if (!admissionIsCurrent(scopeId, admission)) return false
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.MEMBER
            wrappedGrant = null
            admit(admission)
            createdAt = Instant.now()
        }.persist()
        return true
    }

    /** Whether [admission] is stated at the scope's current epoch, read under the rotation's lock. */
    private fun admissionIsCurrent(scopeId: String, admission: Admission): Boolean =
        ScopeEntity.lockRow(scopeId)?.currentEpoch == admission.epoch

    /** Whether a user's account-global "ask before adding me to groups" preference is on (cr-0094). */
    fun requiresGroupAddConsent(userId: String): Boolean =
        VeyqUser.findById(userId)?.requireGroupAddConsent ?: false

    /** A user's identity public key bundle (opaque base64), or null — the owner's device wraps the group name-key to it. */
    fun identityPublicKeyOf(userId: String): String? = VeyqUser.findById(userId)?.identityPublicKey

    /** Admit a member as a **soft consent proposal** (cr-0096): a grant-less, `pendingConsent` MEMBER membership
     *  carrying the wrapped group name-key `K_name` (`sealedNameKey`) + who proposed. grant-delivery SKIPS it — no
     *  `K_SC` wrap — until the invitee accepts. Operator-blind: only ciphertext + operational metadata is written. */
    @Transactional
    fun addSoftPendingMember(
        userId: String, tenantId: String, scopeId: String, sealedNameKey: String, addedBy: String, admission: Admission,
    ): Boolean {
        if (!admissionIsCurrent(scopeId, admission)) return false
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
            admit(admission)
            createdAt = Instant.now()
        }.persist()
        return true
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
                val scope = ScopeEntity.findById(m.scopeId) ?: return@mapNotNull null
                val encryptedName = scope.encryptedName ?: return@mapNotNull null
                GroupInvitationRow(
                    m.scopeId, m.addedBy?.let { profileByUser[it]?.username } ?: "", sealedNameKey, encryptedName, scope.nameEpoch,
                )
            }
    }

    /** The directory row for one member of a scope (the add response). `email` is included; the handler nulls it. */
    fun scopeMemberRow(scopeId: String, userId: String): ScopeMemberRow? {
        val tenantId = ScopeEntity.findById(scopeId)?.tenantId ?: return null
        val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult() ?: return null
        val u = VeyqUser.findById(userId) ?: return null
        val p = profiles.byUserInTenant(tenantId)[userId]
        return ScopeMemberRow(
            userId, p?.username ?: "", p?.sealedName, p?.avatarRevision ?: 0, u.email, m.role, u.identityPublicKey, m.wrappedGrant != null,
            m.pendingConsent, m.admission(),
        )
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
                    m.userId, p?.username ?: "", p?.sealedName, p?.avatarRevision ?: 0, u.email, m.role,
                    u.identityPublicKey, m.wrappedGrant != null, m.pendingConsent, m.admission(),
                )
            }
        }
    }

    /**
     * Every tenant the caller holds an **accepted** membership in (one row per tenant, name+domain joined) — the
     * space switcher. Not only the root row: a member removed from a tenant keeps their own spaces readable for the
     * grace window, and the switcher finds a tenant only through this list (uc.spaces.remove-tenant-member). A
     * SUSPENDED tenant stays listed with its status but withholds both the grant and the icon: suspension refuses
     * its members every read, this one included (en.spaces.tenant-scope E1a).
     */
    fun myTenants(userId: String): List<TenantRow> {
        val accepted = MembershipEntity.find("userId = ?1 and pendingConsent = false", userId).list()
        val stuck = stuckCompromiseRotations(accepted.filter { it.scopeId == it.tenantId })
        return accepted
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
                    val open = t.status != TenantEntity.SUSPENDED
                    val grant = root?.wrappedGrant?.takeIf { open }
                    TenantRow(
                        t.id, t.name, t.domain, role, t.status,
                        grant, rootScope?.sealedIcon?.takeIf { open },
                        rootScope?.iconEpoch ?: 0,
                        root?.grantedEpoch?.takeIf { grant != null },
                        root?.takeIf { grant != null }?.let { granterOf(it) },
                        stuck[tenantId]?.takeIf { open },
                    )
                }
            }
            .sortedBy { it.name }
    }
}
