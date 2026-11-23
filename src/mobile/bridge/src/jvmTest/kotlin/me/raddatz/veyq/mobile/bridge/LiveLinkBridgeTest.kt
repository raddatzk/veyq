package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import org.junit.Assume.assumeTrue
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * Device linking through the bridge's calls against a running dev instance (`VEYQ_LIVE`, as the seeded principal from
 * `VEYQ_ENV_FILE` or `.run/.env`): two bridges over the real core, as two desktops drive them — the trusted one signed in
 * by a call, the new one at its sign-in, no session. The code crosses only as `Link.Code`'s PNG, which the test reads
 * back as a camera would and hands the other bridge's link scan as frames. Both directions; the trusted device's Settings
 * then lists the linked family under the name given. Every device a case signed in with — both trusted logins, both
 * linked devices — is taken off the account after it (`LiveRun`: its wraps, families and register entry). Without
 * `VEYQ_LIVE` each case reports as skipped, never as passed.
 */
class LiveLinkBridgeTest {
    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Test
    fun aNewDeviceBridgeScansTheTrustedBridgesCodeAndIsSignedIn() = link(addDevice = true)

    @Test
    fun theTrustedBridgeScansTheNewBridgesCodeAndTheSixDigitsSignItIn() = link(addDevice = false)

    private fun link(addDevice: Boolean) {
        val origin = System.getenv("VEYQ_LIVE")
        assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        runBlocking {
            val env = liveRun.env
            val t = bridge("live link trusted")
            val n = bridge("live link new")
            try {
                assertEquals(Answer.Done, t.ask(Call.SessionStart))
                assertEquals(Answer.Done, t.ask(Call.SessionSignIn(origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY")), 2.minutes))
                val connection = t.watching(Watched.Session).until(2.minutes) { (it as Update.Session).gate as? Gate.Signed }.connectionKey
                assertEquals(Answer.Done, n.ask(Call.SessionStart))
                val gate = n.watching(Watched.Session)
                gate.until { (it as Update.Session).gate as? Gate.SignIn }

                val label = "Live bridge link ${if (addDevice) "add" else "sign-in"} ${System.currentTimeMillis()}"
                if (addDevice) {
                    val shown = assertIs<Answer.LinkOpened>(t.ask(Call.LinkShowForNewDevice(connection))).link
                    val tw = t.watching(Watched.Link(shown))
                    tw.step(LinkStep.SHOWING)
                    val joining = assertIs<Answer.LinkOpened>(n.ask(Call.LinkScanFromTrusted(origin))).link
                    val nw = n.watching(Watched.Link(joining))
                    nw.step(LinkStep.SCANNING)
                    n.scanInto(joining, t.plane(shown))
                    val number = assertNotNull(nw.step(LinkStep.WAITING).number)
                    assertEquals(number, tw.step(LinkStep.CONFIRMING).number, "the two screens' numbers differ")
                    assertEquals(Answer.Done, t.ask(Call.LinkConfirm(shown, label)))
                    tw.step(LinkStep.DONE)
                    assertEquals(label, nw.step(LinkStep.DONE).label)
                } else {
                    val joining = assertIs<Answer.LinkOpened>(n.ask(Call.LinkShowForSignIn(origin))).link
                    val nw = n.watching(Watched.Link(joining))
                    nw.step(LinkStep.SHOWING)
                    val scanning = assertIs<Answer.LinkOpened>(t.ask(Call.LinkScanSignInCode(connection))).link
                    val tw = t.watching(Watched.Link(scanning))
                    tw.step(LinkStep.SCANNING)
                    t.scanInto(scanning, n.plane(joining))
                    val code = assertNotNull(tw.step(LinkStep.CODE).code)
                    assertEquals(Answer.Done, n.ask(Call.LinkEnterCode(joining, code)))
                    tw.step(LinkStep.CONFIRMING)
                    assertEquals(Answer.Done, t.ask(Call.LinkConfirm(scanning, label)))
                    tw.step(LinkStep.DONE)
                    nw.step(LinkStep.DONE)
                }
                val signed = gate.until(1.minutes) { (it as Update.Session).gate as? Gate.Signed }
                assertEquals(true, signed.offer?.linked, "no linked offer after the link")
                assertEquals(connection.substringAfter('#'), signed.connectionKey.substringAfter('#'), "the linked device is another account")
                // the linked device's tenant is open in its bridge, and the trusted device lists its family under the name
                assertIs<Answer.SettingsOpened>(n.ask(Call.SettingsOpen(signed.connectionKey)))
                val settings = assertIs<Answer.SettingsOpened>(t.ask(Call.SettingsOpen(connection))).settings
                t.watching(Watched.Settings(settings)).until(1.minutes) { u ->
                    (u as Update.Settings).shown?.families?.firstOrNull { it.label == label }
                }
                println("linked through the bridge (${if (addDevice) "add a new device" else "sign in with a QR code"}): $label")
            } finally {
                t.shutdown()
                n.shutdown()
            }
        }
    }

    private suspend fun Watching.step(step: LinkStep): LinkShown = until(2.minutes) { u ->
        val s = (u as Update.Link).shown
        assertTrue(s == null || s.step == step || s.step != LinkStep.ENDED, "the link ended: ${s?.refusal}, ${s?.hint}")
        s?.takeIf { it.step == step }
    }

    /** Link [link]'s code as `Link.Code` hands it (a PNG beside the JSON), read back into a camera's luminance plane. */
    private suspend fun Bridge<*, *>.plane(link: Long): Triple<Int, Int, ByteArray> {
        val (a, png) = askWithPayload(Call.LinkCode(link, 400))
        assertEquals(Answer.Bytes("image/png"), a)
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(assertNotNull(png)))
        return Triple(image.width, image.height, ByteArray(image.width * image.height) { i -> (image.getRGB(i % image.width, i / image.width) and 0xff).toByte() })
    }

    private suspend fun Bridge<*, *>.scanInto(link: Long, plane: Triple<Int, Int, ByteArray>) {
        val scan = assertIs<Answer.ScanOpened>(ask(Call.ScanOpen(ScanKind.LINK, link = link))).scan
        val w = watching(Watched.Scan(scan))
        assertEquals(Answer.Done, ask(Call.ScanCamera(scan, QrScan.Camera.RUNNING)))
        assertEquals(Answer.FrameTaken(true), askWith(Call.ScanFrame(scan, plane.first, plane.second, plane.first), plane.third))
        w.until(1.minutes) { (it as Update.Scan).shown?.takeIf { s -> s.state == ScanState.FOUND } }
    }

    /** A bridge over the real core, its device store a host this test plays (tracked: the device is taken off after). */
    private fun bridge(device: String): Bridge<*, *> {
        val kept = liveRun.track(ConcurrentHashMap<String, String>())
        lateinit var host: Host
        host = Host { token, json ->
            thread {
                val reply = when (val r = wire.decodeFromString(HostRequest.serializer(), json)) {
                    is HostRequest.StoreRead -> HostReply.Value(kept[r.name])
                    is HostRequest.StoreWrite -> HostReply.Done.also { kept[r.name] = r.value }
                    is HostRequest.StoreRemove -> HostReply.Done.also { kept.remove(r.name) }
                    else -> HostReply.Failed("this host draws nothing")
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply))
            }
        }
        return Bridge.live(BouncyCastlePrimitives, { block -> LiveHttp.client(block) }, Config(device), HostStore(host), { NoDeviceKeys }, JvmFiles)
    }
}
