package me.raddatz.veyq.mobile.tree

/**
 * The display label of each of one parent's children (inbox #393): its name, and where several siblings carry the SAME
 * name — two drop box guests named `Anna`, whose entries sit under different guest keys, so the server's slug uniqueness
 * never sees the pair (ADR 0080 decision 11) — the first in order keeps it and the n-th reads `Anna (n)`. Display only,
 * never stored. The one numbering every view of such siblings uses (a drop box's page, a level's heading and cards, the
 * breadcrumb, a guest's folder page), so a folder is called the same in the list it is picked from and on the page it
 * opens. A member's views order by [compareByCreation] — the order the web's URL segments `anna`, `anna-2` come from —, a
 * guest's by [compareById], its resolve serving no creation instant (so the numbers a guest sees may differ from a
 * member's). Held by `contracts/platform/tree/v1-vectors.json`'s `displayNames`.
 */
object DisplayNames {
    /**
     * Sibling order: the server-observed creation instant (to the millisecond, a null one as 0), then the node id in UTF-16
     * code units for two born in one millisecond. Neither part can be steered by a client.
     */
    fun compareByCreation(aCreatedAt: Double?, aId: String, bCreatedAt: Double?, bId: String): Int {
        val a = aCreatedAt ?: 0.0
        val b = bCreatedAt ?: 0.0
        return if (a != b) (if (a < b) -1 else 1) else compareById(aId, bId)
    }

    /** Node-id order in UTF-16 code units (locale-free): where no creation instant is served. */
    fun compareById(a: String, b: String): Int = if (a < b) -1 else if (a > b) 1 else 0

    /** The label of each of [siblings] by id, numbered in creation order ([byCreation]) or in id order. */
    fun <T> of(siblings: List<T>, id: (T) -> String, name: (T) -> String, createdAt: (T) -> Double?, byCreation: Boolean): Map<String, String> {
        val labels = HashMap<String, String>()
        each(siblings, id, name, createdAt, byCreation) { n, label -> labels[n] = label }
        return labels
    }

    /**
     * Hands [label] each sibling's id and label: its name, and `Name (n)` for the n-th of a name several carry. One sort by
     * the exact name, equal names by creation (the tree's one sort; by id where [byCreation] is false), so a name's
     * siblings stand together in their order and no table of names is kept.
     */
    fun <T> each(
        siblings: List<T>, id: (T) -> String, name: (T) -> String, createdAt: (T) -> Double?, byCreation: Boolean, label: (String, String) -> Unit,
    ) {
        var previous: String? = null
        var at = 0
        for (n in LevelOrder.sortedByKey(siblings, name, id, if (byCreation) createdAt else { _ -> null })) {
            val k = name(n)
            at = if (k == previous) at + 1 else 1
            previous = k
            label(id(n), if (at == 1) k else "$k ($at)")
        }
    }
}
