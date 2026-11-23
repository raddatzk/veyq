@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.search.web

import me.raddatz.veyq.mobile.search.Fold
import me.raddatz.veyq.mobile.search.MatchedValue
import me.raddatz.veyq.mobile.search.MonthSpan
import me.raddatz.veyq.mobile.search.Query
import me.raddatz.veyq.mobile.search.Results
import me.raddatz.veyq.mobile.search.SearchChip
import me.raddatz.veyq.mobile.search.SearchDocument
import me.raddatz.veyq.mobile.search.SearchDocuments
import me.raddatz.veyq.mobile.search.SearchFacets
import me.raddatz.veyq.mobile.search.SearchQuery
import me.raddatz.veyq.mobile.search.YearSpan
import kotlin.js.collections.JsReadonlyArray

// The search's façade for the web (ADR 0155 decisions 4 and 5; `@veyq/core-js/search`): the core's `Fold`, `Query` and
// `Results` behind plain values (`Shapes.kt`), so the web folds, suggests, matches, ranks and orders as the apps do and
// keeps only what a browser does — its Vue field, its index and its `Intl.Collator`. What it exports is what the web's
// callers call, no more. Nothing here reaches kotlinx.serialization, coroutines, Ktor or a `NotOnTheWeb` refusal (the
// package's slice check reads the shipped bundle's sources).

/**
 * One generation of the web's documents (the core's [SearchDocuments]): made once per list the web's index hands out and
 * dropped with it, so a suggestion's places and *In:* nodes and a matcher's memory are per generation, as the web's
 * identity of a list was. Opaque: the web holds it (`markRaw`) and passes it back; results come back as positions in it.
 */
@JsExport
class SearchCorpus internal constructor(internal val documents: SearchDocuments) {
    internal val positions: Map<SearchDocument, Int> by lazy {
        HashMap<SearchDocument, Int>(documents.list.size * 2).also { m -> documents.list.forEachIndexed { i, d -> m[d] = i } }
    }
}

/** A field's memory of the queries asked while its list is open (the core's [Query.Matcher]). Opaque, as [SearchCorpus]. */
@JsExport
class SearchMatcher internal constructor() {
    internal val matcher = Query.Matcher()
}

@JsExport
fun fold(s: String): String = Fold.fold(s)

@JsExport
fun foldMap(s: String): FoldMapShape? = Fold.foldMap(s)?.let {
    shape("folded" to it.folded, "start" to it.start.toTypedArray(), "end" to it.end.toTypedArray())
}

/** The text of a query as it is matched: folded, trimmed, one space between words. */
@JsExport
fun matchedText(text: String): String = Query.matchedText(text)

@JsExport
fun wordPrefix(name: String, prefix: String): Boolean = Query.wordPrefix(name, prefix)

@JsExport
fun tailsOf(text: String): JsReadonlyArray<TailShape> = array(Query.tailsOf(text).map { shape<TailShape>("tail" to it.tail, "rest" to it.rest) })

@JsExport
fun nameParts(name: String, t: String): JsReadonlyArray<NamePartShape> =
    array(Query.nameParts(name, t).map { shape<NamePartShape>("text" to it.text, "strong" to it.strong) })

@JsExport
fun chipKey(chip: ChipShape): String = Query.chipKey(chipOf(chip))

/** The search's numbers (see [SearchRulesShape]). */
@JsExport
fun searchRules(): SearchRulesShape = shape(
    "minTail" to Query.MIN_TAIL,
    "perType" to Query.PER_TYPE,
    "rank" to shape<RankShape>(
        "exact" to Query.Rank.EXACT, "prefix" to Query.Rank.PREFIX, "wordStart" to Query.Rank.WORD_START,
        "inside" to Query.Rank.INSIDE, "place" to Query.Rank.PLACE,
    ),
)

/** A generation's corpus of [documents], in their order: a result's position is its index here. */
@JsExport
fun corpus(documents: JsReadonlyArray<DocumentShape>): SearchCorpus = SearchCorpus(SearchDocuments(list(documents).map(::documentOf)))

@JsExport
fun matcher(): SearchMatcher = SearchMatcher()

/** Forget every remembered answer: the list closed, the index moved, the member signed out. */
@JsExport
fun clearMatcher(matcher: SearchMatcher) = matcher.matcher.clear()

/** The chips offered for [text], none already among [chips]; at most a few per type. */
@JsExport
fun suggestChips(text: String, chips: JsReadonlyArray<ChipShape>, sources: ChipSourcesShape): JsReadonlyArray<SuggestionShape> {
    val core = Query.ChipSources(
        documents = sources.corpus.documents,
        spaces = list(sources.spaces).map { it.scopeId to it.label },
        people = list(sources.people).map { it.userId to it.name },
        kinds = list(sources.kinds).map { it.label to list(it.kinds) },
        months = sources.months?.let { months -> list(months).map(::list) },
    )
    return array(Query.suggestChips(text, list(chips).map(::chipOf), core).map { shape<SuggestionShape>("chip" to chipShape(it.chip), "text" to it.text) })
}

/**
 * The matches of [query] in [corpus] as one ordered list (the core's `Results.searchResults`): the first [limit] (every
 * match where null) as positions in the corpus, and how many match. Equal ranks and places order by [compareNames] over
 * the names — the web's `Intl.Collator`'s `compare` —, then by id. [matcher], where given, remembers and narrows.
 */
