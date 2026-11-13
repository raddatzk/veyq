package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.api.apis.PairingApi
import me.raddatz.veyq.mobile.api.apis.SessionApi
import me.raddatz.veyq.mobile.api.apis.TokenApi
import me.raddatz.veyq.mobile.api.models.PairingAnswerRequest
import me.raddatz.veyq.mobile.api.models.PairingCollectRequest
import me.raddatz.veyq.mobile.api.models.PairingDelegateRequest
import me.raddatz.veyq.mobile.api.models.PairingOpenRequest
import me.raddatz.veyq.mobile.api.models.PairingSealRequest
import me.raddatz.veyq.mobile.api.models.RefreshRequest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.Pairing
import me.raddatz.veyq.mobile.crypto.PairingGrant
import me.raddatz.veyq.mobile.crypto.b64
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Device linking (`cr-app-device-linking` phase 3) against a running dev instance (`VEYQ_LIVE`), as the seeded principal
 * (`VEYQ_ENV_FILE`, or `.run/.env`). Both sides are core clients: the trusted one a fresh login of the principal, the new
 * one a device with no session. Only the families these cases create are touched, each revoked at the end — never the
 * principal's other sessions. Without `VEYQ_LIVE` every case reports as skipped, never as passed.
 */
class LiveDeviceLinkTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }

    /**
     * D1's measurement: a delegated family's refresh token, spent once at `POST /api/token/refresh` with the token in the
     * body, rotates; the family it belongs to is the one `listSessionFamilies` marks `current` for its holder; its successor
     * rotates again; and the granting family still refreshes after it (the control).
     */
    @Test
    fun aHandedTokenSpentAtRefreshRotatesAndListsAsCurrent() {
        val origin = needs("VEYQ_LIVE")
        runTest(timeout = 2.minutes) {
            val env = env()
            val trusted = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device(), client)
            val plain = plainClient()
            var linkedFamily: String? = null
            try {
                val pairing = Pairing(p)
                val secret = pairing.mintSecret()
                val handle = b64(pairing.handle(secret))
                val key = pairing.channelKey(secret)
                val relay = PairingApi(origin, plain)
                assertTrue(relay.openPairingChannel(PairingOpenRequest(handle)).success, "open")
                val (priv, pub) = pairing.mintTransportKey()
                assertTrue(relay.answerPairingChannel(PairingAnswerRequest(handle, pairing.transportAnswer(key, pub))).success, "answer")

                val answer = assertNotNull(trusted.call { PairingApi(origin, trusted.http).collectPairingChannel(PairingCollectRequest(handle)) }.answer)
                val sealTo = pairing.readTransportAnswer(key, answer)
                val token = trusted.call { PairingApi(origin, trusted.http).delegatePairingSession(PairingDelegateRequest(handle)) }.refreshToken
                val envelope = pairing.sealGrant(key, sealTo, PairingGrant(trusted.email, trusted.masterKey.copyOf(), token, "D1 measurement"))
                assertTrue(trusted.respond { PairingApi(origin, trusted.http).sealPairingChannel(PairingSealRequest(handle, envelope)) }.success, "seal")

                val collected = relay.collectPairingChannel(PairingCollectRequest(handle))
                assertTrue(collected.success, "collect")
                val grant = pairing.openGrant(key, priv, assertNotNull(collected.body().envelope))

                val first = TokenApi(origin, plain).refreshSession(RefreshRequest(grant.refreshToken))
                assertEquals(200, first.status, "the handed token did not refresh in the body")
                val pair = first.body()
                val successor = assertNotNull(pair.refreshToken, "refresh with a body token answered no body token")
                assertNotEquals(grant.refreshToken, successor, "the handed token did not rotate")

                val mine = SessionApi(origin, bearer(pair.accessToken)).listSessionFamilies()
                assertTrue(mine.success)
                val current = mine.body().filter { it.current == true }
                assertEquals(1, current.size, "not exactly one family marked current for the spender")
                linkedFamily = current.single().id

                val again = TokenApi(origin, plain).refreshSession(RefreshRequest(successor))
                assertEquals(200, again.status, "the successor did not rotate")
                // a second presentation of the spent token is ADR 0004's clone case: refused, the family revoked with it
                assertEquals(401, TokenApi(origin, plain).refreshSession(RefreshRequest(grant.refreshToken)).status, "the spent handed token refreshed a second time")

                val listed = trusted.call { SessionApi(origin, trusted.http).listSessionFamilies() }
                val theirs = listed.firstOrNull { it.id == linkedFamily }
                println("D1: the linked family ${if (theirs == null) "is no longer listed (the clone case revoked it)" else "is listed, current=${theirs.current}"}")
                assertFalse(theirs?.current == true, "the linked family is current for the granting device")

                trusted.refresh()
                assertTrue(trusted.respond { SessionApi(origin, trusted.http).listSessionFamilies() }.success, "the granting family no longer reads after its refresh")
            } finally {
                linkedFamily?.let { f -> runCatching { trusted.respond { SessionApi(origin, trusted.http).revokeSessionFamily(f) } } }
                plain.close()
                leave(trusted)
                trusted.signOut()
            }
        }
    }

    /**
     * *Add a new device* end to end: the trusted login shows its code, a new device's sign-in (the app's `SessionModel`
     * over a fresh device) scans it off the drawn QR, the numbers match, the slide confirms — and the new device is signed
     * in, its family listed on the trusted device under the name given, rotating; then signed out from the trusted device,
     * whose own family still refreshes (the control).
     */
    @Test
    fun aNewDeviceScansTheTrustedCodeAndIsSignedIn() = linkBothWays(addDevice = true)

    /** *Sign in with a QR code* end to end: the new device shows its code, the trusted login scans it and shows six digits, typed once. */
    @Test
    fun theTrustedDeviceScansTheNewCodeAndTheSixDigitsSignItIn() = linkBothWays(addDevice = false)

    private fun linkBothWays(addDevice: Boolean) {
        val origin = needs("VEYQ_LIVE")
        runBlocking {
            val env = env()
            val trusted = VeyqAccount.signIn(p, origin, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device(), client)
            val trustedPage = page(trusted)
            val model = SessionModel.accounts(p, device(), client, Hidden({}, emptySet()), RecentUses({}, emptyList()))
            model.start()
            val label = "Live link ${if (addDevice) "add" else "sign-in"} ${System.currentTimeMillis()}"
            var linkedFamily: String? = null
            var links = emptyList<DeviceLink>()
            var linkedLeft = false
            try {
                trustedPage.refresh()
                assertTrue(trustedPage.current.addDevice, "the instance does not list core.device-linking")
                if (addDevice) {
                    val shown = DeviceLink.showForNewDevice(trusted)
                    val joining = assertNotNull(model.scanFromTrusted(origin))
                    links = listOf(shown, joining)
                    until(shown) { it.step == DeviceLink.Step.SHOWING }
                    until(joining) { it.step == DeviceLink.Step.SCANNING }
                    joining.scan!!.camera(QrScan.Camera.RUNNING)
                    joining.scan!!.offer(frameOf(shown.matrix()!!))
                    until(shown) { it.step == DeviceLink.Step.CONFIRMING }
                    assertEquals(joining.current.number, shown.current.number, "the two screens' numbers differ")
                    assertTrue(shown.confirm(label))
                    until(shown) { it.step == DeviceLink.Step.DONE }
                    until(joining) { it.step == DeviceLink.Step.DONE }
                } else {
                    val joining = assertNotNull(model.showForSignIn(origin))
                    val scanning = DeviceLink.scanSignInCode(trusted)
                    links = listOf(joining, scanning)
                    until(joining) { it.step == DeviceLink.Step.SHOWING }
                    until(scanning) { it.step == DeviceLink.Step.SCANNING }
                    scanning.scan!!.camera(QrScan.Camera.RUNNING)
                    scanning.scan!!.offer(frameOf(joining.matrix()!!))
                    until(scanning) { it.step == DeviceLink.Step.CODE }
                    assertTrue(joining.enterCode(scanning.current.code!!))
                    until(scanning) { it.step == DeviceLink.Step.CONFIRMING }
                    assertTrue(scanning.confirm(label))
                    until(scanning) { it.step == DeviceLink.Step.DONE }
                    until(joining) { it.step == DeviceLink.Step.DONE }
                }
                val signed = assertIs<SessionModel.Gate.Signed>(model.current)
                assertEquals(true, signed.offer?.linked, "no linked offer after the link")
                val linked = assertNotNull(model.signedTo).account
                assertNull(linked.trusted, "the linked device enrolled a trust without being asked")

                // the trusted device reads first: the name must come from the linked device's own write moment 1, not
                // from the reconcile the linked device's Settings would run
                trustedPage.refresh()
                val listed = assertNotNull(trustedPage.current.families!!.firstOrNull { it.label == label }, "the linked family is not listed under the name given")
                assertFalse(listed.current)

                val linkedPage = page(linked)
                linkedPage.refresh()
                linkedFamily = assertNotNull(linkedPage.current.device.familyId, "the server marked no family current for the linked device")
                assertEquals(listed.id, linkedFamily, "the family named on the trusted device is not the linked device's current one")
                assertEquals(label, linkedPage.current.device.label, "the linked device does not carry the name it was given")

                linked.refresh()
                linkedPage.refresh()
                assertNull(linkedPage.current.refusal, "the linked session does not read after its rotation")

                // the linked device leaves the register while its session still writes; the trusted one then ends it
                leave(linked)
                linkedLeft = true
                assertNull(trustedPage.signOut(linkedFamily), "signing the linked family out signed the trusted one out")
                linkedFamily = null
                linkedPage.refresh()
                assertEquals(Msg.ACCOUNT_SETTINGS_LOAD_FAILED, linkedPage.current.refusal, "the revoked linked session still reads")

                trusted.refresh()
                trustedPage.refresh()
                assertNull(trustedPage.current.refusal, "the granting family no longer reads after its refresh")
            } finally {
                links.forEach { it.close() }
                if (!linkedLeft) model.signedTo?.account?.let { leave(it) }
                linkedFamily?.let { f -> runCatching { trusted.respond { SessionApi(origin, trusted.http).revokeSessionFamily(f) } } }
                model.signedTo?.account?.let { runCatching { model.signOut(it) } }
                leave(trusted)
                trusted.signOut()
            }
        }
    }

    /** This run's [account] takes its device's own entry out of the register again (its family and wrap rows with it). */
    private suspend fun leave(account: VeyqAccount) {
        runCatching { account.register().leave(account.thisDevice.id) }.onFailure { println("the register entry of ${account.thisDevice.label} stayed: $it") }
    }

    /** A throwaway device of this test, named so a stray register entry is recognisable as this test's. */
    private fun device() = ThisDevice(MemoryDeviceStore(), SoftwareDeviceKeys(p), "LiveDeviceLinkTest device", p)

    /** Waits (real time, bounded) until [link]'s state meets [done]; a link that ended on the way fails the case. */
    private suspend fun until(link: DeviceLink, done: (DeviceLink.Shown) -> Boolean) {
        val reached = withTimeoutOrNull(60_000) { link.state.first { done(it) || it.step == DeviceLink.Step.ENDED } }
        assertNotNull(reached, "${link.role} stayed at ${link.current}")
        assertTrue(done(reached), "${link.role} ended: ${link.current}")
    }

    private fun page(account: VeyqAccount) =
        AccountSettings(AccountSettings.Over(account), Alone(account), account.email) { System.currentTimeMillis() }

    /** The page's trust and sign-out without a [SessionModel]. */
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

    private fun plainClient(): HttpClient = client {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun bearer(token: String): HttpClient = client {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(io.ktor.client.plugins.DefaultRequest) { header("Authorization", "Bearer $token") }
    }

    /** The environment variable [name]; where it is not set the case is skipped (reported as skipped, never passed). */
    private fun needs(name: String): String {
        val value = System.getenv(name)
        assumeTrue("$name is not set: a live case", value != null)
        return value!!
    }

    private fun env(): Map<String, String> =
        (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
}
