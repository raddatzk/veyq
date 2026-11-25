package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.search.Fold
import me.raddatz.veyq.mobile.search.Query
import me.raddatz.veyq.mobile.search.SearchChip
import me.raddatz.veyq.mobile.search.SearchDocument
import me.raddatz.veyq.mobile.search.SearchDocuments
import me.raddatz.veyq.mobile.search.YearSpan

/**
 * A gallery's filter (`uc.memory.gallery` step 8, ADR 0149 decision 5; the web's `galleryFilter.ts` with `MemoryDetail`'s
 * `markFilter`): the marks' mode — all, everyone's, only mine (none in a personal scope) — AND the facts' chips — a capture
 * day (the gallery's own chip) and the search's year, place and kind (`search/Query`'s `suggestChips`, `satisfies` and
 * `chipKey`, reused) — AND free text over the original's name. One predicate over the gallery's rows, so the grid and the
 * lightbox page the same list. **Stored nowhere**: a [State] lives with its page and goes when another Memory opens.
 *
 * The day is the viewer's: the platform's calendar hands each capture time's day ([Facts.of]'s `dayOf`, `YYYY-MM-DD`) and
 * its label, as it converts a picked time for the core (ADR 0144 decision 3); the core does no zone arithmetic.
 */
object GalleryFilter {
    /**
     * The viewer's calendar (the platform's, ADR 0144 decision 3's rule: the core does no zone arithmetic): an instant's day
     * (`YYYY-MM-DD`) and its chip's words, with the year where the gallery spans more than one.
     */
    class Calendar(val dayOf: (Long) -> String, val label: (Long, withYear: Boolean) -> String) {
        companion object {
            /** ISO days in UTC, labelled by the day itself: a test's, and a platform's until it hands its own. */
            val UTC = Calendar({ me.raddatz.veyq.mobile.export.NodeExport.isoMs(it).substring(0, 10) }) { at, _ -> me.raddatz.veyq.mobile.export.NodeExport.isoMs(at).substring(0, 10) }

            /** The device's calendar ([LocalDays]), each chip as the web labels it in [language] (`Shown.dayLabel`). */
            fun local(language: String) = Calendar({ me.raddatz.veyq.mobile.session.LocalDays.isoOf(it) }) { at, withYear ->
                me.raddatz.veyq.mobile.words.Shown.dayLabelOf(at, withYear, language)
            }
        }
    }

    /** The marks' mode (the web's `markFilter`). */
    enum class Mode { ALL, EVERYONE, MINE }

    /** A filter chip: the search's year, place or kind, or a capture day ([day] `YYYY-MM-DD`, [label] as the chip reads). */
    sealed class Chip {
        data class Search(val chip: SearchChip) : Chip()
        data class Day(val day: String, val label: String) : Chip()
    }

    /** A chip's identity: two chips of one key are the same predicate (`galleryChipKey`). */
    fun key(chip: Chip): String = when (chip) {
        is Chip.Day -> "day:${chip.day}"
        is Chip.Search -> Query.chipKey(chip.chip)
    }

    /** One medium's facts as the filter tests them. */
    class Fact(val doc: SearchDocument, val day: String?)

    /** What the gallery's items offer the filter (`galleryFacts`). */
    class Facts(
        val byId: Map<String, Fact>, val documents: SearchDocuments,
        /** Every capture day that occurs, oldest first, with its chip's label. */
        val days: Map<String, String>,
        val years: Set<Int>, val photos: Boolean, val videos: Boolean,
    ) {
        companion object {
            /**
             * The facts of [items] (a photo or a video: what the Photos view shows); [dayOf] the viewer's capture day of an
             * instant, [dayLabel] its chip's words (with the year where [withYear]), [unnamed] a medium's word without a name.
             */
            fun of(
                items: List<MemoryMedia.Item>, dayOf: (Long) -> String, dayLabel: (Long, withYear: Boolean) -> String,
                unnamed: (video: Boolean) -> String = { "" },
            ): Facts {
                val byId = LinkedHashMap<String, Fact>()
                val firstAt = HashMap<String, Long>()
                val years = HashSet<Int>()
                var photos = false
                var videos = false
                for (item in items) {
                    val d = MemoriesSearch.mediumDocument(item.mediaId, item.nodeId, item.metaRevision, item.original.blobId, item.meta, item.type, item.name, unnamed) ?: continue
                    val at = d.facets.capturedAt
                    val day = at?.let(dayOf)
                    val year = day?.substring(0, 4)?.toIntOrNull()
                    val facets = if (year != null) d.facets.copy(years = YearSpan(year, year)) else d.facets
                    byId[d.id] = Fact(SearchDocument.of(item.scopeId, d.kind, d.id, d.name, nodeId = "", facets = facets, icon = d.icon), day)
                    if (day != null && at != null && day !in firstAt) firstAt[day] = at
                    if (year != null) years += year
                    if (d.kind == MemoriesSearch.VIDEO) videos = true else photos = true
                }
                val days = firstAt.entries.sortedBy { it.key }.associate { (day, at) -> day to dayLabel(at, years.size > 1) }
                return Facts(byId, SearchDocuments(byId.values.map { it.doc }), days, years, photos, videos)
            }
        }
    }

