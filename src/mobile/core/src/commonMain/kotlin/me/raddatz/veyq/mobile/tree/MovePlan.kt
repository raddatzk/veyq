package me.raddatz.veyq.mobile.tree

/**
 * The move's plan (inbox #358, #421; the owner's browser of 2026-10-14), one for every client: where the browser opens
 * ([start]), the trail up to the top of what the session reads ([trail]: a member's scope root, a guest's link root), the
 * places it lists at a level ([places]: the children one may go into or move into) and whether "Move here" may be chosen
 * there ([hereBlock], [hereBlockOf]), and before anything is sent what of a selection goes and what is left out ([plan]).
 * Every function takes the moved nodes as a list ([TreeFacts.Node]s as the caller holds them, a name read by [plan] only): a selection moves through the same browser, and a place is offered only
 * where it takes every one of them. A copy (uc.platform.copy-node) places through the same browser and may go where its
 * originals are.
 *
 * **The places are a hint; the server decides.** They mirror the server's rules so only what would be taken is offered:
 * each kind's placement (`placement`, [KindFacts.placementAllows] or the apps' registry), never a node itself or anything below it (a cycle), never a guest's
 * identity folder or anything inside one (nothing is placed into one, ADR 0080 decision 11), and for a guest only a folder
 * (the one container that lets a guest's child in). Held by `contracts/platform/tree/v1-vectors.json`'s move sections.
 */
object MovePlan {
    /** Why "Move here" cannot be chosen at a place: a moved node is the place itself (for [SELF] and [INSIDE] also why a
     *  listed place cannot be opened). The words the web's string unions spell, constants rather than an enum (the web's
     *  module carries no enum's machinery for them). */
    const val SELF = "self"

    /** The place lies below a moved node (a cycle). */
    const val INSIDE = "inside"

    /** Every moved node is already there (a move only); also why a node of a selection is left out ([plan]). */
    const val CURRENT = "current"

    /** The place is a guest's identity folder or lies in one. */
    const val IDENTITY_FOLDER = "identityFolder"

    /** The place does not take a moved node's kind (for a guest: it is no folder). */
    const val KIND = "kind"

    /** A node of a selection whose name a sibling at the destination takes ([plan]). */
    const val NAME_TAKEN = "nameTaken"

    /** Whether [id] is one of [nodes] ([SELF]) or lies below one ([INSIDE]); bounded against a served cycle. */
    private fun within(tree: TreeFacts, nodes: List<TreeFacts.Node>, id: String): String? {
        if (nodes.any { it.id == id }) return SELF
        var n = tree.node(id)
        var steps = 0
        while (n != null && steps++ <= tree.nodes.size) {
            val at = n.id
            if (nodes.any { it.id == at }) return INSIDE
            n = n.parentId?.let { tree.node(it) }
        }
        return null
    }

    private fun allHere(nodes: List<TreeFacts.Node>, at: String?): Boolean = nodes.isNotEmpty() && nodes.all { it.parentId == at }

    /**
     * Why [nodes] cannot be moved — or, with [copy], copied — to [at] (null: the scope root), or null where they can. [guest]:
     * the session is a link's guest, who places into a folder only.
     */
    fun hereBlock(placement: (kind: String, parentKind: String?) -> Boolean, tree: TreeFacts, nodes: List<TreeFacts.Node>, guest: Boolean, at: String?, copy: Boolean = false): String? {
        if (at != null) {
            within(tree, nodes, at)?.let { return it }
            if (tree.inIdentityFolder(at)) return IDENTITY_FOLDER
        }
        if (!copy && allHere(nodes, at)) return CURRENT
        // a place the tree does not read takes nothing (fail closed; the web's TypeScript asked it as the scope root)
        val kind = if (at == null) null else (tree.node(at)?.kind ?: return KIND)
        if (!nodes.all { placement(it.kind, kind) }) return KIND
        if (guest && kind != KindFacts.FOLDER) return KIND
        return null
    }

    /** Why several [nodes] cannot be moved to [at], and the one that blocks it ([node] null: all of them are there already). */
    class BlockOf(val block: String, val node: TreeFacts.Node?)

