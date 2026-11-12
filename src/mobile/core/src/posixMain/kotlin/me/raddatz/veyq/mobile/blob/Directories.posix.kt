@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

// Apple and Linux list a directory through POSIX's own `opendir`/`readdir`; what each entry is comes from `lstat`
// (`pathFacts`, per platform: the modified time's field is named apart, `st_mtimespec` and `st_mtim`).

internal actual fun listDirectory(path: String): List<String>? {
    val d = opendir(path) ?: return null
    try {
        val out = ArrayList<String>()
        while (true) {
            val e = readdir(d) ?: break
            val n = e.pointed.d_name.toKString()
            if (n != "." && n != "..") out += n
        }
        return out
    } finally {
        closedir(d)
    }
}

internal actual val pathSeparator: Char = '/'
