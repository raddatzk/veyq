package me.raddatz.veyq.mobile.blob

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import me.raddatz.veyq.mobile.crypto.unhex

/** The platform's zlib on every target (`platform.zlib` on the native ones, mingw's linked into veyq.dll statically). */
class InflateTest {
    /** Python's `zlib.compress(b"veyq inflate " * 10)`. */
    private val known = unhex("789c2b4bad2c54c8cc4bcb492c4955281b080e009fcc3111")
    private val text = "veyq inflate ".repeat(10)

    @Test
    fun aKnownStreamInflates() = assertEquals(text, inflate(known, 1024)?.decodeToString())

    @Test
    fun aStreamPastTheCapIsRefused() {
        assertEquals(text, inflate(known, text.length)?.decodeToString(), "exactly the cap is within it")
        assertNull(inflate(known, text.length - 1))
    }

    @Test
    fun aBrokenOrTruncatedStreamIsRefused() {
        assertNull(inflate(known.copyOf(known.size - 6), 1024), "truncated")
        assertNull(inflate(byteArrayOf(1, 2, 3, 4), 1024), "no zlib header")
        assertNull(inflate(ByteArray(0), 1024), "empty")
    }
}
