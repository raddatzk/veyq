package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.raddatz.veyq.mobile.crypto.Pairing
import me.raddatz.veyq.mobile.crypto.PairingGrant
import me.raddatz.veyq.mobile.crypto.PairingMarker
import me.raddatz.veyq.mobile.crypto.PairingMarks
import me.raddatz.veyq.mobile.crypto.PairingPayload
import me.raddatz.veyq.mobile.qr.QrDecoder
import me.raddatz.veyq.mobile.qr.QrEncoder
import me.raddatz.veyq.mobile.qr.QrScan
import me.raddatz.veyq.mobile.session.DeviceLink.Hint
import me.raddatz.veyq.mobile.session.DeviceLink.Refusal
import me.raddatz.veyq.mobile.session.DeviceLink.Step
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * `DeviceLink`'s four roles over a played relay ([PlayedServer]), both sides real: the pairing crypto, the QR drawn by one
 * link and read through the real decoder by the other (`cr-app-device-linking` phase 3).
 */
class DeviceLinkTest {
    private val p = testPrimitives
    private val pairing = Pairing(p)

    private companion object {
        /** Confirmations racing on one link, and links raced: rounds make an overlap of the racers near certain. */
        const val RACERS = 32
        const val RACE_ROUNDS = 20
    }

    /** The links' work for a few poll rounds (the channel's own timer is far away). */
    private fun TestScope.settle(rounds: Int = 3) {
        repeat(rounds) {
            advanceTimeBy(DeviceLink.POLL_MS + 1)
            runCurrent()
        }
    }

    /** The grants a new device's tail received, and how it answers. */
    private class Tail {
        val received = mutableListOf<Received>()
        var fails: Exception? = null

        /** How long the tail works (the token's spend and the successor's keep); [completed] counts the tails that ended. */
        var takesMs = 0L
        var completed = 0

        class Received(val email: String, val masterKey: ByteArray, val token: String, val label: String, val marks: PairingMarks?)

        suspend fun receive(g: PairingGrant) {
            received += Received(g.email, g.masterKey.copyOf(), g.refreshToken, g.label, g.marks)
            fails?.let { throw it }
            if (takesMs > 0) kotlinx.coroutines.delay(takesMs)
            completed++
        }
    }

    /** The text [link] shows, read back off its QR as a camera would. */
    private suspend fun shown(link: DeviceLink): String = assertNotNull(QrDecoder.decode(frameOf(assertNotNull(link.matrix(), "no code shown"))))

    private fun scanning(link: DeviceLink): QrScan<Unit> = assertNotNull(link.scan).also { it.camera(QrScan.Camera.RUNNING) }

    @Test
    fun add_a_new_device_runs_end_to_end_and_the_trusted_device_never_collects_after_the_answer() = runTest {
        val server = PlayedServer()
        val account = server.Account()
        val newSide = server.Side("new")
        val tail = Tail()
        val trusted = DeviceLink.showForNewDevice(p, account, backgroundScope)
        val joining = DeviceLink.scanFromTrusted(p, newSide, tail::receive, scope = backgroundScope)
        runCurrent()
        assertEquals(Step.SHOWING, trusted.current.step)
        assertEquals(Step.SCANNING, joining.current.step)
        assertNull(joining.matrix(), "the scanning side shows no code")
        assertTrue(shown(trusted).startsWith("veyqpair:"))
        assertNotNull(trusted.png(300)).let { assertEquals(0x89.toByte(), it[0]) }

        scanning(joining).offer(frameOf(trusted.matrix()!!))
        runCurrent()
        assertEquals(Step.WAITING, joining.current.step)
        val number = assertNotNull(joining.current.number)
        assertTrue(Regex("^[0-9]{5}$").matches(number))

        settle()
        assertEquals(Step.CONFIRMING, trusted.current.step)
        assertEquals(number, trusted.current.number, "the two screens' numbers differ")
        assertTrue(server.delegations.isEmpty(), "delegated before the slide")
        assertFalse(trusted.confirm("  "), "a blank name was confirmed")

        assertTrue(trusted.confirm(" Kitchen tablet "))
        settle()
        assertEquals(Step.DONE, trusted.current.step)
        assertEquals(1, server.delegations.size)
        assertEquals(1, server.seals.size)
        assertEquals(Step.DONE, joining.current.step)
        assertEquals("Kitchen tablet", joining.current.label)
        val got = tail.received.single()
        assertEquals("p@example.org", got.email)
        assertContentEquals(ByteArray(32) { (it + 1).toByte() }, got.masterKey)
        assertEquals("token-1", got.token)
        assertEquals("Kitchen tablet", got.label)
        assertEquals(PairingMarks(3, 4, mapOf("scope-1" to 2L)), got.marks)
        assertTrue(server.channels.values.single().spent, "the grant was not collected")
        assertEquals(0, account.relay.collectsAfterAnswer, "the trusted device collected after the answer (it would spend the grant)")
        assertTrue(account.handedKeys.single().all { it == 0.toByte() }, "the sealed Master Key copy was not zeroed")
        assertTrue(newSide.closed, "the new device's relay stayed open")
    }

