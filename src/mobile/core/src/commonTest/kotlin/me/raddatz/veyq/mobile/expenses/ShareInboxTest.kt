package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Shared files from arrival to where they went (ADR 0126), both apps' one state machine. */
class ShareInboxTest {
    private val file = PotSession.PendingReceipt("r1", byteArrayOf(1), "image/jpeg", "a.jpg", null)

    @Test
    fun files_wait_are_taken_given_back_and_end_with_the_save() {
        val inbox = ShareInbox()
        val incoming = ShareInbox.Incoming(listOf(file), refused = 2)
        inbox.receive(incoming)
        assertSame(incoming, inbox.waiting.value)
        assertEquals(listOf(file), inbox.take())
        assertNull(inbox.waiting.value, "taken: the pick mode ends")
        assertSame(incoming, inbox.giveBack(), "the editor closed unsaved: they wait again")
        assertSame(incoming, inbox.waiting.value)
        inbox.take()
        inbox.saved()
        assertNull(inbox.giveBack(), "once saved there is nothing to give back")
    }

    @Test
    fun nothing_is_taken_when_no_file_fits_and_a_cancel_ends_the_wait() {
        val inbox = ShareInbox()
        inbox.receive(ShareInbox.Incoming(emptyList(), refused = 3))
        assertNull(inbox.take(), "only refused files: nothing goes into an expense")
        inbox.cancel()
        assertNull(inbox.waiting.value)
        assertEquals(false, inbox.goesInto(null))
    }
}
