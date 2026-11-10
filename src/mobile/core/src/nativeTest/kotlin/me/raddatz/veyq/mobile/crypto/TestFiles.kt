@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.crypto

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/** A file's text (UTF-8), read through POSIX so one reader serves every native target's tests. */
fun readFile(path: String): String {
    val f = fopen(path, "rb") ?: error("cannot read $path")
    try {
        val out = ArrayList<ByteArray>()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = buf.usePinned { fread(it.addressOf(0), 1.convert(), buf.size.convert(), f) }.toInt()
            if (n <= 0) break
            out += buf.copyOf(n)
        }
        val all = ByteArray(out.sumOf { it.size })
        var o = 0
        for (b in out) { b.copyInto(all, o); o += b.size }
        return all.decodeToString()
    } finally {
        fclose(f)
    }
}
