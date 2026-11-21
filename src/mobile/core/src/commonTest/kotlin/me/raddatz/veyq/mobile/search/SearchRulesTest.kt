package me.raddatz.veyq.mobile.search

import me.raddatz.veyq.mobile.media.localWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The search's rules case by case where the vectors (`SearchVectorsTest`) pin them only in passing: the web's own unit tests
 * of its TypeScript twin (`query.test.ts`, `results.test.ts`, `fold.test.ts`), whose assertions moved here when the web's
 * search moved onto the core (ADR 0155). The matcher's memory is held by identity: a remembered answer is the list it was.
 */
class SearchRulesTest {
    private fun doc(
        name: String, id: String = name, scope: String = "s1", pathIds: List<String> = emptyList(), facets: SearchFacets = SearchFacets.NONE,
        aliases: List<String> = emptyList(), keywords: List<String> = emptyList(),
    ) = SearchDocument.of(scopeId = scope, kind = "MEMORY", id = id, name = name, pathIds = pathIds, facets = facets, aliases = aliases, keywords = keywords)

    private fun rank(d: SearchDocument, text: String) = Query.textRank(d, Query.matchedText(text))
    private fun q(text: String, vararg chips: SearchChip) = SearchQuery(chips.toList(), text)

    @Test
    fun anInChipAsksTheNodesIdAndItsSpace() {
        val memo = doc("Memo", scope = "s1", pathIds = listOf("arbeit", "reisen2"))
        assertTrue(Query.satisfies(memo, SearchChip.In("s1", "reisen2", "Reisen", listOf("Arbeit"))))
        assertFalse(Query.satisfies(memo, SearchChip.In("s1", "reisen", "Reisen", emptyList())))
        assertFalse(Query.satisfies(doc("Paris", scope = "g2", pathIds = listOf("reisen")), SearchChip.In("s1", "reisen", "Reisen", emptyList())))
        assertTrue(Query.satisfies(doc("Paris", scope = "s1", pathIds = listOf("reisen")), SearchChip.In("s1", "reisen", "Reisen", emptyList())))
    }

    @Test
    fun aYearChipHoldsBothEndsOfASpan() {
        val d = doc("Trip", facets = SearchFacets(years = YearSpan(2023, 2024)))
        assertEquals(listOf(false, true, true, false), (2022..2025).map { Query.satisfies(d, SearchChip.Year(it)) })
    }

    @Test
    fun aPlaceChipIsTheWholeFoldedPlace() {
        assertFalse(Query.satisfies(doc("Lake", facets = SearchFacets(places = listOf("Zürichsee"))), SearchChip.Place("zurich", "Zürich")))
        assertTrue(Query.satisfies(doc("City", facets = SearchFacets(places = listOf("ZÜRICH"))), SearchChip.Place("zurich", "Zürich")))
    }

    @Test
    fun everyChipMustHold() {
        val d = doc("Trip", facets = SearchFacets(years = YearSpan(2024, 2024), people = listOf("A")))
        assertEquals(1, Query.matchAll(listOf(d), q("", SearchChip.Year(2024), SearchChip.Person("A", ""))).size)
        assertEquals(0, Query.matchAll(listOf(d), q("", SearchChip.Year(2024), SearchChip.Person("x", ""))).size)
    }

    @Test
    fun theRanksOfFoldedAndSeveralWordMatches() {
        assertEquals(Query.Rank.PREFIX, rank(doc("Café Müller"), "cafe mul"))
        assertEquals(Query.Rank.INSIDE, rank(doc("Hauptstraße"), "strasse"))
        assertEquals(Query.Rank.INSIDE, rank(doc("Summer in Rome"), "rome summer"))
    }

    @Test
    fun aSettingsWordRanksAsAPlaceAndANameFirst() {
        val mail = doc("Mail", keywords = listOf("E-Mail", "SMTP"))
        assertEquals(Query.Rank.PLACE, rank(mail, "smtp"))
        assertNull(rank(mail, "mtp"))
        assertEquals(Query.Rank.INSIDE, rank(doc("Gesmtpx", keywords = listOf("SMTP")), "smtp"))
        assertEquals(Query.Rank.EXACT, rank(doc("Mail", keywords = listOf("Mail")), "mail"))
    }

