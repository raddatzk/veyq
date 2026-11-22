package me.raddatz.veyq.mobile.tree

/**
 * One generation of the tree a session reads, as the node tree's rules read it: every node's id, parent, kind, name,
 * creation instant and whether it is a drop box guest's identity folder (ADR 0080 decision 11) — never a key, a sealed
 * byte or a slug token. Indexed by id and by parent once, so a rule that walks ancestry or reads a place's children does
 * not scan the tree per step. A session builds one when it replaces its node list and drops it with the list (the web's
 * `treeStore` and a guest's tree source; the apps per call over the tree they read).
 *
 * Bounded against a served parent cycle: every walk up stops at a node it has seen.
 */
class TreeFacts(val nodes: List<Node>) {
    /**
     * A node: [createdAt] the server's creation instant in epoch milliseconds (null where none is served — a guest's), a
     * `Double` so no `Long` runs on JavaScript; [identity] a drop box guest's identity folder.
     */
    class Node(val id: String, val parentId: String?, val kind: String, val name: String, val createdAt: Double?, val identity: Boolean)

    private val byId: HashMap<String, Node> by lazy {
        HashMap<String, Node>(nodes.size * 2).also { m -> for (n in nodes) if (n.id !in m) m[n.id] = n }
    }
    private val byParent: HashMap<String?, ArrayList<Node>> by lazy {
        val m = HashMap<String?, ArrayList<Node>>()
        for (n in nodes) m.getOrPut(n.parentId) { ArrayList() }.add(n)
        m
    }

    /** The node [id], or null where the session does not read it. */
    fun node(id: String): Node? = byId[id]

    /** The children of [parentId] (null: the scope root), in the order the nodes were given. */
    fun children(parentId: String?): List<Node> = byParent[parentId] ?: emptyList()

    /** Whether [id] (null: the scope root, never) is a guest's identity folder or lies below one. */
    fun inIdentityFolder(id: String?): Boolean {
        var n = id?.let { node(it) }
        val seen = HashSet<String>()
        while (n != null && seen.add(n.id)) {
            if (n.identity) return true
            n = n.parentId?.let { node(it) }
        }
        return false
    }

    private var inputsOf: KindFacts? = null
    private var inputs: HashMap<String, String>? = null

    /** [node]'s token input under [kinds] ([KindFacts.tokenInput]), folded once per generation and kind table. */
    internal fun tokenInput(kinds: KindFacts, node: Node): String {
        if (inputsOf !== kinds) {
            inputsOf = kinds
            inputs = HashMap()
        }
        val held = inputs!!
        return held[node.id] ?: kinds.tokenInput(node.kind, node.name).also { held[node.id] = it }
    }
}
