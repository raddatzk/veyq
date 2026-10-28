package me.raddatz.veyq.core.membership

import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * The shared, **query-only** membership primitive (cr-0160, ADR 0007). A leaf: it reads only its own
 * `MembershipEntity` — no `ScopeEntity` (spaces), no `TenantEntity` (admin) — so `features → shared` stays
 * one-directional with no cycle. The guard queries here are the single source of truth the feature slices
 * delegate to (the `ownerCount` copies that used to be re-implemented verbatim per slice). **Access** is not
 * decided here: whether a caller may act on a space is `ScopeAccess.check` — the two predicates below that look
 * like access checks each answer a narrower question, and their names say which. The
 * membership relation is coarse, server-visible operational metadata (ADR 0007) — reading it never touches a key,
 * so operator-blindness is unaffected. Mutations + the last-owner **policy** stay in the owning feature handlers.
 */
@ApplicationScoped
class MembershipStore {

    /**
     * Whether the user holds **any** row on this scope — an unaccepted proposal included. Not an access check (that
     * is `ScopeAccess.check`, which does not count a proposal): it is the group-add's idempotency question, "is
     * this person already in or already invited" (uc.spaces.consent-group-add A2).
     */
    fun holdsAnyRow(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) > 0

    /**
     * Whether the user **belongs to the tenant** — an accepted membership on its root scope (`scopeId == tenantId`).
     * The one definition of belonging (uc.spaces.scope-access step 4); the question asked about a *target*, not
     * about the caller (a group-add candidate, a recovery address). A member removed from the tenant keeps rows on
     * their own spaces for the grace window, which is why no `tenantId`-wide row count may answer this.
     */
    fun belongsToTenant(userId: String, tenantId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and pendingConsent = false", userId, tenantId) > 0

    /** Whether the user holds an OWNER membership on this scope (the group-admission gate, ADR 0014 §4). */
    fun isScopeOwner(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and role = ?3", userId, scopeId, MembershipEntity.OWNER) > 0

    /** The number of **accepted** OWNER memberships on a scope — the last-owner guard's count (ADR 0007). An unaccepted
     *  proposal is no owner whatever its row says (inbox #597: counted, it let the last accepted owner step down, and a
     *  decline then left the group with none). The **policy** that turns `<= 1` into a `LastOwner` refusal stays in the
     *  feature handler. */
    fun ownerCount(scopeId: String): Long =
        MembershipEntity.count("scopeId = ?1 and role = ?2 and pendingConsent = false", scopeId, MembershipEntity.OWNER)

    /** Whether the user is a tenant **owner** — holds an `OWNER` membership on the tenant **root scope**
     *  (`scopeId == tenantId`, cr-0026), **not** merely any `OWNER` row carrying this `tenantId` (every scope birth
     *  mints its creator an `OWNER` row, so a `tenantId`-only check answers true for ordinary members — the cr-0163
     *  escalation). belonging keys on the root too ([belongsToTenant]); every role check keys on `scopeId` (ADR 0007).
     *  The owner sibling of [ownerCount] — both key the tenant's authority on the root scope. */
    fun isTenantOwner(userId: String, tenantId: String): Boolean = isScopeOwner(userId, tenantId)

    /** A member's role in a scope, or null if they hold no **accepted** membership there — a consent proposal is no
     *  member until it accepts (ADR 0014 §5), so a role change cannot make one an owner (inbox #597). */
    fun memberRole(userId: String, scopeId: String): String? =
        MembershipEntity.find("userId = ?1 and scopeId = ?2 and pendingConsent = false", userId, scopeId).firstResult()?.role

    /** Whether the user holds a grant in this scope (is a key-holder there) — the member-directory gate. */
    fun holdsGrant(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and wrappedGrant is not null", userId, scopeId) > 0
}