    @Test
    fun anAliasCountsAtItsBestRank() {
        val kevin = doc("Kevin Raddatz", aliases = listOf("~kraddatz"))
        assertEquals(Query.Rank.EXACT, rank(kevin, "~kraddatz"))
        assertEquals(Query.Rank.WORD_START, rank(kevin, "kradd"))
        assertEquals(Query.Rank.PREFIX, rank(kevin, "kev"))
        assertNull(rank(doc("Kevin Raddatz"), "~kraddatz"))
    }

    @Test
    fun oneCharacterMatchesWordStartsOnlyPlacesToo() {
        assertNull(rank(doc("Home", facets = SearchFacets(places = listOf("Parkweg"))), "k"))
        assertEquals(Query.Rank.PLACE, rank(doc("Home", facets = SearchFacets(places = listOf("Parkweg"))), "rk"))
        assertEquals(Query.Rank.PLACE, rank(doc("Home", facets = SearchFacets(places = listOf("Kassel"))), "k"))
        assertEquals(Query.Rank.PREFIX, rank(doc("Kevin", aliases = listOf("~xkev")), "~"))
        assertNull(rank(doc("Reiselink"), "k"))
        assertNull(rank(doc("Paket"), "k"))
        assertEquals(Query.Rank.WORD_START, rank(doc("Bad Kissingen"), "k"))
        // the matcher narrows from "rk" no sooner than every rule holds: each step is the whole scan
        val docs = SearchDocuments(listOf(doc("Home", facets = SearchFacets(places = listOf("Parkweg"))), doc("Rome"), doc("Park")))
        val m = Query.Matcher()
        for (t in listOf("r", "rk", "rkw", "rk")) assertEquals(ids(Query.matchAll(docs.list, q(t))), ids(m.match(docs, q(t))), t)
        assertTrue(Query.matchAll(docs.list, q("rk")).any { it.doc.name == "Home" && it.rank == Query.Rank.PLACE })
    }

    @Test
    fun aCharacterOutsideTheBasicPlaneIsOne() {
        assertNull(rank(doc("x🎉"), "🎉"))
        assertEquals(Query.Rank.WORD_START, rank(doc("Party 🎉🎉"), "🎉"))
    }

    @Test
    fun aShortSettingsWordTypedWholeAndTheReason() {
        val storage = doc("Speicher", keywords = listOf("Speicherplatz", "Kontingent", "S3"))
        assertEquals(Query.Rank.PREFIX, rank(storage, "s"))
        assertNull(Query.reasonKeyword(storage, "s"))
        assertNull(rank(doc("Lager", keywords = listOf("S3")), "s"))
        val password = doc("Passwort ändern", keywords = listOf("Kennwort", "Passwort", "Anmeldung"))
        assertEquals("Anmeldung", Query.reasonKeyword(password, "anmel"))
        for (t in listOf("passw", "ke", "")) assertNull(Query.reasonKeyword(password, t), t)
    }

    @Test
    fun aMonthChipHoldsBothEndsOfASpanAcrossAYear() {
        val spring = doc("Spring", facets = SearchFacets(months = MonthSpan("2024-04", "2024-05")))
        assertEquals(listOf(false, true, true, false), (3..6).map { Query.satisfies(spring, SearchChip.Month(2024, it)) })
        assertFalse(Query.satisfies(spring, SearchChip.Month(2023, 5)))
        val newYear = doc("New year", facets = SearchFacets(months = MonthSpan("2023-12", "2024-01")))
        assertTrue(Query.satisfies(newYear, SearchChip.Month(2023, 12)))
        assertTrue(Query.satisfies(newYear, SearchChip.Month(2024, 1)))
        assertFalse(Query.satisfies(newYear, SearchChip.Month(2024, 2)))
        assertFalse(Query.satisfies(doc("Years only", facets = SearchFacets(years = YearSpan(2024, 2024))), SearchChip.Month(2024, 5)))
    }

    @Test
    fun aPhotosMonthIsItsLocalOne() {
        val lastMinute = doc("May", facets = SearchFacets(capturedAt = localWallClock(2024, 5, 31, 23, 59, 0)!!))
        assertTrue(Query.satisfies(lastMinute, SearchChip.Month(2024, 5)))
        assertFalse(Query.satisfies(lastMinute, SearchChip.Month(2024, 6)))
        val firstMinute = doc("June", facets = SearchFacets(capturedAt = localWallClock(2024, 6, 1, 0, 1, 0)!!))
        assertFalse(Query.satisfies(firstMinute, SearchChip.Month(2024, 5)))
        assertTrue(Query.satisfies(firstMinute, SearchChip.Month(2024, 6)))
    }

