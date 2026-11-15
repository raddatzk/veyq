@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.linux.statvfs

// Linux: the blocks free to an unprivileged user, by `statvfs` (Kotlin/Native has it in `platform.linux`, not `posix`).

internal actual fun freeBytes(path: String): Long? = memScoped {
    val s = alloc<statvfs>()
    if (statvfs(path, s.ptr) != 0) return@memScoped null
    s.f_bavail.toLong() * s.f_frsize.toLong()
}

internal actual fun totalBytes(path: String): Long? = memScoped {
    val s = alloc<statvfs>()
    if (statvfs(path, s.ptr) != 0) return@memScoped null
    s.f_blocks.toLong() * s.f_frsize.toLong()
}
