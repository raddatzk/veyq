@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.wcstr
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix._fileno
import platform.posix._fseeki64
import platform.posix._get_osfhandle
import platform.posix._wfopen
import platform.posix._wremove
import platform.posix.fflush
import platform.windows.CreateDirectoryW
import platform.windows.FlushFileBuffers
import platform.windows.GetDiskFreeSpaceExW
import platform.windows.MOVEFILE_REPLACE_EXISTING
import platform.windows.MOVEFILE_WRITE_THROUGH
import platform.windows.MoveFileExW
import platform.windows.RemoveDirectoryW
import platform.windows.ULARGE_INTEGER

// Windows: the path as UTF-16 (`wcstr`), through the C runtime's wide calls. mingw's `fopen` reads its argument in the
// ANSI code page: over plain `fopen` the file `Büro € 中文.bin` was written as `BÃ¼ro â‚¬ ä¸­æ–‡.bin` —
// its UTF-8 bytes read as CP-1252 (measured 2026-11-11 on the German test VM, `FilesTest`, the bite of this file).

internal actual fun openFile(path: String, mode: String): CPointer<FILE>? = _wfopen(path.wcstr, mode.wcstr)

internal actual fun removeFile(path: String): Boolean = _wremove(path.wcstr) == 0

internal actual fun seekFile(file: CPointer<FILE>, offset: Long): Boolean = _fseeki64(file, offset, SEEK_SET) == 0

internal actual fun createDirectory(path: String): Boolean =
    CreateDirectoryW(path, null) != 0 || pathFacts(path)?.kind == PathKind.DIRECTORY

internal actual fun removeDirectory(path: String): Boolean = RemoveDirectoryW(path) != 0

// `_wrename` refuses a target that exists; MoveFileExW replaces it in one step, written through before it returns.
internal actual fun renameFile(from: String, to: String): Boolean =
    MoveFileExW(from, to, (MOVEFILE_REPLACE_EXISTING or MOVEFILE_WRITE_THROUGH).convert()) != 0

internal actual fun syncFile(file: CPointer<FILE>): Boolean =
    fflush(file) == 0 && FlushFileBuffers(_get_osfhandle(_fileno(file)).toCPointer()) != 0

// MOVEFILE_WRITE_THROUGH already made the rename durable; NTFS has no directory fsync.
internal actual fun syncDirectory(path: String) {}

internal actual fun freeBytes(path: String): Long? = memScoped {
    val free = alloc<ULARGE_INTEGER>()
    if (GetDiskFreeSpaceExW(path, free.ptr, null, null) == 0) return@memScoped null
    free.QuadPart.toLong()
}

internal actual fun totalBytes(path: String): Long? = memScoped {
    val total = alloc<ULARGE_INTEGER>()
    if (GetDiskFreeSpaceExW(path, null, total.ptr, null) == 0) return@memScoped null
    total.QuadPart.toLong()
}
