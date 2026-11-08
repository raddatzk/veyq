package me.raddatz.veyq.mobile.crypto

import kotlin.io.encoding.Base64

/** UTF-8 of the NFC form, as the suite's `utf8` (primitives.ts). */
fun utf8(s: String): ByteArray = nfc(s).encodeToByteArray()

/** Unicode NFC normalisation, supplied by each platform's own text library. */
expect fun nfc(s: String): String

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) {
        p.copyInto(out, o)
        o += p.size
    }
    return out
}

fun u32be(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

fun u64be(n: Long): ByteArray = ByteArray(8) { i -> (n ushr (56 - 8 * i)).toByte() }

fun readU32be(b: ByteArray, at: Int): Int =
    ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

/** length-prefixed: u32be(len) ‖ bytes */
fun lp(x: ByteArray): ByteArray = concat(u32be(x.size), x)

/** Splits `lp(a) ‖ lp(b) ‖ …` into exactly [count] parts, refusing anything else. */
fun splitLp(b: ByteArray, count: Int): List<ByteArray> {
    val out = ArrayList<ByteArray>(count)
    var at = 0
    repeat(count) {
        require(at + 4 <= b.size) { "truncated length prefix" }
        val n = readU32be(b, at)
        require(n >= 0 && at + 4 + n <= b.size) { "truncated field" }
        out += b.copyOfRange(at + 4, at + 4 + n)
        at += 4 + n
    }
    require(at == b.size) { "trailing bytes" }
    return out
}

/** The wire's base64: standard alphabet, padded. */
fun b64(bytes: ByteArray): String = Base64.encode(bytes)
fun unb64(s: String): ByteArray = Base64.decode(s)

fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
