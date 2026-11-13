package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * [LiveRun]'s promise against a running dev instance (`VEYQ_LIVE`): a live case leaves the account as it found it. A
 * case's sign-in through [SessionModel] — the device trusted by the password where the server has a wrap slot free,
 * the offer answered "Not now", a tenant opened, and no sign-out, as a failing case leaves it — adds a session family
 * and a register entry (and a DEVICE wrap where a slot was free); [LiveRun.removeAll] takes each away again. Counted by
 * a meter that is itself a live device, its own family and entry left out of the count and taken off after.
 *
 * Its bite: with the case's in-body [LiveRun.removeAll] left out the counts stay one higher and the last assertion fails
 * (its `finally` still takes the device off). A live case of another run against the same account in the same seconds
 * moves the counts too: run it alone. It is skipped where the register has no room for two more entries below
 * [DeviceRegisterStore.MAX_DEVICES].
 */
class LiveRunTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }

    private data class Counts(val families: Int, val devices: Int, val wraps: Int)

    @Test
    fun aLiveCaseLeavesTheAccountAsItFoundIt() = runBlocking {
        val run = LiveRun()
        val origin = run.origin
        assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val before = counts()
        println("[live run] before: $before")
        // the meter and the case each add an entry: past the register's cap a write evicts another device's (#894), and
        // the count no longer says what the case left
        assumeTrue(
            "the register holds ${before.devices} devices: no room for the meter and the case below its cap",
            before.devices + 2 <= DeviceRegisterStore.MAX_DEVICES,
        )

        try {
            val model = SessionModel.accounts(p, run.device(), client, Hidden({}, emptySet()), RecentUses({}, emptyList()))
            model.start()
            val opened = assertNotNull(
                model.signIn(origin!!, run.env.getValue("VEYQ_EMAIL"), run.env.getValue("VEYQ_PASSWORD"), run.env.getValue("VEYQ_SECRET_KEY")),
                "the sign-in failed: ${model.current}",
            )
            model.notNow()
            val trusted = opened.account.trusted != null
            val during = counts()
            println("[live run] during: $during (the device ${if (trusted) "trusted" else "not trusted: no wrap slot free"})")
            assertEquals(Counts(before.families + 1, before.devices + 1, before.wraps + if (trusted) 1 else 0), during, "the case's sign-in did not show")

            run.removeAll() // the bite: without this line the counts stay one higher and the assertion below fails
            val after = counts()
            println("[live run] after: $after")
            assertEquals(before, after, "the live case left something behind")
        } finally {
            run.removeAll() // nothing left once the line above ran; the bitten run's device otherwise
        }
    }

    /** The account's active families, register entries and device wraps, the meter's own family and entry left out. */
    private suspend fun counts(): Counts {
        val meter = LiveRun()
        val device = meter.device("live run meter")
        val account = VeyqAccount.signIn(
            p, meter.origin!!, meter.env.getValue("VEYQ_EMAIL"), meter.env.getValue("VEYQ_PASSWORD"), meter.env.getValue("VEYQ_SECRET_KEY"), device, client,
        )
        val door = AccountSettings.Over(account)
        return try {
            Counts(
                door.families().count { it.current != true },
                account.readRegister().devices.keys.count { it != device.id },
                door.savedKeys().size,
            )
        } finally {
            account.http.close()
            meter.removeAll()
        }
    }
}
