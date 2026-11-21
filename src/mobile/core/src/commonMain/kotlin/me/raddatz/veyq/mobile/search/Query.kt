package me.raddatz.veyq.mobile.search

/** A chip (ADR 0125 decision 4): a typed predicate over every document, never text (the web's `SearchChip`). */
sealed class SearchChip {
    data class Space(val scopeId: String, val label: String) : SearchChip()
    data class Year(val year: Int) : SearchChip()

    /** A calendar month: [month] 1–12. */
    data class Month(val year: Int, val month: Int) : SearchChip()

    /** [place] folded; [label] as a document names it. */
    data class Place(val place: String, val label: String) : SearchChip()
    data class Person(val userId: String, val label: String) : SearchChip()

    /** Every kind whose word is [label]. */
    data class Kind(val kinds: List<String>, val label: String) : SearchChip()

    /** What lies below node [nodeId] of space [scopeId]: [label] its name, [path] the names above it. */
    data class In(val scopeId: String, val nodeId: String, val label: String, val path: List<String>) : SearchChip()
}

/** One text and the chips (the web's `SearchQuery`). */
data class SearchQuery(val chips: List<SearchChip>, val text: String)

/**
 * One generation of the index's documents (the web compares its lists by identity: a `WeakMap` per list, a matcher's
 * `known` list): what a suggestion reads its places and its *In:* nodes from, each computed once per generation, and what a
 * [Query.Matcher] forgets its answers at.
 */
class SearchDocuments(val list: List<SearchDocument>) {
    /** Every place the documents name, folded → as first named (the web's `placesOf`). */
    internal val places: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for (d in list) d.foldedPlaces.forEachIndexed { i, p -> if (p.isNotEmpty() && p !in out) out[p] = d.facets.places!![i] }
        out
    }

    /** The documents of the nodes something lies below (the web's `holdersOf`): what an *In:* chip is offered for. */
    internal val holders: List<SearchDocument> by lazy {
        val below = HashSet<String>()
        for (d in list) for (id in d.pathIds) below += "${d.scopeId}\u0000$id"
        list.filter { it.id == it.nodeId && !it.opensItself && "${it.scopeId}\u0000${it.id}" in below }
    }
}

/**
 * The query (ADR 0125 decision 4, uc.platform.search steps 4–5; ported rule by rule from the web for ADR 0146
 * decision 2, the web's own since ADR 0155 through `@veyq/core-js/search`, and held to `contracts/platform/search/v1-vectors.json`): chips from the typed text, each a predicate
 * over every document, and the text matched against names — exact, prefix, a word's start, inside a word, then a place or a
 * settings word. A result is never listed for a reason the member cannot see: one typed character matches word starts only,
 * a settings word counts from [MIN_KEYWORD] characters. Pure: no network, no key, no storage.
 */
object Query {
    /** The shortest tail of the text a chip is suggested for, folded (a year's four digits aside). */
    const val MIN_TAIL = 2

    /** How many chips of one type are suggested at most. */
    const val PER_TYPE = 3

    /** The fewest letters of a month's name a month chip is offered for. */
    const val MIN_MONTH = 3

    /** The fewest characters of the folded text a match inside a word counts from. */
    const val MIN_INSIDE = 2

    /** The fewest characters of the folded text a settings section's word counts from. */
    const val MIN_KEYWORD = 3

    /** The length from which every rule of [textRank] holds: the shortest text the matcher narrows from. */
    private val EVERY_RULE = maxOf(MIN_INSIDE, MIN_KEYWORD)

    /** How many queries a matcher remembers at most. */
    const val MATCH_HISTORY = 64

    /** The ranks of a text match, best first; [PLACE] is a place's match or a settings word's (ADR 0125 decision 5). */
    object Rank {
        const val EXACT = 0
        const val PREFIX = 1
        const val WORD_START = 2
        const val INSIDE = 3
        const val PLACE = 4
    }

    /** A month as `YYYY-MM`. */
    fun monthOf(year: Int, month: Int): String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"

    /** A chip's identity: two chips of one key are the same predicate. */
    fun chipKey(chip: SearchChip): String = when (chip) {
        is SearchChip.Space -> "space:${chip.scopeId}"
        is SearchChip.Year -> "year:${chip.year}"
        is SearchChip.Month -> "month:${monthOf(chip.year, chip.month)}"
        is SearchChip.Place -> "place:${chip.place}"
        is SearchChip.Person -> "person:${chip.userId}"
        is SearchChip.Kind -> "kind:${chip.kinds.sorted().joinToString(",")}"
        is SearchChip.In -> "in:${chip.scopeId}:${chip.nodeId}"
    }