    @Test
    fun sign_in_with_a_qr_code_runs_end_to_end() = runTest {
        val server = PlayedServer()
        val account = server.Account()
        val tail = Tail()
        val joining = DeviceLink.showForSignIn(p, server.Side("new"), tail::receive, backgroundScope)
        val trusted = DeviceLink.scanSignInCode(p, account, scope = backgroundScope)
        runCurrent()
        assertEquals(Step.SHOWING, joining.current.step)
        assertTrue(shown(joining).startsWith("veyqsignin:"))
        assertNull(joining.current.number, "this direction compares no number (§5)")

        scanning(trusted).offer(frameOf(joining.matrix()!!))
        runCurrent()
        assertEquals(Step.CODE, trusted.current.step)
        val code = assertNotNull(trusted.current.code)
        assertTrue(Regex("^[0-9]{6}$").matches(code))
        assertTrue(server.delegations.isEmpty())

        assertFalse(joining.enterCode("12345"), "five digits were sent")
        assertTrue(joining.enterCode(code))
        assertEquals(Step.WAITING, joining.current.step)
        settle()
        assertEquals(Step.CONFIRMING, trusted.current.step)
        assertNull(trusted.current.number)
        assertTrue(trusted.confirm("Laptop"))
        settle()
        assertEquals(Step.DONE, trusted.current.step)
        assertEquals(Step.DONE, joining.current.step)
        assertEquals("Laptop", tail.received.single().label)
        assertEquals(0, account.relay.collectsAfterAnswer)
    }

    @Test
    fun a_forged_answer_is_refused_before_a_number_shows_and_nothing_is_delegated_or_sealed() = runTest {
        val server = PlayedServer()
        val account = server.Account()
        val trusted = DeviceLink.showForNewDevice(p, account, backgroundScope)
        runCurrent()
        // the relay answers with a key of its own: it holds the handle, never the channel key
        val (_, relayKey) = pairing.mintTransportKey()
        server.channels.values.single().answer = pairing.transportAnswer(ByteArray(32) { 7 }, relayKey)
        settle()
        assertEquals(Step.ENDED, trusted.current.step)
        assertEquals(Refusal.REFUSED, trusted.current.refusal)
        assertNull(trusted.current.number, "a number was shown for a forged answer")
        assertFalse(trusted.confirm("Mallory"), "a refused answer could be confirmed")
        settle()
        assertTrue(server.delegations.isEmpty(), "delegated to a forged answer")
        assertTrue(server.seals.isEmpty(), "sealed to a forged answer")
        assertTrue(account.relay.closed)
    }

    @Test
    fun a_forged_sign_in_answer_is_refused_too() = runTest {
        val server = PlayedServer()
        val trusted = DeviceLink.scanSignInCode(p, server.Account(), scope = backgroundScope)
        runCurrent()
        val secret = pairing.mintSecret()
        server.Side("new").open(b64Handle(secret))
        scanning(trusted).offer(frameOf(QrEncoder.encode(PairingPayload.of(secret, PairingMarker.SIGN_IN))))
        runCurrent()
        val code = assertNotNull(trusted.current.code)
        val (_, relayKey) = pairing.mintTransportKey()
        server.channels.values.single().answer = pairing.signInAnswer(ByteArray(32) { 7 }, relayKey, code)
        settle()
        assertEquals(Refusal.REFUSED, trusted.current.refusal)
        assertTrue(server.delegations.isEmpty())
    }

