package me.raddatz.veyq.mobile.export

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The streamed SHA-256 an archive hashes its payloads with, held to FIPS 180-4's examples and to the JDK's. */
class Sha256Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun theStandardsExamples() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.of(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.of("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        // a million "a", fed in pieces of 997 bytes
        val s = Sha256()
        val piece = ByteArray(997) { 'a'.code.toByte() }
        var left = 1_000_000
        while (left > 0) { val n = minOf(left, piece.size); s.update(piece, 0, n); left -= n }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hex(s.digest()))
    }

    @Test
    fun anyStreamHashesAsTheJdkDoes() {
        val r = Random(7)
        for (size in listOf(1, 55, 56, 63, 64, 65, 119, 120, 127, 128, 1000, 70_001)) {
            val data = r.nextBytes(size)
            val s = Sha256()
            var at = 0
            while (at < size) { val n = minOf(size - at, r.nextInt(1, 200)); s.update(data, at, n); at += n }
            assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(data)), hex(s.digest()), "size $size")
        }
    }
}
