package me.raddatz.veyq.mobile.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonObject

/**
 * The dispatch over the real core (CR D6), as the Linux host will drive it, on the JVM's suite and HTTP client: signs in
 * through a call as the seeded principal (`.run/.env`, or `VEYQ_ENV_FILE`), watches the shell open, reads Home's
 * groups, searches a name and opens the hit, and opens a Pot and reads its view to balances that sum to zero. The
 * device store is a host this test plays, answering from its own thread. Runs only when `VEYQ_LIVE` names an origin
 * of a running dev instance (`./gradlew backend:seed`).
 */
class LiveBridgeTest {
    @Test
    fun theSeededPrincipalSignsInAndReadsTheShellSearchAndAPotThroughCalls() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val envFile = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val env = envFile.readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()

        val kept = ConcurrentHashMap<String, String>()
        lateinit var host: Host
        host = Host { token, json ->
            thread {
                val reply = when (val r = wire.decodeFromString(HostRequest.serializer(), json)) {
                    is HostRequest.StoreRead -> HostReply.Value(kept[r.name])
                    is HostRequest.StoreWrite -> HostReply.Done.also { kept[r.name] = r.value }
                    is HostRequest.StoreRemove -> HostReply.Done.also { kept.remove(r.name) }
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply))
            }
        }
        val b = Bridge.live(BouncyCastlePrimitives, { block -> HttpClient(OkHttp, block) }, Config("bridge test"), HostStore(host))
        val gate = b.watching(Watched.Session)
        val shell = b.watching(Watched.Shell)

        assertEquals(Answer.Done, b.ask(Call.SessionStart))
        assertIs<Gate.SignIn>(gate.until { (it as Update.Session).gate.takeIf { g -> g is Gate.SignIn } })
        val signIn = Call.SessionSignIn(origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"))
        assertEquals(Answer.Done, b.ask(signIn, timeout = 2.minutes))
        val signed = gate.until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }
        println("signed in: $signed")

        val state = shell.until(1.minutes) { (it as Update.Shell).state?.takeIf { s -> s.lists.spaces.isNotEmpty() } }
        println("spaces: " + state.lists.spaces.joinToString { "${it.kind}:${it.name}" } + "; objects: ${state.lists.objects.size}")
        assertTrue(state.lists.spaces.any { it.kind == "PERSONAL" }, "the personal space did not open")
        assertTrue(kept.isNotEmpty(), "nothing reached the host's store") // the device id, the trust marks

        val groups = b.ask(Call.HomeGroups) as Answer.Groups
        println("Home: " + groups.groups.joinToString { g -> "${g.heading?.key}=${g.nodes.size}" })

        val pots = state.lists.objects.filter { it.application == ApplicationId.EXPENSES }
        val first = pots.first()
        val hits = b.ask(Call.SearchQuery(first.name.take(4).uppercase())) as Answer.Hits
        assertTrue(first in hits.hits, "search for ${first.name.take(4)} did not find ${first.name}")
        assertEquals(Answer.Done, b.ask(Call.ShellOpen(first)))
        assertEquals(first, shell.until { (it as Update.Shell).state?.shell?.openObject })

        // each Pot opened as its own model until one with entries, read as the host reads it: the view is what it shows,
        // each row with the core's words for it
        var withEntries = 0
        for (pot in pots) {
            val id = (b.ask(Call.PotOpen(pot)) as Answer.PotOpened).pot
            val view = b.watching(Watched.Pot(id)).untilJson(1.minutes) { u ->
                val s = u["state"] as? JsonObject
                assertTrue(s?.get("failed")?.jsonPrimitive?.boolean != true, "${pot.name} did not open")
                s?.get("view") as? JsonObject
            }
            assertEquals(Answer.Done, b.ask(Call.PotClose(id)))
            val entries = view.getValue("entries").jsonArray.map { it.jsonObject }
            val balances = view.getValue("balances").jsonArray.map { it.jsonObject }
            println("${view.getValue("name").jsonPrimitive.content}: ${entries.size} entries, balances " +
                balances.joinToString { "${it.getValue("row").jsonObject["name"]}=${it.getValue("row").jsonObject.getValue("balance")}" })
            assertEquals(0L, balances.sumOf { it.getValue("row").jsonObject.getValue("balance").jsonPrimitive.long }, "${pot.name}: the balances do not sum to zero")
            entries.forEach { assertTrue(it.getValue("line").jsonArray.isNotEmpty() || it.getValue("row").jsonObject["kind"] is JsonNull) }
            balances.forEach { assertTrue("msg" in it.getValue("balance").jsonObject.getValue("notice").jsonObject) }
            if (entries.isNotEmpty() && ++withEntries == 2) break
        }
        assertTrue(withEntries > 0, "no seeded Pot has entries")
        b.shutdown()
    }
}
