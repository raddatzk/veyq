package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.account.rotation.RotationRegrantEntity
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.identity.keychain.KeyChainStore
import me.raddatz.veyq.core.admin.tenant.TenantStore
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.tasks.CompromiseRotationState
import me.raddatz.veyq.core.tasks.TasksStore
import me.raddatz.veyq.core.scope.MembershipEraser
import me.raddatz.veyq.core.membership.SignedGrant
import me.raddatz.veyq.core.admin.tenant.InviteRegrant
import me.raddatz.veyq.core.admin.tenant.OpenInviteRow
import me.raddatz.veyq.core.crypto.InviteAcceptProof
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.MembershipStore
import me.raddatz.veyq.core.node.rekey.ScopeRekeying
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
    /** The scope's re-key is unfinished — a node lags its current epoch (ADR 0109, derived). Served to every member, whose
     *  device continues it (ADR 0111 decision 11). */
    val rekeying: Boolean,
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

/** A member of a scope — the row behind a `ScopeMember` (the member directory, cr-0077). Every membership is born
 *  with a grant (ADR 0111). `identityPublicKey` is a key-holder's `asymWrap` target — never a key.
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
    /** A soft consent proposal awaiting the target's Accept (cr-0094) — owner-visible only in the directory. */
    val pendingConsent: Boolean = false,
    /** The member's join record in a TENANT scope (ADR 0111 decisions 3 and 12), sealed; null elsewhere. */
    val sealedJoinRecord: String? = null,
    /** The inviting owner's endorsement of that record (ADR 0111 decision 21); null elsewhere. */
    val joinEndorsement: String? = null,
    /** The head of the member's key chain (ADR 0111 decision 7). */
    val keyChainHead: String,
    /** The member's grant is behind the scope's current epoch: a rotation declared them pending (ADR 0111 decision 9). */
    val pending: Boolean,
)

