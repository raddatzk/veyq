package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.memories.models.ItemFavoritesEntry
import me.raddatz.veyq.mobile.api.memories.models.PutItemFavoritesRequest
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.search.SearchChip
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A gallery's filter (ADR 0149 decision 5), the web's `galleryFilter.test.ts` and `MemoryDetail.filter.test.ts` twinned: a
 * day chip, the search's year, place and kind chips, free text over the original's name and the marks' mode, ANDed into one
 * predicate; the mode ignored in a personal scope; the day chips offered for the typed text.
 */
class GalleryFilterTest {
    private val day = 86_400_000L
    private val may3 = 1_746_230_400_000L // 2025-05-03T00:00Z

    /** A medium's id by its letter (the marks' seal binds real ids); the tests speak in letters. */
    private fun u(letter: String) = "00000000-0000-4000-8000-00000000000$letter"

    private fun item(id: String, name: String, at: Long?, place: String? = null, video: Boolean = false, owner: String? = "user:u") = MemoryMedia.Item(
        "s", "n", u(id), MemoryMedia.Blob("b$id", 1, 100, "", null), null, null,
        MemoryMedia.Meta(null, null, at, null, null, null, null, place, null),
        if (video) "video/mp4" else "image/jpeg", name, owner, false, ByteArray(32),
    )

    private val items = listOf(
        item("a", "Beach.jpg", may3 + 3_600_000, "Lisboa"),
        item("b", "Bridge.jpg", may3 + 3_600_000, "Porto"),
        item("c", "Beach evening.mp4", may3 + day + 3_600_000, "Lisboa", video = true),
        item("d", "Old.jpg", may3 - 400 * day, "Lisboa"),
        item("e", "Undated.jpg", null),
    )
    private val facts = GalleryFilter.Facts.of(items, GalleryFilter.Calendar.UTC.dayOf, GalleryFilter.Calendar.UTC.label)

    /** Marks where "ana" marked a and c, and "ben" (the caller) marked b. */
    private suspend fun marks(personal: Boolean = false): ItemMarks {
        val p = testPrimitives
        val suite = V1(p)
        val key = p.randomBytes(32)
        val sets = LinkedHashMap<String, PutItemFavoritesRequest>()
        fun member(name: String, mk: ByteArray) = ItemMarks("7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c", "4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d", personal, suite, p, object : ItemMarks.Store {
            override suspend fun list() = sets.map { (who, r) -> ItemFavoritesEntry(who == name, r.ciphertext, r.wrappedValueKey) }
            override fun key() = key.copyOf() to 1L
            override fun selfKey() = me.raddatz.veyq.mobile.crypto.ItemMarks(suite).selfKey(mk)
            override suspend fun put(request: PutItemFavoritesRequest): Int { sets[name] = request; return 0 }
            override suspend fun reopen() = key.copyOf() to 1L
        })
        val ana = member("ana", p.randomBytes(32)).also { it.load(); it.toggle(u("a")); it.toggle(u("c")) }
        check(ana.mine() == setOf(u("a"), u("c")))
        return member("ben", p.randomBytes(32)).also { it.load(); it.toggle(u("b")) }
    }

    private fun shown(state: GalleryFilter.State, m: ItemMarks? = null, personal: Boolean = false): List<String> {
        val keep = GalleryFilter.predicate(state, { facts }, m, personal) ?: return items.map { it.mediaId.takeLast(1) }
        return items.map { it.mediaId }.filter(keep).map { it.takeLast(1) }
    }

    @Test
    fun nothingNarrows_noPredicate() {
        assertNull(GalleryFilter.predicate(GalleryFilter.State(), { facts }, null, false))
    }

