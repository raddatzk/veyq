package me.raddatz.veyq.mobile.tree

/**
 * The move's plan (inbox #358, #421; the owner's browser of 2026-10-14), one for every client: where the browser opens
 * ([start]), the trail up to the top of what the session reads ([trail]: a member's scope root, a guest's link root), the
 * places it lists at a level ([places]: the children one may go into or move into) and whether "Move here" may be chosen
 * there ([hereBlock], [hereBlockOf]), and before anything is sent what of a selection goes and what is left out ([plan]).
 * Every function takes the moved nodes as a list: a selection moves through the same browser, and a place is offered only
 * where it takes every one of them. A copy (uc.platform.copy-node) places through the same browser and may go where its
 * originals are.
 *
 * **The places are a hint; the server decides.** They mirror the server's rules so only what would be taken is offered:
 * each kind's placement (`placement`, [KindFacts.placementAllows] or the apps' registry), never a node itself or anything below it (a cycle), never a guest's
 * identity folder or anything inside one (nothing is placed into one, ADR 0080 decision 11), and for a guest only a folder
 * (the one container that lets a guest's child in). Held by `contracts/platform/tree/v1-vectors.json`'s move sections.
 */
object MovePlan {
    /** Why "Move here" cannot be chosen at a place (for [SELF] and [INSIDE] also why a listed place cannot be opened). */
    enum class Block(val word: String) {
        /** The place is a moved node itself. */
        SELF("self"),
        /** The place lies below a moved node (a cycle). */
        INSIDE("inside"),
        /** Every moved node is already there (a move only). */
        CURRENT("current"),
        /** The place is a guest's identity folder or lies in one. */
        IDENTITY_FOLDER("identityFolder"),
        /** The place does not take a moved node's kind (for a guest: it is no folder). */
        KIND("kind"),
    }

    /** A moved node as the plan reads it: [name] only where [plan] folds it. */
    class Moved(val id: String, val kind: String, val parentId: String?, val name: String = "")

    /** Whether [id] is one of [nodes] ([Block.SELF]) or lies below one ([Block.INSIDE]); bounded against a served cycle. */
    private fun within(tree: TreeFacts, nodes: List<Moved>, id: String): Block? {
        if (nodes.any { it.id == id }) return Block.SELF
        val seen = HashSet<String>()
        var n = tree.node(id)
        while (n != null && seen.add(n.id)) {
            val at = n.id
            if (nodes.any { it.id == at }) return Block.INSIDE
            n = n.parentId?.let { tree.node(it) }
        }
        return null
    }

    private fun allHere(nodes: List<Moved>, at: String?): Boolean = nodes.isNotEmpty() && nodes.all { it.parentId == at }

    /**
     * Why [nodes] cannot be moved — or, with [copy], copied — to [at] (null: the scope root), or null where they can. [guest]:
     * the session is a link's guest, who places into a folder only.
     */
    fun hereBlock(placement: (kind: String, parentKind: String?) -> Boolean, tree: TreeFacts, nodes: List<Moved>, guest: Boolean, at: String?, copy: Boolean = false): Block? {
        if (at != null) {
            within(tree, nodes, at)?.let { return it }
            if (tree.inIdentityFolder(at)) return Block.IDENTITY_FOLDER
        }
        if (!copy && allHere(nodes, at)) return Block.CURRENT
        // a place the tree does not read takes nothing (fail closed; the web's TypeScript asked it as the scope root)
        val kind = if (at == null) null else (tree.node(at)?.kind ?: return Block.KIND)
        if (!nodes.all { placement(it.kind, kind) }) return Block.KIND
        if (guest && kind != KindFacts.FOLDER) return Block.KIND
        return null
    }

    /** Why several [nodes] cannot be moved to [at], and the one that blocks it ([node] null: all of them are there already). */
    class BlockOf(val block: Block, val node: Moved?)

    /** Why several [nodes] cannot be moved to [at]; a node already there blocks nothing ([plan] leaves it out). */
    fun hereBlockOf(placement: (kind: String, parentKind: String?) -> Boolean, tree: TreeFacts, nodes: List<Moved>, guest: Boolean, at: String?, copy: Boolean = false): BlockOf? {
        if (!copy && allHere(nodes, at)) return BlockOf(Block.CURRENT, null)
        for (node in nodes) {
            val block = hereBlock(placement, tree, listOf(node), guest, at, copy)
            if (block != null && block != Block.CURRENT) return BlockOf(block, node)
        }
        return null
    }

    /** Why one node of a selection is left out before anything is sent: already there, or a sibling there takes its name. */
    enum class Reason(val word: String) { CURRENT("current"), NAME_TAKEN("nameTaken") }

    class LeftOut(val node: Moved, val reason: Reason, val sibling: TreeFacts.Node?)

    class Plan(val send: List<Moved>, val leftOut: List<LeftOut>)

    /**
     * The pre-check of a selection's move to [to]: what is sent, in the given order, and what is left out — a node already
     * there, or one whose name folds to a sibling's there ([SiblingNames.colliding], each by its own kind). The siblings'
     * inputs are folded once for the whole selection.
     */
    fun plan(kinds: KindFacts, tree: TreeFacts, nodes: List<Moved>, to: String?): Plan {
        val send = ArrayList<Moved>()
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
                leftOut += LeftOut(node, Reason.CURRENT, null)
                continue
            }
            val sibling = firstOf[kinds.tokenInput(node.kind, node.name)]?.firstOrNull { it.id != node.id }
            if (sibling != null) leftOut += LeftOut(node, Reason.NAME_TAKEN, sibling) else send += node
        }
        return Plan(send, leftOut)
    }

    /**
     * Where the browser opens: the place the nodes are in (every one the same), else [top] — a member's scope root (null), a
     * guest's link root. A place this session does not read (a guest's node whose parent lies above the link) is the top too.
     */
    fun start(tree: TreeFacts, top: String?, nodes: List<Moved>): String? {
        val parent = nodes.firstOrNull()?.parentId
        if (parent == null || nodes.any { it.parentId != parent }) return top
        return if (tree.node(parent) != null) parent else top
    }

    /** The trail from [top] down to [at], both included: the breadcrumb's places (null: the scope root). A chain that does
     *  not reach the top (bounded against a served cycle) still starts there. */
    fun trail(tree: TreeFacts, top: String?, at: String?): List<String?> {
        val trail = ArrayList<String?>()
        val seen = HashSet<String>()
        var id = at
        while (id != null && id != top && seen.add(id)) {
            trail.add(id)
            id = tree.node(id)?.parentId
        }
        trail.add(top)
        trail.reverse()
        return trail
    }

    /** One place the browser lists: a child of the place on screen; [blocked] ([Block.SELF], [Block.INSIDE]) says it cannot be opened. */
    class Place(val node: TreeFacts.Node, val blocked: Block?)

    /**
     * The places listed at [at]: the children one may go into or move into, in the tree's order (the view sorts them as the
     * level does). A folder always (the way down), for a member also a node whose kind takes every moved node (a Memory for
     * a chapter); never a guest's identity folder. The moved nodes themselves and anything below them are listed blocked.
     */
    fun places(placement: (kind: String, parentKind: String?) -> Boolean, tree: TreeFacts, nodes: List<Moved>, guest: Boolean, at: String?): List<Place> {
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
