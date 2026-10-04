package com.veyq.core.spaces.scope

import com.veyq.core.membership.MembershipEntity

/**
 * The pure last-owner guards for scope-membership changes (ADR 0045 §2, ADR 0007), lifted out of [ScopesHandler] so
 * the "a scope may never be left ownerless" boundary is unit-tested and mutation-verified without a Quarkus boot.
 * The shell pre-resolves the caller-supplied facts — the target's `currentRole`, the requested `targetRole`, and the
 * scope's `ownerCount` — exactly as [com.veyq.core.admin.tenant.RemovalPolicy] pre-resolves its counts; the
 * `<= 1` boundary lives here, the queries stay in the shell.
 *
 * This is a *scope-membership* guard and is deliberately **not** folded into `RemovalPolicy` (that one is *tenant*
 * removal — a cascade in a different package). Same `<= 1` shape, distinct decision. Both predicates are true
 * exactly when the change would strand the scope without an OWNER, so the shell must reject it (ADR 0007).
 */
object MembershipPolicy {
    /** Demoting the target to MEMBER would strand the scope ownerless — true only when the target is the sole OWNER. */
    fun isLastOwnerDemote(currentRole: String, targetRole: String, ownerCount: Long): Boolean =
        currentRole == MembershipEntity.OWNER && targetRole == MembershipEntity.MEMBER && ownerCount <= 1

    /** Removing the target would strand the scope ownerless — true only when the target is the sole OWNER. */
    fun isLastOwnerRemoval(currentRole: String, ownerCount: Long): Boolean =
        currentRole == MembershipEntity.OWNER && ownerCount <= 1
}
