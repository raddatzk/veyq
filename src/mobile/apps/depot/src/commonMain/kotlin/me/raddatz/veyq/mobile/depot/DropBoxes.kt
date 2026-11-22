package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.tree.DisplayNames

/**
 * A drop box as a member reads it (Depot's `DropBoxDetail.vue`, `docs/apps/depot.md` § The drop box): its guests' folders
 * — each the FOLDER a link's guest made under it, its entry under the guest's own identity key (core's `Tree`) —, in the
 * order they were made, a name more than one guest gave numbered as the web numbers it ("Anna", "Anna (2)": the platform's
 * [DisplayNames]), each with the files under it.
 */
object DropBoxes {
    /** Each drop box's row (Depot's `people`): how many guests have a folder in it, by its node id. */
    fun people(nodes: List<me.raddatz.veyq.mobile.navigation.NodeRef>): Map<String, me.raddatz.veyq.mobile.words.Notice> =
        nodes.filter { it.kind == DepotFiles.DROPBOX }.associate { box ->
            val n = nodes.count { it.parentId == box.nodeId && it.kind == "FOLDER" }
            box.nodeId to if (n == 0) me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.DROPBOX_PEOPLE_NONE)
            else me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.DROPBOX_PEOPLE, n)
        }

    /** One guest's folder in a drop box: its node, the name it shows under, the files in it. */
    class GuestFolder(val node: OpenNode, val label: String, val files: List<OpenNode>)

    /** The guests' folders of drop box [dropBoxId] in [scopeId], as its page lists them. */
    @Throws(Exception::class)
    suspend fun folders(tenant: TenantSession, scopeId: String, dropBoxId: String): List<GuestFolder> {
        val nodes = tenant.trees()[scopeId].orEmpty()
        // in creation order (the platform's `DisplayNames`: the server's instant, then the id), numbered as the web numbers
        val made = nodes.filter { it.parentId == dropBoxId && it.kind == "FOLDER" }.map { it to it.asNode().createdAt }
        val folders = made.sortedWith { (a, ma), (b, mb) -> DisplayNames.compareByCreation(ma, a.id, mb, b.id) }.map { it.first }
        val labels = DisplayNames.of(made, { it.first.id }, { it.first.name }, { it.second }, byCreation = true)
        return folders.map { f -> GuestFolder(f, labels.getValue(f.id), nodes.filter { it.parentId == f.id && it.kind == DepotFiles.FILE }) }
    }

    enum class Outcome { CREATED, NAME_TAKEN, LINK_FAILED, FAILED }

    /** A drop box made: its node, and its first link's address to hand out once (null where the link was not made). */
    class Created(val outcome: Outcome, val nodeId: String? = null, val url: String? = null, val minted: me.raddatz.veyq.mobile.session.ShareLinks.Outcome? = null)

    /**
     * Makes a drop box named [name] under [parentId] (null: the space's top) and its first link — the web's
     * `createDropBoxWithLink`, what a folder's share sheet does for "Add files only": the `DEPOT_DROPBOX` node (its name
     * folded as a container's), then a WRITE_ONLY link on it with [password] and [expiresAt], which births its drop key. A
     * link that is not made leaves the drop box (its own sheet makes one later), as the web does.
     */
    @Throws(Exception::class)
    suspend fun create(tenant: TenantSession, scopeId: String, parentId: String?, name: String, password: String, expiresAt: String?): Created {
        val n = name.trim()
        val created = when (val r = me.raddatz.veyq.mobile.session.NodeCreate.create(tenant, scopeId, parentId, DepotFiles.DROPBOX, n, me.raddatz.veyq.mobile.crypto.Naming.slugify(n))) {
            is me.raddatz.veyq.mobile.session.NodeCreate.Result.Done -> r.created
            is me.raddatz.veyq.mobile.session.NodeCreate.Result.Refused ->
                return Created(if (r.reason == me.raddatz.veyq.mobile.session.NodeCreate.Reason.NAME_TAKEN) Outcome.NAME_TAKEN else Outcome.FAILED)
        }
        tenant.forgetTree()
        val minted = me.raddatz.veyq.mobile.session.ShareLinks.mint(tenant, scopeId, created.nodeId, me.raddatz.veyq.mobile.session.ShareLinks.WRITE_ONLY, "", password, expiresAt)
        tenant.forgetTree()
        return if (minted.outcome == me.raddatz.veyq.mobile.session.ShareLinks.Outcome.CREATED) Created(Outcome.CREATED, created.nodeId, minted.url, minted.outcome)
        else Created(Outcome.LINK_FAILED, created.nodeId, minted = minted.outcome)
    }

}
