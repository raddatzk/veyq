package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.apis.AccountProfileApi
import me.raddatz.veyq.mobile.api.models.ProfileUpdateRequest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.WebpEncoder
import me.raddatz.veyq.mobile.navigation.Donation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The last parity gaps against a running dev instance, as a throwaway ([Throwaway], never the seeded accounts; its owner
 * owns its tenant): a space's picture held for the switcher and the drawer after its owner set it, and gone after the
 * removal ([SpaceFaces], ADR 0148 decision 6); an owner's roster with no stuck rotation and no re-key due saying no status
 * line ([SpaceStatus]); the donation banner's dismissal kept on the account, so another device's banner stays away
 * ([Donation], ADR 0124 decision 2). The JVM has no WebP encoder: the icon's bytes are a stand-in the core seals as any.
 */
class LiveParityTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
    private val runs = mutableListOf<LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.forEach { it.removeAll() }

    private suspend fun open(t: Throwaway.Accounts, run: LiveRun): TenantSession {
        val account = VeyqAccount.signIn(p, Throwaway.origin(), t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), run.device(), client)
        return account.open(account.tenants.first { it.domain == t.env.getValue("VEYQ_TENANT_DOMAIN") })
    }

    @Test
    fun aSpacesPictureIsHeldForTheSwitcherOnceItsOwnerSetIt_andTheRosterSaysNoStatus() {
        val t = Throwaway.accounts()
        runTest(timeout = 3.minutes) {
            val tenant = open(t, LiveRun("VEYQ", t.file).also { runs += it })
            val id = tenant.tenant.tenantId
            val picture = "RIFF0000WEBPVP8 ".encodeToByteArray() + ByteArray(64) { it.toByte() }
            val encoder = WebpEncoder { _, _, _, _ -> picture }
            val set = tenant.icons(encoder).set(id, ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
            assertTrue(set.done, "the owner's icon was not set")
            try {
                val held = tenant.faces.held.value
                assertContentEquals(picture, held.picture(id), "the switcher holds the tenant's picture as it was sealed")
                assertTrue(held.round > 0)
                assertFalse(held.marked(id), "no compromise rotation needs an owner here")
                assertEquals(emptyList(), tenant.members.read().status, "no stuck rotation, no re-key due: no status line")
            } finally {
                assertTrue(tenant.icons(encoder).clear(id).done, "the icon was not removed")
            }
            assertNull(tenant.faces.held.value.picture(id), "a removed picture leaves the initial")
        }
    }

    @Test
    fun aDismissedDonationBannerStaysAwayOnAnotherDevice() {
        val t = Throwaway.accounts()
        runTest(timeout = 3.minutes) {
            val tenant = open(t, LiveRun("VEYQ", t.file).also { runs += it })
            val account = tenant.account
            val api = AccountProfileApi(tenant.base, account.http)
            val onAccount: suspend () -> Boolean = { account.call { api.getMyProfile() }.donationBannerDismissed }
            account.call { api.updateMyProfile(ProfileUpdateRequest(donationBannerDismissed = false)) }
            val here = Donation(MemoryDeviceStore())
            assertTrue(here.memberShows(onAccount), "a fresh account was not asked")
            here.dismissMember { account.call { api.updateMyProfile(ProfileUpdateRequest(donationBannerDismissed = true)) }; Unit }
            val another = MemoryDeviceStore()
            assertFalse(Donation(another).memberShows(onAccount), "another device asked again after the dismissal")
            assertEquals("1", another.read(Donation.KEY), "the account's dismissal was not copied onto the other device")
        }
    }
}
