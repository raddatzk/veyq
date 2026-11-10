package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.navigation.NodeRef

/**
 * A node moved elsewhere in its space, core's for every kind (the web's `core/node/move.ts` and `moveNode`, inbox #358): a
 * placement under another parent ([NodePlacement]) — its own key unchanged, only its wrap, name and slug token sealed anew
 * under the destination's key, so everything below it keeps opening. There is no move to another space (the server's "no
 * cross-scope move").
 *
 * **The browser** (owner decision 2026-10-14): it opens at the node's place ([start]), shows one place at a time with a
 * trail up to the scope root ([trail]), lists only the children one may go into or move into ([places]: a folder, the way
 * down, or a node whose kind takes the moved one), and says per place why "Move here" cannot be chosen ([hereBlock]). The
 * places mirror the server's rules — the kind's placement, which each application answers ([placementAllows]), and no cycle —
 * and the server decides; its refusals are worded by [Outcome].
 */
object NodeMove {
    /** Why "Move here" cannot be chosen at a place (for [ITSELF] and [INSIDE] also why a listed place cannot be opened). */
    enum class Block {
        /** The place is the moved node itself. */
        ITSELF,
        /** The place lies below the moved node (a cycle). */
        INSIDE,
        /** The node is already there. */
        CURRENT,
        /** The place does not take the node's kind. */
        KIND,
        /** The place is a guest's identity folder or lies in one: nothing is placed there (ADR 0080 decision 11). */
        IDENTITY_FOLDER,
    }

    /** One place the browser lists at a level: a child there, and why it cannot be opened (null: it can). */
    class Place(val node: NodeRef, val blocked: Block?)

    /** What a blocked place in the list says: the moved node itself, else that it lies inside it. */
    fun placeWord(block: Block): me.raddatz.veyq.mobile.words.Msg = if (block == Block.ITSELF) me.raddatz.veyq.mobile.words.Msg.MOVE_BLOCKED_SELF else me.raddatz.veyq.mobile.words.Msg.MOVE_BLOCKED_INSIDE

    /** Why the place shown takes nothing ([copy]: the copy dialog's words for a guest's folder). */
    fun hereWord(block: Block, copy: Boolean): me.raddatz.veyq.mobile.words.Msg = when (block) {
        Block.ITSELF -> me.raddatz.veyq.mobile.words.Msg.MOVE_HERE_SELF
        Block.INSIDE -> me.raddatz.veyq.mobile.words.Msg.MOVE_HERE_INSIDE
        Block.CURRENT -> me.raddatz.veyq.mobile.words.Msg.MOVE_HERE_CURRENT
        Block.KIND -> me.raddatz.veyq.mobile.words.Msg.MOVE_HERE_KIND
        Block.IDENTITY_FOLDER -> if (copy) me.raddatz.veyq.mobile.words.Msg.COPY_HERE_IDENTITY else me.raddatz.veyq.mobile.words.Msg.MOVE_HERE_IDENTITY
    }

    /** Whether a node of [kind] may be placed under a parent of [parentKind] (null: the scope root): each kind's placement. */
    fun interface PlacementAllows {
        fun allows(kind: String, parentKind: String?): Boolean
    }

    /** Where the browser opens: the place the node is in, else the scope root (null). */
    fun start(tree: List<NodeRef>, moved: NodeRef): String? = moved.parentId?.takeIf { p -> tree.any { it.nodeId == p } }

    /** The trail from the scope root (null) down to [at], both included: the breadcrumb's places. */
    fun trail(tree: List<NodeRef>, at: String?): List<NodeRef?> {
        val byId = tree.associateBy { it.nodeId }
        val out = ArrayList<NodeRef?>()
        val seen = HashSet<String>()
        var id = at
        while (id != null && seen.add(id)) {
            val n = byId[id] ?: break
            out.add(0, n)
            id = n.parentId
        }
        out.add(0, null)
        return out
    }

    /**
     * The places listed at [at] (null: the scope root): a folder always (the way down), else a node whose kind takes the moved
     * one, in the tree's order; the moved node itself and what lies below it are listed blocked — they cannot be opened.
     */
    fun places(tree: List<NodeRef>, moved: NodeRef, at: String?, allows: PlacementAllows): List<Place> =
        tree.filter { it.parentId == at && (it.kind == FOLDER || allows.allows(moved.kind, it.kind)) && !inIdentityFolder(tree, it.nodeId) }
            .map { Place(it, within(tree, moved, it.nodeId)) }

    /** Why [moved] cannot be moved — or, with [copy], copied — to [at] (null: the scope root), or null where it can. A copy
     *  may go where its original is (it takes the next free name there, the web's `moveHereBlockOf(…, 'copy')`). */
    fun hereBlock(tree: List<NodeRef>, moved: NodeRef, at: String?, allows: PlacementAllows, copy: Boolean = false): Block? {
        if (at != null) within(tree, moved, at)?.let { return it }
        if (inIdentityFolder(tree, at)) return Block.IDENTITY_FOLDER
        if (!copy && moved.parentId == at) return Block.CURRENT
        val kind = at?.let { a -> tree.firstOrNull { it.nodeId == a }?.kind ?: return Block.KIND }
        return if (allows.allows(moved.kind, kind)) null else Block.KIND
    }

