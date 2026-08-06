package com.veyq.app.shared.membership

import jakarta.enterprise.context.ApplicationScoped

/**
 * The shared, **query-only** membership primitive (cr-0160, ADR 0007). A leaf: it reads only its own
 * `MembershipEntity` — no `ScopeEntity` (spaces), no `TenantEntity` (admin) — so `features → shared` stays
 * one-directional with no cycle. The guard queries here are the single source of truth the feature slices
 * delegate to (the `isScopeMember`/`ownerCount` copies that used to be re-implemented verbatim per slice). The
 * membership relation is coarse, server-visible operational metadata (ADR 0007) — reading it never touches a key,
 * so operator-blindness is unaffected. Mutations + the last-owner **policy** stay in the owning feature handlers.
 */
@ApplicationScoped
class MembershipStore {

    /** Whether the user holds any membership on this scope (grant or not) — the idempotency / access check. */
    fun isScopeMember(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) > 0

    /** Whether the user holds an OWNER membership on this scope (the group-admission gate, ADR 0014 §4). */
    fun isScopeOwner(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and role = ?3", userId, scopeId, MembershipEntity.OWNER) > 0

    /** The number of OWNER memberships on a scope — the last-owner guard's count (ADR 0007). The **policy** that
     *  turns `<= 1` into a `LastOwner` refusal stays in the feature handler. */
    fun ownerCount(scopeId: String): Long =
        MembershipEntity.count("scopeId = ?1 and role = ?2", scopeId, MembershipEntity.OWNER)

    /** Whether the user already holds a membership in this tenant (i.e. belongs to it). The TENANT root scope is
     *  keyed `scopeId == tenantId`, but tenant belonging is by `tenantId` across all the user's scopes. */
    fun isMember(userId: String, tenantId: String): Boolean =
        MembershipEntity.count("userId = ?1 and tenantId = ?2", userId, tenantId) > 0

    /** Whether the user is a tenant **owner** — holds an `OWNER` membership on the tenant **root scope**
     *  (`scopeId == tenantId`, cr-0026), **not** merely any `OWNER` row carrying this `tenantId` (every scope birth
     *  mints its creator an `OWNER` row, so a `tenantId`-only check answers true for ordinary members — the cr-0163
     *  escalation). `tenantId` expresses belonging (see [isMember]); every role check keys on `scopeId` (ADR 0007).
     *  The owner sibling of [ownerCount] — both key the tenant's authority on the root scope. */
    fun isTenantOwner(userId: String, tenantId: String): Boolean = isScopeOwner(userId, tenantId)

    /** A member's role in a scope, or null if they hold no membership there. */
    fun memberRole(userId: String, scopeId: String): String? =
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.role

    /** Whether the user holds a grant in this scope (is a key-holder there) — the member-directory gate. */
    fun holdsGrant(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and wrappedGrant is not null", userId, scopeId) > 0
}
