package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.Access
import me.raddatz.veyq.core.membership.AccessMode
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.TasksStore
import jakarta.enterprise.context.ApplicationScoped

/** Why a caller may not re-key a scope, or that it may. Every refusal is a [RekeyOutcome] too: an operation answers it
 *  as it is. */
sealed interface RekeyRight {
    object Granted : RekeyRight
    object NoScope : RekeyRight, RekeyOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : RekeyRight, RekeyOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RekeyRight, RekeyOutcome, HttpFailure by Failures.scopeDeleting
    object NotOwner : RekeyRight, RekeyOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object Excluded : RekeyRight, RekeyOutcome, HttpFailure by httpFailure("rotation_excluded", FORBIDDEN)
    object GrantBehind : RekeyRight, RekeyOutcome, HttpFailure by httpFailure("grant_behind", FORBIDDEN)
}

/**
 * Who may read and commit a scope's re-key (CR decision 5): an **owner** of the scope who holds its **current** grant
 * and is **not excluded** by a live rotation job — the gate `rotateScope` applies (`RotationPolicy.plan`). A re-key
 * re-wraps every share from its recovery copy and re-seals every guest identity, so it needs what only an owner with the
 * current epoch holds; an account that rotated its keys after a compromise is excluded until re-admitted (ADR 0060
 * Decision 4), so the old identity never re-keys. The scope-access gate first, as everywhere (ADR 0042): a non-member
 * learns nothing; a read of a deleting group is answered, a write refused.
 */
@ApplicationScoped
class RekeyAccess(private val access: ScopeAccess, private val tasks: TasksStore) {

    fun check(userId: String, scopeId: String, mode: AccessMode): RekeyRight {
        when (access.check(userId, scopeId, mode)) {
            Access.NotMember -> return RekeyRight.NoScope
            Access.Suspended -> return RekeyRight.Suspended
            Access.Deleting -> return RekeyRight.Deleting
            Access.Ok -> Unit
        }
        val membership = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()
            ?: return RekeyRight.NoScope
        if (membership.role != MembershipEntity.OWNER) return RekeyRight.NotOwner
        if (tasks.isRotationExcluded(scopeId, userId)) return RekeyRight.Excluded
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        if (membership.wrappedGrant == null || membership.grantedEpoch < current) return RekeyRight.GrantBehind
        return RekeyRight.Granted
    }
}
