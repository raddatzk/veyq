package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.search.ApplicationSearch
import me.raddatz.veyq.mobile.search.OpenRequests
import me.raddatz.veyq.mobile.search.Results
import me.raddatz.veyq.mobile.search.ScopeDocument
import me.raddatz.veyq.mobile.search.ScopeDocumentsAnswer
import me.raddatz.veyq.mobile.search.SearchContributions
import me.raddatz.veyq.mobile.search.SearchModel
import me.raddatz.veyq.mobile.search.SearchWords
import me.raddatz.veyq.mobile.search.SettingsSection
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search through the bridge (ADR 0146 decision 13): `Search.Open` answered with an id, `Watched.Search` delivering core's
 * `SearchShown` as JSON, every call reaching core's own `SearchModel` — the text in the host's order, a suggestion chosen
 * into a chip, Backspace on an empty field, a result opened where it lives, a settings result naming its section — the
 * words the host's, and no search outliving its tenant: a sign-out disposes it, its watch delivers null and its calls are
 * refused. The tenant is played (a door of the test's own under the real `SearchModel` and `AppKinds.search`).
 */
class SearchDispatchTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    private class Door : SessionModel.Door<Acc, String> {
        val account = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example")))
        override val primitives = BouncyCastlePrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String) = account
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("no kit")
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = error("no trusted device")
        override suspend fun resume(record: TrustedDevice) = error("no trusted device")
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = emptySet<StaySignedIn>()
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
    }

    /** A tenant the test plays to the search: two spaces' trees, a photo listed in one, two members; what it opened. */
    private class PlayedTenant : SearchModel.Door {
        private val key = ByteArray(32) { 2 }
        private val trees = mapOf(
            "s1" to listOf(OpenNode("s1", "trips", null, "FOLDER", "Trips", key), OpenNode("s1", "rome", "trips", "MEMORY", "Rome 2024", key)),
            "s2" to listOf(OpenNode("s2", "kev", null, "MEMORY", "Kevelaer", key)),
        )
        val opened = CopyOnWriteArrayList<String>()
        override suspend fun spaces() = listOf(SpaceRef("s1", "TENANT", "Family"), SpaceRef("s2", "GROUP", "Club"))
        override suspend fun <V : Any> fromTree(key: String, derive: suspend (Map<String, List<OpenNode>>) -> V): V = derive(trees)
        override suspend fun treeMoved(scopeId: String) = false
        override fun notRead() = emptySet<String>()
        override fun openMeta(node: OpenNode): JsonObject? = null
        override fun online() = true
        override fun people() = listOf(Person("u-anna", "anna", "Anna Schmidt"))
        override suspend fun settings() = SettingsSection.offered(SettingsSection.Gates(addDevice = false, storage = false, maps = true))
        override fun where() = Results.SearchContext("s1", null)
        override fun applicationsOff() = emptySet<String>()
        override suspend fun scopeDocuments(
            app: ApplicationSearch, scopeId: String, tag: String?, previous: Map<String, ScopeDocument>, nodes: Map<String, OpenNode>,
            unnamed: (Boolean) -> String,
        ) = ScopeDocumentsAnswer("t", if (scopeId == "s1") listOf(ScopeDocument("memories.photo", "m1", "rome", "1:b", "Romebeach.jpg", me.raddatz.veyq.mobile.search.SearchFacets.NONE, "image")) else emptyList())
        override fun openNode(scopeId: String, nodeId: String) { opened += "node:$scopeId:$nodeId" }
        override fun openPerson(userId: String) { opened += "person:$userId" }
    }

    /** The host's words, played: the kinds and the settings section this test reads, every other word its key. */
    private val words = SearchWords { msg ->
        when (msg) {
            Msg.SETTINGS_MAPS -> "Map and place names"
            Msg.SEARCH_WORDS_MAPS -> "map, places, location"
            Msg.SEARCH_SETTINGS -> "Settings"
            else -> msg.key
        }
    }

    private fun bridge(played: PlayedTenant, requests: OpenRequests) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(SessionModel(Door(), hidden), hidden, JvmFiles, words = words) { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = kotlinx.coroutines.flow.MutableStateFlow(emptyMap()), loadPictures = {},
                search = { w -> SearchModel(played, AppKinds.search, w, requests, SearchContributions(), { 1_763_208_000_000L }) },
            )
        }
    }

    private suspend fun Bridge<*, *>.signedIn() {
        val gate = watching(Watched.Session)
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        gate.until { (it as Update.Session).gate as? Gate.Signed }
        unwatch(gate.id)
    }

    private fun me.raddatz.veyq.mobile.search.SearchShown.names() = results.map { r -> r.name.joinToString("") { it.text } }

    @Test
    fun aSearchRunsThroughItsCallsAndGoesWithItsTenant() = runBlocking {
        val played = PlayedTenant()
        val requests = OpenRequests()
        val b = bridge(played, requests)
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), b.ask(Call.SearchOpen))
        b.signedIn()
        val search = (b.ask(Call.SearchOpen) as Answer.SearchOpened).search
        val field = b.watching(Watched.Search(search))
        assertEquals(Answer.Done, b.ask(Call.SearchFocus(search)))
        // the text in the host's order: the last keystroke is the one the field ends on
        for (t in listOf("K", "Ke", "Kev")) assertEquals(Answer.Done, b.ask(Call.SearchText(search, t)))
        val kev = field.until { u -> (u as Update.Search).shown?.takeIf { it.text == "Kev" && it.results.isNotEmpty() } }
        assertEquals(listOf("Kevelaer"), kev.names())
        assertTrue(kev.announcement.isNotEmpty())

        // a suggestion into a chip, and Backspace on the empty field takes it back
        assertEquals(Answer.Done, b.ask(Call.SearchText(search, "2024")))
        val year = field.until { u -> (u as Update.Search).shown?.takeIf { s -> s.suggestions.any { it.id == "chip:year:2024" } } }
        assertEquals(Answer.Done, b.ask(Call.SearchChoose(search, year.suggestions.indexOfFirst { it.id == "chip:year:2024" })))
        val chosen = field.until { u -> (u as Update.Search).shown?.takeIf { it.chips.isNotEmpty() } }
        assertEquals(listOf("year:2024"), chosen.chips.map { it.id })
        assertEquals("", chosen.text)
        assertEquals(Answer.Done, b.ask(Call.SearchRemoveLastChip(search)))
        field.until { u -> (u as Update.Search).shown?.takeIf { it.chips.isEmpty() && !it.asked } }

        // a photo of the listing opened from where it lies: its Memory, with the request its gallery takes
        assertEquals(Answer.Done, b.ask(Call.SearchText(search, "romebeach")))
        field.until { u -> (u as Update.Search).shown?.takeIf { it.names() == listOf("Romebeach.jpg") } }
        assertEquals(Answer.Done, b.ask(Call.SearchOpenResult(search, 0)))
        kotlinx.coroutines.withTimeout(10_000) { while (played.opened.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("node:s1:rome"), played.opened)
        assertEquals(OpenRequests.Request("s1", "rome", "m1"), requests.request.value)

        // a settings section, in the host's words: the field names its section for the host to open
        assertEquals(Answer.Done, b.ask(Call.SearchText(search, "locat")))
        field.until { u -> (u as Update.Search).shown?.takeIf { it.names() == listOf("Map and place names") } }
        assertEquals(Answer.Done, b.ask(Call.SearchOpenResult(search, 0)))
        assertEquals("maps", field.until { u -> (u as Update.Search).shown?.openSettings })
        assertEquals(Answer.Done, b.ask(Call.SearchMore(search)))
        assertEquals(Answer.Done, b.ask(Call.SearchClose(search)))
        field.until { u -> (u as Update.Search).shown?.takeIf { it.text == "" && !it.asked } }

        // the tenant closes: the search is disposed, its watch delivers null, its calls are refused
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut("a.example#u1")))
        field.until { u -> (u as Update.Search).takeIf { it.shown == null } }
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_SEARCH), b.ask(Call.SearchText(search, "x")), "a search outlived its tenant")
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_SEARCH), b.ask(Call.SearchFocus(search)))
        assertEquals(Update.Search(search, null), b.watching(Watched.Search(search)).next())
        b.shutdown()
    }
}
