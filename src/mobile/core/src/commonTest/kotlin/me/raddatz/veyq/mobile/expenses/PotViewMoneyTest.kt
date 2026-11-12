package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.expenses.PotFixture.ANNA
import me.raddatz.veyq.mobile.expenses.PotFixture.BEN
import me.raddatz.veyq.mobile.expenses.PotFixture.DINNER
import me.raddatz.veyq.mobile.expenses.PotFixture.Rev
import me.raddatz.veyq.mobile.words.Amount
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An amount always comes with its currency (CR D8): every amount a Pot's view hands an app — a row's line, a balance and
 * its facts, a person's facts, a history line — carries the Pot's base or the entry's own currency, so no app falls back
 * to one of its own (Apple's `"EUR"`, Android's locale's).
 */
class PotViewMoneyTest {
    private val museum = "0e000000-0000-4000-9000-000000000002"

    private val inDollars = Expense(
        "Museum", "leisure", "2026-07-02", "USD", 2, "0.9", 2000, emptyList(),
        listOf(Contribution(BEN, 2000)), Allocation(emptyList(), Split.Equal(listOf(ANNA, BEN))),
    )

    private val view = PotFixture.view(
        listOf(Rev(DINNER, 1, PotFixture.expense("Dinner", 3000)), Rev(DINNER, 2, PotFixture.expense("Dinner, edited", 3200)), Rev(museum, 1, inDollars)),
    )

    /** Every amount in [x], however deep in a notice it sits, as (currency, minor units). */
    private fun amounts(x: Any?): List<String?> = when (x) {
        is Piece.Money -> listOf(x.currency)
        is Amount -> listOf(x.currency)
        is Piece.Said -> amounts(x.notice)
        is Notice -> x.args.flatMap(::amounts)
        is Joined -> x.parts.flatMap(::amounts)
        is List<*> -> x.flatMap(::amounts)
        else -> emptyList()
    }

    @Test
    fun everyAmountAViewHandsCarriesItsCurrency() {
        val said = buildList {
            view.entries.forEach { addAll(amounts(view.line(it))) }
            view.balances.forEach { addAll(amounts(view.balanceFacts(it))); addAll(amounts(view.balance(it).notice)) }
            view.personEntries.forEach { addAll(amounts(view.personFacts(it))) }
            view.history(DINNER).forEach { addAll(amounts(view.historyLine(it))) }
        }
        assertTrue(said.size >= 8, "too few amounts looked at: ${said.size}")
        assertTrue(said.none { it == null }, "an amount without its currency: $said")
        assertEquals(setOf("EUR", "USD"), said.filterNotNull().toSet())
    }

    @Test
    fun anEntryInAnotherCurrencySaysItsOwn() {
        val row = view.entries.single { it.id == museum }
        assertEquals(listOf<String?>("USD"), amounts(view.line(row)))
    }
}
