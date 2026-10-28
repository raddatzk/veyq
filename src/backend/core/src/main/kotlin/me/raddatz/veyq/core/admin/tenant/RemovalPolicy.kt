package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.scope.ScopeEntity

/** The membership facts the removal decision needs for one scope the target belongs to, lifted out of the
 *  persistence rows so the decision is testable without a DB. The counts are pre-resolved by the shell so the
 *  sole-owner / last-owner `<= 1` boundaries can live in the pure policy (ADR 0045 §2). Both count **accepted** rows
 *  only: a consent proposal is no member to any role decision (ADR 0007, inbox #598). [pending] is the target's own
 *  row being a consent proposal it has not accepted. */
data class RemovalScope(
    val scopeId: String,
    val kind: String,
    val role: String,
    val acceptedMembers: Long,
    val ownerCount: Long,
    val pending: Boolean = false,
)

/** What the shell must do to one scope in the cascade. `rotateAfter` queues the async layer-2 forward rotation
 *  (cr-0092, Fork 1b) — a GROUP only; the TENANT scope is ACL-revoke, never rotated (ADR 0025). `DROP_PROPOSAL` is a
 *  decline's path: the target's own open proposal goes and the group rotates, with no removal counted. */
enum class CascadeAction { SOFT_DELETE, REMOVE, TRANSFER_THEN_REMOVE, DROP_PROPOSAL }

/** One step. [expireProposals]: the group's only accepted owner leaves, so every open proposal on it is deleted
 *  (ADR 0014 §6) — on a hand-over the group's queued rotation then takes their key away; on a tombstone nobody is left
 *  to rotate for. */
data class CascadeStep(
    val scopeId: String,
    val action: CascadeAction,
    val rotateAfter: Boolean,
    val expireProposals: Boolean = false,
)

/** The computed removal (ADR 0014 §6): either a terminal rejection, or the per-scope steps for the shell to run. */
sealed interface RemovalPlan {
    /** The target holds no membership in this tenant (no row for the tenant scope) — a 404 (ADR 0042). */
    data object MemberNotFound : RemovalPlan

    /** The target is the tenant's last OWNER — the removal is rejected (the last-owner guard, ADR 0007). */
    data object LastOwner : RemovalPlan

    /** The per-scope cascade to execute, in membership-directory order. */
    data class Cascade(val steps: List<CascadeStep>) : RemovalPlan
}

/**
 * The pure tenant member-removal decision (ADR 0045 §2), lifted out of [TenantHandler.removeMember]'s
 * `@Transactional` cascade so its boundaries — the tenant **last-owner** guard and the per-scope action
 * selection, including the sole-owner `ownerCount <= 1` **orphan** trigger — are unit-tested and mutation-verified
 * without a Quarkus boot. The handler stays the imperative shell: it loads the membership directory, asks this
 * policy, and executes the returned steps; it decides nothing.
 *
 * Faithful to ADR 0014 §6 / ADR 0007 / ADR 0025, counting **accepted** members only (inbox #598):
 *  - the target's **own** scopes (PERSONAL + any GROUP with no other accepted member) are grace-tombstoned; the shell
 *    keeps the membership, so they retain read-only access to read/export during the grace window (never rotated),
 *    and such a group's open proposals expire with it,
 *  - **shared** access is cut at once (layer-1) — the TENANT scope ACL-revoke only, a co-shared GROUP additionally
 *    queues a forward rotation (layer-2),
 *  - a GROUP the target is the only accepted owner of hands ownership to the oldest remaining **accepted** member
 *    first (the orphan rule — a server rule, the tenant owner never gains access) and its open proposals expire;
 *    *who* that successor is stays a shell query,
 *  - a GROUP the target is only **proposed** to loses that proposal as a decline would. It is checked first: there
 *    the accepted count is everybody but the target, and the group must never pass for the target's own.
 */
object RemovalPolicy {
    /**
     * [allowLastOwner] is the account erasure's Server Owner door (ADR 0101 decision 9): an erasure cannot wait on a
     * third person, so the last owner is cut like any member and the tenant is left with no owner.
     */
    fun plan(scopes: List<RemovalScope>, tenantScopeId: String, allowLastOwner: Boolean = false): RemovalPlan {
        val tenant = scopes.firstOrNull { it.scopeId == tenantScopeId } ?: return RemovalPlan.MemberNotFound
        if (!allowLastOwner && tenant.role == MembershipEntity.OWNER && tenant.ownerCount <= 1) return RemovalPlan.LastOwner
        return RemovalPlan.Cascade(scopes.map(::step))
    }

    private fun step(s: RemovalScope): CascadeStep = when {
        // The target's own scopes: grace-tombstone, keep the membership (read-only grace, then purge).
        s.kind == ScopeEntity.PERSONAL -> CascadeStep(s.scopeId, CascadeAction.SOFT_DELETE, rotateAfter = false)
        // Only proposed here: the proposal goes as a decline's does — it held the key, so the group rotates.
        s.kind == ScopeEntity.GROUP && s.pending -> CascadeStep(s.scopeId, CascadeAction.DROP_PROPOSAL, rotateAfter = true)
        // No other accepted member: the group is the target's own, and its open proposals go with it.
        s.kind == ScopeEntity.GROUP && s.acceptedMembers <= 1 ->
            CascadeStep(s.scopeId, CascadeAction.SOFT_DELETE, rotateAfter = false, expireProposals = true)
        // Shared content: instant layer-1 cut. A group the target solely owns is handed over first (orphan rule).
        else -> {
            val soleOwnedGroup = s.kind == ScopeEntity.GROUP && s.role == MembershipEntity.OWNER && s.ownerCount <= 1
            val action = if (soleOwnedGroup) CascadeAction.TRANSFER_THEN_REMOVE else CascadeAction.REMOVE
            CascadeStep(s.scopeId, action, rotateAfter = s.kind == ScopeEntity.GROUP, expireProposals = soleOwnedGroup)
        }
    }
}
