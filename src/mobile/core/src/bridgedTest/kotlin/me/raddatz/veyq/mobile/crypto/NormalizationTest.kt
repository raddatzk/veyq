@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.crypto

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Linux's and Windows' `nfc`/`nfkd` (utf8proc) against Unicode's own NormalizationTest.txt for the version utf8proc implements,
 * fetched and pinned by `core/utf8proc/build_utf8proc.py` (ADR 0123's Consequences, the core on Linux). Every line's five columns
 * c1..c5 must satisfy the file's invariants for the two forms the suite uses:
 * NFC: c2 == toNFC(c1) == toNFC(c2) == toNFC(c3), c4 == toNFC(c4) == toNFC(c5);
 * NFKD: c5 == toNFKD(c1..c5) — and, as the file's Part 1 requires, every other code point is its own NFC and NFKD.
 */
class NormalizationTest {
    @Test
    fun everyLineOfUnicodesNormalizationTestHolds() {
        val path = getenv("VEYQ_NORMALIZATION_TEST")?.toKString() ?: error("VEYQ_NORMALIZATION_TEST is not set")
        val text = readFile(path)
        assertTrue(text.startsWith("# NormalizationTest-"), "$path is not Unicode's NormalizationTest.txt")
        val failures = mutableListOf<String>()
        var lines = 0
        var part = ""
        val partOne = HashSet<Int>()
        for (line in text.lineSequence()) {
            val data = line.substringBefore('#').trim()
            if (data.startsWith('@')) part = data
            if (data.isEmpty() || data.startsWith('@')) continue
            val cols = data.split(';').take(5).map { col -> col.trim().split(' ').map { it.toInt(16) } }
            if (part == "@Part1") partOne += cols[0].single()
            val c = cols.map { col -> col.joinToString("") { cp(it) } }
            lines++
            val nfcWant = listOf(c[1], c[1], c[1], c[3], c[3])
            for (i in 0..4) {
                if (nfc(c[i]) != nfcWant[i]) failures += "NFC(c${i + 1}) of `$data`"
                if (nfkd(c[i]) != c[4]) failures += "NFKD(c${i + 1}) of `$data`"
            }
        }
        var others = 0
        for (code in 0..0x10FFFF) {
            if (code in 0xD800..0xDFFF || code in partOne) continue
            val x = cp(code)
            if (nfc(x) != x || nfkd(x) != x) failures += "U+${code.toString(16).uppercase()} is not its own NFC and NFKD"
            others++
        }
        println("NormalizationTest: $lines lines, $others other code points, ${failures.size} failures")
        assertTrue(lines > 19_000, "only $lines lines read")
        assertEquals(emptyList(), failures.take(20), "${failures.size} failures")
    }

    private fun cp(code: Int): String =
        if (code < 0x10000) code.toChar().toString()
        else (code - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
}
