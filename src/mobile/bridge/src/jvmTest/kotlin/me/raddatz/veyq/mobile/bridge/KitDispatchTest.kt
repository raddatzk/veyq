package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.account.KitImport
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.qr.QrEncoder
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * An Emergency Kit at the desktops' sign-in through the bridge (ADR 0131 decision 3, extended inward): the kit file by its
 * path ([Call.SessionSignInKit]), read by the core, its pages drawn by the host the test plays through real [Host]
 * requests and replies (the pixels as the reply's payload, [HostPictures]); and the kit's code scanned
 * ([ScanKind.KIT]). Over the web's three fixtures (a fake account: `alex.beispiel@example.org`, the Secret Key of the bytes
 * 0..31, `https://familie.example.org`). The kit's Secret Key never crosses outward: the core holds it and hands it to the
 * door's sign-in.
 */
class KitDispatchTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    /** What reached the door: a typed Secret Key as typed, a held kit's key as its bytes. */
    private val typedKeys = mutableListOf<String>()
    private val heldKeys = mutableListOf<List<Byte>>()

    private inner class Door : SessionModel.Door<Acc, String> {
        val account = Acc("familie.example.org#u1", listOf(TenantRef("t1", "Familie", "familie.example.org")))
        override val primitives = BouncyCastlePrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc {
            typedKeys += secretKey
            throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
        }
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc {
            heldKeys += secretKey.toList()
            if (password != "right" || server != "https://familie.example.org") throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
            return account
        }
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = error("no trusted device")
        override suspend fun resume(record: TrustedDevice) = error("no trusted device")
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = null
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = emptySet<StaySignedIn>()
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
    }

    /**
     * The host the tests play, over a real [Host]: each request it gets recorded as JSON reads it back, a PDF opening
     * with [pages] pages, each page and picture answered by [draw] (a reply and the payload beside it).
     */
    private class PlayedHost(
        private val pages: Int?,
        private val draw: (HostRequest) -> Pair<HostReply, ByteArray?> = { blank() },
    ) {
        val asked: MutableList<HostRequest> = java.util.Collections.synchronizedList(ArrayList())
        lateinit var host: Host

        init {
            host = Host { token, json ->
                val request = wire.decodeFromString(HostRequest.serializer(), json)
                asked += request
                val (reply, payload) = when (request) {
                    is HostRequest.PdfPages -> (pages?.let { HostReply.Value(it.toString()) } ?: HostReply.Failed("no PDF")) to null
                    is HostRequest.PdfPage, is HostRequest.PictureLuma -> draw(request)
                    else -> HostReply.Failed("no store here") to null
                }
                host.reply(token, wire.encodeToString(HostReply.serializer(), reply), payload)
            }
        }
    }

    /** The kit files read through [JvmFiles], each read's cap recorded. */
    private val caps = java.util.Collections.synchronizedList(ArrayList<Int>())
    private val files = object : DeviceFiles by JvmFiles {
        override suspend fun read(path: String, most: Int): ByteArray = JvmFiles.read(path, most.also { caps += it })
    }

    private fun kitBridge(host: PlayedHost?, decode: suspend (QrFrame) -> String? = { null }) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(
            SessionModel(Door(), hidden, RecentUses({}, emptyList())), hidden, files, decode = decode,
            pictures = { path -> host?.let { HostPictures(it.host, path) } ?: NoPictures },
        ) { null }
    }

    private val fixtures = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/account/emergencyKit/__fixtures__")
    private fun fixture(name: String) = File(fixtures, name).path

    /** The fixtures' kit: the token `kit-pdfkit-saved.pdf` carries raw (all three carry the same kit). */
    private val token: String = Regex("veyqkit:[A-Za-z0-9_-]+").find(String(File(fixtures, "kit-pdfkit-saved.pdf").readBytes(), Charsets.ISO_8859_1))!!.value
    private val fixtureKit = SessionModel.KitShown("alex.beispiel@example.org", "https://familie.example.org", KitImport.ServerFrom.TOKEN)
    private val fixtureKey = (0 until 32).map { it.toByte() }

    /** Everything a session watch carried outward, as the host reads it. */
    private fun Bridge<*, *>.outward(): MutableList<String> = mutableListOf<String>().also { seen ->
        watch(wire.encodeToString(Watched.serializer(), Watched.Session)) { synchronized(seen) { seen += it } }
    }

    private fun MutableList<String>.carriesNothingOfTheKit() = synchronized(this) {
        assertTrue(isNotEmpty())
        // neither the token nor any group of the key's text form (`SecretKeyText`) ever crosses
        val keyText = me.raddatz.veyq.mobile.crypto.SecretKeyText.format(BouncyCastlePrimitives, ByteArray(32) { it.toByte() })
        val groups = keyText.split('-').filter { it.length >= 5 }
        assertTrue(none { token.substringAfter(':').take(24) in it || groups.any { g -> g in it } }, "the kit crossed outward: $this")
    }

    private suspend fun Bridge<*, *>.signInShown() {
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertTrue(gateNow() is Gate.SignIn)
    }

    @Test
    fun aKitInTheFilesBytesFillsTheFormWithNoHostRequestAndItsHeldKeySignsIn() = runBlocking {
        val host = PlayedHost(pages = 1)
        val b = kitBridge(host)
        val seen = b.outward()
        b.signInShown()
        assertEquals(Answer.Done, b.ask(Call.SessionSignInKit(fixture("kit-pdfkit-saved.pdf"), "other.example")))
        assertEquals(emptyList(), host.asked.toList(), "a kit in the file's bytes asked the host to draw")
        assertEquals(listOf(KitImport.FILE_CAP), caps.toList(), "the file was not read under the kit's cap")
        val form = b.gateNow() as Gate.SignIn
        assertEquals(fixtureKit.copy(typedServer = "https://other.example"), form.kit, "the other server not said")
        assertEquals("alex.beispiel@example.org", form.email)
        assertEquals("https://familie.example.org", form.server)

        // the sign-in with no Secret Key typed takes the key the core holds
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn("https://familie.example.org", "alex.beispiel@example.org", "right", "")))
        assertEquals(listOf(fixtureKey), heldKeys.toList())
        assertEquals(emptyList(), typedKeys.toList())
        kotlinx.coroutines.delay(50.milliseconds)
        seen.carriesNothingOfTheKit()
        b.shutdown()
    }

    @Test
    fun aPrintedKitIsDrawnPageByPageByTheHostItsPixelsBesideTheReply() = runBlocking {
        // page 1 a cover sheet with no code, page 2 the kit's code: drawn at the web's width, then the fallback's
        val host = PlayedHost(pages = 5) { r -> if (r is HostRequest.PdfPage && r.page == 2) code(token) else blank() }
        val b = kitBridge(host)
        val seen = b.outward()
        b.signInShown()
        val path = fixture("kit-printed.pdf")
        assertEquals(Answer.Done, b.ask(Call.SessionSignInKit(path, "")))
        assertEquals(
            listOf(HostRequest.PdfPages(path), HostRequest.PdfPage(path, 1, 2400), HostRequest.PdfPage(path, 1, 2000), HostRequest.PdfPage(path, 2, 2400)),
            host.asked.toList(),
        )
        assertEquals(fixtureKit, (b.gateNow() as Gate.SignIn).kit)
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn("https://familie.example.org", "alex.beispiel@example.org", "right", "")))
        assertEquals(listOf(fixtureKey), heldKeys.toList())
        seen.carriesNothingOfTheKit()
        b.shutdown()
    }

    @Test
    fun aFileWithNoKitIsRefusedAndSaidAndADrawingShorterThanItsSizeIsNone() = runBlocking {
        // every page "drawn" with fewer bytes than its size says: no drawing, so no code is read from it
        val host = PlayedHost(pages = 1) { r ->
            if (r is HostRequest.PdfPage) code(token).let { (reply, bytes) -> reply to bytes!!.copyOf(bytes.size - 1) } else blank()
        }
        val b = kitBridge(host)
        b.signInShown()
        val path = fixture("kit-photo.pdf")
        assertEquals(Answer.Refused(Answer.Why.NO_KIT), b.ask(Call.SessionSignInKit(path, "")))
        assertEquals(listOf(HostRequest.PdfPages(path), HostRequest.PdfPage(path, 1, 2400), HostRequest.PdfPage(path, 1, 2000)), host.asked.toList())
        val form = b.gateNow() as Gate.SignIn
        assertNull(form.kit)
        assertEquals(Msg.SIGNIN_KIT_UNREADABLE, form.error)
        b.shutdown()
    }

    @Test
    fun aPathThatDoesNotOpenAndAFilePastTheCapAreReadAsNoKitAndAskTheHostNothing() = runBlocking {
        val host = PlayedHost(pages = 1) { code(token) }
        val b = kitBridge(host)
        b.signInShown()
        assertEquals(Answer.Refused(Answer.Why.NO_KIT), b.ask(Call.SessionSignInKit(File(fixtures, "no-such-kit.pdf").path, "")))
        assertEquals(Msg.SIGNIN_KIT_UNREADABLE, (b.gateNow() as Gate.SignIn).error)

        val big = File.createTempFile("kit-past-the-cap", ".pdf", File(System.getProperty("java.io.tmpdir")))
        try {
            java.io.RandomAccessFile(big, "rw").use { it.setLength(KitImport.FILE_CAP + 1L) }
            assertEquals(Answer.Refused(Answer.Why.NO_KIT), b.ask(Call.SessionSignInKit(big.path, "")))
        } finally {
            big.delete()
        }
        assertEquals(emptyList(), host.asked.toList(), "the host was asked to draw a file the core did not read")
        assertEquals(listOf(KitImport.FILE_CAP, KitImport.FILE_CAP), caps.toList())
        b.shutdown()
    }

    @Test
    fun aPictureFileIsDrawnByTheHostAndTheClearEndsTheHeldKey() = runBlocking {
        val host = PlayedHost(pages = null) { r -> if (r is HostRequest.PictureLuma) code(token) else blank() }
        val b = kitBridge(host)
        b.signInShown()
        val picture = File.createTempFile("kit-photo", ".jpg", File(System.getProperty("java.io.tmpdir")))
        try {
            picture.writeBytes(byteArrayOf(-1, -40, -1, -32) + ByteArray(64)) // no kit in its bytes: only its picture
            assertEquals(Answer.Done, b.ask(Call.SessionSignInKit(picture.path, "familie.example.org")))
            assertEquals(listOf(HostRequest.PictureLuma(picture.path, 2400)), host.asked.toList())
        } finally {
            picture.delete()
        }
        // the same server typed as the kit's: no notice
        assertEquals(fixtureKit, (b.gateNow() as Gate.SignIn).kit)

        assertEquals(Answer.Done, b.ask(Call.SessionSignInKitClear))
        assertNull((b.gateNow() as Gate.SignIn).kit)
        // with the kit cleared, an empty key is a missing one: nothing held reaches the door
        b.ask(Call.SessionSignIn("https://familie.example.org", "alex.beispiel@example.org", "right", ""))
        assertEquals(emptyList(), heldKeys.toList())
        b.shutdown()
    }

    @Test
    fun aKitScanSkipsForeignCodesAndAnOlderKitThenFillsTheFormNeverSayingItsText() = runBlocking {
        val texts = java.util.concurrent.LinkedBlockingQueue(listOf("https://example.org/not-ours", olderKit(), token))
        val b = kitBridge(null) { texts.poll() }
        val seen = b.outward()
        b.signInShown()
        val updates = mutableListOf<String>()
        val scan = (b.ask(Call.ScanOpen(ScanKind.KIT, "other.example")) as Answer.ScanOpened).scan
        b.watch(wire.encodeToString(Watched.serializer(), Watched.Scan(scan))) { synchronized(updates) { updates += it } }
        assertEquals(Answer.Done, b.ask(Call.ScanCamera(scan, me.raddatz.veyq.mobile.qr.QrScan.Camera.RUNNING)))
        val w = b.watching(Watched.Scan(scan))
        while (texts.isNotEmpty()) {
            b.askWith(Call.ScanFrame(scan, 8, 8, 8), ByteArray(64))
            kotlinx.coroutines.delay(5.milliseconds)
        }
        assertEquals("veyqkit", w.until { u -> (u as Update.Scan).shown?.takeIf { it.state == ScanState.FOUND } }.found)
        val form = b.until { (it as? Gate.SignIn)?.kit }
        assertEquals(fixtureKit.copy(typedServer = "https://other.example"), form)
        assertEquals(Answer.Done, b.ask(Call.ScanClose(scan)))
        assertEquals(Answer.Done, b.ask(Call.SessionSignIn("https://familie.example.org", "alex.beispiel@example.org", "right", "")))
        assertEquals(listOf(fixtureKey), heldKeys.toList())
        synchronized(updates) { assertTrue(updates.none { "veyqkit:" in it }, updates.toString()) }
        seen.carriesNothingOfTheKit()
        b.shutdown()
    }

    /** The session's gate until [pick] takes one. */
    private suspend fun <T : Any> Bridge<*, *>.until(pick: (Gate) -> T?): T =
        watching(Watched.Session).let { w -> w.until { pick((it as Update.Session).gate) }.also { unwatch(w.id) } }

    /** A kit of version 2 (the web's parser refuses it, so a scan skips it): the fixture's kit with `v` 2. */
    private fun olderKit(): String {
        val json = String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(token.substringAfter(':')))
        assertTrue("\"v\":3" in json)
        return "veyqkit:" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.replace("\"v\":3", "\"v\":2").encodeToByteArray())
    }

    private companion object {
        /** A page with no code: white. */
        fun blank(): Pair<HostReply, ByteArray?> = HostReply.Pixels(64, 90) to ByteArray(64 * 90) { -1 }

        /** [text]'s code drawn at 3 px a module, as a host draws a page that shows it. */
        fun code(text: String): Pair<HostReply, ByteArray?> {
            val code = QrEncoder.encode(text)
            val side = code.side * 3
            return HostReply.Pixels(side, side) to ByteArray(side * side) { if (code.dark(it % side / 3, it / side / 3)) 0 else -1 }
        }
    }
}