    /** Whether [id] (null: the scope root) is a guest's identity folder or lies in one (the web's `inIdentityFolder`). */
    fun inIdentityFolder(tree: List<NodeRef>, id: String?): Boolean {
        val byId = tree.associateBy { it.nodeId }
        val seen = HashSet<String>()
        var n = id?.let { byId[it] }
        while (n != null && seen.add(n.nodeId)) {
            if (n.identity) return true
            n = n.parentId?.let { byId[it] }
        }
        return false
    }

    /** Whether moving [node] ends a guest's folder (the web's `endsIdentity`): its identity link goes with the move, the
     *  guest adds nothing to it any more, and nothing brings it back into the drop box — asked before it is sent. */
    fun endsIdentity(node: NodeRef): Boolean = node.identity

    /** Whether [id] is [moved] or lies below it (bounded: a served parent cycle stops instead of spinning). */
    private fun within(tree: List<NodeRef>, moved: NodeRef, id: String): Block? {
        if (id == moved.nodeId) return Block.ITSELF
        val byId = tree.associateBy { it.nodeId }
        val seen = HashSet<String>()
        var n = byId[id]
        while (n != null && seen.add(n.nodeId)) {
            if (n.nodeId == moved.nodeId) return Block.INSIDE
            n = n.parentId?.let { byId[it] }
        }
        return null
    }

    enum class Outcome {
        MOVED,
        /** Refused before sending ([hereBlock]): the node is there already, or the place cannot hold it. */
        BLOCKED,
        /** Something there folds to its name — before sending (the loaded tree), or as the server answers `slug_taken`. */
        NAME_TAKEN,
        /** The place does not take this kind of node (`422 illegal_structure`). */
        NOT_ALLOWED,
        /** It, or the place chosen, is no longer there. */
        GONE,
        /** Someone else moved or renamed it at the same time, again after the tree was read anew. */
        CONFLICT,
        /** The space is being deleted: nothing is moved in it. */
        SCOPE_DELETING,
        /** Not re-keyed since its space's key changed, and still not after waiting. */
        BUSY,
        FAILED,
    }

    /** What came of a move: [taken] is the node at the destination its name collides with, where the loaded tree shows one. */
    class Result(val outcome: Outcome, val taken: OpenNode? = null)

    /**
     * Moves node [nodeId] of [scopeId] under [to] (null: the scope root), keeping its name, whose slug token's input
     * [tokenInput] folds as its application does; [allows] is the kinds' placement. The tree is read anew after it.
     */
    @Throws(Exception::class)
    suspend fun move(
        tenant: TenantSession,
        scopeId: String,
        nodeId: String,
        to: String?,
        tokenInput: (kind: String, name: String) -> String?,
        allows: PlacementAllows,
        waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    ): Result {
        val tree = tenant.trees()[scopeId].orEmpty()
        val node = tree.firstOrNull { it.id == nodeId } ?: return Result(Outcome.GONE)
        val refs = tree.map { it.asNode() }
        if (to != null && tree.none { it.id == to }) return Result(Outcome.GONE)
        if (hereBlock(refs, node.asNode(), to, allows) != null) return Result(Outcome.BLOCKED)
        val input = tokenInput(node.kind, node.name) ?: return Result(Outcome.FAILED)
        // A node at the destination that folds to the same input: said by name before anything is sent (`planMove`).
        tree.firstOrNull { it.parentId == to && it.id != nodeId && tokenInput(it.kind, it.name) == input }
            ?.let { return Result(Outcome.NAME_TAKEN, taken = it) }
        val ended = NodePlacement.place(tenant, scopeId, nodeId, parentOf = { to }, nameOf = { it.name }, tokenInput = tokenInput, waitsMs = waitsMs)
        return Result(
            when (ended) {
                NodePlacement.Ended.Placed -> Outcome.MOVED
                NodePlacement.Ended.Busy -> Outcome.BUSY
                NodePlacement.Ended.Gone -> Outcome.GONE
                is NodePlacement.Ended.Refused -> when {
                    ended.status == 404 -> Outcome.GONE
                    ended.status == 422 -> Outcome.NOT_ALLOWED
                    ended.status == 409 && ended.code == "slug_taken" -> Outcome.NAME_TAKEN
                    ended.status == 409 && ended.code == "stale_placement" -> Outcome.CONFLICT
                    ended.status == 409 && ended.code == "scope_deleting" -> Outcome.SCOPE_DELETING
                    else -> Outcome.FAILED
                }
            },
        )
    }

    /** The platform's container kind: the way down in the browser, wherever it may itself be placed. */
    const val FOLDER = "FOLDER"
}