    /** Why several [nodes] cannot be moved to [at]; a node already there blocks nothing ([plan] leaves it out). */
    fun hereBlockOf(placement: (kind: String, parentKind: String?) -> Boolean, tree: TreeFacts, nodes: List<TreeFacts.Node>, guest: Boolean, at: String?, copy: Boolean = false): BlockOf? {
        if (!copy && allHere(nodes, at)) return BlockOf(CURRENT, null)
        for (node in nodes) {
            val block = hereBlock(placement, tree, listOf(node), guest, at, copy)
            if (block != null && block != CURRENT) return BlockOf(block, node)
        }
        return null
    }

    /** Why one node of a selection is left out before anything is sent: [CURRENT] (already there) or [NAME_TAKEN] by [sibling]. */
    class LeftOut(val node: TreeFacts.Node, val reason: String, val sibling: TreeFacts.Node?)

    class Plan(val send: List<TreeFacts.Node>, val leftOut: List<LeftOut>)

    /**
     * The pre-check of a selection's move to [to]: what is sent, in the given order, and what is left out — a node already
     * there, or one whose name folds to a sibling's there ([SiblingNames.colliding], each by its own kind). The siblings'
     * inputs are folded once for the whole selection.
     */
    fun plan(kinds: KindFacts, tree: TreeFacts, nodes: List<TreeFacts.Node>, to: String?): Plan {
        val send = ArrayList<TreeFacts.Node>()
        val leftOut = ArrayList<LeftOut>()
        val there = tree.children(to)
        // the first sibling of each input, and the second where the first is a moved node itself (it never meets itself)
        val firstOf = HashMap<String, ArrayList<TreeFacts.Node>>()
        for (n in there) {
            val held = firstOf.getOrPut(tree.tokenInput(kinds, n)) { ArrayList(1) }
            if (held.size < 2) held.add(n)
        }
        for (node in nodes) {
            if (node.parentId == to) {
                leftOut += LeftOut(node, CURRENT, null)
                continue
            }
            val sibling = firstOf[kinds.tokenInput(node.kind, node.name)]?.firstOrNull { it.id != node.id }
            if (sibling != null) leftOut += LeftOut(node, NAME_TAKEN, sibling) else send += node
        }
        return Plan(send, leftOut)
    }

    /**
     * Where the browser opens: the place the nodes are in (every one the same), else [top] — a member's scope root (null), a
     * guest's link root. A place this session does not read (a guest's node whose parent lies above the link) is the top too.
     */
    fun start(tree: TreeFacts, top: String?, nodes: List<TreeFacts.Node>): String? {
        val parent = nodes.firstOrNull()?.parentId
        if (parent == null || nodes.any { it.parentId != parent }) return top
        return if (tree.node(parent) != null) parent else top
    }

    /** The trail from [top] down to [at], both included: the breadcrumb's places (null: the scope root). A chain that does
     *  not reach the top (bounded against a served cycle) still starts there. */
    fun trail(tree: TreeFacts, top: String?, at: String?): List<String?> {
        val trail = ArrayList<String?>()
        var id = at
        while (id != null && id != top && trail.size <= tree.nodes.size && id !in trail) {
            trail.add(0, id)
            id = tree.node(id)?.parentId
        }
        trail.add(0, top)
        return trail
    }

    /** One place the browser lists: a child of the place on screen; [blocked] ([SELF], [INSIDE]) says it cannot be opened. */
    class Place(val node: TreeFacts.Node, val blocked: String?)

    /**
     * The places listed at [at]: the children one may go into or move into, in the tree's order (the view sorts them as the
     * level does). A folder always (the way down), for a member also a node whose kind takes every moved node (a Memory for
     * a chapter); never a guest's identity folder. The moved nodes themselves and anything below them are listed blocked.
     */
    fun places(placement: (kind: String, parentKind: String?) -> Boolean, tree: TreeFacts, nodes: List<TreeFacts.Node>, guest: Boolean, at: String?): List<Place> {
        val out = ArrayList<Place>()
        for (child in tree.children(at)) {
            val takes = child.kind == KindFacts.FOLDER ||
                (!guest && nodes.isNotEmpty() && nodes.all { placement(it.kind, child.kind) })
            if (takes && !tree.inIdentityFolder(child.id)) out += Place(child, within(tree, nodes, child.id))
        }
        return out
    }

    /** Whether moving a node ends a drop box guest's folder (ADR 0080 decision 11): its identity link goes with the move,
     *  the guest adds nothing to it any more, and no move brings it back into the drop box (no undo) — asked before sending. */
    fun endsIdentity(tree: TreeFacts, id: String): Boolean = tree.node(id)?.identity == true
}
