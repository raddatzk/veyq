package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A copy's entry, the web's `remapEntry`: every person, a refund's expense and an own category under new ids, a default
 *  category as it is, and only the receipts that are copied — and what comes out is still a revision its reader takes. */
class PotCopyTest {
    private fun u(n: Int) = "%08d-0000-4000-8000-000000000000".format(n)
    private val fresh = HashMap<String, String>()
    private var next = 100
    private val id = { old: String -> fresh.getOrPut(old) { u(next++) } }
    private val receipt = { old: String -> "r-" + old }

    @Test
    fun aRefundMapsItsPeopleItsExpenseAndItsOwnCategory() {
        val ana = u(1); val ben = u(2); val dinner = u(3); val own = u(4); val r1 = u(5); val r2 = u(6)
        val refund = Refund(
            "Back", own, "2026-11-09", "EUR", 2, "1", 900, listOf(r1, r2),
            listOf(Contribution(ana, 900)),
            Allocation(listOf(Item("Wine", 300, Split.Shares(listOf(ben to 1L)))), Split.Equal(listOf(ana, ben))), refunds = dinner,
        )
        val mapped = PotCopy.remap(refund, id, { "%08d-0000-4000-8000-000000000999".format(it.take(8).toInt()) }, listOf(r1)) as Refund
        assertEquals(listOf(id(ana)), mapped.receivers.map { it.participant })
        assertEquals(id(dinner), mapped.refunds)
        assertEquals(id(own), mapped.category)
        assertEquals(listOf(id(ben)), (mapped.allocation.items.single().split as Split.Shares).shares.map { it.first })
        assertEquals(listOf(id(ana), id(ben)), (mapped.allocation.rest as Split.Equal).participants)
        assertEquals(1, mapped.receipts.size, "only the copied receipt is listed")
        assertTrue(listOf(ana, ben, dinner, own).none { it in listOf(mapped.refunds, mapped.category) + mapped.receivers.map { r -> r.participant } })
        // what an import writes is a revision its own reader takes
        RevisionFormat.encode(RevisionBody(emptyList(), mapped))
    }

    @Test
    fun aDefaultCategoryStaysAndATransferMapsBothEnds() {
        val ana = u(1); val ben = u(2)
        val e = Expense("Taxi", "transport", "2026-11-09", "EUR", 2, "1", 500, emptyList(), listOf(Contribution(ana, 500)),
            Allocation(emptyList(), Split.Percent(listOf(ana to 50L, ben to 50L))))
        assertEquals("transport", (PotCopy.remap(e, id, receipt, emptyList()) as Expense).category)
        val t = PotCopy.remap(Transfer(null, "2026-11-09", "EUR", 2, "1", 500, emptyList(), ben, ana), id, receipt, emptyList()) as Transfer
        assertEquals(id(ben) to id(ana), t.from to t.to)
    }
}
