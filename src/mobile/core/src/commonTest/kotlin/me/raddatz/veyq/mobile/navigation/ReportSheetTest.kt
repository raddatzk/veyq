package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A node's report sheet (ADR 0093, ADR 0129 decision 2): the form's rules, one send, the receipt, the privacy link. */
class ReportSheetTest {
    private val folder = NodeAt("s", "f", "FOLDER", "Trip")

    private class Door(override val origin: String = "https://veyq.example") : ReportSheet.Door {
        val sent = mutableListOf<List<String>>()
        var answer = NodeReport.Sent(NodeReport.Outcome.SENT, "abcd1234", "2026-11-11T10:00:00Z")
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String): NodeReport.Sent {
            gate?.await()
            sent += listOf(scope.name, category, reason, contact)
            return answer
        }
    }

    @Test
    fun the_privacy_link_is_the_tenants_notice_at_the_reporters_contact() {
        assertEquals("https://veyq.example/privacy#reporter-contact", ReportSheet(folder, Door()).privacyUrl)
        assertEquals("http://veyq.localhost:8080/privacy#reporter-contact", ReportSheet(folder, Door("http://veyq.localhost:8080/")).privacyUrl)
    }

    @Test
    fun a_report_is_sent_only_with_a_category_a_reason_and_good_faith() = runTest {
        val door = Door()
        val sheet = ReportSheet(folder, door, backgroundScope)
        assertFalse(sheet.current.canSend)
        sheet.category("NOT_A_CATEGORY")
        assertNull(sheet.current.category)
        sheet.category(NodeReport.CSAM)
        assertEquals(Msg.REPORT_CSAM_NOTE, sheet.current.note)
        sheet.category("TERMS_BREACH")
        assertNull(sheet.current.note)
        sheet.reason("   ")
        sheet.goodFaith(true)
        assertFalse(sheet.current.canSend)
        // nothing goes out while the form is not complete
        sheet.send()
        assertTrue(door.sent.isEmpty())
        sheet.reason("x".repeat(NodeReport.REASON_MAX + 10))
        assertEquals(NodeReport.REASON_MAX, sheet.current.reason.length)
        sheet.contact("c".repeat(NodeReport.CONTACT_MAX + 1))
        assertEquals(NodeReport.CONTACT_MAX, sheet.current.contact.length)
        sheet.goodFaith(false)
        assertFalse(sheet.current.canSend)
        sheet.goodFaith(true)
        assertTrue(sheet.current.canSend)
        sheet.whole(false)
        assertEquals(Msg.REPORT_SCOPE_NAME_HINT, sheet.current.scopeHint)
    }

    @Test
    fun a_report_goes_once_and_its_receipt_ends_the_form() = runTest {
        val door = Door().apply { gate = CompletableDeferred() }
        val sheet = ReportSheet(folder, door, backgroundScope)
        sheet.category("ILLEGAL_CONTENT"); sheet.reason("why"); sheet.goodFaith(true); sheet.whole(false)
        val first = async { sheet.send() }
        runCurrent()
        // under way: the form holds still and a second send sends nothing
        assertTrue(sheet.current.sending)
        sheet.reason("changed")
        assertEquals("why", sheet.current.reason)
        assertFalse(sheet.current.canSend)
        val second = async { sheet.send() }
        door.gate!!.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf(listOf("NAME", "ILLEGAL_CONTENT", "why", "")), door.sent)
        assertEquals("abcd1234", sheet.current.sent?.reference)
        assertEquals(Notice(Msg.REPORT_RECEIPT, "abcd1234", "2026-11-11T10:00:00Z"), sheet.current.receipt)
        // sent: no further send, no edit
        assertFalse(sheet.current.canSend)
        sheet.send()
        assertEquals(1, door.sent.size)
    }

    @Test
    fun a_refused_report_says_why_and_keeps_the_form() = runTest {
        val door = Door().apply { answer = NodeReport.Sent(NodeReport.Outcome.TOO_MANY_OPEN) }
        val sheet = ReportSheet(folder, door, backgroundScope)
        sheet.category("ILLEGAL_CONTENT"); sheet.reason("why"); sheet.goodFaith(true)
        sheet.send()
        assertEquals(Notice(Msg.REPORT_TOO_MANY_OPEN), sheet.current.problem)
        assertNull(sheet.current.sent)
        assertTrue(sheet.current.canSend)
        door.answer = NodeReport.Sent(NodeReport.Outcome.SENT, "r", "t")
        sheet.send()
        assertNull(sheet.current.problem)
        assertEquals(2, door.sent.size)
    }
}
