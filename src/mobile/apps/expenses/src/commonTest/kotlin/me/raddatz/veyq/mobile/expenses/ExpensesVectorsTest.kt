package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unhex
import me.raddatz.veyq.mobile.json.JsonText
import me.raddatz.veyq.mobile.json.JsonValue
import me.raddatz.veyq.mobile.json.jsNumberText
import me.raddatz.veyq.mobile.json.jsonArray
import me.raddatz.veyq.mobile.json.jsonObject
import me.raddatz.veyq.mobile.json.jsonString
import me.raddatz.veyq.mobile.qr.readRepoFile
import me.raddatz.veyq.mobile.search.testEnv
import me.raddatz.veyq.mobile.search.writeRepoFile
import me.raddatz.veyq.mobile.session.testPrimitives
import me.raddatz.veyq.mobile.words.Shown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Expenses' rules against `contracts/expenses/v1-vectors.json` — every case of every section, on the JVM and on
 * `macosArm64` (and on Linux and Windows through `run.py test`); the web runs the same cases through `@veyq/core-js/expenses`
 * (`src/expenses.test.ts`, and the web's `features/expenses/vectors.test.ts`). A case that fails names its section, index and
 * rule; answers are compared as JSON, in the shape the web reads them. And against `contracts/crypto/vectors/v1.json`'s
 * `expense-entry` seal, whose plaintext is a padded revision this reader must accept.
 *
 * **The write mode is here** (ADR 0155 § Consequences: the core's output is the one source): `VEYQ_WRITE_VECTORS=1 ./gradlew
 * :core:jvmTest --tests '*ExpensesVectorsTest*'` records every answered section's `expected` from the core's own functions,
 * written as JavaScript's `JSON.stringify(vectors, null, 2)` writes it, so an unchanged rule rewrites the file byte for byte.
 * `conversion`, `rounding` and `pots` keep the independent implementation's answers they were generated with; the core is
 * held to them, never writes them. Read each changed case against its rule before committing it.
 */
class ExpensesVectorsTest {
    private val vectors = JsonText.parse(readRepoFile(FILE).decodeToString()) as JsonValue.Obj
    private val writing = testEnv("VEYQ_WRITE_VECTORS") == "1"
    private val suite = V1(testPrimitives)

    private fun JsonValue?.o() = this as JsonValue.Obj
    private fun JsonValue?.a() = (this as JsonValue.Arr).items
    private fun JsonValue?.s(): String? = (this as? JsonValue.Str)?.value
    private fun JsonValue?.l(): Long = (this as JsonValue.Num).value.toLong()
    private fun JsonValue?.i(): Int = l().toInt()
    private fun num(n: Long) = JsonValue.Num(n)
    private fun str(s: String?): JsonValue = s?.let(::jsonString) ?: JsonValue.Null
    private fun bool(b: Boolean) = JsonValue.Bool(b)
    private fun cases(section: String) = vectors[section].a().map { it.o() }

    // ---- the arithmetic and the whole Pots (the independent implementation's answers) ----

    @Test
    fun conversionCases() {
        val cases = cases("conversion")
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val got = Money.convertToBase(Big.of(c["amount"].l()), c["minorUnits"].i(), c["rate"].s()!!, c["baseMinorUnits"].i())
            assertEquals(c["expected"].l(), got.toLong(), c.toString())
        }
    }

    @Test
    fun roundingCases() {
        val cases = cases("rounding")
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val got = Money.allocate(Big.of(c["total"].l()), c["weights"].a().map { Fraction.parse(it.s()!!) }).map { it.toLong() }
            assertEquals(c["expected"].a().map { it.l() }, got, c.toString())
        }
    }

    private var clock = 0L

    /** A held revision without a seal: the ledger reads bodies only. */
    private fun held(entryId: String, content: JsonValue.Obj): HeldRevision =
        HeldRevision(entryId, 1, 1_767_225_600_000 + clock++, "", bodyOf(content), null)

    private fun bodyOf(content: JsonValue.Obj): RevisionBody =
        RevisionFormat.read(JsonValue.Obj(linkedMapOf<String, JsonValue>("v" to num(1), "heads" to jsonArray(emptyList())) + content.fields))

    private fun potOf(pot: JsonValue.Obj, extra: List<JsonValue.Obj> = emptyList()): List<HeldRevision> {
        val people = pot["participants"].a().map {
            val p = it.o()
            held(p["id"].s()!!, jsonObject("kind" to str("participant"), "name" to p["name"], "active" to bool(true), "joined" to p["joined"]))
        }
        return people + (pot["entries"].a().map { it.o() } + extra).map { held(it["id"].s()!!, it["body"].o()) }
    }

    private fun asLongs(m: Map<String, Big>) = m.mapValues { it.value.toLong() }
    private fun expectedMap(o: JsonValue.Obj, k: String) = (o[k] as? JsonValue.Obj)?.fields?.mapValues { it.value.l() } ?: emptyMap()

    @Test
    fun everyVectorPot() {
        val pots = cases("pots")
        assertEquals(3, pots.size)
        for (pot in pots) {
            val name = pot["name"].s()
            val base = pot["base"].o().let { Ledger.BaseCurrency(it["currency"].s()!!, it["minorUnits"].i()) }
            val expected = pot["expected"].o()
            val ledger = Ledger.build(potOf(pot), base)
            assertEquals(expected["joinOrder"].a().map { it.s() }, ledger.participants.map { it.id }, "$name join order")
            for ((id, w) in expected["entries"].o().fields) {
                val want = w.o()
                val entry = ledger.entries.first { it.id == id }
                assertEquals(Ledger.Status.LIVE, entry.status, "$name $id status")
                val effect = entry.effect!!
                assertEquals(want["base"].l(), effect.base.toLong(), "$name $id base")
                assertEquals(expectedMap(want, "paid"), asLongs(effect.paid), "$name $id paid")
                assertEquals(expectedMap(want, "received"), asLongs(effect.received), "$name $id received")
                assertEquals(expectedMap(want, "shares"), asLongs(effect.shares), "$name $id shares")
                assertEquals(expectedMap(want, "shares").keys.toList(), effect.shares.keys.toList(), "$name $id shares in join order")
            }
            assertEquals(expectedMap(expected, "balances"), asLongs(ledger.balances), "$name balances")
            assertEquals(Big.ZERO, ledger.balances.values.fold(Big.ZERO) { a, b -> a + b })
            val settle = expected["settleUp"].a().map { Triple(it.o()["from"].s(), it.o()["to"].s(), it.o()["amount"].l()) }
            assertEquals(settle, ledger.settleUp.map { Triple(it.from, it.to, it.amount.toLong()) }, "$name settle-up")
            assertTrue(ledger.settleUp.size < pot["participants"].a().size, "$name settles in fewer transfers than people")

            for (inv in (pot["invalid"] as? JsonValue.Arr)?.items.orEmpty().map { it.o() }) {
                val with = Ledger.build(potOf(pot, listOf(inv)), base)
                val entry = with.entries.first { it.id == inv["id"].s() }
                assertEquals(Ledger.Status.INVALID, entry.status, "$name invalid ${inv["id"].s()}")
                assertEquals(inv["problem"].s(), problemName(entry.problem))
                assertEquals(inv["problem"].s(), problemName(Ledger.entryProblem(entry.content!!)), "the write path refuses it before sealing")
                assertEquals(null, entry.effect)
                assertEquals(expectedMap(expected, "balances"), asLongs(with.balances), "$name balances with an invalid entry")
            }

            // Recording the settle-up as transfers settles the Pot.
            val transfers = ledger.settleUp.mapIndexed { i, t ->
                jsonObject(
                    "id" to str("0e000000-0000-4000-a000-${i.toString().padStart(12, '0')}"),
                    "body" to jsonObject(
                        "kind" to str("transfer"), "date" to str("2026-08-02"), "currency" to str(base.currency), "minorUnits" to num(base.minorUnits.toLong()),
                        "rate" to str("1"), "amount" to num(t.amount.toLong()), "from" to str(t.from), "to" to str(t.to), "receipts" to jsonArray(emptyList()),
                    ),
                )
            }
            val after = Ledger.build(potOf(pot, transfers), base)
            assertTrue(after.balances.values.all { it.signum == 0 }, "$name not settled after its settle-up")
            assertTrue(after.settleUp.isEmpty())
        }
    }

    // ---- the crypto vector and the codec ----

    @Test
    fun theCryptoVectorsExpenseEntryIsARevisionThisReaderTakes() {
        val v = JsonText.parse(readRepoFile("contracts/crypto/vectors/v1.json").decodeToString()).o()
        val c = v["field_seal"].o()["expense-entry"].o()
        val body = RevisionFormat.decode(Pad.unpad(unhex(c["plaintext"].s()!!)))
        assertEquals(body, RevisionFormat.decode(RevisionFormat.encode(body)), "a written body does not read back as itself")
        val ctx = c["context"].o()
        val address = RevisionAddress(ctx["scopeId"].s()!!, ctx["nodeId"].s()!!, ctx["entryId"].s()!!, ctx["revision"].l())
        // The vector's envelope under its parent key is K_rev's field seal: open it as the codec does.
        val opened = suite.openField(
            Context("expense-entry", "scopeId" to address.scopeId, "nodeId" to address.nodeId, "entryId" to address.entryId, "revision" to address.revision),
            unhex(c["parent_key"].s()!!), unhex(c["envelope"].s()!!),
        )
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

    // ---- every other section: the core's answer per case, compared as JSON ----

    /** JSON equality as the web's `toEqual` reads it: objects by their keys in any order, numbers by value. */
    private fun same(a: JsonValue?, b: JsonValue?): Boolean = when {
        a is JsonValue.Obj && b is JsonValue.Obj -> a.fields.keys == b.fields.keys && a.fields.keys.all { same(a[it], b[it]) }
        a is JsonValue.Num && b is JsonValue.Num -> a.value == b.value // -0 is 0, as the file writes it
        a is JsonValue.Arr && b is JsonValue.Arr -> a.items.size == b.items.size && a.items.indices.all { same(a.items[it], b.items[it]) }
        else -> a == b
    }

    private fun readAnswer(read: () -> RevisionBody): JsonValue = try {
        jsonObject("body" to RevisionFormat.toJson(read()))
    } catch (e: RevisionFormatInvalid) {
        jsonObject("path" to str(e.path))
    }

    private fun problemName(p: Ledger.Problem?) = p?.name?.lowercase()?.replace('_', '-')

    private fun heldOf(j: JsonValue): HeldRevision {
        val o = j.o()
        val body = (o["body"] as? JsonValue.Obj)?.let { RevisionFormat.read(it) }
        return HeldRevision(o["entryId"].s()!!, o["revision"].l(), o["createdAt"].l(), o["digest"].s()!!, body, if (body == null) RevisionUnreadable.Reason.BODY else null)
    }

    /** A split as the web hands one over, unchecked: the reader would refuse a person twice before the rule could name it. */
    private fun splitOf(j: JsonValue): Split {
        val o = j.o()
        fun pairs(list: String, key: String) = o[list].a().map { it.o()["participant"].s()!! to it.o()[key].l() }
        return when (o["mode"].s()) {
            "equal" -> Split.Equal(o["participants"].a().map { it.s()!! })
            "shares" -> Split.Shares(pairs("shares", "shares"))
            "percent" -> Split.Percent(pairs("percents", "percent"))
            else -> Split.Amounts(pairs("amounts", "amount"))
        }
    }

    private fun pairs(m: Map<String, Big>) = jsonArray(m.map { (k, v) -> jsonArray(listOf(str(k), num(v.toLong()))) })
    private fun ref(entryId: String, revision: Long) = jsonObject("entryId" to str(entryId), "revision" to num(revision))

    private fun ledgerJson(pot: Ledger.Pot) = jsonObject(
        "participants" to jsonArray(pot.participants.map { p ->
            jsonObject("id" to str(p.id), "name" to str(p.name), "active" to bool(p.active), "joined" to num(p.joined), "erased" to bool(p.erased), "member" to str(p.member))
        }),
        "entries" to jsonArray(pot.entries.map { e ->
            jsonObject(
                "id" to str(e.id), "revision" to num(e.revision), "status" to str(e.status.name.lowercase()), "problem" to str(problemName(e.problem)),
                "edited" to bool(e.edited), "recordedAt" to num(e.recordedAt), "content" to (e.content?.let(RevisionFormat::contentJson) ?: JsonValue.Null),
                "effect" to (e.effect?.let { f -> jsonObject("base" to num(f.base.toLong()), "paid" to pairs(f.paid), "received" to pairs(f.received), "shares" to pairs(f.shares)) } ?: JsonValue.Null),
            )
        }),
        "balances" to pairs(pot.balances),
        "settleUp" to jsonArray(pot.settleUp.map { jsonObject("from" to str(it.from), "to" to str(it.to), "amount" to num(it.amount.toLong())) }),
        "overview" to jsonObject(
            "byCategory" to pairs(pot.overview.byCategory),
            "byPerson" to jsonArray(pot.overview.byPerson.map { (k, v) -> jsonArray(listOf(str(k), jsonObject("paid" to num(v.first.toLong()), "share" to num(v.second.toLong())))) }),
            "byCategoryAndPerson" to jsonArray(pot.overview.byCategoryAndPerson.map { (k, v) -> jsonArray(listOf(str(k), pairs(v))) }),
        ),
    )

    private val answers: Map<String, (JsonValue.Obj) -> JsonValue> = mapOf(
        "read" to { c -> readAnswer { RevisionFormat.read(c["body"]!!) } },
        "decode" to { c -> readAnswer { RevisionFormat.decode(c["hex"].s()?.let(::unhex) ?: c["utf8"].s()!!.encodeToByteArray()) } },
        "encode" to { c ->
            try {
                val bytes = RevisionFormat.encode(RevisionFormat.read(c["body"]!!))
                jsonObject("length" to num(bytes.size.toLong()), "json" to JsonText.parse(bytes.decodeToString()))
            } catch (e: RevisionFormatInvalid) {
                jsonObject("path" to str(e.path))
            }
        },
        "check" to { c ->
            val held = c["held"].a().map(::heldOf)
            val check = LedgerCheck.of(held)
            fun refs(l: List<LedgerCheck.Ref>) = jsonArray(l.map { ref(it.entryId, it.revision) })
            jsonObject(
                "check" to jsonObject(
                    "complete" to bool(check.complete), "missing" to refs(check.missing), "altered" to refs(check.altered),
                    "unreadable" to refs(check.unreadable), "entries" to jsonArray(check.entries.map(::jsonString)),
                ),
                "currentHeads" to jsonArray(Revisions.currentHeads(held).map { jsonString(it.id) }),
                "headsToName" to jsonArray(Revisions.headsToName(held).map { jsonObject("entryId" to str(it.entryId), "revision" to num(it.revision), "digest" to str(it.digest)) }),
            )
        },
        "entryProblem" to { c ->
            val content = bodyOf(c["content"].o()).content as MoneyContent
            val people = (c["participants"] as? JsonValue.Arr)?.items?.map { it.s()!! }?.toSet()
            str(problemName(Ledger.entryProblem(content, people?.let { set -> { id: String -> id in set } })))
        },
        "splitBreaks" to { c ->
            val problem = Ledger.Problem.valueOf(c["problem"].s()!!.uppercase().replace('-', '_'))
            bool(Ledger.splitBreaks(problem, splitOf(c["split"]!!), (c["part"] as? JsonValue.Num)?.let { Big.of(it.value.toLong()) }))
        },
        "named" to { c -> jsonArray(Ledger.namedParticipants(bodyOf(c["content"].o()).content as MoneyContent).map(::jsonString)) },
        "ledger" to { c ->
            val base = c["base"].o().let { Ledger.BaseCurrency(it["currency"].s()!!, it["minorUnits"].i()) }
            ledgerJson(Ledger.build(c["held"].a().map(::heldOf), base))
        },
        "settleUp" to { c ->
            val balances = LinkedHashMap<String, Big>()
            c["balances"].a().forEach { balances[it.a()[0].s()!!] = Big.of(it.a()[1].l()) }
            jsonArray(Ledger.settleUp(balances, c["order"].a().map { it.s()!! }).map { jsonObject("from" to str(it.from), "to" to str(it.to), "amount" to num(it.amount.toLong())) })
        },
        "amounts" to { c ->
            fun sep() = if (c["language"].s() == "de") ',' else '.'
            when (c["op"].s()) {
                "parseAmount" -> str(Amounts.parse(c["text"].s()!!, c["minorUnits"].i())?.toString())
                "decimalOf" -> str(Amounts.text(c["amount"].s()!!.toLong(), c["minorUnits"].i()))
                "amountText" -> str(Amounts.text(c["amount"].s()!!.toLong(), c["minorUnits"].i(), sep()))
                "parseRate" -> str(Amounts.parseRate(c["text"].s()!!))
                "formatMoney" -> str(Shown.money(c["amount"].s()!!.toLong(), c["currency"].s(), c["minorUnits"].i(), c["language"].s()!!))
                else -> error("no such op")
            }
        },
        "rates" to { c ->
            fun table(t: Map<String, ExchangeRates.Row>?): JsonValue =
                t?.let { JsonValue.Obj(it.mapValues { (_, r) -> jsonObject("rate" to str(r.rate), "date" to str(r.date)) }) } ?: JsonValue.Null
            when (c["op"].s()) {
                "parseTable" -> table(ExchangeRateTable.parseTable(c["answer"] ?: JsonText.parse(c["text"].s()!!), c["known"].a().map { it.s()!! }.toSet()))
                "crossRate" -> {
                    val t = c["table"].o().fields.mapValues { (_, r) -> ExchangeRates.Row(r.o()["rate"].s()!!, r.o()["date"].s()!!) }
                    ExchangeRateTable.crossRate(t, c["from"].s()!!, c["to"].s()!!)?.let { jsonObject("rate" to str(it.rate), "date" to str(it.date)) } ?: JsonValue.Null
                }
                else -> error("no such op")
            }
        },
    )

    private fun section(name: String) {
        if (writing) return // the write mode records instead (writeTheVectors)
        val cases = cases(name)
        assertTrue(cases.isNotEmpty(), "no $name vectors")
        val wrong = cases.mapIndexedNotNull { i, case ->
            val got = try { answers.getValue(name)(case) } catch (e: Exception) { str("threw $e") }
            if (same(got, case["expected"])) null else "$name #$i (${case["rule"].s()}): want ${case["expected"]}, got $got"
        }
        assertEquals(emptyList(), wrong, "${wrong.size} of ${cases.size} $name vectors")
    }

    @Test fun read() = section("read")
    @Test fun decode() = section("decode")
    @Test fun encode() = section("encode")
    @Test fun check() = section("check")
    @Test fun entryProblem() = section("entryProblem")
    @Test fun splitBreaks() = section("splitBreaks")
    @Test fun named() = section("named")
    @Test fun ledger() = section("ledger")
    @Test fun settleUp() = section("settleUp")
    @Test fun amounts() = section("amounts")
    @Test fun rates() = section("rates")

    @Test
    fun everyCaseIsRecorded() {
        // a case without its `expected` would compare nothing: the file is the recorded one, every case answered
        for (s in answers.keys) for (c in cases(s)) assertTrue(c.has("expected"), "$s case without its expected: $c")
    }

    /** The write mode (see the class): every answered section's `expected`, the rest of the file as it was. */
    @Test
    fun writeTheVectors() {
        if (!writing) return
        val recorded = JsonValue.Obj(vectors.fields.mapValues { (key, value) ->
            val answer = answers[key] ?: return@mapValues value
            jsonArray(value.a().map { c ->
                val case = c.o()
                val fields = LinkedHashMap(case.fields)
                fields["expected"] = answer(case)
                JsonValue.Obj(fields)
            })
        })
        writeRepoFile(FILE, stringify(recorded, "") + "\n")
    }

    /** [e] as JavaScript's `JSON.stringify(e, null, 2)` writes it, so a rewrite diffs only where an answer changed. */
    private fun stringify(e: JsonValue, indent: String): String {
        val inner = "$indent  "
        return when (e) {
            JsonValue.Null -> "null"
            is JsonValue.Bool -> e.value.toString()
            is JsonValue.Num -> jsNumberText(e.value)
            is JsonValue.Str -> quote(e.value)
            is JsonValue.Arr -> if (e.items.isEmpty()) "[]" else e.items.joinToString(",\n", "[\n", "\n$indent]") { inner + stringify(it, inner) }
            is JsonValue.Obj -> if (e.fields.isEmpty()) "{}"
            else e.fields.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) -> "$inner${quote(k)}: ${stringify(v, inner)}" }
        }
    }

    /** A string as `JSON.stringify` quotes it: the quote and the backslash escaped, the C0 controls as their short escape or a
     *  `u00xx` escape, a lone surrogate as its `udxxx` escape, every other character as itself. */
    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for ((i, c) in s.withIndex()) {
            val lone = (c.isHighSurrogate() && !(i + 1 < s.length && s[i + 1].isLowSurrogate())) ||
                (c.isLowSurrogate() && !(i > 0 && s[i - 1].isHighSurrogate()))
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || lone -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private companion object {
        const val FILE = "contracts/expenses/v1-vectors.json"
    }
}
