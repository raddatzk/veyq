package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.json.JsonText
import me.raddatz.veyq.mobile.json.JsonValue
import me.raddatz.veyq.mobile.json.jsNumberText
import me.raddatz.veyq.mobile.qr.readRepoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The rules no vector case observes through the web's façade, as the web's former unit tests held them (`money.test.ts`'s
 * exact pieces, `format.test.ts`'s reader-is-the-schema pins), as `SearchRulesTest` is the search's: the fractions, the
 * rounding and the floor, the allocation's refusals and its exactness past the doubles, the reader's patterns read from
 * the schema itself, and the JSON seam's number text.
 */
class ExpensesRulesTest {
    @Test
    fun readsARateAsAnExactFractionAndRefusesWhatTheSchemaRefuses() {
        assertEquals(Fraction.of(11725, 10000), Money.parseRate("1.1725"))
        assertEquals(Fraction.of(Big.ONE, Big.pow10(12)), Money.parseRate("0.000000000001"))
        assertEquals(Fraction.of(100), Money.parseRate("100"))
        for (bad in listOf("0", "0.0", "-1", "1e3", ".5", "1.", "01", "1.0000000000001", "1234567890123", "")) {
            assertEquals("rate_malformed", assertFailsWith<IllegalArgumentException>(bad) { Money.parseRate(bad) }.message)
        }
    }

    @Test
    fun roundsAHalfAwayFromZeroBothSigns() {
        assertEquals(listOf(3L, -3L, 2L, -2L, 0L), listOf(Fraction.of(5, 2), Fraction.of(-5, 2), Fraction.of(7, 3), Fraction.of(-7, 3), Fraction.of(0)).map { Money.roundHalfAwayFromZero(it).toLong() })
    }

    @Test
    fun floorsTowardMinusInfinity() {
        assertEquals(listOf(3L, -4L, -3L), listOf(7L to 2L, -7L to 2L, -6L to 2L).map { (n, d) -> Money.floorDiv(Big.of(n), Big.of(d)).toLong() })
    }

    @Test
    fun staysExactPastTheDoubles() {
        val total = Big.of(9007199254740991L) * Big.of(1000) + Big.of(7)
        val shares = Money.allocate(total, List(3) { Fraction.of(1) })
        assertEquals(total, shares.fold(Big.ZERO) { a, b -> a + b })
        assertTrue(shares[0] - shares[2] <= Big.ONE)
    }

    @Test
    fun refusesAnAllocationItCannotMake() {
        fun refusal(f: () -> Unit) = assertFailsWith<IllegalArgumentException> { f() }.message
        assertEquals("allocate_no_weight", refusal { Money.allocate(Big.of(10), listOf(Fraction.of(0), Fraction.of(0))) })
        assertEquals("allocate_negative_weight", refusal { Money.allocate(Big.of(10), listOf(Fraction.of(-1), Fraction.of(2))) })
        assertEquals("allocate_negative_total", refusal { Money.allocate(Big.of(-1), listOf(Fraction.of(1))) })
        assertEquals("fraction_zero_denominator", refusal { Fraction.of(1, 0) })
    }

    @Test
    fun theReaderIsTheSchema() {
        val schema = JsonText.parse(readRepoFile("contracts/expenses/entry.v1.schema.json").decodeToString()) as JsonValue.Obj
        fun at(vararg path: String): JsonValue? = path.fold(schema as JsonValue?) { v, k -> (v as? JsonValue.Obj)?.get(k) }
        fun pattern(vararg path: String) = (at(*path) as JsonValue.Str).value
        assertEquals(pattern("\$defs", "uuid", "pattern"), RevisionFormat.UUID.pattern)
        assertEquals(pattern("\$defs", "head", "properties", "digest", "pattern"), RevisionFormat.DIGEST.pattern)
        assertEquals(pattern("\$defs", "currency", "pattern"), RevisionFormat.CURRENCY.pattern)
        assertEquals(pattern("\$defs", "date", "pattern"), RevisionFormat.DATE.pattern)
        assertEquals(pattern("\$defs", "rate", "pattern"), Money.RATE.pattern)
        assertEquals(RevisionFormat.MAX_HEADS.toDouble(), (at("properties", "heads", "maxItems") as JsonValue.Num).value)
        assertEquals(listOf("expense", "refund", "transfer", "participant", "deleted", "erasure"), (at("properties", "kind", "enum") as JsonValue.Arr).items.map { (it as JsonValue.Str).value })
        val categories = (((at("\$defs", "category", "anyOf") as JsonValue.Arr).items[0] as JsonValue.Obj)["enum"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value }
        assertEquals(categories, RevisionFormat.DEFAULT_CATEGORIES)
        val modes = (at("\$defs", "split", "oneOf") as JsonValue.Arr).items.map { (((it as JsonValue.Obj)["properties"] as JsonValue.Obj)["mode"] as JsonValue.Obj)["const"].let { c -> (c as JsonValue.Str).value } }
        assertEquals(listOf("equal", "shares", "percent", "amounts"), modes)
        val participant = at("\$defs", "participant") as JsonValue.Obj
        assertEquals(listOf("kind", "name", "active", "joined"), (participant["required"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value })
    }

    @Test
    fun aNumberReadsAsJavaScriptWritesIt() {
        val cases = listOf(1.0 to "1", 1.1725 to "1.1725", 177.84 to "177.84", 0.0000123 to "0.0000123", 1e-7 to "1e-7", 1e21 to "1e+21",
            1.5e21 to "1.5e+21", 123456789012345680000.0 to "123456789012345680000", -0.5 to "-0.5", 0.0 to "0", 9007199254740992.0 to "9007199254740992", 1e-6 to "0.000001")
        assertEquals(cases.map { it.second }, cases.map { jsNumberText(it.first) })
    }

    @Test
    fun theHandWrittenUuidCheckIsTheSchemasPattern() {
        val valid = "0e000000-0000-4000-8000-0000000000ab"
        // every one-character change of a valid id, by every character that matters, at every position; and lengths around it
        val alphabet = "0123456789abcdefABCDEFgz-_ :\u00e9"
        val cases = buildList {
            add(valid)
            for (i in valid.indices) for (c in alphabet) add(valid.substring(0, i) + c + valid.substring(i + 1))
            add(valid.dropLast(1)); add(valid + "0"); add(""); add(valid.uppercase()); add("-".repeat(36))
        }
        assertEquals(cases.map { RevisionFormat.UUID.matches(it) }, cases.map { RevisionFormat.isUuid(it) })
        assertTrue(cases.count { RevisionFormat.isUuid(it) } > 100, "the cases reach accepted ids too")
    }
}
