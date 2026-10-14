package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import io.quarkus.arc.All
import io.quarkus.arc.ClientProxy
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

/** A tree operation a guest asks of a container ([GuestNodeRules]): each names the kind of the child it acts on. */
sealed interface GuestNodeOp {
    val childKind: String

    /** Create a node of [childKind] in the container. */
    data class CreateChild(override val childKind: String) : GuestNodeOp
    /** Move a node of [childKind] out of the container (to another parent). */
    data class MoveChildOut(override val childKind: String) : GuestNodeOp
    /** Move a node of [childKind] into the container (from another parent). */
    data class MoveChildIn(override val childKind: String) : GuestNodeOp
    /** Rename a node of [childKind] that stays in the container. */
    data class RenameChild(override val childKind: String) : GuestNodeOp
}

/**
 * What a `READ_WRITE` guest may do to the children of a container of a kind (ADR 0080 decisions 6 and 10): **the owner
 * of the container decides** — core for a `FOLDER` ([FolderGuestNodeRules]), an application for its own kinds. The
 * guest tree operations (`ShareNodesHandler`) ask it on both sides of a move: the source parent's owner lets the child
 * out, the destination's lets it in; a rename asks the parent's owner, a create the parent's. It is asked in addition
 * to `NodeKindRules` (where a kind may live at all), never instead of it, for a `READ_WRITE` guest — and for a
 * `WRITE_ONLY` one's only tree operation, a create inside its own identity folder (ADR 0080 decision 11), asked as
 * a `READ_WRITE` guest's there and, on top, of the root kind's owner ([WriteOnlyGuestKinds]: which kinds that guest may
 * create at all); a `WRITE_ONLY` guest places nothing.
 *
 * **Exactly one implementation per kind, and none is the default** — the partition of `NodeKindRules` and
 * [ShareRoots]. A container kind no implementation handles, or one two implementations claim, allows a guest nothing
 * (`illegal_scope`), rather than letting CDI bean order choose; so a new application never hands its guests tree
 * rights by accident, and Memories, which declares nothing, keeps a guest out of the inside of a Memory or a chapter.
 * Reading is never asked here: a guest reads what its keys open.
 */
interface GuestNodeRules {
    /** Whether this implementation answers for containers of [containerKind]. */
    fun handles(containerKind: String): Boolean

    /** Whether a `READ_WRITE` guest may do [op] in a container of [containerKind] — one this implementation [handles]. */
    fun readWriteAllows(containerKind: String, op: GuestNodeOp): Boolean

    companion object {
        /** The one rule of [rules] that handles [containerKind] decides; none or several → not allowed. */
        fun readWriteAllows(rules: List<GuestNodeRules>, containerKind: String, op: GuestNodeOp): Boolean =
            rules.filter { it.handles(containerKind) }.singleOrNull()?.readWriteAllows(containerKind, op) ?: false
    }
}

/**
 * Core's own container (ADR 0080 decision 6): inside a `FOLDER` a `READ_WRITE` guest **creates** a `FOLDER`, or a kind
 * its own application declares guest-creatable ([GuestCreatableKinds]: Files' `FILE`) — never a Memory, a drop box or
 * any other application's node (owner decision 2026-10-14); it **moves in, moves out and renames** any kind. Either way
 * `NodeKindRules` then decides whether that kind may live there.
 *
 * It also holds the declarations to their partition at startup ([GuestCreatableKinds.requireDisjoint]): a kind two
 * applications declare fails the boot.
 */
@ApplicationScoped
class FolderGuestNodeRules(
    /** `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor). */
    @All private val creatable: MutableList<GuestCreatableKinds>,
) : GuestNodeRules {
    override fun handles(containerKind: String): Boolean = containerKind == NodeEntity.FOLDER

    override fun readWriteAllows(containerKind: String, op: GuestNodeOp): Boolean = when (op) {
        is GuestNodeOp.CreateChild -> op.childKind == NodeEntity.FOLDER || GuestCreatableKinds.allows(creatable, op.childKind)
        is GuestNodeOp.MoveChildOut, is GuestNodeOp.MoveChildIn, is GuestNodeOp.RenameChild -> true
    }

    /** Uncaught on purpose: an exception out of a `StartupEvent` observer fails startup. */
    fun onStart(@Observes ev: StartupEvent) {
        GuestCreatableKinds.requireDisjoint(creatable.associate { ClientProxy.unwrap(it).javaClass.name to it.kinds() })
    }
}
