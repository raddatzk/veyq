package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * *Finish your profile*'s check (ADR 0084 decision 7) against a running dev instance, as a throwaway ([Throwaway], never
 * the seeded accounts): the throwaway's name cleared through the profile model (no picture), the check then true; the name
 * set back, the check false. With `VEYQ_LIVE_PROFILE_CLEARED=1` the name stays cleared, for an app's pass of the prompt
 * (set it back in the app, or by a run without it).
 */
class LiveFinishProfileTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
    private val runs = mutableListOf<LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.forEach { it.removeAll() }

    @Test
    fun aProfileWithoutANameOrAPictureIsAskedToBeFinished() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        runTest(timeout = 3.minutes) {
            val run = LiveRun("VEYQ", t.file).also { runs += it }
            val account = VeyqAccount.signIn(p, origin, t.email(), t.env.getValue("VEYQ_PASSWORD"), t.env.getValue("VEYQ_SECRET_KEY"), run.device(), client)
            val tenant = account.open(account.tenants.first { it.domain == t.env.getValue("VEYQ_TENANT_DOMAIN") })
            val m = tenant.profile(null)
            m.load()
            val (first, last) = m.current.firstName to m.current.lastName
            m.setFirstName("")
            m.setLastName("")
            m.save()
            val door = TenantProfileDoor(tenant)
            try {
                assertTrue(ProfileModel.unfinished(door, account.suite), "a profile with no name and no picture was not asked to be finished")
            } finally {
                if (System.getenv("VEYQ_LIVE_PROFILE_CLEARED") != "1") {
                    val back = tenant.profile(null)
                    back.load()
                    back.setFirstName(first.ifBlank { "Throwaway" })
                    back.setLastName(last)
                    back.save()
                    assertFalse(ProfileModel.unfinished(door, account.suite), "a named profile was asked to be finished")
                }
            }
        }
    }
}
