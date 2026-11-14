package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.words.Msg
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The account's Settings page (`uc.account.sessions`) against a running dev instance (`VEYQ_LIVE`).
 *
 * As the seeded principal: a second login of it, on a second device, is listed with the server's `current` mark, names
 * itself through the register, is signed out from the first and drops out of its list; the first's own family still
 * refreshes afterwards (the control). Only the two sessions this test signs in are touched — never the principal's
 * other families, nor the owner's browser.
 *
 * "Sign out every other" ends every family of an account, so it runs only against a throwaway account the dev seeding
 * made: `VEYQ_LIVE_OTHERS_ENV=<an env file>` with its `VEYQ_EMAIL`, `VEYQ_PASSWORD`, `VEYQ_SECRET_KEY` — never the
 * principal. Without `VEYQ_LIVE` both report as skipped (an assumption), never as passed.
 */
class LiveAccountSettingsTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }

    /** Every device a case signs in with, per account, taken off that account again after it, whatever it came to. */
    private val runs = mutableListOf<LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.forEach { it.removeAll() }

    @Test
    fun aSecondLoginIsListedRenamedSignedOutAndGone() {
        val origin = needs("VEYQ_LIVE")
        runTest(timeout = 3.minutes) {
            // `.run/.env` of this checkout, or VEYQ_ENV_FILE's (a worktree has none)
            val env = env(System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env"))
            val run = LiveRun().also { runs += it }
            val (control, controlPage) = signIn(origin, env, run)
            val (second, secondPage) = signIn(origin, env, run)
            try {
                secondPage.refresh()
                val mine = assertNotNull(secondPage.current.device.familyId, "the server marked no family current")
                assertEquals(mine, secondPage.current.families!!.first().id)
                assertTrue(secondPage.current.families!!.first().current)
                assertEquals(env.getValue("VEYQ_EMAIL").trim().lowercase(), secondPage.current.email.lowercase())
                assertNotNull(secondPage.current.emailConfirmed)

                val name = "App settings live ${System.currentTimeMillis()}"
                secondPage.rename(name)
                assertNull(secondPage.current.refusal)
                assertEquals(name, secondPage.current.device.label)

                controlPage.refresh()
                val listed = assertNotNull(controlPage.current.families!!.firstOrNull { it.id == mine }, "the second login is not listed")
                assertFalse(listed.current)
                assertEquals(name, listed.label, "the rename did not reach the other device through the register")

                assertNull(controlPage.signOut(mine), "signing another family out signed this one out")
                assertNull(controlPage.current.families!!.firstOrNull { it.id == mine }, "the signed-out family is still listed")

                // the control: this device's own family still answers
                controlPage.refresh()
                assertNull(controlPage.current.refusal, "the control's own session no longer reads")
                assertTrue(controlPage.current.families!!.first().current)

                secondPage.refresh()
                assertEquals(Msg.ACCOUNT_SETTINGS_LOAD_FAILED, secondPage.current.refusal, "the signed-out session still reads")
            } finally {
                second.http.close()
                control.signOut()
            }
        }
    }

    @Test
    fun signingOutEveryOtherEndsTheSecondAndKeepsThisOne() {
        val origin = needs("VEYQ_LIVE")
        val file = needs("VEYQ_LIVE_OTHERS_ENV")
        runTest(timeout = 3.minutes) {
            val env = env(File(file))
            val run = LiveRun(envFile = File(file)).also { runs += it }
            val (control, controlPage) = signIn(origin, env, run)
            val (second, secondPage) = signIn(origin, env, run)
            try {
                secondPage.refresh()
                val theirs = assertNotNull(secondPage.current.device.familyId)
                controlPage.refresh()
                assertTrue(controlPage.current.families!!.any { it.id == theirs })

                controlPage.signOutOthers()
                assertNull(controlPage.current.refusal)
                // the control: this device's own family is all that is left, and it still reads
                controlPage.refresh()
                assertNull(controlPage.current.refusal, "signing out every other ended this device's own session")
                assertEquals(listOf(true), controlPage.current.families!!.map { it.current })

                secondPage.refresh()
                assertEquals(Msg.ACCOUNT_SETTINGS_LOAD_FAILED, secondPage.current.refusal, "the other session still reads")
            } finally {
                second.http.close()
                control.signOut()
            }
        }
    }

    /** The environment variable [name]; where it is not set the case is skipped (an assumption: reported as skipped, never passed). */
    private fun needs(name: String): String {
        val value = System.getenv(name)
        assumeTrue("$name is not set: a live case", value != null)
        return value!!
    }

    /** One login of the account in [env] on a fresh device, with its Settings page. */
    private suspend fun signIn(origin: String, env: Map<String, String>, run: LiveRun): Pair<VeyqAccount, AccountSettings<VeyqAccount, TenantSession>> {
        val account = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), run.device(), client)
        return account to AccountSettings(AccountSettings.Over(account), Alone(account), account.email) { System.currentTimeMillis() }
    }

    /** The page's trust and sign-out without a [SessionModel]: this test enrols no trust. */
    private class Alone(private val account: VeyqAccount) : AccountSettings.Here<VeyqAccount, TenantSession> {
        override fun trust() = AccountSettings.Trust(account.trusted?.method, account.trusted?.unlockLevel, emptyList())
        override suspend fun stay(level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(password: String) = TrustChange.FAILED
        override suspend fun stopTrusting() {}
        override suspend fun signOut(): SessionModel.SignedOut<VeyqAccount, TenantSession> {
            account.signOut()
            return SessionModel.SignedOut(true, null)
        }
    }

    private fun env(file: File): Map<String, String> = file.readLines()
        .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
        .toMap()
}
