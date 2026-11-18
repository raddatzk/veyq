package me.raddatz.veyq.mobile.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The search field's model over a door the test plays (ADR 0146 decisions 5–10): the documents of the visible spaces only,
 * the open Memory's photos winning over the same medium from the space's listing, a result opened where it lives, the
 * members and the settings sections as results, chips chosen and removed, the listings asked by their tag and a space
 * whose listing fails named *not searched*. Real `AppKinds.search`: a Memory's facets are read from its meta.
 */
class SearchModelTest {
    private val s1 = SpaceRef("s1", "TENANT", "Family")
    private val s2 = SpaceRef("s2", "GROUP", "Club")
    private val key = ByteArray(32) { 1 }

    private fun node(scope: String, id: String, parent: String?, kind: String, name: String, meta: String? = null) =
        OpenNode(scope, id, parent, kind, name, key, wrappedMeta = meta)

    private val trees = mapOf(
        "s1" to listOf(
            node("s1", "trips", null, "FOLDER", "Trips"),
            node("s1", "rome", "trips", "MEMORY", "Rome 2024", meta = "sealed-rome"),
            node("s1", "pot", null, "EXPENSES_POT", "Kevin's pot"),
        ),
        "s2" to listOf(node("s2", "kev", null, "MEMORY", "Kevelaer")),
    )
    private val metas = mapOf("rome" to buildJsonObject {
        put("v", 1)
        putJsonObject("date") { put("mode", "single"); put("from", "2024-05-01"); put("to", "2024-05-01") }
        putJsonObject("participants") { put("userIds", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("u-anna")))) }
    })

    private inner class Door : SearchModel.Door {
        var visible = listOf(s1, s2)
        var where = Results.SearchContext("s1", null)
        val opened = ArrayList<String>()
        val listingTags = ArrayList<String?>()
        var listingFails = emptySet<String>()
        var listings: Map<String, List<ScopeDocument>> = mapOf(
            "s1" to listOf(ScopeDocument("memories.photo", "m1", "rome", "1:b1", "Rome beach.jpg", SearchFacets(capturedAt = 1_715_774_400_000L), "image")),
        )
        private var derived: Pair<Any, Any>? = null

        override suspend fun spaces() = visible
        @Suppress("UNCHECKED_CAST")
        override suspend fun <V : Any> fromTree(key: String, derive: suspend (Map<String, List<OpenNode>>) -> V): V {
            derived?.let { (from, v) -> if (from === trees) return v as V }
            return derive(trees).also { derived = trees to it }
        }
        override suspend fun treeMoved(scopeId: String) = false
        override fun notRead() = emptySet<String>()
        override fun openMeta(node: OpenNode): JsonObject? = metas[node.id]
        override fun online() = true
        override fun people(): Collection<Person> = people
        override suspend fun settings() = SettingsSection.offered(SettingsSection.Gates(addDevice = false, storage = true, maps = true, appearance = false))
        override fun where() = where
        override fun applicationsOff() = emptySet<String>()
        override suspend fun scopeDocuments(
            app: ApplicationSearch, scopeId: String, tag: String?, previous: Map<String, ScopeDocument>, nodes: Map<String, OpenNode>,
            unnamed: (Boolean) -> String,
        ): ScopeDocumentsAnswer? {
            listingTags += tag
            if (scopeId in listingFails) throw IllegalStateException("listing")
            if (tag == "t-$scopeId") return null
            return ScopeDocumentsAnswer("t-$scopeId", listings[scopeId].orEmpty())
        }
        override fun openNode(scopeId: String, nodeId: String) { opened += "node:$scopeId:$nodeId" }
        override fun openPerson(userId: String) { opened += "person:$userId" }
    }

    private val people = listOf(Person("u-anna", "anna", "Anna Schmidt"), Person("u-kevin", "kraddatz", "Kevin Raddatz"))
    private val door = Door()
    private val requests = OpenRequests()
    private val contributions = SearchContributions()
    private val words = SearchWords { msg ->
        when (msg) {
            Msg.SEARCH_KIND_PHOTO -> "Photo"
            Msg.SEARCH_KIND_VIDEO -> "Video"
            Msg.SEARCH_KIND_MEMORY -> "Memory"
            Msg.SEARCH_KIND_PERSON -> "Person"
            Msg.SEARCH_SETTINGS -> "Settings"
            Msg.SETTINGS_MAPS -> "Map and place names"
            Msg.SEARCH_WORDS_MAPS -> "map, places, location"
            else -> msg.key
        }
    }
    private val model = SearchModel(door, AppKinds.search, words, requests, contributions, { 1_763_208_000_000L })

    @AfterTest
    fun dispose() = model.dispose()

    private fun ask(text: String): SearchShown = runBlocking {
        model.text(text)
        model.settled()
        model.current
    }

    private fun started() = runBlocking {
        model.focus()
        model.settled()
    }

    private fun names(s: SearchShown) = s.results.map { r -> r.name.joinToString("") { it.text } }

    @Test
    fun aCalmFocusShowsNothingAndTheFirstCharacterAsks() {
        started()
        assertEquals(false, model.current.asked)
        assertTrue(model.current.results.isEmpty())
        assertTrue(ask("k").asked)
    }

    // ADR 0146 decision 10 — bite (g): a hidden space's node among the documents turns this red
    @Test
    fun aSpaceHiddenOnThisDeviceIsNotSearched() {
        started()
        assertEquals(listOf("Kevelaer", "Kevin Raddatz", "Kevin's pot"), names(ask("kev")).sorted())
        door.visible = listOf(s1)
        started()
        assertEquals(listOf("Kevin Raddatz", "Kevin's pot"), names(ask("ke")).filter { it.startsWith("Kev") }.sorted())
        assertTrue(ask("kevel").results.isEmpty(), "the hidden space's Memory is not found")
    }

    // ADR 0146 decision 8 — bite (h): the space's listing's medium beside the gallery's own turns this red
    @Test
    fun theOpenMemorysOwnPhotoWinsOverTheListingsAndOpensInPlace() {
        started()
        assertEquals(listOf("Rome beach.jpg"), names(ask("beach")))
        contributions.contribute(
            SearchContributions.Contribution("s1", "rome", listOf(ContributedDocument("memories.photo", "m1", "1:b1", "Rome beach.jpg", SearchFacets.NONE, "image"))),
        )
        door.where = Results.SearchContext("s1", "rome")
        val shown = ask("beac")
        assertEquals(listOf("Rome beach.jpg"), names(shown))
        runBlocking {
            model.open(0)
            model.settled()
        }
        assertEquals(OpenRequests.Request("s1", "rome", "m1"), requests.request.value)
        assertEquals(emptyList(), door.opened, "the open Memory's photo opens in place, no navigation")
    }

    @Test
    fun aPhotoAnotherSpacesListingFoundOpensItsMemoryWithARequest() {
        started()
        ask("beach")
        runBlocking {
            model.open(0)
            model.settled()
        }
        assertEquals(listOf("node:s1:rome"), door.opened)
        assertEquals(OpenRequests.Request("s1", "rome", "m1"), requests.request.value)
        // the gallery takes it once it holds the medium; one it cannot answer once settled is dropped
        assertNull(requests.take("s1", "other", { true }, settled = true))
        assertEquals("m1", requests.take("s1", "rome", { it == "m1" }, settled = false))
        assertNull(requests.request.value)
    }

    @Test
    fun aYearChipFromTheTextNarrowsToWhatSpansItAndBackspaceTakesItBack() {
        started()
        val offered = ask("2024")
        assertEquals("year:2024", offered.suggestions.first().id.removePrefix("chip:"))
        runBlocking {
            model.choose(0)
            model.settled()
        }
        val chosen = model.current
        assertEquals(listOf("year:2024"), chosen.chips.map { it.id })
        assertEquals("", chosen.text)
        assertEquals(listOf("Rome 2024", "Rome beach.jpg"), names(chosen).sorted())
        assertEquals(Notice(Msg.SEARCH_ANNOUNCE_CHIP_ADDED, "2024"), chosen.announcement.first())
        runBlocking {
            model.removeLastChip()
            model.settled()
        }
        assertTrue(model.current.chips.isEmpty())
        assertEquals(false, model.current.asked)
    }

    @Test
    fun aPersonIsAResultAndAChipOfWhatTheyTakePartIn() {
        started()
        val shown = ask("anna")
        assertEquals(listOf("Anna Schmidt"), names(shown))
        assertTrue(shown.suggestions.any { it.id == "chip:person:u-anna" })
        runBlocking {
            model.open(0)
            model.settled()
        }
        assertEquals(listOf("person:u-anna"), door.opened)
        runBlocking {
            model.text("anna")
            model.settled()
            model.choose(model.current.suggestions.indexOfFirst { it.id == "chip:person:u-anna" })
            model.settled()
        }
        assertEquals(listOf("Rome 2024"), names(model.current), "Person: Anna means what she takes part in, not her profile")
    }

    @Test
    fun aSettingsSectionIsFoundByItsWordAndOpensTheSettingsPageThere() {
        started()
        val shown = ask("locat")
        assertEquals(listOf("Map and place names"), names(shown))
        assertEquals(listOf(me.raddatz.veyq.mobile.words.Piece.Plain("Settings")), shown.results.single().line)
        runBlocking {
            model.open(0)
            model.settled()
        }
        assertEquals("maps", model.current.openSettings)
    }

    @Test
    fun aListingIsAskedByItsTagAndOneThatFailsNamesItsSpace() {
        started()
        started()
        assertEquals(listOf(null, null, "t-s1", "t-s2"), door.listingTags.take(4))
        door.listingFails = setOf("s2")
        started()
        assertEquals(Notice(Msg.SEARCH_NOT_SEARCHED, me.raddatz.veyq.mobile.words.Joined(listOf(Notice(Msg.SPACE_GROUP)), ", ")), ask("x").status.first())
        // its nodes are still searched
        assertEquals(listOf("Kevelaer"), names(ask("kevel")))
    }

    @Test
    fun nothingRunsOnceTheTenantClosed() {
        started()
        ask("rome")
        model.dispose()
        val before = model.current
        model.text("kev")
        runBlocking { kotlinx.coroutines.delay(200) }
        assertEquals(before, model.current)
    }
}
