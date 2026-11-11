@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.wcstr
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix._fseeki64
import platform.posix._wfopen
import platform.posix._wremove

// Windows: the path as UTF-16 (`wcstr`), through the C runtime's wide calls. mingw's `fopen` reads its argument in the
// ANSI code page: over plain `fopen` the file `Büro € 中文.bin` was written as `BÃ¼ro â‚¬ ä¸­æ–‡.bin` —
// its UTF-8 bytes read as CP-1252 (measured 2026-11-11 on the German test VM, `FilesTest`, the bite of this file).

internal actual fun openFile(path: String, mode: String): CPointer<FILE>? = _wfopen(path.wcstr, mode.wcstr)

internal actual fun removeFile(path: String): Boolean = _wremove(path.wcstr) == 0

internal actual fun seekFile(file: CPointer<FILE>, offset: Long): Boolean = _fseeki64(file, offset, SEEK_SET) == 0