    /**
     * The chips offered for [text], none already among [chips] (`suggestGalleryChips`): a year that occurs where two or more
     * do, the days whose label the longest tail of the text that names any begins at a word, the places the items carry,
     * and photo or video where both occur ([photo], [video]: the kinds' words in the UI's language).
     */
    fun suggest(text: String, chips: List<Chip>, facts: Facts, photo: String, video: String): List<Pair<Chip, String>> {
        val offered = Query.suggestChips(
            text, chips.mapNotNull { (it as? Chip.Search)?.chip },
            Query.ChipSources(
                facts.documents, emptyList(), emptyList(),
                if (facts.photos && facts.videos) listOf(photo to listOf(MemoriesSearch.PHOTO), video to listOf(MemoriesSearch.VIDEO)) else emptyList(),
            ),
        ).filter { it.chip is SearchChip.Year || it.chip is SearchChip.Place || it.chip is SearchChip.Kind }
        val years = offered.filter { s -> (s.chip as? SearchChip.Year)?.let { facts.years.size > 1 && it.year in facts.years } == true }
        val taken = chips.mapTo(HashSet(), ::key)
        val days = ArrayList<Pair<Chip, String>>()
        for (tail in Query.tailsOf(text)) {
            val t = Fold.fold(tail.tail)
            if (t.length < Query.MIN_TAIL) continue
            for ((day, label) in facts.days) {
                if (days.size >= Query.PER_TYPE) break
                if ("day:$day" !in taken && Query.wordPrefix(Fold.fold(label), t)) days += Chip.Day(day, label) to tail.rest
            }
            if (days.isNotEmpty()) break
        }
        return years.map { Chip.Search(it.chip) to it.text } + days + offered.filter { it.chip !is SearchChip.Year }.map { Chip.Search(it.chip) to it.text }
    }

    /** One filter's state: the marks' [mode], the [chips], the [text]. */
    data class State(val mode: Mode = Mode.ALL, val chips: List<Chip> = emptyList(), val text: String = "") {
        /** Whether anything narrows (the gallery then pays for the predicate). */
        val narrows: Boolean get() = mode != Mode.ALL || chips.isNotEmpty() || text.isNotBlank()
    }

    /**
     * The gallery's one predicate over a medium id (`galleryPredicate`): the marks' [State.mode] — ignored in a [personal]
     * scope, which has no mode — AND every chip AND the folded text over the original's name; null where nothing narrows.
     */
    fun predicate(state: State, facts: () -> Facts?, marks: GalleryModel.Marks?, personal: Boolean): ((String) -> Boolean)? {
        val mode = if (personal) Mode.ALL else state.mode
        val markTest: ((String) -> Boolean)? = when (mode) {
            Mode.ALL -> null
            Mode.EVERYONE -> marks?.markedByAnyone().orEmpty().let { set -> { id: String -> id in set } }
            Mode.MINE -> marks?.mine().orEmpty().let { set -> { id: String -> id in set } }
        }
        val factTest = if (state.chips.isEmpty() && state.text.isBlank()) null else factTest(facts(), state)
        if (markTest == null && factTest == null) return null
        return { id -> (markTest == null || markTest(id)) && (factTest == null || factTest(id)) }
    }

    private fun factTest(all: Facts?, state: State): (String) -> Boolean {
        val t = Fold.fold(state.text.trim()).replace(Regex("\\s+"), " ")
        return { id -> all?.byId?.get(id)?.let { keeps(it, state.chips, t) } == true }
    }

    /** Whether one medium's facts pass every chip and the folded text [t] (the original's name; a place is a chip's). */
    private fun keeps(fact: Fact, chips: List<Chip>, t: String): Boolean {
        for (c in chips) {
            val ok = when (c) {
                is Chip.Day -> fact.day == c.day
                is Chip.Search -> Query.satisfies(fact.doc, c.chip)
            }
            if (!ok) return false
        }
        if (t.isEmpty()) return true
        val rank = Query.textRank(fact.doc, t) ?: return false
        return rank < Query.Rank.PLACE
    }
}
