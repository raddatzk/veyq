package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.session.MemoryDeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * veyq's one ask (ADR 0124 decisions 1, 2 and 7; the web's `DonationBanner.member.test.ts` and `.guest.test.ts`): shown
 * while neither this device nor the account holds a dismissal, a failed read showing it, an account's dismissal copied onto
 * the device, a dismissal kept here first and on the account after. Bites, each measured red: [Donation.memberShows]
 * without its device check, without copying the account's dismissal, hiding on a failed read; [Donation.dismissMember]
 * writing the account only.
 */
class DonationTest {
    @Test
    fun aMemberIsAskedWhileNeitherTheDeviceNorTheAccountHoldsADismissal() = runTest {
        val store = MemoryDeviceStore()
        assertTrue(Donation(store).memberShows { false })
        assertTrue(Donation(store).memberShows { error("offline") }, "a read that fails shows it (A4)")
        var asked = false
        store.write(Donation.KEY, "1")
        assertFalse(Donation(store).memberShows { asked = true; false })
        assertFalse(asked, "the device's dismissal asks the account nothing")
    }

    @Test
    fun theAccountsDismissalIsCopiedOntoTheDevice() = runTest {
        val store = MemoryDeviceStore()
        val d = Donation(store)
        assertFalse(d.memberShows { true })
        assertEquals("1", store.read(Donation.KEY))
        assertTrue(d.dismissedHere.value)
        assertFalse(Donation(store).guestShows, "and a guest on this device is not asked either")
    }

    @Test
    fun aDismissalIsKeptHereFirst_aFailedAccountWriteNotSaid() = runTest {
        val store = MemoryDeviceStore()
        val d = Donation(store)
        var written = false
        d.dismissMember { written = true }
        assertTrue(written)
        assertTrue(d.dismissedHere.value)
        val other = MemoryDeviceStore()
        Donation(other).dismissMember { error("offline") }
        assertEquals("1", other.read(Donation.KEY), "the device's holds when the account's write fails")
        val guest = Donation(MemoryDeviceStore())
        assertTrue(guest.guestShows)
        guest.dismissHere()
        assertFalse(guest.guestShows)
    }
}