@JsExport
fun search(
    corpus: SearchCorpus, query: QueryShape, context: ContextShape, limit: Int?, matcher: SearchMatcher?, compareNames: (String, String) -> Int,
): HitsShape {
    val found = Results.searchResults(
        corpus.documents, SearchQuery(list(query.chips).map(::chipOf), query.text), Results.SearchContext(context.activeScopeId, context.openNodeId),
        limit ?: Int.MAX_VALUE, matcher?.matcher, Comparator { a, b -> compareNames(a.name, b.name) },
    )
    val positions = corpus.positions
    return shape("positions" to found.results.map { positions.getValue(it) }.toTypedArray(), "total" to found.total)
}

/** How [doc] matches the folded text [t], as its rank, or null where it does not. */
@JsExport
fun textRank(doc: DocumentShape, t: String): Int? = Query.textRank(documentOf(doc), t)

/** Whether [doc] satisfies [chip]. */
@JsExport
fun satisfies(doc: DocumentShape, chip: ChipShape): Boolean = Query.satisfies(documentOf(doc), chipOf(chip))

/** The settings word that is the reason [doc] matches the folded text [t], as the section names it, or null. */
@JsExport
fun reasonKeyword(doc: DocumentShape, t: String): String? = Query.reasonKeyword(documentOf(doc), t)

/** The values of [chips] that [doc] carries, each once, in the chips' order (the core's `Query.matchedValues`). */
@JsExport
fun matchedValues(doc: DocumentShape, chips: JsReadonlyArray<ChipShape>): JsReadonlyArray<MatchedShape> =
    array(Query.matchedValues(documentOf(doc), list(chips).map(::chipOf)).map { v ->
        when (v) {
            is MatchedValue.Text -> shape<MatchedShape>("text" to v.text)
            is MatchedValue.Month -> shape("year" to v.year, "month" to v.month)
        }
    })

/** Whether [doc] is the node whose page is on screen (the core's `Results.isHere`). */
@JsExport
fun isHere(doc: DocumentShape, here: HereShape): Boolean =
    Results.isHere(documentOf(doc), Results.SearchContext(here.activeScopeId, null, here.hereNodeId))

private fun documentOf(d: DocumentShape): SearchDocument = SearchDocument.of(
    scopeId = d.scopeId, kind = d.kind, id = d.id, name = d.name, nodeId = d.nodeId, revision = d.revision ?: "", path = list(d.path),
    pathIds = d.pathIds?.let(::list).orEmpty(), facets = facetsOf(d.facets), opensItself = d.opensItself == true,
    aliases = d.aliases?.let(::list).orEmpty(), keywords = d.keywords?.let(::list).orEmpty(), subtitle = d.subtitle,
)

private fun facetsOf(f: FacetsShape): SearchFacets = SearchFacets(
    years = f.years?.let { YearSpan(it.from, it.to) },
    months = f.months?.let { MonthSpan(it.from, it.to) },
    // the web's `Number.isFinite`: a capture time that is not a finite number is none
    capturedAt = f.capturedAt?.takeIf { it.isFinite() }?.toLong(),
    places = f.places?.let(::list),
    people = f.people?.let(::list),
)

private fun chipOf(c: ChipShape): SearchChip = when (c.type) {
    "space" -> SearchChip.Space(c.scopeId!!, c.label!!)
    "year" -> SearchChip.Year(c.year!!)
    "month" -> SearchChip.Month(c.year!!, c.month!!)
    "place" -> SearchChip.Place(c.place!!, c.label!!)
    "person" -> SearchChip.Person(c.userId!!, c.label!!)
    "kind" -> SearchChip.Kind(list(c.kinds!!), c.label!!)
    "in" -> SearchChip.In(c.scopeId!!, c.nodeId!!, c.label!!, list(c.path!!))
    else -> throw IllegalArgumentException("not a search chip: ${c.type}")
}

/** A chip as the web's `SearchChip` spells it: its type's fields only, in the web's order. */
private fun chipShape(c: SearchChip): ChipShape = when (c) {
    is SearchChip.Space -> shape("type" to "space", "scopeId" to c.scopeId, "label" to c.label)
    is SearchChip.Year -> shape("type" to "year", "year" to c.year)
    is SearchChip.Month -> shape("type" to "month", "year" to c.year, "month" to c.month)
    is SearchChip.Place -> shape("type" to "place", "place" to c.place, "label" to c.label)
    is SearchChip.Person -> shape("type" to "person", "userId" to c.userId, "label" to c.label)
    is SearchChip.Kind -> shape("type" to "kind", "kinds" to c.kinds.toTypedArray(), "label" to c.label)
    is SearchChip.In -> shape("type" to "in", "scopeId" to c.scopeId, "nodeId" to c.nodeId, "label" to c.label, "path" to c.path.toTypedArray())
}

/** A JavaScript array read as a list, not copied. */
private fun <T> list(a: JsReadonlyArray<T>): List<T> = a.unsafeCast<Array<T>>().asList()

private fun <T> array(l: List<T>): JsReadonlyArray<T> = l.toTypedArray().unsafeCast<JsReadonlyArray<T>>()

/** A plain JavaScript object of [fields], typed as the shape it spells. */
private fun <T> shape(vararg fields: Pair<String, Any?>): T {
    val o: dynamic = js("({})")
    for ((k, v) in fields) o[k] = v
    return o.unsafeCast<T>()
}