/** The result of resolving a per-tenant username to a group-add candidate (cr-0094) — records nothing. */
data class ResolvedCandidateRow(
    val userId: String,
    val username: String,
    val identityPublicKey: String,
    val requiresConsent: Boolean,
    /** The head of the candidate's key chain (ADR 0111 decision 7). */
    val keyChainHead: String,
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
    private val keyChain: KeyChainStore,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge). What goes with one member's access
    // to one scope, owned by the package whose rows they are (today item favorites, ADR 0068).
    @param:All private val membershipErasers: MutableList<MembershipEraser>,
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
     *  derived name-key `K_name`, cr-0096) on the scope row, at name-epoch 0. Group names are not unique — no existence check. */
    @Transactional
    fun createGroup(scopeId: String, userId: String, tenantId: String, encryptedName: String, grant: SignedGrant): Boolean {
        if (scopeIdTaken(scopeId)) return false // the client-minted id is taken → nothing written
        val now = Instant.now()
        ScopeEntity().apply {
            id = scopeId
            this.tenantId = tenantId
            kind = ScopeEntity.GROUP
            this.encryptedName = encryptedName
            nameEditorUserId = userId // ADR 0093 § 8: the creator sealed the name
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
    fun renameGroup(scopeId: String, encryptedName: String, nameEpoch: Int, editor: PrincipalKey.User): Boolean =
        ScopeEntity.update(
            "encryptedName = ?1, nameEpoch = ?2, nameEditorUserId = ?4 where id = ?3 and currentEpoch = ?2",
            encryptedName, nameEpoch, scopeId, editor.userId,
        ) == 1

    /** Set/replace a scope's icon (uc.spaces.set-space-icon): overwrite `sealed_icon` (re-sealed under `K_icon` of
     *  the current epoch) + `icon_epoch`. The caller (handler) has already gated owner + active. Returns false,
     *  nothing written, when [iconEpoch] is not the scope's current epoch — the [renameGroup] gate, for the same
     *  reason. */
    @Transactional
    fun setIcon(scopeId: String, sealedIcon: String, iconEpoch: Int, editor: PrincipalKey.User): Boolean =
        ScopeEntity.update(
            "sealedIcon = ?1, iconEpoch = ?2, iconEditorUserId = ?4 where id = ?3 and currentEpoch = ?2",
            sealedIcon, iconEpoch, scopeId, editor.userId,
        ) == 1

    /** Clear a scope's icon (uc.spaces.set-space-icon A5): reset `sealed_icon` + `icon_epoch` so every member falls
     *  back to the initial. **No epoch** — a removal carries no ciphertext that could be stale; idempotent (clearing
     *  an already-iconless scope is a no-op). Owner-gated by the caller (handler). */
    @Transactional
    fun clearIcon(scopeId: String) {
        ScopeEntity.findById(scopeId)?.let {
            it.sealedIcon = null
            it.iconEditorUserId = null // a cleared field has no editor (ADR 0093 § 8)
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
                        rekeying = ScopeRekeying.lagsBelow(it.id, it.currentEpoch),
                    )
                }
            }
        }
    }

    /**
     * For the scopes among [memberships] the caller **owns**, what a rotation queued there (a removal cascade's, ADR 0025)
     * needs from an owner (uc.spaces.complete-queued-rotation E2/E8): `FAILED` or `STALLED`, else nothing. A plain
     * member's row never carries it — only owners can promote a co-owner or re-queue.
     */
    private fun stuckCompromiseRotations(memberships: List<MembershipEntity>): Map<String, CompromiseRotationState> {
        val owned = memberships.filter { it.role == MembershipEntity.OWNER && it.wrappedGrant != null }.map { it.scopeId }
        return tasks.queuedRotations(owned)
    }

    /** The granter of a membership's grant — id, signature, and the identity public key the grant row recorded when
     *  it was written (the key that signed it, [MembershipEntity.granterIdentityPublicKey]) — or null when the grant
     *  carries no signature (written before grants were signed) or no recorded key. Never the granter's CURRENT key:
     *  after an account rotation that is a key the signature was not made with, and the recipient, which verifies
     *  before it reads its pin, would refuse every grant the rotator signed (ADR 0028 Decision 6). */
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
     * row's lock, which is this transaction's first statement: the caller is an OWNER, the target holds an accepted row
     * (a consent proposal is no member until it accepts, so it is never made an owner — inbox #597), and the change
     * leaves an accepted OWNER ([MembershipPolicy]). Each removal-rotation holds the same lock. Checked before
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
     * ADR 0068, and on a TENANT scope every verification they gave or received there, ADR 0111 decision 12) — as they
     * do in a removal-rotation.
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
     * by re-issue. A pending proposal's delete is no removal: it holds the grant (ADR 0111 decision 4), but the server
     * served it nothing of the group, so it has no copy to rebuild a link from (uc.spaces.consent-group-add A3a).
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

    /** Commit a rotation (ADR 0024 §4): if [epoch] is the scope's next one and — for a TENANT — the rotation re-grants
     *  or declares pending every open member invite, advance to it, store the chain-link and apply the re-grants (each
     *  to the new epoch, signed by [granterId]) to the members and the invites; otherwise store nothing. Client-minted;
     *  the server stores only ciphertext.
     *
     *  Dumb by design (ADR 0045 §2): the decision — only the next epoch commits, and no open invite is left behind
     *  silently (ADR 0111 decision 2) — lives in the pure [RotationPolicy], where it is unit-tested and
     *  mutation-verified. This method only locks the row, loads the directory and the open invites, checks the key each
     *  invite re-grant names, asks the policy, and writes what it returns.
     *
     *  The scope row is read under `PESSIMISTIC_WRITE`, so two in-flight commits stating the same epoch serialize:
     *  the loser reads the winner's epoch and is a clean stale. Without the lock both read the old epoch, both pass
     *  the policy, and the loser dies on `ux_scope_epoch` — the chain still holds, but as an error the client does
     *  not retry. Every grant written at a stated epoch takes the same lock ([addMember], the tenant's member invite and
     *  its accept), so no grant can land at an epoch a concurrent rotation is leaving.
     *
     *  The same lock is what makes the two facts about the caller exact (ADR 0060 Decision 4): every scope-rotation
     *  origination takes it too, so an account rotation and the rotator's own commit on one scope serialise, and
     *  whichever commits second sees the other. A commit that lands marks every rotation job of the scope leased to
     *  the committer ([TasksStore.markRotated]) — the only thing that lets such a job complete. */
    @Transactional
    fun rotate(
        scopeId: String, epoch: Int, wrappedPrev: String, granterId: String,
        grants: List<RotationGrant>, inviteGrants: List<InviteGrant> = emptyList(), pendingInviteIds: Set<String> = emptySet(),
        removes: String? = null, revokeLinks: Boolean = false, pendingUserIds: Set<String> = emptySet(),
        regranted: Boolean = false,
    ): RotationDecision {
        val scope = ScopeEntity.lockRow(scopeId)!!
        val all = MembershipEntity.find("scopeId", scope.id).list()
        val invites = if (scope.kind == ScopeEntity.TENANT) tenants.openMemberInvites(scope.tenantId).associateBy { it.id } else emptyMap()
        // A re-grant names the key it is wrapped to (ADR 0111): the open invite's own, or nothing is stored.
        if (inviteGrants.any { g -> invites[g.inviteId]?.let { !InviteAcceptProof.digestMatches(it.invitePublicKey, g.recipientIdentityDigest) } == true }) {
            return RotationDecision.RecipientKeyNotCurrent
        }
        val caller = all.firstOrNull { it.userId == granterId }
        val decision = RotationPolicy.plan(
            // The last-owner guard counts accepted owners only: a proposal is a MEMBER to it whatever its row says (#597).
            scope.currentEpoch, epoch, all.map { RotationMember(it.userId, if (it.pendingConsent) MembershipEntity.MEMBER else it.role) },
            invites.keys, grants, inviteGrants, pendingInviteIds, pendingUserIds, removes,
            callerGrantedEpoch = caller?.wrappedGrant?.let { caller.grantedEpoch },
            // A non-owner is here only on a pending re-key row (the handler's gate); the row's epoch is read under this
            // lock, so a commit by someone else in between answers stale rather than letting a second epoch through.
            callerRegrantEpoch = if (regranted) regrantEpoch(granterId, scopeId) ?: -1 else null,
        )
        val plan = (decision as? RotationDecision.Commit)?.plan ?: return decision
        // Every re-grant names the key it is wrapped to (ADR 0111 decision 10): the member's current identity, read under
        // this lock — the one an account's publish takes for every scope it holds — or nothing is stored. A grant to an
        // identity its account retired is exactly what a stale directory read would wrap to.
        if (plan.grants.any { g -> !InviteAcceptProof.digestMatches(identityKeyOf(g.userId), g.recipientIdentityDigest) }) {
            return RotationDecision.RecipientKeyNotCurrent
        }
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
        tenants.carryInvites(plan.inviteGrants.map { InviteRegrant(it.inviteId, it.grant) }, plan.newEpoch, granterId)
        tasks.markRotated(scopeId, granterId, plan.newEpoch)
        return decision
    }

    /** The epoch [userId]'s pending re-key row on [scopeId] names (ADR 0111 decision 8), or null when it has none. */
    fun regrantEpoch(userId: String, scopeId: String): Int? =
        RotationRegrantEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.epochAtRotation

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

    /** One of a user's memberships within a tenant, tagged with its scope kind + the **accepted** member and owner
     *  counts on that scope and whether the user's own row is an unaccepted proposal — a row of the
     *  `removeTenantMember` worklist (cr-0091). The counts are pre-resolved here so the removal decision's sole-owner /
     *  last-owner `<= 1` boundaries live in the pure [RemovalPolicy], not an SQL predicate — unit-tested and
     *  mutation-verified without a boot (ADR 0045 §2). A proposal is counted by neither: it is no member to any role
     *  decision until it accepts (inbox #598). */
    data class TenantMembershipRow(
        val scopeId: String,
        val kind: String,
        val role: String,
        val acceptedMembers: Long,
        val ownerCount: Long,
        val pending: Boolean,
    )

    /** The ids of every scope the user holds a row on in this tenant — the locks a tenant removal takes before it
     *  plans ([membershipsInTenant] is the plan's read, made under them). */
    fun scopeIdsInTenant(userId: String, tenantId: String): List<String> =
        MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().map { it.scopeId }

    /** Every scope the user holds a row on in this tenant, each tagged with the scope kind + its accepted member and
     *  owner counts — the input to the per-kind removal cascade (delete shared, orphan-transfer, soft-delete own,
     *  drop a proposal; cr-0091, inbox #598). */
    fun membershipsInTenant(userId: String, tenantId: String): List<TenantMembershipRow> =
        MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().mapNotNull { m ->
            ScopeEntity.findById(m.scopeId)?.let { s ->
                TenantMembershipRow(
                    m.scopeId, s.kind, m.role,
                    MembershipEntity.count("scopeId = ?1 and pendingConsent = false", m.scopeId),
                    membership.ownerCount(m.scopeId),
                    m.pendingConsent,
                )
            }
        }

    /** The earliest-joined **other accepted** member of a scope (min `createdAt`), or null if none is left — the
     *  orphan rule's deterministic successor owner (cr-0091, ADR 0014 §6). A consent proposal is never handed a group,
     *  however old: it is no member until it accepts (inbox #598). */
    fun oldestOtherMember(scopeId: String, exceptUserId: String): String? =
        MembershipEntity.find(
            "scopeId = ?1 and userId <> ?2 and pendingConsent = false order by createdAt asc, id", scopeId, exceptUserId,
        ).firstResult()?.userId

    /**
     * Expire every open consent proposal on a group (ADR 0014 §6, inbox #598) — its only accepted owner is leaving,
     * and a proposal, no member, is never handed the group. The rows go as a decline's do
     * ([deletePendingMembership]): no removal is counted, since the server served a proposal nothing of the group. The
     * caller holds the scope row's lock and queues the rotation its proposals' grants call for, if anybody is left.
     */
    @Transactional
    fun expireProposals(scopeId: String): Int =
        MembershipEntity.delete("scopeId = ?1 and pendingConsent = true", scopeId).toInt()

    /**
     * Add a member to a GROUP **with the grant** the owner's client wrapped to their identity key (ADR 0111 decision 4)
     * — role MEMBER, granter [addedBy] with their identity key read here. With [sealedNameKey] it is a **soft consent
     * proposal** (cr-0096): `pendingConsent`, carrying the wrapped group name-key `K_name` and who proposed, and the
     * group is not listed to the target until they accept; the grant is the same either way, so accepting changes
     * nothing about keys. Refused — false, nothing written — when [epoch] is not the scope's current one, read under the
     * lock a rotation takes: a rotation that committed in between could not have re-granted it. Operator-blind: only
     * ciphertext + operational metadata is written.
     */
    @Transactional
    fun addMember(
        userId: String, tenantId: String, scopeId: String, grant: SignedGrant, epoch: Int, addedBy: String, sealedNameKey: String?,
    ): Boolean {
        if (ScopeEntity.lockRow(scopeId)?.currentEpoch != epoch) return false
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.MEMBER
            wrappedGrant = grant.wrappedGrant
            grantedEpoch = epoch
            granterUserId = addedBy
            granterIdentityPublicKey = identityKeyOf(addedBy)
            grantSignature = grant.signature
            if (sealedNameKey != null) {
                pendingConsent = true
                this.sealedNameKey = sealedNameKey
                this.addedBy = addedBy
            }
            createdAt = Instant.now()
        }.persist()
        return true
    }

    /** Whether a user's account-global "ask before adding me to groups" preference is on (cr-0094). */
    fun requiresGroupAddConsent(userId: String): Boolean =
        VeyqUser.findById(userId)?.requireGroupAddConsent ?: false

    /** A user's identity public key bundle (opaque base64), or null — the owner's device wraps the group name-key to it. */
    fun identityPublicKeyOf(userId: String): String? = VeyqUser.findById(userId)?.identityPublicKey

    /** The open member invites of a TENANT scope with their keys (ADR 0111 decision 2); none for another kind. */
    fun openInvites(scopeId: String): List<OpenInviteRow> =
        ScopeEntity.findById(scopeId)?.takeIf { it.kind == ScopeEntity.TENANT }?.let { tenants.openInviteKeys(it.tenantId) } ?: emptyList()

    /** The caller's membership state on a scope (cr-0094): a live pending proposal, an ordinary member, or none. */
    fun consentState(userId: String, scopeId: String): ConsentState =
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()
            ?.let { if (it.pendingConsent) ConsentState.PENDING else ConsentState.ACTIVE } ?: ConsentState.NONE

    /** Accept a proposal (cr-0096): clear `pendingConsent` (and the now-moot wrapped name-key / addedBy) on the
     *  caller's own soft membership — it already holds its grant. Returns rows updated (0 if none was pending). The
     *  caller holds the scope row's lock (`ScopesHandler.acceptInvitation`, inbox #599): a tenant removal decides from a
     *  proposal under it, and an accept that lands between that read and its deletes outlives the removal. */
    @Transactional
    fun clearPendingConsent(userId: String, scopeId: String): Int =
        MembershipEntity.update(
            "pendingConsent = false, sealedNameKey = null, addedBy = null " +
                "where userId = ?1 and scopeId = ?2 and pendingConsent = true",
            userId, scopeId,
        )

    /** Decline / rescind a proposal (cr-0094): delete the caller's soft membership. Guarded to `pendingConsent`
     *  rows only — never deletes a real (accepted) membership. The proposal held a grant, so the caller queues the
     *  group's rotation in the same transaction (ADR 0111 decision 4). */
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
        val scope = ScopeEntity.findById(scopeId) ?: return null
        val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult() ?: return null
        val u = VeyqUser.findById(userId) ?: return null
        val p = profiles.byUserInTenant(tenantId)[userId]
        return ScopeMemberRow(
            userId, p?.username ?: "", p?.sealedName, p?.avatarRevision ?: 0, u.email, m.role, u.identityPublicKey,
            m.pendingConsent, p?.sealedJoinRecord.takeIf { scopeId == tenantId }, p?.joinEndorsement.takeIf { scopeId == tenantId },
            keyChainHead = keyChain.headOf(userId) ?: return null, pending = m.grantedEpoch < scope.currentEpoch,
        )
    }

    /** Whether the user is the tenant's OWNER (gates the owner-only email in the directory). */
    /** A tenant owner holds an OWNER membership on the tenant **root scope** (cr-0163), not merely any OWNER row
     *  carrying this `tenantId` — gates the owner-only member email (ADR 0023). Delegates to the single home. */
    fun isTenantOwner(userId: String, tenantId: String): Boolean = membership.isTenantOwner(userId, tenantId)

    /** Every member of a scope: identity (per-tenant username + sealed name), pubkey, email and — in the TENANT scope —
     *  the sealed join record. The email is included on every row here; the handler nulls it for non-owner callers
     *  (ADR 0023). */
    fun listMembers(scopeId: String): List<ScopeMemberRow> {
        val scope = ScopeEntity.findById(scopeId) ?: return emptyList()
        val tenantId = scope.tenantId
        val profileByUser = profiles.byUserInTenant(tenantId)
        val memberships = MembershipEntity.find("scopeId", scopeId).list()
        val userIds = memberships.map { it.userId }
        // Every member's account in one read, never one `findById` per member (inbox #537): 1,000 members were 1,017
        // statements, now a constant. `MemberListQueryCountSliceTest`.
        val users = if (userIds.isEmpty()) emptyMap() else VeyqUser.list("id in ?1", userIds.toSet()).associateBy { it.id }
        val heads = keyChain.heads(userIds)
        return memberships.mapNotNull { m ->
            users[m.userId]?.let { u ->
                val p = profileByUser[m.userId]
                ScopeMemberRow(
                    m.userId, p?.username ?: "", p?.sealedName, p?.avatarRevision ?: 0, u.email, m.role,
                    u.identityPublicKey, m.pendingConsent, p?.sealedJoinRecord.takeIf { scopeId == tenantId },
                    p?.joinEndorsement.takeIf { scopeId == tenantId },
                    keyChainHead = heads[m.userId] ?: return@let null, pending = m.grantedEpoch < scope.currentEpoch,
                )
            }
        }
    }

    /** The head of [userId]'s key chain (ADR 0111 decision 7), or null for an unknown account. */
    fun keyChainHeadOf(userId: String): String? = keyChain.headOf(userId)

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
                    val open = !TenantEntity.isOffline(t.status)
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
