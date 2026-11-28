package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.sharing.ReceivedLink
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
 * Finishing a recovery in the app (uc.account.recover-access steps 1–11) against a running dev instance, as a throwaway
 * ([Throwaway], never the seeded accounts): the link asked for, read from Mailpit, opened as the finish, the Secret Key
 * typed, a new password chosen; the old password then refused, the new one signing in to the same spaces, and the
 * password changed back so the throwaway's `--erase` still signs in. A dev backend that sends no mail SKIPS the case after
 * the ask, saying so — never passes it.
 */
class LiveRecoveryTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }
    private val runs = mutableListOf<LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.forEach { it.removeAll() }

    @Test
    fun aRecoveryFinishedInTheAppSignsInWithTheNewPassword() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        val email = t.email()
        val original = t.env.getValue("VEYQ_PASSWORD")
        val next = "live-recovered-${System.currentTimeMillis()}"
        runTest(timeout = 6.minutes) {
            val run = LiveRun("VEYQ", t.file).also { runs += it }
            val before = VeyqAccount.signIn(p, origin, email, original, t.env.getValue("VEYQ_SECRET_KEY"), run.device(), client)
            val tenant = before.tenants.first { it.domain == t.env.getValue("VEYQ_TENANT_DOMAIN") }
            val spaces = before.open(tenant).spaces().map { it.scopeId }.toSet()
            // the ask's host names the tenant whose relay mails it (ADR 0054 §4): the throwaway's, which sends via Mailpit
            val live = URI.create(origin)
            val tenantOrigin = "${live.scheme}://${tenant.domain}${if (live.port > 0) ":${live.port}" else ""}"
            val since = System.currentTimeMillis()
            assertEquals(SessionModel.RecoveryAsked.SENT, VeyqAccount.requestRecovery(tenantOrigin, email, client), "the ask was not taken")
            val token = mailedToken(email, since)
            assumeTrue("no recovery mail reached Mailpit for the throwaway within 40 s: the finish is not reached", token != null)
            val link = assertNotNull(ReceivedLink.recoveryOf("$tenantOrigin/recover#$token"))
            val finish = RecoveryFinish.of(p, client, link)
            try {
                finish.load()
                assertEquals(RecoveryPhase.KEY, finish.current.phase, "the link did not release: ${finish.current.refusal}")
                assertEquals(email.lowercase(), finish.current.email.lowercase())
                finish.unlock(p.randomBytes(32))
                assertEquals(me.raddatz.veyq.mobile.words.Msg.RECOVERY_KEY_FAILED, finish.current.refusal, "a wrong key opened the wrap")
                finish.unlock(assertNotNull(SecretKeyText.read(p, t.env.getValue("VEYQ_SECRET_KEY"))))
                assertEquals(RecoveryPhase.PASSWORD, finish.current.phase, "the Secret Key did not open the wrap: ${finish.current.refusal}")
                finish.finish(next, next)
                assertEquals(RecoveryPhase.DONE, finish.current.phase, "the complete was refused: ${finish.current.refusal}")
            } finally {
                finish.close()
            }
            var changedBack = false
            try {
                val refused = runCatching { VeyqAccount.signIn(p, origin, email, original, t.env.getValue("VEYQ_SECRET_KEY"), run.device(), client) }.exceptionOrNull()
                assertEquals(SignInFailure.Reason.CREDENTIALS, (refused as? SignInFailure)?.reason, "the old password still signs in")
                val again = VeyqAccount.signIn(p, origin, email, next, t.env.getValue("VEYQ_SECRET_KEY"), run.device(), client)
                val after = again.open(again.tenants.first { it.domain == tenant.domain }).spaces().map { it.scopeId }.toSet()
                assertTrue(spaces.isNotEmpty() && after == spaces, "the recovered Master Key does not open the spaces: $spaces → $after")
                // the link is spent: a second release is refused
                val spent = RecoveryFinish.of(p, client, link)
                spent.load()
                assertEquals(RecoveryPhase.SPENT, spent.current.phase, "a spent link released again")
                spent.close()
                val back = again.passwordChange()
                back.run(next, original, original, assertNotNull(SecretKeyText.read(p, t.env.getValue("VEYQ_SECRET_KEY"))))
                assertTrue(back.current.done, "the password was not changed back: ${back.current.refusal}")
                changedBack = true
            } finally {
                if (!changedBack) println("[live] the throwaway's password is now the recovered one; erase it by hand")
            }
        }
    }

    /** The token of the newest recovery mail Mailpit holds for [to] since [since] (the link's `/recover#<token>`), polled 40 s. */
    private fun mailedToken(to: String, since: Long): String? {
        val http = java.net.http.HttpClient.newHttpClient()
        val mailpit = System.getenv("VEYQ_MAILPIT") ?: "http://127.0.0.1:18025"
        repeat(40) {
            val search = http.send(
                HttpRequest.newBuilder(URI.create("$mailpit/api/v1/search?query=" + java.net.URLEncoder.encode("to:\"$to\"", "UTF-8"))).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            for (id in Regex("\"ID\":\"([^\"]+)\"").findAll(search).map { it.groupValues[1] }.take(5)) {
                val text = http.send(HttpRequest.newBuilder(URI.create("$mailpit/api/v1/message/$id")).build(), HttpResponse.BodyHandlers.ofString()).body()
                val created = Regex("\"Date\":\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                if (created != null && created < since - 5_000) continue
                Regex("/recover#([A-Za-z0-9_-]{43})").find(text)?.groupValues?.get(1)?.let { return it }
            }
            Thread.sleep(1000)
        }
        return null
    }
}
