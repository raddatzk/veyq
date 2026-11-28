package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.session.DeviceStore

/**
 * veyq's one ask for a donation (ADR 0124; uc.platform.support-the-project; the web's `DonationBanner.vue` over
 * `donationDismissal.ts`). Where it shows is ADR 0124 decision 7's: the apps outside the stores — Linux and Windows, the
 * bridge's hosts — draw the banner, above the signed-in shell and a received link's page, never on the way in; Android
 * and Apple draw none (their Settings' *About veyq* carries the plain *Donate* link). The rule is one: a guest's dismissal
 * is kept on this device ([dismissHere]); a member's on this device and on the account (`donationBannerDismissed`, the
 * profile update), so it holds on every device — the banner shows only while neither holds one ([memberShows]).
 */
class Donation(private val store: DeviceStore) {
    companion object {
        /** The device's dismissal, one for the member and the guest (the web's one browser key). */
        const val KEY = "veyq.donationBannerDismissed"
    }

    private val _dismissed = MutableStateFlow(store.read(KEY) == "1")

    /** Whether this device holds a dismissal. */
    val dismissedHere: StateFlow<Boolean> = _dismissed.asStateFlow()

    /** A guest's banner: while this device holds no dismissal. */
    val guestShows: Boolean get() = !_dismissed.value

    /** Dismissed on this device, for good. */
    fun dismissHere() {
        store.write(KEY, "1")
        _dismissed.value = true
    }

    /**
     * A member's banner: not while this device holds a dismissal; else as the account's [onAccount] says — a dismissal
     * there is copied onto this device and shows nothing; a read that fails shows it (the web's A4: the ask stays possible).
     */
    suspend fun memberShows(onAccount: suspend () -> Boolean): Boolean {
        if (_dismissed.value) return false
        val held = try {
            onAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true
        }
        if (held) {
            dismissHere()
            return false
        }
        return true
    }

    /** A member's dismissal: on this device at once, then on the account through [onAccount]; a failed write is not said. */
    suspend fun dismissMember(onAccount: suspend () -> Unit) {
        dismissHere()
        try {
            onAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the device's dismissal holds; the next device asks once more (the web's ignored write)
        }
    }
}