    @Test
    fun aDayAPlaceAndTheTextAreAnded() {
        val dayChip = GalleryFilter.Chip.Day("2025-05-03", "2025-05-03")
        assertEquals(listOf("a", "b"), shown(GalleryFilter.State(chips = listOf(dayChip))))
        val lisboa = GalleryFilter.Chip.Search(SearchChip.Place("lisboa", "Lisboa"))
        assertEquals(listOf("a"), shown(GalleryFilter.State(chips = listOf(dayChip, lisboa))))
        assertEquals(listOf("a", "c"), shown(GalleryFilter.State(chips = listOf(lisboa), text = "beach")), "the text over the original's name")
        assertEquals(listOf("c"), shown(GalleryFilter.State(chips = listOf(GalleryFilter.Chip.Search(SearchChip.Kind(listOf(MemoriesSearch.VIDEO), "Video"))))))
        assertEquals(listOf("d"), shown(GalleryFilter.State(chips = listOf(GalleryFilter.Chip.Search(SearchChip.Year(2024))))))
        assertEquals(emptyList(), shown(GalleryFilter.State(text = "porto")), "a place is a chip's, never the text's")
    }

    @Test
    fun theMarksModeAndsWithTheFacts_andIsIgnoredInAPersonalScope() = runTest {
        val m = marks()
        assertEquals(listOf("a", "b", "c"), shown(GalleryFilter.State(mode = GalleryFilter.Mode.EVERYONE), m))
        assertEquals(listOf("b"), shown(GalleryFilter.State(mode = GalleryFilter.Mode.MINE), m))
        val dayChip = GalleryFilter.Chip.Day("2025-05-03", "2025-05-03")
        assertEquals(listOf("a", "b"), shown(GalleryFilter.State(mode = GalleryFilter.Mode.EVERYONE, chips = listOf(dayChip)), m))
        assertEquals(listOf("b"), shown(GalleryFilter.State(mode = GalleryFilter.Mode.MINE, chips = listOf(dayChip), text = "bridge"), m), "day ∧ text ∧ mine")
        // a personal scope has no mode: every medium, whatever the state says
        assertEquals(items.map { it.mediaId.takeLast(1) }, shown(GalleryFilter.State(mode = GalleryFilter.Mode.MINE), m, personal = true))
    }

    @Test
    fun theDaysAndYearsOccurringAreOffered() {
        assertEquals(listOf("2024-03-29", "2025-05-03", "2025-05-04"), facts.days.keys.toList())
        assertEquals(setOf(2024, 2025), facts.years)
        val offered = GalleryFilter.suggest("2025-05", emptyList(), facts, "Photo", "Video").map { it.first }
        assertEquals(listOf("day:2025-05-03", "day:2025-05-04"), offered.filterIsInstance<GalleryFilter.Chip.Day>().map(GalleryFilter::key))
        val taken = GalleryFilter.suggest("2025-05", listOf(GalleryFilter.Chip.Day("2025-05-03", "2025-05-03")), facts, "Photo", "Video").map { it.first }
        assertEquals(listOf("day:2025-05-04"), taken.filterIsInstance<GalleryFilter.Chip.Day>().map(GalleryFilter::key), "a chip chosen is not offered again")
        assertEquals(true, GalleryFilter.suggest("lis", emptyList(), facts, "Photo", "Video").any { (it.first as? GalleryFilter.Chip.Search)?.chip is SearchChip.Place })
    }

    /** Who added it (inbox #1356): Ana a, c and d; Ben, a link's guest, b; e carries no owner. */
    private val added = listOf(
        item("a", "Beach.jpg", may3 + 3_600_000, owner = "user:ana"),
        item("b", "Bridge.jpg", may3 + 3_600_000, owner = "share:ben"),
        item("c", "Beach evening.mp4", may3 + day + 3_600_000, video = true, owner = "user:ana"),
        item("d", "Old.jpg", may3 - 400 * day, owner = "user:ana"),
        item("e", "Undated.jpg", null, owner = null),
    )
    private val addedFacts = GalleryFilter.Facts.of(added, GalleryFilter.Calendar.UTC.dayOf, GalleryFilter.Calendar.UTC.label)
    private val names = mapOf("user:ana" to "Ana", "share:ben" to "Ben (guest)")

    private fun kept(state: GalleryFilter.State): List<String> {
        val keep = GalleryFilter.predicate(state, { addedFacts }, null, false) ?: return added.map { it.mediaId.takeLast(1) }
        return added.map { it.mediaId }.filter(keep).map { it.takeLast(1) }
    }

