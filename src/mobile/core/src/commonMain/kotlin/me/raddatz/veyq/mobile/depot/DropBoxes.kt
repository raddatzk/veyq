package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * A drop box as a member reads it (Depot's `DropBoxDetail.vue`, `docs/apps/depot.md` § The drop box): its guests' folders
 * — each the FOLDER a link's guest made under it, its entry under the guest's own identity key (core's `Tree`) —, in the
 * order they were made (`compareByCreation`), a name more than one guest gave numbered as the web numbers it ("Anna",
 * "Anna (2)", `displayNamesOf`), each with the files under it.
 */
object DropBoxes {
    /** One guest's folder in a drop box: its node, the name it shows under, the files in it. */
    class GuestFolder(val node: OpenNode, val label: String, val files: List<OpenNode>)

    /** The guests' folders of drop box [dropBoxId] in [scopeId], as its page lists them. */
    @Throws(Exception::class)
    suspend fun folders(tenant: TenantSession, scopeId: String, dropBoxId: String): List<GuestFolder> {
        val nodes = tenant.trees()[scopeId].orEmpty()
        val folders = nodes.filter { it.parentId == dropBoxId && it.kind == "FOLDER" }.sortedWith(::compareByCreation)
        val labels = labels(folders.map { it.id to it.name })
        return folders.map { f -> GuestFolder(f, labels.getValue(f.id), nodes.filter { it.parentId == f.id && it.kind == DepotFiles.FILE }) }
    }

    /** The web's `compareByCreation`: by the instant the server made them, then by id. */
    internal fun compareByCreation(a: OpenNode, b: OpenNode): Int {
        val c = (a.createdAt ?: "").compareTo(b.createdAt ?: "")
        return if (c != 0) c else a.id.compareTo(b.id)
    }

    /** The web's `displayNamesOf`, over [ordered] (id, name) pairs in their order: a repeated name gains " (n)". */
    fun labels(ordered: List<Pair<String, String>>): Map<String, String> {
        val seen = HashMap<String, Int>()
        return ordered.associate { (id, name) ->
            val count = (seen[name] ?: 0) + 1
            seen[name] = count
            id to if (count == 1) name else "$name ($count)"
        }
    }
}
