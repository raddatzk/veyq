package me.raddatz.veyq.mobile.search

import me.raddatz.veyq.mobile.session.LocalDays

// The search's documents (ADR 0125 decisions 2 and 6; the web's `core/search/model.ts` keeps the shape
// it draws, ADR 0146): what a member
// can find, as plaintext derived from what the client already opened — never a key, never a second source of truth.

/** Calendar years, both ends included: a Memory dated 2023-12-30 to 2024-01-02 is `2023..2024`. */
data class YearSpan(val from: Int, val to: Int)

/** Calendar months as `YYYY-MM`, both ends included. */
data class MonthSpan(val from: String, val to: String)

/** What a document carries beyond its name — each a chip's predicate ([Query]). A fact it does not have is null. */
data class SearchFacets(
    /** The years the object spans (a Memory's date range). */
    val years: YearSpan? = null,
    /** The calendar months it spans, `YYYY-MM` (a Memory's date range): a month chip's. */
    val months: MonthSpan? = null,
    /** When it was captured, epoch milliseconds (a photo): its year matches a year chip, and the row shows the day. */
    val capturedAt: Long? = null,
    /** The places it names, as named (a Memory's place, a photo's place). */
    val places: List<String>? = null,
    /** The people in it, by user id (a Memory's participants) — never a name. */
    val people: List<String>? = null,
) {
    companion object {
        val NONE = SearchFacets()
    }
}

/** One searchable object (the web's `SearchDocument`), its folded fields made once by [of]. */
class SearchDocument private constructor(
    /** The space it lives in; empty for a document of no space (a person, a settings section). */
    val scopeId: String,
    /** A node's kind, or a contributed kind (`memories.photo`). */
    val kind: String,
    /** A node's id, or the contributed object's (a medium's id). */
    val id: String,
    /** The revision it was derived at. */
    val revision: String,
    val name: String,
    /** The names of the nodes above it, outermost first. */
    val path: List<String>,
    /** The ids of those nodes, in the same order: what an *In:* chip and the open node's place ask. An object's (a photo's)
     *  ends with its own node. Empty for a document of no space. */
    val pathIds: List<String>,
    val facets: SearchFacets,
    /** The node a contributed document belongs to (the open Memory); a node's own document: its own id. */
    val nodeId: String,
    /** A document that opens itself (the web's `open`): the open Memory's contribution, a person, a settings section. */
    val opensItself: Boolean,
    /** A contributed document's icon kind; a node's comes from its kind. */
    val icon: String?,
    /** Other names it is found by as by its name (a person's `~username`). */
    val aliases: List<String>,
    /** Words it is found by at a word's start only, from the third typed character on (a settings section's). */
    val keywords: List<String>,
    /** Its own second line (a person's handle); a node's is where it lives. */
    val subtitle: String?,
) {
    val foldedName: String = Fold.fold(name)
    val foldedPlaces: List<String> = facets.places.orEmpty().map(Fold::fold)
    val foldedAliases: List<String> = aliases.map(Fold::fold)
    val foldedKeywords: List<String> = keywords.map(Fold::fold)

    /** The same document at another place (the open Memory's contribution placed under its node's path). */
    fun at(scopeId: String, nodeId: String, path: List<String>, pathIds: List<String>): SearchDocument =
        SearchDocument(scopeId, kind, id, revision, name, path, pathIds, facets, nodeId, opensItself, icon, aliases, keywords, subtitle)

    companion object {
        /** Core's one way to make a document (the web's `searchDocument`), so its folded fields are always there. */
        fun of(
            scopeId: String, kind: String, id: String, name: String,
            nodeId: String = id, revision: String = "", path: List<String> = emptyList(), pathIds: List<String> = emptyList(),
            facets: SearchFacets = SearchFacets.NONE, opensItself: Boolean = false, icon: String? = null,
            aliases: List<String> = emptyList(), keywords: List<String> = emptyList(), subtitle: String? = null,
        ): SearchDocument = SearchDocument(scopeId, kind, id, revision, name, path, pathIds, facets, nodeId, opensItself, icon, aliases, keywords, subtitle)

        /** The kind of a member's document (ADR 0125 decision 9). */
        const val PERSON = "person"

        /** The kind of a settings section's document (ADR 0125 decision 9). */
        const val SETTINGS = "settings"
    }
}

/** What an application contributes for the node on screen (the web's `ContributedDocument`): a document it opens itself. */
data class ContributedDocument(
    val kind: String,
    val id: String,
    val revision: String,
    val name: String,
    val facets: SearchFacets,
    val icon: String? = null,
)

/**
 * What an application finds in a whole space (the web's `ScopeDocument`, ADR 0125 decision 8a): an object of its own inside
 * a node — Memories' photo or video —; core places it under its node's path.
 */
data class ScopeDocument(
    val kind: String,
    val id: String,
    /** The node the object belongs to (a medium's Memory or chapter). */
    val nodeId: String,
    /** The seals it was read from: a document at an unchanged revision is kept, not opened again. */
    val revision: String,
    val name: String,
    val facets: SearchFacets,
    val icon: String? = null,
)

/** Year, month (1–12) and day of an epoch millisecond in the device's time zone (the web reads local time; [LocalDays]). */
internal fun localDate(epochMs: Long): Triple<Int, Int, Int> = LocalDays.civil(LocalDays.dayOf(epochMs))

/** The years a document spans: its own span, else its capture year (local time) — the web's `yearsOf`. */
fun yearsOf(doc: SearchDocument): YearSpan? {
    doc.facets.years?.let { return it }
    val at = doc.facets.capturedAt ?: return null
    val year = localDate(at).first
    return YearSpan(year, year)
}

/** The months a document spans: its own span, else its capture month (local time) — the web's `monthsOf`. */
fun monthsOf(doc: SearchDocument): MonthSpan? {
    doc.facets.months?.let { return it }
    val at = doc.facets.capturedAt ?: return null
    val (y, m) = localDate(at)
    val month = Query.monthOf(y, m)
    return MonthSpan(month, month)
}