    /** What a chip may be suggested from (the web's `ChipSources`). */
    class ChipSources(
        val documents: SearchDocuments,
        val spaces: List<Pair<String, String>>,
        /** user id → name. */
        val people: List<Pair<String, String>>,
        /** Each kind word with the kinds it names. */
        val kinds: List<Pair<String, List<String>>>,
        /** Each month's names, January first; none: no month is offered. */
        val months: List<List<String>>? = null,
    )

    /** A chip offered for the typed text: [text] is what stays once it is chosen. */
    data class ChipSuggestion(val chip: SearchChip, val text: String)

    /** Whether the folded [name] has a word that starts with the folded [prefix], the whole name included. */
    fun wordPrefix(name: String, prefix: String): Boolean {
        if (name.startsWith(prefix)) return true
        var i = name.indexOf(prefix)
        while (i > 0) {
            if (isBoundary(name, i)) return true
            i = name.indexOf(prefix, i + 1)
        }
        return false
    }

    private fun isBoundary(s: String, i: Int): Boolean = i == 0 || !JsText.isLetterOrNumber(s[i - 1])

    /** One tail of the typed text and what stays before it. */
    data class Tail(val tail: String, val rest: String)

    /** The tails of [text] that start at a word, longest first, each with what stays before it (the web's `tailsOf`). */
    fun tailsOf(text: String): List<Tail> {
        val out = ArrayList<Tail>()
        var i = 0
        while (i < text.length) {
            if (JsText.isJsSpace(text[i])) {
                i++
                continue
            }
            out += Tail(JsText.trim(text.substring(i)), JsText.trimEnd(text.substring(0, i)))
            while (i < text.length && !JsText.isJsSpace(text[i])) i++
        }
        return out
    }

    private fun isYear(s: String) = s.length == 4 && s.all(JsText::isAsciiDigit)

    private val TYPES = listOf("month", "year", "person", "place", "space", "in", "kind")

    private fun typeOf(chip: SearchChip) = when (chip) {
        is SearchChip.Month -> "month"
        is SearchChip.Year -> "year"
        is SearchChip.Person -> "person"
        is SearchChip.Place -> "place"
        is SearchChip.Space -> "space"
        is SearchChip.In -> "in"
        is SearchChip.Kind -> "kind"
    }

    /** The chips offered for [text], none already among [chips]; at most [PER_TYPE] per type (the web's `suggestChips`). */
    fun suggestChips(text: String, chips: List<SearchChip>, sources: ChipSources): List<ChipSuggestion> {
        val taken = chips.mapTo(HashSet(), ::chipKey)
        val seen = HashSet<String>()
        val byType = TYPES.associateWith { ArrayList<ChipSuggestion>() }
        fun offer(s: ChipSuggestion) {
            val key = chipKey(s.chip)
            val list = byType.getValue(typeOf(s.chip))
            if (key in taken || key in seen || list.size >= PER_TYPE) return
            seen += key
            list += s
        }
        val tails = tailsOf(text)
        val last = tails.lastOrNull()
        if (last != null && isYear(last.tail)) {
            val year = last.tail.toInt()
            offer(ChipSuggestion(SearchChip.Year(year), last.rest))
            // the one word before the year: a month's name, or its start
            val before = tails.getOrNull(tails.size - 2)
            val word = if (before != null) Fold.fold(JsText.trim(last.rest.substring(before.rest.length))) else ""
            if (before != null && word.length >= MIN_MONTH && word.none(JsText::isJsSpace)) {
                sources.months?.forEachIndexed { i, names ->
                    if (names.any { Fold.fold(it).startsWith(word) }) offer(ChipSuggestion(SearchChip.Month(year, i + 1), before.rest))
                }
            }
        }
        val places = sources.documents.places
        val holders = sources.documents.holders
        for ((tail, rest) in tails) {
            val t = Fold.fold(tail)
            if (t.length < MIN_TAIL) continue
            for ((userId, name) in sources.people) if (wordPrefix(Fold.fold(name), t)) offer(ChipSuggestion(SearchChip.Person(userId, name), rest))
            for ((place, label) in places) if (wordPrefix(place, t)) offer(ChipSuggestion(SearchChip.Place(place, label), rest))
            for ((scopeId, label) in sources.spaces) if (wordPrefix(Fold.fold(label), t)) offer(ChipSuggestion(SearchChip.Space(scopeId, label), rest))
            for (n in holders) if (wordPrefix(n.foldedName, t)) offer(ChipSuggestion(SearchChip.In(n.scopeId, n.id, n.name, n.path), rest))
            for ((label, kinds) in sources.kinds) if (wordPrefix(Fold.fold(label), t)) offer(ChipSuggestion(SearchChip.Kind(kinds, label), rest))
        }
        return TYPES.flatMap { byType.getValue(it) }
    }

