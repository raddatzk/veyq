package me.raddatz.veyq.mobile.tree

/**
 * A member's sibling pre-check (inbox #389, ADR 0080 § a name's slug is folded by its node's kind): before a create, a
 * rename or a move is sent, the tree read is searched for a sibling the new name collides with, which is then named. It
 * compares what the server compares — siblings' slug tokens share the parent's key, so equal tokens are equal token inputs
 * ([KindFacts.tokenInput]): each name folded by ITS OWN kind's folding, behind its application's route slug. So only
 * siblings of one route group meet (ADR 0080 decision 16): a Memory and a Pot `Rom` coexist; among the plain names a folder
 * `a.b` (`a-b`) and a file `a.b` (`a.b`) coexist, a folder `a.b` and a file `a-b` collide. The server's `409 slug_taken`
 * stays the race backstop (a sibling created since the tree was read, or one this member cannot open). Held by
 * `contracts/platform/tree/v1-vectors.json`'s `collision`.
 */
object SiblingNames {
    /**
     * The first child of [parentId] (null: the scope root) in [tree]'s order whose token input equals the one [name] would
     * have for a node of [kind], or null; [self] is the node being renamed or moved, which never collides with itself.
     */
    fun colliding(kinds: KindFacts, tree: TreeFacts, parentId: String?, kind: String, name: String, self: String?): TreeFacts.Node? {
        val input = kinds.tokenInput(kind, name)
        for (n in tree.children(parentId)) if (n.id != self && tree.tokenInput(kinds, n) == input) return n
        return null
    }

    /**
     * The same rule over [siblings] the caller already holds (one parent's children), folded by [tokenInput] — the apps'
     * registry, which answers null for a kind this build does not know (it then meets nothing).
     */
    fun <T> colliding(
        siblings: List<T>, id: (T) -> String, kindOf: (T) -> String, nameOf: (T) -> String,
        kind: String, name: String, self: String?, tokenInput: (kind: String, name: String) -> String?,
    ): T? {
        val input = tokenInput(kind, name) ?: return null
        return siblings.firstOrNull { id(it) != self && tokenInput(kindOf(it), nameOf(it)) == input }
    }
}
