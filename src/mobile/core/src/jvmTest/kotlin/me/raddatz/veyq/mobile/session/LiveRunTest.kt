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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [LiveRun]'s promise against a running dev instance (`VEYQ_LIVE`): a live case leaves the account as it found it. A
 * case's sign-in through [SessionModel] — the device trusted by the password where the server has a wrap slot free,
 * the offer answered "Not now", a tenant opened, and no sign-out, as a failing case leaves it — adds a session family
 * and a register entry (and a DEVICE wrap where a slot was free); [LiveRun.removeAll] takes each away again. Counted by
 * a meter that is itself a live device, its own family and entry left out of the count and taken off after.
 *
 * Its bite: with the case's in-body [LiveRun.removeAll] left out the counts stay one higher and the last assertion fails
 * (its `finally` still takes the device off). A live case of another run against the same account in the same seconds
 * moves the counts too: run it alone. The register is compared by its entries: the case's own is there during the case
 * and gone after, and nothing else was added. Where it is full ([DeviceRegisterStore.MAX_DEVICES]), the meter's and the
 * case's entries may each evict the least recently seen **orphan** (a device no family or wrap row names) — what every
 * sign-in at the cap does — so there the entries after are a subset of those before, and exactly them where the
 * register had room (measured 2026-11-14: the dev principal's register held 31 entries, the meter's left out).
 */
class LiveRunTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }

    private data class Counts(val families: Int, val devices: Set<String>, val wraps: Int) {
        override fun toString() = "Counts(families=$families, devices=${devices.size}, wraps=$wraps)"
    }

    @Test
    fun aLiveCaseLeavesTheAccountAsItFoundIt() = runBlocking {
        val run = LiveRun()
        val origin = run.origin
        assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val before = counts()
        println("[live run] before: $before")
        // the meter and the case each add an entry: past the register's cap a write may evict an orphan's
        val roomy = before.devices.size + 2 <= DeviceRegisterStore.MAX_DEVICES

        try {
            val case = run.device()
            val model = SessionModel.accounts(p, case, client, Hidden({}, emptySet()), RecentUses({}, emptyList()))
            model.start()
            val opened = assertNotNull(
                model.signIn(origin!!, run.env.getValue("VEYQ_EMAIL"), run.env.getValue("VEYQ_PASSWORD"), run.env.getValue("VEYQ_SECRET_KEY")),
                "the sign-in failed: ${model.current}",
            )
            model.notNow()
            val trusted = opened.account.trusted != null
            val during = counts()
            println("[live run] during: $during (the device ${if (trusted) "trusted" else "not trusted: no wrap slot free"})")
            assertEquals(before.families + 1, during.families, "the case's sign-in made no session family")
            assertEquals(before.wraps + if (trusted) 1 else 0, during.wraps, "the case's trust did not show as a wrap")
            assertTrue(case.id in during.devices, "the case's device is not in the register")
            assertTrue(before.devices.containsAll(during.devices - case.id), "the register holds an entry the case did not explain")

            run.removeAll() // the bite: without this line the counts stay one higher and the assertion below fails
            val after = counts()
            println("[live run] after: $after")
            assertEquals(before.families, after.families, "the live case left a session family behind")
            assertEquals(before.wraps, after.wraps, "the live case left a wrap behind")
            assertFalse(case.id in after.devices, "the live case left its register entry behind")
            if (roomy) assertEquals(before.devices, after.devices, "the register changed") else assertTrue(before.devices.containsAll(after.devices))
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
                account.readRegister().devices.keys - device.id,
                door.savedKeys().size,
            )
        } finally {
            account.http.close()
            meter.removeAll()
        }
    }
}