    /** Whether [doc] satisfies [chip]. */
    fun satisfies(doc: SearchDocument, chip: SearchChip): Boolean = when (chip) {
        is SearchChip.Space -> doc.scopeId == chip.scopeId
        is SearchChip.Year -> yearsOf(doc)?.let { it.from <= chip.year && chip.year <= it.to } ?: false
        is SearchChip.Month -> monthsOf(doc)?.let { val m = monthOf(chip.year, chip.month); it.from <= m && m <= it.to } ?: false
        is SearchChip.Place -> chip.place in doc.foldedPlaces
        is SearchChip.Person -> doc.facets.people?.contains(chip.userId) == true
        is SearchChip.Kind -> doc.kind in chip.kinds
        // by id, never by name; the space too, as an id is the space's
        is SearchChip.In -> doc.scopeId == chip.scopeId && chip.nodeId in doc.pathIds
    }

    /** The characters of [t] — code points, so a character outside the basic plane is one. */
    private fun lengthOf(t: String): Int {
        var n = 0
        for (c in t) if (!c.isLowSurrogate()) n++
        return n
    }

    private fun wordsOf(t: String): List<String> = JsText.words(t)

    /**
     * How [doc] matches the folded text [t] (its [words]), or null; an empty text matches at the best rank. Every rule must be
     * monotone in the text from [EVERY_RULE] characters on, since the matcher narrows remembered matches while the text grows.
     */
    fun textRank(doc: SearchDocument, t: String, words: List<String> = wordsOf(t)): Int? {
        if (t.isEmpty()) return Rank.EXACT
        val inside = lengthOf(t) >= MIN_INSIDE
        bestNameRank(doc, t, words, inside)?.let { return it }
        if (keywordAt(doc, t, words) >= 0) return Rank.PLACE
        // indexed loops, here and below: this runs for every document on every keystroke, and an iterator per document
        // was a third of the web's query time (ADR 0155's speed criterion, measured 2026-11-20)
        val places = doc.foldedPlaces
        for (i in places.indices) if (if (inside) places[i].holds(t) else wordPrefix(places[i], t)) return Rank.PLACE
        return null
    }

    private fun bestNameRank(doc: SearchDocument, t: String, words: List<String>, inside: Boolean): Int? {
        var best = nameRank(doc.foldedName, t, words, inside)
        val aliases = doc.foldedAliases
        for (i in aliases.indices) {
            val rank = nameRank(aliases[i], t, words, inside)
            if (rank != null && (best == null || rank < best)) best = rank
        }
        return best
    }

    /** Which of [doc]'s settings words [t] matches — from a word's start, from [MIN_KEYWORD] on; a shorter word typed whole. */
    private fun keywordAt(doc: SearchDocument, t: String, words: List<String>): Int {
        if (doc.foldedKeywords.isEmpty()) return -1
        if (lengthOf(t) < MIN_KEYWORD) return doc.foldedKeywords.indexOfFirst { it == t }
        return doc.foldedKeywords.indexOfFirst { k -> nameRank(k, t, words, false)?.let { it < Rank.INSIDE } ?: false }
    }

    /** The settings word that is the reason [doc] matches [t], as the section names it; null where a name matched. */
    fun reasonKeyword(doc: SearchDocument, t: String): String? {
        if (t.isEmpty() || doc.keywords.isEmpty()) return null
        val words = wordsOf(t)
        if (bestNameRank(doc, t, words, lengthOf(t) >= MIN_INSIDE) != null) return null
        val at = keywordAt(doc, t, words)
        return if (at >= 0) doc.keywords[at] else null
    }

    private fun nameRank(name: String, t: String, words: List<String>, inside: Boolean): Int? {
        // most documents do not hold the text at all: one search answers them
        if (!name.holds(t)) return if (words.size > 1 && words.all { name.holds(it) }) Rank.INSIDE else null
        if (name == t) return Rank.EXACT
        if (name.startsWith(t)) return Rank.PREFIX
        if (wordPrefix(name, t)) return Rank.WORD_START
        return if (inside) Rank.INSIDE else null
    }

    /** A document that matched, with its rank. */
    class Match(val doc: SearchDocument, val rank: Int)

    /** The text of a query as it is matched: folded, trimmed, one space between words (the web's `matchedText`). */
    fun matchedText(text: String): String = JsText.collapseSpaces(Fold.fold(JsText.trim(text)))

