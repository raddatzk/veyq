package me.raddatz.veyq.mobile.search

/**
 * The results: ONE list after the chip suggestions (ADR 0125 decision 5; ported from the web for ADR 0146,
 * the web's own since ADR 0155). The order is the match first ([Query.Rank]), then where the result lies — below the open node, the rest of the
 * active space, the other spaces, then the documents of no space —, then the name ([NameOrder], or the caller's), then the id. A chip is a
 * predicate over every document, so no kind is hidden by a rule of its own. The list is ranked only as far as it is drawn:
 * the first `limit` are selected, the total stays exact.
 */
object Results {
    /** Where a result lies, nearest first: its second key in the order. */
    object Place {
        const val OPEN = 0
        const val SPACE = 1
        const val OTHER = 2
        const val NOWHERE = 3
    }

    /**
     * Where the member stands: the space on screen (null: none), the node whose page is on screen (not a folder level), and
     * the node the screen heads ([hereNodeId], a folder's level too: the web's breadcrumb's last crumb), which only marks a
     * result *You are here* ([isHere]) and orders nothing.
     */
    data class SearchContext(val activeScopeId: String?, val openNodeId: String?, val hereNodeId: String? = null)

    /**
     * Whether [doc] is the node whose page is on screen (the web's `isHere`, inbox #962): a node's own document in the active
     * space whose id is [SearchContext.hereNodeId]. Its row is marked *You are here*, and opening it only closes the field.
     */
    fun isHere(doc: SearchDocument, ctx: SearchContext): Boolean =
        ctx.hereNodeId != null && doc.scopeId == ctx.activeScopeId && doc.id == doc.nodeId && doc.nodeId == ctx.hereNodeId

    /** The best matches in order (the first `limit`) and how many documents match. */
    class SearchResults(val results: List<SearchDocument>, val total: Int)

    /** A match with the two keys the order reads before the name. */
    class Ranked(val doc: SearchDocument, val rank: Int, val place: Int)

    /**
     * Where [doc] lies for a member standing at [ctx], or null for a document not listed: a contribution counts only for the
     * node open in the active space; below the open node is what lies below it by id; the node itself lies in its space.
     */
    fun placeOf(doc: SearchDocument, ctx: SearchContext): Int? {
        if (doc.kind == SearchDocument.PERSON || doc.kind == SearchDocument.SETTINGS) return Place.NOWHERE
        val open = ctx.openNodeId != null && doc.scopeId == ctx.activeScopeId
        if (doc.opensItself) return if (open && doc.nodeId == ctx.openNodeId) Place.OPEN else null
        if (doc.scopeId != ctx.activeScopeId) return Place.OTHER
        return if (open && ctx.openNodeId in doc.pathIds) Place.OPEN else Place.SPACE
    }

    /**
     * The matches of [query] in [docs] as one ordered list: its first [limit] and how many match. [names] orders two documents
     * of an equal rank and place; the apps take the default, [BY_NAME], and the web passes its browser's `Intl.Collator` over
     * the names (ADR 0155 § Consequences: the one parameter the web's search adds, so it keeps its collation).
     */
    fun searchResults(
        docs: SearchDocuments, query: SearchQuery, ctx: SearchContext, limit: Int = Int.MAX_VALUE, matcher: Query.Matcher? = null,
        names: Comparator<SearchDocument> = BY_NAME,
    ): SearchResults {
        val ranked = ArrayList<Ranked>()
        val matches = matcher?.match(docs, query) ?: Query.matchAll(docs.list, query)
        for (m in matches) placeOf(m.doc, ctx)?.let { ranked += Ranked(m.doc, m.rank, it) }
        val order = if (names === BY_NAME) ORDER else orderBy(names)
        return SearchResults(firstRanked(ranked, limit, order).map { it.doc }, ranked.size)
    }

    /** The apps' name order: [NameOrder] over the folded names. */
    val BY_NAME: Comparator<SearchDocument> = Comparator { a, b -> NameOrder.compare(a.foldedName, b.foldedName) }

    /** The order: the rank, where the result lies, the name ([names]), the id (UTF-16 order, as JavaScript's `<`). */
    fun orderBy(names: Comparator<SearchDocument>): Comparator<Ranked> = Comparator { a, b ->
        (a.rank - b.rank).takeIf { it != 0 }
            ?: (a.place - b.place).takeIf { it != 0 }
            ?: names.compare(a.doc, b.doc).takeIf { it != 0 }
            ?: a.doc.id.compareTo(b.doc.id)
    }

    /** The order with the apps' names ([BY_NAME]). */
    val ORDER: Comparator<Ranked> = orderBy(BY_NAME)

    /** The first [n] of [matches] in [order], without sorting the rest; [matches] itself is left as it was. */
    fun <T> firstRanked(matches: List<T>, n: Int, order: Comparator<T>): List<T> {
        if (matches.size <= n) return matches.sortedWith(order)
        val kept = ArrayList<T>(n + 1)
        if (n <= 0) return kept
        for (m in matches) {
            if (kept.size == n && order.compare(m, kept[n - 1]) >= 0) continue
            var lo = 0
            var hi = kept.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (order.compare(m, kept[mid]) < 0) hi = mid else lo = mid + 1
            }
            kept.add(lo, m)
            if (kept.size > n) kept.removeAt(kept.size - 1)
        }
        return kept
    }
}

/**
 * The name order, one for every platform (ADR 0146 decision 3): where the web orders by `Intl.Collator(locale, {numeric:
 * true, sensitivity: 'base'})`, the core compares the FOLDED names (case and accents aside, as `base` does) with each run of
 * ASCII digits read as a number ("Trip 2" before "Trip 10", leading zeros aside), every other character by its UTF-16 unit.
 * Names equal so are equal, and the id decides, as on the web. The residual: names led by punctuation, or with a letter a
 * language collates apart (Swedish å), may order differently from a browser's collator at an equal rank and place.
 */
object NameOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val si = i
                while (i < a.length && a[i] in '0'..'9') i++
                val sj = j
                while (j < b.length && b[j] in '0'..'9') j++
                val ra = a.substring(si, i).trimStart('0')
                val rb = b.substring(sj, j).trimStart('0')
                if (ra.length != rb.length) return ra.length - rb.length
                val c = ra.compareTo(rb)
                if (c != 0) return c
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
