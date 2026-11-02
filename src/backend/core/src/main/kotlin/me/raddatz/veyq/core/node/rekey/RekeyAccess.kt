package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.account.rotation.RotationRegrantEntity
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
import me.raddatz.veyq.core.sharing.WriteOnlyGuestKinds
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped

/** Why a caller may not re-key a scope or one of its nodes, or that it may. Every refusal is a [RekeyOutcome] too: an operation answers it
 *  as it is. */
sealed interface RekeyRight {
    object Granted : RekeyRight
    object NoScope : RekeyRight, RekeyOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : RekeyRight, RekeyOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RekeyRight, RekeyOutcome, HttpFailure by Failures.scopeDeleting
    object NotOwner : RekeyRight, RekeyOutcome, HttpFailure by httpFailure("not_scope_owner", FORBIDDEN)
    object GrantBehind : RekeyRight, RekeyOutcome, HttpFailure by httpFailure("grant_behind", FORBIDDEN)
}

/**
 * Who may read and commit a re-key (ADR 0109; ADR 0111 decision 11): **any member** of the scope who holds its
 * **current** grant. A re-key is not governance — it changes neither
 * membership nor content nor who holds a link (re-wrapping a share from its recovery copy keeps it working), so it
 * needs only the current epoch, which every such member holds. It is still a write path onto the node (it re-seals
 * names, meta and EXIF the server cannot check), so it is open where the caller may write: everywhere a member may,
 * **except** a node only an owner re-keys ([OwnerOnlyRekey]: a drop box, a guest's identity folder or below one, where
 * members place nothing, ADR 0080 Decision 11) — there a member is [RekeyRight.NotOwner]. The old identity of an account
 * that secured itself holds no current grant once the account's next epoch is in (ADR 0111 decision 8), so it re-keys
 * nothing after that.
 * The scope-access gate first, as everywhere (ADR 0042): a non-member learns nothing; a read of a deleting group is
 * answered, a write refused.
 */
@ApplicationScoped
class RekeyAccess(private val access: ScopeAccess, private val ownerOnly: OwnerOnlyRekey) {

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
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        if (membership.wrappedGrant == null || membership.grantedEpoch < current) return RekeyRight.GrantBehind
        if (membership.role != MembershipEntity.OWNER && nodeId != null && ownerOnly.at(scopeId, nodeId)) return RekeyRight.NotOwner
        return RekeyRight.Granted
    }

    /**
     * The right to read and stage a rotation's scope-level re-seal (ADR 0115 decisions 2 and 7): only the rotating client
     * holds the old key and the new one, so only a caller who may commit the scope's rotation — an owner, or a member
     * whose account securing left a pending re-key row for the scope (`rotateScope`'s Who may commit, ADR 0111
     * decision 8) — and who holds its current grant. The scope-access gate first, as everywhere (ADR 0042).
     */
    fun checkRotator(userId: String, scopeId: String, mode: AccessMode): RekeyRight {
        when (access.check(userId, scopeId, mode)) {
            Access.NotMember -> return RekeyRight.NoScope
            Access.Suspended -> return RekeyRight.Suspended
            Access.Deleting -> return RekeyRight.Deleting
            Access.Ok -> Unit
        }
        val membership = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()
            ?: return RekeyRight.NoScope
        val owner = membership.role == MembershipEntity.OWNER && !membership.pendingConsent
        if (!owner && RotationRegrantEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) == 0L) return RekeyRight.NotOwner
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        if (membership.wrappedGrant == null || membership.grantedEpoch < current) return RekeyRight.GrantBehind
        return RekeyRight.Granted
    }
}

/**
 * The nodes only an owner of the scope re-keys (ADR 0111 decision 11, last bullet; ADR 0080 Decision 11): where members
 * place nothing of their own. That is a **drop box** — a node of a kind whose `WRITE_ONLY` guests get identity folders,
 * as the kind's application declares through [WriteOnlyGuestKinds] (Depot's `DEPOT_DROPBOX`; one implementation claiming
 * the kind is enough, fail closed toward the owner) — and a guest's identity folder ([NodeEntity.shareIdentityId]) with
 * everything below one, the walk `NodesStore.identityFolderOwner` takes.
 *
 * **A drop key alone does not make a node owner-only**: a Memory or chapter gets one with its first `WRITE_ONLY` link,
 * which any member may make, and members write into it. Its re-key re-seals only what a member holding the current
 * epoch opens — the drop private key under the node's key, each guest's `K_up` through it, each link's
 * `K_share` from its recovery copy (ADR 0109 point 5) — and writes nothing a member could not already write there: a
 * member sets a node's drop key with its first link, seals the public half under every link it makes, and trashes any
 * guest's upload. Served to the client as `RekeyNode.ownerOnly`, so a member's re-key skips them.
 */
@ApplicationScoped
class OwnerOnlyRekey(
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @param:All private val writeOnlyKinds: MutableList<WriteOnlyGuestKinds>,
) {
    /** Whether [node] is owner-only; [parentOf] answers a parent id with its row in the same scope, null at the root. */
    fun of(node: NodeEntity, parentOf: (String) -> NodeEntity?): Boolean = of(node, ::isDropBox, parentOf)

    /** [of] for the node [nodeId] of [scopeId], read row by row; false for a node not in the scope (the operation then
     *  answers its own `node_not_found`). */
    fun at(scopeId: String, nodeId: String): Boolean {
        val node = NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId } ?: return false
        return of(node) { id -> NodeEntity.findById(id)?.takeIf { it.scopeId == scopeId } }
    }

    private fun isDropBox(kind: String): Boolean = writeOnlyKinds.any { it.creatable(kind) != null }

    companion object {
        /** The rule itself, boot-free: [dropBox] answers whether a node of a kind is a drop box. */
        fun of(node: NodeEntity, dropBox: (String) -> Boolean, parentOf: (String) -> NodeEntity?): Boolean {
            if (dropBox(node.kind)) return true
            var cur: NodeEntity? = node
            while (cur != null) {
                if (cur.shareIdentityId != null) return true
                cur = parentOf(cur.parentId)
            }
            return false
        }
    }
}