    @Test
    fun a_forged_envelope_is_refused_and_never_reaches_the_tail() = runTest {
        val server = PlayedServer()
        val tail = Tail()
        val trusted = DeviceLink.showForNewDevice(p, server.Account(), backgroundScope)
        val joining = DeviceLink.scanFromTrusted(p, server.Side("new"), tail::receive, scope = backgroundScope)
        runCurrent()
        val text = shown(trusted)
        scanning(joining).offer(frameOf(trusted.matrix()!!))
        runCurrent()
        settle()
        assertEquals(Step.CONFIRMING, trusted.current.step)
        // the relay seals to the key it read off the answer, under a MAC it cannot make
        val channel = server.channels.values.single()
        val realKey = pairing.channelKey(assertNotNull(PairingPayload.parse(text, PairingMarker.ADD_DEVICE)))
        val pub = pairing.readTransportAnswer(realKey, assertNotNull(channel.answer))
        channel.envelope = pairing.sealGrant(ByteArray(32) { 9 }, pub, PairingGrant("m@evil", ByteArray(32), "stolen", "Mallory"))
        settle()
        assertEquals(Step.ENDED, joining.current.step)
        assertEquals(Refusal.REFUSED, joining.current.refusal)
        assertTrue(tail.received.isEmpty(), "a forged grant reached the sign-in")
        trusted.close()
    }

    @Test
    fun the_other_directions_code_is_skipped_with_a_hint_and_the_scan_goes_on() = runTest {
        val server = PlayedServer()
        val joining = DeviceLink.scanFromTrusted(p, server.Side("new"), Tail()::receive, scope = backgroundScope)
        val trusted = DeviceLink.scanSignInCode(p, server.Account(), scope = backgroundScope)
        runCurrent()
        val signIn = frameOf(QrEncoder.encode(PairingPayload.of(pairing.mintSecret(), PairingMarker.SIGN_IN)))
        val addDevice = frameOf(QrEncoder.encode(PairingPayload.of(pairing.mintSecret(), PairingMarker.ADD_DEVICE)))
        val other = frameOf(QrEncoder.encode("https://example.org/veyqpair:x"))

        scanning(joining).offer(signIn)
        runCurrent()
        assertEquals(Step.SCANNING, joining.current.step)
        assertEquals(Hint.OTHER_DIRECTION, joining.current.hint)
        assertEquals(QrScan.State.Scanning, joining.scan!!.current, "the scan stopped on the other direction's code")
        assertEquals(0, server.channels.size)

        scanning(trusted).offer(addDevice)
        runCurrent()
        assertEquals(Step.SCANNING, trusted.current.step)
        assertEquals(Hint.OTHER_DIRECTION, trusted.current.hint)

        trusted.scan!!.offer(other)
        runCurrent()
        assertEquals(Step.SCANNING, trusted.current.step, "a foreign code was taken")

        // the right code still goes on: here a channel this server never had (another server's code) — gone, same server
        joining.scan!!.offer(addDevice)
        runCurrent()
        assertEquals(Step.ENDED, joining.current.step)
        assertEquals(Refusal.GONE, joining.current.refusal)
        assertEquals(Hint.SAME_SERVER, joining.current.hint)
        trusted.close()
    }

    @Test
    fun a_wrong_six_digit_code_is_sent_once_the_trusted_device_refuses_it_and_the_pairing_is_abandoned() = runTest {
        val server = PlayedServer()
        val account = server.Account()
        val tail = Tail()
        val newSide = server.Side("new")
        val joining = DeviceLink.showForSignIn(p, newSide, tail::receive, backgroundScope)
        val trusted = DeviceLink.scanSignInCode(p, account, scope = backgroundScope)
        runCurrent()
        scanning(trusted).offer(frameOf(joining.matrix()!!))
        runCurrent()
        val code = assertNotNull(trusted.current.code)
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertTrue(joining.enterCode(wrong))
        assertFalse(joining.enterCode(code), "a second code was sent: the answer slot is write-once")
        runCurrent()
        assertEquals(1, newSide.answers)
        settle()
        assertEquals(Refusal.REFUSED, trusted.current.refusal)
        assertTrue(server.delegations.isEmpty())
        assertEquals(Step.WAITING, joining.current.step, "the new device learns nothing but the channel going quiet")
        server.expire(server.channels.keys.single())
        settle()
        assertEquals(Step.ENDED, joining.current.step)
        assertEquals(Refusal.GONE, joining.current.refusal)
        assertTrue(tail.received.isEmpty())
    }

