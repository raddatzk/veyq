@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.qr

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv

internal actual fun readRepoFile(path: String): ByteArray {
    val root = getenv("VEYQ_REPO_ROOT")?.toKString() ?: error("VEYQ_REPO_ROOT is not set")
    val f = fopen("$root/$path", "rb") ?: error("cannot read $path")
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
        return all
    } finally {
        fclose(f)
    }
}
