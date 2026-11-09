package me.raddatz.veyq.mobile.export

import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.FolderCreate
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A folder and everything under it copied within its space (uc.platform.copy-node), the web's `core/export/copySubtree.ts`
 * replayed as `importSubtree.ts` replays it: planned first — the kinds this build copies, the rest left out with what is
 * under them and said ([NodeExport.Skipped]) —, then created top-down, a folder as core's new folder and every other kind by
 * its application's copy ([Kinds.duplicate]: Expenses' `PotCopy`), each under fresh keys. The copy's root takes the original's
 * name, numbered past a sibling's (`name (2)` … `(50)`, the web's `MAX_NAME_TRIES`); what is under it keeps its names. A
 * failure part-way moves what was made to the trash (the web's rollback), so nothing half-copied stays.
 */
object SubtreeCopy {
    const val NOT_COPYABLE = "not_copyable"
    const val MAX_NAME_TRIES = 50

    /**
     * How the build's kinds copy (the composition root's): which kinds copy ([copies]; a folder always does) and the copy
     * of one node ([duplicate]: the original, the scope, the new parent; the new node's id, or null where it did not go
     * through). Not `copy`: Swift would read NSObject's.
     */
    class Kinds(val copies: (String) -> Boolean, val duplicate: suspend (TenantSession, OpenNode, String, String?) -> String?)

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
        if (planned.node.kind != NodeRef.FOLDER) return Copied(Outcome.NOTHING_TO_COPY, leftOut = left)
        // the root: the original's name, numbered past a sibling folder's
        var top: String? = null
        var topName: String? = null
        for (i in 1..MAX_NAME_TRIES) {
            val name = if (i == 1) planned.node.name else "${planned.node.name} ($i)"
            when (val r = FolderCreate.create(tenant, scopeId, parentId, name)) {
                is NodeCreate.Result.Done -> { top = r.created.nodeId; topName = name }
                is NodeCreate.Result.Refused -> if (r.reason == NodeCreate.Reason.NAME_TAKEN) continue else return Copied(Outcome.FAILED, leftOut = left)
            }
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
            val made = if (p.node.kind == NodeRef.FOLDER) {
                (FolderCreate.create(tenant, scopeId, parentId, p.node.name) as? NodeCreate.Result.Done)?.created?.nodeId
            } else {
                kinds.duplicate(tenant, p.node, scopeId, parentId)
            } ?: return false
            if (!replay(tenant, scopeId, p.children, made, kinds)) return false
        }
        return true
    }
}
