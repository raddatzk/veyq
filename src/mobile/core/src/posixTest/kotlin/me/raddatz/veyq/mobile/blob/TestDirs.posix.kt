@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir

internal actual val separator: String = "/"

internal actual fun tempDir(): String = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"

internal actual fun makeDir(path: String): Boolean = mkdir(path, 0x1C0.convert()) == 0

internal actual fun removeDir(path: String) {
    rmdir(path)
}

internal actual fun listDir(path: String): List<String> {
    val d = opendir(path) ?: return emptyList()
    try {
        val out = ArrayList<String>()
        while (true) {
            val e = readdir(d) ?: break
            val n = e.pointed.d_name.toKString()
            if (n != "." && n != "..") out += n
        }
        return out.sorted()
    } finally {
        closedir(d)
    }
}
