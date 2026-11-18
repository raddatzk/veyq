package me.raddatz.veyq.mobile.search

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.media.ByteSource
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.memories.place.MemoryDate
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.memories.place.MemoryLocation
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The apps' search against a running dev instance (ADR 0146; `VEYQ_LIVE`, skipped without one).
 * The dev principal makes a Memory in its tenant's space — itself a participant (*I was there*), a date and a place set,
 * a photo uploaded into it — and searches standing in its personal space: the Memory found by its name from the other
 * space, a year chip, a person chip, *In:* the Memory listing its photo, the photo found by the space's listing and
 * opened with a request the Memory's gallery takes (its lightbox on it); a rename made by a second session seen after the
 * next focus revalidates the trees; and a further focus with nothing moved reading each space's tree and listing as one
 * `304` each, counted at the transport. The Memory (and its photo) is trashed and purged and every device taken off the
 * account however the case ends; no device trust is enrolled.
 */
class SearchLiveTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    /** Every `listNodes` and `listScopeMedia` answer, as path and status. */
    private val reads = ConcurrentLinkedQueue<Pair<String, Int>>()
    private val counted = createClientPlugin("SearchLiveReads") {
        onResponse { r ->
            val path = r.call.request.url.encodedPath
            if (path.matches(Regex("/api/scopes/[^/]+/(nodes|media)"))) reads += path to r.status.value
        }
    }
    private val client: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(OkHttp) { block(); install(counted) } }

    private val root = File(System.getProperty("veyq.repoRoot"))

    /** The apps' English words, from the catalog as every platform reads it. */
    private val english: Map<String, String> by lazy {
        Json.parseToJsonElement(File(root, "src/mobile/words/words.json").readText()).jsonObject.mapValues { (_, v) ->
            when (val en = v.jsonObject["en"]) {
                is JsonPrimitive -> en.content
                is JsonObject -> en["other"]!!.jsonPrimitive.content
                else -> ""
            }
        }
    }
    private val words = SearchWords { msg -> english[msg.key] ?: msg.key }

    private suspend fun signIn(): TenantSession {
        val env = liveRun.env
        val account = VeyqAccount.signIn(
            BouncyCastlePrimitives, liveRun.origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"),
            liveRun.device("live search test"), client,
        )
        return account.open(account.defaultTenant!!)
    }

    private fun SearchShown.names() = results.map { r -> r.name.joinToString("") { it.text } }

    private suspend fun SearchModel.typed(text: String): SearchShown {
        text(text)
        settled()
        return current
    }

    private suspend fun SearchModel.chose(key: String): SearchShown {
        val at = current.suggestions.indexOfFirst { it.id == key }
        assertTrue(at >= 0, "no suggestion $key among ${current.suggestions.map { it.id }}")
        choose(at)
        settled()
        return current
    }

    @Test
    fun theWholeTenantIsSearchedFromAnotherSpace_aPhotoOpensInItsMemory_andAnIdleFocusIsOne304EachSpaceAndListing() = runBlocking {
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", liveRun.origin != null)
        val tenant = signIn()
        val shell = ShellModel(tenant, Hidden({}, emptySet()), RecentUses({}, emptyList())) { System.currentTimeMillis() }
        shell.load(openLastUsed = false)
        val spaces = shell.state.first { it != null }!!.lists.spaces
        val tenantSpace = assertNotNull(spaces.firstOrNull { it.kind == "TENANT" }?.scopeId, "no tenant space")
        val personalSpace = assertNotNull(spaces.firstOrNull { it.kind == "PERSONAL" }?.scopeId, "no personal space")
        val me = tenant.account.userId
        val myName = assertNotNull(tenant.people[me]?.displayName, "the roster names the principal")
        val n = System.currentTimeMillis() % 100_000
        val name = "Searchlive $n"
        var memory: String? = null
        val model = shell.searchModel(words, appearance = false)
        try {
            memory = assertNotNull(shell.createMemory(tenantSpace, null, name, wasThere = true).node).nodeId
            assertEquals(MemoryFacts.Outcome.Written, MemoryFacts.setDate(tenant, tenantSpace, memory, MemoryDate(MemoryDate.Mode.SINGLE, "2019-08-15", "2019-08-15")))
            assertEquals(
                MemoryFacts.Outcome.Written,
                MemoryFacts.setLocation(tenant, tenantSpace, memory, MemoryLocation("Zwolleplace $n", MemoryLocation.NameSource.MANUAL, null, MemoryLocation.DerivedMode.AUTO)),
            )
            val photo = File(root, "src/web/src/features/memories/__fixtures__/exif-sample.jpg").readBytes()
            val up = MediaUpload.upload(
                tenant, tenantSpace, memory,
                MediaUpload.Original(ByteSource(photo.size.toLong()) { from, length -> photo.copyOfRange(from.toInt(), minOf(photo.size.toLong(), from + length).toInt()) }, "image/jpeg", "Beachphoto $n.jpg", 1_000_000),
                MediaUpload.Derived(photo, photo, 4032, 3024), System.currentTimeMillis(),
            )
            assertEquals(MediaUpload.Outcome.DONE, up.outcome, up.code)
            val mediaId = assertNotNull(up.mediaId)
            shell.refresh()
            // standing in the personal space: the Memory lies in another
            shell.switchSpace(personalSpace)

            model.focus()
            model.settled()
            val byName = model.typed(name)
            assertEquals(name, byName.names().first(), "the Memory found by its name from another space")
            val year = model.typed("2019")
            assertTrue(year.suggestions.any { it.id == "chip:year:2019" })
            assertTrue(name in model.chose("chip:year:2019").names(), "a year chip lists the Memory dated in it")
            model.removeLastChip()
            model.settled()
            model.typed(myName.split(' ').first().take(5))
            assertTrue(name in model.chose("chip:person:$me").names(), "a person chip lists the Memory they take part in")
            model.removeLastChip()
            model.settled()
            model.typed("Searchlive")
            val inChip = model.chose("chip:in:$tenantSpace:$memory")
            assertEquals(listOf("Beachphoto $n.jpg"), inChip.names(), "In: the Memory lists its photo, the Memory itself not")
            model.removeLastChip()
            model.settled()

            // the photo found by the space's listing, opened from another space: its Memory opens with a request
            val found = model.typed("Beachphoto $n")
            assertEquals(listOf("Beachphoto $n.jpg"), found.names())
            model.open(0)
            model.settled()
            assertEquals(OpenRequests.Request(tenantSpace, memory, mediaId), shell.openRequests.request.value)
            assertEquals(memory, shell.state.value!!.shell.openObject?.nodeId, "the Memory opened")
            val uploads = shell.mediaUploads(UploadQueue(), object : MediaUpload.Deriver {
                override fun derive(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (MediaUpload.Derived) -> Unit) = error("no upload")
            }, {}, {})
            val gallery = shell.galleryModel(NodeAt(tenantSpace, memory, "MEMORY", name), uploads)
            try {
                val taken = gallery.state.first { it.searchOpened != null }
                assertEquals(mediaId, taken.searchOpened, "the gallery took the request: its lightbox on the photo")
                assertEquals(null, shell.openRequests.request.value)
                // the open Memory's own photo is its gallery's contribution now
                assertEquals(memory, shell.searchContributions.current.value?.nodeId)
            } finally {
                gallery.close()
            }

            // a rename from a second session: seen after the next focus revalidates the trees
            val other = signIn()
            val renamed = "$name renamed"
            assertEquals(NodeRename.Outcome.RENAMED, NodeRename.rename(other, tenantSpace, memory, renamed, AppKinds::tokenInput, System.currentTimeMillis()).outcome)
            assertTrue(model.typed(renamed).names().isEmpty(), "not seen before the focus revalidates")
            model.focus()
            model.settled()
            model.text("")
            assertEquals(renamed, model.typed(renamed).names().firstOrNull(), "the rename seen after the trees revalidated")

            // a further focus with nothing moved: one 304 per space's tree and listing, nothing else read
            model.settled()
            reads.clear()
            model.focus()
            model.settled()
            val searched = spaces.map { it.scopeId }.filter { it !in tenant.closed.keys }
            val idle = reads.toList()
            println("veyq-live: an idle focus read ${idle.size} trees and listings over ${searched.size} spaces: ${idle.map { it.second }}")
            assertTrue(idle.all { it.second == 304 }, "an idle focus read something whole: $idle")
            assertEquals(searched.size * 2, idle.size, "one tree and one listing per space: $idle")
        } finally {
            model.dispose()
            memory?.let { m ->
                runCatching { NodeTrash.delete(tenant, tenantSpace, m) }
                println("veyq-live: purged the case's Memory: ${runCatching { NodeTrash.purge(tenant, tenantSpace, m) }.getOrNull()}")
            }
        }
        Unit
    }
}
