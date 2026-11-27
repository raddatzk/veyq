package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.account.KitRecord
import me.raddatz.veyq.mobile.account.KitStatus
import me.raddatz.veyq.mobile.account.KitStep
import me.raddatz.veyq.mobile.account.KitWriter
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account's kit record and the renewal (ADR 0157, uc.account.renew-emergency-kit; the web's `kitRecord.test.ts` and
 * `KitRenewalDialog.test.ts`): the record names the layout this writer prints and reads as layout 1 wherever it is none, a
 * new kit is made only from a Secret Key that passed the floor, its record is written once the kit is kept — never at
 * render —, a write that fails leaves a working kit and says so, and the notice speaks once per layout.
 */
class KitRenewalTest {
    private val p = testPrimitives

    @Test
    fun theRecordNamesThisLayoutAndReadsAsLayoutOneWhereItIsNone() {
        val a = PlayedCredentials(p)
        val mk = a.masterKey()!!
        assertEquals(2, KitRecord.layoutOf(a.suite, mk, KitRecord.seal(a.suite, mk)))
        assertEquals(1, KitRecord.layoutOf(a.suite, mk, null), "no record: a kit made before the record existed")
        assertEquals(1, KitRecord.layoutOf(a.suite, p.randomBytes(32), KitRecord.seal(a.suite, mk)), "another account's blob")
        assertEquals(1, KitRecord.layoutOf(a.suite, mk, "bm90IGEgcmVjb3Jk"), "garbage")
        for (json in listOf("""{"layout":0}""", """{"layout":"2"}""", """{"layout":2.5}""", """{"other":2}""", "[]")) {
            val sealed = b64(a.suite.seal(Context("kit-record"), mk, json.encodeToByteArray()))
            assertEquals(1, KitRecord.layoutOf(a.suite, mk, sealed), json)
        }
        val three = b64(a.suite.seal(Context("kit-record"), mk, """{"layout":3}""".encodeToByteArray()))
        assertEquals(3, KitRecord.layoutOf(a.suite, mk, three), "a later writer's layout reads as itself")
    }

    @Test
    fun theStatusIsReadOnceAFailedReadIsAskedAgainAndAKitMadeHereIsCurrent() = runTest {
        var reads = 0
        var fail = true
        val status = KitStatus { reads++; if (fail) error("offline") else KitRecord.LAYOUT_WITHOUT_RECORD }
        status.ensure()
        assertEquals(KitStatus.Standing.UNREADABLE, status.current.standing)
        fail = false
        status.ensure()
        status.ensure()
        assertEquals(2, reads, "read once it answered, asked again after a failure")
        assertEquals(KitStatus.Standing.OLDER, status.current.standing)
        assertTrue(status.current.older)
        status.remember()
        assertEquals(KitStatus.Standing.CURRENT, status.current.standing)
        assertEquals(KitWriter.LAYOUT, status.current.layout)
    }

    /** Bite: a notice that ignores the dismissal (or the layout it spoke of) is red here. */
    @Test
    fun theNoticeSpeaksWhileTheKitIsOlderUntilThisLayoutsNoticeWasDismissed() {
        val older = KitStatus.State(layout = 1)
        assertEquals(Notice(Msg.KIT_LAYOUT_NEWS_2), KitStatus.notice(older, dismissed = 0))
        assertNull(KitStatus.notice(older, dismissed = KitWriter.LAYOUT), "dismissed for this layout")
        assertEquals(Notice(Msg.KIT_LAYOUT_NEWS_2), KitStatus.notice(older, dismissed = KitWriter.LAYOUT - 1), "an earlier layout's notice dismissed: told once more")
        assertNull(KitStatus.notice(KitStatus.State(layout = KitWriter.LAYOUT), 0), "current")
        assertNull(KitStatus.notice(KitStatus.State(failed = true), 0), "unread")
    }

    private suspend fun kept(step: KitStep): KitStep.State {
        assertTrue(step.saveInto("en", 20_775L) {})
        step.confirm(true)
        return withContext(Dispatchers.Default) { withTimeout(5_000) { step.state.first { it.recorded != null } } }
    }

    @Test
    fun aNewKitIsMadeOnlyFromAKeyThatPassedAndItsRecordIsWrittenOnceKept() = runTest {
        val a = PlayedCredentials(p)
        val renewal = KitRenewal(a, p)
        assertNull(renewal.kit(a.password, p.randomBytes(32)))
        assertEquals(Msg.KIT_RENEW_REFUSED, renewal.current.refusal)
        assertNull(renewal.kit("not the password", a.secretKeyCopy()))
        assertEquals(Msg.KIT_RENEW_REFUSED, renewal.current.refusal)
        assertTrue(a.kitRecords.isEmpty(), "a refused proof wrote a record")

        val step = assertNotNull(renewal.kit(a.password, a.secretKeyCopy()))
        assertNull(renewal.current.refusal)
        assertTrue(a.kitRecords.isEmpty(), "written at render")
        val done = kept(step)
        assertEquals(true, done.recorded)
        assertNull(done.refusal)
        assertEquals(KitWriter.LAYOUT, KitRecord.layoutOf(a.suite, a.masterKey()!!, a.kitRecords.single()), "the record names this layout under the account's Master Key")
        step.confirm(false)
        step.confirm(true)
        assertEquals(1, a.kitRecords.size, "written once")
        step.close()
    }

    @Test
    fun aRecordThatDoesNotWriteLeavesAWorkingKitAndSaysSo() = runTest {
        val a = PlayedCredentials(p).apply { recordTaken = false }
        val step = assertNotNull(KitRenewal(a, p).kit(a.password, a.secretKeyCopy()))
        val done = kept(step)
        assertEquals(false, done.recorded)
        assertEquals(Msg.KIT_RENEW_NOT_NOTED, done.refusal)
        assertTrue(done.saved && done.ready, "the kit itself stands")
        step.close()
    }

    @Test
    fun theAddressChangesKitWritesItsRecordToo() = runTest {
        val a = PlayedCredentials(p).apply { taken = true }
        val step = assertNotNull(AddressChange(a, p).kit(a.password, a.secretKeyCopy()))
        assertTrue(a.kitRecords.isEmpty())
        assertEquals(true, kept(step).recorded)
        assertEquals(KitWriter.LAYOUT, KitRecord.layoutOf(a.suite, a.masterKey()!!, a.kitRecords.single()))
        step.close()
    }

    /** Bite: a step that records before a file was written and kept is red here. */
    @Test
    fun anUnconfirmedKitWritesNothing() = runTest {
        val a = PlayedCredentials(p)
        val step = assertNotNull(KitRenewal(a, p).kit(a.password, a.secretKeyCopy()))
        step.confirm(true)
        assertFalse(step.current.confirmed, "nothing saved yet: \"I saved it\" does not take")
        // a record would be written on the step's own scope: give it the time it would take
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) }
        assertTrue(a.kitRecords.isEmpty(), "a kit nobody saved was recorded")
        step.close()
    }
}
