package me.raddatz.veyq.mobile.media

import kotlin.math.max
import kotlin.math.min

/**
 * A file the media headers read ([ImageHeader], [VideoHeader], [ExifReader]): its size, and its bytes `[from, from + length)`
 * — fewer only at its end. Offsets are `Double`s, exact to 2^53, so the web's path carries no emulated `Long` (ADR 0155,
 * § The media headers' slice); the apps hand a [ByteSource] and [media] adapts it.
 */
class MediaBytes(val size: Double, val read: suspend (from: Double, length: Int) -> ByteArray)

/** [this] as the media headers read it. */
fun ByteSource.media(): MediaBytes = MediaBytes(size.toDouble()) { from, length -> read(from.toLong(), length) }

/**
 * Reads a file in windows of [window] bytes, at most [cap] bytes in all and at most [maxVisits] boxes or elements walked: a
 * header the walk does not reach within them is not one it reads. [hold] makes a range readable; the readers then take
 * their bytes at absolute offsets from the window. Nothing here holds the file.
 */
internal class HeaderReader(private val file: MediaBytes, private val window: Int, private val cap: Double, private val maxVisits: Int) {
    private var from = 0.0
    private var bytes = ByteArray(0)
    private var spent = 0.0
    private var visits = 0

    val size: Double get() = file.size

    /** Makes `[at, at + length)` readable (fewer at the file's end); the count of bytes held from [at], or -1 where none
     *  are there or the cap is spent. */
    suspend fun hold(at: Double, length: Int): Int {
        if (at < 0 || at >= file.size) return -1
        val end = min(file.size, at + length)
        if (at < from || end > from + bytes.size) {
            val n = (min(file.size, at + max(length, window)) - at).toInt()
            if (spent + n > cap) return -1
            spent += n
            bytes = file.read(at, n)
            from = at
            if (bytes.size < (end - at).toInt()) return -1
        }
        return (end - at).toInt()
    }

    /** Counts one box or element visited; false once the walk has visited its most. */
    fun visit(): Boolean = ++visits <= maxVisits

    private fun i(at: Double): Int = (at - from).toInt()
    fun u8(at: Double): Int = bytes[i(at)].toInt() and 0xFF
    fun u16be(at: Double): Int = Bytes.u16be(bytes, i(at))
    fun u32be(at: Double): Double = Bytes.u32be(bytes, i(at))
    fun i32be(at: Double): Int = Bytes.i32be(bytes, i(at))
    fun fourcc(at: Double, s: String): Boolean = Bytes.ascii(bytes, i(at), s)

    /** A copy of the held `[at, at + length)`. */
    fun copy(at: Double, length: Int): ByteArray = bytes.copyOfRange(i(at), i(at) + length)
}

/** Big- and little-endian readers over a byte array; a read past its end is the caller's to rule out. */
internal object Bytes {
    fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
    fun u16be(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
    fun u16le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)
    fun u24le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16)
    fun i32be(b: ByteArray, at: Int): Int = (u8(b, at) shl 24) or (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)
    fun i32le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16) or (u8(b, at + 3) shl 24)

    /** An unsigned 32-bit number, as a `Double` (no `Long` on the web's path). */
    fun u32be(b: ByteArray, at: Int): Double = unsigned(i32be(b, at))
    fun u32le(b: ByteArray, at: Int): Double = unsigned(i32le(b, at))
    fun unsigned(v: Int): Double = if (v < 0) v.toDouble() + 4_294_967_296.0 else v.toDouble()

    /** Whether [b] holds the ASCII [s] at [at]. */
    fun ascii(b: ByteArray, at: Int, s: String): Boolean {
        if (at < 0 || at + s.length > b.size) return false
        for (i in s.indices) if ((b[at + i].toInt() and 0xFF) != s[i].code) return false
        return true
    }
}
