package me.raddatz.veyq.mobile.expenses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unhex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Expenses port against `contracts/expenses/v1-vectors.json` — every conversion and rounding case and every whole
 * Pot, as the web's `ledger.test.ts` runs them — and against `contracts/crypto/vectors/v1.json`'s `expense-entry` seal,
 * whose plaintext is a padded revision this reader must accept.
 */
class ExpensesVectorsTest {
    private val root = File(System.getProperty("veyq.repoRoot"))
    private val vectors = Json.parseToJsonElement(File(root, "contracts/expenses/v1-vectors.json").readText()).jsonObject
    private val suite = V1(BouncyCastlePrimitives)

    private fun JsonElement.str(k: String) = jsonObject[k]!!.jsonPrimitive.content
    private fun JsonElement.long(k: String) = jsonObject[k]!!.jsonPrimitive.long

    @Test
    fun conversionCases() {
        val cases = vectors["conversion"]!!.jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val got = Money.convertToBase(Big.of(c.long("amount")), c.long("minorUnits").toInt(), c.str("rate"), c.long("baseMinorUnits").toInt())
            assertEquals(c.long("expected"), got.toLong(), c.toString())
        }
    }

    @Test
    fun roundingCases() {
        val cases = vectors["rounding"]!!.jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val weights = c.jsonObject["weights"]!!.jsonArray.map { Fraction.parse(it.jsonPrimitive.content) }
            val got = Money.allocate(Big.of(c.long("total")), weights).map { it.toLong() }
            assertEquals(c.jsonObject["expected"]!!.jsonArray.map { it.jsonPrimitive.long }, got, c.toString())
        }
    }

    private var clock = 0L

    /** A held revision without a seal: the ledger reads bodies only. */
    private fun held(entryId: String, content: JsonObject): HeldRevision {
        val body = RevisionFormat.read(buildJsonObject { put("v", 1); put("heads", JsonArray(emptyList())); content.forEach { (k, v) -> put(k, v) } })
        return HeldRevision(entryId, 1, 1_767_225_600_000 + clock++, "", body, null)
    }

    private fun potOf(pot: JsonObject, extra: List<JsonElement> = emptyList()): List<HeldRevision> {
        val people = pot["participants"]!!.jsonArray.map {
            held(it.str("id"), buildJsonObject { put("kind", "participant"); put("name", it.str("name")); put("active", true); put("joined", it.long("joined")) })
        }
        return people + (pot["entries"]!!.jsonArray + extra).map { held(it.str("id"), it.jsonObject["body"]!!.jsonObject) }
    }

    private fun asLongs(m: Map<String, Big>) = m.mapValues { it.value.toLong() }
    private fun expectedMap(o: JsonObject, k: String) = (o[k] as? JsonObject)?.mapValues { it.value.jsonPrimitive.long } ?: emptyMap()

    @Test
    fun everyVectorPot() {
        val pots = vectors["pots"]!!.jsonArray
        assertEquals(3, pots.size)
        for (p in pots) {
            val pot = p.jsonObject
            val name = pot["name"]!!.jsonPrimitive.content
            val base = pot["base"]!!.let { Ledger.BaseCurrency(it.str("currency"), it.long("minorUnits").toInt()) }
            val expected = pot["expected"]!!.jsonObject
            val ledger = Ledger.build(potOf(pot), base)
            assertEquals(expected["joinOrder"]!!.jsonArray.map { it.jsonPrimitive.content }, ledger.participants.map { it.id }, "$name join order")
            for ((id, want) in expected["entries"]!!.jsonObject) {
                val entry = ledger.entries.first { it.id == id }
                assertEquals(Ledger.Status.LIVE, entry.status, "$name $id status")
                val effect = entry.effect!!
                assertEquals(want.long("base"), effect.base.toLong(), "$name $id base")
                assertEquals(expectedMap(want.jsonObject, "paid"), asLongs(effect.paid), "$name $id paid")
                assertEquals(expectedMap(want.jsonObject, "received"), asLongs(effect.received), "$name $id received")
                assertEquals(expectedMap(want.jsonObject, "shares"), asLongs(effect.shares), "$name $id shares")
                assertEquals(expectedMap(want.jsonObject, "shares").keys.toList(), effect.shares.keys.toList(), "$name $id shares in join order")
            }
            assertEquals(expectedMap(expected, "balances"), asLongs(ledger.balances), "$name balances")
            assertEquals(Big.ZERO, ledger.balances.values.fold(Big.ZERO) { a, b -> a + b })
            val settle = expected["settleUp"]!!.jsonArray.map { Triple(it.str("from"), it.str("to"), it.long("amount")) }
            assertEquals(settle, ledger.settleUp.map { Triple(it.from, it.to, it.amount.toLong()) }, "$name settle-up")

            for (inv in (pot["invalid"] as? JsonArray).orEmpty()) {
                val with = Ledger.build(potOf(pot, listOf(inv)), base)
                val entry = with.entries.first { it.id == inv.str("id") }
                assertEquals(Ledger.Status.INVALID, entry.status, "$name invalid ${inv.str("id")}")
                assertEquals(inv.str("problem"), entry.problem!!.name.lowercase().replace('_', '-'))
                assertNull(entry.effect)
                assertEquals(expectedMap(expected, "balances"), asLongs(with.balances), "$name balances with an invalid entry")
            }

            // Recording the settle-up as transfers settles the Pot.
            val transfers = ledger.settleUp.mapIndexed { i, t ->
                buildJsonObject {
                    put("id", "0e000000-0000-4000-a000-${i.toString().padStart(12, '0')}")
                    put("body", buildJsonObject {
                        put("kind", "transfer"); put("date", "2026-08-02"); put("currency", base.currency); put("minorUnits", base.minorUnits)
                        put("rate", "1"); put("amount", t.amount.toLong()); put("from", t.from); put("to", t.to); put("receipts", JsonArray(emptyList()))
                    })
                }
            }
            val after = Ledger.build(potOf(pot, transfers), base)
            assertTrue(after.balances.values.all { it.signum == 0 }, "$name not settled after its settle-up")
            assertTrue(after.settleUp.isEmpty())
        }
    }

    @Test
    fun theCryptoVectorsExpenseEntryIsARevisionThisReaderTakes() {
        val v = Json.parseToJsonElement(File(root, "contracts/crypto/vectors/v1.json").readText()).jsonObject
        val c = v["field_seal"]!!.jsonObject["expense-entry"]!!.jsonObject
        val body = RevisionFormat.decode(Pad.unpad(unhex(c.str("plaintext"))))
        assertEquals(body, RevisionFormat.decode(RevisionFormat.encode(body)), "a written body does not read back as itself")
        val ctx = c["context"]!!.jsonObject
        val address = RevisionAddress(ctx.str("scopeId"), ctx.str("nodeId"), ctx.str("entryId"), ctx.long("revision"))
        // The vector's envelope under its parent key is K_rev's field seal: open it as the codec does.
        val opened = suite.openField(Context("expense-entry", "scopeId" to address.scopeId, "nodeId" to address.nodeId, "entryId" to address.entryId, "revision" to address.revision), unhex(c.str("parent_key")), unhex(c.str("envelope")))
        assertEquals(body, RevisionFormat.decode(Pad.unpad(opened)))
    }

    @Test
    fun aRevisionOpensOnlyAtItsOwnAddress() {
        val codec = RevisionCodec(suite)
        val potKey = suite.p.randomBytes(32)
        val at = RevisionAddress("0e000000-0000-4000-8000-0000000000a1", "0e000000-0000-4000-8000-0000000000b1", "0e000000-0000-4000-9000-000000000001", 1)
        val body = RevisionBody(emptyList(), ParticipantBody("Anna", true, 1))
        val sealed = codec.seal(at, potKey, body)
        assertEquals(744, me.raddatz.veyq.mobile.crypto.b64(sealed.sealedBody).length, "a small body seals to the 512-byte class")
        assertEquals(body, codec.open(at, potKey, sealed))
        assertEquals(RevisionUnreadable.Reason.KEY, assertFailsWith<RevisionUnreadable> { codec.open(at.copy(revision = 2), potKey, sealed) }.reason)
        assertEquals(RevisionUnreadable.Reason.KEY, assertFailsWith<RevisionUnreadable> { codec.open(at, suite.p.randomBytes(32), sealed) }.reason)
        val big = RevisionBody(emptyList(), ParticipantBody("x".repeat(100), true, 1))
        assertTrue(codec.seal(at, potKey, big).sealedBody.size < 1000)
    }

    @Test
    fun theReaderRefusesWhatTheSchemaRefuses() {
        fun bad(json: String) = assertFailsWith<RevisionFormatInvalid> { RevisionFormat.read(Json.parseToJsonElement(json)) }
        bad("""{"v":2,"heads":[],"kind":"deleted"}""")
        bad("""{"v":1,"heads":[],"kind":"nope"}""")
        bad("""{"v":1,"heads":[],"kind":"participant","name":"","active":true,"joined":1}""")
        bad("""{"v":1,"heads":[],"kind":"transfer","date":"2026-13-01","currency":"EUR","minorUnits":2,"rate":"1","amount":1,"from":"0e000000-0000-4000-8000-000000000001","to":"0e000000-0000-4000-8000-000000000002","receipts":[]}""")
        bad("""{"v":1,"heads":[],"kind":"transfer","date":"2026-01-01","currency":"EUR","minorUnits":2,"rate":"01","amount":1,"from":"0e000000-0000-4000-8000-000000000001","to":"0e000000-0000-4000-8000-000000000002","receipts":[]}""")
        val ok = RevisionFormat.read(Json.parseToJsonElement("""{"v":1,"heads":[],"kind":"participant","name":"Ännä","active":true,"joined":1.0,"extra":1}"""))
        assertEquals(ParticipantBody("Ännä", true, 1), ok.content)
    }
}
