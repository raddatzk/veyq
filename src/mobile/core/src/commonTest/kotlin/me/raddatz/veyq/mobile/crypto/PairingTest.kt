package me.raddatz.veyq.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.raddatz.veyq.mobile.session.testPrimitives

/**
 * What `#pairing_channel` leaves to the code (ADR 0059 §5, §6, §11): the round trips through the answer and the
 * envelope, each refusal by its name with the honest value as its control, the draws. The bytes are the vectors'
 * ([Conformance]); these are the properties a vector cannot hold.
 */
class PairingTest {
    private val p = testPrimitives
    private val pairing = Pairing(p)

    private fun channel() = pairing.channelKey(pairing.mintSecret())
    private fun grant(marks: PairingMarks? = null) =
        PairingGrant("someone@example.org", p.randomBytes(32), "cmVmcmVzaC10b2tlbi1vcGFxdWU", "Küchen-Tablet", marks)

    private fun refusal(f: () -> Unit): PairingRefusal? =
        try { f(); null } catch (e: PairingRefused) { e.reason }

    @Test
    fun theAnswerReadsBackUnderItsChannelKeyAndNoOther() {
        val key = channel()
        val (_, pub) = pairing.mintTransportKey()
        val answer = pairing.transportAnswer(key, pub)
        assertTrue(pairing.readTransportAnswer(key, answer).serialize().contentEquals(pub.serialize()))
        assertEquals(PairingRefusal.ANSWER_REFUSED, refusal { pairing.readTransportAnswer(channel(), answer) })
        assertEquals(PairingRefusal.ANSWER_UNREADABLE, refusal { pairing.readTransportAnswer(key, b64(pub.serialize())) })
        assertEquals(PairingRefusal.ANSWER_UNREADABLE, refusal { pairing.readTransportAnswer(key, b64(unb64(answer) + byteArrayOf(0))) })
    }

    @Test
    fun aSignInAnswerHoldsItsCodeAndIsNoAddDeviceAnswer() {
        val key = channel()
        val (_, pub) = pairing.mintTransportKey()
        val code = pairing.drawCode()
        val other = if (code == "123456") "654321" else "123456"
        val answer = pairing.signInAnswer(key, pub, code)
        assertTrue(pairing.readSignInAnswer(key, code, answer).serialize().contentEquals(pub.serialize()))
        assertEquals(PairingRefusal.ANSWER_REFUSED, refusal { pairing.readSignInAnswer(key, other, answer) })
        assertEquals(PairingRefusal.ANSWER_REFUSED, refusal { pairing.readTransportAnswer(key, answer) })
        assertEquals(PairingRefusal.ANSWER_REFUSED, refusal { pairing.readSignInAnswer(key, code, pairing.transportAnswer(key, pub)) })
        assertEquals(unb64(answer).size, unb64(pairing.transportAnswer(key, pub)).size, "the relay can tell the directions apart by size")
    }

    @Test
    fun theGrantRoundTripsAndOpensOnlyWithItsChannelKey() {
        val key = channel()
        val (priv, pub) = pairing.mintTransportKey()
        val marks = PairingMarks(5, 3, linkedMapOf("scope-a" to 2L))
        val sent = grant(marks)
        val opened = pairing.openGrant(key, priv, pairing.sealGrant(key, pub, sent))
        assertEquals(sent.email, opened.email)
        assertTrue(sent.masterKey.contentEquals(opened.masterKey))
        assertEquals(sent.refreshToken, opened.refreshToken)
        assertEquals(sent.label, opened.label)
        assertEquals(marks, opened.marks)
        // the relay's own grant: a valid seal to the right key, MACed under a key it guessed
        assertEquals(PairingRefusal.ENVELOPE_REFUSED, refusal { pairing.openGrant(key, priv, pairing.sealGrant(channel(), pub, grant())) })
        assertEquals(PairingRefusal.ENVELOPE_UNREADABLE, refusal { pairing.openGrant(key, priv, "not base64!") })
        // sealed to another device: the MAC verifies, the seal does not open
        val (otherPriv, _) = pairing.mintTransportKey()
        assertFailsWith<OpenRefused> { pairing.openGrant(key, otherPriv, pairing.sealGrant(key, pub, grant())) }
    }

    @Test
    fun theCheckNumberIsFiveDigitsOfTheAnsweringKeyAlone() {
        val (_, a) = pairing.mintTransportKey()
        val (_, b) = pairing.mintTransportKey()
        assertTrue(Regex("[0-9]{5}").matches(pairing.checkNumber(a)))
        assertEquals(pairing.checkNumber(a), pairing.checkNumber(IdentityPublic(a.kem, a.sign)))
        assertTrue(pairing.checkNumber(a) != pairing.checkNumber(b) || a.serialize().contentEquals(b.serialize()))
    }

    /** The draws, forced: a secret whose base64 starts with `/` is drawn again; a byte from 250 up is not a digit. */
    @Test
    fun theDrawsRefuseWhatTheyMustNotHandOut() {
        val draws = ArrayDeque(listOf(ByteArray(32) { 0xff.toByte() }, ByteArray(32) { 1 }))
        val forced = object : Primitives by p {
            override fun randomBytes(n: Int) = draws.removeFirst().copyOf(n)
        }
        assertTrue(b64(ByteArray(32) { 0xff.toByte() }).startsWith("/"))
        assertTrue(Pairing(forced).mintSecret().contentEquals(ByteArray(32) { 1 }))

        draws.clear()
        draws += byteArrayOf(250.toByte(), 255.toByte(), 3, 249.toByte(), 0, 10)
        draws += byteArrayOf(7, 8, 9, 9, 9, 9)
        assertEquals("390078", Pairing(forced).drawCode())
        repeat(50) { assertTrue(Regex("[0-9]{6}").matches(pairing.drawCode())) }
    }

    @Test
    fun aMintedSecretIsReadBackUnderItsOwnMarkerOnly() {
        repeat(64) {
            val secret = pairing.mintSecret()
            for (marker in PairingMarker.entries) {
                val text = PairingPayload.of(secret, marker)
                assertFalse("://" in text, text)
                assertTrue(PairingPayload.parse(text, marker)!!.contentEquals(secret))
                val other = PairingMarker.entries.single { it != marker }
                assertEquals(null, PairingPayload.parse(text, other))
            }
        }
    }

    @Test
    fun theGrantShowsNeitherKeyNorTokenAndWipes() {
        val g = grant()
        assertFalse(g.refreshToken in g.toString())
        assertFalse(b64(g.masterKey) in g.toString())
        g.wipe()
        assertTrue(g.masterKey.all { it == 0.toByte() })
    }
}
