package me.raddatz.veyq.mobile.tree

/**
 * One generation of the tree a session reads, as the node tree's rules read it: every node's id, parent, kind, name,
 * creation instant and whether it is a drop box guest's identity folder (ADR 0080 decision 11) — never a key, a sealed
 * byte or a slug token. Indexed by id and by parent once, so a rule that walks ancestry or reads a place's children does
 * not scan the tree per step. A session builds one when it replaces its node list and drops it with the list (the web's
 * `treeStore` and a guest's tree source; the apps per call over the tree they read).
 *
 * Bounded against a served parent cycle: no walk up takes more steps than the tree has nodes.
 */
class TreeFacts(val nodes: List<Node>) {
    /**
     * A node: [createdAt] the server's creation instant in epoch milliseconds (null where none is served — a guest's), a
     * `Double` so no `Long` runs on JavaScript; [identity] a drop box guest's identity folder.
     */
    class Node(val id: String, val parentId: String?, val kind: String, val name: String, val createdAt: Double?, val identity: Boolean) {
        /** Its token input under [inputOf], folded once ([TreeFacts.tokenInput]). */
        internal var input: String? = null
        internal var inputOf: KindFacts? = null
    }

    private val byId = HashMap<String, Node>(nodes.size * 2)
    private val byParent = HashMap<String?, ArrayList<Node>>()

    init {
        for (n in nodes) {
            if (byId[n.id] == null) byId[n.id] = n
            byParent.getOrPut(n.parentId) { ArrayList() }.add(n)
        }
    }

    /** The node [id], or null where the session does not read it. */
    fun node(id: String): Node? = byId[id]

    /** The children of [parentId] (null: the scope root), in the order the nodes were given. */
    fun children(parentId: String?): List<Node> = byParent[parentId] ?: emptyList()

    /** Whether [id] (null: the scope root, never) is a guest's identity folder or lies below one. */
    fun inIdentityFolder(id: String?): Boolean {
        var n = id?.let { node(it) }
        var steps = 0
        while (n != null && steps++ <= nodes.size) {
            if (n.identity) return true
            n = n.parentId?.let { node(it) }
        }
        return false
    }

    /** [node]'s token input under [kinds] ([KindFacts.tokenInput]), folded once per node and kind table. */
    internal fun tokenInput(kinds: KindFacts, node: Node): String {
        if (node.inputOf !== kinds) {
            node.input = kinds.tokenInput(node.kind, node.name)
            node.inputOf = kinds
        }
        return node.input!!
    }
}
