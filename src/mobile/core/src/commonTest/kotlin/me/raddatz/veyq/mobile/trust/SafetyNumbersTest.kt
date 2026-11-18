package me.raddatz.veyq.mobile.trust

import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.qr.QrSchemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The safety code's shape beyond the vectors (which `Conformance` reads: `#safety_code`, `#fingerprint`). */
class SafetyNumbersTest {
    private val hash = ByteArray(32) { (it * 7).toByte() }
    private val head = ByteArray(32) { (255 - it).toByte() }

    @Test
    fun aPayloadIs98CharactersAndReadsBackAsAMatch() {
        val payload = SafetyNumbers.payload(hash, head)
        assertEquals(98, payload.length)
        assertEquals(SafetyNumbers.Verdict.MATCH, SafetyNumbers.compareScan(payload, b64(hash), b64(head)))
        assertEquals("veyqid", QrSchemes.of(payload))
    }

    @Test
    fun onlyThirtyTwoBytesRender() {
        assertFailsWith<IllegalArgumentException> { SafetyNumbers.fingerprintDigits(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { SafetyNumbers.chainCodeDigits(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { SafetyNumbers.payload(hash, ByteArray(31)) }
    }

    @Test
    fun theChainCodeIsTheFirstTwoGroupsOfTheSameRendering() {
        assertEquals(SafetyNumbers.fingerprintDigits(head).split(' ').take(2).joinToString(" "), SafetyNumbers.chainCodeDigits(head))
    }
}