    private fun ids(ms: List<Query.Match>) = ms.map { "${it.doc.id}:${it.rank}" }.sorted()

    private val docs = listOf(doc("Kevin"), doc("Kennwort"), doc("IMG_0001.jpg", id = "img"), doc("Paket"), doc("Rome"))

    @Test
    fun theMatcherNarrowsOnlyWhatItRemembered() {
        val list = ArrayList(docs)
        val g = SearchDocuments(list)
        val m = Query.Matcher()
        m.match(g, q("kev"))
        list[2] = doc("kevimg", id = "img") // renamed in place: a narrowed answer never asks it again
        assertFalse(m.match(g, q("kevi")).any { it.doc.id == "img" })
        assertTrue(Query.matchAll(list, q("kevi")).any { it.doc.id == "img" })
    }

    @Test
    fun theMatcherScansAgainForANewGenerationAndAnswersNothingForNothing() {
        val g = SearchDocuments(docs)
        val m = Query.Matcher()
        m.match(g, q("kev"))
        assertTrue(m.match(SearchDocuments(docs + doc("Kevina")), q("kev")).any { it.doc.name == "Kevina" })
        assertTrue(m.match(g, q("")).isEmpty())
    }

    @Test
    fun theSameQueryAgainIsTheRememberedAnswer() {
        val g = SearchDocuments(docs)
        val m = Query.Matcher()
        val ke = m.match(g, q("ke"))
        assertSame(ke, m.match(g, q("ke ")))
        m.match(g, q("kev"))
        m.match(g, q("kevi"))
        assertSame(ke, m.match(g, q("ke"))) // Backspace
    }

    @Test
    fun theMatcherForgetsAtANewGenerationAndAtClear() {
        val g = SearchDocuments(docs)
        val m = Query.Matcher()
        val ke = m.match(g, q("ke"))
        assertSame(ke, m.match(g, q("ke")))
        assertNotSame(ke, m.match(SearchDocuments(docs), q("ke")))
        val again = m.match(g, q("ke"))
        m.clear()
        assertNotSame(again, m.match(g, q("ke")))
    }

    @Test
    fun theMatcherRemembersAtMostItsHistory() {
        val g = SearchDocuments(docs)
        val m = Query.Matcher()
        val first = m.match(g, q("ke"))
        repeat(Query.MATCH_HISTORY) { m.match(g, q("x$it", SearchChip.Year(2010 + it % 8))) }
        assertNotSame(first, m.match(g, q("ke")))
    }

    // the generator of the web's test: names that tie on rank and differ by case, digits and accents; half in another space
    private val many = List(300) { i ->
        doc("${listOf("Rom", "rom", "Röm", "Roma", "Trip to Rome", "Aroma")[i % 6]} ${(i * 7919) % 300}", id = "n$i", scope = if (i % 2 == 1) "active" else "other")
    }
    private val ctx = Results.SearchContext("active", null)

    @Test
    fun theFirstNAreAFullSortsFirstN() {
        val ranked = Query.matchAll(many, q("rom")).map { Results.Ranked(it.doc, it.rank, Results.placeOf(it.doc, ctx)!!) }
        val before = ranked.map { it.doc.id }
        val full = ranked.sortedWith(Results.ORDER).map { it.doc.id }
        for (n in listOf(0, 1, 10, 20, 299, 300, 301)) assertEquals(full.take(n), Results.firstRanked(ranked, n, Results.ORDER).map { it.doc.id }, "n=$n")
        assertEquals(before, ranked.map { it.doc.id }) // the matches handed in are left as they were
        val page = Results.searchResults(SearchDocuments(many), q("rom"), ctx, limit = 10)
        val whole = Results.searchResults(SearchDocuments(many), q("rom"), ctx)
        assertEquals(listOf(10, 300), listOf(page.results.size, page.total))
        assertEquals(whole.results.take(10), page.results)
    }

    @Test
    fun foldReadsTheCapitalSharpSAndCapitals() {
        assertEquals("strasse", Fold.fold("STRAẞE"))
        assertEquals("ecole", Fold.fold("ÉCOLE"))
    }
}
