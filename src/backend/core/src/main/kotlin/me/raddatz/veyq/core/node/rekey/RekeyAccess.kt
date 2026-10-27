package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.Access
import me.raddatz.veyq.core.membership.AccessMode
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tasks.TasksStore
import jakarta.enterprise.context.ApplicationScoped

/** Why a caller may not re-key a scope or one of its nodes, or that it may. Every refusal is a [RekeyOutcome] too: an operation answers it
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
 * Who may read and commit a re-key (ADR 0109; ADR 0111 decision 11): **any member** of the scope who holds its
 * **current** grant and is **not excluded** by a live rotation job. A re-key is not governance — it changes neither
 * membership nor content nor who holds a link (re-wrapping a share from its recovery copy keeps it working), so it
 * needs only the current epoch, which every such member holds. It is still a write path onto the node (it re-seals
 * names, meta and EXIF the server cannot check), so it is open where the caller may write: everywhere a member may,
 * **except** a node only an owner re-keys ([OwnerOnlyRekey]: a drop box root, a guest's identity folder or below one,
 * where members place nothing, ADR 0080 Decision 11) — there a member is [RekeyRight.NotOwner]. An account that rotated
 * its keys after a compromise is excluded until re-admitted (ADR 0060 Decision 4), so the old identity never re-keys.
 * The scope-access gate first, as everywhere (ADR 0042): a non-member learns nothing; a read of a deleting group is
 * answered, a write refused.
 */
@ApplicationScoped
class RekeyAccess(private val access: ScopeAccess, private val tasks: TasksStore) {

    /** The caller's right to re-key [scopeId] — at [nodeId] when the operation names a node (null for the listing, which
     *  serves every node and marks the owner-only ones instead). */
    fun check(userId: String, scopeId: String, mode: AccessMode, nodeId: String?): RekeyRight {
        when (access.check(userId, scopeId, mode)) {
            Access.NotMember -> return RekeyRight.NoScope
            Access.Suspended -> return RekeyRight.Suspended
            Access.Deleting -> return RekeyRight.Deleting
            Access.Ok -> Unit
        }
        val membership = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()
            ?: return RekeyRight.NoScope
        if (tasks.isRotationExcluded(scopeId, userId)) return RekeyRight.Excluded
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        if (membership.wrappedGrant == null || membership.grantedEpoch < current) return RekeyRight.GrantBehind
        if (membership.role != MembershipEntity.OWNER && nodeId != null && OwnerOnlyRekey.at(scopeId, nodeId)) return RekeyRight.NotOwner
        return RekeyRight.Granted
    }
}

/**
 * The nodes only an owner of the scope re-keys (ADR 0111 decision 11, last bullet; ADR 0080 Decision 11): a drop box
 * root of any kind (it carries a drop private key, [NodeEntity.sealedDropPrivateKey]), a guest's identity folder
 * ([NodeEntity.shareIdentityId]) and everything below one — the walk `NodesStore.identityFolderOwner` takes. A node a
 * member placed directly in a drop box root is no identity folder and stays any member's to re-key, once an owner
 * re-keyed the root above it (top-down). Served to the client as `RekeyNode.ownerOnly`, so a member's re-key skips them.
 */
object OwnerOnlyRekey {
    /** Whether [node] is owner-only; [parentOf] answers a parent id with its row in the same scope, null at the root. */
    fun of(node: NodeEntity, parentOf: (String) -> NodeEntity?): Boolean {
        if (node.sealedDropPrivateKey != null) return true
        var cur: NodeEntity? = node
        while (cur != null) {
            if (cur.shareIdentityId != null) return true
            cur = parentOf(cur.parentId)
        }
        return false
    }

    /** [of] for the node [nodeId] of [scopeId], read row by row; false for a node not in the scope (the operation then
     *  answers its own `node_not_found`). */
    fun at(scopeId: String, nodeId: String): Boolean {
        val node = NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId } ?: return false
        return of(node) { id -> NodeEntity.findById(id)?.takeIf { it.scopeId == scopeId } }
    }
}