    @Test
    fun whoAddedIt_isAndedWithTheOtherChipsAndTheText() {
        assertEquals(listOf("user:ana", "share:ben"), addedFacts.contributors.toList())
        val ana = GalleryFilter.Chip.AddedBy("user:ana", "Ana")
        val ben = GalleryFilter.Chip.AddedBy("share:ben", "Ben (guest)")
        assertEquals(listOf("a", "c", "d"), kept(GalleryFilter.State(chips = listOf(ana))))
        assertEquals(listOf("b"), kept(GalleryFilter.State(chips = listOf(ben))))
        assertEquals(listOf("a"), kept(GalleryFilter.State(chips = listOf(ana, GalleryFilter.Chip.Day("2025-05-03", "2025-05-03")))))
        assertEquals(listOf("c"), kept(GalleryFilter.State(chips = listOf(ana), text = "evening")))
        assertEquals(emptyList(), kept(GalleryFilter.State(chips = listOf(ana, ben))), "two contributors at once: nothing is both's")
        assertEquals("by:share:ben", GalleryFilter.key(ben))
    }

    @Test
    fun whoAddedIt_isOfferedByName_onlyWhereMoreThanOneContributed() {
        fun by(text: String, chips: List<GalleryFilter.Chip> = emptyList(), facts: GalleryFilter.Facts = addedFacts, named: Map<String, String> = names) =
            GalleryFilter.suggest(text, chips, facts, "Photo", "Video", named).filter { it.first is GalleryFilter.Chip.AddedBy }
                .map { (c, rest) -> "${(c as GalleryFilter.Chip.AddedBy).name}|$rest" }
        assertEquals(listOf("Ben (guest)|"), by("ben"))
        assertEquals(listOf("Ana|beach"), by("beach an"), "the longest tail naming one; the rest stays text")
        assertEquals(emptyList(), by("ben", listOf(GalleryFilter.Chip.AddedBy("share:ben", "Ben (guest)"))), "a chip chosen is not offered again")
        assertEquals(emptyList(), by("ben", named = mapOf("user:ana" to "Ana")), "one the platform did not name is not offered")
        val one = GalleryFilter.Facts.of(added.filter { it.ownerKey != "share:ben" }, GalleryFilter.Calendar.UTC.dayOf, GalleryFilter.Calendar.UTC.label)
        assertEquals(emptyList(), by("ana", facts = one), "one contributor narrows nothing")
    }

    @Test
    fun aContributorIsNamedAsTheContributionsTabNamesThem() {
        val member = MediaDetails.Uploader(MediaDetails.UploaderKind.MEMBER, "Ana", "ana")
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("Ana"), GalleryFilter.contributorName("user:ana", member))
        fun said(p: me.raddatz.veyq.mobile.words.Piece) = (p as me.raddatz.veyq.mobile.words.Piece.Said).notice.let { it.msg to it.args }
        assertEquals(me.raddatz.veyq.mobile.words.Msg.MEMORY_BUCKETS_MEMBER to emptyList<Any>(), said(GalleryFilter.contributorName("user:old", MediaDetails.Uploader(MediaDetails.UploaderKind.FORMER, userId = "old"))))
        assertEquals(me.raddatz.veyq.mobile.words.Msg.MEMORY_BUCKETS_MEMBER to emptyList<Any>(), said(GalleryFilter.contributorName("user:unread", null)))
        assertEquals(me.raddatz.veyq.mobile.words.Msg.MEMORY_UPLOADER_GUEST_NAMED to listOf<Any>("Ben"), said(GalleryFilter.contributorName("share:ben", MediaDetails.Uploader(MediaDetails.UploaderKind.GUEST, "Ben"))))
        assertEquals(me.raddatz.veyq.mobile.words.Msg.MEMORY_BUCKETS_CONTRIBUTOR to emptyList<Any>(), said(GalleryFilter.contributorName("share:x", MediaDetails.Uploader(MediaDetails.UploaderKind.GUEST))))
    }
}
