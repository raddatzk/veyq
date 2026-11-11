package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.expenses.PotFixture.ANNA
import me.raddatz.veyq.mobile.expenses.PotFixture.BEN
import me.raddatz.veyq.mobile.expenses.PotFixture.DINNER
import me.raddatz.veyq.mobile.expenses.PotFixture.Rev
import me.raddatz.veyq.mobile.expenses.PotFixture.expense
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The entry editor both apps drew (the web's `EntryEditor.vue`, ADR 0127 decision 2), now core's: the refusal said once
 * tried, a kind's change carrying the amount, the rate filled and an answer for a currency changed since dropped, the
 * receipts' caps, a stale save read again, the skipped receipts said, the delete only once asked, today the door's —
 * over a door the test plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditorTest {
    private class Save(val entryId: String, val form: EntryForm, val basedOn: Long?, val pending: List<String>, val own: Set<String>)

    private class Door(var view: PotView?) : EntryEditor.Door {
        var today = "2026-11-12"
        private var ids = 0
        val asked = mutableListOf<Triple<String, String, CompletableDeferred<ExchangeRates.Filled?>>>()
        val saves = mutableListOf<Save>()
        val written = ArrayDeque<EntryEditor.Written>()
        /** What the Pot reads after the next save (a stale one's re-read). */
        var after: PotView? = null
        val deletes = mutableListOf<Pair<String, Long>>()
        var deleted = PotSession.WriteOutcome.WRITTEN

        override fun view() = view
        override fun today() = today
        override fun newId() = "id-${++ids}"
        override suspend fun rate(currency: String, day: String): ExchangeRates.Filled? {
            val d = CompletableDeferred<ExchangeRates.Filled?>()
            asked += Triple(currency, day, d)
            return d.await()
        }
        override suspend fun save(entryId: String, form: EntryForm, basedOn: Long?, pending: List<PotSession.PendingReceipt>, own: Set<String>): EntryEditor.Written {
            saves += Save(entryId, form, basedOn, pending.map { it.id }, own)
            after?.let { view = it }
            return written.removeFirstOrNull() ?: EntryEditor.Written(PotSession.WriteOutcome.WRITTEN)
        }
        override suspend fun delete(entryId: String, basedOn: Long): PotSession.WriteOutcome {
            deletes += entryId to basedOn
            return deleted
        }
    }

    private val dinner = listOf(Rev(DINNER, 1, expense("Dinner", 3000)))

    private fun TestScope.editor(door: Door, entryId: String? = null, pending: List<PotSession.PendingReceipt> = emptyList()) =
        EntryEditor.open(door, entryId, '.', pending, backgroundScope, UnconfinedTestDispatcher(testScheduler))!!

    private fun png(n: Int = 1) = EntryEditor.Picked(ByteArray(n), "image/png", "r.png")

    @Test
    fun aNewEntryIsDatedTheDoorsTodayPaidByTheFirstPersonAndSplitEqually() = runTest {
        val door = Door(PotFixture.view()).apply { today = "2026-11-12" }
        val s = editor(door).current
        assertEquals("2026-11-12", s.form.date)
        assertEquals(listOf(ANNA), s.form.payers.map { it.participant })
        assertEquals(listOf(ANNA, BEN), s.form.rest.rows.map { it.participant })
        assertTrue(s.isNew && s.editable)
    }

    @Test
    fun theRefusalIsSaidOnlyOnceASaveWasTriedAndNothingIsSent() = runTest {
        val door = Door(PotFixture.view())
        val e = editor(door)
        assertNull(e.current.refusal, "a refusal said before any save")
        val saved = e.save()
        assertFalse(saved.done)
        assertEquals("payers", e.current.refusal?.problem)
        assertEquals("payers", e.current.refusal?.at)
        assertTrue(door.saves.isEmpty(), "a form that cannot be saved was sent")
        // typed into: the next refusal is said at once, the form being tried
        e.change(EditorChange.Paid(0, "12.50"))
        assertEquals("title", e.current.refusal?.at)
        e.change(EditorChange.Title("Lunch"))
        assertNull(e.current.refusal)
        assertEquals(listOf(1250L, -1250L + 1250L), listOf(e.current.preview.sumOf { it.given }, e.current.preview.sumOf { it.given - it.share }))
    }

    @Test
    fun aKindsChangeKeepsTheAmountAcross() = runTest {
        val e = editor(Door(PotFixture.view()))
        e.change(EditorChange.Paid(0, "12.50"))
        e.change(EditorChange.Kind(EntryForm.Kind.TRANSFER))
        assertEquals(EntryForm.Kind.TRANSFER, e.current.form.kind)
        assertEquals("12.50", e.current.form.amount, "a transfer's empty amount takes the payers' total")
        e.change(EditorChange.Paid(0, ""))
        e.change(EditorChange.Amount("7"))
        e.change(EditorChange.Kind(EntryForm.Kind.REFUND))
        assertEquals("7", e.current.form.payers.single().amount, "a lone payer without an amount takes the transfer's")
    }

    @Test
    fun anotherCurrencyFillsItsRateFromTheDayAndAnAnswerForACurrencyChangedSinceIsDropped() = runTest {
        val door = Door(PotFixture.view())
        val e = editor(door)
        assertNull(e.current.rate, "the base currency asks for no rate")
        e.change(EditorChange.Currency("USD"))
        runCurrent()
        assertEquals(EntryEditor.Rate(EntryEditor.RateState.FILLING), e.current.rate)
        assertEquals("USD" to "2026-11-12", door.asked.single().let { it.first to it.second }, "the rate is asked for the door's today")
        assertEquals("", e.current.form.rate)
        e.change(EditorChange.Currency("GBP"))
        runCurrent()
        door.asked[0].third.complete(ExchangeRates.Filled("0.9", "2026-11-11"))
        runCurrent()
        assertEquals("", e.current.form.rate, "USD's answer filled GBP's rate")
        assertEquals(EntryEditor.RateState.FILLING, e.current.rate?.state)
        door.asked[1].third.complete(ExchangeRates.Filled("1.15", "2026-11-11"))
        runCurrent()
        assertEquals("1.15", e.current.form.rate)
        assertEquals(EntryEditor.Rate(EntryEditor.RateState.FILLED, "2026-11-11"), e.current.rate)
        // none in the table: said, the rate left to be typed
        e.change(EditorChange.Currency("JPY"))
        runCurrent()
        door.asked[2].third.complete(null)
        runCurrent()
        assertEquals(EntryEditor.Rate(EntryEditor.RateState.MISSING), e.current.rate)
        assertEquals(0, e.current.form.minorUnits, "the yen's digits")
        // no service named: nothing asked
        val off = Door(PotFixture.view(rates = false))
        val e2 = editor(off)
        e2.change(EditorChange.Currency("USD"))
        runCurrent()
        assertTrue(off.asked.isEmpty())
        assertNull(e2.current.rate)
    }

    @Test
    fun aTypedRateIsNeverOverwrittenByTheFill() = runTest {
        val door = Door(PotFixture.view())
        val e = editor(door)
        e.change(EditorChange.Currency("USD"))
        runCurrent()
        e.change(EditorChange.RateTyped("0.8"))
        door.asked.single().third.complete(ExchangeRates.Filled("0.9", "2026-11-11"))
        runCurrent()
        assertEquals("0.8", e.current.form.rate)
        assertNull(e.current.rate)
    }

    @Test
    fun receiptsAreCappedBySizeKindAndCountAndARefusedPickIsSaid() = runTest {
        val e = editor(Door(PotFixture.view()))
        assertTrue(e.current.canAddReceipt)
        e.addReceipts(listOf(EntryEditor.Picked(ByteArray(Receipts.MAX_BYTES.toInt() + 1), "image/jpeg", "big.jpg")))
        assertTrue(e.current.refusedPick, "a file above 10 MiB was taken")
        assertTrue(e.current.pending.isEmpty())
        e.addReceipts(listOf(EntryEditor.Picked(ByteArray(3), "text/plain", "notes.txt")))
        assertTrue(e.current.refusedPick, "a text file was taken")
        e.addReceipts(listOf(EntryEditor.Picked(null, "image/png", "gone.png")))
        assertTrue(e.current.refusedPick, "a file that did not read was taken")
        e.addReceipts(listOf(EntryEditor.Picked(ByteArray(Receipts.MAX_BYTES.toInt()), "application/pdf", "max.pdf")))
        assertFalse(e.current.refusedPick)
        assertEquals(listOf("max.pdf"), e.current.pending.map { it.name })
        e.addReceipts(List(Receipts.MAX_PER_ENTRY) { png() })
        assertTrue(e.current.refusedPick, "the 101st receipt was taken")
        assertEquals(Receipts.MAX_PER_ENTRY, e.current.pending.size)
        assertFalse(e.current.canAddReceipt)
        // each held under an id of its own, minted by the door
        assertEquals(Receipts.MAX_PER_ENTRY, e.current.pending.map { it.id }.toSet().size)
        e.dropReceipt(e.current.pending.first().id)
        assertTrue(e.current.canAddReceipt)
    }

    @Test
    fun aStaleSaveReadsTheOtherVersionIntoTheFormKeepsItsOwnReceiptsAndSaysSo() = runTest {
        val door = Door(PotFixture.view(dinner))
        val e = editor(door, DINNER)
        assertEquals("Dinner", e.current.form.title)
        e.change(EditorChange.Title("Mine"))
        e.addReceipts(listOf(png()))
        val receipt = e.current.pending.single().id
        door.after = PotFixture.view(dinner + Rev(DINNER, 2, expense("Theirs", 4000)))
        door.written += EntryEditor.Written(PotSession.WriteOutcome.STALE, uploaded = listOf(receipt))
        val saved = e.save()
        assertFalse(saved.done)
        assertEquals(Notice(Msg.ED_STALE), saved.notice)
        assertEquals(Notice(Msg.ED_STALE), e.current.notice)
        assertEquals(1L, door.saves.single().basedOn)
        assertEquals("Theirs", e.current.form.title, "the other writer's version is not shown")
        assertEquals(listOf(receipt), e.current.form.receipts, "the receipt this editor uploaded was lost")
        assertTrue(e.current.pending.isEmpty())
        assertFalse(e.current.saving)
        // saving again goes on top of their revision, without uploading the receipt twice
        assertTrue(e.save().done)
        val again = door.saves.last()
        assertEquals(2L, again.basedOn)
        assertTrue(again.pending.isEmpty())
        assertEquals(setOf(receipt), again.own)
        assertEquals(DINNER, again.entryId)
    }

    @Test
    fun aSaveThatLeftReceiptsOutSaysWhichAndIsDone() = runTest {
        val door = Door(PotFixture.view())
        val e = editor(door)
        e.change(EditorChange.Paid(0, "10"))
        e.change(EditorChange.Title("Taxi"))
        e.addReceipts(listOf(png()))
        door.written += EntryEditor.Written(PotSession.WriteOutcome.WRITTEN, skipped = listOf("r.png"))
        val saved = e.save()
        assertTrue(saved.done)
        assertEquals(Notice(Msg.RECEIPTS_NO_ROOM, Joined(listOf("r.png"), ", ")), saved.notice)
        assertTrue(e.current.done)
        // a new entry's record is minted once, at open
        assertEquals("id-1", door.saves.single().entryId)
        assertNull(door.saves.single().basedOn)
    }

    @Test
    fun aRefusedSaveIsSaidAndTheEditorStaysOpen() = runTest {
        val door = Door(PotFixture.view(dinner))
        val e = editor(door, DINNER)
        door.written += EntryEditor.Written(PotSession.WriteOutcome.BLOCKED)
        val saved = e.save()
        assertFalse(saved.done)
        assertEquals(Notice(Msg.ED_BLOCKED), saved.notice)
        assertFalse(e.current.done)
        assertFalse(e.current.saving)
    }

    @Test
    fun anEntryIsDeletedOnlyOnceAsked() = runTest {
        val door = Door(PotFixture.view(dinner))
        val e = editor(door, DINNER)
        assertFalse(e.delete(), "deleted without asking")
        assertTrue(door.deletes.isEmpty())
        e.askDelete()
        assertTrue(e.current.asking)
        e.cancelAsk()
        assertFalse(e.delete(), "deleted after the ask was cancelled")
        e.askDelete()
        assertTrue(e.delete())
        assertEquals(listOf(DINNER to 1L), door.deletes)
        assertTrue(e.current.done)
        // a new entry has nothing to delete
        val fresh = editor(Door(PotFixture.view()))
        fresh.askDelete()
        assertFalse(fresh.current.asking)
    }

    @Test
    fun aReaderSeesTheEntryAndChangesNothing() = runTest {
        val door = Door(PotFixture.view(dinner, canWrite = false))
        val e = editor(door, DINNER)
        assertFalse(e.current.editable)
        e.change(EditorChange.Title("x"))
        assertEquals("Dinner", e.current.form.title)
        assertFalse(e.save().done)
        assertTrue(door.saves.isEmpty())
        assertFalse(e.current.canAddReceipt)
        assertNotNull(e.current.form)
    }
}
