package me.raddatz.veyq.mobile.account

import me.raddatz.veyq.mobile.crypto.hex
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Sha1] held to FIPS 180-4's examples (and the one-block and two-block padding edges) and the web test's oracle. */
class Sha1Test {
    @Test
    fun fipsExamples() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(Sha1.of("abc".encodeToByteArray())))
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", hex(Sha1.of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hex(Sha1.of(ByteArray(0))))
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", hex(Sha1.of(ByteArray(1_000_000) { 'a'.code.toByte() })))
    }

    /** The padding's edges: a length field that fits the last block (55), one that pushes a second (56), block multiples. */
    @Test
    fun paddingEdges() {
        val expected = mapOf(
            55 to "c1c8bbdc22796e28c0e15163d20899b65621d65a", 56 to "c2db330f6083854c99d4b5bfb6e8f29f201be699",
            63 to "03f09f5b158a7a8cdad920bddc29b81c18a551f5", 64 to "0098ba824b5c16427bd7a1122a5a442a25ec644d",
            119 to "ee971065aaa017e0632a8ca6c77bb3bf8b1dfc56",
        )
        for ((n, digest) in expected) assertEquals(digest, hex(Sha1.of(ByteArray(n) { 'a'.code.toByte() })), "$n bytes")
    }

    @Test
    fun theWebTestsOracleInUpperCaseHex() {
        assertEquals("ABF7AAD6438836DBE526AA231ABDE2D0EEF74D42", Sha1.hexUpper("correct horse battery staple"))
    }
}
