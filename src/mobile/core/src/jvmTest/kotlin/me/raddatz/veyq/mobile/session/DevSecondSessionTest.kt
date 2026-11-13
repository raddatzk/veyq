package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.words.Msg
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Dev tooling, not a test of the product: a second session of the seeded principal for an app's Settings pass
 * (`cr-app-account-settings` phase 2), to be signed out from the app's list. With `VEYQ_LIVE` and
 * `VEYQ_DEV_SECOND_SESSION=<label>` it signs in on a fresh device, names itself `<label>` through the register (the row
 * the app's list shows), and waits until its page no longer reads — signed out from elsewhere —, printing after how
 * long; at `VEYQ_DEV_SECOND_SESSION_WAIT_S` (default 900) it signs itself out and fails. Only its own session is touched.
 */
class DevSecondSessionTest {
    @Test
    fun aSecondSessionWaitsToBeSignedOutFromAnApp() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val label = System.getenv("VEYQ_DEV_SECOND_SESSION") ?: return@runBlocking
        val wait = System.getenv("VEYQ_DEV_SECOND_SESSION_WAIT_S")?.toLong() ?: 900L
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val p = BouncyCastlePrimitives
        val account = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), testDevice(p)) { block ->
            HttpClient(OkHttp, block)
        }
        val page = AccountSettings(AccountSettings.Over(account), Alone(account), account.email) { System.currentTimeMillis() }
        page.refresh()
        page.rename(label)
        assertNull(page.current.refusal, "the second session could not name itself")
        println("dev second session: signed in as \"$label\"; waiting up to $wait s to be signed out")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < wait * 1000) {
            delay(3_000)
            page.refresh()
            if (page.current.refusal == Msg.ACCOUNT_SETTINGS_LOAD_FAILED) {
                println("dev second session: signed out from elsewhere after ${(System.currentTimeMillis() - start) / 1000} s")
                account.http.close()
                return@runBlocking
            }
        }
        account.signOut()
        fail("not signed out within $wait s: signed itself out")
    }

    /**
     * A session a pass left behind (an app stopped before its "Sign out") signed out from a fresh login: with `VEYQ_LIVE`,
     * `VEYQ_DEV_SIGN_OUT_LABEL=<label>` and `VEYQ_DEV_SIGN_OUT_SINCE=<yyyy-MM-ddTHH:mm>` (local time, the minute the list
     * shows as "signed in"), exactly one family of that label signed in that minute is signed out — none or several, and
     * nothing is; then the fresh login signs itself out.
     */
    @Test
    fun aLeftOverSessionIsSignedOut() = runBlocking {
        val origin = System.getenv("VEYQ_LIVE") ?: return@runBlocking
        val label = System.getenv("VEYQ_DEV_SIGN_OUT_LABEL") ?: return@runBlocking
        val since = java.time.LocalDateTime.parse(System.getenv("VEYQ_DEV_SIGN_OUT_SINCE") ?: return@runBlocking)
        val minute = since.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        val p = BouncyCastlePrimitives
        val account = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), testDevice(p)) { block ->
            HttpClient(OkHttp, block)
        }
        val page = AccountSettings(AccountSettings.Over(account), Alone(account), account.email) { System.currentTimeMillis() }
        try {
            page.refresh()
            val matches = page.current.families.orEmpty().filter {
                !it.current && it.label == label && it.createdAt != null && it.createdAt!! in minute until minute + 60_000
            }
            if (matches.size != 1) fail("${matches.size} sessions named \"$label\" signed in at $since: none signed out")
            assertNull(page.signOut(matches.single().id), "the left-over session's sign-out signed this one out")
            println("dev sign-out: the \"$label\" session of $since signed out")
        } finally {
            account.signOut()
        }
    }

    /** The page's trust and sign-out without a [SessionModel]: no trust is enrolled. */
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
}
