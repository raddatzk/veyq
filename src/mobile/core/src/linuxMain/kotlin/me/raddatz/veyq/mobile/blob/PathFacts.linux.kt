@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.lstat
import platform.posix.stat

// Linux: a path's facts by `lstat`, which reports a link as the link it is and never follows it.

internal actual fun pathFacts(path: String): PathFacts? = memScoped {
    val st = alloc<stat>()
    if (lstat(path, st.ptr) != 0) return@memScoped null
    val kind = when (st.st_mode.toInt() and S_IFMT) {
        S_IFREG -> PathKind.FILE
        S_IFDIR -> PathKind.DIRECTORY
        S_IFLNK -> PathKind.LINK
        else -> PathKind.OTHER
    }
    PathFacts(kind, st.st_size, st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1_000_000)
}
