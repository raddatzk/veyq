package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import jakarta.enterprise.context.ApplicationScoped

/** What an operation does to the space: READ passes a deleting space, WRITE does not. */
enum class AccessMode { READ, WRITE }

/** The gate's answer. [NotMember] maps to the operation's own 404 — never to anything that tells it apart. */
sealed interface Access {
    data object Ok : Access
    data object NotMember : Access
    data object Suspended : Access
    data object Deleting : Access
}

/**
 * The **one** scope-access gate (uc.spaces.scope-access, ADR 0042). Every scope-, tenant-, share- and
 * job-addressed operation asks it inline, **before anything else it does** — existence, kind, role and capability
 * checks all come after, so a caller the gate refuses learns nothing the gate knows. A tenant-addressed operation
 * asks it on the tenant's root scope (`scopeId == tenantId`), so *belonging to a tenant* is one fact with one
 * definition.
 *
 * The answer comes in a fixed order, and the order is the security property:
 *  1. no **accepted** membership (`pendingConsent = false`) on the scope → [Access.NotMember]. An unaccepted
 *     proposal is not a membership here (ADR 0014 §5): the proposed member answers their own invitation and is a
 *     stranger everywhere else. Nothing past this line is ever revealed to such a caller.
 *  2. the scope's tenant is SUSPENDED → [Access.Suspended], for reads and writes alike, whatever `Host` the request
 *     arrived on (ADR 0066) — the space switcher reaches every tenant by id from any domain, so the `Host` filter
 *     alone could not see which tenant a request is *about*.
 *  3. WRITE only: no accepted membership on the tenant's **root** → [Access.NotMember] — a member removed from the
 *     tenant keeps their own spaces readable for the grace window and changes nothing in them, restore included.
 *  4. WRITE only: the scope is being deleted (`deletedAt` set) → [Access.Deleting]. Two writes waive exactly this
 *     answer and nothing before it (uc.spaces.scope-access A1): restoring the group, the only way out of the grace,
 *     and revoking a link, which only ever closes exposure.
 *
 * The status is read on every call, never cached: the gate reads the row a suspension or a deletion just wrote.
 * Operator-blind by construction — membership rows, a scope's `deletedAt` and a tenant's `status`, all operational
 * metadata the server already holds (ADR 0007); no key, no name, no content.
 */
@ApplicationScoped
class ScopeAccess {

    fun check(userId: String, scopeId: String, mode: AccessMode): Access {
        if (!accepted(userId, scopeId)) return Access.NotMember
        val scope = ScopeEntity.findById(scopeId) ?: return Access.NotMember
        if (TenantEntity.isOffline(TenantEntity.findById(scope.tenantId)?.status)) return Access.Suspended
        if (mode == AccessMode.WRITE) {
            if (!accepted(userId, scope.tenantId)) return Access.NotMember
            if (scope.deletedAt != null) return Access.Deleting
        }
        return Access.Ok
    }

    /** The scopes among [scopeIds] this user may write — a claim's eligibility (a job is worked by writing). */
    fun writable(userId: String, scopeIds: Set<String>): Set<String> =
        scopeIds.filterTo(HashSet()) { check(userId, it, AccessMode.WRITE) == Access.Ok }

    /**
     * Whether a space serves **guests**: it exists, is not being deleted and its tenant is not suspended
     * (uc.spaces.scope-access A4). A share guest or an invite holder is no member, so they are told neither state —
     * a paused space answers them exactly as a revoked link or a dead invite does, and nothing is written, so the
     * link works again unchanged after a restore or a reactivation.
     */
    fun isLive(scopeId: String): Boolean {
        val scope = ScopeEntity.findById(scopeId) ?: return false
        return scope.deletedAt == null && isTenantLive(scope.tenantId)
    }

    /** Whether a tenant is not suspended — the guest-facing half of [isLive], for tenant-addressed tokens. */
    fun isTenantLive(tenantId: String): Boolean = !TenantEntity.isOffline(TenantEntity.findById(tenantId)?.status)

    private fun accepted(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and pendingConsent = false", userId, scopeId) > 0
}

/**
 * Project a refused [Access] onto an operation's own outcome variants: `null` when the gate let the call through.
 * A READ never answers [Access.Deleting], and neither does a check on a tenant's root scope (a tenant is suspended
 * or revoked, never put into a deletion grace) — so those pass no `deleting` variant, and a `Deleting` there is a
 * programming error, not a state.
 */
fun <T> Access.refusal(notMember: T, suspended: T, deleting: T? = null): T? = when (this) {
    Access.Ok -> null
    Access.NotMember -> notMember
    Access.Suspended -> suspended
    Access.Deleting -> deleting ?: error("Deleting answered to a check that declared none (a READ, or a tenant root)")
}
