package me.raddatz.veyq.mobile.crypto

import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** [b64] is written by hand (no standard library tables on JavaScript): it writes what `kotlin.io.encoding.Base64` writes. */
class B64Test {
    @Test
    fun everyLengthAndEveryByteAsTheStandardLibraryWritesIt() {
        val random = Random(7)
        for (n in 0..70) {
            repeat(8) {
                val bytes = random.nextBytes(n)
                assertEquals(Base64.encode(bytes), b64(bytes), "length $n")
                assertContentEquals(bytes, unb64(b64(bytes)))
            }
        }
        val all = ByteArray(256) { it.toByte() }
        assertEquals(Base64.encode(all), b64(all))
        assertEquals("Ri1_-w", b64url(byteArrayOf(0x46, 0x2d, 0x7f, 0xfb.toByte())))
    }
}
