package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.session.LocalDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The open Pot's model (ADR 0129 decision 2): its live poll, its writes' saving and refusal, its editor's today — over a Pot the test plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class PotModelTest {
    private val rome = ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Rome")
    private val paris = ObjectRef("s", "q", "EXPENSES_POT", ApplicationId.EXPENSES, "Paris")

    private class Played(var view: PotView = PotFixture.view()) : PotModel.Opened {
        var polls = 0
        val ops = mutableListOf<PotModel.Op>()
        var outcome = PotSession.WriteOutcome.WRITTEN
        override val session: PotSession? = null
        override fun view() = view
        override suspend fun poll(): Boolean { polls++; return true }
        override suspend fun refresh() {}
        override suspend fun rekeyed() {}
        override fun newId() = "id"
        override suspend fun write(op: PotModel.Op): PotSession.WriteOutcome {
            ops += op
            return outcome
        }
    }

    private val noon = 1_794_484_800_000L // 2026-11-12T12:00:00Z

    private fun TestScope.model(pot: Played, now: () -> Long = { currentTime }) = PotModel(null, now, { pot }, backgroundScope)

    @Test
    fun theLivePollIsSkippedWhileAWriteIsSaving() = runTest {
        val pot = Played()
        val m = model(pot)
        m.open(rome)
        val gate = CompletableDeferred<PotSession.WriteOutcome>()
        val write = launch { m.writing(said = true) { gate.await() } }
        runCurrent()
        assertTrue(m.current!!.saving)
        val live = backgroundScope.launch { m.live() }
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, pot.polls, "the live poll read while a write was saving")
        gate.complete(PotSession.WriteOutcome.WRITTEN)
        write.join()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(pot.polls >= 1, "the live poll did not resume after the write")
        live.cancel()
    }

    @Test
    fun aWritesRefusalIsTheMessageAndAWrittenOneClearsIt() = runTest {
        val m = model(Played())
        m.open(rome)
        assertEquals(false, m.writing(said = true) { PotSession.WriteOutcome.STALE })
        assertEquals("STALE", m.current!!.message)
        assertEquals(false, m.current!!.saving)
        assertEquals(true, m.writing(said = true) { PotSession.WriteOutcome.WRITTEN })
        assertNull(m.current!!.message)
        // the editor says its own: the Pot's message stays as it was
        m.writing(said = false) { PotSession.WriteOutcome.REFUSED }
        assertNull(m.current!!.message)
    }

    @Test
    fun aCloseOfAnotherPotIsIgnored() = runTest {
        val m = model(Played())
        m.open(rome)
        m.close(paris)
        assertEquals(rome, m.current?.ref)
        m.close(rome)
        assertNull(m.current)
    }

    @Test
    fun aNewEntryIsDatedTheCoresToday() = runTest {
        val m = model(Played(), now = { noon })
        m.open(rome)
        val editor = assertNotNull(m.editor(null, '.'))
        assertEquals(LocalDays.isoOf(noon), editor.current.form.date)
        assertEquals("2026-11-12", editor.current.form.date)
        editor.close()
    }

    private val owing = listOf(PotFixture.Rev(PotFixture.DINNER, 1, PotFixture.expense("Dinner", 3000)))

    @Test
    fun aSettleUpIsRecordedOnTheCoresToday() = runTest {
        val pot = Played(PotFixture.view(owing))
        val m = model(pot, now = { noon })
        m.open(rome)
        val rows = m.current!!.view!!.settleUp
        assertEquals(1, rows.size, "Ben owes Anna")
        assertTrue(m.record(rows))
        assertEquals(listOf<PotModel.Op>(PotModel.Op.Record(rows, "2026-11-12")), pot.ops)
        // nothing to record sends nothing; a reader records nothing
        assertEquals(false, m.record(emptyList()))
        val reader = Played(PotFixture.view(owing, canWrite = false))
        val r = model(reader, now = { noon })
        r.open(rome)
        assertEquals(false, r.record(r.current!!.view!!.settleUp))
        assertTrue(reader.ops.isEmpty())
        assertEquals(1, pot.ops.size)
    }

    @Test
    fun aRecordsRefusalIsThePotsMessage() = runTest {
        val pot = Played(PotFixture.view(owing)).apply { outcome = PotSession.WriteOutcome.STALE }
        val m = model(pot)
        m.open(rome)
        assertEquals(false, m.record(m.current!!.view!!.settleUp))
        assertEquals("STALE", m.current!!.message)
    }

    private val deleted = PotFixture.DINNER

    private fun tombstone(purgeAfter: Long?) =
        owing + PotFixture.Rev(deleted, 2, Deleted, purgeAfter = purgeAfter)

    @Test
    fun aRestoreIsWrittenInsideItsWindowOnItsLatestRevision() = runTest {
        val pot = Played(PotFixture.view(tombstone(purgeAfter = noon + 1)))
        val m = model(pot, now = { noon })
        m.open(rome)
        assertTrue(m.restorable(deleted))
        assertTrue(m.restore(deleted))
        assertEquals(listOf<PotModel.Op>(PotModel.Op.Restore(deleted, 2)), pot.ops)
    }

    @Test
    fun aRestoreIsRefusedOutsideItsWindowByTheCoresClock() = runTest {
        val pot = Played(PotFixture.view(tombstone(purgeAfter = noon)))
        val m = model(pot, now = { noon })
        m.open(rome)
        assertEquals(false, m.restorable(deleted), "the window closes at purgeAfter")
        assertEquals(false, m.restore(deleted))
        assertTrue(pot.ops.isEmpty(), "a restore outside its window was sent")
        assertEquals(PotModel.NOT_RESTORABLE, m.current!!.message)
        assertEquals(me.raddatz.veyq.mobile.words.Msg.ENTRY_NOT_RESTORABLE, me.raddatz.veyq.mobile.words.Notices.potRefused(m.current!!.message!!))
    }

    @Test
    fun theBaseCurrencyIsRefusedOnceMoneyIsIn() = runTest {
        val full = Played(PotFixture.view(owing))
        val m = model(full)
        m.open(rome)
        val settings = assertNotNull(m.settings())
        assertEquals(false, settings.setBaseCurrency("USD"))
        assertTrue(full.ops.isEmpty(), "a base currency was sent for a Pot holding money")
        assertEquals(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.SETTINGS_CURRENCY_FIXED), settings.current.notice)

        val empty = Played(PotFixture.view())
        val e = model(empty)
        e.open(rome)
        val s = assertNotNull(e.settings())
        assertTrue(s.setBaseCurrency("EUR"), "the Pot's own currency is nothing to do")
        assertTrue(empty.ops.isEmpty())
        assertTrue(s.setBaseCurrency("JPY"))
        assertEquals(listOf<PotModel.Op>(PotModel.Op.BaseCurrency("JPY", 0)), empty.ops)
        assertNull(s.current.notice)
    }

    @Test
    fun anEraseIsSentOnlyOnceAsked() = runTest {
        val pot = Played(PotFixture.view())
        val m = model(pot)
        m.open(rome)
        val s = assertNotNull(m.settings())
        assertEquals(false, s.erase(), "an erase nobody asked")
        assertTrue(pot.ops.isEmpty())
        s.askErase(PotFixture.BEN)
        assertEquals(PotFixture.BEN, s.current.asking?.personId)
        s.cancelAsk()
        assertEquals(false, s.erase(), "an erase cancelled")
        assertTrue(pot.ops.isEmpty())
        s.askErase(PotFixture.BEN)
        assertTrue(s.erase())
        assertEquals(listOf<PotModel.Op>(PotModel.Op.Erase(PotFixture.BEN)), pot.ops)
        assertNull(s.current.asking)
        // an unsettled person is never asked about
        val owes = Played(PotFixture.view(owing))
        val o = model(owes)
        o.open(rome)
        val so = assertNotNull(o.settings())
        so.askErase(PotFixture.BEN)
        assertNull(so.current.asking)
    }

    @Test
    fun aSettingsRefusalIsTheSettingsNoticeNotThePots() = runTest {
        val pot = Played(PotFixture.view()).apply { outcome = PotSession.WriteOutcome.INVALID }
        val m = model(pot)
        m.open(rome)
        val s = assertNotNull(m.settings())
        assertEquals(false, s.addPerson("Cleo"))
        assertEquals(me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.PEOPLE_INVALID), s.current.notice)
        assertNull(m.current!!.message)
        assertEquals(false, s.addPerson("  "), "a blank name is sent")
        assertEquals(1, pot.ops.size)
        pot.outcome = PotSession.WriteOutcome.WRITTEN
        assertTrue(s.toggleActive(PotFixture.ANNA))
        assertEquals(PotModel.Op.SetActive(PotFixture.ANNA, false), pot.ops.last())
        assertNull(s.current.notice)
    }

    @Test
    fun aLivePollStartedWhileThePotOpensWaitsForItsReadThenPolls() = runTest {
        val pot = Played()
        val gate = CompletableDeferred<Unit>()
        val m = PotModel(null, { currentTime }, { gate.await(); pot }, backgroundScope)
        val opening = launch { m.open(rome) }
        runCurrent()
        val live = backgroundScope.launch { m.live() }
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(live.isActive, "the live poll gave up on a Pot still opening")
        assertEquals(0, pot.polls)
        gate.complete(Unit)
        opening.join()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(pot.polls >= 1, "the live poll did not start once the Pot was read")
        live.cancel()
    }

    @Test
    fun aLivePollEndsWhenItsPotCloses() = runTest {
        val pot = Played()
        val m = model(pot)
        m.open(rome)
        advanceTimeBy(10_000)
        val live = backgroundScope.launch { m.live() }
        runCurrent()
        assertEquals(1, pot.polls, "a poll that comes back after a while catches up at once")
        m.close(rome)
        runCurrent()
        assertTrue(live.isCompleted, "the live poll outlived its Pot")
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, pot.polls, "a closed Pot was polled")
    }

    @Test
    fun aWritesRefusalIsSaidAtOnceAndOneRefusedBeforeItWasSentIsARefusal() = runTest {
        val pot = Played(PotFixture.view(owing)).apply { outcome = PotSession.WriteOutcome.STALE }
        val m = model(pot)
        m.open(rome)
        assertEquals(false, m.record(m.current!!.view!!.settleUp))
        assertEquals(me.raddatz.veyq.mobile.words.Msg.ENTRY_STALE, m.refusal().msg)
        m.clearMessage()
        assertEquals(false, m.record(emptyList()))
        assertEquals(me.raddatz.veyq.mobile.words.Msg.PEOPLE_FAILED, m.refusal().msg)
    }
}
