@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.FILE
import platform.posix.O_RDONLY
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fseeko
import platform.posix.fsync
import platform.posix.mkdir
import platform.posix.open
import platform.posix.remove
import platform.posix.rename
import platform.posix.rmdir

// Apple and Linux: a path is the bytes it is, UTF-8 as the wire and the system's dialogs hand it.

internal actual fun openFile(path: String, mode: String): CPointer<FILE>? = fopen(path, mode)

internal actual fun removeFile(path: String): Boolean = remove(path) == 0

internal actual fun seekFile(file: CPointer<FILE>, offset: Long): Boolean = fseeko(file, offset, SEEK_SET) == 0

internal actual fun createDirectory(path: String): Boolean =
    mkdir(path, "700".toInt(8).convert()) == 0 || pathFacts(path)?.kind == PathKind.DIRECTORY

internal actual fun removeDirectory(path: String): Boolean = rmdir(path) == 0

internal actual fun renameFile(from: String, to: String): Boolean = rename(from, to) == 0

internal actual fun syncFile(file: CPointer<FILE>): Boolean = fflush(file) == 0 && fsync(fileno(file)) == 0

internal actual fun syncDirectory(path: String) {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return
    fsync(fd)
    close(fd)
}

// `freeBytes` is per platform: Linux by `statvfs`, Apple by its file manager (`statvfs` does not commonize between them).