    /** The documents matching [query], unordered. A query with neither a chip nor text matches nothing. */
    fun matchAll(docs: List<SearchDocument>, query: SearchQuery): List<Match> = matchIn(docs, query.chips, matchedText(query.text))

    private fun matchIn(docs: Iterable<SearchDocument>, chips: List<SearchChip>, t: String): List<Match> {
        if (t.isEmpty() && chips.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        val words = wordsOf(t)
        for (doc in docs) {
            if (chips.isNotEmpty() && !chips.all { satisfies(doc, it) }) continue
            textRank(doc, t, words)?.let { out += Match(doc, it) }
        }
        return out
    }

    /**
     * A [matchAll] for a field the member types into (the web's `createMatcher`): it remembers the matches of every query
     * asked while the field is open, for one generation of documents. The same query again is its remembered answer; a
     * query that grew from a remembered one narrows the longest such one's matches — only from a text of [EVERY_RULE]
     * characters or an empty one, below which the rules are not monotone; anything else scans every document. Another
     * generation forgets everything, as does [clear]. In memory only.
     */
    class Matcher {
        private class Asked(val chips: String, val t: String, val matches: List<Match>)

        private var known: SearchDocuments? = null
        private val history = LinkedHashMap<String, Asked>()

        fun match(docs: SearchDocuments, query: SearchQuery): List<Match> {
            val t = matchedText(query.text)
            if (t.isEmpty() && query.chips.isEmpty()) return emptyList()
            if (docs !== known) {
                history.clear()
                known = docs
            }
            val chips = query.chips.map(::chipKey).sorted().joinToString("\u0000")
            val key = "$chips\u0001$t"
            history.remove(key)?.let { asked ->
                history[key] = asked // asked again: the newest
                return asked.matches
            }
            var from: Asked? = null
            for (h in history.values) {
                if (h.chips == chips && t.startsWith(h.t) && narrowsFrom(h.t) && (from == null || h.t.length > from.t.length)) from = h
            }
            val matches = if (from != null) matchIn(from.matches.map { it.doc }, query.chips, t) else matchIn(docs.list, query.chips, t)
            history[key] = Asked(chips, t, matches)
            if (history.size > MATCH_HISTORY) history.remove(history.keys.first())
            return matches
        }

        /** Forget every remembered answer: the list closed, the index moved, the member signed out. */
        fun clear() {
            history.clear()
            known = null
        }

        private fun narrowsFrom(t: String) = t.isEmpty() || lengthOf(t) >= EVERY_RULE
    }

    /** A part of a name as a result row draws it: [strong] where it matched the text. */
    data class NamePart(val text: String, val strong: Boolean)

    /** Where the folded [t] matched the folded [name], as ranges of the folded name (the web's `matchedRanges`). */
    fun matchedRanges(name: String, t: String): List<IntArray> {
        if (t.isEmpty()) return emptyList()
        fun at(w: String): Int {
            var i = name.indexOf(w)
            while (i >= 0) {
                if (isBoundary(name, i)) return i
                i = name.indexOf(w, i + 1)
            }
            return name.indexOf(w)
        }
        val whole = at(t)
        val words = if (whole >= 0) listOf(t) else wordsOf(t)
        val ranges = ArrayList<IntArray>()
        for (w in words) {
            val i = at(w)
            if (i < 0) return emptyList()
            ranges += intArrayOf(i, i + w.length)
        }
        ranges.sortBy { it[0] }
        val merged = ArrayList<IntArray>()
        for (r in ranges) {
            val prev = merged.lastOrNull()
            if (prev != null && r[0] <= prev[1]) prev[1] = maxOf(prev[1], r[1]) else merged += intArrayOf(r[0], r[1])
        }
        return merged
    }

    /** [name] in parts, those that matched the folded [t] strong (the web's `nameParts`); one plain part where none did. */
    fun nameParts(name: String, t: String): List<NamePart> {
        val map = if (t.isNotEmpty()) Fold.foldMap(name) else null
        val ranges = if (map != null) matchedRanges(map.folded, t) else emptyList()
        if (map == null || ranges.isEmpty()) return listOf(NamePart(name, false))
        val parts = ArrayList<NamePart>()
        var at = 0
        for ((a, b) in ranges.map { it[0] to it[1] }) {
            val from = maxOf(map.start[a], at)
            val to = map.end[b - 1]
            if (from > at) parts += NamePart(name.substring(at, from), false)
            parts += NamePart(name.substring(from, to), true)
            at = to
        }
        if (at < name.length) parts += NamePart(name.substring(at), false)
        return parts
    }
}
