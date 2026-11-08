package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An entry's life on screen (the web's `EntriesPanel.vue`, `EntryHistory.vue`, `receiptRules.ts`): a deleted entry leaves
 * the list for the Pot's deleted ones, restorable until its tombstone's `purgeAfter`; its history newest first, each
 * revision with its writer as the directory names them; the settle-up rows carry who pays whom.
 */
class PotViewEntriesTest {
    private val anna = "0e000000-0000-4000-8000-000000000001"
    private val ben = "0e000000-0000-4000-8000-000000000002"
    private val dinner = "0e000000-0000-4000-9000-000000000001"
    private val taxi = "0e000000-0000-4000-9000-000000000002"
    private val base = Ledger.BaseCurrency("EUR", 2)
    private val userA = "6e000000-0000-4000-8000-000000000001"

    private fun expense(title: String, amount: Long, payer: String = anna, currency: String = "EUR", rate: String = "1") =
        Expense(title, "food", "2026-07-01", currency, 2, rate, amount, emptyList(), listOf(Contribution(payer, amount)), Allocation(emptyList(), Split.Equal(listOf(anna, ben))))

    private class Rev(val id: String, val revision: Long, val content: RevisionContent?, val writer: String? = null, val purgeAfter: Long? = null)

    private fun view(revs: List<Rev>, canWrite: Boolean = true): PotView {
        val held = revs.mapIndexed { i, r ->
            HeldRevision(r.id, r.revision, 1_000L * (i + 1), "", r.content?.let { RevisionBody(emptyList(), it) }, if (r.content == null) RevisionUnreadable.Reason.KEY else null, r.writer, r.purgeAfter)
        }
        val ledger = Ledger.build(held, base)
        return PotView(
            PotView.Source(
                "Trip", PotMeta(base, emptyList()), ledger, LedgerCheck.of(held), null, emptySet(), canWrite,
                held = held, directory = mapOf(userA to "Anna Adler"),
            ),
        )
    }

    private val people = listOf(Rev(anna, 1, ParticipantBody("Anna", true, 1)), Rev(ben, 1, ParticipantBody("Ben", true, 2)))

    @Test
    fun aDeletedEntryLeavesTheListForTheDeletedOnesAndIsRestorableUntilItsPurge() {
        val v = view(people + listOf(Rev(dinner, 1, expense("Dinner", 300)), Rev(dinner, 2, Deleted, purgeAfter = 5_000), Rev(taxi, 1, expense("Taxi", 100, ben))))
        assertEquals(listOf(taxi), v.entries.map { it.id })
        assertEquals(listOf(dinner), v.deletedEntries.map { it.id })
        // Its last content is what the deleted row shows.
        assertEquals("Dinner", v.deletedEntries.single().title)
        // It counts nowhere, yet shows what it was: its content's amount in the base currency.
        assertEquals(300L, v.deletedEntries.single().amount)
        assertEquals(2, v.latestRevision(dinner))
        assertEquals(5_000L, v.purgeAfter(dinner))
        assertTrue(v.restorable(dinner, 4_999))
        assertFalse(v.restorable(dinner, 5_000))
        // A live entry is no restore; nor is anything without the right to write.
        assertFalse(v.restorable(taxi, 0))
        assertNull(v.purgeAfter(taxi))
        assertFalse(view(people + listOf(Rev(dinner, 1, expense("Dinner", 300)), Rev(dinner, 2, Deleted)), canWrite = false).restorable(dinner, 0))
        // No time served: offered.
        assertTrue(view(people + listOf(Rev(dinner, 1, expense("Dinner", 300)), Rev(dinner, 2, Deleted))).restorable(dinner, Long.MAX_VALUE))
        // A restored entry is live again, and no restore.
        // (A purge time served on a live revision is no tombstone's: it is not read.)
        val restored = view(people + listOf(Rev(dinner, 1, expense("Dinner", 300)), Rev(dinner, 2, Deleted, purgeAfter = 5_000), Rev(dinner, 3, expense("Dinner", 300), purgeAfter = 9_000)))
        assertEquals(listOf(dinner), restored.entries.map { it.id })
        assertFalse(restored.restorable(dinner, 0))
        assertNull(restored.purgeAfter(dinner))
    }

    @Test
    fun theHistoryIsNewestFirstWithTheBaseAmountAndTheWriter() {
        val v = view(
            people + listOf(
                Rev(dinner, 1, expense("Dinner", 300), "user:$userA"),
                // A link guest whose identity id happens to be a member's user id is still no member.
                Rev(dinner, 2, expense("Dinner out", 1000, currency = "CHF", rate = "1.5"), "share:$userA"),
                Rev(dinner, 3, Deleted, "user:6e000000-0000-4000-8000-0000000000ff"),
                Rev(dinner, 4, null),
            ),
        )
        val h = v.history(dinner)
        assertEquals(listOf(4L, 3L, 2L, 1L), h.map { it.revision })
        assertEquals(listOf(PotView.HistoryKind.UNREADABLE, PotView.HistoryKind.DELETED, PotView.HistoryKind.MONEY, PotView.HistoryKind.MONEY), h.map { it.kind })
        assertEquals("Dinner out", h[2].title)
        assertEquals(1500L, h[2].amount)
        assertEquals(300L, h[3].amount)
        // A member by the directory's name; a link guest and a former member are "someone".
        assertEquals(listOf(null, null, null, "Anna Adler"), h.map { it.writer })
        assertEquals(listOf(4000L, 3000L, 2000L, 1000L).map { it + 2000 }, h.map { it.at })
    }

    @Test
    fun aSettleUpRowCarriesWhoPaysWhom() {
        val v = view(people + listOf(Rev(dinner, 1, expense("Dinner", 300))))
        val s = v.settleUp.single()
        assertEquals(Triple(ben, anna, 150L), Triple(s.fromId, s.toId, s.amount))
        assertEquals("Ben" to "Anna", s.from to s.to)
    }
}
