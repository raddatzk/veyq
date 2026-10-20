package me.raddatz.veyq.core.scope

/**
 * What goes with one user's access to one scope: the package that owns per-member rows of a scope deletes that
 * member's rows here, so the membership removal in `ScopesStore` names no table of the Memories application
 * (ADR 0079). Unlike a [ScopeEraser], nothing of the scope is erased — one user loses access to one scope, and every
 * other member's rows, and this member's rows in every other scope, stay.
 *
 * Called on **every** path that deletes an accepted membership, inside that path's transaction: the group's
 * removal-rotation (`ScopesStore.rotate`, under the scope row's lock it takes first) and the tenant cascade's cut
 * (`ScopesStore.removeMembership`, under the scope lock `TenantHandler.removeMember` holds). A failure rolls the
 * membership delete back with it, so a member is never removed while their rows stay.
 *
 * An implementation deletes rows keyed by `(userId, scopeId)` only. It must take no lock beyond the caller's scope-row
 * lock (the lock order is scope → membership → job, and a lock taken here could invert it) and must not call the blob
 * store. No phase: implementations touch disjoint tables and none references another's rows; give the interface a
 * phase the day one does.
 */
interface MembershipEraser {
    fun eraseMembership(userId: String, scopeId: String)
}
