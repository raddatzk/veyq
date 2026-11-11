@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fopen
import platform.posix.fseeko
import platform.posix.remove

// Apple and Linux: a path is the bytes it is, UTF-8 as the wire and the system's dialogs hand it.

internal actual fun openFile(path: String, mode: String): CPointer<FILE>? = fopen(path, mode)

internal actual fun removeFile(path: String): Boolean = remove(path) == 0

internal actual fun seekFile(file: CPointer<FILE>, offset: Long): Boolean = fseeko(file, offset, SEEK_SET) == 0
