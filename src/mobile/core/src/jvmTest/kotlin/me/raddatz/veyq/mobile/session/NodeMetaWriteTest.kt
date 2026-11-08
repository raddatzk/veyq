package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.PotMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core's sealed-meta write (the web's `sealedMetaWrite.test.ts` and `nodeMeta.test.ts`): only an absent blob is fresh,
 * siblings survive, and a stale answer re-runs only the writer's own change on what the server holds.
 */
class NodeMetaWriteTest {
    private val suite = V1(BouncyCastlePrimitives)
    private val scope = "5c000000-0000-4000-8000-000000000001"
    private val node = "5c000000-0000-4000-8000-000000000002"
    private val key = ByteArray(32) { 7 }

    private fun open(w: String?) = NodeMetaWrite.openForWrite(suite, scope, node, key, w)
    private fun seal(m: JsonObject) = NodeMetaWrite.seal(suite, scope, node, key, m)
    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun onlyAnAbsentBlobIsFresh() {
        assertEquals(JsonObject(mapOf("v" to JsonPrimitive(1))), open(null))
        val other = NodeMetaWrite.seal(suite, scope, "5c000000-0000-4000-8000-000000000003", key, obj("cover" to "x"))
        assertFailsWith<NodeMetaWrite.Unreadable> { open(other) }
        assertFailsWith<NodeMetaWrite.Unreadable> { open("AAAA") }
    }

    @Test
    fun aSealKeepsEverySiblingAndSaysVersionOne() {
        val opened = open(seal(JsonObject(obj("cover" to "c", "place" to "Rome") + ("v" to JsonPrimitive(9)))))
        assertEquals("c", opened["cover"]!!.jsonPrimitive.content)
        assertEquals("Rome", opened["place"]!!.jsonPrimitive.content)
        assertEquals(1, opened["v"]!!.jsonPrimitive.content.toInt())
        // A blob another writer sealed with another version opens as version 1 for a write too.
        val foreign = me.raddatz.veyq.mobile.crypto.b64(
            suite.sealField(NodeMetaWrite.context(scope, node), key, """{"v":9,"cover":"c"}""".encodeToByteArray()),
        )
        assertEquals(1, open(foreign)["v"]!!.jsonPrimitive.content.toInt())
    }

    private val potMeta = PotMeta(Ledger.BaseCurrency("EUR", 2), emptyList())
    private val cat1 = "0c000000-0000-4000-8000-000000000001"
    private val cat2 = "0c000000-0000-4000-8000-000000000002"

    /** A server holding one blob: a send at its revision is taken, any other is answered stale with what it holds. */
    private inner class Server(var state: NodeMetaWrite.State) {
        var sends = 0
        suspend fun send(w: String, rev: Long): NodeMetaWrite.Answer {
            sends++
            if (rev != state.metaRevision) return NodeMetaWrite.Answer.Stale(state)
            state = NodeMetaWrite.State(w, rev + 1)
            return NodeMetaWrite.Answer.Taken
        }
    }

    private fun addCategory(id: String, name: String): (JsonObject) -> JsonObject? = { env ->
        val now = PotMeta.of(env)!!
        JsonObject(env + (PotMeta.KEY to now.copy(categories = now.categories + (id to name)).toJson()))
    }

    @Test
    fun aStaleWriteReRunsItsOwnChangeOnWhatTheServerHolds() = runBlocking {
        val start = seal(JsonObject(mapOf(PotMeta.KEY to potMeta.toJson(), "cover" to JsonPrimitive("keep"))))
        // Another member added a category since this one read revision 3.
        val theirs = seal(JsonObject(open(start) + (PotMeta.KEY to potMeta.copy(categories = listOf(cat1 to "Fuel")).toJson())))
        val server = Server(NodeMetaWrite.State(theirs, 4))
        val r = NodeMetaWrite.write(NodeMetaWrite.State(start, 3), ::open, ::seal, addCategory(cat2, "Gifts")) { w, rev -> server.send(w, rev) }
        assertTrue(r is NodeMetaWrite.Result.Wrote)
        assertEquals(NodeMetaWrite.State(server.state.wrappedMeta, 5), r.state)
        assertEquals(2, server.sends)
        val stored = open(server.state.wrappedMeta)
        assertEquals(listOf(cat1 to "Fuel", cat2 to "Gifts"), PotMeta.of(stored)!!.categories)
        assertEquals("keep", stored["cover"]!!.jsonPrimitive.content)
        assertEquals(5, server.state.metaRevision)
    }

    @Test
    fun noWriteIsSentWhenTheEditAsksForNone() = runBlocking {
        val server = Server(NodeMetaWrite.State(null, 0))
        val r = NodeMetaWrite.write(NodeMetaWrite.State(null, 0), ::open, ::seal, { null }) { w, rev -> server.send(w, rev) }
        assertTrue(r is NodeMetaWrite.Result.Unchanged)
        assertEquals(0, server.sends)
    }

    @Test
    fun theReBaseIsBoundedAndAnotherRefusalIsHandedBack() = runBlocking {
        var sends = 0
        val stale = NodeMetaWrite.write(NodeMetaWrite.State(null, 0), ::open, ::seal, { it }) { _, _ ->
            sends++
            NodeMetaWrite.Answer.Stale(NodeMetaWrite.State(null, sends.toLong()))
        }
        assertEquals(NodeMetaWrite.Result.Refused(409, "stale_revision"), stale)
        assertEquals(NodeMetaWrite.MAX_ATTEMPTS, sends)
        val refused = NodeMetaWrite.write(NodeMetaWrite.State(null, 0), ::open, ::seal, { it }) { _, _ -> NodeMetaWrite.Answer.Refused(409, "stale_epoch") }
        assertEquals(NodeMetaWrite.Result.Refused(409, "stale_epoch"), refused)
    }

    @Test
    fun theStaleBodyIsReadForItsFieldsOnly() {
        assertEquals(NodeMetaWrite.State("abc", 7), NodeMetaWrite.staleOf("""{"code":"stale_revision","wrappedMeta":"abc","metaRevision":7}"""))
        assertEquals(NodeMetaWrite.State(null, 7), NodeMetaWrite.staleOf("""{"code":"stale_revision","metaRevision":7}"""))
        assertNull(NodeMetaWrite.staleOf("""{"code":"stale_epoch","metaRevision":7}"""))
        assertNull(NodeMetaWrite.staleOf("""{"code":"stale_revision","metaRevision":"7"}"""))
        assertNull(NodeMetaWrite.staleOf("not json"))
    }

    @Test
    fun aPotObjectIsWrittenOnlyAsItsReaderReadsIt() {
        val json = potMeta.copy(categories = listOf(cat1 to "Fuel")).toJson()
        assertEquals("EUR", json["baseCurrency"]!!.jsonPrimitive.content)
        assertEquals("Fuel", json["categories"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(potMeta.toJson()["categories"])
        assertEquals(potMeta, PotMeta.read(Json.parseToJsonElement(potMeta.toJson().toString())))
        assertFailsWith<IllegalArgumentException> { potMeta.copy(categories = listOf(cat1 to "")).toJson() }
        assertFailsWith<IllegalArgumentException> { PotMeta(Ledger.BaseCurrency("eur", 2), emptyList()).toJson() }
    }
}