    @Test
    fun no_answer_says_same_server_after_the_first_window_and_ends_at_the_channels_expiry() = runTest {
        val server = PlayedServer()
        val account = server.Account()
        val trusted = DeviceLink.showForNewDevice(p, account, backgroundScope)
        val joining = DeviceLink.showForSignIn(p, server.Side("new"), Tail()::receive, backgroundScope)
        runCurrent()
        advanceTimeBy(DeviceLink.HINT_AFTER_MS - 1_000)
        runCurrent()
        assertNull(trusted.current.hint)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Hint.SAME_SERVER, trusted.current.hint)
        assertEquals(Hint.SAME_SERVER, joining.current.hint)
        assertEquals(Step.SHOWING, trusted.current.step)
        advanceTimeBy(DeviceLink.CHANNEL_MS)
        runCurrent()
        assertEquals(Refusal.GONE, trusted.current.refusal)
        assertEquals(Refusal.GONE, joining.current.refusal)
        val polls = account.relay.collects
        settle()
        assertEquals(polls, account.relay.collects, "an ended link polls on")
        assertFalse(joining.enterCode("123456"), "a code was sent on an ended link")
    }

    @Test
    fun an_instance_that_does_not_link_devices_ends_every_role_off() = runTest {
        val server = PlayedServer().apply { capable = false }
        val links = listOf(
            DeviceLink.showForNewDevice(p, server.Account(), backgroundScope),
            DeviceLink.scanSignInCode(p, server.Account(), scope = backgroundScope),
            DeviceLink.scanFromTrusted(p, server.Side("n"), Tail()::receive, scope = backgroundScope),
            DeviceLink.showForSignIn(p, server.Side("n"), Tail()::receive, backgroundScope),
        )
        runCurrent()
        for (l in links) assertEquals(Refusal.OFF, l.current.refusal, "${l.role}")
        assertTrue(server.channels.isEmpty())

        // switched off between the setup read and the open: the 422
        val late = PlayedServer().apply { off = true }
        val shown = DeviceLink.showForNewDevice(p, late.Account(), backgroundScope)
        runCurrent()
        assertEquals(Refusal.OFF, shown.current.refusal)
        assertNull(shown.matrix(), "a code was shown for a channel that never opened")
    }

    @Test
    fun the_tails_failure_ends_the_link_a_spent_token_as_gone() = runTest {
        for ((failure, refusal) in listOf(
            SignInFailure(SignInFailure.Reason.SESSION_ENDED) to Refusal.GONE,
            SignInFailure(SignInFailure.Reason.UNREACHABLE) to Refusal.FAILED,
        )) {
            val server = PlayedServer()
            val tail = Tail().apply { fails = failure }
            val trusted = DeviceLink.showForNewDevice(p, server.Account(), backgroundScope)
            val joining = DeviceLink.scanFromTrusted(p, server.Side("new"), tail::receive, scope = backgroundScope)
            runCurrent()
            scanning(joining).offer(frameOf(trusted.matrix()!!))
            runCurrent()
            settle()
            trusted.confirm("Phone")
            settle()
            assertEquals(1, tail.received.size)
            assertEquals(Step.ENDED, joining.current.step)
            assertEquals(refusal, joining.current.refusal)
        }
    }

    @Test
    fun closing_stops_the_polls_and_sends_nothing_more() = runTest {
        val server = PlayedServer()
        val account = server.Account()
        val trusted = DeviceLink.showForNewDevice(p, account, backgroundScope)
        runCurrent()
        settle()
        trusted.close()
        assertEquals(Step.ENDED, trusted.current.step)
        assertNull(trusted.current.refusal, "a close is no refusal")
        assertNull(trusted.matrix(), "the code is still drawn after the close")
        val polls = account.relay.collects
        settle()
        assertEquals(polls, account.relay.collects, "a closed link polls on")
        assertTrue(account.relay.closed)
    }

    @Test
    fun the_state_carries_no_secret() = runTest {
        val server = PlayedServer()
        val trusted = DeviceLink.showForNewDevice(p, server.Account(), backgroundScope)
        val joining = DeviceLink.scanFromTrusted(p, server.Side("new"), Tail()::receive, scope = backgroundScope)
        runCurrent()
        val text = shown(trusted)
        scanning(joining).offer(frameOf(trusted.matrix()!!))
        runCurrent()
        settle()
        trusted.confirm("Phone")
        val secretText = text.substringAfter(':')
        val handle = server.channels.keys.single()
        for (state in listOf(trusted.current, joining.current)) {
            val json = Json.encodeToString(DeviceLink.Shown.serializer(), state)
            for (secret in listOf(secretText, handle, "token-1")) {
                assertFalse(secret in json, "the state's JSON carries a secret")
                assertFalse(secret in state.toString(), "the state's text carries a secret")
            }
        }
        settle()
    }

    // ---- the review's findings (2026-11-13): single-flight, failures caught, a close racing the grant, secrets ----

    /** *Add a new device* to the trusted side's CONFIRMING: the new device scanned, answered, the answer verified. */
    private fun TestScope.confirming(account: PlayedServer.Account, newSide: DeviceLink.Relay, tail: Tail): Pair<DeviceLink, DeviceLink> {
        val trusted = DeviceLink.showForNewDevice(p, account, backgroundScope)
        val joining = DeviceLink.scanFromTrusted(p, newSide, tail::receive, scope = backgroundScope)
        runCurrent()
        scanning(joining).offer(frameOf(trusted.matrix()!!))
        runCurrent()
        settle()
        assertEquals(Step.CONFIRMING, trusted.current.step)
        return trusted to joining
    }

    @Test
    // the rounds' pairings take a while on a loaded host: the wall-clock guard has room (virtual time never moves here)
    fun a_double_tap_or_a_race_of_confirmations_delegates_once() = runTest(timeout = 5.minutes) {
        val server = PlayedServer()
        val (trusted, joining) = confirming(server.Account(), server.Side("new"), Tail())
        assertTrue(trusted.confirm("Phone"))
        assertFalse(trusted.confirm("Phone"), "a second tap confirmed again")
        settle()
        assertEquals(1, server.delegations.size, "a double tap delegated twice")
        assertEquals(Step.DONE, trusted.current.step)
        assertEquals(Step.DONE, joining.current.step)

        repeat(RACE_ROUNDS) { round ->
            val raced = PlayedServer()
            val (other, _) = confirming(raced.Account(), raced.Side("new"), Tail())
            val taken = race(RACERS) { other.confirm("Phone") }
            assertEquals(1, taken.count { it }, "round $round: racing confirmations moved the link ${taken.count { it }} times")
            settle()
            assertEquals(1, raced.delegations.size, "round $round: racing confirmations delegated ${raced.delegations.size} times")
            assertEquals(Step.DONE, other.current.step)
        }
    }

    /**
     * [act] on [n] threads of their own at once: each waits on one gate until all [n] are up (parked, not spinning, so a
     * loaded host does not starve the last one), so the calls overlap as closely as the host lets them. The test thread
     * **blocks** meanwhile instead of suspending — a `runTest` body suspended on another dispatcher lets the scheduler
     * run the links' delayed work and jump virtual time (to the channel's end, when the racers are slow to get a
     * thread), which made this race flaky (2026-11-13: `expected 1 but was 0` with `Dispatchers.Default` busy). Blocked,
     * no virtual time passes; what the confirmations launch waits on the test dispatcher for the [settle] after.
     */
    @OptIn(ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)
    private fun <T> race(n: Int, act: () -> T): List<T> {
        val up = AtomicInt(0)
        val gate = CompletableDeferred<Unit>()
        return newFixedThreadPoolContext(n, "race").use { threads ->
            runBlocking(threads) {
                (1..n).map {
                    async {
                        if (up.incrementAndFetch() == n) gate.complete(Unit)
                        gate.await()
                        act()
                    }
                }.awaitAll()
            }
        }
    }

    @Test
    fun a_seal_that_fails_ends_the_link_failed_instead_of_escaping() = runTest {
        val server = PlayedServer()
        val (trusted, joining) = confirming(server.Account(keyFails = true), server.Side("new"), Tail())
        assertTrue(trusted.confirm("Phone"))
        settle()
        assertEquals(Step.ENDED, trusted.current.step, "the link stayed in ${trusted.current.step}")
        assertEquals(Refusal.FAILED, trusted.current.refusal)
        assertTrue(server.seals.isEmpty())
        joining.close()
    }

    @Test
    fun an_envelope_whose_mac_verifies_but_whose_seal_does_not_open_ends_failed() = runTest {
        val server = PlayedServer()
        val tail = Tail()
        val trusted = DeviceLink.showForNewDevice(p, server.Account(), backgroundScope)
        val joining = DeviceLink.scanFromTrusted(p, server.Side("new"), tail::receive, scope = backgroundScope)
        runCurrent()
        val key = pairing.channelKey(assertNotNull(PairingPayload.parse(shown(trusted), PairingMarker.ADD_DEVICE)))
        scanning(joining).offer(frameOf(trusted.matrix()!!))
        runCurrent()
        settle()
        val garbage = ByteArray(1200) { it.toByte() }
        server.channels.values.single().envelope = b64(concat(lp(garbage), lp(pairing.envelopeMac(key, garbage))))
        settle()
        assertEquals(Step.ENDED, joining.current.step, "the link stayed in ${joining.current.step}")
        assertEquals(Refusal.FAILED, joining.current.refusal)
        assertTrue(tail.received.isEmpty())
        trusted.close()
    }

    /** A relay that runs [onEnvelope] the moment the collect that hands the envelope over returns. */
    private class Racing(private val inner: DeviceLink.Relay, private val onEnvelope: () -> Unit) : DeviceLink.Relay by inner {
        override suspend fun collect(handle: String) = inner.collect(handle).also { if (it.envelope != null) onEnvelope() }
    }

    /**
     * The close zeroes the link's channel key in place: a grant opened after it would be checked under the all-zero key,
     * which the relay — holding the transport key off the answer — can MAC under. The relay plants such an envelope and
     * the screen closes as it is collected: nothing is opened, nothing spent.
     */
    @Test
    fun a_close_between_the_envelope_and_its_opening_opens_and_spends_nothing() = runTest {
        val server = PlayedServer()
        val tail = Tail()
        lateinit var joining: DeviceLink
        val trusted = DeviceLink.showForNewDevice(p, server.Account(), backgroundScope)
        joining = DeviceLink.scanFromTrusted(p, Racing(server.Side("new")) { joining.close() }, tail::receive, scope = backgroundScope)
        runCurrent()
        val key = pairing.channelKey(assertNotNull(PairingPayload.parse(shown(trusted), PairingMarker.ADD_DEVICE)))
        scanning(joining).offer(frameOf(trusted.matrix()!!))
        runCurrent()
        settle()
        val channel = server.channels.values.single()
        assertNotNull(pairing.readTransportAnswer(key, assertNotNull(channel.answer)))
        // the close zeroes the transport seeds too: the key they then derive is as public as the zero channel key
        val zeroed = me.raddatz.veyq.mobile.crypto.V1(p).identityPublic(me.raddatz.veyq.mobile.crypto.IdentityPrivate(ByteArray(32), ByteArray(32)))
        channel.envelope = pairing.sealGrant(ByteArray(32), zeroed, PairingGrant("m@evil", ByteArray(32), "stolen", "Mallory"))
        settle()
        assertEquals(Step.ENDED, joining.current.step)
        assertNull(joining.current.refusal)
        assertTrue(tail.received.isEmpty(), "a closed link opened a grant under its zeroed key")
        trusted.close()
    }

    @Test
    fun a_close_while_the_tail_runs_lets_it_finish_so_the_spent_tokens_successor_is_kept() = runTest {
        val server = PlayedServer()
        val tail = Tail().apply { takesMs = 60_000 }
        val (trusted, joining) = confirming(server.Account(), server.Side("new"), tail)
        assertTrue(trusted.confirm("Phone"))
        settle()
        assertEquals(Step.FINISHING, joining.current.step)
        joining.close()
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(1, tail.completed, "closing the screen cut the sign-in's tail off after the token was spent")
        assertEquals(Step.ENDED, joining.current.step)
        assertTrue(joining.signedIn(), "the tail signed in, but the closed link said it did not (inbox #918)")
    }

    @Test
    fun a_link_that_ends_without_its_tail_says_it_signed_nothing_in() = runTest {
        val server = PlayedServer()
        val failing = Tail().apply { fails = IllegalStateException("the tail failed") }
        val (trusted, joining) = confirming(server.Account(), server.Side("new"), failing)
        assertTrue(trusted.confirm("Phone"))
        settle()
        assertEquals(Step.ENDED, joining.current.step)
        assertFalse(joining.signedIn())
        assertFalse(trusted.signedIn(), "a trusted device's link said it signed in")

        val closed = DeviceLink.showForSignIn(p, server.Side("new"), Tail()::receive, backgroundScope)
        runCurrent()
        closed.close()
        assertFalse(closed.signedIn())
    }

    @Test
    fun the_channel_key_and_the_transport_key_end_zeroed() = runTest {
        val server = PlayedServer()
        val (trusted, joining) = confirming(server.Account(), server.Side("new"), Tail())
        val held = joining.heldSecrets()
        assertEquals(3, held.size)
        assertTrue(held.all { s -> s.any { it != 0.toByte() } }, "a secret was zero before the end")
        joining.close()
        trusted.close()
        for (s in held + trusted.heldSecrets()) assertTrue(s.all { it == 0.toByte() }, "a secret outlived the link")
    }

    private fun b64Handle(secret: ByteArray): String = me.raddatz.veyq.mobile.crypto.b64(pairing.handle(secret))
}
