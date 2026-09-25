package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.scope.ScopeEntity

/** The membership facts the removal decision needs for one scope the target belongs to, lifted out of the
 *  persistence rows so the decision is testable without a DB. `ownerCount` is pre-resolved by the shell so the
 *  sole-owner / last-owner `<= 1` boundaries can live in the pure policy (ADR 0045 §2). */
data class RemovalScope(
    val scopeId: String,
    val kind: String,
    val role: String,
    val memberCount: Long,
    val ownerCount: Long,
)

/** What the shell must do to one scope in the cascade. `rotateAfter` queues the async layer-2 forward rotation
 *  (cr-0092, Fork 1b) — a co-shared GROUP only; the TENANT scope is ACL-revoke, never rotated (ADR 0025). */
enum class CascadeAction { SOFT_DELETE, REMOVE, TRANSFER_THEN_REMOVE }

data class CascadeStep(val scopeId: String, val action: CascadeAction, val rotateAfter: Boolean)

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
 * policy, and executes the returned steps (soft-delete / remove / transfer-then-remove); it decides nothing.
 *
 * Faithful to ADR 0014 §6 / ADR 0007 / ADR 0025:
 *  - the target's **own** scopes (PERSONAL + any solo GROUP) are grace-tombstoned; the shell keeps the membership,
 *    so they retain read-only access to read/export during the grace window (never rotated),
 *  - **shared** access is cut at once (layer-1) — the TENANT scope ACL-revoke only, a co-shared GROUP additionally
 *    queues a forward rotation (layer-2),
 *  - a GROUP the target **solely** owns hands ownership to the oldest remaining member first (the orphan rule — a
 *    server rule, the tenant owner never gains access); *who* that successor is stays a shell query.
 */
object RemovalPolicy {
    fun plan(scopes: List<RemovalScope>, tenantScopeId: String): RemovalPlan {
        val tenant = scopes.firstOrNull { it.scopeId == tenantScopeId } ?: return RemovalPlan.MemberNotFound
        if (tenant.role == MembershipEntity.OWNER && tenant.ownerCount <= 1) return RemovalPlan.LastOwner
        return RemovalPlan.Cascade(scopes.map(::step))
    }

    private fun step(s: RemovalScope): CascadeStep = when {
        // The target's own scopes: grace-tombstone, keep the membership (read-only grace, then purge).
        s.kind == ScopeEntity.PERSONAL -> CascadeStep(s.scopeId, CascadeAction.SOFT_DELETE, rotateAfter = false)
        s.kind == ScopeEntity.GROUP && s.memberCount <= 1 -> CascadeStep(s.scopeId, CascadeAction.SOFT_DELETE, rotateAfter = false)
        // Shared content: instant layer-1 cut. A group the target solely owns is handed over first (orphan rule).
        else -> {
            val soleOwnedGroup = s.kind == ScopeEntity.GROUP && s.role == MembershipEntity.OWNER && s.ownerCount <= 1
            val action = if (soleOwnedGroup) CascadeAction.TRANSFER_THEN_REMOVE else CascadeAction.REMOVE
            CascadeStep(s.scopeId, action, rotateAfter = s.kind == ScopeEntity.GROUP)
        }
    }
}
