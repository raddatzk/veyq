package com.veyq.core.membership

import com.veyq.core.scope.ScopeEntity
import com.veyq.core.tenant.TenantEntity
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

    /** The number of OWNER memberships on a scope — the last-owner guard's count (ADR 0007). The **policy** that
     *  turns `<= 1` into a `LastOwner` refusal stays in the feature handler. */
    fun ownerCount(scopeId: String): Long =
        MembershipEntity.count("scopeId = ?1 and role = ?2", scopeId, MembershipEntity.OWNER)

    /** Whether the user is a tenant **owner** — holds an `OWNER` membership on the tenant **root scope**
     *  (`scopeId == tenantId`, cr-0026), **not** merely any `OWNER` row carrying this `tenantId` (every scope birth
     *  mints its creator an `OWNER` row, so a `tenantId`-only check answers true for ordinary members — the cr-0163
     *  escalation). belonging keys on the root too ([belongsToTenant]); every role check keys on `scopeId` (ADR 0007).
     *  The owner sibling of [ownerCount] — both key the tenant's authority on the root scope. */
    fun isTenantOwner(userId: String, tenantId: String): Boolean = isScopeOwner(userId, tenantId)

    /** A member's role in a scope, or null if they hold no membership there. */
    fun memberRole(userId: String, scopeId: String): String? =
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.role

    /** Whether the user holds a grant in this scope (is a key-holder there) — the member-directory gate. */
    fun holdsGrant(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and wrappedGrant is not null", userId, scopeId) > 0

    /**
     * Every scope this user belongs to, mapped to how many members that scope has (ADR 0060, key rotation).
     *
     * The count is what decides **who may re-key** after a rotation, and it is drawn on member count rather
     * than on scope kind on purpose: a scope with one member has nobody to re-admit the rotator and nobody to
     * exclude but the rotator's own old identity, so it is self-served — the personal scope is the ordinary
     * case, and a group nobody else has joined behaves identically. Anything else must be rotated by a
     * *different* key-holder (ADR 0060 Decision 4).
     *
     * A `pendingConsent` proposal is **not** a member: it holds no grant, cannot complete a rotation job, and
     * is deliberately skipped by grant-delivery until the invitee accepts (ADR 0014 §5). Counting one would
     * queue a rotation in a scope where nobody can ever claim it, which is a permanent `awaiting access` for
     * the rotator with no human anywhere who could resolve it.
     */
    fun memberCountsByScopeFor(userId: String): Map<String, Long> {
        val mine = MembershipEntity.find("userId = ?1 and pendingConsent = false", userId).list()
            .map { it.scopeId }.toSet()
        if (mine.isEmpty()) return emptyMap()
        return MembershipEntity.find("scopeId in ?1 and pendingConsent = false", mine).list()
            .groupingBy { it.scopeId }.eachCount()
            .mapValues { (_, count) -> count.toLong() }
    }
}
