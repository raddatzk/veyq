package me.raddatz.veyq.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.PairingGrant
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.session.DeviceLink
import me.raddatz.veyq.mobile.session.PlayedServer
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Device linking through the bridge (`cr-app-device-linking` D6): the `Link.*` calls, [Watched.Link], and a link's scan
 * through `Scan.*` of kind [ScanKind.LINK], over the core's real `DeviceLink` and a played relay (core's [PlayedServer],
 * serialized here: the bridge's calls run on many threads). Two bridges link with each other as the host drives them —
 * a trusted one signed in by password, a new one at its sign-in —, the code crossing only as a PNG the test reads back
 * through the core's decoder as a camera would. Nothing the bridges send carries the code's text, the channel's handle or
 * the handed token. The polls are the core's (2 s, real time), so a case runs a few seconds.
 */
class LinkDispatchTest {
    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    private val server = PlayedServer()
    private val lock = Mutex()

    /** The played server's side, one call at a time. */
    private inner class Locked(val side: PlayedServer.Side) : DeviceLink.Relay {
        override suspend fun capable() = lock.withLock { side.capable() }
        override suspend fun open(handle: String) = lock.withLock { side.open(handle) }
        override suspend fun answer(handle: String, answer: String) = lock.withLock { side.answer(handle, answer) }
        override suspend fun collect(handle: String) = lock.withLock { side.collect(handle) }
        override fun close() = side.close()
    }

    /** The trusted account's half over the played server, one call at a time; [granted] each one the bridge made. */
    private inner class LockedAccount(val account: PlayedServer.Account = server.Account()) : DeviceLink.Granter {
        override val relay = Locked(account.relay)
        override val email = account.email
        override fun masterKey() = account.masterKey()
        override suspend fun marks() = account.marks()
        override suspend fun delegate(handle: String) = lock.withLock { account.delegate(handle) }
        override suspend fun seal(handle: String, envelope: String) = lock.withLock { account.seal(handle, envelope) }
    }

    private val granted = java.util.Collections.synchronizedList(ArrayList<LockedAccount>())

    /** The grants that reached a new device's door, and what holds its sign-in's tail (completed: it runs on). */
    private val received = java.util.Collections.synchronizedList(ArrayList<PairingGrant>())
    private val newRelays = java.util.Collections.synchronizedList(ArrayList<String>())
    private var tailHeld = CompletableDeferred(Unit)

