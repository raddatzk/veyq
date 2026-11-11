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

/** The open Pot's model (ADR 0127 decision 2): its live poll, its writes' saving and refusal, its editor's today — over a Pot the test plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class PotModelTest {
    private val rome = ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Rome")
    private val paris = ObjectRef("s", "q", "EXPENSES_POT", ApplicationId.EXPENSES, "Paris")

    private class Played : PotModel.Opened {
        var polls = 0
        override val session: PotSession? = null
        override fun view() = PotFixture.view()
        override suspend fun poll(): Boolean { polls++; return true }
        override suspend fun refresh() {}
        override suspend fun rekeyed() {}
        override fun newId() = "id"
    }

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
        val noon = 1_794_484_800_000L // 2026-11-12T12:00:00Z
        val m = model(Played(), now = { noon })
        m.open(rome)
        val editor = assertNotNull(m.editor(null, '.'))
        assertEquals(LocalDays.isoOf(noon), editor.current.form.date)
        assertEquals("2026-11-12", editor.current.form.date)
        editor.close()
    }
}
