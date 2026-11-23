package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.account.KitImport
import me.raddatz.veyq.mobile.account.KitImportTest
import me.raddatz.veyq.mobile.account.KitPictures
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.qr.QrFrame
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.test.AfterTest

/**
 * Signs in to a running dev instance as the seeded principal (`.run/.env`, or `VEYQ_ENV_FILE`'s in a worktree, which has
 * none; `./gradlew backend:seed`) and opens every tenant of the account. Runs only when `VEYQ_LIVE` names an origin of
 * the server (e.g. `http://veyq.localhost:8080`); without it each case reports as skipped, never as passed.
 */
class LiveSignInTest {
    /** Every device this case signs in with, taken off the account again after it, whatever it came to. */
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> LiveHttp.client(block) }

    @Test
    fun theSeededPrincipalSignsInUnlocksAndReadsEveryTenant() {
        val origin = live()
        runTest(timeout = 2.minutes) {
            val env = env()
            val account = VeyqAccount.signIn(
                p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), liveRun.device(), client,
            )
            println("tenants: " + account.tenants.joinToString { "${it.name}@${it.domain}" })
            for (tenant in account.tenants) {
                val t = account.open(tenant)
                println("[${tenant.name}] spaces: " + t.spaces().joinToString { "${it.kind}:${it.name}" })
                println("[${tenant.name}] nodes: " + t.nodes().joinToString { "${it.kind}:${it.name}" })
                println("[${tenant.name}] favorites: " + t.favorites().joinToString { it.name })
                assertTrue(t.spaces().any { it.kind == "PERSONAL" }, "the personal scope of ${tenant.name} did not open")
            }
            account.signOut()
        }
    }

    /**
     * The kit's sign-in (`SessionModel.readKit`): a kit PDF built here in memory from the principal's credentials — its
     * token in the document information's `Keywords` only, as UTF-16 hex, so the read takes step 2 — fills the form, and
     * a sign-in with an empty Secret Key reaches the shell over the account's tenants. The device trust the sign-in
     * enrols is forgotten again (its wrap deleted) and the session signed out. Nothing of the kit is printed or written.
     */
    @Test
    fun aKitPdfSignsThePrincipalInWithAnEmptySecretKey() {
        val origin = live()
        runTest(timeout = 2.minutes) {
            val env = env()
            val email = env.getValue("VEYQ_EMAIL")
            val file = kitPdf(email, env.getValue("VEYQ_SECRET_KEY"), origin)
            val model = SessionModel.accounts(p, liveRun.device(), client, Hidden({}, emptySet()), RecentUses({}, emptyList()))
            model.start()
            assertTrue(model.readKit(file, NothingDrawn), "the kit was not read")
            val form = assertIs<SessionModel.Gate.SignIn>(model.current)
            assertEquals(SessionModel.KitShown(email, origin, KitImport.ServerFrom.TOKEN), form.kit)
            assertEquals(origin, form.server)
            assertEquals(email, form.email)

            val opened = assertNotNull(model.signIn(form.server!!, form.email!!, env.getValue("VEYQ_PASSWORD"), ""), "the kit's sign-in failed: ${model.current}")
            try {
                assertIs<SessionModel.Gate.Signed>(model.current)
                assertTrue(opened.account.tenants.isNotEmpty(), "the shell has no tenant")
                assertTrue(opened.session.spaces().any { it.kind == "PERSONAL" }, "the personal scope did not open")
            } finally {
                model.forgetDevice(opened.account)
                model.signOut(opened.account)
            }
        }
    }

    /** A kit PDF of the account: `{v: 3, email, sk, srv}` as the web writes it, the token in `Keywords` as UTF-16BE hex. */
    private fun kitPdf(email: String, secretKey: String, server: String): ByteArray = Companion.kitPdf(kitToken(email, secretKey, server))

    companion object {
        /** The kit's token, `veyqkit:` and `{v: 3, email, sk, srv}` as the web writes it (base64url, unpadded). */
        internal fun kitToken(email: String, secretKey: String, server: String): String {
            val p = BouncyCastlePrimitives
            val key = assertNotNull(SecretKeyText.read(p, secretKey), "VEYQ_SECRET_KEY does not read as a Secret Key")
            val json = buildJsonObject {
                put("v", 3)
                put("email", email)
                put("sk", SecretKeyText.format(p, key))
                put("srv", server)
            }.toString().encodeToByteArray()
            key.fill(0)
            val token = "veyqkit:" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json)
            json.fill(0)
            return token
        }

        /** A PDF that carries [token] in its document information's `Keywords` only, as UTF-16BE hex (no page draws it). */
        internal fun kitPdf(token: String): ByteArray {
            val utf16 = "feff" + hex(token.flatMap { listOf((it.code shr 8).toByte(), it.code.toByte()) }.toByteArray())
            return KitImportTest.pdf(KitImportTest.info("<$utf16>"))
        }
    }

    /** A platform that draws nothing: the kit is in the `Keywords`, no page is needed. */
    private object NothingDrawn : KitPictures {
        override suspend fun pdfPages(): Int? = null
        override suspend fun pdfPage(page: Int, width: Int): QrFrame? = null
        override suspend fun picture(longSide: Int): QrFrame? = null
    }

    /** `VEYQ_LIVE`'s origin; the case is skipped (an assumption, reported as skipped) where it is not set. */
    private fun live(): String {
        val origin = System.getenv("VEYQ_LIVE")
        assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        return origin!!
    }

    /** `.run/.env` of this checkout, or `VEYQ_ENV_FILE`'s (a worktree has none). */
    private fun env(): Map<String, String> =
        (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
}
