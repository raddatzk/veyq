package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.apis.AccountEmailChangeApi
import me.raddatz.veyq.mobile.api.models.EmailChangeConfirmRequest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import org.junit.Assume.assumeTrue
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The password and the address change (ADR 0148 decision 6) against a running dev instance, as a throwaway ([Throwaway],
 * never the seeded accounts — a file naming either FAILS). Each case puts the throwaway back as its file names it (the
 * password changed back, the address changed back), so the throwaway's `--erase` still signs in.
 */
class LiveCredentialFlowsTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }
    private val runs = mutableListOf<LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.forEach { it.removeAll() }

    private suspend fun signIn(origin: String, t: Throwaway.Accounts, email: String, password: String, run: LiveRun = LiveRun("VEYQ", t.file).also { runs += it }, device: ThisDevice = run.device()): VeyqAccount =
        VeyqAccount.signIn(p, origin, email, password, t.env.getValue("VEYQ_SECRET_KEY"), device, client)

    private fun key(t: Throwaway.Accounts): ByteArray = assertNotNull(SecretKeyText.read(p, t.env.getValue("VEYQ_SECRET_KEY")))

    /** uc.account.change-password: changed, the old password refused, the new one signs in to the same Master Key (the
     *  personal scope still opens), and changed back. */
    @Test
    fun aChangedPasswordSignsInAndTheOldOneIsRefused() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val email = t.email()
        val original = t.env.getValue("VEYQ_PASSWORD")
        val next = "live-changed-${System.currentTimeMillis()}"
        runTest(timeout = 4.minutes) {
            val account = signIn(origin, t, email, original)
            val tenant = account.tenants.first { it.domain == t.env.getValue("VEYQ_TENANT_DOMAIN") }
            val before = account.open(tenant).spaces().map { it.scopeId }.toSet()
            val change = account.passwordChange()
            change.run(original, next, next, key(t))
            assertTrue(change.current.done, "refused: ${change.current.refusal}")
            var changedBack = false
            try {
                val refused = runCatching { signIn(origin, t, email, original) }.exceptionOrNull()
                assertEquals(SignInFailure.Reason.CREDENTIALS, (refused as? SignInFailure)?.reason, "the old password still signs in")
                val again = signIn(origin, t, email, next)
                val after = again.open(again.tenants.first { it.domain == tenant.domain }).spaces().map { it.scopeId }.toSet()
                assertTrue(before.isNotEmpty() && after == before, "the new password's Master Key does not open the spaces: $before → $after")
                val back = again.passwordChange()
                back.run(next, original, original, key(t))
                assertTrue(back.current.done, "the password was not changed back: ${back.current.refusal}")
                changedBack = true
            } finally {
                if (!changedBack) println("[live] the throwaway's password is now the changed one; erase it by hand")
            }
        }
    }

    /**
     * uc.account.change-email: the request as the app sends it; the mailed link read from Mailpit and confirmed as the
     * web's page confirms it (`confirmEmailChange` with the link's token in the account's session); the old address
     * refused at sign-in, the new one signing in and re-addressing the record this device keeps; then changed back. This
     * Mac's dev backend may send no mail (JavaMail's EHLO stall, the owner's fix): the case is then SKIPPED after the
     * request, saying so — never passed.
     */
    @Test
    fun anAddressChangeConfirmedOnTheWebReaddressesTheRecord() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val email = t.email()
        val password = t.env.getValue("VEYQ_PASSWORD")
        val next = email.replace("@", "+moved@")
        runTest(timeout = 5.minutes) {
            val run = LiveRun("VEYQ", t.file).also { runs += it }
            val device = run.device()
            val account = signIn(origin, t, email, password, run, device)
            account.trustThisDevice(password, null)
            assertNotNull(account.trusted, "the device was not trusted")
            val token = changeAndRead(account, next, password, t)
            confirm(origin, account, token)
            val refused = runCatching { signIn(origin, t, email, password) }.exceptionOrNull()
            assertEquals(SignInFailure.Reason.CREDENTIALS, (refused as? SignInFailure)?.reason, "the old address still signs in")
            val moved = signIn(origin, t, next, password, run, device)
            assertEquals(next, moved.trusted?.email, "the record this device keeps was not re-addressed")
            assertEquals(next, device.trusted().single().email)
            // back to the address the throwaway's file names
            val tokenBack = changeAndRead(moved, email, password, t)
            confirm(origin, moved, tokenBack)
            signIn(origin, t, email, password)
        }
    }

    private suspend fun changeAndRead(account: VeyqAccount, to: String, password: String, t: Throwaway.Accounts): String {
        val change = account.addressChange()
        change.request(to, password, key(t))
        if (change.current.refusal == me.raddatz.veyq.mobile.words.Msg.EMAIL_CHANGE_MAIL_UNAVAILABLE) {
            assumeTrue("the dev backend sends no mail (503): the confirm is not reached", false)
        }
        assertEquals(to, change.current.sentTo, "the request was refused: ${change.current.refusal}")
        val token = mailedToken(to)
        assumeTrue("no mail reached Mailpit for $to within 40 s: the confirm is not reached", token != null)
        return token!!
    }

    /** The web's `ConfirmEmailChangeView`: `confirmEmailChange` with the link's token, in the same account's session. */
    private suspend fun confirm(origin: String, account: VeyqAccount, token: String) {
        val r = account.respond { AccountEmailChangeApi(account.originOf(account.defaultTenant!!), account.http).confirmEmailChange(EmailChangeConfirmRequest(token)) }
        assertTrue(r.success, "the confirm was refused: ${r.status}")
    }

    /** The token of the newest mail Mailpit holds for [to] (the link's `#token=…` / `token=` part), polled 40 s. */
    private fun mailedToken(to: String): String? {
        val http = java.net.http.HttpClient.newHttpClient()
        val mailpit = System.getenv("VEYQ_MAILPIT") ?: "http://127.0.0.1:18025"
        repeat(40) {
            val search = http.send(
                HttpRequest.newBuilder(URI.create("$mailpit/api/v1/search?query=" + java.net.URLEncoder.encode("to:\"$to\"", "UTF-8"))).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            val id = Regex("\"ID\":\"([^\"]+)\"").find(search)?.groupValues?.get(1)
            if (id != null) {
                val text = http.send(HttpRequest.newBuilder(URI.create("$mailpit/api/v1/message/$id")).build(), HttpResponse.BodyHandlers.ofString()).body()
                Regex("token=([A-Za-z0-9_-]{16,})").find(text)?.groupValues?.get(1)?.let { return it }
            }
            Thread.sleep(1000)
        }
        return null
    }
}
