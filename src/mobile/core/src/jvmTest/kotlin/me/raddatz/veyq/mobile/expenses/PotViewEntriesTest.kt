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
    fun anEntryOpensForEveryViewerAnUnreadableOneBlankOnItsRevisionAnInvalidOneMarked() {
        val wrong = Expense("Wrong", "food", "2026-07-01", "EUR", 2, "1", 300, emptyList(), listOf(Contribution(anna, 200)), Allocation(emptyList(), Split.Equal(listOf(anna, ben))))
        val revs = people + listOf(Rev(dinner, 1, expense("Dinner", 300)), Rev(taxi, 1, expense("Taxi", 100)), Rev(taxi, 2, null), Rev(taxi + "0", 1, wrong))
        for (canWrite in listOf(true, false)) {
            val v = view(revs, canWrite)
            // A reader opens it too (read only: the editor's to say), never only a writer.
            val live = v.editing(dinner, today = "2026-11-08")!!
            assertEquals(Ledger.Status.LIVE to "Dinner", live.status to live.form.title)
            assertEquals(1L, live.basedOn)
            // Unreadable: a blank form dated today, on the revision it cannot read, so a save replaces that one.
            val unreadable = v.editing(taxi, today = "2026-11-08")!!
            assertEquals(Ledger.Status.UNREADABLE, unreadable.status)
            assertEquals("" to "2026-11-08", unreadable.form.title to unreadable.form.date)
            assertEquals(2L, unreadable.basedOn)
            assertEquals(listOf(anna, ben), unreadable.people.map { it.id })
            // Invalid: its content as held, marked.
            val invalid = v.editing(taxi + "0", today = "2026-11-08")!!
            assertEquals(Ledger.Status.INVALID to "Wrong", invalid.status to invalid.form.title)
        }
        // A deleted entry is no live one to open here (its own sheet shows it).
        assertNull(view(revs + Rev(dinner, 2, Deleted)).editing(dinner))
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

    @Test
    fun anEntrysLineAndBadgeAreTheCoresPieces() {
        val night = "0e000000-0000-4000-9000-000000000003"
        val pay = "0e000000-0000-4000-9000-000000000004"
        val v = view(
            listOf(Rev(anna, 1, ParticipantBody("Anna", true, 1)), Rev(ben, 1, ParticipantBody("Ben", true, 2))) + listOf(
                Rev(dinner, 1, expense("Dinner", 300)),
                Rev(taxi, 1, expense("Taxi", 1000, ben, currency = "JPY", rate = "0.01").copy(minorUnits = 0, category = "lost-one")),
                Rev(night, 1, Refund("Hotel", "accommodation", "2026-07-02", "EUR", 2, "1", 50, emptyList(), listOf(Contribution(anna, 50), Contribution(ben, 0)), Allocation(emptyList(), Split.Equal(listOf(anna, ben))))),
                Rev(pay, 1, Transfer(null, "2026-07-03", "EUR", 2, "1", 150, emptyList(), ben, anna)),
                Rev(pay, 2, Transfer(null, "2026-07-03", "EUR", 2, "1", 150, emptyList(), ben, anna)),
            ),
        )
        fun line(id: String) = v.line(v.entries.single { it.id == id })
        val said = { n: me.raddatz.veyq.mobile.words.Notice -> me.raddatz.veyq.mobile.words.Piece.Said(n) }
        assertEquals(
            listOf(me.raddatz.veyq.mobile.words.Piece.Day("2026-07-01"), said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.CAT_FOOD)),
                said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.POT_EXPENSE_BY, me.raddatz.veyq.mobile.words.Joined(listOf("Anna"), ", ")))),
            line(dinner),
        )
        // A category the Pot no longer holds is unknown; an amount in another currency follows in its own digits.
        assertEquals(said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.CAT_UNKNOWN)), line(taxi)[1])
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Money(1000, "JPY", 0), line(taxi).last())
        assertEquals(said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.POT_REFUND_TO, me.raddatz.veyq.mobile.words.Joined(listOf("Anna", "Ben"), ", "))), line(night)[2])
        assertEquals(listOf(me.raddatz.veyq.mobile.words.Piece.Day("2026-07-03"), said(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.POT_TRANSFER_BETWEEN, "Ben", "Anna"))), line(pay))
        // Edited is said quietly; invalid and unreadable as a danger; nothing for a plain live entry.
        assertEquals(me.raddatz.veyq.mobile.words.Msg.POT_EDITED to false, v.badge(v.entries.single { it.id == pay })!!.let { it.notice.msg to it.danger })
        assertNull(v.badge(v.entries.single { it.id == dinner }))
        // An erased participant reads as a deleted person.
        assertEquals(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.POT_DELETED_PERSON), v.personWords(""))
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("Mine"), PotView(PotView.Source("T", PotMeta(base, listOf("mine" to "Mine")), null, LedgerCheck.of(emptyList()), null, emptySet(), true)).categoryLabel("mine"))
    }

    @Test
    fun aHistoryRowAndAMissingRecordAreSaidByTheCore() {
        val v = view(people + listOf(Rev(dinner, 1, expense("Dinner", 300)), Rev(dinner, 2, Deleted)))
        val said = { m: me.raddatz.veyq.mobile.words.Msg -> me.raddatz.veyq.mobile.words.Piece.Said(me.raddatz.veyq.mobile.words.Notice(m)) }
        assertEquals(
            listOf(me.raddatz.veyq.mobile.words.Piece.Plain("Dinner"), me.raddatz.veyq.mobile.words.Piece.Money(300, "EUR", 2)),
            v.historyLine(PotView.HistoryRow(1, PotView.HistoryKind.MONEY, "Dinner", 300, 0, null)),
        )
        assertEquals(listOf(said(me.raddatz.veyq.mobile.words.Msg.HISTORY_DELETED)), v.historyLine(PotView.HistoryRow(2, PotView.HistoryKind.DELETED, null, null, 0, null)))
        assertEquals(said(me.raddatz.veyq.mobile.words.Msg.POT_DELETED_PERSON), v.missingName(PotView.Missing(PotView.MissingKind.ERASED_PERSON, "")))
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("Ben"), v.missingName(PotView.Missing(PotView.MissingKind.PERSON, "Ben")))
        assertTrue(me.raddatz.veyq.mobile.session.NodeReport.canSend("SPAM", "it is spam", goodFaith = true))
        assertFalse(me.raddatz.veyq.mobile.session.NodeReport.canSend("SPAM", "   ", goodFaith = true))
        assertFalse(me.raddatz.veyq.mobile.session.NodeReport.canSend(null, "x", goodFaith = true))
        assertFalse(me.raddatz.veyq.mobile.session.NodeReport.canSend("SPAM", "x", goodFaith = false))
        // the report's room counts as the web's maxlength does (UTF-16 units), its categories in the web's order
        assertEquals(254, me.raddatz.veyq.mobile.session.NodeReport.contact("ä".repeat(300)).length)
        assertEquals(2000, me.raddatz.veyq.mobile.session.NodeReport.reason("x".repeat(2500)).length)
        assertEquals("CHILD_SEXUAL_ABUSE", me.raddatz.veyq.mobile.session.NodeReport.CATEGORIES.first().token)
        assertEquals(me.raddatz.veyq.mobile.words.Msg.REPORT_CSAM_NOTE, me.raddatz.veyq.mobile.session.NodeReport.note("CHILD_SEXUAL_ABUSE"))
        kotlin.test.assertNull(me.raddatz.veyq.mobile.session.NodeReport.note("TERMS_BREACH"))
    }

    @Test
    fun theSettingsOfferTheCurrencyAndCategoriesToWhoMayChangeThem() {
        val writer = view(people)
        assertTrue(writer.currencyEditable && writer.categoriesShown && writer.canAddCategory)
        val reader = view(people, canWrite = false)
        // a reader sees no currency picker and no categories where the Pot has none of its own
        assertFalse(reader.currencyEditable || reader.categoriesShown || reader.canAddCategory)
    }
}
