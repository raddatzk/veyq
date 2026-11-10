package me.raddatz.veyq.mobile.expenses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.expenses.EntryForm.FormItem
import me.raddatz.veyq.mobile.expenses.EntryForm.FormSplit
import me.raddatz.veyq.mobile.expenses.EntryForm.Mode
import me.raddatz.veyq.mobile.expenses.EntryForm.Payer
import me.raddatz.veyq.mobile.expenses.EntryForm.Refusal
import me.raddatz.veyq.mobile.expenses.EntryForm.SplitRow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The editor's form (the web's `entryForm.test.ts`): every vector entry survives the form unchanged, the Pot written
 * from those forms computes the vectors' balances, and each rule refuses — at its field — beside the control it lets
 * through.
 */
class EntryFormTest {
    private val vectors = Json.parseToJsonElement(File(System.getProperty("veyq.repoRoot"), "contracts/expenses/v1-vectors.json").readText()).jsonObject

    private fun body(o: JsonObject): MoneyContent =
        RevisionFormat.read(buildJsonObject { put("v", 1); put("heads", JsonArray(emptyList())); o.forEach { (k, v) -> put(k, v) } }).content as MoneyContent

    @Test
    fun everyVectorEntryOpenedAndSavedUnchangedIsTheVeryBody() {
        for (pot in vectors["pots"]!!.jsonArray.map { it.jsonObject }) {
            val base = pot["base"]!!.jsonObject.let { Ledger.BaseCurrency(it["currency"]!!.jsonPrimitive.content, it["minorUnits"]!!.jsonPrimitive.long.toInt()) }
            val people = pot["participants"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            for (sep in listOf('.', ',')) {
                for (e in pot["entries"]!!.jsonArray) {
                    val c = body(e.jsonObject["body"]!!.jsonObject)
                    assertEquals(EntryForm.Result(c, null), EntryForm.of(c, base, people, sep).contentOf { it in people }, "${pot["name"]} $sep $c")
                }
            }
            for (e in pot["invalid"]?.jsonArray.orEmpty()) {
                val c = body(e.jsonObject["body"]!!.jsonObject)
                val r = EntryForm.of(c, base, people, ',').contentOf { it in people }.refusal
                assertEquals(e.jsonObject["problem"]!!.jsonPrimitive.content, r?.problem, "${pot["name"]} invalid")
                assert(r!!.at == "rest" || r.at == "item") { "$r" }
            }
        }
    }

    @Test
    fun thePotWrittenFromThoseFormsComputesTheVectorsBalances() {
        val codec = RevisionCodec(V1(BouncyCastlePrimitives))
        val key = ByteArray(32) { 3 }
        val scope = "22222222-2222-4222-8222-222222222222"
        val node = "0e000000-0000-4000-b000-000000000001"
        for (pot in vectors["pots"]!!.jsonArray.map { it.jsonObject }) {
            val base = pot["base"]!!.jsonObject.let { Ledger.BaseCurrency(it["currency"]!!.jsonPrimitive.content, it["minorUnits"]!!.jsonPrimitive.long.toInt()) }
            val participants = pot["participants"]!!.jsonArray.map { it.jsonObject }
            val people = participants.map { it["id"]!!.jsonPrimitive.content }
            var held = listOf<HeldRevision>()
            fun write(writes: List<PotWrite>) {
                val rows = PotWrites.seal(codec, scope, node, key, held, writes, base).map {
                    ServedRevision(it.entryId, it.revision, SealedRevision(java.util.Base64.getDecoder().decode(it.sealedBody), java.util.Base64.getDecoder().decode(it.wrappedRevisionKey)), 0)
                }
                held = held + Revisions.open(codec, scope, node, key, rows)
            }
            write(participants.map { PotWrite(it["id"]!!.jsonPrimitive.content, ParticipantBody(it["name"]!!.jsonPrimitive.content, true, it["joined"]!!.jsonPrimitive.long)) })
            write(pot["entries"]!!.jsonArray.map { e ->
                val c = body(e.jsonObject["body"]!!.jsonObject)
                PotWrite(e.jsonObject["id"]!!.jsonPrimitive.content, assertNotNull(EntryForm.of(c, base, people, ',').contentOf { it in people }.content))
            })
            val ledger = Ledger.build(held, base)
            val expected = pot["expected"]!!.jsonObject
            assertEquals(expected["balances"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }, ledger.balances.mapValues { it.value.toLong() }, "${pot["name"]}")
            assertEquals(
                expected["settleUp"]!!.jsonArray.map { Triple(it.jsonObject["from"]!!.jsonPrimitive.content, it.jsonObject["to"]!!.jsonPrimitive.content, it.jsonObject["amount"]!!.jsonPrimitive.long) },
                ledger.settleUp.map { Triple(it.from, it.to, it.amount.toLong()) },
            )
        }
    }

    private val a = "0e000000-0000-4000-8000-000000000001"
    private val b = "0e000000-0000-4000-8000-000000000002"
    private val c = "0e000000-0000-4000-8000-000000000003"
    private val eur = Ledger.BaseCurrency("EUR", 2)

    private fun rows(mode: Mode, typed: List<Pair<String, String>>) = FormSplit(
        mode,
        typed.map { (id, v) ->
            when (mode) {
                Mode.SHARES -> SplitRow(id, shares = v)
                Mode.PERCENT -> SplitRow(id, percent = v)
                Mode.AMOUNTS -> SplitRow(id, amount = v)
            }
        },
        true,
    )

    private fun dinner() = EntryForm.empty(eur, listOf(a, b, c), "2026-07-01").let { it.copy(title = "Dinner", category = "food", payers = listOf(Payer(a, "100,00"))) }

    private fun allocationOf(f: EntryForm) = (f.contentOf().content as Expense).allocation

    @Test
    fun aNewExpenseIsTheFirstPersonPayingSplitEquallyAmongEveryoneOffered() {
        assertEquals(EntryForm.equalSplit(listOf(a, b, c)), dinner().rest)
        assertEquals(
            Expense("Dinner", "food", "2026-07-01", "EUR", 2, "1", 10000, emptyList(), listOf(Contribution(a, 10000)), Allocation(emptyList(), Split.Equal(listOf(a, b, c)))),
            dinner().contentOf().content,
        )
    }

    @Test
    fun theAmountIsThePayersSum() {
        val f = dinner().copy(payers = listOf(Payer(a, "40,01"), Payer(b, "20")))
        assertEquals(6001, f.payersTotal)
        val e = f.contentOf().content as Expense
        assertEquals(6001, e.amount)
        assertEquals(listOf(Contribution(a, 4001), Contribution(b, 2000)), e.payers)
    }

    @Test
    fun itemsTakeTheirAmountsAndSplitsAndTheRestItsOwn() {
        val f = dinner().copy(
            items = listOf(
                EntryForm.emptyItem(listOf(a, b, c)).copy(label = "Wine", amount = "30"),
                FormItem("Cake", "10,5", rows(Mode.PERCENT, listOf(a to "33,33", b to "66.67"))),
                FormItem("Cheese", "5", rows(Mode.AMOUNTS, listOf(a to "1,50", c to "3.5"))),
            ),
            rest = rows(Mode.SHARES, listOf(a to "2", c to "1")),
        )
        assertEquals(5450, f.restLeft)
        assertEquals(
            Allocation(
                listOf(
                    Item("Wine", 3000, Split.Equal(listOf(a, b, c))),
                    Item("Cake", 1050, Split.Percent(listOf(a to 3333L, b to 6667L))),
                    Item("Cheese", 500, Split.Amounts(listOf(a to 150L, c to 350L))),
                ),
                Split.Shares(listOf(a to 2L, c to 1L)),
            ),
            allocationOf(f),
        )
        // Items that fill the amount: no rest split is sealed.
        val exact = dinner().copy(items = listOf(EntryForm.emptyItem(listOf(b)).copy(label = "Ben", amount = "70"), EntryForm.emptyItem(listOf(c)).copy(label = "Cleo", amount = "30")))
        assertEquals(Allocation(listOf(Item("Ben", 7000, Split.Equal(listOf(b))), Item("Cleo", 3000, Split.Equal(listOf(c))))), allocationOf(exact))
    }

    @Test
    fun sharesOfOneEachAreSealedEqual() {
        fun sealed(rest: FormSplit) = allocationOf(dinner().copy(rest = rest)).rest
        assertEquals(Split.Equal(listOf(a, b, c)), sealed(rows(Mode.SHARES, listOf(a to "1", b to "1", c to "1"))))
        assertEquals(Split.Equal(listOf(c, a)), sealed(rows(Mode.SHARES, listOf(c to "1", a to "1"))))
        assertEquals(Split.Shares(listOf(a to 1L, b to 2L)), sealed(rows(Mode.SHARES, listOf(a to "1", b to "2"))))
    }

    @Test
    fun anEntryOpensAsOneLineWhereEqualOverEveryoneOfferedAndAsRowsOtherwise() {
        fun opened(rest: Split, items: List<Item> = emptyList()) = EntryForm.of(
            Expense("X", "other", "2026-07-01", "EUR", 2, "1", 900, emptyList(), listOf(Contribution(a, 900)), Allocation(items, rest)), eur, listOf(a, b, c), ',',
        )
        assertEquals(false, opened(Split.Equal(listOf(c, a, b))).rest.expanded)
        assertEquals(EntryForm.equalSplit(listOf(a, b), true), opened(Split.Equal(listOf(a, b))).rest)
        assertEquals(true, opened(Split.Shares(listOf(a to 2L, b to 1L, c to 1L))).rest.expanded)
        assertEquals(rows(Mode.AMOUNTS, listOf(a to "4,50", b to "4,50")), opened(Split.Amounts(listOf(a to 450L, b to 450L))).rest)
        assertEquals(false, opened(Split.Equal(listOf(a)), listOf(Item("Wine", 300, Split.Equal(listOf(a, b, c))))).items[0].split.expanded)
    }

    @Test
    fun everyoneEquallyKeepsWhatWasTypedOtherwise() {
        assertEquals(
            FormSplit(Mode.SHARES, listOf(SplitRow(a), SplitRow(b, percent = "60")), true),
            EntryForm.everyoneEqually(rows(Mode.PERCENT, listOf(b to "60", c to "40")), listOf(a, b)),
        )
    }

    @Test
    fun totalsCountWhatParsesOfTheNamedRows() {
        assertEquals(150, EntryForm.amountsTotal(rows(Mode.AMOUNTS, listOf(a to "1,50", b to "x", "" to "3")), 2))
        assertEquals(listOf(3333L, 10000L, 1L), listOf("33,33", "100", "0,01").map { EntryForm.parsePercent(it) })
        assertEquals(listOf(null, null, null, null), listOf("33,333", "100,01", "0", "x").map { EntryForm.parsePercent(it) })
        assertEquals(10000, EntryForm.percentTotal(rows(Mode.PERCENT, listOf(a to "60", b to "40", "" to "5"))))
        // A sum never passes 2^53 - 1: a value that would is left out.
        assertEquals(RevisionFormat.MAX_SAFE, EntryForm.typedSum(listOf("90071992547409,91", "0,01"), 2))
    }

    @Test
    fun switchingTheKindCarriesTheAmount() {
        assertEquals("100,00", dinner().withKind(EntryForm.Kind.TRANSFER, ',').amount)
        val back = EntryForm.empty(eur, listOf(a, b), "2026-07-01").copy(kind = EntryForm.Kind.TRANSFER, amount = "12,50").withKind(EntryForm.Kind.EXPENSE)
        assertEquals(listOf(Payer(a, "12,50")), back.payers)
    }

    @Test
    fun anotherCurrencyTakesItsDigitsAndAsksForTheRate() {
        val yen = EntryForm.empty(eur, listOf(a, b), "2026-07-01").withCurrency("JPY", 0, eur)
        assertEquals(0 to "", yen.minorUnits to yen.rate)
        val ramen = yen.copy(title = "Ramen", payers = listOf(Payer(a, "5000")))
        assertEquals(Refusal("rate", "rate"), ramen.contentOf().refusal)
        val typed = ramen.copy(rate = "0,0061234").contentOf().content!!
        assertEquals(Triple(5000L, "0.0061234", 0), Triple(typed.amount, typed.rate, typed.minorUnits))
        assertEquals(2 to "1", yen.withCurrency("EUR", 0, eur).let { it.minorUnits to it.rate })
    }

    @Test
    fun refusesWhatDoesNotAddUpAndNamesWhere() {
        val base = EntryForm.empty(eur, listOf(a, b), "2026-07-01").copy(title = "Taxi", payers = listOf(Payer(a, "10")))
        fun item(amount: String, split: FormSplit = EntryForm.equalSplit(listOf(a))) = FormItem("Wine", amount, split)
        fun refusal(f: EntryForm, isParticipant: ((String) -> Boolean)? = null) = f.contentOf(isParticipant).refusal
        fun ok(f: EntryForm) = assertNull(refusal(f), "$f")
        assertEquals(Refusal("title", "title"), refusal(base.copy(title = " ")))
        assertEquals(Refusal("date", "date"), refusal(base.copy(date = "1.7.2026")))
        assertEquals(Refusal("payers", "payers"), refusal(base.copy(payers = listOf(Payer(a, "10.001")))))
        assertEquals(Refusal("payers", "payers"), refusal(base.copy(payers = listOf(Payer(a, "4"), Payer(b, "")))))
        ok(base.copy(payers = listOf(Payer(a, "4"), Payer(b, "6"))))
        assertEquals(Refusal("payers", "payers"), refusal(base.copy(payers = listOf(Payer("", "4")))))
        assertEquals(Refusal("named-twice", "payers"), refusal(base.copy(payers = listOf(Payer(a, "4"), Payer(a, "6")))))
        assertEquals(Refusal("amount", "payers"), refusal(base.copy(payers = listOf(Payer(a, "90071992547409.91"), Payer(b, "1")))))
        assertEquals(Refusal("items-above-total", "items"), refusal(base.copy(items = listOf(item("11")))))
        ok(base.copy(items = listOf(item("10"))))
        assertEquals(Refusal("items", "item", 0), refusal(base.copy(items = listOf(item("4", EntryForm.equalSplit(emptyList()))))))
        assertEquals(Refusal("items", "item", 1), refusal(base.copy(items = listOf(item("4"), item("4").copy(label = " ")))))
        assertEquals(Refusal("items", "item", 0), refusal(base.copy(items = listOf(item("x")))))
        assertEquals(Refusal("split", "rest"), refusal(base.copy(rest = EntryForm.equalSplit(emptyList()))))
        assertEquals(Refusal("split", "rest"), refusal(base.copy(rest = rows(Mode.SHARES, listOf("" to "1")))))
        ok(base.copy(items = listOf(item("10")), rest = EntryForm.equalSplit(emptyList())))
        assertEquals(Refusal("percent-unbalanced", "rest"), refusal(base.copy(rest = rows(Mode.PERCENT, listOf(a to "50", b to "49,99")))))
        assertEquals(Refusal("split-shares", "rest"), refusal(base.copy(rest = rows(Mode.SHARES, listOf(a to "1,5")))))
        assertEquals(Refusal("split-shares", "rest"), refusal(base.copy(rest = rows(Mode.SHARES, listOf(a to "")))))
        assertEquals(Refusal("split-shares", "rest"), refusal(base.copy(rest = rows(Mode.SHARES, listOf(a to "1000001")))))
        ok(base.copy(rest = rows(Mode.SHARES, listOf(a to "1000000"))))
        assertEquals(Refusal("split-percents", "rest"), refusal(base.copy(rest = rows(Mode.PERCENT, listOf(a to "100,001")))))
        assertEquals(Refusal("split-amounts", "rest"), refusal(base.copy(rest = rows(Mode.AMOUNTS, listOf(a to "10.001")))))
        assertEquals(Refusal("amounts-unbalanced", "rest"), refusal(base.copy(rest = rows(Mode.AMOUNTS, listOf(a to "6", b to "3,99")))))
        ok(base.copy(rest = rows(Mode.AMOUNTS, listOf(a to "6", b to "4"))))
        assertEquals(Refusal("amounts-unbalanced", "item", 1), refusal(base.copy(items = listOf(item("4"), item("3", rows(Mode.AMOUNTS, listOf(b to "2")))))))
        assertEquals(Refusal("named-twice", "item", 0), refusal(base.copy(items = listOf(item("4", rows(Mode.SHARES, listOf(a to "1", a to "2")))))))
        assertEquals(Refusal("transfer", "transfer"), refusal(base.copy(kind = EntryForm.Kind.TRANSFER, amount = "10", from = a, to = a)))
        assertEquals(Refusal("amount", "amount"), refusal(base.copy(kind = EntryForm.Kind.TRANSFER, amount = "", from = a, to = b)))
        assertEquals(Refusal("rate", "rate"), refusal(base.copy(kind = EntryForm.Kind.TRANSFER, amount = "10", from = a, to = b, rate = "")))
        ok(base.copy(kind = EntryForm.Kind.TRANSFER, amount = "10", from = a, to = b, title = ""))
        assertEquals(Refusal("unknown-participant", "rest"), refusal(base) { it == a })
        assertEquals(Refusal("unknown-participant", "payers"), refusal(base) { it == b })
        assertEquals(Refusal("unknown-participant", "transfer"), refusal(base.copy(kind = EntryForm.Kind.TRANSFER, amount = "10", from = a, to = b)) { it == a })
    }

    @Test
    fun aRefundKeepsWhatItRefunds() {
        val r = EntryForm.empty(eur, listOf(a, b), "2026-07-01").copy(kind = EntryForm.Kind.REFUND, title = "Back", payers = listOf(Payer(a, "5")), refunds = "0e000000-0000-4000-9000-000000000001")
        val content = r.contentOf().content as Refund
        assertEquals(listOf(Contribution(a, 500)), content.receivers)
        assertEquals("0e000000-0000-4000-9000-000000000001", content.refunds)
        assertEquals(r.refunds, EntryForm.of(content, eur, listOf(a, b)).refunds)
    }

    @Test
    fun thePreviewIsTheEffectASaveSealsAndAMissingTitleMovesNoMoney() {
        val f = dinner().copy(title = "")
        val effect = assertNotNull(f.preview(eur, listOf(a, b, c)))
        assertEquals(listOf(3334L, 3333L, 3333L), listOf(a, b, c).map { effect.shares[it]!!.toLong() })
        assertNull(f.copy(kind = EntryForm.Kind.TRANSFER, amount = "1", from = a, to = b).preview(eur, listOf(a, b, c)))
        assertNull(f.copy(payers = listOf(Payer(a, "x"))).preview(eur, listOf(a, b, c)))
    }

    @Test
    fun amountsAreTypedOverInTheReadersSeparator() {
        assertEquals(listOf("12,50", "0.05", "1234", "-0,01"), listOf(Amounts.text(1250, 2, ','), Amounts.text(5, 2), Amounts.text(1234, 0), Amounts.text(-1, 2, ',')))
        assertEquals(1000L, Amounts.parse("10 00", 0))
        assertEquals("0.5", Amounts.parseRate(" 0,5 "))
        assertNull(Amounts.parseRate("0"))
    }

    @Test
    fun theEditorsStepsAreTheFormsOwn() {
        val people = listOf("a", "b", "c")
        val split = EntryForm.equalSplit(listOf("a", "c"))
        // b taken in lands between a and c; what was typed for c stays
        val typed = EntryForm.typed(split, "c", "3")
        val withB = EntryForm.toggled(typed, "b", true, people)
        kotlin.test.assertEquals(listOf("a", "b", "c"), withB.rows.map { it.participant })
        kotlin.test.assertEquals("3", withB.rows.last().shares)
        kotlin.test.assertEquals(listOf("a", "c"), EntryForm.toggled(withB, "b", false, people).rows.map { it.participant })
        // the folded line
        val names = listOf(PotView.Person("a", "Anna"), PotView.Person("b", "Ben"))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.ED_SPLIT_ONLY, "Anna"), EntryForm.collapsedWords(EntryForm.equalSplit(listOf("a")), names))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.ED_SPLIT_EVERYONE, 2), EntryForm.collapsedWords(EntryForm.equalSplit(listOf("a", "b")), names))
        // the hints: a percent total off a hundred, an amounts total off its part, nothing by shares
        val pct = EntryForm.typed(EntryForm.typed(split.copy(mode = EntryForm.Mode.PERCENT), "a", "40"), "c", "50")
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.ED_PERCENT_TOTAL, "90,00"), EntryForm.splitHint(pct, 0, "EUR", 2, ','))
        val amounts = EntryForm.typed(split.copy(mode = EntryForm.Mode.AMOUNTS), "a", "1.00")
        kotlin.test.assertEquals(
            me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.ED_AMOUNTS_TOTAL, me.raddatz.veyq.mobile.words.Amount(100, "EUR", 2), me.raddatz.veyq.mobile.words.Amount(300, "EUR", 2)),
            EntryForm.splitHint(amounts, 300, "EUR", 2),
        )
        kotlin.test.assertNull(EntryForm.splitHint(amounts, 0, "EUR", 2))
        kotlin.test.assertNull(EntryForm.splitHint(split, 300, "EUR", 2))
        // payers: the next unused, removable past one; the rest shows without items
        val form = EntryForm.empty(Ledger.BaseCurrency("EUR", 2), people, "2026-11-10")
        kotlin.test.assertEquals("b", form.nextPayer(people))
        kotlin.test.assertFalse(form.canRemovePayer)
        kotlin.test.assertTrue(form.showsRest)
    }
}
