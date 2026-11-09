package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A node and everything under it copied within its space (uc.platform.copy-node), the web's `core/export/copySubtree.ts`
 * replayed as `importSubtree.ts` replays it: planned first — the kinds this build copies, the rest left out with what is
 * under them and said ([NodeExport.Skipped]) —, then created top-down, a folder as core's new folder, a kind that copies
 * as one ([Kinds.asFolder]: Depot's drop box, the web's `importsAsFolder`) too, and every other kind by its application's
 * copy ([Kinds.duplicate]: Expenses' `PotCopy`, Depot's file uploaded again), each under fresh keys. The copy's root takes
 * the original's name, numbered past a sibling's (`name (2)` … `(50)`, the web's `MAX_NAME_TRIES`, a file's whole name
 * included); what is under it keeps its names. A failure part-way moves what was made to the trash (the web's rollback),
 * so nothing half-copied stays.
 */
object SubtreeCopy {
    const val NOT_COPYABLE = "not_copyable"
    const val MAX_NAME_TRIES = 50

    /**
     * How the build's kinds copy (the composition root's): which kinds copy ([copies]; a folder always does), which become a
     * plain folder ([asFolder]), and the copy of one node ([duplicate]: the original, the scope, the new parent and the name
     * to give it; what was made). Not `copy`: Swift would read NSObject's.
     */
    class Kinds(
        val copies: (String) -> Boolean,
        val duplicate: suspend (TenantSession, OpenNode, String, String?, String) -> Made,
        val asFolder: (String) -> Boolean = { false },
    )

    /** One node's copy: its id, or none — [nameTaken] where something there already holds the name, else it failed. */
    class Made(val nodeId: String?, val nameTaken: Boolean = false)

    enum class Outcome { COPIED, NOTHING_TO_COPY, NAME_TAKEN, FAILED }

    class Copied(val outcome: Outcome, val nodeId: String? = null, val name: String? = null, val leftOut: List<NodeExport.Skipped> = emptyList())

    private class Planned(val node: OpenNode, val children: List<Planned>)

    /** What a copy would make — the [kinds] it copies, whose losses the dialog names — and leave out ([leftOut]). */
    class Preview(val kinds: Set<String>, val leftOut: List<NodeExport.Skipped>)

    /** What a copy of [rootId] would make and leave out, before anything is written (the web's dialog lists it first). */
    @Throws(Exception::class)
    suspend fun preview(tenant: TenantSession, scopeId: String, rootId: String, kinds: Kinds): Preview {
        val (planned, left) = plan(tenant, scopeId, rootId, kinds) ?: return Preview(emptySet(), emptyList())
        fun all(p: Planned): List<String> = listOf(p.node.kind) + p.children.flatMap { all(it) }
        return Preview(all(planned).toSet(), left)
    }

    private suspend fun plan(tenant: TenantSession, scopeId: String, rootId: String, kinds: Kinds): Pair<Planned, List<NodeExport.Skipped>>? {
        val nodes = tenant.trees()[scopeId] ?: return null
        val root = nodes.firstOrNull { it.id == rootId } ?: return null
        val children = nodes.groupBy { it.parentId }
        val left = mutableListOf<NodeExport.Skipped>()
        fun build(n: OpenNode): Planned? {
            if (n.kind != NodeRef.FOLDER && !kinds.copies(n.kind)) {
                left += NodeExport.Skipped(n.name, n.kind, NOT_COPYABLE)
                return null
            }
            val under = children[n.id].orEmpty().sortedWith { a, b -> NodeExport.naturalCompare(a.name, b.name) }.mapNotNull { build(it) }
            return Planned(n, under)
        }
        return build(root)?.let { it to left }
    }

    /** Copies folder [rootId] of [scopeId] under [parentId] (null: the space's top), with everything under it [kinds] copies. */
    @Throws(Exception::class)
    suspend fun duplicate(tenant: TenantSession, scopeId: String, rootId: String, parentId: String?, kinds: Kinds): Copied {
        val (planned, left) = plan(tenant, scopeId, rootId, kinds) ?: return Copied(Outcome.NOTHING_TO_COPY)
        // the root: the original's name, numbered past a sibling's
        var top: String? = null
        var topName: String? = null
        for (i in 1..MAX_NAME_TRIES) {
            val name = if (i == 1) planned.node.name else "${planned.node.name} ($i)"
            val made = make(tenant, scopeId, planned.node, parentId, name, kinds)
            if (made.nameTaken) continue
            top = made.nodeId ?: return Copied(Outcome.FAILED, leftOut = left)
            topName = name
            break
        }
        val made = top ?: return Copied(Outcome.NAME_TAKEN, leftOut = left)
        val ok = runCatching { replay(tenant, scopeId, planned.children, made, kinds) }.getOrDefault(false)
        tenant.forgetTree()
        if (!ok) {
            // the web's rollback: what was made goes to the trash with everything under it
            runCatching { NodeTrash.delete(tenant, scopeId, made) }
            tenant.forgetTree()
            return Copied(Outcome.FAILED, leftOut = left)
        }
        return Copied(Outcome.COPIED, made, topName, left)
    }

    /** Each of [nodes] made under [parentId], then what is under it; false at the first that did not go through. */
    private suspend fun replay(tenant: TenantSession, scopeId: String, nodes: List<Planned>, parentId: String, kinds: Kinds): Boolean {
        for (p in nodes) {
            val made = make(tenant, scopeId, p.node, parentId, p.node.name, kinds).nodeId ?: return false
            if (!replay(tenant, scopeId, p.children, made, kinds)) return false
        }
        return true
    }

    /** One node's copy named [name] under [parentId]: a folder (or a kind copied as one) core's new folder, else its kind's. */
    private suspend fun make(tenant: TenantSession, scopeId: String, node: OpenNode, parentId: String?, name: String, kinds: Kinds): Made {
        if (node.kind != NodeRef.FOLDER && !kinds.asFolder(node.kind)) return kinds.duplicate(tenant, node, scopeId, parentId, name)
        return when (val r = FolderCreate.create(tenant, scopeId, parentId, name)) {
            is NodeCreate.Result.Done -> Made(r.created.nodeId)
            is NodeCreate.Result.Refused -> Made(null, nameTaken = r.reason == NodeCreate.Reason.NAME_TAKEN)
        }
    }
}