    private inner class Door(private val account: Acc) : SessionModel.Door<Acc, String> {
        override val primitives = BouncyCastlePrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String): Acc {
            if (password != "right") throw SignInFailure(SignInFailure.Reason.CREDENTIALS)
            return account
        }
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("no kit")
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
        override fun relay(server: String): DeviceLink.Relay = Locked(this@LinkDispatchTest.server.Side("new")).also { newRelays += server }
        override suspend fun linked(server: String, grant: PairingGrant): Acc {
            received += PairingGrant(grant.email, grant.masterKey.copyOf(), grant.refreshToken, grant.label, grant.marks)
            tailHeld.await()
            return account
        }
    }

    private val trustedAccount = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example"), TenantRef("ta2", "A2", "a2.example")))
    private val newAccount = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example")))
    private val bridges = mutableListOf<Bridge<*, *>>()

    private fun bridge(account: Acc) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(
            SessionModel(Door(account), hidden, RecentUses({}, emptyList())), hidden,
            trustedLink = { _, role, decode ->
                val g = LockedAccount().also { granted += it }
                when (role) {
                    DeviceLink.Role.SHOW_FOR_NEW_DEVICE -> DeviceLink.showForNewDevice(BouncyCastlePrimitives, g)
                    DeviceLink.Role.SCAN_SIGN_IN_CODE -> DeviceLink.scanSignInCode(BouncyCastlePrimitives, g, decode)
                    else -> null
                }
            },
        ) { _ -> Tenant(shell = null, pot = null, me = "u1", pictures = MutableStateFlow(emptyMap()), loadPictures = {}) }
    }.also { bridges += it }

    @AfterTest
    fun shutDown() = bridges.forEach { it.shutdown() }

    /** Everything a bridge sent outward on the watches the test holds, as the host reads it. */
    private val outward = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun Bridge<*, *>.watchingAll(w: Watched): Watching {
        val ch = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val id = watch(wire.encodeToString(Watched.serializer(), w)) { outward += it; ch.trySend(it) }
        return Watching(id, ch)
    }

    private suspend fun Bridge<*, *>.trusted(): Bridge<*, *> = apply {
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
    }

    private suspend fun Bridge<*, *>.atSignIn(): Bridge<*, *> = apply {
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertIs<Gate.SignIn>(gateNow())
    }

    private suspend fun Watching.step(step: LinkStep): LinkShown = until(30.seconds) { u ->
        val s = (u as Update.Link).shown
        assertTrue(s == null || s.step == step || s.step != LinkStep.ENDED, "the link ended: ${s?.refusal}")
        s?.takeIf { it.step == step }
    }

    /** Link [link]'s code as the host gets it ([Call.LinkCode]: a PNG beside the JSON), and the plane a camera would hand on. */
    private suspend fun Bridge<*, *>.code(link: Long): Pair<String, Triple<Int, Int, ByteArray>> {
        val (a, png) = askWithPayload(Call.LinkCode(link, 300))
        assertEquals(Answer.Bytes("image/png"), a)
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(assertNotNull(png)))
        val plane = ByteArray(image.width * image.height) { i -> (image.getRGB(i % image.width, i / image.width) and 0xff).toByte() }
        val text = assertNotNull(me.raddatz.veyq.mobile.qr.QrDecoder.decode(me.raddatz.veyq.mobile.qr.QrFrame(image.width, image.height, image.width, plane)))
        return text to Triple(image.width, image.height, plane)
    }

    /** [plane] shown to link [link]'s camera through `Scan.*` of kind LINK, until its scan settles on [scheme]. */
    private suspend fun Bridge<*, *>.scanInto(link: Long, plane: Triple<Int, Int, ByteArray>, scheme: String) {
        val scan = assertIs<Answer.ScanOpened>(ask(Call.ScanOpen(ScanKind.LINK, link = link))).scan
        val w = watchingAll(Watched.Scan(scan))
        assertEquals(Answer.Done, ask(Call.ScanCamera(scan, QrScan.Camera.RUNNING)))
        assertEquals(Answer.FrameTaken(true), askWith(Call.ScanFrame(scan, plane.first, plane.second, plane.first), plane.third))
        assertEquals(scheme, w.until { (it as Update.Scan).shown?.takeIf { s -> s.state == ScanState.FOUND } }.found)
    }

    /** Nothing sent outward carries the code's text (past its marker), a channel's handle or a handed token. */
    private fun carriesNothingOfTheCode(texts: List<String>) = synchronized(outward) {
        assertTrue(outward.isNotEmpty())
        val secrets = texts.map { it.substringAfter(':') } + server.channels.keys + listOf("token-1", "token-2")
        val leaked = outward.filter { o -> secrets.any { it in o } }
        assertEquals(emptyList(), leaked, "a link sent its code, handle or token outward")
    }

    /** *Add a new device* up to the slide: the trusted bridge shows, the new bridge scans. The links' ids and watches. */
    private suspend fun addDeviceToTheSlide(t: Bridge<*, *>, n: Bridge<*, *>): AddDevice {
        val shown = assertIs<Answer.LinkOpened>(t.ask(Call.LinkShowForNewDevice("a.example#u1"))).link
        val tw = t.watchingAll(Watched.Link(shown))
        assertEquals(LinkRole.SHOW_FOR_NEW_DEVICE, tw.step(LinkStep.SHOWING).role)
        val (text, plane) = t.code(shown)
        assertTrue(text.startsWith("veyqpair:"))

        val joining = assertIs<Answer.LinkOpened>(n.ask(Call.LinkScanFromTrusted("a.example"))).link
        val nw = n.watchingAll(Watched.Link(joining))
        nw.step(LinkStep.SCANNING)
        // a scanning link draws no code
        assertEquals(Answer.Refused(Answer.Why.NO_BYTES), n.ask(Call.LinkCode(joining, 300)))
        n.scanInto(joining, plane, "veyqpair")
        val waiting = nw.step(LinkStep.WAITING)
        val confirming = tw.step(LinkStep.CONFIRMING)
        assertNotNull(waiting.number)
        assertEquals(waiting.number, confirming.number, "the two screens' numbers differ")
        return AddDevice(shown, tw, joining, nw, text)
    }

    private class AddDevice(val shown: Long, val tw: Watching, val joining: Long, val nw: Watching, val text: String)

    @Test
    fun aNewDeviceScansTheTrustedCodeThroughTheCallsAndItsAccountOpens() = runBlocking {
        val t = bridge(trustedAccount).trusted()
        val n = bridge(newAccount).atSignIn()
        val gate = n.watchingAll(Watched.Session)
        val a = addDeviceToTheSlide(t, n)

        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), t.ask(Call.LinkConfirm(a.shown, "  ")), "a blank name confirmed")
        assertEquals(Answer.Done, t.ask(Call.LinkConfirm(a.shown, "Laptop")))
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), t.ask(Call.LinkConfirm(a.shown, "Laptop")), "a second confirmation taken")
        a.tw.step(LinkStep.DONE)
        assertEquals("Laptop", a.nw.step(LinkStep.DONE).label)

        // the new device is signed in over the form's server, with the linked offer, and its tenant is open in the bridge
        val signed = gate.until { (it as Update.Session).gate as? Gate.Signed }
        assertEquals(true, signed.offer?.linked)
        assertEquals(listOf("https://a.example"), newRelays.toList())
        assertEquals(listOf("Laptop"), received.map { it.label })
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CONNECTION), n.ask(Call.SettingsOpen("a.example#u1")), "no tenant opened after the link")
        assertEquals(1, server.delegations.size)
        carriesNothingOfTheCode(listOf(a.text))
    }

    @Test
    fun theTrustedDeviceScansTheNewCodeAndTheSixDigitsAreTypedOnce() = runBlocking {
        val t = bridge(trustedAccount).trusted()
        val n = bridge(newAccount).atSignIn()
        val gate = n.watchingAll(Watched.Session)

        val joining = assertIs<Answer.LinkOpened>(n.ask(Call.LinkShowForSignIn("a.example"))).link
        val nw = n.watchingAll(Watched.Link(joining))
        nw.step(LinkStep.SHOWING)
        val (text, plane) = n.code(joining)
        assertTrue(text.startsWith("veyqsignin:"))
        // a showing link has no scan to open
        assertEquals(Answer.Refused(Answer.Why.NOT_OFFERED), n.ask(Call.ScanOpen(ScanKind.LINK, link = joining)))

        val scanning = assertIs<Answer.LinkOpened>(t.ask(Call.LinkScanSignInCode("a.example#u1"))).link
        val tw = t.watchingAll(Watched.Link(scanning))
        tw.step(LinkStep.SCANNING)
        t.scanInto(scanning, plane, "veyqsignin")
        val code = assertNotNull(tw.step(LinkStep.CODE).code)
        assertTrue(Regex("^[0-9]{6}$").matches(code))

        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), n.ask(Call.LinkEnterCode(joining, "12")), "five digits short taken")
        assertEquals(Answer.Done, n.ask(Call.LinkEnterCode(joining, code)))
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), n.ask(Call.LinkEnterCode(joining, code)), "the code sent twice")
        nw.step(LinkStep.WAITING)
        tw.step(LinkStep.CONFIRMING)
        assertEquals(Answer.Done, t.ask(Call.LinkConfirm(scanning, "Phone")))
        tw.step(LinkStep.DONE)
        nw.step(LinkStep.DONE)
        assertEquals(true, gate.until { (it as Update.Session).gate as? Gate.Signed }.offer?.linked)
        assertEquals(listOf("Phone"), received.map { it.label })
        carriesNothingOfTheCode(listOf(text))
    }

    @Test
    fun racingConfirmationsDelegateOnce() = runBlocking {
        val t = bridge(trustedAccount).trusted()
        val n = bridge(newAccount).atSignIn()
        val a = addDeviceToTheSlide(t, n)
        val answers = (1..32).map { async { t.ask(Call.LinkConfirm(a.shown, "Laptop")) } }.awaitAll()
        assertEquals(1, answers.count { it == Answer.Done }, "$answers")
        assertTrue(answers.filter { it != Answer.Done }.all { it == Answer.Refused(Answer.Why.NOT_TAKEN) })
        a.nw.step(LinkStep.DONE)
        assertEquals(1, server.delegations.size)
        assertEquals(1, server.seals.size)
    }

    @Test
    fun aCloseWhileTheGrantIsSpentEndsTheScreenAndTheAccountStillOpens() = runBlocking {
        tailHeld = CompletableDeferred()
        val t = bridge(trustedAccount).trusted()
        val n = bridge(newAccount).atSignIn()
        val gate = n.watchingAll(Watched.Session)
        val a = addDeviceToTheSlide(t, n)
        assertEquals(Answer.Done, t.ask(Call.LinkConfirm(a.shown, "Laptop")))
        a.nw.step(LinkStep.FINISHING)
        assertEquals(Answer.Done, n.ask(Call.LinkClose(a.joining)))
        assertNull(a.nw.until { (it as Update.Link).takeIf { u -> u.shown == null } }.shown)
        tailHeld.complete(Unit)
        gate.until { (it as Update.Session).gate as? Gate.Signed }
        val open = withTimeoutAnswer { n.ask(Call.SettingsOpen("a.example#u1")) != Answer.Refused(Answer.Why.NO_TENANT_OPEN) }
        assertTrue(open, "the closed link's sign-in did not open its tenant")
    }

    /** Asks [probe] until it holds, for at most ten seconds. */
    private suspend fun withTimeoutAnswer(probe: suspend () -> Boolean): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!probe()) kotlinx.coroutines.delay(50); true } ?: false

    @Test
    fun aTenantSwitchClosesTheTrustedLinkAndItsPollsStop() = runBlocking {
        val t = bridge(trustedAccount).trusted()
        val shown = assertIs<Answer.LinkOpened>(t.ask(Call.LinkShowForNewDevice("a.example#u1"))).link
        val w = t.watchingAll(Watched.Link(shown))
        w.step(LinkStep.SHOWING)
        assertEquals(Answer.Done, t.ask(Call.SessionSwitchTo("a.example#u1", "ta2")))
        assertNull(w.until { (it as Update.Link).takeIf { u -> u.shown == null } }.shown)
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_LINK), t.ask(Call.LinkCode(shown, 300)))
        assertTrue(granted.single().relay.side.closed, "the switched-away link's relay is still open")
        // a fresh watch of it: null at once
        assertNull((t.watching(Watched.Link(shown)).next() as Update.Link).shown)
    }

    @Test
    fun whatALinkCallCannotReachIsRefusedByName() = runBlocking {
        val t = bridge(trustedAccount)
        assertEquals(Answer.Done, t.ask(Call.SessionStart))
        // the trusted roles need a tenant open and a connection held; the new device's a sign-in shown
        assertEquals(Answer.Refused(Answer.Why.NO_TENANT_OPEN), t.ask(Call.LinkShowForNewDevice("a.example#u1")))
        assertEquals(Answer.Done, t.ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_CONNECTION), t.ask(Call.LinkScanSignInCode("b.example#u2")))
        assertEquals(Answer.Refused(Answer.Why.NO_SIGN_IN), t.ask(Call.LinkScanFromTrusted("a.example")))
        assertEquals(Answer.Refused(Answer.Why.NO_SIGN_IN), t.ask(Call.LinkShowForSignIn("a.example")))
        for (call in listOf(Call.LinkCode(99, 300), Call.LinkConfirm(99, "x"), Call.LinkEnterCode(99, "123456"), Call.LinkClose(99), Call.ScanOpen(ScanKind.LINK, link = 99), Call.ScanOpen(ScanKind.LINK))) {
            assertEquals(Answer.Refused(Answer.Why.NO_SUCH_LINK), t.ask(call), "$call")
        }
        assertNull((t.watching(Watched.Link(99)).next() as Update.Link).shown)
        // a confirmation or a code where the role has none
        val shown = assertIs<Answer.LinkOpened>(t.ask(Call.LinkShowForNewDevice("a.example#u1"))).link
        t.watching(Watched.Link(shown)).step(LinkStep.SHOWING)
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), t.ask(Call.LinkEnterCode(shown, "123456")))
        assertEquals(Answer.Refused(Answer.Why.NOT_TAKEN), t.ask(Call.LinkConfirm(shown, "Laptop")), "confirmed before an answer")
        // closed by the host: ended, its relay closed, its id gone
        assertEquals(Answer.Done, t.ask(Call.LinkClose(shown)))
        assertTrue(granted.single().relay.side.closed)
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_LINK), t.ask(Call.LinkClose(shown)))
    }

    /** The wire's link enums are the core's, name for name: a step added to the core is red here, not a crash in `valueOf`. */
    @Test
    fun theWiresLinkEnumsAreTheCoresByName() {
        assertEquals(DeviceLink.Role.entries.map { it.name }, LinkRole.entries.map { it.name })
        assertEquals(DeviceLink.Step.entries.map { it.name }, LinkStep.entries.map { it.name })
        assertEquals(DeviceLink.Refusal.entries.map { it.name }, LinkRefusal.entries.map { it.name })
        assertEquals(DeviceLink.Hint.entries.map { it.name }, LinkHint.entries.map { it.name })
    }
}
