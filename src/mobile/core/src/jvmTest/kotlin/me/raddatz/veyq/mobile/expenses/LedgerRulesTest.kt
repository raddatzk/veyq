package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ledger's rules the vector Pots do not reach, each beside its balanced control (the web's `ledger.test.ts` edge
 * cases): every reason an entry is refused, and the settle-up's tie broken by join order.
 */
class LedgerRulesTest {
    private val a = "0e000000-0000-4000-8000-000000000001"
    private val b = "0e000000-0000-4000-8000-000000000002"
    private val c = "0e000000-0000-4000-8000-000000000003"

    private fun expense(amount: Long, payers: List<Contribution>, allocation: Allocation) =
        Expense("Dinner", "food", "2026-07-01", "EUR", 2, "1", amount, emptyList(), payers, allocation)

    private val all = Split.Equal(listOf(a, b, c))

    @Test
    fun aBalancedEntryHasNoProblem() {
        assertNull(Ledger.entryProblem(expense(300, listOf(Contribution(a, 300)), Allocation(emptyList(), all))) { it in setOf(a, b, c) })
    }

    @Test
    fun everyReasonAnEntryIsRefused() {
        fun problem(e: MoneyContent, participants: Set<String> = setOf(a, b, c)) = Ledger.entryProblem(e) { it in participants }
        assertEquals(Ledger.Problem.PAYERS_UNBALANCED, problem(expense(300, listOf(Contribution(a, 200)), Allocation(emptyList(), all))))
        assertEquals(
            Ledger.Problem.RECEIVERS_UNBALANCED,
            problem(Refund("Back", "food", "2026-07-01", "EUR", 2, "1", 300, emptyList(), listOf(Contribution(a, 299)), Allocation(emptyList(), all))),
        )
        assertEquals(Ledger.Problem.NAMED_TWICE, problem(expense(300, listOf(Contribution(a, 100), Contribution(a, 200)), Allocation(emptyList(), all))))
        assertEquals(Ledger.Problem.NAMED_TWICE, problem(expense(300, listOf(Contribution(a, 300)), Allocation(emptyList(), Split.Equal(listOf(a, a))))))
        assertEquals(Ledger.Problem.ITEMS_ABOVE_TOTAL, problem(expense(300, listOf(Contribution(a, 300)), Allocation(listOf(Item("Wine", 400, all))))))
        assertEquals(Ledger.Problem.REST_UNALLOCATED, problem(expense(300, listOf(Contribution(a, 300)), Allocation(listOf(Item("Wine", 100, all))))))
        assertEquals(
            Ledger.Problem.PERCENT_UNBALANCED,
            problem(expense(300, listOf(Contribution(a, 300)), Allocation(emptyList(), Split.Percent(listOf(a to 5000L, b to 4000L))))),
        )
        assertEquals(
            Ledger.Problem.AMOUNTS_UNBALANCED,
            problem(expense(300, listOf(Contribution(a, 300)), Allocation(emptyList(), Split.Amounts(listOf(a to 100L, b to 100L))))),
        )
        assertEquals(Ledger.Problem.UNKNOWN_PARTICIPANT, problem(expense(300, listOf(Contribution(a, 300)), Allocation(emptyList(), all)), setOf(a, b)))
        // The sums alone, without the Pot's participants: the write path's first question.
        assertNull(Ledger.entryProblem(expense(300, listOf(Contribution(a, 300)), Allocation(emptyList(), all))))
    }

    @Test
    fun anEntryNamingNoParticipantCountsNowhere() {
        var t = 0L
        fun held(id: String, content: RevisionContent) = HeldRevision(id, 1, t++, "", RevisionBody(emptyList(), content), null)
        val revisions = listOf(
            held(a, ParticipantBody("Anna", true, 1)),
            held(b, ParticipantBody("Ben", true, 2)),
            held("0e000000-0000-4000-9000-000000000001", expense(300, listOf(Contribution(a, 300)), Allocation(emptyList(), all))),
        )
        val pot = Ledger.build(revisions, Ledger.BaseCurrency("EUR", 2))
        assertEquals(Ledger.Status.INVALID, pot.entries.single().status)
        assertEquals(Ledger.Problem.UNKNOWN_PARTICIPANT, pot.entries.single().problem)
        assertEquals(listOf(Big.ZERO, Big.ZERO), pot.balances.values.toList())
    }

    @Test
    fun theSettleUpBreaksATieByJoinOrder() {
        val balances = mapOf(b to Big.of(-500), a to Big.of(-500), c to Big.of(1000))
        val settle = Ledger.settleUp(balances, listOf(a, b, c))
        assertEquals(listOf(Ledger.Settlement(a, c, Big.of(500)), Ledger.Settlement(b, c, Big.of(500))), settle)
    }
}
