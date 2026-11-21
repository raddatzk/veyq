package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Pot's view as the wire carries it to a link's guest (ADR 0150 decision 5; the guest doors' test opens the door). */
class PotViewGuestTest {
    @Test
    fun theWireCarriesTheCoresOfferNeverCanWrite() {
        // the desktops draw *Add entry* by it
        val shown = PotFixture.view(canWrite = false, guest = true, offersEntries = true).shown()
        assertTrue(shown.offersEntries, "the wire's view does not offer Add entry to an unnamed READ_WRITE guest")
        assertFalse(shown.canWrite)
    }
}
